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
 * 手势起始位置的命中结果（T1-5l：锚点已移除，只剩两类）。
 */
sealed interface FrameHit {
    /** 命中选框内部：拖动 = 整体移动。 */
    object Inside : FrameHit

    /** 选框外（含选框边框上）：拖动 = 新建选框。 */
    object Outside : FrameHit
}

/**
 * 帧显示视图变换（像素单位，原点为视图左上角，T1-5d）：
 * 缩放系数与平移量只影响显示与手势坐标换算，不改变选框的比例坐标语义。
 */
data class ViewTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 帧标注编辑的纯运算（T1-5d）：选框编辑（命中 / 移动）与视图变换（缩放 / 平移 / 坐标反算）。
 *
 * T1-5l 起**不再有拉伸手柄**：选框大小由「拖出即定」决定，不满意就点左上角关闭钮重画
 * （真机反馈：锚点压住选区边缘、影响画面判读）。因此本对象不再包含 [resize] 与手柄几何。
 *
 * 全部以帧比例坐标表达，帧尺寸、视图像素尺寸与关闭钮热区由调用方换算传入；
 * 不含任何安卓依赖，可在 JVM 离线单测（与识别口径一致：无设备绑定常量）。
 */
object FrameEditMath {

    /** 视图缩放范围：1 = 适应视图（整帧可见）；上限 16 保证小标志放大到可逐像素对齐边缘的粒度（T1-5k 提高）。 */
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 16f

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
     * 命中测试（帧比例坐标）：**选框内 = 整体移动，选框外 = 新建**（T1-5l 口径）。
     * 关闭钮由 [hitCloseButton] 单独判定并在手势层优先处理，这里不产生其命中。
     */
    fun hitTest(rect: RatioRect?, x: Float, y: Float): FrameHit {
        if (rect == null) return FrameHit.Outside
        val inside = x > rect.left && y > rect.top && x < rect.right && y < rect.bottom
        return if (inside) FrameHit.Inside else FrameHit.Outside
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
     * scale clamp 到 [MIN_SCALE, MAX_SCALE]；偏移 clamp 使画面始终覆盖视图（放大后不露黑边）。
     */
    fun zoomBy(
        transform: ViewTransform,
        focusX: Float,
        focusY: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): ViewTransform {
        val scale = (transform.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = scale / transform.scale
        var ox = focusX - (focusX - transform.offsetX) * ratio + panX
        var oy = focusY - (focusY - transform.offsetY) * ratio + panY
        ox = ox.coerceIn(viewWidth * (1f - scale), 0f)
        oy = oy.coerceIn(viewHeight * (1f - scale), 0f)
        return ViewTransform(scale, ox, oy)
    }

    /** 视图坐标（像素）→ 帧比例坐标（0..1）；考虑当前缩放与平移，越界 clamp 到帧界。 */
    fun toRatio(
        x: Float,
        y: Float,
        transform: ViewTransform,
        viewWidth: Float,
        viewHeight: Float,
    ): FloatArray {
        if (viewWidth <= 0f || viewHeight <= 0f) return floatArrayOf(0f, 0f)
        val fx = ((x - transform.offsetX) / transform.scale).coerceIn(0f, viewWidth)
        val fy = ((y - transform.offsetY) / transform.scale).coerceIn(0f, viewHeight)
        return floatArrayOf(fx / viewWidth, fy / viewHeight)
    }
}
