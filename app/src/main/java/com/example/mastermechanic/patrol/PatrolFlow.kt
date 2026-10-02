package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState

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

        /**
         * 农场（自己的 / 好友的都读成这个，2026-09-21 合并）。
         *
         * 两个农场的画面本质是同一个场景（返回箭头、好友入口样式一致）。
         * 2026-09-23：连"这是谁的农场"也不再判定（用户拍板整块删掉）——
         * 换号 / 拜访的路径在两个农场里完全一样，所以一个 Scene 就够。
         */
        FARM,

        /**
         * **认得出的画面，但它不在 FR-04 的起点表里**：好友列表、设置页、退出登录确认框、返回场景确认框。
         *
         * [firstStep] 对它返回 null —— 在这里中止并提示用户先退出来（回农场 / 大厅 / 启动页），
         * 不做猜测性点击。保留这个值是为了让"认得出画面但不是起点"与[UNKNOWN]"根本认不出来"
         * 两种情况**可分**：前者提示用户动手就行，后者得先查识别。
         */
        NOT_A_START,

        /** 认不出来 —— **一发点击都不发**，直接中止。 */
        UNKNOWN,
    }

    /** FR-04 的三种执行区间（由菜单项决定用哪一种）。 */
    enum class Range(
        /**
         * **日志 / 说明里这条流程叫什么**（2026-10-03 用户定稿）。
         *
         * 之前日志一律写「跑号」，2026-10-03 换成「流程」之后用户指出："**只写『流程』两个字，怎么区分
         * 具体是哪个流程？**" ⇒ 菜单上本来就有三种（换号 / 拜访 / 换号+拜访），日志必须能对上其中一个。
         *
         * 取值标准 = **它到底在做什么**（由 [Range] 决定，即这次执行的区间），而不是"用户点了哪一行"：
         * 「拜访（预设）」走的是 [SWITCH_AND_VISIT] ⇒ 日志就写「换号+拜访」，
         * 与菜单那一行的名字一致 ✓。
         */
        val logLabel: String,
    ) {
        /** 只换号：起点所在步 → 第 6 步（登录进大厅）。 */
        SWITCH_ONLY("换号"),

        /** 只拜访：第 7 步 → 第 10 步（跳过全部换号步骤）。 */
        VISIT_ONLY("拜访"),

        /** 换号 + 拜访：起点所在步 → 第 10 步（「拜访（预设）」用它）。 */
        SWITCH_AND_VISIT("换号+拜访"),
    }

    /**
     * FR-04 的步骤。编号与需求表**逐条对齐**，日志与排障才能直接对号入座。
     *
     * [label] 是**给用户看的中文名**（T4-5：悬浮窗要显示"当前第几步、在做什么"）。
     * 与 [com.example.mastermechanic.decision.UiState.label] 同一做法：枚举自带中文，
     * 界面层不做 `when` 映射（映射一散开，加步骤时必漏一处）。
     */
    enum class Step(val number: Int, val label: String) {
        /** 1 起始确认。 */
        CHECK_START(1, "起始确认"),

        /** 2 退出农场（仅起点为农场 / 好友的农场时）。 */
        LEAVE_FARM(2, "退出农场"),

        /** 3 退出登录。 */
        LOGOUT(3, "退出登录"),

        /** 4 换区。 */
        SWITCH_SERVER(4, "换区"),

        /** 5 选择区服（按名称精确定位，找不到即中止）。 */
        PICK_SERVER(5, "选择区服"),

        /** 6 登录游戏（其间按 FR-01 关闭弹窗，可能 0~多个）。 */
        LOGIN(6, "登录游戏"),

        /** 7 进入农场。 */
        ENTER_FARM(7, "进入农场"),

        /** 8 打开好友列表。 */
        OPEN_FRIENDS(8, "打开好友列表"),

        /** 9 拜访好友（按名称精确定位）。 */
        VISIT_FRIEND(9, "拜访好友"),

        /** 10 本项完成：**停在好友的农场**，不做任何返回动作。 */
        DONE(10, "完成"),
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
     * @param paused **已暂停**（游戏离开前台）：不动作、不计数；回前台**不自动继续**，等用户点「继续」（FR-05）。
     * @param lastActionAtMs 上一次真正发出点击的时刻（0 = 还没点过）。编排层用它判断
     *   "刚点完、画面还没切过来"还是"这一击确实没生效"（[PatrolRunner.SETTLE_WAIT_MS]）。
     * @param stateAtLastAction **点击那一刻**认到的画面（null = 未记录）。观察窗口据此**提前结束**：
     *   画面确实换了另一屏、且新画面上这一步有事可做时，不必再等满 [PatrolRunner.SETTLE_WAIT_MS]
     *   （2026-09-21，真机实测每步白等 ≈1.1~1.4s）。null 时退化为"窗口内一律等"的老行为，
     *   于是纯逻辑调用方（单测 / 离线重跑）不受影响。
     * @param stepEnteredAtMs **进入这一步**的时刻（0 = 未记录 → 不做超时判定，纯逻辑单测不受影响）。
     *   编排层用它算"这一步等多久了"（[STEP_TIMEOUT_MS]，见那里的说明）；⚠ **它不是唯一的起算点** ——
     *   2026-10-03 起耐心从 [budgetBase]（进入这一步 / 最后一次真动手，两者取晚）起算。
     */
    data class State(
        val range: Range,
        val step: Step,
        val retries: Int = 0,
        val outcome: Outcome = Outcome.RUNNING,
        val reason: String? = null,
        val paused: Boolean = false,
        val lastActionAtMs: Long = 0L,
        val stateAtLastAction: UiState? = null,
        val stepEnteredAtMs: Long = 0L,
    ) {
        val isRunning: Boolean get() = outcome == Outcome.RUNNING && !paused
    }

    /** 单步重试上限：失败到第 3 次即中止（FR-04 硬性要求 5）。 */
    const val MAX_RETRIES = 3

    /**
     * 单步的时间预算（毫秒）：从**进入这一步**起算，超了就中止并说明。
     *
     * ## 为什么光有"重试 3 次"不够（2026-09-20 真机实录）
     *
     * 从大厅点「只拜访」：第 7 步点大厅的农场入口 → **游戏加载农场画面花了约 6.6 秒**才被识别确认，
     * 而旧口径的耐心 = 观察窗口 2s + 3 次重试 ≈ **4.3s** → **中止发生在成功前 2 秒**
     * （用户看到的就是"流程只到农场就停了"）。
     *
     * 两个教训：
     * 1. **重试次数不是"等多久"**：它是"看清了、但不是该动手的地方"的次数判据；
     *    画面**还没看清**（`UNKNOWN`，多半在加载）不该算失败 —— 那会白耗预算；
     * 2. **次数判据会随轮速漂移**：本轮把间隔调到 500ms/200ms 并"动作即回快档"之后，
     *    同样 3 次重试在墙钟上只剩一半时间。要谈耐心，就得用**时间**谈。
     *
     * 取值：15s ≈ 实测最慢一次（6.6s）的 2 倍余量；到点即中止并说明等了多久（不静默卡住）。
     *
     * ## 2026-10-03：耐心从「我们最后一次真动手」起算（[budgetBase]）
     *
     * 上面那句"从**进入这一步**起算"本身是个坑，真机撞过一次（2026-10-03 01:03）：
     * 第 7 步（进入农场）进入后**先有 7.5 秒我们下不了手**——① 这一步自带的让路窗 2.5s、
     * ② 游戏自己弹了「新手引导」→「新手大厅」两层，③ FR-02 逐个关掉它们；
     * 而游戏真正加载农场又花了 **8.7 秒** ⇒ 准备 7.5 + 加载 8.7 = **16.3s > 15s** ⇒
     * **在「未知 -> 农场」命中前 1.24 秒中止**（用户报："换号+拜访中止在了自己的农场"）。
     *
     * ⇒ 一句话口径：**"我们还没动手的那段时间不该扣耐心"**（那是在等条件齐，不是白等）。
     * 与 [rebaseStepBudget]（画面停更顺延）/ [resume]（暂停不计入）**同源**：都是"刚才那段等待不作数"。
     *
     * ⚠ **不加"3 份"硬上界**：现状设计下每步**至多推一次**起点（口径 A 一步一击 + 搜索链中间小步
     * `advanceStep = false` 不记 + 第 5 步滚屏刻意不记）⇒ 总时长天然 ≈ 准备时间 + 一份耐心；
     * "迟迟不敢动手"仍由 `lastActionAtMs == 0` 那段（从进入步骤起算 15s）快速发现 ✓。
     */
    const val STEP_TIMEOUT_MS = 15_000L

    /**
     * **第 6 步（登录游戏 → 等「大厅」）的时间预算**（ms，2026-09-29 加，取 **30s**）。
     *
     * ## 为什么它要单独放宽（真机实测两次，都是纯游戏加载时间）
     *
     * ```
     * 02:21  第 5 步通过 13.88 → 跑号已完成 28.05  ⇒ 第 6 步等了 ≈14.2s（其间关掉 1 个活动弹窗）
     * 02:40  第 5 步通过 16.61 → 跑号已完成 30.04  ⇒ 第 6 步等了 ≈13.4s
     * ```
     * 而预算是 15s ⇒ **余量只剩 1~1.6s** ⇒ 登录再慢一点（弱网 / 手机更卡）就会以
     * "等了 N 秒，画面还没看清（期望「大厅」）"中止，而这件事**与应用无关**（点完「登录游戏」之后
     * 游戏自己要加载、还常常附带一个活动弹窗）。
     *
     * ⇒ 第 6 步单独给 30s（实测最慢 14.2s 的 2 倍余量），其余步骤仍是 [STEP_TIMEOUT_MS]——
     * 保留"进错画面 / 点不动"这类真问题的快速发现能力。
     */
    const val LOGIN_TIMEOUT_MS = 30_000L

    /**
     * **第 3 步（退出登录 → 等「启动页」）的时间预算**（ms，2026-09-30 加，取 **60s**）。
     *
     * ## 为什么它比 [LOGIN_TIMEOUT_MS] 还要宽
     *
     * 与第 6 步**同源**（"点完之后游戏自己要重启 / 加载"），但真机实测更狠：2026-09-30 验收实录 ——
     * 点下「确认退出」之后 **35 秒**仍停在「未知」，而**帧一直在来**（230 / 155 帧每 10 秒 ⇒ 游戏在画、
     * 不是采集停更）⇒ 15 秒预算下整条"换号+拜访"被中止。用户给的领域知识：**频繁退出登录有概率触发
     * 系统风控 ⇒ 强制下线**，那条路要人工把微信登录 / 授权做掉 ⇒ **自动恢复的时间长短不定**。
     *
     * ## 到点之后怎么办（用户口径，2026-09-30）
     *
     * **不猜成因、不给专门指引**："不需要过于明确的指引 —— 核心都是**长时间识别不到待识别元素**，
     * 用户自己能看到画面的变化" ⇒ 中止原因只陈述事实（等了多久 / 在等哪一屏 / 现在看到的是什么），
     * 措辞见 [PatrolRunner]（不做"多半是风控 / 请手动登录"这类判断）。
     *
     * 取值 60s ≈ 实测 35s 的 1.7 倍；真需要人工登录时到时中止 → 用户登录后点「继续」即可
     * （[resume] 会**重新起算**本步预算 ⇒ 不会一点就立刻再超时）。
     */
    const val LOGOUT_TIMEOUT_MS = 60_000L

    /**
     * **第 7 步（进入农场）的时间预算**（ms，2026-10-03 加，取 **20s**）。
     *
     * ## 取值：实测最慢 × 2（和第 3 / 6 / 9 步同一个取法，不是随手拍的）
     *
     * 两次真机实测"点下农场入口 → 状态确认「农场」"：**6.6s**（2026-09-20）/ **8.7s**（2026-10-03）⇒
     * 最慢 8.7s，20s ≈ **2.3 倍**余量。（第 3 步 35s→60s 是 1.7 倍；第 6 步 14.2s→30s 是 2.1 倍；
     * 第 9 步 ~12s→25s 是 2 倍。）
     *
     * ## 为什么不是 15s、也不是 30s
     *
     * - **不是 15s**：15/8.7 = 1.7 倍，**偏薄**——已经有两个样本 6.6 / 8.7，第三次落 12 秒就又白中止一次。
     * - **不是 30s**：30s 是**第 6 步（登录）**的档位（要启动游戏、拉资源）；第 7 步只是"点一下进农场"，
     *   它的失败模式（点歪了 / 入口没了 / 被带进活动页）用户**一眼能看出来** ⇒ 干等 30 秒是纯亏，
     *   而且会**掩盖真故障**（2026-10-01 `03:26` 那次"点进活动页"就是靠这个超时报出来的）。
     * - ⚠ 那 8.7 秒里**约 1.2 秒是识别慢造成的**（状态"连续 2 次命中"确认 + 识别 P95 215ms）⇒
     *   NFR-01 提速后同样这次约 7.5 秒 ⇒ 20s 的余量自动升到 2.7 倍。**余量该在识别侧挣，不该用加时间买。**
     *
     * 📌 **别的步骤不同步加长**（用户 2026-10-03 问过"可否统一 30s"，答复是不）：第 1/2/4/5/8 步
     * 点完画面 0.7~2.1 秒就变（真机：第 4 步 0.7s、第 5 步 2.1s）⇒ 15s 已是 7~15 倍余量，
     * 提到 30s 只会把"点不动 / 进错界面"这类**真故障**的发现时间白拉长一倍。
     */
    const val FARM_ENTER_TIMEOUT_MS = 20_000L

    /**
     * **这一步**的时间预算：第 6 步是"等游戏登录加载"、第 3 步是"等退出登录后游戏重启"、
     * 第 7 步是"进农场要加载一整屏"、第 9 步是"搜索链 + 结果页"
     * （见 [LOGIN_TIMEOUT_MS] / [LOGOUT_TIMEOUT_MS] / [FARM_ENTER_TIMEOUT_MS] / [FRIEND_TIMEOUT_MS]），
     * 其余步骤仍是 [STEP_TIMEOUT_MS]。
     */
    fun timeoutFor(step: Step): Long = when (step) {
        Step.LOGIN -> LOGIN_TIMEOUT_MS
        Step.LOGOUT -> LOGOUT_TIMEOUT_MS
        Step.ENTER_FARM -> FARM_ENTER_TIMEOUT_MS
        Step.VISIT_FRIEND -> FRIEND_TIMEOUT_MS
        else -> STEP_TIMEOUT_MS
    }

    /**
     * **第 9 步（拜访好友）的时间预算**（ms，2026-10-01 加，取 **25s**）。
     *
     * ## 为什么要单独放宽（用户 2026-10-01 报的那个失败循环）
     *
     * 这一步从 2026-10-01 起改成"**先看当前屏；没有就走搜索**"（[PatrolAnchors.FRIEND_SEARCH_ENTRY]）：
     * ① 点「搜好友」→ ② 点「请输入好友昵称」→ ③ 原生写文字 → ③.5 点原生「确定」/ 收输入法 → ④ 点「搜索」
     * ⇒ 五小步 ≈ **6~8 秒**；万一写入没进框（旧现象：点搜索提示"请输入文本"，然后又回去点输入框），
     * 还要**重写一次**（再 ≈6 秒）⇒ 贴着通用的 15s 预算，"结果页还没出来就被中止"几乎必然发生
     * （用户原话："…继续重复，直到时间到达中止阈值"）。
     *
     * ⇒ 给到 **25s**（≈ 搜索链一次半 + 结果页等待 6s + 点拜访的余量）。
     * ⚠ **重写次数另有上限**（`CaptureService.searchWriteMaxAttempts = 2`）⇒ 不会无限重试；
     * 两次都进不去框就**如实停下**（不再等预算）。
     */
    const val FRIEND_TIMEOUT_MS = 25_000L

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
        Scene.FARM -> Step.LEAVE_FARM
        // 好友列表 / 设置页 / 确认框都不在起点表里：先让用户退出来（不猜）
        Scene.NOT_A_START, Scene.UNKNOWN -> null
    }

    /**
     * 「只拜访」的起点判定：**它只在游戏里成立**（还没登录 / 停在选服页时直接判不成立）。
     *
     * 注意这里**必须**与 [switchStart] 分开两个函数，不能写成两个局部 `val` ——
     * 那样两段都会被求值，这段的 `null` 会把换号那两种起点一起毙掉（2026-09-17 单测抓到的）。
     */
    private fun visitStart(scene: Scene): Step? = when (scene) {
        Scene.LOBBY -> Step.ENTER_FARM // 大厅 → 先进农场
        // 已在农场（自己的或别人的）→ 直接开好友列表（第 7 步「进入农场」在农场里没有入口）
        Scene.FARM -> Step.OPEN_FRIENDS
        // 好友列表同样不在起点表里（面板已经打开了也不代表"该走哪条路"有定论）
        Scene.BOOT, Scene.SERVER_LIST, Scene.NOT_A_START, Scene.UNKNOWN -> null
    }

    /** 开始一次执行；起点不成立时返回 null（调用方据此提示用户，不静默）。 */
    fun start(scene: Scene, range: Range, nowMs: Long = 0L): State? =
        firstStep(scene, range)?.let { State(range = range, step = it, stepEnteredAtMs = nowMs) }

    /**
     * 当前步骤**验证通过** → 进入下一步；已到区间终点 → 完成（FR-04 第 10 步 = 停在好友的农场）。
     *
     * [nowMs] 用于给新步骤起算时间预算（见 [STEP_TIMEOUT_MS]）；0 = 不记录（纯逻辑单测）。
     */
    fun advance(state: State, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        if (state.step == lastStep(state.range)) {
            return state.copy(outcome = Outcome.FINISHED, retries = 0, reason = null)
        }
        val next = Step.entries.firstOrNull { it.number == state.step.number + 1 }
            ?: return state.copy(outcome = Outcome.FINISHED, retries = 0, reason = null)
        // **"本步已下过击"的标记必须清零**：⑤ 用它判断"点了之后只等期望画面"（口径 A，2026-09-24），
        // 上一步的击不能带到新的一步上来（否则新一步开头会被当成"刚点过"，白等一整个预算）。
        return state.copy(
            step = next,
            retries = 0,
            reason = null,
            stepEnteredAtMs = nowMs,
            lastActionAtMs = 0L,
            stateAtLastAction = null,
        )
    }

    /**
     * 当前步骤**验证不通过**：重试计数 +1；到上限（次数）**或**超过时间预算 → 中止并带上原因。
     * 注意：这里是**原地重试同一步**，不会往前跳，也不会回头重跑前面的步骤。
     *
     * 时间预算（[nowMs] 非 0 时生效）：有些"不通过"是**一次就够**的（例如换号换错了区服继续等也没用），
     * 所以次数上限保留；但真正的慢场景（加载慢）由 [STEP_TIMEOUT_MS] 兜底。
     */
    fun fail(state: State, reason: String, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        val retries = state.retries + 1
        val base = budgetBase(state)
        val timedOut = base > 0 && nowMs > 0 && nowMs - base >= timeoutFor(state.step)
        return if (retries >= MAX_RETRIES || timedOut) {
            state.copy(retries = retries, outcome = Outcome.FAILED, reason = reason)
        } else {
            state.copy(retries = retries, reason = reason)
        }
    }

    /**
     * **这一步的耐心从哪一刻起算**（2026-10-03 加，乙方案；被 [waitedMs] / [timedOut] / [fail] 共用）。
     *
     * 口径一句话：**"我们还没动手的那段时间不该扣耐心"** ⇒
     * `max(进入这一步的时刻, 这一步最后一次真动手的时刻)`。
     *
     * - 还没动手（[State.lastActionAtMs] == 0）⇒ 就是 [State.stepEnteredAtMs]（**老行为**）
     *   ⇒ "迟迟不敢动手 / 锚点定位不到"仍能在 15 秒内快速发现，不会因为这条口径而永远等下去；
     * - 动过手 ⇒ 从那一击起算 ⇒ 游戏自己的加载时间才由 [timeoutFor] 判，**"准备"（等弹层关掉、
     *   等让路窗过去）不再扣耐心**；
     * - [stepEnteredAtMs] == 0（纯逻辑调用方 / 单测没给时刻）⇒ 返回 0 ⇒ **永不超时**，
     *   与这条口径上线前逐字段一致；
     * - 那一击被[作废](withoutPendingAction)（门禁拒发 / 被遮挡屏吞掉）⇒ 标记已清 ⇒ 起点自动回退，
     *   **"没点成"绝不会被当成"动过手"**（不会白赚一份耐心）。
     *
     * ⚠ 注意 [PatrolRunner] ⑦b 那个让路窗（`ENTER_FARM_SETTLE_MS`）**故意仍以 [State.stepEnteredAtMs]
     * 起算** —— 它的语义是"刚进大厅这一小会儿别抢着点"，与耐心是两件事，别顺手也改成"最后一次动手"。
     */
    fun budgetBase(state: State): Long =
        if (state.stepEnteredAtMs <= 0L) {
            0L
        } else {
            maxOf(state.stepEnteredAtMs, state.lastActionAtMs)
        }

    /** 这一步已经等了多久（毫秒）；0 = 没记录过进入时刻。起算点见 [budgetBase]。 */
    fun waitedMs(state: State, nowMs: Long): Long {
        val base = budgetBase(state)
        return if (base > 0 && nowMs > base) nowMs - base else 0L
    }

    /** 是否已经超过这一步的时间预算（没记录进入时刻 → 永不超时：纯逻辑调用不受影响）。 */
    fun timedOut(state: State, nowMs: Long): Boolean =
        budgetBase(state) > 0 && nowMs > 0 && waitedMs(state, nowMs) >= timeoutFor(state.step)

    /** 游戏离开前台：**暂停**（不动作、不计数）。已经暂停 / 已结束就原样返回。 */
    fun pause(state: State): State =
        if (state.outcome == Outcome.RUNNING && !state.paused) {
            state.copy(paused = true, reason = "游戏不在前台，已暂停")
        } else {
            state
        }

    /**
     * 记下"这一击已经发出"：时刻 + **点击那一刻认到的画面**。
     *
     * 后者用于观察窗口的提前结束（画面确实换了另一屏就不必干等，
     * 见 [PatrolRunner.SETTLE_WAIT_MS] 与 [State.stateAtLastAction]）；
     * 不提供（null）时退化为"窗口内一律等"的老行为。
     */
    fun acted(state: State, nowMs: Long, clickedState: UiState? = null): State =
        state.copy(lastActionAtMs = nowMs, stateAtLastAction = clickedState)

    /**
     * **撤销"这一击已经发出"的标记**（2026-09-30 加，progress 第 325 条 b）：
     * 编排层**提议**一击时就写下了 `lastActionAtMs`，而 ⑤ 靠它判断"接下来只等期望画面"；
     * 可门禁可能把这一击**拒掉**（画面旧 / 越界 / 不在前台）—— 那时留着这个标记，就会**白等一整个步骤预算**
     * （真机实录：落点被误判"屏外"静默拒绝 ⇒ 流程把它当"刚点过" ⇒ 白等 16 秒后中止，
     * 用户看到的是"卡在了打开好友列表"）。
     *
     * ⇒ 调用方（采集层）在**门禁拒绝**之后用它把状态退回"这一步还没点过"，
     * 下一轮就会重新定位、重新提议（见 `CaptureService` 里那一处）。
     *
     * **第二个调用点（2026-10-01）**：那一击被**遮挡屏吞掉**时——真机：第 7 步点「农场入口」后约 1 秒
     * 弹出活动弹窗，弹窗关掉后画面回到大厅（那一下其实没进去）。遮挡屏盖着的画面本来就不作数 ⇒
     * "点了"这个结论同样作废，否则 ⑤ 会让人白等满这一步的预算（见 `PatrolRunner` 的 ② 分支）。
     */
    fun withoutPendingAction(state: State): State =
        state.copy(lastActionAtMs = 0L, stateAtLastAction = null)

    /**
     * **画面刚从"看不见"回到能看见 ⇒ 这一步的预算重新起算**（2026-10-01 真机缺陷修）。
     *
     * 这条口径本来就在 [PatrolRunner] 里写着（`RoundInput.framesStalled`：*"画面一回来，从那时起
     * 重新给足这一步的预算"*），但实现只覆盖了"**停更期间还在跑轮次**"这条路径 —— 每轮把
     * [State.stepEnteredAtMs] 顺延到当轮时刻。真机里还有另一条路：**停更期间轮次稀了 / 断了**
     * （零新帧时识别轮不再按 200ms 走），而**画面回来的那一刻**闸先被解除（`processFrame` 收到新帧）、
     * 紧接着那一轮拿到的 `waitedMs` 就已经是"停更期间累计"的：
     * ```
     * 20:35:48.602  第 8 步开始（点「农场」界面标志）
     * 20:35:53.203  判定「画面采集已停」⇒ 停止一切点击（此后 60+ 秒零新帧）
     * 20:36:52.245  用户点「重新授权采集」⇒ 会话被替换（→ 新会话 20:36:57.254 建立）
     * 20:36:57.407  跑号: 已中止：第 8 步…**等了 36 秒**仍未识别出要等的画面（预算只有 15 秒）✗
     * 20:36:58.398  状态转移: 未知 -> 好友列表          ← 中止后 1 秒，期望的画面就来了 ✗✗
     * ```
     * ⇒ 调用方（采集层）在**解除"画面采集已停"那道闸的那一刻**调用本函数：把这一步的时间预算
     * 重新起算到"画面回来"这一刻。语义与 [resume] 的"预算重新起算"同源 —— 都是
     * **"刚才那段等待不作数"**，区别只在触发者（用户点继续 / 画面自己回来）。
     *
     * ⚠ 只管**正在跑**的步骤：暂停 / 已中止 / 已完成的预算归 [resume] 管（别在这里替用户做决定）。
     */
    fun rebaseStepBudget(state: State, nowMs: Long): State =
        if (state.isRunning && !state.paused) state.copy(stepEnteredAtMs = nowMs) else state

    /**
     * 用户点「继续」（FR-05）：**从失败的那一步重试**，不重跑前面的步骤；顺带解除暂停。
     *
     * 只在「已中止」或「已暂停」时有意义（还在跑的调用方没有副作用）。
     */
    fun resume(state: State, nowMs: Long = 0L): State = when {
        // 时间预算**重新起算**：用户刚点了「继续」，不该拿"之前等了多久"立刻再判超时
        state.outcome == Outcome.FAILED -> state.copy(
            outcome = Outcome.RUNNING,
            retries = 0,
            reason = null,
            paused = false,
            stepEnteredAtMs = nowMs,
            // **作废"本步已下过击"**（2026-10-01）：见下面暂停分支的长注释（同一个坑）。
            lastActionAtMs = 0L,
            stateAtLastAction = null,
        )

        // ⚠ **暂停后继续：必须把"本步已下过击"一起作废，并重新起算预算**（2026-10-01 真机修）。
        //
        // 症状（用户原话"**暂停后切出再切回游戏，点了继续没有用**"）——真机实录：
        // ```
        // 20:14:30.526  跑号: 已暂停（游戏不在前台）        ← 此刻 lastActionAtMs > 0（刚点过「退出登录」）
        // 20:14:54.912  继续：从3步接着来                    ← 「继续」其实点到了、也生效了
        // 20:14:55.183  跑号: 第 3 步：刚点过，等画面切换，继续等（已等 28 秒）  ← 之后一直是这句
        // 20:15:14.499  …（已等 47 秒）⇒ 一路涨到这一步的预算耗尽
        // ```
        // 根因：[PatrolRunner] 的 ⑤ 判据是**标志位**（`lastActionAtMs > 0 && !screenMovedOn`）
        // ⇒ "本步已下过击：接下来只判期望画面、绝不补点"（口径 A，2026-09-24）。暂停前那一击的标记
        // 留在状态里 ⇒ 继续后 ⑤ 立刻接手 ⇒ **既不重新定位、也不补点**，只能干等到超时 ✗。
        // （暂停期间用户切出去又回来，画面早就该重新看一遍了 —— 那一击的上下文已经不成立。）
        // ⚠ 同理，**暂停期间计时不该继续跑**：真机那次继续后是从"已等 28 秒"起算、只剩 32 秒，
        //    等于把暂停的时长算进了这一步的耐心 ⇒ [nowMs] 非 0 时把预算顺延到此刻。
        state.paused -> state.copy(
            paused = false,
            reason = null,
            stepEnteredAtMs = if (nowMs > 0) nowMs else state.stepEnteredAtMs,
            lastActionAtMs = 0L,
            stateAtLastAction = null,
        )

        else -> state
    }
}
