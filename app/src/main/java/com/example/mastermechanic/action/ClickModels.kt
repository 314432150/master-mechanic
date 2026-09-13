package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState

/**
 * 点击的合法来源（红线 2）：除以下三类外，任何代码路径都不得产生点击。
 * （FR-02「新手引导」已于 2026-09-13 移除，故为三类。）
 */
enum class ClickSource(val label: String) {
    FR_01("FR-01 活动弹窗自动关闭"),
    PATROL_STEP("巡查主流程步骤"),
    FLOATING_MENU("用户经悬浮窗菜单发起的操作"),
}

/**
 * 点击模式（B5）：默认**演练**——只识别、不下发；实点必须显式开启。
 * 演练模式不是"降级"，而是默认安全态：M1 的"只识别"能力在 M2 延续，用户明确要求才真点。
 */
enum class ClickMode(val label: String) {
    DRILL("演练（只识别）"),
    LIVE("实点"),
}

/** 拒绝原因（写进审计，便于真机核对"为什么没点"）。 */
enum class ClickDenyReason(val label: String) {
    DRILL_MODE("演练模式：只识别不下发"),
    NOT_FOREGROUND("游戏不在前台"),
    STATE_UNKNOWN("状态未知"),
    IN_FLIGHT("上一个手势仍在途（串行约束）"),
    TOO_SOON("与上一次点击间隔不足"),
    NO_INJECTOR("无障碍服务不可用（无法注入）"),
}

/**
 * 一次点击请求。
 *
 * [frameX] / [frameY] 是**运行帧像素坐标**，且必须来自识别判定结果（ADR-002「坐标一律来自识别判定结果」）——
 * 不接受调用方凭空写死的坐标。[decisionId] 是判定记录 ID，与 [source] 一起构成 NFR-05 的点击审计链。
 */
data class ClickRequest(
    val decisionId: String,
    val source: ClickSource,
    val anchorName: String,
    val frameX: Double,
    val frameY: Double,
) {
    init {
        require(decisionId.isNotBlank()) { "点击请求必须携带判定记录 ID（NFR-05 审计链）" }
        require(anchorName.isNotBlank()) { "点击请求必须说明目标锚点（审计用）" }
    }
}

/** 下发前的环境快照：门禁的全部输入（红线 1：非前台 / 状态未知 → 零点击）。 */
data class ClickEnvironment(
    val gameForeground: Boolean,
    val state: UiState,
    val mode: ClickMode,
)

/** 门禁判定结果。 */
data class ClickVerdict(
    val allowed: Boolean,
    val reason: ClickDenyReason? = null,
) {
    val detail: String get() = if (allowed) "允许下发" else reason?.label ?: "拒绝（原因未知）"
}

/** 一次判定的审计记录（NFR-05）：无论允许还是拒绝都要留痕。 */
data class ClickAudit(
    val decisionId: String,
    val source: ClickSource,
    val anchorName: String,
    val frameX: Int,
    val frameY: Int,
    val mode: ClickMode,
    val state: UiState,
    val foreground: Boolean,
    val allowed: Boolean,
    val reason: ClickDenyReason?,
    val nowMs: Long,
)

/**
 * 点击生命周期事件（审计链）：决定一次、手势结束一次。
 * 手势结束的事件里带 [completed]——取消（false）按"点击未完成"处理，交由步骤验证失败路径，不盲重试（红线 5）。
 */
sealed interface ClickEvent {
    data class Decided(val audit: ClickAudit) : ClickEvent

    data class GestureEnded(
        val decisionId: String,
        val source: ClickSource,
        val anchorName: String,
        val completed: Boolean,
        val nowMs: Long,
    ) : ClickEvent
}
