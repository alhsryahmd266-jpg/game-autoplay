package com.omega.autoplay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad); gravity = Gravity.CENTER_HORIZONTAL
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        root.addView(status)
        fun btn(t: String, f: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        btn("1) السماح بالظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) تفعيل خدمة إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3) تشغيل الزر العائم") { start() }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
    }

    override fun onResume() {
        super.onResume()
        val o = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val a = if (Bus.a11y != null) "✅" else "❌"
        status.text = "الظهور فوق التطبيقات: $o\nإمكانية الوصول: $a"
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "الخطوة 1 أولاً", Toast.LENGTH_SHORT).show(); return }
        if (Bus.a11y == null) { Toast.makeText(this, "الخطوة 2 أولاً", Toast.LENGTH_SHORT).show(); return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, FloatingService::class.java).putExtra("code", resultCode).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
