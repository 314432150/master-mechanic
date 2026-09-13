package com.example.mastermechanic.action

import com.example.mastermechanic.decision.AnchorHit

/**
 * FR-01 活动弹窗自动关闭的决策本体（T2-4，纯逻辑，可 JVM 重跑）。
 *
 * 职责边界（与邻层的关系）：
 * - **不产生点击**：只给出 [ClickRequest]，下发一律经 `ClickDispatch.submit`（红线 6 的唯一实现点）；
 * - **不定位锚点**：锚点由 `AnchorLocator` 按当前状态定位后作为输入传入（本类只决定"用哪个 + 要不要用"）；
 * - **不做识别**：弹窗是否命中 / 是否确认由识别循环的 `RoundResult` 如实传入。
 *
 * ## 口径（T2-8，2026-09-13 真机实测后修订）
 *
 * **不做"单次点击是否奏效"的判定，只要弹窗还在就继续点**，唯一停止条件是「同一段弹窗内点击次数达到上限」。
 *
 * 为什么撤销原判据（原为「连续 [maxAttempts] 次点击后仍命中 → 判为误匹配，放弃」）：
 * 真机日志证明**「点击后仍命中」无法区分两件事**——
 * ① 弹窗没被关掉；② 前一个弹窗**已被关掉、紧接着冒出了下一个弹窗**（用户实测："挨个点掉，点掉一个马上又出现一个"）。
 * 二者在日志上完全同形，于是原判据把"正常地关掉一个又一个弹窗"误判成"反复失败"，
 * 3 次后放弃 → 剩下的弹窗再没人管（2026-09-13 实点现场即如此）。既然判不出来，就不该由它决定动作。
 *
 * **为什么仍保留一个上限**：上限防的不是"点不掉"，而是**"锚点匹配到了不是关闭控件的位置"**——
 * 那种情况下程序会一直往错位置点，而游戏里点到别处可能是不可逆操作（需求 §3.1「宁可漏关，不可错点」）。
 * 上限一到即停手；弹窗真的消失后自动复位，下一段弹窗重新计数。
 *
 * 每轮流程：
 * ```
 * ① 记录上一枪的观测结果（仅写日志，不影响动作）
 * ② 弹窗真的不在 → 复位并结束
 * ③ 已达上限 → 停止点击（等弹窗消失后复位）
 * ④ 弹窗已确认 + 锚点命中 → 下发一次点击
 * ```
 * 相邻点击的间隔由统一转发层保证（红线 6，≥300ms），本类不做节流。
 *
 * 演练模式（[ClickMode.DRILL]，B5）：判定照算、日志照出（真机演练要看"本应点哪里"），但**不计数**——
 * 否则演练长跑会把从未真正发出的点击算成"已达上限"。
 */
class PopupCloseController(
    private val maxClicksPerPopupRun: Int = DEFAULT_MAX_CLICKS_PER_POPUP_RUN,
) {

    init {
        require(maxClicksPerPopupRun >= 1) { "点击上限至少为 1：$maxClicksPerPopupRun" }
    }

    /** 本段弹窗内已下发的点击次数（0 = 还没点过）。弹窗消失即复位。 */
    var attempt: Int = 0
        private set

    /** 是否有一枪已下发、等下一轮记录观测结果（**不影响动作**）。 */
    var awaitingVerification: Boolean = false
        private set

    /** 是否已达本段上限（停止点击；弹窗消失后复位）。 */
    var gaveUp: Boolean = false
        private set

    fun onRound(input: PopupRoundInput): PopupRoundOutcome {
        // 冻结轮（FR-09）：非前台时"弹窗还在不在"无从判断——不动作、也**不推进计数**
        if (!input.foreground) {
            return outcome(PopupVerification.None, PopupStep.Skip("目标不在前台"))
        }

        // 弹窗是否还在，用**本轮原始命中**（比滞回结论灵敏：弹窗刚弹出/刚消失都能立刻反映）
        val present = input.popupHit || input.popupConfirmed
        var verification: PopupVerification = PopupVerification.None

        if (!present) {
            // 弹窗真的不在了 → 本段结束，计数复位（复位前把"上一枪之后的样子"记一次）
            if (awaitingVerification) {
                awaitingVerification = false
                verification = PopupVerification.Closed(attempt)
            }
            if (attempt != 0) reset()
            return outcome(verification, PopupStep.Skip("未见活动弹窗"))
        }

        // ① 上一枪的观测（只写日志）：点击之后弹窗是否还在。**不作为动作依据**
        if (awaitingVerification) {
            awaitingVerification = false
            verification = PopupVerification.StillPresent(attempt)
        }

        if (gaveUp) {
            return outcome(
                verification,
                PopupStep.Skip("已达本段点击上限（$attempt 次），停止点击，弹窗消失后自动复位"),
            )
        }

        // 弹窗刚命中、滞回还没确认（连续 2 次命中才进入该状态）：等下一轮——不拿未确认的结论点击
        if (!input.popupConfirmed) {
            return outcome(verification, PopupStep.Skip("弹窗刚命中、尚未确认（等下一轮）"))
        }

        // 兜底：达到上限 → 停手（防"锚点匹配到非关闭控件"时无限点击）
        if (attempt >= maxClicksPerPopupRun) {
            gaveUp = true
            return outcome(verification, PopupStep.GiveUp(attempt))
        }

        // 多条锚点时取**产物声明顺序的第一个**（AnchorLocator 保持声明顺序）：标定先标一条最保险
        val anchor = input.anchors.firstOrNull()
            ?: return outcome(
                verification,
                PopupStep.Skip("未标定 / 未命中关闭控件锚点（不猜位置）"),
            )

        val request = ClickRequest(
            decisionId = input.decisionId,
            source = ClickSource.FR_01,
            anchorName = anchor.name,
            frameX = anchor.frameX,
            frameY = anchor.frameY,
        )
        // 演练：只出判定（日志写"本应点击"），不计数——见类注释。
        // 计数只在实点推进，故演练下 attempt 恒为 0，状态描述里统一按"第 1 次"呈现。
        val live = input.mode == ClickMode.LIVE
        if (live) {
            attempt += 1
            awaitingVerification = true
        }
        return outcome(verification, PopupStep.Click(request, attempt = if (live) attempt else 1))
    }

    private fun reset() {
        attempt = 0
        awaitingVerification = false
        gaveUp = false
    }

    private fun outcome(verification: PopupVerification, step: PopupStep): PopupRoundOutcome =
        PopupRoundOutcome(verification = verification, attempt = attempt, step = step)

    companion object {

        /**
         * 同一段弹窗内的点击次数上限（T2-8，2026-09-13 用户拍板取 12）。
         *
         * 用途**仅是兜底**"锚点匹配到非关闭控件"的情形——不是"单次点击成败"的判据
         * （那个判不出来，见类注释）。12 足以覆盖常见弹窗数（实测一次登录 3~5 个）与少量误判；
         * 即使锚点全错，配合转发层 ≥300ms 的间隔，最多约 4 秒即停手。
         */
        const val DEFAULT_MAX_CLICKS_PER_POPUP_RUN = 12
    }
}

/**
 * 一轮 FR-01 决策的输入（全部由调用方如实提供，本类不猜）。
 *
 * @param foreground 目标是否前台（冻结轮 = false → 不动作、不计数）
 * @param mode 点击模式：演练只出判定（B5）
 * @param popupConfirmed 滞回结论 = 活动弹窗（连续 2 次命中才算确认）
 * @param popupHit 本轮**原始命中**含活动弹窗（用于判断"弹窗是否还在"，比滞回结论更灵敏）
 * @param anchors 当前状态的锚点命中列表（调用方已按 `AnchorLocator.locate` 定位；未确认状态时给空列表）
 * @param decisionId 判定记录 ID（NFR-05 审计链：与 `MM-Click` 日志对齐）
 */
data class PopupRoundInput(
    val foreground: Boolean,
    val mode: ClickMode,
    val popupConfirmed: Boolean,
    val popupHit: Boolean,
    val anchors: List<AnchorHit>,
    val decisionId: String,
)

/**
 * 上一枪的**观测**结论（只写日志、供事后审计"动作是否生效"，**不驱动任何动作**）。
 *
 * 之所以只观测不决策：见 [PopupCloseController] 类注释——「点击后仍命中」无法区分
 * "没关掉"与"换了一个新弹窗"，因此它不能作为成败判据。
 */
sealed interface PopupVerification {

    /** 本轮没有待观测的点击（没点过、或冻结轮）。 */
    data object None : PopupVerification

    /** 上一枪之后弹窗**已不再命中**（本段共点击 [attempts] 次）。 */
    data class Closed(val attempts: Int) : PopupVerification

    /** 上一枪之后**仍命中**弹窗（[attempts] = 本段已点击次数）——可能是没关掉，也可能是换了新弹窗。 */
    data class StillPresent(val attempts: Int) : PopupVerification
}

/** 本轮动作。 */
sealed interface PopupStep {

    /** 不动作，[note] 说明原因（写日志用；同一原因连续出现时调用方只记一次，避免刷屏）。 */
    data class Skip(val note: String) : PopupStep

    /** 下发一次点击（**是否真的下发由门禁决定**：演练 / 非前台 / 状态未知都会被拒并留痕）。 */
    data class Click(val request: ClickRequest, val attempt: Int) : PopupStep

    /** 本段点击次数达上限 → 停止点击（兜底；弹窗消失后复位）。 */
    data class GiveUp(val attempts: Int) : PopupStep
}

/** 一轮决策的完整结果：既说明"上一枪之后的观测"，也说明"这一轮要不要开枪"。 */
data class PopupRoundOutcome(
    val verification: PopupVerification,
    val attempt: Int,
    val step: PopupStep,
)
