package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.RemoteRepository
import io.github.xororz.localdream.ui.components.GenerationParamsDialog
import io.github.xororz.localdream.ui.components.OverlayIconButton
import io.github.xororz.localdream.ui.components.ReproduceParametersDialog
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.utils.ParamShareField
import io.github.xororz.localdream.utils.saveImage
import io.github.xororz.localdream.utils.saveImageFromFile
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * History dialog region of [ModelRunScreen]: the filter sheet, the detail
 * preview overlay (with upscale/ultrafix/save actions), the parameters
 * dialog, the reproduce-parameters dialog, the delete confirmation and the
 * batch save/delete dialogs with their progress overlay.
 *
 * All state is read from the holders *inside* this composable so flag flips
 * don't recompose the screen orchestrator. Operations that belong to the
 * screen (img2img hand-off, parameter persistence, pager scrolling) are
 * passed in as callbacks.
 */
@Composable
internal fun RunHistoryDialogs(
    historyState: RunHistoryState,
    resultState: RunResultState,
    runState: RunGenerationState,
    setupState: RunSetupState,
    shareState: RunShareImportState,
    ultrafixState: RunUltrafixState,
    upscaleState: RunUpscaleState,
    model: Model?,
    isRemote: Boolean,
    remoteRepository: RemoteRepository,
    historyManager: HistoryManager,
    knownModelIds: List<String>,
    knownSchedulers: List<String>,
    knownSizes: List<String>,
    useImg2img: Boolean,
    promptField: PromptFieldController,
    negativePromptField: PromptFieldController,
    scope: CoroutineScope,
    onSendBitmapToImg2img: (Bitmap) -> Unit,
    onSaveUltrafixParams: () -> Unit,
    onSaveAllFields: () -> Unit,
    onClearImg2imgState: () -> Unit,
    onScrollToPromptPage: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // String resources hoisted to composable scope (lint: LocalContextGetResourceValueCall).
    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDeleted = stringResource(R.string.deleted)
    val msgDeleteFailedMessage = stringResource(R.string.delete_failed_message)
    val msgImageLoadFailed = stringResource(R.string.image_load_failed)
    val msgSavedCountWithFailed = stringResource(R.string.saved_count_with_failed)
    val msgDeletedCountWithFailed = stringResource(R.string.deleted_count_with_failed)

    if (historyState.showHistoryFilterSheet) {
        HistoryFilterSheet(
            initialFilter = historyState.historyFilter,
            knownModelIds = knownModelIds,
            knownSchedulers = knownSchedulers,
            knownSizes = knownSizes,
            onApply = {
                historyState.historyFilter = it
                historyState.showHistoryFilterSheet = false
            },
            onDismiss = { historyState.showHistoryFilterSheet = false },
        )
    }

    // History detail dialog
    if (historyState.showHistoryDetailDialog && historyState.selectedHistoryItem != null) {
        val detailImagePath = historyState.selectedHistoryItem?.imageFile?.absolutePath
        val historyBitmap by produceState<Bitmap?>(null, detailImagePath) {
            value = withContext(Dispatchers.IO) {
                detailImagePath?.let { BitmapFactory.decodeFile(it) }
            }
        }
        val dismissDetail = {
            historyState.showHistoryDetailDialog = false
            historyState.selectedHistoryItem = null
        }
        // Same gating as the result page, applied to the previewed item. The
        // click path loads the item into the result-page state (exactly like
        // tapping a thumbnail) and reuses the regular upscale/ultrafix flows.
        val detailItem = historyState.selectedHistoryItem
        val detailIdle =
            !runState.isRunning && !upscaleState.isUpscaling && !ultrafixState.isUltrafixPreparing
        val detailCanUpscale = historyBitmap != null && detailItem != null &&
            detailIdle && model?.runOnCpu == false &&
            (!isRemote || remoteRepository.upscalerPaths.isNotEmpty()) &&
            maxOf(detailItem.params.width, detailItem.params.height) <= 1024
        val detailCanUltrafix = historyBitmap != null && detailItem != null &&
            detailIdle && useImg2img && model?.isSdxl == true && runState.cfg == 1f &&
            maxOf(detailItem.params.width, detailItem.params.height) > 1024 &&
            minOf(detailItem.params.width, detailItem.params.height) >=
            maxOf(setupState.currentWidth, setupState.currentHeight, 512)
        val loadDetailIntoResult = fun(): Boolean {
            val bmp = historyBitmap ?: return false
            val item = detailItem ?: return false
            resultState.currentBitmap = bmp
            resultState.generationParams = item.params
            resultState.generationParamsModelId = item.modelId
            resultState.currentDisplayedHistoryId = item.id
            resultState.imageVersion++
            dismissDetail()
            return true
        }
        ZoomableImageOverlay(
            bitmap = historyBitmap,
            onDismiss = dismissDetail,
            topEndContent = {
                OverlayIconButton(
                    icon = Icons.Default.Info,
                    contentDescription = "View parameters",
                    onClick = {
                        if (historyState.selectedHistoryItem != null) {
                            historyState.showHistoryParametersDialog = true
                        }
                    },
                )
                OverlayIconButton(
                    icon = if (detailItem?.favorite == true) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    contentDescription = "toggle favorite",
                    onClick = {
                        val item = historyState.selectedHistoryItem
                        if (item != null) {
                            // Keep the dialog's own copy in sync; the grid
                            // refreshes through the observed flow.
                            historyState.selectedHistoryItem = item.copy(favorite = !item.favorite)
                            scope.launch(Dispatchers.IO) {
                                historyManager.setFavorite(item.id, !item.favorite)
                            }
                        }
                    },
                )
                if (detailCanUpscale) {
                    OverlayIconButton(
                        icon = Icons.Default.AutoFixHigh,
                        contentDescription = "upscale image",
                        onClick = {
                            if (loadDetailIntoResult()) {
                                upscaleState.showUpscalerDialog = true
                            }
                        },
                    )
                }
                if (detailCanUltrafix) {
                    OverlayIconButton(
                        icon = Icons.Default.AutoAwesome,
                        contentDescription = "ultrafix image",
                        onClick = {
                            if (loadDetailIntoResult()) {
                                ultrafixState.showUltrafixConfirmDialog = true
                            }
                        },
                    )
                }
                OverlayIconButton(
                    icon = Icons.Default.Save,
                    contentDescription = "Save to gallery",
                    onClick = {
                        val bitmapToSave = historyBitmap
                        if (bitmapToSave != null) {
                            scope.launch {
                                saveImage(
                                    context = context,
                                    bitmap = bitmapToSave,
                                    onSuccess = {
                                        Toast.makeText(
                                            context,
                                            msgImageSaved,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onError = { errorMsg ->
                                        Toast.makeText(
                                            context,
                                            errorMsg,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        )
    }

    // History parameters dialog
    if (historyState.showHistoryParametersDialog && historyState.selectedHistoryItem != null) {
        val params = historyState.selectedHistoryItem!!.params
        GenerationParamsDialog(
            title = stringResource(R.string.generation_params_title),
            params = params,
            modelId = historyState.selectedHistoryItem?.modelId ?: "",
            displayMode = historyState.selectedHistoryItem?.mode,
            showImg2imgButton = useImg2img,
            onShare = {
                shareState.shareSourceParams = params
                shareState.shareSourceModelId = historyState.selectedHistoryItem?.modelId
            },
            onSendToImg2img = {
                val item = historyState.selectedHistoryItem
                if (item != null) {
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(item.imageFile.absolutePath)
                        }
                        if (bmp != null) {
                            onSendBitmapToImg2img(bmp)
                            historyState.showHistoryParametersDialog = false
                            historyState.showHistoryDetailDialog = false
                            historyState.selectedHistoryItem = null
                        } else {
                            Toast.makeText(
                                context,
                                msgImageLoadFailed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onReproduce = {
                historyState.pendingReproduceParams = historyState.selectedHistoryItem!!.params
                historyState.showHistoryParametersDialog = false
                historyState.showReproduceParamsDialog = true
            },
            onDismiss = { historyState.showHistoryParametersDialog = false },
        )
    }

    // Reproduce parameters dialog
    if (historyState.showReproduceParamsDialog && historyState.pendingReproduceParams != null) {
        val params = historyState.pendingReproduceParams!!
        ReproduceParametersDialog(
            params = params,
            onApply = { selectedFields ->
                if (ParamShareField.PROMPT in selectedFields) {
                    promptField.replaceText(params.prompt)
                }
                if (ParamShareField.NEGATIVE_PROMPT in selectedFields) {
                    negativePromptField.replaceText(params.negativePrompt)
                }
                // An UltraFix image was produced by the tiled img2img repair pass,
                // whose steps/denoise live in their own UltraFix variables. Route
                // the reproduced values there (denoise is stored as a strength but
                // edited as a step count) instead of the prompt-page settings.
                val isUltrafixParams = params.mode == GenerationMode.ULTRAFIX
                if (ParamShareField.STEPS in selectedFields) {
                    if (isUltrafixParams) {
                        ultrafixState.ultrafixSteps = params.steps.toFloat()
                        val maxDenoiseSteps = minOf(
                            GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX,
                            ultrafixState.ultrafixSteps.roundToInt(),
                        )
                        ultrafixState.ultrafixDenoiseSteps =
                            ultrafixState.ultrafixDenoiseSteps.coerceIn(0, maxDenoiseSteps)
                    } else {
                        runState.steps = params.steps.toFloat()
                    }
                }
                if (ParamShareField.CFG in selectedFields) {
                    runState.cfg = params.cfg
                }
                if (ParamShareField.SEED in selectedFields) {
                    runState.seed = params.seed?.toString() ?: ""
                }
                if (ParamShareField.SCHEDULER in selectedFields) {
                    runState.scheduler = params.scheduler
                }
                if (ParamShareField.DENOISE_STRENGTH in selectedFields) {
                    if (isUltrafixParams) {
                        // Invert strength = (steps - 0.5) / total -> steps.
                        val total = params.steps
                        val maxDenoiseSteps = minOf(
                            GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX,
                            total,
                        )
                        ultrafixState.ultrafixDenoiseSteps =
                            (params.denoiseStrength * total + 0.5f).roundToInt()
                                .coerceIn(0, maxDenoiseSteps)
                    } else {
                        runState.denoiseStrength = params.denoiseStrength
                    }
                }
                if (isUltrafixParams) {
                    onSaveUltrafixParams()
                }
                if (model?.usesFixedCanvas == true && useImg2img) {
                    val newRatio = inferAspectRatioString(params.width, params.height)
                    if (newRatio != runState.aspectRatio) {
                        runState.aspectRatio = newRatio
                        onClearImg2imgState()
                    }
                }
                onSaveAllFields()

                historyState.showReproduceParamsDialog = false
                historyState.pendingReproduceParams = null
                historyState.showHistoryDetailDialog = false
                historyState.selectedHistoryItem = null
                onScrollToPromptPage()
            },
            onDismiss = {
                historyState.showReproduceParamsDialog = false
                historyState.pendingReproduceParams = null
                historyState.showHistoryDetailDialog = false
                historyState.selectedHistoryItem = null
            },
        )
    }

    // Delete confirmation dialog
    if (historyState.showDeleteHistoryDialog && historyState.selectedHistoryItem != null) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.delete_image),
            text = stringResource(R.string.delete_image_confirm),
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            onConfirm = {
                scope.launch {
                    val success = historyManager.deleteHistoryItem(
                        item = historyState.selectedHistoryItem!!,
                    )
                    if (success) {
                        historyState.showDeleteHistoryDialog = false
                        historyState.showHistoryDetailDialog = false
                        historyState.selectedHistoryItem = null
                        Toast.makeText(
                            context,
                            msgDeleted,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            msgDeleteFailedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { historyState.showDeleteHistoryDialog = false },
        )
    }

    // Batch save confirmation dialog
    if (historyState.showBatchSaveDialog && historyState.selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_save),
            text = pluralStringResource(
                R.plurals.batch_save_confirm,
                historyState.selectedIds.size,
                historyState.selectedIds.size,
            ),
            confirmText = stringResource(R.string.yes),
            onConfirm = {
                val ids = historyState.selectedIds.toList()
                historyState.showBatchSaveDialog = false
                if (ids.isNotEmpty()) {
                    historyState.batchSaveTotal = ids.size
                    historyState.batchSaveCurrent = 0
                    historyState.batchSaveFailed = 0
                    historyState.isBatchSaving = true
                    scope.launch(Dispatchers.IO) {
                        // Resolve ids to items; any gone-missing counts as failed.
                        val items = historyManager.getItems(ids)
                        val missing = ids.size - items.size
                        if (missing > 0) {
                            withContext(Dispatchers.Main) {
                                historyState.batchSaveFailed += missing
                                historyState.batchSaveCurrent += missing
                            }
                        }
                        items.forEach { item ->
                            var success = false
                            if (item.imageFile.exists()) {
                                saveImageFromFile(
                                    context = context,
                                    sourceFile = item.imageFile,
                                    onSuccess = { success = true },
                                    onError = { },
                                )
                            }
                            withContext(Dispatchers.Main) {
                                historyState.batchSaveCurrent += 1
                                if (!success) historyState.batchSaveFailed += 1
                            }
                        }
                        withContext(Dispatchers.Main) {
                            val total = historyState.batchSaveTotal
                            val failed = historyState.batchSaveFailed
                            val saved = total - failed
                            val message = if (failed == 0) {
                                resources.getQuantityString(
                                    R.plurals.saved_count,
                                    saved,
                                    saved,
                                )
                            } else {
                                msgSavedCountWithFailed.format(saved, failed)
                            }
                            Toast.makeText(
                                context,
                                message,
                                Toast.LENGTH_SHORT,
                            ).show()
                            historyState.isBatchSaving = false
                            historyState.selectedIds.clear()
                            historyState.isSelectionMode = false
                        }
                    }
                }
            },
            onDismiss = { historyState.showBatchSaveDialog = false },
        )
    }

    // Batch save progress dialog (modal — blocks other interactions)
    if (historyState.isBatchSaving) {
        BatchSaveProgressDialog(current = historyState.batchSaveCurrent, total = historyState.batchSaveTotal)
    }

    // Batch delete confirmation dialog
    if (historyState.showBatchDeleteDialog && historyState.selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_delete),
            text = pluralStringResource(
                R.plurals.batch_delete_confirm,
                historyState.selectedIds.size,
                historyState.selectedIds.size,
            ),
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            onConfirm = {
                val ids = historyState.selectedIds.toList()
                historyState.showBatchDeleteDialog = false
                scope.launch {
                    val itemsToDelete = historyManager.getItems(ids)
                    val successCount = historyManager.deleteHistoryItems(itemsToDelete)
                    val failCount = ids.size - successCount

                    historyState.selectedIds.clear()
                    historyState.isSelectionMode = false

                    val message = if (failCount == 0) {
                        resources.getQuantityString(
                            R.plurals.deleted_count,
                            successCount,
                            successCount,
                        )
                    } else {
                        msgDeletedCountWithFailed.format(successCount, failCount)
                    }
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { historyState.showBatchDeleteDialog = false },
        )
    }
}
