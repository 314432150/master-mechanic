package com.example.mastermechanic.calibration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.mastermechanic.recognition.Template
import java.io.File
import java.nio.ByteBuffer

/**
 * 标定帧文件工具（T1-5b）：PNG 帧的解码——预览缩略图、原始 RGBA 读出与模板灰度预览。
 *
 * 依赖安卓框架，仅 UI 侧使用；模板提取本身为纯逻辑（见 [TemplateExtractor]）。
 */
object CalibrationFrames {

    /** 原始帧的 RGBA 像素与尺寸。 */
    class FrameRgba(val pixels: ByteArray, val width: Int, val height: Int)

    /** 预览缩略图与原始帧尺寸（标注换算以原始尺寸为准，与缩略图采样率无关）。 */
    class Preview(val bitmap: Bitmap, val frameWidth: Int, val frameHeight: Int)

    /**
     * 读取原分辨率 RGBA（压紧行跨度 = 宽×4）；解码失败返回 null（损坏文件）。
     *
     * **最近一次的结果留在内存里**（[lastRgba]）：框选一个界面往往要在**同一张帧**上写好几条
     * （标志 + 锚点、或者几个不同控件），而每次都要"解 PNG（≈18MB 位图）→ 拷成 18MB 字节数组"。
     * 留一份 ⇒ 同帧的后续框选几乎零成本（真机 07:40 那串 ≈350MB/s 的分配里也有它的份）。
     * 只留一份：多了就是拿内存换速度，不划算。
     */
    fun readRgba(file: File): FrameRgba? {
        val key = file.absolutePath
        lastRgba?.let { if (it.first == key) return it.second }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val rgba = try {
            val pixels = ByteArray(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
            FrameRgba(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
        lastRgba = key to rgba
        return rgba
    }

    /** 最近一次 [readRgba] 的结果（路径 → 像素）；见它的说明。 */
    @Volatile
    private var lastRgba: Pair<String, FrameRgba>? = null

    /** 模板灰度 → 预览位图（T1-5l 产物查看）：模板像素即灰度值，展开为不透明 ARGB 灰。 */
    fun templateBitmap(template: Template): Bitmap {
        val argb = IntArray(template.width * template.height) { i ->
            val gray = template.pixels[i].toInt() and 0xFF
            (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        return Bitmap.createBitmap(argb, template.width, template.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * **预览解码缓存**（2026-09-29 关键修复，"连续框选几张图就崩"的元凶）。
     *
     * ## 为什么必须缓存
     *
     * 帧是**全分辨率 PNG**（3168×1440）。`BitmapFactory` 对 PNG 的 `inSampleSize` **省不下解码开销**：
     * 它仍要按原尺寸解出来再缩放 ⇒ **一次预览解码 ≈ 35MB 的短命分配**（18MB 位图 + 解码缓冲）。
     * 而缩略图条 / 翻帧页**每次重组都重新解码**（`LazyRow` 把划出屏幕的项丢掉，滑回来 = 再解一次）——
     * 标定者在框选时**一直在翻帧**，于是真机实录：
     * ```
     * 07:40:16 起  GC freed 91~96MB AllocSpace bytes, 0(0B) LOS objects，每 ~270ms 一次（≈350MB/s）
     * 07:40:20     am_anr: Input dispatching timed out … Waited 5000ms for MotionEvent（黑屏）
     * ```
     * ⇒ ≈10 次解码/秒 × 35MB = 350MB/s ✓ 与实录吻合。缓存之后同一个 (文件, 宽度) 只解一次。
     *
     * 预算 24MB（按位图 `byteCount` 计）：缩略图（144 宽 ≈37KB）能放几百张，大图（1080 宽 ≈3MB）
     * 能放七八张 —— 足够覆盖"来回翻十张帧"的实际动作，又不会把内存吃回去。
     *
     * 线程安全：`LruCache` 自带同步；解码本来就在 `Dispatchers.IO` 上做。
     */
    private val previewCache = object : LruCache<String, Preview>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Preview): Int = value.bitmap.byteCount
    }

    /** 解码预览缩略图（宽 ≤ maxWidth 的最大 2 次幂下采样）；失败返回 null。**带缓存**（见 [previewCache]）。 */
    fun decodePreview(file: File, maxWidth: Int): Preview? {
        val key = "${file.absolutePath}@$maxWidth"
        previewCache.get(key)?.let { return it }
        val decoded = decodePreviewUncached(file, maxWidth) ?: return null
        previewCache.put(key, decoded)
        return decoded
    }

    private fun decodePreviewUncached(file: File, maxWidth: Int): Preview? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return Preview(bitmap, bounds.outWidth, bounds.outHeight)
    }

    /** 预览缓存预算（字节）：见 [previewCache] 的说明。 */
    private const val PREVIEW_CACHE_BYTES = 24 * 1024 * 1024
}
