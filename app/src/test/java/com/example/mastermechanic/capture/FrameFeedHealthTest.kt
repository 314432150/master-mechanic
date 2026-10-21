package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFeedHealthTest {

    /** 一轮结束 → 隔 [gap] 毫秒来帧；重复 [times] 次。 */
    private fun rounds(health: FrameFeedHealth, gapMs: Long, times: Int, startMs: Long = 1_000L): Long {
        var t = startMs
        repeat(times) {
            health.noteRoundEnd(t)
            val callbackAt = t + gapMs
            health.noteCallback(callbackAt)
            t = callbackAt + 1_000L
        }
        return t
    }

    @Test
    fun repeatedSlowGapsJudgeSlow() {
        val health = FrameFeedHealth()
        val end = rounds(health, gapMs = 1_200L, times = 3)
        assertTrue("轮次结束后每次都要等 1.2 秒才来帧 ⇒ 平台送得慢", health.isSlow(end))
        assertEquals(3, health.slowHits())
    }

    @Test
    fun fastGapsNeverJudgeSlow() {
        val health = FrameFeedHealth()
        val end = rounds(health, gapMs = 20L, times = 10)
        assertFalse(health.isSlow(end))
        assertEquals(20L, health.lastFastGapMs())
    }

    @Test
    fun oneSlowGapIsNotEnough() {
        val health = FrameFeedHealth()
        val end = rounds(health, gapMs = 900L, times = 2)
        assertFalse("只慢两次不下结论（冷启动头几帧本来就慢）", health.isSlow(end))
    }

    @Test
    fun fastGapClearsAccumulatedSlowHits() {
        val health = FrameFeedHealth()
        // 两次慢 + 一次快：快的那次把慢计数清零 ⇒ 不应判慢
        var t = 1_000L
        repeat(2) {
            health.noteRoundEnd(t)
            health.noteCallback(t + 900L)
            t += 2_000L
        }
        health.noteRoundEnd(t)
        health.noteCallback(t + 10L)
        t += 1_000L
        assertFalse(health.isSlow(t))
        assertEquals(0, health.slowHits())
    }

    @Test
    fun noCallbackAfterRoundIsNotJudgedSlow() {
        val health = FrameFeedHealth()
        // 静止页：每轮结束后根本没有下一帧 ⇒ 本类不表态（交给停更 / 零投喂两条判据）
        repeat(5) { i ->
            health.noteRoundEnd(1_000L + i * 1_000L)
        }
        assertFalse(health.isSlow(10_000L))
    }

    @Test
    fun callbackDuringRoundIsIgnored() {
        val health = FrameFeedHealth()
        // 还没结束过任何一轮：这条回调没有可比基线
        assertNull(health.noteCallback(1_000L))
        // 一轮结束后先记一次；再来一次回调时基线已被消费 ⇒ 也不参与（一次轮次只观测一次）
        health.noteRoundEnd(2_000L)
        assertNotNull(health.noteCallback(2_010L))
        assertNull(health.noteCallback(2_020L))
    }

    @Test
    fun slowdownConclusionExpiresAfterWindow() {
        val health = FrameFeedHealth()
        val end = rounds(health, gapMs = 1_000L, times = 3)
        assertTrue(health.isSlow(end))
        assertFalse("8 秒内平台没再慢过 ⇒ 结论过期，不能一直挂着", health.isSlow(end + 9_000L))
    }

    @Test
    fun resetDiscardsEverything() {
        val health = FrameFeedHealth()
        rounds(health, gapMs = 1_200L, times = 3)
        health.reset()
        assertEquals(0, health.slowHits())
        assertEquals(-1L, health.lastFastGapMs())
        assertFalse(health.isSlow(100_000L))
    }
}
