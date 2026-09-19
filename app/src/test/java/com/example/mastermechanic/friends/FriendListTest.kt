package com.example.mastermechanic.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 好友清单模型单测（FR-07「拜访」三级列表的数据源；2026-09-15 新增）。
 *
 * 只测**纯逻辑**：格式校验、同一个好友只能有一条、增删改调序、以及"不可变 + 越界抛错"这套口径
 * （与 FR-03 巡查配置 / FR-10 服务器清单完全一致，换个색数据换了，规则没换）。
 */
class FriendListTest {

    private fun list(vararg names: String) = FriendList(names.map { FriendEntry(it) })

    // ---------------------------------------------------------------- 条目与格式校验

    @Test
    fun entryTrimsEndsButKeepsInnerSpaces() {
        assertEquals("张三", FriendEntry.of("  张三  ").name)
        // 名字中间的空格式属书写的一部分：改了就不是屏幕上的那个名字（定位会失准）
        assertEquals("卧龙山 小趴菜", FriendEntry.of(" 卧龙山 小趴菜 ").name)
    }

    @Test
    fun entryRejectsBlankName() {
        assertTrue(runCatching { FriendEntry.of("   ") }.isFailure)
        assertTrue(runCatching { FriendEntry.of("") }.isFailure)
    }

    @Test
    fun entryRejectsSeparatorAndNewline() {
        assertTrue(runCatching { FriendEntry.of("阿明|备用") }.isFailure)
        assertTrue(runCatching { FriendEntry.of("阿明\n第二个") }.isFailure)
    }

    // ---------------------------------------------------------------- 唯一性（构造时不变量）

    @Test
    fun sameFriendTwiceIsRejectedAtConstruction() {
        // 唯一性是**类型的不变量**，不是界面上的一道校验：构造 / 新增 / 插入 / 改写 / 读盘全被它盖住
        assertTrue(runCatching { FriendList(listOf(FriendEntry("阿明"), FriendEntry("阿明"))) }.isFailure)
    }

    @Test
    fun addingExistingFriendIsRejected() {
        assertTrue(runCatching { list("阿明").add(FriendEntry("阿明")) }.isFailure)
    }

    @Test
    fun renamingToAnotherExistingFriendIsRejected() {
        val friends = list("阿明", "张三")
        // 改自己（名字没动）→ 放行
        assertEquals(2, friends.update(0, FriendEntry("阿明")).size)
        // 改成别人用着的名字 → 拒绝（不静默去重）
        assertTrue(runCatching { friends.update(0, FriendEntry("张三")) }.isFailure)
    }

    // ---------------------------------------------------------------- 编辑操作

    @Test
    fun addAppendsAndInsertRespectsPosition() {
        assertEquals(listOf("阿明", "张三"), list("阿明").add(FriendEntry("张三")).entries.map { it.name })
        assertEquals(
            listOf("张三", "阿明", "李四"),
            list("阿明", "李四").insert(0, FriendEntry("张三")).entries.map { it.name },
        )
        // 插入位置只能在 0..size：越界说明界面算错了，静默夹取会掩盖 bug
        assertTrue(runCatching { list("阿明").insert(5, FriendEntry("张三")) }.isFailure)
    }

    @Test
    fun removeAndClearWorkAndKeepOthersInOrder() {
        assertEquals(listOf("阿明"), list("阿明", "张三").remove(1).entries.map { it.name })
        assertTrue(list("阿明").clear().isEmpty)
        assertTrue(runCatching { list("阿明").remove(1) }.isFailure)
    }

    @Test
    fun moveByOneKeepsListSizeAndIsNoOpAtEndpoints() {
        val friends = list("一", "二", "三")
        assertEquals(listOf("二", "一", "三"), friends.move(0, +1).entries.map { it.name })
        assertEquals(listOf("一", "三", "二"), friends.move(2, -1).entries.map { it.name })
        // 端点：按钮本来就该是无效动作，原样返回（不是错误，也不写盘）
        assertEquals(friends, friends.move(0, -1))
        assertEquals(friends, friends.move(2, +1))
        assertTrue(runCatching { friends.move(0, +2) }.isFailure)
    }

    @Test
    fun indexOfNameFindsTheMatchOnly() {
        val friends = list("阿明", "张三")
        assertEquals(1, friends.indexOfName("张三"))
        assertEquals(-1, friends.indexOfName("李四"))
    }

    @Test
    fun emptyListIsLegal() {
        // 「整体清空」的结果必须能构造出来，它不是一种损坏状态
        assertTrue(FriendList.EMPTY.isEmpty)
        assertFalse(FriendList.EMPTY.isNotEmpty)
        assertEquals(0, FriendList.EMPTY.size)
    }
}
