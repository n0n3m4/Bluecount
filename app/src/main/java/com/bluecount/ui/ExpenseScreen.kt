package com.bluecount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluecount.Put
import com.bluecount.SplitMode
import com.bluecount.UserId
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(eventId: String, expenseId: String?, onBack: () -> Unit) {
  val state by repo.state(eventId).collectAsStateWithLifecycle(null)
  val scope = rememberCoroutineScope()

  var loaded by remember { mutableStateOf(false) }
  var title by remember { mutableStateOf("") }
  var amount by remember { mutableStateOf("") }
  var date by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
  var payer by remember { mutableStateOf("") }
  var mode by remember { mutableStateOf(SplitMode.EQUAL) }
  val picked = remember { mutableStateMapOf<UserId, String>() }
  var pickingDate by remember { mutableStateOf(false) }
  var payerMenu by remember { mutableStateOf(false) }

  val s = state
  // Seed the form once the log has been read; everything after that is the user's edit.
  LaunchedEffect(s) {
    if (s == null || loaded) return@LaunchedEffect
    val existing = s.expenses.firstOrNull { it.id == expenseId }
    if (existing != null) {
      title = existing.title
      amount = existing.cents.money()
      date = existing.date
      payer = existing.payer
      mode = existing.mode
      existing.shares.forEach { (id, w) -> picked[id] = if (existing.mode == SplitMode.EXACT) w.money() else w.toString() }
    } else {
      payer = repo.me
      s.members.keys.forEach { picked[it] = "1" }
    }
    loaded = true
  }

  val cents = amount.toCentsOrNull()
  val weights: Map<UserId, Long> =
    picked.mapValues { (_, text) ->
      when (mode) {
        SplitMode.EXACT -> text.toCentsOrNull() ?: -1L
        else -> text.trim().toLongOrNull() ?: -1L
      }
    }
  val badWeight = weights.values.any { it < 0 }
  val exactMismatch = mode == SplitMode.EXACT && !badWeight && cents != null && weights.values.sum() != cents
  val canSave = s != null && cents != null && cents > 0 && payer.isNotEmpty() && picked.isNotEmpty() && !badWeight && !exactMismatch

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (expenseId == null) "New expense" else "Edit expense") },
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
                    date = date,
                    payer = payer,
                    mode = mode,
                    shares = if (mode == SplitMode.EQUAL) weights.mapValues { 1L } else weights,
                  )
                }
                onBack()
              }
            },
          ) {
            Text("Save")
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
      OutlinedTextField(title, { title = it }, label = { Text("What for") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(
        amount,
        { amount = it },
        label = { Text("Amount (${s.currency})") },
        isError = amount.isNotEmpty() && cents == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Date: ${LocalDate.ofEpochDay(date)}")
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Paid by", Modifier.weight(1f))
        TextButton(onClick = { payerMenu = true }) { Text(s.nick(payer)) }
        DropdownMenu(payerMenu, onDismissRequest = { payerMenu = false }) {
          s.members.keys.forEach { id ->
            DropdownMenuItem(
              text = { Text(s.nick(id)) },
              onClick = {
                payer = id
                payerMenu = false
              },
            )
          }
        }
      }

      HorizontalDivider()
      Text("Split", style = MaterialTheme.typography.titleMedium)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SplitMode.entries.forEachIndexed { i, m ->
          SegmentedButton(
            selected = mode == m,
            onClick = { mode = m },
            shape = SegmentedButtonDefaults.itemShape(i, SplitMode.entries.size),
          ) {
            Text(when (m) { SplitMode.EQUAL -> "Equally"; SplitMode.SHARES -> "Shares"; SplitMode.EXACT -> "Amounts" })
          }
        }
      }

      s.members.keys.forEach { id ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Checkbox(
            checked = picked.containsKey(id),
            onCheckedChange = { on -> if (on) picked[id] = if (mode == SplitMode.EXACT) "" else "1" else picked.remove(id) },
          )
          Text(s.nick(id) + if (id == repo.me) " (you)" else "", Modifier.weight(1f))
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

      if (exactMismatch) {
        Text(
          "The amounts add up to ${weights.values.sum().money()}, not ${cents.money()}.",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (expenseId != null) {
        HorizontalDivider()
        TextButton(
          onClick = {
            scope.launch {
              // A delete is another op, not an erasure — the history stays intact (spec §9).
              repo.append(eventId) { Put(id = expenseId, deleted = true) }
              onBack()
            }
          }
        ) {
          Text("Delete expense", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
      }
    }
  }

  if (pickingDate) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = date * 86_400_000L)
    DatePickerDialog(
      onDismissRequest = { pickingDate = false },
      confirmButton = {
        TextButton(
          onClick = {
            picker.selectedDateMillis?.let { date = it / 86_400_000L }
            pickingDate = false
          }
        ) {
          Text("OK")
        }
      },
    ) {
      DatePicker(picker)
    }
  }
}
