package com.example.mastermechanic.calibration

import com.example.mastermechanic.capture.RgbaToGray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 模板提取单测（T1-5a）：RGBA 帧 → 灰度模板 的裁剪正确性、行跨度处理与边界拒绝。
 */
class TemplateExtractorTest {

    /** 4x3 测试帧：每列一种纯色（红 / 绿 / 蓝 / 白），三行相同。 */
    private fun colorColumnsFrame(): ByteArray {
        val colors = arrayOf(
            intArrayOf(255, 0, 0),
            intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255),
            intArrayOf(255, 255, 255),
        )
        val bytes = ByteArray(4 * 3 * 4)
        var i = 0
        repeat(3) {
            colors.forEach { (r, g, b) ->
                bytes[i++] = r.toByte()
                bytes[i++] = g.toByte()
                bytes[i++] = b.toByte()
                bytes[i++] = 0xFF.toByte()
            }
        }
        return bytes
    }

    @Test
    fun extractsSubRegionWithExpectedLuma() {
        // BT.601：红=76、绿=150、蓝=29、白=255
        val template = TemplateExtractor.extract(
            rgba = colorColumnsFrame(),
            frameWidth = 4,
            frameHeight = 3,
            rowStride = 16,
            x0 = 1,
            y0 = 0,
            x1 = 3,
            y1 = 2,
        )
        assertEquals(2, template.width)
        assertEquals(2, template.height)
        assertArrayEquals(
            byteArrayOf(150.toByte(), 29.toByte(), 150.toByte(), 29.toByte()),
            template.pixels,
        )
    }

    @Test
    fun extractWholeFrameMatchesRgbaToGrayPipeline() {
        // 提取与运行时帧灰度同源（同一实现）：全帧提取 = RgbaToGray 结果
        val pixels = ByteArray(5 * 4) { (it * 7 % 251).toByte() }
        val rgba = SyntheticRgba.fromGray(pixels, 5, 4)
        val template = TemplateExtractor.extract(rgba, 5, 4, 20, 0, 0, 5, 4)
        val gray = RgbaToGray.toGray(rgba, 5, 4, 20)
        assertArrayEquals(gray.pixels, template.pixels)
    }

    @Test
    fun respectsRowStridePadding() {
        val pixels = ByteArray(4 * 3) { (100 + it).toByte() }
        val rgba = SyntheticRgba.fromGray(pixels, 4, 3, rowStride = 24) // 每行 8 字节填充
        val template = TemplateExtractor.extract(rgba, 4, 3, 24, 2, 1, 4, 2)
        assertEquals(2, template.width)
        assertEquals(1, template.height)
        assertArrayEquals(byteArrayOf(pixels[4 + 2], pixels[4 + 3]), template.pixels)
    }

    @Test
    fun rejectsOutOfBoundsRect() {
        val rgba = colorColumnsFrame()
        assertThrows(IllegalArgumentException::class.java) {
            TemplateExtractor.extract(rgba, 4, 3, 16, 0, 0, 5, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateExtractor.extract(rgba, 4, 3, 16, 0, 2, 4, 4)
        }
    }

    @Test
    fun rejectsEmptyOrNegativeRect() {
        val rgba = colorColumnsFrame()
        assertThrows(IllegalArgumentException::class.java) {
            TemplateExtractor.extract(rgba, 4, 3, 16, 2, 0, 2, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateExtractor.extract(rgba, 4, 3, 16, -1, 0, 3, 3)
        }
    }
}
