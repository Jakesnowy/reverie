package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.Model

/**
 * Model download state for [ModelListScreen]: the in-flight download (model +
 * live progress), its error, and the download/upgrade confirmation targets.
 * Field names mirror the former inline `remember` blocks one-to-one.
 */
class ModelListDownloadState {
    var downloadingModel by mutableStateOf<Model?>(null)
    var currentProgress by mutableStateOf<DownloadProgress?>(null)
    var downloadError by mutableStateOf<String?>(null)
    var showDownloadConfirm by mutableStateOf<Model?>(null)
    var showUpgradeConfirm by mutableStateOf<Model?>(null)
}
