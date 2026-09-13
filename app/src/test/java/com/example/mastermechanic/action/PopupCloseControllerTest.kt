package com.example.mastermechanic.action

import com.example.mastermechanic.decision.AnchorHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-01 弹窗闭环决策单测（T2-4）：确认后才点、点完必验证、连续 3 次未生效即放弃、放弃后能复位，
 * 以及演练模式只出判定不计数（B1 / B2 / B4 / B5）。
 */
class PopupCloseControllerTest {

    private fun anchor(
        name: String = "关闭弹窗",
        frameX: Double = 1000.0,
        frameY: Double = 120.0,
    ) = AnchorHit(
        name = name,
        score = 0.95,
        frameX = frameX,
        frameY = frameY,
        calibrationX = 500.0,
        calibrationY = 60.0,
    )

    private fun input(
        foreground: Boolean = true,
        mode: ClickMode = ClickMode.LIVE,
        confirmed: Boolean = true,
        hit: Boolean = true,
        anchors: List<AnchorHit> = listOf(anchor()),
        decisionId: String = "fr01-1",
    ) = PopupRoundInput(
        foreground = foreground,
        mode = mode,
        popupConfirmed = confirmed,
        popupHit = hit,
        anchors = anchors,
        decisionId = decisionId,
    )

    private fun skipNote(outcome: PopupRoundOutcome): String = (outcome.step as PopupStep.Skip).note

    private fun click(outcome: PopupRoundOutcome): PopupStep.Click = outcome.step as PopupStep.Click

    @Test
    fun confirmedPopupWithAnchorDispatchesExactlyOneClick() {
        val controller = PopupCloseController()

        val outcome = controller.onRound(input(decisionId = "fr01-7"))

        // 来源必须是 FR_01（红线 2：只承认三类合法来源），坐标**原样**来自锚点命中（ADR-002：不自己算）
        val request = click(outcome).request
        assertEquals("fr01-7", request.decisionId)
        assertEquals(ClickSource.FR_01, request.source)
        assertEquals("关闭弹窗", request.anchorName)
        assertEquals(1000.0, request.frameX, 1e-9)
        assertEquals(120.0, request.frameY, 1e-9)
        assertEquals(1, outcome.attempt)
        assertTrue("点击已下发，必须进入等验证", controller.awaitingVerification)
        assertEquals(PopupVerification.None, outcome.verification)
    }

    @Test
    fun noPopupMeansNoClick() {
        val controller = PopupCloseController()

        val outcome = controller.onRound(input(confirmed = false, hit = false))

        assertEquals("未见活动弹窗", skipNote(outcome))
        assertEquals(0, outcome.attempt)
        assertFalse(controller.awaitingVerification)
    }

    @Test
    fun unconfirmedHitWaitsForOneMoreRound() {
        // 滞回未确认（只命中 1 次）：宁可晚一轮，不拿未确认的结论去点（红线 7）
        val controller = PopupCloseController()

        val outcome = controller.onRound(input(confirmed = false, hit = true))

        assertTrue(skipNote(outcome).contains("尚未确认"))
        assertEquals(0, outcome.attempt)
    }

    @Test
    fun missingAnchorNeverClicks() {
        // 红线 3/7：未标定 / 未命中锚点 → 零点击，且不能静默（note 说明原因，服务层写日志）
        val controller = PopupCloseController()

        val outcome = controller.onRound(input(anchors = emptyList()))

        assertTrue(skipNote(outcome).contains("未标定"))
        assertEquals(0, outcome.attempt)
        assertFalse(controller.awaitingVerification)
    }

    @Test
    fun picksFirstDeclaredAnchorWhenSeveralDeclared() {
        val controller = PopupCloseController()

        val outcome = controller.onRound(
            input(anchors = listOf(anchor(name = "关闭弹窗"), anchor(name = "今日不再弹出"))),
        )

        assertEquals("关闭弹窗", click(outcome).request.anchorName)
    }

    @Test
    fun verificationClosedResetsRetryCounter() {
        val controller = PopupCloseController()
        controller.onRound(input())

        val outcome = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))

        assertEquals(PopupVerification.Closed(1), outcome.verification)
        assertEquals("未见活动弹窗", skipNote(outcome))
        assertEquals("弹窗消失即复位，不残留上一次的计数", 0, controller.attempt)
        assertFalse(controller.awaitingVerification)
    }

    @Test
    fun stillPresentCountsUpAndRetriesUpToTheLimit() {
        val controller = PopupCloseController()

        assertEquals(1, click(controller.onRound(input())).attempt)
        // 第 2 轮：第 1 枪没打成 → 计数 1，再打第 2 枪
        val second = controller.onRound(input())
        assertEquals(PopupVerification.StillPresent(1), second.verification)
        assertEquals(2, click(second).attempt)
        // 第 3 轮：再打第 3 枪（上限是 3 次，第 3 次仍允许发出）
        val third = controller.onRound(input())
        assertEquals(PopupVerification.StillPresent(2), third.verification)
        assertEquals(3, click(third).attempt)
    }

    @Test
    fun givesUpAfterThreeFailedAttemptsAndStopsClicking() {
        val controller = PopupCloseController()
        repeat(3) { controller.onRound(input()) }

        // 第 4 轮：第 3 枪仍没打成 → 次数用尽，放弃（FR-01 失败处理：宁可漏关，不可错点）
        val giveUp = controller.onRound(input())
        assertEquals(PopupVerification.StillPresent(3), giveUp.verification)
        assertEquals(PopupStep.GiveUp(3), giveUp.step)
        assertTrue(controller.gaveUp)

        // 放弃后不再点击（也不重复报"放弃"之外的结论）
        val after = controller.onRound(input())
        assertTrue(skipNote(after).contains("已放弃"))
        assertEquals(3, after.attempt)
    }

    @Test
    fun gaveUpResetsWhenPopupIsGone() {
        val controller = PopupCloseController()
        repeat(4) { controller.onRound(input()) }
        assertTrue(controller.gaveUp)

        val reset = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertTrue(skipNote(reset).contains("复位"))
        assertFalse(controller.gaveUp)
        assertEquals(0, controller.attempt)

        // 复位后下一次命中可以重新尝试
        assertEquals(1, click(controller.onRound(input())).attempt)
    }

    @Test
    fun frozenRoundNeitherClicksNorConsumesVerification() {
        // 非前台（FR-09）：这一轮不做判断，更不能用它来"验证"上一枪
        val controller = PopupCloseController()
        controller.onRound(input())

        val frozen = controller.onRound(input(foreground = false))
        assertEquals("目标不在前台", skipNote(frozen))
        assertTrue("等验证的状态必须保留，冻结轮不算验证", controller.awaitingVerification)
        assertEquals(1, controller.attempt)

        // 回到前台且弹窗已消失 → 这时才算验证通过
        val closed = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertEquals(PopupVerification.Closed(1), closed.verification)
    }

    @Test
    fun drillModeReportsClickWithoutCountingOrVerifying() {
        // 演练（B5）：日志要能看出"本应点哪里"，但那 3 次重试属于真实下发——
        // 演练下不计数、不等验证，否则长跑会在 3 轮后"放弃"从未发出的点击
        val controller = PopupCloseController()

        repeat(5) {
            val outcome = controller.onRound(input(mode = ClickMode.DRILL))
            assertEquals(1, click(outcome).attempt)
            assertEquals(PopupVerification.None, outcome.verification)
        }
        assertEquals(0, controller.attempt)
        assertFalse(controller.awaitingVerification)
        assertFalse(controller.gaveUp)
    }

    @Test
    fun retryLimitMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { PopupCloseController(maxAttempts = 0) }
        assertTrue(PopupCloseController(maxAttempts = 1).let { it.onRound(input()).step is PopupStep.Click })
    }
}
