package io.github.xororz.localdream.ui.screens

import android.content.SharedPreferences
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.UpscalerModel
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay

/**
 * Upscaler dialog region of [ModelRunScreen]: the upscaler picker flow and
 * the "upscaling" blocking overlay.
 *
 * State is read from [upscaleState] *inside* this composable so flag flips
 * (dialog open/close, upscale running) don't recompose the screen
 * orchestrator; the upscale execution itself stays with the screen and is
 * passed in as [onUpscaleConfirmed].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RunUpscaleDialogs(
    upscaleState: RunUpscaleState,
    modelId: String,
    upscalerRepository: UpscalerRepository,
    upscalerPreferences: SharedPreferences,
    upscalersOverride: List<UpscalerModel>?,
    onUpscaleConfirmed: (UpscalerModel, Int) -> Unit,
) {
    if (upscaleState.showUpscalerDialog) {
        UpscalerPickerFlow(
            modelId = modelId,
            upscalerRepository = upscalerRepository,
            upscalerPreferences = upscalerPreferences,
            upscalersOverride = upscalersOverride,
            onDismiss = { upscaleState.showUpscalerDialog = false },
            onUpscalerConfirmed = { selectedUpscaler, selectedScale ->
                upscaleState.showUpscalerDialog = false
                onUpscaleConfirmed(selectedUpscaler, selectedScale)
            },
        )
    }

    BlockingProgressOverlay(visible = upscaleState.isUpscaling) {
        ContainedLoadingIndicator()
        Text(
            text = stringResource(R.string.upscaling_image),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
