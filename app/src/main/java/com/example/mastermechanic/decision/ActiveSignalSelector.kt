package com.example.mastermechanic.decision

/**
 * 按状态启用信号子集（T1-10g，纯逻辑）：决定每轮**真正参与匹配**的信号名集合。
 *
 * 背景（用户口径）：实际运行时大多数场景同一时间只需搜索一个信号，只有「点击登录 → 进入大厅前」
 * 需要同时搜索新手引导 / 新手大厅 / 活动弹窗，其余信号按流程步骤在指定阶段搜索。
 *
 * 选择规则（返回 `null` 表示**全扫**）：
 * 1. 状态未知 → 全扫（未知是唯一能进入任何状态的入口，必须全扫才能确定状态）；
 * 2. 上一轮当前状态未命中（[probing]，疑似正在转移）→ 立即全扫，用于发现新状态；
 * 3. 每 [fallbackPeriod] 轮 → 全扫一次（防漏网，覆盖"子集配置不全"的极端情况）；
 * 4. 其余 → 当前状态的信号 ∪ 该状态的[附加信号][attached]。
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
     * 默认空：严格按「一个状态搜自己的信号」口径；需要同时搜多类时在此加一行即可。
     */
    private val attached: Map<UiState, Set<String>> = emptyMap(),
    /** 兜底全扫周期（轮）：≥1；[Int.MAX_VALUE] 表示不启用周期全扫。 */
    private val fallbackPeriod: Int = DEFAULT_FALLBACK_PERIOD,
) {

    init {
        require(fallbackPeriod >= 1) { "兜底全扫周期至少为 1：$fallbackPeriod" }
    }

    /**
     * 本轮要匹配的信号名集合；`null` = 全扫。
     *
     * @param state 当前状态（滞回结论）
     * @param probing 上一轮当前状态是否未命中（疑似转移）
     * @param round 轮序号（从 0 开始，仅前台轮计数）
     */
    fun select(state: UiState, probing: Boolean, round: Int): Set<String>? = when {
        state == UiState.UNKNOWN -> null
        probing -> null
        fallbackPeriod != Int.MAX_VALUE && round % fallbackPeriod == 0 -> null
        else -> {
            val base = signalsOf(state)
            val extra = attached[state].orEmpty()
            if (base.isEmpty() && extra.isEmpty()) null else base + extra
        }
    }

    companion object {

        /** 默认兜底全扫周期：每 10 轮一次（稳定档约 30s，与检测节奏同量级）。 */
        const val DEFAULT_FALLBACK_PERIOD = 10

        /**
         * 由标定产物的状态—信号规则构造：未知状态没有规则（拿到空集 → 全扫）。
         * 附加信号默认空（严格按用户口径：一个状态搜自己的信号）。
         */
        fun fromRules(
            rules: List<SignalStateMapping.Rule>,
            attached: Map<UiState, Set<String>> = emptyMap(),
            fallbackPeriod: Int = DEFAULT_FALLBACK_PERIOD,
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
