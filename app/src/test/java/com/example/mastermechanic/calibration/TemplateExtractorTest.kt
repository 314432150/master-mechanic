package com.example.mastermechanic.calibration

import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    // ---------------------------------------------------------------- 缩略模板（2026-09-29）

    @Test
    fun previewLeavesSmallTemplatesUntouched() {
        // 长边已经不超过上限 ⇒ **原样返回同一个对象**（调用方据此判断"要不要落盘"）
        val template = Template(40, 30, ByteArray(40 * 30) { it.toByte() })
        assertSame(template, TemplateExtractor.preview(template, maxSide = 64))
    }

    @Test
    fun previewShrinksTheLongSideToTheCap() {
        // 只圈区域的锚点不再存整块：真机 `server_list_area` 2532×1118 把产物撑到 5.4MB，
        // 重载解码 OOM、把帧线程读崩过（progress 第 258 条）
        val template = Template(2532, 1118, SyntheticImages.pattern(2532, 1118, seed = 7L))
        val preview = TemplateExtractor.preview(template, maxSide = 64)

        assertEquals(64, preview.width)
        assertEquals(28, preview.height) // 按比例缩（2532/1118 ≈ 2.26 ⇒ 64/28.3）
        assertEquals(64 * 28, preview.pixels.size)
        // 最近邻抽样 ⇒ 每个点都来自原图某个**真实位置**（没插值出来的假像素）
        val sourceValues = template.pixels.toSet()
        assertTrue(preview.pixels.all { it in sourceValues })
    }

    @Test
    fun previewKeepsAspectRatioAndCoversTheWholeArea() {
        // 8×1 缩到 4×1：四个点应均匀覆盖整条（首尾都要沾到，不能只取前半段）
        val wide = Template(8, 1, byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        val preview = TemplateExtractor.preview(wide, maxSide = 4)
        assertEquals(4, preview.width)
        assertEquals(1, preview.height)
        assertArrayEquals(byteArrayOf(10, 30, 50, 70), preview.pixels)
    }

    @Test
    fun previewRejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException::class.java) {
            TemplateExtractor.preview(Template(8, 8, ByteArray(64)), maxSide = 0)
        }
    }
}
