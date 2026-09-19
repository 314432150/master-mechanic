package com.example.mastermechanic.patrol

/**
 * FR-04「跑号主流程」的**纯逻辑状态机**（M4-T4-1）。
 *
 * 之所以先做纯逻辑：这张表里全是"判定"——起点算哪一步、这一步属不属于当前区间、验证不过要不要停、
 * 重试几次放弃 —— 而**真正点屏幕**那部分（T4-2 识别 / T4-3 定位 / T4-4 注入）依赖真机。
 * 把判定先钉住，后面三步就只剩"把识别结果喂进来、把点击发出去"。
 *
 * 三条硬口径（FR-04「流程中的硬性要求」）：
 * - **每步都要验证**，验证不过就停（不连续盲点）；单步重试**不超过 3 次**，超过即中止；
 * - **中止后不自动重试整条流程**，只能由用户决定「继续」或「跳过」（FR-05）；
 * - 相邻点击间隔 **≥300ms**（NFR-06），间隔由调用方按 [CLICK_GAP_MS] 节流。
 */
object PatrolFlow {

    /** 第 1 步要认出来的画面（识别由 T4-2 提供，本文件只认枚举）。 */
    enum class Scene {
        /** 启动页（登录前）。 */
        BOOT,

        /** 选服页（换区后的服务器列表）。 */
        SERVER_LIST,

        /** 游戏大厅。 */
        LOBBY,

        /** 自己的农场。 */
        FARM,

        /** 好友的农场。 */
        FRIEND_FARM,

        /** 认不出来 —— **一发点击都不发**，直接中止。 */
        UNKNOWN,
    }

    /** FR-04 的三种执行区间（由菜单项决定用哪一种）。 */
    enum class Range {
        /** 只换号：起点所在步 → 第 6 步（登录进大厅）。 */
        SWITCH_ONLY,

        /** 只拜访：第 7 步 → 第 10 步（跳过全部换号步骤）。 */
        VISIT_ONLY,

        /** 换号 + 拜访：起点所在步 → 第 10 步（「拜访（预设）」用它）。 */
        SWITCH_AND_VISIT,
    }

    /** FR-04 的步骤。编号与需求表**逐条对齐**，日志与排障才能直接对号入座。 */
    enum class Step(val number: Int) {
        /** 1 起始确认。 */
        CHECK_START(1),

        /** 2 退出农场（仅起点为农场 / 好友的农场时）。 */
        LEAVE_FARM(2),

        /** 3 退出登录。 */
        LOGOUT(3),

        /** 4 换区。 */
        SWITCH_SERVER(4),

        /** 5 选择区服（按名称精确定位，找不到即中止）。 */
        PICK_SERVER(5),

        /** 6 登录游戏（其间按 FR-01 关闭弹窗，可能 0~多个）。 */
        LOGIN(6),

        /** 7 进入农场。 */
        ENTER_FARM(7),

        /** 8 打开好友列表。 */
        OPEN_FRIENDS(8),

        /** 9 拜访好友（按名称精确定位）。 */
        VISIT_FRIEND(9),

        /** 10 本项完成：**停在好友的农场**，不做任何返回动作。 */
        DONE(10),
    }

    /** 一次执行的结局。 */
    enum class Outcome {
        RUNNING,
        /** 跑到区间终点（成功）。 */
        FINISHED,
        /** 中止：验证不过 + 重试超限，或者起点认不出来。 */
        FAILED,
    }

    /**
     * 一次执行的进度。
     *
     * @param retries 当前步骤已经失败了几回（成功进入下一步时清零）。
     * @param reason 失败原因（给用户看的，挂在悬浮窗上 —— 不静默）。
     */
    data class State(
        val range: Range,
        val step: Step,
        val retries: Int = 0,
        val outcome: Outcome = Outcome.RUNNING,
        val reason: String? = null,
    ) {
        val isRunning: Boolean get() = outcome == Outcome.RUNNING
    }

    /** 单步重试上限：失败到第 3 次即中止（FR-04 硬性要求 5）。 */
    const val MAX_RETRIES = 3

    /** 相邻点击最小间隔（FR-04 硬性要求 8 / NFR-06）。 */
    const val CLICK_GAP_MS = 300L

    /** 这个步骤属不属于该区间（区间外的步骤直接跳过，不执行也不验证）。 */
    fun inRange(step: Step, range: Range): Boolean = when (range) {
        Range.SWITCH_ONLY -> step.number in 1..6
        Range.VISIT_ONLY -> step.number in 7..10
        Range.SWITCH_AND_VISIT -> true
    }

    /** 区间的终点步：到这步（验证通过后）就算完成。 */
    fun lastStep(range: Range): Step = when (range) {
        Range.SWITCH_ONLY -> Step.LOGIN
        Range.VISIT_ONLY, Range.SWITCH_AND_VISIT -> Step.DONE
    }

    /**
     * 第 1 步：按**起点场景**决定从哪一步开始（FR-04 的「起点 → 起始步骤」表）。
     *
     * 返回 null = **起点不成立**（场景认不出来，或者"只拜访"却还停在启动页 / 选服页）——
     * 这时必须中止并说明，**绝不做猜测性点击**（红线 2）。
     */
    fun firstStep(scene: Scene, range: Range): Step? = when (range) {
        Range.SWITCH_ONLY, Range.SWITCH_AND_VISIT -> switchStart(scene)
        Range.VISIT_ONLY -> visitStart(scene)
    }

    /** 换号那两种区间共用的起点判定。 */
    private fun switchStart(scene: Scene): Step? = when (scene) {
        Scene.BOOT -> Step.SWITCH_SERVER // 启动页 → 统一从「换区」开始（多一次换区但不会走错）
        Scene.SERVER_LIST -> Step.PICK_SERVER // 已在选服页 → 不必再点一次「换区」
        Scene.LOBBY -> Step.LOGOUT // 大厅没有农场退出入口
        Scene.FARM, Scene.FRIEND_FARM -> Step.LEAVE_FARM
        Scene.UNKNOWN -> null
    }

    /**
     * 「只拜访」的起点判定：**它只在游戏里成立**（还没登录 / 停在选服页时直接判不成立）。
     *
     * 注意这里**必须**与 [switchStart] 分开两个函数，不能写成两个局部 `val` ——
     * 那样两段都会被求值，这段的 `null` 会把换号那两种起点一起毙掉（2026-09-17 单测抓到的）。
     */
    private fun visitStart(scene: Scene): Step? = when (scene) {
        Scene.LOBBY -> Step.ENTER_FARM // 大厅 → 先进农场
        // 已在农场 / 别人的农场 → 直接开好友列表（第 7 步「进入农场」在农场里没有入口）
        Scene.FARM, Scene.FRIEND_FARM -> Step.OPEN_FRIENDS
        Scene.BOOT, Scene.SERVER_LIST, Scene.UNKNOWN -> null
    }

    /** 开始一次执行；起点不成立时返回 null（调用方据此提示用户，不静默）。 */
    fun start(scene: Scene, range: Range): State? =
        firstStep(scene, range)?.let { State(range = range, step = it) }

    /**
     * 当前步骤**验证通过** → 进入下一步；已到区间终点 → 完成（FR-04 第 10 步 = 停在好友的农场）。
     */
    fun advance(state: State): State {
        if (!state.isRunning) return state
        if (state.step == lastStep(state.range)) {
            return state.copy(outcome = Outcome.FINISHED, retries = 0, reason = null)
        }
        val next = Step.entries.firstOrNull { it.number == state.step.number + 1 }
            ?: return state.copy(outcome = Outcome.FINISHED, retries = 0, reason = null)
        return state.copy(step = next, retries = 0, reason = null)
    }

    /**
     * 当前步骤**验证不通过**：重试计数 +1；到上限 → 中止并带上原因。
     * 注意：这里是**原地重试同一步**，不会往前跳，也不会回头重跑前面的步骤。
     */
    fun fail(state: State, reason: String): State {
        if (!state.isRunning) return state
        val retries = state.retries + 1
        return if (retries >= MAX_RETRIES) {
            state.copy(retries = retries, outcome = Outcome.FAILED, reason = reason)
        } else {
            state.copy(retries = retries, reason = reason)
        }
    }

    /**
     * 用户点「继续」（FR-05）：**从失败的那一步重试**，不重跑前面的步骤。
     * 只在已中止时有意义（还在跑的调用它没有副作用）。
     */
    fun resume(state: State): State =
        if (state.outcome == Outcome.FAILED) {
            state.copy(outcome = Outcome.RUNNING, retries = 0, reason = null)
        } else {
            state
        }
}
