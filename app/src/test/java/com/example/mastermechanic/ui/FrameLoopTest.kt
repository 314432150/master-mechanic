package com.example.mastermechanic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧主图 / 帧条**首尾循环**的索引换算单测（[FrameLoop]）。
 *
 * 循环本身（滑页手感、帧条跟着走）只能在真机上核；这里钉住的是换算那条算术，以及三个容易踩的点：
 * 1. **最后一帧往后一页 = 第一帧、第一帧往前一页 = 最后一帧**（用户 2026-10-25 的原话）；
 * 2. **跨边界只走一步** —— 缩略图条不能从"最后一帧"倒着划过整条才回到第一帧；
 * 3. **1 帧 / 空池不循环**（同一张图无限重复没有意义，而且 `modulo 0` 会直接抛异常）。
 */
class FrameLoopTest {

    private val count = 99

    @Test
    fun `第一帧往前一页就是最后一帧`() {
        val first = FrameLoop.pageOf(0, count)
        assertEquals(count - 1, FrameLoop.indexOf(first - 1, count))
    }

    @Test
    fun `最后一帧往后一页就是第一帧`() {
        val last = FrameLoop.pageOf(count - 1, count)
        assertEquals(0, FrameLoop.indexOf(last + 1, count))
    }

    @Test
    fun `锚点页码就是第一帧`() {
        assertEquals(0, FrameLoop.indexOf(FrameLoop.ANCHOR, count))
        assertEquals(FrameLoop.ANCHOR, FrameLoop.pageOf(0, count))
    }

    @Test
    fun `跨边界时帧条只走一步（不倒着划过整条）`() {
        val last = FrameLoop.pageOf(count - 1, count)
        // 选中帧从最后一帧跳到第一帧：帧条应当**向前一格**，而不是往回退 count-1 格
        assertEquals(last + 1, FrameLoop.nearestPage(last, 0, count))

        val first = FrameLoop.pageOf(0, count)
        // 反向同理：第一帧跳回最后一帧 = 往回一格
        assertEquals(first - 1, FrameLoop.nearestPage(first, count - 1, count))
    }

    @Test
    fun `点远处缩略图时主图走最短的那条`() {
        val current = FrameLoop.pageOf(0, count)
        // 往前 3 格更近 ⇒ 向后再绕一整圈
        assertEquals(current + 3, FrameLoop.nearestPage(current, 3, count))
        // 往后 3 格更近（第 96 帧 = 第一帧往前 3 格）
        assertEquals(current - 3, FrameLoop.nearestPage(current, 96, count))
    }

    @Test
    fun `nearestPage 的结果一定落在目标帧上`() {
        val current = FrameLoop.pageOf(0, count)
        (0 until count).forEach { index ->
            val target = FrameLoop.nearestPage(current, index, count)
            assertEquals("第 $index 帧", index, FrameLoop.indexOf(target, count))
        }
    }

    @Test
    fun `两帧也循环`() {
        val second = FrameLoop.pageOf(1, 2)
        assertEquals(0, FrameLoop.indexOf(second + 1, 2))
        assertEquals(1, FrameLoop.indexOf(FrameLoop.pageOf(0, 2) - 1, 2))
        assertTrue(FrameLoop.virtualCount(2) > 2)
    }

    @Test
    fun `只有一帧时不循环（也不除零）`() {
        assertEquals(1, FrameLoop.virtualCount(1))
        assertEquals(0, FrameLoop.pageOf(0, 1))
        assertEquals(0, FrameLoop.indexOf(0, 1))
        // 万一真滑到了别的页（不该发生）：仍按唯一那一帧算，不崩
        assertEquals(0, FrameLoop.indexOf(FrameLoop.ANCHOR + 7, 1))
        assertEquals(0, FrameLoop.nearestPage(FrameLoop.ANCHOR, 0, 1))
    }

    @Test
    fun `空池不循环也不抛异常`() {
        assertEquals(0, FrameLoop.virtualCount(0))
        assertEquals(0, FrameLoop.indexOf(FrameLoop.ANCHOR + 7, 0))
    }
}
