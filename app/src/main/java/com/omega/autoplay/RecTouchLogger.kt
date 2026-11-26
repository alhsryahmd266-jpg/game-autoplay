package com.omega.autoplay

/**
 * Best-effort raw touch logger for "تسجيل فريم", built on the SAME embedded ADB connection the app
 * already uses for bot input (GbAdbManager) — read-only use, no changes to that file or to ShellServer.
 * Runs `getevent` as the shell user to read the touchscreen's raw events directly, independent of any
 * on-screen "show taps" indicator. Entirely optional: if the device/connection doesn't allow it, the
 * caller just gets null/false back and frame recording continues normally without a touch log.
 */

import android.content.Context
import android.os.SystemClock
import com.omega.autoplay.adb.GbAdbManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

class RecTouchLogger(private val ctx: Context) {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set
    @Volatile var active = false
        private set

    /** One entry per currently-down finger: [curX, curY, prevX, prevY] (0..1 of screen). Thread-safe snapshot. */
    @Synchronized
    fun snapshot(): List<FloatArray> = liveSlots.values.map { it.copyOf() }

    private val liveSlots = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()

    private val devHeader = Pattern.compile("add device \\d+:\\s*(/dev/input/event\\d+)")
    private val minMax = Pattern.compile("min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
    private val evAbs = Pattern.compile("EV_ABS\\s+ABS_MT_(SLOT|TRACKING_ID|POSITION_X|POSITION_Y)\\s+([0-9a-fA-F]+)")

    private class Range(var min: Int = 0, var max: Int = 1)

    /** One-shot: lists input devices and finds the touchscreen's node + its reported X/Y range. Blocks briefly. */
    private fun discover(): Triple<String, Range, Range>? {
        val m = GbAdbManager.getInstance(ctx)
        if (!m.isConnected()) {
            try {
                m.autoConnect(ctx, 5000L)
            } catch (t: Throwable) {
                lastError = "الشل مش متفعّل (اعمل الاقتران بالكود من ٦ أرقام الأول)"
                return null
            }
        }
        val s = try {
            m.openStream("exec:getevent -lp")
        } catch (t: Throwable) {
            lastError = "فشل فتح getevent: " + t.message
            return null
        }
        val text = try {
            BufferedReader(InputStreamReader(s.openInputStream())).readText()
        } catch (t: Throwable) {
            lastError = "فشل قراءة أجهزة اللمس: " + t.message
            return null
        } finally {
            try { s.close() } catch (_: Throwable) { }
        }
        var curDev: String? = null
        var x = Range(); var y = Range()
        var foundDev: String? = null
        for (line in text.lineSequence()) {
            val dm = devHeader.matcher(line)
            if (dm.find()) {
                curDev = dm.group(1)
                continue
            }
            if (line.contains("POSITION_X") || line.contains(" 0035")) {
                val mm = minMax.matcher(line)
                if (mm.find()) {
                    x = Range(mm.group(1)!!.toInt(), mm.group(2)!!.toInt())
                    foundDev = curDev
                }
            } else if (line.contains("POSITION_Y") || line.contains(" 0036")) {
                val mm = minMax.matcher(line)
                if (mm.find()) y = Range(mm.group(1)!!.toInt(), mm.group(2)!!.toInt())
            }
        }
        if (foundDev == null) {
            lastError = "ملقتش جهاز لمس بياناته واضحة (getevent -lp)"
            return null
        }
        return Triple(foundDev, x, y)
    }

    /** Starts logging in the background; returns true once it is actually streaming. Safe to call even if it fails. */
    fun start(outFile: File): Boolean {
        stop()
        running = true
        active = false
        thread = Thread({ run(outFile) }, "ap-touchlog").also { it.start() }
        // give it a moment to either confirm or fail, so the caller can show one honest status line
        var waited = 0
        while (running && !active && lastError == null && waited < 4000) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { }
            waited += 100
        }
        return active
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Throwable) { }
        try { thread?.join(800) } catch (_: Throwable) { }
        thread = null
        active = false
    }

    private fun run(outFile: File) {
        val found = discover()
        if (found == null || !running) return
        val (dev, rx, ry) = found
        val m = GbAdbManager.getInstance(ctx)
        val stream = try {
            m.openStream("exec:getevent -lt $dev")
        } catch (t: Throwable) {
            lastError = "فشل بدء تسجيل اللمس: " + t.message
            return
        }
        active = true
        AppLog.d("تسجيل فريم: تسجيل اللمس شغّال على $dev")
        val slotPos = HashMap<Int, FloatArray>()
        val slotDown = HashMap<Int, Boolean>()

        fun updateLive() {
            val m = HashMap<Int, FloatArray>()
            for ((sId, down) in slotDown) {
                if (down) slotPos[sId]?.let { m[sId] = it.copyOf() }
            }
            synchronized(this@RecTouchLogger) { liveSlots.keys.retainAll(m.keys); liveSlots.putAll(m) }
        }
        var slot = 0
        val writer = outFile.bufferedWriter()
        try {
            val r = BufferedReader(InputStreamReader(stream.openInputStream()))
            while (running) {
                val line = r.readLine() ?: break
                if (line.contains("SYN_REPORT")) {
                    val t = SystemClock.uptimeMillis()
                    val active = slotDown.filterValues { it }.keys.sorted()
                    if (active.isNotEmpty()) {
                        val sb = StringBuilder("{\"t_ms\":").append(t).append(",\"touches\":[")
                        for ((i, sId) in active.withIndex()) {
                            val p = slotPos[sId] ?: continue
                            if (i > 0) sb.append(',')
                            sb.append("{\"slot\":").append(sId).append(",\"x\":").append(p[0]).append(",\"y\":").append(p[1]).append('}')
                        }
                        sb.append("]}\n")
                        writer.write(sb.toString())
                        writer.flush()
                    }
                    updateLive()
                    continue
                }
                val am = evAbs.matcher(line)
                if (am.find()) {
                    val code = am.group(1)
                    val v = am.group(2)!!.toLong(16)
                    when (code) {
                        "SLOT" -> slot = v.toInt()
                        "TRACKING_ID" -> slotDown[slot] = v != 0xFFFFFFFFL
                        "POSITION_X" -> {
                            val nx = ((v - rx.min).toFloat() / max(1, rx.max - rx.min)).coerceIn(0f, 1f)
                            // [cur, prevStillHere] layout: [curX, curY, prevX, prevY] — shift cur->prev only on real moves
                            val cur = slotPos.getOrPut(slot) { floatArrayOf(nx, 0f, nx, 0f) }
                            cur[2] = cur[0]
                            cur[0] = nx
                        }
                        "POSITION_Y" -> {
                            val ny = ((v - ry.min).toFloat() / max(1, ry.max - ry.min)).coerceIn(0f, 1f)
                            val cur = slotPos.getOrPut(slot) { floatArrayOf(0f, ny, 0f, ny) }
                            cur[3] = cur[1]
                            cur[1] = ny
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) lastError = "تسجيل اللمس وقف: " + t.message
        } finally {
            try { writer.flush(); writer.close() } catch (_: Throwable) { }
            try { stream.close() } catch (_: Throwable) { }
            this.active = false
        }
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
