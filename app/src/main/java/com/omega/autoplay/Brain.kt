package com.omega.autoplay

import kotlin.math.abs
import kotlin.math.sin

/** Rule-based tactical brain. Replace/extend with a learned policy later. */
class Brain {
    private var tick = 0
    private var strafe = 1f

    private fun c(v: Float) = v.coerceIn(-1f, 1f)

    fun step(d: List<Detection>): Plan {
        tick++
        if (tick % 14 == 0) strafe = -strafe
        val enemy = d.filter { it.label == "enemy" && it.conf > 0.45f }.maxByOrNull { it.w * it.h }
        val weapon = d.filter { it.label == "weapon" }.maxByOrNull { it.conf }
        val medkit = d.filter { it.label == "medkit" }.maxByOrNull { it.conf }
        val lowHp = d.any { it.label == "low_health" }
        val outZone = d.any { it.label == "zone_edge" }

        if (lowHp) {
            if (medkit != null) return Plan(0f, -1f, medkit.cx - 0.5f, 0f, false, false, false)
            val aligned = enemy != null && abs(enemy.cx - 0.5f) < 0.08f
            return Plan(c(strafe * 0.8f), 0.9f, 0f, 0f, aligned, true, false)
        }
        if (enemy != null) {
            val aligned = abs(enemy.cx - 0.5f) < 0.07f
            val my = if (enemy.h < 0.2f) -0.6f else if (enemy.h > 0.45f) 0.4f else 0f
            return Plan(strafe * 0.9f, my, (enemy.cx - 0.5f) * 1.2f, (enemy.cy - 0.42f) * 0.8f,
                aligned || enemy.h > 0.35f, false, tick % 25 == 0)
        }
        if (weapon != null) return Plan(0f, -1f, weapon.cx - 0.5f, 0f, false, false, false)
        if (outZone) return Plan(0f, -1f, 0.25f, 0f, false, false, false)
        // patrol: move forward and sweep the camera
        return Plan(0f, -0.9f, (sin(tick / 10.0) * 0.15).toFloat(), 0f, false, false, tick % 40 == 0)
    }
}
