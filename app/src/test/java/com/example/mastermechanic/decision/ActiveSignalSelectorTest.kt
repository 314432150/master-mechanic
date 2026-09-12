package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按状态启用信号子集（T1-10g，纯逻辑）：
 * ① 选择规则（未知 / 转移探测 / 周期兜底 / 稳定子集 / 附加集）；
 * ② 子集模式下「未搜到的信号不产生判定记录」；
 * ③ 与全扫模式的**状态序列逐帧一致**（判定语义不变）。
 */
class ActiveSignalSelectorTest {

    private val params = MatchParams(0.85, 0.10, 5)

    private fun selector(
        rules: Map<UiState, Set<String>> = mapOf(
            UiState.LAUNCH_PAGE to setOf("a"),
            UiState.HALL to setOf("b"),
        ),
        attached: Map<UiState, Set<String>> = emptyMap(),
        period: Int = 10,
    ): ActiveSignalSelector = ActiveSignalSelector(
        signalsOf = { rules[it].orEmpty() },
        attached = attached,
        fallbackPeriod = period,
    )

    // --- ① 选择规则 ---

    @Test
    fun unknownStateScansAll() {
        assertNull("未知状态必须全扫", selector().select(UiState.UNKNOWN, probing = false, round = 3))
    }

    @Test
    fun probingScansAll() {
        assertNull(
            "上一轮当前状态未命中（疑似转移）时立即全扫",
            selector().select(UiState.LAUNCH_PAGE, probing = true, round = 3),
        )
    }

    @Test
    fun periodicFallbackScansAll() {
        val s = selector(period = 5)
        assertNull("周期兜底轮全扫", s.select(UiState.LAUNCH_PAGE, probing = false, round = 0))
        assertNull(s.select(UiState.LAUNCH_PAGE, probing = false, round = 5))
        assertEquals(
            setOf("a"),
            s.select(UiState.LAUNCH_PAGE, probing = false, round = 3),
        )
    }

    @Test
    fun stableStateUsesOwnSignalsPlusAttached() {
        val s = selector(attached = mapOf(UiState.HALL to setOf("popup", "guide")), period = Int.MAX_VALUE)
        assertEquals(setOf("b", "popup", "guide"), s.select(UiState.HALL, probing = false, round = 7))
    }

    @Test
    fun stateWithoutSignalsFallsBackToFullScan() {
        val s = selector(rules = mapOf(UiState.LAUNCH_PAGE to setOf("a")), period = Int.MAX_VALUE)
        assertNull("没有可用信号的状态不能给出空子集", s.select(UiState.FARM, probing = false, round = 1))
    }

    @Test
    fun periodMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { selector(period = 0) }
    }

    @Test
    fun fromRulesBuildsByStateMapping() {
        val s = ActiveSignalSelector.fromRules(
            listOf(
                SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_start")),
                SignalStateMapping.Rule(UiState.HALL, setOf("hall")),
            ),
            fallbackPeriod = Int.MAX_VALUE,
        )
        assertEquals(setOf("hall"), s.select(UiState.HALL, probing = false, round = 1))
        assertNull(s.select(UiState.FARM, probing = false, round = 1))
    }

    // --- ②③ 集成：子集模式 vs 全扫 ---

    private fun template(seed: Long): Template = Template(16, 16, SyntheticImages.pattern(16, 16, seed))

    /** 在 160×160 的空白帧上贴一个模板（左上角位置）。 */
    private fun frameWith(template: Template, x: Int, y: Int): GrayImage {
        val size = 160
        val pixels = ByteArray(size * size)
        for (ty in 0 until template.height) {
            for (tx in 0 until template.width) {
                pixels[(y + ty) * size + x + tx] = template.pixels[ty * template.width + tx]
            }
        }
        return GrayImage(size, size, pixels)
    }

    private val fullWindow = com.example.mastermechanic.recognition.SearchWindow(0.0, 0.0, 1.0, 1.0)

    private fun loop(selector: ActiveSignalSelector? = null, full: Boolean = false): RecognitionLoop {
        val a = template(1L)
        val b = template(2L)
        val signals = listOf(
            SignalSpec("a", fullWindow, listOf(a)),
            SignalSpec("b", fullWindow, listOf(b)),
        )
        val mapping = SignalStateMapping(
            listOf(
                SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
                SignalStateMapping.Rule(UiState.HALL, setOf("b")),
            ),
        )
        return RecognitionLoop(
            signals = signals,
            params = params,
            mapping = mapping,
            selector = if (full) null else selector,
        )
    }

    /** 前 [first] 帧贴 A，其后贴 B；返回每轮结束时的状态序列。 */
    private fun run(loop: RecognitionLoop, a: Template, b: Template, rounds: Int, first: Int): List<UiState> {
        val states = ArrayList<UiState>()
        for (i in 0 until rounds) {
            val frame = if (i < first) frameWith(a, 20, 20) else frameWith(b, 90, 90)
            states += loop.process(frame, isForeground = true).state
        }
        return states
    }

    @Test
    fun subsetModeStateSequenceMatchesFullScan() {
        val a = template(1L)
        val b = template(2L)
        val full = run(loop(null, full = true), a, b, rounds = 12, first = 6)
        val subset = run(loop(ActiveSignalSelector.fromRules(
            listOf(
                SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
                SignalStateMapping.Rule(UiState.HALL, setOf("b")),
            ),
        )), a, b, rounds = 12, first = 6)
        assertEquals("子集模式与全扫的状态序列必须逐帧一致", full, subset)
        assertTrue("应观察到状态转移：${full.distinct()}", full.distinct().size >= 3)
    }

    @Test
    fun subsetModeRecordsOnlySearchedSignals() {
        val a = template(1L)
        val b = template(2L)
        val loop = loop(
            ActiveSignalSelector.fromRules(
                listOf(
                    SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
                    SignalStateMapping.Rule(UiState.HALL, setOf("b")),
                ),
            ),
            full = false,
        )
        // 先稳定进入 LAUNCH_PAGE（前 4 帧全贴 A）
        repeat(4) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        assertEquals(UiState.LAUNCH_PAGE, loop.state)

        val result = loop.process(frameWith(a, 20, 20), isForeground = true)
        assertEquals("稳定在启动页时只搜自己的信号", setOf("a"), result.searched)
        assertEquals(
            "子集外的信号不产生判定记录（不是记为未命中）",
            listOf("a"),
            result.records.map { it.signalName },
        )
        assertTrue(result.records.first().matched)
        assertTrue("本轮命中 → 不触发转移探测", result.stable)
    }

    @Test
    fun missedOwnSignalTriggersFullScanNextRound() {
        val a = template(1L)
        val b = template(2L)
        val loop = loop(
            ActiveSignalSelector.fromRules(
                listOf(
                    SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
                    SignalStateMapping.Rule(UiState.HALL, setOf("b")),
                ),
                fallbackPeriod = Int.MAX_VALUE,
            ),
            full = false,
        )
        repeat(4) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        // 当前状态未命中（换画面）→ 下一轮应全扫以发现新状态
        loop.process(frameWith(b, 90, 90), isForeground = true)
        val next = loop.process(frameWith(b, 90, 90), isForeground = true)
        assertNull("上一轮未命中 → 本轮全扫（转移探测）", next.searched)
        assertEquals(2, next.records.size)
    }
}
