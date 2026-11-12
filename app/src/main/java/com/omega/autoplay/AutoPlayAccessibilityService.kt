package com.omega.autoplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class AutoPlayAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { Bus.a11y = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { Bus.a11y = null; return super.onUnbind(intent) }

    private fun clampX(c: Controls, x: Float) = x.coerceIn(2f, c.w - 2f)
    private fun clampY(c: Controls, y: Float) = y.coerceIn(2f, c.h - 2f)

    private fun tap(c: Controls, x: Float, y: Float, dur: Long) =
        GestureDescription.StrokeDescription(Path().apply { moveTo(clampX(c, x), clampY(c, y)) }, 0, dur)

    private fun drag(c: Controls, x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) =
        GestureDescription.StrokeDescription(Path().apply {
            moveTo(clampX(c, x1), clampY(c, y1)); lineTo(clampX(c, x2), clampY(c, y2))
        }, 0, dur)

    /** One control tick: movement + aim + fire (+heal/jump) as simultaneous multitouch strokes. */
    fun perform(p: Plan, c: Controls, done: () -> Unit) {
        val dur = 140L
        val b = GestureDescription.Builder()
        var n = 0
        if (abs(p.mx) + abs(p.my) > 0.05f) {
            b.addStroke(drag(c, c.joy.x, c.joy.y, c.joy.x + p.mx * c.joyR, c.joy.y + p.my * c.joyR, dur)); n++
        }
        if (abs(p.ax) + abs(p.ay) > 0.01f) {
            val dx = (p.ax * c.w * 0.6f).coerceIn(-c.lookR, c.lookR)
            val dy = (p.ay * c.h * 0.6f).coerceIn(-c.lookR, c.lookR)
            b.addStroke(drag(c, c.look.x, c.look.y, c.look.x + dx, c.look.y + dy, dur)); n++
        }
        if (p.fire) { b.addStroke(tap(c, c.fire.x, c.fire.y, dur)); n++ }
        if (p.heal) { b.addStroke(tap(c, c.heal.x, c.heal.y, 80)); n++ }
        if (p.jump) { b.addStroke(tap(c, c.jump.x, c.jump.y, 80)); n++ }
        if (n == 0) { done(); return }
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done() }
            override fun onCancelled(g: GestureDescription?) { done() }
        }, null)
        if (!ok) done()
    }
}
