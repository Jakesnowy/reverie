package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.GenerationDefaults
import kotlinx.coroutines.Job

/**
 * UltraFix (tiled img2img repair of an upscaled image) UI state for
 * [ModelRunScreen], hoisted out of the screen body so the dialog region can
 * read it inside its own recompose scope. Field names mirror the former
 * inline `remember` blocks one-to-one.
 */
class RunUltrafixState {
    var showUltrafixImportDialog by mutableStateOf(false)
    var showUltrafixConfirmDialog by mutableStateOf(false)

    /** Marks the in-flight generation as an ultrafix run so the completion handler can record the right mode. */
    var pendingUltrafix by mutableStateOf(false)
    var isUltrafixPreparing by mutableStateOf(false)

    // UltraFix runs with its own steps/denoise (defaults 10 / 0.4), persisted
    // globally and kept independent of the main generation params so tweaking
    // them in the UltraFix dialog never touches the prompt-page settings.
    var ultrafixSteps by mutableFloatStateOf(GenerationDefaults.GLOBAL.ultrafixSteps)

    /** Denoise is controlled as a step count (0..min(10, ultrafixSteps)); the backend strength is derived from it at run time. */
    var ultrafixDenoiseSteps by mutableIntStateOf(GenerationDefaults.GLOBAL.ultrafixDenoiseSteps)

    /** On (default): UltraFix runs on neutral quality tags instead of the prompt-page prompt. Off: uses the prompt-page prompt (legacy behavior). */
    var ultrafixQualityDenoise by mutableStateOf(GenerationDefaults.GLOBAL.ultrafixQualityDenoise)

    var ultrafixSaveJob: Job? by mutableStateOf(null)
}
