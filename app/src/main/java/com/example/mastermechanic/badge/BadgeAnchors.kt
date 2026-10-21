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
 * 依据 = 用户 2026-10-21 给的截图与流程描述。「继续」「切换」、活动介绍的「跳过」、
 * 第二个「返回」在哪个屏上**还没见过** ⇒ 不声明，标定走到那几屏时再补（任务卡有清单）。
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

    /** 获取立牌弹框 · 领取（登录游戏那条任务行的领取按钮；多实例，见文件头）。 */
    const val BADGE_CLAIM = "badge_claim"

    /** 获取立牌弹框 · 关闭（右上角 X）。 */
    const val BADGE_CLAIM_CLOSE = "badge_claim_close"

    /** 我的立牌弹框 · 分享（立牌卡上的分享按钮；两张卡两个，见文件头）。 */
    const val BADGE_SHARE = "badge_share"

    /** 我的立牌弹框 · 关闭（右上角 X）。 */
    const val BADGE_MY_CLOSE = "badge_my_close"

    /**
     * 复制分享弹框 · 分享码文本区（**只圈区域**，不点）：
     * `【墨子顶级立牌1M09G2】价值877分` 那一块 —— 文字识别的输入范围（[ShareCodeParser] 吃它的输出）。
     */
    const val BADGE_SHARE_TEXT_AREA = "badge_share_text_area"

    /** 复制分享弹框 · 关闭（右上角 X / 返回）。 */
    const val BADGE_SHARE_CLOSE = "badge_share_close"

    // ------------------------------------------------------------------ 用途表

    /** 只圈区域（给文字识别圈范围）的锚点：不进模板点击定位（与 `PatrolAnchors.nonLocatableAnchors` 合并）。 */
    val regionAnchors: Set<String> = setOf(BADGE_SHARE_TEXT_AREA)

    /** 立牌流程各屏的用途（与 [usageByState] 一一对齐；空列表 = 该屏没有立牌用途）。 */
    fun purposesFor(state: UiState): List<PatrolAnchors.Purpose> = when (state) {
        UiState.HALL -> listOf(PatrolAnchors.Purpose("活动入口", HALL_ACTIVITY))

        UiState.ACTIVITY_CENTER -> listOf(
            PatrolAnchors.Purpose("获取立牌入口", BADGE_CLAIM_ENTRY),
            PatrolAnchors.Purpose("返回", BADGE_ACTIVITY_EXIT),
        )

        UiState.BADGE_CLAIM -> listOf(
            PatrolAnchors.Purpose("领取", BADGE_CLAIM),
            PatrolAnchors.Purpose("关闭", BADGE_CLAIM_CLOSE),
        )

        UiState.BADGE_MY -> listOf(
            PatrolAnchors.Purpose("分享", BADGE_SHARE),
            PatrolAnchors.Purpose("关闭", BADGE_MY_CLOSE),
        )

        UiState.BADGE_SHARE -> listOf(
            PatrolAnchors.Purpose("分享码文本区", BADGE_SHARE_TEXT_AREA),
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
        UiState.ACTIVITY_CENTER to setOf(BADGE_CLAIM_ENTRY, BADGE_ACTIVITY_EXIT),
        UiState.BADGE_CLAIM to setOf(BADGE_CLAIM, BADGE_CLAIM_CLOSE),
        UiState.BADGE_MY to setOf(BADGE_SHARE, BADGE_MY_CLOSE),
        UiState.BADGE_SHARE to setOf(BADGE_SHARE_TEXT_AREA, BADGE_SHARE_CLOSE),
    )
}
