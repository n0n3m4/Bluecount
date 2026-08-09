package com.bluecount

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.bluecount.theme.BluecountTheme
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class MainActivity : ComponentActivity() {
  private val askNearby =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSync() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    setContent {
      BluecountTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }

    if (!hasNearbyPermissions()) askNearby.launch(nearbyPermissions())
  }

  override fun onStart() {
    super.onStart()
    startSync()
  }

  override fun onStop() {
    super.onStop()
    App.instance.sync.stop()
  }

  /**
   * Nearby Connections is part of Google Play Services; without it the app still tracks expenses
   * perfectly well, it just can never exchange them. Say so rather than failing silently.
   */
  private fun startSync() {
    if (!hasNearbyPermissions()) return
    if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
      Toast.makeText(this, "Google Play Services missing — sharing with nearby phones is unavailable", Toast.LENGTH_LONG).show()
      return
    }
    App.instance.sync.start()
  }
}
