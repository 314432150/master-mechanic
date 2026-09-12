package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 两级匹配优化（T1-10a）一致性测试：与「穷举全精度」参考实现对照。
 *
 * 对照口径：分数 ≥ [STRONG_SCORE] 的峰集合必须逐项一致（位置精确、分数数值容差
 * [SCORE_TOLERANCE]）——这些峰对应「命中 / 竞争位置」判定与审计字段；低分噪声峰
 * 不在对照范围（其位置本就不稳定，判定不受影响）。
 */
class TemplateMatcherOptimizedTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    @Test
    fun singlePeakMatchesReference() {
        val image = SyntheticImages.background(400, 300, seed = 11)
        val pattern = SyntheticImages.pattern(20, 16, seed = 12)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val actual = TemplateMatcher.findPeaks(image, template, fullWindow, params)
        val expected = referenceFindPeaks(image, template, fullWindow, params)

        assertSameStrongPeaks(expected, actual, "单峰")
        val best = actual.first()
        assertEquals("峰位置 x", 30, best.x)
        assertEquals("峰位置 y", 40, best.y)
        assertTrue("精确匹配分数应接近 1：${best.score}", best.score > 0.99)
    }

    @Test
    fun duplicatePatternsBothSurviveSuppression() {
        val image = SyntheticImages.background(400, 300, seed = 13)
        val pattern = SyntheticImages.pattern(20, 16, seed = 14)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        SyntheticImages.drawPattern(image, 150, 200, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val actual = TemplateMatcher.findPeaks(image, template, fullWindow, params)
        val expected = referenceFindPeaks(image, template, fullWindow, params)

        assertSameStrongPeaks(expected, actual, "重复目标")
        assertEquals("两处相似目标均应保留为强峰", 2, actual.count { it.score >= 0.9 })
        assertEquals(30, actual[0].x)
        assertEquals(150, actual[1].x)
    }

    @Test
    fun narrowTextureInGridBlindSpotIsFound() {
        // 2px 宽的亮条：完美匹配峰宽度（2px）远小于粗搜网格步进（盲区中心场景）；
        // 注：1px 线在奇偶周期下与网格除不尽，属已知边界（需步进 1，成本超预算）。
        val image = SyntheticImages.background(400, 300, seed = 15)
        for (y in 60..89) {
            image.pixels[y * image.width + 101] = 255.toByte()
            image.pixels[y * image.width + 102] = 255.toByte()
        }
        val template = SyntheticImages.crop(image, 99, 60, 7, 30)

        val actual = TemplateMatcher.findPeaks(image, template, fullWindow, params)
        val expected = referenceFindPeaks(image, template, fullWindow, params)

        // 弱纹理场景（2px 信号 / 7px 模板）：仅对照判定输入（top1 / top2 / 差距）。
        // 尾部低分峰（参考的 0.50-0.76）不带判定信息，粗搜近似允许其缺失。
        assertSameDecisionPeaks(expected, actual, "窄纹理")
        val best = actual.first()
        assertEquals("窄纹理峰位置 x", 99, best.x)
        assertEquals("窄纹理峰位置 y", 60, best.y)
    }

    @Test
    fun translucentOverlayPositionUnaffected() {
        val image = SyntheticImages.background(400, 300, seed = 16)
        val pattern = SyntheticImages.pattern(20, 16, seed = 17)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)
        SyntheticImages.overlay(image, 42, 40, 8, 16, alpha = 0.5, value = 230)

        val actual = TemplateMatcher.findPeaks(image, template, fullWindow, params)
        val expected = referenceFindPeaks(image, template, fullWindow, params)

        assertSameStrongPeaks(expected, actual, "遮罩")
        assertEquals(30, actual.first().x)
        assertEquals(40, actual.first().y)
    }

    @Test
    fun constantTemplateAndFlatImageProduceNoPeaks() {
        val flat = GrayImage(80, 60, ByteArray(80 * 60) { 128.toByte() })
        val textured = Template(8, 8, SyntheticImages.pattern(8, 8, seed = 18))
        val constant = Template(8, 8, ByteArray(8 * 8) { 128.toByte() })

        assertTrue("恒定帧无有效方差", TemplateMatcher.findPeaks(flat, textured, fullWindow, params).isEmpty())
        assertTrue("常量模板无有效纹理", TemplateMatcher.findPeaks(flat, constant, fullWindow, params).isEmpty())
    }

    @Test
    fun windowTooSmallForTemplateProducesNoPeaks() {
        val image = SyntheticImages.background(400, 300, seed = 19)
        val template = SyntheticImages.crop(image, 30, 40, 40, 30)
        val tiny = SearchWindow(0.0, 0.0, 0.05, 0.05) // 20x15 像素，装不下 40x30 模板

        assertTrue(TemplateMatcher.findPeaks(image, template, tiny, params).isEmpty())
    }

    @Test
    fun repeatedRunsAreFieldEqual() {
        val image = SyntheticImages.background(400, 300, seed = 20)
        val pattern = SyntheticImages.pattern(20, 16, seed = 21)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val first = TemplateMatcher.findPeaks(image, template, fullWindow, params)
        val second = TemplateMatcher.findPeaks(image, template, fullWindow, params)

        assertEquals("同输入两次运行逐字段一致（§5-4）", first, second)
    }

    @Test
    fun windowEdgePositionIsStillScanned() {
        // 目标贴近窗口右 / 下边界（网格末点覆盖场景）：窗口恰好到目标右下角
        val image = SyntheticImages.background(400, 300, seed = 22)
        val pattern = SyntheticImages.pattern(20, 16, seed = 23)
        SyntheticImages.drawPattern(image, 250, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 250, 40, 20, 16)
        // 右边界 = (250+20)/400 = 0.675：maxX 位置即目标位
        val window = SearchWindow(0.0, 0.0, 0.675, 1.0)

        val actual = TemplateMatcher.findPeaks(image, template, window, params)
        val expected = referenceFindPeaks(image, template, window, params)

        assertSameStrongPeaks(expected, actual, "窗口边界")
        assertEquals("贴边目标应被定位", 250, actual.first().x)
    }

    // ---- 对照辅助 ----

    /** 分数 ≥ 0.5 的峰集合应逐项一致（位置精确、分数容差内）。 */
    private fun assertSameStrongPeaks(expected: List<MatchPeak>, actual: List<MatchPeak>, scene: String) {
        val expectedStrong = expected.filter { it.score >= STRONG_SCORE }
        val actualStrong = actual.filter { it.score >= STRONG_SCORE }
        assertEquals("$scene：高分辨峰数", expectedStrong.size, actualStrong.size)
        expectedStrong.zip(actualStrong).forEachIndexed { index, (e, a) ->
            assertEquals("$scene：峰 $index 位置 x", e.x, a.x)
            assertEquals("$scene：峰 $index 位置 y", e.y, a.y)
            assertEquals("$scene：峰 $index 分数", e.score, a.score, SCORE_TOLERANCE)
        }
    }

    /**
     * 判定口径对照（弱纹理场景）：S1 判定输入 = suppress 后 top1 位置 / 分数与 top2 分数
     * （最高与次高分差距决定命中 / 歧义）。弱信号下尾部峰的位置与个数本就不稳定，
     * 粗搜近似允许其差异；但判定输入必须与穷举全精度一致。
     */
    private fun assertSameDecisionPeaks(expected: List<MatchPeak>, actual: List<MatchPeak>, scene: String) {
        assertTrue("$scene：参考应至少检出 1 个峰", expected.isNotEmpty())
        assertTrue("$scene：实际应至少检出 1 个峰", actual.isNotEmpty())
        assertEquals("$scene：top1 位置 x", expected[0].x, actual[0].x)
        assertEquals("$scene：top1 位置 y", expected[0].y, actual[0].y)
        assertEquals("$scene：top1 分数", expected[0].score, actual[0].score, SCORE_TOLERANCE)
        if (expected.size >= 2) {
            assertTrue("$scene：参考次峰非空时实际也应有次峰", actual.size >= 2)
            assertEquals("$scene：top2 分数（差距判定输入）", expected[1].score, actual[1].score, SCORE_TOLERANCE)
            assertEquals(
                "$scene：top1-top2 差距",
                expected[0].score - expected[1].score,
                actual[0].score - actual[1].score,
                SCORE_TOLERANCE,
            )
        }
    }

    /** 穷举全精度参考实现（优化前的朴素算法，仅测试对照用）。 */
    private fun referenceFindPeaks(
        image: GrayImage,
        template: Template,
        window: SearchWindow,
        params: MatchParams,
    ): List<MatchPeak> {
        if (template.centeredSumSq <= 1e-6) return emptyList()
        val bounds = window.pixelBounds(image.width, image.height)
        val maxX = bounds.x1 - template.width
        val maxY = bounds.y1 - template.height
        if (maxX < bounds.x0 || maxY < bounds.y0) return emptyList()
        val candidates = ArrayList<MatchPeak>()
        for (y in bounds.y0..maxY) {
            for (x in bounds.x0..maxX) {
                val score = referenceNcc(image, template, x, y)
                if (score > 0.0) candidates.add(MatchPeak(score, x, y))
            }
        }
        return TemplateMatcher.suppressPeaks(candidates, params.peakMinDistance)
    }

    private fun referenceNcc(image: GrayImage, template: Template, x: Int, y: Int): Double {
        val tw = template.width
        val th = template.height
        val centered = template.centered
        var sumP = 0.0
        var sumP2 = 0.0
        var dot = 0.0
        var rowStart = y * image.width + x
        var ti = 0
        for (ty in 0 until th) {
            var i = rowStart
            for (tx in 0 until tw) {
                val p = (image.pixels[i].toInt() and 0xFF).toDouble()
                sumP += p
                sumP2 += p * p
                dot += centered[ti] * p
                i++
                ti++
            }
            rowStart += image.width
        }
        val n = (tw * th).toDouble()
        val variance = sumP2 - sumP * sumP / n
        if (variance / n < 1e-6) return 0.0
        return dot / sqrt(template.centeredSumSq * variance)
    }

    private companion object {
        const val STRONG_SCORE = 0.5
        const val SCORE_TOLERANCE = 1e-6
    }
}
