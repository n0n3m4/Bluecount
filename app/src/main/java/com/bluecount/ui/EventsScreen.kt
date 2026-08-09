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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluecount.EventRow
import com.bluecount.Identity
import com.bluecount.R
import com.bluecount.shortId
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(onOpen: (String) -> Unit, onScan: () -> Unit, onSettings: () -> Unit) {
  val events by repo.events.collectAsStateWithLifecycle(emptyList())
  var creating by remember { mutableStateOf(false) }
  var nickname by remember { mutableStateOf(repo.nickname) }
  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Bluecount") },
        actions = { IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_person), "You") } },
      )
    },
    floatingActionButton = {
      // The text-only overload: the icon slot always reserves its 12dp spacer, so passing an empty
      // one left both labels shoved off-centre. These keep words rather than icons because there is
      // no glyph for "create an event" that anyone reads on the first try.
      Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExtendedFloatingActionButton(onClick = onScan) { Text("Scan QR") }
        ExtendedFloatingActionButton(onClick = { creating = true }) { Text("New event") }
      }
    },
  ) { pad ->
    if (events.isEmpty()) {
      Empty("No events yet.\n\nCreate one, or scan a friend's QR code to join theirs.", Modifier.padding(pad))
    } else {
      LazyColumn(Modifier.padding(pad)) {
        items(events, key = { it.id }) { row ->
          EventCard(row, onClick = { onOpen(row.id) })
          HorizontalDivider()
        }
      }
    }
  }

  // This is the start destination, so a blank stored nickname here *is* first launch.
  if (nickname.isBlank()) {
    WelcomeDialog(
      onDone = {
        repo.nickname = it
        nickname = it
      }
    )
  }

  if (creating) {
    NewEventDialog(
      onDismiss = { creating = false },
      onCreate = { name, currency, nick ->
        creating = false
        scope.launch { onOpen(repo.createEvent(name, currency, nick)) }
      },
    )
  }
}

@Composable
private fun EventCard(row: EventRow, onClick: () -> Unit) {
  val state by repo.state(row.id).collectAsStateWithLifecycle(null)
  // One line per currency the user is actually in the red or black in. Nothing is netted across
  // currencies here — a single "you owe" number would have to pick a rate, and there is none.
  val mine = state?.balances?.mapNotNull { (cur, b) -> b[repo.me]?.takeIf { it != 0L }?.let { cur to it } }.orEmpty()

  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(state?.name?.takeIf { it.isNotBlank() } ?: row.name, style = MaterialTheme.typography.titleMedium)
      Text(
        "${state?.members?.size ?: 1} people · ${state?.expenses?.size ?: 0} expenses",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(
        when {
          mine.isEmpty() -> "settled"
          mine.all { it.second > 0 } -> "you are owed"
          mine.all { it.second < 0 } -> "you owe"
          else -> "owed / owing"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )
      mine.forEach { (cur, cents) ->
        Text(
          kotlin.math.abs(cents).money(cur),
          fontWeight = FontWeight.Bold,
          color = if (cents > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/**
 * Asked once, on first launch. The stored nickname is only ever a prefill — the authority on who
 * you are in an event is the signed [com.bluecount.Profile] op — but every event you create or join
 * needs a name to put in one, and an empty box in that dialog is a worse place to ask for it.
 */
@Composable
private fun WelcomeDialog(onDone: (String) -> Unit) {
  var nick by remember { mutableStateOf("") }

  AlertDialog(
    // No dismiss and no cancel: there is nothing behind this on a fresh install, and every path
    // out of the events list needs the name anyway.
    onDismissRequest = {},
    title = { Text("Welcome to Bluecount") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What should we call you? This is the name the others see next to whatever you add.")
        OutlinedTextField(nick, { nick = it }, label = { Text("Your name") }, singleLine = true)
        Text(
          "Kept on this phone. You can change it any time under “Me”.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
      }
    },
    confirmButton = {
      TextButton(enabled = nick.isNotBlank(), onClick = { onDone(nick.trim()) }) { Text("Continue") }
    },
  )
}

@Composable
private fun NewEventDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
  var name by remember { mutableStateOf("") }
  // Fixed at creation, per the ledger's design: it is only the default each expense starts from,
  // and individual expenses may be in any currency afterwards.
  var currency by remember { mutableStateOf(repo.currencies.first()) }
  var nick by remember { mutableStateOf(repo.nickname) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New event") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        CurrencyField(currency, { currency = it })
        Text(
          "The default for new expenses. Any expense can use another currency.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(nick, { nick = it }, label = { Text("Your name in this event") }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(
        enabled = name.isNotBlank() && nick.isNotBlank() && currency.isNotBlank(),
        onClick = { onCreate(name.trim(), currency.trim(), nick.trim()) },
      ) {
        Text("Create")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  var nick by remember { mutableStateOf(repo.nickname) }
  var currencies by remember { mutableStateOf(repo.currencies.joinToString(", ")) }

  Scaffold(topBar = { TopAppBar(title = { Text("You") }, navigationIcon = { BackButton(onBack) }) }) { pad ->
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      OutlinedTextField(
        nick,
        {
          nick = it
          repo.nickname = it
        },
        label = { Text("Default name") },
        supportingText = { Text("Used when you create or join an event. Changing it here does not rename you in events you already joined.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        currencies,
        {
          currencies = it
          // A blank field would leave the pickers with nothing, so the getter falls back to the
          // defaults; the text stays as typed until the screen is reopened.
          repo.currencies = it.split(",").map { c -> c.trim().uppercase() }.filter { c -> c.isNotEmpty() }
        },
        label = { Text("Currencies") },
        supportingText = { Text("Quick picks offered when choosing a currency, first one is the default. Any 3-letter code can still be typed in by hand.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Your ID", style = MaterialTheme.typography.titleSmall)
          Text("…" + Identity.me.shortId(), style = MaterialTheme.typography.bodyMedium)
          Text(
            "This is the public half of a key generated on this phone. It signs everything you add, " +
              "which is how the app tells whose expenses are whose. It cannot be backed up or moved: " +
              "reinstalling or losing the phone means a new identity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
          )
        }
      }
    }
  }
}
