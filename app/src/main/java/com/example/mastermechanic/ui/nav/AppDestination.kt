package com.example.mastermechanic.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.mastermechanic.R

/**
 * **目的地 → 图标 + 文案**（M5-U1）。
 *
 * 为什么不放进 [Routes]：那边是纯 Kotlin（可进 JVM 单测），这边要 Compose 的 `ImageVector` 与
 * `R.string` ⇒ 一旦混在一起，[RoutesTest] 就会被 Android 依赖拖下水。
 *
 * 图标一律取 **`material-icons-core`** 里就有的那几个（`PlayArrow` / `Build` / `Person` / `Create` /
 * `Lock` / `Info` / `Settings` / `AccountCircle`）—— 不引 `material-icons-extended`（那是一大包矢量，
 * 会顶到 NFR-03 的 25MB 包体线）。
 */
enum class TopLevelDestination(
    val route: String,
    /** **页面标题**用的全名（`TopAppBar`；也是「帮助」与读屏里的正式称呼）。 */
    @StringRes val labelRes: Int,
    /**
     * **底栏标签**用的短名。
     *
     * 用户 2026-10-01："「**拜访设置**」「**账号与好友**」作为导航栏**太长了**，换个简短的"
     * ⇒ 底栏一律 **2 字**（运行 / 拜访 / 清单 / 标定），页面标题仍是全名。
     * ⚠ 两者不一致是**刻意的**：底栏宽度只够 2 字（再长会被截断成"拜访设…"，比短名更糟），
     * 而进入页面后需要的是"我在哪一页"的准确说法 ⇒ 各自用各自合适的长度。
     */
    @StringRes val shortLabelRes: Int,
    val icon: ImageVector,
) {
    /** 运行：现在能不能跑 / 跑到哪了。 */
    RUN(Routes.RUN, R.string.nav_run, R.string.nav_run, Icons.Filled.PlayArrow),

    /** 拜访设置（原计划名「规则」）：全名四个字，底栏简作「拜访」。 */
    VISIT_SETTINGS(
        Routes.VISIT_SETTINGS,
        R.string.nav_visit_settings,
        R.string.nav_visit_settings_short,
        Icons.Filled.Build,
    ),

    /** 账号与好友（原计划名「清单」）：内含区服 / 好友两个 Tab，底栏简作「清单」。 */
    ACCOUNTS(
        Routes.ACCOUNTS,
        R.string.nav_accounts,
        R.string.nav_accounts_short,
        Icons.Filled.Person,
    ),

    /** 标定：样本帧 + 产物与参数 ⇒ 全屏标定工作台。 */
    CALIBRATION(Routes.CALIBRATION, R.string.nav_calibration, R.string.nav_calibration, Icons.Filled.Create),
}

/** 抽屉里的系统级入口。 */
enum class DrawerDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    /** 授权与权限：四项状态与一键授予（首次启动缺授权时自动进这里）。 */
    AUTH(Routes.AUTH, R.string.nav_auth, Icons.Filled.Lock),

    /** 诊断：画面在不在来 / 当前识别画面与分数 / 单帧耗时 / 日志。 */
    DIAGNOSTICS(Routes.DIAGNOSTICS, R.string.nav_diagnostics, Icons.Filled.Info),

    /** 设置：自动关弹窗暂停·恢复 / 悬浮窗提示位置重置 / 退出 App。 */
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),

    /** 帮助·关于：术语与流程说明（长文案里"为什么"的归宿）。 */
    HELP(Routes.HELP, R.string.nav_help, Icons.Filled.AccountCircle),
}

/**
 * 目的地 → 标题文案（`TopAppBar` 用）。
 *
 * 找不到就回 `null`（调用方自己决定怎么办）—— 不在这里硬编一个兜底标题：**没登记的路由说明有人加了
 * 目的地却忘了给它名字**，那种情况应该当场看得见（[RoutesTest] 也钉了"一级与抽屉的路由必须成套"）。
 */
@StringRes
fun titleResOf(route: String?): Int? = when (route) {
    Routes.RUN -> R.string.nav_run
    Routes.VISIT_SETTINGS -> R.string.nav_visit_settings
    Routes.ACCOUNTS -> R.string.nav_accounts
    Routes.CALIBRATION -> R.string.nav_calibration
    Routes.AUTH -> R.string.nav_auth
    Routes.DIAGNOSTICS -> R.string.nav_diagnostics
    Routes.SETTINGS -> R.string.nav_settings
    Routes.HELP -> R.string.nav_help
    else -> null
}
