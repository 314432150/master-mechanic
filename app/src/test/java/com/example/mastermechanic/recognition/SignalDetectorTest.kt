package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDetectorTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    // ---- 判定规则级（§5-2，精确边界）----

    @Test
    fun judgeRejectsWhenNoPeaks() {
        assertEquals(Verdict.NOT_MATCHED, SignalDetector(emptyList(), params).judge(emptyList()))
    }

    @Test
    fun judgeRejectsBestBelowThreshold() {
        assertEquals(
            Verdict.NOT_MATCHED,
            SignalDetector(emptyList(), params).judge(listOf(MatchPeak(0.84, 1, 1))),
        )
    }

    @Test
    fun judgeMatchesAtExactThresholdWithoutCompetitor() {
        assertEquals(
            Verdict.MATCHED,
            SignalDetector(emptyList(), params).judge(listOf(MatchPeak(0.85, 1, 1))),
        )
    }

    @Test
    fun judgeUnreliableWhenRunnerUpAlsoAboveThreshold() {
        // 多处高分：竞争位置也达到命中线（0.90 ≥ 0.85）
        assertEquals(
            Verdict.UNRELIABLE,
            SignalDetector(emptyList(), params).judge(
                listOf(MatchPeak(0.97, 10, 10), MatchPeak(0.90, 90, 60)),
            ),
        )
    }

    @Test
    fun judgeUnreliableWhenMarginTooSmall() {
        // 差距过小：0.97 − 0.80 = 0.17 < 0.2；竞争分低于命中线（不属「多处高分」）
        val detector = SignalDetector(emptyList(), params.copy(ambiguityMargin = 0.2))
        assertEquals(
            Verdict.UNRELIABLE,
            detector.judge(listOf(MatchPeak(0.97, 10, 10), MatchPeak(0.80, 90, 60))),
        )
    }

    @Test
    fun judgeMatchedWhenMarginSufficient() {
        // 同上一组分数，仅差距线收紧到 0.1：0.17 ≥ 0.1 → 可信
        assertEquals(
            Verdict.MATCHED,
            SignalDetector(emptyList(), params).judge(
                listOf(MatchPeak(0.97, 10, 10), MatchPeak(0.80, 90, 60)),
            ),
        )
    }

    // ---- 图像级（端到端）----

    @Test
    fun detectMatchesTargetWithFullAuditRecord() {
        val image = SyntheticImages.background(400, 300, seed = 11)
        val pattern = SyntheticImages.pattern(20, 16, seed = 12)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        SyntheticImages.drawPattern(image, 200, 150, 20, 16, SyntheticImages.pattern(20, 16, seed = 13))
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val record = SignalDetector(
            listOf(SignalSpec("测试标志", fullWindow, listOf(template))),
            params,
        ).detect(image).single()

        // A3：命中、位置、置信度、最强竞争位置四项齐全
        assertEquals(Verdict.MATCHED, record.verdict)
        val best = record.best
        assertNotNull(best)
        assertEquals(30, best!!.x)
        assertEquals(40, best.y)
        assertTrue("精确命中分数应接近 1：${best.score}", best.score > 0.99)
        val competitor = record.competitor
        assertNotNull(competitor)
        assertTrue("竞争位置分数应低于命中线：${competitor!!.score}", competitor.score < params.matchThreshold)
        assertTrue("竞争位置应弱于最优：", competitor.score < best.score)
    }

    @Test
    fun detectUnreliableWhenTwoIdenticalTargets() {
        val image = SyntheticImages.background(400, 300, seed = 21)
        val pattern = SyntheticImages.pattern(20, 16, seed = 22)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        SyntheticImages.drawPattern(image, 150, 100, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)

        val record = SignalDetector(
            listOf(SignalSpec("测试标志", fullWindow, listOf(template))),
            params,
        ).detect(image).single()

        // A4 多处高分：两个相同目标都超过命中线 → 不可信，按未识别处理
        assertEquals(Verdict.UNRELIABLE, record.verdict)
        assertTrue(record.best!!.score >= params.matchThreshold)
        assertTrue(record.competitor!!.score >= params.matchThreshold)
    }

    @Test
    fun detectNotMatchedWhenTargetAbsent() {
        val image = SyntheticImages.background(400, 300, seed = 31)
        val template = Template(20, 16, SyntheticImages.pattern(20, 16, seed = 32))

        val record = SignalDetector(
            listOf(SignalSpec("测试标志", fullWindow, listOf(template))),
            params,
        ).detect(image).single()

        assertEquals(Verdict.NOT_MATCHED, record.verdict)
    }

    @Test
    fun multiTemplateSignalMatchesWhicheverTemplatePresent() {
        val image = SyntheticImages.background(400, 300, seed = 41)
        val present = SyntheticImages.pattern(20, 16, seed = 42)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, present)

        val templateA = SyntheticImages.crop(image, 30, 40, 20, 16)
        val templateB = Template(20, 16, SyntheticImages.pattern(20, 16, seed = 43)) // 画面中不存在
        val signal = SignalSpec("测试标志", fullWindow, listOf(templateB, templateA))

        val record = SignalDetector(listOf(signal), params).detect(image).single()

        assertEquals(Verdict.MATCHED, record.verdict)
        assertEquals(30, record.best!!.x)
        assertEquals(40, record.best!!.y)
    }

    @Test
    fun replayOfSameInputProducesIdenticalRecords() {
        val image = SyntheticImages.background(400, 300, seed = 51)
        val patternA = SyntheticImages.pattern(20, 16, seed = 52)
        val patternB = SyntheticImages.pattern(24, 18, seed = 53)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, patternA)
        SyntheticImages.drawPattern(image, 260, 200, 24, 18, patternB)

        val signals = listOf(
            SignalSpec("标志A", fullWindow, listOf(SyntheticImages.crop(image, 30, 40, 20, 16))),
            SignalSpec(
                "标志B",
                SearchWindow(0.5, 0.5, 1.0, 1.0),
                listOf(SyntheticImages.crop(image, 260, 200, 24, 18)),
            ),
        )
        val detector = SignalDetector(signals, params)

        // A2：同一批画面数据 + 同一套参数，重复执行结果完全一致（§5-4）
        val first = detector.detect(image)
        val second = detector.detect(image)
        val third = detector.detect(image)

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(Verdict.MATCHED, first[0].verdict)
        assertEquals(Verdict.MATCHED, first[1].verdict)
    }
}
