package com.example.mastermechanic.auth

/**
 * 关键授权项（M0-T0-2 口径，见 docs/plans/m0-skeleton.md）。
 *
 * **声明顺序不含显示含义** —— 授权页的自上而下顺序由 `AuthorizationChecks.collect` 的返回顺序决定
 * （屏幕采集刻意排最后：点它会跳到游戏）。
 *
 * [RESIDENT] 于 2026-09-20 由用户口径加入：**常驻守护服务是运行自动化的必备项** ——
 * 菜单请求由它接收、点击由它放行，没启动就整个自动化不动作（此前它是"可选卡"，
 * 于是"忘了启动"表现为"点了毫无反应"，真机上连踩两次）。标签上不标"（必需）"（同日用户口径），
 * 必需性靠"未启动"红字 + 描述文案 + 门禁拒绝传达。
 */
enum class AuthItem {
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    RESIDENT,
    NOTIFICATIONS,
}

/**
 * 授权页**自上而下**的显示顺序（2026-09-20 用户口径）：**屏幕采集排在最后** ——
 * 点它会让系统弹授权窗、并跳到《王者荣耀》，用户得切回游戏再切回来；
 * 所以"点之前能顺手做完的事"（无障碍 / 常驻守护 / 通知）一律排它上面，避免来回跳两趟。
 *
 * 抽成常量（而不是写在 `collect` 里）是为了能**单测钉住**这个顺序——它是个用户口径，不是随手排序。
 */
val AUTHORIZATION_DISPLAY_ORDER: List<AuthItem> = listOf(
    AuthItem.ACCESSIBILITY,
    AuthItem.RESIDENT,
    AuthItem.NOTIFICATIONS,
    AuthItem.SCREEN_CAPTURE,
)

/** 单项授权状态（minSdk 34 起三项授权在任何目标设备上都需要，不再有"无需授权"这一档）。 */
enum class AuthState {
    GRANTED,
    MISSING,
}

data class AuthStatus(val item: AuthItem, val state: AuthState)

/** 授权汇总：纯逻辑，不依赖安卓框架，便于 JVM 单测。 */
object AuthorizationSummary {

    /** 仍缺失的授权项，按传入顺序返回。 */
    fun missingItems(statuses: List<AuthStatus>): List<AuthItem> =
        statuses.filter { it.state == AuthState.MISSING }.map { it.item }

    fun isAllReady(statuses: List<AuthStatus>): Boolean = missingItems(statuses).isEmpty()
}
