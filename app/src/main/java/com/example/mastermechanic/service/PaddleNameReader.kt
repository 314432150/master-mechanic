package com.example.mastermechanic.service

import android.graphics.Bitmap
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.NameReader
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.RowBands

/**
 * 名称识别的 **Paddle 实现**（M6 换引擎）：`NameReader` 的另一个实现，**接口与坐标语义不变**。
 *
 * ## 与 ML Kit 版的差别（也是它为什么更省事）
 *
 * ML Kit 版把整块区域丢给引擎，由引擎自己切行；这条路换到 rec-only 引擎上不成立（rec 一次只认一行）。
 * 所以这里先用 [RowBands] 按"墨量投影"**把区域切成行带**，再逐行喂 [OrtRecEngine]。
 * 好处：不带检测模型、每行 ~20ms、不会把等级数字并进名字（离线实测见证据文档 §4.2）。
 *
 * ## 坐标
 *
 * 返回的 `x/y` 一律是**运行帧坐标**（[RowBands] 已经把区域偏移加回去了），
 * 与 ML Kit 版"除以放大倍数再加区域原点"等价 —— 判定层的行带 / 列顺序不受影响。
 *
 * ## 失败口径
 *
 * 一行读不出字就跳过那一行（不猜、不补）。整体异常由调用方按"未找到"处理（与 ML Kit 版一致）。
 */
class PaddleNameReader(
    /** 一行灰度小图 → 原文。抽成函数是为了单测能塞假的实现（真机走 `OrtRecEngine::recognize`）。 */
    private val recognize: (Bitmap) -> String,
) : NameReader {

    override fun read(image: GrayImage, region: PixelBounds): List<NameCandidate> {
        val bands = RowBands.split(image, region)
        val candidates = ArrayList<NameCandidate>(bands.size)
        for (band in bands) {
            val bitmap = toBitmap(image, band)
            val text = try {
                recognize(bitmap)
            } finally {
                bitmap.recycle()
            }.trim()
            if (text.isEmpty()) continue
            candidates += NameCandidate(
                text = text,
                x = band.x0,
                y = band.y0,
                width = (band.x1 - band.x0).coerceAtLeast(1),
                height = (band.y1 - band.y0).coerceAtLeast(1),
            )
        }
        MmLog.i(
            TAG,
            "文字识别：区域 ${region.x1 - region.x0}x${region.y1 - region.y0} → 切出 ${bands.size} 行带，" +
                "读到 ${candidates.size} 行",
        )
        // 原文照打（排障口径与 ML Kit 版一致：不然"为什么没匹配上"无从判断）
        MmLog.i(TAG, "读到原文：${candidates.joinToString(" ｜ ") { it.text }}")
        return candidates
    }

    /** 灰度帧的指定区域 → 位图（灰度填成 RGB 三通道同值，与 ML Kit 版同一条路径）。 */
    private fun toBitmap(image: GrayImage, band: PixelBounds): Bitmap {
        val width = (band.x1 - band.x0).coerceAtLeast(1)
        val height = (band.y1 - band.y0).coerceAtLeast(1)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = (band.y0 + y) * image.width
            for (x in 0 until width) {
                val v = image.pixels[row + band.x0 + x].toInt() and 0xFF
                pixels[y * width + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    companion object {

        private const val TAG = "MM-PaddleName"

        /** 生产装配：一个引擎（一次装模型）配一个 reader。 */
        fun of(engine: OrtRecEngine): PaddleNameReader = PaddleNameReader { engine.recognize(it) }
    }
}
