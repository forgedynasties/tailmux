package com.cd4li.tmuxcore

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

object QrGen {
    /** Encode [text] as a QR PNG and return it as a data: URI for an <img>. */
    fun dataUri(text: String, size: Int = 480): String {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dark = 0xFF16161E.toInt()
        val light = 0xFFFFFFFF.toInt()
        for (y in 0 until size) for (x in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) dark else light)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
