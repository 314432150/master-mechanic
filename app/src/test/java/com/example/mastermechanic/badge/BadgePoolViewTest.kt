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
    fun expandToggleVisibleOnlyWhenOverTopCount() {
        assertFalse(BadgePoolView.showExpandToggle(3))
        assertTrue(BadgePoolView.showExpandToggle(4))
    }
}
