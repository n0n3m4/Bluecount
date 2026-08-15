package com.bluecount

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
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

  /** An exported event someone sent through a chat app, waiting to be merged. */
  private var pendingImport by mutableStateOf<Uri?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    pendingImport = importUri(intent)

    enableEdgeToEdge()
    setContent {
      BluecountTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          MainNavigation(pendingImport, ::importDone)
        }
      }
    }

    // POST_NOTIFICATIONS is asked for alongside but kept out of nearbyPermissions(), because
    // denying it must not stop sync — it only costs the "updates nearby" fallback notification.
    val ask =
      if (Build.VERSION.SDK_INT >= 33) nearbyPermissions() + Manifest.permission.POST_NOTIFICATIONS
      else nearbyPermissions()
    if (!hasNearbyPermissions()) askNearby.launch(ask)

    askBatteryExemption()
  }

  /** singleTask, so a file tapped while the app is open lands here instead of in a second copy. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingImport = importUri(intent)
  }

  /**
   * VIEW puts the file in the data URI, SEND puts it in EXTRA_STREAM; it is the same file either
   * way. SEND is worth accepting because "Open with" is not offered at all by some messengers, and
   * their Share button is then the only way the attachment can reach us.
   */
  private fun importUri(from: Intent?): Uri? =
    when (from?.action) {
      Intent.ACTION_VIEW -> from.data
      Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(from, Intent.EXTRA_STREAM, Uri::class.java)
      else -> null
    }

  private fun importDone() {
    pendingImport = null
    // The activity keeps its launch intent across a rotation, and replaying the import on every
    // recreate would re-toast and re-ask. Forget it now that it has been handled.
    intent = Intent()
  }

  override fun onStart() {
    super.onStart()
    startSync()
    // Beacon so phones with the app closed wake and come to collect; keep listening for theirs.
    Wake.advertise(this)
    Wake.arm(this)
    // A wake window may still be open from before the app was opened, and its notification is now
    // redundant. Only safe after startSync() took our hold: onDestroy releases the service's, and
    // the count reaching zero in between would tear the radio down and bring it straight back up.
    stopService(Intent(this, SyncService::class.java))
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
