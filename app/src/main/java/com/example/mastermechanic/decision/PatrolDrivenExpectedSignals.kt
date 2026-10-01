package com.example.mastermechanic.decision

/**
 * 跑号期间的期望集合（M4-T4-4b）：**弹窗集合 ∪ 当前步骤要认的画面标志**。
 *
 * ## 为什么跑号时要换期望集合
 *
 * 生产默认只搜活动弹窗（`PopupWatchExpectedSignals`，T2-5）——那时程序"什么流程都没在跑"，
 * 认识弹窗就够了。可一旦跑号开始，**每一步都要靠"认出画面"来验证**（FR-04 硬性要求 1）：
 * 「退出农场」要认出大厅、「登录游戏」要认出大厅……不搜这些画面的标志，就永远等不到验证。
 *
 * 所以跑号激活时，每轮搜：
 *
 * - **弹窗那一组**（FR-01 恒定要搜：弹窗会随时冒出来打断步骤，不能因为跑号就看不见它）；
 * - **当前步骤要认的画面**（[watch] 由流程层每轮设置 = 动作所在状态 ∪ 验证目标，
 *   见 `PatrolScenes.actionStates` 与 `PatrolScenes.expectedState`）。
 *
 * ## 为什么不直接外扩成全集
 *
 * 全集会退化成 T2-1 明确取消的「全扫」：轮耗时回到 ≈490ms，且把"没标定"的信号也算进搜索量。
 * 这里刻意只加**当前这一步真正需要的那几个状态**。
 *
 * ## 空集的含义
 *
 * 流程没在跑 → [watch] 为空 → 等价守护集合（不额外搜）。
 */
class PatrolDrivenExpectedSignals(
    /** 守护集合（弹窗）：跑号与否它都必须在。 */
    private val popup: ExpectedSignals,
    /** 产物里的「状态 → 标志名」映射（由 `CalibrationData.stateRules` 得到）。 */
    private val rules: List<SignalStateMapping.Rule>,
    /**
     * **待命期**（[watching] 为空 = 流程没在跑）额外要搜的画面：**能当起点的那些**
     * （生产传 `PatrolScenes.startStates`，本包不认识它，所以由调用方注入）。
     *
     * ## 为什么待命期也得搜（2026-09-20 真机暴露的死锁）
     *
     * 待命期原本只有弹窗集合 → 识别结论恒为「未知」；而"用户点菜单那一刻，起点成不成立"
     * 必须先知道画面（`PatrolFlow.firstStep`）→ **永远启动不了**：用户点「只拜访」只会得到
     * "起点不成立（当前画面「未知」）"，界面上还没人告诉他为什么。
     *
     * 只加**起点画面**而不是全集：其余画面（好友列表 / 设置页 / 确认框）搜了也启动不了流程；
     * 待命期的成本仍是"弹窗 + 5 屏标志"，没有退回 T2-1 取消的全扫。
     */
    private val standbyStates: Set<UiState> = emptySet(),
) : ExpectedSignals {

    /**
     * 当前步骤要认的画面（流程层每轮设置；**只在帧线程调用**，与 [expected] 同线程）。
     *
     * 传空集 = 回到守护口径（流程没在跑 / 这一步不需要认画面）。
     */
    @Volatile
    private var watching: Set<UiState> = emptySet()

    fun watch(states: Collection<UiState>) {
        watching = states.toSet()
    }

    /** 当前在看哪些画面（日志 / 排障用）。 */
    val watched: Set<UiState> get() = watching

    override fun expected(known: Set<String>): Set<String> {
        // 没在跑 → 搜"能当起点的画面"（否则识别结论恒「未知」，起点判定永远不成立）；
        // 在跑 → 只搜当前步骤要认的画面（起点已定，不必再看别的）
        val states = if (watching.isEmpty()) standbyStates else watching
        val stepNames = states
            .mapNotNull { state -> rules.firstOrNull { it.state == state }?.signalNames }
            .flatten()
            .toSet()
        // 弹窗在前：并集的顺序不影响识别结果，但日志里"先弹窗、后当前步骤"读起来更自然
        return popup.expected(known) + stepNames
    }
}
