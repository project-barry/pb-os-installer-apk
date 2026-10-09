package org.projectbarry.pbosinstaller.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {
    /** The code the app draws scans back to the same address. */
    @Test fun roundTrip() {
        val url = "https://discord.gg/euPurKCWc4"
        val modules = QrCode.modules(url)
        val scale = 8
        val size = modules.size * scale
        val pixels = IntArray(size * size) { i ->
            if (modules[(i / size) / scale][(i % size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }
}
