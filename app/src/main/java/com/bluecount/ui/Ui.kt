package com.bluecount.ui

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bluecount.App
import com.bluecount.Expense
import com.bluecount.Kind
import com.bluecount.R
import com.bluecount.Repo
import com.bluecount.SyncEngine
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * The leading mark on an expense row. All three are arrows so the list reads as one family: value
 * going out, coming back, or crossing over. Drawables rather than "↩"/"⇄", which rendered at label
 * size and thin next to anything Material drew — see the icon rule in CLAUDE.md.
 */
fun Kind.icon(): Int =
  when (this) {
    Kind.EXPENSE -> R.drawable.ic_arrow_forward
    Kind.REIMBURSEMENT -> R.drawable.ic_undo
    Kind.CONVERSION -> R.drawable.ic_swap_horiz
  }

/**
 * When an expense happened, in the phone's own zone. [DateUtils] for both because it is the only
 * formatter that also honours the user's 12/24-hour setting, not just the locale.
 *
 * No [DateUtils.FORMAT_SHOW_YEAR]: with neither year flag set, `DateUtils` prints the year only
 * when the date is not in the current one, which is the rare case for a debt.
 *
 * An op written before [com.bluecount.Put.at] existed carries a zone-free epoch day, so it has no
 * time to show and is turned into millis at *local* midnight — the epoch-day millis themselves are
 * UTC midnight and would render a whole day off for anyone far enough west.
 */
@Composable
fun Expense.whenText(): String {
  val ctx = LocalContext.current
  val millis =
    if (at != 0L) at
    else LocalDate.ofEpochDay(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
  return DateUtils.formatDateTime(
    ctx,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
      (if (at != 0L) DateUtils.FORMAT_SHOW_TIME else 0),
  )
}

/**
 * Composable because it resolves against the reader's locale. Deliberately *not* callable from the
 * write path: a kind label must never end up inside a signed op, or a Russian phone would ship a
 * Russian title to an English one. `ExpenseRow` falls back to this at render time instead.
 */
@Composable
fun Kind.label(): String =
  stringResource(
    when (this) {
      Kind.EXPENSE -> R.string.kind_expense
      Kind.REIMBURSEMENT -> R.string.kind_payback
      Kind.CONVERSION -> R.string.kind_exchange
    }
  )

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
      label = { Text(stringResource(R.string.currency_short)) },
      singleLine = true,
      trailingIcon = {
        IconButton(onClick = { open = true }) {
          Icon(painterResource(R.drawable.ic_arrow_drop_down), stringResource(R.string.cd_pick_currency))
        }
      },
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

/**
 * Straight to a share sheet: a file the user filed away somewhere is the whole feature.
 *
 * ponytail: the copy stays in `cacheDir/export` until the OS reclaims it — a chooser gives no
 * completion callback to delete it on. Matters more for a key than for a CSV; the fix is a
 * `CreateDocument` picker writing the bytes to the user's chosen place directly.
 */
fun shareFile(context: Context, name: String, mime: String, content: String, chooserTitle: String) {
  val file = File(File(context.cacheDir, "export").apply { mkdirs() }, name)
  file.writeText(content)
  val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
  context.startActivity(
    Intent.createChooser(
      Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      },
      chooserTitle,
    )
  )
}

val repo: Repo
  get() = App.instance.repo

val sync: SyncEngine
  get() = App.instance.sync

/**
 * A real 24dp vector, not a "←" text glyph. A glyph sits on the *text* baseline and inherits the
 * font's own weight, size and side bearings, so it lands off the icon grid the app bar aligns to
 * and never matches the weight of a system icon — which is what made these look homemade.
 *
 * Drawables rather than material-icons: that artifact stopped shipping at 1.7.8 and is not in the
 * Compose BOM's live set, so a handful of `res/drawable` vectors is the route that stays alive.
 */
@Composable
fun BackButton(onBack: () -> Unit) {
  IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.cd_back)) }
}

@Composable
fun Empty(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
  }
}
