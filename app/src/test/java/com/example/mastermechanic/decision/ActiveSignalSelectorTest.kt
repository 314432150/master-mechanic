package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按状态启用信号子集（T1-10g 建立，T1-13 收敛口径）：
 * ① 选择规则（状态未知 → 全扫；其余 → 当前状态信号 ∪ 附加集；**周期兜底默认关闭**）；
 * ② 子集模式下「未搜到的信号不产生判定记录」；
 * ③ **取消补扫**：预期未命中时不再于同轮发现新状态，需经滞回转「未知」后全扫
 *   ——这是 T1-13 的**预期行为**（识别层只回答"预期在不在"，不做发现式搜索）。
 */
class ActiveSignalSelectorTest {

    private val params = MatchParams(0.85, 0.10, 5)

    private fun selector(
        rules: Map<UiState, Set<String>> = mapOf(
            UiState.LAUNCH_PAGE to setOf("a"),
            UiState.HALL to setOf("b"),
        ),
        attached: Map<UiState, Set<String>> = emptyMap(),
        period: Int = ActiveSignalSelector.NO_FALLBACK,
    ): ActiveSignalSelector = ActiveSignalSelector(
        signalsOf = { rules[it].orEmpty() },
        attached = attached,
        fallbackPeriod = period,
    )

    // --- ① 选择规则 ---

    @Test
    fun unknownStateScansAll() {
        assertNull("未知状态必须全扫", selector().select(UiState.UNKNOWN, round = 3))
    }

    @Test
    fun defaultDisablesPeriodicFallback() {
        // T1-13：默认不启用周期兜底——全扫只保留「状态未知」这一种时刻
        val s = selector()
        assertEquals("第 0 轮不再是全扫", setOf("a"), s.select(UiState.LAUNCH_PAGE, round = 0))
        assertEquals("任意轮次都不再有兜底全扫", setOf("a"), s.select(UiState.LAUNCH_PAGE, round = 100))
    }

    @Test
    fun periodicFallbackScansAllWhenEnabled() {
        // 能力保留（默认关闭）：显式给出周期时仍按周期全扫
        val s = selector(period = 5)
        assertNull("周期兜底轮全扫", s.select(UiState.LAUNCH_PAGE, round = 0))
        assertNull(s.select(UiState.LAUNCH_PAGE, round = 5))
        assertEquals(setOf("a"), s.select(UiState.LAUNCH_PAGE, round = 3))
    }

    @Test
    fun stableStateUsesOwnSignalsPlusAttached() {
        val s = selector(attached = mapOf(UiState.HALL to setOf("popup", "guide")))
        assertEquals(setOf("b", "popup", "guide"), s.select(UiState.HALL, round = 7))
    }

    @Test
    fun stateWithoutSignalsFallsBackToFullScan() {
        val s = selector(rules = mapOf(UiState.LAUNCH_PAGE to setOf("a")))
        assertNull("没有可用信号的状态不能给出空子集", s.select(UiState.FARM, round = 1))
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
        )
        assertEquals(setOf("hall"), s.select(UiState.HALL, round = 1))
        assertNull(s.select(UiState.FARM, round = 1))
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

    private val fullWindow = SearchWindow(0.0, 0.0, 1.0, 1.0)

    /** [selector] 为 null 即全扫模式（每轮匹配全部信号）。 */
    private fun loop(selector: ActiveSignalSelector? = null): RecognitionLoop {
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
            selector = selector,
        )
    }

    /** 启动页搜索 a、大厅搜索 b（周期兜底默认关闭）。 */
    private fun subsetSelector(): ActiveSignalSelector = ActiveSignalSelector.fromRules(
        listOf(
            SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
            SignalStateMapping.Rule(UiState.HALL, setOf("b")),
        ),
    )

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
    fun subsetModeRecordsOnlySearchedSignals() {
        val a = template(1L)
        val loop = loop(subsetSelector())
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
        assertTrue("命中当前状态且无候选累积 → 确实稳定（可降档）", result.settled)
    }

    @Test
    fun expectationChangeGoesThroughUnknownInsteadOfProbing() {
        // T1-13：取消补扫后，画面变化时**不再当轮发现新状态**，而是连续未命中 → 转「未知」→ 全扫后识别
        val a = template(1L)
        val b = template(2L)
        val full = run(loop(null), a, b, rounds = 12, first = 6)
        val subset = run(loop(subsetSelector()), a, b, rounds = 12, first = 6)

        assertEquals("全扫模式：画面变化后连 2 命中即进入大厅", UiState.HALL, full[7])
        assertEquals("子集模式：变化当轮不发现新状态（仍判为启动页）", UiState.LAUNCH_PAGE, subset[6])
        assertTrue("子集模式：连续未命中后应出现「未知」", subset.contains(UiState.UNKNOWN))
        assertEquals("子集模式：最终仍到达同一状态（语义不变，只是慢几轮）", UiState.HALL, subset.last())
    }

    @Test
    fun missedOwnSignalDoesNotTriggerFullScan() {
        val a = template(1L)
        val b = template(2L)
        val loop = loop(subsetSelector())
        repeat(4) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        assertEquals(UiState.LAUNCH_PAGE, loop.state)

        val missed = loop.process(frameWith(b, 90, 90), isForeground = true)
        assertEquals("未命中不补扫：仍只搜子集", setOf("a"), missed.searched)
        assertEquals(1, missed.records.size)
        assertTrue("未达预期 → 不得降档", !missed.settled)

        // 连续 3 次未命中 → 转「未知」→ 下一轮全扫（全扫的唯一时刻）
        loop.process(frameWith(b, 90, 90), isForeground = true)
        loop.process(frameWith(b, 90, 90), isForeground = true)
        assertEquals(UiState.UNKNOWN, loop.state)
        val afterUnknown = loop.process(frameWith(b, 90, 90), isForeground = true)
        assertNull("状态未知 → 全扫", afterUnknown.searched)
        assertEquals(2, afterUnknown.records.size)
    }
}
