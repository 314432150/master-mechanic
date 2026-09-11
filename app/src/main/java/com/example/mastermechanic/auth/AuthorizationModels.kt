package com.example.mastermechanic.auth

/** 关键授权项（M0-T0-2 口径，见 docs/plans/m0-skeleton.md）。 */
enum class AuthItem {
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    NOTIFICATIONS,
}

/** 单项授权状态；NOT_APPLICABLE 表示当前系统版本下无需该授权。 */
enum class AuthState {
    GRANTED,
    MISSING,
    NOT_APPLICABLE,
}

data class AuthStatus(val item: AuthItem, val state: AuthState)

/** 授权汇总：纯逻辑，不依赖安卓框架，便于 JVM 单测。 */
object AuthorizationSummary {

    /** 仍缺失的授权项，按传入顺序返回。 */
    fun missingItems(statuses: List<AuthStatus>): List<AuthItem> =
        statuses.filter { it.state == AuthState.MISSING }.map { it.item }

    fun isAllReady(statuses: List<AuthStatus>): Boolean = missingItems(statuses).isEmpty()
}
