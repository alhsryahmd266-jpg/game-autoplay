package com.omega.autoplay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.omega.autoplay.adb.GbAdbManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Touch injection abstraction. Pointer ids are chosen by the caller (0..9).
 * touchMove only records; flush() sends.
 */
interface InputBackend {
    val name: String
    fun touchDown(id: Int, x: Float, y: Float)
    fun touchMove(id: Int, x: Float, y: Float)
    fun touchUp(id: Int)
    fun flush()
    fun releaseAll()
    fun close()
}

object Backends {
    /** pref: 0 = auto (shell if paired, else accessibility), 1 = shell only, 2 = accessibility only. */
    fun create(ctx: Context, pref: Int): Pair<InputBackend?, String> {
        val errs = ArrayList<String>()
        if (pref == 1 || (pref == 0 && Prefs.shellPaired)) {
            val s = AdbShellInput(ctx)
            val e = s.prepare()
            if (e == null) return Pair(s, "Shell")
            errs.add("Shell: $e")
            s.close()
            if (pref == 1) return Pair(null, errs.joinToString(" | "))
        }
        if (BotAccessibilityService.instance != null) return Pair(AccessibilityInput(), "Accessibility")
        errs.add("Accessibility: الخدمة مش مفعّلة")
        return Pair(null, errs.joinToString(" | "))
    }
}

/** Accessibility backend: pointers are kept down by chaining "continued" strokes (each dispatch is a ~40 ms gesture). */
class AccessibilityInput : InputBackend {
    override val name = "Accessibility"

    private class P(val id: Int, var x: Float, var y: Float) {
        var stroke: GestureDescription.StrokeDescription? = null
        var lastX = x
        var lastY = y
        var ending = false
    }

    private val ptrs = LinkedHashMap<Int, P>()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var again = false
    private val durMs = 40L

    @Synchronized
    override fun touchDown(id: Int, x: Float, y: Float) {
        val p = ptrs[id]
        if (p != null && !p.ending) {
            p.x = x
            p.y = y
        } else {
            ptrs[id] = P(id, x, y)
        }
    }

    @Synchronized
    override fun touchMove(id: Int, x: Float, y: Float) {
        val p = ptrs[id] ?: return
        p.x = x
        p.y = y
    }

    @Synchronized
    override fun touchUp(id: Int) {
        val p = ptrs[id] ?: return
        p.ending = true
    }

    override fun flush() {
        main.post { pump() }
    }

    @Synchronized
    private fun pump() {
        if (ptrs.isEmpty()) return
        if (busy) {
            again = true
            return
        }
        val svc = BotAccessibilityService.instance ?: return
        val b = GestureDescription.Builder()
        val finished = ArrayList<Int>()
        var count = 0
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        for (p in ptrs.values) {
            if (count >= maxStrokes) break
            val path = Path()
            path.moveTo(p.lastX, p.lastY)
            if (p.x != p.lastX || p.y != p.lastY) path.lineTo(p.x, p.y)
            val willContinue = !p.ending
            val prev = p.stroke
            val stroke = if (prev == null) {
                GestureDescription.StrokeDescription(path, 0L, durMs, willContinue)
            } else {
                prev.continueStroke(path, 0L, durMs, willContinue)
            }
            p.stroke = stroke
            p.lastX = p.x
            p.lastY = p.y
            b.addStroke(stroke)
            if (p.ending) finished.add(p.id)
            count++
        }
        if (count == 0) return
        busy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                done(false)
            }

            override fun onCancelled(g: GestureDescription?) {
                done(true)
            }
        }
        val ok = try {
            svc.dispatchGesture(b.build(), cb, null)
        } catch (t: Throwable) {
            AppLog.d("dispatchGesture failed: " + t.message)
            false
        }
        if (!ok) busy = false
        for (id in finished) ptrs.remove(id)
    }

    @Synchronized
    private fun done(cancelled: Boolean) {
        busy = false
        if (cancelled) {
            for (p in ptrs.values) p.stroke = null
        }
        if (again) {
            again = false
            main.post { pump() }
        }
    }

    @Synchronized
    override fun releaseAll() {
        for (p in ptrs.values) p.ending = true
        main.post { pump() }
    }

    override fun close() {
        releaseAll()
    }
}

/**
 * Built-in shell backend: the app talks to the phone's own wireless-debugging daemon (paired once with the 6-digit code),
 * starts [ShellServer] as the shell user and sends it touch commands over the ADB stream (real multi-touch events).
 */
class AdbShellInput(private val ctx: Context) : InputBackend {
    override val name = "Shell"

    private var stream: AdbStream? = null
    private var out: OutputStream? = null
    private val pending = StringBuilder()

    @Volatile private var ready = false
    @Volatile private var error: String? = null
    @Volatile private var broken = false

    /** @return null when ready, otherwise the reason. */
    fun prepare(): String? {
        try {
            val m = GbAdbManager.getInstance(ctx)
            if (!m.isConnected()) {
                val ok = try {
                    m.autoConnect(ctx, 6000L)
                } catch (e: AdbPairingRequiredException) {
                    return "لازم تفعّل الشل مرة واحدة (اقتران بالكود)"
                }
                if (!ok) return "مش قادر أوصل للـ ADB: شغّل Wireless debugging وكون على Wi-Fi"
            }
            val apk = ctx.applicationInfo.sourceDir
            val s = m.openStream("exec:CLASSPATH=$apk exec app_process /system/bin com.omega.autoplay.ShellServer")
            stream = s
            out = s.openOutputStream()
            val latch = CountDownLatch(1)
            Thread {
                try {
                    val r = BufferedReader(InputStreamReader(s.openInputStream()))
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("READY")) {
                            ready = true
                            latch.countDown()
                        } else if (line.startsWith("ERR")) {
                            if (!ready) error = line
                            AppLog.d("shell server: $line")
                            latch.countDown()
                        }
                    }
                } catch (t: Throwable) {
                } finally {
                    broken = true
                    latch.countDown()
                }
            }.start()
            if (!latch.await(7, TimeUnit.SECONDS)) return "السيرفر ما ردّش"
            if (!ready) return error ?: "السيرفر وقف"
            broken = false
            return null
        } catch (t: Throwable) {
            return "فشل الشل: " + t.javaClass.simpleName + " " + (t.message ?: "")
        }
    }

    @Synchronized
    override fun touchDown(id: Int, x: Float, y: Float) {
        pending.append("D ").append(id).append(' ').append(x.toInt()).append(' ').append(y.toInt()).append('\n')
    }

    @Synchronized
    override fun touchMove(id: Int, x: Float, y: Float) {
        pending.append("M ").append(id).append(' ').append(x.toInt()).append(' ').append(y.toInt()).append('\n')
    }

    @Synchronized
    override fun touchUp(id: Int) {
        pending.append("U ").append(id).append('\n')
    }

    @Synchronized
    override fun flush() {
        pending.append("F\n")
        write()
    }

    @Synchronized
    override fun releaseAll() {
        pending.setLength(0)
        pending.append("X\n")
        write()
    }

    private fun write() {
        val o = out
        if (o == null || broken) {
            pending.setLength(0)
            return
        }
        try {
            o.write(pending.toString().toByteArray(Charsets.UTF_8))
            o.flush()
        } catch (t: Throwable) {
            broken = true
            AppLog.d("shell write failed: " + t.javaClass.simpleName + " " + t.message)
        }
        pending.setLength(0)
    }

    override fun close() {
        try {
            pending.setLength(0)
            pending.append("X\nQ\n")
            write()
        } catch (t: Throwable) {
        }
        try { stream?.close() } catch (t: Throwable) { }
        stream = null
        out = null
    }
}
