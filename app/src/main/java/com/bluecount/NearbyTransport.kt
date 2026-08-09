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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
      arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
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
  private val discovered = mutableMapOf<String, String>()
  private val live = mutableMapOf<String, NearbyPeer>()
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
    val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
    client.startAdvertising(myName, SERVICE_ID, lifecycle, options).addOnFailureListener {
      Log.w(TAG, "advertising failed", it)
    }
    client
      .startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
      .addOnFailureListener { Log.w(TAG, "discovery failed", it) }
  }

  /** Retry anyone we know about but are not talking to — called on a timer and after local writes. */
  fun reconnect() {
    for ((id, name) in discovered) if (id !in live) connectTo(id, name)
  }

  private fun connectTo(endpointId: String, endpointName: String) {
    // Both phones see each other at once. Letting the lexicographically smaller name dial avoids
    // two crossing requests that Nearby would then have to reject.
    if (myName >= endpointName) return
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
 * Owns the transport for as long as the app is in the foreground and re-announces every minute
 * (spec §7). Background sync would need a foreground service and would still be killed on most
 * phones, so it is deliberately not attempted.
 */
class SyncEngine(private val context: Context, private val repo: Repo) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var transport: NearbyTransport? = null
  private var ticker: Job? = null

  /** Short text for the UI: what the radio is doing right now. */
  val status = MutableStateFlow("Not syncing")

  fun start() {
    if (transport != null) return
    if (!context.hasNearbyPermissions()) {
      status.value = "Nearby permissions not granted"
      return
    }
    val t = NearbyTransport(context, repo.me.shortId())
    transport = t
    status.value = "Looking for people nearby…"
    t.start { peer ->
      status.value = "Syncing with ${peer.name}"
      Session(peer, repo, scope) { merged -> if (merged > 0) status.value = "Received $merged new entries" }.start()
    }
    ticker =
      scope.launch {
        while (isActive) {
          delay(60_000)
          t.reconnect()
        }
      }
  }

  fun stop() {
    ticker?.cancel()
    ticker = null
    transport?.stop()
    transport = null
    status.value = "Not syncing"
  }

  /** After a local write: go tell someone (spec §7). */
  fun kick() {
    transport?.reconnect()
  }
}
