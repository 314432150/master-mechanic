package com.example.mastermechanic.decision

import com.example.mastermechanic.capture.RgbaToGray
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
        assertFalse("未知态下不得降档（预期尚未建立）", first.settled)

        val second = loop.process(image, isForeground = true)
        assertEquals(UiState.FARM, second.transition!!.to)
        assertEquals(UiState.FARM, second.state)
        assertFalse(second.stable) // 变化轮：用于 NFR-02 短间隔
        assertFalse("转移轮不得降档", second.settled)

        // T1-13 降档判据「确实稳定」：预期（当前状态）命中 ∧ 无候选累积 ∧ 无转移 → 才允许长间隔
        val third = loop.process(image, isForeground = true)
        assertNull(third.transition)
        assertEquals(UiState.FARM, third.state)
        assertTrue("连续命中且无转移 → 确实稳定", third.settled)
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
    fun signalCostSamplesCoverExactlyTheSearchedSignals() {
        // T1-13b / T2-1：逐信号耗时只为「本轮真正搜索的信号」产生样本——
        // 期望集合内每个信号一个样本；期望收窄 → 只有集合内的信号；空期望与冻结轮 → 空（不残留上一轮）。
        val image = SyntheticImages.background(400, 300, seed = 101)
        val pattern = SyntheticImages.pattern(20, 16, seed = 102)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val signals = listOf(
            SignalSpec("信号甲", fullWindow, listOf(Template(20, 16, pattern))),
            SignalSpec("信号乙", fullWindow, listOf(Template(20, 16, pattern))),
        )
        val rules = listOf(SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("信号甲")))
        val expected = MutableExpectedSignals(setOf("信号甲", "信号乙"))
        val loop = RecognitionLoop(
            signals,
            params,
            SignalStateMapping(rules),
            expectedSignals = expected,
        )

        loop.process(image, isForeground = true) // 期望集合内两个信号 → 各一个样本
        assertEquals(setOf("信号甲", "信号乙"), loop.lastSignalCostMs.keys)
        assertTrue(
            "耗时样本应为有限非负值",
            loop.lastSignalCostMs.values.all { it.isFinite() && it >= 0.0 },
        )

        loop.process(image, isForeground = true) // 连 2 次命中 → 转入启动页
        assertEquals(UiState.LAUNCH_PAGE, loop.state)
        expected.set(setOf("信号甲")) // 期望收窄（模拟阶段 / 流程换步）
        loop.process(image, isForeground = true)
        assertEquals("只为本轮搜索集合内的信号产生样本", setOf("信号甲"), loop.lastSignalCostMs.keys)

        expected.set(emptySet()) // 空期望 = 不搜
        loop.process(image, isForeground = true)
        assertTrue("不搜的一轮不产生耗时样本", loop.lastSignalCostMs.isEmpty())

        loop.process(image, isForeground = false) // 冻结轮不搜索
        assertTrue("冻结轮不残留耗时样本", loop.lastSignalCostMs.isEmpty())

        val uncalibrated = RecognitionLoop.uncalibrated()
        uncalibrated.process(image, isForeground = true)
        assertTrue("未标定循环无信号可测", uncalibrated.lastSignalCostMs.isEmpty())
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

    @Test
    fun windowRegionsMapEverySignalWindow() {
        // 多个信号窗口（不同比例）：窗口区域逐个映射为其像素范围（floor/ceil 口径）
        val windowA = SearchWindow(0.25, 0.5, 0.5, 0.75)
        val windowB = SearchWindow(0.1, 0.1, 0.9, 0.9)
        val template = Template(4, 4, SyntheticImages.pattern(4, 4, seed = 82))
        val loop = RecognitionLoop(
            listOf(
                SignalSpec("信号甲", windowA, listOf(template)),
                SignalSpec("信号乙", windowB, listOf(template)),
            ),
            params,
            SignalStateMapping(emptyList()),
        )

        val regions = loop.windowRegions(400, 300)
        assertEquals(2, regions.size)
        assertEquals(windowA.pixelBounds(400, 300), regions[0])
        assertEquals(windowB.pixelBounds(400, 300), regions[1])
    }

    @Test
    fun uncalibratedLoopHasNoWindowRegions() {
        assertTrue(RecognitionLoop.uncalibrated().windowRegions(400, 300).isEmpty())
    }

    @Test
    fun windowedGrayConversionKeepsDetectionIdentical() {
        // T1-10b 端到端等价：同一帧，全帧灰度 vs 窗口化灰度（仅信号窗口区域）→ 判定逐字段一致。
        // 窗口设为恰好装下模板（[30,50)×[40,56)）：覆盖模板贴满窗口的极限情况。
        val width = 400
        val height = 300
        val image = SyntheticImages.background(width, height, seed = 91)
        val pattern = SyntheticImages.pattern(20, 16, seed = 92)
        SyntheticImages.drawPattern(image, 30, 40, 20, 16, pattern)
        val template = SyntheticImages.crop(image, 30, 40, 20, 16)
        val window = SearchWindow(30.0 / width, 40.0 / height, 50.0 / width, 56.0 / height)
        val loop = RecognitionLoop(
            listOf(SignalSpec("信号", window, listOf(template))),
            params,
            SignalStateMapping(emptyList()),
        )

        // 灰度 ByteArray → RGBA 字节流（R=G=B=gray，A=255；BT.601 往返为恒等）
        val rgba = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val v = image.pixels[i]
            rgba[i * 4] = v
            rgba[i * 4 + 1] = v
            rgba[i * 4 + 2] = v
            rgba[i * 4 + 3] = 0xFF.toByte()
        }

        val full = RgbaToGray.toGray(rgba, width, height, width * 4)
        val windowed = RgbaToGray.toGrayRegions(
            rgba, width, height, width * 4,
            loop.windowRegions(width, height),
        )

        val fullRound = loop.process(full, isForeground = true)
        val windowedRound = loop.process(windowed, isForeground = true)
        assertEquals(fullRound.records, windowedRound.records)
        assertEquals(fullRound.state, windowedRound.state)
        assertTrue("场景应命中：${windowedRound.records}", windowedRound.records.first().matched)
    }

    /** 可改期望集合（模拟阶段推进 / 流程换步）。 */
    private class MutableExpectedSignals(private var names: Set<String>) : ExpectedSignals {
        fun set(next: Set<String>) {
            names = next
        }

        override fun expected(known: Set<String>): Set<String> = names
    }
}
