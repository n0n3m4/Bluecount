package com.bluecount

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object EventsKey : NavKey

@Serializable data class EventKey(val id: String) : NavKey

/** [expenseId] null means "new expense". */
@Serializable data class ExpenseKey(val event: String, val expenseId: String? = null) : NavKey

@Serializable data class ShareKey(val event: String) : NavKey

@Serializable data object ScanKey : NavKey

@Serializable data object SettingsKey : NavKey
