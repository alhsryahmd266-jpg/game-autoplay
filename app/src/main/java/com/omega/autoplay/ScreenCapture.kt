package com.omega.autoplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.roundToInt

private fun posOf(b: Buffer, n: Int) {
    b.position(n)
}

/** Mirrors the display into a small ImageReader (long side = [longSide]) and hands out RGBA frames. */
class ScreenCapture(
    private val ctx: Context,
    private val mp: MediaProjection,
    private val handler: Handler,
    private val longSide: Int
) {
    var realW = 0
        private set
    var realH = 0
        private set
    private var capW = 0
    private var capH = 0
    private var dpi = 320
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val lk = ReentrantLock()
    private val cond = lk.newCondition()
    @Volatile private var frame: Frame? = null
    private var seq = 0L
    private var served = 0L
    private var lastAccept = 0L
    @Volatile private var active = false

    init {
        build()
    }

    private fun metrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        return dm
    }

    @Synchronized
    private fun build() {
        val dm = metrics()
        realW = dm.widthPixels
        realH = dm.heightPixels
        dpi = dm.densityDpi
        val s = longSide.toFloat() / max(realW, realH)
        capW = ((realW * s).roundToInt() / 2 * 2).coerceAtLeast(2)
        capH = ((realH * s).roundToInt() / 2 * 2).coerceAtLeast(2)
        val old = reader
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)
        reader = r
        val v = vd
        if (v == null) {
            vd = mp.createVirtualDisplay(
                "autoplay", capW, capH, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                if (active) r.surface else null, null, handler
            )
        } else {
            v.resize(capW, capH, dpi)
            v.surface = if (active) r.surface else null
        }
        try { old?.close() } catch (_: Exception) { }
    }

    fun resume() {
        active = true
        vd?.surface = reader?.surface
    }

    fun pause() {
        active = false
        vd?.surface = null
    }

    /** @return true when the screen rotated and the capture was rebuilt. */
    fun checkRotation(): Boolean {
        val dm = metrics()
        if (dm.widthPixels != realW || dm.heightPixels != realH) {
            build()
            return true
        }
        return false
    }

    fun last(): Frame? = frame

    val lastFrameAt: Long get() = frame?.t ?: 0L

    /** Re-attach the mirror to a fresh reader (used when frames stop arriving). */
    fun refresh() {
        try {
            build()
            if (active) vd?.surface = reader?.surface
        } catch (e: Exception) {
            AppLog.d("capture refresh failed: " + e.message)
        }
    }

    private fun onImage(ir: ImageReader) {
        val img = try { ir.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (now - lastAccept < 25) return
            lastAccept = now
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val w = img.width
            val h = img.height
            val out = ByteArray(w * h * 4)
            if (rs == w * 4) {
                buf.get(out, 0, w * h * 4)
            } else {
                for (y in 0 until h) {
                    posOf(buf, y * rs)
                    buf.get(out, y * w * 4, w * 4)
                }
            }
            lk.lock()
            try {
                frame = Frame(w, h, out, now)
                seq++
                cond.signalAll()
            } finally {
                lk.unlock()
            }
        } finally {
            img.close()
        }
    }

    fun awaitFrame(timeoutMs: Long): Frame? {
        lk.lock()
        try {
            var left = timeoutMs * 1_000_000L
            while (seq == served) {
                if (left <= 0L) return null
                left = cond.awaitNanos(left)
            }
            served = seq
            return frame
        } finally {
            lk.unlock()
        }
    }

    fun saveFrame(f: Frame, file: File) {
        val bmp = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(f.px))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun release() {
        try { vd?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        vd = null
        reader = null
    }
}
