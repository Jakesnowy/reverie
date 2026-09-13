package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Dialog-flag state for [ModelListScreen]: which management dialog is open
 * (settings / file manager / backup / temp-clean / embedding manager /
 * custom-model / help) plus the model-conversion progress shown as a blocking
 * overlay. Field names mirror the former inline `remember` blocks one-to-one.
 */
class ModelListDialogsState {
    var showSettingsDialog by mutableStateOf(false)
    var showFileManagerDialog by mutableStateOf(false)
    var showBackupDialog by mutableStateOf(false)
    var showCleanTempDialog by mutableStateOf(false)
    var tempScanBytes by mutableLongStateOf(0L)
    var showEmbeddingManagerDialog by mutableStateOf(false)
    var showCustomModelDialog by mutableStateOf(false)
    var showCustomNpuModelDialog by mutableStateOf(false)
    var showHelpDialog by mutableStateOf(false)

    var isConverting by mutableStateOf(false)
    var conversionProgress by mutableStateOf("")
    var extractByteProgress by mutableStateOf<ExtractByteProgress?>(null)
}
