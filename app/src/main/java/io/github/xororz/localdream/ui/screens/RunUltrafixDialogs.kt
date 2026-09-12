@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay
import kotlin.math.roundToInt

/**
 * UltraFix dialog region of [ModelRunScreen]: the local-image import dialog,
 * the parameter confirmation dialog, and the "preparing" blocking overlay.
 *
 * All state is read from the holders *inside* this composable so flag flips
 * (dialog open/close, preparing) don't recompose the screen orchestrator.
 * Heavy operations stay with the screen and are passed in as callbacks.
 */
@Composable
internal fun RunUltrafixDialogs(
    ultrafixState: RunUltrafixState,
    resultState: RunResultState,
    setupState: RunSetupState,
    promptField: PromptFieldController,
    negativePromptField: PromptFieldController,
    saveUltrafixParams: () -> Unit,
    startUltrafix: () -> Unit,
    applyUltrafixImport: (Bitmap) -> Unit,
) {
    // Import-and-resize a local image for UltraFix. A confirmed image that
    // already satisfies the UltraFix window goes straight to the parameter
    // dialog; a smaller one just lands on the result page, where the visible
    // upscale button is the natural next step.
    if (ultrafixState.showUltrafixImportDialog) {
        UltrafixImportDialog(
            tileSize = maxOf(setupState.currentWidth, setupState.currentHeight, 512),
            onDismiss = { ultrafixState.showUltrafixImportDialog = false },
            onConfirm = { bitmap ->
                applyUltrafixImport(bitmap)
                ultrafixState.showUltrafixImportDialog = false
                val tile = maxOf(setupState.currentWidth, setupState.currentHeight, 512)
                if (maxOf(bitmap.width, bitmap.height) > 1024 &&
                    minOf(bitmap.width, bitmap.height) >= tile
                ) {
                    ultrafixState.showUltrafixConfirmDialog = true
                }
            },
        )
    }

    // Ultrafix parameter confirmation.
    if (ultrafixState.showUltrafixConfirmDialog) {
        AlertDialog(
            onDismissRequest = { ultrafixState.showUltrafixConfirmDialog = false },
            title = { Text(stringResource(R.string.ultrafix)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ultrafix_confirm_hint))
                    Text(
                        stringResource(
                            R.string.ultrafix_source_info,
                            resultState.currentBitmap?.width ?: 0,
                            resultState.currentBitmap?.height ?: 0,
                            maxOf(setupState.currentWidth, setupState.currentHeight),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        stringResource(R.string.ultrafix_longpress_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Independent UltraFix steps (1..20). Lowering steps also
                    // tightens the denoise-step cap below.
                    val denoiseStepsMax =
                        minOf(
                            GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX,
                            ultrafixState.ultrafixSteps.roundToInt(),
                        )
                    Text(
                        stringResource(R.string.steps, ultrafixState.ultrafixSteps.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = ultrafixState.ultrafixSteps,
                        onValueChange = {
                            ultrafixState.ultrafixSteps = it
                            val newMax =
                                minOf(GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX, it.roundToInt())
                            if (ultrafixState.ultrafixDenoiseSteps > newMax) {
                                ultrafixState.ultrafixDenoiseSteps = newMax
                            }
                            saveUltrafixParams()
                        },
                        valueRange = GenerationDefaults.ULTRAFIX_STEPS_MIN..GenerationDefaults.ULTRAFIX_STEPS_MAX,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Independent UltraFix denoise steps (0..min(10, steps)):
                    // how many of the total steps actually denoise.
                    Text(
                        stringResource(
                            R.string.ultrafix_denoise_steps_label,
                            ultrafixState.ultrafixDenoiseSteps,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = ultrafixState.ultrafixDenoiseSteps.toFloat(),
                        onValueChange = {
                            ultrafixState.ultrafixDenoiseSteps = it.roundToInt()
                            saveUltrafixParams()
                        },
                        valueRange = 0f..denoiseStepsMax.toFloat(),
                        steps = (denoiseStepsMax - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Quality-denoise toggle: run UltraFix on neutral quality
                    // tags instead of the prompt-page prompt. On by default.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                R.string.ultrafix_quality_denoise_desc,
                                GenerationDefaults.ULTRAFIX_QUALITY_PROMPT,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = ultrafixState.ultrafixQualityDenoise,
                            onCheckedChange = {
                                ultrafixState.ultrafixQualityDenoise = it
                                saveUltrafixParams()
                            },
                        )
                    }

                    TextButton(
                        onClick = {
                            ultrafixState.ultrafixSteps = GenerationDefaults.GLOBAL.ultrafixSteps
                            ultrafixState.ultrafixDenoiseSteps =
                                GenerationDefaults.GLOBAL.ultrafixDenoiseSteps
                            ultrafixState.ultrafixQualityDenoise =
                                GenerationDefaults.GLOBAL.ultrafixQualityDenoise
                            saveUltrafixParams()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ultrafix_restore_defaults))
                    }

                    Text(
                        stringResource(R.string.ultrafix_other_params_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The prompts are only a reminder of what will run; show a
                    // truncated preview instead of the full text. With the
                    // toggle on, UltraFix runs on the quality tags, so preview
                    // those instead of the prompt-page text.
                    fun preview(text: String) = if (text.length > 80) text.take(80) + "..." else text
                    val ultrafixPreviewPrompt =
                        if (ultrafixState.ultrafixQualityDenoise) {
                            GenerationDefaults.ULTRAFIX_QUALITY_PROMPT
                        } else {
                            promptField.text
                        }
                    if (ultrafixPreviewPrompt.isNotBlank()) {
                        Text(
                            stringResource(R.string.image_prompt),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            preview(ultrafixPreviewPrompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (negativePromptField.text.isNotBlank()) {
                        Text(
                            stringResource(R.string.negative_prompt),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            preview(negativePromptField.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ultrafixState.showUltrafixConfirmDialog = false
                    startUltrafix()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { ultrafixState.showUltrafixConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    BlockingProgressOverlay(visible = ultrafixState.isUltrafixPreparing) {
        ContainedLoadingIndicator()
        Text(
            text = stringResource(R.string.ultrafix_preparing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
