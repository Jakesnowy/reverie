package io.github.xororz.localdream.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.data.Resolution

/**
 * Remaining screen-setup state for [ModelRunScreen], hoisted out of the
 * screen body: confirmation/dialog flags, the scratch parameters object used
 * by the parameters dialog, the active canvas size with the resolutions the
 * current backend supports, and the backend-restart trigger. Field names
 * mirror the former inline `remember` blocks one-to-one.
 */
class RunSetupState(
    generationParamsTmp: GenerationParameters,
    initialGenerationSize: Int,
) {
    var showResetConfirmDialog by mutableStateOf(false)
    var showOpenCLWarningDialog by mutableStateOf(false)
    var showInterruptDialog by mutableStateOf(false)
    var showParametersDialog by mutableStateOf(false)
    var showReportDialog by mutableStateOf(false)
    var showAdvancedSettings by mutableStateOf(false)

    // Scratch parameters object edited by the parameters dialog before being
    // applied to the result state.
    var generationParamsTmp by mutableStateOf(generationParamsTmp)

    var currentWidth by mutableIntStateOf(initialGenerationSize)
    var currentHeight by mutableIntStateOf(initialGenerationSize)
    var availableResolutions by mutableStateOf<List<Resolution>>(emptyList())
    var showResolutionChangeDialog by mutableStateOf(false)
    var pendingResolution by mutableStateOf<Resolution?>(null)

    // Bumped to force the backend to restart (resolution/config changes).
    var backendRestartTrigger by mutableIntStateOf(0)

    var isPreviewMode by mutableStateOf(false)
}
