package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亮度判据的纯逻辑单测（`RegionBrightness` / `BrightnessBaseline`）。
 *
 * 这些用例**不涉及任何真机数值**（除最后一条只用来钉住"两态必须落在分界两侧"）：
 * 均值、裁剪、分界都是构造出来的。真机可分性由 `BlessBrightnessProbeTest` 用真实帧验证。
 */
class RegionBrightnessTest {

    /** 造一张 width×height 的灰度帧，像素值由 (x,y) 决定。 */
    private fun image(width: Int, height: Int, value: (Int, Int) -> Int): GrayImage {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = value(x, y).toByte()
            }
        }
        return GrayImage(width, height, pixels)
    }

    @Test
    fun meanAveragesOnlyTheGivenBounds() {
        // 左半 10、右半 200：整帧均值 105，只取左半应为 10
        val frame = image(10, 4) { x, _ -> if (x < 5) 10 else 200 }
        assertEquals(10.0, RegionBrightness.mean(frame, PixelBounds(0, 0, 5, 4))!!, 1e-9)
        assertEquals(200.0, RegionBrightness.mean(frame, PixelBounds(5, 0, 10, 4))!!, 1e-9)
        assertEquals(105.0, RegionBrightness.mean(frame, PixelBounds(0, 0, 10, 4))!!, 1e-9)
    }

    @Test
    fun meanIsExactForNonIntegerAverages() {
        // 三个像素 0 / 1 / 2 ⇒ 1.0；两个像素 0 / 1 ⇒ 0.5（不能被取整成 0 或 1）
        val frame = image(3, 1) { x, _ -> x }
        assertEquals(1.0, RegionBrightness.mean(frame, PixelBounds(0, 0, 3, 1))!!, 1e-9)
        assertEquals(0.5, RegionBrightness.mean(frame, PixelBounds(0, 0, 2, 1))!!, 1e-9)
    }

    @Test
    fun brightPixelsBelow255AreReadAsUnsigned() {
        // 200 存进 Byte 会变成负数，读取必须按无符号还原（否则均值会掉到 0 附近）
        val frame = image(2, 2) { _, _ -> 200 }
        assertEquals(200.0, RegionBrightness.mean(frame, PixelBounds(0, 0, 2, 2))!!, 1e-9)
    }

    @Test
    fun emptyOrInvertedBoundsGiveNull() {
        val frame = image(4, 4) { _, _ -> 128 }
        assertNull("宽度为 0", RegionBrightness.mean(frame, PixelBounds(2, 2, 2, 4)))
        assertNull("高度为 0", RegionBrightness.mean(frame, PixelBounds(0, 0, 4, 0)))
        assertNull("反过来的矩形", RegionBrightness.mean(frame, PixelBounds(3, 0, 1, 4)))
    }

    @Test
    fun outOfFrameBoundsAreClippedInsteadOfCrashing() {
        val frame = image(4, 4) { _, _ -> 50 }
        assertEquals(50.0, RegionBrightness.mean(frame, PixelBounds(-5, -5, 99, 99))!!, 1e-9)
        assertNull("整块在帧外 ⇒ 读不到", RegionBrightness.mean(frame, PixelBounds(10, 10, 20, 20)))
    }

    @Test
    fun baselinePutsThresholdAtMidpoint() {
        val baseline = BrightnessBaseline(onMean = 165.0, offMean = 122.6)
        assertEquals(143.8, baseline.threshold, 1e-9)
        assertTrue(baseline.isOn(165.0))
        assertTrue("恰好等于分界时判亮", baseline.isOn(143.8))
        assertFalse(baseline.isOn(143.79))
        assertFalse(baseline.isOn(122.6))
    }

    @Test
    fun baselineRejectsImpossiblePairs() {
        assertThrows("亮态必须严格大于灰态", IllegalArgumentException::class.java) {
            BrightnessBaseline(onMean = 120.0, offMean = 120.0)
        }
        assertThrows("反过来的基线是接线错误", IllegalArgumentException::class.java) {
            BrightnessBaseline(onMean = 100.0, offMean = 160.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrightnessBaseline(onMean = Double.NaN, offMean = 100.0)
        }
    }

    @Test
    fun baselineSeparatesTheRealDeviceMeans() {
        // 2026-10-28 真机实测的两个值（写进用例只为钉住"这两态必须落在分界两侧"，
        // 不作为产品默认值 —— 产品阈值一律来自标定产物）
        val baseline = BrightnessBaseline(onMean = 165.0, offMean = 122.6)
        assertTrue("亮态应判亮", baseline.isOn(165.0))
        assertFalse("灰态应判灰", baseline.isOn(122.6))
    }
}
