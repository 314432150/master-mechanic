package com.example.mastermechanic.recognition

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画面区（T1-11c）：帧内承载目标应用画面的像素矩形；画面之外是画布留白（均匀色带），不含目标内容。
 */
data class ContentArea(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
}

/**
 * 画布几何（T1-11c，纯逻辑）：标定画布与运行帧之间按「**画面区**」换算，而不是按整幅画布比例。
 *
 * 模型来自 T1-11a 实测（结论见 `docs/recognition/research/research-canvas-orientation.md`）：
 * 目标画面在画布内**等比缩放并居中**（contain + center），画面长宽比 = 标定画布的长边 / 短边；
 * 画面之外为留白。同一台设备的两个方向互为转置，因此标定画布里的画面是居中窄带、
 * 另一方向（转置尺寸）画布里铺满整幅——这正是旧实现「按整幅比例换算」失效的根因。
 *
 * 全部输入为**标定帧尺寸与运行帧尺寸**（分别来自标定产物与采集帧），不含任何设备分辨率 / 坐标常量
 * （红线 4）；算术为确定性口径（起点 floor、长度四舍五入），保证同输入同结果（§5-4）。
 */
class CanvasGeometry private constructor(
    val calibrationWidth: Int,
    val calibrationHeight: Int,
    /** 标定画布内的画面区（标定坐标）。 */
    val calibrationArea: ContentArea,
    private val pictureAspect: Double,
) {

    /** [frameWidth] × [frameHeight] 画布内的画面区。 */
    fun contentArea(frameWidth: Int, frameHeight: Int): ContentArea =
        containFit(frameWidth, frameHeight, pictureAspect)

    /** 运行帧尺寸对应的换算结果（标定几何 ↔ 该帧几何）。 */
    fun mappingFor(frameWidth: Int, frameHeight: Int): CanvasMapping {
        require(frameWidth > 0 && frameHeight > 0) { "运行帧尺寸必须为正：${frameWidth}x$frameHeight" }
        return CanvasMapping(
            calibrationWidth = calibrationWidth,
            calibrationHeight = calibrationHeight,
            calibrationArea = calibrationArea,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            frameArea = contentArea(frameWidth, frameHeight),
        )
    }

    companion object {

        /**
         * 采样外扩（像素）：源区域边界为双线性采样留出的余量
         * （最末目标像素的采样点最多越界 1 像素，外扩 2 像素足够）。
         */
        internal const val SOURCE_PADDING = 2

        /** 以标定帧尺寸建立几何：画面长宽比取标定画布的长边 / 短边。 */
        fun of(calibrationWidth: Int, calibrationHeight: Int): CanvasGeometry {
            require(calibrationWidth > 0 && calibrationHeight > 0) {
                "标定帧尺寸必须为正：${calibrationWidth}x$calibrationHeight"
            }
            val aspect = max(calibrationWidth, calibrationHeight).toDouble() /
                min(calibrationWidth, calibrationHeight)
            return CanvasGeometry(
                calibrationWidth = calibrationWidth,
                calibrationHeight = calibrationHeight,
                calibrationArea = containFit(calibrationWidth, calibrationHeight, aspect),
                pictureAspect = aspect,
            )
        }

        /** 等比缩放居中（contain + center）：起点 floor、长边四舍五入，确定性。 */
        private fun containFit(frameWidth: Int, frameHeight: Int, pictureAspect: Double): ContentArea {
            val scale = min(frameWidth / pictureAspect, frameHeight.toDouble())
            val width = (pictureAspect * scale).roundToInt().coerceIn(1, frameWidth)
            val height = scale.roundToInt().coerceIn(1, frameHeight)
            val x0 = floor((frameWidth - width) / 2.0).toInt().coerceIn(0, frameWidth - width)
            val y0 = floor((frameHeight - height) / 2.0).toInt().coerceIn(0, frameHeight - height)
            return ContentArea(x0, y0, x0 + width, y0 + height)
        }
    }
}

/**
 * 某个运行帧尺寸下的换算（T1-11c）：标定画布几何 ↔ 该帧几何，两者按画面区对齐。
 *
 * - 标定坐标 → 运行帧坐标：`frame = frameArea 左上 + (calib − calibrationArea 左上) × scale`
 *   ——运行帧画面更大时 `scale > 1`（如 3168×1440 帧相对 1440×3168 标定为 2.2）；
 * - 运行帧坐标 → 标定坐标：上式的逆。
 */
class CanvasMapping internal constructor(
    val calibrationWidth: Int,
    val calibrationHeight: Int,
    val calibrationArea: ContentArea,
    val frameWidth: Int,
    val frameHeight: Int,
    val frameArea: ContentArea,
) {

    /** 运行帧画面区宽 / 标定画面区宽。 */
    val scale: Double = frameArea.width.toDouble() / calibrationArea.width

    /** 画面区与标定一致 → 无需换算（零开销快路径：常见竖画布会话走此路径）。 */
    val isIdentity: Boolean = frameArea == calibrationArea

    /** 标定坐标 → 运行帧坐标（浮点）：画面在运行帧里更大时**乘** [scale]。 */
    fun toFrameX(calibrationX: Double): Double =
        frameArea.x0 + (calibrationX - calibrationArea.x0) * scale

    fun toFrameY(calibrationY: Double): Double =
        frameArea.y0 + (calibrationY - calibrationArea.y0) * scale

    /** 运行帧坐标 → 标定坐标（浮点）：除以 [scale]，归一化采样的反向一步。 */
    fun toCalibrationX(frameX: Double): Double =
        calibrationArea.x0 + (frameX - frameArea.x0) / scale

    fun toCalibrationY(frameY: Double): Double =
        calibrationArea.y0 + (frameY - frameArea.y0) / scale

    /**
     * 标定坐标下的像素区域 → 运行帧坐标下的像素区域（floor / ceil + 采样外扩，裁剪到帧内）。
     * 采集层据此只转换「识别真正会读到的源像素」（T1-10b 同口径）。
     */
    fun sourceBounds(bounds: PixelBounds, padding: Int = CanvasGeometry.SOURCE_PADDING): PixelBounds =
        PixelBounds(
            x0 = (floor(toFrameX(bounds.x0.toDouble())).toInt() - padding).coerceIn(0, frameWidth),
            y0 = (floor(toFrameY(bounds.y0.toDouble())).toInt() - padding).coerceIn(0, frameHeight),
            x1 = (ceilOf(toFrameX(bounds.x1.toDouble())) + padding).coerceIn(0, frameWidth),
            y1 = (ceilOf(toFrameY(bounds.y1.toDouble())) + padding).coerceIn(0, frameHeight),
        )

    private fun ceilOf(value: Double): Int = -floor(-value).toInt()
}
