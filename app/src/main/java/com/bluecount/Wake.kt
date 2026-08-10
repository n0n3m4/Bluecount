package com.bluecount

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "BluecountSync"

/**
 * Our own beacon UUID: the ASCII bytes of "BluecountWakeSvc". Fixed forever — it is the only thing
 * a sleeping phone matches on, so changing it makes every installed copy deaf to every new one.
 */
private val WAKE_UUID: UUID = UUID.fromString("426c7565-636f-756e-7457-616b65537663")

private const val WAKE_ACTION = "com.bluecount.WAKE"
private const val WAKE_REQUEST = 1
private const val CHANNEL = "sync"
private const val NOTE_ID = 1

/** How long a woken phone stays on the radio. Nearby needs a few seconds to discover and connect. */
private const val WAKE_WINDOW_MS = 45_000L

/** A phone whose chip cannot do first-match filtering re-broadcasts constantly; ignore the flood. */
private const val WAKE_DEBOUNCE_MS = 120_000L

/**
 * Nearby Connections cannot wake a stopped app — it has no manifest entry point, and the BLE UUID it
 * advertises is internal and rotates, so it cannot be scanned for either. This is the only thing
 * Android offers instead: a scan registered with a [PendingIntent] is delivered to a manifest
 * receiver even when the process is dead.
 *
 * So: a phone with the app open beacons our own UUID; sleeping phones wake on it, sync for
 * [WAKE_WINDOW_MS], and go back to sleep.
 *
 * ponytail: a woken phone does not beacon in turn, so a wake does not relay past one hop. Doing so
 * needs a "only re-beacon if we actually merged something" rule, or two phones ping-pong each other
 * awake forever. Add it if one-hop proves too little.
 */
object Wake {
  private var advertiser: AdvertiseCallback? = null

  private fun pendingIntent(context: Context): PendingIntent {
    // Explicit component, and MUTABLE because the Bluetooth stack has to attach the scan results.
    val intent = Intent(context, WakeReceiver::class.java).setAction(WAKE_ACTION)
    return PendingIntent.getBroadcast(
      context.applicationContext,
      WAKE_REQUEST,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
  }

  private fun scanner(context: Context) =
    context.getSystemService<BluetoothManager>()?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

  private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun scanPermission(context: Context) =
    Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_SCAN)

  /**
   * Register the background scan. Survives the app being closed, but not a reboot, not Bluetooth
   * being toggled off, and not a force-stop — hence [WakeReceiver] re-arming on both broadcasts.
   * Safe to call repeatedly: the fixed request code means the same PendingIntent every time.
   */
  @SuppressLint("MissingPermission") // scanPermission() above; lint cannot see through the helper.
  fun arm(context: Context) {
    if (!App.instance.repo.wakeEnabled) return
    if (!scanPermission(context)) return
    val scanner = scanner(context) ?: return
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(WAKE_UUID)).build())
    val pending = pendingIntent(context)
    // FIRST_MATCH is one broadcast per appearance instead of a stream, but it needs the chip to do
    // the filtering. Not every chip does, and startScan reports that as a non-zero return.
    val first =
      ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .build()
    try {
      if (scanner.startScan(filters, first, pending) == 0) return
      val all = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
      Log.d(TAG, "first-match scan unsupported, falling back: ${scanner.startScan(filters, all, pending)}")
    } catch (e: Exception) {
      Log.w(TAG, "arm failed", e)
    }
  }

  @SuppressLint("MissingPermission") // scanPermission() below.
  fun disarm(context: Context) {
    if (!scanPermission(context)) return
    try {
      scanner(context)?.stopScan(pendingIntent(context))
    } catch (e: Exception) {
      Log.w(TAG, "disarm failed", e)
    }
  }

  /** Beacon while the app is open, so anyone asleep nearby wakes up and comes to collect. */
  @SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE checked below.
  fun advertise(context: Context) {
    if (advertiser != null) return
    if (Build.VERSION.SDK_INT >= 31 && !granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)) return
    val le = context.getSystemService<BluetoothManager>()?.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return
    val settings =
      AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(false)
        .build()
    // A 128-bit UUID is 18 of the 31 payload bytes; including the device name overflows the packet
    // and the whole advertisement fails.
    val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(WAKE_UUID)).setIncludeDeviceName(false).build()
    val cb =
      object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
          Log.w(TAG, "advertise failed: $errorCode")
          advertiser = null
        }
      }
    try {
      le.startAdvertising(settings, data, cb)
      advertiser = cb
    } catch (e: Exception) {
      Log.w(TAG, "advertise failed", e)
    }
  }

  @SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE checked below.
  fun stopAdvertising(context: Context) {
    val cb = advertiser ?: return
    advertiser = null
    if (Build.VERSION.SDK_INT >= 31 && !granted(context, Manifest.permission.BLUETOOTH_ADVERTISE)) return
    try {
      context.getSystemService<BluetoothManager>()?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
    } catch (e: Exception) {
      Log.w(TAG, "stopAdvertising failed", e)
    }
  }
}

/**
 * Exported because system broadcasts come from the system uid and are not delivered to a private
 * receiver. A forged wake intent can at worst cause a sync, so guarding on the action is enough.
 */
class WakeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED -> Wake.arm(context)
      BluetoothAdapter.ACTION_STATE_CHANGED ->
        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) Wake.arm(context)
      WAKE_ACTION -> wake(context)
    }
  }

  private fun wake(context: Context) {
    val repo = App.instance.repo
    val now = System.currentTimeMillis()
    if (now - repo.lastWake < WAKE_DEBOUNCE_MS) return
    repo.lastWake = now
    try {
      ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
    } catch (e: Exception) {
      // Android 12+ blocks background service starts unless the sender allowlisted us. The BLE
      // stack normally does, but OEMs vary — so fall back to asking rather than failing silently.
      Log.w(TAG, "background start blocked, notifying instead", e)
      notify(
        context,
        NotificationCompat.Builder(context, CHANNEL)
          .setSmallIcon(R.drawable.ic_sync)
          .setContentTitle(context.getString(R.string.note_updates_title))
          .setContentText(context.getString(R.string.note_updates_text))
          .setContentIntent(
            PendingIntent.getActivity(
              context,
              0,
              Intent(context, MainActivity::class.java),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
          )
          .setAutoCancel(true)
          .build(),
      )
    }
  }
}

private fun channel(context: Context) {
  context
    .getSystemService<NotificationManager>()
    ?.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW))
}

private fun notify(context: Context, note: Notification) {
  channel(context)
  if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
  context.getSystemService<NotificationManager>()?.notify(NOTE_ID, note)
}

/**
 * Holds the radio open for [WAKE_WINDOW_MS] after a beacon wakes us. `SyncEngine` is hold-counted,
 * so the user opening the app mid-window does not fight with this and neither tears down the other.
 */
class SyncService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var running = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    channel(this)
    val note =
      NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_sync)
        .setContentTitle(getString(R.string.note_syncing))
        .setOngoing(true)
        .build()
    // Must be the first thing we do: the system kills us if it does not happen within 5s.
    if (Build.VERSION.SDK_INT >= 30) {
      startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
      startForeground(NOTE_ID, note)
    }

    if (!running) {
      running = true
      App.instance.sync.start()
      scope.launch {
        delay(WAKE_WINDOW_MS)
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  /** The only place the hold is released, so it happens exactly once however the service dies. */
  override fun onDestroy() {
    if (running) {
      running = false
      App.instance.sync.stop()
    }
    scope.cancel()
  }
}
