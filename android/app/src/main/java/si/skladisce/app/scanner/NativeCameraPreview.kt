package si.skladisce.app.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class NativeCameraController {
    internal var camera: Camera? = null
    internal var imageCapture: ImageCapture? = null
    internal val captureExecutor = Executors.newSingleThreadExecutor()

    var isTorchOn by mutableStateOf(false)
        private set
    var zoomFactor by mutableFloatStateOf(1f)
        private set

    fun toggleTorch() {
        val cam = camera ?: return
        isTorchOn = !isTorchOn
        cam.cameraControl.enableTorch(isTorchOn)
    }

    fun cycleZoom() {
        val levels = listOf(1f, 1.5f, 2f, 3f)
        val idx = levels.indexOfFirst { kotlin.math.abs(it - zoomFactor) < 0.05f }
        val next = levels[(idx + 1) % levels.size]
        zoomFactor = next
        camera?.cameraControl?.setZoomRatio(next)
    }

    fun captureHighResBitmap(
        onCaptured: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val ic = imageCapture ?: run {
            onError(IllegalStateException("Kamera še ni pripravljena."))
            return
        }
        ic.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bmp = imageProxyToBitmap(image)
                        onCaptured(bmp)
                    } catch (t: Throwable) {
                        onError(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Napaka pri dekodiranju slike.")
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return raw
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated != raw) raw.recycle()
        return rotated
    }
}

@Composable
fun NativeCameraPreview(
    modifier: Modifier = Modifier,
    controller: NativeCameraController,
    isAutoScanEnabled: Boolean,
    showIdleReticle: Boolean = true,
    onScanEvent: (ScanEvent) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hudState by remember { mutableStateOf(HudOverlayState()) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }
    var focusTapTimestamp by remember { mutableLongStateOf(0L) }

    val autoScanRef = remember { mutableStateOf(isAutoScanEnabled) }
    autoScanRef.value = isAutoScanEnabled

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    val analyzer = remember {
        CameraOcrAnalyzer(
            isAutoScanEnabled = { autoScanRef.value },
            onHudUpdated = { newState -> hudState = newState },
            onScanEvent = onScanEvent
        )
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also {
                        it.setAnalyzer(cameraExecutor, analyzer)
                    }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
                controller.camera = cam
                controller.imageCapture = imageCapture

                previewView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        focusTapOffset = Offset(event.x, event.y)
                        focusTapTimestamp = System.currentTimeMillis()
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                        true
                    } else false
                }
            } catch (_: Throwable) {
                // Kamera ni na voljo
            }
        }, mainExecutor)

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            controller.camera = null
            controller.imageCapture = null
            analyzer.close()
            cameraExecutor.shutdownNow()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserY"
    )
    val textMeasurer = rememberTextMeasurer()

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // 60fps nativni HUD overlay nad PreviewView z natančno FILL_CENTER projekcijo
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewW = size.width
            val viewH = size.height
            if (viewW <= 0f || viewH <= 0f) return@Canvas

            val imgW = hudState.frameWidth.coerceAtLeast(1).toFloat()
            val imgH = hudState.frameHeight.coerceAtLeast(1).toFloat()
            val scale = max(viewW / imgW, viewH / imgH)
            val renderW = imgW * scale
            val renderH = imgH * scale
            val offX = (viewW - renderW) / 2f
            val offY = (viewH - renderH) / 2f

            // Sekundarne zaznane škatle v kadru (oranžni črtkani okviri)
            for (secRect in hudState.secondaryRects) {
                val l = (offX + secRect.left * renderW).coerceIn(4f, viewW - 24f)
                val t = (offY + secRect.top * renderH).coerceIn(4f, viewH - 24f)
                val w = (secRect.width * renderW).coerceIn(24f, viewW - l)
                val h = (secRect.height * renderH).coerceIn(20f, viewH - t)
                drawRoundRect(
                    color = Color(0xFFF59E0B),
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )
                )
            }

            val pRect = hudState.primaryRect
            if (pRect != null) {
                val padX = pRect.width * 0.08f
                val padY = pRect.height * 0.14f
                val nl = (pRect.left - padX).coerceIn(0f, 1f)
                val nt = (pRect.top - padY).coerceIn(0f, 1f)
                val nw = (pRect.width + padX * 2f).coerceIn(0.05f, 1f - nl)
                val nh = (pRect.height + padY * 2f).coerceIn(0.04f, 1f - nt)

                val left = (offX + nl * renderW).coerceIn(6f, viewW - 40f)
                val top = (offY + nt * renderH).coerceIn(6f, viewH - 40f)
                val boxW = (nw * renderW).coerceIn(40f, viewW - left - 6f)
                val boxH = (nh * renderH).coerceIn(32f, viewH - top - 6f)

                drawRoundRect(
                    color = Color(0x2610B981),
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(14f, 14f)
                )
                drawRoundRect(
                    color = Color(0xFF10B981),
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = 4.5f)
                )

                // Kotni poudarki
                val cLen = 22f
                val cornerColor = Color(0xFF34D399)
                drawLine(cornerColor, Offset(left, top + cLen), Offset(left, top), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left, top), Offset(left + cLen, top), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left + boxW - cLen, top), Offset(left + boxW, top), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left + boxW, top), Offset(left + boxW, top + cLen), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left, top + boxH - cLen), Offset(left, top + boxH), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left, top + boxH), Offset(left + cLen, top + boxH), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left + boxW - cLen, top + boxH), Offset(left + boxW, top + boxH), 7f, StrokeCap.Round)
                drawLine(cornerColor, Offset(left + boxW, top + boxH - cLen), Offset(left + boxW, top + boxH), 7f, StrokeCap.Round)

                if (hudState.primaryLabel.isNotBlank()) {
                    val measured = textMeasurer.measure(
                        text = hudState.primaryLabel,
                        style = TextStyle(color = Color(0xFF042F2E), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    )
                    val pillW = measured.size.width + 20f
                    val pillH = measured.size.height + 10f
                    val pillY = (top - pillH - 6f).coerceAtLeast(6f)
                    val pillX = left.coerceAtMost((viewW - pillW - 6f).coerceAtLeast(6f))
                    drawRoundRect(
                        color = Color(0xFF34D399),
                        topLeft = Offset(pillX, pillY),
                        size = Size(pillW, pillH),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawText(measured, topLeft = Offset(pillX + 10f, pillY + 5f))
                }
            } else if (showIdleReticle) {
                // Vizir v pripravljenosti
                val guideW = viewW * 0.78f
                val guideH = viewH * 0.56f
                val gx = (viewW - guideW) / 2f
                val gy = (viewH - guideH) / 2f
                drawRoundRect(
                    color = Color(0x4038BDF8),
                    topLeft = Offset(gx, gy),
                    size = Size(guideW, guideH),
                    cornerRadius = CornerRadius(18f, 18f),
                    style = Stroke(width = 2.5f)
                )
                val cLen = 28f
                val cColor = Color(0xFF38BDF8)
                drawLine(cColor, Offset(gx, gy + cLen), Offset(gx, gy), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx, gy), Offset(gx + cLen, gy), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx + guideW - cLen, gy), Offset(gx + guideW, gy), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx + guideW, gy), Offset(gx + guideW, gy + cLen), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx, gy + guideH - cLen), Offset(gx, gy + guideH), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx, gy + guideH), Offset(gx + cLen, gy + guideH), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx + guideW - cLen, gy + guideH), Offset(gx + guideW, gy + guideH), 6f, StrokeCap.Round)
                drawLine(cColor, Offset(gx + guideW, gy + guideH - cLen), Offset(gx + guideW, gy + guideH), 6f, StrokeCap.Round)

                if (isAutoScanEnabled) {
                    val ly = gy + 12f + (guideH - 24f) * laserProgress
                    drawLine(
                        color = Color(0xAA38BDF8),
                        start = Offset(gx + 14f, ly),
                        end = Offset(gx + guideW - 14f, ly),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Krog za Tap-to-Focus
            val tapPos = focusTapOffset
            if (tapPos != null && System.currentTimeMillis() - focusTapTimestamp < 1200L) {
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = 36f,
                    center = tapPos,
                    style = Stroke(width = 3.5f)
                )
            }
        }
    }
}
