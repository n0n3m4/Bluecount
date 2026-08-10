package com.bluecount

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.bluecount.theme.BluecountTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class MainActivity : ComponentActivity() {
  private val askNearby =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSync() }

  /** `SyncEngine` is hold-counted; this makes sure the activity contributes exactly one hold. */
  private var held = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      BluecountTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }

    // POST_NOTIFICATIONS is asked for alongside but kept out of nearbyPermissions(), because
    // denying it must not stop sync — it only costs the "updates nearby" fallback notification.
    val ask =
      if (Build.VERSION.SDK_INT >= 33) nearbyPermissions() + Manifest.permission.POST_NOTIFICATIONS
      else nearbyPermissions()
    if (!hasNearbyPermissions()) askNearby.launch(ask)

    askBatteryExemption()
  }

  override fun onStart() {
    super.onStart()
    startSync()
    // Beacon so phones with the app closed wake and come to collect; keep listening for theirs.
    Wake.advertise(this)
    Wake.arm(this)
  }

  override fun onStop() {
    super.onStop()
    Wake.stopAdvertising(this)
    // The scan stays armed on purpose — that is the whole point of it.
    if (held) {
      held = false
      App.instance.sync.stop()
    }
  }

  /**
   * Nearby Connections is part of Google Play Services; without it the app still tracks expenses
   * perfectly well, it just can never exchange them. Say so rather than failing silently.
   */
  private fun startSync() {
    if (held) return
    if (!hasNearbyPermissions()) return
    if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
      Toast.makeText(this, getString(R.string.no_play_services), Toast.LENGTH_LONG).show()
      return
    }
    held = true
    App.instance.sync.start()
  }

  /**
   * Doze suspends app BLE scans, which is exactly what waking a closed app depends on. Ask once —
   * Play forbids this prompt for general-purpose apps, but Bluecount is sideloaded.
   */
  private fun askBatteryExemption() {
    val repo = App.instance.repo
    if (repo.batteryAsked || !repo.wakeEnabled) return
    val pm = getSystemService<PowerManager>() ?: return
    if (pm.isIgnoringBatteryOptimizations(packageName)) return
    repo.batteryAsked = true
    try {
      startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    } catch (_: Exception) {
      // Some ROMs ship no handler for it. Nothing to do; wake just stays less reliable.
    }
  }
}
