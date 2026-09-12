package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布几何单测（T1-11c）：以 T1-11a **真机实测值**为锚，钉住「画面区」模型与换算口径。
 *
 * 实测出处：`docs/recognition/research/research-canvas-orientation.md`
 * （竖画布内容带行 1256..1911、横画布满幅、横 → 竖 = ×2.2 + 平移）。
 */
class CanvasGeometryTest {

    private val geometry = CanvasGeometry.of(1440, 3168)

    @Test
    fun calibrationAreaMatchesMeasuredBand() {
        // 上留白 1256 / 下留白 1257 / 左右无留白（实测）
        assertEquals(ContentArea(0, 1256, 1440, 1911), geometry.calibrationArea)
    }

    @Test
    fun transposedFrameAreaCoversWholeFrame() {
        // 横画布 3168×1440：画面满幅（实测内容带 = 整幅）
        assertEquals(ContentArea(0, 0, 3168, 1440), geometry.contentArea(3168, 1440))
    }

    @Test
    fun calibrationSizeMappingIsIdentity() {
        val mapping = geometry.mappingFor(1440, 3168)
        assertTrue(mapping.isIdentity)
        assertEquals(1.0, mapping.scale, 1e-9)
        assertEquals(651.0, mapping.toFrameX(651.0), 1e-9)
        assertEquals(1256.0, mapping.toFrameY(1256.0), 1e-9)
    }

    @Test
    fun transposedMappingReproducesMeasuredScaleAndOffset() {
        // 标定锚点（开始游戏，651,1744）在横画布帧上的位置实测为 1432.2 × 1073.6
        val mapping = geometry.mappingFor(3168, 1440)
        assertFalse(mapping.isIdentity)
        assertEquals(2.2, mapping.scale, 1e-9)
        assertEquals(1432.2, mapping.toFrameX(651.0), 1e-9)
        assertEquals(1073.6, mapping.toFrameY(1744.0), 1e-9)
        assertEquals(651.0, mapping.toCalibrationX(1432.2), 1e-9)
        assertEquals(1744.0, mapping.toCalibrationY(1073.6), 1e-9)
    }

    @Test
    fun sourceBoundsMapsWindowWithSamplingPadding() {
        val mapping = geometry.mappingFor(3168, 1440)
        val window = PixelBounds(520, 1707, 913, 1818) // launch_start 搜索窗口（标定坐标）
        assertEquals(PixelBounds(1142, 990, 2011, 1239), mapping.sourceBounds(window))
    }

    @Test
    fun sourceBoundsIsClippedToFrame() {
        val mapping = geometry.mappingFor(3168, 1440)
        val clamped = mapping.sourceBounds(PixelBounds(0, 0, 5, 5))
        // 画面区之上（标定 y=0 位于留白）→ 源区域上边界被裁剪到帧内
        assertEquals(0, clamped.x0)
        assertEquals(0, clamped.y0)
        assertTrue(clamped.x1 <= mapping.frameWidth && clamped.y1 <= mapping.frameHeight)
    }

    @Test
    fun smallCanvasKeepsIntegerRoundingDeterministic() {
        // 40×88（同长宽比）：画面带 40×18，起点 (88−18)/2 = 35
        assertEquals(ContentArea(0, 35, 40, 53), CanvasGeometry.of(40, 88).calibrationArea)
    }
}
