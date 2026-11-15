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

/** Turns raw detections + the frame into the situation the Brain reasons about. */
class Perception(private val layout: Layout) {
    private var prev: Frame? = null
    private var hudSeenAt = 0L
    var hudKnown = false

    fun reset(t: Long) {
        prev = null
        hudSeenAt = t
    }

    fun build(f: Frame, dets: List<Det>, t: Long): World {
        val aspect = f.w.toFloat() / f.h
        val enemies = dets.filter { it.role == Role.ENEMY }
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
            cross = cross
        )
    }
}
