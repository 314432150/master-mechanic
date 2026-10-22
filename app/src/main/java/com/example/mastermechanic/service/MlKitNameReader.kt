package com.example.mastermechanic.service

import android.graphics.Bitmap
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.DigitReader
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.NameReader
import com.example.mastermechanic.recognition.PixelBounds
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 名称识别的**安卓实现**（M4-T4-3）：ML Kit 中文文字识别（**捆绑模型版**）。
 *
 * ## 为什么是捆绑版
 *
 * vivo 国行**没有 Google Play 服务**，"按需下载模型"那版拿不到模型；捆绑版把模型打进 APK
 * （代价与包体账见 `progress.md` 第 95 条：只打 arm64-v8a 后 release 包 16.48MB，达标）。
 *
 * ## 为什么要先放大
 *
 * 帧池实测：服务器列表的**名字行字高只有 ≈15px**（游戏画面在采集帧里只占 1440×655 的一条带）。
 * 这么小的中文直接送识别很勉强 ⇒ 这里先把区域**放大 3 倍**再识别（只在需要定位的第 5 / 9 步做，
 * 不是每帧），识别结果的位置再**除回去**换成运行帧坐标。
 *
 * ## 失败口径
 *
 * 识别失败 / 超时一律返回**空列表**（= 未找到），由编排层"停下并提示"——
 * **绝不猜位置**（FR-04 红线 #4：宁可停下）。
 */
class MlKitNameReader(
    /** 放大倍数（真机字高 15px ⇒ 放大后 ≈45px）。 */
    private val scale: Int = DEFAULT_SCALE,
    /**
     * 数字块（立牌分数）用的额外放大倍数：在 [scale] 之上再乘一次（2026-10-23）。
     * 分数是花体字，放大 + 二值化后笔画边界更清楚。
     */
    private val digitScaleFactor: Int = 2,
    /**
     * 单次识别的等待上限；超时按"未找到"处理（不让帧线程无限等）。
     *
     * **3s → 1s（2026-09-22 真机改）**：这个调用是**同步**的、跑在帧线程上，
     * 3 秒的上限意味着最坏情况一轮识别就被拖 3 秒（真机帧管线统计实录：
     * 24 秒只处理 11 帧 ⇒ 约 2.2 秒一轮）。1 秒足够读完这点文字，超了就按"未找到"。
     */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : NameReader, DigitReader {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override fun read(image: GrayImage, region: PixelBounds): List<NameCandidate> = readRegion(image, region, scale, binarize = false)

    /**
     * 数字块：**二值化 + 更大放大**（2026-10-23）。真机「999」被稳定读成「666」，
     * 灰度的抗锯齿让花体 9 的笔画和 6 太像；二值化把这层模糊去掉。
     */
    override fun readDigits(image: GrayImage, region: PixelBounds): List<NameCandidate> =
        readRegion(image, region, scale * digitScaleFactor, binarize = true)

    private fun readRegion(
        image: GrayImage,
        region: PixelBounds,
        upscale: Int,
        binarize: Boolean,
    ): List<NameCandidate> {
        val width = (region.x1 - region.x0).coerceAtLeast(1)
        val height = (region.y1 - region.y0).coerceAtLeast(1)
        val source = if (binarize) toBinaryBitmap(image, region) else toBitmap(image, region)
        val bitmap = Bitmap.createScaledBitmap(source, width * upscale, height * upscale, true)
        if (source !== bitmap) source.recycle()
        return try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
            val candidates = ArrayList<NameCandidate>()
            result.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    candidates += NameCandidate(
                        text = line.text,
                        x = region.x0 + box.left / upscale,
                        y = region.y0 + box.top / upscale,
                        width = (box.width() / upscale).coerceAtLeast(1),
                        height = (box.height() / upscale).coerceAtLeast(1),
                    )
                }
            }
            MmLog.i(
                TAG,
                "文字识别：区域 ${width}x$height → 放大 ×$upscale" +
                    (if (binarize) "（数字块：已二值化）" else "") + "，读到 ${candidates.size} 行",
            )
            // 把读到的**原文**打出来（2026-09-22 真机排障）：只报"读到 N 行"时，
            // "为什么没匹配上目标名"根本无从判断 —— 是识别错了字？带了标点？还是那一行压根没扫进区域？
            MmLog.i(TAG, "读到原文：${candidates.joinToString(" ｜ ") { it.text }}")
            candidates
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            MmLog.w(TAG, "文字识别失败（按未找到处理，不猜位置）：${t.message}")
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 灰度区域 → **二值化**位图（数字块专用，2026-10-23）：阈值取区域内 (最小 + 最大) / 2。
     *
     * 区域几乎单色（极差 < [MIN_BINARY_RANGE]）时**不二值化**、原样给灰度 ——
     * 否则整块会被二值化成一整片黑或白，什么都读不到。
     */
    private fun toBinaryBitmap(image: GrayImage, region: PixelBounds): Bitmap {
        val width = (region.x1 - region.x0).coerceAtLeast(1)
        val height = (region.y1 - region.y0).coerceAtLeast(1)
        var min = 255
        var max = 0
        for (y in 0 until height) {
            val row = (region.y0 + y) * image.width
            for (x in 0 until width) {
                val v = image.pixels[row + region.x0 + x].toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        val threshold = if (max - min >= MIN_BINARY_RANGE) (min + max) / 2 else -1
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = (region.y0 + y) * image.width
            for (x in 0 until width) {
                val v = image.pixels[row + region.x0 + x].toInt() and 0xFF
                val out = if (threshold < 0) v else if (v <= threshold) 0 else 255
                pixels[y * width + x] = 0xFF000000.toInt() or (out shl 16) or (out shl 8) or out
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 灰度帧的指定区域 → 位图（灰度填成 RGB 三通道同值）。 */
    private fun toBitmap(image: GrayImage, region: PixelBounds): Bitmap {
        val width = (region.x1 - region.x0).coerceAtLeast(1)
        val height = (region.y1 - region.y0).coerceAtLeast(1)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = (region.y0 + y) * image.width
            for (x in 0 until width) {
                val v = image.pixels[row + region.x0 + x].toInt() and 0xFF
                pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {

        private const val TAG = "MM-NameReader"

        /**
         * 生产默认放大倍数。**公开出来是为了让试读探针（[com.example.mastermechanic.patrol.FriendListOcrProbe]）
         * 显式复用同一个值** —— 试读若用另一套参数，它的结论就不再代表生产。
         */
        const val DEFAULT_SCALE = 3

        /**
         * 生产默认等待上限（毫秒）。同上：试读要能报出"生产这 1 秒够不够"，
         * 就得引用同一个常量，而不是在别处再写一遍 `1000`。
         */
        const val DEFAULT_TIMEOUT_MS = 1_000L

        /** 数字块二值化的最小灰度极差：小于它视为"几乎单色"，不二值化（见 [toBinaryBitmap]）。 */
        private const val MIN_BINARY_RANGE = 24
    }
}
