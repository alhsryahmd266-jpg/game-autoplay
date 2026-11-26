package com.omega.autoplay

/**
 * خدمة "تسجيل فريم" — fully independent of BotService. Owns its own MediaProjection/ScreenCapture
 * and its own small floating button (RecOverlay). Never starts, stops, or shares state with the bot.
 */

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import java.io.File

class RecService : Service() {
    companion object {
        const val ACT_START = "com.omega.autoplay.REC_START"
        const val ACT_STOP = "com.omega.autoplay.REC_STOP"
        const val EX_CODE = "code"
        const val EX_DATA = "data"

        @Volatile var instance: RecService? = null
        @Volatile var status: String = "متوقف"
    }

    private val main = Handler(Looper.getMainLooper())
    private val capThread = HandlerThread("rec-capture")
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var overlay: RecOverlay? = null
    private lateinit var recorder: Recorder
    private var touchLogger: RecTouchLogger? = null
    @Volatile private var recording = false
    @Volatile private var converting = false
    private var captureThreadRef: Thread? = null
    private var gone = false
    private var toldTouchStatus = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        capThread.start()
        recorder = Recorder(this)
        instance = this
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startFg("جاهز")
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
            this, 2, Intent(this, RecService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, Util.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("تسجيل فريم")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "إيقاف", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
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
                main.post { shutdown() }
            }
        }, main)
        capture = ScreenCapture(this, mp, Handler(capThread.looper), 640)
        overlay = RecOverlay(this, { toggleRecording() }, { startConvert() }).also {
            it.show()
            it.setCount(RecorderPaths.count(this))
        }
        status = "جاهز"
    }

    private fun toggleRecording() {
        if (converting) {
            toast("مستنيين التحويل يخلص")
            return
        }
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val cap = capture ?: return
        cap.resume()
        recording = true
        overlay?.setState(RecOverlay.RECORDING)
        status = "بيسجّل"
        val save = Prefs.recSaveRate
        captureThreadRef = Thread({
            Process.setThreadPriority(5)
            while (recording) {
                val f = cap.awaitFrame(300) ?: continue
                val touchSnap = if (Prefs.recDrawTouches) (touchLogger?.snapshot() ?: emptyList()) else emptyList()
                if (recorder.maybeSave(f, save, touchSnap)) {
                    val n = RecorderPaths.count(this)
                    overlay?.setCount(n)
                }
            }
        }, "ap-rec-capture").also { it.start() }

        if (touchLogger == null) {
            val tl = RecTouchLogger(this)
            touchLogger = tl
            Thread({
                val ok = tl.start(File(RecorderPaths.stage(this), "touches.jsonl"))
                if (!toldTouchStatus) {
                    toldTouchStatus = true
                    toast(if (ok) "تسجيل اللمس شغّال كمان ✔" else "تسجيل من غير بيانات اللمس (${tl.lastError ?: "الشل مش متاح"})")
                }
            }, "ap-touchlog-start").start()
        }
    }

    private fun stopRecording() {
        recording = false
        try { captureThreadRef?.join(600) } catch (_: Throwable) { }
        captureThreadRef = null
        capture?.pause()
        overlay?.setState(RecOverlay.IDLE)
        status = "واقف، " + RecorderPaths.count(this) + " لقطة محفوظة"
    }

    private fun startConvert() {
        if (converting) return
        if (recording) stopRecording()
        touchLogger?.stop()
        touchLogger = null
        val total = RecorderPaths.count(this)
        if (total == 0) {
            toast("مفيش لقطات متسجّلة")
            return
        }
        converting = true
        overlay?.setState(RecOverlay.CONVERTING)
        status = "بيحوّل..."
        Thread({
            Process.setThreadPriority(5)
            val res = recorder.convert(0.3f) { done, tot -> overlay?.setProgress(done, tot) }
            converting = false
            overlay?.setState(RecOverlay.IDLE)
            overlay?.setCount(0)
            status = "جاهز"
            if (res.error != null && res.zipFile == null) {
                toast("فشل التحويل: " + res.error)
            } else {
                val cl = "شخص ${res.perClass.getOrElse(0){0}}، عربية ${res.perClass.getOrElse(1){0}}، طيارة ${res.perClass.getOrElse(2){0}}"
                toast("تم ✔ ${res.images} صورة في التنزيلات ← TasjeelFrame\nمصنّف تلقائي: $cl")
            }
        }, "ap-rec-convert").start()
    }

    private fun shutdown() {
        if (gone) return
        gone = true
        recording = false
        try { captureThreadRef?.join(600) } catch (_: Throwable) { }
        touchLogger?.stop()
        touchLogger = null
        try { overlay?.hide() } catch (_: Throwable) { }
        overlay = null
        try { capture?.release() } catch (_: Throwable) { }
        capture = null
        try { projection?.stop() } catch (_: Throwable) { }
        projection = null
        status = "متوقف"
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
