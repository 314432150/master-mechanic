package com.example.mastermechanic.capture

import org.junit.Assert.assertFalse
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
}
