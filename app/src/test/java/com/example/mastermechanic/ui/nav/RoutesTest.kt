package com.example.mastermechanic.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **M5-U1 导航骨架的纯逻辑守卫**（2026-10-01）。
 *
 * 钉住的是**验收 V1** 里那条能自动测的部分：「**一级目的地 ≤5**、路由不重名、落地页在一级里」。
 * V1 的另一半（"任意功能 ≤2 次点击可达"）是**真机逐项走查**的事，单测做不到 —— 如实写在这里，
 * 免得看的人以为"V1 已经全测过了"。
 *
 * ⚠ 本用例**只能碰 [Routes]**（纯 Kotlin）。图标与 `R.string` 在 [AppDestination] 里，
 * 那些进不了 JVM 单测（`ImageVector` / 资源表都要 Android 运行时）。
 */
class RoutesTest {

    @Test
    fun theBottomBarStaysWithinFiveDestinations() {
        // V1 的硬线：一级 ≤5。超了就说明有人往底部导航里塞东西了 —— 该塞进抽屉或某个页面里。
        assertTrue(
            "一级目的地 ${Routes.topLevel.size} 个：${Routes.topLevel}",
            Routes.topLevel.size <= 5,
        )
    }

    @Test
    fun everyRouteIsNamedOnceAndNobodyIsEmpty() {
        // 路由名重复 => NavHost 里 `composable(...)` 会互相覆盖（后注册的赢），表现为"点某个 Tab 出来的是别的页"。
        val duplicates = Routes.all.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("有重复的路由名：$duplicates", duplicates.isEmpty())
        assertTrue("有空白路由名", Routes.all.none { it.isBlank() })
        assertTrue("一级与抽屉不能互相重复", (Routes.topLevel intersect Routes.drawer.toSet()).isEmpty())
    }

    @Test
    fun theLandingPageIsOneOfTheTopLevelOnes() {
        // 落地页若不在底部导航里，用户一进来就会看到"底栏哪一项都没选中"。
        assertTrue("落地页 ${Routes.START} 必须是一级目的地之一", Routes.START in Routes.topLevel)
        assertEquals("落地页=运行（打开 App 最先要知道「现在能不能跑」）", Routes.RUN, Routes.START)
    }

    @Test
    fun theMenuKeepsTheFourSystemEntriesTheUserPicked() {
        // 用户 2026-10-01 拍板：抽屉 = 授权与权限 / 诊断 / 设置 / 帮助·关于（顺序也是这个）。
        assertEquals(
            listOf(Routes.AUTH, Routes.DIAGNOSTICS, Routes.SETTINGS, Routes.HELP),
            Routes.drawer,
        )
        // 一级：运行 / 拜访设置 / 账号与好友 / 标定（用户当天把「规则」「清单」改成了后两个名字里的说法）。
        assertEquals(
            listOf(Routes.RUN, Routes.VISIT_SETTINGS, Routes.ACCOUNTS, Routes.CALIBRATION),
            Routes.topLevel,
        )
    }
}
