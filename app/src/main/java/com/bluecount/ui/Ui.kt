package com.bluecount.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluecount.App
import com.bluecount.Repo
import com.bluecount.SyncEngine
import kotlin.math.abs

/** Cents to text without ever touching a float. */
fun Long.money(): String {
  val sign = if (this < 0) "-" else ""
  val a = abs(this)
  return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}

/** Accepts "12", "12.3", "12,34". Null for anything else — no silent rounding of user input. */
fun String.toCentsOrNull(): Long? {
  val m = Regex("""^(\d{1,12})(?:[.,](\d{0,2}))?$""").matchEntire(trim()) ?: return null
  return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
}

val repo: Repo
  get() = App.instance.repo

val sync: SyncEngine
  get() = App.instance.sync

// The material-icons artifacts are one more dependency for a handful of glyphs; text does fine.
@Composable
fun BackButton(onBack: () -> Unit) {
  IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
  }
}
