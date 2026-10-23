package com.example.mastermechanic.badge

import com.example.mastermechanic.servers.ServerEntry
import com.example.mastermechanic.servers.ServerList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BadgeTask]（临时需求 temp/badge-share-1021）：跨区服队列的纯逻辑口径。
 *
 * 队列 = **清单顺序**，剔除今天已收集的（FR-T1 / FR-T2）——
 * 不排序、不改写名字（屏幕上要逐字符对得上，红线 3）。
 */
class BadgeTaskTest {

    private val list = ServerList(
        listOf(
            ServerEntry(serverNo = "1", serverName = "星月晚"),
            ServerEntry(serverNo = "2", serverName = "云归处"),
            ServerEntry(serverNo = "3", serverName = "雾隐山"),
        ),
    )

    @Test
    fun queueKeepsTheListOrder() {
        assertEquals(listOf("星月晚", "云归处", "雾隐山"), BadgeTask.queueOf(list) { false })
    }

    @Test
    fun collectedServersAreSkipped() {
        val queue = BadgeTask.queueOf(list) { it == "云归处" }
        assertEquals(listOf("星月晚", "雾隐山"), queue)
    }

    @Test
    fun emptyListYieldsEmptyQueue() {
        assertEquals(emptyList<String>(), BadgeTask.queueOf(ServerList.EMPTY) { false })
    }

    @Test
    fun everythingCollectedYieldsEmptyQueue() {
        assertEquals(emptyList<String>(), BadgeTask.queueOf(list) { true })
    }

    @Test
    fun nothingPendingRerunsTheWholeList() {
        // 2026-10-22 用户口径："不应该阻止我重复搜集" ⇒ 全部已收集时按整轮重跑
        assertEquals(
            listOf("星月晚", "云归处", "雾隐山"),
            BadgeTask.runQueueOf(list, pending = emptyList()),
        )
    }

    @Test
    fun pendingServersWinOverTheRerun() {
        // 有没收集的 ⇒ 仍按"续跑"只跑这些（重复搜集不被拦，但也不必白跑已完成的）
        assertEquals(
            listOf("云归处"),
            BadgeTask.runQueueOf(list, pending = listOf("云归处")),
        )
    }

    @Test
    fun emptyListStillRerunsNothing() {
        assertEquals(emptyList<String>(), BadgeTask.runQueueOf(ServerList.EMPTY, pending = emptyList()))
    }

    // ---------------------------------------------------------------- 续跑游标

    private val today = java.time.LocalDate.of(2026, 10, 23)

    @Test
    fun cursorRoundTripsThroughItsOwnTextForm() {
        val text = BadgeTask.formatCursor(today, "云归处")
        assertEquals("2026-10-23|云归处", text)
        assertEquals("云归处", BadgeTask.parseCursor(text, today))
    }

    @Test
    fun cursorFromAnotherDayIsIgnored() {
        // 隔天的游标没有意义：那是上一轮的进度，今天该从清单第一个开始
        assertEquals(null, BadgeTask.parseCursor("2026-10-22|云归处", today))
    }

    @Test
    fun malformedCursorIsIgnored() {
        assertEquals(null, BadgeTask.parseCursor("", today))
        assertEquals(null, BadgeTask.parseCursor("云归处", today))
        assertEquals(null, BadgeTask.parseCursor("2026-10-23|", today))
    }

    @Test
    fun resumingFromTheCursorRotatesTheQueueWithoutLosingAnyone() {
        // 2026-10-23 用户报障：收完第 1 个区服、在换第 2 个的重登中途强制停止 ⇒ 再发起要从第 2 个开始，
        // 而不是从清单第一个。实现 = 用 `BadgeSession.rotateTo` 把游标转到队首（集合不变）。
        val queue = listOf("星月晚", "云归处", "雾隐山")
        val cursor = BadgeTask.parseCursor(BadgeTask.formatCursor(today, "云归处"), today)
        assertEquals(listOf("云归处", "雾隐山", "星月晚"), BadgeSession.rotateTo(queue, cursor!!))
    }
}
