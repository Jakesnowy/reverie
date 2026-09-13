package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Custom-model source state for [ModelListScreen]: the source picker
 * selection (huggingface / custom URL / local import) and the base-URL text
 * fields backing it. Field names mirror the former inline `remember` blocks
 * one-to-one.
 */
class ModelListSourceState {
    var tempBaseUrl by mutableStateOf("")
    var selectedSource by mutableStateOf("huggingface")
    var currentBaseUrl by mutableStateOf("https://huggingface.co/")
}
