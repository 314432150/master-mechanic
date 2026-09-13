package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 巡查配置模型单测（M3-T3-2 / FR-03）：校验（名称不得为空）、重复区服、
 * 增 / 删 / 改 / 调序 / 整体清空，以及越界序号一律抛错。
 */
class PatrolConfigTest {

    private fun item(server: String, friend: String) = PatrolItem(server, friend)

    private val a = item("392区", "阿明")
    private val b = item("418区", "小美")
    private val c = item("392区", "阿强") // 同一区服重复出现（FR-03「重复」允许）

    @Test
    fun rejectsBlankOrIllegalNames() {
        // 空 / 全空白（FR-03「区服名称与好友名称均不得为空」）
        assertThrows(IllegalArgumentException::class.java) { item("", "阿明") }
        assertThrows(IllegalArgumentException::class.java) { item("392区", "   ") }
        assertThrows(IllegalArgumentException::class.java) { item("\t", "阿明") }
        // 含分隔符 / 换行（编码层硬约束，见 PatrolConfigCodec）
        assertThrows(IllegalArgumentException::class.java) { item("392|区", "阿明") }
        assertThrows(IllegalArgumentException::class.java) { item("392区", "阿明\n") }
    }

    @Test
    fun ofTrimsSurroundingWhitespace() {
        val trimmed = PatrolItem.of("  392区  ", " 阿明 ")
        assertEquals("392区", trimmed.serverName)
        assertEquals("阿明", trimmed.friendName)
        // 全是空白 → 去空白后为空 → 仍然拒收
        assertThrows(IllegalArgumentException::class.java) { PatrolItem.of("392区", "   ") }
    }

    @Test
    fun emptyConfigIsValid() {
        assertEquals(0, PatrolConfig.EMPTY.size)
        assertTrue(PatrolConfig.EMPTY.isEmpty)
        assertFalse(PatrolConfig.EMPTY.isNotEmpty)
    }

    @Test
    fun addAppendsToTheEndAndKeepsDuplicates() {
        val config = PatrolConfig.EMPTY.add(a).add(b).add(c)
        assertEquals(listOf(a, b, c), config.items)
        // 同一区服出现两次是合法的，不做去重
        assertEquals(2, config.items.count { it.serverName == "392区" })
    }

    @Test
    fun insertPlacesItemAtGivenPosition() {
        val config = PatrolConfig(listOf(a, c)).insert(1, b)
        assertEquals(listOf(a, b, c), config.items)
        // 插入到末尾（index == size）等价追加
        assertEquals(listOf(a, b, c), PatrolConfig(listOf(a, b)).insert(2, c).items)
    }

    @Test
    fun updateReplacesOnlyTheTargetItem() {
        val original = PatrolConfig(listOf(a, b, c))
        val updated = original.update(1, item("418区", "阿珍"))
        assertEquals(listOf(a, item("418区", "阿珍"), c), updated.items)
        // 原实例不被修改（编辑操作返回新实例）
        assertEquals(listOf(a, b, c), original.items)
    }

    @Test
    fun removeAndClearWork() {
        val config = PatrolConfig(listOf(a, b, c))
        assertEquals(listOf(a, c), config.remove(1).items)
        assertTrue(config.clear().isEmpty)
        // 删空后仍是合法配置
        assertEquals(0, PatrolConfig(listOf(a)).remove(0).size)
    }

    @Test
    fun moveSwapsNeighboursAndStopsAtEnds() {
        val config = PatrolConfig(listOf(a, b, c))
        assertEquals(listOf(b, a, c), config.move(0, +1).items)
        assertEquals(listOf(a, c, b), config.move(2, -1).items)
        // 端点：原样返回（不是错误——界面上的端点按钮就该是无效动作）
        assertSame(config, config.move(0, -1))
        assertSame(config, config.move(2, +1))
    }

    @Test
    fun outOfRangeIndicesThrow() {
        val config = PatrolConfig(listOf(a, b))
        assertThrows(IllegalArgumentException::class.java) { config.update(2, a) }
        assertThrows(IllegalArgumentException::class.java) { config.remove(-1) }
        assertThrows(IllegalArgumentException::class.java) { config.move(2, -1) }
        assertThrows(IllegalArgumentException::class.java) { config.insert(3, a) }
        // 非法方向
        assertThrows(IllegalArgumentException::class.java) { config.move(0, +2) }
    }

    @Test
    fun isValidNameRejectsSeparatorAndBlank() {
        assertTrue(PatrolConfig.isValidName("392区"))
        assertFalse(PatrolConfig.isValidName(""))
        assertFalse(PatrolConfig.isValidName("  "))
        assertFalse(PatrolConfig.isValidName("a|b"))
        assertFalse(PatrolConfig.isValidName("a\nb"))
    }
}
