package com.bluecount.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluecount.EventState
import com.bluecount.R
import com.bluecount.Wake
import com.bluecount.Expense
import com.bluecount.Kind
import com.bluecount.Put
import com.bluecount.Transfer
import com.bluecount.settleUp
import java.io.File
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
  val syncStatus by sync.status.collectAsStateWithLifecycle()
  var tab by remember { mutableStateOf(0) }
  var menu by remember { mutableStateOf(false) }
  var leaving by remember { mutableStateOf(false) }
  var wake by remember { mutableStateOf(repo.wakeEnabled) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val s = state

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(s?.name?.ifBlank { "Event" } ?: "…") },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = onShare) { Icon(painterResource(R.drawable.ic_person_add), "Invite") }
          // A TextButton here was a 64dp-wide stadium pill around a tiny glyph, next to a round
          // 48dp back button; an IconButton is the square, centred thing an app bar expects.
          IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), "More") }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
              text = { Text("Sync now") },
              onClick = {
                menu = false
                sync.kick()
              },
            )
            DropdownMenuItem(
              text = { Text(if (wake) "Wake nearby phones: on" else "Wake nearby phones: off") },
              onClick = {
                menu = false
                wake = !wake
                repo.wakeEnabled = wake
                if (wake) Wake.arm(context) else Wake.disarm(context)
              },
            )
            DropdownMenuItem(
              text = { Text("Export CSV") },
              onClick = {
                menu = false
                scope.launch { shareCsv(context, s?.name.orEmpty(), repo.exportCsv(eventId)) }
              },
            )
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
      if (s != null) {
        FloatingActionButton(onClick = { onExpense(null) }) { Icon(painterResource(R.drawable.ic_add), "Add expense") }
      }
    },
  ) { pad ->
    Column(Modifier.padding(pad)) {
      PrimaryTabRow(tab) {
        Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Expenses") })
        Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Balances") })
      }
      // Without this, "nobody else is nearby" and "Nearby permission denied" look identical: an
      // event with one member and no explanation.
      Text(
        syncStatus,
        Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
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

/** Straight to a share sheet — there is no import side, so a file the user filed away is the whole feature. */
private fun shareCsv(context: Context, name: String, csv: String) {
  val file = File(File(context.cacheDir, "export").apply { mkdirs() }, safeName(name) + ".csv")
  file.writeText(csv)
  val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
  context.startActivity(
    Intent.createChooser(
      Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      },
      "Export CSV",
    )
  )
}

private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "event" }

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
    Icon(
      painterResource(e.kind.icon()),
      e.kind.label(),
      Modifier.padding(end = 12.dp),
      tint = MaterialTheme.colorScheme.outline,
    )
    val whenText = e.whenText()
    Column(Modifier.weight(1f)) {
      Text(e.title.ifBlank { e.kind.label() })
      Text(
        buildString {
          val to = e.shares.keys.singleOrNull()
          when (e.kind) {
            Kind.REIMBURSEMENT -> append("${s.nick(e.payer)} paid back" + if (to != null) " ${s.nick(to)}" else "")
            Kind.CONVERSION -> append("${s.nick(e.payer)} → ${to?.let { s.nick(it) } ?: "?"} · ${e.toCents.money(e.toCurrency)}")
            Kind.EXPENSE -> append("paid by ${s.nick(e.payer)}")
          }
          append(" · ")
          append(whenText)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    // Who paid and who wrote the op are different facts — anyone may edit anyone's expense, and the
    // signature says who did. lastEditor is that author.
    Column(horizontalAlignment = Alignment.End) {
      Text(e.cents.money(e.currency), fontWeight = FontWeight.Bold)
      Text(
        "by ${s.nick(e.lastEditor)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
  }
}

@Composable
private fun Balances(s: EventState, eventId: String) {
  var recording by remember { mutableStateOf<Pair<String, Transfer>?>(null) }
  val scope = rememberCoroutineScope()
  // Independently per currency: settleUp() knows nothing about currencies and must not start to.
  val transfers = remember(s.balances) { s.balances.mapValues { (_, b) -> settleUp(b) } }
  // A header per currency would be noise on the single-currency event that most of these are.
  val headed = s.balances.size > 1

  if (s.balances.isEmpty()) {
    Empty("Nothing to settle yet.")
    return
  }

  LazyColumn {
    s.balances.forEach { (cur, balances) ->
      if (headed) {
        item(key = "h/$cur") {
          Text(
            cur,
            Modifier.fillMaxWidth().padding(16.dp, 24.dp, 16.dp, 4.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          HorizontalDivider()
        }
      }

      items(balances.entries.sortedByDescending { it.value }.toList(), key = { "$cur/${it.key}" }) { (id, cents) ->
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
          Text(s.nick(id) + if (id == repo.me) " (you)" else "", Modifier.weight(1f))
          Text(
            cents.money(cur),
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

      item(key = "s/$cur") {
        Text(
          "Settle up",
          Modifier.padding(16.dp, 24.dp, 16.dp, 8.dp),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      val ts = transfers[cur].orEmpty()
      if (ts.isEmpty()) {
        item(key = "q/$cur") {
          Text("Everyone is square in $cur.", Modifier.padding(16.dp, 0.dp), color = MaterialTheme.colorScheme.outline)
        }
      }
      items(ts, key = { "$cur/${it.from}/${it.to}" }) { t ->
        Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("${s.nick(t.from)} → ${s.nick(t.to)}", Modifier.weight(1f))
          Text(t.cents.money(cur))
          TextButton(onClick = { recording = cur to t }) { Text("Record") }
        }
      }
    }
  }

  recording?.let { (cur, t) ->
    AlertDialog(
      onDismissRequest = { recording = null },
      title = { Text("Record payback") },
      text = { Text("${s.nick(t.from)} gave ${t.cents.money(cur)} to ${s.nick(t.to)} in cash.") },
      confirmButton = {
        TextButton(
          onClick = {
            recording = null
            scope.launch {
              repo.append(eventId) { id ->
                Put(
                  id = id,
                  title = "Payback",
                  cents = t.cents,
                  date = LocalDate.now().toEpochDay(),
                  at = System.currentTimeMillis(),
                  payer = t.from,
                  shares = mapOf(t.to to 1L),
                  kind = Kind.REIMBURSEMENT,
                  currency = cur,
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
