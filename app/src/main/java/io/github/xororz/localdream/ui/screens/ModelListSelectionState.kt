package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.Model

/**
 * List-selection state for [ModelListScreen]: multi-select mode, the selected
 * models, the ordered pinned ids (drives the pinned-first sort within each
 * tab; loaded once and kept in sync as the user pins/unpins/renames), and the
 * delete/rename confirmation targets. Field names mirror the former inline
 * `remember` blocks one-to-one.
 */
class ModelListSelectionState(initialPinnedIds: List<String>) {
    var isSelectionMode by mutableStateOf(false)
    var selectedModels by mutableStateOf(setOf<Model>())

    /** Ordered pinned ids; drives the pinned-first sort within each tab. */
    var pinnedIds by mutableStateOf(initialPinnedIds)
    var showDeleteConfirm by mutableStateOf(false)
    var renameTarget by mutableStateOf<Model?>(null)
}
