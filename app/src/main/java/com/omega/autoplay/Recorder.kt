package com.omega.autoplay

/**
 * تسجيل فريم (Tasjeel Frame) — a self-contained, standalone recorder tool.
 * It is independent from BotService/Brain/Controller: it only reads shared, generic
 * utilities (ScreenCapture, Frame, TfliteEngine, AppLog, Prefs, Util) and never
 * modifies them. Nothing here touches gameplay logic.
 *
 * Flow: start/stop any number of short clips (frames pile up in a staging folder) ->
 * "Convert" runs the bundled generic detector (person/car/airplane) over every staged
 * frame, writes YOLO-format labels, zips everything into ONE file, saves it to
 * Download/TasjeelFrame, then clears the staging folder for the next batch.
 */

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

object RecorderPaths {
    fun stage(c: Context) = File(c.filesDir, "rec_stage")
    fun images(c: Context) = File(stage(c), "images").also { it.mkdirs() }
    fun labels(c: Context) = File(stage(c), "labels").also { it.mkdirs() }

    fun count(c: Context): Int = images(c).list()?.size ?: 0

    fun clear(c: Context) {
        stage(c).deleteRecursively()
    }
}

/** The 3 classes this tool pre-labels, mapped from the bundled model's COCO ids. */
object PrelabelClasses {
    // output_id to (coco_class_id, name)
    val MAP = listOf(0 to "person", 2 to "car", 4 to "airplane")
    val NAMES = MAP.map { it.second }
    val COCO_TO_OUT: Map<Int, Int> = MAP.mapIndexed { out, (coco, _) -> coco to out }.toMap()
}

/** Loads the small bundled generic detector (coco_prelabel.tflite) once; independent of ModelStore/Prefs. */
object Prelabel {
    @Volatile private var engine: TfliteEngine? = null

    @Synchronized
    fun get(c: Context): TfliteEngine {
        val e = engine
        if (e != null) return e
        val bytes = c.assets.open("coco_prelabel.tflite").use { it.readBytes() }
        val n = TfliteEngine(bytes, threads = 2, gpu = false)
        engine = n
        return n
    }

    /** Raw decode restricted to person/car/airplane; returns normalized [0..1] boxes + output class id (0,1,2). */
    fun detect(eng: TfliteEngine, f: Frame, conf: Float): List<FloatArray> {
        val inW = eng.inW
        val inH = eng.inH
        val sc = min(inW.toFloat() / f.w, inH.toFloat() / f.h)
        val nw = (f.w * sc).toInt().coerceIn(1, inW)
        val nh = (f.h * sc).toInt().coerceIn(1, inH)
        val padX = (inW - nw) / 2
        val padY = (inH - nh) / 2
        val buf = FloatArray(3 * inW * inH)
        java.util.Arrays.fill(buf, 114f / 255f)
        val plane = inW * inH
        val px = f.px
        val inv = 1f / 255f
        for (y in 0 until nh) {
            val sy = min(f.h - 1, (y / sc).toInt())
            val row = sy * f.w * 4
            val dy = (y + padY) * inW + padX
            for (x in 0 until nw) {
                val sx = min(f.w - 1, (x / sc).toInt())
                val i = row + sx * 4
                val r = (px[i].toInt() and 0xFF) * inv
                val g = (px[i + 1].toInt() and 0xFF) * inv
                val b = (px[i + 2].toInt() and 0xFF) * inv
                val d = dy + x
                if (eng.nhwc) {
                    val o = d * 3
                    buf[o] = r; buf[o + 1] = g; buf[o + 2] = b
                } else {
                    buf[d] = r; buf[plane + d] = g; buf[2 * plane + d] = b
                }
            }
        }
        val res = eng.run(buf)
        return decode(res.data, res.shape, sc, padX.toFloat(), padY.toFloat(), f.w, f.h, conf)
    }

    /** @return list of [outClass, x1, y1, x2, y2, score] in 0..1 image-normalized coords. */
    private fun decode(out: FloatArray, shape: IntArray, sc: Float, padX: Float, padY: Float, srcW: Int, srcH: Int, conf: Float): List<FloatArray> {
        if (shape.size != 3) return emptyList()
        val a = shape[1]
        val b = shape[2]
        val chFirst = a < b
        val ch = if (chFirst) a else b
        val n = if (chFirst) b else a
        val nc = ch - 4
        if (nc < 5) return emptyList()
        val wanted = PrelabelClasses.COCO_TO_OUT.keys
        data class Cand(val outCls: Int, val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)
        val cands = ArrayList<Cand>()
        for (i in 0 until n) {
            for (cocoCls in wanted) {
                val s = if (chFirst) out[(4 + cocoCls) * n + i] else out[i * ch + 4 + cocoCls]
                if (s < conf) continue
                val cx: Float; val cy: Float; val w: Float; val h: Float
                if (chFirst) {
                    cx = out[i]; cy = out[n + i]; w = out[2 * n + i]; h = out[3 * n + i]
                } else {
                    val o = i * ch
                    cx = out[o]; cy = out[o + 1]; w = out[o + 2]; h = out[o + 3]
                }
                cands.add(Cand(PrelabelClasses.COCO_TO_OUT.getValue(cocoCls), s, cx, cy, w, h))
            }
        }
        cands.sortByDescending { it.score }
        val keep = ArrayList<FloatArray>()
        for (c in cands) {
            val x1 = ((c.cx - c.w / 2f - padX) / sc / srcW).coerceIn(0f, 1f)
            val y1 = ((c.cy - c.h / 2f - padY) / sc / srcH).coerceIn(0f, 1f)
            val x2 = ((c.cx + c.w / 2f - padX) / sc / srcW).coerceIn(0f, 1f)
            val y2 = ((c.cy + c.h / 2f - padY) / sc / srcH).coerceIn(0f, 1f)
            if (x2 <= x1 || y2 <= y1) continue
            var sup = false
            for (k in keep) {
                if (k[0].toInt() != c.outCls) continue
                val ix = max(0f, min(x2, k[3]) - max(x1, k[1]))
                val iy = max(0f, min(y2, k[4]) - max(y1, k[2]))
                val inter = ix * iy
                val u = (x2 - x1) * (y2 - y1) + (k[3] - k[1]) * (k[4] - k[2]) - inter
                if (u > 0f && inter / u > 0.5f) { sup = true; break }
            }
            if (!sup) keep.add(floatArrayOf(c.outCls.toFloat(), x1, y1, x2, y2, c.score))
            if (keep.size >= 30) break
        }
        return keep
    }
}

class Recorder(private val ctx: Context) {
    private var lastSaveAt = 0L

    /**
     * Call for every captured frame while recording; saves it to the staging folder if the save-rate allows.
     * Timestamps use SystemClock.uptimeMillis() (monotonic, excludes deep sleep) to match the clock basis
     * getevent normally reports, so frames and the optional touch log can be correlated afterwards.
     * [touches], when given, draws a small dot at each active touch and a thin arrow for its recent direction.
     */
    fun maybeSave(f: Frame, savesPerSec: Int, touches: List<FloatArray> = emptyList()): Boolean {
        val now = SystemClock.uptimeMillis()
        val minGap = 1000L / max(1, savesPerSec)
        if (now - lastSaveAt < minGap) return false
        lastSaveAt = now
        try {
            val bmp = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(f.px))
            if (touches.isNotEmpty()) drawTouches(bmp, touches)
            val name = "f_${now}_${(0..999).random()}.jpg"
            val file = File(RecorderPaths.images(ctx), name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            try {
                File(RecorderPaths.stage(ctx), "frames.jsonl").appendText(
                    "{\"file\":\"$name\",\"t_ms\":$now,\"w\":${f.w},\"h\":${f.h}}\n"
                )
            } catch (t: Throwable) {
                AppLog.d("تسجيل فريم: فشل تسجيل سجل اللقطة: " + t.message)
            }
            return true
        } catch (t: Throwable) {
            AppLog.d("تسجيل فريم: فشل حفظ لقطة: " + t.message)
            return false
        }
    }

    /** Draws one small dot + a thin direction arrow per active touch, straight onto the saved bitmap's pixels. */
    private fun drawTouches(bmp: Bitmap, touches: List<FloatArray>) {
        val c = android.graphics.Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val diag = kotlin.math.hypot(w, h)
        val stroke = (diag * 0.004f).coerceIn(2f, 7f)
        val dot = (diag * 0.010f).coerceIn(5f, 16f)
        val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke + 3f
            color = android.graphics.Color.BLACK
            alpha = 160
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke
            color = android.graphics.Color.parseColor("#00E5FF")
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val fillGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; alpha = 160 }
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#00E5FF") }
        for (t in touches) {
            if (t.size < 4) continue
            val cx = t[0] * w; val cy = t[1] * h
            val px = t[2] * w; val py = t[3] * h
            c.drawCircle(cx, cy, dot + 2f, fillGlow)
            c.drawCircle(cx, cy, dot, fill)
            val mdx = cx - px; val mdy = cy - py
            if (kotlin.math.hypot(mdx, mdy) > diag * 0.006f) {
                // arrow points in the direction of travel, drawn a bit ahead of the current point
                val ex = cx + mdx; val ey = cy + mdy
                c.drawLine(cx, cy, ex, ey, glow)
                c.drawLine(cx, cy, ex, ey, line)
                val ang = kotlin.math.atan2(mdy, mdx)
                val hs = dot * 1.3f
                val a1 = ang + Math.PI * 0.8
                val a2 = ang - Math.PI * 0.8
                val p1x = ex + (hs * kotlin.math.cos(a1)).toFloat(); val p1y = ey + (hs * kotlin.math.sin(a1)).toFloat()
                val p2x = ex + (hs * kotlin.math.cos(a2)).toFloat(); val p2y = ey + (hs * kotlin.math.sin(a2)).toFloat()
                c.drawLine(ex, ey, p1x, p1y, line)
                c.drawLine(ex, ey, p2x, p2y, line)
            }
        }
    }

    class ConvertResult(val images: Int, val labeled: Int, val perClass: IntArray, val zipFile: File?, val error: String?)

    /** Runs the prelabel detector over every staged image, writes YOLO labels, zips, saves to Downloads, clears staging. Call off the main thread. */
    fun convert(conf: Float, onProgress: (Int, Int) -> Unit): ConvertResult {
        val imgDir = RecorderPaths.images(ctx)
        val lblDir = RecorderPaths.labels(ctx)
        val files = imgDir.listFiles { f -> f.extension.equals("jpg", true) }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return ConvertResult(0, 0, IntArray(3), null, "مفيش صور متسجّلة")
        val eng = try {
            Prelabel.get(ctx)
        } catch (t: Throwable) {
            return ConvertResult(files.size, 0, IntArray(3), null, "فشل تحميل نموذج التصنيف: " + t.message)
        }
        var labeled = 0
        val perClass = IntArray(3)
        for ((idx, file) in files.withIndex()) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
                    bmp.copyPixelsToBuffer(buf)
                    val frame = Frame(bmp.width, bmp.height, buf.array(), 0L)
                    val dets = Prelabel.detect(eng, frame, conf)
                    bmp.recycle()
                    val txt = File(lblDir, file.nameWithoutExtension + ".txt")
                    if (dets.isNotEmpty()) {
                        val sb = StringBuilder()
                        for (d in dets) {
                            val cls = d[0].toInt()
                            val cx = (d[1] + d[3]) / 2f
                            val cy = (d[2] + d[4]) / 2f
                            val w = d[3] - d[1]
                            val h = d[4] - d[2]
                            sb.append(cls).append(' ').append(cx).append(' ').append(cy).append(' ').append(w).append(' ').append(h).append('\n')
                            perClass[cls]++
                        }
                        txt.writeText(sb.toString())
                        labeled++
                    } else {
                        txt.writeText("")
                    }
                } else {
                    File(lblDir, file.nameWithoutExtension + ".txt").writeText("")
                }
            } catch (t: Throwable) {
                AppLog.d("تسجيل فريم: فشل تصنيف " + file.name + ": " + t.message)
            }
            if (idx % 10 == 0 || idx == files.size - 1) onProgress(idx + 1, files.size)
        }
        val yaml = "path: .\ntrain: images\nval: images\nnc: ${PrelabelClasses.NAMES.size}\nnames: [${PrelabelClasses.NAMES.joinToString(", ")}]\n"
        File(RecorderPaths.stage(ctx), "data.yaml").writeText(yaml)
        File(RecorderPaths.stage(ctx), "README.txt").writeText(README_TXT)
        File(RecorderPaths.stage(ctx), "sync_frames_touches.py").writeText(SYNC_SCRIPT)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val zipName = "تسجيل_فريم_$stamp.zip"
        val zipTmp = File(ctx.cacheDir, zipName)
        return try {
            zipDir(RecorderPaths.stage(ctx), zipTmp)
            val saved = saveZipToDownloads(zipTmp, zipName)
            zipTmp.delete()
            RecorderPaths.clear(ctx)
            ConvertResult(files.size, labeled, perClass, saved, if (saved == null) "فشل حفظ الملف في التنزيلات" else null)
        } catch (t: Throwable) {
            ConvertResult(files.size, labeled, perClass, null, "فشل الضغط: " + t.message)
        }
    }

    private fun zipDir(dir: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun addDir(d: File, prefix: String) {
                for (f in d.listFiles() ?: emptyArray()) {
                    val name = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                    if (f.isDirectory) {
                        addDir(f, name)
                    } else {
                        zos.putNextEntry(ZipEntry(name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            addDir(dir, "")
        }
    }

    /** Returns the saved File reference when possible (API < 29), else null (still saved via MediaStore on 29+, just no direct File handle). */
    private fun saveZipToDownloads(src: File, name: String): File? {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TasjeelFrame")
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            return File(name)
        }
        @Suppress("DEPRECATION")
        val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val sub = File(dlDir, "TasjeelFrame").apply { mkdirs() }
        val dst = File(sub, name)
        src.copyTo(dst, overwrite = true)
        return dst
    }
}

private val README_TXT = """
تسجيل فريم — ملف بيانات
========================
images/   لقطات الشاشة (jpg)
labels/   تصنيف مبدئي تلقائي بصيغة YOLO لكل لقطة (person=0, car=1, airplane=2) — راجعه وصححه في Roboflow قبل التدريب، ده نقطة بداية مش تصنيف نهائي.
frames.jsonl   سجل كل لقطة محفوظة: اسم الملف ووقتها (t_ms = SystemClock.uptimeMillis على الموبايل وقت التسجيل).
touches.jsonl  (لو كان متاحًا على جهازك) سجل خام للمسات: نفس وحدة الوقت (t_ms)، وموضع كل إصبع (0..1 من عرض/ارتفاع الشاشة) لحظة كل لمسة.
sync_frames_touches.py   سكريبت جاهز يدمج frames.jsonl مع touches.jsonl في جدول واحد (كل لقطة + أقرب لمسة ليها واتجاه حركتها)، لو محتاجه للتدريب.
data.yaml   صيغة Roboflow/YOLO القياسية لأسماء الكلاسات.

ملحوظة: توقيت touches.jsonl بييجي من getevent على الجهاز، وقد يختلف شوية عن قاعدة الوقت دي حسب نسخة الأندرويد. لو الأرقام بعيدة عن بعض جدًا وقت المطابقة، قارن أول بصمتين زمنيتين من الملفين كبداية تصحيح.
""".trimIndent()

private val SYNC_SCRIPT = """
#!/usr/bin/env python3
# Merges frames.jsonl + touches.jsonl into one CSV: each frame row gets the nearest touch sample(s)
# and, when two samples bracket it, the instantaneous drag vector (dx, dy) in 0..1 screen fractions.
# Usage: python sync_frames_touches.py  (run inside the unzipped folder)
import json, csv, bisect

def load(name):
    rows = []
    try:
        with open(name) as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    except FileNotFoundError:
        pass
    return rows

frames = load("frames.jsonl")
touches = load("touches.jsonl")
touches.sort(key=lambda t: t["t_ms"])
t_times = [t["t_ms"] for t in touches]

with open("frames_with_touch.csv", "w", newline="") as out:
    w = csv.writer(out)
    w.writerow(["file", "t_ms", "touch_count", "x0", "y0", "dx", "dy"])
    for fr in frames:
        t = fr["t_ms"]
        i = bisect.bisect_left(t_times, t)
        before = touches[i - 1] if i > 0 else None
        after = touches[i] if i < len(touches) else None
        pick = min([x for x in (before, after) if x], key=lambda x: abs(x["t_ms"] - t), default=None)
        x0 = y0 = dx = dy = ""
        n = 0
        if pick and pick.get("touches"):
            n = len(pick["touches"])
            x0, y0 = pick["touches"][0]["x"], pick["touches"][0]["y"]
            if before and after and before is not pick and before.get("touches") and pick.get("touches"):
                dx = pick["touches"][0]["x"] - before["touches"][0]["x"]
                dy = pick["touches"][0]["y"] - before["touches"][0]["y"]
        w.writerow([fr["file"], t, n, x0, y0, dx, dy])
print("wrote frames_with_touch.csv")
""".trimIndent()
