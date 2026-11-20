package com.omega.autoplay

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Hud {
    /** Fraction (0..1) of a bar's width that is coloured. -1 when it cannot be read. */
    fun barFill(f: Frame, d: Det): Float {
        val x1 = (d.x1 * f.w).toInt().coerceIn(0, f.w - 2)
        val x2 = (d.x2 * f.w).toInt().coerceIn(x1 + 1, f.w)
        val yc = (d.cy * f.h).toInt().coerceIn(1, f.h - 2)
        var filled = 0
        var total = 0
        for (x in x1 until x2) {
            var on = false
            for (dy in -1..1) {
                val i = ((yc + dy) * f.w + x) * 4
                val r = f.px[i].toInt() and 0xFF
                val g = f.px[i + 1].toInt() and 0xFF
                val b = f.px[i + 2].toInt() and 0xFF
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx > 90 && mx - mn > 45) on = true
            }
            total++
            if (on) filled++
        }
        if (total == 0 || filled == 0) return -1f
        return filled.toFloat() / total
    }

    /** Mean absolute difference between two frames over the middle of the screen (0..255). */
    fun motion(a: Frame?, b: Frame): Float {
        if (a == null || a.w != b.w || a.h != b.h) return 99f
        var sum = 0L
        var n = 0
        val x0 = b.w * 25 / 100
        val x1 = b.w * 75 / 100
        val y0 = b.h * 20 / 100
        val y1 = b.h * 70 / 100
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val i = (y * b.w + x) * 4
                val ga = (a.px[i].toInt() and 0xFF) + (a.px[i + 1].toInt() and 0xFF) + (a.px[i + 2].toInt() and 0xFF)
                val gb = (b.px[i].toInt() and 0xFF) + (b.px[i + 1].toInt() and 0xFF) + (b.px[i + 2].toInt() and 0xFF)
                sum += abs(ga - gb)
                n++
                x += 6
            }
            y += 6
        }
        return if (n == 0) 0f else sum.toFloat() / n / 3f
    }
}

/**
 * In third-person games the detector often labels the player's own avatar as an enemy. During a short calibration
 * (the bot walks and sweeps the camera without aiming) anything that stays fixed on the screen is the avatar, and is
 * ignored afterwards. Real enemies move across the screen while the camera moves, so they never become "self".
 */
class SelfFilter {
    private class Cand(var x1: Float, var y1: Float, var x2: Float, var y2: Float, var n: Int)

    private val cands = ArrayList<Cand>()
    private val boxes = ArrayList<FloatArray>()
    private var frames = 0
    var learning = false
        private set
    val count: Int get() = boxes.size

    fun begin() {
        cands.clear()
        boxes.clear()
        frames = 0
        learning = true
    }

    private fun iouOf(ax1: Float, ay1: Float, ax2: Float, ay2: Float, bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
        val ix = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val iy = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = ix * iy
        val u = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun feed(dets: List<Det>) {
        frames++
        for (d in dets) {
            if (d.role != Role.ENEMY && d.role != Role.ENEMY_HEAD) continue
            var hit: Cand? = null
            for (c in cands) {
                if (iouOf(c.x1, c.y1, c.x2, c.y2, d.x1, d.y1, d.x2, d.y2) > 0.5f) {
                    hit = c
                    break
                }
            }
            if (hit == null) {
                cands.add(Cand(d.x1, d.y1, d.x2, d.y2, 1))
            } else {
                hit.n++
                hit.x1 += (d.x1 - hit.x1) * 0.2f
                hit.y1 += (d.y1 - hit.y1) * 0.2f
                hit.x2 += (d.x2 - hit.x2) * 0.2f
                hit.y2 += (d.y2 - hit.y2) * 0.2f
            }
        }
    }

    fun finish() {
        learning = false
        val need = max(8, (frames * 0.6f).toInt())
        for (c in cands) {
            if (c.n >= need) boxes.add(floatArrayOf(c.x1, c.y1, c.x2, c.y2))
        }
        cands.clear()
        AppLog.d("self filter: " + boxes.size + " fixed box(es) learned from " + frames + " frames")
    }

    fun isSelf(d: Det): Boolean {
        for (b in boxes) {
            if (iouOf(b[0], b[1], b[2], b[3], d.x1, d.y1, d.x2, d.y2) > 0.35f) return true
            val inside = d.cx >= b[0] && d.cx <= b[2] && d.cy >= b[1] && d.cy <= b[3]
            if (inside && d.w * d.h >= 0.4f * (b[2] - b[0]) * (b[3] - b[1])) return true
        }
        return false
    }
}

/** Turns raw detections + the frame into the situation the Brain reasons about. */
class Perception(private val layout: Layout) {
    private var prev: Frame? = null
    private var hudSeenAt = 0L
    private var calibUntil = 0L
    private val self = SelfFilter()
    var hudKnown = false

    fun reset(t: Long) {
        prev = null
        hudSeenAt = t
        calibUntil = t + 4500
        self.begin()
    }

    fun build(f: Frame, allDets: List<Det>, t: Long): World {
        val aspect = f.w.toFloat() / f.h
        val calibrating = t < calibUntil
        if (calibrating) {
            self.feed(allDets)
        } else if (self.learning) {
            self.finish()
        }
        val dets = if (calibrating) allDets else allDets.filter { !((it.role == Role.ENEMY || it.role == Role.ENEMY_HEAD) && self.isSelf(it)) }
        // drop impossible boxes (tiny specks, absurd aspect ratios): they are false positives, not enemies
        val enemies = dets.filter { it.role == Role.ENEMY && it.h > 0.02f && it.w > 0.006f && it.h / max(it.w, 0.001f) < 8f && it.w / max(it.h, 0.001f) < 4f }
        val heads = dets.filter { it.role == Role.ENEMY_HEAD }
        val allies = dets.filter { it.role == Role.ALLY }
        val loot = dets.filter { it.role == Role.LOOT }
        val ctl = HashMap<Role, Det>()
        for (d in dets) {
            if (d.role.isControl) {
                val o = ctl[d.role]
                if (o == null || d.conf > o.conf) ctl[d.role] = d
            }
        }
        for ((r, d) in ctl) layout.learn(r, d, aspect)
        val hp = dets.filter { it.role == Role.HEALTH_BAR }.maxByOrNull { it.conf }
        var health: Float? = null
        if (hp != null) {
            val v = Hud.barFill(f, hp)
            if (v >= 0f) health = v
        }
        val minimap = dets.filter { it.role == Role.MINIMAP }.maxByOrNull { it.conf }
        val hudNow = ctl.containsKey(Role.JOYSTICK) || ctl.containsKey(Role.FIRE) || hp != null || minimap != null
        if (hudNow) hudSeenAt = t
        val hudVisible = !hudKnown || (t - hudSeenAt) < 1800
        val cr = dets.filter { it.role == Role.CROSSHAIR }.maxByOrNull { it.conf }
        val cross = if (cr != null) PointF(cr.cx, cr.cy) else PointF(layout.cross.x, layout.cross.y)
        val motion = Hud.motion(prev, f)
        val pf = prev
        val ego = if (pf != null) Ego.estimate(pf, f) else null
        val egoOk = ego != null && ego.quality > 0.15f
        prev = f
        return World(
            t = t,
            enemies = enemies,
            heads = heads,
            allies = allies,
            loot = loot,
            ctl = ctl,
            health = health,
            lowHealth = dets.any { it.role == Role.LOW_HEALTH },
            zoneWarn = dets.filter { it.role == Role.ZONE_WARNING }.maxByOrNull { it.conf },
            minimap = minimap,
            zoneTarget = dets.filter { it.role == Role.ZONE_TARGET }.maxByOrNull { it.conf },
            menu = dets.filter { it.role == Role.MENU_BUTTON }.maxByOrNull { it.conf },
            hudVisible = hudVisible,
            motion = motion,
            cross = cross,
            frame = f,
            egoDx = if (ego != null) ego.dx else 0f,
            egoDy = if (ego != null) ego.dy else 0f,
            egoOk = egoOk,
            calibrating = calibrating
        )
    }
}
