package com.example.mastermechanic.calibration

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 比例选区（相对帧尺寸 0..1）：与预览采样率、显示尺寸、视图缩放均无关，跨分辨率安全（T1-5b，T1-5d 起共享给框编辑运算）。
 */
data class RatioRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 换算为原图像素矩形 [x0, y0, x1, y1]；同一比例在任何路径下换算一致。 */
    fun toPixels(frameWidth: Int, frameHeight: Int): IntArray {
        var x0 = (left * frameWidth).roundToInt().coerceIn(0, frameWidth)
        var x1 = (right * frameWidth).roundToInt().coerceIn(0, frameWidth)
        var y0 = (top * frameHeight).roundToInt().coerceIn(0, frameHeight)
        var y1 = (bottom * frameHeight).roundToInt().coerceIn(0, frameHeight)
        if (x0 > x1) {
            val t = x0; x0 = x1; x1 = t
        }
        if (y0 > y1) {
            val t = y0; y0 = y1; y1 = t
        }
        return intArrayOf(x0, y0, x1, y1)
    }
}

/**
 * 手势起始位置的命中结果（T1-5l 移除锚点手柄；T2-3f 恢复「拖外框白线拉伸」但**仍不画任何手柄**）。
 */
sealed interface FrameHit {
    /** 命中选框内部：拖动 = 整体移动。 */
    object Inside : FrameHit

    /** 选框外较远处：拖动 = 新建选框。 */
    object Outside : FrameHit

    /**
     * 命中**外框拉伸带**（T2-3f）：拖动 = 按被命中的边调整选框。
     * 两条边同真 = 角（双轴同时调整）；只一条为真 = 边（单轴）。
     *
     * 用户口径：锚点（手柄）隐藏，但拖动外围白线必须保留拉伸 / 缩放——白线本身就是抓手。
     */
    data class Resize(
        val left: Boolean,
        val top: Boolean,
        val right: Boolean,
        val bottom: Boolean,
    ) : FrameHit
}

/**
 * 帧显示视图变换（像素单位，原点为**画面适配矩形**的左上角，T1-5d）：
 * 缩放系数与平移量只影响显示与手势坐标换算，不改变选框的比例坐标语义。
 *
 * 2026-09-23 起`offsetX/offsetY` 的含义由「相对视口」收紧为「相对 [FitRect] 原点」——
 * 视口从"按帧比例切出来的中央横带"提升为整屏之后，画面在视口里多了一段 letterbox 偏移
 * （竖屏机上框横屏帧，上下各留一大条黑边）。换算时务必带上 [FitRect.left] / [FitRect.top]，
 * 漏掉会让手指与框整体错位（竖屏下接近半屏）。
 */
data class ViewTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * **画面适配矩形**（2026-09-23）：1 倍时画面在视口里占的那块区域，原点 = 视口左上角。
 *
 * 为什么需要它：视口现在是**整屏**，而画面按帧宽高比内接于它（`ContentScale.Fit` 的几何），
 * 于是"画面在哪"与"视口在哪"不再是一回事。手势坐标、关闭钮热区、平移约束都要以它为基准。
 *
 * 竖屏手机（1080×2340）框横屏帧（3168×1440）时的典型值：`left=0, top≈924, width=1080, height≈491`
 * —— 就是用户说的"中央那条横带"。现在放大后可以把画面拖出这条带（只受视口裁切）。
 */
data class FitRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** 视口或帧尺寸退化（0 / 负）时视为空：各处换算都按"空"短路，不做无意义的除法。 */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * 帧标注编辑的纯运算（T1-5d）：选框编辑（命中 / 移动）与视图变换（缩放 / 平移 / 坐标反算）。
 *
 * T1-5l 起**不再有拉伸手柄**（真机反馈：锚点压住选区边缘、影响画面判读）；
 * T2-3f 按用户口径恢复**拉伸能力但不画手柄**：白线本身是抓手，[hitTest] 给出 [FrameHit.Resize] 八向区域，
 * [resize] 按命中的边调整选框。
 *
 * 全部以帧比例坐标表达，帧尺寸、视图像素尺寸与关闭钮热区由调用方换算传入；
 * 不含任何安卓依赖，可在 JVM 离线单测（与识别口径一致：无设备绑定常量）。
 */
object FrameEditMath {

    /** 视图缩放范围：1 = 适应视图（整帧可见）；上限 16 保证小标志放大到可逐像素对齐边缘的粒度（T1-5k 提高）。 */
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 16f

    /**
     * **适配态阈值**（2026-09-23）：`scale ≤ 1.01` 视为"就是适应大小"，此时平移**强制归零（画面居中）**。
     *
     * 为什么要有它：1 倍的语义是"整帧可见、居中、上下留黑边"（用户认这个视觉基线），
     * 放开平移之后必须有一条规则保证它不会被平移破坏；它同时兑现了"复位后一定居中"。
     * 阈值而不是 `== 1f`：捏合手势的浮点误差会让 scale 停在 1.0000001。
     */
    const val FIT_EPS = 1.01f

    /**
     * **每根轴上画面至少要有这么大比例留在视口里**（2026-09-23，设计评审口径）。
     *
     * 放开平移是为了"放大后能把画面拖到中央横带之外"，但**不能让画面被拖到完全看不见**
     * （用户会以为"把图拖丢了"）。25% 的依据：既能一眼看出画面还在那儿、又能把双指按上去抓回来，
     * 同时不足以让用户误判"画面没了"。实际下限还要与 [panMinVisiblePx] 取大者（保证在低倍率下
     * 不至于只剩一条抓不住的细缝）。
     */
    const val MIN_VISIBLE_FRACTION = 0.25f

    /**
     * 由视口尺寸与帧宽高比算出**画面适配矩形**（1 倍时画面占的区域，居中、留黑边）。
     *
     * 与 `ContentScale.Fit` 同一套几何：谁更"扁"就以谁为准，另一个方向居中留空。
     * 纯函数、无安卓依赖；视口铺满整屏之后，"画面在哪"必须由它算出来，不能靠猜。
     */
    fun fitRect(viewWidth: Float, viewHeight: Float, frameAspect: Float): FitRect {
        if (viewWidth <= 0f || viewHeight <= 0f || frameAspect <= 0f) return FitRect(0f, 0f, 0f, 0f)
        val wide = frameAspect >= viewWidth / viewHeight
        val width = if (wide) viewWidth else viewHeight * frameAspect
        val height = if (wide) viewWidth / frameAspect else viewHeight
        return FitRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }

    /**
     * 平移量的允许区间（画面局部像素）：把 [offsetX] / [offsetY] 夹到"每轴至少留
     * [MIN_VISIBLE_FRACTION]（且不少于 [minVisiblePx]）的可见量"之内。
     *
     * ## 为什么下限用"画面自身尺寸"而不是"视口的比例"
     *
     * 竖屏机上框横屏帧时，画面高（≈491）远小于视口高（≈2340）。若把条件写成
     * "至少覆盖视口高的 25%"，该条件**永不可满足** ⇒ `coerceIn(lo, hi)` 上下界倒置 ⇒
     * 画面被顶到奇怪位置（或直接抛异常）。以画面自身尺寸为基准，区间**恒非空**，
     * 语义还正好是我们想要的："小轴方向可以把画面从贴顶一直挪到贴底"。
     *
     * 最后那道 `coerceAtMost(min(画面, 视口))` 是给分屏 / 极矮视口准备的保险：
     * 视口比 [minVisiblePx] 还小时先把下限收住，否则区间照样会倒置。
     */
    fun clampOffset(
        offsetX: Float,
        offsetY: Float,
        fit: FitRect,
        viewWidth: Float,
        viewHeight: Float,
        scale: Float,
        minVisiblePx: Float = 0f,
    ): Pair<Float, Float> {
        // 适应态：强制居中（1 倍的视觉基线不可被平移破坏）
        if (scale <= FIT_EPS) return 0f to 0f
        val shownX = fit.width * scale
        val shownY = fit.height * scale
        val mx = panMinVisiblePx(shownX, viewWidth, minVisiblePx)
        val my = panMinVisiblePx(shownY, viewHeight, minVisiblePx)
        // 画面左边界（视口坐标）= fit.left + offsetX，允许落在 [mx, viewWidth - mx] 相关区间内
        val x = orderedClamp(offsetX, mx - shownX - fit.left, viewWidth - mx - fit.left)
        val y = orderedClamp(offsetY, my - shownY - fit.top, viewHeight - my - fit.top)
        return x to y
    }

    private fun panMinVisiblePx(shown: Float, viewport: Float, minVisiblePx: Float): Float =
        maxOf(MIN_VISIBLE_FRACTION * shown, minVisiblePx).coerceIn(0f, minOf(shown, viewport))

    /** 夹取并保证上下界不倒置（浮点退化时 `coerceIn(min > max)` 会抛异常，那是真机崩溃）。 */
    private fun orderedClamp(value: Float, a: Float, b: Float): Float =
        if (a <= b) value.coerceIn(a, b) else value.coerceIn(b, a)

    /**
     * 关闭钮中心（帧比例）：选框左上角外侧 [marginX] / [marginY]，clamp 在帧界内（T1-5k 外置，T1-5l 保留）。
     * 选框贴帧界时退化为该角本身——仍在选框之外或恰在角上，[hitCloseButton] 的「选区内不命中」判定不受影响。
     */
    fun closeButtonCenter(rect: RatioRect, marginX: Float, marginY: Float): Pair<Float, Float> =
        (rect.left - marginX).coerceAtLeast(0f) to (rect.top - marginY).coerceAtLeast(0f)

    /**
     * 外层操作框（T1-5k 引入，T1-5l 短暂移除后按 T1-5m 用户口径恢复**仅视觉**部分）：
     * 选框各方向外扩 [marginX] / [marginY] 并 clamp 在帧界内；只用于绘制白色细线，
     * 不参与命中测试（命中区仍只有「框内移动 / 框外新建 / 左上 ✕」三类）。
     */
    fun outerFrame(rect: RatioRect, marginX: Float, marginY: Float): RatioRect = RatioRect(
        left = (rect.left - marginX).coerceAtLeast(0f),
        top = (rect.top - marginY).coerceAtLeast(0f),
        right = (rect.right + marginX).coerceAtMost(1f),
        bottom = (rect.bottom + marginY).coerceAtMost(1f),
    )

    /**
     * 命中测试（帧比例坐标，T2-3f 三区）：
     *
     * 1. **外框拉伸带** → [FrameHit.Resize]：内边界 = 选框本身，外边界 = 外框线（[outerFrame]）再向外
     *    [marginX] / [marginY]。整条环宽 `2 × margin`，白线正好在环中间——所以「按在白线上或它两侧一指宽内」
     *    都能抓到（线上 ±[margin] 都算）。环**完全落在选框之外**（内边界即选框边），
     *    选框内部仍是整体移动，不会误拉伸。
     * 2. **选框内 = 整体移动**（[FrameHit.Inside]）。
     * 3. 其余 = 新建（[FrameHit.Outside]）。
     *
     * 贴内线（`x == rect.left` 等）也算带内：否则选框线上那一条像素会落到「新建」，贴边一按就毁掉整条选框。
     * 关闭钮由 [hitCloseButton] 单独判定并在手势层优先处理（左上角那一格归关闭钮），这里不产生其命中。
     */
    fun hitTest(
        rect: RatioRect?,
        x: Float,
        y: Float,
        marginX: Float,
        marginY: Float,
    ): FrameHit {
        if (rect == null) return FrameHit.Outside
        val outer = outerFrame(rect, marginX, marginY)
        val bandLeft = outer.left - marginX
        val bandRight = outer.right + marginX
        val bandTop = outer.top - marginY
        val bandBottom = outer.bottom + marginY
        val outsideRect = x <= rect.left || x >= rect.right || y <= rect.top || y >= rect.bottom
        val inBand =
            x >= bandLeft && x <= bandRight && y >= bandTop && y <= bandBottom
        if (outsideRect && inBand) {
            return FrameHit.Resize(
                left = x <= rect.left,
                top = y <= rect.top,
                right = x >= rect.right,
                bottom = y >= rect.bottom,
            )
        }
        val inside = x > rect.left && y > rect.top && x < rect.right && y < rect.bottom
        return if (inside) FrameHit.Inside else FrameHit.Outside
    }

    /**
     * 拖外框拉伸（T2-3f）：按 [hit] 命中的边（两条 = 角）应用位移。
     *
     * [dx] / [dy] 用「手势起点 → 当前」的**累计位移**（不是逐帧增量）：这样把框缩小到手指跑到框外时
     * 仍然继续跟随，不会中途卡住。结果同时 clamp 在帧界 [0,1] 与最小边长之内；
     * [minWidth] / [minHeight] 由调用方按帧尺寸换算（模板最小边长口径，避免拉出提取不了的框）。
     */
    fun resize(
        rect: RatioRect,
        hit: FrameHit.Resize,
        dx: Float,
        dy: Float,
        minWidth: Float,
        minHeight: Float,
    ): RatioRect {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        if (hit.left) left = (left + dx).coerceIn(0f, (right - minWidth).coerceAtLeast(0f))
        if (hit.right) right = (right + dx).coerceIn((left + minWidth).coerceAtMost(1f), 1f)
        if (hit.top) top = (top + dy).coerceIn(0f, (bottom - minHeight).coerceAtLeast(0f))
        if (hit.bottom) bottom = (bottom + dy).coerceIn((top + minHeight).coerceAtMost(1f), 1f)
        return RatioRect(left, top, right, bottom)
    }

    /**
     * 「关闭」按钮命中（帧比例坐标；rx / ry 为热区半径，屏幕视觉恒定由调用方按缩放补偿）。
     * 按钮位于**选框左上角外侧**；命中优先于移动 / 新建判定，由手势层在抬手（未超过滑动阈值）时清除当前选框。
     *
     * **选区内一律不算关闭钮命中**（T1-5k 起）：热区会侵入面积较小的选框，若不加此保护，
     * 想拖动选框内部（移动）会误清掉整条选框。
     */
    fun hitCloseButton(
        rect: RatioRect?,
        x: Float,
        y: Float,
        marginX: Float,
        marginY: Float,
        rx: Float,
        ry: Float,
    ): Boolean {
        if (rect == null) return false
        if (x > rect.left && y > rect.top && x < rect.right && y < rect.bottom) return false
        val center = closeButtonCenter(rect, marginX, marginY)
        return abs(x - center.first) <= rx && abs(y - center.second) <= ry
    }

    /** 整体移动：clamp 使框始终位于帧界内。 */
    fun move(rect: RatioRect, dx: Float, dy: Float): RatioRect {
        val ddx = dx.coerceIn(-rect.left, 1f - rect.right)
        val ddy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return RatioRect(rect.left + ddx, rect.top + ddy, rect.right + ddx, rect.bottom + ddy)
    }

    /**
     * 双指手势：以双指焦点为中心应用缩放与平移（焦点处的画面内容保持在屏幕原位）。
     *
     * - `scale` clamp 到 [MIN_SCALE, MAX_SCALE]；
     * - 偏移约束在 2026-09-23 **由「画面恰好铺满视口、不露黑边」放开为** [clampOffset] 的
     *   "每轴至少留 [MIN_VISIBLE_FRACTION] 可见" —— 这正是"放大后能把画面拖出中央横带"的来源；
     * - [focusX] / [focusY] 传**视口坐标**即可，适配矩形的原点偏移在这里统一扣掉，
     *   调用方不必自己减（漏减会让缩放焦点漂移，症状是"越放大越跑偏"）。
     *
     * @param fit 画面适配矩形（[fitRect]）
     * @param viewWidth / [viewHeight] 视口（整屏可用区）像素尺寸
     * @param minVisiblePx 每轴可见下限的像素兜底（调用方按 48dp 换算；纯逻辑层不认 dp）
     */
    fun zoomBy(
        transform: ViewTransform,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        fit: FitRect,
        viewWidth: Float,
        viewHeight: Float,
        minVisiblePx: Float = 0f,
    ): ViewTransform {
        val scale = (transform.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = scale / transform.scale
        // 焦点换算到画面局部坐标：视口坐标 − 适配矩形原点
        val fx = focusX - fit.left
        val fy = focusY - fit.top
        val ox = fx - (fx - transform.offsetX) * ratio + panX
        val oy = fy - (fy - transform.offsetY) * ratio + panY
        val (cx, cy) = clampOffset(ox, oy, fit, viewWidth, viewHeight, scale, minVisiblePx)
        return ViewTransform(scale, cx, cy)
    }

    /**
     * **视口坐标**（像素）→ 帧比例坐标（0..1）；考虑适配矩形偏移、当前缩放与平移，越界 clamp 到帧界。
     *
     * 只此一个入口（而不是"传画面局部坐标"的版本）：[FitRect] 里带着画面在视口里的位置，
     * 原点偏移在这里扣一次，调用方**没有机会漏掉** —— 漏掉的表现是手指与框整体错位（竖屏下近半屏）。
     */
    fun toRatio(x: Float, y: Float, transform: ViewTransform, fit: FitRect): FloatArray {
        if (fit.isEmpty) return floatArrayOf(0f, 0f)
        val fx = ((x - fit.left - transform.offsetX) / transform.scale).coerceIn(0f, fit.width)
        val fy = ((y - fit.top - transform.offsetY) / transform.scale).coerceIn(0f, fit.height)
        return floatArrayOf(fx / fit.width, fy / fit.height)
    }
}
