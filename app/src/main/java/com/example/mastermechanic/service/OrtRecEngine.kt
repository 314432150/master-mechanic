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
    fun recognize(bitmap: Bitmap): String = recognizeBatch(listOf(bitmap)).first()

    /**
     * **一次跑一批**（同一区域的多条行带），返回顺序与入参一致。
     *
     * ## 为什么值得批
     *
     * rec 模型**一次只能读一行**（2026-10-23 桌面实测：把两行一起喂进去读出空串、五行读出「器」），
     * 所以"选服务器"那一步一屏 26 行就得调用 26 次。而每次调用的**固定开销**（JNI + `session.run`
     * + 张量创建）在真机上占大头；批起来只付一次。
     *
     * 桌面实测（同一份 ONNX、同一批裁块）：**逐条 4 次 24.0ms vs 批量 1 次 20.7ms**，输出逐条相同
     * （`_tmp_check/ocr/probe_rec.py`）。模型输入的第 0 维是动态的（`['p2o.DynamicDimension.0', 3, '?', '?']`），
     * 因此可以直接喂 N 条。
     *
     * ## 补零口径
     *
     * 同一批里每条的宽度按长宽比各算一次，张量宽度取最大值、**右侧补 0**（归一化后的 0 = 中灰）。
     * 离线验证过"补零不影响结果"；宽度相差很大时多算的也只有极少数时间步。
     */
    fun recognizeBatch(bitmaps: List<Bitmap>): List<String> {
        if (bitmaps.isEmpty()) return emptyList()
        if (bitmaps.size == 1) {
            val bitmap = bitmaps[0]
            val width = RecInput.width(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, RecInput.HEIGHT, true)
            try {
                val pixels = IntArray(width * RecInput.HEIGHT)
                scaled.getPixels(pixels, 0, width, 0, 0, width, RecInput.HEIGHT)
                return runBatch(toBgrFloat(pixels, width, width), 1, width)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        val widths = bitmaps.map { RecInput.width(it.width, it.height) }
        val maxWidth = widths.maxOrNull() ?: return emptyList()
        val plane = maxWidth * RecInput.HEIGHT
        val data = FloatArray(bitmaps.size * 3 * plane)
        for (i in bitmaps.indices) {
            val w = widths[i]
            val scaled = Bitmap.createScaledBitmap(bitmaps[i], w, RecInput.HEIGHT, true)
            val pixels = IntArray(w * RecInput.HEIGHT)
            try {
                scaled.getPixels(pixels, 0, w, 0, 0, w, RecInput.HEIGHT)
            } finally {
                if (scaled !== bitmaps[i]) scaled.recycle()
            }
            val item = toBgrFloat(pixels, w, maxWidth)
            System.arraycopy(item, 0, data, i * 3 * plane, item.size)
        }
        return runBatch(data, bitmaps.size, maxWidth)
    }

    private fun runBatch(data: FloatArray, batch: Int, width: Int): List<String> {
        val shape = longArrayOf(batch.toLong(), 3, RecInput.HEIGHT.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            engine().run(mapOf(inputName to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val logits = output[0].value as Array<Array<FloatArray>>
                return logits.map { decode(it) }
            }
        }
    }

    private fun decode(sequence: Array<FloatArray>): String {
        val steps = sequence.size
        val classes = sequence[0].size
        val flat = FloatArray(steps * classes)
        for (t in 0 until steps) {
            System.arraycopy(sequence[t], 0, flat, t * classes, classes)
        }
        return CtcDecode.greedy(flat, steps, classes) { index -> labelOf(index) }
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

    /**
     * 灰度块 → `[3, H, planeWidth]` 的 BGR 归一化浮点（通道在外、像素在内）。
     *
     * @param bitmapWidth 这张图自己的宽度（像素数组按它排）
     * @param planeWidth 张量里的宽度（批量时 = 本批最大宽度；右侧补 0 = 归一化后的中灰）
     */
    private fun toBgrFloat(pixels: IntArray, bitmapWidth: Int, planeWidth: Int): FloatArray {
        val plane = planeWidth * RecInput.HEIGHT
        val out = FloatArray(3 * plane)
        for (y in 0 until RecInput.HEIGHT) {
            for (x in 0 until bitmapWidth) {
                val color = pixels[y * bitmapWidth + x]
                val dst = y * planeWidth + x
                // 通道 0/1/2 = B/G/R（模型按 BGR 训练）
                out[dst] = normalize(color and 0xFF)
                out[plane + dst] = normalize((color shr 8) and 0xFF)
                out[2 * plane + dst] = normalize((color shr 16) and 0xFF)
            }
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
