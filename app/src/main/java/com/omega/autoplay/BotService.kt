package com.omega.autoplay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

class BotService : Service() {
    companion object {
        const val ACT_START = "com.omega.autoplay.START"
        const val ACT_STOP = "com.omega.autoplay.STOP"
        const val EX_CODE = "code"
        const val EX_DATA = "data"

        @Volatile var instance: BotService? = null
        @Volatile var status: String = "متوقف"
    }

    private val main = Handler(Looper.getMainLooper())
    private val capThread = HandlerThread("ap-capture")
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var yolo: Yolo? = null
    private var overlay: Overlay? = null
    private var debugView: DebugView? = null
    private lateinit var layout: Layout
    private lateinit var brain: Brain
    private lateinit var perception: Perception
    private lateinit var controller: Controller
    @Volatile private var runFlag = false
    @Volatile private var starting = false
    private var gone = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        capThread.start()
        layout = Layout().also { it.load(Prefs.layoutJson) }
        brain = Brain(layout)
        perception = Perception(layout)
        controller = Controller(layout, packageName)
        instance = this
        BotState.stopRequest = { main.post { stopBot() } }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startFg("جاهز: افتح لعبتك واضغط ▶")
        when (i?.action) {
            ACT_START -> if (projection == null) setupProjection(i)
            ACT_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() }
    }

    private fun startFg(text: String) {
        Util.ensureChannel(this)
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, Util.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AutoPlay Ω")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "إيقاف الخدمة", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupProjection(i: Intent) {
        val code = i.getIntExtra(EX_CODE, 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>(EX_DATA)
        if (data == null) {
            toast("مفيش إذن التقاط الشاشة")
            shutdown()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    AppLog.d("projection stopped")
                    shutdown()
                }
            }
        }, main)
        status = "بحمّل النموذج..."
        Thread {
            Process.setThreadPriority(5)
            val (y, info) = ModelStore.build(this)
            yolo = y
            perception.hudKnown = y?.hasHudClasses() ?: false
            AppLog.d("model: " + info.msg)
            main.post {
                if (gone) return@post
                val side = if (y != null) max(y.inW, y.inH) else 640
                capture = ScreenCapture(this, mp, Handler(capThread.looper), side)
                overlay = Overlay(this, { toggle() }, { toggleRecord() }).also { it.show() }
                if (Prefs.debugBoxes) addDebug()
                status = if (y != null) "جاهز" else "جاهز (بدون نموذج: وضع تجريبي)"
                if (y == null) toast("مفيش نموذج: التطبيق هيمشي ويلفّ بس (وضع تجريبي)")
            }
        }.start()
    }

    private fun addDebug() {
        val v = DebugView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.alpha = 0.8f
        try {
            wm.addView(v, p)
            debugView = v
        } catch (t: Throwable) {
            AppLog.d("debug view failed: " + t.message)
        }
    }

    private fun toggle() {
        if (recording) {
            recording = false
        } else if (runFlag) {
            stopBot()
        } else {
            startBot()
        }
    }

    @Volatile private var recording = false
    private var recCount = 0

    /** Long-press on the floating button: record frames of YOUR game (for labelling and training your own detector). */
    private fun toggleRecord() {
        if (recording) {
            recording = false
            return
        }
        if (runFlag || starting) {
            toast("وقّف البوت الأول وبعدين سجّل")
            return
        }
        val pkg = Prefs.targetPkg
        if (pkg.isEmpty()) {
            toast("اختار لعبتك من التطبيق الأول")
            return
        }
        if (Safety.isBlocked(pkg)) {
            toast("دي لعبة أونلاين معروفة. التسجيل للعبتك الأوفلاين بس")
            return
        }
        if (Build.VERSION.SDK_INT < 29) {
            toast("التسجيل محتاج أندرويد 10 أو أحدث")
            return
        }
        val cap = capture ?: return
        recording = true
        recCount = 0
        overlay?.setState(Overlay.REC)
        cap.resume()
        toast("بدأ تسجيل لقطات من لعبتك (لقطة كل ثانية تقريبًا). اضغط الزر العايم عشان توقف")
        Thread({ recLoop(cap) }, "ap-rec").start()
    }

    private fun recLoop(cap: ScreenCapture) {
        Process.setThreadPriority(5)
        var last = 0L
        var first = true
        while (recording) {
            val f = cap.awaitFrame(500) ?: continue
            val now = SystemClock.uptimeMillis()
            if (now - last < 900) continue
            last = now
            if (first) {
                first = false
                try { cap.saveFrame(f, File(filesDir, "frame.png")) } catch (_: Throwable) { }
            }
            if (saveFrameToDownloads(f)) {
                recCount++
                overlay?.setInfo("⏺ $recCount")
            }
            if (recCount >= 2000) recording = false
        }
        cap.pause()
        overlay?.setState(Overlay.IDLE)
        overlay?.setInfo("")
        toast("وقف التسجيل: $recCount لقطة في التنزيلات ← AutoPlayFrames")
    }

    private fun saveFrameToDownloads(f: Frame): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val bmp = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(f.px))
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, "frame_" + System.currentTimeMillis() + ".jpg")
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AutoPlayFrames")
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            var ok = false
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { ok = bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            bmp.recycle()
            ok
        } catch (t: Throwable) {
            AppLog.d("save frame failed: " + t.message)
            false
        }
    }

    private fun startBot() {
        if (runFlag || starting) return
        val pkg = Prefs.targetPkg
        if (pkg.isEmpty()) {
            toast("اختار لعبتك من التطبيق الأول")
            return
        }
        if (Safety.isBlocked(pkg)) {
            toast("اللعبة دي أونلاين معروفة. التطبيق للعبتك الأوفلاين بس")
            return
        }
        val cap = capture
        if (cap == null) {
            toast("لسه بجهّز، استنى ثانيتين")
            return
        }
        starting = true
        overlay?.setState(Overlay.WAIT)
        status = "بجهّز اللمس..."
        Thread {
            layout.reset()
            layout.load(Prefs.layoutJson)
            val (b, why) = Backends.create(this, Prefs.backend)
            if (b == null) {
                toast("مش قادر أبدأ: $why")
                starting = false
                status = "فشل التشغيل"
                overlay?.setState(Overlay.IDLE)
                return@Thread
            }
            AppLog.d("input backend: $why")
            cap.resume()
            val first = cap.awaitFrame(2000)
            if (first == null) {
                toast("مفيش صورة من الشاشة. جرّب تاني")
                cap.pause()
                b.close()
                starting = false
                overlay?.setState(Overlay.IDLE)
                return@Thread
            }
            try { cap.saveFrame(first, File(filesDir, "frame.png")) } catch (t: Throwable) { AppLog.d("save frame: " + t.message) }
            brain.lookGapMs = if (b.name == "Shell") 110L else 170L
            controller.start(cap.realW, cap.realH, b)
            val now = SystemClock.uptimeMillis()
            brain.reset(now)
            perception.reset(now)
            runFlag = true
            BotState.running = true
            starting = false
            status = "شغّال (" + b.name + ")"
            overlay?.setState(Overlay.RUN)
            Thread({ loop(cap) }, "ap-loop").start()
        }.start()
    }

    private fun stopBot() {
        if (!runFlag && !starting) return
        runFlag = false
        BotState.running = false
        try { controller.stop() } catch (t: Throwable) { AppLog.d("controller stop: " + t.message) }
        capture?.pause()
        Prefs.layoutJson = layout.toJson()
        overlay?.setState(Overlay.IDLE)
        overlay?.setInfo("")
        status = "متوقف"
        AppLog.d("stopped")
    }

    private fun loop(cap: ScreenCapture) {
        Process.setThreadPriority(5)
        var frames = 0
        var t0 = SystemClock.uptimeMillis()
        var lastRot = 0L
        while (runFlag) {
            val f = cap.awaitFrame(300) ?: continue
            val tStart = SystemClock.uptimeMillis()
            try {
                if (tStart - lastRot > 700) {
                    lastRot = tStart
                    if (cap.checkRotation()) controller.setScreen(cap.realW, cap.realH)
                }
                val y = yolo
                val dets = if (y != null) y.detect(f, Prefs.conf, 0.45f) else emptyList()
                val w = perception.build(f, dets, tStart)
                val c = brain.decide(w, cap.realW, cap.realH)
                controller.apply(c)
                debugView?.dets = dets
                frames++
                if (tStart - t0 >= 1000) {
                    val st = if (controller.paused) "اللعبة مش قدامك" else c.status
                    val txt = "$frames fps · " + (y?.lastMs?.toInt() ?: 0) + "ms · " + st
                    overlay?.setInfo(txt)
                    status = "شغّال: $txt"
                    frames = 0
                    t0 = tStart
                }
            } catch (e: Throwable) {
                AppLog.d("loop: " + e.javaClass.simpleName + " " + e.message)
                try { Thread.sleep(60) } catch (_: InterruptedException) { }
            }
            // frame pacing: optional cap set by the user (default 30 = effectively uncapped)
            val fpsCap = Prefs.maxFps
            val minPeriod = 1000L / max(1, fpsCap)
            val spent = SystemClock.uptimeMillis() - tStart
            if (spent < minPeriod) {
                try { Thread.sleep(minPeriod - spent) } catch (_: InterruptedException) { }
            }
        }
    }

    /** Long-press on the floating button: save the current game frame so buttons can be placed in the editor. */
    private fun snapshot() {
        val cap = capture ?: return
        Thread {
            val was = runFlag
            if (!was) cap.resume()
            val f = if (was) cap.last() else cap.awaitFrame(1500)
            if (!was) cap.pause()
            if (f != null) {
                try {
                    cap.saveFrame(f, File(filesDir, "frame.png"))
                    toast("اتخزّنت لقطة من اللعبة. افتح التطبيق ← ضبط الأزرار")
                } catch (t: Throwable) {
                    toast("فشل حفظ اللقطة")
                }
            } else {
                toast("مفيش صورة")
            }
        }.start()
    }

    private fun shutdown() {
        if (gone) return
        gone = true
        try { stopBot() } catch (_: Throwable) { }
        try { overlay?.hide() } catch (_: Throwable) { }
        try { debugView?.let { wm.removeView(it) } } catch (_: Throwable) { }
        debugView = null
        try { capture?.release() } catch (_: Throwable) { }
        capture = null
        try { projection?.stop() } catch (_: Throwable) { }
        projection = null
        try { yolo?.close() } catch (_: Throwable) { }
        yolo = null
        status = "متوقف"
        BotState.stopRequest = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!gone) shutdown()
        capThread.quitSafely()
        super.onDestroy()
    }
}
