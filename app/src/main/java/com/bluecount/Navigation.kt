package com.bluecount

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.bluecount.ui.EventScreen
import com.bluecount.ui.EventsScreen
import com.bluecount.ui.ExpenseScreen
import com.bluecount.ui.ImportDialog
import com.bluecount.ui.ScanScreen
import com.bluecount.ui.SettingsScreen
import com.bluecount.ui.ShareScreen

/** @param importUri a file tapped in another app, or null. See [ImportDialog]. */
@Composable
fun MainNavigation(importUri: Uri? = null, onImportDone: () -> Unit = {}) {
  val backStack = rememberNavBackStack(EventsKey)
  val back = { backStack.removeLastOrNull(); Unit }

  NavDisplay(
    backStack = backStack,
    onBack = { back() },
    entryProvider =
      entryProvider {
        entry<EventsKey> { EventsScreen(onOpen = { backStack.add(EventKey(it)) }, onScan = { backStack.add(ScanKey) }, onSettings = { backStack.add(SettingsKey) }) }
        entry<EventKey> { key ->
          EventScreen(
            eventId = key.id,
            onBack = back,
            onExpense = { backStack.add(ExpenseKey(key.id, it)) },
            onShare = { backStack.add(ShareKey(key.id)) },
            onLeave = { backStack.clear(); backStack.add(EventsKey) },
          )
        }
        entry<ExpenseKey> { key -> ExpenseScreen(eventId = key.event, expenseId = key.expenseId, onBack = back) }
        entry<ShareKey> { key -> ShareScreen(eventId = key.event, onBack = back) }
        entry<ScanKey> { ScanScreen(onBack = back, onJoined = { backStack.removeLastOrNull(); backStack.add(EventKey(it)) }) }
        entry<SettingsKey> { SettingsScreen(onBack = back) }
      },
  )

  importUri?.let { uri ->
    ImportDialog(
      uri = uri,
      onDone = onImportDone,
      // Guarded: tapping the file for the event already on screen should not stack a second copy.
      onOpen = { if (backStack.lastOrNull() != EventKey(it)) backStack.add(EventKey(it)) },
    )
  }
}
