package com.example.mastermechanic.badge

import com.example.mastermechanic.action.ClickRequest
import com.example.mastermechanic.action.ClickSource
import com.example.mastermechanic.decision.AnchorHit
import com.example.mastermechanic.decision.UiState

/**
 * 立牌支线的**每轮编排**（临时需求 temp/badge-share-1021，2026-10-21；纯逻辑、可 JVM 重跑）。
 *
 * 与 `PatrolRunner` 同一套边界与口径（刻意逐条对齐，读一处就懂另一处）：
 * - **不产生点击**：只给 [ClickRequest]，下发一律经 `ClickDispatch`（红线 6）；
 * - **不做识别**：画面 / 锚点 / OCR / 多实例消歧全由采集层喂进来；
 * - **一轮只做一件事**：等 / 点 / 进下一步 / 停下并说明原因；
 * - **口径 A**：本步一旦下过击，接下来只等期望画面（不重新定位、不补点）；
 * - **遮挡屏优先**：活动弹窗在屏时先等 FR-01 关掉（立牌自己的弹框**不是**遮挡屏）。
 *
 * ## 立牌特有的三处
 *
 * 1. **判定步**（空槽 / 选卡 / 读取）：不点击，消费采集层的结论（[ClaimPlan] / [CardPlan] / [Reading]）；
 * 2. **同屏验证**：点「领取」后画面不变，靠 `Action.expectAnchor`（「点击继续」出现）判通过；
 * 3. **已领取是合法结果**：两行都已领取 ⇒ [ClaimPlan.AlreadyClaimed] ⇒ 不报错、直接收尾（FR-T2）。
 */
object BadgeRunner {

    /** 一击之后留给画面切换的观察窗口（ms）——**上限**，画面确实换了且下一步有事可做时提前结束。 */
    const val SETTLE_WAIT_MS = 2_000L



    /**
     * "击后仍停在动手前那一屏"时，允许重发的冷却（ms）。
     *
     * 真机教训（2026-10-22）：点活动入口后画面从「大厅」变成「未知」并一直未知，口径 A 又不允许补点
     * ⇒ 只能干等到 30 秒超时。
     *
     * 取值 1.5 秒（当天从 4 秒收紧）：实测这一击失效的常见原因是**新号登录后的新手引导 / 新手大厅
     * 把它盖住**（FR-02 关掉后回到大厅），画面已经干净、没必要再等 4 秒；1.5 秒足够覆盖
     * "活动页真的在加载"那一段，30 秒预算内最多重试约 10 次。
     */
    const val ACTION_RETRY_MS = 1_500L

    /** 一轮的全部输入（全部来自采集层 / 定位器 / 时钟）。 */
    data class RoundInput(
        val foreground: Boolean,
        /** 收不到新帧（与点击门禁同一信号）：不判、不点，本步计时暂停（同 `PatrolRunner.RoundInput.framesStalled`）。 */
        val framesStalled: Boolean = false,
        /** 锚点名的中文说法（日志 / 中止原因里用它，别把契约名甩给用户）。 */
        val anchorLabel: (String) -> String = { it },
        /** 本轮识别出的界面（滞回结论）。 */
        val state: UiState,
        /** 本轮**原始命中**含活动弹窗（比滞回结论更灵敏）。 */
        val popupHit: Boolean,
        /** 滞回结论 = 活动弹窗。 */
        val popupConfirmed: Boolean,
        /** 当前状态定位到的锚点（按产物声明顺序）。 */
        val anchors: List<AnchorHit>,
        val nowMs: Long,
        /** 上一次点击的时刻（0 = 本次执行还没点过）。 */
        val lastClickAtMs: Long,
        /** 「领取」这一步点哪里（多实例按任务行文字消歧，见 [ClaimPlan]）。 */
        val claimPlan: ClaimPlan = ClaimPlan.Waiting,
        /** 「选卡」这一步点哪里（多实例按卡序消歧，见 [CardPlan]）。 */
        val cardPlan: CardPlan = CardPlan.Waiting,
        /** 两个读取步的 OCR 结论（[Reading]）。 */
        val reading: Reading = Reading.Waiting,
        /** 判定记录 ID（NFR-05 审计链）。 */
        val decisionId: String,
    )

    /**
     * 「领取」点哪里：三条路各自对应一句说得清的话。
     *
     * - [Click]：行文字对上了「登录游戏」，点该行按钮；
     * - [AlreadyClaimed]：该行已是「已领取」（没有可点的按钮）——**合法**，直接收尾（FR-T2）；
     * - [Failed]：识别不出唯一行 / 两行都像 ⇒ 停下报原因（红线 3/7）。
     */
    sealed interface ClaimPlan {
        data object Waiting : ClaimPlan
        data class Click(val frameX: Double, val frameY: Double, val detail: String) : ClaimPlan
        data class AlreadyClaimed(val detail: String) : ClaimPlan
        data class Failed(val reason: String) : ClaimPlan
    }

    /**
     * 「选卡」点哪里：同模板多实例，按卡的**位置顺序**逐个读（红线 3：不挑最像的）。
     *
     * @param remaining 这张点完之后还有几张没读（0 = 这是最后一张；只用于日志）
     */
    sealed interface CardPlan {
        data object Waiting : CardPlan
        data class Click(
            val frameX: Double,
            val frameY: Double,
            val detail: String,
            val remaining: Int = 0,
        ) : CardPlan

        /** 没有没读过的卡了 ⇒ 关闭「我的立牌」（不点击分享）。 */
        data class NoMore(val detail: String) : CardPlan

        data class Failed(val reason: String) : CardPlan
    }

    /**
     * 多个「分享」命中按**阅读顺序**排（2026-10-23）：先按行（y 相近的算一行），行内按 x。
     *
     * ## 为什么不能只按 y
     *
     * 用户 2026-10-23 确认立牌卡是**横向排列**的 ⇒ 同一行里各按钮的 y 有 ±几像素的匹配噪声，
     * 只按 y 排序会得到"随机"的行内顺序；只按 x 排序则在纵向堆叠时会把第二行接到第一行中间。
     * 这里用几何关系自己判：**y 跨度 ≤ x 跨度**（一条横排）⇒ 按 x；否则按 y 再按 x（多行）。
     *
     * 纯逻辑（[AnchorHit] 只是坐标数据），可 JVM 单测。
     */
    fun readingOrder(hits: List<com.example.mastermechanic.decision.AnchorHit>): List<com.example.mastermechanic.decision.AnchorHit> {
        if (hits.size <= 1) return hits
        val xSpan = hits.maxOf { it.frameX } - hits.minOf { it.frameX }
        val ySpan = hits.maxOf { it.frameY } - hits.minOf { it.frameY }
        val byX = compareBy<com.example.mastermechanic.decision.AnchorHit> { it.frameX }
        val byYThenX = compareBy<com.example.mastermechanic.decision.AnchorHit> { it.frameY }.then(byX)
        return if (ySpan <= xSpan) hits.sortedWith(byX) else hits.sortedWith(byYThenX)
    }

    /** 两个读取步的 OCR 结论。 */
    sealed interface Reading {
        data object Waiting : Reading

        /** 三块都解析出唯一值（采集层已组装好记录，**还没写盘**）。 */
        data class Ok(val record: BadgeRecord, val detail: String) : Reading

        /** 解析不出 / 不唯一 ⇒ 停下报原因（FR-T5 / 红线 7）。 */
        data class Failed(val reason: String) : Reading
    }

    /** 一轮的结论。 */
    data class RoundResult(
        val state: BadgeFlow.State?,
        /** 本轮要下发的点击；null = 这一轮不动手。 */
        val request: ClickRequest? = null,
        /** 给用户看的一句话（悬浮窗 / 日志）。 */
        val note: String? = null,
        /** 本轮验证通过的那条记录（采集层负责写盘；null = 没有新记录）。 */
        val record: BadgeRecord? = null,
    ) {
        val finished: Boolean get() = state?.outcome == BadgeFlow.Outcome.FINISHED
        val failed: Boolean get() = state?.outcome == BadgeFlow.Outcome.FAILED
    }

    private val idle = RoundResult(state = null)

    /**
     * 一轮编排：看画面 → 决定"等 / 点 / 进下一步 / 停下"。
     *
     * @param current 当前进度；null = 没有在跑的立牌支线
     */
    fun onRound(current: BadgeFlow.State?, input: RoundInput): RoundResult {
        if (current == null) return idle

        // ① 前台：不在前台 → 暂停（回前台不自动继续，FR-05）
        if (!input.foreground) {
            return RoundResult(BadgeFlow.pause(current), note = "游戏不在前台，已暂停——回前台后点「继续」")
        }
        if (current.paused) return RoundResult(current, note = "已暂停，等用户点「继续」")
        if (!current.isRunning) return RoundResult(current, note = current.reason)

        // ⓪ 我们暂时看不见画面：不判、不点，本步计时顺延（宽限见 BadgeFlow.STALL_REBASE_GRACE_MS）
        if (input.framesStalled) {
            val rebased = BadgeFlow.rebaseOnStall(current, input.nowMs)
            if (BadgeFlow.timedOut(rebased, input.nowMs)) {
                val reason = stallAbortReason(rebased, input)
                return RoundResult(BadgeFlow.fail(rebased, reason, input.nowMs), note = "已中止：$reason")
            }
            return RoundResult(
                rebased,
                note = "画面久未更新（收不到新帧）：先不动手，这一步的计时暂停；碰一下屏幕或重新授权采集",
            )
        }
        val running = BadgeFlow.onFramesResumed(current)

        // ② 遮挡屏优先（活动弹窗 / 新手两屏）：盖着时点了也是白点，等 FR-01 / FR-02 关掉。
        //
        // **本步计时同时顺延**（2026-10-22 用户口径）：新号登录后被「新手引导 + 新手大厅」吃掉 8 秒，
        // 那是游戏自己的加载时间，不该算进我们这一步的 30 秒预算（真机那次因此逼近超时）。
        val obstruction = when (input.state) {
            UiState.ACTIVITY_POPUP -> "活动弹窗（FR-01）"
            UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL -> "新手界面（FR-02）"
            else -> null
        }
        if (input.popupConfirmed || input.popupHit || obstruction != null) {
            val rebased = BadgeFlow.rebaseOnObstruction(running, input.nowMs)
            val swallowed = running.lastActionAtMs > 0
            val who = obstruction ?: "活动弹窗（FR-01）"
            return RoundResult(
                if (swallowed) BadgeFlow.withoutPendingAction(rebased) else rebased,
                note = "等$who 关掉再继续第 ${running.step.number} 步" +
                    if (swallowed) "（刚才那一枪作废：关掉后没到期望画面就重发）" else "",
            )
        }

        val anchorNames = input.anchors.mapTo(mutableSetOf()) { it.name }
        // ③ 第 1 步的「就绪门」（2026-10-22 两屏合并）：离开活动中心就清零，重新进入重新起算
        val prepared = if (running.step == BadgeFlow.Step.ENTER_ACTIVITY &&
            input.state != UiState.ACTIVITY_CENTER &&
            running.centerSinceMs > 0L
        ) {
            running.copy(centerSinceMs = 0L)
        } else {
            running
        }
        if (prepared.step == BadgeFlow.Step.ENTER_ACTIVITY && input.state == UiState.ACTIVITY_CENTER) {
            enterActivityGate(prepared, input, anchorNames)?.let { return it }
        }
        return when (prepared.step) {
            BadgeFlow.Step.DECIDE_BRANCH -> decideBranch(prepared, input, anchorNames)
            BadgeFlow.Step.PICK_CARD -> pickCard(prepared, input)
            BadgeFlow.Step.READ_FIRST, BadgeFlow.Step.READ_CARD -> read(prepared, input)
            else -> clickStep(prepared, input, anchorNames)
        }
    }

    /**
     * 第 1 步的**就绪门**（2026-10-22 两屏合并后新增）。
     *
     * 活动中心的标志只有左上返回 —— 立牌活动与"点活动进错的那一页"**都命中它**，
     * 所以"进了活动中心"不再等于"进了立牌活动"。唯一可判的证据是**等不等得到「获取立牌」**：
     *
     * - 就绪 ⇒ 本步直接通过（不点任何东西），进第 2 步；
     * - 未就绪、且离起算不足 [BadgeFlow.ACTIVITY_READY_TIMEOUT_MS] ⇒ 继续等（动效 3~8 秒，别急）；
     * - 等满仍不出现 ⇒ 返回 null，交给 [clickStep] 点**已定位到的**左上返回（那一页是别的活动），
     *   退回大厅后重新点「活动」，次数由 [BadgeFlow.ENTRY_MAX_ATTEMPTS] 兜住。
     *
     * ⚠ 日志只说**读数**（"等了 N 秒仍没看到「获取立牌」"），不说"判定为其他活动"——后者是推断。
     */
    private fun enterActivityGate(
        state: BadgeFlow.State,
        input: RoundInput,
        anchorNames: Set<String>,
    ): RoundResult? {
        if (BadgeScenes.activityReady(anchorNames)) {
            val next = BadgeFlow.advance(state, input.nowMs)
            return RoundResult(
                next,
                note = "第 1 步：${UiState.ACTIVITY_CENTER.label}已就绪（「获取立牌」「分享立牌」都在）→ 第 2 步",
            )
        }
        val since = if (state.centerSinceMs > 0L) state.centerSinceMs else input.nowMs
        val waited = input.nowMs - since
        val missing = BadgeScenes.readyAnchors.filterNot { it in anchorNames }
            .joinToString("、") { input.anchorLabel(it) }
        if (waited < BadgeFlow.ACTIVITY_READY_TIMEOUT_MS) {
            return RoundResult(
                state.copy(centerSinceMs = since),
                note = "第 1 步：在「${UiState.ACTIVITY_CENTER.label}」等就绪（还差「$missing」；" +
                    "已等 ${waited / 1000} 秒，最多 ${BadgeFlow.ACTIVITY_READY_TIMEOUT_MS / 1000} 秒）",
            )
        }

        // 等满了：这一屏**不是**立牌活动 ⇒ 点左上返回，退回大厅重进。
        //
        // 为什么在这里自己判定、不交给 [clickStep]：那里 ④ 会把「已到活动中心」当成第 1 步通过
        // （大厅那条动作的期望画面就是活动中心）⇒ 等满 10 秒后反而"通过"，永远走不到点返回。
        // 这里仍走同一套约束：**锚点必须定位到**才点、点击间隔、记录"已动手"、按 [ACTION_RETRY_MS] 冷却重试。
        val back = BadgeScenes.actions(BadgeFlow.Step.ENTER_ACTIVITY)
            .firstOrNull { it.state == UiState.ACTIVITY_CENTER } ?: return null
        val hit = input.anchors.firstOrNull { it.name == back.anchor }
            ?: return RoundResult(
                state,
                note = "第 1 步：等了 ${waited / 1000} 秒仍没看到「$missing」，但锚点" +
                    "「${input.anchorLabel(back.anchor)}」没定位到 ⇒ 不点，继续等",
            )
        val backPending = state.lastActionAtMs > 0 && state.stateAtLastAction == UiState.ACTIVITY_CENTER
        if (backPending && input.nowMs - state.lastActionAtMs < ACTION_RETRY_MS) {
            return RoundResult(state, note = "第 1 步：刚点过返回，等画面切回大厅")
        }
        if (input.nowMs - input.lastClickAtMs < BadgeFlow.CLICK_GAP_MS) {
            return RoundResult(state, note = "点击间隔未满 ${BadgeFlow.CLICK_GAP_MS}ms，等下一轮")
        }
        return RoundResult(
            BadgeFlow.acted(state, input.nowMs, input.state),
            ClickRequest(
                decisionId = input.decisionId,
                source = ClickSource.BADGE_STEP,
                anchorName = back.anchor,
                frameX = hit.frameX,
                frameY = hit.frameY,
            ),
            "第 1 步：在「${UiState.ACTIVITY_CENTER.label}」等了 ${waited / 1000} 秒仍没看到" +
                "「$missing」⇒ 点「${input.anchorLabel(back.anchor)}」退回大厅，重新点「活动入口」" +
                "（第 ${state.entryAttempts}/${BadgeFlow.ENTRY_MAX_ATTEMPTS} 次尝试没进到立牌活动）",
        )
    }

    /**
     * 判定步：活动中心**可交互**（两个入口都定位到）+ 最少再等 [BadgeScenes.MIN_SETTLE_MS] 之后，
     * 用**空立牌槽的存在性**决定走领取还是直接分享（2026-10-21 三态对照帧）。
     */
    private fun decideBranch(
        state: BadgeFlow.State,
        input: RoundInput,
        anchorNames: Set<String>,
    ): RoundResult {
        if (input.state != UiState.ACTIVITY_CENTER) {
            return waitOrAbort(
                state,
                input,
                "第 ${state.step.number} 步：等「${UiState.ACTIVITY_CENTER.label}」，现在${describe(input.state)}",
            )
        }
        if (!BadgeScenes.activityReady(anchorNames)) {
            val missing = BadgeScenes.readyAnchors.filterNot { it in anchorNames }
                .joinToString("、") { input.anchorLabel(it) }
            return waitOrAbort(
                state,
                input,
                "第 ${state.step.number} 步：${UiState.ACTIVITY_CENTER.label}还没就绪" +
                    "（自动提交动效可能还在播；缺「$missing」）",
            )
        }
        val waited = BadgeFlow.waitedMs(state, input.nowMs)
        if (waited < BadgeScenes.MIN_SETTLE_MS) {
            return RoundResult(
                state,
                note = "第 ${state.step.number} 步：${UiState.ACTIVITY_CENTER.label}已可交互，再稳 " +
                    "${(BadgeScenes.MIN_SETTLE_MS - waited) / 1000 + 1} 秒再判今日立牌",
            )
        }
        // **「不在」需要连续确认，「在」是硬证据立刻采信**（2026-10-22）：
        // 空槽缺席既可能是"真的已领取"，也可能只是这一轮没搜到（被红点/动效盖住、框偏了），
        // 一轮定生死会直接跳过领取。连续 [BadgeFlow.EMPTY_SLOT_ABSENT_CONFIRM_ROUNDS] 轮都缺席才认。
        val slotSeen = BadgeAnchors.BADGE_EMPTY_SLOT in anchorNames
        val absentRounds = if (slotSeen) 0 else state.emptySlotAbsentRounds + 1
        if (!slotSeen && absentRounds < BadgeFlow.EMPTY_SLOT_ABSENT_CONFIRM_ROUNDS) {
            return RoundResult(
                state.copy(emptySlotAbsentRounds = absentRounds),
                note = "第 ${state.step.number} 步：这一轮没定位到「空立牌槽」" +
                    "（第 $absentRounds/${BadgeFlow.EMPTY_SLOT_ABSENT_CONFIRM_ROUNDS} 轮确认；" +
                    "可能真的已领取，也可能只是没搜到）",
            )
        }
        val needsClaim = slotSeen
        val next = BadgeFlow.decide(state.copy(emptySlotAbsentRounds = absentRounds), needsClaim, input.nowMs)
        return RoundResult(
            next,
            note = if (needsClaim) {
                "第 ${state.step.number} 步：空立牌槽在 ⇒ 今日还没领取，走「获取立牌」"
            } else {
                "第 ${state.step.number} 步：连续 $absentRounds 轮都没定位到空立牌槽" +
                    " ⇒ 今日已获取，直接「分享立牌」"
            } + " → 第 ${next.step.number} 步",
        )
    }

    /** 选卡步：有没读过的卡就点它的「分享」，全部读过就关闭我的立牌。 */
    private fun pickCard(state: BadgeFlow.State, input: RoundInput): RoundResult {
        return when (val plan = input.cardPlan) {
            BadgeRunner.CardPlan.Waiting -> waitOrAbort(
                state,
                input,
                "第 ${state.step.number} 步：在「我的立牌」里找还没读过的卡",
            )

            is BadgeRunner.CardPlan.Failed -> fail(state, input, plan.reason)

            is BadgeRunner.CardPlan.NoMore -> {
                val next = BadgeFlow.advanceTo(state, BadgeFlow.Step.CLOSE_MY, input.nowMs)
                RoundResult(
                    next,
                    note = "第 ${state.step.number} 步：${plan.detail} ⇒ 关闭「我的立牌」",
                )
            }

            is BadgeRunner.CardPlan.Click -> {
                if (input.nowMs - input.lastClickAtMs < BadgeFlow.CLICK_GAP_MS) {
                    return RoundResult(state, note = "点击间隔未满 ${BadgeFlow.CLICK_GAP_MS}ms，等下一轮")
                }
                val next = BadgeFlow.advance(state, input.nowMs)
                RoundResult(
                    BadgeFlow.acted(next, input.nowMs, input.state),
                    ClickRequest(
                        decisionId = input.decisionId,
                        source = ClickSource.BADGE_STEP,
                        anchorName = BadgeAnchors.BADGE_SHARE,
                        frameX = plan.frameX,
                        frameY = plan.frameY,
                    ),
                    "第 ${state.step.number} 步：点这张卡的「分享」（${plan.detail}）",
                )
            }
        }
    }

    /** 读取步：OCR 结论 → 记录 / 推进 / 停下。 */
    private fun read(state: BadgeFlow.State, input: RoundInput): RoundResult =
        when (val reading = input.reading) {
            BadgeRunner.Reading.Waiting -> {
                // 画面还没到复制分享（刚点完分享 / 切换）⇒ 等；到了但还没读出来也等
                if (input.state != UiState.BADGE_SHARE) {
                    waitOrAbort(
                        state,
                        input,
                        "第 ${state.step.number} 步：等「分享立牌弹框」，现在${describe(input.state)}",
                    )
                } else {
                    waitOrAbort(state, input, "第 ${state.step.number} 步：正在读立牌信息（分 / 名称 / 分享码）")
                }
            }

            is BadgeRunner.Reading.Failed -> fail(state, input, reading.reason)

            is BadgeRunner.Reading.Ok -> {
                val next = if (state.step == BadgeFlow.Step.READ_CARD) {
                    BadgeFlow.cardRead(state, input.nowMs)
                } else {
                    BadgeFlow.advance(state, input.nowMs)
                }
                RoundResult(
                    next,
                    note = "第 ${state.step.number} 步：读到「${reading.record.cardName}」" +
                        "${reading.record.score} 分、码 ${reading.record.code}（${reading.detail}）" +
                        " → 第 ${next.step.number} 步",
                    record = reading.record,
                )
            }
        }

    /**
     * 点击步：先验证上一步的结果，再决定要不要点（与 `PatrolRunner` ④⑤⑧⑨⑩⑪ 同序）。
     */
    private fun clickStep(
        state: BadgeFlow.State,
        input: RoundInput,
        anchorNames: Set<String>,
    ): RoundResult {
        val actions = BadgeScenes.actions(state.step)

        // ④ 验证：看到期望画面（同屏动作还要看到 expectAnchor）⇒ 进下一步
        //
        // [BadgeScenes.Action.restartStep] 的动作（第 1 步的「返回」）只在**刚才那一击确实是它**时才算通过：
        // 否则人已经站在大厅（返回的落点），那条动作每轮都会"通过"，步骤就再也走不到点「活动入口」。
        // @param restartStep 的动作还要额外确认"刚才那一击就是在它那一屏上发的"（否则回到大厅后
        // 每轮都会被它判成通过，步骤永远走不到点「活动入口」）。
        val passed = actions.firstOrNull {
            BadgeScenes.check(it, input.state, anchorNames) &&
                (!it.restartStep || (state.lastActionAtMs > 0 && state.stateAtLastAction == it.state))
        }
        if (passed != null) {
            // 第 1 步的兜底动作（在「其他活动页」点返回）：点成功了，但**这一步的目标还没达成** ⇒
            // 不推进步骤，清掉"已动手"标记，下一轮回到大厅继续点「活动入口」。
            if (passed.restartStep) {
                return RoundResult(
                    BadgeFlow.withoutPendingAction(state),
                    note = "第 ${state.step.number} 步：已退回大厅（第 ${state.entryAttempts} 次进入尝试没进到立牌活动）" +
                        " ⇒ 重新点「活动入口」",
                )
            }
            var next = BadgeFlow.advance(state, input.nowMs)
            var jump = ""
            // 读完一张卡、关掉复制分享之后**必须回到「选卡」**判断还有没有未读卡
            // （顺序链上的下一步是「关闭我的立牌」，直接走过去会在还有卡时把弹框关掉 —— 真机 2026-10-22）。
            // 若那个 X 把「我的立牌」那层也一起关掉（只有 1 张卡时实测如此）⇒ 已回活动中心，直接准备返回大厅。
            if (state.step == BadgeFlow.Step.CLOSE_CARD) {
                // ⚠ 2026-10-23 起**正常流程不再进这一步**：每读完一张卡改为点「切换」回「我的立牌」
                //（见 `BadgeFlow.cardRead`），所以走不到这里。保留这段是为了"表里还声明着这张屏/这个锚点"
                // 时行为仍然合理（万一手工改状态或旧产物回放），不新增任何假设。
                if (input.state == UiState.ACTIVITY_CENTER) {
                    next = BadgeFlow.advanceTo(state, BadgeFlow.Step.EXIT_ACTIVITY, input.nowMs)
                    jump = "（关闭 X 连「我的立牌」一起关了 ⇒ 跳过关闭弹框）"
                } else {
                    next = BadgeFlow.advanceTo(state, BadgeFlow.Step.PICK_CARD, input.nowMs)
                    jump = "（回「我的立牌」看还有没有未读卡）"
                }
            }
            return RoundResult(
                next,
                note = if (next.finished) {
                    "本区服已完成"
                } else {
                    "第 ${state.step.number} 步通过 → 第 ${next.step.number} 步$jump"
                },
            )
        }

        // ⑤ 本步已经下过击 ⇒ 只等期望画面（口径 A：不重新定位、不补点）。
        //
        // **例外**（真机 2026-10-22）：画面**明确回到动手前那一屏** ⇒ 那一击没生效（没进活动中心），
        // 冷却 [ACTION_RETRY_MS] 后撤销"已动手"，让下一轮重新定位、重发。
        // 没有这条，点一次没反应就只能干等到本步超时（实测 30 秒后误报中止）。
        if (state.lastActionAtMs > 0 && !screenMovedOn(state, input)) {
            val backOnActionScreen = actions.any { it.state == input.state }
            if (backOnActionScreen && input.nowMs - state.lastActionAtMs >= ACTION_RETRY_MS) {
                return RoundResult(
                    BadgeFlow.withoutPendingAction(state),
                    note = "第 ${state.step.number} 步：画面还停在动手前那一屏（上一击没生效）⇒ 撤销，下一轮重试",
                )
            }
            val what = if (input.state == UiState.UNKNOWN) {
                "第 ${state.step.number} 步：刚点过，画面还没看清（可能正在加载）"
            } else {
                "第 ${state.step.number} 步：刚点过，等画面切换"
            }
            return waitOrAbort(state, input, what)
        }

        // ⑥ 画面还没看清（多半在加载）：等，不消耗重试
        if (input.state == UiState.UNKNOWN) {
            return waitOrAbort(state, input, "第 ${state.step.number} 步：画面还没看清（可能正在加载）")
        }

        // ⑦ 「领取」的已领取路径：没有可点的按钮是**合法结果**（FR-T2），不点、直接收尾
        if (state.step == BadgeFlow.Step.CLAIM_TASK) {
            when (val plan = input.claimPlan) {
                is BadgeRunner.ClaimPlan.AlreadyClaimed -> {
                    val next = BadgeFlow.advance(state, input.nowMs)
                    return RoundResult(
                        next,
                        note = "第 ${state.step.number} 步：${plan.detail} ⇒ 不重领，直接收尾",
                    )
                }

                is BadgeRunner.ClaimPlan.Failed -> return fail(state, input, plan.reason)

                is BadgeRunner.ClaimPlan.Click -> {
                    if (input.state != UiState.BADGE_CLAIM) {
                        return waitOrAbort(
                            state,
                            input,
                            "第 ${state.step.number} 步：等「获取立牌弹框」，现在${describe(input.state)}",
                        )
                    }
                    if (input.nowMs - input.lastClickAtMs < BadgeFlow.CLICK_GAP_MS) {
                        return RoundResult(state, note = "点击间隔未满 ${BadgeFlow.CLICK_GAP_MS}ms，等下一轮")
                    }
                    return RoundResult(
                        BadgeFlow.acted(state, input.nowMs, input.state),
                        ClickRequest(
                            decisionId = input.decisionId,
                            source = ClickSource.BADGE_STEP,
                            anchorName = BadgeAnchors.BADGE_CLAIM,
                            frameX = plan.frameX,
                            frameY = plan.frameY,
                        ),
                        "第 ${state.step.number} 步：点「登录游戏」那行的「领取」（${plan.detail}）" +
                            "，之后应看到「${input.anchorLabel(BadgeAnchors.BADGE_CONTINUE)}」",
                    )
                }

                // **等**，绝不落到下面的"按锚点直接点"：多实例（两行各一个领取按钮）没经行文字消歧就点，
                // 等于按锚点乱挑一个（红线 3）。采集层还没算出位置时唯一的合法动作是等。
                BadgeRunner.ClaimPlan.Waiting -> return waitOrAbort(
                    state,
                    input,
                    "第 ${state.step.number} 步：正在认「登录游戏」那一行的领取按钮",
                )
            }
        }

        // ⑧ 这一步在当前画面上有没有要点的东西
        val candidate = actions.firstOrNull { it.state == input.state }
            ?: return waitOrAbort(
                state,
                input,
                "第 ${state.step.number} 步：画面是「${input.state.label}」，不是要动手的地方",
            )

        // ⑨ 锚点定位：按 ID 精确取；同屏有多个候选时取**第一个确实定位到的**（语义不同，不是"最像的"）
        val hit = input.anchors.firstOrNull { it.name == candidate.anchor }
            ?: return waitOrAbort(
                state,
                input,
                "第 ${state.step.number} 步：锚点「${input.anchorLabel(candidate.anchor)}」没定位到" +
                    "（未标定或没命中）",
            )

        // ⑩ 节流
        if (input.nowMs - input.lastClickAtMs < BadgeFlow.CLICK_GAP_MS) {
            return RoundResult(state, note = "点击间隔未满 ${BadgeFlow.CLICK_GAP_MS}ms，等下一轮")
        }

        // ⑩½ 第 1 步的**次数上限**：点「活动」可能进的是别的活动页，兜底是返回再点；
        // 用户口径"极大概率"能进但不是必然 ⇒ 到上限就停（FR-T5），不无限重试。
        val countingEntry = state.step == BadgeFlow.Step.ENTER_ACTIVITY &&
            candidate.anchor == BadgeAnchors.HALL_ACTIVITY
        val attempts = if (countingEntry) state.entryAttempts + 1 else state.entryAttempts
        if (countingEntry && attempts > BadgeFlow.ENTRY_MAX_ATTEMPTS) {
            return fail(
                state,
                input,
                "第 ${state.step.number} 步：连续 ${BadgeFlow.ENTRY_MAX_ATTEMPTS} 次点「活动入口」都没进到立牌活动" +
                    "（每次都进的是别的活动页，返回重进也没用）⇒ 停下，请手动进入一次立牌活动后重跑",
            )
        }

        // ⑪ 点这一下
        val earlyExit = input.nowMs - state.lastActionAtMs < SETTLE_WAIT_MS
        val expectText = candidate.expect?.label
            ?: input.anchorLabel(candidate.expectAnchor ?: "")
        val clickState = if (countingEntry) state.copy(entryAttempts = attempts) else state
        return RoundResult(
            BadgeFlow.acted(clickState, input.nowMs, input.state),
            ClickRequest(
                decisionId = input.decisionId,
                source = ClickSource.BADGE_STEP,
                anchorName = candidate.anchor,
                frameX = hit.frameX,
                frameY = hit.frameY,
            ),
            "第 ${state.step.number} 步：点「${input.anchorLabel(candidate.anchor)}」" +
                (if (countingEntry) "（第 $attempts/${BadgeFlow.ENTRY_MAX_ATTEMPTS} 次尝试进立牌活动）" else "") +
                "，之后应看到「$expectText」" +
                if (earlyExit) "（画面已切换，观察窗口提前结束）" else "",
        )
    }

    /**
     * 画面是否已经**确实换了另一屏**（观察窗口据此提前结束，同 `PatrolRunner.screenMovedOn`）。
     *
     * 三条缺一不可：有"点击那一刻的画面"可比 / 当前不是 `未知` / 画面变了且下一步有事可做。
     */
    private fun screenMovedOn(state: BadgeFlow.State, input: RoundInput): Boolean {
        val clicked = state.stateAtLastAction ?: return false
        if (input.state == UiState.UNKNOWN || input.state == clicked) return false
        if (!state.isRunning) return false
        val nextStep = BadgeFlow.advancedStepOf(state.step) ?: return false
        return BadgeScenes.chainStates(nextStep).contains(input.state)
    }

    /** 「等，等不到就如实停下」：不消耗重试，到本步预算才中止。 */
    private fun waitOrAbort(state: BadgeFlow.State, input: RoundInput, what: String): RoundResult =
        if (BadgeFlow.timedOut(state, input.nowMs)) {
            val reason = timeoutReason(state, input)
            RoundResult(BadgeFlow.fail(state, reason, input.nowMs), note = "已中止：$reason")
        } else {
            RoundResult(state, note = "$what，继续等（已等 ${BadgeFlow.waitedMs(state, input.nowMs) / 1000} 秒）")
        }

    /** 停下：一句话说清是哪一步、为什么（FR-T5）。 */
    private fun fail(state: BadgeFlow.State, input: RoundInput, reason: String): RoundResult {
        val full = "第 ${state.step.number} 步（${state.step.label}）：$reason"
        return RoundResult(BadgeFlow.fail(state, full, input.nowMs), note = "已中止：$full")
    }

    /** 超时原因：只陈述事实（等了多久 / 期望什么 / 现在是什么），不猜成因。 */
    private fun timeoutReason(state: BadgeFlow.State, input: RoundInput): String {
        val waited = BadgeFlow.waitedMs(state, input.nowMs) / 1000
        val expected = BadgeScenes.expectedStates(state.step)
            .joinToString(" / ") { it.label }
            .ifEmpty { state.step.label }
        val since = if (state.lastActionAtMs > 0) "点下去之后等了" else "等了"
        return "第 ${state.step.number} 步（${state.step.label}）：$since $waited 秒仍未识别出要等的画面" +
            "（期望「$expected」，现在${describe(input.state)}）"
    }

    /** 停更宽限用完：根因是收不到画面，不是认不出（同 `PatrolRunner.stallAbortReason`）。 */
    private fun stallAbortReason(state: BadgeFlow.State, input: RoundInput): String {
        val stalled = if (state.framesStalledSinceMs > 0L) {
            (input.nowMs - state.framesStalledSinceMs) / 1000
        } else {
            -1L
        }
        val acted = if (state.lastActionAtMs > 0L) {
            "这一步已经点下去了（可能已经生效，只是无法确认）"
        } else {
            "这一步还没能动手"
        }
        return "第 ${state.step.number} 步（${state.step.label}）：收不到新画面，持续 $stalled 秒" +
            "（宽限 ${BadgeFlow.STALL_REBASE_GRACE_MS / 1000} 秒已用完）⇒ 中止；$acted"
    }

    /** 一句话说清"现在看到的是什么"。 */
    private fun describe(state: UiState): String = when (state) {
        UiState.ACTIVITY_POPUP -> "是活动弹窗（先由 FR-01 自动关闭点掉）"
        UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL -> "是新手界面（先由 FR-02 自动关闭点掉）"
        UiState.UNKNOWN -> "认不出来"
        else -> "是「${state.label}」"
    }
}
