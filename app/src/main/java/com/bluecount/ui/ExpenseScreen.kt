package com.bluecount.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluecount.EventState
import com.bluecount.Kind
import com.bluecount.Put
import com.bluecount.R
import com.bluecount.SplitMode
import com.bluecount.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(eventId: String, expenseId: String?, onBack: () -> Unit) {
  val state by repo.state(eventId).collectAsStateWithLifecycle(null)
  val scope = rememberCoroutineScope()

  var loaded by remember { mutableStateOf(false) }
  var kind by remember { mutableStateOf(Kind.EXPENSE) }
  var title by remember { mutableStateOf("") }
  var amount by remember { mutableStateOf("") }
  var currency by remember { mutableStateOf("") }
  var toAmount by remember { mutableStateOf("") }
  var toCurrency by remember { mutableStateOf("") }
  var rate by remember { mutableStateOf("") }
  // One instant, not a day plus a time: the pickers each edit their own part of it in the local zone.
  var at by remember { mutableStateOf(System.currentTimeMillis()) }
  var payer by remember { mutableStateOf("") }
  var mode by remember { mutableStateOf(SplitMode.EQUAL) }
  val picked = remember { mutableStateMapOf<UserId, String>() }
  var pickingDate by remember { mutableStateOf(false) }
  var pickingTime by remember { mutableStateOf(false) }
  var payerMenu by remember { mutableStateOf(false) }
  var toMenu by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf(false) }

  val s = state
  // Seed the form once the log has been read; everything after that is the user's edit.
  LaunchedEffect(s) {
    if (s == null || loaded) return@LaunchedEffect
    val existing = s.expenses.firstOrNull { it.id == expenseId }
    if (existing != null) {
      kind = existing.kind
      title = existing.title
      amount = existing.cents.money()
      currency = existing.currency
      // An op written before expenses carried a time has only a day; editing one stamps it midnight.
      at =
        if (existing.at != 0L) existing.at
        else LocalDate.ofEpochDay(existing.date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
      payer = existing.payer
      mode = existing.mode
      existing.shares.forEach { (id, w) -> picked[id] = if (existing.mode == SplitMode.EXACT) w.money() else w.toString() }
      if (existing.kind == Kind.CONVERSION) {
        toAmount = existing.toCents.money()
        toCurrency = existing.toCurrency
        rate = rateMicros(existing.cents, existing.toCents).micros()
      }
    } else {
      payer = repo.me
      currency = s.currency
      s.members.keys.forEach { picked[it] = "1" }
    }
    loaded = true
  }

  // Stored UTC, edited and shown local: this is the only place the two representations meet.
  val local = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
  val ctx = LocalContext.current

  val cents = amount.toCentsOrNull()
  val toCents = toAmount.toCentsOrNull()
  val rateM = rate.toMicrosOrNull()
  val oneToOne = kind != Kind.EXPENSE

  /**
   * Rate, "gives" and "receives" are three views of two numbers, so whichever the user last touched
   * drives the other. Only the two amounts are ever saved: the rate is derived on the way out again,
   * which keeps division away from the ledger and stops a stored rate contradicting the amounts.
   */
  fun reprice(from: Long?, r: Long?) {
    if (from != null && from > 0 && r != null && r > 0) toAmount = (from * r / MICRO).money()
  }

  val weights: Map<UserId, Long> =
    picked.mapValues { (_, text) ->
      when {
        oneToOne -> 1L
        mode == SplitMode.EXACT -> text.toCentsOrNull() ?: -1L
        else -> text.trim().toLongOrNull() ?: -1L
      }
    }
  val badWeight = weights.values.any { it < 0 }
  val exactMismatch =
    !oneToOne && mode == SplitMode.EXACT && !badWeight && cents != null && weights.values.sum() != cents
  // A payback or an exchange to yourself is a no-op that would still show up in the list; block it.
  val other = picked.keys.singleOrNull()
  val badPair = oneToOne && (other == null || other == payer)
  val badExchange =
    kind == Kind.CONVERSION && (toCents == null || toCents <= 0 || toCurrency.isBlank() || toCurrency == currency)
  val canSave =
    s != null && cents != null && cents > 0 && payer.isNotEmpty() && currency.isNotBlank() &&
      picked.isNotEmpty() && !badWeight && !exactMismatch && !badPair && !badExchange

  Scaffold(
    topBar = {
      TopAppBar(
        // Six whole strings rather than "New "/"Edit " glued onto a lowercased label: in an
        // inflected language the noun changes shape and the fragments cannot be reassembled.
        title = {
          Text(
            stringResource(
              when (kind) {
                Kind.EXPENSE -> if (expenseId == null) R.string.title_new_expense else R.string.title_edit_expense
                Kind.REIMBURSEMENT -> if (expenseId == null) R.string.title_new_payback else R.string.title_edit_payback
                Kind.CONVERSION -> if (expenseId == null) R.string.title_new_exchange else R.string.title_edit_exchange
              }
            )
          )
        },
        navigationIcon = { BackButton(onBack) },
        actions = {
          TextButton(
            enabled = canSave,
            onClick = {
              scope.launch {
                repo.append(eventId) { newId ->
                  Put(
                    id = expenseId ?: newId,
                    title = title.trim(),
                    cents = cents!!,
                    at = at,
                    // The day is written too, for a build that predates `at` and as the list's fallback.
                    date = local.toLocalDate().toEpochDay(),
                    payer = payer,
                    mode = if (oneToOne) SplitMode.EQUAL else mode,
                    shares = if (oneToOne || mode == SplitMode.EQUAL) weights.mapValues { 1L } else weights,
                    kind = kind,
                    currency = currency,
                    toCents = if (kind == Kind.CONVERSION) toCents!! else 0,
                    toCurrency = if (kind == Kind.CONVERSION) toCurrency else "",
                  )
                }
                onBack()
              }
            },
          ) {
            Text(stringResource(R.string.save))
          }
        },
      )
    }
  ) { pad ->
    if (s == null) return@Scaffold

    Column(
      Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Kind.entries.forEachIndexed { i, k ->
          SegmentedButton(
            selected = kind == k,
            onClick = {
              kind = k
              // One-to-one kinds carry exactly one recipient; going back to an expense means
              // everyone again, which is what the editor opens with for a new expense anyway.
              if (k == Kind.EXPENSE) {
                mode = SplitMode.EQUAL
                picked.clear()
                s.members.keys.forEach { picked[it] = "1" }
              } else {
                val to = picked.keys.firstOrNull { it != payer } ?: s.members.keys.firstOrNull { it != payer }
                picked.clear()
                if (to != null) picked[to] = "1"
                if (k == Kind.CONVERSION && toCurrency.isBlank()) {
                  toCurrency = repo.currencies.firstOrNull { it != currency } ?: ""
                }
              }
            },
            shape = SegmentedButtonDefaults.itemShape(i, Kind.entries.size),
          ) {
            // No icon of our own: the selected item already gets Material's 24dp check in the icon
            // slot, and anything we put beside the label only competed with it.
            Text(k.label())
          }
        }
      }

      OutlinedTextField(
        title,
        { title = it },
        label = { Text(stringResource(R.string.what_for)) },
        placeholder = { if (kind != Kind.EXPENSE) Text(kind.label()) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      AmountRow(
        label = stringResource(if (kind == Kind.CONVERSION) R.string.gives else R.string.amount),
        amount = amount,
        onAmount = {
          amount = it
          reprice(it.toCentsOrNull(), rateM)
        },
        bad = amount.isNotEmpty() && cents == null,
        currency = currency,
        onCurrency = { currency = it },
      )

      if (kind == Kind.CONVERSION) {
        AmountRow(
          label = stringResource(R.string.receives),
          amount = toAmount,
          onAmount = {
            toAmount = it
            // Typing the far side defines the rate instead of being defined by it.
            val t = it.toCentsOrNull()
            if (cents != null && t != null) rate = rateMicros(cents, t).micros()
          },
          bad = toAmount.isNotEmpty() && (toCents == null || toCents <= 0),
          currency = toCurrency,
          onCurrency = { toCurrency = it },
        )
        OutlinedTextField(
          rate,
          {
            rate = it
            reprice(cents, it.toMicrosOrNull())
          },
          label = { Text(stringResource(R.string.rate_label, currency.ifBlank { "?" }, toCurrency.ifBlank { "?" })) },
          isError = rate.isNotEmpty() && rateM == null,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        if (toCurrency.isNotBlank() && toCurrency == currency) {
          Text(
            stringResource(R.string.same_currency_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) {
          Text(
            DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
          )
        }
        OutlinedButton(onClick = { pickingTime = true }, modifier = Modifier.weight(1f)) {
          Text(DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_TIME))
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(if (oneToOne) R.string.from else R.string.paid_by), Modifier.weight(1f))
        TextButton(onClick = { payerMenu = true }) { Text(s.nick(payer)) }
        DropdownMenu(payerMenu, onDismissRequest = { payerMenu = false }) {
          s.members.keys.forEach { id ->
            DropdownMenuItem(
              text = { Text(s.nick(id)) },
              onClick = {
                payer = id
                // Keep the pair valid rather than letting the user save a transfer to themselves.
                if (oneToOne && picked.containsKey(id)) {
                  picked.clear()
                  s.members.keys.firstOrNull { it != id }?.let { picked[it] = "1" }
                }
                payerMenu = false
              },
            )
          }
        }
      }

      if (oneToOne) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.to), Modifier.weight(1f))
          TextButton(onClick = { toMenu = true }) { Text(other?.let { s.nick(it) } ?: "—") }
          DropdownMenu(toMenu, onDismissRequest = { toMenu = false }) {
            s.members.keys.filter { it != payer }.forEach { id ->
              DropdownMenuItem(
                text = { Text(s.nick(id)) },
                onClick = {
                  picked.clear()
                  picked[id] = "1"
                  toMenu = false
                },
              )
            }
          }
        }
        Text(
          when (kind) {
            Kind.CONVERSION ->
              stringResource(R.string.conversion_hint, currency.ifBlank { "?" }, toCurrency.ifBlank { "?" })
            else -> stringResource(R.string.payback_hint, s.nick(payer), currency.ifBlank { "?" })
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
      } else {
        SplitSection(s, mode, { mode = it }, picked, weights)
      }

      if (exactMismatch) {
        Text(
          stringResource(R.string.exact_mismatch, weights.values.sum().money(), cents.money()),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (expenseId != null) {
        HorizontalDivider()
        TextButton(onClick = { deleting = true }) {
          Text(
            stringResource(deleteLabel(kind)),
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }

  if (deleting && expenseId != null) {
    AlertDialog(
      onDismissRequest = { deleting = false },
      title = { Text(stringResource(deleteLabel(kind))) },
      text = { Text(stringResource(R.string.delete_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            deleting = false
            scope.launch {
              // A delete is another op, not an erasure — the history stays intact (spec §9).
              repo.append(eventId) { Put(id = expenseId, deleted = true) }
              onBack()
            }
          }
        ) {
          Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  if (pickingDate) {
    // The M3 picker speaks UTC midnight, so the local day goes in and only the day comes back out —
    // the time already on `at` has to survive a change of date.
    val picker =
      rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().toEpochDay() * 86_400_000L)
    DatePickerDialog(
      onDismissRequest = { pickingDate = false },
      confirmButton = {
        TextButton(
          onClick = {
            picker.selectedDateMillis?.let {
              at = local.with(LocalDate.ofEpochDay(it / 86_400_000L)).toInstant().toEpochMilli()
            }
            pickingDate = false
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
    ) {
      DatePicker(picker)
    }
  }

  if (pickingTime) {
    val picker = rememberTimePickerState(local.hour, local.minute)
    TimePickerDialog(
      onDismissRequest = { pickingTime = false },
      title = { TimePickerDialogDefaults.Title(TimePickerDisplayMode.Picker) },
      confirmButton = {
        TextButton(
          onClick = {
            // Seconds cleared: the user picked a minute, and keeping the old ones would be a lie.
            at = local.withHour(picker.hour).withMinute(picker.minute).withSecond(0).withNano(0)
              .toInstant().toEpochMilli()
            pickingTime = false
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
    ) {
      TimePicker(picker)
    }
  }
}

/** Whole sentences per kind, not "Delete " + noun — the noun inflects in Russian. */
private fun deleteLabel(kind: Kind) =
  when (kind) {
    Kind.EXPENSE -> R.string.delete_expense
    Kind.REIMBURSEMENT -> R.string.delete_payback
    Kind.CONVERSION -> R.string.delete_exchange
  }

/** Amount plus its currency. Three of these on screen at once for an exchange, so it is one thing. */
@Composable
private fun AmountRow(
  label: String,
  amount: String,
  onAmount: (String) -> Unit,
  bad: Boolean,
  currency: String,
  onCurrency: (String) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      amount,
      onAmount,
      label = { Text(label) },
      isError = bad,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      singleLine = true,
      modifier = Modifier.weight(1f),
    )
    CurrencyField(currency, onCurrency, Modifier.width(120.dp))
  }
}

@Composable
private fun SplitSection(
  s: EventState,
  mode: SplitMode,
  onMode: (SplitMode) -> Unit,
  picked: MutableMap<UserId, String>,
  weights: Map<UserId, Long>,
) {
  HorizontalDivider()
  Text(stringResource(R.string.split), style = MaterialTheme.typography.titleMedium)
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    SplitMode.entries.forEachIndexed { i, m ->
      SegmentedButton(
        selected = mode == m,
        onClick = { onMode(m) },
        shape = SegmentedButtonDefaults.itemShape(i, SplitMode.entries.size),
      ) {
        Text(
          stringResource(
            when (m) {
              SplitMode.EQUAL -> R.string.split_equally
              SplitMode.SHARES -> R.string.split_shares
              SplitMode.EXACT -> R.string.split_amounts
            }
          )
        )
      }
    }
  }

  s.members.keys.forEach { id ->
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Checkbox(
        checked = picked.containsKey(id),
        onCheckedChange = { on -> if (on) picked[id] = if (mode == SplitMode.EXACT) "" else "1" else picked.remove(id) },
      )
      Text(if (id == repo.me) stringResource(R.string.name_you, s.nick(id)) else s.nick(id), Modifier.weight(1f))
      if (mode != SplitMode.EQUAL && picked.containsKey(id)) {
        OutlinedTextField(
          picked[id] ?: "",
          { picked[id] = it },
          modifier = Modifier.width(110.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          isError = (weights[id] ?: -1L) < 0,
        )
      }
    }
  }
}
