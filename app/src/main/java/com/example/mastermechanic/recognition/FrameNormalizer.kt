package com.example.mastermechanic.recognition

import kotlin.math.floor

/**
 * 帧方向归一（T1-11c，纯逻辑）：把运行帧灰度按 [CanvasMapping] 重采样到**标定画布几何**。
 *
 * 目的：让下游（模板 / 搜索窗口 / 参数 / 判定）保持不变——归一后判定输入始终处于标定几何，
 * 「判定语义不变」从而成立（T1-11a 结论：画面等比缩放居中、**无旋转**，故只需缩放 + 平移）。
 *
 * - 只填 [regions]（**标定坐标系**下的像素区域，即各信号窗口）覆盖的像素，其余为 0：
 *   判定只读取窗口内像素（T1-10b 同口径），窗口外置零与全帧换算在判定上等价；
 * - 画面区与标定一致时（[CanvasMapping.isIdentity]）直接返回原帧：零拷贝、零换算，
 *   常见竖画布会话的开销与改动前完全相同；
 * - 双线性采样（像素中心对齐、边界钳制），结果确定（§5-4）。
 */
object FrameNormalizer {

    fun normalize(frame: GrayImage, mapping: CanvasMapping, regions: List<PixelBounds>): GrayImage {
        if (mapping.isIdentity) return frame
        require(frame.width == mapping.frameWidth && frame.height == mapping.frameHeight) {
            "帧尺寸 ${frame.width}x${frame.height} 与换算所用 ${mapping.frameWidth}x${mapping.frameHeight} 不符"
        }
        val width = mapping.calibrationWidth
        val height = mapping.calibrationHeight
        val out = ByteArray(width * height)
        for (region in regions) {
            val x0 = region.x0.coerceIn(0, width)
            val y0 = region.y0.coerceIn(0, height)
            val x1 = region.x1.coerceIn(0, width)
            val y1 = region.y1.coerceIn(0, height)
            for (y in y0 until y1) {
                // 目标（标定坐标）像素中心 → 源（运行帧）采样坐标：
                // 像素中心约定（整数即像素索引），scale = 1 时采样点等于原坐标、恒等无损。
                val sourceY = mapping.toFrameY(y + 0.5) - 0.5
                val rowStart = y * width
                for (x in x0 until x1) {
                    val sourceX = mapping.toFrameX(x + 0.5) - 0.5
                    out[rowStart + x] = sample(frame, sourceX, sourceY).toByte()
                }
            }
        }
        return GrayImage(width, height, out)
    }

    /** 双线性采样；越界按边缘像素钳制（不引入额外的黑边偏差）。 */
    private fun sample(frame: GrayImage, x: Double, y: Double): Int {
        val xFloor = floor(x).toInt()
        val yFloor = floor(y).toInt()
        val fx = x - xFloor
        val fy = y - yFloor
        val x0 = xFloor.coerceIn(0, frame.width - 1)
        val y0 = yFloor.coerceIn(0, frame.height - 1)
        val x1 = (xFloor + 1).coerceIn(0, frame.width - 1)
        val y1 = (yFloor + 1).coerceIn(0, frame.height - 1)
        val p00 = frame.pixels[y0 * frame.width + x0].toInt() and 0xFF
        val p10 = frame.pixels[y0 * frame.width + x1].toInt() and 0xFF
        val p01 = frame.pixels[y1 * frame.width + x0].toInt() and 0xFF
        val p11 = frame.pixels[y1 * frame.width + x1].toInt() and 0xFF
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return floor(top + (bottom - top) * fy + 0.5).toInt().coerceIn(0, 255)
    }
}
