package com.example.mastermechanic.capture

import com.example.mastermechanic.recognition.GrayImage

/**
 * RGBA_8888 → 灰度帧转换（T1-4，纯逻辑）：采集层到识别层的格式适配。
 *
 * 亮度按 BT.601：Y = 0.299R + 0.587G + 0.114B（整数运算、四舍五入，确定性）。
 * 源数据行跨度 [rowStride] 由采集端给出（可能含对齐填充），逐行按跨度寻址；
 * 像素跨度固定为 4（RGBA_8888），不支持其他布局。
 */
object RgbaToGray {

    /** 单像素字节数（RGBA_8888）。 */
    private const val PIXEL_BYTES = 4

    fun toGray(rgba: ByteArray, width: Int, height: Int, rowStride: Int): GrayImage {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x$height" }
        require(rowStride >= width * PIXEL_BYTES) { "行跨度 $rowStride 小于一行像素字节数（${width * PIXEL_BYTES}）" }
        require(rgba.size >= (height - 1) * rowStride + (width - 1) * PIXEL_BYTES + 3) {
            "RGBA 数据不足以覆盖 ${width}x$height（rowStride=$rowStride，实际 ${rgba.size} 字节）"
        }

        val out = ByteArray(width * height)
        var dst = 0
        var rowStart = 0
        for (y in 0 until height) {
            var src = rowStart
            for (x in 0 until width) {
                val r = rgba[src].toInt() and 0xFF
                val g = rgba[src + 1].toInt() and 0xFF
                val b = rgba[src + 2].toInt() and 0xFF
                out[dst] = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
                src += PIXEL_BYTES
                dst++
            }
            rowStart += rowStride
        }
        return GrayImage(width, height, out)
    }
}
