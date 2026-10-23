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
        nowMs: Long = now,
    ) = BadgeRunner.RoundInput(
        foreground = foreground,
        framesStalled = framesStalled,
        state = uiState,
        popupHit = popupHit,
        popupConfirmed = popupConfirmed,
        anchors = anchors,
        nowMs = nowMs,
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
    fun centerWithoutBadgeContentWaitsThenClicksBack() {
        // 2026-10-22 两屏合并后：活动中心的标志只有左上返回，**立牌活动与其他活动页都命中它**
        // ⇒ "进错了"只能靠**等不到「获取立牌」**来判。没等满 `ACTIVITY_READY_TIMEOUT_MS` 一下都不点。
        val entering = state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now).copy(centerSinceMs = now)
        val waiting = BadgeRunner.onRound(
            entering,
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(hit(BadgeAnchors.BADGE_ACTIVITY_EXIT)),
                nowMs = now + 3_000,
            ),
        )
        assertNull("还没等满就点返回，会把正在播动效的正确页面踢回大厅", waiting.request)
        assertEquals("起算时刻不被刷新", now, waiting.state?.centerSinceMs)

        val back = BadgeRunner.onRound(
            waiting.state!!,
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(hit(BadgeAnchors.BADGE_ACTIVITY_EXIT, 200.0, 90.0)),
                nowMs = now + BadgeFlow.ACTIVITY_READY_TIMEOUT_MS,
            ),
        )
        assertEquals("等满了才点返回", BadgeAnchors.BADGE_ACTIVITY_EXIT, back.request?.anchorName)
        assertEquals("点完仍停在第 1 步", BadgeFlow.Step.ENTER_ACTIVITY, back.state?.step)

        // 返回的落点 = 大厅：这一击算"通过"，但**不算这一步完成**（restartStep）
        val afterBack = BadgeRunner.onRound(
            back.state!!.copy(
                lastActionAtMs = now + BadgeFlow.ACTIVITY_READY_TIMEOUT_MS - 500,
                stateAtLastAction = UiState.ACTIVITY_CENTER,
            ),
            input(UiState.HALL, nowMs = now + BadgeFlow.ACTIVITY_READY_TIMEOUT_MS),
        )
        assertEquals("退回大厅后不许直接进第 2 步", BadgeFlow.Step.ENTER_ACTIVITY, afterBack.state?.step)
        assertEquals("已动手标记要清掉，下一轮才能重新点活动入口", 0L, afterBack.state?.lastActionAtMs)
        assertEquals("离开活动中心 ⇒ 等待起算清零", 0L, afterBack.state?.centerSinceMs)

        // 下一轮在大厅：重新点「活动入口」，并且计数 +1
        val retry = BadgeRunner.onRound(
            afterBack.state!!,
            input(
                UiState.HALL,
                anchors = listOf(hit(BadgeAnchors.HALL_ACTIVITY)),
                nowMs = now + BadgeFlow.ACTIVITY_READY_TIMEOUT_MS + 1_000,
            ),
        )
        assertEquals(BadgeAnchors.HALL_ACTIVITY, retry.request?.anchorName)
        assertEquals(1, retry.state?.entryAttempts)
    }

    /**
     * 2026-10-23 用户口径：点「活动」可能停在**别的活动页** ⇒ 这次不等满 10 秒，
     * 只要在活动列表里看到「立牌活动入口」就直接点它进立牌活动。
     */
    @Test
    fun activityListEntryIsClickedWithoutWaitingOutTheTimeout() {
        val entering = state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now).copy(centerSinceMs = now)
        val clicked = BadgeRunner.onRound(
            entering,
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(
                    hit(BadgeAnchors.BADGE_ACTIVITY_ENTRY, 120.0, 900.0),
                    hit(BadgeAnchors.BADGE_ACTIVITY_EXIT),
                ),
                nowMs = now + 1_000,
            ),
        )
        assertEquals("先点活动列表里的立牌活动入口", BadgeAnchors.BADGE_ACTIVITY_ENTRY, clicked.request?.anchorName)
        assertEquals("点完仍停在第 1 步（等「分享立牌」出现）", BadgeFlow.Step.ENTER_ACTIVITY, clicked.state?.step)
        assertTrue("点过要记成已动手", (clicked.state?.lastActionAtMs ?: 0L) > 0L)
    }

    /**
     * 第 2 步的**自救**（2026-10-23 真机两次卡在 20 秒后中止）：第 1 步偶发假就绪 ⇒ 进第 2 步才发现
     * 没在立牌活动页。这时活动列表里那张卡还在 ⇒ 点它，而不是干等到超时。
     */
    @Test
    fun decideBranchClicksTheActivityEntryWhenNotReady() {
        val deciding = state(BadgeFlow.Step.DECIDE_BRANCH, nowMs = now)
        val clicked = BadgeRunner.onRound(
            deciding,
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(
                    hit(BadgeAnchors.BADGE_ACTIVITY_ENTRY, 120.0, 900.0),
                    hit(BadgeAnchors.BADGE_ACTIVITY_EXIT),
                ),
                nowMs = now + 1_000,
            ),
        )
        assertEquals("第 2 步发现没就绪就该点活动列表里的入口", BadgeAnchors.BADGE_ACTIVITY_ENTRY, clicked.request?.anchorName)
        assertEquals("点入口不改变步骤", BadgeFlow.Step.DECIDE_BRANCH, clicked.state?.step)
    }

    /** 已经在立牌活动页（「分享立牌」已定位到）⇒ 就绪放行，**不许**再去点列表里那张卡。 */
    @Test
    fun shareEntryAlreadyPresentSkipsTheActivityListEntry() {
        val entering = state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now).copy(centerSinceMs = now)
        val advanced = BadgeRunner.onRound(
            entering,
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(
                    hit(BadgeAnchors.BADGE_SHARE_ENTRY),
                    hit(BadgeAnchors.BADGE_ACTIVITY_ENTRY, 120.0, 900.0),
                ),
                nowMs = now + 500,
            ),
        )
        assertNull("就绪了就不该再点任何东西", advanced.request)
        assertEquals(BadgeFlow.Step.DECIDE_BRANCH, advanced.state?.step)
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
        // 2026-10-22 两屏合并：到活动中心还不够，要**就绪**（两个入口都在）才算第 1 步通过。
        // 就绪后一下都不点，直接进第 2 步，并把等待起算清零。
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.ENTER_ACTIVITY, nowMs = now).copy(centerSinceMs = now),
            input(
                UiState.ACTIVITY_CENTER,
                anchors = listOf(
                    hit(BadgeAnchors.BADGE_ACTIVITY_EXIT),
                    hit(BadgeAnchors.BADGE_CLAIM_ENTRY),
                    hit(BadgeAnchors.BADGE_SHARE_ENTRY),
                ),
                nowMs = now + 4_000,
            ),
        )
        assertNull("已经到活动中心 ⇒ 不该再点大厅入口", result.request)
        assertEquals(BadgeFlow.Step.DECIDE_BRANCH, result.state?.step)
        assertEquals(0L, result.state?.centerSinceMs)
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
            state(BadgeFlow.Step.READ_CARD).copy(cardsRead = 1, cardsRemaining = 1),
            input(
                UiState.BADGE_SHARE,
                step = BadgeFlow.Step.READ_CARD,
                reading = BadgeRunner.Reading.Ok(record, "三块都读到唯一值"),
            ),
        )
        assertEquals(record, result.record)
        // 2026-10-23：还有下一张 ⇒ 点「切换」回「我的立牌」
        assertEquals(BadgeFlow.Step.SWITCH_TO_MY, result.state?.step)
        assertEquals(2, result.state?.cardsRead)
    }

    @Test
    fun readingTheLastCardClosesTheShareDialogDirectly() {
        // 用户口径：点到最后一张的分享码弹框后**不再点切换**，直接点右上角 X 回活动中心
        val record = BadgeRecord("星月晚", "墨子顶级立牌", "1M09G2", 877, LocalDate.of(2026, 10, 21))
        val result = BadgeRunner.onRound(
            state(BadgeFlow.Step.READ_CARD).copy(cardsRead = 2, cardsRemaining = 0),
            input(
                UiState.BADGE_SHARE,
                step = BadgeFlow.Step.READ_CARD,
                reading = BadgeRunner.Reading.Ok(record, "三块都读到唯一值"),
            ),
        )
        assertEquals(BadgeFlow.Step.CLOSE_CARD, result.state?.step)
    }

    // ---------------------------------------------------------------- 卡列表的跳过与顺序（2026-10-23）

    private fun hit2(x: Double, y: Double) = AnchorHit("badge_share", 0.99, x, y, x, y)

    @Test
    fun firstVisitSkipsTheCardThatWasAlreadyRead() {
        // 「分享立牌」进去的是第 1 张；点「切换」进「我的立牌」后，第 1 个「分享」就是它 ⇒ 跳过
        val hits = listOf(hit2(300.0, 300.0), hit2(900.0, 300.0), hit2(1500.0, 300.0))
        val pick = BadgeRunner.planCardPick(hits, readKeys = emptySet(), firstVisit = true)
        assertEquals("300,300", pick.skipKey)
        assertEquals("下一张是第 2 个", 900.0, pick.next?.frameX ?: 0.0, 0.001)
        assertEquals("点完它还剩 1 张", 1, pick.unread - 1)
    }

    @Test
    fun laterVisitsDoNotSkipAnything() {
        val hits = listOf(hit2(300.0, 300.0), hit2(900.0, 300.0), hit2(1500.0, 300.0))
        val pick = BadgeRunner.planCardPick(
            hits,
            readKeys = setOf("300,300", "900,300"),
            firstVisit = false,
        )
        assertEquals(null, pick.skipKey)
        assertEquals(1500.0, pick.next?.frameX ?: 0.0, 0.001)
        assertEquals(1, pick.unread)
    }

    @Test
    fun singleCardServerHasNothingLeftToClick() {
        // 只有 1 张卡：那个「分享」就是刚读过的那张 ⇒ 跳过之后没有可点的（直接关「我的立牌」）
        val pick = BadgeRunner.planCardPick(listOf(hit2(300.0, 300.0)), emptySet(), firstVisit = true)
        assertEquals("300,300", pick.skipKey)
        assertEquals(null, pick.next)
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

    // ---------------------------------------------------------------- 多张卡的阅读顺序（2026-10-23）

    private fun hit(x: Double, y: Double) = AnchorHit("badge_share", 0.99, x, y, x, y)

    @Test
    fun horizontallyArrangedCardsAreOrderedLeftToRight() {
        // 用户 2026-10-23：立牌卡是**横向排列**的 ⇒ 同一行里按 x 读；y 的几像素噪声不该打乱顺序
        val hits = listOf(hit(900.0, 300.0), hit(300.0, 304.0), hit(1500.0, 298.0))
        assertEquals(
            listOf(300.0, 900.0, 1500.0),
            BadgeRunner.readingOrder(hits).map { it.frameX },
        )
    }

    @Test
    fun verticallyStackedCardsStillReadTopDown() {
        // 纵向堆叠时不能只按 x（否则第二行会插到第一行中间）：y 跨度更大 ⇒ 先按 y
        val hits = listOf(hit(300.0, 900.0), hit(300.0, 300.0), hit(300.0, 1500.0))
        assertEquals(
            listOf(300.0, 900.0, 1500.0),
            BadgeRunner.readingOrder(hits).map { it.frameY },
        )
    }
}
