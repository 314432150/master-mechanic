package com.example.mastermechanic.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗阶段期望集合（T2-1 / FR-01）：**命中「启动页」才开始注入弹窗期期望集合**（用户口径 2026-09-13）。
 *
 * 守护待命只搜「启动页」（入口标志）；命中即进入弹窗期（启动页 + 选择服务器 + 活动弹窗 + 大厅）；
 * 命中大厅即收回（大厅 = 离开「启动页 → 进入大厅」这一段的标志）。
 */
class PopupPhaseExpectedSignalsTest {

    private val rules = listOf(
        SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_start")),
        SignalStateMapping.Rule(UiState.SERVER_SELECT, setOf("server_select")),
        SignalStateMapping.Rule(UiState.HALL, setOf("hall")),
        SignalStateMapping.Rule(
            UiState.ACTIVITY_POPUP,
            setOf("popup_close", "popup_close2", "popup_close3"),
        ),
        SignalStateMapping.Rule(UiState.FARM, setOf("farm")),
    )
    private val known = rules.flatMap { it.signalNames }.toSet()

    @Test
    fun standbySearchesLaunchPageOnly() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        assertEquals(PopupPhaseExpectedSignals.Phase.STANDBY, signals.phase)
        assertEquals("守护待命只搜入口标志", setOf("launch_start"), signals.expected(known))
    }

    @Test
    fun hittingLaunchPageOpensPopupPhase() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        assertEquals(PopupPhaseExpectedSignals.Phase.POPUP, signals.phase)
        assertEquals(
            "弹窗期 = 启动页 ∪ 选择服务器 ∪ 活动弹窗（含全部样式记录）∪ 大厅",
            setOf("launch_start", "server_select", "hall", "popup_close", "popup_close2", "popup_close3"),
            signals.expected(known),
        )
    }

    @Test
    fun hittingHallClosesPopupPhase() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        signals.onRound(setOf(UiState.HALL))
        assertEquals(PopupPhaseExpectedSignals.Phase.STANDBY, signals.phase)
        assertEquals(setOf("launch_start"), signals.expected(known))
    }

    @Test
    fun standbyIgnoresHallHit() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.HALL))
        assertEquals("待命期命中大厅不进入弹窗期", PopupPhaseExpectedSignals.Phase.STANDBY, signals.phase)
    }

    @Test
    fun popupPhaseSurvivesOtherHits() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        signals.onRound(setOf(UiState.ACTIVITY_POPUP))
        signals.onRound(setOf(UiState.SERVER_SELECT))
        signals.onRound(emptySet())
        assertEquals(PopupPhaseExpectedSignals.Phase.POPUP, signals.phase)
    }

    @Test
    fun uncalibratedStatesContributeNoNames() {
        // 产物里没有「选择服务器 / 大厅 / 活动弹窗」→ 弹窗期期望就只剩启动页（该状态在本产品上不可识别）
        val signals = PopupPhaseExpectedSignals.fromRules(
            listOf(SignalStateMapping.Rule(UiState.LAUNCH_PAGE, setOf("launch_start"))),
        )
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        assertEquals(setOf("launch_start"), signals.expected(setOf("launch_start")))
    }

    @Test
    fun popupPhaseExcludesStatesOutsideTheWindow() {
        val signals = PopupPhaseExpectedSignals.fromRules(rules)
        signals.onRound(setOf(UiState.LAUNCH_PAGE))
        assertTrue("农场不在弹窗段内，不该被搜", "farm" !in signals.expected(known))
    }

    @Test
    fun allDeclaresEveryCalibratedSignalAndNoneSearchesNothing() {
        assertEquals("ALL = 显式声明全集（M1 演练口径）", known, ExpectedSignals.ALL.expected(known))
        assertEquals(emptySet<String>(), ExpectedSignals.NONE.expected(known))
        assertEquals(setOf("launch_start"), FixedExpectedSignals.of("launch_start").expected(known))
        assertTrue(FixedExpectedSignals.of().expected(known).isEmpty())
    }
}
