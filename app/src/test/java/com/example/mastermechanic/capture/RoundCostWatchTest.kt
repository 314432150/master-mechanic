package com.example.mastermechanic.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundCostWatchTest {

    private fun watch(vararg costs: Double, atMs: Long = 1_000L): RoundCostWatch =
        RoundCostWatch().also { w -> costs.forEachIndexed { i, c -> w.record(atMs + i * 100L, c) } }

    // 真机 2026-10-19 23:48：每轮 ≈2800ms（其中识别 697ms、灰度 35.5ms）
    @Test
    fun slowRoundsJudgeWarming() {
        val w = watch(2_800.0, 2_700.0, 2_900.0)
        assertEquals(RoundCostWatch.Status.WARMING, w.status(2_000L))
    }

    // 真机稳定期：每轮 ≈82ms
    @Test
    fun normalRoundsJudgeWarm() {
        val w = watch(82.0, 78.0, 96.0)
        assertEquals(RoundCostWatch.Status.WARM, w.status(2_000L))
    }

    // 第 5 / 9 步读名字那几轮天然 300~400ms：一两轮 OCR 不能把正常流程判成"没热好"
    @Test
    fun oneSlowOcrRoundDoesNotBlockStart() {
        val w = watch(82.0, 400.0, 78.0, 85.0)
        assertEquals(RoundCostWatch.Status.WARM, w.status(2_000L))
    }

    @Test
    fun singleSampleIsNoEvidence() {
        val w = watch(2_800.0)
        assertEquals(RoundCostWatch.Status.NO_DATA, w.status(1_500L))
        assertNull(w.typicalMs(1_500L))
    }

    @Test
    fun staleSamplesAreIgnored() {
        val w = RoundCostWatch()
        w.record(1_000L, 2_800.0)
        w.record(1_100L, 2_800.0)
        // 10 秒后这两轮已经不代表现在
        assertEquals(RoundCostWatch.Status.NO_DATA, w.status(20_000L))
    }

    @Test
    fun onlyLastCapacitySamplesCount() {
        val w = RoundCostWatch(capacity = 4)
        // 先慢 4 轮，后快 4 轮：窗口里只剩快的 ⇒ 应判可开跑
        repeat(4) { i -> w.record(1_000L + i * 100L, 2_800.0) }
        repeat(4) { i -> w.record(2_000L + i * 100L, 80.0) }
        assertEquals(RoundCostWatch.Status.WARM, w.status(2_500L))
    }

    @Test
    fun resetDiscardsEverything() {
        val w = watch(2_800.0, 2_800.0, 2_800.0)
        w.reset()
        assertEquals(RoundCostWatch.Status.NO_DATA, w.status(1_500L))
        assertFalse(w.typicalMs(1_500L) != null)
    }

    @Test
    fun thresholdBoundaryIsInclusiveWarm() {
        // 恰好 250ms 视为"算得动"（门限语义：≤ 即可）
        val w = watch(250.0, 250.0)
        assertTrue(w.status(1_500L) == RoundCostWatch.Status.WARM)
    }
}
