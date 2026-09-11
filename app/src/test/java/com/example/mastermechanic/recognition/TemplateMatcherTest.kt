package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    @Test
    fun exactPatternLocksPositionAndHighScore() {
        val image = SyntheticImages.background(400, 300, seed = 1)
        val pattern = SyntheticImages.pattern(20, 16, seed = 2)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val peaks = TemplateMatcher.findPeaks(image, template, fullWindow, params)

        val best = peaks.first()
        assertEquals(30, best.x)
        assertEquals(40, best.y)
        assertTrue("精确匹配分数应接近 1：${best.score}", best.score > 0.99)
    }

    @Test
    fun linearBrightnessContrastChangeLeavesScoreUnchanged() {
        val image = SyntheticImages.background(400, 300, seed = 3)
        val pattern = SyntheticImages.pattern(20, 16, seed = 4)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        // ±线性变换无越界裁剪时，NCC 严格不变（§5-3 ② 背景动态的数学基础）
        val changed = SyntheticImages.transformed(image, contrast = 1.3, brightness = 25.0)
        val peaks = TemplateMatcher.findPeaks(changed, template, fullWindow, params)

        val best = peaks.first()
        assertEquals(30, best.x)
        assertEquals(40, best.y)
        assertTrue("归一化后分数应保持高位：${best.score}", best.score > 0.95)
    }

    @Test
    fun partialTranslucentOverlayKeepsMatch() {
        val image = SyntheticImages.background(400, 300, seed = 5)
        val pattern = SyntheticImages.pattern(20, 16, seed = 6)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        // 目标区域右侧约 40% 面积叠上半透明遮罩（§5-3 ①）。
        // 遮罩会拉低相似度（此处实测约 0.65，远高于噪声峰 ~0.25）：
        // 位置定位不受影响；命中线标定时需将此类遮罩场景纳入样本。
        SyntheticImages.overlay(image, 42, 40, 8, 16, alpha = 0.5, value = 230)

        val peaks = TemplateMatcher.findPeaks(image, template, fullWindow, params)

        val best = peaks.first()
        assertEquals(30, best.x)
        assertEquals(40, best.y)
        assertTrue("遮罩下分数应仍显著高于噪声：${best.score}", best.score > 0.6)
    }

    @Test
    fun targetOutsideWindowIsNotSeen() {
        val image = SyntheticImages.background(400, 300, seed = 7)
        val pattern = SyntheticImages.pattern(20, 16, seed = 8)
        SyntheticImages.drawPattern(image, 250, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 250, 40, 20, 16)
        val leftHalf = SearchWindow(0.0, 0.0, 0.5, 1.0)

        val peaks = TemplateMatcher.findPeaks(image, template, leftHalf, params)

        val best = peaks.firstOrNull()
        assertTrue("窗口外目标不得命中：${best?.score}", best == null || best.score < params.matchThreshold)
    }

    @Test
    fun windowBoundsFollowImageSize() {
        val window = SearchWindow(0.25, 0.5, 0.75, 1.0)
        assertEquals(PixelBounds(100, 150, 300, 300), window.pixelBounds(400, 300))
    }

    @Test
    fun suppressPeaksPicksStrongestAndDropsNeighbors() {
        val picked = TemplateMatcher.suppressPeaks(
            listOf(
                MatchPeak(0.90, 10, 10),
                MatchPeak(0.80, 12, 11), // 与首峰 (2,1) < 半径 5 → 同一处
                MatchPeak(0.70, 50, 50), // 远离 → 保留为竞争位置
            ),
            minDistance = 5,
        )

        assertEquals(2, picked.size)
        assertEquals(MatchPeak(0.90, 10, 10), picked[0])
        assertEquals(MatchPeak(0.70, 50, 50), picked[1])
    }

    @Test
    fun suppressPeaksIsStableForEqualScores() {
        val picked = TemplateMatcher.suppressPeaks(
            listOf(MatchPeak(0.5, 20, 8), MatchPeak(0.5, 8, 20)),
            minDistance = 5,
        )

        // 同分按 y、x 升序（确定性 tie-break，§5-4）
        assertEquals(MatchPeak(0.5, 20, 8), picked[0])
        assertEquals(MatchPeak(0.5, 8, 20), picked[1])
    }
}
