package com.bluecount.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bluecount.Genesis
import com.bluecount.Op
import com.bluecount.R
import com.bluecount.decodePayload
import com.bluecount.importOps
import com.bluecount.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirrors the `<intent-filter>`s in AndroidManifest.xml, which cannot read Kotlin constants.
 *
 * The extension carries more weight than the type: `MimeTypeMap` has never heard of `.bluecount`,
 * so most messengers re-label the attachment `application/octet-stream` on the way through and the
 * manifest has to catch it by path as well as by type.
 */
const val BLUECOUNT_EXT = ".bluecount"
const val BLUECOUNT_MIME = "application/vnd.bluecount"

/** What was in the file, once it has been read and checked. */
private class Imported(val ops: List<Op>, val invite: Invite)

/**
 * A file tapped in a chat app. Two outcomes: merge into an event we are already in, or offer to
 * join one we are not — which is the only way to be let into an event without meeting anybody.
 *
 * A dialog rather than a nav entry, so it can appear over whatever was on screen and the back stack
 * never has to know that intents exist.
 */
@Composable
fun ImportDialog(uri: Uri, onDone: () -> Unit, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var joining by remember { mutableStateOf<Imported?>(null) }
  var nick by remember { mutableStateOf(repo.nickname) }

  LaunchedEffect(uri) {
    val found = read(context, uri)
    when {
      found == null -> {
        Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show()
        onDone()
      }
      // Already a member: nothing to confirm. Merging is append-only, signature-checked and
      // deduped, so the worst a file can do to an event we hold is add nothing.
      repo.eventRow(found.invite.id) != null -> {
        report(context, repo.merge(found.ops))
        onOpen(found.invite.id)
        onDone()
      }
      else -> joining = found
    }
  }

  // Same dialog as the QR scan, and deliberately so: a confirmation is the whole membership check
  // this app has ever had, because knowing the event id is the whole credential (spec §5).
  joining?.let { found ->
    AlertDialog(
      onDismissRequest = { onDone() },
      title = { Text(stringResource(R.string.join_title, found.invite.name)) },
      text = {
        OutlinedTextField(nick, { nick = it }, label = { Text(stringResource(R.string.your_name_in_event)) }, singleLine = true)
      },
      confirmButton = {
        TextButton(
          enabled = nick.isNotBlank(),
          onClick = {
            scope.launch {
              val (id, name, currency) = found.invite
              repo.joinEvent(id, name, currency, nick.trim())
              report(context, repo.merge(found.ops))
              onOpen(id)
              onDone()
            }
          },
        ) {
          Text(stringResource(R.string.join))
        }
      },
      dismissButton = { TextButton(onClick = { onDone() }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/**
 * Everything untrusted happens here, off the main thread: the read is capped so a huge attachment
 * cannot decide our heap, and [importOps] returns empty rather than throwing for anything that is
 * not one of our files — including a half-downloaded one.
 */
private suspend fun read(context: Context, uri: Uri): Imported? =
  withContext(Dispatchers.IO) {
    val bytes =
      runCatching { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }.getOrNull()
    val ops = bytes?.let(::importOps).orEmpty()
    val event = ops.firstOrNull()?.event ?: return@withContext null
    Imported(ops, invite(event, ops))
  }

/**
 * The name to show before anything is merged. Taken from the file's own [Genesis], verified first —
 * an unsigned one would let a doctored file put any words it likes in the join dialog. It is still
 * only a label: the fold replaces it with the signed event name as soon as the ops land.
 */
private fun invite(event: String, ops: List<Op>): Invite {
  val genesis =
    ops.firstNotNullOfOrNull {
      if (it.event != event || !it.verified()) null else decodePayload(it.payload) as? Genesis
    }
  return Invite(event, genesis?.name.orEmpty(), genesis?.currency.orEmpty().ifBlank { "EUR" })
}

/**
 * A file is the whole history every time, so the only number worth reporting is what was new — and
 * 0 is the useful answer, meaning the sender's copy had nothing this phone did not already hold.
 * Same wording as a radio sync, because it is the same event: entries arrived.
 */
private fun report(context: Context, added: Int) =
  Toast.makeText(
    context,
    context.resources.getQuantityString(R.plurals.n_received, added, added),
    Toast.LENGTH_LONG,
  ).show()
