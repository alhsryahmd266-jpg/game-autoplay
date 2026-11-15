package com.omega.autoplay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Buffer.rewind()/position() have covariant overrides in newer JDKs; calling them on the Buffer type keeps old Android happy.
fun Buffer.rw() {
    this.rewind()
}

class EngineResult(val data: FloatArray, val shape: IntArray)

interface Engine : AutoCloseable {
    val inW: Int
    val inH: Int
    val nhwc: Boolean
    val tag: String
    val metaNames: List<String>
    val outShapeStatic: IntArray
    fun run(input: FloatArray): EngineResult
}

class TfliteEngine(model: ByteArray, threads: Int, gpu: Boolean) : Engine {
    private val interp: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    override val inW: Int
    override val inH: Int
    override val nhwc: Boolean = true
    override var tag: String = "TFLite"
    override val metaNames: List<String> = emptyList()
    override val outShapeStatic: IntArray
    private val inBuf: ByteBuffer
    private val outBuf: ByteBuffer

    init {
        val mb = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder())
        mb.put(model)
        mb.rw()
        var ip: Interpreter? = null
        if (gpu) {
            try {
                val d = GpuDelegate()
                val o = Interpreter.Options()
                o.addDelegate(d)
                ip = Interpreter(mb, o)
                gpuDelegate = d
                tag = "TFLite GPU"
            } catch (t: Throwable) {
                AppLog.d("GPU delegate failed: " + t.message)
                gpuDelegate = null
                ip = null
            }
        }
        if (ip == null) {
            mb.rw()
            val o = Interpreter.Options()
            o.setNumThreads(threads)
            ip = Interpreter(mb, o)
            tag = "TFLite CPU x" + threads
        }
        interp = ip ?: throw IllegalStateException("tflite")
        val it0 = interp.getInputTensor(0)
        val s = it0.shape()
        if (s.size != 4 || s[3] != 3) throw IllegalArgumentException("شكل الدخل غير مدعوم: " + s.joinToString("x"))
        if (it0.dataType() != DataType.FLOAT32) throw IllegalArgumentException("النموذج int8 غير مدعوم، صدّره float16 أو float32")
        if (interp.outputTensorCount != 1) throw IllegalArgumentException("النموذج فيه أكتر من خرج، غير مدعوم")
        inH = s[1]
        inW = s[2]
        val ot = interp.getOutputTensor(0)
        if (ot.dataType() != DataType.FLOAT32) throw IllegalArgumentException("خرج النموذج مش float32")
        outShapeStatic = ot.shape()
        inBuf = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        outBuf = ByteBuffer.allocateDirect(ot.numBytes()).order(ByteOrder.nativeOrder())
    }

    override fun run(input: FloatArray): EngineResult {
        inBuf.rw()
        inBuf.asFloatBuffer().put(input)
        inBuf.rw()
        outBuf.rw()
        interp.run(inBuf, outBuf)
        outBuf.rw()
        val out = FloatArray(outBuf.capacity() / 4)
        outBuf.asFloatBuffer().get(out)
        return EngineResult(out, outShapeStatic)
    }

    override fun close() {
        try { interp.close() } catch (_: Throwable) { }
        try { gpuDelegate?.close() } catch (_: Throwable) { }
    }
}

class OrtEngine(model: ByteArray, threads: Int, nnapi: Boolean) : Engine {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inName: String
    override val inW: Int
    override val inH: Int
    override val nhwc: Boolean = false
    override var tag: String = "ONNX CPU x" + threads
    override val metaNames: List<String>
    override val outShapeStatic: IntArray
    private val inBuf: FloatBuffer

    init {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(threads)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (nnapi) {
            try {
                o.addNnapi()
                tag = "ONNX NNAPI"
            } catch (t: Throwable) {
                AppLog.d("NNAPI unavailable: " + t.message)
            }
        }
        session = env.createSession(model, o)
        val e = session.inputInfo.entries.first()
        inName = e.key
        val sh = (e.value.info as TensorInfo).shape
        inH = if (sh.size == 4 && sh[2] > 0) sh[2].toInt() else 640
        inW = if (sh.size == 4 && sh[3] > 0) sh[3].toInt() else 640
        var names: List<String> = emptyList()
        try {
            names = Names.parsePyDict(session.metadata.customMetadata["names"])
        } catch (_: Throwable) {
        }
        metaNames = names
        var os = IntArray(0)
        try {
            val oi = session.outputInfo.values.first().info as TensorInfo
            os = oi.shape.map { it.toInt() }.toIntArray()
        } catch (_: Throwable) {
        }
        outShapeStatic = os
        inBuf = ByteBuffer.allocateDirect(4 * 3 * inH * inW).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    override fun run(input: FloatArray): EngineResult {
        inBuf.rw()
        inBuf.put(input)
        inBuf.rw()
        val t = OnnxTensor.createTensor(env, inBuf, longArrayOf(1, 3, inH.toLong(), inW.toLong()))
        try {
            val r = session.run(mapOf(inName to t))
            try {
                val ot = r.get(0) as OnnxTensor
                val shape = ot.info.shape.map { it.toInt() }.toIntArray()
                val fb = ot.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                return EngineResult(out, shape)
            } finally {
                r.close()
            }
        } finally {
            t.close()
        }
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) { }
    }
}

object Names {
    private val rx = Regex("""['"]?(\d+)['"]?\s*:\s*['"]([^'"]+)['"]""")

    val COCO = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    )

    fun isCoco(n: List<String>): Boolean = n.size == 80 && n[0] == "person" && n[79] == "toothbrush"

    /** Default role of class [i]: for a generic COCO model only "person" (stand-in enemy) matters. */
    fun defaultRole(n: List<String>, i: Int): Role =
        if (isCoco(n)) (if (i == 0) Role.ENEMY else Role.IGNORE) else Roles.guess(n[i])

    fun effectiveRoles(n: List<String>, overrides: Map<String, String>): Array<Role> = Array(n.size) { i ->
        val o = overrides[n[i]]
        val forced = if (o != null) Role.values().firstOrNull { it.name == o } else null
        forced ?: defaultRole(n, i)
    }

    fun parsePyDict(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val m = HashMap<Int, String>()
        val end = s.indexOf('}').let { if (it > 0) it else s.length }
        for (x in rx.findAll(s.substring(0, end))) m[x.groupValues[1].toInt()] = x.groupValues[2]
        if (m.isEmpty()) return emptyList()
        val mx = m.keys.maxOrNull() ?: return emptyList()
        return (0..mx).map { m[it] ?: ("class" + it) }
    }

    /** Ultralytics appends the metadata (with class names) to the end of a .tflite file. */
    fun fromBinary(b: ByteArray): List<String> {
        val start = max(0, b.size - 200_000)
        val tail = String(b, start, b.size - start, Charsets.ISO_8859_1)
        var i = tail.lastIndexOf("names")
        while (i >= 0) {
            val r = parsePyDict(tail.substring(i + 5, min(tail.length, i + 20000)))
            if (r.isNotEmpty()) return r
            i = if (i > 0) tail.lastIndexOf("names", i - 1) else -1
        }
        var p = tail.indexOf("PK\u0003\u0004")
        var tries = 0
        while (p >= 0 && tries < 4) {
            try {
                val zin = ZipInputStream(ByteArrayInputStream(b, start + p, b.size - start - p))
                var e = zin.nextEntry
                while (e != null) {
                    val txt = String(zin.readBytes(), Charsets.UTF_8)
                    val k = txt.indexOf("names")
                    if (k >= 0) {
                        val r = parsePyDict(txt.substring(k + 5))
                        if (r.isNotEmpty()) return r
                    }
                    e = zin.nextEntry
                }
            } catch (_: Throwable) {
            }
            p = tail.indexOf("PK\u0003\u0004", p + 1)
            tries++
        }
        return emptyList()
    }

    /** data.yaml / dataset .zip (Roboflow) / plain text, one name per line. */
    fun parseImport(fileName: String, bytes: ByteArray): List<String> {
        val low = fileName.lowercase()
        if (low.endsWith(".zip")) {
            try {
                val z = ZipInputStream(ByteArrayInputStream(bytes))
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name.lowercase()
                    if (n.endsWith("data.yaml") || n.endsWith("data.yml")) return parseYaml(String(z.readBytes(), Charsets.UTF_8))
                    e = z.nextEntry
                }
            } catch (_: Throwable) {
            }
            return emptyList()
        }
        val s = String(bytes, Charsets.UTF_8)
        if (low.endsWith(".yaml") || low.endsWith(".yml")) return parseYaml(s)
        return s.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseYaml(s: String): List<String> {
        val lines = s.lines()
        val i = lines.indexOfFirst { it.trimStart().startsWith("names:") }
        if (i < 0) return emptyList()
        val rest = lines[i].substringAfter("names:").trim()
        if (rest.startsWith("[")) {
            val inner = rest.substringAfter("[").substringBefore("]")
            return inner.split(",").map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        }
        val out = ArrayList<Pair<Int, String>>()
        var k = 0
        val kv = Regex("""^(\d+)\s*:\s*(.+)$""")
        for (j in i + 1 until lines.size) {
            val l = lines[j]
            if (l.isBlank()) continue
            val t = l.trim()
            if (!l.startsWith(" ") && !l.startsWith("\t") && !t.startsWith("-")) break
            if (t.startsWith("-")) {
                out.add(Pair(k, t.removePrefix("-").trim().trim('\'', '"')))
                k++
            } else {
                val m = kv.find(t) ?: break
                out.add(Pair(m.groupValues[1].toInt(), m.groupValues[2].trim().trim('\'', '"')))
            }
        }
        if (out.isEmpty()) return emptyList()
        val mx = out.maxOf { it.first }
        val arr = Array(mx + 1) { "class$it" }
        for ((a, b) in out) arr[a] = b
        return arr.toList()
    }
}

class Yolo(val eng: Engine) {
    var names: List<String> = emptyList()
        private set
    var roles: Array<Role> = emptyArray()
        private set
    var lastMs = 0f
    val inW = eng.inW
    val inH = eng.inH
    private val buf = FloatArray(3 * inW * inH)
    private var xmap = IntArray(0)
    private var xmapW = -1

    fun setNames(n: List<String>, overrides: Map<String, String>) {
        names = n
        roles = Names.effectiveRoles(n, overrides)
    }

    fun hasHudClasses(): Boolean = roles.any { it in Role.HUD }

    fun detect(f: Frame, conf: Float, iouThr: Float): List<Det> {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val sc = min(inW.toFloat() / f.w, inH.toFloat() / f.h)
        val nw = (f.w * sc).roundToInt().coerceIn(1, inW)
        val nh = (f.h * sc).roundToInt().coerceIn(1, inH)
        val padX = (inW - nw) / 2
        val padY = (inH - nh) / 2
        fillInput(f, sc, nw, nh, padX, padY)
        val res = eng.run(buf)
        val out = decode(res.data, res.shape, sc, padX.toFloat(), padY.toFloat(), f.w, f.h, conf, iouThr)
        lastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000f
        return out
    }

    private fun fillInput(f: Frame, sc: Float, nw: Int, nh: Int, padX: Int, padY: Int) {
        java.util.Arrays.fill(buf, 114f / 255f)
        if (xmap.size != nw || xmapW != f.w) {
            xmap = IntArray(nw) { x -> min(f.w - 1, (x / sc).toInt()) }
            xmapW = f.w
        }
        val px = f.px
        val inv = 1f / 255f
        val plane = inW * inH
        val nhwc = eng.nhwc
        for (y in 0 until nh) {
            val sy = min(f.h - 1, (y / sc).toInt())
            val row = sy * f.w * 4
            val dy = (y + padY) * inW + padX
            for (x in 0 until nw) {
                val i = row + xmap[x] * 4
                val r = (px[i].toInt() and 0xFF) * inv
                val g = (px[i + 1].toInt() and 0xFF) * inv
                val b = (px[i + 2].toInt() and 0xFF) * inv
                val d = dy + x
                if (nhwc) {
                    val o = d * 3
                    buf[o] = r; buf[o + 1] = g; buf[o + 2] = b
                } else {
                    buf[d] = r; buf[plane + d] = g; buf[2 * plane + d] = b
                }
            }
        }
    }

    private fun decode(out: FloatArray, shape: IntArray, sc: Float, padX: Float, padY: Float, srcW: Int, srcH: Int, conf: Float, iouThr: Float): List<Det> {
        if (shape.size != 3) return emptyList()
        val cand = ArrayList<FloatArray>()   // [cls, score, cx, cy, w, h] in model-input pixels
        val e2e = shape[2] == 6 && shape[1] <= 1000
        if (e2e) {
            val n = shape[1]
            var mx = 0f
            for (i in 0 until n) {
                val o = i * 6
                if (out[o + 4] >= conf) mx = max(mx, max(out[o + 2], out[o + 3]))
            }
            val pixel = mx > 2f
            for (i in 0 until n) {
                val o = i * 6
                val s = out[o + 4]
                if (s < conf) continue
                var x1 = out[o]; var y1 = out[o + 1]; var x2 = out[o + 2]; var y2 = out[o + 3]
                if (!pixel) { x1 *= inW; x2 *= inW; y1 *= inH; y2 *= inH }
                cand.add(floatArrayOf(out[o + 5].roundToInt().toFloat(), s, (x1 + x2) / 2f, (y1 + y2) / 2f, x2 - x1, y2 - y1))
            }
        } else {
            val a = shape[1]
            val b = shape[2]
            val chFirst = a < b
            val ch = if (chFirst) a else b
            val n = if (chFirst) b else a
            val nc = ch - 4
            if (nc < 1) return emptyList()
            var mx = 0f
            val tmp = ArrayList<FloatArray>()
            for (i in 0 until n) {
                var best = -1
                var bs = 0f
                for (k in 0 until nc) {
                    val s = if (chFirst) out[(4 + k) * n + i] else out[i * ch + 4 + k]
                    if (s > bs) { bs = s; best = k }
                }
                if (best < 0 || bs < conf) continue
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float
                if (chFirst) {
                    cx = out[i]; cy = out[n + i]; w = out[2 * n + i]; h = out[3 * n + i]
                } else {
                    val o = i * ch
                    cx = out[o]; cy = out[o + 1]; w = out[o + 2]; h = out[o + 3]
                }
                mx = max(mx, max(max(cx, cy), max(w, h)))
                tmp.add(floatArrayOf(best.toFloat(), bs, cx, cy, w, h))
            }
            val pixel = mx > 2f
            for (t in tmp) cand.add(if (pixel) t else floatArrayOf(t[0], t[1], t[2] * inW, t[3] * inH, t[4] * inW, t[5] * inH))
        }
        cand.sortByDescending { it[1] }
        val keep = ArrayList<Det>()
        for (t in cand) {
            if (keep.size >= 40) break
            val cls = t[0].toInt()
            val role = roles.getOrElse(cls) { Role.IGNORE }
            if (role == Role.IGNORE) continue
            val x1 = ((t[2] - t[4] / 2f - padX) / sc / srcW).coerceIn(0f, 1f)
            val y1 = ((t[3] - t[5] / 2f - padY) / sc / srcH).coerceIn(0f, 1f)
            val x2 = ((t[2] + t[4] / 2f - padX) / sc / srcW).coerceIn(0f, 1f)
            val y2 = ((t[3] + t[5] / 2f - padY) / sc / srcH).coerceIn(0f, 1f)
            if (x2 <= x1 || y2 <= y1) continue
            val d = Det(cls, role, t[1], x1, y1, x2, y2)
            var sup = false
            if (!e2e) {
                for (k in keep) {
                    if (k.cls == cls && iouOf(k, d) > iouThr) { sup = true; break }
                }
            }
            if (!sup) keep.add(d)
        }
        return keep
    }

    private fun iouOf(a: Det, b: Det): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val u = a.w * a.h + b.w * b.h - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun close() {
        try { eng.close() } catch (_: Throwable) { }
    }
}

object ModelStore {
    class Info(
        val ok: Boolean,
        val msg: String,
        val names: List<String> = emptyList(),
        val tag: String = "",
        val inW: Int = 0,
        val inH: Int = 0
    )

    private fun f(c: Context, ext: String) = File(c.filesDir, "model.$ext")

    fun isTflite(b: ByteArray): Boolean =
        b.size > 8 && b[4] == 'T'.code.toByte() && b[5] == 'F'.code.toByte() && b[6] == 'L'.code.toByte() && b[7] == '3'.code.toByte()

    private fun assetModel(c: Context): Pair<String, ByteArray>? {
        val list = try { c.assets.list("") } catch (_: Exception) { null } ?: return null
        for (n in listOf("model.tflite", "model.onnx")) {
            if (list.contains(n)) return Pair(n, c.assets.open(n).use { it.readBytes() })
        }
        return null
    }

    private fun loadBytes(c: Context): Pair<String, ByteArray>? {
        for (ext in listOf("tflite", "onnx")) {
            val file = f(c, ext)
            if (file.exists()) return Pair(file.name, file.readBytes())
        }
        return assetModel(c)
    }

    fun hasModel(c: Context): Boolean = f(c, "tflite").exists() || f(c, "onnx").exists() || assetModel(c) != null

    fun save(c: Context, bytes: ByteArray): String {
        val ext = if (isTflite(bytes)) "tflite" else "onnx"
        f(c, "tflite").delete()
        f(c, "onnx").delete()
        f(c, ext).writeBytes(bytes)
        return ext
    }

    fun remove(c: Context) {
        f(c, "tflite").delete()
        f(c, "onnx").delete()
        File(c.filesDir, "names.txt").delete()
        Prefs.lastNames = ""
    }

    fun saveNames(c: Context, names: List<String>) {
        File(c.filesDir, "names.txt").writeText(names.joinToString("\n"))
        Prefs.lastNames = names.joinToString("\n")
    }

    fun savedNames(c: Context): List<String> {
        val file = File(c.filesDir, "names.txt")
        if (file.exists()) return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return Prefs.lastNames.split('\n').filter { it.isNotBlank() }
    }

    fun guessNc(shape: IntArray): Int {
        if (shape.size != 3) return -1
        if (shape[2] == 6 && shape[1] <= 1000) return -1
        val ch = min(shape[1], shape[2])
        return if (ch > 4) ch - 4 else -1
    }

    /** Builds the detector. Takes a second or two: call from a background thread. */
    fun build(c: Context): Pair<Yolo?, Info> {
        val loaded = loadBytes(c) ?: return Pair(null, Info(false, "مفيش نموذج متسجّل"))
        val bytes = loaded.second
        return try {
            val eng: Engine = if (isTflite(bytes)) TfliteEngine(bytes, Prefs.threads, Prefs.useGpu) else OrtEngine(bytes, Prefs.threads, Prefs.useNnapi)
            var names = savedNames(c)
            if (names.isEmpty()) names = eng.metaNames
            if (names.isEmpty() && isTflite(bytes)) names = Names.fromBinary(bytes)
            if (names.isEmpty() && guessNc(eng.outShapeStatic) == 80) names = Names.COCO
            val y = Yolo(eng)
            y.setNames(names, Roles.parseOverrides(Prefs.classRoles))
            if (names.isNotEmpty()) Prefs.lastNames = names.joinToString("\n")
            val used = y.roles.count { it != Role.IGNORE }
            val msg = loaded.first + " — " + eng.tag + " — " + eng.inW + "x" + eng.inH + " — " + names.size + " كلاس، منهم " + used + " مفهومين"
            Pair(y, Info(true, msg, names, eng.tag, eng.inW, eng.inH))
        } catch (t: Throwable) {
            Pair(null, Info(false, "فشل تحميل النموذج: " + t.javaClass.simpleName + " " + (t.message ?: "")))
        }
    }
}
