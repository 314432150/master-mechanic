package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别循环编排单测（T1-4）：端到端「合成画面 → 判定 → 状态命中 → 滞回状态机」，
 * 含非前台冻结与未标定空配置运行。
 */
class RecognitionLoopTest {

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)
    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    /** 含目标纹理的画面 + 以该纹理为模板的标定循环。 */
    private fun calibratedLoop(): Pair<RecognitionLoop, GrayImage> {
        val image = SyntheticImages.background(400, 300, seed = 61)
        val pattern = SyntheticImages.pattern(20, 16, seed = 62)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)
        val signal = SignalSpec("农场标志", fullWindow, listOf(template))
        val mapping = SignalStateMapping(listOf(SignalStateMapping.Rule(UiState.FARM, setOf("农场标志"))))
        return RecognitionLoop(listOf(signal), params, mapping) to image
    }

    @Test
    fun entersStateAfterTwoConsecutiveHits() {
        val (loop, image) = calibratedLoop()
        val first = loop.process(image, isForeground = true)
        assertNull(first.transition)
        assertEquals(UiState.UNKNOWN, first.state)
        assertTrue(first.stable)

        val second = loop.process(image, isForeground = true)
        assertEquals(UiState.FARM, second.transition!!.to)
        assertEquals(UiState.FARM, second.state)
        assertFalse(second.stable) // 变化轮：用于 NFR-02 短间隔
    }

    @Test
    fun nonForegroundRoundFreezesAndSkipsRecognition() {
        val (loop, image) = calibratedLoop()
        loop.process(image, isForeground = true) // 命中 1

        val frozen = loop.process(image, isForeground = false)
        assertTrue(frozen.frozen)
        assertTrue(frozen.records.isEmpty())
        assertTrue(frozen.hits.isEmpty())
        assertNull(frozen.transition)
        assertFalse(frozen.stable) // 冻结轮不参与节流建议

        // 冻结轮不消耗计数：下一前台轮即连 2 进入
        val resumed = loop.process(image, isForeground = true)
        assertEquals(UiState.FARM, resumed.transition!!.to)
    }

    @Test
    fun absentTargetKeepsUnknown() {
        val image = SyntheticImages.background(400, 300, seed = 71)
        val template = Template(20, 16, SyntheticImages.pattern(20, 16, seed = 72))
        val signal = SignalSpec("农场标志", fullWindow, listOf(template))
        val mapping = SignalStateMapping(listOf(SignalStateMapping.Rule(UiState.FARM, setOf("农场标志"))))
        val loop = RecognitionLoop(listOf(signal), params, mapping)

        repeat(5) { loop.process(image, isForeground = true) }
        assertEquals(UiState.UNKNOWN, loop.state)
    }

    @Test
    fun uncalibratedLoopRunsWithNoSignals() {
        val loop = RecognitionLoop.uncalibrated()
        assertEquals(0, loop.signalCount)

        val image = SyntheticImages.background(40, 30, seed = 81)
        val result = loop.process(image, isForeground = true)
        assertTrue(result.records.isEmpty())
        assertEquals(UiState.UNKNOWN, result.state)
        assertNull(result.transition)
        assertTrue(result.stable)
    }

    @Test
    fun sameSequenceProducesIdenticalResults() {
        fun run(): List<RoundResult> {
            val (loop, image) = calibratedLoop()
            return listOf(
                loop.process(image, isForeground = true),
                loop.process(image, isForeground = true),
                loop.process(image, isForeground = false),
                loop.process(image, isForeground = true),
            )
        }
        assertEquals(run(), run())
    }
}
