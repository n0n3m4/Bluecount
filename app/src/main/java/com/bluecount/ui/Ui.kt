package com.bluecount.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluecount.App
import com.bluecount.Kind
import com.bluecount.Repo
import com.bluecount.SyncEngine
import kotlin.math.abs

/** Cents to text without ever touching a float. */
fun Long.money(): String {
  val sign = if (this < 0) "-" else ""
  val a = abs(this)
  return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
}

/** Amounts are never shown bare now that an event can hold several currencies at once. */
fun Long.money(cur: String): String = "${money()} $cur"

/** Accepts "12", "12.3", "12,34". Null for anything else — no silent rounding of user input. */
fun String.toCentsOrNull(): Long? {
  val m = Regex("""^(\d{1,12})(?:[.,](\d{0,2}))?$""").matchEntire(trim()) ?: return null
  return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
}

/** An exchange rate scaled by [MICRO], so the rate fields stay integer like everything else. */
const val MICRO = 1_000_000L

fun String.toMicrosOrNull(): Long? {
  val m = Regex("""^(\d{1,9})(?:[.,](\d{0,6}))?$""").matchEntire(trim()) ?: return null
  return m.groupValues[1].toLong() * MICRO + m.groupValues[2].padEnd(6, '0').toLong()
}

/** Micros back to text, trailing zeros dropped: 475_000_000 → "475", 1_234_500 → "1.2345". */
fun Long.micros(): String {
  val frac = (this % MICRO).toString().padStart(6, '0').trimEnd('0')
  return "${this / MICRO}" + if (frac.isEmpty()) "" else ".$frac"
}

/** The rate a conversion op implies. Display only — the ledger only ever sees the two amounts. */
fun rateMicros(from: Long, to: Long): Long = if (from <= 0) 0L else to * MICRO / from

/** Leading glyph per expense kind. Text, so no material-icons artifact just for three shapes. */
fun Kind.glyph(): String =
  when (this) {
    Kind.EXPENSE -> "•"
    Kind.REIMBURSEMENT -> "↩"
    Kind.CONVERSION -> "⇄"
  }

fun Kind.label(): String =
  when (this) {
    Kind.EXPENSE -> "Expense"
    Kind.REIMBURSEMENT -> "Payback"
    Kind.CONVERSION -> "Exchange"
  }

/**
 * A currency code field: free text so any 3-letter code works, with the user's shortlist one tap
 * away. Uppercased on the way in because "usd" and "USD" must not become two separate balances.
 */
@Composable
fun CurrencyField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
  var open by remember { mutableStateOf(false) }
  Box(modifier) {
    OutlinedTextField(
      value,
      { onChange(it.filter { c -> c.isLetter() }.take(3).uppercase()) },
      label = { Text("Cur") },
      singleLine = true,
      trailingIcon = { TextButton(onClick = { open = true }) { Text("▾") } },
      modifier = Modifier.fillMaxWidth(),
    )
    DropdownMenu(open, onDismissRequest = { open = false }) {
      repo.currencies.forEach { c ->
        DropdownMenuItem(
          text = { Text(c) },
          onClick = {
            onChange(c)
            open = false
          },
        )
      }
    }
  }
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
