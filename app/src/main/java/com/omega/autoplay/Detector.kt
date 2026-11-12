package com.omega.autoplay

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

interface Detector { fun detect(bmp: Bitmap): List<Detection> }

class NullDetector : Detector { override fun detect(bmp: Bitmap): List<Detection> = emptyList() }

/** YOLOv8-style TFLite detector. Put model.tflite + labels.txt in app/src/main/assets. */
class TfliteDetector(ctx: Context) : Detector {
    private val interp: Interpreter
    private val labels: List<String>
    private val size = 320

    init {
        val fd = ctx.assets.openFd("model.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(buf, Interpreter.Options().apply { setNumThreads(4) })
        labels = ctx.assets.open("labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    }

    override fun detect(bmp: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
        val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        for (p in px) {
            input.putFloat(((p shr 16) and 255) / 255f)
            input.putFloat(((p shr 8) and 255) / 255f)
            input.putFloat((p and 255) / 255f)
        }
        input.rewind()
        val shape = interp.getOutputTensor(0).shape()
        val out = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        interp.run(input, out)
        val chFirst = shape[1] < shape[2]
        val n = if (chFirst) shape[2] else shape[1]
        val ch = if (chFirst) shape[1] else shape[2]
        val nc = ch - 4
        fun v(i: Int, k: Int): Float = if (chFirst) out[0][k][i] else out[0][i][k]
        val res = ArrayList<Detection>()
        for (i in 0 until n) {
            var best = 0; var bs = 0f
            for (k in 0 until nc) { val s = v(i, 4 + k); if (s > bs) { bs = s; best = k } }
            if (bs < 0.45f) continue
            var cx = v(i, 0); var cy = v(i, 1); var w = v(i, 2); var h = v(i, 3)
            if (cx > 1.5f || cy > 1.5f || w > 1.5f) { cx /= size; cy /= size; w /= size; h /= size }
            res.add(Detection(labels.getOrElse(best) { "c$best" }, bs, cx, cy, w, h))
        }
        return nms(res)
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2; val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2; val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val iw = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
        val ih = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
        val inter = iw * ih
        val u = a.w * a.h + b.w * b.h - inter
        return if (u <= 0f) 0f else inter / u
    }

    private fun nms(d: List<Detection>): List<Detection> {
        val keep = ArrayList<Detection>()
        for (x in d.sortedByDescending { it.conf }) {
            if (keep.none { it.label == x.label && iou(it, x) > 0.5f }) keep.add(x)
        }
        return keep
    }
}
