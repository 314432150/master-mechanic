package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击门禁单测（T2-3c）：红线 1（非前台 / 状态未知 → 零点击）、红线 6（完成 → 开始 ≥ 300ms）、
 * 串行约束，以及判定顺序（审计要能看出"最先拦在哪一条"）。
 */
class ClickGateTest {

    private fun environment(
        foreground: Boolean = true,
        state: UiState = UiState.ACTIVITY_POPUP,
        onScreen: Boolean = true,
        guard: Boolean = true,
        stalled: Boolean = false,
        suspect: Boolean = false,
    ) = ClickEnvironment(
        gameForeground = foreground,
        state = state,
        targetOnScreen = onScreen,
        guardRunning = guard,
        framesStalled = stalled,
        foregroundSuspect = suspect,
    )

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
    fun deniesWhenTheFrameStreamIsStalled() {
        // 2026-09-29 用户报"换号 / 拜访时很多标志和锚点都认不出"：会话重建后**5 分钟**没有一帧新画面，
        // 程序照着 **234 秒前**那张大厅画面继续判、继续点 ⇒ 这种情况一律不许点（宁可停下，不可乱点）。
        val verdict = ClickGate().decide(environment(stalled = true), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.STALE_FRAMES, verdict.reason)
    }

    @Test
    fun stalledFramesOutrankTheStaleState() {
        // 画面停摆时"当前状态"本身也是从旧画面读出来的 ⇒ 报"状态未知"会把人引偏，必须报真正的原因
        val verdict = ClickGate().decide(
            environment(stalled = true, state = UiState.UNKNOWN),
            nowMs = 1_000L,
        )

        assertEquals(ClickDenyReason.STALE_FRAMES, verdict.reason)
    }

    @Test
    fun deniesWhileTheForegroundIsMerelySuspect() {
        // 2026-10-01（用户报"**游戏在前台，却提示不在前台**"）：复核看到"游戏窗口还在屏上、只是焦点
        // 被 vivo 桌面抢走"时，**先不放行点击**（安全侧不允许有任何延迟），等下一次复核确认才改判
        // 前台状态（用户可见的后果不白挨）。真机那两次各持续 **2.70 / 2.99 秒** ⇒ 那两秒里不该
        // 有半枪打出去，但也不该把跑号暂停掉（用户还得手动点「继续」）。
        val verdict = ClickGate().decide(environment(suspect = true), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.FOREGROUND_SUSPECT, verdict.reason)
    }

    @Test
    fun suspectOutranksStalledFramesButNotTheRealNotForeground() {
        // 判定顺序：真「不在前台」> **可疑** > 停更 —— 可疑是"还没定论的前台问题"，比"画面停更"更前置
        assertEquals(
            ClickDenyReason.FOREGROUND_SUSPECT,
            ClickGate().decide(environment(suspect = true, stalled = true), nowMs = 1_000L).reason,
        )
        assertEquals(
            ClickDenyReason.NOT_FOREGROUND,
            ClickGate().decide(environment(foreground = false, suspect = true), nowMs = 1_000L).reason,
        )
    }

    @Test
    fun notForegroundStillOutranksStalledFrames() {
        // 前台是更前置的事实：目标不在前台时"没有新帧"毫无信息量（可能只是屏幕静止）
        val verdict = ClickGate().decide(
            environment(foreground = false, stalled = true),
            nowMs = 1_000L,
        )

        assertEquals(ClickDenyReason.NOT_FOREGROUND, verdict.reason)
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
    fun deniesEverythingWhileGuardIsNotRunning() {
        // 2026-09-20 用户口径：常驻守护是**必备项** —— 没起就整体不动作（连 FR-01 关弹窗也不行），
        // 免得出现"看着在跑、其实没人接单"的假运行态
        val verdict = ClickGate().decide(environment(guard = false), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.GUARD_NOT_RUNNING, verdict.reason)
    }

    @Test
    fun guardIsCheckedBeforeEverythingElse() {
        // 顺序固定：守护是**第一拦**（2026-09-22 移除演练模式后，它成为判定列表的首条）
        val verdict = ClickGate().decide(
            environment(guard = false, foreground = false),
            nowMs = 1_000L,
        )

        assertEquals(ClickDenyReason.GUARD_NOT_RUNNING, verdict.reason)
    }

    @Test
    fun deniesWhenConvertedTargetIsOffScreen() {
        // 2026-09-20 增：换算后的落点不在屏幕上 → 硬性拒绝（比"等 300ms"更靠前，因为不是时序问题）
        val verdict = ClickGate().decide(environment(onScreen = false), nowMs = 1_000L)

        assertFalse(verdict.allowed)
        assertEquals(ClickDenyReason.OUT_OF_SCREEN, verdict.reason)
    }

    @Test
    fun reasonFollowsFixedPriorityOrder() {
        // 多种不利条件同时成立：首个拦下的应是守护未启动（门禁顺序固定，审计可解释）
        val gate = ClickGate()
        gate.onGestureStarted("判定-1")

        val verdict = gate.decide(
            environment(guard = false, foreground = false, state = UiState.UNKNOWN, onScreen = false),
            nowMs = 1_000L,
        )

        assertEquals(ClickDenyReason.GUARD_NOT_RUNNING, verdict.reason)
        assertEquals(ClickDenyReason.NOT_FOREGROUND, gate.decide(environment(foreground = false), 1_000L).reason)
        assertEquals(ClickDenyReason.OUT_OF_SCREEN, gate.decide(environment(onScreen = false), 1_000L).reason)
    }

    @Test
    fun rejectsSpacingBelowHardLimit() {
        // 红线 6 是硬下限：任何调用方都不能把间隔调小
        assertThrows(IllegalArgumentException::class.java) { ClickGate(spacingMs = 299L) }
        assertTrue(ClickGate(spacingMs = 300L).decide(environment(), nowMs = 0L).allowed)
    }
}
