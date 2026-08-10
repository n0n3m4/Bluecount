package com.bluecount.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bluecount.R
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the QR encodes (spec §3): the event id plus a human-readable name and currency. */
data class Invite(val id: String, val name: String, val currency: String)

fun Invite.toUri(): String =
  "bluecount:$id?n=${URLEncoder.encode(name, "UTF-8")}&c=${URLEncoder.encode(currency, "UTF-8")}"

/**
 * The name here is only a label to show before the event syncs; the signed [com.bluecount.Genesis]
 * op is what the app trusts once it arrives, so a doctored QR cannot permanently rename anything.
 */
fun parseInvite(text: String): Invite? {
  if (!text.startsWith("bluecount:")) return null
  val body = text.removePrefix("bluecount:")
  val id = body.substringBefore('?')
  if (id.isEmpty()) return null
  val params =
    body.substringAfter('?', "").split('&').mapNotNull {
      val k = it.substringBefore('=')
      if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }.toMap()
  return Invite(id, params["n"].orEmpty(), params["c"].orEmpty().ifBlank { "EUR" })
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
  val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
  val pixels = IntArray(size * size)
  for (y in 0 until size) for (x in 0 until size) {
    pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
  }
  return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(eventId: String, onBack: () -> Unit) {
  val state by repo.state(eventId).collectAsStateWithLifecycle(null)
  val s = state
  val bitmap =
    remember(s?.name, s?.currency) {
      s?.let { qrBitmap(Invite(eventId, it.name, it.currency).toUri()) }
    }

  Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.invite)) }, navigationIcon = { BackButton(onBack) }) }) { pad ->
    Column(
      Modifier.padding(pad).padding(24.dp).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(s?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
      bitmap?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.cd_event_qr), modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
      Text(
        stringResource(R.string.invite_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

@AndroidXOptIn(ExperimentalGetImage::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit, onJoined: (String) -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  var granted by remember {
    mutableStateOf(
      androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

  var found by remember { mutableStateOf<Invite?>(null) }
  var nick by remember { mutableStateOf(repo.nickname) }

  Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_invite)) }, navigationIcon = { BackButton(onBack) }) }) { pad ->
    if (!granted) {
      Empty(stringResource(R.string.camera_needed), Modifier.padding(pad))
      return@Scaffold
    }

    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
      onDispose {
        executor.shutdown()
        scanner.close()
      }
    }

    LaunchedEffect(Unit) {
      val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
      val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
      val analysis =
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
          it.setAnalyzer(executor) { proxy ->
            val image = proxy.image
            if (image == null) {
              proxy.close()
            } else {
              scanner
                .process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                  codes.firstNotNullOfOrNull { b: Barcode -> b.rawValue?.let(::parseInvite) }
                    ?.let { invite -> if (found == null) found = invite }
                }
                .addOnCompleteListener { proxy.close() }
            }
          }
        }
      provider.unbindAll()
      provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    androidx.compose.ui.viewinterop.AndroidView(
      factory = { previewView },
      modifier = Modifier.padding(pad).fillMaxSize(),
    )
  }

  found?.let { invite ->
    AlertDialog(
      onDismissRequest = { found = null },
      title = { Text(stringResource(R.string.join_title, invite.name)) },
      text = {
        OutlinedTextField(nick, { nick = it }, label = { Text(stringResource(R.string.your_name_in_event)) }, singleLine = true)
      },
      confirmButton = {
        TextButton(
          enabled = nick.isNotBlank(),
          onClick = {
            scope.launch {
              repo.joinEvent(invite.id, invite.name, invite.currency, nick.trim())
              onJoined(invite.id)
            }
          },
        ) {
          Text(stringResource(R.string.join))
        }
      },
      dismissButton = { TextButton(onClick = { found = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}
