package com.example.mastermechanic.badge

import com.example.mastermechanic.action.ClickSource
import com.example.mastermechanic.decision.AnchorHit
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [BadgeRunner]（临时需求 temp/badge-share-1021）：每轮编排的纯逻辑口径。
 *
 * 重点不是"能点"，而是**不该点的时候一下都不点**：
 * 判定步未就绪 / 多实例未消歧 / 画面没确认 / 点击间隔未满 / 停更 —— 全部只等。
 */
class BadgeRunnerTest {

    private val now = 100_000L

    private fun hit(name: String, x: Double = 100.0, y: Double = 200.0) =
        AnchorHit(name = name, score = 0.95, frameX = x, frameY = y, calibrationX = x, calibrationY = y)

    private fun state(step: BadgeFlow.Step, nowMs: Long = 0L, paused: Boolean = false) =
        BadgeFlow.State(step = step, stepEnteredAtMs = nowMs, paused = paused)

    private fun input(
        uiState: UiState,
        step: BadgeFlow.Step = BadgeFlow.Step.ENTER_ACTIVITY,
        anchors: List<AnchorHit> = emptyList(),
        foreground: Boolean = true,
        popupHit: Boolean = false,
        popupConfirmed: Boolean = false,
        framesStalled: Boolean = false,
        lastClickAtMs: Long = 0L,
        claimPlan: BadgeRunner.ClaimPlan = BadgeRunner.ClaimPlan.Waiting,
        cardPlan: BadgeRunner.CardPlan = BadgeRunner.CardPlan.Waiting,
        reading: BadgeRunner.Reading = BadgeRunner.Reading.Waiting,
    ) = BadgeRunner.RoundInput(
        foreground = foreground,
        framesStalled = framesStalled,
        state = uiState,
        popupHit = popupHit,
        popupConfirmed = popupConfirmed,
        anchors = anchors,
        nowMs = now,
        lastClickAtMs = lastClickAtMs,
        claimPlan = claimPlan,
        cardPlan = cardPlan,
        reading = reading,
        decisionId = "badge-test",
    )

    @Test
    fun noTaskMeansNothingHappens() {
        val result = BadgeRunner.onRound(null, input(UiState.HALL))
        assertNull(result.state)
        assertNull(result.request)
    }

    @Test
    fun foregroundLostPausesInsteadOfClicking() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY),
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY)), foreground = false),
        )
        assertNull(result.request)
        assertEquals(true, result.state?.paused)
    }

    @Test
    fun popupBlocksAndVoidsThePendingAction() {
        val pending = state(BadgeFlow.Step.ENTER_ACTIVITY).copy(lastActionAtMs = now - 500)
        val result = BadgeRunner.onRound(
            pending,
            input(UiState.ACTIVITY_POPUP, popupHit = true, popupConfirmed = true),
        )
        assertNull("活动弹窗在屏时一下都不点", result.request)
        assertEquals("刚才那一枪作废（关掉后没到期望画面就重发）", 0L, result.state?.lastActionAtMs)
    }

    @Test
    fun hallEntryClickUsesTheHallAnchorAndBadgeSource() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY),
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY, 300.0, 400.0))),
        )
        val request = result.request
        assertNotNull(request)
        assertEquals(BadgeAnchors.HALL_ACTIVITY, request?.anchorName)
        assertEquals(ClickSource.BADGE_STEP, request?.source)
        assertEquals(300.0, request?.frameX ?: 0.0, 0.001)
        assertEquals(400.0, request?.frameY ?: 0.0, 0.001)
        assertEquals(now, result.state?.lastActionAtMs)
    }

    @Test
    fun wrongActivityPageClicksBackThenRetriesTheActivityEntry() {
        // 2026-10-22 用户口径：点「活动」有时进的是**别的活动页**（没有「获取立牌」，只是同一窗口的另一个活动）。
        // 那里认成「其他活动页」⇒ 点左上角返回退回大厅 ⇒ 再点「活动入口」，步骤不推进。
        val onOther = state(BadgeFlow.Step.ENTER_ACTIVITY)
        val back = BadgeRunner.onRound(
            onOther,
            input(
                UiState.ACTIVITY_OTHER,
                step = BadgeFlow.Step.ENTER_ACTIVITY,
                anchors = listOf(hit(BadgeAnchors.BADGE_ACTIVITY_EXIT, 200.0, 90.0)),
            ),
        )
        assertEquals("点返回，而不是乱点", BadgeAnchors.BADGE_ACTIVITY_EXIT, back.request?.anchorName)
        assertEquals("点完仍停在第 1 步", BadgeFlow.Step.ENTER_ACTIVITY, back.state?.step)

        // 返回的落点 = 大厅：这一击算"通过"，但**不算这一步完成**（restartStep）
        val afterBack = BadgeRunner.onRound(
            back.state!!.copy(lastActionAtMs = now - 500, stateAtLastAction = UiState.ACTIVITY_OTHER),
            input(UiState.HALL),
        )
        assertEquals("退回大厅后不许直接进第 2 步", BadgeFlow.Step.ENTER_ACTIVITY, afterBack.state?.step)
        assertEquals("已动手标记要清掉，下一轮才能重新点活动入口", 0L, afterBack.state?.lastActionAtMs)

        // 下一轮在大厅：重新点「活动入口」，并且计数 +1
        val retry = BadgeRunner.onRound(
            afterBack.state!!,
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY))),
        )
        assertEquals(BadgeAnchors.HALL_ACTIVITY, retry.request?.anchorName)
        assertEquals(1, retry.state?.entryAttempts)
    }

    @Test
    fun entryAttemptsAreCappedInsteadOfLoopingForever() {
        // "极大概率"≠ 必然：3 次都没进立牌活动就停下报原因（FR-T5），不无限重试。
        var current = state(BadgeFlow.Step.ENTER_ACTIVITY).copy(entryAttempts = BadgeFlow.ENTRY_MAX_ATTEMPTS)
        val result = BadgeRunner.onRound(
            current,
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY))),
        )
        assertNull("到上限后不许再点", result.request)
        assertEquals(true, result.state?.failed)
        assertTrue(
            "原因要说清是「进了别的活动」：${result.state?.reason}",
            result.state?.reason.orEmpty().contains("别的活动"),
        )
        current = result.state!!
        assertEquals(BadgeFlow.Step.ENTER_ACTIVITY, current.step)
    }

    @Test
    fun reachingTheExpectedScreenAdvancesWithoutClicking() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY),
            input(UiState.ACTIVITY_CENTER),
        )
        assertNull("已经到活动中心 ⇒ 不该再点大厅入口", result.request)
        assertEquals(BadgeFlow.Step.DECIDE_BRANCH, result.state?.step)
    }

    @Test
    fun clickGapIsRespected() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY),
            input(
                UiState.HALL,
                anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY)),
                lastClickAtMs = now - 100,
            ),
        )
        assertNull(result.request)
        assertTrue(result.note.orEmpty().contains("间隔"))
    }

    @Test
    fun stuckOnTheSameScreenRetriesAfterCooldown() {
        // 真机 2026-10-22：点活动入口后画面一直停在「大厅」（或认成未知），口径 A 不允许补点
        // ⇒ 干等 30 秒超时。回到动手前那一屏 + 冷却够了 ⇒ 撤销，让下一轮重发。
        val clicked = state(BadgeFlow.Step.ENTER_ACTIVITY)
            .copy(lastActionAtMs = now - BadgeRunner.ACTION_RETRY_MS - 1, stateAtLastAction = UiState.HALL)
        val result = BadgeRunner.onRound(
            clicked,
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY))),
        )
        assertNull("这一轮只撤销，不立刻再点", result.request)
        assertEquals(0L, result.state?.lastActionAtMs)

        val retry = BadgeRunner.onRound(
            result.state,
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY))),
        )
        assertNotNull("下一轮应重新发起点击", retry.request)
    }

    @Test
    fun sameScreenBeforeCooldownKeepsWaiting() {
        val clicked = state(BadgeFlow.Step.ENTER_ACTIVITY)
            .copy(
                lastActionAtMs = now - BadgeRunner.ACTION_RETRY_MS + 500,
                stateAtLastAction = UiState.HALL,
            )
        val result = BadgeRunner.onRound(
            clicked,
            input(UiState.HALL, anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY))),
        )
        assertNull("冷却没到 ⇒ 不重发", result.request)
        assertEquals(clicked.lastActionAtMs, result.state?.lastActionAtMs)
    }

    @Test
    fun anchorMissKeepsWaitingAndNeverGuesses() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY),
            input(UiState.HALL, anchors = emptyList()),
        )
        assertNull(result.request)
        assertEquals(BadgeFlow.Step.ENTER_ACTIVITY, result.state?.step)
    }

    @Test
    fun decideBranchWaitsUntilThePageIsInteractive() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.DECIDE_BRANCH, now - 5_000),
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.DECIDE_BRANCH),
        )
        assertNull(result.request)
        assertEquals("自动提交动效没结束 ⇒ 不判、不点", BadgeFlow.Step.DECIDE_BRANCH, result.state?.step)
    }

    @Test
    fun decideBranchWaitsTheMinimumSettleEvenWhenReady() {
        val ready = listOf(
            hit(BadgeAnchors.BADGE_CLAIM_ENTRY),
            hit(BadgeAnchors.BADGE_SHARE_ENTRY),
        )
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.DECIDE_BRANCH, now - 500),
            input(
                UiState.ACTIVITY_CENTER,
                step = BadgeFlow.Step.DECIDE_BRANCH,
                anchors = ready + hit(BadgeAnchors.BADGE_EMPTY_SLOT),
            ),
        )
        assertEquals(BadgeFlow.Step.DECIDE_BRANCH, result.state?.step)
    }

    @Test
    fun emptySlotPresentMeansClaimingToday() {
        val anchors = listOf(
            hit(BadgeAnchors.BADGE_CLAIM_ENTRY),
            hit(BadgeAnchors.BADGE_SHARE_ENTRY),
            hit(BadgeAnchors.BADGE_EMPTY_SLOT),
        )
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.DECIDE_BRANCH, now - 4_000),
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.DECIDE_BRANCH, anchors = anchors),
        )
        assertEquals(BadgeFlow.Step.CLAIM_ENTRY, result.state?.step)
        assertEquals(true, result.state?.needsClaim)
    }

    @Test
    fun missingEmptySlotNeedsConsecutiveRoundsBeforeDecidingAlreadyCollected() {
        // 「空槽不在」= 已领取 是**缺席证据**，一轮不算数（可能只是这一轮没搜到）：先等一轮确认。
        val anchors = listOf(
            hit(BadgeAnchors.BADGE_CLAIM_ENTRY),
            hit(BadgeAnchors.BADGE_SHARE_ENTRY),
        )
        val first = BadgeRunner.onRound(
            state(BadgeFlow.Step.DECIDE_BRANCH, now - 4_000),
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.DECIDE_BRANCH, anchors = anchors),
        )
        assertEquals("一轮缺席不作数", BadgeFlow.Step.DECIDE_BRANCH, first.state?.step)
        assertEquals(1, first.state?.emptySlotAbsentRounds)

        val second = BadgeRunner.onRound(
            first.state!!,
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.DECIDE_BRANCH, anchors = anchors),
        )
        assertEquals("连续两轮都不在 ⇒ 直接分享，不点「获取立牌」", BadgeFlow.Step.SHARE_ENTRY, second.state?.step)
        assertEquals(false, second.state?.needsClaim)
        assertEquals("进入下一步后计数清零（下个区服重新确认）", 0, second.state?.emptySlotAbsentRounds)
    }

    @Test
    fun emptySlotReappearingClearsTheAbsenceStreak() {
        // 存在是硬证据：只要有一轮命中，立刻认"还没领取"，并清零缺席计数。
        val ready = listOf(
            hit(BadgeAnchors.BADGE_CLAIM_ENTRY),
            hit(BadgeAnchors.BADGE_SHARE_ENTRY),
        )
        val absentOnce = BadgeRunner.onRound(
            state(BadgeFlow.Step.DECIDE_BRANCH, now - 4_000),
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.DECIDE_BRANCH, anchors = ready),
        )
        val appeared = BadgeRunner.onRound(
            absentOnce.state!!,
            input(
                UiState.ACTIVITY_CENTER,
                step = BadgeFlow.Step.DECIDE_BRANCH,
                anchors = ready + hit(BadgeAnchors.BADGE_EMPTY_SLOT),
            ),
        )
        assertEquals(BadgeFlow.Step.CLAIM_ENTRY, appeared.state?.step)
        assertEquals(true, appeared.state?.needsClaim)
        assertEquals("空槽回来 ⇒ 缺席计数清零", 0, appeared.state?.emptySlotAbsentRounds)
    }

    @Test
    fun alreadyClaimedSkipsWithoutError() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.CLAIM_TASK),
            input(
                UiState.BADGE_CLAIM,
                step = BadgeFlow.Step.CLAIM_TASK,
                claimPlan = BadgeRunner.ClaimPlan.AlreadyClaimed("这一行已是「已领取」"),
            ),
        )
        assertNull("已领取不重领", result.request)
        assertEquals(BadgeFlow.Step.CLAIM_FINISH, result.state?.step)
    }

    @Test
    fun claimWaitingNeverFallsBackToPickingAnAnchor() {
        // 两行各有一个领取按钮；采集层还没按行文字消歧 ⇒ 宁可等，也不许按锚点挑一个
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.CLAIM_TASK),
            input(
                UiState.BADGE_CLAIM,
                step = BadgeFlow.Step.CLAIM_TASK,
                anchors = listOf(hit(BadgeAnchors.BADGE_CLAIM)),
            ),
        )
        assertNull(result.request)
        assertEquals(BadgeFlow.Step.CLAIM_TASK, result.state?.step)
    }

    @Test
    fun claimClicksTheDisambiguatedPoint() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.CLAIM_TASK),
            input(
                UiState.BADGE_CLAIM,
                step = BadgeFlow.Step.CLAIM_TASK,
                claimPlan = BadgeRunner.ClaimPlan.Click(123.0, 456.0, "行文字「登录游戏」"),
            ),
        )
        assertEquals(BadgeAnchors.BADGE_CLAIM, result.request?.anchorName)
        assertEquals(123.0, result.request?.frameX ?: 0.0, 0.001)
        assertEquals(456.0, result.request?.frameY ?: 0.0, 0.001)
    }

    @Test
    fun noMoreCardsClosesMyBadgeWithoutClickingShare() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.PICK_CARD),
            input(
                UiState.BADGE_MY,
                step = BadgeFlow.Step.PICK_CARD,
                cardPlan = BadgeRunner.CardPlan.NoMore("两张卡都读过了"),
            ),
        )
        assertNull(result.request)
        assertEquals(BadgeFlow.Step.CLOSE_MY, result.state?.step)
    }

    @Test
    fun pickingACardClicksShareAndMovesToReading() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.PICK_CARD),
            input(
                UiState.BADGE_MY,
                step = BadgeFlow.Step.PICK_CARD,
                cardPlan = BadgeRunner.CardPlan.Click(50.0, 60.0, "第 1 张卡", remaining = 1),
            ),
        )
        assertEquals(BadgeAnchors.BADGE_SHARE, result.request?.anchorName)
        assertEquals(BadgeFlow.Step.READ_CARD, result.state?.step)
        assertEquals(now, result.state?.lastActionAtMs)
    }

    @Test
    fun cardReadingRecordsAndAdvancesToClose() {
        val record = BadgeRecord("星月晚", "墨子顶级立牌", "1M09G2", 877, LocalDate.of(2026, 10, 21))
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.READ_CARD).copy(cardsRead = 1),
            input(
                UiState.BADGE_SHARE,
                step = BadgeFlow.Step.READ_CARD,
                reading = BadgeRunner.Reading.Ok(record, "三块都读到唯一值"),
            ),
        )
        assertEquals(record, result.record)
        assertEquals(BadgeFlow.Step.CLOSE_CARD, result.state?.step)
        assertEquals(2, result.state?.cardsRead)
    }

    @Test
    fun closingShareDialogOntoMyBadgeGoesToPickCard() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.CLOSE_CARD),
            input(UiState.BADGE_MY, step = BadgeFlow.Step.CLOSE_CARD),
        )
        assertEquals(BadgeFlow.Step.PICK_CARD, result.state?.step)
    }

    @Test
    fun closingShareDialogOntoActivityCenterSkipsClosingMyBadge() {
        // 真机 2026-10-22：只有 1 张卡时，关闭 X 把「我的立牌」那层一起关掉，画面直接回活动中心
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.CLOSE_CARD),
            input(UiState.ACTIVITY_CENTER, step = BadgeFlow.Step.CLOSE_CARD),
        )
        assertEquals("已回活动中心 ⇒ 跳过关闭我的立牌，直接返回大厅", BadgeFlow.Step.EXIT_ACTIVITY, result.state?.step)
    }

    @Test
    fun unparsableCodeStopsInsteadOfSavingAPartialRecord() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.READ_FIRST),
            input(
                UiState.BADGE_SHARE,
                step = BadgeFlow.Step.READ_FIRST,
                reading = BadgeRunner.Reading.Failed("分享码块里读到两个候选值"),
            ),
        )
        assertNull(result.record)
        assertEquals(BadgeFlow.Outcome.FAILED, result.state?.outcome)
        assertTrue(result.state?.reason.orEmpty().contains("分享码"))
    }

    @Test
    fun waitingTooLongStopsWithFactsNotGuesses() {
        val timedOut = state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now - BadgeFlow.ENTER_ACTIVITY_TIMEOUT_MS - 1)
        val result = BadgeRunner.onRound(timedOut, input(UiState.HALL))
        assertEquals(BadgeFlow.Outcome.FAILED, result.state?.outcome)
        val reason = result.state?.reason.orEmpty()
        assertTrue(reason.contains("等了"))
        assertTrue(reason.contains("期望"))
        assertTrue(reason.contains("现在"))
    }

    @Test
    fun tutorialScreensPostponeTheStepClock() {
        // 新号登录后，游戏自己的「新手引导 / 新手大厅」不该算进我们的步预算（2026-10-22）
        val entered = now - 8_000
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = entered),
            input(UiState.TUTORIAL_HALL, step = BadgeFlow.Step.ENTER_ACTIVITY),
        )
        assertEquals("计时顺延到此刻", now, result.state?.stepEnteredAtMs)
        assertEquals(8_000L, result.state?.obstructedMs)
        assertEquals(BadgeFlow.Outcome.RUNNING, result.state?.outcome)
    }

    @Test
    fun obstructionPostponementIsCapped() {
        val entered = now - BadgeFlow.OBSTRUCTION_REBASE_LIMIT_MS - 5_000
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = entered),
            input(UiState.TUTORIAL_GUIDE, step = BadgeFlow.Step.ENTER_ACTIVITY),
        )
        assertEquals(
            "最多只顺延上限那么多，之后照常算超时",
            BadgeFlow.OBSTRUCTION_REBASE_LIMIT_MS,
            result.state?.obstructedMs,
        )
    }

    @Test
    fun stalledFramesPauseTheClockInsteadOfAborting() {
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now - 1_000),
            input(UiState.HALL, framesStalled = true),
        )
        assertNull(result.request)
        assertEquals(BadgeFlow.Outcome.RUNNING, result.state?.outcome)
        assertEquals(now, result.state?.stepEnteredAtMs)
    }
}
