package io.github.xororz.localdream.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.BackgroundGenerationService.GenerationState
import io.github.xororz.localdream.ui.theme.Motion

/**
 * The generate button of [ModelRunScreen]'s prompt page, including the
 * loading indicator swap while a run is in flight.
 *
 * Collects the background service's generation state *inside* this scope so
 * progress ticks only recompose the button, not the whole prompt page (or
 * the screen orchestrator). The start/batch logic stays with the screen as
 * [onGenerateClick]; the busy gating mirrors the former inline `enabled`
 * expression exactly.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RunGenerateButton(
    runState: RunGenerationState,
    upscaleState: RunUpscaleState,
    ultrafixState: RunUltrafixState,
    modifier: Modifier = Modifier,
    onGenerateClick: () -> Unit,
) {
    val serviceState by BackgroundGenerationService.generationState.collectAsState()
    Button(
        onClick = onGenerateClick,
        enabled = serviceState !is GenerationState.Progress &&
            !runState.isRunning && !upscaleState.isUpscaling && !ultrafixState.isUltrafixPreparing,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
    ) {
        AnimatedContent(
            targetState = serviceState is GenerationState.Progress || upscaleState.isUpscaling,
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(Motion.DurationShort)) + scaleIn(
                        initialScale = 0.8f,
                        animationSpec = tween(Motion.DurationShort),
                    )
                    )
                    .togetherWith(
                        fadeOut(animationSpec = tween(Motion.DurationShort)) + scaleOut(
                            targetScale = 0.8f,
                            animationSpec = tween(Motion.DurationShort),
                        ),
                    )
            },
            label = "GenerateButtonContent",
        ) { isLoading ->
            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.generate_image))
            }
        }
    }
}
