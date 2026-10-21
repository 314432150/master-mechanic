package com.example.mastermechanic.badge

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors

/**
 * 立牌收集流程的**锚点契约**（**临时需求** temp/badge-share-1021，2026-10-21；
 * 活动结束随分支整体移除，见 `docs/plans/temp-badge-share.md`）。
 *
 * 与 `PatrolAnchors` 同一口径：锚点名 = 用途名（snake_case），标定页按本表给用途选项，
 * 运行时按用途名在产物里取模板。差别只是**归属流程不同**（立牌流程 vs FR-04 巡查）——
 * 分开放是为了移除时整块删掉，不动巡查契约。
 *
 * ## 本表只含"已经见过屏"的控件（红线：不凭猜测开工）
 *
 * 依据 = 用户 2026-10-21 给的截图、流程描述与真机标定走查。「切换」已确认在**复制分享弹框**上
 * （点它才进「我的立牌」，2026-10-21 真机）；立牌信息**分三块**（分数 / 立牌名称 / 分享码）。
 * 还没见过的：「继续」、活动介绍的「跳过」、第二个「返回」 ⇒ 仍不声明，见屏再补。
 *
 * ## 多实例锚点（运行时消歧，Slice 3）
 *
 * 「领取」（获取立牌两条任务行各一个）与「分享」（我的立牌两张卡各一个）都是**同模板多处出现** ——
 * 红线 7「多处命中按未识别」管的是"认没认出来"，管不了"点哪一个"；点哪一个由流程按
 * **行 / 卡的归属信息**（文字识别对齐）消歧，不许按"最像的"挑（红线 3 同源）。
 *
 * ## 标志（marker）请框标题文字，别框右上角白 X
 *
 * 立牌三个弹框的关闭控件与 FR-01 活动弹窗的关闭控件**同形（白 X）**：拿 X 当标志既分不出
 * "这是哪个弹框"，又有被 FR-01 自动关弹窗当成活动弹窗误点的风险 ⇒ 标志一律框
 * **标题文字**（「获取立牌」/「我的立牌」/「复制分享」），X 只作为锚点用途框。
 */
object BadgeAnchors {

    // ------------------------------------------------------------------ 锚点名（= 用途名）

    /** 大厅 · 活动入口（进活动中心；临时需求借用大厅这一屏，仍归 [UiState.HALL]）。 */
    const val HALL_ACTIVITY = "hall_activity"

    /** 活动中心 · 获取立牌入口（打开「获取立牌」弹框）。 */
    const val BADGE_CLAIM_ENTRY = "badge_claim_entry"

    /** 活动中心 · 返回（左上角 «；退出活动中心回大厅）。 */
    const val BADGE_ACTIVITY_EXIT = "badge_activity_exit"

    /** 活动中心 · 分享立牌（打开「复制分享」弹框；2026-10-21 真机确认有此入口）。 */
    const val BADGE_SHARE_ENTRY = "badge_share_entry"

    /**
     * 活动中心 · 空立牌槽（**只判存在，不点**）：**领取分支的唯一判据**（2026-10-21 用户拍板：
     * 空槽与今日总分二选一，留空槽 —— 二值判定、不碰 OCR）。
     *
     * 槽空着 ⇒ 今日还没领到立牌（点「获取立牌」）；槽里换成立牌 + 分数 ⇒ 已获取（直接「分享立牌」）。
     * 三态对照帧已证；领到后「+」消失。
     */
    const val BADGE_EMPTY_SLOT = "badge_empty_slot"

    /** 获取立牌弹框 · 领取（登录游戏那条任务行的领取按钮；多实例，见文件头）。 */
    const val BADGE_CLAIM = "badge_claim"

    /** 获取立牌弹框 · 关闭（右上角 X）。 */
    const val BADGE_CLAIM_CLOSE = "badge_claim_close"

    /** 获取立牌弹框 · 点击继续（点「领取」之后出现的继续按钮；2026-10-21 用户确认归本弹框）。 */
    const val BADGE_CONTINUE = "badge_continue"

    /** 我的立牌弹框 · 分享（立牌卡上的分享按钮；两张卡两个，见文件头）。 */
    const val BADGE_SHARE = "badge_share"

    /** 我的立牌弹框 · 关闭（右上角 X）。 */
    const val BADGE_MY_CLOSE = "badge_my_close"

    /**
     * 分享立牌弹框 · 立牌分享码（**只圈区域**，不点）：分享码那一块。
     * 2026-10-21 真机确认：弹框上立牌信息**分三块**（分数 / 立牌名称 / 分享码），三块各圈一个区域
     * 送去文字识别（[ShareCodeParser] 逐块取值）。剪贴板里 `【…】价值…分` 那种整行格式是点
     * 「复制分享码」才有的文本 —— 不考虑（用户 2026-10-21 口径）。
     */
    const val BADGE_SHARE_TEXT_AREA = "badge_share_text_area"

    /** 分享立牌弹框 · 立牌分数（**只圈区域**）：三块之一。 */
    const val BADGE_SHARE_SCORE_AREA = "badge_share_score_area"

    /** 分享立牌弹框 · 立牌名称（**只圈区域**）：三块之一。 */
    const val BADGE_SHARE_NAME_AREA = "badge_share_name_area"

    /**
     * 复制分享弹框 · 切换：点它**才进入「我的立牌」弹框**（2026-10-21 真机确认；
     * 原以为「切换」在获取立牌弹框上 —— 没见过屏不声明，见过才落）。
     */
    const val BADGE_SWITCH = "badge_switch"

    /** 复制分享弹框 · 关闭（右上角 X / 返回）。 */
    const val BADGE_SHARE_CLOSE = "badge_share_close"

    // ------------------------------------------------------------------ 用途表

    /** 只圈区域（给文字识别圈范围）的锚点：不进模板点击定位（与 `PatrolAnchors.nonLocatableAnchors` 合并）。 */
    val regionAnchors: Set<String> = setOf(
        BADGE_SHARE_SCORE_AREA,
        BADGE_SHARE_NAME_AREA,
        BADGE_SHARE_TEXT_AREA,
    )

    /**
     * **允许同屏多处命中**的锚点（2026-10-21 Slice 3）：同一模板在一屏出现多次是**预期**，
     * 不是"位置歧义"（红线 7 管的是"认没认出来"）。
     *
     * - [BADGE_CLAIM]：两条任务行各一个「领取」；
     * - [BADGE_SHARE]：「我的立牌」每张卡一个「分享」。
     *
     * 拿到多个位置后**必须**按行文字 / 卡序 + 已读位置消歧；分不清就停下报原因（红线 3）。
     */
    val multiInstanceAnchors: Set<String> = setOf(BADGE_CLAIM, BADGE_SHARE)

    /**
     * 立牌点击锚点的**用途**（日志 / 中止原因里给人看的短名）。
     *
     * 为什么需要它：`badge_claim_entry` 同时是「活动中心」的状态**标志**，而 `SignalNames.of` 的口径是
     * "标志与锚点同名时优先说标志" ⇒ 日志会写成「点「「活动中心」的界面标志」」，
     * 看着像在点状态标志（红线 5 的错觉）。这里按**动作语义**说：获取立牌入口 / 领取 / 分享……
     * （2026-10-22 真机日志暴露，改为本条）。
     */
    fun clickLabel(anchor: String): String = when (anchor) {
        HALL_ACTIVITY -> "活动入口"
        BADGE_CLAIM_ENTRY -> "获取立牌入口"
        BADGE_CLAIM -> "领取"
        BADGE_CONTINUE -> "点击继续"
        BADGE_CLAIM_CLOSE -> "关闭获取立牌弹框"
        BADGE_SHARE_ENTRY -> "分享立牌入口"
        BADGE_SWITCH -> "切换"
        BADGE_SHARE -> "分享"
        BADGE_SHARE_CLOSE -> "关闭分享立牌弹框"
        BADGE_MY_CLOSE -> "关闭我的立牌弹框"
        BADGE_ACTIVITY_EXIT -> "返回大厅"
        BADGE_EMPTY_SLOT -> "空立牌槽"
        else -> anchor
    }

    /**
     * 锚点名 → **用途中文名**（启动前校验的缺口清单 / 日志里给人看）。
     *
     * 与 [clickLabel] 的分工：前者是"这个控件的用途叫什么"（标定页选的也是这个词），
     * 后者是"点它等于做什么"（动作语义）。校验时用户要照着去标定页找，所以用用途名。
     */
    fun purposeLabel(anchor: String): String = PURPOSE_LABELS[anchor] ?: anchor

    private val PURPOSE_LABELS: Map<String, String> =
        UiState.entries.flatMap { purposesFor(it) }.associate { it.name to it.label }

    /** 立牌流程各屏的用途（与 [usageByState] 一一对齐；空列表 = 该屏没有立牌用途）。 */
    fun purposesFor(state: UiState): List<PatrolAnchors.Purpose> = when (state) {
        UiState.HALL -> listOf(PatrolAnchors.Purpose("活动入口", HALL_ACTIVITY))

        UiState.ACTIVITY_CENTER -> listOf(
            PatrolAnchors.Purpose("获取立牌入口", BADGE_CLAIM_ENTRY),
            PatrolAnchors.Purpose("分享立牌", BADGE_SHARE_ENTRY),
            PatrolAnchors.Purpose("空立牌槽", BADGE_EMPTY_SLOT),
            PatrolAnchors.Purpose("返回", BADGE_ACTIVITY_EXIT),
        )

        UiState.BADGE_CLAIM -> listOf(
            PatrolAnchors.Purpose("领取", BADGE_CLAIM),
            PatrolAnchors.Purpose("点击继续", BADGE_CONTINUE),
            PatrolAnchors.Purpose("关闭", BADGE_CLAIM_CLOSE),
        )

        UiState.BADGE_MY -> listOf(
            PatrolAnchors.Purpose("分享", BADGE_SHARE),
            PatrolAnchors.Purpose("关闭", BADGE_MY_CLOSE),
        )

        UiState.BADGE_SHARE -> listOf(
            PatrolAnchors.Purpose("立牌分数", BADGE_SHARE_SCORE_AREA),
            PatrolAnchors.Purpose("立牌名称", BADGE_SHARE_NAME_AREA),
            PatrolAnchors.Purpose("立牌分享码", BADGE_SHARE_TEXT_AREA),
            PatrolAnchors.Purpose("切换", BADGE_SWITCH),
            PatrolAnchors.Purpose("关闭", BADGE_SHARE_CLOSE),
        )

        else -> emptyList()
    }

    /**
     * 该状态在立牌流程里**要用的锚点**（点击或读区）——与 [purposesFor] 必须一一对齐
     * （`PatrolAnchorsTest.purposesCoverExactlyTheAnchorsUsedOnEachState` 合并本表后钉住）。
     *
     * 点击链（谁在第几步点谁）随 Slice 3 落地；届时本表改为从点击链推导，防两处漂移。
     */
    val usageByState: Map<UiState, Set<String>> = mapOf(
        UiState.HALL to setOf(HALL_ACTIVITY),
        UiState.ACTIVITY_CENTER to setOf(
            BADGE_CLAIM_ENTRY,
            BADGE_SHARE_ENTRY,
            BADGE_EMPTY_SLOT,
            BADGE_ACTIVITY_EXIT,
        ),
        UiState.BADGE_CLAIM to setOf(BADGE_CLAIM, BADGE_CONTINUE, BADGE_CLAIM_CLOSE),
        UiState.BADGE_MY to setOf(BADGE_SHARE, BADGE_MY_CLOSE),
        UiState.BADGE_SHARE to setOf(
            BADGE_SHARE_SCORE_AREA,
            BADGE_SHARE_NAME_AREA,
            BADGE_SHARE_TEXT_AREA,
            BADGE_SWITCH,
            BADGE_SHARE_CLOSE,
        ),
    )
}
