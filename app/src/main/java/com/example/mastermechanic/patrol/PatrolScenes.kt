package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState

/**
 * 识别结论 ↔ 跑号场景（M4-T4-2）：把 M1/M2 已经跑通的识别能力接成跑号主流程能用的三样东西。
 *
 * 为什么单独一层：[PatrolFlow] 故意只认自己的枚举（不认识 `UiState`、识别、点击），
 * 它的单测因此不需要任何识别概念；而"哪块画面 = 哪个场景"是**接线**，放这里，只此一处。
 *
 * 三件事：
 * 1. [read] —— 当前画面能不能确定场景。**「弹窗遮挡」与「认不出来」必须分开**：
 *    前者等 FR-01 关掉弹窗就恢复正常，后者是用户该被叫来看一眼（处理方式完全不同，合在一起就会误中止）；
 * 2. [expectedState] —— 每一步"做完之后应该看到什么"（FR-04 表格的「进入下一步的验证」列，逐条对齐）；
 * 3. [check] —— 拿一次识别结论去对这一步的验证（通过 / 还在等 + 原因）。
 *
 * **本层不点击、不管重试**：点哪个锚点在 [PatrolAnchors.actions]，重试上限在 [PatrolFlow.fail]。
 */
object PatrolScenes {

    /** 一次场景读取的结论。 */
    sealed interface Reading {

        /** 画面明确：[scene] 就是当前场景。 */
        data class Ready(val scene: PatrolFlow.Scene) : Reading

        /**
         * 被遮挡屏挡住（活动弹窗 / 新手引导 / 新手大厅）：**现在判不了场景，但这不是错误**。
         * 这类屏是任何阶段的最高优先级，等自动关闭把它点掉再读一次即可（不中止、不点击）。
         */
        data class Blocked(val reason: String) : Reading

        /** 认不出来（连续未命中 → 未知）：不操作，提示用户（红线 2 / FR-05）。 */
        data object Unrecognized : Reading
    }

    /** 把识别结论读成场景。 */
    fun read(state: UiState): Reading = when (state) {
        UiState.LAUNCH_PAGE -> Reading.Ready(PatrolFlow.Scene.BOOT)
        UiState.SERVER_SELECT -> Reading.Ready(PatrolFlow.Scene.SERVER_LIST)
        UiState.HALL -> Reading.Ready(PatrolFlow.Scene.LOBBY)
        UiState.FARM -> Reading.Ready(PatrolFlow.Scene.FARM)
        // 2026-09-21 合并：好友的农场与自己的农场**是同一个场景**，都读成 Scene.FARM。
        // 2026-09-23：连"这是谁的农场"也不再判定 —— 两个农场的后续路径完全一样（用户拍板）
        UiState.FRIEND_FARM -> Reading.Ready(PatrolFlow.Scene.FARM)
        // 好友列表 / 设置页 / 退出登录确认框 / 返回场景确认框：画面清楚，但都不在 FR-04 的起点表里
        UiState.FRIEND_LIST, UiState.HALL_SETTINGS, UiState.LOGOUT_CONFIRM,
        UiState.SCENE_RETURN_CONFIRM,
        -> Reading.Ready(PatrolFlow.Scene.NOT_A_START)

        // 遮挡屏盖住画面时，底下是什么界面**看不见** —— 既不能当场景用，也不能算"认不出来"。
        // 三屏口径一致：交给自动关闭（活动弹窗 = FR-01；新手两页 = FR-02）点掉，再读一次。
        UiState.ACTIVITY_POPUP -> Reading.Blocked("活动弹窗挡住了画面（先由 FR-01 自动关闭点掉）")
        UiState.TUTORIAL_GUIDE -> Reading.Blocked(
            "新手引导挡住了画面（只在新手号出现；先由 FR-02 自动关闭点掉）",
        )
        UiState.TUTORIAL_HALL -> Reading.Blocked("新手大厅不是可用大厅（先由 FR-02 自动关闭退出新手大厅）")
        UiState.UNKNOWN -> Reading.Unrecognized
    }

    /**
     * **能当起点**的画面（起点表的输入侧）。
     *
     * ## 为什么需要它（2026-09-20 真机暴露的死锁）
     *
     * 待命期（流程没在跑）识别层只搜**活动弹窗**那几条标志（T2-5 为省成本定的口径）→
     * 识别结论恒为「未知」→ 而"点菜单那一刻起点成不成立"**必须**先知道画面 →
     * 于是**永远启动不了**：用户点「只拜访」只会看到"起点不成立（当前画面「未知」）"。
     *
     * 所以待命期的期望集合把**这几屏的标志**一并搜（见 `PatrolDrivenExpectedSignals.standbyStates`）：
     * 只有它们能启动流程，其余画面（好友列表 / 设置页 / 确认框）搜了也没用。
     *
     * 与 [read] 的关系由单测钉住：**本集合 = 所有读得出 `Ready` 且在起点表里有出路的画面**
     * （防止以后加了新状态、忘了同步这里，死锁复发）。
     */
    val startStates: Set<UiState> = setOf(
        UiState.LAUNCH_PAGE,
        UiState.SERVER_SELECT,
        UiState.HALL,
        UiState.FARM,
        UiState.FRIEND_FARM,
    )

    /**
     * 这一步「进入下一步的验证」要看哪个画面；null = **该步没有验证目标**。
     *
     * null 只有两种情形：第 1 步（起点确认由 [PatrolFlow.firstStep] 承担，不靠"看到什么"）、
     * 第 10 步（FR-04 明写「无动作，无需验证」——到达好友的农场即完成，不做任何返回动作）。
     *
     * 一步有多次点击时（如第 3 步的退出登录），这里是**最后一次点击之后**要看到的画面；
     * 中间几次的验证目标在 [PatrolAnchors.Action.expect] 里，两者由单测钉住一致。
     */
    fun expectedState(step: PatrolFlow.Step): UiState? = when (step) {
        PatrolFlow.Step.CHECK_START, PatrolFlow.Step.DONE -> null
        PatrolFlow.Step.LEAVE_FARM -> UiState.HALL
        PatrolFlow.Step.LOGOUT -> UiState.LAUNCH_PAGE
        PatrolFlow.Step.SWITCH_SERVER -> UiState.SERVER_SELECT
        // 第 5 步的验证是"**离开**服务器列表、回到启动页"（换区成功后是登录页，不是选服页）
        PatrolFlow.Step.PICK_SERVER -> UiState.LAUNCH_PAGE
        PatrolFlow.Step.LOGIN -> UiState.HALL
        PatrolFlow.Step.ENTER_FARM -> UiState.FARM
        PatrolFlow.Step.OPEN_FRIENDS -> UiState.FRIEND_LIST
        // 2026-09-21 合并：拜访后验证"到了农场"（FRIEND_FARM 由 [isSameScene] 等价通过）。
        // 2026-09-23：不再叠"是不是**指定**好友的农场"那一层（农场归属判定整体删除；
        // 点的是哪一行由第 9 步的名称定位保证，见红线 3）
        //
        // ⚠ **已知边界（2026-10-01 用户解释，别当缺陷修）**：我们要求"点完拜访要看到「农场」"，
        // 但**若用户此刻已经站在目标好友的农场里**（比如他自己先进去了，再从那里点「只拜访」），
        // 游戏会判定"已在该农场"⇒ **不做场景切换**，好友面板就停在原样 ⇒ 这一步永远等不到「农场」
        // ⇒ 到预算耗尽**如实中止**（真机实录 2026-10-01 `08:08`：点拜访后一直判「好友列表」，15 秒中止；
        // 当时的定位与点击都正常、开火前复眼也是 ✓）。
        // ⇒ 这是**游戏行为**、不是本程序的定位/点击问题；**不改判据**（红线 2「验证不通过就停」）。
        // 规避：从**大厅 / 自己的农场**出发跑「只拜访」，别停在目标好友的农场里发起。
        PatrolFlow.Step.VISIT_FRIEND -> UiState.FARM
    }

    /** 一次验证的结论。 */
    sealed interface Check {

        /** 看到预期画面了：可以进入下一步（红线 5：动作后必须验证）。 */
        data object Passed : Check

        /**
         * 还没到预期画面：**保持不动、继续看**（不补点、不跳步）。
         *
         * 含"弹窗遮挡"与"认不出来"两种：它们都不是"这步失败了"，只是现在还不能确认。
         * 什么时候算失败由调用方的等待上限决定（超时 → [PatrolFlow.fail]）。
         */
        data class Waiting(val detail: String) : Check
    }

    /**
     * 两个状态是否是**同一个场景**（2026-09-21 农场合并后才有这个概念）。
     *
     * `FARM` 与 `FRIEND_FARM` 是同一个场景（返回箭头、好友入口样式一致，用户已确认）⇒ 验证时**等价**。
     * 旧枚举值 `FRIEND_FARM` 保留只是为了**兼容既有产物**（里面的 state= / anchor= 规则仍写着它），
     * 新建的逻辑一律走 `FARM`。
     */
    fun isSameScene(a: UiState, b: UiState): Boolean =
        a == b || (a == UiState.FARM && b == UiState.FRIEND_FARM) ||
            (a == UiState.FRIEND_FARM && b == UiState.FARM)

    /** 「登录游戏」这一步的步号（见 [check] 里那条"出现遮挡屏 ⇒ 已进游戏"的**提前判完成**）。 */
    private const val LOGIN_STEP = 6

    /**
     * 拿当前的识别结论去对 [step] 的验证。
     *
     * [anchorLabel] = **锚点名的中文说法**（2026-10-01 用户口径）：等待原因里出现锚点名时用它，
     * 别把 `hall_settings` 这种英文契约名甩给用户看。默认恒等 ⇒ 既有调用点与单测行为不变。
     */
    fun check(
        step: PatrolFlow.Step,
        state: UiState,
        anchorLabel: (String) -> String = { it },
    ): Check {
        val expected = expectedState(step) ?: return Check.Passed
        // **第 6 步（登录游戏）：出现"进游戏后才会有的界面"即判完成**（2026-09-30 用户给的领域知识）。
        //
        // 为什么成立：新手引导 / 新手大厅 / 活动弹窗**只会在进入游戏之后出现**
        //（用户口径：新手引导出现得最早，比活动弹窗还早）⇒ 它们出现 = **已经进游戏了**。
        //
        // ⚠ 但它们是"**证据**"、不是"**关卡**"：新手引导只在新号出现、活动弹窗看活动 ⇒ **都不必然出现**
        // ⇒ 所以只是**提前**判完成（下面那条"等 expectedState"的判据**原样保留**兜底，一个都不出现时照旧等）。
        if (step.number == LOGIN_STEP && state in UiState.guardedOverlays) return Check.Passed
        // 两个农场合一：任一个都算"到了农场"（是谁的由 FarmIdentity 判，不在这里区分）
        if (isSameScene(state, expected)) return Check.Passed
        // 这一步要走好几次时（退出登录），当前画面可能正是"该做下一击"的地方 —— 日志里说清楚
        val pending = PatrolAnchors.actionFor(step, state)
        val detail = if (pending != null) {
            "第 ${step.number} 步还没做完：现在可以点「${anchorLabel(pending.anchor)}」，" +
                "做完应该看到「${pending.expect.label}」"
        } else {
            "第 ${step.number} 步：等「${expected.label}」，现在${describe(state)}"
        }
        return Check.Waiting(detail)
    }

    /**
     * **这一轮该搜哪些画面**（2026-10-01，用户口径："`hall_settings_e1` 肯定是在 `hall_settings` 找到后才出现的，
     * 这两个不需要每轮都搜吧"）。
     *
     * ## 为什么原来要整步全搜、现在为什么能收窄
     *
     * 原来采集层每轮把**这一步链条上全部画面**塞进期望集合（第 3 步 = 大厅 / 设置页 / 确认框 / 启动页 ⇒
     * 加上遮挡屏一共 7 条，约 42M 乘加/轮）。可链条是**有序**的：站在大厅时，"设置页 / 确认框 / 启动页"
     * 的标志本轮不可能命中 —— 搜了纯属白花时间。
     *
     * ## 判据（两条，缺一不可）
     *
     * - **有结论**（[currentState] ≠ 未知）⇒ 只搜 `{当前屏, 当前屏的下一屏}`：
     *   - 留着**当前屏**：点击可能**没生效**（被门禁拒 / 点歪），画面那一刻还在原处；
     *   - 加上**下一屏**：这一击成功时，下一轮要立刻认得出新画面。
     * - **认不出来** ⇒ **退回整步集合**（[actionStates] + [expectedState]）。
     *   这是**自愈阀**：收窄后若真实画面不在集合里（点了两屏 / 被弹回上一屏），下一轮就会"认不出" ⇒
     *   本函数立刻放宽，代价只有 1 轮（≈200ms）—— 而"永远收窄"会退化成白等超时。
     *
     * ## 不在本函数的范围
     *
     * **遮挡屏（弹窗 / 新手两屏）永远由守卫集合单独提供**（`PopupWatchExpectedSignals`），与本函数无关 ⇒
     * FR-01 / FR-02 的可见性一分不减（这条是红线，别顺手挪进来）。
     */
    fun watchStatesFor(step: PatrolFlow.Step, currentState: UiState): Set<UiState> {
        val chain = actionStates(step) + listOfNotNull(expectedState(step))
        // 该步没有链条（第 1 步「确认起点」/ 第 10 步「完成」）⇒ **空集** = 沿用"守护集合"口径
        // （`PatrolDrivenExpectedSignals` 把空 watching 当作待命期：搜守卫弹窗 + 起点画面）。
        // 这里**不能**返回 `{当前屏}`：那会把起点画面收成一个，用户在别处点菜单就认不出起点了。
        if (chain.isEmpty()) return emptySet()
        if (currentState == UiState.UNKNOWN) return chain.toSet()
        // 当前屏可能**不在本步链条上**（用户自己走到别的界面去了）—— 仍然要搜它：
        // 否则下一轮必然"认不出"，菜单也说不清"你现在停在哪一屏"。
        return setOfNotNull(currentState, PatrolAnchors.actionFor(step, currentState)?.expect)
    }

    /** 一句话说清"现在看到的是什么"——等待原因里必须能看出是遮挡、认不出，还是走错了界面。 */
    fun describe(state: UiState): String = when (state) {
        UiState.ACTIVITY_POPUP -> "是活动弹窗（先由 FR-01 自动关闭点掉）"
        UiState.TUTORIAL_GUIDE -> "是新手引导（只在新手号出现；先由 FR-02 自动关闭点掉）"
        UiState.TUTORIAL_HALL -> "是新手大厅（先由 FR-02 自动关闭退出新手大厅）"
        UiState.UNKNOWN -> "认不出来"
        else -> "是「${state.label}」"
    }

    /**
     * 标定页「所属界面」清单的**展示顺序**（2026-09-19 用户口径：按流程顺序排）。
     *
     * 为什么按流程排：标定的人**正站在某个画面上**，顺着游戏流程一格格往前走，清单里下一项就在手边；
     * "最常用优先"会把大厅 / 农场拽到前面、打乱这条路线，反而每次都要重新找。
     *
     * **三个"异常屏"置首**（新手引导 / 新手大厅 / 活动弹窗，按**出现顺序**）、与流程站用分隔线隔开：
     * 它们不是流程中的任何一站，而是"随时可能出现"的异常，也最容易被漏标 —— 放在横滑行的最可见端。
     * （2026-09-29 用户口径："标定时新手两屏也排到最前面"；此前只有活动弹窗置首。）
     *
     * ⚠️ **这与 [UiState] 的枚举声明顺序是两件事**：声明顺序 = 「多状态同时命中时的候选优先级」，
     * 是功能、一个字都不能动；这里只决定**渲染顺序**。两者都由单测钉着。
     */
    val calibrationOrder: List<UiState> = listOf(
        UiState.TUTORIAL_GUIDE,
        UiState.TUTORIAL_HALL,
        UiState.ACTIVITY_POPUP,
        UiState.LAUNCH_PAGE,
        UiState.SERVER_SELECT,
        UiState.HALL,
        UiState.HALL_SETTINGS,
        UiState.LOGOUT_CONFIRM,
        UiState.FARM,
        UiState.SCENE_RETURN_CONFIRM,
        UiState.FRIEND_LIST,
        // 「好友的农场」**不再列入**（2026-09-21）：两个农场合一后它就是农场，
        // 再给一个独立选项只会让人以为"还有第二个农场要标"。旧产物里写着它的记录照旧能读
        // （产物清单把它们并进「农场」那组，见 CalibrationArtifactGroups）。
    )

    /**
     * 这一步的**动作**要在哪些已确认状态下才允许下发（锚点只在这些状态里找）。
     *
     * 与 [expectedState] 是两回事：那条是"做完之后看到什么"，这条是"做之前得先看清什么"。
     * 例：第 7 步「进入农场」要先在大厅确认、点大厅里的农场入口，做完之后看到农场。
     *
     * 锚点步骤直接取 [PatrolAnchors.actions]（不重不漏，由单测钉着）；**按名称定位的两步**
     * 没有锚点，但同样有"在哪个画面动手"的问题 → 显式给出列表所在的那个状态。
     * 空列表 = 该步没有任何动作（第 1 / 10 步）。
     */
    fun actionStates(step: PatrolFlow.Step): List<UiState> {
        val anchored = PatrolAnchors.actions(step).map { it.state }
        if (anchored.isNotEmpty()) return anchored.distinct()
        return when (step) {
            // 第 5 / 9 步：在列表里按名称挑一个 —— 动手前必须确认的就是"列表开着"
            PatrolFlow.Step.PICK_SERVER -> listOf(UiState.SERVER_SELECT)
            PatrolFlow.Step.VISIT_FRIEND -> listOf(UiState.FRIEND_LIST)
            else -> emptyList()
        }
    }
}
