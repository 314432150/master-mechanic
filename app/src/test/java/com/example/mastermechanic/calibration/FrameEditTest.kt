package com.example.mastermechanic.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 框编辑纯运算单测（T1-5d 起，T1-5e 增关闭钮命中）：命中 / 移动 / 拉伸 / 视图变换 / 坐标反算。
 * 全部以帧比例坐标验证，与视图尺寸、缩放、帧分辨率解耦（零设备绑定口径）。
 */
class FrameEditTest {

    private val rect = RatioRect(left = 0.3f, top = 0.4f, right = 0.6f, bottom = 0.7f)
    private val eps = 0.0001f

    // --- hitTest：手柄热区优先于框内，框外为新建 ---

    @Test
    fun hitTestOnNullSelectionIsOutside() {
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(null, 0.5f, 0.5f, 0.02f, 0.02f))
    }

    @Test
    fun hitTestCenterIsInside() {
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.55f, 0.02f, 0.02f))
    }

    @Test
    fun hitTestCornersPreferHandle() {
        // 左上角自 T1-5e 起为关闭钮（无拉伸手柄），该位置按框内处理
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.41f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.TR), FrameEditMath.hitTest(rect, 0.59f, 0.41f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.BL), FrameEditMath.hitTest(rect, 0.31f, 0.69f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.BR), FrameEditMath.hitTest(rect, 0.59f, 0.69f, 0.02f, 0.02f))
    }

    // --- hitCloseButton：左上角关闭钮热区（T1-5e） ---

    @Test
    fun hitCloseButtonOnNullSelectionIsFalse() {
        assertFalse(FrameEditMath.hitCloseButton(null, 0.3f, 0.4f, 0.02f, 0.02f))
    }

    @Test
    fun hitCloseButtonHitsCornerAndNeighborhood() {
        assertTrue(FrameEditMath.hitCloseButton(rect, 0.3f, 0.4f, 0.02f, 0.02f))
        assertTrue(FrameEditMath.hitCloseButton(rect, 0.31f, 0.41f, 0.02f, 0.02f))
    }

    @Test
    fun hitCloseButtonOutsideHotZoneIsFalse() {
        // 热区为方形：仅某一轴进入范围不算命中
        assertFalse(FrameEditMath.hitCloseButton(rect, 0.35f, 0.45f, 0.02f, 0.02f))
        assertFalse(FrameEditMath.hitCloseButton(rect, 0.3f, 0.5f, 0.02f, 0.02f))
    }

    @Test
    fun hitTestEdgeMidpointsResolveToEdgeHandles() {
        assertEquals(FrameHit.Handle(FrameHandle.T), FrameEditMath.hitTest(rect, 0.45f, 0.41f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.B), FrameEditMath.hitTest(rect, 0.45f, 0.69f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.L), FrameEditMath.hitTest(rect, 0.31f, 0.55f, 0.02f, 0.02f))
        assertEquals(FrameHit.Handle(FrameHandle.R), FrameEditMath.hitTest(rect, 0.59f, 0.55f, 0.02f, 0.02f))
    }

    @Test
    fun hitTestOutsideRegion() {
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.1f, 0.1f, 0.02f, 0.02f))
        // 手柄热区之外的框内位置仍为 Inside
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.55f, 0.01f, 0.01f))
    }

    // --- move：整体平移并 clamp 在帧界内 ---

    @Test
    fun moveShiftsRect() {
        val moved = FrameEditMath.move(rect, 0.1f, -0.1f)
        assertEquals(0.4f, moved.left, eps)
        assertEquals(0.3f, moved.top, eps)
        assertEquals(0.7f, moved.right, eps)
        assertEquals(0.6f, moved.bottom, eps)
    }

    @Test
    fun moveClampsToFrameBounds() {
        val moved = FrameEditMath.move(rect, -1f, 1f)
        assertEquals(0f, moved.left, eps)
        assertEquals(1f, moved.bottom, eps)
        // 尺寸保持不变
        assertEquals(rect.right - rect.left, moved.right - moved.left, eps)
        assertEquals(rect.bottom - rect.top, moved.bottom - moved.top, eps)
    }

    // --- resize：只动被拖的边 / 角，对边固定 ---

    @Test
    fun resizeTopRightMovesOnlyTopRight() {
        val resized = FrameEditMath.resize(rect, FrameHandle.TR, 0.1f, -0.05f)
        assertEquals(rect.left, resized.left, eps)
        assertEquals(0.35f, resized.top, eps)
        assertEquals(0.7f, resized.right, eps)
        assertEquals(rect.bottom, resized.bottom, eps)
    }

    @Test
    fun resizeRightEdgeOnlyMovesRight() {
        val resized = FrameEditMath.resize(rect, FrameHandle.R, 0.1f, 0.2f)
        assertEquals(rect.left, resized.left, eps)
        assertEquals(rect.top, resized.top, eps)
        assertEquals(0.7f, resized.right, eps)
        assertEquals(rect.bottom, resized.bottom, eps)
    }

    @Test
    fun resizeEnforcesMinSize() {
        // BL 向 TR 方向猛拉：收敛到 MIN_SIZE，不翻转
        val resized = FrameEditMath.resize(rect, FrameHandle.BL, 1f, -1f)
        assertEquals(rect.right - FrameEditMath.MIN_SIZE, resized.left, eps)
        assertEquals(rect.top + FrameEditMath.MIN_SIZE, resized.bottom, eps)
    }

    @Test
    fun resizeClampsToUnitBounds() {
        val resized = FrameEditMath.resize(rect, FrameHandle.BR, 1f, 1f)
        assertEquals(1f, resized.right, eps)
        assertEquals(1f, resized.bottom, eps)
        val resized2 = FrameEditMath.resize(rect, FrameHandle.L, -1f, 0f)
        assertEquals(0f, resized2.left, eps)
    }

    // --- zoomBy：焦点保持、范围与偏移 clamp ---

    @Test
    fun zoomKeepsFocusStable() {
        // 1x → 2x，焦点 (100,100)，视图 200x400：焦点处画面内容缩放后仍在屏幕 100
        val t = FrameEditMath.zoomBy(ViewTransform(), 100f, 100f, 2f, 0f, 0f, 200f, 400f)
        assertEquals(2f, t.scale, eps)
        assertEquals(-100f, t.offsetX, eps)
        assertEquals(-100f, t.offsetY, eps)
    }

    @Test
    fun zoomClampsScaleRange() {
        val up = FrameEditMath.zoomBy(ViewTransform(scale = 6f), 0f, 0f, 10f, 0f, 0f, 200f, 400f)
        assertEquals(FrameEditMath.MAX_SCALE, up.scale, eps)
        val down = FrameEditMath.zoomBy(
            ViewTransform(scale = 2f, offsetX = -50f, offsetY = -50f),
            0f, 0f, 0.01f, 0f, 0f, 200f, 400f,
        )
        assertEquals(FrameEditMath.MIN_SCALE, down.scale, eps)
        // 缩回 1x 后偏移收敛到 0（不露黑边）
        assertEquals(0f, down.offsetX, eps)
        assertEquals(0f, down.offsetY, eps)
    }

    @Test
    fun zoomClampsOffsetToCoverView() {
        // 2x、视图 200x400：偏移允许范围 [-200,0] × [-400,0]
        val t = FrameEditMath.zoomBy(
            ViewTransform(scale = 2f, offsetX = -100f, offsetY = -200f),
            100f, 200f, 1f, 500f, 500f, 200f, 400f,
        )
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
        val t2 = FrameEditMath.zoomBy(
            ViewTransform(scale = 2f), 100f, 200f, 1f, -1000f, -1000f, 200f, 400f,
        )
        assertEquals(-200f, t2.offsetX, eps)
        assertEquals(-400f, t2.offsetY, eps)
    }

    // --- toRatio：视图坐标 → 帧比例（含缩放平移反算与越界 clamp） ---

    @Test
    fun toRatioIdentityTransform() {
        val r = FrameEditMath.toRatio(100f, 200f, ViewTransform(), 200f, 400f)
        assertEquals(0.5f, r[0], eps)
        assertEquals(0.5f, r[1], eps)
    }

    @Test
    fun toRatioAccountsForZoomAndPan() {
        // scale=2, offset=-100：视图 (100,100) → 帧坐标 (100+100)/2 = 100
        val r = FrameEditMath.toRatio(100f, 100f, ViewTransform(2f, -100f, -100f), 200f, 400f)
        assertEquals(0.5f, r[0], eps)
        assertEquals(0.25f, r[1], eps)
    }

    @Test
    fun toRatioClampsToFrame() {
        val r = FrameEditMath.toRatio(-50f, 9999f, ViewTransform(), 200f, 400f)
        assertEquals(0f, r[0], eps)
        assertEquals(1f, r[1], eps)
    }

    @Test
    fun toRatioDegenerateViewReturnsZero() {
        val r = FrameEditMath.toRatio(10f, 10f, ViewTransform(), 0f, 0f)
        assertEquals(0f, r[0], eps)
        assertEquals(0f, r[1], eps)
    }
}
