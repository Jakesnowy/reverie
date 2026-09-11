package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem

/**
 * History-related UI state for [ModelRunScreen], hoisted out of the screen
 * body so the composable stays readable and its recompose scopes stay small
 * (history / filter / selection / batch concerns in one place).
 *
 * Field names mirror the former inline `remember` blocks one-to-one; the
 * paged and observed history flows stay in the composable because they need
 * composition scope (`collectAsLazyPagingItems` / `collectAsState`).
 */
class RunHistoryState(initialModelId: String) {
    /** Newest-first filter applied to the strip, dialogs and batch actions. */
    var historyFilter by mutableStateOf(HistoryFilter(modelIds = setOf(initialModelId)))

    var selectedHistoryItem by mutableStateOf<HistoryItem?>(null)
    var showHistoryFilterSheet by mutableStateOf(false)
    var showHistoryDetailDialog by mutableStateOf(false)
    var showHistoryParametersDialog by mutableStateOf(false)
    var showDeleteHistoryDialog by mutableStateOf(false)
    var showReproduceParamsDialog by mutableStateOf(false)
    var pendingReproduceParams by mutableStateOf<GenerationParameters?>(null)

    /** Multi-select mode over the history strip (batch delete / save). */
    var isSelectionMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<Long>()
    var showBatchDeleteDialog by mutableStateOf(false)
    var showBatchSaveDialog by mutableStateOf(false)
    var isBatchSaving by mutableStateOf(false)
    var batchSaveTotal by mutableIntStateOf(0)
    var batchSaveCurrent by mutableIntStateOf(0)
    var batchSaveFailed by mutableIntStateOf(0)
}
