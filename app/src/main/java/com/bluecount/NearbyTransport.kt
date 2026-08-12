package com.bluecount

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.Payload as NearbyPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "BluecountSync"
private const val SERVICE_ID = "com.bluecount"

/** Runtime permissions Nearby needs on this API level. */
fun nearbyPermissions(): Array<String> =
  when {
    Build.VERSION.SDK_INT >= 33 ->
      arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.NEARBY_WIFI_DEVICES,
      )
    Build.VERSION.SDK_INT >= 31 ->
      // COARSE is requested with FINE because Android 12+ ignores a FINE-only request outright.
      arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
      )
    // Below 31 the radios used for discovery are location-gated, and the system location
    // toggle must also be on — a permission grant alone is not enough.
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
  }

fun Context.hasNearbyPermissions(): Boolean =
  nearbyPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Nearby Connections in P2P_CLUSTER mode: advertise and discover at the same time, so any two
 * phones running the app can pair up without either being "the server".
 *
 * Requires Google Play Services — there is no fallback, that is the price of not writing our own
 * BLE + Wi-Fi Direct stack.
 */
class NearbyTransport(context: Context, private val myName: String) : Transport {
  private val client = Nearby.getConnectionsClient(context.applicationContext)
  // Concurrent: Nearby mutates these from its own callback thread while reconnect() iterates them
  // from a coroutine after a local write.
  private val discovered = ConcurrentHashMap<String, String>()
  private val live = ConcurrentHashMap<String, NearbyPeer>()
  private var onPeer: ((Peer) -> Unit)? = null

  private inner class NearbyPeer(val endpointId: String, override val name: String) : Peer {
    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onClosed: (() -> Unit)? = null

    override fun send(bytes: ByteArray) {
      client.sendPayload(endpointId, NearbyPayload.fromBytes(bytes))
    }

    override fun close() {
      client.disconnectFromEndpoint(endpointId)
      live.remove(endpointId)
      onClosed?.invoke()
    }
  }

  private val payloads =
    object : PayloadCallback() {
      override fun onPayloadReceived(endpointId: String, payload: NearbyPayload) {
        payload.asBytes()?.let { live[endpointId]?.onMessage?.invoke(it) }
      }

      override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

  private val lifecycle =
    object : ConnectionLifecycleCallback() {
      override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        // Spec §5: no auth, anyone may connect. Forged ops are rejected on signature anyway, so a
        // hostile peer can withhold data but never invent it.
        client.acceptConnection(endpointId, payloads)
      }

      override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
        if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
          Log.d(TAG, "connect $endpointId failed: ${resolution.status.statusCode}")
          return
        }
        val peer = NearbyPeer(endpointId, discovered[endpointId] ?: endpointId)
        live[endpointId] = peer
        onPeer?.invoke(peer)
      }

      override fun onDisconnected(endpointId: String) {
        live.remove(endpointId)?.onClosed?.invoke()
      }
    }

  private val discovery =
    object : EndpointDiscoveryCallback() {
      override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
        discovered[endpointId] = info.endpointName
        connectTo(endpointId, info.endpointName)
      }

      override fun onEndpointLost(endpointId: String) {
        discovered.remove(endpointId)
      }
    }

  override fun start(onPeer: (Peer) -> Unit) {
    this.onPeer = onPeer
    // setLowPower means BLE only, and that is not about battery here. Bluetooth Classic has no
    // advertisement payload — the adapter's friendly name *is* the broadcast — so with it enabled
    // Nearby renames the phone to a base64 blob for as long as we advertise, and any car that
    // connects in that window caches the blob in its paired-device list and shows it forever.
    // The price is range and throughput; our payloads are a few KB of signed ops.
    val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).setLowPower(true).build()
    client.startAdvertising(myName, SERVICE_ID, lifecycle, options).addOnFailureListener {
      Log.w(TAG, "advertising failed", it)
    }
    // Matched to the advertiser: no point running a Bluetooth Classic inquiry for a medium nobody
    // advertises on any more.
    val found = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).setLowPower(true).build()
    client.startDiscovery(SERVICE_ID, discovery, found).addOnFailureListener { Log.w(TAG, "discovery failed", it) }
  }

  /** Retry anyone we know about but are not talking to — called on a timer and after local writes. */
  fun reconnect() {
    for ((id, name) in discovered) if (!live.containsKey(id)) connectTo(id, name)
  }

  private fun connectTo(endpointId: String, endpointName: String) {
    // Both phones see each other at once. Letting the lexicographically smaller name dial avoids
    // two crossing requests that Nearby would then have to reject. Equal names — a short-id
    // collision — must still dial, or neither side ever would and the two would never sync at all;
    // Nearby rejects the loser of the race and the failure listener below swallows it.
    if (myName > endpointName) return
    client.requestConnection(myName, endpointId, lifecycle).addOnFailureListener {
      Log.d(TAG, "requestConnection $endpointId: ${it.message}")
    }
  }

  override fun stop() {
    client.stopAdvertising()
    client.stopDiscovery()
    client.stopAllEndpoints()
    discovered.clear()
    live.clear()
  }
}

/**
 * Owns the transport while anyone holds it: the foreground activity (spec §7), or `SyncService`
 * during the window a BLE beacon woke us for (see `Wake.kt`).
 *
 * [start] and [stop] are hold-counted, because both of those can overlap — opening the app during a
 * wake window must not have the activity's `onStop` tear the service's radio down, or vice versa.
 *
 * Connections are held open for the whole period rather than torn down after each exchange, so a
 * local write reaches everyone in the room immediately. The minute timer is only a recovery path
 * for peers we are not connected to.
 */
class SyncEngine(private val context: Context, private val repo: Repo) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val sessions = CopyOnWriteArrayList<Session>()
  // Peers we have an event in common with, by name. Separate from `sessions` because that counts
  // connections, and a connection is not what the status is about — see the onHello gate below.
  private val sharing = CopyOnWriteArrayList<String>()
  private var transport: NearbyTransport? = null
  private var ticker: Job? = null
  // Volatile because WakeReceiver reads it below from a broadcast thread.
  @Volatile private var holds = 0

  /** Someone already owns the radio: the foreground activity, or a wake window still open. */
  val busy: Boolean
    get() = holds > 0

  /** Short text for the UI: what the radio is doing right now. */
  val status = MutableStateFlow(context.getString(R.string.sync_off))

  /**
   * ponytail: started unconditionally, so a phone that has joined no events still advertises,
   * discovers and connects — `acceptable()` means it can neither store nor relay a single op, so
   * that is battery spent on connections that can never carry anything. Skip `t.start` when
   * `repo.clocks()` is empty if it ever shows up in a battery trace; `kick()` then has to be able to
   * cold-start the transport, because `joinEvent` → `onAppend` → `kick()` is what would first need
   * the radio up.
   */
  @Synchronized
  fun start() {
    holds++
    startTransport()
  }

  /**
   * Importing a key changes the identity the endpoint name below is derived from, so the radio has
   * to come back up under the new one — a peer that saw the old name would otherwise be told about
   * ops signed by someone it has no name for.
   */
  @Synchronized
  fun rename() {
    if (transport == null) return
    teardown()
    startTransport()
  }

  private fun startTransport() {
    if (transport != null) return
    if (!context.hasNearbyPermissions()) {
      status.value = context.getString(R.string.sync_no_permission)
      return
    }
    val t = NearbyTransport(context, repo.me.shortId())
    transport = t
    status.value = context.getString(R.string.sync_looking)
    t.start { peer ->
      lateinit var session: Session
      session =
        Session(
          peer,
          repo,
          scope,
          // Being connected is not being in sync: a phone that has joined nothing, or only other
          // trips, exchanges nothing at all. `clocks()` is keyed by joined event, so its key set is
          // exactly what we can share.
          onHello = { theirs ->
            if (theirs.any { it in repo.clocks().keys }) {
              sharing += peer.name
              status.value = context.getString(R.string.sync_with, peer.name)
            }
          },
          onMerged = { merged ->
            if (merged > 0)
              status.value = context.resources.getQuantityString(R.plurals.n_received, merged, merged)
            // Relaying comes free: pushing back to whoever sent these is a no-op, because a session
            // never resends what it already handled.
            pushAll()
          },
          onClosed = {
            sessions.remove(session)
            sharing.remove(peer.name)
            if (sharing.isEmpty()) status.value = context.getString(R.string.sync_looking)
          },
        )
      sessions += session
      session.start()
    }
    ticker =
      scope.launch {
        while (isActive) {
          delay(60_000)
          t.reconnect()
        }
      }
  }

  @Synchronized
  fun stop() {
    // coerceAtLeast rather than trusting the count: an unpaired stop must never make the next
    // holder's start() a no-op, which would leave the radio silently off.
    holds = (holds - 1).coerceAtLeast(0)
    if (holds > 0) return
    teardown()
  }

  private fun teardown() {
    ticker?.cancel()
    ticker = null
    transport?.stop()
    transport = null
    sessions.clear()
    sharing.clear()
    scope.coroutineContext.cancelChildren()
    status.value = context.getString(R.string.sync_off)
  }

  /** After a local write: tell everyone we are already talking to, and dial anyone we are not. */
  fun kick() {
    pushAll()
    transport?.reconnect()
  }

  private fun pushAll() {
    scope.launch { sessions.forEach { it.push() } }
  }
}
