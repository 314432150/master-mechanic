package com.example.mastermechanic.ui.nav

/**
 * **M5-U1 导航骨架的路由名**（2026-10-01）。
 *
 * 刻意做成**纯 Kotlin**（不 import 任何 Android / Compose 东西）⇒ 可以进 JVM 单测
 * （[RoutesTest] 钉住"一级 ≤5 / 路由不重名 / 落地页在一级里"这几条 —— 验收 V1 的那部分）。
 * 图标与文案在 [AppDestination] 里（那边要 Compose 与 `R.string`，进不了 JVM 单测）。
 *
 * ## 为什么用字符串路由、不用 2.8+ 的 type-safe routes
 *
 * type-safe 需要 `kotlinx-serialization` 插件（又是一份构建配置）；U1 的验收是
 * "**行为不变，仅换壳**" ⇒ 不值得为一张换壳卡再引一个插件。将来真要迁，改动只在本文件与
 * `MasterMechanicApp` 的 `composable(...)` 调用点。
 *
 * ## 与旧 `MainActivity.screen` 编号的对应（换壳前的状态，供对照）
 *
 * ```
 * 旧 0（授权与运行） ⇒ [RUN]（一级）+ [AUTH]（抽屉，U1 阶段同样接 `AuthorizationRoute`）
 * 旧 1（识别标定）   ⇒ [CALIBRATION]（一级）
 * 旧 2              ⇒ **空号**（2026-09-16 移除的「跑号配置」）—— 换壳后这个"编号地狱"消失
 * 旧 3（服务器清单） ⇒ [ACCOUNTS] 的「区服清单」Tab
 * 旧 4（好友清单）   ⇒ [ACCOUNTS] 的「好友清单」Tab
 * ```
 */
object Routes {

    /** 一级：运行（现在能不能跑 / 跑到哪了）。 */
    const val RUN = "run"

    /** 一级：拜访设置（用户 2026-10-01 改名：原计划叫「规则」）。 */
    const val VISIT_SETTINGS = "visit_settings"

    /** 一级：账号与好友（用户 2026-10-01 改名：原计划叫「清单」）—— 内含两个 Tab。 */
    const val ACCOUNTS = "accounts"

    /** 一级：标定。 */
    const val CALIBRATION = "calibration"

    /** 抽屉：授权与权限（首次启动缺授权时自动进这里）。 */
    const val AUTH = "auth"

    /** 抽屉：诊断。 */
    const val DIAGNOSTICS = "diagnostics"

    /** 抽屉：设置。 */
    const val SETTINGS = "settings"

    /** 抽屉：帮助·关于。 */
    const val HELP = "help"

    /**
     * **一级目的地**（底部导航）—— 验收 V1 要求 **≤5 项**，这里是 4 项。
     *
     * 顺序 = 底部导航上的显示顺序（从"最常做的事"到"最不常做的事"）。
     */
    val topLevel: List<String> = listOf(RUN, VISIT_SETTINGS, ACCOUNTS, CALIBRATION)

    /** **抽屉目的地**（系统级入口，不占底部导航）。 */
    val drawer: List<String> = listOf(AUTH, DIAGNOSTICS, SETTINGS, HELP)

    /** 图里全部目的地（一级 + 抽屉）。 */
    val all: List<String> = topLevel + drawer

    /**
     * **落地页**（也是返回栈的根）。
     *
     * 选「运行」的依据：用户打开 App 最想知道的永远是"**现在能不能跑 / 跑到哪了**"
     * （FR-05 的口径也是"结局常驻可见"）⇒ 首页放它。
     */
    const val START = RUN
}
