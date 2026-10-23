package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [BadgeTask]（临时需求 temp/badge-share-1021）：**这一轮要收哪些**的纯逻辑口径。
 *
 * 2026-10-23 用户口径（两次报障后定稿）：
 * 1. 不再看"今天已收集过"（一天一次的布尔值说不清跑到第几个）；
 * 2. 也不用"刚跑完 10 秒内忽略"的时间猜测；
 * 3. 盘上记的是**本轮已经收过的区服集合**，续跑 = 清单原顺序 + 跳过它们 ——
 *    不能只记"下一个是谁"再靠轮换队列把它转到队首：那样清单里排在前面的区服会被排到这一轮最后，
 *    用户看到的是"已经收完的第 1 个又跑了一遍"（第三次报障的直接原因）。
 */
class BadgeTaskTest {

    private val today = LocalDate.of(2026, 10, 23)

    private val names = listOf("星月晚", "云归处", "雾隐山")

    // ---------------------------------------------------------------- 轮次标记的文本形式

    @Test
    fun runningMarkerCarriesTheServersAlreadyCollectedInThisRound() {
        val text = BadgeTask.formatMarker(today, BadgeTask.RoundState.RUNNING, setOf("星月晚", "云归处"))
        assertEquals("2026-10-23|RUNNING|星月晚,云归处", text)
        assertEquals(
            BadgeTask.RoundMarker(BadgeTask.RoundState.RUNNING, setOf("星月晚", "云归处")),
            BadgeTask.parseMarker(text, today),
        )
    }

    @Test
    fun doneMarkerCarriesNoServers() {
        val text = BadgeTask.formatMarker(today, BadgeTask.RoundState.DONE, emptySet())
        assertEquals("2026-10-23|DONE|", text)
        assertEquals(BadgeTask.RoundMarker(BadgeTask.RoundState.DONE), BadgeTask.parseMarker(text, today))
    }

    @Test
    fun markerFromAnotherDayIsIgnored() {
        // 隔天的标记没有意义：今天该从清单第 1 个开始
        assertNull(BadgeTask.parseMarker("2026-10-22|RUNNING|星月晚", today))
    }

    @Test
    fun malformedMarkerIsIgnored() {
        assertNull(BadgeTask.parseMarker("", today))
        assertNull(BadgeTask.parseMarker("星月晚", today))
    }

    @Test
    fun oldCursorFormatIsTreatedAsFinished() {
        // 旧格式 `日期|区服名` 只记了"下一个是谁"，说不出哪些已经收过 ⇒ 宁可干净地重跑一轮
        assertEquals(
            BadgeTask.RoundMarker(BadgeTask.RoundState.DONE),
            BadgeTask.parseMarker("2026-10-23|云归处", today),
        )
    }

    // ---------------------------------------------------------------- 续跑的队列

    @Test
    fun resumingKeepsListOrderAndSkipsWhatThisRoundAlreadyCollected() {
        // 关键回归（2026-10-23 第三次报障）：已收「星月晚」⇒ 续跑只跑「云归处、雾隐山」，
        // **不轮换**（轮换会把「星月晚」绕到最后，看起来像从头开始、永远跑不完）
        val marker = BadgeTask.parseMarker(
            BadgeTask.formatMarker(today, BadgeTask.RoundState.RUNNING, setOf("星月晚")),
            today,
        )!!
        val remaining = names.filterNot { it in marker.done }
        assertEquals(listOf("云归处", "雾隐山"), remaining)
        assertEquals("列表第 1 个不该被绕到后面", "云归处", remaining.first())
    }

    @Test
    fun everythingCollectedMeansTheRoundIsOver() {
        // 本轮 14 个全收完 ⇒ 剩余为空 ⇒ 一轮结束（写 DONE 后下次才从第 1 个重新开始）
        val marker = BadgeTask.parseMarker(
            BadgeTask.formatMarker(today, BadgeTask.RoundState.RUNNING, names.toSet()),
            today,
        )!!
        assertTrue(names.filterNot { it in marker.done }.isEmpty())
    }
}
