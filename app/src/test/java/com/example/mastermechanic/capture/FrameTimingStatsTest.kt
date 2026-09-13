package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTimingStatsTest {

    @Test
    fun returnsNullUntilWindowFull() {
        val stats = FrameTimingStats()
        repeat(FrameTimingStats.WINDOW_SIZE - 1) { i ->
            assertNull("第 ${i + 1} 个样本未满窗不应输出", stats.record(4.0))
        }
        val summary = stats.record(4.0)
        assertNotNull(summary)
        assertEquals(100, summary!!.sampleCount)
    }

    @Test
    fun p95UsesNearestRank() {
        val stats = FrameTimingStats()
        // 样本 1..100：升序第 95 位 = 95.0
        var summary: TimingSummary? = null
        (1..100).forEach { summary = stats.record(it.toDouble()) }
        assertEquals(95.0, summary!!.p95Ms, 0.0)
        assertEquals(50.5, summary!!.avgMs, 0.0)
        assertEquals(100.0, summary!!.maxMs, 0.0)
    }

    @Test
    fun uniformSamplesReportSameValue() {
        val stats = FrameTimingStats()
        var summary: TimingSummary? = null
        repeat(100) { summary = stats.record(10.0) }
        assertEquals(10.0, summary!!.p95Ms, 0.0)
    }

    @Test
    fun windowResetsAfterFull() {
        val stats = FrameTimingStats()
        repeat(100) { stats.record(1.0) }
        // 满窗后样本不延续：再 99 个仍不满窗
        repeat(99) { assertNull(stats.record(2.0)) }
        val second = stats.record(2.0)
        assertEquals(2.0, second!!.p95Ms, 0.0)
    }

    @Test
    fun resetDiscardsPartialWindow() {
        val stats = FrameTimingStats()
        repeat(50) { stats.record(1.0) }
        stats.reset()
        // 若未丢弃，再 50 个即满窗；重置后需完整 100 个
        repeat(99) { assertNull(stats.record(1.0)) }
        assertNotNull(stats.record(1.0))
    }

    @Test
    fun customWindowSizeUsesNearestRank() {
        val stats = FrameTimingStats(windowSize = 20)
        var summary: TimingSummary? = null
        (1..20).forEach { summary = stats.record(it.toDouble()) }
        // ⌈0.95 × 20⌉ = 19 → 升序第 19 位 = 19.0
        assertEquals(19.0, summary!!.p95Ms, 0.0)
    }

    @Test
    fun windowSizeMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { FrameTimingStats(windowSize = 0) }
    }
}
