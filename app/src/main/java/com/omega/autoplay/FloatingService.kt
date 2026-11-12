package com.omega.autoplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var capturer: ScreenCapturer? = null
    private var proj: MediaProjection? = null
    private var detector: Detector = NullDetector()
    private val brain = Brain()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (capturer != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("ap", "AutoPlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ap").setContentTitle("Game AutoPlay")
            .setContentText("الزر العائم جاهز").setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        proj = p
        capturer = ScreenCapturer(p)
        detector = try { TfliteDetector(this) } catch (e: Throwable) { NullDetector() }
        addBubble()
        return START_NOT_STICKY
    }

    private fun addBubble() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val tv = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1E88E5.toInt()) }
        }
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) + abs(dy) > 12) moved = true
                    lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                    wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggle(); true }
                else -> false
            }
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    private fun toggle() { if (running) stopPlay() else startPlay() }

    private fun realSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }

    private fun startPlay() {
        val a11y = Bus.a11y
        if (a11y == null) { Toast.makeText(this, "فعّل خدمة إمكانية الوصول أولاً", Toast.LENGTH_LONG).show(); return }
        val cap = capturer ?: return
        running = true
        bubble?.text = "■"
        val (w, h) = realSize()
        val dpi = resources.displayMetrics.densityDpi
        worker = Thread {
            cap.start(w, h, dpi)
            val controls = Controls.defaults(w, h)
            var frames = 0
            while (running) {
                val bmp = cap.latest()
                if (bmp == null) { Thread.sleep(20); continue }
                val dets = try { detector.detect(bmp) } catch (e: Throwable) { emptyList() }
                if (frames < 8) { controls.update(dets); frames++ }
                val plan = brain.step(dets)
                val latch = CountDownLatch(1)
                a11y.perform(plan, controls) { latch.countDown() }
                latch.await(500, TimeUnit.MILLISECONDS)
            }
            cap.stop()
        }.also { it.start() }
    }

    private fun stopPlay() {
        running = false
        bubble?.text = "▶"
    }

    override fun onDestroy() {
        running = false
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        capturer?.release()
        super.onDestroy()
    }
}
