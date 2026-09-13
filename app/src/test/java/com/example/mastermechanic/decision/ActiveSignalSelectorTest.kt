package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.SyntheticImages
import com.example.mastermechanic.recognition.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 期望集合驱动（T2-1，用户口径 2026-09-13）：识别层每轮**只搜外部注入的期望集合**，
 * 不外扩、不兜底。本测试覆盖：
 * ① [ActiveSignalSelector]：期望集合 ∩ 已标定信号（剔除未标定的期望名）；空集原样返回 = 不搜；
 * ② 已取消的历史口径：「状态未知 → 全扫」「按状态自动选子集」「周期兜底」都不复存在
 *   （旧用例 `unknownStateScansAll` / `defaultDisablesPeriodicFallback` / `fromRulesBuildsByStateMapping`
 *   随口径一并删除）；
 * ③ 集成：全集口径行为不变（M1 演练口径 = 显式声明全集）；期望外的信号**根本不搜**——
 *   不产生判定记录与耗时样本；不搜的一轮对状态机如同不存在。
 */
class ActiveSignalSelectorTest {

    private val params = MatchParams(0.85, 0.10, 5)

    // --- ① 期望集合 → 本轮搜索集合 ---

    @Test
    fun selectKeepsOnlyCalibratedNames() {
        val selector = ActiveSignalSelector(known = setOf("a", "b"))
        assertEquals(
            "未标定的期望名要剔除，否则「没有的能力」会被记成本轮搜索量",
            setOf("a"),
            selector.select(setOf("a", "ghost")),
        )
    }

    @Test
    fun emptyExpectationMeansNoSearch() {
        assertTrue(
            "空集合 = 不搜",
            ActiveSignalSelector(known = setOf("a")).select(emptySet()).isEmpty(),
        )
    }

    @Test
    fun expectationWithNoCalibratedNameIsAlsoNoSearch() {
        assertTrue(
            "期望名全部未标定 → 本轮无可搜",
            ActiveSignalSelector(known = setOf("a")).select(setOf("ghost")).isEmpty(),
        )
    }

    @Test
    fun searchOrderFollowsExpectation() {
        val selector = ActiveSignalSelector(known = setOf("a", "b"))
        assertEquals(
            "搜索顺序按期望集合的迭代顺序（结果确定、便于比对日志）",
            listOf("b", "a"),
            selector.select(linkedSetOf("b", "a")).toList(),
        )
    }

    // --- ③ 集成：识别循环 ---

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
    private val a = template(1L)
    private val b = template(2L)

    private fun loop(expected: ExpectedSignals): RecognitionLoop = RecognitionLoop(
        signals = listOf(
            SignalSpec("a", fullWindow, listOf(a)),
            SignalSpec("b", fullWindow, listOf(b)),
        ),
        params = params,
        mapping = SignalStateMapping(
            listOf(
                SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")),
                SignalStateMapping.Rule(UiState.HALL, setOf("b")),
            ),
        ),
        expectedSignals = expected,
    )

    /** 贴 A 进入启动页（连 4 轮，走完滞回确认）。 */
    private fun enterLaunchPage(loop: RecognitionLoop) {
        repeat(4) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        assertEquals(UiState.LAUNCH_PAGE, loop.state)
    }

    @Test
    fun fullSetSearchesEveryCalibratedSignal() {
        // M1 演练口径：显式声明全集 → 每轮搜索集合 = 全部已标定信号（= 改动前的「全扫轮」）
        val loop = loop(ExpectedSignals.ALL)
        val round = loop.process(frameWith(a, 20, 20), isForeground = true)
        assertEquals(setOf("a", "b"), round.searched)
        assertEquals("每个被搜的信号各产生一个耗时样本", 2, loop.lastSignalCostMs.size)
    }

    @Test
    fun fullSetExpectationKeepsOldBehaviour() {
        // M1 演练口径：显式声明全集（每轮搜产物里的全部信号）——画面变化后连 2 命中即进入大厅
        val loop = loop(ExpectedSignals.ALL)
        repeat(4) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        assertEquals(UiState.LAUNCH_PAGE, loop.state)
        loop.process(frameWith(b, 90, 90), isForeground = true)
        val second = loop.process(frameWith(b, 90, 90), isForeground = true)
        assertEquals("全集口径下判定行为与改动前一致（连 2 命中转移）", UiState.HALL, second.state)
    }

    @Test
    fun signalOutsideExpectationIsNotSearched() {
        val loop = loop(FixedExpectedSignals.of("a"))
        enterLaunchPage(loop)

        // 画面换成 b，但 b 不在期望里 → 只搜 a，不产生 b 的判定记录（不是记为未命中）
        val round = loop.process(frameWith(b, 90, 90), isForeground = true)
        assertEquals(setOf("a"), round.searched)
        assertEquals(listOf("a"), round.records.map { it.signalName })
        assertTrue("b 没被搜 → 不会产生落地页的命中", round.hits.isEmpty())
        assertTrue("期望内未命中 → 不得降档", !round.settled)
    }

    @Test
    fun narrowedExpectationEventuallyLeavesOldState() {
        val expected = MutableExpected(setOf("a"))
        val loop = loop(expected)
        enterLaunchPage(loop)

        // 阶段切换：期望收窄到不含「启动页」的信号 → 连续未命中 → 转未知（识别层不再认识它）
        expected.set(setOf("b"))
        repeat(3) { loop.process(frameWith(a, 20, 20), isForeground = true) }
        assertEquals("期望里不再有当前状态 → 按「连续未命中」离开", UiState.UNKNOWN, loop.state)
    }

    @Test
    fun emptyExpectationSearchesNothing() {
        val loop = loop(FixedExpectedSignals.of())
        val round = loop.process(frameWith(a, 20, 20), isForeground = true)
        assertTrue("空期望 → 本轮不搜", round.searched.isEmpty())
        assertTrue("不搜就不产生判定记录", round.records.isEmpty())
        assertTrue("不搜也不产生耗时样本", loop.lastSignalCostMs.isEmpty())
        assertEquals(UiState.UNKNOWN, round.state)
        assertTrue("不搜的一轮不得被当成稳定轮", !round.settled)
    }

    @Test
    fun frozenRoundSearchesNothingAndDoesNotAdvanceExpected() {
        val spy = SpyExpected(setOf("a"))
        val loop = RecognitionLoop(
            signals = listOf(SignalSpec("a", fullWindow, listOf(a))),
            params = params,
            mapping = SignalStateMapping(listOf(SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("a")))),
            expectedSignals = spy,
        )
        val frozen = loop.process(frameWith(a, 20, 20), isForeground = false)
        assertTrue(frozen.searched.isEmpty())
        assertTrue(frozen.records.isEmpty())
        assertEquals("冻结轮不推进期望 / 阶段（冻结轮如同不存在）", 0, spy.rounds)

        loop.process(frameWith(a, 20, 20), isForeground = true)
        assertEquals("前台轮才推进", 1, spy.rounds)
    }

    /** 固定集合 + 记录 [onRound] 调用次数（验证「冻结轮不推进」）。 */
    private class SpyExpected(private val names: Set<String>) : ExpectedSignals {
        var rounds = 0
            private set

        override fun expected(known: Set<String>): Set<String> = names

        override fun onRound(hits: Set<UiState>) {
            rounds++
        }
    }

    /** 可改集合（模拟阶段切换 / 流程换步）。 */
    private class MutableExpected(private var names: Set<String>) : ExpectedSignals {
        fun set(next: Set<String>) {
            names = next
        }

        override fun expected(known: Set<String>): Set<String> = names
    }
}
