package com.example.mastermechanic.action

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入拟人化单测（2026-09-21）。
 *
 * 钉住两件事：
 * 1. **抖动必须有界** —— 红线 3 / 7：落空是安全的，猜中才是危险的，抖动不能把落点推出锚点；
 * 2. **必须真的抖** —— 退化成常量的抖动等于没抖（真机特征仍然是"永远同一个像素"）。
 */
class InputJitterTest {

    @Test
    fun offsetsStayWithinTheRadius() {
        val jitter = InputJitter(Random(1))

        repeat(500) {
            val dx = jitter.offset()
            val dy = jitter.offset()
            val r = InputJitter.DEFAULT_RADIUS_PX
            assertTrue("x 抖动越界：$dx", dx in -r..r)
            assertTrue("y 抖动越界：$dy", dy in -r..r)
        }
    }

    @Test
    fun zeroRadiusMeansNoJitter() {
        assertEquals(0, InputJitter(Random(1), radiusPx = 0).offset())
    }

    @Test
    fun itActuallyVaries() {
        val jitter = InputJitter(Random(7))
        val samples = (1..50).map { jitter.offset() }

        assertTrue("抖动不该退化成一个常量：${samples.toSet()}", samples.toSet().size > 1)
    }

    @Test
    fun pressAndTravelStayInHumanRanges() {
        val jitter = InputJitter(Random(2))

        repeat(200) {
            val press = jitter.pressDurationMs()
            assertTrue(
                "按压时长应在真人短按区间：$press",
                press in InputJitter.PRESS_MIN_MS..InputJitter.PRESS_MAX_MS,
            )
            val travel = jitter.travelPx()
            assertTrue(
                "行程长度应在区间内：$travel",
                travel in InputJitter.TRAVEL_MIN_PX..InputJitter.TRAVEL_MAX_PX,
            )
            val angle = jitter.travelAngleRadians()
            assertTrue("方向取值有界：$angle", angle >= 0.0 && angle < 2 * Math.PI)
        }
    }

    @Test
    fun sameSeedReproducesTheSameSequence() {
        // 可复现：单测 / 排障可以拿到完全一样的抖动序列（§5-4 的例外只限本模块，见 ADR-002 补记）
        val first = InputJitter(Random(42))
        val second = InputJitter(Random(42))

        repeat(20) { assertEquals(first.offset(), second.offset()) }
    }
}
