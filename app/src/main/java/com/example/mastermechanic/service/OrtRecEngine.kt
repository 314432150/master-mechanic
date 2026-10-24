package com.example.mastermechanic.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.graphics.Bitmap
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.CtcDecode
import com.example.mastermechanic.recognition.RecInput
import java.nio.FloatBuffer

/**
 * PP-OCRv4 mobile rec 的**安卓执行体**（M6 换 OCR 引擎，2026-10-23）。
 *
 * ## 为什么只跑 rec、不跑 det
 *
 * 离线实测（`docs/verification/m6/t6-1-ocr-offline-gate.md` §3.3/§4.2）：det 的耗时随"检测框数"线性涨
 * （整屏 2.1~3.1s，桌面），还会把等级数字并进名字、把一行切成两块；而**区域/行带本来就在判定层手里**，
 * 直接把裁好的块喂 rec 是 20ms 级，且不出这些毛病。⇒ det 模型不捆进包，**裁块是调用方的责任**。
 *
 * ## 输入像素口径（必须与训练/验证一致，否则全错）
 *
 * 高固定 [RecInput.HEIGHT]、宽按长宽比、**BGR 通道序**、归一化 `(v/255 - 0.5)/0.5`。
 * 这条口径已在桌面用**同一份 ONNX + 同一批真机原图**验过（8/8 读对，含 `999`）。
 *
 * ## 生命周期
 *
 * 会话（[OrtSession]）很贵（10MB 模型、几十毫秒），**建一次、复用**；用完 [close]。
 * 模型从 `assets/ocr/` 读，走 `noCompress`，不联网、不需要 Google Play。
 */
class OrtRecEngine(
    private val assets: AssetManager,
    private val modelAsset: String = DEFAULT_MODEL,
    private val keysAsset: String = DEFAULT_KEYS,
) : AutoCloseable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val labels: List<String> by lazy { loadLabels() }

    private var session: OrtSession? = null

    private val inputName: String get() = engine().inputNames.iterator().next()

    /** 读一块已经裁好的图（一整行名字 / 一个分数块），返回识别到的**原文**。 */
    fun recognize(bitmap: Bitmap): String {
        val width = RecInput.width(bitmap.width, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, RecInput.HEIGHT, true)
        try {
            val pixels = IntArray(width * RecInput.HEIGHT)
            scaled.getPixels(pixels, 0, width, 0, 0, width, RecInput.HEIGHT)
            return run(toBgrFloat(pixels, width), width)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun run(data: FloatArray, width: Int): String {
        val shape = longArrayOf(1, 3, RecInput.HEIGHT.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            engine().run(mapOf(inputName to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val logits = output[0].value as Array<Array<FloatArray>>
                val steps = logits[0].size
                val classes = logits[0][0].size
                val flat = FloatArray(steps * classes)
                for (t in 0 until steps) {
                    System.arraycopy(logits[0][t], 0, flat, t * classes, classes)
                }
                return CtcDecode.greedy(flat, steps, classes) { index -> labelOf(index) }
            }
        }
    }

    /**
     * 类别下标 → 字符。口径与 PaddleOCR 一致：`0` 是空白，`1..N` 是字典字符，
     * 最后**再补一个空格类**（字典里没有空格，但模型输出里有）。
     */
    private fun labelOf(index: Int): String? = when {
        index <= 0 -> null
        index <= labels.size -> labels[index - 1]
        index == labels.size + 1 -> " "
        else -> null
    }

    /** 灰度块 → `[3, H, W]` 的 BGR 归一化浮点（通道在外、像素在内）。 */
    private fun toBgrFloat(pixels: IntArray, width: Int): FloatArray {
        val plane = width * RecInput.HEIGHT
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val color = pixels[i]
            // 通道 0/1/2 = B/G/R（模型按 BGR 训练）
            out[i] = normalize(color and 0xFF)
            out[plane + i] = normalize((color shr 8) and 0xFF)
            out[2 * plane + i] = normalize((color shr 16) and 0xFF)
        }
        return out
    }

    private fun normalize(value: Int) = (value / 255f - 0.5f) / 0.5f

    private fun loadLabels(): List<String> =
        assets.open(keysAsset).bufferedReader().useLines { lines ->
            lines.filter { it.isNotEmpty() }.toList()
        }.also { MmLog.i(TAG, "字符表：${it.size} 个") }

    private fun engine(): OrtSession = session ?: createSession().also { session = it }

    private fun createSession(): OrtSession {
        val bytes = assets.open(modelAsset).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(DEFAULT_THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val created = env.createSession(bytes, options)
        MmLog.i(TAG, "模型已加载：$modelAsset（${bytes.size / 1024}KB，线程 $DEFAULT_THREADS）")
        return created
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {

        private const val TAG = "MM-PaddleRec"

        const val DEFAULT_MODEL = "ocr/ppocrv4_rec.onnx"

        const val DEFAULT_KEYS = "ocr/ppocr_keys.txt"

        /** 帧线程同步调用 ⇒ 线程数按"别抢游戏与采集的核"取舍（真机 8 核）。 */
        const val DEFAULT_THREADS = 4
    }
}
