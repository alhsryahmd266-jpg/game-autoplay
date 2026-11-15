package com.omega.autoplay

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Place the on-screen controls by tapping them on a screenshot of the game. */
class LayoutActivity : Activity() {
    private lateinit var lay: Layout
    private lateinit var view: LView
    private var sel = "JOYSTICK"
    private val keys = Role.CONTROLS.map { it.name } + listOf("LOOK", "CROSS")
    private val chips = HashMap<String, Button>()
    private lateinit var hint: TextView

    private fun label(k: String): String = when (k) {
        "LOOK" -> "منطقة الكاميرا"
        "CROSS" -> "مؤشر التصويب"
        else -> Role.valueOf(k).ar
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        lay = Layout()
        lay.load(Prefs.layoutJson)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val bar = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4))
        for (k in keys) {
            val bt = Ui.button(this, label(k), Ui.ACC2) { select(k) }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = Ui.dp(this, 6)
            bt.layoutParams = lp
            row.addView(bt)
            chips[k] = bt
        }
        bar.addView(row)
        root.addView(bar)

        hint = Ui.text(this, "", 12f, Ui.SUB)
        hint.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 2))
        root.addView(hint)

        view = LView(this)
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8))
        val sbLabel = Ui.text(this, "حجم العصا", 12f, Ui.SUB)
        bottom.addView(sbLabel)
        val sb = SeekBar(this)
        sb.max = 18
        sb.progress = ((lay.joyR * 100).toInt() - 4).coerceIn(0, 18)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                lay.joyR = (p + 4) / 100f
                if (u) {
                    save()
                    view.invalidate()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bottom.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = Ui.button(this, "مسح العنصر", Ui.BAD) {
            if (sel != "LOOK" && sel != "CROSS") {
                val r = Role.valueOf(sel)
                lay.pos.remove(r)
                lay.locked.remove(r)
                save()
                view.invalidate()
            }
        }
        clear.layoutParams = LinearLayout.LayoutParams(-2, -2)
        bottom.addView(clear)
        val done = Ui.button(this, "تم", Ui.ACC) { finish() }
        done.layoutParams = LinearLayout.LayoutParams(-2, -2)
        bottom.addView(done)
        root.addView(bottom)
        setContentView(root)
        select("JOYSTICK")
    }

    private fun select(k: String) {
        sel = k
        for ((key, bt) in chips) bt.background = Ui.round(if (key == k) Ui.ACC else Ui.ACC2, Ui.dp(this, 12).toFloat())
        hint.text = "اختر عنصر من فوق، وبعدين المس مكانه على اللقطة. المحدد دلوقتي: " + label(k)
    }

    private fun save() {
        Prefs.layoutJson = lay.toJson()
    }

    private fun place(nx: Float, ny: Float) {
        when (sel) {
            "LOOK" -> lay.look.set(nx, ny)
            "CROSS" -> lay.cross.set(nx, ny)
            else -> lay.place(Role.valueOf(sel), nx, ny)
        }
        save()
        view.invalidate()
    }

    private inner class LView(c: Context) : View(c) {
        private val bmp: Bitmap? = try {
            val f = File(filesDir, "frame.png")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (_: Throwable) {
            null
        }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private var dst = RectF()

        private fun fit(): RectF {
            val bw = bmp?.width?.toFloat() ?: 20f
            val bh = bmp?.height?.toFloat() ?: 9f
            val s = minOf(width / bw, height / bh)
            val w = bw * s
            val h = bh * s
            val l = (width - w) / 2f
            val t = (height - h) / 2f
            return RectF(l, t, l + w, t + h)
        }

        override fun onDraw(c: Canvas) {
            dst = fit()
            if (bmp != null) {
                c.drawBitmap(bmp, null, dst, null)
            } else {
                p.style = Paint.Style.FILL
                p.color = 0xFF1B2338.toInt()
                c.drawRect(dst, p)
                c.drawText("مفيش لقطة: شغّل البوت مرة داخل اللعبة (أو ضغطة طويلة على الزر العايم)", dst.left + 20f, dst.centerY(), tp)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            for ((r, v) in lay.pos) {
                val x = dst.left + v.x * dst.width()
                val y = dst.top + v.y * dst.height()
                p.color = if (r.name == sel) Ui.ACC else Color.WHITE
                val rad = if (r == Role.JOYSTICK) lay.joyR * dst.height() else 34f
                c.drawCircle(x, y, rad, p)
                c.drawText(r.ar, x - rad, y - rad - 6f, tp)
            }
            p.color = if (sel == "LOOK") Ui.ACC else Color.YELLOW
            val lx = dst.left + lay.look.x * dst.width()
            val ly = dst.top + lay.look.y * dst.height()
            val hw = lay.lookHalfW * dst.width()
            c.drawRect(lx - hw, ly - 0.28f * dst.height(), lx + hw, ly + 0.28f * dst.height(), p)
            c.drawText("كاميرا", lx - hw, ly - 0.28f * dst.height() - 6f, tp)
            p.color = if (sel == "CROSS") Ui.ACC else Color.RED
            val cx = dst.left + lay.cross.x * dst.width()
            val cy = dst.top + lay.cross.y * dst.height()
            c.drawLine(cx - 24f, cy, cx + 24f, cy, p)
            c.drawLine(cx, cy - 24f, cx, cy + 24f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val d = fit()
                if (d.contains(e.x, e.y)) {
                    place((e.x - d.left) / d.width(), (e.y - d.top) / d.height())
                } else {
                    Toast.makeText(context, "المس جوه اللقطة", Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
    }
}
