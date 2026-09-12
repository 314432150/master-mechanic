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
 * 选框可拉伸手柄：右上 / 左下 / 右下角 + 四边中点（图片工具交互，T1-5d）。
 * 左上角自 T1-5e 起为关闭按钮，不再作为拉伸手柄（见 [FrameEditMath.hitCloseButton]）。
 */
enum class FrameHandle { T, TR, L, R, BL, B, BR }

/** 手势起始位置的命中结果（T1-5d）。 */
sealed interface FrameHit {
    /** 命中手柄：拖动 = 拉伸手柄对应的边 / 角。 */
    data class Handle(val handle: FrameHandle) : FrameHit

    /** 命中框内：拖动 = 整体移动。 */
    object Inside : FrameHit

    /** 框外：拖动 = 新建选框。 */
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
 * 帧标注编辑的纯运算（T1-5d）：选框编辑（命中 / 移动 / 拉伸）与视图变换（缩放 / 平移 / 坐标反算）。
 *
 * 全部以帧比例坐标表达，帧尺寸、视图像素尺寸与手柄热区由调用方换算传入；
 * 不含任何安卓依赖，可在 JVM 离线单测（与识别口径一致：无设备绑定常量）。
 */
object FrameEditMath {

    /** 视图缩放范围：1 = 适应视图（整帧可见）；上限保证横屏内容区的小标志放大到可框选粒度。 */
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 8f

    /** 选框最小边长（帧比例）：约 7px @ 1440 宽帧，与模板提取下限（8px）同量级，防止拉伸翻转。 */
    const val MIN_SIZE = 0.005f

    /**
     * 命中测试（帧比例坐标）：手柄热区（rx / ry 为帧比例半径）优先于框内，框外为新建。
     * 角先于边（角热区与边热区重叠时角优先）；左上角为关闭按钮，由 [hitCloseButton]
     * 单独判定并在手势层优先处理，这里不再产生其手柄命中。
     */
    fun hitTest(rect: RatioRect?, x: Float, y: Float, rx: Float, ry: Float): FrameHit {
        if (rect == null) return FrameHit.Outside
        val midX = (rect.left + rect.right) / 2f
        val midY = (rect.top + rect.bottom) / 2f
        val handles = listOf(
            FrameHandle.TR to (rect.right to rect.top),
            FrameHandle.BL to (rect.left to rect.bottom),
            FrameHandle.BR to (rect.right to rect.bottom),
            FrameHandle.T to (midX to rect.top),
            FrameHandle.B to (midX to rect.bottom),
            FrameHandle.L to (rect.left to midY),
            FrameHandle.R to (rect.right to midY),
        )
        for ((handle, center) in handles) {
            if (abs(x - center.first) <= rx && abs(y - center.second) <= ry) {
                return FrameHit.Handle(handle)
            }
        }
        if (x in rect.left..rect.right && y in rect.top..rect.bottom) return FrameHit.Inside
        return FrameHit.Outside
    }

    /**
     * 左上角「关闭」按钮命中（帧比例坐标；rx / ry 为热区半径，与手柄同量级）。
     * 按钮位于选框左上角（原 TL 拉伸锚点位置）；命中优先于手柄与框内判定，
     * 由手势层在抬手（未超过滑动阈值）时清除当前选框。
     */
    fun hitCloseButton(rect: RatioRect?, x: Float, y: Float, rx: Float, ry: Float): Boolean {
        if (rect == null) return false
        return abs(x - rect.left) <= rx && abs(y - rect.top) <= ry
    }

    /** 整体移动：clamp 使框始终位于帧界内。 */
    fun move(rect: RatioRect, dx: Float, dy: Float): RatioRect {
        val ddx = dx.coerceIn(-rect.left, 1f - rect.right)
        val ddy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return RatioRect(rect.left + ddx, rect.top + ddy, rect.right + ddx, rect.bottom + ddy)
    }

    /** 拉伸：只移动手柄对应的边 / 角，对边固定；clamp 边界并使边长不小于 MIN_SIZE。 */
    fun resize(rect: RatioRect, handle: FrameHandle, dx: Float, dy: Float): RatioRect {
        var l = rect.left
        var t = rect.top
        var r = rect.right
        var b = rect.bottom
        if (handle == FrameHandle.L || handle == FrameHandle.BL) {
            l = (l + dx).coerceIn(0f, r - MIN_SIZE)
        }
        if (handle == FrameHandle.TR || handle == FrameHandle.R || handle == FrameHandle.BR) {
            r = (r + dx).coerceIn(l + MIN_SIZE, 1f)
        }
        if (handle == FrameHandle.T || handle == FrameHandle.TR) {
            t = (t + dy).coerceIn(0f, b - MIN_SIZE)
        }
        if (handle == FrameHandle.BL || handle == FrameHandle.B || handle == FrameHandle.BR) {
            b = (b + dy).coerceIn(t + MIN_SIZE, 1f)
        }
        return RatioRect(l, t, r, b)
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
