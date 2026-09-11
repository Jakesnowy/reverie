package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.utils.ImportedParams

/**
 * Parameter share/import UI state for [ModelRunScreen], hoisted out of the
 * screen body. Field names mirror the former inline `remember` block; the
 * two observed preference flows (share encoding, clipboard clearing) stay in
 * the composable because they need composition scope.
 */
class RunShareImportState {
    var shareSourceParams by mutableStateOf<GenerationParameters?>(null)
    var shareSourceModelId by mutableStateOf<String?>(null)
    var pendingImport by mutableStateOf<ImportedParams?>(null)
    var clipboardImportChecked by mutableStateOf(false)
}
