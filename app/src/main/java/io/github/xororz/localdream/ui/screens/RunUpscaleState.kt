package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Upscaler UI state for [ModelRunScreen], hoisted out of the screen body so
 * the dialog region can read it inside its own recompose scope. Field names
 * mirror the former inline `remember` blocks one-to-one.
 */
class RunUpscaleState {
    var showUpscalerDialog by mutableStateOf(false)

    /** True while the upscale request runs; drives the blocking overlay. */
    var isUpscaling by mutableStateOf(false)
}
