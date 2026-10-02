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
 *
 * ## 这个集合里**只有界面标志，永远没有锚点**（2026-10-02 用户复述并确认的口径）
 *
 * 用户原话："同时搜的信号应该去除锚点，只要保留标志，因为**对应的锚点必然在标志出现后才出现**" ——
 * 这条**结构上已经成立**，本类只往集合里放 [SignalStateMapping.Rule.signalNames]（标志名），
 * 锚点名（`Rule.anchorNames`）从任何路径都进不来：
 * - 锚点由 [AnchorLocator] **按需**定位，且只在**状态确认之后**才定位（未确认就定位 = 用未确认的
 *   画面找点击点，红线 7）；关闭闭环那颗「定位不到就不点」也是同一句话。
 * - 真机侧的对照证据（`mm-log-20261002.txt`）：弹窗期每轮「本轮搜索集合变化: activity_popup_e1、
 *   activity_popup_e2、farm_friends、hall_settings、launch_login、server_select_e1、tutorial_guide_e1、
 *   tutorial_hall_e1」—— 8 条**全是标志**（产物里 `hall_settings` 有标志与锚点两条记录，搜的是**标志**那条）。
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

    /**
     * **上一轮命中里有没有遮挡屏**（2026-10-02 用户提速要求："弹窗在屏时一轮为什么要搜 8 个信号这么多"）。
     *
     * 只由 [onRound] 写（帧线程，与 [expected] 同线程 ⇒ 用 `@Volatile` 保险）。
     */
    @Volatile
    private var overlayOnScreen: Boolean = false

    /**
     * 上一轮**透过弹窗还看得见**的那些画面（遮挡屏之外的那些命中状态）。
     *
     * 为什么要留它们：弹窗盖住屏幕时，**状态机仍要能立刻确认"弹窗没了、底下是什么"** ——
     * 而确认状态的那条标志必须**在搜索集合里**（不搜就没有记录、没有记录就不能确认）。
     * 真机 23:21:00 那轮「命中：活动弹窗、大厅」就是它：大厅的标志（右上角设置入口）露在弹窗外，
     * 弹窗一关就是它让状态**当轮**就能回落，不必等下一轮重新搜。
     */
    @Volatile
    private var visibleBehindOverlay: Set<UiState> = emptySet()

    override fun onRound(hits: Set<UiState>) {
        overlayOnScreen = hits.any { it in UiState.guardedOverlays }
        visibleBehindOverlay = hits.filter { it !in UiState.guardedOverlays }.toSet()
    }

    override fun expected(known: Set<String>): Set<String> {
        val popupNames = popup.expected(known)

        // **遮挡屏在屏 ⇒ 流程预期 / 待命起点那一组本轮不搜**（2026-10-02 用户提速）。
        //
        // ## 为什么可以收窄（安全依据，不是"看起来没用"）
        //
        // 收窄期间保留的是「弹窗标志 ∪ 上一轮还看得见的那些画面的标志」，因此：
        // - **状态判定不受影响**：要确认的面（弹窗自己 + 底下还露着的那些）全都在集合里；
        // - **点击门禁不受影响**：门禁要求"状态已知"，而弹窗在屏时状态由**弹窗自己的标志**确认 ✓；
        // - **跑号步骤判定不受影响**：弹窗盖着屏幕时，流程预期的那些画面（启动页 / 选服页 / 农场…）
        //   本来就**看不见**，搜了必然不命中 ⇒ 纯浪费（真机：弹窗期一轮 8 条、识别 P95 186ms）。
        //
        // ## 为什么不能更狠（把"底下那些"也一起砍掉）
        //
        // 砍掉的话，弹窗一关状态会先掉「未知」再重新确认（滞回要 2 次命中 ⇒ 约 +0.3~0.5 秒），
        // 而**连续弹窗**（真机实测一串 3 个，间隔 0.8 秒）每多一个都吃这个延迟 ⇒ 反而更慢。
        // ⇒ 只砍"看不见的那些"，这是这份改动里**唯一**能省又不会反噬的地方。
        if (overlayOnScreen) {
            val behind = visibleBehindOverlay
                .mapNotNull { state -> rules.firstOrNull { it.state == state }?.signalNames }
                .flatten()
                .toSet()
            return popupNames + behind
        }

        // 没在跑 → 搜"能当起点的画面"（否则识别结论恒「未知」，起点判定永远不成立）；
        // 在跑 → 只搜当前步骤要认的画面（起点已定，不必再看别的）
        val states = if (watching.isEmpty()) standbyStates else watching
        val stepNames = states
            .mapNotNull { state -> rules.firstOrNull { it.state == state }?.signalNames }
            .flatten()
            .toSet()
        // 弹窗在前：并集的顺序不影响识别结果，但日志里"先弹窗、后当前步骤"读起来更自然
        return popupNames + stepNames
    }
}
