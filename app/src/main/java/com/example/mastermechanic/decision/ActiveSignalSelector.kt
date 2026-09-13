package com.example.mastermechanic.decision

/**
 * 按状态启用信号子集（T1-10g，纯逻辑）：决定每轮**真正参与匹配**的信号名集合。
 *
 * 背景（用户口径）：实际运行时大多数场景同一时间只需搜索一个信号；T1-13 起口径为
 * 「只搜**预期状态**自己的信号」（M2 流程编排把"本步预期集合"经 [attached] 注入）。
 *
 * 选择规则（返回 `null` 表示**全扫**）：
 * 1. 状态未知 → 全扫（未知是唯一能进入任何状态的入口，必须全扫才能确定状态）；
 * 2. 其余 → 当前状态的信号 ∪ 该状态的[附加信号][attached]。
 *
 * T1-13 的两处收敛（原 T1-10g 的"发现式"机制全部取消）：
 * - **取消转移探测全扫**（原 `probing` 参数）：未命中不再顺带发现其他状态——真实巡查由流程驱动，
 *   每一步都有明确预期，"发现新状态"不是识别层的职责；
 * - **取消周期兜底全扫**（原每 10 轮一次，[NO_FALLBACK] 为默认）：全扫只保留「无预期 / 状态未知」一种时刻。
 *
 * 语义约束（红线）：未进入子集的信号**不产生判定记录**，状态机不得把它当作「未命中」
 * （否则离开计数会被"没搜"而非"没命中"推高，见 FR-09 冻结的同类语义）。
 * 子集内信号的判定结果与全扫一致——匹配本身未被裁剪，只是跳过了不相关的信号。
 *
 * 不含任何设备绑定常量；信号名来自标定产物。
 */
class ActiveSignalSelector(
    /** 状态 → 该状态的信号名集合（来自标定产物的状态—信号规则）。 */
    private val signalsOf: (UiState) -> Set<String>,
    /**
     * 状态 → 除自身信号外还需一并搜索的附加信号（如某阶段可能同时出现弹窗 / 引导）。
     *
     * 默认空（严格按「一个状态搜自己的信号」口径）。T1-13 取消补扫后，本参数是**唯一**的
     * "某状态需要同时搜多个信号"入口，M2 由流程的预期集合注入（例：点击开始游戏后预期「大厅或弹窗」）。
     */
    private val attached: Map<UiState, Set<String>> = emptyMap(),
    /** 兜底全扫周期（轮）：≥1；[NO_FALLBACK] 表示不启用周期全扫（T1-13 起的默认口径）。 */
    private val fallbackPeriod: Int = NO_FALLBACK,
) {

    init {
        require(fallbackPeriod >= 1) { "兜底全扫周期至少为 1：$fallbackPeriod" }
    }

    /**
     * 本轮要匹配的信号名集合；`null` = 全扫。
     *
     * @param state 当前状态（滞回结论）
     * @param round 轮序号（从 0 开始，仅前台轮计数）
     */
    fun select(state: UiState, round: Int): Set<String>? = when {
        state == UiState.UNKNOWN -> null
        fallbackPeriod != NO_FALLBACK && round % fallbackPeriod == 0 -> null
        else -> {
            val base = signalsOf(state)
            val extra = attached[state].orEmpty()
            if (base.isEmpty() && extra.isEmpty()) null else base + extra
        }
    }

    companion object {

        /** 不启用周期兜底全扫（T1-13：全扫只保留「无预期 / 状态未知」一种时刻）。 */
        const val NO_FALLBACK = Int.MAX_VALUE

        /**
         * 由标定产物的状态—信号规则构造：未知状态没有规则（拿到空集 → 全扫）。
         * 附加信号默认空（严格按用户口径：一个状态搜自己的信号）。
         */
        fun fromRules(
            rules: List<SignalStateMapping.Rule>,
            attached: Map<UiState, Set<String>> = emptyMap(),
            fallbackPeriod: Int = NO_FALLBACK,
        ): ActiveSignalSelector {
            val byState = HashMap<UiState, Set<String>>()
            rules.forEach { rule ->
                byState[rule.state] = byState[rule.state].orEmpty() + rule.signalNames
            }
            return ActiveSignalSelector(
                signalsOf = { byState[it].orEmpty() },
                attached = attached,
                fallbackPeriod = fallbackPeriod,
            )
        }
    }
}
