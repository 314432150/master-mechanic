package com.example.mastermechanic.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **无障碍自愈的纯逻辑**（2026-10-25）：只钉两件事 ——
 * ① 只**追加**、绝不删除 / 重排别人的无障碍服务；② 判"列表里是否已有本包"时**只比包名**
 * （系统存的是短名 `pkg/.service.X`，我们生成的是长名 `pkg/full.Service`，比字符串必漏判）。
 *
 * 真正写系统设置那一半只能在真机验证（`docs/progress.md` 2026-10-25 20:24 有实测日志）。
 */
class AccessibilitySelfHealTest {

    private val pkg = "com.example.mastermechanic"
    private val selfShort = "$pkg/.service.MasterMechanicAccessibilityService"

    @Test
    fun emptyListBecomesJustOurs() {
        assertEquals(selfShort, AccessibilitySelfHeal.mergedList("", selfShort))
        assertEquals(selfShort, AccessibilitySelfHeal.mergedList("   ", selfShort))
    }

    @Test
    fun othersAreKeptInOrderAndOursGoesLast() {
        val others = "li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        assertEquals(
            "$others:$selfShort",
            AccessibilitySelfHeal.mergedList(others, selfShort),
        )
    }

    /** 末尾有多余分隔符时不要写出 `a::b` 这种脏值（系统回读时会把它当成空项）。 */
    @Test
    fun trailingSeparatorsAreTrimmed() {
        val others = "li.songe.gkd/com.example.X"
        assertEquals("$others:$selfShort", AccessibilitySelfHeal.mergedList("$others:", selfShort))
        assertEquals("$others:$selfShort", AccessibilitySelfHeal.mergedList(":$others", selfShort))
    }

    /**
     * **拨关时只摘自己**（2026-10-25）：这条路是"我们第一次会**删除**系统设置里的东西"，
     * 所以单独钉死 —— 别人的无障碍服务必须一个不少、顺序不变。
     */
    @Test
    fun withoutSelfRemovesOnlyUs() {
        val other1 = "li.songe.gkd/com.example.SelectToSpeakService"
        val other2 = "com.other.tool/.Whatever"
        assertEquals(
            "$other1:$other2",
            AccessibilitySelfHeal.withoutSelf("$other1:$selfShort:$other2", pkg),
        )
        assertEquals(other1, AccessibilitySelfHeal.withoutSelf("$other1:$selfShort", pkg))
        assertEquals("", AccessibilitySelfHeal.withoutSelf(selfShort, pkg))
        // 别人的项一个都不能少
        assertEquals("$other1:$other2", AccessibilitySelfHeal.withoutSelf("$other1:$other2", pkg))
    }

    @Test
    fun containsOnlyComparesPackageName() {
        // 系统存短名、我们生成长名 —— 只比包名才不会漏判
        assertTrue(AccessibilitySelfHeal.containsSelf("$pkg/com.example.mastermechanic.service.X", pkg))
        assertTrue(AccessibilitySelfHeal.containsSelf("a/b:$selfShort", pkg))
        assertFalse(AccessibilitySelfHeal.containsSelf("a/b", pkg))
        assertFalse(AccessibilitySelfHeal.containsSelf("", pkg))
        // 前缀相近的别的包不能被误判成本包
        assertFalse(AccessibilitySelfHeal.containsSelf("$pkg.evil/x", pkg))
    }
}
