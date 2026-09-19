package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 悬浮窗几何单测（M3-T3-4 / T3-7 / FR-07）：手柄贴边（窗口完全在屏内，可见条贴边）、
 * 菜单相对手柄的落位、跨分辨率还原。
 *
 * 状态标签窗已取消（2026-09-17 用户口径）—— 标签相关测试随 `LabelPosition` / `FloatingPositions` 一起删除；
 * 拖动阈值判据（`FloatingGesture`）随"手柄不可拖动"一并删除（2026-09-19）—— 手柄只响应单击展开。
 *
 * 数值用抽象的"屏幕"尺寸代入，不绑定任何真实设备（红线 4）。
 */
class FloatingPositionTest {

    private val screenWidth = 1440
    private val screenHeight = 3168
    private val handleWidth = 40
    private val margin = 12

    @Test
    fun handleWindowIsFlushToEdgeSoTheWholeTouchAreaWorks() {
        val visualWidth = 7
        // 右贴边：窗口右缘 = 屏幕右缘（窗口**完全在屏内** → 透明触摸区全都点得到）
        assertEquals(
            screenWidth - handleWidth,
            FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth, visualWidth),
        )
        assertEquals(
            screenWidth,
            FloatingLayout.handleX(FloatingSide.RIGHT, handleWidth, screenWidth, visualWidth) + handleWidth,
        )
        // 左贴边：可见条贴左边（与右贴边对称）
        assertEquals(
            -(handleWidth - visualWidth),
            FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth, visualWidth),
        )
        assertEquals(
            visualWidth,
            FloatingLayout.handleX(FloatingSide.LEFT, handleWidth, screenWidth, visualWidth) + handleWidth,
        )
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
    }

    @Test
    fun menuSitsBesideVisibleBarAndStaysInsideScreen() {
        val menuWidth = 300
        val visualWidth = 7
        val gap = visualWidth + margin
        // 右贴边：菜单右缘 = 屏宽 -（手柄**可见条**宽 + 间距）→ 不会被手柄压住
        assertEquals(
            screenWidth - gap - menuWidth,
            FloatingLayout.menuX(FloatingSide.RIGHT, menuWidth, screenWidth, visualWidth, margin),
        )
        // 左贴边：对称
        assertEquals(
            gap,
            FloatingLayout.menuX(FloatingSide.LEFT, menuWidth, screenWidth, visualWidth, margin),
        )
        // 菜单比屏幕还宽 → 退化到边距，不产生越屏（菜单绝不能被裁）
        assertEquals(
            margin,
            FloatingLayout.menuX(FloatingSide.RIGHT, screenWidth + 100, screenWidth, visualWidth, margin),
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
    fun defaultHandlePositionIsRightEdgeAndUpperFifth() {
        // 手柄默认值：右侧边缘、纵向偏上（0.200000，2026-09-17 用户口径"适当往上调整"）。
        // 测试与常量**用同一个 DEFAULT_Y_RATIO**比值，免得以后调手柄位置时又把这条用例写死挂掉
        //（沿革：0.333333 → 0.200000，每次都是写死 1/3 被挂）。
        assertEquals(FloatingSide.RIGHT, FloatingPosition.DEFAULT.side)
        assertEquals(FloatingPosition.DEFAULT_Y_RATIO, FloatingPosition.DEFAULT.yRatio, 1e-6)
        assertEquals(
            screenHeight * FloatingPosition.DEFAULT_Y_RATIO,
            FloatingLayout.y(FloatingPosition.DEFAULT.yRatio, 0, screenHeight).toDouble(),
            0.5,
        )
    }
}