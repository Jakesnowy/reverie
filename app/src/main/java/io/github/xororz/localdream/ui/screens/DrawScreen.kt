package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.compose.foundation.Canvas as ComposeCanvas

enum class CloneMode { OFF, SELECTING, DRAWING }

data class StrokePath(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val alpha: Float,
    val isEraser: Boolean = false,
    val blurRadius: Float = 0f,
    val cloneShader: BitmapShader? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    originalBitmap: Bitmap,
    onDrawingSaved: (Bitmap) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("brush_prefs", Context.MODE_PRIVATE) }
    var brushColor by remember { mutableStateOf(Color(prefs.getInt("brush_color", Color.Red.toArgb()))) }
    var brushSize by remember { mutableFloatStateOf(prefs.getFloat("brush_size", 60f)) }
    var brushAlpha by remember { mutableFloatStateOf(prefs.getFloat("brush_alpha", 1f)) }
    var brushBlur by remember { mutableFloatStateOf(prefs.getFloat("brush_blur", 10f)) }
    var eraserSize by remember { mutableFloatStateOf(prefs.getFloat("eraser_size", 60f)) }
    var eraserAlpha by remember { mutableFloatStateOf(prefs.getFloat("eraser_alpha", 1f)) }
    var eraserBlur by remember { mutableFloatStateOf(prefs.getFloat("eraser_blur", 0f)) }

    val paths = remember { mutableStateListOf<StrokePath>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var pathUpdateTrigger by remember { mutableStateOf(0) }
    var imageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var isEraserMode by remember { mutableStateOf(false) }
    var isPickerMode by remember { mutableStateOf(false) }
    var isZoomMode by remember { mutableStateOf(false) }
    var isTouchpadMode by remember { mutableStateOf(true) }

    var cloneMode by remember { mutableStateOf(CloneMode.OFF) }
    var activeCloneShader by remember { mutableStateOf<BitmapShader?>(null) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var isAdjustingBrush by remember { mutableStateOf(false) }
    val showPreview = isTouchpadMode || (cloneMode == CloneMode.SELECTING)
    var previewOffset by remember { mutableStateOf(Offset.Zero) }
    var isOffsetInitialized by remember { mutableStateOf(false) }

    val currentSize = if (isEraserMode) eraserSize else brushSize
    val currentAlpha = if (isEraserMode) eraserAlpha else brushAlpha
    val currentBlur = if (isEraserMode) eraserBlur else brushBlur
    var forceNewLayerNextDraw by remember { mutableStateOf(false) }

    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drawing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (paths.isEmpty()) {
                                onNavigateBack(); return@Button
                            }
                            val drawingBitmap = createBitmap(originalBitmap.width, originalBitmap.height)
                            val drawingCanvas = Canvas(drawingBitmap)
                            val paint = Paint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                strokeJoin = Paint.Join.ROUND
                                strokeCap = Paint.Cap.ROUND
                            }
                            val coords = imageCoordinates
                            if (coords != null && coords.size.width > 0 && coords.size.height > 0) {
                                val containerWidth = coords.size.width.toFloat()
                                val containerHeight = coords.size.height.toFloat()
                                val bitmapWidth = originalBitmap.width.toFloat()
                                val bitmapHeight = originalBitmap.height.toFloat()
                                val scale = minOf(containerWidth / bitmapWidth, containerHeight / bitmapHeight)
                                val offsetX = (containerWidth - (bitmapWidth * scale)) / 2f
                                val offsetY = (containerHeight - (bitmapHeight * scale)) / 2f

                                drawingCanvas.save()
                                drawingCanvas.scale(1f / scale, 1f / scale)
                                drawingCanvas.translate(-offsetX, -offsetY)
                                paths.forEach { strokePath ->
                                    paint.strokeWidth = strokePath.strokeWidth
                                    paint.maskFilter = if (strokePath.blurRadius > 0f) {
                                        BlurMaskFilter(strokePath.blurRadius, BlurMaskFilter.Blur.NORMAL)
                                    } else null

                                    if (strokePath.isEraser) {
                                        paint.shader = null
                                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                                        paint.setARGB((strokePath.alpha * 255).toInt(), 0, 0, 0)
                                    } else {
                                        paint.xfermode = null
                                        if (strokePath.cloneShader != null) {
                                            paint.shader = strokePath.cloneShader
                                            paint.alpha = (strokePath.alpha * 255).toInt()
                                        } else {
                                            paint.shader = null
                                            val c = strokePath.color
                                            paint.setARGB(
                                                (strokePath.alpha * 255).toInt(),
                                                (c.red * 255).toInt(),
                                                (c.green * 255).toInt(),
                                                (c.blue * 255).toInt(),
                                            )
                                        }
                                    }
                                    drawingCanvas.drawPath(strokePath.path.asAndroidPath(), paint)
                                }
                                drawingCanvas.restore()
                                val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                Canvas(resultBitmap).drawBitmap(drawingBitmap, 0f, 0f, null)
                                drawingBitmap.recycle()
                                onDrawingSaved(resultBitmap)
                            }
                        },
                    ) { Text("Done") }
                },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Column {
                    BrushToolsComponent(
                        currentColor = brushColor,
                        onColorClick = { showColorPickerDialog = true },
                        currentSize = currentSize,
                        onSizeChange = { value ->
                            if (isEraserMode) {
                                eraserSize = value; prefs.edit { putFloat("eraser_size", value) }
                            } else {
                                brushSize = value; prefs.edit { putFloat("brush_size", value) }
                            }
                            isAdjustingBrush = true
                        },
                        currentAlpha = currentAlpha,
                        onAlphaChange = { value ->
                            if (isEraserMode) {
                                eraserAlpha = value; prefs.edit { putFloat("eraser_alpha", value) }
                            } else {
                                brushAlpha = value; prefs.edit { putFloat("brush_alpha", value) }
                            }
                            isAdjustingBrush = true
                        },
                        currentBlur = currentBlur,
                        onBlurChange = { value ->
                            if (isEraserMode) {
                                eraserBlur = value; prefs.edit { putFloat("eraser_blur", value) }
                            } else {
                                brushBlur = value; prefs.edit { putFloat("brush_blur", value) }
                            }
                            isAdjustingBrush = true
                        },
                        isEraserMode = isEraserMode,
                        onEraserModeChange = {
                            isEraserMode = it; if (it) {
                            isPickerMode = false; isZoomMode = false; cloneMode = CloneMode.OFF
                        }
                        },
                        isPickerMode = isPickerMode,
                        onPickerModeChange = {
                            isPickerMode = it; if (it) {
                            isEraserMode = false; isZoomMode = false; cloneMode = CloneMode.OFF
                        }
                        },
                        isZoomMode = isZoomMode,
                        onZoomModeChange = {
                            isZoomMode = it; if (it) {
                            isEraserMode = false; isPickerMode = false; cloneMode = CloneMode.OFF
                        }
                        },
                        isTouchpadMode = isTouchpadMode,
                        onTouchpadModeChange = { isTouchpadMode = it },
                        onUndo = { if (paths.isNotEmpty()) paths.removeAt(paths.lastIndex) },
                        onClearAll = {
                            showClearDialog = true
                        },
                        onAdjustmentStateChange = { isDragging -> isAdjustingBrush = isDragging },
                        onNewLayerClick = {
                            forceNewLayerNextDraw = true; Toast.makeText(
                            context,
                            "New layer",
                            Toast.LENGTH_SHORT,
                        ).show()
                        },
                        cloneMode = cloneMode,
                        onCloneModeClick = {
                            when (cloneMode) {
                                CloneMode.OFF -> {
                                    cloneMode = CloneMode.SELECTING
                                    isEraserMode = false; isPickerMode = false; isZoomMode = false
                                }

                                CloneMode.SELECTING -> {
                                    imageCoordinates?.let { coords ->
                                        val scale = minOf(
                                            coords.size.width / originalBitmap.width.toFloat(),
                                            coords.size.height / originalBitmap.height.toFloat(),
                                        )
                                        val offsetX = (coords.size.width - (originalBitmap.width * scale)) / 2f
                                        val offsetY = (coords.size.height - (originalBitmap.height * scale)) / 2f
                                        val bX = ((previewOffset.x - offsetX) / scale).toInt()
                                            .coerceIn(0, originalBitmap.width - 1)
                                        val bY = ((previewOffset.y - offsetY) / scale).toInt()
                                            .coerceIn(0, originalBitmap.height - 1)

                                        val radius =
                                            (currentSize / scale / 2f).toInt().coerceIn(8, originalBitmap.width)
                                        val startX = (bX - radius).coerceIn(0, originalBitmap.width - 1)
                                        val startY = (bY - radius).coerceIn(0, originalBitmap.height - 1)
                                        val endX = (bX + radius).coerceIn(0, originalBitmap.width)
                                        val endY = (bY + radius).coerceIn(0, originalBitmap.height)
                                        val w = endX - startX
                                        val h = endY - startY

                                        if (w > 0 && h > 0) {
                                            val crop = Bitmap.createBitmap(originalBitmap, startX, startY, w, h)
                                            val shader =
                                                BitmapShader(crop, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                                            val matrix = Matrix()
                                            matrix.postScale(scale, scale)
                                            matrix.postTranslate(startX * scale + offsetX, startY * scale + offsetY)
                                            shader.setLocalMatrix(matrix)
                                            activeCloneShader = shader
                                            cloneMode = CloneMode.DRAWING
                                        }
                                    }
                                }

                                CloneMode.DRAWING -> {
                                    cloneMode = CloneMode.OFF; activeCloneShader = null
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        imageCoordinates = it
                        if (!isOffsetInitialized && it.size.width > 0) {
                            previewOffset = Offset(it.size.width / 2f, it.size.height / 2f)
                            isOffsetInitialized = true
                        }
                    }
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = zoomOffset.x,
                        translationY = zoomOffset.y,
                    )
                    .pointerInput(isZoomMode, isPickerMode, isTouchpadMode, cloneMode) {
                        if (isZoomMode) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 8f)
                                zoomOffset = if (zoomScale > 1f) zoomOffset + pan else Offset.Zero
                            }
                        } else {
                            awaitEachGesture {
                                var isMultiTouch = false
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!isPickerMode && cloneMode != CloneMode.SELECTING) {
                                    val startPoint = if (isTouchpadMode) previewOffset else down.position
                                    currentPath = Path().apply { moveTo(startPoint.x, startPoint.y) }
                                    pathUpdateTrigger++
                                }
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1) {
                                        isMultiTouch = true
                                        currentPath = null
                                        pathUpdateTrigger++
                                    }
                                    val pointerChange = event.changes.first()
                                    val dragAmount = pointerChange.position - pointerChange.previousPosition
                                    if (isTouchpadMode || cloneMode == CloneMode.SELECTING) {
                                        previewOffset += dragAmount
                                    } else {
                                        previewOffset = pointerChange.position
                                    }

                                    if (isPickerMode) {
                                        imageCoordinates?.let { coords ->
                                            val scale = minOf(
                                                coords.size.width / originalBitmap.width.toFloat(),
                                                coords.size.height / originalBitmap.height.toFloat(),
                                            )
                                            val offsetX = (coords.size.width - (originalBitmap.width * scale)) / 2f
                                            val offsetY = (coords.size.height - (originalBitmap.height * scale)) / 2f
                                            val targetPoint =
                                                if (isTouchpadMode) previewOffset else pointerChange.position
                                            val bitmapX = ((targetPoint.x - offsetX) / scale).toInt()
                                                .coerceIn(0, originalBitmap.width - 1)
                                            val bitmapY = ((targetPoint.y - offsetY) / scale).toInt()
                                                .coerceIn(0, originalBitmap.height - 1)

                                            // Усредняем цвета в радиусе вокруг точки
                                            val sampleRadius = 5 // Радиус выборки в пикселях
                                            var totalRed = 0f
                                            var totalGreen = 0f
                                            var totalBlue = 0f
                                            var totalAlpha = 0f
                                            var count = 0

                                            for (dx in -sampleRadius..sampleRadius) {
                                                for (dy in -sampleRadius..sampleRadius) {
                                                    val x = (bitmapX + dx).coerceIn(0, originalBitmap.width - 1)
                                                    val y = (bitmapY + dy).coerceIn(0, originalBitmap.height - 1)
                                                    val pixel = originalBitmap[x, y]
                                                    totalRed += android.graphics.Color.red(pixel)
                                                    totalGreen += android.graphics.Color.green(pixel)
                                                    totalBlue += android.graphics.Color.blue(pixel)
                                                    totalAlpha += android.graphics.Color.alpha(pixel)
                                                    count++
                                                }
                                            }

                                            val avgRed = (totalRed / count).toInt()
                                            val avgGreen = (totalGreen / count).toInt()
                                            val avgBlue = (totalBlue / count).toInt()
                                            val avgAlpha = (totalAlpha / count).toInt()

                                            val pickedColor =
                                                Color(android.graphics.Color.argb(avgAlpha, avgRed, avgGreen, avgBlue))
                                            brushColor = pickedColor
                                            prefs.edit { putInt("brush_color", pickedColor.toArgb()) }
                                        }
                                    } else if (!isMultiTouch && cloneMode != CloneMode.SELECTING) {
                                        val currentPoint = if (isTouchpadMode) previewOffset else pointerChange.position
                                        currentPath?.lineTo(currentPoint.x, currentPoint.y)
                                        pathUpdateTrigger++
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                if (isPickerMode) {
                                    isPickerMode = false
                                    Toast.makeText(context, "Color changed", Toast.LENGTH_SHORT).show()
                                } else if (!isMultiTouch && cloneMode != CloneMode.SELECTING) {
                                    currentPath?.let { finishedPath ->
                                        val snapColor = if (isEraserMode) Color.Transparent else brushColor
                                        val snapWidth = if (isEraserMode) eraserSize else brushSize
                                        val snapAlpha = if (isEraserMode) eraserAlpha else brushAlpha
                                        val snapBlur = if (isEraserMode) eraserBlur else brushBlur
                                        val snapShader =
                                            if (cloneMode == CloneMode.DRAWING && !isEraserMode) activeCloneShader else null
                                        val lastStroke = paths.lastOrNull()

                                        if (!isEraserMode && !forceNewLayerNextDraw && lastStroke != null &&
                                            !lastStroke.isEraser && lastStroke.strokeWidth == snapWidth &&
                                            lastStroke.alpha == snapAlpha && lastStroke.blurRadius == snapBlur &&
                                            lastStroke.color == snapColor && lastStroke.cloneShader == snapShader
                                        ) {
                                            lastStroke.path.addPath(finishedPath)
                                        } else {
                                            paths.add(
                                                StrokePath(
                                                    finishedPath,
                                                    snapColor,
                                                    snapWidth,
                                                    snapAlpha,
                                                    isEraserMode,
                                                    snapBlur,
                                                    snapShader,
                                                ),
                                            )
                                            forceNewLayerNextDraw = false
                                        }
                                    }
                                }
                                currentPath = null
                                pathUpdateTrigger++
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = originalBitmap.asImageBitmap(),
                    contentDescription = "Original Background",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                ComposeCanvas(
                    modifier = Modifier.fillMaxSize(),
                    onDraw = {
                        pathUpdateTrigger
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(size.toRect(), androidx.compose.ui.graphics.Paint())
                            val nativePaint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                            paths.forEach { strokePath ->
                                nativePaint.strokeWidth = strokePath.strokeWidth
                                nativePaint.alpha = (strokePath.alpha * 255).toInt()
                                nativePaint.maskFilter = if (strokePath.blurRadius > 0f) BlurMaskFilter(
                                    strokePath.blurRadius,
                                    BlurMaskFilter.Blur.NORMAL,
                                ) else null

                                if (strokePath.isEraser) {
                                    nativePaint.shader = null
                                    nativePaint.color = Color.Black.toArgb()
                                    nativePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                                    nativePaint.setARGB((strokePath.alpha * 255).toInt(), 0, 0, 0)
                                } else {
                                    nativePaint.xfermode = null
                                    if (strokePath.cloneShader != null) {
                                        nativePaint.shader = strokePath.cloneShader
                                        nativePaint.alpha = (strokePath.alpha * 255).toInt()
                                    } else {
                                        nativePaint.shader = null
                                        nativePaint.color = strokePath.color.toArgb()
                                        nativePaint.alpha = (strokePath.alpha * 255).toInt()
                                    }
                                }
                                canvas.nativeCanvas.drawPath(strokePath.path.asAndroidPath(), nativePaint)
                            }
                            currentPath?.let {
                                nativePaint.strokeWidth = currentSize
                                nativePaint.alpha = (currentAlpha * 255).toInt()
                                nativePaint.maskFilter = if (currentBlur > 0f) BlurMaskFilter(
                                    currentBlur,
                                    BlurMaskFilter.Blur.NORMAL,
                                ) else null

                                if (isEraserMode) {
                                    nativePaint.shader = null
                                    nativePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                                    nativePaint.setARGB((currentAlpha * 255).toInt(), 0, 0, 0)
                                } else {
                                    nativePaint.xfermode = null
                                    if (cloneMode == CloneMode.DRAWING) {
                                        nativePaint.shader = activeCloneShader
                                        nativePaint.alpha = (currentAlpha * 255).toInt()
                                    } else {
                                        nativePaint.shader = null
                                        nativePaint.color = brushColor.toArgb()
                                        nativePaint.alpha = (currentAlpha * 255).toInt()
                                    }
                                }
                                canvas.nativeCanvas.drawPath(it.asAndroidPath(), nativePaint)
                            }
                            canvas.restore()
                        }
                    },
                )
            }
            if (showPreview) {
                val density = LocalDensity.current
                val brushSizeInDp = with(density) { currentSize.toDp() }
                val isSelecting = cloneMode == CloneMode.SELECTING
                val baseColor = if (isEraserMode) Color.White else if (isSelecting) Color.Cyan else brushColor
                val finalAlpha = if (isEraserMode) 0.4f else if (isSelecting) 0.6f else currentAlpha

                Box(
                    modifier = Modifier
                        .size(brushSizeInDp)
                        .offset(
                            x = with(density) { (previewOffset.x - currentSize / 2f).toDp() },
                            y = with(density) { (previewOffset.y - currentSize / 2f).toDp() },
                        )
                        .border(
                            width = if (isSelecting) 2.5.dp else 1.5.dp,
                            color = if (isEraserMode) Color.Red else if (isSelecting) Color.Cyan else Color.White,
                            shape = if (isSelecting) RectangleShape else CircleShape,
                        ),
                ) {
                    if (cloneMode != CloneMode.SELECTING) {
                        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                            val radius = size.minDimension / 2f
                            if (currentBlur > 0f) {
                                val ratio = (currentBlur / currentSize).coerceIn(0f, 0.5f)
                                val startRadiusRatio = (1f - ratio * 2f).coerceIn(0f, 1f)
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0.0f to baseColor.copy(alpha = finalAlpha),
                                            startRadiusRatio to baseColor.copy(alpha = finalAlpha),
                                            1.0f to Color.Transparent,
                                        ),
                                        center = center,
                                        radius = radius,
                                    ),
                                    radius = radius,
                                    center = center,
                                )
                            } else {
                                drawCircle(color = baseColor.copy(alpha = finalAlpha), radius = radius, center = center)
                            }
                        }
                    }
                }
            }
            if (showColorPickerDialog) {
                SimpleColorPickerDialog(
                    initialColor = brushColor,
                    onColorSelected = { pickedColor ->
                        brushColor = pickedColor; prefs.edit {
                        putInt(
                            "brush_color",
                            pickedColor.toArgb(),
                        )
                    }
                    },
                    onDismiss = { showColorPickerDialog = false },
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(text = "Clear canvas") },
            text = { Text(text = "Are you sure you want to delete all drawings?") },
            confirmButton = {
                TextButton(
                    onClick = {

                        paths.clear()
                        zoomScale = 1f
                        zoomOffset = Offset.Zero
                        imageCoordinates?.let { previewOffset = Offset(it.size.width / 2f, it.size.height / 2f) }

                        // Close dialog
                        showClearDialog = false
                    },
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun BrushToolsComponent(
    currentColor: Color,
    onColorClick: () -> Unit,
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    currentAlpha: Float,
    onAlphaChange: (Float) -> Unit,
    currentBlur: Float,
    onBlurChange: (Float) -> Unit,
    isEraserMode: Boolean,
    onEraserModeChange: (Boolean) -> Unit,
    isPickerMode: Boolean,
    onPickerModeChange: (Boolean) -> Unit,
    isZoomMode: Boolean,
    onZoomModeChange: (Boolean) -> Unit,
    isTouchpadMode: Boolean,
    onTouchpadModeChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onClearAll: () -> Unit,
    onAdjustmentStateChange: (Boolean) -> Unit,
    onNewLayerClick: () -> Unit,
    cloneMode: CloneMode,
    onCloneModeClick: () -> Unit,
) {
    val sizeInteractionSource = remember { MutableInteractionSource() }
    val alphaInteractionSource = remember { MutableInteractionSource() }
    val blurInteractionSource = remember { MutableInteractionSource() }
    val isSizeDragged by sizeInteractionSource.collectIsDraggedAsState()
    val isAlphaDragged by alphaInteractionSource.collectIsDraggedAsState()
    val isBlurDragged by blurInteractionSource.collectIsDraggedAsState()

    LaunchedEffect(isSizeDragged, isAlphaDragged, isBlurDragged) {
        onAdjustmentStateChange(isSizeDragged || isAlphaDragged || isBlurDragged)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentSize,
                    onValueChange = onSizeChange,
                    valueRange = 5f..200f,
                    interactionSource = sizeInteractionSource,
                )
                Text(text = "Size: ${currentSize.toInt()}", style = MaterialTheme.typography.bodySmall)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentAlpha,
                    onValueChange = onAlphaChange,
                    valueRange = 0.1f..1f,
                    interactionSource = alphaInteractionSource,
                )
                Text(text = "Transp.: ${(currentAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentBlur,
                    onValueChange = onBlurChange,
                    valueRange = 0f..100f,
                    interactionSource = blurInteractionSource,
                )
                Text(text = "Blur: ${currentBlur.toInt()}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            //horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Current color / Select color
            FilledIconButton(
                onClick = onColorClick,
                enabled = !isEraserMode && cloneMode == CloneMode.OFF,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = currentColor, // Цвет, когда кнопка активна
                    disabledContainerColor = currentColor.copy(alpha = 0.38f),
                ),
            ) {}

            // Pipette
            FilledIconToggleButton(checked = isPickerMode, onCheckedChange = onPickerModeChange) {
                Icon(imageVector = Icons.Default.Colorize, contentDescription = "Pipette")
            }

            // Stamp / Clone
            FilledIconToggleButton(checked = (cloneMode != CloneMode.OFF), onCheckedChange = { onCloneModeClick() }) {
                Icon(
                    imageVector = when (cloneMode) {
                        CloneMode.OFF -> Icons.Default.Approval
                        CloneMode.SELECTING -> Icons.Default.SelectAll
                        CloneMode.DRAWING -> Icons.Default.Brush
                    },
                    contentDescription = when (cloneMode) {
                        CloneMode.OFF -> "Stamp disabled"
                        CloneMode.SELECTING -> "Selecting"
                        CloneMode.DRAWING -> "Drawing"
                    },
                )
            }

            // Eraser
            FilledIconToggleButton(checked = isEraserMode, onCheckedChange = onEraserModeChange) {
                Icon(imageVector = Icons.Default.ContentCut, contentDescription = "Eraser")
            }

            // New layer
            FilledIconToggleButton(checked = false, onCheckedChange = { onNewLayerClick() }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "New layer")
            }

            // Undo
            FilledIconToggleButton(checked = false, onCheckedChange = { onUndo() }) {
                Icon(imageVector = Icons.AutoMirrored.Default.Undo, contentDescription = "Undo")
            }

        }


        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            //horizontalArrangement = Arrangement.Spa,
            verticalAlignment = Alignment.CenterVertically,
        ) {

            // Zoom
            FilledIconToggleButton(checked = isZoomMode, onCheckedChange = onZoomModeChange) {
                Icon(imageVector = Icons.Default.Search, contentDescription = "Zoom")
            }

            // Touch mode
            FilledIconToggleButton(
                checked = !isTouchpadMode,
                onCheckedChange = { isChecked -> onTouchpadModeChange(!isChecked) },
            ) {
                Icon(imageVector = Icons.Default.TouchApp, contentDescription = "Touch mode")
            }

            // Clear all
            FilledIconToggleButton(checked = false, onCheckedChange = { onClearAll() }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear all")
            }

        }
    }
}

@Composable
fun SimpleColorPickerDialog(initialColor: Color, onColorSelected: (Color) -> Unit, onDismiss: () -> Unit) {
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var value by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
    }
    val currentSelectedColor = remember(hue, saturation, value) { Color.hsv(hue, saturation, value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите цвет") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(
                            brush = Brush.verticalGradient(colors = listOf(Color.White, Color.Black)),
                            shape = MaterialTheme.shapes.medium,
                        )
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val x = change.position.x.coerceIn(0f, size.width.toFloat())
                                val y = change.position.y.coerceIn(0f, size.height.toFloat())
                                saturation = x / size.width.toFloat()
                                value = 1f - (y / size.height.toFloat())
                            }
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.hsv(hue, 1f, 1f),
                                    ),
                                ),
                                shape = MaterialTheme.shapes.medium,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = (saturation * 200).dp - 8.dp, y = ((1f - value) * 200).dp - 8.dp)
                            .size(16.dp)
                            .background(Color.Transparent, shape = CircleShape)
                            .border(
                                width = 2.dp,
                                color = if (value < 0.5f) Color.White else Color.Black,
                                shape = CircleShape,
                            ),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val hueColors = remember {
                    listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            brush = Brush.horizontalGradient(colors = hueColors),
                            shape = MaterialTheme.shapes.small,
                        ),
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Результат:")
                    Box(
                        modifier = Modifier
                            .size(60.dp, 30.dp)
                            .background(currentSelectedColor, shape = MaterialTheme.shapes.small)
                            .border(1.dp, Color.Gray, MaterialTheme.shapes.small),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onColorSelected(currentSelectedColor); onDismiss() }) { Text("Select") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
