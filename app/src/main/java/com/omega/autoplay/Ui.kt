package com.omega.autoplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = 0xFF0B0F1A.toInt()
    val CARD = 0xFF141B2D.toInt()
    val ACC = 0xFF3DDC97.toInt()
    val ACC2 = 0xFF7C5CFF.toInt()
    val TXT = 0xFFE8ECF5.toInt()
    val SUB = 0xFF9AA4BF.toInt()
    val BAD = 0xFFFF5C6C.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, r: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = r
        return g
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = round(CARD, dp(c, 16).toFloat())
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(c, 12)
        l.layoutParams = lp
        return l
    }

    fun text(c: Context, s: String, size: Float = 14f, color: Int = TXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    fun button(c: Context, s: String, color: Int = ACC2, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = s
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 14f
        b.background = round(color, dp(c, 12).toFloat())
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(c, 8)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
