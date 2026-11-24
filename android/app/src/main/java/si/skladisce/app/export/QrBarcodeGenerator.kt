package si.skladisce.app.export

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrBarcodeGenerator {
    fun generateQrBitmap(content: String, sizePx: Int = 240): Bitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    fun generateCode128Bitmap(content: String, widthPx: Int = 360, heightPx: Int = 80): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 0)
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.CODE_128, widthPx, heightPx, hints)
            val pixels = IntArray(widthPx * heightPx)
            for (y in 0 until heightPx) {
                val offset = y * widthPx
                for (x in 0 until widthPx) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, widthPx, heightPx, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}
