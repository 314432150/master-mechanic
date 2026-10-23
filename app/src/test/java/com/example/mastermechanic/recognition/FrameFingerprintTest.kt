package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 画面指纹（2026-10-23）：**"这一屏跟上次读文字时一模一样吗"** 的判据。
 *
 * 它守的是"第二遍读可以复用第一遍结果"这条捷径 —— 只有像素确实没变才允许，
 * 变了就必须真读（2026-09-29 事故：画面回弹了 2 行，却拿旧读数当"两次一致"，点错区服）。
 */
class FrameFingerprintTest {

    private fun image(width: Int, height: Int, fill: Int = 40): GrayImage =
        GrayImage(width, height, ByteArray(width * height) { fill.toByte() })

    private val region = PixelBounds(10, 10, 60, 40)

    @Test
    fun sameImageAndRegionGiveTheSameFingerprint() {
        val a = image(100, 100)
        val b = image(100, 100)
        assertEquals(FrameFingerprint.of(a, listOf(region)), FrameFingerprint.of(b, listOf(region)))
    }

    @Test
    fun aChangedPixelInsideTheRegionChangesTheFingerprint() {
        val a = image(100, 100)
        val b = image(100, 100)
        b.pixels[20 * 100 + 20] = 200.toByte()
        assertNotEquals(FrameFingerprint.of(a, listOf(region)), FrameFingerprint.of(b, listOf(region)))
    }

    @Test
    fun changesOutsideTheRegionDoNotMatter() {
        // 区域外的像素变化与本步读数无关（例如别处有弹窗动画）⇒ 仍可判定"这一屏没变"
        val a = image(100, 100)
        val b = image(100, 100)
        b.pixels[80 * 100 + 80] = 200.toByte()
        assertEquals(FrameFingerprint.of(a, listOf(region)), FrameFingerprint.of(b, listOf(region)))
    }

    @Test
    fun regionOrderMattersSoCallersMustKeepItStable() {
        // 两列是按固定顺序读的；这里钉住"指纹对输入顺序敏感"，避免有人误以为可以随意重排。
        // 两块必须**内容不同**，否则顺序换不换结果都一样（那正是这条用例最初写错的地方）。
        val a = image(100, 100)
        val left = PixelBounds(10, 10, 40, 40)
        val right = PixelBounds(50, 10, 90, 40)
        for (y in 10 until 40) {
            for (x in 50 until 90) a.pixels[y * 100 + x] = 200.toByte()
        }
        assertNotEquals(
            FrameFingerprint.of(a, listOf(left, right)),
            FrameFingerprint.of(a, listOf(right, left)),
        )
    }

    @Test
    fun emptyRegionListYieldsTheEmptyMarker() {
        assertEquals(FrameFingerprint.EMPTY, FrameFingerprint.of(image(10, 10), emptyList()))
    }

    @Test
    fun degenerateRegionYieldsTheEmptyMarker() {
        assertEquals(
            FrameFingerprint.EMPTY,
            FrameFingerprint.of(image(10, 10), listOf(PixelBounds(5, 5, 5, 8))),
        )
    }
}
