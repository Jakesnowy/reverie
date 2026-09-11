package io.github.xororz.localdream.ui.screens

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.GenerationDefaults

/**
 * Generation-run UI state for [ModelRunScreen], hoisted out of the screen
 * body: the parameter values the user tunes (cfg/steps/seed/denoise/scheduler/
 * aspect ratio/batch), the img2img input selection, and the live run state
 * (isRunning/progress/error/backend health). Field names mirror the former
 * inline `remember` block one-to-one.
 */
class RunGenerationState {
    var cfg by mutableFloatStateOf(GenerationDefaults.GLOBAL.cfg)
    var steps by mutableFloatStateOf(GenerationDefaults.GLOBAL.steps)
    var seed by mutableStateOf(GenerationDefaults.GLOBAL.seed)
    var denoiseStrength by mutableFloatStateOf(GenerationDefaults.GLOBAL.denoiseStrength)
    var useOpenCL by mutableStateOf(false)
    var batchCounts by mutableIntStateOf(GenerationDefaults.GLOBAL.batchCounts)
    var scheduler by mutableStateOf(GenerationDefaults.GLOBAL.scheduler)
    var aspectRatio by mutableStateOf(GenerationDefaults.GLOBAL.aspectRatio)
    var showCustomAspectRatioDialog by mutableStateOf(false)
    var currentBatchIndex by mutableIntStateOf(0)

    // img2img input: picked source image plus the tmp.txt base64 encode flag.
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var base64EncodeDone by mutableStateOf(false)

    var returnedSeed by mutableStateOf<Long?>(null)
    var isRunning by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var errorMessage by mutableStateOf<String?>(null)
    var isCheckingBackend by mutableStateOf(true)

    // True only after a health check succeeded (and reset when a restart
    // begins). Gates tokenizer calls: "checking finished" alone also covers
    // the failure case, where firing tokenize requests is pointless.
    var backendReady by mutableStateOf(false)

    var generationStartTime by mutableStateOf<Long?>(null)
    var hasInitialized by mutableStateOf(false)
}
