package com.example.mastermechanic.badge

import com.example.mastermechanic.decision.UiState

/**
 * 立牌收集支线的**纯逻辑状态机**（临时需求 temp/badge-share-1021，2026-10-21）。
 *
 * 为什么不塞进 `PatrolFlow`：见 `docs/plans/temp-badge-share.md` 的「接入跑号运行时」——
 * 立牌有**条件分支**（空槽判领取 / 今日已获取 / 已领取）与**卡片循环**，而巡查是单次线性区间；
 * 而且本需求要整支可删（FR-T6），混进主流程就得回滚主流程。
 *
 * ## 边界（与 `PatrolFlow` 同一套切法）
 *
 * - **不点击**：只给出"该点哪个锚点"，下发一律经 `ClickDispatch`（红线 6）；
 * - **不识别**：画面 / 锚点 / OCR 结论都由采集层喂进来；
 * - **不管节流**：`ClickGate` 兜底，这里只判"这一轮能不能点"。
 *
 * ## 每个区服一次 [State]，跑完由 `BadgeTask` 取下一个区服
 *
 * 换号段（第 3~6 步）**不在这里**：它复用 `PatrolFlow(Range.SWITCH_ONLY)`，跑到大厅之后
 * 才把接力棒交给本状态机（见 `BadgeSession.Phase`）。
 */
object BadgeFlow {

    /**
     * 立牌支线的步骤。编号只用于日志与排障（与巡查的第几步**不是**同一个编号体系）。
     *
     * 名字都用"做什么"（`ENTER_ACTIVITY` / `READ_CARD`），不用"第几屏"——
     * 同一屏会出现多次（复制分享弹框被进两次：已选立牌一次、每张卡一次）。
     */
    enum class Step(val number: Int, val label: String) {
        /** 1 大厅点「活动入口」→ 活动中心，并等「获取立牌」出现（等不到 ⇒ 返回重进）。 */
        ENTER_ACTIVITY(1, "进入立牌活动"),

        /**
         * 2 **不点击的判定步**：等自动提交动效结束（可交互），再看空立牌槽在不在。
         *
         * 结论记进 [State.needsClaim]：空槽在 ⇒ 走领取；不在 ⇒ 直接分享（2026-10-21 三态对照帧）。
         */
        DECIDE_BRANCH(2, "判断今日立牌"),

        /** 3 活动中心点「获取立牌入口」→ 获取立牌弹框。 */
        CLAIM_ENTRY(3, "打开获取立牌"),

        /**
         * 4 获取立牌弹框点「登录游戏」那行的「领取」（多实例，按行文字消歧）。
         *
         * 两行都已是「已领取」时**没有可点的按钮** ⇒ 合法地直接推进（FR-T2：不报错、不重领）。
         */
        CLAIM_TASK(4, "领取登录立牌"),

        /** 5 点「点击继续」收尾；没有继续按钮（已领取路径）⇒ 点关闭 X。 */
        CLAIM_FINISH(5, "收尾获取立牌"),

        /** 6 活动中心点「分享立牌」→ 复制分享弹框。 */
        SHARE_ENTRY(6, "打开分享立牌"),

        /** 7 复制分享弹框：OCR 三块（分数 / 立牌名 / 分享码）→ 记录「已选立牌」。 */
        READ_FIRST(7, "读取已选立牌"),

        /** 8 复制分享弹框点「切换」→ 我的立牌弹框。 */
        SWITCH_TO_MY(8, "切到我的立牌"),

        /**
         * 9 **不点击的判定步**：还有没读过的卡 ⇒ 点它的「分享」进复制分享；
         * 全部读过 ⇒ 推进到 [CLOSE_MY]。点哪张卡由采集层给（`CardPlan`）。
         */
        PICK_CARD(9, "选择立牌卡"),

        /** 10 复制分享弹框（某张卡）：OCR 三块 → 记录。 */
        READ_CARD(10, "读取卡的立牌信息"),

        /** 11 复制分享弹框点关闭 X → 回「我的立牌」。 */
        CLOSE_CARD(11, "关闭分享立牌"),

        /** 12 我的立牌弹框点关闭 X → 回活动中心。 */
        CLOSE_MY(12, "关闭我的立牌"),

        /** 13 活动中心点左上角返回 → 大厅（本区服收尾）。 */
        EXIT_ACTIVITY(13, "返回大厅"),
    }

    /** 一次执行的结局（与 `PatrolFlow.Outcome` 同义，但不共享类型：两条流程互不依赖）。 */
    enum class Outcome {
        RUNNING,

        /** 本区服跑完（大厅）；是否还有下一个区服由 `BadgeTask` 决定。 */
        FINISHED,

        /** 停下并说清原因（FR-T5）。 */
        FAILED,
    }

    /**
     * 一次执行的进度。
     *
     * @param needsClaim [DECIDE_BRANCH] 的结论（null = 还没判）—— 只用于日志与"为什么走了这条分支"。
     * @param cardsRead 已经读过几张卡（日志 / 展示用；"还有没有卡"由采集层的 `CardPlan` 判）。
     * @param lastActionAtMs 上一次真正发出点击的时刻（0 = 还没点过）：口径 A 的"点后只等期望画面"。
     * @param stateAtLastAction 点击那一刻认到的画面（null = 未记录，退化为"窗口内一律等"）。
     * @param stepEnteredAtMs 进入这一步的时刻（0 = 不记录 ⇒ 不做超时判定，纯逻辑单测不受影响）。
     * @param framesStalledSinceMs 本次"收不到新帧"开始的时刻（0 = 不在停更）。
     * @param obstructedMs 本步因为**遮挡屏在屏**已经顺延掉的总时长（[OBSTRUCTION_REBASE_LIMIT_MS] 封顶）。
     */
    data class State(
        val step: Step,
        val needsClaim: Boolean? = null,
        /**
         * 「空立牌槽」**连续没定位到**的轮数（判「今日已获取」前的确认，2026-10-22）。
         *
         * 为什么需要它：`badge_empty_slot` 的**缺席**原来是"一轮定生死"——没命中就当已领取。
         * 但"不在"和"这一轮没搜到"在观感上完全一样（模板被红点/动效盖住、框偏了都会缺席），
         * 一旦误判就会跳过领取直接去分享（用户 2026-10-22 提问点出的缺口）。
         * 现在要求连续 [EMPTY_SLOT_ABSENT_CONFIRM_ROUNDS] 轮缺席才认「已获取」；
         * 只要有一轮命中就立刻清零并认「还没领取」（**存在是硬证据，缺席才需要确认**）。
         */
        val emptySlotAbsentRounds: Int = 0,
        /**
         * 第 1 步已经**点了几次「活动入口」**（2026-10-22）。
         *
         * 为什么要计数：点「活动」不一定进立牌活动（可能进上次看的别的活动页），兜底是"点返回 →
         * 回大厅 → 再点活动"。没有上限就会无限循环（用户观察"极大概率"能进，不是必然）⇒
         * 超过 [ENTRY_MAX_ATTEMPTS] 如实停下，让用户手动进一次。
         */
        val entryAttempts: Int = 0,
        /**
         * 第 1 步在「活动中心」上**开始等「获取立牌」的时刻**（0 = 还不在等）。
         *
         * 2026-10-22 两屏合并后，`活动中心` 标志（左上返回）只证明"进了活动窗口"——立牌活动与其他
         * 活动页都成立。判"进错了"只能靠**等不到「获取立牌」**：从看到活动中心起算，
         * 持续 [ACTIVITY_READY_TIMEOUT_MS] 仍不出现 ⇒ 点返回重进（次数上限同上）。
         * 一旦就绪、或画面离开活动中心，就清零（下一次进入重新起算）。
         */
        val centerSinceMs: Long = 0L,
        val cardsRead: Int = 0,
        /**
         * 上一轮「选卡」**点完之后还剩几张没读**（0 = 这是最后一张，2026-10-23）。
         *
         * 为什么需要它：真机发现「复制分享」的 X 会**把「我的立牌」那层一起关掉** ⇒ 回到活动中心后
         * 如果还有没读的卡，就必须**再进一次**「分享立牌 → 切换」才能读到下一张（原来一律当"只有 1 张"
         * 直接返回大厅 ⇒ 3 张卡只收到 1 张）。
         */
        val cardsRemaining: Int = 0,
        /** 本轮为了读多张卡，重新进了几次「分享立牌」（防止在异常画面上无限循环）。 */
        val cardRounds: Int = 0,
        val outcome: Outcome = Outcome.RUNNING,
        val reason: String? = null,
        val paused: Boolean = false,
        val lastActionAtMs: Long = 0L,
        val stateAtLastAction: UiState? = null,
        val stepEnteredAtMs: Long = 0L,
        val framesStalledSinceMs: Long = 0L,
        val obstructedMs: Long = 0L,
    ) {
        val isRunning: Boolean get() = outcome == Outcome.RUNNING && !paused
        val finished: Boolean get() = outcome == Outcome.FINISHED
        val failed: Boolean get() = outcome == Outcome.FAILED
    }

    /** 开始一次支线：第 1 步永远从「进入立牌活动」开始（此时人已在大厅）。 */
    fun start(nowMs: Long = 0L): State = State(step = Step.ENTER_ACTIVITY, stepEnteredAtMs = nowMs)

    /** 这一步的时间预算（ms）。 */
    fun timeoutFor(step: Step): Long = when (step) {
        // 进活动中心：真机要经过"点入口 → 活动弹窗（FR-01 关掉）→ 回大厅 → 再点 → 加载"，
        // 2026-10-22 实测第一次走到活动中心花了 17 秒（15 秒预算当场误判中止）
        Step.ENTER_ACTIVITY -> ENTER_ACTIVITY_TIMEOUT_MS
        // 活动中心每日首次打开会播自动提交动效（用户口径：等它自然结束）——给足耐心
        Step.DECIDE_BRANCH -> ACTIVITY_SETTLE_TIMEOUT_MS
        // 领取 / 读取要等弹框动画与 OCR
        Step.CLAIM_TASK, Step.READ_FIRST, Step.READ_CARD -> OCR_STEP_TIMEOUT_MS
        else -> STEP_TIMEOUT_MS
    }

    /**
     * 「进入立牌活动」的预算（ms）：含活动弹窗被 FR-01 关掉后重新点击 + 加载 + **等就绪**。
     *
     * 2026-10-22 从 30s 提到 45s：这一屏现在还多了一种"进的是别的活动页 ⇒ 点返回、再点活动"的
     * 兜底（单次往返约 4 秒 + 等待 [ACTIVITY_READY_TIMEOUT_MS]），最多 [ENTRY_MAX_ATTEMPTS] 次。
     * 次数上限自己会停，不靠超时兜底。
     */
    const val ENTER_ACTIVITY_TIMEOUT_MS = 45_000L

    /**
     * 第 1 步最多点几次「活动入口」（含第一次）。
     *
     * 用户观察（2026-10-22）：进错活动时"点返回 → 再点活动"**极大概率**第二次就进立牌活动，
     * 但不是必然 ⇒ 到上限就停下报原因（FR-T5），绝不无限重试。
     *
     * 2026-10-22 晚从 3 提到 4：判"进错"改成了**等满 [ACTIVITY_READY_TIMEOUT_MS]**，
     * 慢设备上动效偶尔超过它就白挨一次返回；多给一次机会，别让一个区服整条中止。
     */
    const val ENTRY_MAX_ATTEMPTS = 4

    /**
     * 在「活动中心」等「获取立牌」出现的上限（ms）——等不到 ⇒ 认为进的是**别的活动页**。
     *
     * 取值依据：活动中心每日首次打开会播"自动提交立牌"动效，真机实测 3~8 秒才可交互；
     * 锚点也要等动效结束才认得出。10 秒 = 实测上限 8 秒 + 2 秒余量。
     *
     * ⚠ 这是**上限不是固定等待**：正确的立牌活动在「获取立牌」一出现就继续，不额外等；
     * 只有真进错页时才付满这 10 秒。**不能按轮数**：一轮随设备负载在 0.4~0.9 秒之间浮动。
     *
     * 也不能更短：判早了会把**正在播动效的正确页面**踢回大厅，白跑一个来回还消耗一次
     * [ENTRY_MAX_ATTEMPTS]。宁可多等，不要误踢。
     */
    const val ACTIVITY_READY_TIMEOUT_MS = 10_000L

    /** 通用单步预算（ms）：点一下就切屏的动作，15s 是 7~15 倍余量（同 `PatrolFlow.STEP_TIMEOUT_MS` 的理由）。 */
    const val STEP_TIMEOUT_MS = 15_000L

    /** 等活动中心自动提交结束的预算（ms）：真机动效约 3~8s；20s 覆盖慢加载，超时如实停下。 */
    const val ACTIVITY_SETTLE_TIMEOUT_MS = 20_000L

    /**
     * 「空槽不在 ⇒ 今日已获取」需要**连续**几轮缺席才算数（一轮 ≈0.4~0.9s）。
     *
     * 取 2 而不是 3：空槽是带立牌底座的**大区域**，真缺席时几乎不可能连着两轮都搜到边缘；
     * 而每多一轮就多赔 1 秒 × 每区服（14 个区服 ≈14 秒）。2 轮是"够挡偶发漏检、又不贵"的折中。
     */
    const val EMPTY_SLOT_ABSENT_CONFIRM_ROUNDS = 2

    /**
     * 读取立牌信息的预算（ms）。
     *
     * 2026-10-22 从 10s 提到 20s：分享码改成**两次读数一致才落盘**（见 `CodeConfirmation`），
     * 一屏三块 OCR 实测约 1~3s ⇒ 两次确认就要 2~6s，再留一次不一致时的重读余量，
     * 10s 会在"读得慢但完全正常"的机器上先超时。领取卡（CLAIM_TASK）共用这个预算，放宽无害。
     */
    const val OCR_STEP_TIMEOUT_MS = 20_000L

    /** 相邻点击最小间隔（红线 6；真正兜底在 `ClickGate`）。 */
    const val CLICK_GAP_MS = 300L

    /** 停更宽限（ms）：超过它就不再顺延本步计时，走超时中止（否则静态页会死等）。 */
    const val STALL_REBASE_GRACE_MS = 30_000L

    /**
     * **遮挡屏顺延上限**（ms）：活动弹窗 / 新手两屏在屏时本步计时顺延，但最多顺延这么久。
     *
     * 为什么要封顶：FR-01 / FR-02 自己会停手（次数上限），若那时遮挡屏还在屏（用户手动停在那里），
     * 不封顶就等于"这一步永不超时" ⇒ 任务挂死。60 秒足够覆盖关弹窗 + 游戏加载。
     */
    const val OBSTRUCTION_REBASE_LIMIT_MS = 60_000L

    /** 顺序推进到下一步；已在末步 ⇒ 完成。 */
    fun advance(state: State, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        val next = advancedStepOf(state.step) ?: return finish(state)
        return enter(state, next, nowMs)
    }

    /** 顺序下一步；末步返回 null（不在 `advance` 里用 `?:` 的写法，是为了让编排层能问"下一步是哪一屏"）。 */
    fun advancedStepOf(step: Step): Step? = Step.entries.firstOrNull { it.number == step.number + 1 }

    /**
     * **受控跳转**（分支 / 卡片循环用）：
     * - [DECIDE_BRANCH] → 领取链或直接分享；
     * - [PICK_CARD] → 关闭我的立牌（没有未读卡了）；
     * - [CLOSE_CARD] → 回到 [PICK_CARD]（还有卡）或 [EXIT_ACTIVITY]（X 连两层一起关掉，真机 2026-10-22）；
     *   **X 关了「我的立牌」但还有卡没读**（真机 2026-10-23：一个区服 3 张卡）⇒ 回 [SHARE_ENTRY]
     *   再走一遍（活动中心 → 分享立牌 → 切换 → 选下一张）。
     *
     * 只允许从这三个判定 / 回环步跳，其余步骤调用会**原样返回**：跳转表写错时宁可停在原地等超时，
     * 也不许凭空跨过一步（红线 5：动作后未验证不得执行下一步）。
     */
    fun advanceTo(state: State, step: Step, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        val allowed = state.step == Step.DECIDE_BRANCH ||
            state.step == Step.PICK_CARD ||
            state.step == Step.CLOSE_CARD
        if (!allowed) return state
        return enter(state, step, nowMs)
    }

    /** 判定结论记进状态并跳转（[DECIDE_BRANCH] 专用）。 */
    fun decide(state: State, needsClaim: Boolean, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        if (state.step != Step.DECIDE_BRANCH) return state
        val next = if (needsClaim) Step.CLAIM_ENTRY else Step.SHARE_ENTRY
        return enter(state.copy(needsClaim = needsClaim), next, nowMs)
    }

    /** 记一次"卡片已读"（[READ_CARD] 验证通过时）并推进到关闭。 */
    fun cardRead(state: State, nowMs: Long = 0L): State {
        if (!state.isRunning) return state
        if (state.step != Step.READ_CARD) return state
        return enter(state.copy(cardsRead = state.cardsRead + 1), Step.CLOSE_CARD, nowMs)
    }

    /** 进入新一步：清掉"已动手"标记与停更状态（上一步的击不能带到下一步，口径 A）。 */
    private fun enter(state: State, step: Step, nowMs: Long): State = state.copy(
        step = step,
        reason = null,
        stepEnteredAtMs = nowMs,
        emptySlotAbsentRounds = 0,
        entryAttempts = 0,
        centerSinceMs = 0L,
        lastActionAtMs = 0L,
        stateAtLastAction = null,
        framesStalledSinceMs = 0L,
        obstructedMs = 0L,
    )

    private fun finish(state: State): State =
        state.copy(outcome = Outcome.FINISHED, reason = null, lastActionAtMs = 0L, stateAtLastAction = null)

    /** 本步已经等了多少毫秒（日志要能读出"等了 N 秒"）。 */
    fun waitedMs(state: State, nowMs: Long): Long =
        if (state.stepEnteredAtMs == 0L) 0L else (nowMs - state.stepEnteredAtMs).coerceAtLeast(0L)

    /** 本步是否已超时（[State.stepEnteredAtMs] 为 0 ⇒ 永远不算超时，纯逻辑调用方不受影响）。 */
    fun timedOut(state: State, nowMs: Long): Boolean =
        state.stepEnteredAtMs != 0L && waitedMs(state, nowMs) >= timeoutFor(state.step)

    /** 中止（FR-T5：停下并说清原因，绝不盲跳下一个区服）。 */
    fun fail(state: State, reason: String, nowMs: Long = 0L): State =
        state.copy(outcome = Outcome.FAILED, reason = reason, stepEnteredAtMs = nowMs)

    /** 游戏离开前台 ⇒ 暂停（不动作、不计时；回前台**不自动继续**，FR-05）。 */
    fun pause(state: State): State =
        if (!state.isRunning) state else state.copy(paused = true, reason = "游戏不在前台")

    /** 用户点「继续」：从暂停的那一步接着来。 */
    fun resume(state: State, nowMs: Long = 0L): State =
        if (!state.paused) state else state.copy(paused = false, reason = null, stepEnteredAtMs = nowMs)

    /**
     * 记下"这一击已发出"（口径 A：之后只等期望画面，不补点）。
     *
     * [stateAtLastAction] = 点击那一刻的画面，用于判断"画面确实换了另一屏"时提前结束观察窗口。
     */
    fun acted(state: State, nowMs: Long, stateAtLastAction: UiState?): State =
        state.copy(lastActionAtMs = nowMs, stateAtLastAction = stateAtLastAction)

    /** 撤销"已动手"（点击被门禁拒绝 / 被遮挡屏吞掉时用，见 `PatrolFlow.withoutPendingAction` 的同款理由）。 */
    fun withoutPendingAction(state: State): State =
        state.copy(lastActionAtMs = 0L, stateAtLastAction = null)

    /**
     * 收不到新帧 ⇒ **本步计时顺延到此刻**（闹钟暂停），宽限用完就不再顺延。
     *
     * 与 `PatrolFlow.rebaseOnStall` 同一口径：静态页永远不会出新帧，若无上限则本步永不超时 ⇒ 死等。
     */
    fun rebaseOnStall(state: State, nowMs: Long): State {
        if (!state.isRunning) return state
        val since = if (state.framesStalledSinceMs == 0L) nowMs else state.framesStalledSinceMs
        val withinGrace = nowMs - since <= STALL_REBASE_GRACE_MS
        return state.copy(
            framesStalledSinceMs = since,
            stepEnteredAtMs = if (withinGrace && state.stepEnteredAtMs != 0L) {
                state.stepEnteredAtMs + (nowMs - state.stepEnteredAtMs).coerceAtLeast(0L)
            } else {
                state.stepEnteredAtMs
            },
        )
    }

    /** 画面回来了（收到新帧）⇒ 清掉停更标记。 */
    fun onFramesResumed(state: State): State =
        if (state.framesStalledSinceMs == 0L) state else state.copy(framesStalledSinceMs = 0L)

    /**
     * **遮挡屏在屏 ⇒ 本步计时顺延到此刻**（活动弹窗 / 新手引导 / 新手大厅）。
     *
     * 那些屏是游戏自己弹的、由 FR-01/FR-02 关掉，关掉要 1~2 轮；**不该算进"我们这一步的超时"**。
     * 真机 2026-10-22：新号登录后「新手引导 + 新手大厅」两屏把第 1 步吃掉 8 秒，
     * 再加入无效点击的 4 秒冷却，19 秒才进活动中心（30 秒预算被游戏自己的加载占掉一半）。
     *
     * 顺延累计量封顶 [OBSTRUCTION_REBASE_LIMIT_MS]（否则遮挡屏一直不消失就永不超时）。
     */
    fun rebaseOnObstruction(state: State, nowMs: Long): State {
        if (!state.isRunning || state.stepEnteredAtMs == 0L) return state
        if (state.obstructedMs >= OBSTRUCTION_REBASE_LIMIT_MS) return state
        val waited = (nowMs - state.stepEnteredAtMs).coerceAtLeast(0L)
        val extended = minOf(waited, OBSTRUCTION_REBASE_LIMIT_MS - state.obstructedMs)
        return state.copy(
            stepEnteredAtMs = state.stepEnteredAtMs + extended,
            obstructedMs = state.obstructedMs + extended,
        )
    }
}
