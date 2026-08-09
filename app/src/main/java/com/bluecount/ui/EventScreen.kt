package com.bluecount.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluecount.EventState
import com.bluecount.Expense
import com.bluecount.Kind
import com.bluecount.Put
import com.bluecount.Transfer
import com.bluecount.settleUp
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(
  eventId: String,
  onBack: () -> Unit,
  onExpense: (String?) -> Unit,
  onShare: () -> Unit,
  onLeave: () -> Unit,
) {
  val state by repo.state(eventId).collectAsStateWithLifecycle(null)
  var tab by remember { mutableStateOf(0) }
  var menu by remember { mutableStateOf(false) }
  var leaving by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val s = state

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(s?.name?.ifBlank { "Event" } ?: "…") },
        navigationIcon = { BackButton(onBack) },
        actions = {
          TextButton(onClick = onShare) { Text("Invite") }
          TextButton(onClick = { menu = true }) { Text("⋮") }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
              text = { Text("Leave event") },
              onClick = {
                menu = false
                leaving = true
              },
            )
          }
        },
      )
    },
    floatingActionButton = {
      if (s != null) ExtendedFloatingActionButton(text = { Text("Add expense") }, icon = {}, onClick = { onExpense(null) })
    },
  ) { pad ->
    Column(Modifier.padding(pad)) {
      PrimaryTabRow(tab) {
        Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Expenses") })
        Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Balances") })
      }
      when {
        s == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        tab == 0 -> ExpenseList(s, onExpense)
        else -> Balances(s, eventId)
      }
    }
  }

  if (leaving) {
    AlertDialog(
      onDismissRequest = { leaving = false },
      title = { Text("Leave event?") },
      text = {
        Text(
          "This removes the event and its history from this phone only. Everyone else keeps their " +
            "copy, and scanning the QR again brings it all back."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            leaving = false
            scope.launch {
              repo.leaveEvent(eventId)
              onLeave()
            }
          }
        ) {
          Text("Leave")
        }
      },
      dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun ExpenseList(s: EventState, onExpense: (String?) -> Unit) {
  if (s.expenses.isEmpty()) {
    Empty("No expenses yet.")
    return
  }
  LazyColumn {
    items(s.expenses, key = { it.id }) { e ->
      ExpenseRow(e, s, onClick = { onExpense(e.id) })
      HorizontalDivider()
    }
  }
}

@Composable
private fun ExpenseRow(e: Expense, s: EventState, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(e.title.ifBlank { if (e.kind == Kind.REIMBURSEMENT) "Payment" else "Expense" })
      Text(
        buildString {
          append(if (e.kind == Kind.REIMBURSEMENT) "${s.nick(e.payer)} paid back" else "paid by ${s.nick(e.payer)}")
          append(" · ")
          append(LocalDate.ofEpochDay(e.date))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Text("${e.cents.money()} ${s.currency}", fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun Balances(s: EventState, eventId: String) {
  var recording by remember { mutableStateOf<Transfer?>(null) }
  val scope = rememberCoroutineScope()
  val transfers = remember(s.balances) { settleUp(s.balances) }

  LazyColumn {
    items(s.balances.entries.sortedByDescending { it.value }.toList(), key = { it.key }) { (id, cents) ->
      Row(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(s.nick(id) + if (id == repo.me) " (you)" else "", Modifier.weight(1f))
        Text(
          "${cents.money()} ${s.currency}",
          color =
            when {
              cents > 0 -> MaterialTheme.colorScheme.primary
              cents < 0 -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.outline
            },
        )
      }
      HorizontalDivider()
    }

    item {
      Text(
        "Settle up",
        Modifier.padding(16.dp, 24.dp, 16.dp, 8.dp),
        style = MaterialTheme.typography.titleMedium,
      )
    }
    if (transfers.isEmpty()) {
      item { Text("Everyone is square.", Modifier.padding(16.dp, 0.dp), color = MaterialTheme.colorScheme.outline) }
    }
    items(transfers, key = { "${it.from}/${it.to}" }) { t ->
      Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${s.nick(t.from)} → ${s.nick(t.to)}", Modifier.weight(1f))
        Text("${t.cents.money()} ${s.currency}")
        TextButton(onClick = { recording = t }) { Text("Record") }
      }
    }
  }

  recording?.let { t ->
    AlertDialog(
      onDismissRequest = { recording = null },
      title = { Text("Record payment") },
      text = { Text("${s.nick(t.from)} gave ${t.cents.money()} ${s.currency} to ${s.nick(t.to)} in cash.") },
      confirmButton = {
        TextButton(
          onClick = {
            recording = null
            scope.launch {
              repo.append(eventId) { id ->
                Put(
                  id = id,
                  title = "Payment",
                  cents = t.cents,
                  date = LocalDate.now().toEpochDay(),
                  payer = t.from,
                  shares = mapOf(t.to to 1L),
                  kind = Kind.REIMBURSEMENT,
                )
              }
            }
          }
        ) {
          Text("Record")
        }
      },
      dismissButton = { TextButton(onClick = { recording = null }) { Text("Cancel") } },
    )
  }
}
