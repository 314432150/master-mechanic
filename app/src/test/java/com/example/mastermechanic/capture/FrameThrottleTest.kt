package com.example.mastermechanic.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameThrottleTest {

    @Test
    fun firstFrameAlwaysPasses() {
        assertTrue(FrameThrottle(1000).shouldProcess(Long.MAX_VALUE))
    }

    @Test
    fun framesWithinIntervalAreSkipped() {
        val throttle = FrameThrottle(1000)
        assertTrue(throttle.shouldProcess(10_000))
        assertFalse(throttle.shouldProcess(10_500))
        assertFalse(throttle.shouldProcess(10_999))
    }

    @Test
    fun frameAtIntervalBoundaryPasses() {
        val throttle = FrameThrottle(1000)
        assertTrue(throttle.shouldProcess(10_000))
        assertTrue(throttle.shouldProcess(11_000))
    }

    @Test
    fun intervalRestartsAfterEachPass() {
        val throttle = FrameThrottle(1000)
        assertTrue(throttle.shouldProcess(0))
        assertFalse(throttle.shouldProcess(999))
        assertTrue(throttle.shouldProcess(1_000))
        assertFalse(throttle.shouldProcess(1_500))
        assertTrue(throttle.shouldProcess(2_000))
    }

    @Test
    fun setIntervalTakesEffectOnNextDecision() {
        val throttle = FrameThrottle(1000)
        assertTrue(throttle.shouldProcess(0))
        assertFalse(throttle.shouldProcess(500))

        throttle.setInterval(3000) // 调大立即生效
        assertFalse(throttle.shouldProcess(1_500))
        assertTrue(throttle.shouldProcess(3_000))

        throttle.setInterval(500) // 调小立即生效
        assertFalse(throttle.shouldProcess(3_300))
        assertTrue(throttle.shouldProcess(3_500))
    }

    @Test
    fun setIntervalRejectsNonPositive() {
        val throttle = FrameThrottle(1000)
        assertThrows(IllegalArgumentException::class.java) { throttle.setInterval(0) }
    }

    @Test
    fun markProcessedEndRestartsIntervalFromProcessingEnd() {
        // T1-13：间隔是「两轮之间的休息时间」——单轮耗时（此处 270ms，大窗口信号量级）不计入间隔，
        // 否则会出现"处理完就满足间隔"的连续满负荷。
        val throttle = FrameThrottle(200)
        assertTrue(throttle.shouldProcess(0))
        throttle.markProcessedEnd(270)
        assertFalse("距本轮结束不足 200ms → 跳过", throttle.shouldProcess(400))
        assertTrue("距本轮结束满 200ms → 放行", throttle.shouldProcess(470))
    }
}
