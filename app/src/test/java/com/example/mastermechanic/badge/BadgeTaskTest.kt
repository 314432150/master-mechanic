package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * [BadgeTask]（临时需求 temp/badge-share-1021）：**这一轮从哪开始**的纯逻辑口径。
 *
 * 2026-10-23 用户口径：不再看"今天已收集过"（一天一次的布尔值说不清跑到第几个），
 * 改成盘上的一轮标记 ——
 * - 上一轮**没结束**（RUNNING）⇒ 从它停下的区服接着收；
 * - 上一轮**已结束**（DONE / 没标记）⇒ 从清单第 1 个开始。
 * 这样也就不需要"刚跑完 10 秒内忽略"的时间猜测了。
 */
class BadgeTaskTest {

    private val today = LocalDate.of(2026, 10, 23)

    private val names = listOf("星月晚", "云归处", "雾隐山")

    // ---------------------------------------------------------------- 轮次标记的文本形式

    @Test
    fun runningMarkerCarriesTheNextServer() {
        val text = BadgeTask.formatMarker(today, BadgeTask.RoundState.RUNNING, "云归处")
        assertEquals("2026-10-23|RUNNING|云归处", text)
        assertEquals(
            BadgeTask.RoundMarker(BadgeTask.RoundState.RUNNING, "云归处"),
            BadgeTask.parseMarker(text, today),
        )
    }

    @Test
    fun doneMarkerCarriesNoServer() {
        val text = BadgeTask.formatMarker(today, BadgeTask.RoundState.DONE, null)
        assertEquals("2026-10-23|DONE|", text)
        assertEquals(
            BadgeTask.RoundMarker(BadgeTask.RoundState.DONE, null),
            BadgeTask.parseMarker(text, today),
        )
    }

    @Test
    fun markerFromAnotherDayIsIgnored() {
        // 隔天的标记没有意义：今天该从清单第 1 个开始
        assertNull(BadgeTask.parseMarker("2026-10-22|RUNNING|云归处", today))
    }

    @Test
    fun malformedMarkerIsIgnored() {
        assertNull(BadgeTask.parseMarker("", today))
        assertNull(BadgeTask.parseMarker("云归处", today))
    }

    @Test
    fun oldCursorFormatStillReadsAsRunning() {
        // 兼容 2026-10-23 早些时候的旧格式 `日期|区服名`（那时只当续跑游标）：按"进行中"读，别丢进度
        assertEquals(
            BadgeTask.RoundMarker(BadgeTask.RoundState.RUNNING, "云归处"),
            BadgeTask.parseMarker("2026-10-23|云归处", today),
        )
    }

    // ---------------------------------------------------------------- 队列起点

    @Test
    fun resumingRotatesTheQueueToTheRecordedServerWithoutLosingAnyone() {
        val marker = BadgeTask.parseMarker(
            BadgeTask.formatMarker(today, BadgeTask.RoundState.RUNNING, "云归处"),
            today,
        )
        assertEquals(listOf("云归处", "雾隐山", "星月晚"), BadgeSession.rotateTo(names, marker!!.server!!))
    }

    @Test
    fun finishedRoundLeavesTheListOrderAlone() {
        // DONE ⇒ 不做任何轮换（起点 = 清单第 1 个）。这里钉住"轮换只由 RUNNING 触发"这条边界：
        // DONE 标记 parse 出来不能带区服名，否则调用方会误以为还要轮换。
        val marker = BadgeTask.parseMarker(
            BadgeTask.formatMarker(today, BadgeTask.RoundState.DONE, "云归处"),
            today,
        )
        assertEquals(BadgeTask.RoundState.DONE, marker!!.state)
        assertNull("DONE 标记不该带区服名（带了也不读）", marker.server)
    }
}
