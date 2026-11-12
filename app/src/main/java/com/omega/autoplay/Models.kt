package com.omega.autoplay

import android.graphics.PointF

/** Detection in normalized coordinates (0..1). */
data class Detection(val label: String, val conf: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

data class Plan(
    val mx: Float, val my: Float,      // movement joystick -1..1 (my=-1 forward)
    val ax: Float, val ay: Float,      // aim delta, normalized screen units
    val fire: Boolean, val heal: Boolean, val jump: Boolean
)

class Controls(
    val w: Int, val h: Int,
    var joy: PointF, var joyR: Float,
    var fire: PointF, var heal: PointF, var jump: PointF,
    var look: PointF, var lookR: Float
) {
    companion object {
        fun defaults(w: Int, h: Int) = Controls(
            w, h,
            PointF(0.14f * w, 0.68f * h), 0.11f * h,
            PointF(0.88f * w, 0.66f * h), PointF(0.75f * w, 0.86f * h), PointF(0.93f * w, 0.45f * h),
            PointF(0.65f * w, 0.35f * h), 0.18f * w
        )
    }

    /** Auto-detect UI controls from the model's detections (labels: joystick, fire, heal, jump). */
    fun update(d: List<Detection>) {
        for (x in d) {
            if (x.conf < 0.5f) continue
            val p = PointF(x.cx * w, x.cy * h)
            when (x.label) {
                "joystick" -> { joy = p; joyR = maxOf(x.w * w, x.h * h) * 0.5f }
                "fire" -> fire = p
                "heal" -> heal = p
                "jump" -> jump = p
            }
        }
    }
}

object Bus {
    @Volatile var a11y: AutoPlayAccessibilityService? = null
}
