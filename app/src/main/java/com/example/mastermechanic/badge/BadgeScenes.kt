package com.example.mastermechanic.badge

import com.example.mastermechanic.decision.UiState

/**
 * 立牌支线每一步「在哪个画面、点哪个锚点、点完看到什么」（临时需求 temp/badge-share-1021）。
 *
 * 与 `PatrolAnchors.actions` 同形，但多两项立牌特有的东西：
 *
 * 1. **[Action.expectAnchor]**：同屏验证。点了「领取」之后画面**还是**获取立牌弹框，
 *    只有「点击继续」按钮冒出来 ⇒ 用"期望画面"验证会**立刻通过**（点之前就成立），必须靠锚点出现来验证；
 * 2. **判定步**（[Step.DECIDE_BRANCH] / [Step.PICK_CARD] / 两个读取步）**没有动作**：
 *    它们的结论由采集层算好（空槽存在性 / 卡片列表 / OCR 结果），本表只声明"它们的验证目标是什么"。
 *
 * 三条红线在这张表上的落点：
 * - 每个动作都带 `state`（**先在确认的画面上动手**，红线 1/7）；
 * - 每个点击动作都带验证目标（`expect` 或 `expectAnchor`，红线 5）；
 * - 多实例（领取 / 分享）**不在这里选**，由采集层按行文字 / 卡序给出唯一位置（红线 3）。
 */
object BadgeScenes {

    /**
     * 一次动作：在 [state] 确认画面，点 [anchor]，做完应看到 [expect] 画面**或** [expectAnchor] 出现。
     *
     * 两者至少有一个非空（否则就是"点完不验证"，红线 5）。
     */
    data class Action(
        val state: UiState,
        val anchor: String,
        val expect: UiState? = null,
        val expectAnchor: String? = null,
    )

    /**
     * 这一步要按顺序尝试的动作；**空列表 = 这一步不点**（判定步 / 读取步）。
     *
     * 同一屏有多个候选时按**列表顺序**取第一个"锚点确实定位到"的（[CLAIM_FINISH]：
     * 有「点击继续」就点它，没有（已领取路径）才点关闭 X）。这不是"挑最像的"——
     * 两者是**语义不同的两个控件**，选谁由"哪个存在"决定，与识别分数无关。
     */
    fun actions(step: BadgeFlow.Step): List<Action> = when (step) {
        BadgeFlow.Step.ENTER_ACTIVITY -> listOf(
            Action(UiState.HALL, BadgeAnchors.HALL_ACTIVITY, expect = UiState.ACTIVITY_CENTER),
        )

        // 判定步：不点击（就绪判定 + 空槽存在性 ⇒ needsClaim）
        BadgeFlow.Step.DECIDE_BRANCH -> emptyList()

        BadgeFlow.Step.CLAIM_ENTRY -> listOf(
            Action(
                UiState.ACTIVITY_CENTER,
                BadgeAnchors.BADGE_CLAIM_ENTRY,
                expect = UiState.BADGE_CLAIM,
            ),
        )

        // 点「领取」：画面不变，标志是「点击继续」出现（expectAnchor）
        BadgeFlow.Step.CLAIM_TASK -> listOf(
            Action(
                UiState.BADGE_CLAIM,
                BadgeAnchors.BADGE_CLAIM,
                expectAnchor = BadgeAnchors.BADGE_CONTINUE,
            ),
        )

        // 收尾：优先「点击继续」，没有才点关闭；两者都回活动中心
        BadgeFlow.Step.CLAIM_FINISH -> listOf(
            Action(
                UiState.BADGE_CLAIM,
                BadgeAnchors.BADGE_CONTINUE,
                expect = UiState.ACTIVITY_CENTER,
            ),
            Action(
                UiState.BADGE_CLAIM,
                BadgeAnchors.BADGE_CLAIM_CLOSE,
                expect = UiState.ACTIVITY_CENTER,
            ),
        )

        BadgeFlow.Step.SHARE_ENTRY -> listOf(
            Action(
                UiState.ACTIVITY_CENTER,
                BadgeAnchors.BADGE_SHARE_ENTRY,
                expect = UiState.BADGE_SHARE,
            ),
        )

        // 读取步：不点击（OCR 结论由采集层给）
        BadgeFlow.Step.READ_FIRST -> emptyList()

        BadgeFlow.Step.SWITCH_TO_MY -> listOf(
            Action(UiState.BADGE_SHARE, BadgeAnchors.BADGE_SWITCH, expect = UiState.BADGE_MY),
        )

        // 判定步：有卡 ⇒ 点该卡的「分享」（位置由采集层给）；没有 ⇒ 推进到关闭我的立牌
        BadgeFlow.Step.PICK_CARD -> emptyList()

        BadgeFlow.Step.READ_CARD -> emptyList()

        // 关闭复制分享：**两条合法结果**（真机 2026-10-22）
        // - 回「我的立牌」⇒ 还有卡就读下一张；
        // - 直接回「活动中心」⇒ 这个 X 把「我的立牌」那层一起关掉了（当时只有 1 张卡），跳过「关闭我的立牌」
        BadgeFlow.Step.CLOSE_CARD -> listOf(
            Action(UiState.BADGE_SHARE, BadgeAnchors.BADGE_SHARE_CLOSE, expect = UiState.BADGE_MY),
            Action(UiState.BADGE_SHARE, BadgeAnchors.BADGE_SHARE_CLOSE, expect = UiState.ACTIVITY_CENTER),
        )

        BadgeFlow.Step.CLOSE_MY -> listOf(
            Action(UiState.BADGE_MY, BadgeAnchors.BADGE_MY_CLOSE, expect = UiState.ACTIVITY_CENTER),
        )

        BadgeFlow.Step.EXIT_ACTIVITY -> listOf(
            Action(
                UiState.ACTIVITY_CENTER,
                BadgeAnchors.BADGE_ACTIVITY_EXIT,
                expect = UiState.HALL,
            ),
        )
    }

    /** 这一步的动作所在画面（`actionStates` 的唯一来源）。 */
    fun actionStates(step: BadgeFlow.Step): List<UiState> =
        actions(step).map { it.state }.distinct()

    /**
     * 这一步"做完之后应该看到什么"；**空集 = 本步不靠画面验证**
     * （两次读取靠 OCR 结论、[PICK_CARD] 的两条路各自不同）。
     *
     * [DECIDE_BRANCH] 返回自身：它就站在活动中心，验证的是"还在这一屏、且动效结束"。
     */
    fun expectedStates(step: BadgeFlow.Step): Set<UiState> = when (step) {
        BadgeFlow.Step.ENTER_ACTIVITY -> setOf(UiState.ACTIVITY_CENTER)
        BadgeFlow.Step.DECIDE_BRANCH -> setOf(UiState.ACTIVITY_CENTER)
        BadgeFlow.Step.CLAIM_ENTRY -> setOf(UiState.BADGE_CLAIM)
        // 同屏验证：画面仍是获取立牌弹框，靠 expectAnchor 判通过（见 [Action.expectAnchor]）
        BadgeFlow.Step.CLAIM_TASK -> setOf(UiState.BADGE_CLAIM)
        BadgeFlow.Step.CLAIM_FINISH -> setOf(UiState.ACTIVITY_CENTER)
        BadgeFlow.Step.SHARE_ENTRY -> setOf(UiState.BADGE_SHARE)
        BadgeFlow.Step.READ_FIRST -> setOf(UiState.BADGE_SHARE)
        BadgeFlow.Step.SWITCH_TO_MY -> setOf(UiState.BADGE_MY)
        // 有卡 ⇒ 进复制分享；没有 ⇒ 留在我的立牌（两条都由采集层的 CardPlan 决定）
        BadgeFlow.Step.PICK_CARD -> setOf(UiState.BADGE_SHARE, UiState.BADGE_MY)
        BadgeFlow.Step.READ_CARD -> setOf(UiState.BADGE_SHARE)
        BadgeFlow.Step.CLOSE_CARD -> setOf(UiState.BADGE_MY, UiState.ACTIVITY_CENTER)
        BadgeFlow.Step.CLOSE_MY -> setOf(UiState.ACTIVITY_CENTER)
        BadgeFlow.Step.EXIT_ACTIVITY -> setOf(UiState.HALL)
    }

    /**
     * 这一步是不是"**不点击、由采集层给结论**"的步：
     * 空槽判定 / 选卡 / 两个读取步。
     */
    fun isJudgmentStep(step: BadgeFlow.Step): Boolean = actions(step).isEmpty()

    /**
     * 这一步在 [state] 上**需要定位哪几条锚点**（提速，2026-10-22）：只定位这些，别把整屏扫一遍。
     *
     * 为什么要这张表：采集层原先每轮把当前状态**全部**可点锚点都定位一遍，而一步其实只用一条。
     * 单条成本护栏后约 60~120ms ⇒ 活动中心每轮白跑 3 条、获取立牌 2 条、我的立牌 1 条。
     *
     * 三条来源，**缺一条就出错**：
     * 1. 本步动作的锚点（[Action.anchor]）；
     * 2. 同屏验证用的 [Action.expectAnchor]（例：点「领取」之后要看「点击继续」是否出现）；
     * 3. 判定步自己的判据（活动中心就绪的两个入口 + 空槽、选卡时的「分享」）。
     *
     * 返回**空集 = 这一步不需要定位**：读取步只看 OCR 区域；画面不是本步的动作画面时也不该动手
     * （编排器本来就不会在那个画面上点）。
     */
    fun neededAnchors(step: BadgeFlow.Step, state: UiState): Set<String> {
        val fromActions = actions(step)
            .filter { it.state == state }
            .flatMap { action -> listOfNotNull(action.anchor, action.expectAnchor) }
        val fromJudgment = when {
            step == BadgeFlow.Step.DECIDE_BRANCH && state == UiState.ACTIVITY_CENTER ->
                readyAnchors + BadgeAnchors.BADGE_EMPTY_SLOT

            step == BadgeFlow.Step.PICK_CARD && state == UiState.BADGE_MY ->
                setOf(BadgeAnchors.BADGE_SHARE)

            else -> emptySet()
        }
        return fromActions.toSet() + fromJudgment
    }

    /**
     * **每轮该搜哪些画面**（与 `PatrolScenes.watchStatesFor` 同一口径，含自愈阀）：
     * 有结论 ⇒ 当前屏 + 这一步的验证目标；认不出来 ⇒ 退回**整条立牌链**（[allStates]）。
     *
     * ⚠ 自愈阀必须给 [allStates]，不能只给 [chainStates]（**真机教训 2026-10-22**）：
     * 第 11 步点了关闭 X 之后画面可能直接跳到「活动中心」或「大厅」（关闭按钮关掉的是整层立牌界面），
     * 而 `chainStates(CLOSE_CARD)` 只含 {复制分享, 我的立牌} ⇒ 认不出 ⇒ 永远 `未知` ⇒ 15 秒后误报中止。
     *
     * 遮挡屏（活动弹窗 / 新手两屏）**不在这里**：由守卫集合恒定提供，FR-01/FR-02 的可见性一分不减。
     */
    fun watchStatesFor(step: BadgeFlow.Step, currentState: UiState): Set<UiState> {
        if (currentState == UiState.UNKNOWN) return allStates
        return setOf(currentState) + expectedStates(step)
    }

    /**
     * 立牌支线**全部**可能出现的画面：每个步骤的画面链的并集（= 活动中心 / 四个弹框 / 大厅）。
     *
     * 用途只有一个：**认不出来时把搜索面放到最大**，让流程能自愈回链上的任意一屏
     * （真正决定"该做什么"的仍是步骤契约，搜索面变大不等于放宽验证）。
     */
    val allStates: Set<UiState> = BadgeFlow.Step.entries.flatMap { chainStates(it) }.toSet()

    /** 这一步的完整画面链（动作所在屏 + 验证目标）；自愈阀放宽时用它。 */
    fun chainStates(step: BadgeFlow.Step): Set<UiState> =
        actionStates(step).toSet() + expectedStates(step)

    /**
     * 一次点击的验证是否通过。
     *
     * @param anchorNames 当前轮**已定位到**的锚点名（[Action.expectAnchor] 用它判）
     */
    fun check(
        action: Action,
        currentState: UiState,
        anchorNames: Set<String>,
    ): Boolean {
        val expected = action.expect
        if (expected != null && expected != currentState) return false
        val expectedAnchor = action.expectAnchor
        if (expectedAnchor != null) return expectedAnchor in anchorNames
        return true
    }

    /**
     * 活动中心**就绪**的判据（每日首次进入的自动提交动效结束）：两个入口**都**定位到。
     *
     * 为什么用"两个入口都在"而不是干等 N 秒：动效时长随网络/机型变（真机 3~8 秒），
     * 定长等待不是太短就是白等；而两个入口同时可定位 ⇒ 页面已经可交互。
     *
     * ⚠ 锚点框得不准时会一直不就绪 ⇒ 到 [BadgeFlow.ACTIVITY_SETTLE_TIMEOUT_MS] 如实停下
     * （停下 ≠ 错动作，FR-T5）。
     */
    val readyAnchors: Set<String> = setOf(BadgeAnchors.BADGE_CLAIM_ENTRY, BadgeAnchors.BADGE_SHARE_ENTRY)

    /**
     * 就绪之后**最少**再等多久（ms）：防动效末尾那一下仍在换图（空槽判定误读的窗口）。
     *
     * 2026-10-22 真机：两入口都已定位到之后还要再等 3 秒，纯属白等（每区服 3 秒）；
     * 1.2 秒既覆盖动效收尾，又不拖速度。
     */
    const val MIN_SETTLE_MS = 1_200L

    /** 活动中心是否已就绪（见 [readyAnchors]）。 */
    fun activityReady(anchorNames: Set<String>): Boolean = readyAnchors.all { it in anchorNames }
}
