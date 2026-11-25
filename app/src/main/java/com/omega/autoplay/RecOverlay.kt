package com.omega.autoplay

/** The small, dedicated floating button for "تسجيل فريم" — fully independent from Overlay.kt (the bot's button). */

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot

class RecOverlay(
    private val ctx: Context,
    private val onToggle: () -> Unit,
    private val onConvert: () -> Unit
) {
    companion object {
        const val IDLE = 0
        const val RECORDING = 1
        const val CONVERTING = 2
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val lp = WindowManager.LayoutParams(
        (64 * density).toInt(), (64 * density).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = Prefs.recOvX
        y = Prefs.recOvY
        alpha = 0.9f
    }
    private var view: BtnView? = null

    fun show() {
        if (view != null) return
        val v = BtnView(ctx)
        view = v
        try { wm.addView(v, lp) } catch (t: Throwable) { AppLog.d("تسجيل فريم: فشل إظهار الزر: " + t.message) }
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (_: Throwable) { }
        view = null
    }

    val isShown: Boolean get() = view != null

    fun setState(s: Int) {
        view?.post { view?.state = s; view?.invalidate() }
    }

    fun setCount(n: Int) {
        view?.post { view?.count = n; view?.invalidate() }
    }

    fun setProgress(done: Int, total: Int) {
        view?.post { view?.progText = "$done/$total"; view?.invalidate() }
    }

    private inner class BtnView(c: Context) : View(c) {
        var state = IDLE
        var count = 0
        var progText = ""
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f * density
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
        private val longRun = Runnable {
            longFired = true
            onConvert()
        }

        override fun onDraw(c: Canvas) {
            val d = 40f * density
            val cx = width / 2f
            val cy = d / 2f + 4f * density
            p.style = Paint.Style.FILL
            p.color = when (state) {
                RECORDING -> Color.parseColor("#E23A3A")
                CONVERTING -> Color.parseColor("#F5A623")
                else -> Color.parseColor("#2A3142")
            }
            c.drawCircle(cx, cy, d / 2f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * density
            p.color = Color.WHITE
            c.drawCircle(cx, cy, d / 2f - 1f * density, p)
            p.style = Paint.Style.FILL
            when (state) {
                RECORDING -> {
                    val s = d * 0.22f
                    c.drawRect(cx - s, cy - s, cx + s, cy + s, p)
                }
                CONVERTING -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f * density
                    p.color = Color.WHITE
                    val ang = ((System.currentTimeMillis() / 6) % 360).toFloat()
                    c.drawArc(cx - d * 0.28f, cy - d * 0.28f, cx + d * 0.28f, cy + d * 0.28f, ang, 260f, false, p)
                    postInvalidateDelayed(40)
                }
                else -> {
                    p.color = Color.parseColor("#E23A3A")
                    c.drawCircle(cx, cy, d * 0.22f, p)
                }
            }
            val label = if (state == CONVERTING) progText else if (count > 0) count.toString() else "فريم"
            c.drawText(label, cx, cy + d / 2f + 11f * density, tp)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    postDelayed(longRun, 800)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || hypot(dx, dy) > slop) {
                        moved = true
                        removeCallbacks(longRun)
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(this, lp) } catch (_: Throwable) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longRun)
                    if (moved) {
                        Prefs.recOvX = lp.x; Prefs.recOvY = lp.y
                    } else if (!longFired) {
                        onToggle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(longRun)
            }
            return true
        }
    }
}
