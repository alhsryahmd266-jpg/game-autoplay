package com.omega.autoplay

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the controller really did with the last camera drag (used to measure the game's look sensitivity). */
object DragLog {
    @Volatile var seq = 0
    @Volatile var dx = 0f
    @Volatile var dy = 0f
    @Volatile var at = 0L
}

/**
 * Tactical brain.
 *  - calibration (first seconds): measures how many pixels the picture moves per pixel of finger drag, so aiming is exact
 *  - tracking: follows every enemy over time, estimates its own motion (camera motion removed) and leads the shot
 *  - combat: aim, burst fire, stand-and-shoot at range / strafe up close, retreat when weak
 *  - memory: after losing an enemy it searches toward where it was last seen
 *  - when quiet: heal, reload, escape the zone, loot, explore (with stuck detection)
 */
class Brain(private val layout: Layout) {
    private class Trk(val id: Int, var x: Float, var y: Float, var w: Float, var h: Float, var seenT: Long, var conf: Float) {
        var vx = 0f
        var vy = 0f
        var hits = 1
        var det: Det? = null
    }

    private val rnd = java.util.Random()
    private val trks = ArrayList<Trk>()
    private var nextId = 1
    private var lastTrackT = 0L
    private var curId = -1

    private var strafe = 1f
    private var strafeUntil = 0L
    private var fireUntil = 0L
    private var burstGapUntil = 0L
    private var firedMs = 0L
    private var lastTick = 0L
    private var lastJump = 0L
    private var lastHeal = 0L
    private var lastMenu = 0L
    private var lastInteract = 0L
    private var lastReload = 0L
    private var lastLook = 0L
    private var lastEnemyAt = 0L
    private var lostSide = 1f
    private var hadEnemy = false
    private var nextTurn = 0L
    private var turnUntil = 0L
    private var turnDir = 1f
    private var lowMotionSince = 0L
    private var unstickUntil = 0L
    private var unstickDir = 1f
    private var lootSince = 0L
    private var ignoreLootUntil = 0L

    // aim calibration
    private val probes = arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(0, 1), intArrayOf(0, -1))
    private var calStart = 0L
    private var calStep = 0
    private var calPhase = 0
    private var calIssuedAt = 0L
    private var calSeq = 0
    private var calF0: Frame? = null
    private var calAmpX = 30f
    private var calAmpY = 20f
    private val kxs = ArrayList<Float>()
    private val kys = ArrayList<Float>()
    private var calDone = false

    /** picture shift (screen px) per px of finger drag; negative when dragging right turns the camera right. 0 = unknown. */
    var kx = 0f
        private set
    var ky = 0f
        private set

    @Volatile var lookGapMs = 130L

    fun reset(t: Long) {
        lastTick = 0L; lastEnemyAt = 0L; hadEnemy = false; lowMotionSince = 0L; unstickUntil = 0L
        fireUntil = 0L; burstGapUntil = 0L; firedMs = 0L; lootSince = 0L; ignoreLootUntil = 0L
        trks.clear(); curId = -1; lastTrackT = 0L
        nextTurn = t + 2500
        calStart = t; calStep = 0; calPhase = 0; calF0 = null; calDone = false
        calAmpX = 30f; calAmpY = 20f
        kxs.clear(); kys.clear()
        kx = Prefs.calKx
        ky = Prefs.calKy
    }

    // ---------------------------------------------------------------- calibration

    private fun median(l: List<Float>): Float? {
        if (l.isEmpty()) return null
        val s = l.sorted()
        return s[s.size / 2]
    }

    private fun consistent(l: List<Float>, m: Float): Boolean {
        if (l.size < 2) return l.size == 1
        val ok = l.count { it * m > 0f && abs(it) >= abs(m) / 2.5f && abs(it) <= abs(m) * 2.5f }
        return ok * 10 >= l.size * 6
    }

    private fun finishCal() {
        calDone = true
        val mx = median(kxs)
        val my = median(kys)
        if (mx != null && abs(mx) in 0.15f..30f && consistent(kxs, mx)) {
            kx = mx
            Prefs.calKx = mx
        }
        if (my != null && abs(my) in 0.15f..30f && consistent(kys, my)) {
            ky = my
            Prefs.calKy = my
        } else if (kx != 0f) {
            ky = kx
        }
        AppLog.d("aim calibration: kx=" + kx + " ky=" + ky + " samples=" + kxs.size + "/" + kys.size + (if (kx == 0f) " (failed: using fixed gain)" else ""))
    }

    private fun calibrate(w: World, c: Command, sw: Int) {
        val t = w.t
        c.status = "معايرة التصويب"
        c.absoluteLook = true
        if (calStep >= probes.size) return
        if (calPhase == 0) {
            if (t - calStart < 400) return
            val p = probes[calStep]
            calF0 = w.frame
            if (p[0] == 0) c.lookDx = p[1] * calAmpX else c.lookDy = p[1] * calAmpY
            calIssuedAt = t
            calSeq = DragLog.seq
            calPhase = 1
            lastLook = t
        } else {
            val executed = DragLog.seq != calSeq
            if (executed && t - DragLog.at > 380) {
                val f0 = calF0
                val r = if (f0 != null) Ego.estimate(f0, w.frame) else null
                val p = probes[calStep]
                if (r != null && r.quality > 0.2f) {
                    val scale = sw.toFloat() / w.frame.w
                    val horizontal = p[0] == 0
                    val s = if (horizontal) abs(r.dx) else abs(r.dy)
                    val limit = if (horizontal) 80f else 24f
                    if (s > 0.8f * limit) {
                        if (horizontal) calAmpX = max(8f, calAmpX * 0.5f) else calAmpY = max(8f, calAmpY * 0.5f)
                    } else if (s < 3f) {
                        if (horizontal) calAmpX = min(120f, calAmpX * 2f) else calAmpY = min(80f, calAmpY * 2f)
                    } else if (horizontal && abs(DragLog.dx) > 5f) {
                        kxs.add(r.dx * scale / DragLog.dx)
                    } else if (!horizontal && abs(DragLog.dy) > 5f) {
                        kys.add(r.dy * scale / DragLog.dy)
                    }
                }
                calStep++
                calPhase = 0
            } else if (t - calIssuedAt > 1600) {
                calStep++
                calPhase = 0
            }
        }
    }

    // ---------------------------------------------------------------- tracking

    private fun trackUpdate(w: World) {
        val t = w.t
        val dt = if (lastTrackT == 0L) 0.1f else ((t - lastTrackT) / 1000f).coerceIn(0.03f, 0.5f)
        lastTrackT = t
        val egoX = if (w.egoOk) w.egoDx / w.frame.w else 0f
        val egoY = if (w.egoOk) w.egoDy / w.frame.h else 0f
        val used = BooleanArray(w.enemies.size)
        for (tr in trks) {
            val px = tr.x + egoX
            val py = tr.y + egoY
            var bi = -1
            var bd = 0.13f
            for (i in w.enemies.indices) {
                if (used[i]) continue
                val d = w.enemies[i]
                val dist = hypot((d.cx - px) * 2f, d.cy - py)
                if (dist < bd) {
                    bd = dist
                    bi = i
                }
            }
            if (bi >= 0) {
                used[bi] = true
                val d = w.enemies[bi]
                tr.vx = (tr.vx * 0.6f + ((d.cx - px) / dt) * 0.4f).coerceIn(-1.5f, 1.5f)
                tr.vy = (tr.vy * 0.6f + ((d.cy - py) / dt) * 0.4f).coerceIn(-1.5f, 1.5f)
                tr.x = d.cx
                tr.y = d.cy
                tr.w = d.w
                tr.h = d.h
                tr.seenT = t
                tr.hits++
                tr.conf = d.conf
                tr.det = d
            } else {
                tr.x = px
                tr.y = py
                tr.det = null
            }
        }
        for (i in w.enemies.indices) {
            if (used[i]) continue
            val d = w.enemies[i]
            val n = Trk(nextId++, d.cx, d.cy, d.w, d.h, t, d.conf)
            n.det = d
            trks.add(n)
        }
        trks.removeAll { t - it.seenT > 700 }
    }

    private fun pickTrack(w: World): Trk? {
        var best: Trk? = null
        var bs = Float.MAX_VALUE
        for (tr in trks) {
            if (tr.seenT != w.t || tr.det == null) continue
            val d = hypot((tr.x - w.cross.x) * 2f, tr.y - w.cross.y)
            var s = d - tr.conf * 0.2f
            if (tr.id == curId) s -= 0.15f
            if (s < bs) {
                bs = s
                best = tr
            }
        }
        return best
    }

    private fun aimPoint(w: World, e: Det): PointF {
        val h = w.heads.firstOrNull { it.cx >= e.x1 && it.cx <= e.x2 && it.cy >= e.y1 && it.cy <= e.y2 }
        return if (h != null) PointF(h.cx, h.cy) else PointF(e.cx, e.y1 + e.h * 0.25f)
    }

    // ---------------------------------------------------------------- look helpers

    /** Camera turn to the right by [r] screen px of picture shift, expressed as a finger drag. */
    private fun yawRight(r: Float): Float = if (kx != 0f) -r / kx else r

    /** Drag needed to bring something that is (ex, ey) screen px away from the crosshair onto it. */
    private fun lookToward(ex: Float, ey: Float, c: Command, sw: Int, sh: Int, maxStep: Float) {
        val damp = Prefs.aimGain
        var dx = 0f
        var dy = 0f
        if (kx != 0f) {
            val kyy = if (ky != 0f) ky else kx
            if (abs(ex) > 0.004f * sw) dx = (-ex / kx) * damp
            if (abs(ey) > 0.008f * sh) dy = (-ey / kyy) * damp
            c.absoluteLook = true
        } else {
            if (abs(ex) > 0.004f * sw) dx = ex * damp
            if (abs(ey) > 0.008f * sh) dy = ey * damp * 0.8f
        }
        c.lookDx = dx.coerceIn(-maxStep, maxStep)
        c.lookDy = dy.coerceIn(-maxStep * 0.6f, maxStep * 0.6f)
    }

    // ---------------------------------------------------------------- decision

    fun decide(w: World, sw: Int, sh: Int): Command {
        val c = Command()
        val t = w.t
        val dt = if (lastTick == 0L) 0L else min(300L, t - lastTick)
        lastTick = t
        val style = Prefs.style
        val maxStep = 0.16f * sw
        val canLook = t - lastLook >= lookGapMs

        if (w.calibrating) {
            calibrate(w, c, sw)
            return c
        }
        if (!calDone) finishCal()

        if (!w.hudVisible) {
            c.status = "قائمة / موت"
            val m = w.menu
            if (m != null && t - lastMenu > 1500) {
                c.taps.add(PointF(m.cx, m.cy))
                lastMenu = t
            }
            return c
        }

        trackUpdate(w)
        val tr = pickTrack(w)
        val hp = w.health
        val weak = (hp != null && hp < (if (style == 0) 0.45f else 0.3f)) || w.lowHealth

        if (tr != null) {
            c.status = "قتال"
            curId = tr.id
            hadEnemy = true
            lastEnemyAt = t
            val det = tr.det
            val ap = if (det != null) aimPoint(w, det) else PointF(tr.x, tr.y)
            val lx = if (tr.hits >= 3) (tr.vx * LEAD).coerceIn(-0.10f, 0.10f) else 0f
            val ly = if (tr.hits >= 3) (tr.vy * LEAD).coerceIn(-0.08f, 0.08f) else 0f
            val exNow = (ap.x - w.cross.x) * sw
            val eyNow = (ap.y - w.cross.y) * sh
            lostSide = if (exNow >= 0f) 1f else -1f
            if (canLook) {
                lookToward((ap.x + lx - w.cross.x) * sw, (ap.y + ly - w.cross.y) * sh, c, sw, sh, maxStep)
                if (c.lookDx != 0f || c.lookDy != 0f) lastLook = t
            }
            val bw = tr.w * sw
            val bh = tr.h * sh
            val aligned = abs(exNow) < max(0.03f * sw, 0.35f * bw) && abs(eyNow) < max(0.05f * sh, 0.30f * bh)
            val allyInLine = w.allies.any { w.cross.x >= it.x1 && w.cross.x <= it.x2 && w.cross.y >= it.y1 && w.cross.y <= it.y2 }
            if (aligned && !allyInLine && tr.conf >= Prefs.conf && t >= burstGapUntil && t >= fireUntil) {
                fireUntil = t + 420 + rnd.nextInt(300)
                burstGapUntil = fireUntil + 110 + rnd.nextInt(120)
            }
            if (t > strafeUntil) {
                strafe = if (rnd.nextBoolean()) 1f else -1f
                strafeUntil = t + 600 + rnd.nextInt(1100)
            }
            val near = when (style) { 2 -> 0.16f; 1 -> 0.12f; else -> 0.09f }
            val far = tr.h < near * 0.7f
            var fwd = 0f
            if (far) fwd = 0.9f else if (tr.h < near) fwd = 0.45f else if (tr.h > 0.45f) fwd = -0.7f
            if (weak) fwd = -0.8f
            var mvx = strafe * 0.85f
            if (aligned && tr.h < 0.14f && !weak) {
                mvx = 0f
                if (fwd > 0f) fwd = 0f
            }
            c.moveX = mvx
            c.moveY = fwd
            if (!weak && layout.has(Role.JUMP) && t - lastJump > 2400 + rnd.nextInt(2600)) {
                c.tap(layout.get(Role.JUMP))
                lastJump = t
            }
        } else {
            curId = -1
            val since = t - lastEnemyAt
            val searching = hadEnemy && since < 2200
            if (!searching) hadEnemy = false
            if (since > 1200) {
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
            if (searching) {
                c.status = "بحث عن العدو"
                if (t > strafeUntil) {
                    strafe = if (rnd.nextBoolean()) 1f else -1f
                    strafeUntil = t + 500 + rnd.nextInt(700)
                }
                c.moveX = strafe * 0.5f
                c.moveY = 0f
                if (canLook) {
                    c.lookDx = yawRight(lostSide * 0.045f * sw).coerceIn(-maxStep, maxStep)
                    lastLook = t
                }
            } else if (zw != null) {
                c.status = "هروب من الزون"
                lootSince = 0L
                if (mm != null && zt != null) {
                    val vx = (zt.cx - mm.cx) / max(mm.w, 0.01f)
                    val vy = (zt.cy - mm.cy) / max(mm.h, 0.01f)
                    val ang = atan2(vx, -vy)
                    if (abs(ang) > 0.3f && canLook) {
                        c.lookDx = yawRight(ang * 0.09f * sw).coerceIn(-maxStep, maxStep)
                        lastLook = t
                    }
                    c.moveY = if (abs(ang) < 0.9f) 1f else 0.2f
                } else {
                    val side = if (zw.cx < 0.5f) 1f else -1f
                    if (canLook) {
                        c.lookDx = yawRight(side * 0.10f * sw).coerceIn(-maxStep, maxStep)
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
                    lookToward(ex, 0f, c, sw, sh, maxStep)
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

    companion object {
        /** seconds the shot is led by: camera + gesture + detection latency */
        const val LEAD = 0.20f
    }
}
