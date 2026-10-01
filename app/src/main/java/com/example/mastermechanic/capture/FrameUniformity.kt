package com.example.mastermechanic.capture

import kotlin.math.sqrt

/**
 * 单帧**内容自检**（2026-09-23）：判断这一帧是不是"近乎全平"。
 *
 * ## 为什么需要它
 *
 * 帧流看门狗（`CaptureService` 的转屏检测）只看**有没有新帧**。而真机 2026-09-23 遇到的是另一种：
 * **帧一直在来，但内容是黑的**（转屏后镜像不再绘制内容）—— 识别因此恒「未知」、OCR 读到 0 行，
 * 而所有"帧流还活着吗"的证据都显示正常，只能靠试读报告或翻日志才发现。
 * 这一小段把那种情况也变成**可发现的**（判定与告警在 `CaptureService.noteFrameContent`）。
 *
 * ## 口径
 *
 * - **只采样、不算整帧**：每隔 [SAMPLE_STRIDE_BYTES] 字节取一点（1440×3168 的 RGBA 帧约 4.4k 个采样点），
 *   对"整帧是不是纯色"这个问题足够，开销可忽略（帧管线本身是几十~上百毫秒级）；
 * - **只取 G 通道**：步长取 4 的倍数、起点固定，保证每个采样点都落在同一通道上；亮度贡献最大，
 *   又不必做任何色彩转换；
 * - 阈值 [FLAT_STDDEV] 与试读报告的区域质检同口径（纯色区域连压缩噪声也就个位数，
 *   而**任何有文字**的画面都远高于它）。
 *
 * 纯逻辑（不碰安卓）⇒ JVM 可测：[com.example.mastermechanic.capture.FrameUniformityTest]。
 */
object FrameUniformity {

    /** 「近乎全平」的标准差阈值（灰度 0..255）。 */
    const val FLAT_STDDEV = 5.0

    /**
     * 采样步长（字节）= 4 × 1025。
     *
     * 两个约束缺一不可（**单测踩出来过**）：
     * 1. **必须是 4 的倍数**：采样点固定在 G 通道上（否则会把色彩差当成内容差）；
     * 2. **不能是 2 的幂**：取 4096 时，每个采样点跨过的像素数是 1024（偶数）⇒ 所有采样点的像素奇偶性
     *    完全一样，**恰好与步长同周期的纹理会被整片漏掉**（单测里那个逐像素明暗交替的画面就被误判成"全平"）。
     *    取 1025（奇数）之后，像素的奇偶性与模 4 相位都会轮换，细纹理也能被采到。
     */
    const val SAMPLE_STRIDE_BYTES = 4 * 1025

    /** 采样起点：RGBA 布局里的 G（见类注释的"只取 G 通道"）。 */
    const val GREEN_OFFSET = 1

    /** 采样结果；[flat] = 这一帧近乎全平（纯色）。 */
    data class Sample(val count: Int, val mean: Double, val stdDev: Double) {
        val flat: Boolean get() = count > 0 && stdDev < FLAT_STDDEV
    }

    /**
     * 采样 [pixels] 的前 [length] 个字节。
     *
     * @param length 有效字节数（可以小于数组长度）——批次末尾之外的行对齐填充一并采到也无害，
     *   它同样"要么有内容要么没有"，不影响"整帧是不是纯色"这个判断。
     */
    fun sample(pixels: ByteArray, length: Int = pixels.size): Sample {
        if (length <= 0) return Sample(0, 0.0, 0.0)
        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var i = GREEN_OFFSET
        while (i < length) {
            val value = (pixels[i].toInt() and 0xFF).toDouble()
            sum += value
            sumSq += value * value
            count++
            i += SAMPLE_STRIDE_BYTES
        }
        if (count == 0) return Sample(0, 0.0, 0.0)
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        return Sample(count, mean, sqrt(variance))
    }
}
