package com.example.mastermechanic.action

import com.example.mastermechanic.decision.AnchorHit

/**
 * FR-01 活动弹窗自动关闭的决策本体（T2-4，纯逻辑，可 JVM 重跑）：把「识别 → 点击 → 验证」
 * 串成一次**有界重试**的闭环。
 *
 * 职责边界（与邻层的关系）：
 * - **不产生点击**：只给出 [ClickRequest]，下发一律经 `ClickDispatch.submit`（红线 6 的唯一实现点）；
 * - **不定位锚点**：锚点由 `AnchorLocator` 按当前状态定位后作为输入传入（本类只决定"用哪个 + 要不要用"）；
 * - **不做识别**：弹窗是否命中 / 是否确认由识别循环的 `RoundResult` 如实传入。
 *
 * 状态机（每轮 [onRound] 一次）：
 * ```
 * ① 验证上一枪 ── 弹窗消失 → 成功（计数复位）
 *              └ 弹窗仍在 → 计数已满? 放弃 : 继续
 * ② 决定本轮 ─── 未见弹窗 / 未确认 / 无锚点 → 不动作（各自带原因）
 *              └ 确认 + 有锚点 → 下发一次点击，进入"等验证"
 * ```
 * **顺序不可颠倒**：先验证再动作，是红线 5「动作后未验证，不得执行下一步」在单轮粒度上的落地——
 * 上一枪还没被下一轮确认，就绝不允许再打一枪。
 *
 * 重试上限（requirements FR-01 失败处理）：连续 [maxAttempts] 次「点击后仍命中」→ 判为误匹配，
 * **放弃本次点击**并记录；此后不再点击，直到弹窗消失（用户手动关掉 / 换了界面）才复位。
 *
 * 演练模式（[ClickMode.DRILL]，B5）：判定照算、日志照出（真机演练要看"本应点哪里"），
 * 但**不推进计数、不进入等待验证**——那 3 次重试是给真实下发的，演练下"放弃"等于放弃从未发出的点击。
 */
class PopupCloseController(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    init {
        require(maxAttempts >= 1) { "重试上限至少为 1：$maxAttempts" }
    }

    /** 本次弹窗已下发的点击次数（0 = 还没点过）。弹窗消失即复位。 */
    var attempt: Int = 0
        private set

    /** 是否有一枪已下发、等下一轮验证。 */
    var awaitingVerification: Boolean = false
        private set

    /** 是否已放弃本次点击（连续 [maxAttempts] 次未生效）。 */
    var gaveUp: Boolean = false
        private set

    fun onRound(input: PopupRoundInput): PopupRoundOutcome {
        // 冻结轮（FR-09）：非前台时"弹窗还在不在"无从判断——不动作、也**不推进计数**（否则计数会被非前台时间吃掉）
        if (!input.foreground) {
            return outcome(PopupVerification.None, PopupStep.Skip("目标不在前台"))
        }

        val present = input.popupHit || input.popupConfirmed
        var verification: PopupVerification = PopupVerification.None

        // ① 先验证上一枪（红线 5）：上一枪的结果决定这一轮能不能再打
        if (awaitingVerification) {
            awaitingVerification = false
            val fired = attempt
            if (present) {
                verification = PopupVerification.StillPresent(fired)
            } else {
                verification = PopupVerification.Closed(fired)
                reset()
            }
        }

        if (gaveUp) {
            return if (present) {
                outcome(verification, PopupStep.Skip("已放弃本次点击（连续 $attempt 次未生效）"))
            } else {
                reset()
                outcome(verification, PopupStep.Skip("弹窗已消失，重试计数复位"))
            }
        }

        if (!present) {
            if (attempt != 0) reset()
            return outcome(verification, PopupStep.Skip("未见活动弹窗"))
        }

        // 弹窗刚命中、滞回还没确认（连续 2 次命中才进入该状态）：等下一轮——宁可晚 200ms，不拿未确认的结论点击
        if (!input.popupConfirmed) {
            return outcome(verification, PopupStep.Skip("弹窗刚命中、尚未确认（等下一轮）"))
        }

        // 上一枪确认没打成，且次数用尽 → 放弃（宁可漏关，不可错点）
        if (verification is PopupVerification.StillPresent && attempt >= maxAttempts) {
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
        // 演练：只出判定（日志写"本应点击"），不计数、不等验证——见类注释。
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

        /** requirements FR-01 失败处理：连续 3 次仍命中 → 放弃本次点击。 */
        const val DEFAULT_MAX_ATTEMPTS = 3
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

/** 上一枪的验证结论（写日志用，也是后续审计"动作是否生效"的依据）。 */
sealed interface PopupVerification {

    /** 本轮没有待验证的点击（没点过、或冻结轮）。 */
    data object None : PopupVerification

    /** 弹窗已消失，上一枪生效；[attempts] = 本次共下发了几枪。 */
    data class Closed(val attempts: Int) : PopupVerification

    /** 弹窗仍在，上一枪未生效；[attempts] = 目前已尝试几次。 */
    data class StillPresent(val attempts: Int) : PopupVerification
}

/** 本轮动作。 */
sealed interface PopupStep {

    /** 不动作，[note] 说明原因（写日志用；同一原因连续出现时调用方只记一次，避免刷屏）。 */
    data class Skip(val note: String) : PopupStep

    /** 下发一次点击（**是否真的下发由门禁决定**：演练 / 非前台 / 状态未知都会被拒并留痕）。 */
    data class Click(val request: ClickRequest, val attempt: Int) : PopupStep

    /** 连续 [attempts] 次未生效 → 放弃本次点击（FR-01 失败处理）。 */
    data class GiveUp(val attempts: Int) : PopupStep
}

/** 一轮决策的完整结果：既说明"上一枪打成没打成"，也说明"这一轮要不要开枪"。 */
data class PopupRoundOutcome(
    val verification: PopupVerification,
    val attempt: Int,
    val step: PopupStep,
)
