package com.example.mastermechanic.decision

/**
 * 状态变化事件：仅在实际发生转移的那一轮产生（[UiStateMachine.update] 的返回值）。
 *
 * [reason] 面向日志与审计（如「连续 2 次命中（进入）」）。
 */
data class UiStateTransition(val from: UiState, val to: UiState, val reason: String)

/**
 * 界面状态机（T1-4，§2.1 / §2.2，纯逻辑：不依赖安卓框架，JVM 可测）。
 *
 * 滞回规则（§2.2、glossary「滞回」）：
 * - 进入：某状态连续 [ENTER_STREAK] 轮为当轮最高优先级命中 → 进入该状态；
 *   进入条件满足即切换（进入与离开是两个独立条件，先满足者生效，
 *   不等待旧状态的离开计数凑满）；
 * - 离开：当前状态连续 [LEAVE_STREAK] 轮未命中，且本轮无候选满足进入条件 → 转「未知」
 *   （「未知」没有标志；离开后未确认新状态时的行为与「未知」一致：不操作、提示用户）；
 * - 未知：连续 3 轮未命中任何标志的自然结果（当前为「未知」时不再累计离开计数）；
 * - 非前台轮（[foreground] = false，FR-09）：整体冻结——不消耗任何连续计数、
 *   不产生事件；该轮如同不存在（冻结前后的计数直接衔接）。
 *
 * 输入为「命中状态集合」（由 [SignalStateMapping] 从判定记录解析），本类不感知
 * 画面数据与判定细节；多状态同时命中时取 [UiState] 声明顺序最靠前（优先级最高）者为候选。
 */
class UiStateMachine(initial: UiState = UiState.UNKNOWN) {

    /** 当前状态（滞回后的结论）。 */
    var current: UiState = initial
        private set

    /** 当轮最高优先级命中状态及其连续轮数。 */
    private var candidate: UiState? = null
    private var candidateStreak = 0

    /** 当前状态连续未命中轮数（当前为「未知」时不累计）。 */
    private var currentMissStreak = 0

    /**
     * 处理一轮判定结果，返回状态变化事件；状态未变化返回 null。
     *
     * @param hits 本轮命中的状态集合（可为空集，不得包含「未知」）
     * @param foreground 本轮是否处于前台；false 时整体冻结（FR-09）
     */
    fun update(hits: Set<UiState>, foreground: Boolean): UiStateTransition? {
        if (!foreground) return null

        val nextCandidate = hits.filter { it.isCandidate }.minByOrNull { it.ordinal }
        if (nextCandidate != null && nextCandidate == candidate) {
            candidateStreak++
        } else {
            candidate = nextCandidate
            candidateStreak = if (nextCandidate == null) 0 else 1
        }

        currentMissStreak = if (current == UiState.UNKNOWN || current in hits) {
            0
        } else {
            currentMissStreak + 1
        }

        val entering = candidate
        if (entering != null && entering != current && candidateStreak >= ENTER_STREAK) {
            return transition(entering, "连续 $ENTER_STREAK 次命中（进入）")
        }
        if (current != UiState.UNKNOWN && currentMissStreak >= LEAVE_STREAK) {
            return transition(UiState.UNKNOWN, "连续 $LEAVE_STREAK 次未命中（离开）")
        }
        return null
    }

    private fun transition(to: UiState, reason: String): UiStateTransition {
        val from = current
        current = to
        currentMissStreak = 0
        return UiStateTransition(from, to, reason)
    }

    companion object {

        /** §2.2：进入需连续 2 次命中。 */
        const val ENTER_STREAK = 2

        /** §2.2：离开需连续 3 次未命中。 */
        const val LEAVE_STREAK = 3
    }
}
