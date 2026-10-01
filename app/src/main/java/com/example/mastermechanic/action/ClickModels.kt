package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState

/**
 * 点击的合法来源（红线 2）：除以下四类外，任何代码路径都不得产生点击。
 *
 * FR-02「新手引导 / 新手大厅自动关闭」2026-09-13 随需求移除，**2026-09-29 恢复**
 * （新手号上这两屏确实会出现，见 `requirements.md` §3.1 的恢复条件）⇒ 回到四类。
 */
enum class ClickSource(val tag: String, val label: String) {
    FR_01("FR-01", "FR-01 活动弹窗自动关闭"),
    FR_02("FR-02", "FR-02 新手引导 / 新手大厅自动关闭"),
    PATROL_STEP("跑号", "巡查主流程步骤"),
    FLOATING_MENU("菜单", "用户经悬浮窗菜单发起的操作"),
}

/**
 * 拒绝原因（写进审计，便于真机核对"为什么没点"）。
 *
 * 不再有「演练模式」这一条：2026-09-22 用户口径 —— 只识别不下发的逻辑**已整体移除**，
 * 程序默认（且唯一）行为就是真实点击，其余硬性约束（守护 / 前台 / 状态 / 落点 / 间隔）不变。
 */
enum class ClickDenyReason(val label: String) {
    GUARD_NOT_RUNNING("常驻守护服务未启动（自动化整体不动作）"),
    NOT_FOREGROUND("游戏不在前台"),
    STALE_FRAMES("画面采集已停（手上那张画面是旧的，点出去等于照着旧画面点）"),
    /**
     * **焦点刚被别的窗口抢走、但还不确定游戏真的离开了**（2026-10-01 加）。
     *
     * 真机实录（用户报"**游戏在前台，却提示不在前台**"）：`com.bbk.launcher2`（vivo 桌面）会
     * **短暂抢走"活动窗口"**（`20:35:36.776` 判不在前台 ⇒ `20:35:39.478` 又回来，共 **2.7 秒**；
     * 另一次 2.99 秒），而那段时间**游戏窗口一直在屏上** ⇒ "暂停跑号 + 摘悬浮窗"全是白挨的
     * （用户还得手动点「继续」）。
     * ⇒ 复核判"不在前台"时**先闸住点击**（本原因），等下一次复核确认后才真的改判
     * （两段式见 `MasterMechanicAccessibilityService.refreshForeground`）。正常路径不会出现它。
     */
    FOREGROUND_SUSPECT("前台焦点刚被别的窗口抢走（先不放行点击，等复核确认）"),
    STATE_UNKNOWN("状态未知"),
    OUT_OF_SCREEN("点击点落在屏幕外（坐标换算结果异常）"),
    IN_FLIGHT("上一个手势仍在途（串行约束）"),
    TOO_SOON("与上一次点击间隔不足"),
    NO_INJECTOR("无障碍服务不可用（无法注入）"),
}

/** 手势种类（审计与日志用，2026-09-29 加）：点击 / 滑动。 */
enum class ClickGestureKind(val label: String) {
    CLICK("点击"),
    SWIPE("滑动"),
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

/**
 * 一次**滑动**手势请求（2026-09-29 加：第 5 步「服务器列表逐屏滚动查找」用）。
 *
 * 需求依据：FR-04 硬性要求 #4 —— "服务器列表需要滚动时，逐屏滚动查找；滚动次数设上限，
 * 超出即中止并提示"。在此之前 `GestureInjector` **只有点击**，判定即使说"再滚一屏"也无从下发。
 *
 * 与 [ClickRequest]**同一套门禁 / 间隔 / 串行 / 审计**（红线 6：没有第二个下发口），
 * 区别只是手势本身是"按住拖一段"而不是"点一下"。起止点都是**运行帧坐标**。
 *
 * ⚠ 方向口径（容易写反，钉在这里）：**手指向下划 = 看更靠前的条目（回顶）；手指向上划 = 看更靠后的条目**。
 */
data class SwipeRequest(
    val decisionId: String,
    val source: ClickSource,
    val anchorName: String,
    val fromFrameX: Double,
    val fromFrameY: Double,
    val toFrameX: Double,
    val toFrameY: Double,
    /** 拖动时长（ms）：太短会被当成"甩"（惯性飞很远），太长就成了"慢慢拖"。 */
    val durationMs: Long,
) {
    init {
        require(decisionId.isNotBlank()) { "滑动请求必须携带判定记录 ID（NFR-05 审计链）" }
        require(anchorName.isNotBlank()) { "滑动请求必须说明目标锚点（审计用）" }
        require(durationMs > 0) { "拖动时长必须为正：$durationMs" }
    }
}

/**
 * 下发前的环境快照：门禁的全部输入（红线 1：非前台 / 状态未知 → 零点击）。
 *
 * [targetOnScreen] = 落点在显示区域内（[ScreenSize.project]；T4-6 起不再换算，只剩越界判定）；
 * **默认 true** 是为了让"没判定过"的调用路径保持既有行为，由转发层在定落点后如实覆盖。
 *
 * [guardRunning] = 常驻守护服务在跑（2026-09-20 用户口径：**它是运行自动化的必备项**，
 * 没起就一律不点）；默认 true 同样只为兼容"没传"的既有测试路径，生产由 `ClickDispatch` 如实传入。
 */
data class ClickEnvironment(
    val gameForeground: Boolean,
    val state: UiState,
    val targetOnScreen: Boolean = true,
    val guardRunning: Boolean = true,
    /**
     * **画面采集是否已停**（2026-09-29 加，用户报"换号 / 拜访时很多标志和锚点都认不出"）。
     *
     * 真机实录：会话重建（用户回到游戏 / 重新授权）之后**一帧新画面都没有**，程序却继续用
     * 那张**5 分钟前**的大厅画面做判定 ⇒ "什么都认不出"，而且**照着旧坐标点了一枪** ✗。
     * 判据与"何时才算真的停了"见 `CaptureService.checkFrameExpectation`：
     * **我们下发过一击（或目标刚回到前台）之后一直没来新帧** —— 这两种时刻，屏幕**必然**变了，
     * 所以"没有新帧"不再能用"静止页面不产帧"解释。
     *
     * 默认 false ⇒ 既有调用路径（含全部单测）行为不变。
     */
    val framesStalled: Boolean = false,
    /**
     * **前台"可疑"**（2026-10-01 加）：复核判"不在前台"，但**游戏的窗口还在屏上且可见**
     * ⇒ 更可能是"别的窗口（桌面 / 系统弹窗）短暂抢走焦点"而不是"游戏被切走了"。
     *
     * 置位期间点击一律被拒（[ClickDenyReason.FOREGROUND_SUSPECT]）—— 安全侧不加延迟：
     * 万一游戏真的被盖住，我们**一枪都不会打出去**；而"暂停跑号 / 摘悬浮窗"这些**用户可见的后果**
     * 要等下一次复核确认才发生（见 `MasterMechanicAccessibilityService.refreshForeground`）。
     *
     * 默认 false ⇒ 既有调用路径（含全部单测）行为不变。
     */
    val foregroundSuspect: Boolean = false,
)

/** 门禁判定结果。 */
data class ClickVerdict(
    val allowed: Boolean,
    val reason: ClickDenyReason? = null,
) {
    val detail: String get() = if (allowed) "允许下发" else reason?.label ?: "拒绝（原因未知）"
}

/**
 * 一次判定的审计记录（NFR-05）：无论允许还是拒绝都要留痕。
 *
 * **两套坐标都留**（[frameX] / [frameY] 来自识别判定结果，[screenX] / [screenY] 是换算后真正下发的落点）：
 * 只留一套时，真机上"点歪了"无法区分是**识别定位错了**还是**坐标换算错了**——
 * 2026-09-20 那次"点了毫无反应"就吃了这个亏（日志里只有一个坐标，两件事混在一起）。
 */
data class ClickAudit(
    val decisionId: String,
    val source: ClickSource,
    val anchorName: String,
    val frameX: Int,
    val frameY: Int,
    val screenX: Int,
    val screenY: Int,
    val state: UiState,
    val foreground: Boolean,
    val allowed: Boolean,
    val reason: ClickDenyReason?,
    val nowMs: Long,
    /** 滑动的**终点**（屏幕坐标）；点击为 null（2026-09-29 加）。 */
    val toScreenX: Int? = null,
    val toScreenY: Int? = null,
    /** 手势种类（默认点击：既有调用方与测试不必改）。 */
    val kind: ClickGestureKind = ClickGestureKind.CLICK,
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
        /** 手势种类（默认点击）。 */
        val kind: ClickGestureKind = ClickGestureKind.CLICK,
    ) : ClickEvent
}
