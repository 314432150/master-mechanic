package com.example.mastermechanic.calibration

import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.recognition.Template

/**
 * 模板提取（T1-5a，纯逻辑）：从标定帧的 RGBA 数据中裁剪指定矩形，生成灰度模板。
 *
 * 灰度化复用 [RgbaToGray]（与运行时帧灰度是同一实现），保证标定模板与运行帧同源（§5-4）。
 * 矩形为半开区间 [x0, x1) × [y0, y1)，须落在帧内且非空。
 */
object TemplateExtractor {

    fun extract(
        rgba: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        rowStride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Template {
        require(x0 >= 0 && y0 >= 0 && x1 <= frameWidth && y1 <= frameHeight && x0 < x1 && y0 < y1) {
            "裁剪区域 [$x0,$x1)×[$y0,$y1) 超出帧 ${frameWidth}x$frameHeight 或为空"
        }
        val gray = RgbaToGray.toGray(rgba, frameWidth, frameHeight, rowStride)
        val width = x1 - x0
        val height = y1 - y0
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            val rowStart = (y0 + y) * frameWidth
            gray.pixels.copyInto(pixels, y * width, rowStart + x0, rowStart + x1)
        }
        return Template(width, height, pixels)
    }
}
