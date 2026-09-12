package com.example.mastermechanic.capture

import kotlin.math.roundToInt

/**
 * 单帧处理耗时统计器（纯逻辑，JVM 可测）：按连续 [windowSize] 帧为一窗汇总。
 *
 * T1-9 接入帧管线，承载 NFR-01 的测量口径（真机连续 100 帧统计 P95 < 20ms）：
 * 每满一窗输出一次 [TimingSummary] 并自动开始下一窗（样本不跨窗累积）。
 */
class FrameTimingStats(private val windowSize: Int = WINDOW_SIZE) {

    init {
        require(windowSize > 0) { "窗口大小必须为正：$windowSize" }
    }

    private val samples = DoubleArray(windowSize)
    private var count = 0

    /** 记录一帧处理耗时（毫秒）；满窗时返回汇总并重置，未满返回 null。 */
    fun record(costMs: Double): TimingSummary? {
        samples[count++] = costMs
        if (count < windowSize) return null
        val summary = summarize()
        count = 0
        return summary
    }

    /** 丢弃未满窗的样本（会话重建时调用，统计不跨会话续窗）。 */
    fun reset() {
        count = 0
    }

    private fun summarize(): TimingSummary {
        val sorted = samples.sortedArray()
        // nearest-rank：P95 = 升序第 ⌈0.95n⌉ 位（整数运算，避免浮点边界误差）
        val rank = (P95_PERCENT * windowSize + 99) / 100
        return TimingSummary(
            sampleCount = windowSize,
            p95Ms = sorted[rank - 1],
            avgMs = samples.average(),
            maxMs = sorted.last(),
        )
    }

    companion object {

        /** NFR-01 口径：连续 100 帧为一窗。 */
        const val WINDOW_SIZE = 100

        private const val P95_PERCENT = 95
    }
}

/** 一窗耗时汇总（毫秒）。 */
data class TimingSummary(
    val sampleCount: Int,
    val p95Ms: Double,
    val avgMs: Double,
    val maxMs: Double,
) {
    /** 供日志展示的取一位小数口径（仅格式化，不影响判定）。 */
    val p95DisplayMs: Double get() = (p95Ms * 10).roundToInt() / 10.0
    val avgDisplayMs: Double get() = (avgMs * 10).roundToInt() / 10.0
    val maxDisplayMs: Double get() = (maxMs * 10).roundToInt() / 10.0
}
