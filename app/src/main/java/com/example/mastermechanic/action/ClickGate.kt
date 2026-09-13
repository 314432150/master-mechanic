package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState

/**
 * 点击门禁与节流（T2-3c，纯逻辑，ADR-002）：红线 1（非前台 / 状态未知 → 零点击）与
 * 红线 6（相邻点击间隔 ≥ 300ms）的**唯一实现点**——不依赖任何调用方自觉。
 *
 * 判定顺序（固定，审计里能看出"最先拦在哪一条"）：
 * 1. 演练模式 → 拒绝；
 * 2. 游戏不在前台 → 拒绝；
 * 3. 状态未知 → 拒绝；
 * 4. 已有手势在途（串行）→ 拒绝；
 * 5. 距上次手势**完成**不足 [spacingMs] → 拒绝（该口径比"开始到开始"更保守，兼容 NFR-06 的任何解释）。
 *
 * 调用约定：[decide] 只读不写；真正下发前后由 [onGestureStarted] / [onGestureFinished] 推进计时。
 * 时间一律由外部注入（单调时钟），本类不读系统时间——便于单测，也避免墙钟跳变影响间隔。
 */
class ClickGate(private val spacingMs: Long = MIN_SPACING_MS) {

    init {
        require(spacingMs >= MIN_SPACING_MS) { "间隔不得低于 $MIN_SPACING_MS ms（红线 6）：$spacingMs" }
    }

    /** 上一次手势**完成**的时刻（未发生过 → null）。 */
    var lastFinishedMs: Long? = null
        private set

    /** 在途手势的判定记录 ID（null = 空闲）。 */
    var inFlightDecisionId: String? = null
        private set

    val isIdle: Boolean get() = inFlightDecisionId == null

    /** 判定是否允许下发（无副作用，可重复调用）。 */
    fun decide(environment: ClickEnvironment, nowMs: Long): ClickVerdict = when {
        environment.mode != ClickMode.LIVE -> ClickVerdict(false, ClickDenyReason.DRILL_MODE)
        !environment.gameForeground -> ClickVerdict(false, ClickDenyReason.NOT_FOREGROUND)
        environment.state == UiState.UNKNOWN -> ClickVerdict(false, ClickDenyReason.STATE_UNKNOWN)
        inFlightDecisionId != null -> ClickVerdict(false, ClickDenyReason.IN_FLIGHT)
        !spacingSatisfied(nowMs) -> ClickVerdict(false, ClickDenyReason.TOO_SOON)
        else -> ClickVerdict(true)
    }

    /** 距上次手势完成还需等待的毫秒数（0 = 可以立即开始）。 */
    fun remainingWaitMs(nowMs: Long): Long {
        val last = lastFinishedMs ?: return 0L
        return (last + spacingMs - nowMs).coerceAtLeast(0L)
    }

    /** 手势开始：标记在途。 */
    fun onGestureStarted(decisionId: String) {
        inFlightDecisionId = decisionId
    }

    /**
     * 手势结束（完成或取消）：解除在途并把间隔起算点推到此刻。
     * 取消同样要计入间隔——否则"被系统打断"会变成绕过 300ms 的缺口。
     */
    fun onGestureFinished(nowMs: Long) {
        inFlightDecisionId = null
        lastFinishedMs = nowMs
    }

    private fun spacingSatisfied(nowMs: Long): Boolean = remainingWaitMs(nowMs) == 0L

    companion object {
        /** 红线 6 的下限：相邻点击（手势完成 → 下次手势开始）间隔。 */
        const val MIN_SPACING_MS = 300L
    }
}
