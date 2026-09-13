package com.example.mastermechanic.capture

import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.PixelBounds

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

    /**
     * 窗口化灰度转换（T1-10b）：仅转换 [regions] 覆盖的像素，其余保持 0。
     * 识别只读取信号窗口内像素（模板放置位置及其覆盖范围均在窗口内），
     * 因此窗口外置零与全帧转换在判定上等价；区域按帧范围裁剪、允许重叠。
     */
    fun toGrayRegions(
        rgba: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        regions: List<PixelBounds>,
    ): GrayImage {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x$height" }
        require(rowStride >= width * PIXEL_BYTES) { "行跨度 $rowStride 小于一行像素字节数（${width * PIXEL_BYTES}）" }
        require(rgba.size >= (height - 1) * rowStride + (width - 1) * PIXEL_BYTES + 3) {
            "RGBA 数据不足以覆盖 ${width}x$height（rowStride=$rowStride，实际 ${rgba.size} 字节）"
        }

        val out = ByteArray(width * height)
        for (region in regions) {
            val rx0 = region.x0.coerceIn(0, width)
            val ry0 = region.y0.coerceIn(0, height)
            val rx1 = region.x1.coerceIn(0, width)
            val ry1 = region.y1.coerceIn(0, height)
            for (y in ry0 until ry1) {
                var src = y * rowStride + rx0 * PIXEL_BYTES
                var dst = y * width + rx0
                for (x in rx0 until rx1) {
                    val r = rgba[src].toInt() and 0xFF
                    val g = rgba[src + 1].toInt() and 0xFF
                    val b = rgba[src + 2].toInt() and 0xFF
                    out[dst] = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
                    src += PIXEL_BYTES
                    dst++
                }
            }
        }
        return GrayImage(width, height, out)
    }
}
