package com.omega.autoplay

import android.graphics.PointF
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Virtual touch panel: turns the Brain's Commands into pointer events.
 *   pointer 0 = movement stick (held), 1 = camera drag, 2 = fire (held), 3 = taps.
 * Runs its own loop (fast with the shell backend, slower with accessibility) and only touches the screen while the
 * target game is in the foreground.
 */
class Controller(private val layout: Layout, private val ownPkg: String) {
    @Volatile private var backend: InputBackend? = null
    @Volatile private var sw = 2400
    @Volatile private var sh = 1080
    @Volatile private var mx = 0f
    @Volatile private var my = 0f
    @Volatile private var fire = false
    @Volatile private var run = false
    @Volatile var paused = false
        private set
    private var th: Thread? = null

    private val lookLock = Any()
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendLook = false
    private var pendAbs = false
    private val tapQ = ConcurrentLinkedQueue<PointF>()

    private var joyDown = false
    private var joyX = 0f
    private var joyY = 0f
    private var fireDown = false
    private var lookActive = false
    private var lookStep = 0
    private var lx0 = 0f
    private var ly0 = 0f
    private var lx1 = 0f
    private var ly1 = 0f
    private var tapActive = false
    private var tapUntil = 0L
    private var lastFg = 0L

    val backendName: String get() = backend?.name ?: "-"
    val periodMs: Long get() = if (backend?.name == "Shell") 16L else 45L

    fun start(w: Int, h: Int, b: InputBackend) {
        stop()
        sw = w
        sh = h
        backend = b
        mx = 0f; my = 0f; fire = false
        joyDown = false; fireDown = false; lookActive = false; tapActive = false
        pendLook = false
        tapQ.clear()
        run = true
        th = Thread({ loop() }, "ap-controller").also { it.start() }
    }

    fun setScreen(w: Int, h: Int) {
        sw = w
        sh = h
    }

    fun stop() {
        run = false
        try { th?.join(600) } catch (_: Throwable) { }
        th = null
        val b = backend
        backend = null
        try { b?.releaseAll() } catch (_: Throwable) { }
        try { b?.close() } catch (_: Throwable) { }
    }

    fun apply(c: Command) {
        mx = c.moveX
        my = c.moveY
        fire = c.fire
        if (c.lookDx != 0f || c.lookDy != 0f) {
            synchronized(lookLock) {
                pendDx = c.lookDx
                pendDy = c.lookDy
                pendAbs = c.absoluteLook
                pendLook = true
            }
        }
        for (p in c.taps) tapQ.add(p)
    }

    private fun loop() {
        while (run) {
            val t0 = SystemClock.uptimeMillis()
            try {
                tick(t0)
            } catch (e: Throwable) {
                AppLog.d("controller: " + e.javaClass.simpleName + " " + e.message)
            }
            val left = periodMs - (SystemClock.uptimeMillis() - t0)
            if (left > 0) {
                try { Thread.sleep(left) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun gameInFront(now: Long): Boolean {
        val svc = BotAccessibilityService.instance ?: return true
        if (now - lastFg > 1200) {
            lastFg = now
            val p = svc.currentPkg()
            if (p != null && p != ownPkg) BotAccessibilityService.fgPkg = p
        }
        val tgt = Prefs.targetPkg
        val cur = BotAccessibilityService.fgPkg
        return tgt.isEmpty() || cur.isEmpty() || cur == tgt
    }

    private fun tick(now: Long) {
        val b = backend ?: return
        if (!gameInFront(now)) {
            if (!paused) {
                paused = true
                AppLog.d("paused: the game is not in front")
                b.releaseAll()
                joyDown = false; fireDown = false; lookActive = false; tapActive = false
            }
            return
        }
        paused = false
        val w = sw.toFloat()
        val h = sh.toFloat()

        val jp = layout.get(Role.JOYSTICK)
        if (jp != null) {
            val cx = jp.x * w
            val cy = jp.y * h
            val r = layout.joyR * h
            val mag = hypot(mx, my)
            if (mag > 0.08f) {
                val k = 0.92f * r / max(1f, mag)
                val tx = cx + mx * k
                val ty = cy - my * k
                if (!joyDown) {
                    b.touchDown(0, cx, cy)
                    joyX = cx
                    joyY = cy
                    joyDown = true
                }
                joyX += (tx - joyX) * 0.45f
                joyY += (ty - joyY) * 0.45f
                b.touchMove(0, joyX, joyY)
            } else if (joyDown) {
                b.touchUp(0)
                joyDown = false
            }
        }

        val fp = layout.get(Role.FIRE)
        if (fp != null) {
            if (fire && !fireDown) {
                b.touchDown(2, fp.x * w, fp.y * h)
                fireDown = true
            } else if (!fire && fireDown) {
                b.touchUp(2)
                fireDown = false
            }
        }

        val shell = b.name == "Shell"
        if (!lookActive) {
            var dx = 0f
            var dy = 0f
            var have = false
            var absL = false
            synchronized(lookLock) {
                if (pendLook) {
                    dx = pendDx
                    dy = pendDy
                    absL = pendAbs
                    pendLook = false
                    have = true
                }
            }
            if (have) {
                if (Prefs.invertY && !absL) dy = -dy
                val ax = layout.look.x * w
                val ay = layout.look.y * h
                val hw = layout.lookHalfW * w
                val hh = 0.28f * h
                lx0 = (ax - dx / 2f).coerceIn(ax - hw, ax + hw)
                lx1 = (lx0 + dx).coerceIn(ax - hw, ax + hw)
                ly0 = (ay - dy / 2f).coerceIn(ay - hh, ay + hh)
                ly1 = (ly0 + dy).coerceIn(ay - hh, ay + hh)
                DragLog.dx = lx1 - lx0
                DragLog.dy = ly1 - ly0
                b.touchDown(1, lx0, ly0)
                lookActive = true
                lookStep = 0
            }
        } else {
            lookStep++
            val steps = if (shell) 5 else 2
            val f = min(1f, lookStep / steps.toFloat())
            b.touchMove(1, lx0 + (lx1 - lx0) * f, ly0 + (ly1 - ly0) * f)
            if (lookStep >= steps) {
                b.touchUp(1)
                lookActive = false
                DragLog.at = SystemClock.uptimeMillis()
                DragLog.seq = DragLog.seq + 1
            }
        }

        if (tapActive) {
            if (now >= tapUntil) {
                b.touchUp(3)
                tapActive = false
            }
        } else {
            val p = tapQ.poll()
            if (p != null) {
                b.touchDown(3, p.x * w, p.y * h)
                tapActive = true
                tapUntil = now + 60
            }
        }
        b.flush()
    }
}
