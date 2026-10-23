package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行切分（M6 换引擎，2026-10-23）：rec-only 依赖它把区域切成"一行一块"。
 *
 * 输入是合成的灰度图（背景 40、文字 200），所以能在 JVM 里钉住"切几条、切在哪、坐标是帧坐标"。
 */
class RowBandsTest {

    private fun frame(
        width: Int,
        height: Int,
        stripes: List<IntRange>,
        background: Int = 40,
        ink: Int = 200,
    ): GrayImage {
        val pixels = ByteArray(width * height) { background.toByte() }
        for (stripe in stripes) {
            for (y in stripe) {
                if (y !in 0 until height) continue
                for (x in 0 until width) pixels[y * width + x] = ink.toByte()
            }
        }
        return GrayImage(width, height, pixels)
    }

    @Test
    fun eachTextStripeBecomesOneBandAtFrameCoordinates() {
        val image = frame(120, 100, listOf(10..19, 40..55))

        val bands = RowBands.split(image, PixelBounds(0, 0, 120, 100))

        assertEquals(2, bands.size)
        assertEquals(10, bands[0].y0)
        assertEquals(20, bands[0].y1)
        assertEquals(40, bands[1].y0)
        assertEquals(56, bands[1].y1)
        // 横向一律给整块区域的边界（行尾按钮由调用方先裁掉）
        assertEquals(0, bands[0].x0)
        assertEquals(120, bands[0].x1)
    }

    @Test
    fun thinNoiseIsDroppedInsteadOfBecomingALine() {
        // 6px 高的"字"低于 MIN_HEIGHT ⇒ 不当作一行（噪声 / 分隔线）
        val image = frame(120, 100, listOf(10..19, 80..85))

        val bands = RowBands.split(image, PixelBounds(0, 0, 120, 100))

        assertEquals(1, bands.size)
        assertEquals(10, bands[0].y0)
    }

    @Test
    fun stripesSeparatedByASmallGapAreMergedIntoOneLine() {
        // 3px 的空隙（抗锯齿 / 笔画间隙）不该把一行拆成两行
        val image = frame(120, 100, listOf(10..13, 17..20))

        val bands = RowBands.split(image, PixelBounds(0, 0, 120, 100))

        assertEquals(1, bands.size)
        assertEquals(10, bands[0].y0)
        assertEquals(21, bands[0].y1)
    }

    @Test
    fun bandsStayInsideTheRequestedRegion() {
        val image = frame(120, 100, listOf(10..19, 40..60))

        // 区域从 y=30 开始 ⇒ 第一条带（10..19）不在区域内，不该出现
        val bands = RowBands.split(image, PixelBounds(20, 30, 100, 90))

        assertEquals(1, bands.size)
        assertEquals(30 + 10, bands[0].y0) // 40
        assertEquals(20, bands[0].x0)
        assertEquals(100, bands[0].x1)
    }

    @Test
    fun flatRegionHasNoBands() {
        val image = frame(120, 100, emptyList())

        assertTrue(RowBands.split(image, PixelBounds(0, 0, 120, 100)).isEmpty())
    }

    @Test
    fun emptyRegionHasNoBands() {
        val image = frame(120, 100, listOf(10..19))

        assertTrue(RowBands.split(image, PixelBounds(50, 50, 50, 80)).isEmpty())
    }

    @Test
    fun darkTextOnLightBackgroundIsAlsoDetected() {
        // 分数弹框是"深字浅卡"，投影法不能只认浅字
        val image = frame(120, 100, listOf(10..19), background = 220, ink = 30)

        val bands = RowBands.split(image, PixelBounds(0, 0, 120, 100))

        assertEquals(1, bands.size)
        assertEquals(10, bands[0].y0)
    }
}
