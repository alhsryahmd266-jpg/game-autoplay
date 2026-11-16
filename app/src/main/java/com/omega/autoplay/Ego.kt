package com.omega.autoplay

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera-motion estimate between two frames: block matching on a sky/background band
 * (HUD corners, the top HUD row and the player's avatar are outside the band).
 * Result is the shift of the CONTENT in frame pixels (+x = content moved right).
 */
object Ego {
    class Result(val dx: Float, val dy: Float, val quality: Float)

    private class Band(val g: IntArray, val w: Int, val h: Int)

    const val S = 4
    private const val RX_MAX = 20
    private const val RY_MAX = 6

    private fun band(f: Frame): Band? {
        val x0 = f.w * 22 / 100
        val x1 = f.w * 78 / 100
        val y0 = f.h * 10 / 100
        val y1 = f.h * 34 / 100
        val bw = (x1 - x0) / S
        val bh = (y1 - y0) / S
        if (bw < 24 || bh < 8) return null
        val g = IntArray(bw * bh)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                var s = 0
                for (oy in 0..2 step 2) {
                    for (ox in 0..2 step 2) {
                        val i = ((y0 + y * S + oy) * f.w + (x0 + x * S + ox)) * 4
                        s += (f.px[i].toInt() and 0xFF) + (f.px[i + 1].toInt() and 0xFF) + (f.px[i + 2].toInt() and 0xFF)
                    }
                }
                g[y * bw + x] = s / 12
            }
        }
        return Band(g, bw, bh)
    }

    /** Shift of [b] relative to [a]: b(x, y) ~ a(x - dx, y - dy). Returns null when it cannot be measured. */
    fun estimate(a: Frame, b: Frame): Result? {
        if (a.w != b.w || a.h != b.h) return null
        val ba = band(a) ?: return null
        val bb = band(b) ?: return null
        val w = ba.w
        val h = ba.h
        val rx = min(RX_MAX, w * 40 / 100)
        val ry = min(RY_MAX, h * 40 / 100)
        val nx = 2 * rx + 1
        val costs = FloatArray(nx * (2 * ry + 1)) { Float.MAX_VALUE }
        var best = Float.MAX_VALUE
        var bsx = 0
        var bsy = 0
        for (sy in -ry..ry) {
            for (sx in -rx..rx) {
                val x0 = max(0, sx)
                val x1 = min(w, w + sx)
                val y0 = max(0, sy)
                val y1 = min(h, h + sy)
                val cnt = (x1 - x0) * (y1 - y0)
                if (cnt * 100 < w * h * 45) continue
                var sum = 0
                for (y in y0 until y1) {
                    var ib = y * w + x0
                    var ia = (y - sy) * w + (x0 - sx)
                    for (x in x0 until x1) {
                        sum += abs(bb.g[ib] - ba.g[ia])
                        ib++
                        ia++
                    }
                }
                val c = sum.toFloat() / cnt
                costs[(sy + ry) * nx + (sx + rx)] = c
                if (c < best) {
                    best = c
                    bsx = sx
                    bsy = sy
                }
            }
        }
        if (best == Float.MAX_VALUE) return null
        var second = Float.MAX_VALUE
        for (sy in -ry..ry) {
            for (sx in -rx..rx) {
                if (abs(sx - bsx) <= 1 && abs(sy - bsy) <= 1) continue
                val c = costs[(sy + ry) * nx + (sx + rx)]
                if (c < second) second = c
            }
        }
        var q = if (second == Float.MAX_VALUE) 0f else (second - best) / (second + 1f)
        if (abs(bsx) == rx || abs(bsy) == ry) q *= 0.3f
        var fx = 0f
        var fy = 0f
        if (abs(bsx) < rx) {
            val cm = costs[(bsy + ry) * nx + (bsx - 1 + rx)]
            val cp = costs[(bsy + ry) * nx + (bsx + 1 + rx)]
            val den = cm - 2f * best + cp
            if (cm < Float.MAX_VALUE && cp < Float.MAX_VALUE && den > 1e-6f) fx = (0.5f * (cm - cp) / den).coerceIn(-0.5f, 0.5f)
        }
        if (abs(bsy) < ry) {
            val cm = costs[(bsy - 1 + ry) * nx + (bsx + rx)]
            val cp = costs[(bsy + 1 + ry) * nx + (bsx + rx)]
            val den = cm - 2f * best + cp
            if (cm < Float.MAX_VALUE && cp < Float.MAX_VALUE && den > 1e-6f) fy = (0.5f * (cm - cp) / den).coerceIn(-0.5f, 0.5f)
        }
        return Result((bsx + fx) * S, (bsy + fy) * S, q)
    }
}
