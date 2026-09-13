package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何与手势判据单测（M3-T3-4 / FR-07）：贴边露出比例、跨分辨率还原、吸附最近边缘、
 * 拖动阈值判据（不得用"位移是否非零"）。
 *
 * 数值用抽象的"屏幕"尺寸代入，不绑定任何真实设备（红线 4）。
 */
class FloatingPositionTest {

    private val screenWidth = 1440
    private val screenHeight = 3168
    private val handleWidth = 60
    private val labelWidth = 200
    private val gap = 10

    @Test
    fun handleHugsEdgeAndRevealsFortyPercent() {
        // 右侧：窗口左缘 = 屏宽 - 露出宽度
        val reveal = (handleWidth * FloatingPosition.REVEAL_RATIO).toInt()
        assertEquals(screenWidth - reveal, FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth))
        // 左侧：窗口右缘 = 露出宽度，其余移出屏幕
        assertEquals(-(handleWidth - reveal), FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth))
        // 露出宽度确实是 40%
        val leftX = FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth)
        assertEquals(reveal, leftX + handleWidth)
        val rightX = FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth)
        assertEquals(reveal, screenWidth - rightX)
    }

    @Test
    fun verticalPositionIsRatioBasedAndSurvivesResolutionChange() {
        // 同一比例在不同屏高下落在相近的相对位置（FR-07「始终可被找回」）
        assertEquals(1584, FloatingLayout.y(0.5, 40, 3168))
        assertEquals(720, FloatingLayout.y(0.5, 40, 1440))
    }

    @Test
    fun verticalPositionIsClampedInsideScreen() {
        assertEquals(0, FloatingLayout.y(0.0, 40, screenHeight))
        // 比例 1.0 会被夹到"贴着底边"而不是超出屏幕
        assertEquals(screenHeight - 40, FloatingLayout.y(1.0, 40, screenHeight))
    }

    @Test
    fun illegalRatioIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition(FloatingSide.LEFT, -0.01) }
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition(FloatingSide.LEFT, 1.01) }
    }

    @Test
    fun snapPicksNearestEdgeByWindowCenter() {
        // 中心落在左半屏 → 左；右半屏 → 右（含正好中线取左）
        assertEquals(FloatingSide.LEFT, FloatingPosition.snap(700f, 100, screenWidth, screenHeight).side)
        assertEquals(FloatingSide.LEFT, FloatingPosition.snap(720f, 100, screenWidth, screenHeight).side)
        assertEquals(FloatingSide.RIGHT, FloatingPosition.snap(730f, 100, screenWidth, screenHeight).side)
        assertEquals(FloatingSide.RIGHT, FloatingPosition.snap(1430f, 100, screenWidth, screenHeight).side)
    }

    @Test
    fun snapConvertsTopYToRatioAndClamps() {
        assertEquals(0.5, FloatingPosition.snap(2000f, 1584, screenWidth, screenHeight).yRatio, 1e-9)
        // 拖出屏幕（顶部为负 / 底部越界）→ 夹到 0..1
        assertEquals(0.0, FloatingPosition.snap(2000f, -50, screenWidth, screenHeight).yRatio, 1e-9)
        assertEquals(1.0, FloatingPosition.snap(2000f, screenHeight + 500, screenWidth, screenHeight).yRatio, 1e-9)
    }

    @Test
    fun snapRejectsInvalidScreenSize() {
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition.snap(10f, 10, 0, 100) }
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition.snap(10f, 10, 100, 0) }
    }

    @Test
    fun labelSitsInwardFromTheVisiblePartOnBothSides() {
        // 右侧：标签紧贴手柄可见部分（左缘）的左侧
        val rightHandleX = FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth)
        assertEquals(
            rightHandleX - gap - labelWidth,
            FloatingLayout.labelX(FloatingSide.RIGHT, rightHandleX, handleWidth, labelWidth, screenWidth, gap),
        )
        // 左侧：标签紧贴手柄可见部分（右缘）的右侧
        val leftHandleX = FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth)
        assertEquals(
            leftHandleX + handleWidth + gap,
            FloatingLayout.labelX(FloatingSide.LEFT, leftHandleX, handleWidth, labelWidth, screenWidth, gap),
        )
    }

    @Test
    fun labelIsClampedInsideScreenWhenItWouldNotFit() {
        // 窄屏 + 宽标签：clamp 在屏内（宁可压到手柄上，也不能把文字推出屏幕）
        val x = FloatingLayout.labelX(FloatingSide.LEFT, 0, 10, screenWidth + 100, screenWidth, gap)
        assertEquals(0, x)
        val right = FloatingLayout.labelX(FloatingSide.RIGHT, screenWidth, 10, screenWidth + 100, screenWidth, gap)
        assertEquals(0, right)
    }

    @Test
    fun labelGoesBelowWhenThereIsNoRoomAbove() {
        assertEquals(50, FloatingLayout.labelY(100, 40, 60, screenHeight, gap))
        // 贴顶部时改放下方
        assertEquals(80, FloatingLayout.labelY(10, 40, 60, screenHeight, gap))
    }

    @Test
    fun dragClampKeepsWindowInsideScreen() {
        assertEquals(0, FloatingLayout.clampInside(-500, 60, screenWidth))
        assertEquals(screenWidth - 60, FloatingLayout.clampInside(99999, 60, screenWidth))
        assertEquals(100, FloatingLayout.clampInside(100, 60, screenWidth))
        // 窗口比屏幕还大时退化为 0（不抛错、不产生负值）
        assertEquals(0, FloatingLayout.clampInside(50, screenWidth + 10, screenWidth))
    }

    @Test
    fun dragNeedsToExceedTouchSlop() {
        // 位移不足阈值 → 不是拖动（手指抖动不应让菜单永远展不开，FR-07 原文）
        assertFalse(FloatingGesture.isDrag(1f, 1f, 8f))
        assertFalse(FloatingGesture.isDrag(5f, 5f, 8f))
        // 达到阈值 → 拖动（对角线按欧氏距离判定）
        assertTrue(FloatingGesture.isDrag(8f, 0f, 8f))
        assertTrue(FloatingGesture.isDrag(3f, 4f, 5f))
        assertFalse(FloatingGesture.isDrag(3f, 4f, 6f))
        assertThrows(IllegalArgumentException::class.java) { FloatingGesture.isDrag(1f, 1f, -1f) }
    }

    @Test
    fun sideTokenRoundTrip() {
        assertEquals(FloatingSide.LEFT, FloatingSide.fromToken("left"))
        assertEquals(FloatingSide.RIGHT, FloatingSide.fromToken("right"))
        assertNull(FloatingSide.fromToken("top"))
    }
}
