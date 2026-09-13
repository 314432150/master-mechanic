package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何与手势判据单测（M3-T3-4 / T3-7 / FR-07）：手柄贴边露出比例、状态标签中心点定位、
 * 跨分辨率还原、吸附最近边缘、拖动阈值判据（不得用"位移是否非零"）。
 *
 * 数值用抽象的"屏幕"尺寸代入，不绑定任何真实设备（红线 4）。
 */
class FloatingPositionTest {

    private val screenWidth = 1440
    private val screenHeight = 3168
    private val handleWidth = 40
    private val labelWidth = 200
    private val labelHeight = 100
    private val margin = 12

    @Test
    fun handleHugsEdgeAndRevealsHalf() {
        val reveal = (handleWidth * FloatingPosition.REVEAL_RATIO).toInt()
        // 右侧：窗口左缘 = 屏宽 - 露出宽度
        assertEquals(screenWidth - reveal, FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth))
        // 左侧：窗口右缘 = 露出宽度，其余移出屏幕
        assertEquals(-(handleWidth - reveal), FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth))
        // 露出宽度确实是 50%（扁半圆：一半在屏外）
        assertEquals(reveal, FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth) + handleWidth)
        assertEquals(reveal, screenWidth - FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth))
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
        assertEquals(screenHeight - 40, FloatingLayout.y(1.0, 40, screenHeight))
    }

    @Test
    fun illegalRatiosAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition(FloatingSide.LEFT, -0.01) }
        assertThrows(IllegalArgumentException::class.java) { FloatingPosition(FloatingSide.LEFT, 1.01) }
        assertThrows(IllegalArgumentException::class.java) { LabelPosition(-0.01, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { LabelPosition(0.5, 1.01) }
    }

    @Test
    fun invalidScreenSizeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatingLayout.snapLabelCenter(0, 0, 10, 10, 0, 100)
        }
    }

    @Test
    fun labelCenterRatioMapsToPixelOffset() {
        // 居中（0.5）→ 标签左缘 = 屏宽一半 - 标签一半
        assertEquals(
            screenWidth / 2 - labelWidth / 2,
            FloatingLayout.labelXByCenter(0.5, labelWidth, screenWidth, margin),
        )
        // 0.25 同理
        assertEquals(
            (screenWidth * 0.25).toInt() - labelWidth / 2,
            FloatingLayout.labelXByCenter(0.25, labelWidth, screenWidth, margin),
        )
    }

    @Test
    fun labelIsClampedInsideScreenWithMargin() {
        // 贴左 / 贴右都退到边距位置，绝不越出屏幕
        assertEquals(margin, FloatingLayout.labelXByCenter(0.0, labelWidth, screenWidth, margin))
        assertEquals(
            screenWidth - labelWidth - margin,
            FloatingLayout.labelXByCenter(1.0, labelWidth, screenWidth, margin),
        )
        // 标签比屏幕还宽 → 退化为边距（不产生负偏移 / 不抛错）
        assertEquals(margin, FloatingLayout.labelXByCenter(0.5, screenWidth + 10, screenWidth, margin))
    }

    @Test
    fun labelDefaultLandsBottomCenter() {
        // 默认（0.5, 1.0）→ 居中 + 贴着底边（留边距）
        val x = FloatingLayout.labelXByCenter(
            LabelPosition.DEFAULT.xRatio,
            labelWidth,
            screenWidth,
            margin,
        )
        val y = FloatingLayout.labelYByCenter(
            LabelPosition.DEFAULT.yRatio,
            labelHeight,
            screenHeight,
            margin,
        )
        assertEquals(screenWidth / 2 - labelWidth / 2, x)
        assertEquals(screenHeight - labelHeight - margin, y)
    }

    @Test
    fun labelDragRoundTripsThroughCenterRatios() {
        // 拖动到 (300, 500) → 折成中心比例 → 再还原回同一像素位置（跨分辨率可还原的前提）
        val position = FloatingLayout.snapLabelCenter(
            windowX = 300,
            windowY = 500,
            labelWidth = labelWidth,
            labelHeight = labelHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        assertEquals(
            300,
            FloatingLayout.labelXByCenter(position.xRatio, labelWidth, screenWidth, margin),
        )
        assertEquals(
            500,
            FloatingLayout.labelYByCenter(position.yRatio, labelHeight, screenHeight, margin),
        )
        // 换算比例落在 0..1
        assertTrue(position.xRatio in 0.0..1.0)
        assertTrue(position.yRatio in 0.0..1.0)
    }

    @Test
    fun dragClampKeepsWindowInsideScreen() {
        assertEquals(0, FloatingLayout.clampInside(-500, 60, screenWidth))
        assertEquals(screenWidth - 60, FloatingLayout.clampInside(99999, 60, screenWidth))
        assertEquals(100, FloatingLayout.clampInside(100, 60, screenWidth))
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
    fun revealWidthIsHalfOfHandle() {
        assertEquals(handleWidth / 2, FloatingLayout.revealWidth(handleWidth))
        // 19dp 手柄 → 露出 10dp（一半在屏外）
        assertEquals(10, FloatingLayout.revealWidth(19))
    }

    @Test
    fun menuSitsBesideHandleAndStaysInsideScreen() {
        val menuWidth = 300
        val gap = FloatingLayout.revealWidth(handleWidth) + margin
        // 右贴边：菜单右缘 = 屏宽 -（手柄露出宽度 + 间距）→ 不会被手柄压住
        assertEquals(
            screenWidth - gap - menuWidth,
            FloatingLayout.menuX(FloatingSide.RIGHT, menuWidth, screenWidth, handleWidth, margin),
        )
        // 左贴边：对称
        assertEquals(
            gap,
            FloatingLayout.menuX(FloatingSide.LEFT, menuWidth, screenWidth, handleWidth, margin),
        )
        // 菜单比屏幕还宽 → 退化到边距，不产生越屏（菜单绝不能被裁）
        assertEquals(
            margin,
            FloatingLayout.menuX(FloatingSide.RIGHT, screenWidth + 100, screenWidth, handleWidth, margin),
        )
    }

    @Test
    fun menuFollowsHandleVerticallyAndStaysInsideScreen() {
        val menuHeight = 200
        // 与手柄**中心**对齐
        assertEquals(1056 + 16 - 100, FloatingLayout.menuY(1056, 32, menuHeight, screenHeight, margin))
        // 手柄贴顶 / 贴底 → 菜单被夹在屏内
        assertEquals(margin, FloatingLayout.menuY(0, 32, menuHeight, screenHeight, margin))
        assertEquals(
            screenHeight - menuHeight - margin,
            FloatingLayout.menuY(screenHeight - 32, 32, menuHeight, screenHeight, margin),
        )
        // 菜单比屏幕还高 → 退化到边距
        assertEquals(margin, FloatingLayout.menuY(0, 32, screenHeight + 10, screenHeight, margin))
    }

    @Test
    fun sideTokenRoundTrip() {
        assertEquals(FloatingSide.LEFT, FloatingSide.fromToken("left"))
        assertEquals(FloatingSide.RIGHT, FloatingSide.fromToken("right"))
        assertNull(FloatingSide.fromToken("top"))
    }

    @Test
    fun defaultPositionsAreHandleRightAndLabelBottomCenter() {
        // 手柄：右侧边缘、纵向三分之一（2026-09-14 用户口径；手柄不可拖动，位置只由默认 / 重置决定）
        assertEquals(FloatingSide.RIGHT, FloatingPositions.DEFAULT.handle.side)
        // 6 位小数（写盘精度）→ 换算到像素仍是屏高的三分之一（3168 * 1/3 = 1056）
        assertEquals(1.0 / 3.0, FloatingPositions.DEFAULT.handle.yRatio, 1e-6)
        assertEquals(FloatingSide.RIGHT, FloatingPosition.DEFAULT.side)
        assertEquals(screenHeight / 3, FloatingLayout.y(FloatingPosition.DEFAULT.yRatio, 0, screenHeight))
        // 状态标签：底部居中
        assertEquals(LabelPosition.DEFAULT, FloatingPositions.DEFAULT.label)
        assertEquals(0.5, LabelPosition.DEFAULT.xRatio, 1e-9)
        assertEquals(1.0, LabelPosition.DEFAULT.yRatio, 1e-9)
    }
}
