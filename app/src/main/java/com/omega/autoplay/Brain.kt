package com.omega.autoplay

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Rule-based tactical brain. Priorities: menus -> combat (aim / shoot / strafe / retreat) -> heal & reload when quiet
 * -> escape the zone -> loot -> explore (with stuck detection).
 */
class Brain(private val layout: Layout) {
    private val rnd = java.util.Random()
    private var strafe = 1f
    private var strafeUntil = 0L
    private var fireUntil = 0L
    private var firedMs = 0L
    private var lastTick = 0L
    private var lastJump = 0L
    private var lastHeal = 0L
    private var lastMenu = 0L
    private var lastInteract = 0L
    private var lastReload = 0L
    private var lastLook = 0L
    private var lastEnemy = 0L
    private var nextTurn = 0L
    private var turnUntil = 0L
    private var turnDir = 1f
    private var lowMotionSince = 0L
    private var unstickUntil = 0L
    private var unstickDir = 1f
    private var tx = -1f
    private var ty = -1f
    private var lootSince = 0L
    private var ignoreLootUntil = 0L
    @Volatile var lookGapMs = 130L

    fun reset(t: Long) {
        lastTick = 0L; lastEnemy = t; lowMotionSince = 0L; unstickUntil = 0L
        fireUntil = 0L; firedMs = 0L; tx = -1f; lootSince = 0L; ignoreLootUntil = 0L
        nextTurn = t + 2500
    }

    private fun pickTarget(w: World): Det? {
        var best: Det? = null
        var bs = Float.MAX_VALUE
        for (e in w.enemies) {
            val d = hypot((e.cx - w.cross.x) * 2f, e.cy - w.cross.y)
            var s = d - e.conf * 0.2f
            if (tx >= 0f && hypot((e.cx - tx) * 2f, e.cy - ty) < 0.12f) s -= 0.15f
            if (s < bs) {
                bs = s
                best = e
            }
        }
        return best
    }

    private fun aimPoint(w: World, e: Det): PointF {
        val h = w.heads.firstOrNull { it.cx >= e.x1 && it.cx <= e.x2 && it.cy >= e.y1 && it.cy <= e.y2 }
        return if (h != null) PointF(h.cx, h.cy) else PointF(e.cx, e.y1 + e.h * 0.25f)
    }

    fun decide(w: World, sw: Int, sh: Int): Command {
        val c = Command()
        val t = w.t
        val dt = if (lastTick == 0L) 0L else min(300L, t - lastTick)
        lastTick = t
        val gain = Prefs.aimGain
        val style = Prefs.style
        val maxStep = 0.16f * sw
        val canLook = t - lastLook >= lookGapMs

        if (!w.hudVisible) {
            c.status = "قائمة / موت"
            val m = w.menu
            if (m != null && t - lastMenu > 1500) {
                c.taps.add(PointF(m.cx, m.cy))
                lastMenu = t
            }
            return c
        }

        if (w.calibrating) {
            c.status = "معايرة: بتعلّم شكل شخصيتك"
            c.moveY = 0.7f
            if (canLook) {
                c.lookDx = (if ((t / 600L) % 2L == 0L) 1f else -1f) * 0.05f * sw
                lastLook = t
            }
            return c
        }

        val tgt = pickTarget(w)
        val hp = w.health
        if (tgt != null) {
            c.status = "قتال"
            lastEnemy = t
            tx = tgt.cx
            ty = tgt.cy
            val ap = aimPoint(w, tgt)
            val ex = (ap.x - w.cross.x) * sw
            val ey = (ap.y - w.cross.y) * sh
            if (canLook) {
                val dx = if (abs(ex) > 0.004f * sw) (ex * gain).coerceIn(-maxStep, maxStep) else 0f
                val dy = if (abs(ey) > 0.008f * sh) (ey * gain * 0.8f).coerceIn(-maxStep * 0.6f, maxStep * 0.6f) else 0f
                if (dx != 0f || dy != 0f) {
                    c.lookDx = dx
                    c.lookDy = dy
                    lastLook = t
                }
            }
            val bw = tgt.w * sw
            val bh = tgt.h * sh
            val aligned = abs(ex) < max(0.03f * sw, 0.35f * bw) && abs(ey) < max(0.05f * sh, 0.30f * bh)
            val allyInLine = w.allies.any { w.cross.x >= it.x1 && w.cross.x <= it.x2 && w.cross.y >= it.y1 && w.cross.y <= it.y2 }
            if (aligned && !allyInLine && tgt.conf >= Prefs.conf) fireUntil = t + 450
            if (t > strafeUntil) {
                strafe = if (rnd.nextBoolean()) 1f else -1f
                strafeUntil = t + 600 + rnd.nextInt(1100)
            }
            val near = when (style) { 2 -> 0.16f; 1 -> 0.12f; else -> 0.09f }
            var fwd = 0f
            if (tgt.h < near * 0.7f) fwd = 0.9f else if (tgt.h < near) fwd = 0.45f else if (tgt.h > 0.45f) fwd = -0.7f
            val weak = (hp != null && hp < (if (style == 0) 0.45f else 0.3f)) || w.lowHealth
            if (weak) fwd = -0.8f
            c.moveX = strafe * 0.85f
            c.moveY = fwd
            if (!weak && layout.has(Role.JUMP) && t - lastJump > 2400 + rnd.nextInt(2600)) {
                c.tap(layout.get(Role.JUMP))
                lastJump = t
            }
        } else {
            tx = -1f
            val quiet = t - lastEnemy
            if (quiet > 1200) {
                if (((hp != null && hp < 0.6f) || w.lowHealth) && layout.has(Role.HEAL) && t - lastHeal > 7000) {
                    c.tap(layout.get(Role.HEAL))
                    lastHeal = t
                } else if (firedMs > 3500 && layout.has(Role.RELOAD) && t - lastReload > 5000) {
                    c.tap(layout.get(Role.RELOAD))
                    lastReload = t
                    firedMs = 0
                }
            }
            if (w.ctl.containsKey(Role.INTERACT) && t - lastInteract > 1400) {
                c.tap(layout.get(Role.INTERACT))
                lastInteract = t
            }
            val zw = w.zoneWarn
            val mm = w.minimap
            val zt = w.zoneTarget
            val loot = if (t < ignoreLootUntil) null else w.loot.minByOrNull { abs(it.cx - w.cross.x) + 0.5f * abs(it.cy - w.cross.y) }
            if (zw != null) {
                c.status = "هروب من الزون"
                lootSince = 0L
                if (mm != null && zt != null) {
                    val vx = (zt.cx - mm.cx) / max(mm.w, 0.01f)
                    val vy = (zt.cy - mm.cy) / max(mm.h, 0.01f)
                    val ang = atan2(vx, -vy)
                    if (abs(ang) > 0.3f && canLook) {
                        c.lookDx = (ang * 0.09f * sw).coerceIn(-maxStep, maxStep)
                        lastLook = t
                    }
                    c.moveY = if (abs(ang) < 0.9f) 1f else 0.2f
                } else {
                    val side = if (zw.cx < 0.5f) 1f else -1f
                    if (canLook) {
                        c.lookDx = side * 0.10f * sw
                        lastLook = t
                    }
                    c.moveY = 1f
                }
            } else if (loot != null) {
                c.status = "غنيمة"
                if (lootSince == 0L) lootSince = t
                if (t - lootSince > 9000) {
                    ignoreLootUntil = t + 12000
                    lootSince = 0L
                }
                val ex = (loot.cx - w.cross.x) * sw
                if (abs(ex) > 0.03f * sw && canLook) {
                    c.lookDx = (ex * gain).coerceIn(-maxStep, maxStep)
                    lastLook = t
                }
                c.moveY = if (loot.h > 0.32f) 0f else if (abs(ex) < 0.08f * sw) 0.9f else 0.25f
            } else {
                c.status = "استكشاف"
                lootSince = 0L
                if (w.motion < 1.6f) {
                    if (lowMotionSince == 0L) lowMotionSince = t
                } else {
                    lowMotionSince = 0L
                }
                if (lowMotionSince != 0L && t - lowMotionSince > 1300 && t > unstickUntil) {
                    unstickUntil = t + 1000
                    unstickDir = if (rnd.nextBoolean()) 1f else -1f
                    lowMotionSince = 0L
                    c.tap(layout.get(Role.JUMP))
                }
                if (t < unstickUntil) {
                    c.status = "فك العلوق"
                    c.moveY = -0.4f
                    c.moveX = unstickDir
                    if (canLook) {
                        c.lookDx = unstickDir * 0.12f * sw
                        lastLook = t
                    }
                } else {
                    c.moveY = 1f
                    if (t > nextTurn) {
                        turnUntil = t + 450 + rnd.nextInt(600)
                        turnDir = if (rnd.nextBoolean()) 1f else -1f
                        nextTurn = t + 3500 + rnd.nextInt(4500)
                    }
                    if (t < turnUntil && canLook) {
                        c.lookDx = turnDir * 0.05f * sw
                        lastLook = t
                    }
                }
            }
        }
        if (t < fireUntil) {
            c.fire = true
            firedMs += dt
        }
        return c
    }
}
