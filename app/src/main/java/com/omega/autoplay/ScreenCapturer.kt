package com.omega.autoplay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import kotlin.math.max

class ScreenCapturer(private val proj: MediaProjection) {
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun start(w: Int, h: Int, dpi: Int) {
        val s = 640f / max(w, h)
        val cw = (w * s).toInt().coerceAtLeast(64)
        val ch = (h * s).toInt().coerceAtLeast(64)
        reader?.close()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        val d = vd
        if (d == null) {
            vd = proj.createVirtualDisplay("ap", cw, ch, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        } else {
            d.resize(cw, ch, dpi)
            d.surface = r.surface
        }
    }

    fun latest(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(p.buffer)
            return if (rowPad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } finally {
            img.close()
        }
    }

    fun stop() {
        reader?.close()
        reader = null
    }

    fun release() {
        stop()
        vd?.release(); vd = null
        proj.stop()
    }
}
