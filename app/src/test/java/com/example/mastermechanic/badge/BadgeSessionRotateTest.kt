package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列轮换（2026-10-23 用户报障）：整轮重跑时人已经在某个区服 ⇒ 从它开始，而不是从清单第一个重来。
 *
 * 只测纯逻辑那一段（[BadgeSession.rotateTo]）：集合必须**一个不少**，顺序只换起点。
 */
class BadgeSessionRotateTest {

    private val queue = listOf("发条权杖", "海鹰之羽", "莲动渔舟", "碧海之眼")

    @Test
    fun rotateToTheCurrentServerKeepsEveryServer() {
        // 人在第 2 个 ⇒ 从它开始，后面的接上，前面的排到末尾（不是丢掉）
        assertEquals(
            listOf("海鹰之羽", "莲动渔舟", "碧海之眼", "发条权杖"),
            BadgeSession.rotateTo(queue, "海鹰之羽"),
        )
        assertEquals(
            listOf("碧海之眼", "发条权杖", "海鹰之羽", "莲动渔舟"),
            BadgeSession.rotateTo(queue, "碧海之眼"),
        )
    }

    @Test
    fun rotateToTheFirstServerChangesNothing() {
        assertEquals(queue, BadgeSession.rotateTo(queue, "发条权杖"))
    }

    @Test
    fun serverOutsideTheQueueIsNotInvented() {
        // 不在队列里 ⇒ 原样返回（打乱顺序只会让人看不懂）
        assertEquals(queue, BadgeSession.rotateTo(queue, "不存在的区服"))
    }

    @Test
    fun emptyQueueStaysEmpty() {
        assertEquals(emptyList<String>(), BadgeSession.rotateTo(emptyList(), "发条权杖"))
    }
}
