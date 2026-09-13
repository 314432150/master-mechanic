package com.example.mastermechanic.action

import com.example.mastermechanic.decision.AnchorHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-01 弹窗闭环决策单测（T2-4；口径经 T2-8 修订）。
 *
 * **现行口径**（2026-09-13 真机实测后由用户拍板）：
 * - 弹窗已确认 + 锚点命中 → 每轮下发一次点击，**不做"单次点击是否奏效"的判定**——
 *   实测「点击后仍命中」无法区分"弹窗没关掉"与"前一个关掉、紧接着冒出新的一个"，二者在日志上完全同形；
 * - 唯一的停止条件是**本段弹窗内点击次数达到上限**，它只兜底"锚点匹配到了非关闭控件"（宁可漏关，不可错点）；
 * - 弹窗真的消失 → 复位，下一段弹窗重新计数（多弹窗场景因此能逐个关掉）；
 * - 演练模式只出判定、不计数（B5）。
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
        assertTrue("点击已下发，等待下一轮观测", controller.awaitingVerification)
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
    fun popupGoneReportsClosedAndResets() {
        val controller = PopupCloseController()
        controller.onRound(input())

        val outcome = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))

        assertEquals(PopupVerification.Closed(1), outcome.verification)
        assertEquals("未见活动弹窗", skipNote(outcome))
        assertEquals("弹窗消失即复位，不残留上一段的计数", 0, controller.attempt)
        assertFalse(controller.awaitingVerification)
    }

    /** T2-8 的核心：弹窗还在就继续点——"点击后仍命中"可能是新弹窗，不能据此停手。 */
    @Test
    fun keepsClickingWhilePopupStaysPresent() {
        val controller = PopupCloseController()

        assertEquals(1, click(controller.onRound(input())).attempt)

        val second = controller.onRound(input())
        assertEquals("仍命中不是失败，继续点", 2, click(second).attempt)
        assertEquals("但观测结论要照记（供事后审计）", PopupVerification.StillPresent(1), second.verification)

        assertEquals(3, click(controller.onRound(input())).attempt)
        assertFalse("未达上限不得停手", controller.gaveUp)
    }

    @Test
    fun stillPresentIsObservationNotFailure() {
        // 旧口径下这里会 3 次即放弃；新口径下只要没到上限就一直点——
        // 因为"仍在命中"既可能是没关掉，也可能是前一个关掉后冒出的新弹窗（真机实测同形）
        val controller = PopupCloseController(maxClicksPerPopupRun = 8)
        repeat(8) { round ->
            assertEquals(round + 1, click(controller.onRound(input())).attempt)
            assertFalse(controller.gaveUp)
        }
    }

    @Test
    fun givesUpAtClickLimitAndStopsClicking() {
        // 上限只兜底"锚点匹配到非关闭控件"：到点即停，避免一直往错位置点（不可逆操作）
        val controller = PopupCloseController(maxClicksPerPopupRun = 4)
        repeat(4) { assertEquals(it + 1, click(controller.onRound(input())).attempt) }

        val giveUp = controller.onRound(input())
        assertEquals(PopupStep.GiveUp(4), giveUp.step)
        assertTrue(controller.gaveUp)

        val after = controller.onRound(input())
        assertTrue(skipNote(after).contains("已达本段点击上限"))
        assertEquals("达上限后不得再点", 4, controller.attempt)
    }

    @Test
    fun defaultClickLimitIsTwelve() {
        // 2026-09-13 用户拍板：一次登录实测 3~5 个弹窗，取 12 覆盖常见数量 + 少量误判
        assertEquals(12, PopupCloseController.DEFAULT_MAX_CLICKS_PER_POPUP_RUN)
    }

    @Test
    fun gaveUpResetsWhenPopupIsGone() {
        // 多弹窗场景的关键：上一段触顶停手后，弹窗真的消失 → 复位，新的弹窗可以重新点
        val controller = PopupCloseController(maxClicksPerPopupRun = 2)
        repeat(3) { controller.onRound(input()) }
        assertTrue(controller.gaveUp)

        val reset = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertEquals("未见活动弹窗", skipNote(reset))
        assertFalse(controller.gaveUp)
        assertEquals(0, controller.attempt)

        assertEquals(1, click(controller.onRound(input())).attempt)
    }

    @Test
    fun frozenRoundNeitherClicksNorCounts() {
        // 非前台（FR-09）：这一轮不做判断，也不能用它来"观测"上一枪
        val controller = PopupCloseController()
        controller.onRound(input())

        val frozen = controller.onRound(input(foreground = false))
        assertEquals("目标不在前台", skipNote(frozen))
        assertTrue("等观测的状态必须保留，冻结轮不算", controller.awaitingVerification)
        assertEquals(1, controller.attempt)

        val closed = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertEquals(PopupVerification.Closed(1), closed.verification)
    }

    @Test
    fun drillModeReportsClickWithoutCounting() {
        // 演练（B5）：日志要能看出"本应点哪里"，但不计数——否则长跑会占满上限而"停手"，
        // 停的却是从未真正发出的点击。上限取 2 以便证明"确实没计数"。
        val controller = PopupCloseController(maxClicksPerPopupRun = 2)

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
    fun rejectsNonPositiveClickLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(maxClicksPerPopupRun = 0)
        }
    }
}
