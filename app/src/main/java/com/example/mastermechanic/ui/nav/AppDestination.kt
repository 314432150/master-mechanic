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
enum class TopLevelDestination(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    /** 运行：现在能不能跑 / 跑到哪了。 */
    RUN(Routes.RUN, R.string.nav_run, Icons.Filled.PlayArrow),

    /** 拜访设置（原计划名「规则」）。 */
    VISIT_SETTINGS(Routes.VISIT_SETTINGS, R.string.nav_visit_settings, Icons.Filled.Build),

    /** 账号与好友（原计划名「清单」）：内含区服 / 好友两个 Tab。 */
    ACCOUNTS(Routes.ACCOUNTS, R.string.nav_accounts, Icons.Filled.Person),

    /** 标定：样本帧 + 产物与参数 ⇒ 全屏标定工作台。 */
    CALIBRATION(Routes.CALIBRATION, R.string.nav_calibration, Icons.Filled.Create),
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
