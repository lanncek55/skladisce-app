package si.skladisce.app.scanner

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max

data class HudOverlayState(
    val primaryRect: NormRect? = null,
    val primaryLabel: String = "",
    val secondaryRects: List<NormRect> = emptyList(),
    val frameWidth: Int = 1,
    val frameHeight: Int = 1,
    val lastLatencyMs: Long = 0L
)

sealed interface ScanEvent {
    data class BoxOcr(
        val result: WarehouseScanResult,
        val uprightW: Int,
        val uprightH: Int,
        val elapsedMs: Long
    ) : ScanEvent

    data class ProductBarcode(
        val value: String,
        val format: Int,
        val rect: NormRect?,
        val uprightW: Int,
        val uprightH: Int,
        val elapsedMs: Long
    ) : ScanEvent

    data class ShelfQr(val info: ShelfQrInfo) : ScanEvent
    data class MixedBox(val boxId: String) : ScanEvent
}

class CameraOcrAnalyzer(
    private val isAutoScanEnabled: () -> Boolean,
    private val onHudUpdated: (HudOverlayState) -> Unit,
    private val onScanEvent: (ScanEvent) -> Unit
) : ImageAnalysis.Analyzer {

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_UPC_A
            )
            .build()
    )

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var lastAnalyzedAtMs = 0L

    @Volatile
    private var lastDetectedHudAtMs = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        if (!isAutoScanEnabled()) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAtMs < 110L) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMs = now
        val startNs = SystemClock.elapsedRealtimeNanos()

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val uprightW = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
            val uprightH = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height

            val image0 = InputImage.fromMediaImage(mediaImage, rotation)

            // 1. Strojno branje črtnih kod in QR kod (Zero-Copy)
            val barcodes = runCatching { Tasks.await(barcodeScanner.process(image0)) }.getOrNull().orEmpty()
            for (b in barcodes) {
                val raw = b.rawValue?.trim().orEmpty()
                if (raw.isEmpty()) continue
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L

                if (WarehouseOcrParser.isShelfQRCode(raw)) {
                    onScanEvent(ScanEvent.ShelfQr(WarehouseOcrParser.parseShelfQR(raw)))
                    return
                }
                val mixedId = WarehouseOcrParser.parseMixedBoxBarcode(raw)
                if (mixedId != null) {
                    onScanEvent(ScanEvent.MixedBox(mixedId))
                    return
                }

                val bBox = b.boundingBox
                val normRect = if (bBox != null && uprightW > 0 && uprightH > 0) {
                    NormRect(
                        left = (bBox.left / uprightW.toFloat()).coerceIn(0f, 1f),
                        top = (bBox.top / uprightH.toFloat()).coerceIn(0f, 1f),
                        right = (bBox.right / uprightW.toFloat()).coerceIn(0f, 1f),
                        bottom = (bBox.bottom / uprightH.toFloat()).coerceIn(0f, 1f)
                    )
                } else null

                if (normRect != null) {
                    lastDetectedHudAtMs = SystemClock.elapsedRealtime()
                    onHudUpdated(
                        HudOverlayState(
                            primaryRect = normRect,
                            primaryLabel = "⚡ $raw (${elapsedMs}ms)",
                            frameWidth = uprightW,
                            frameHeight = uprightH,
                            lastLatencyMs = elapsedMs
                        )
                    )
                }
                onScanEvent(ScanEvent.ProductBarcode(raw, b.format, normRect, uprightW, uprightH, elapsedMs))
                return
            }

            // 2. ML Kit OCR na 0°
            val text0 = runCatching { Tasks.await(textRecognizer.process(image0)) }.getOrNull()
            val res0 = text0?.let {
                WarehouseOcrParser.findBestBoxCandidate(
                    blocks = toBlocks(it),
                    imgW = uprightW,
                    imgH = uprightH,
                    fullText = it.text,
                    mode = "ocr_0"
                )
            }
            if (res0 != null && res0.bestSku.isNotEmpty()) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
                emitOcrResult(res0, uprightW, uprightH, elapsedMs)
                return
            }

            // 3. ML Kit OCR na 180° (obrnjene škatle na paletah)
            val image180 = InputImage.fromMediaImage(mediaImage, (rotation + 180) % 360)
            val text180 = runCatching { Tasks.await(textRecognizer.process(image180)) }.getOrNull()
            val res180 = text180?.let {
                WarehouseOcrParser.findBestBoxCandidate(
                    blocks = toBlocks(it),
                    imgW = uprightW,
                    imgH = uprightH,
                    fullText = it.text,
                    mode = "ocr_180"
                )
            }
            if (res180 != null && res180.bestSku.isNotEmpty()) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
                val unrotated = res180.copy(
                    boxRect = res180.boxRect?.let { r -> WarehouseOcrParser.unrotateNormRect(r, 180) },
                    otherBoxes = res180.otherBoxes.map { ob ->
                        ob.copy(boxRect = ob.boxRect?.let { r -> WarehouseOcrParser.unrotateNormRect(r, 180) })
                    }
                )
                emitOcrResult(unrotated, uprightW, uprightH, elapsedMs)
                return
            }

            // 4. ML Kit OCR na 90° (pokončne škatle)
            val image90 = InputImage.fromMediaImage(mediaImage, (rotation + 90) % 360)
            val text90 = runCatching { Tasks.await(textRecognizer.process(image90)) }.getOrNull()
            val res90 = text90?.let {
                WarehouseOcrParser.findBestBoxCandidate(
                    blocks = toBlocks(it),
                    imgW = uprightH,
                    imgH = uprightW,
                    fullText = it.text,
                    mode = "ocr_90"
                )
            }
            if (res90 != null && res90.bestSku.isNotEmpty()) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L
                val unrotated = res90.copy(
                    boxRect = res90.boxRect?.let { r -> WarehouseOcrParser.unrotateNormRect(r, 90) },
                    otherBoxes = res90.otherBoxes.map { ob ->
                        ob.copy(boxRect = ob.boxRect?.let { r -> WarehouseOcrParser.unrotateNormRect(r, 90) })
                    }
                )
                emitOcrResult(unrotated, uprightW, uprightH, elapsedMs)
                return
            }

            // Kratka histereza pred skrivanjem HUD okvirja, da okvirček ne utripa
            if (SystemClock.elapsedRealtime() - lastDetectedHudAtMs > 450L) {
                onHudUpdated(HudOverlayState(frameWidth = uprightW, frameHeight = uprightH))
            }
        } catch (_: Throwable) {
            // Ignoriraj prehodne napake kadra
        } finally {
            imageProxy.close()
        }
    }

    private fun emitOcrResult(res: WarehouseScanResult, uprightW: Int, uprightH: Int, elapsedMs: Long) {
        lastDetectedHudAtMs = SystemClock.elapsedRealtime()
        onHudUpdated(
            HudOverlayState(
                primaryRect = res.boxRect,
                primaryLabel = "⚡ ${res.bestSku} • Box ${res.boxCurrent}/${res.boxTotal} • ${elapsedMs}ms",
                secondaryRects = res.otherBoxes.mapNotNull { it.boxRect },
                frameWidth = uprightW,
                frameHeight = uprightH,
                lastLatencyMs = elapsedMs
            )
        )
        onScanEvent(ScanEvent.BoxOcr(res, uprightW, uprightH, elapsedMs))
    }

    fun close() {
        runCatching { barcodeScanner.close() }
        runCatching { textRecognizer.close() }
    }

    companion object {
        fun toBlocks(visionText: Text): List<OcrTextBlock> {
            return visionText.textBlocks.mapNotNull { blk ->
                val b = blk.boundingBox ?: return@mapNotNull null
                OcrTextBlock(blk.text, b.left, b.top, b.right, b.bottom)
            }
        }

        fun analyzeStaticBitmap(
            bitmap: Bitmap,
            preferReceipt: Boolean = false
        ): Pair<WarehouseScanResult?, ReceiptScanResult?> {
            val barcodeClient = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_UPC_A
                    )
                    .build()
            )
            val textClient = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            try {
                // Poskusi z do 1600px za maksimalno natančnost na statičnih fotografijah / računih
                val maxDim = max(bitmap.width, bitmap.height)
                val scaled = if (maxDim > 1600) {
                    val ratio = 1600f / maxDim
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }

                val img0 = InputImage.fromBitmap(scaled, 0)
                val text0 = runCatching { Tasks.await(textClient.process(img0)) }.getOrNull()

                if (preferReceipt && text0 != null) {
                    WarehouseOcrParser.parseReceipt(text0.text)?.let { return null to it }
                }

                if (text0 != null) {
                    WarehouseOcrParser.parseReceipt(text0.text)?.let {
                        if (it.items.size >= 2 || preferReceipt) return null to it
                    }

                    val cand0 = WarehouseOcrParser.findBestBoxCandidate(
                        toBlocks(text0),
                        scaled.width,
                        scaled.height,
                        text0.text,
                        "static_0"
                    )
                    if (cand0 != null && cand0.bestSku.isNotEmpty()) {
                        return cand0 to null
                    }
                }

                // Fallback na črtno kodo
                val barcodes = runCatching { Tasks.await(barcodeClient.process(img0)) }.getOrNull().orEmpty()
                if (barcodes.isNotEmpty()) {
                    val raw = barcodes.first().rawValue?.trim().orEmpty()
                    if (raw.isNotEmpty()) {
                        return WarehouseScanResult(
                            primarySku = if (raw.length == 7 && WarehouseOcrParser.isPrimaryValid(raw)) raw else "",
                            barcode = raw,
                            name = "Artikel $raw",
                            mode = "static_barcode"
                        ) to null
                    }
                }

                // Rotacija 180° in 90° fallback za obrnjene slike iz galerije
                for (rot in listOf(180, 90, 270)) {
                    val imgR = InputImage.fromBitmap(scaled, rot)
                    val textR = runCatching { Tasks.await(textClient.process(imgR)) }.getOrNull() ?: continue
                    val candR = WarehouseOcrParser.findBestBoxCandidate(
                        toBlocks(textR),
                        if (rot == 90 || rot == 270) scaled.height else scaled.width,
                        if (rot == 90 || rot == 270) scaled.width else scaled.height,
                        textR.text,
                        "static_$rot"
                    )
                    if (candR != null && candR.bestSku.isNotEmpty()) {
                        return candR to null
                    }
                }
                return null to null
            } finally {
                runCatching { barcodeClient.close() }
                runCatching { textClient.close() }
            }
        }
    }
}
