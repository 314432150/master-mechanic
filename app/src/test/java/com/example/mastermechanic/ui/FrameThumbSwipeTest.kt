package com.example.mastermechanic.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图上下滑删除的判据单测（[FrameThumbSwipe]）。
 *
 * 手势本身只能在真机上核；这里钉住的是"划多远 / 甩多快算数"这条算术，以及两处容易踩的点：
 * 1. **方向不限**（用户口径"上划或者下划"）—— 两个方向都必须能触发；
 * 2. **甩动不能只看速度** —— 抬手瞬间的速度本来就不稳，没有最小位移兜底就会误弹确认框。
 */
class FrameThumbSwipeTest {

    /** 真机密度量级（3x 屏）；乘出来是像素，与界面里传进来的单位一致。 */
    private val density = 2.75f

    private fun px(dp: Float): Float = dp * density

    @Test
    fun `下滑超过门槛要弹确认`() {
        assertTrue(FrameThumbSwipe.isDeleteSwipe(px(45f), 0f, density))
    }

    @Test
    fun `上滑超过门槛也要弹确认（方向不限）`() {
        assertTrue(FrameThumbSwipe.isDeleteSwipe(px(-45f), 0f, density))
    }

    @Test
    fun `差一点且没甩动不算`() {
        assertFalse(FrameThumbSwipe.isDeleteSwipe(px(30f), 0f, density))
        assertFalse(FrameThumbSwipe.isDeleteSwipe(px(-30f), px(-100f), density))
    }

    @Test
    fun `位移小但甩得够快也算`() {
        assertTrue(FrameThumbSwipe.isDeleteSwipe(px(20f), px(400f), density))
        assertTrue(FrameThumbSwipe.isDeleteSwipe(px(-20f), px(-400f), density))
    }

    @Test
    fun `甩得再快，几乎没位移也不算`() {
        // 手指按住不动、抬手时速度抖了一下 → 不能弹确认框
        assertFalse(FrameThumbSwipe.isDeleteSwipe(px(5f), px(900f), density))
    }

    @Test
    fun `刚过门槛就算（边界按位移本身判）`() {
        assertTrue(FrameThumbSwipe.isDeleteSwipe(FrameThumbSwipe.DELETE_DISTANCE_DP * density, 0f, density))
    }
}
