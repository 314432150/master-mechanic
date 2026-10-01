package com.example.mastermechanic.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 框编辑纯运算单测（T1-5d 起；T1-5l 移除手柄，T2-3f 恢复「拖外框白线拉伸」但**不画手柄**）：
 * 命中（框内移动 / 外框拉伸带 / 框外新建）、外框拉伸、外置关闭钮、整体移动、视图变换与坐标反算。
 *
 * 全部以帧比例坐标验证，与视图尺寸、缩放、帧分辨率解耦（零设备绑定口径）。
 */
class FrameEditTest {

    private val rect = RatioRect(left = 0.3f, top = 0.4f, right = 0.6f, bottom = 0.7f)
    private val eps = 0.0001f

    /** 外框外扩 / 关闭钮外置边距（帧比例）：0.05 ≈ 72px @ 1440 宽帧。拉伸带宽度同为 0.05（白线两侧各 0.05）。 */
    private val margin = 0.05f

    // --- hitTest：框内移动 / 外框拉伸带（T2-3f）/ 框外新建 ---

    @Test
    fun hitTestOnNullSelectionIsOutside() {
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(null, 0.5f, 0.5f, margin, margin))
    }

    @Test
    fun hitTestCenterIsInside() {
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.55f, margin, margin))
    }

    @Test
    fun hitTestNearEdgesIsInside() {
        // 拉伸带完全落在选框之外：选框内部的边缘与角照旧只归「整体移动」
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.41f, margin, margin))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.59f, 0.41f, margin, margin))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.69f, margin, margin))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.59f, 0.69f, margin, margin))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.45f, 0.41f, margin, margin))
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.31f, 0.55f, margin, margin))
    }

    @Test
    fun hitTestOnOuterLineResizesThatEdge() {
        // 外层白线在 0.25 / 0.65（外框线）：按在线上 = 拉那一条边；缩放的补偿由调用方换算，纯运算口径只看比例
        assertEquals(
            FrameHit.Resize(left = true, top = false, right = false, bottom = false),
            FrameEditMath.hitTest(rect, 0.25f, 0.55f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = false, right = true, bottom = false),
            FrameEditMath.hitTest(rect, 0.65f, 0.55f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = true, right = false, bottom = false),
            FrameEditMath.hitTest(rect, 0.45f, 0.35f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = false, right = false, bottom = true),
            FrameEditMath.hitTest(rect, 0.45f, 0.75f, margin, margin),
        )
    }

    @Test
    fun hitTestInsideGrabRingOnEitherSideOfLineResizes() {
        // 抓手带宽 = 线上 ±margin（环的外边界 = 外框线再外扩 margin）：线内侧 0.26 与线外侧 0.24 都要认
        assertEquals(
            FrameHit.Resize(left = true, top = false, right = false, bottom = false),
            FrameEditMath.hitTest(rect, 0.26f, 0.55f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = true, top = false, right = false, bottom = false),
            FrameEditMath.hitTest(rect, 0.20f, 0.55f, margin, margin),
        )
    }

    @Test
    fun hitTestOnSelectionBorderResizesInsteadOfCreatingNew() {
        // 贴内线也算拉伸带（T2-3f）：否则选框线上那一像素落到「新建」，贴边一按就毁掉整条选框
        assertEquals(
            FrameHit.Resize(left = true, top = false, right = false, bottom = false),
            FrameEditMath.hitTest(rect, rect.left, 0.55f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = true, right = false, bottom = false),
            FrameEditMath.hitTest(rect, 0.45f, rect.top, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = false, right = true, bottom = false),
            FrameEditMath.hitTest(rect, rect.right, 0.55f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = false, right = false, bottom = true),
            FrameEditMath.hitTest(rect, 0.45f, rect.bottom, margin, margin),
        )
    }

    @Test
    fun hitTestAtOuterCornerResizesBothAxes() {
        val topLeft = FrameEditMath.hitTest(rect, 0.25f, 0.35f, margin, margin)
        assertEquals(
            FrameHit.Resize(left = true, top = true, right = false, bottom = false),
            topLeft,
        )
        assertEquals(
            FrameHit.Resize(left = false, top = true, right = true, bottom = false),
            FrameEditMath.hitTest(rect, 0.65f, 0.35f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = true, top = false, right = false, bottom = true),
            FrameEditMath.hitTest(rect, 0.25f, 0.75f, margin, margin),
        )
        assertEquals(
            FrameHit.Resize(left = false, top = false, right = true, bottom = true),
            FrameEditMath.hitTest(rect, 0.65f, 0.75f, margin, margin),
        )
    }

    @Test
    fun hitTestOutsideGrabBandIsOutside() {
        // 环外才是「新建」：够远（>2×margin）才另起一条选框
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.1f, 0.1f, margin, margin))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.19f, 0.55f, margin, margin))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.75f, 0.55f, margin, margin))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.45f, 0.85f, margin, margin))
    }

    @Test
    fun hitTestOutsideVerticallyAlignedButOutOfBandIsOutside() {
        // 只对上了某一轴的坐标、但整体在环外 → 仍是新建（否则选区上下方一大片都会变成拉伸）
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.25f, 0.2f, margin, margin))
        assertEquals(FrameHit.Outside, FrameEditMath.hitTest(rect, 0.15f, 0.35f, margin, margin))
    }

    // --- 外框拉伸：按命中的边调整，clamp 在帧界与最小边长内 ---

    @Test
    fun resizeMovesOnlyHitEdges() {
        val right = FrameEditMath.resize(
            rect,
            FrameHit.Resize(left = false, top = false, right = true, bottom = false),
            dx = 0.1f,
            dy = 0.2f,
            minWidth = 0.01f,
            minHeight = 0.01f,
        )
        assertEquals(0.3f, right.left, eps)
        assertEquals(0.4f, right.top, eps)
        assertEquals(0.7f, right.right, eps)
        assertEquals(0.7f, right.bottom, eps)
    }

    @Test
    fun resizeCornerAdjustsBothAxes() {
        val grown = FrameEditMath.resize(
            rect,
            FrameHit.Resize(left = true, top = true, right = false, bottom = false),
            dx = -0.1f,
            dy = -0.1f,
            minWidth = 0.01f,
            minHeight = 0.01f,
        )
        assertEquals(0.2f, grown.left, eps)
        assertEquals(0.3f, grown.top, eps)
        assertEquals(0.6f, grown.right, eps)
        assertEquals(0.7f, grown.bottom, eps)
    }

    @Test
    fun resizeClampsToFrameBounds() {
        // 往帧外拖：左边界顶到 0 就停住，不会跑出帧（其余边不动）
        val pinned = FrameEditMath.resize(
            rect,
            FrameHit.Resize(left = true, top = false, right = false, bottom = false),
            dx = -0.9f,
            dy = 0f,
            minWidth = 0.01f,
            minHeight = 0.01f,
        )
        assertEquals(0f, pinned.left, eps)
        assertEquals(0.6f, pinned.right, eps)

        val pinnedRight = FrameEditMath.resize(
            rect,
            FrameHit.Resize(left = false, top = false, right = true, bottom = false),
            dx = 0.9f,
            dy = 0f,
            minWidth = 0.01f,
            minHeight = 0.01f,
        )
        assertEquals(1f, pinnedRight.right, eps)
    }

    @Test
    fun resizeKeepsMinimumEdgeLength() {
        // 往内拖：右边界最多缩到 left + minWidth（模板最小边长口径，拉出更小的框也提取不出模板）
        val squeezed = FrameEditMath.resize(
            rect,
            FrameHit.Resize(left = false, top = false, right = true, bottom = false),
            dx = -0.5f,
            dy = 0f,
            minWidth = 0.05f,
            minHeight = 0.05f,
        )
        assertEquals(0.3f, squeezed.left, eps)
        assertEquals(0.35f, squeezed.right, eps)
    }

    @Test
    fun resizeOnDegenerateSmallRectDoesNotThrow() {
        // 已有选框比最小边长还小时（旧产物 / 快速拖出的小框）：clamp 区间不得上下界倒置
        // （coerceIn(min > max) 会抛 IllegalArgumentException，那是真机崩溃）
        val tiny = RatioRect(0.5f, 0.5f, 0.501f, 0.501f)
        val grown = FrameEditMath.resize(
            tiny,
            FrameHit.Resize(left = true, top = true, right = false, bottom = false),
            dx = -0.02f,
            dy = -0.02f,
            minWidth = 0.05f,
            minHeight = 0.05f,
        )
        assertTrue("左边界必须落在帧内且不越过右边界：${grown.left}", grown.left >= 0f && grown.left < tiny.right)
        assertTrue("上边界必须落在帧内且不越过下边界：${grown.top}", grown.top >= 0f && grown.top < tiny.bottom)
        assertEquals(tiny.right, grown.right, eps)
        assertEquals(tiny.bottom, grown.bottom, eps)
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
        assertEquals(FrameHit.Inside, FrameEditMath.hitTest(rect, 0.305f, 0.405f, tightMargin, tightMargin))
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

    // --- fitRect：视口 → 画面适配矩形（2026-09-23 视口由"中央横带"提升为整屏） ---

    @Test
    fun fitRectLetterboxesALandscapeFrameInAPortraitViewport() {
        // 用户真机：竖屏视口（1080×2340）框横屏帧（3168×1440，比例 ≈2.2）
        // ⇒ 宽度铺满、上下留黑边 —— 这就是用户说的"中央那条横带"
        val fit = FrameEditMath.fitRect(1080f, 2340f, 3168f / 1440f)
        assertEquals(0f, fit.left, 0.01f)
        assertEquals(1080f, fit.width, 0.01f)
        assertEquals(1080f / (3168f / 1440f), fit.height, 0.05f)
        assertEquals((2340f - fit.height) / 2f, fit.top, 0.05f)
        assertEquals(fit.top, 2340f - fit.bottom, 0.05f)
    }

    @Test
    fun fitRectPillarboxesAPortraitFrameInALandscapeViewport() {
        // 反过来（横屏视口 + 竖屏帧）：高度铺满、左右留黑边
        val fit = FrameEditMath.fitRect(2340f, 1080f, 1440f / 3168f)
        assertEquals(0f, fit.top, 0.01f)
        assertEquals(1080f, fit.height, 0.01f)
        assertEquals((2340f - fit.width) / 2f, fit.left, 0.05f)
    }

    @Test
    fun fitRectDegenerateInputsAreEmpty() {
        // 视口 0 尺寸（首帧布局未回报）或帧比例非法 → 空矩形，各处换算短路，不做无意义的除法
        assertTrue(FrameEditMath.fitRect(0f, 100f, 2f).isEmpty)
        assertTrue(FrameEditMath.fitRect(100f, 0f, 2f).isEmpty)
        assertTrue(FrameEditMath.fitRect(100f, 100f, 0f).isEmpty)
    }

    // --- zoomBy：焦点保持、范围 clamp、平移放开（可拖出适配矩形） ---

    /** 竖屏视口（1080×2340）+ 横屏帧比例（2.2）：画面 = 中央一条横带。 */
    private val bandFit = FrameEditMath.fitRect(1080f, 2340f, 2.2f)
    private val bandViewWidth = 1080f
    private val bandViewHeight = 2340f

    /** 画面在某一轴上的可见长度（px）= 画面区间与视口区间的交集长度。 */
    private fun visibleOnAxis(start: Float, length: Float, viewport: Float): Float =
        (minOf(start + length, viewport) - maxOf(start, 0f)).coerceAtLeast(0f)

    @Test
    fun zoomAtFitScaleForcesCentering() {
        // 适应态（≤ FIT_EPS）平移一律归零：1 倍的视觉基线（居中 + 上下黑边）不可被平移破坏。
        // 顺带守住了"点『适应画面』之后一定回到居中"这条承诺。
        val t = FrameEditMath.zoomBy(
            ViewTransform(), 0f, 0f, 1f, 500f, 500f, bandFit, bandViewWidth, bandViewHeight, 48f,
        )
        assertEquals(1f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
    }

    @Test
    fun zoomKeepsFocusStable() {
        // 画面正好铺满视口（无黑边）时：2 倍、焦点 (100,100) 处的画面内容缩放后仍在视口 100
        val fit = FrameEditMath.fitRect(200f, 400f, 0.5f)
        val t = FrameEditMath.zoomBy(ViewTransform(), 100f, 100f, 2f, 0f, 0f, fit, 200f, 400f, 0f)
        assertEquals(2f, t.scale, eps)
        assertEquals(-100f, t.offsetX, eps)
        assertEquals(-100f, t.offsetY, eps)
    }

    @Test
    fun zoomFocusStaysPutEvenWithLetterboxOffset() {
        // 有黑边时焦点也必须稳住：视口 (540, 1200) 落在画面正中 ⇒ 2 倍后该点仍是画面正中
        val fit = FrameEditMath.fitRect(1080f, 2340f, 2.2f)
        val centerX = 540f
        val centerY = fit.top + fit.height / 2f
        val t = FrameEditMath.zoomBy(
            ViewTransform(), centerX, centerY, 2f, 0f, 0f, fit, 1080f, 2340f, 48f,
        )
        // 画面中心在视口坐标里必须没动
        val pictureCenterY = fit.top + t.offsetY + fit.height * t.scale / 2f
        assertEquals(centerY, pictureCenterY, 0.05f)
    }

    @Test
    fun zoomClampsScaleRange() {
        val up = FrameEditMath.zoomBy(
            ViewTransform(scale = 6f), 0f, 0f, 10f, 0f, 0f, bandFit, bandViewWidth, bandViewHeight, 48f,
        )
        assertEquals(FrameEditMath.MAX_SCALE, up.scale, eps)
        val down = FrameEditMath.zoomBy(
            ViewTransform(scale = 2f, offsetX = -50f, offsetY = -50f),
            0f, 0f, 0.01f, 0f, 0f, bandFit, bandViewWidth, bandViewHeight, 48f,
        )
        assertEquals(FrameEditMath.MIN_SCALE, down.scale, eps)
        // 缩回适应大小后偏移收敛到 0（画面重新居中）
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
    fun zoomCanDragThePictureOutOfTheFitRect() {
        // **本次改造的核心行为**：放大后画面可以拖出原来那块适配矩形（旧口径会把它夹回"恰好铺满"）
        val t = FrameEditMath.zoomBy(
            ViewTransform(scale = 4f), 540f, 1200f, 1f, 0f, -5000f,
            bandFit, bandViewWidth, bandViewHeight, 48f,
        )
        val pictureTop = bandFit.top + t.offsetY
        assertTrue("画面应能被拖到视口上方（顶边为负）：$pictureTop", pictureTop < 0f)
        assertTrue(
            "但至少还留 48px 可见：${visibleOnAxis(pictureTop, bandFit.height * t.scale, bandViewHeight)}",
            visibleOnAxis(pictureTop, bandFit.height * t.scale, bandViewHeight) >= 48f - 0.01f,
        )
    }

    @Test
    fun zoomNeverLetsThePictureLeaveTheViewport() {
        // 往任一方向狂拖，画面都不会被拖到完全看不见（用户最怕的"把图拖丢了"）
        val scale = 4f
        val shownHeight = bandFit.height * scale
        val shownWidth = bandFit.width * scale
        val expectedVisibleY = maxOf(FrameEditMath.MIN_VISIBLE_FRACTION * shownHeight, 48f)
        val expectedVisibleX = maxOf(FrameEditMath.MIN_VISIBLE_FRACTION * shownWidth, 48f)
        for (panY in listOf(-100_000f, 100_000f)) {
            val t = FrameEditMath.zoomBy(
                ViewTransform(scale = scale), 540f, 1200f, 1f, 0f, panY,
                bandFit, bandViewWidth, bandViewHeight, 48f,
            )
            val visible = visibleOnAxis(bandFit.top + t.offsetY, shownHeight, bandViewHeight)
            assertEquals("纵向至少留 $expectedVisibleY 可见", expectedVisibleY, visible, 0.05f)
        }
        for (panX in listOf(-100_000f, 100_000f)) {
            val t = FrameEditMath.zoomBy(
                ViewTransform(scale = scale), 540f, 1200f, 1f, panX, 0f,
                bandFit, bandViewWidth, bandViewHeight, 48f,
            )
            val visible = visibleOnAxis(bandFit.left + t.offsetX, shownWidth, bandViewWidth)
            assertEquals("横向至少留 $expectedVisibleX 可见", expectedVisibleX, visible, 0.05f)
        }
    }

    @Test
    fun clampOffsetNeverInvertsItsBounds() {
        // 画面比视口小得多（竖屏机上的横屏帧就是这个情形）：下限必须按**画面自身尺寸**算，
        // 否则"至少覆盖视口 25%"永不可满足 ⇒ coerceIn 上下界倒置 ⇒ 画面被顶到怪位置或直接抛异常
        val tinyInHugeViewport = FrameEditMath.fitRect(1080f, 2340f, 0.05f)
        val clamped = FrameEditMath.clampOffset(
            offsetX = -100_000f,
            offsetY = -100_000f,
            fit = tinyInHugeViewport,
            viewWidth = 1080f,
            viewHeight = 2340f,
            scale = 2f,
            minVisiblePx = 48f,
        )
        val shownHeight = tinyInHugeViewport.height * 2f
        val visible = visibleOnAxis(tinyInHugeViewport.top + clamped.second, shownHeight, 2340f)
        // 可见量 = 契约下限（画面自身尺寸的 25%），而不是"覆盖视口 25%"那种永不可满足的条件
        assertEquals(
            "可见量必须是契约下限（按画面自身尺寸算）",
            maxOf(FrameEditMath.MIN_VISIBLE_FRACTION * shownHeight, 48f),
            visible,
            0.05f,
        )

        // 极矮视口 + 大兜底值：区间不能倒置（倒置会抛 IllegalArgumentException，那是真机崩溃）
        val degenerate = FrameEditMath.clampOffset(
            offsetX = 0f, offsetY = 0f,
            fit = FitRect(0f, 0f, 10f, 20f),
            viewWidth = 1080f, viewHeight = 20f,
            scale = 2f, minVisiblePx = 48f,
        )
        assertEquals("区间倒置会抛异常；未抛且值落在界内", 0f, degenerate.first, eps)
        assertEquals(0f, degenerate.second, eps)
    }

    // --- toRatio：视口坐标 → 帧比例（含适配矩形偏移、缩放平移反算与越界 clamp） ---

    @Test
    fun toRatioIdentityTransform() {
        val fit = FrameEditMath.fitRect(200f, 400f, 0.5f)
        val r = FrameEditMath.toRatio(100f, 200f, ViewTransform(), fit)
        assertEquals(0.5f, r[0], eps)
        assertEquals(0.5f, r[1], eps)
    }

    @Test
    fun toRatioSubtractsTheLetterboxOffset() {
        // 有黑边时最容易错的一处：视口坐标必须**先减去画面原点**（漏减就是手指与框整体错位）
        val fit = FitRect(left = 0f, top = 100f, width = 200f, height = 400f)
        val r = FrameEditMath.toRatio(100f, 300f, ViewTransform(), fit)
        assertEquals(0.5f, r[0], eps)
        assertEquals(0.5f, r[1], eps)
        // 黑边里（画面之外）的点按帧界裁住，不会算出负比例
        val above = FrameEditMath.toRatio(100f, 10f, ViewTransform(), fit)
        assertEquals(0f, above[1], eps)
        val below = FrameEditMath.toRatio(100f, 600f, ViewTransform(), fit)
        assertEquals(1f, below[1], eps)
    }

    @Test
    fun toRatioAccountsForZoomAndPan() {
        // scale=2, offset=-100：视口 (100,100) → 画面局部 (100+100)/2 = 100
        val fit = FitRect(0f, 0f, 200f, 400f)
        val r = FrameEditMath.toRatio(100f, 100f, ViewTransform(2f, -100f, -100f), fit)
        assertEquals(0.5f, r[0], eps)
        assertEquals(0.25f, r[1], eps)
    }

    @Test
    fun toRatioClampsToFrame() {
        val fit = FrameEditMath.fitRect(200f, 400f, 0.5f)
        val r = FrameEditMath.toRatio(-50f, 9999f, ViewTransform(), fit)
        assertEquals(0f, r[0], eps)
        assertEquals(1f, r[1], eps)
    }

    @Test
    fun toRatioDegenerateViewReturnsZero() {
        val r = FrameEditMath.toRatio(10f, 10f, ViewTransform(), FitRect(0f, 0f, 0f, 0f))
        assertEquals(0f, r[0], eps)
        assertEquals(0f, r[1], eps)
    }
}
