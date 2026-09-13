package io.github.xororz.localdream.ui.screens

import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.BackgroundGenerationService.GenerationState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Generation-service state machine of [ModelRunScreen]: turns service state
 * emissions (progress / complete / error) into screen state updates, history
 * persistence, and the result-page hand-off.
 *
 * Collects the service flow *inside* this composable so progress ticks only
 * recompose this (invisible) scope, never the screen orchestrator. Launched
 * effect is keyed on the collected state, preserving the original
 * cancel-and-restart-per-emission semantics.
 */
@Composable
internal fun RunGenerationEffects(
    runState: RunGenerationState,
    resultState: RunResultState,
    setupState: RunSetupState,
    ultrafixState: RunUltrafixState,
    img2ImgState: RunImg2ImgState,
    model: Model?,
    modelId: String,
    historyManager: HistoryManager,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
) {
    val serviceState by BackgroundGenerationService.generationState.collectAsState()
    LaunchedEffect(serviceState) {

        when (val state = serviceState) {
            is GenerationState.Progress -> {
                if (runState.generationStartTime == null) {
                    runState.generationStartTime = System.currentTimeMillis()
                }
                runState.progress = state.progress
                runState.isRunning = true
                state.intermediateImage?.let { resultState.intermediateBitmap = it }
            }

            is GenerationState.Complete -> {
                resultState.intermediateBitmap = null
                withContext(Dispatchers.Main) {
                    Log.d("ModelRunScreen", "update bitmap")

                    state.seed?.let { runState.returnedSeed = it }
                    runState.progress = 0f

                    val genTime = runState.generationStartTime?.let { startTime ->
                        val endTime = System.currentTimeMillis()
                        val duration = endTime - startTime
                        when {
                            duration < 1000 -> "${duration}ms"

                            duration < 60000 -> String.format(Locale.US, "%.1fs", duration / 1000.0)

                            else -> String.format(
                                Locale.US,
                                "%dm%ds",
                                duration / 60000,
                                (duration % 60000) / 1000,
                            )
                        }
                    }

                    val wasUltrafix = ultrafixState.pendingUltrafix
                    ultrafixState.pendingUltrafix = false
                    val currentGenerationMode = when {
                        wasUltrafix -> GenerationMode.ULTRAFIX
                        img2ImgState.isInpaintMode -> GenerationMode.INPAINT
                        runState.selectedImageUri != null -> GenerationMode.IMG2IMG
                        else -> GenerationMode.TXT2IMG
                    }

                    val newParams = GenerationParameters(
                        steps = setupState.generationParamsTmp.steps,
                        cfg = setupState.generationParamsTmp.cfg,
                        seed = runState.returnedSeed,
                        prompt = setupState.generationParamsTmp.prompt,
                        negativePrompt = setupState.generationParamsTmp.negativePrompt,
                        generationTime = genTime,
                        width = if (model?.runOnCpu == true) setupState.generationParamsTmp.width else state.bitmap.width,
                        height = if (model?.runOnCpu == true) setupState.generationParamsTmp.height else state.bitmap.height,
                        runOnCpu = model?.runOnCpu ?: false,
                        denoiseStrength = setupState.generationParamsTmp.denoiseStrength,
                        useOpenCL = setupState.generationParamsTmp.useOpenCL,
                        scheduler = setupState.generationParamsTmp.scheduler,
                        mode = currentGenerationMode,
                        nsfwScore = state.nsfwScore,
                    )

                    // Save to disk and update history list. The saved item's id is
                    // forwarded to both the snapshot and the currently-displayed marker
                    // so handleSaveImage can later confirm the user is still looking at
                    // this generation (and not a different history thumbnail).
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedItem = historyManager.saveGeneratedImage(
                            modelId = modelId,
                            bitmap = state.bitmap,
                            params = newParams,
                            mode = currentGenerationMode,
                        )
                        if (savedItem != null) {
                            withContext(Dispatchers.Main) {
                                // An ultrafix result is a standalone image, not a
                                // stitchable inpaint patch.
                                if (!wasUltrafix) {
                                    resultState.stitchableHistoryIds = setOf(savedItem.id)
                                }
                                resultState.currentDisplayedHistoryId = savedItem.id
                            }
                        }
                    }

                    resultState.currentBitmap = state.bitmap
                    resultState.generationParams = newParams
                    resultState.generationParamsModelId = modelId
                    resultState.imageVersion += 1

                    if (!wasUltrafix) {
                        img2ImgState.snapshotIsInpaintMode = img2ImgState.isInpaintMode
                        img2ImgState.snapshotSelectedImageUri = runState.selectedImageUri
                        img2ImgState.snapshotCropRect = img2ImgState.cropRect
                        img2ImgState.snapshotMaskBitmap = if (img2ImgState.isInpaintMode) img2ImgState.maskBitmap else null
                        img2ImgState.snapshotDrawingOverlayBitmap = img2ImgState.drawingOverlayBitmap
                        img2ImgState.snapshotHasOriginalImage = img2ImgState.hasOriginalImageForStitch
                    }
                    // resultState.stitchableHistoryIds / resultState.currentDisplayedHistoryId are set once
                    // the DB save above resolves.
                    resultState.stitchableHistoryIds = emptySet()
                    resultState.currentDisplayedHistoryId = null

                    Log.d(
                        "ModelRunScreen",
                        "params update: ${resultState.generationParams?.steps}, ${resultState.generationParams?.cfg}",
                    )

                    runState.generationStartTime = null

                    if (pagerState.currentPage == 0 && !setupState.showAdvancedSettings) {
                        try {
                            pagerState.animateScrollToPage(1)
                        } finally {
                            BackgroundGenerationService.markBitmapConsumed()
                        }
                    } else {
                        BackgroundGenerationService.markBitmapConsumed()
                    }
                }
            }

            is GenerationState.Error -> {
                resultState.intermediateBitmap = null
                runState.errorMessage = state.message
                runState.isRunning = false
                runState.progress = 0f
                runState.generationStartTime = null
                ultrafixState.pendingUltrafix = false
            }

            else -> {
                runState.isRunning = false
                runState.progress = 0f
            }
        }
    }
}
