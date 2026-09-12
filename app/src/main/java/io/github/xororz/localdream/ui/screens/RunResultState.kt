package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Result-page UI state for [ModelRunScreen], hoisted out of the screen body:
 * the currently displayed bitmap, its generation parameters, and the
 * history-linking bookkeeping (which history entry is on screen and which
 * entries may be stitched into inpaint sources). Field names mirror the
 * former inline `remember` block one-to-one.
 */
class RunResultState(initialModelId: String) {
    var currentBitmap by mutableStateOf<Bitmap?>(null)
    var intermediateBitmap by mutableStateOf<Bitmap?>(null)
    var imageVersion by mutableIntStateOf(0)
    var generationParams by mutableStateOf<GenerationParameters?>(null)
    var generationParamsModelId by mutableStateOf(initialModelId)
    var currentDisplayedHistoryId by mutableStateOf<Long?>(null)
    var stitchableHistoryIds by mutableStateOf<Set<Long>>(emptySet())
}
