package com.example.mastermechanic.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗守护期望集合（T2-5 / FR-01）：**每轮只搜活动弹窗标志，没有阶段**。
 *
 * 依据（2026-09-13 现场 + 用户实测）：弹窗**物理上一定在大厅之后**出现，但程序**可能未及时识别到大厅**
 * （大厅一闪即被弹窗盖住）→ 判定"弹窗该不该管"只能看"**弹窗自己出现了没有**"，
 * 不能依赖"是否已识别到大厅"。旧实现的「命中大厅 → 收回窗口」正是当天漏掉弹窗的根因。
 */
class PopupWatchExpectedSignalsTest {

    private val rules = listOf(
        SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_start")),
        SignalStateMapping.Rule(UiState.SERVER_SELECT, setOf("server_select")),
        SignalStateMapping.Rule(UiState.HALL, setOf("hall")),
        SignalStateMapping.Rule(
            UiState.ACTIVITY_POPUP,
            setOf("popup_close", "popup_close2", "popup_close5"),
        ),
        SignalStateMapping.Rule(UiState.FARM, setOf("farm")),
    )
    private val known = rules.flatMap { it.signalNames }.toSet()

    /** 活动弹窗的每条样式记录都要搜（同一状态多条记录 → 全部纳入，任一命中即该状态命中，§2.1）。 */
    @Test
    fun searchesEveryPopupRecord() {
        val signals = PopupWatchExpectedSignals.fromRules(rules)
        assertEquals(
            setOf("popup_close", "popup_close2", "popup_close5"),
            signals.expected(known),
        )
    }

    /** `launch_start` 是大窗口信号（单轮 ≈283ms），是守护期待命成本的大头，不再纳入。 */
    @Test
    fun neverSearchesLaunchStartOrOtherStates() {
        val expected = PopupWatchExpectedSignals.fromRules(rules).expected(known)
        assertTrue("大窗口 launch_start 不再进守护期", "launch_start" !in expected)
        assertTrue(
            "大厅 / 服务器列表 / 农场都不搜",
            expected.none { it in setOf("hall", "server_select", "farm") },
        )
    }

    /** T2-5 的核心：没有阶段 —— 不论画面命中什么，集合恒定。 */
    @Test
    fun setDoesNotChangeAcrossRounds() {
        val signals = PopupWatchExpectedSignals.fromRules(rules)
        val before = signals.expected(known)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        signals.onRound(setOf(UiState.HALL))
        signals.onRound(setOf(UiState.ACTIVITY_POPUP))
        signals.onRound(emptySet())
        assertEquals("集合不随命中变化（无阶段）", before, signals.expected(known))
    }

    /** 现场重现（17:54:59）：命中大厅之后弹窗才出现 —— 旧口径在这一步永久关闭了搜索。 */
    @Test
    fun hallHitDoesNotClosePopupSearch() {
        val signals = PopupWatchExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        signals.onRound(setOf(UiState.HALL))
        assertTrue(
            "命中大厅后仍需搜弹窗（弹窗在大厅之后才出现）",
            "popup_close" in signals.expected(known),
        )
    }

    /** 产物里没有活动弹窗记录（未标定）→ 空集 = 不搜，不得退化成全扫。 */
    @Test
    fun uncalibratedPopupYieldsEmptySet() {
        val signals = PopupWatchExpectedSignals.fromRules(
            listOf(SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_start"))),
        )
        assertEquals(emptySet<String>(), signals.expected(setOf("launch_start")))
    }

    @Test
    fun allDeclaresEveryCalibratedSignalAndNoneSearchesNothing() {
        assertEquals("ALL = 显式声明全集（M1 演练口径）", known, ExpectedSignals.ALL.expected(known))
        assertEquals(emptySet<String>(), ExpectedSignals.NONE.expected(known))
        assertEquals(setOf("launch_start"), FixedExpectedSignals.of("launch_start").expected(known))
        assertTrue(FixedExpectedSignals.of().expected(known).isEmpty())
    }
}
