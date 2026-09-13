package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Img2Img / Inpaint source-editing state for [ModelRunScreen], hoisted out of
 * the screen body: the crop/draw/inpaint screen toggles, the working bitmaps
 * (cropped source, inpaint mask, transparent drawing overlay), and the
 * snapshots taken at generation time so a later save can stitch the patch
 * back into its original context. Field names mirror the former inline
 * `remember` blocks one-to-one.
 */
class RunImg2ImgState {
    var showCropScreen by mutableStateOf(false)
    var imageUriForCrop by mutableStateOf<Uri?>(null)
    var croppedBitmap by mutableStateOf<Bitmap?>(null)

    var showInpaintScreen by mutableStateOf(false)
    var maskBitmap by mutableStateOf<Bitmap?>(null)
    var isInpaintMode by mutableStateOf(false)
    var savedPathHistory by mutableStateOf<List<PathData>?>(null)
    var cropRect by mutableStateOf<AndroidRect?>(null)

    var showDrawScreen by mutableStateOf(false)

    /** Transparent canvas edits, retained for full-image inpaint exports. */
    var drawingOverlayBitmap by mutableStateOf<Bitmap?>(null)

    /**
     * True only when the img2img source came from the gallery picker (not a
     * result/history bitmap, whose uri is a synthetic tmp.txt base64 path).
     */
    var hasOriginalImageForStitch by mutableStateOf(false)

    // Snapshot of the source state at generation time; a later save consults
    // these so the patch is only stitched when the displayed bitmap really is
    // that generation's result.
    var snapshotIsInpaintMode by mutableStateOf(false)
    var snapshotSelectedImageUri by mutableStateOf<Uri?>(null)
    var snapshotCropRect by mutableStateOf<AndroidRect?>(null)
    var snapshotMaskBitmap by mutableStateOf<Bitmap?>(null)
    var snapshotDrawingOverlayBitmap by mutableStateOf<Bitmap?>(null)
    var snapshotHasOriginalImage by mutableStateOf(false)
}
