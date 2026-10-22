package com.example.mastermechanic.badge

import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BadgeFlow]（临时需求 temp/badge-share-1021）：立牌支线状态机的纯逻辑口径。
 *
 * 钉的是**分支与推进**（最容易写错、且错一次就会点错控件）：
 * 空槽分支只能从判定步跳、卡片读完只能从读取步推进、进入新步骤必须清掉"已动手"标记。
 */
class BadgeFlowTest {

    private val t0 = 1_000L

    @Test
    fun startBeginsAtEnterActivity() {
        val state = BadgeFlow.start(t0)
        assertEquals(BadgeFlow.Step.ENTER_ACTIVITY, state.step)
        assertEquals(BadgeFlow.Outcome.RUNNING, state.outcome)
        assertTrue(state.isRunning)
        assertEquals(t0, state.stepEnteredAtMs)
    }

    @Test
    fun advanceWalksEveryStepThenFinishes() {
        var state = BadgeFlow.start(t0)
        val seen = mutableListOf(state.step)
        while (state.outcome == BadgeFlow.Outcome.RUNNING) {
            val next = BadgeFlow.advance(state, t0 + 1_000)
            assertTrue("步骤必须只增不减", next.step.number >= state.step.number)
            state = next
            if (state.isRunning) seen += state.step
        }
        assertEquals(BadgeFlow.Outcome.FINISHED, state.outcome)
        assertEquals(BadgeFlow.Step.entries.toList(), seen)
    }

    @Test
    fun decideSendsToClaimWhenEmptySlotPresent() {
        val atBranch = advanceTo(BadgeFlow.Step.DECIDE_BRANCH)
        val next = BadgeFlow.decide(atBranch, needsClaim = true, nowMs = t0 + 2_000)
        assertEquals(BadgeFlow.Step.CLAIM_ENTRY, next.step)
        assertEquals(true, next.needsClaim)
        assertEquals(t0 + 2_000, next.stepEnteredAtMs)
    }

    @Test
    fun decideSendsToShareWhenAlreadyClaimedToday() {
        val atBranch = advanceTo(BadgeFlow.Step.DECIDE_BRANCH)
        val next = BadgeFlow.decide(atBranch, needsClaim = false, nowMs = t0 + 2_000)
        assertEquals(BadgeFlow.Step.SHARE_ENTRY, next.step)
        assertEquals(false, next.needsClaim)
    }

    @Test
    fun decideOnlyWorksOnTheBranchStep() {
        val atEntry = BadgeFlow.start(t0)
        assertEquals("不在判定步 ⇒ 原样返回", atEntry, BadgeFlow.decide(atEntry, needsClaim = true, t0 + 1))
    }

    @Test
    fun advanceToIsOnlyAllowedFromJudgmentSteps() {
        val atEntry = BadgeFlow.start(t0)
        assertEquals(
            "点完不验证就跳步 = 红线 5 违规；非判定步一律不跳",
            atEntry,
            BadgeFlow.advanceTo(atEntry, BadgeFlow.Step.EXIT_ACTIVITY, t0 + 1),
        )
        val atBranch = advanceTo(BadgeFlow.Step.DECIDE_BRANCH)
        assertEquals(
            BadgeFlow.Step.SHARE_ENTRY,
            BadgeFlow.advanceTo(atBranch, BadgeFlow.Step.SHARE_ENTRY, t0 + 1).step,
        )
    }

    @Test
    fun cardReadGoesBackToMyBadgeViaSwitch() {
        // 2026-10-23 用户口径：读完一张**点「切换」回「我的立牌」**选下一张，
        // 不再点 X（真机上 X 会把「我的立牌」一起关掉，多张卡就得重新进一次分享）
        val atRead = advanceTo(BadgeFlow.Step.READ_CARD, cardsRead = 1)
        val next = BadgeFlow.cardRead(atRead, nowMs = t0 + 5_000)
        assertEquals(BadgeFlow.Step.SWITCH_TO_MY, next.step)
        assertEquals(2, next.cardsRead)

        val atFirstRead = advanceTo(BadgeFlow.Step.READ_FIRST)
        assertEquals("第一张（已选立牌）不算卡", atFirstRead, BadgeFlow.cardRead(atFirstRead, t0 + 1))
    }

    @Test
    fun enterClearsPendingActionAndStallState() {
        val state = BadgeFlow.start(t0)
            .let { BadgeFlow.acted(it, t0 + 100, UiState.HALL) }
            .copy(framesStalledSinceMs = t0 + 200)
        val next = BadgeFlow.advance(state, t0 + 500)
        assertEquals(0L, next.lastActionAtMs)
        assertNull(next.stateAtLastAction)
        assertEquals(0L, next.framesStalledSinceMs)
    }

    @Test
    fun withoutPendingActionLetsTheSameStepFireAgain() {
        val acted = BadgeFlow.acted(BadgeFlow.start(t0), t0 + 100, UiState.HALL)
        val cleared = BadgeFlow.withoutPendingAction(acted)
        assertEquals(0L, cleared.lastActionAtMs)
        assertNull(cleared.stateAtLastAction)
    }

    @Test
    fun timeoutUsesPerStepBudget() {
        val atEntry = BadgeFlow.start(t0)
        assertFalse(BadgeFlow.timedOut(atEntry, t0 + BadgeFlow.ENTER_ACTIVITY_TIMEOUT_MS - 1))
        assertTrue(BadgeFlow.timedOut(atEntry, t0 + BadgeFlow.ENTER_ACTIVITY_TIMEOUT_MS))

        val atBranch = advanceTo(BadgeFlow.Step.DECIDE_BRANCH)
        val entered = atBranch.stepEnteredAtMs
        assertFalse(BadgeFlow.timedOut(atBranch, entered + BadgeFlow.ACTIVITY_SETTLE_TIMEOUT_MS - 1))
        assertTrue(BadgeFlow.timedOut(atBranch, entered + BadgeFlow.ACTIVITY_SETTLE_TIMEOUT_MS))
    }

    @Test
    fun pureLogicCallersWithoutClockNeverTimeOut() {
        val noClock = BadgeFlow.State(step = BadgeFlow.Step.ENTER_ACTIVITY)
        assertFalse(BadgeFlow.timedOut(noClock, Long.MAX_VALUE))
        assertEquals(0L, BadgeFlow.waitedMs(noClock, Long.MAX_VALUE))
    }

    @Test
    fun rebaseOnStallPausesTheClockWithinGrace() {
        val state = BadgeFlow.start(t0)
        val rebased = BadgeFlow.rebaseOnStall(state, t0 + 6_000)
        assertEquals(t0 + 6_000, rebased.framesStalledSinceMs)
        assertEquals("计时顺延到此刻", t0 + 6_000, rebased.stepEnteredAtMs)

        val beyond = BadgeFlow.rebaseOnStall(rebased, rebased.framesStalledSinceMs + BadgeFlow.STALL_REBASE_GRACE_MS + 1)
        assertEquals("宽限用完不再顺延", rebased.stepEnteredAtMs, beyond.stepEnteredAtMs)
    }

    @Test
    fun framesResumedClearsTheStallMark() {
        val stalled = BadgeFlow.rebaseOnStall(BadgeFlow.start(t0), t0 + 1_000)
        assertEquals(0L, BadgeFlow.onFramesResumed(stalled).framesStalledSinceMs)
    }

    @Test
    fun pauseAndResumeDoNotLoseTheStep() {
        val state = BadgeFlow.start(t0)
        val paused = BadgeFlow.pause(state)
        assertFalse(paused.isRunning)
        assertEquals(state.step, paused.step)
        val resumed = BadgeFlow.resume(paused, t0 + 9_000)
        assertTrue(resumed.isRunning)
        assertEquals(state.step, resumed.step)
        assertEquals("继续之后重新给足本步预算", t0 + 9_000, resumed.stepEnteredAtMs)
    }

    @Test
    fun failStopsTheFlowAndKeepsTheReason() {
        val failed = BadgeFlow.fail(BadgeFlow.start(t0), "分享码读不出来")
        assertEquals(BadgeFlow.Outcome.FAILED, failed.outcome)
        assertEquals("分享码读不出来", failed.reason)
        assertEquals("已中止后不再推进", failed, BadgeFlow.advance(failed, t0 + 1))
    }

    /** 从起点顺序推进到 [step]（测试辅助）。 */
    private fun advanceTo(step: BadgeFlow.Step, cardsRead: Int = 0): BadgeFlow.State {
        var state = BadgeFlow.start(t0).copy(cardsRead = cardsRead)
        while (state.step.number < step.number) {
            state = BadgeFlow.advance(state, t0 + state.step.number * 1_000L)
        }
        assertEquals(step, state.step)
        return state
    }
}
