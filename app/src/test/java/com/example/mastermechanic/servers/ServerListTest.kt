package com.example.mastermechanic.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器清单模型单测（M3-T3-9 / FR-10）：字段校验（区服名必填，平台 / 区号 / 角色名 / 等级可空）、
 * **同一区服只能有一条**（一个区服只有一个小号；判重**只认区服名称**）、增 / 删 / 改 / 调序 / 整体清空，
 * 以及越界序号一律抛错。
 */
class ServerListTest {

    private val a = ServerEntry(ServerPlatform.WECHAT, "392", "微信392区", "阿明", "Lv.50")
    private val b = ServerEntry(serverName = "418区") // 平台 / 区号 / 角色名 / 等级全留空
    private val c = ServerEntry(ServerPlatform.QQ, "502", "QQ502区", "阿强")

    @Test
    fun rejectsBlankServerNameAndIllegalFields() {
        // 区服名称必填（它是唯一参与定位的字段）
        assertThrows(IllegalArgumentException::class.java) { ServerEntry(serverName = "") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry(serverName = "   ") }
        assertThrows(IllegalArgumentException::class.java) { ServerEntry(serverName = "\t") }
        // 任一字段含分隔符 / 换行 → 拒收（编码层硬约束，见 ServerListCodec）
        assertThrows(IllegalArgumentException::class.java) { ServerEntry(serverName = "392|区") }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEntry(serverName = "392区", characterName = "阿|明")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEntry(serverName = "392区", level = "Lv|\n50")
        }
        // 区号也是"可留空，但同样不许含分隔符 / 换行"
        assertThrows(IllegalArgumentException::class.java) {
            ServerEntry(serverName = "392区", serverNo = "3|92")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEntry(serverName = "392区", serverNo = "\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerEntry(serverName = "392区").copy(level = "\n")
        }
    }

    @Test
    fun optionalFieldsMayBeEmpty() {
        // 平台 / 区号 / 角色名 / 等级留空都是正常状态（只用于辨识，不参与定位）
        assertNull(b.platform)
        assertEquals("", b.serverNo)
        assertEquals("", b.characterName)
        assertEquals("", b.level)
        assertFalse(b.hasPlatform)
        assertFalse(b.hasServerNo)
        assertFalse(b.hasCharacterName)
        assertFalse(b.hasLevel)
        assertTrue(a.hasPlatform)
        assertTrue(a.hasServerNo)
        assertTrue(a.hasCharacterName)
        assertTrue(a.hasLevel)
    }

    @Test
    fun ofTrimsEachFieldAndRejectsBlankServerName() {
        val trimmed = ServerEntry.of(ServerPlatform.WECHAT, " 392 ", "  微信392区 ", " 阿明 ", "  Lv.50 ")
        assertEquals(a, trimmed)
        // 省略可选字段 = 留空
        assertEquals(ServerEntry(serverName = "微信392区"), ServerEntry.of(serverName = "微信392区"))
        // 区服名全是空白 → 去空白后为空 → 仍然拒收
        assertThrows(IllegalArgumentException::class.java) { ServerEntry.of(serverName = "   ") }
        // 可选字段全是空白 → 归为空（不是"一个空格的角色名"）
        assertEquals("", ServerEntry.of(serverName = "392区", characterName = "   ").characterName)
        assertEquals("", ServerEntry.of(serverName = "392区", serverNo = "   ").serverNo)
    }

    @Test
    fun emptyListIsValid() {
        assertEquals(0, ServerList.EMPTY.size)
        assertTrue(ServerList.EMPTY.isEmpty)
        assertFalse(ServerList.EMPTY.isNotEmpty)
    }

    @Test
    fun addAppendsToTheEnd() {
        val list = ServerList.EMPTY.add(a).add(b).add(c)
        assertEquals(listOf(a, b, c), list.entries)
    }

    @Test
    fun duplicateServerNameIsRejectedEverywhere() {
        // 一个区服只有一个小号：唯一性是构造时不变量，构造点一处管住所有增 / 插 / 改
        assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(a, c.copy(serverName = "微信392区")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(a)).add(c.copy(serverName = "微信392区"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(a)).insert(1, c.copy(serverName = "微信392区"))
        }
        // 把某一条改成别的既有条目的区服名 → 拒收
        assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(a, c)).update(1, c.copy(serverName = "微信392区"))
        }
        // 错误消息说清是哪条、为什么
        val e = assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(a, c.copy(serverName = "微信392区")))
        }
        assertTrue(e.message!!.contains("微信392区"))
        assertTrue(e.message!!.contains("只能有一条"))
    }

    @Test
    fun duplicateIsDecidedByServerNameOnly() {
        // 平台与区号只是附加的辨识信息：它们不同**也照样算重名**（用户口径 2026-09-14）
        assertThrows(IllegalArgumentException::class.java) {
            ServerList(listOf(b)).add(ServerEntry(ServerPlatform.QQ, "999", "418区"))
        }
        // 反过来，区服名称不同就是两条（哪怕平台区号一模一样）
        val same = ServerList(listOf(b)).add(ServerEntry(ServerPlatform.QQ, "418", "419区"))
        assertEquals(listOf("418区", "419区"), same.entries.map { it.serverName })
    }

    @Test
    fun updateKeepingOwnServerNameIsAllowed() {
        // 只改平台 / 区号 / 角色名 / 等级（区服名没动）不能被自己的重名挡住
        val original = ServerList(listOf(a, b))
        val edited = a.copy(serverNo = "393", characterName = "阿明二号", level = "满级")
        val updated = original.update(0, edited)
        assertEquals(listOf(edited, b), updated.entries)
    }

    @Test
    fun indexOfServerNameFindsEntryOrMinusOne() {
        val list = ServerList(listOf(a, b, c))
        assertEquals(0, list.indexOfServerName("微信392区"))
        assertEquals(2, list.indexOfServerName("QQ502区"))
        assertEquals(-1, list.indexOfServerName("999区"))
        assertEquals(-1, ServerList.EMPTY.indexOfServerName("微信392区"))
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
        val edited = b.copy(characterName = "小美", level = "Lv.30")
        val updated = original.update(1, edited)
        assertEquals(listOf(a, edited, c), updated.entries)
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
    fun moveToRelocatesEntryAndKeepsOthersOrdered() {
        val list = ServerList(listOf(a, b, c))
        // 往前搬：C 拖到最上面
        assertEquals(listOf(c, a, b), list.moveTo(2, 0).entries)
        // 往后搬：A 拖到最后
        assertEquals(listOf(b, c, a), list.moveTo(0, 2).entries)
        // 相邻搬一格 == move(+, 1)（拖动与"上移 / 下移"是同一套顺序语义）
        assertEquals(listOf(b, a, c), list.moveTo(0, 1).entries)
        assertEquals(listOf(b, a, c), list.move(0, +1).entries)
        // 原实例不被修改（编辑操作返回新实例）
        assertEquals(listOf(a, b, c), list.entries)
    }

    @Test
    fun moveToSamePositionReturnsSameInstanceAndOutOfRangeThrows() {
        val list = ServerList(listOf(a, b, c))
        // 拖动没跨过任何一行 → 顺序没变，返回原实例（界面据此不写盘）
        assertSame(list, list.moveTo(1, 1))
        assertThrows(IllegalArgumentException::class.java) { list.moveTo(3, 0) }
        assertThrows(IllegalArgumentException::class.java) { list.moveTo(0, 3) }
        assertThrows(IllegalArgumentException::class.java) { list.moveTo(-1, 0) }
        // 空清单上拖不出东西来
        assertThrows(IllegalArgumentException::class.java) { ServerList.EMPTY.moveTo(0, 0) }
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
        // 可选字段（区号 / 角色名 / 等级）：空合法，含分隔符 / 换行不合法
        assertTrue(ServerList.isValidOptionalField(""))
        assertTrue(ServerList.isValidOptionalField("392"))
        assertTrue(ServerList.isValidOptionalField("阿明"))
        assertFalse(ServerList.isValidOptionalField("a|b"))
        assertFalse(ServerList.isValidOptionalField("a\rb"))
    }

    @Test
    fun platformCodeRoundTripMatchesEnums() {
        // 盘上的编码 ↔ 枚举：只有这两个编码认，别的一律当"没填"（由调用方决定拒收）
        assertEquals(ServerPlatform.WECHAT, ServerPlatform.fromCode("wechat"))
        assertEquals(ServerPlatform.QQ, ServerPlatform.fromCode("qq"))
        assertNull(ServerPlatform.fromCode("wx"))
        assertNull(ServerPlatform.fromCode(""))
        assertEquals(listOf("wechat", "qq"), ServerPlatform.entries.map { it.code })
    }
}
