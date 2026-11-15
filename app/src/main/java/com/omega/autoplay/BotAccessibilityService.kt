package com.omega.autoplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: BotAccessibilityService? = null
        @Volatile var fgPkg: String = ""
    }

    override fun onServiceConnected() {
        Prefs.init(this)
        instance = this
        AppLog.d("accessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui") return
        fgPkg = p
    }

    override fun onInterrupt() {}

    /** While the bot runs, Volume Down is an emergency stop. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BotState.running && Prefs.volStop && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) BotState.stopRequest?.invoke()
            return true
        }
        return false
    }

    /** Package of the window the user is looking at right now (null when unknown). */
    fun currentPkg(): String? {
        return try {
            val r = rootInActiveWindow
            val p = r?.packageName?.toString()
            @Suppress("DEPRECATION")
            r?.recycle()
            p
        } catch (t: Throwable) {
            null
        }
    }
}
