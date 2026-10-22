package com.example.mastermechanic.badge

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BadgePoolView]：分数倒序、top 3 截断、展开全部、展开行可见性。 */
class BadgePoolViewTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 21)

    private fun record(server: String, code: String, score: Int) =
        BadgeRecord(server, "立牌甲", code, score, day)

    private val unsorted = listOf(
        record("莲动渔舟", "BBBB22", 199),
        record("发条权杖", "AAAA11", 877),
        record("月色如水", "CCCC33", 877),
        record("星河入梦", "DDDD44", 307),
    )

    @Test
    fun sortsByScoreDescThenServerThenCode() {
        val sorted = BadgePoolView.byScoreDesc(unsorted)
        assertEquals(
            listOf("发条权杖", "月色如水", "星河入梦", "莲动渔舟"),
            sorted.map { it.serverName },
        )
    }

    @Test
    fun tieBreakIsDeterministicByServerThenCode() {
        val tied = listOf(
            record("月色如水", "CCCC33", 877),
            record("发条权杖", "BBBB22", 877),
            record("发条权杖", "AAAA11", 877),
        )
        assertEquals(
            listOf("AAAA11", "BBBB22", "CCCC33"),
            BadgePoolView.byScoreDesc(tied).map { it.code },
        )
    }

    @Test
    fun collapsedShowsOnlyTopThree() {
        val rows = BadgePoolView.rows(BadgeData(records = unsorted), expanded = false)
        assertEquals(3, rows.size)
        assertEquals(listOf(877, 877, 307), rows.map { it.score })
    }

    @Test
    fun expandedShowsAll() {
        val rows = BadgePoolView.rows(BadgeData(records = unsorted), expanded = true)
        assertEquals(4, rows.size)
    }

    @Test
    fun rowsCarryCopyableCode() {
        val rows = BadgePoolView.rows(BadgeData(records = unsorted), expanded = true)
        assertEquals("AAAA11", rows.first().code)
    }

    @Test
    fun rowShowsServerScoreAndCode() {
        // 2026-10-22 第三次口径：行上是**区服名 · 分数 · 分享码**（立牌名不上屏，只在复制内容与读屏里）
        val rows = BadgePoolView.rows(BadgeData(records = unsorted), expanded = true)
        val top = rows.first()
        assertEquals("发条权杖 · 877分 · AAAA11", top.displayText)
        assertEquals("立牌名不该出现在显示文字里", false, top.displayText.contains(top.cardName))
    }

    @Test
    fun copiedTextMatchesTheUsersFormatExactly() {
        // 用户给定格式（逐字）：【曹操顶级立牌WD6HD5】价值948分
        val row = BadgePoolView.Row(
            serverName = "发条权杖",
            cardName = "曹操顶级立牌",
            code = "WD6HD5",
            score = 948,
        )
        assertEquals("【曹操顶级立牌WD6HD5】价值948分", row.copyText)
    }

    @Test
    fun expandToggleVisibleOnlyWhenOverTopCount() {
        assertFalse(BadgePoolView.showExpandToggle(3))
        assertTrue(BadgePoolView.showExpandToggle(4))
    }

    @Test
    fun summaryCountsAllRecordsAndTakesTheTopScore() {
        // 顶部统计（2026-10-23 用户口径）：总数 + 最高分；**统计的是全部记录**，不是 top 3 那三行
        val summary = BadgePoolView.summary(BadgeData(records = unsorted, collected = emptyList()))
        assertEquals(4, summary.total)
        assertEquals(877, summary.maxScore)
    }

    @Test
    fun emptyPoolHasNoTopScore() {
        val summary = BadgePoolView.summary(BadgeData())
        assertEquals(0, summary.total)
        assertEquals(null, summary.maxScore)
    }
}
