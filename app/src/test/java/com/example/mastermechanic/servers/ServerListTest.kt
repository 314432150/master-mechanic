package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器清单模型单测（M3-T3-9 / FR-10）：字段校验（区服名必填、角色名与等级可空）、
 * 同一区服多条、增 / 删 / 改 / 调序 / 整体清空，以及越界序号一律抛错。
 */
class ServerListTest {

    private val a = ServerEntry("392区", "阿明", "Lv.50")
    private val b = ServerEntry("418区") // 角色名 / 等级留空
    private val c = ServerEntry("392区", "阿强") // 同一区服第二条（FR-10 允许）

    @Test
    fun rejectsBlankServerNameAndIllegalFields() {
        // 区服名称必填（它是唯一参与定位的字段）
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("   ") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("\t") }
        // 任一字段含分隔符 / 换行 → 拒收（编码层硬约束，见 ServerListCodec）
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("392|区") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("392区", "阿|明") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("392区", "阿明", "Lv|\n50") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry("392区", "") .copy(level = "\n") }
    }

    @Test
    fun optionalFieldsMayBeEmpty() {
        // 角色名 / 等级留空是正常状态（只用于辨识，不参与定位）
        assertEquals("", b.characterName)
        assertEquals("", b.level)
        assertFalse(b.hasCharacterName)
        assertFalse(b.hasLevel)
        assertTrue(a.hasCharacterName)
        assertTrue(a.hasLevel)
    }

    @Test
    fun ofTrimsEachFieldAndRejectsBlankServerName() {
        val trimmed = ServerEntry.of("  392区 ", " 阿明 ", "  Lv.50 ")
        assertEquals(ServerEntry("392区", "阿明", "Lv.50"), trimmed)
        // 省略可选字段 = 留空
        assertEquals(ServerEntry("392区"), ServerEntry.of("392区"))
        // 区服名全是空白 → 去空白后为空 → 仍然拒收
        assertThrows(IllegalArgumentException::class.java) { ServerEntry.of("   ") }
        // 角色名全是空白 → 归为空（不是"一个空格的角色名"）
        assertEquals("", ServerEntry.of("392区", "   ").characterName)
    }

    @Test
    fun emptyListIsValid() {
        assertEquals(0, ServerList.EMPTY.size)
        assertTrue(ServerList.EMPTY.isEmpty)
        assertFalse(ServerList.EMPTY.isNotEmpty)
    }

    @Test
    fun addAppendsToTheEndAndKeepsDuplicates() {
        val list = ServerList.EMPTY.add(a).add(b).add(c)
        assertEquals(listOf(a, b, c), list.entries)
        // 同一区服出现两次是合法的，不做去重
        assertEquals(2, list.entries.count { it.serverName == "392区" })
    }

    @Test
    fun insertPlacesEntryAtGivenPosition() {
        val list = ServerList(listOf(a, c)).insert(1, b)
        assertEquals(listOf(a, b, c), list.entries)
        // 插入到末尾（index == size）等价追加
        assertEquals(listOf(a, b, c), ServerList(listOf(a, b)).insert(2, c).entries)
    }

    @Test
    fun updateReplacesOnlyTheTargetEntry() {
        val original = ServerList(listOf(a, b, c))
        val updated = original.update(1, ServerEntry("418区", "小美", "Lv.30"))
        assertEquals(listOf(a, ServerEntry("418区", "小美", "Lv.30"), c), updated.entries)
        // 原实例不被修改（编辑操作返回新实例）
        assertEquals(listOf(a, b, c), original.entries)
    }

    @Test
    fun removeAndClearWork() {
        val list = ServerList(listOf(a, b, c))
        assertEquals(listOf(a, c), list.remove(1).entries)
        assertTrue(list.clear().isEmpty)
        assertEquals(0, ServerList(listOf(a)).remove(0).size)
    }

    @Test
    fun moveSwapsNeighboursAndStopsAtEnds() {
        val list = ServerList(listOf(a, b, c))
        assertEquals(listOf(b, a, c), list.move(0, +1).entries)
        assertEquals(listOf(a, c, b), list.move(2, -1).entries)
        // 端点：原样返回（不是错误——界面上的端点按钮就该是无效动作）
        assertSame(list, list.move(0, -1))
        assertSame(list, list.move(2, +1))
    }

    @Test
    fun outOfRangeIndicesThrow() {
        val list = ServerList(listOf(a, b))
        assertThrows(IllegalArgumentException::class.java) { list.update(2, a) }
        assertThrows(IllegalArgumentException::class.java) { list.remove(-1) }
        assertThrows(IllegalArgumentException::class.java) { list.move(2, -1) }
        assertThrows(IllegalArgumentException::class.java) { list.insert(3, a) }
        // 非法方向
        assertThrows(IllegalArgumentException::class.java) { list.move(0, +2) }
    }

    @Test
    fun validationHelpersMatchTheirContracts() {
        assertTrue(ServerList.isValidServerName("392区"))
        assertFalse(ServerList.isValidServerName(""))
        assertFalse(ServerList.isValidServerName("  "))
        assertFalse(ServerList.isValidServerName("a|b"))
        assertFalse(ServerList.isValidServerName("a\nb"))
        // 可选字段：空合法，含分隔符 / 换行不合法
        assertTrue(ServerList.isValidOptionalField(""))
        assertTrue(ServerList.isValidOptionalField("阿明"))
        assertFalse(ServerList.isValidOptionalField("a|b"))
        assertFalse(ServerList.isValidOptionalField("a\rb"))
    }
}
