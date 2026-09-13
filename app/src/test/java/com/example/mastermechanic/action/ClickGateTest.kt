package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击门禁单测（T2-3c）：红线 1（非前台 / 状态未知 → 零点击）、红线 6（完成 → 开始 ≥ 300ms）、
 * 串行约束，以及判定顺序（审计要能看出"最先拦在哪一条"）。
 */
class ClickGateTest {

    private fun environment(
        mode: ClickMode = ClickMode.LIVE,
        foreground: Boolean = true,
        state: UiState = UiState.ACTIVITY_POPUP,
    ) = ClickEnvironment(gameForeground = foreground, state = state, mode = mode)

    @Test
    fun drillModeDeniesEvenWhenEverythingElseIsFine() {
        val gate = ClickGate()

        val verdict = gate.decide(environment(mode = ClickMode.DRILL), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.DRILL_MODE, verdict.reason)
        assertTrue(gate.isIdle)
        assertNull(gate.lastFinishedMs)
    }

    @Test
    fun deniesWhenGameNotForeground() {
        val verdict = ClickGate().decide(environment(foreground = false), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.NOT_FOREGROUND, verdict.reason)
    }

    @Test
    fun deniesWhenStateUnknown() {
        val verdict = ClickGate().decide(environment(state = UiState.UNKNOWN), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.STATE_UNKNOWN, verdict.reason)
    }

    @Test
    fun deniesWhileAnotherGestureIsInFlight() {
        val gate = ClickGate()
        gate.onGestureStarted("判定-1")

        val verdict = gate.decide(environment(), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.IN_FLIGHT, verdict.reason)
        assertFalse(gate.isIdle)
    }

    @Test
    fun spacingIsMeasuredFromGestureFinishToNextStart() {
        val gate = ClickGate()
        gate.onGestureStarted("判定-1")
        gate.onGestureFinished(nowMs = 1_000L)

        assertFalse("完成 299ms 后不得开始", gate.decide(environment(), nowMs = 1_299L).allowed)
        assertEquals(ClickDenyReason.TOO_SOON, gate.decide(environment(), nowMs = 1_299L).reason)
        assertEquals(1L, gate.remainingWaitMs(1_299L))
        assertTrue("正好 300ms 可以开始（红线 6 是下限）", gate.decide(environment(), nowMs = 1_300L).allowed)
        assertEquals(0L, gate.remainingWaitMs(1_300L))
    }

    @Test
    fun cancelledGestureStillCountsForSpacing() {
        // 被系统打断（onCancelled）也把间隔起算点推到此刻：否则"打断"会变成绕过 300ms 的缺口
        val gate = ClickGate()
        gate.onGestureStarted("判定-1")
        gate.onGestureFinished(nowMs = 2_000L)

        assertFalse(gate.decide(environment(), nowMs = 2_100L).allowed)
        assertEquals(200L, gate.remainingWaitMs(2_100L))
    }

    @Test
    fun firstClickHasNoWait() {
        val gate = ClickGate()

        assertTrue(gate.decide(environment(), nowMs = 10L).allowed)
        assertEquals(0L, gate.remainingWaitMs(10L))
    }

    @Test
    fun reasonFollowsFixedPriorityOrder() {
        // 四种不利条件同时成立：首个拦下的应是演练模式（门禁顺序固定，审计可解释）
        val gate = ClickGate()
        gate.onGestureStarted("判定-1")

        val verdict = gate.decide(
            environment(mode = ClickMode.DRILL, foreground = false, state = UiState.UNKNOWN),
            nowMs = 1_000L,
        )

        assertEquals(ClickDenyReason.DRILL_MODE, verdict.reason)
        assertEquals(ClickDenyReason.NOT_FOREGROUND, gate.decide(environment(foreground = false), 1_000L).reason)
    }

    @Test
    fun rejectsSpacingBelowHardLimit() {
        // 红线 6 是硬下限：任何调用方都不能把间隔调小
        assertThrows(IllegalArgumentException::class.java) { ClickGate(spacingMs = 299L) }
        assertTrue(ClickGate(spacingMs = 300L).decide(environment(), nowMs = 0L).allowed)
    }
}
