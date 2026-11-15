package com.omega.autoplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot

/** The floating play/stop button (drag it while stopped; one tap while running stops immediately). */
class Overlay(
    private val ctx: Context,
    private val onToggle: () -> Unit,
    private val onSnapshot: () -> Unit
) {
    companion object {
        const val IDLE = 0
        const val RUN = 1
        const val WAIT = 2
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val lp = WindowManager.LayoutParams(
        (84 * density).toInt(), (78 * density).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = Prefs.ovX
        y = Prefs.ovY
        alpha = 0.85f
    }
    private var view: BtnView? = null

    fun show() {
        if (view != null) return
        val v = BtnView(ctx)
        view = v
        try { wm.addView(v, lp) } catch (t: Throwable) { AppLog.d("overlay add failed: " + t.message) }
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Throwable) { }
        view = null
    }

    fun setState(s: Int) {
        view?.post {
            view?.state = s
            view?.invalidate()
        }
    }

    fun setInfo(t: String) {
        view?.post {
            view?.info = t
            view?.invalidate()
        }
    }

    private inner class BtnView(c: Context) : View(c) {
        var state = IDLE
        var info = ""
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longFired = false
        private var ignoreUp = false
        private val longRun = Runnable {
            longFired = true
            onSnapshot()
        }

        override fun onDraw(c: Canvas) {
            val d = 52f * density
            val cx = width / 2f
            val cy = d / 2f + 4f * density
            p.style = Paint.Style.FILL
            p.color = when (state) {
                RUN -> Color.parseColor("#22C55E")
                WAIT -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#7C5CFF")
            }
            c.drawCircle(cx, cy, d / 2f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * density
            p.color = Color.WHITE
            c.drawCircle(cx, cy, d / 2f - 1.5f * density, p)
            p.style = Paint.Style.FILL
            if (state == RUN) {
                val s = d * 0.26f
                c.drawRect(RectF(cx - s, cy - s, cx + s, cy + s), p)
            } else if (state == IDLE) {
                val s = d * 0.26f
                val path = Path()
                path.moveTo(cx - s * 0.7f, cy - s)
                path.lineTo(cx + s, cy)
                path.lineTo(cx - s * 0.7f, cy + s)
                path.close()
                c.drawPath(path, p)
            } else {
                c.drawCircle(cx, cy, d * 0.12f, p)
            }
            if (info.isNotEmpty()) c.drawText(info, cx, cy + d / 2f + 11f * density, tp)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (BotState.running) {
                        ignoreUp = true
                        onToggle()
                        return true
                    }
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longFired = false
                    postDelayed(longRun, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!ignoreUp && !BotState.running) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (moved || hypot(dx, dy) > slop) {
                            moved = true
                            removeCallbacks(longRun)
                            lp.x = (startX + dx).toInt()
                            lp.y = (startY + dy).toInt()
                            try { wm.updateViewLayout(this, lp) } catch (_: Throwable) { }
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longRun)
                    if (ignoreUp) {
                        ignoreUp = false
                        return true
                    }
                    if (moved) {
                        Prefs.ovX = lp.x
                        Prefs.ovY = lp.y
                    } else if (!longFired) {
                        onToggle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longRun)
                    ignoreUp = false
                }
            }
            return true
        }
    }
}

/** Full-screen, untouchable layer that draws the detector's boxes (turn on from settings; it is captured too, so use only to debug). */
class DebugView(c: Context) : View(c) {
    @Volatile var dets: List<Det> = emptyList()
        set(v) {
            field = v
            postInvalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(c: Canvas) {
        for (d in dets) {
            p.color = when (d.role) {
                Role.ENEMY, Role.ENEMY_HEAD -> Color.RED
                Role.ALLY -> Color.GREEN
                Role.LOOT -> Color.YELLOW
                else -> Color.CYAN
            }
            c.drawRect(d.x1 * width, d.y1 * height, d.x2 * width, d.y2 * height, p)
            c.drawText(d.role.name.take(7) + " " + (d.conf * 100).toInt(), d.x1 * width, d.y1 * height - 4f, tp)
        }
    }
}
