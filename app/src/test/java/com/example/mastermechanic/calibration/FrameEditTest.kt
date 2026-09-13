package com.example.mastermechanic.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 框编辑纯运算单测（T1-5d 起；T1-5l 起**移除全部锚点与拉伸**）：
 * 命中（框内移动 / 框外新建）、外置关闭钮、整体移动、视图变换与坐标反算。
 *
 * 全部以帧比例坐标验证，与视图尺寸、缩放、帧分辨率解耦（零设备绑定口径）。
 */
class FrameEditTest {

    private val rect = RatioRect(left = 0.3f, top = 0.4f, right = 0.6f, bottom = 0.7f)
    private val eps = 0.0001f

    /** 关闭钮外置边距（帧比例）：0.05 ≈ 72px @ 1440 宽帧。 */
    private val margin = 0.05f

    // --- hitTest：只有「框内移动」与「框外新建」两类（T1-5l） ---

    @Test
    fun hitTestOnNullSelectionIsOutside() {
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(null, 0.5f, 0.5f))
    }

    @Test
    fun hitTestCenterIsInside() {
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.55f))
    }

    @Test
    fun hitTestNearEdgesIsInside() {
        // 无锚点后，选框的边缘与角都归选框本体：拖动即整体移动
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.41f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.59f, 0.41f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.69f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.59f, 0.69f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.41f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.55f))
    }

    @Test
    fun hitTestOutsideRegionIsOutside() {
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.1f, 0.1f))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.65f, 0.55f))
    }

    @Test
    fun hitTestOnSelectionBorderIsOutside() {
        // 边界线不属于「框内」：贴边按下按新建处理（选框本体由框内一像素起算）
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, rect.left, 0.55f))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.45f, rect.top))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, rect.right, 0.55f))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.45f, rect.bottom))
    }

    // --- 外置关闭钮：中心几何与命中 ---

    @Test
    fun closeButtonCenterSitsOutsideSelectionCorner() {
        val center = FrameEditMath.closeButtonCenter(rect, margin, margin)
        assertEquals(0.25f, center.first, eps)
        assertEquals(0.35f, center.second, eps)
        val inside = center.first > rect.left && center.first < rect.right &&
            center.second > rect.top && center.second < rect.bottom
        assertFalse("关闭钮中心不得落在选区内", inside)
    }

    @Test
    fun closeButtonCenterClampsToFrameBounds() {
        // 选框贴帧界时无外扩空间：中心退化为该角本身（仍在选框之外或恰在角上）
        val flush = RatioRect(0.02f, 0.02f, 0.2f, 0.2f)
        val center = FrameEditMath.closeButtonCenter(flush, margin, margin)
        assertEquals(0f, center.first, eps)
        assertEquals(0f, center.second, eps)
    }

    @Test
    fun hitCloseButtonOnNullSelectionIsFalse() {
        assertFalse(FrameEditMath.hitCloseButton(null, 0.25f, 0.35f, margin, margin, 0.02f, 0.02f))
    }

    @Test
    fun hitCloseButtonHitsAtExternalCenter() {
        assertTrue(FrameEditMath.hitCloseButton(rect, 0.25f, 0.35f, margin, margin, 0.02f, 0.02f))
        assertTrue(FrameEditMath.hitCloseButton(rect, 0.26f, 0.36f, margin, margin, 0.02f, 0.02f))
    }

    @Test
    fun hitCloseButtonNeverFiresInsideSelection() {
        // 窄边距（0.01）+ 大热区（0.05）时热区会侵入选区：选区内必须一律不命中，
        // 否则想拖动选框内部（移动）会误清掉整条选框
        val tightMargin = 0.01f
        assertTrue(
            "前提：该点在关闭钮热区内",
            abs(0.305f - (rect.left - tightMargin)) <= 0.05f &&
                abs(0.405f - (rect.top - tightMargin)) <= 0.05f,
        )
        assertFalse(FrameEditMath.hitCloseButton(rect, 0.305f, 0.405f, tightMargin, tightMargin, 0.05f, 0.05f))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.305f, 0.405f))
    }

    @Test
    fun hitCloseButtonRequiresBothAxes() {
        // 热区为方形：仅某一轴进入范围不算命中
        assertFalse(FrameEditMath.hitCloseButton(rect, 0.25f, 0.45f, margin, margin, 0.02f, 0.02f))
        assertFalse(FrameEditMath.hitCloseButton(rect, 0.15f, 0.35f, margin, margin, 0.02f, 0.02f))
    }

    // --- 外层操作框（T1-5m 恢复，仅视觉） ---

    @Test
    fun outerFrameInflatesSelectionByMargin() {
        val outer = FrameEditMath.outerFrame(rect, margin, margin)
        assertEquals(0.25f, outer.left, eps)
        assertEquals(0.35f, outer.top, eps)
        assertEquals(0.65f, outer.right, eps)
        assertEquals(0.75f, outer.bottom, eps)
    }

    @Test
    fun outerFrameClampsToFrameBounds() {
        val flush = RatioRect(0.02f, 0.02f, 0.2f, 0.2f)
        val outer = FrameEditMath.outerFrame(flush, margin, margin)
        // 贴边侧无外扩空间 → 该侧与选框重合
        assertEquals(0f, outer.left, eps)
        assertEquals(0f, outer.top, eps)
        assertEquals(0.25f, outer.right, eps)
        assertEquals(0.25f, outer.bottom, eps)
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
    fun zoomAllowsPixelLevelAlignment() {
        // 小标志需放大到逐像素对齐边缘（T1-5k 提高上限，T1-5l 保留）
        assertEquals(16f, FrameEditMath.MAX_SCALE, eps)
        assertEquals(1f, FrameEditMath.MIN_SCALE, eps)
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
