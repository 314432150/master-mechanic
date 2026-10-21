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
}
