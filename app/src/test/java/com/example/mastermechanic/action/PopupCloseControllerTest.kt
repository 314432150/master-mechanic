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
 * - **补点前必须"本轮这张画面是上一枪之后拍的"**（2026-09-24 真机事故后加）：拿旧画面上的
 *   "弹窗仍在"去补枪 = 盲点一次（真机点进了商城）；判据用**本轮画面的拍摄时刻**，
 *   不是"最近一帧的到达时刻"（帧流一直在来，后者永远很新）；
 * - 只有真实点击一种行为（2026-09-22 用户口径）：给出判定就计数，「演练不计数」的分支已移除。
 */
class PopupCloseControllerTest {

    /**
     * "三屏都不在了"那句 Skip 的原文（2026-09-29 由「未见活动弹窗」改为按三屏说 —— 本闭环现在
     * 活动弹窗 / 新手引导 / 新手大厅共用）。用常量是为了让改文案只改一处。
     */
    private val OVERLAY_GONE_NOTE = "未见遮挡屏（活动弹窗 / 新手引导 / 新手大厅）"

    /**
     * 每轮自增的时钟（模拟"每轮都吃到新拍的画面"）。
     *
     * ⚠ **步长必须大于补点观察窗**（[PopupCloseController.DEFAULT_RECLICK_GUARD_MS] = 1.0s）：
     * 否则"弹窗还在就继续点"那几条用例会被观察窗拦下 —— 观察窗本身由
     * [aSecondClickWaitsForARealObservationWindowAfterThePreviousClick] 专门验。
     * 要验"旧画面不补点"就显式传 frameAtMs。
     */
    private var clockMs = 1_000L

    private fun tick(): Long {
        clockMs += 1_100
        return clockMs
    }

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
        confirmed: Boolean = true,
        hit: Boolean = true,
        anchors: List<AnchorHit> = listOf(anchor()),
        decisionId: String = "fr01-1",
        nowMs: Long = tick(),
        frameAtMs: Long = nowMs,
        changedPercent: Double? = null,
        source: ClickSource = ClickSource.FR_01,
        overlayKey: String? = null,
    ) = PopupRoundInput(
        foreground = foreground,
        popupConfirmed = confirmed,
        popupHit = hit,
        anchors = anchors,
        decisionId = decisionId,
        nowMs = nowMs,
        frameAtMs = frameAtMs,
        frameChangedPercent = changedPercent,
        source = source,
        overlayKey = overlayKey,
    )

    private fun skipNote(outcome: PopupRoundOutcome): String = (outcome.step as PopupStep.Skip).note

    @Test
    fun afterGivingUpItReArmsOnceThePictureSettlesWithTheOverlayStillThere() {
        // 2026-10-01（用户报"活动弹窗没有全部关掉"）：停手三条路里有一条是"**本轮画面整幅换掉了 ⇒
        // 多半是弹窗已被关掉**"，它是用"整帧变化 %"这个**代理量**判的 —— 分不开"弹窗没了"与
        // "弹窗还在、只是内容换了一幅"。真机那次正是后者：停手之后弹窗一直挂在屏上（标志以 0.9958
        // 连续命中），而复位条件只有"换屏 / 遮挡屏消失" ⇒ **永远不复位**。
        // ⇒ 现在补一条自愈：停手后画面稳定（过了 reArmAfterMs）、遮挡屏**仍被确认** ⇒ 复位重试。
        val controller = PopupCloseController(maxClicksPerPopupRun = 1, reArmAfterMs = 3_000L, maxReArms = 1)

        // 第 1 枪（上限是 1，所以这一枪之后就到了上限）
        controller.onRound(input())
        assertFalse(controller.gaveUp)

        // 到了次数上限 ⇒ 停手
        assertTrue(controller.onRound(input()).step is PopupStep.GiveUp)
        assertTrue(controller.gaveUp)

        // 停手后**还没过 3 秒** ⇒ 保持停手（等画面稳定）
        clockMs += 500
        assertTrue(skipNote(controller.onRound(input())).startsWith("本段已停手"))

        // 过了 3 秒 + 遮挡屏仍被确认 ⇒ **复位重试**
        clockMs += 3_000
        assertEquals(
            "停手后画面已稳定、遮挡屏仍被确认 ⇒ 复位重试（第 1/1 次）",
            skipNote(controller.onRound(input())),
        )
        assertFalse("复位后不再停手（界面那句「已停手」随之清空）", controller.gaveUp)
        assertEquals("计数复位", 0, controller.attempt)

        // 再点满 ⇒ 又停手；但"再给机会"的额度已用完 ⇒ 这次不再复位
        controller.onRound(input())
        assertTrue(controller.onRound(input()).step is PopupStep.GiveUp)
        clockMs += 3_000
        assertTrue(
            "额度用完 ⇒ 老实停手（等换屏或遮挡屏消失）",
            skipNote(controller.onRound(input())).startsWith("本段已停手"),
        )
    }

    /**
     * 停手原因（2026-09-30）：`PopupStep.GiveUp` 从"只带次数"扩成"**次数 + 为什么停手**" ——
     * 界面要把这句原样说给用户（标签「关弹窗已停手」+ 悬浮窗菜单里的原因），所以这里也按 reason 断言。
     */
    private fun giveUpReason(outcome: PopupRoundOutcome): String = (outcome.step as PopupStep.GiveUp).reason

    @Test
    fun theClickSourceIsCarriedThroughForAudit() {
        // 2026-09-29：三屏共用同一个闭环（活动弹窗 = FR-01；新手引导 / 新手大厅 = FR-02）
        // ⇒ 来源必须由调用方带进来，否则审计会把新手两页记成"活动弹窗自动关闭"
        val outcome = PopupCloseController().onRound(
            input(source = ClickSource.FR_02, decisionId = "fr02-9"),
        )

        val request = click(outcome).request
        assertEquals(ClickSource.FR_02, request.source)
        assertEquals("fr02-9", request.decisionId)
    }

    @Test
    fun theDefaultSourceIsStillTheActivityPopup() {
        // 老调用点不带 source ⇒ 默认 FR-01（活动弹窗），行为一个字不变
        val request = click(PopupCloseController().onRound(input())).request
        assertEquals(ClickSource.FR_01, request.source)
    }

    private fun click(outcome: PopupRoundOutcome): PopupStep.Click = outcome.step as PopupStep.Click

    @Test
    fun anotherOverlayScreenStartsANewRun() {
        // **2026-09-30 真机**：新手引导关掉后 **0.46s** 游戏直接弹新手大厅（整帧变化 82.7% ⇒ 本段停手）。
        // 停手本身没错（那一刻确实"露出下层"），但它必须**跟着换屏重开一段** —— 否则第二屏永远点不了
        //（用户报："新手引导页关闭了，但是新手大厅页没有关闭"）。
        val controller = PopupCloseController()

        // 第 1 屏：确认 + 有锚点 ⇒ 点一枪
        assertEquals("fr02-1", click(controller.onRound(input(overlayKey = "TUTORIAL_GUIDE", decisionId = "fr02-1"))).request.decisionId)
        // 这一枪之后画面整幅换掉 ⇒ 本段停手（2026-09-30：改回 **GiveUp** —— 界面要靠它说「关弹窗已停手」）
        assertEquals(
            true,
            giveUpReason(controller.onRound(input(overlayKey = "TUTORIAL_GUIDE", changedPercent = 82.7))).contains("整幅换掉"),
        )
        // ⚠ 关键：接着弹的是**另一屏**遮挡屏 ⇒ 新的一段 ⇒ 这一枪必须还能下发
        assertEquals(
            "换了另一屏遮挡屏就该重开一段 ⇒ 这一枪必须能下发",
            "fr02-2",
            click(controller.onRound(input(overlayKey = "TUTORIAL_HALL", decisionId = "fr02-2"))).request.decisionId,
        )
    }

    @Test
    fun sameOverlayScreenKeepsTheRunStopped() {
        // 对照：**还是同一屏** ⇒ 不得"换屏重开"（否则就绕过了"整幅换掉 ⇒ 停手"这道安全闸）
        val controller = PopupCloseController()
        click(controller.onRound(input(overlayKey = "TUTORIAL_HALL")))
        assertEquals(
            true,
            giveUpReason(controller.onRound(input(overlayKey = "TUTORIAL_HALL", changedPercent = 82.7))).contains("整幅换掉"),
        )
        // 停手之后的那几轮仍旧是 Skip（"本段已停手…"）：动作与"为什么停手"是两件事 ——
        // 前者每轮都会重复，后者只在停下的那一刻给出（界面那份由调用方转成 `PopupCloseSignal.gaveUpReason`）
        assertEquals(true, controller.onRound(input(overlayKey = "TUTORIAL_HALL")).step is PopupStep.Skip)
    }

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

        assertEquals(OVERLAY_GONE_NOTE, skipNote(outcome))
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
        assertEquals(OVERLAY_GONE_NOTE, skipNote(outcome))
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
    fun aSecondClickWaitsForAFrameTakenAfterThePreviousClick() {
        // **2026-09-24 真机事故**：第 1 枪把弹窗关掉，露出的下层界面（大厅）在同一个位置正好是
        // 「商城」入口；而那一轮判定用的还是**点击之前**拍下的那张画面（帧流一直在来，
        // 但本轮吃到的可能是兜底重放的缓存副本）⇒"点击后仍命中弹窗"成立 ⇒ 又补一枪
        // ⇒ **把用户点进了商城**。判据：**本轮这张画面必须晚于上一枪**，不晚就绝不补点。
        val controller = PopupCloseController()

        assertEquals(1, click(controller.onRound(input(nowMs = 1_000, frameAtMs = 1_000))).attempt)

        // 这一轮吃的还是点击之前那张（1_000 ≤ 1_000）⇒ 不补点
        val skipped = controller.onRound(input(nowMs = 1_200, frameAtMs = 1_000))
        assertTrue(
            "应说明'画面还是上一枪之前的'：${skipNote(skipped)}",
            skipNote(skipped).contains("还是上一枪之前的"),
        )
        assertEquals("不补点，也不推进计数", 1, controller.attempt)
        assertFalse("这一轮没点，等观测的状态已消费", controller.awaitingVerification)

        // 这一枪**之后**拍的画面里、弹窗仍命中 ⇒ 允许补点（真没关掉 / 或换了个新弹窗，两种都该点）
        // （时间要给够观察窗 —— 那一条另有专门用例）
        assertEquals(2, click(controller.onRound(input(nowMs = 3_000, frameAtMs = 2_600))).attempt)
    }

    @Test
    fun aSecondClickWaitsForARealObservationWindowAfterThePreviousClick() {
        // **2026-09-28 真机（progress 第 219 条）**：第 1 枪之后 800ms 的那张新帧里弹窗仍 0.9999 命中
        // —— 游戏还没把这一枪的结果画出来 ⇒ 画面是"旧内容的新帧"⇒ 补了两枪 ⇒ 弹窗关掉后下层
        // 那一片正好是「商城」⇒ 用户进了商城。所以判据从"比上一枪新"收紧成"**晚够一个观察窗**"。
        val controller = PopupCloseController()

        assertEquals(1, click(controller.onRound(input(nowMs = 1_000, frameAtMs = 1_000))).attempt)

        // 比上一枪新、但不够一个观察窗 ⇒ 仍然不补点
        val early = controller.onRound(input(nowMs = 1_800, frameAtMs = 1_800))
        assertTrue("应说明还差多久到观察窗：${skipNote(early)}", skipNote(early).contains("观察窗"))
        assertEquals("不补点，也不推进计数", 1, controller.attempt)
        assertFalse(controller.awaitingVerification)

        // 晚够观察窗（1_000 + 1_500）之后弹窗仍命中 ⇒ 允许补点
        assertEquals(2, click(controller.onRound(input(nowMs = 2_600, frameAtMs = 2_600))).attempt)
    }

    @Test
    fun rejectsNegativeGuardWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(reClickGuardMs = -1)
        }
    }

    @Test
    fun givesUpWhenTheRunHasLastedTooLong() {
        // 观察窗把每枪抬到 ≥1.5s ⇒ 只看次数会变成"对着错位置连点 18 秒"
        // ⇒ 再加一道**总时长**闸：本段从第一枪起超过 maxRunMs 就停手（弹窗消失后复位）
        val controller = PopupCloseController(maxRunMs = 3_000)

        assertEquals(1, click(controller.onRound(input(nowMs = 1_000, frameAtMs = 1_000))).attempt)
        assertEquals(2, click(controller.onRound(input(nowMs = 2_600, frameAtMs = 2_600))).attempt)

        // 距第一枪 3.2s（≥ 3s）⇒ 即使次数远未到上限也停手（2026-09-30：停手要带上**为什么** —— 界面要说）
        val stopped = controller.onRound(input(nowMs = 4_200, frameAtMs = 4_200)).step as PopupStep.GiveUp
        assertEquals(2, stopped.attempts)
        assertTrue("原因要说清是时长到顶", stopped.reason.contains("时长上限"))
        assertTrue(controller.gaveUp)

        // 弹窗消失 ⇒ 复位，总时长重新计时（下一段弹窗照常能点）
        val reset = controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertEquals(OVERLAY_GONE_NOTE, skipNote(reset))
        assertFalse(controller.gaveUp)
        assertEquals(1, click(controller.onRound(input(nowMs = 5_000, frameAtMs = 5_000))).attempt)
    }

    @Test
    fun rejectsNonPositiveRunLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(maxRunMs = 0)
        }
    }

    @Test
    fun givesUpAtClickLimitAndStopsClicking() {
        // 上限只兜底"锚点匹配到非关闭控件"：到点即停，避免一直往错位置点（不可逆操作）
        val controller = PopupCloseController(maxClicksPerPopupRun = 4)
        repeat(4) { assertEquals(it + 1, click(controller.onRound(input())).attempt) }

        val giveUp = controller.onRound(input()).step as PopupStep.GiveUp
        assertEquals(4, giveUp.attempts)
        assertTrue("原因要说清是次数到顶", giveUp.reason.contains("次数上限"))
        assertTrue(controller.gaveUp)

        val after = controller.onRound(input())
        // 措辞 2026-09-30 改：旧句会被读成"上限 = 已点次数"（见 PopupCloseController 的说明）
        assertTrue(skipNote(after).contains("本段已停手"))
        assertTrue("要说清已点几次", skipNote(after).contains("已点 4 次"))
        assertTrue("要说清怎么才会复位", skipNote(after).contains("换屏或遮挡屏消失后自动复位"))
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
        assertEquals(OVERLAY_GONE_NOTE, skipNote(reset))
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
    fun rejectsNonPositiveClickLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(maxClicksPerPopupRun = 0)
        }
    }

    @Test
    fun identicalPictureBlocksTheSecondClick() {
        // 2026-09-28 加（真机实测：同一张静止画面 = 0.1%，换弹窗 = 41.7%）：
        // 观察窗过了、弹窗仍确认、锚点也命中，但画面**逐点几乎没变** ⇒ 那一枪等于再打同一张图
        // （两次"点进商城"的危险一枪都出在这种时刻）⇒ 宁可漏关，不扣这一枪。
        val controller = PopupCloseController()
        assertEquals(1, click(controller.onRound(input(changedPercent = 41.7))).attempt)

        val second = controller.onRound(input(changedPercent = 0.1))

        assertTrue("必须是 Skip 而不是再点一枪", second.step is PopupStep.Skip)
        assertTrue(skipNote(second).contains("几乎一模一样"))
        assertEquals("不得计数", 1, controller.attempt)
    }

    @Test
    fun changedPictureAllowsTheSecondClick() {
        // 画面真的变了（换弹窗）⇒ 照旧补枪：这道闸只拦"同一张画面"，不拦"多弹窗连关"
        val controller = PopupCloseController()
        controller.onRound(input(changedPercent = 41.7))

        assertEquals(2, click(controller.onRound(input(changedPercent = 43.5))).attempt)
    }

    @Test
    fun unknownChangeDoesNotBlockTheSecondClick() {
        // 不可比（没基线 / 几何变了）：不把"不知道"当"没变"——照旧由观察窗说了算
        val controller = PopupCloseController()
        controller.onRound(input(changedPercent = null))

        assertEquals(2, click(controller.onRound(input(changedPercent = null))).attempt)
    }

    @Test
    fun identicalFrameThresholdIsTwoPercent() {
        // 口径钉死：实测"没变"是 0.1%、"变了"是 41.7%，2.0% 离两头都极远
        assertEquals(2.0, PopupCloseController.DEFAULT_IDENTICAL_FRAME_PERCENT, 1e-9)
    }

    @Test
    fun rejectsNegativeIdenticalFramePercent() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(identicalFramePercent = -0.1)
        }
    }

    @Test
    fun wholeScreenReplacedStopsTheSegment() {
        // 2026-09-28 真机：两次"只差 10ms / 210ms 就被观察窗拦下"的危险一枪，
        // 都打在"弹窗刚消失、露出下层界面"那张画面上（69.4% / 70.6%）——那正是进商城那一枪的同型现场。
        // 2026-09-29 又出现一次 **53.3%** 的同型现场（原来 60 的阈值拦不住它，只剩"锚点没命中"在兜，
        // 而那件事在 2026-09-24 恰恰不可靠）⇒ 阈值收到 50 之后这种就被判成"整幅换掉"而停手。
        val controller = PopupCloseController()
        controller.onRound(input(changedPercent = 40.2))

        val stop = controller.onRound(input(changedPercent = 53.3))

        assertTrue("必须停手", controller.gaveUp)
        // 2026-09-30：这条路从 Skip 改成 **GiveUp（带原因）** —— 界面要靠它说「关弹窗已停手」；
        // 真机那次走的是 Skip ⇒ 标签退回「待命」并被整条摘掉，弹窗压在屏上而界面一个字都不说。
        val stopped = stop.step as PopupStep.GiveUp
        assertTrue("原因要给用户看", stopped.reason.contains("整幅换掉"))
        assertEquals("不得计数", 1, controller.attempt)

        // 停手是"本段"的：弹窗真的消失 → 复位，下一个弹窗照旧能点
        controller.onRound(input(hit = false, confirmed = false, anchors = emptyList()))
        assertFalse(controller.gaveUp)
        assertEquals(1, click(controller.onRound(input(changedPercent = null))).attempt)
    }

    @Test
    fun replacedThresholdBoundary() {
        // 阈值是">= 50%"：49.9 照旧补枪、50.0 停手
        val clickable = PopupCloseController()
        clickable.onRound(input(changedPercent = 41.7))
        assertEquals(2, click(clickable.onRound(input(changedPercent = 49.9))).attempt)

        val stopped = PopupCloseController()
        stopped.onRound(input(changedPercent = 41.7))
        stopped.onRound(input(changedPercent = 50.0))
        assertTrue(stopped.gaveUp)
    }

    @Test
    fun rejectsReplacedThresholdBelowIdenticalOne() {
        // 两个阈值语义上必须有序：低于"同一画面"阈值就自相矛盾
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(identicalFramePercent = 2.0, sceneReplacedPercent = 1.0)
        }
    }

    @Test
    fun sceneReplacedThresholdIsFiftyPercent() {
        // 口径钉死：实测"换弹窗"最大 43.5%，"露出下层"最小 53.3%（2026-09-29 那段的 53.3/53.4
        // 让原来的 60 失效，于是从 60 收到 50）⇒ 50 落在这道空档里
        assertEquals(50.0, PopupCloseController.DEFAULT_SCENE_REPLACED_PERCENT, 1e-9)
    }

    @Test
    fun identicalPictureStopsBlockingAfterTheHold() {
        // 2026-09-29 真机（用户："第一个弹窗很久才关掉"）：第 1 枪之后 6 秒画面只变 0.0%~1.7%，
        // 被"同一张画面"这条闸一路拦着 ⇒ 补枪拖到 6 秒后。
        // 过了容忍时长画面还是一模一样 ⇒ 游戏早定住了、弹窗真的还在 ⇒ 必须放行。
        val controller = PopupCloseController()
        controller.onRound(input(nowMs = 10_000L, changedPercent = 41.7))

        val later = input(nowMs = 13_000L, changedPercent = 0.4)

        assertEquals(2, click(controller.onRound(later)).attempt)
    }

    @Test
    fun identicalHoldBoundaryIsInclusive() {
        // 边界：距上一枪**正好**容忍时长 ⇒ 视为"定住了"（>= 判据），照旧补枪
        val controller = PopupCloseController()
        controller.onRound(input(nowMs = 10_000L, changedPercent = 41.7))

        val atBoundary = input(nowMs = 12_500L, changedPercent = 0.4)

        assertEquals(2, click(controller.onRound(atBoundary)).attempt)
    }

    @Test
    fun identicalFrameHoldIsTwoAndAHalfSeconds() {
        // 口径钉死：渲染延迟实测 ≈0.76s、两次危险一枪分别等了 0.17s / 0.8s ⇒ 2.5s 两边都留足余量
        assertEquals(2_500L, PopupCloseController.DEFAULT_IDENTICAL_FRAME_HOLD_MS)
    }

    @Test
    fun rejectsNegativeIdenticalFrameHold() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(identicalFrameHoldMs = -1L)
        }
    }

    @Test
    fun changedPictureAllowsAnEarlySecondClick() {
        // 2026-09-29 用户口径："只要画面变化超过 20%，就认定画面发生了变化，然后只点一次"、
        // "真人点击的逻辑就是 弹窗出现-点关闭-下一个弹窗出现-点关闭-大厅出现结束点击"。
        // ⇒ 画面换了一幅（≈40% = 换了一个弹窗）就不必再等满观察窗（1.0s）。
        val controller = PopupCloseController()
        controller.onRound(input(nowMs = 10_000L, frameAtMs = 10_000L, changedPercent = null))

        // 只晚 400ms（< 观察窗 1.0s），但画面已经换了一幅 ⇒ 直接补枪
        val early = input(nowMs = 10_400L, frameAtMs = 10_400L, changedPercent = 40.0)

        assertEquals(2, click(controller.onRound(early)).attempt)
    }

    @Test
    fun animationFramesAreStillWaitedOut() {
        // 但**画面刚变**的那一下（< 350ms）还要等一拍：那时可能正处在淡出/淡入的动画中间，
        // 锚点也许正压在半透明的旧弹窗上（实测新弹窗 ≈0.27s 才完整画出）
        val controller = PopupCloseController()
        controller.onRound(input(nowMs = 10_000L, frameAtMs = 10_000L, changedPercent = null))

        val tooEarly = input(nowMs = 10_200L, frameAtMs = 10_200L, changedPercent = 40.0)
        val skipped = controller.onRound(tooEarly)

        assertTrue(skipNote(skipped).contains("避免点在动画中间"))
        assertEquals("不得计数", 1, controller.attempt)
    }

    @Test
    fun smallChangeStillWaitsForTheObservationWindow() {
        // 变化没到 20%（还没证据说明游戏画出了结果）⇒ 退回原来的两道闸：先等观察窗
        val controller = PopupCloseController()
        controller.onRound(input(nowMs = 10_000L, frameAtMs = 10_000L, changedPercent = null))

        val small = input(nowMs = 10_400L, frameAtMs = 10_400L, changedPercent = 8.0)
        val skipped = controller.onRound(small)

        assertTrue("应仍是观察窗那条，实际：${skipNote(skipped)}", skipNote(skipped).contains("观察窗"))
        assertEquals(1, controller.attempt)
    }

    @Test
    fun renderedChangeThresholdIsTwentyPercent() {
        // 口径钉死（用户口径 20%）：实测"换一个弹窗"是 39.8~43.5%、"弹窗没了"是 68.6~82%
        assertEquals(20.0, PopupCloseController.DEFAULT_RENDERED_CHANGE_PERCENT, 1e-9)
        assertEquals(350L, PopupCloseController.DEFAULT_RENDERED_FLOOR_MS)
    }

    @Test
    fun rejectsInconsistentRenderedThresholds() {
        // 三个阈值必须有序：同一画面 ≤ 画面换了一幅 ≤ 整幅换掉
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(sceneReplacedPercent = 10.0, renderedChangePercent = 20.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(renderedChangePercent = 1.0, identicalFramePercent = 2.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(renderedFloorMs = -1L)
        }
    }

    @Test
    fun staleDecisionFrameBlocksTheShot() {
        // 2026-09-29（用户："如何绝对防止在大厅里误点进入商城"）：唯一那条路径是"判定用的画面比屏幕旧"
        // ⇒ 决策帧拍得太久以前就不开枪（等下一轮）。
        // ⚠ 年龄取"**上限 + 500ms**"而不是写死的 800ms：上限本身要跟着**轮耗时**调
        // （2026-09-29 由 500 调到 1500，因为识别轮涨到 ≈600ms 之后 500ms 把每轮都拦了）——
        // 写死一个数就会在上限调整后又"悄悄不再越过上限"。
        val controller = PopupCloseController()
        val now = tick()
        val tooOld = now - (PopupCloseController.DEFAULT_MAX_FRAME_AGE_MS + 500)

        val stale = controller.onRound(input(nowMs = now, frameAtMs = tooOld))

        assertTrue("必须是 Skip 而不是开枪", stale.step is PopupStep.Skip)
        assertTrue(skipNote(stale).contains("画面不够新"))
        assertEquals("不得计数", 0, controller.attempt)
    }

    @Test
    fun frameAgeBoundaryIsInclusiveAtTheCeiling() {
        // 判据是"> 上限才拦"：恰好等于上限放行、上限+1ms 拦下
        val ceiling = PopupCloseController.DEFAULT_MAX_FRAME_AGE_MS
        val ok = PopupCloseController()
        val now = tick()
        assertEquals(1, click(ok.onRound(input(nowMs = now, frameAtMs = now - ceiling))).attempt)

        val blocked = PopupCloseController()
        val later = tick()
        val stale = blocked.onRound(input(nowMs = later, frameAtMs = later - ceiling - 1))
        assertTrue(stale.step is PopupStep.Skip)
    }

    @Test
    fun defaultFrameAgeCeilingAccommodatesTheCurrentRoundCost() {
        // 口径钉死：**它是"轮询 + 单轮识别"的函数，不是画面有多旧的绝对量** ——
        // 真机实录（2026-09-29 第 286 条）：识别轮涨到 ≈600ms 后，500ms 上限把**每一轮**都拦下
        // （`本轮那张画面是 578ms 前拍到的（上限 500ms）…`，连续几十轮一枪没发 ⇒ 新手引导关不掉）。
        // ⇒ 1500ms：覆盖当前 ≈600ms 常态（含大标志重框前后），仍把"几秒前那张"挡在外面；
        //    "只对现在的画面开枪"由**开火前复眼**（现取最新帧 + 重新命中）负责，见 PreFireRecheck。
        assertEquals(1500L, PopupCloseController.DEFAULT_MAX_FRAME_AGE_MS)
        assertTrue(
            "必须容得下当前实测的决策帧龄（≈600ms），否则这道闸会恒拦",
            PopupCloseController.DEFAULT_MAX_FRAME_AGE_MS > 600L,
        )
    }

    @Test
    fun rejectsNonPositiveFrameAgeCeiling() {
        assertThrows(IllegalArgumentException::class.java) {
            PopupCloseController(maxFrameAgeMs = 0)
        }
    }
}
