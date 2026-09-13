package com.example.mastermechanic.capture

/**
 * 帧节流器（纯逻辑，JVM 可测）：按最小间隔决定是否处理本帧。
 *
 * T1-2 以固定间隔维持帧管线（不消费全部帧，避免无谓开销）；
 * 识别循环接入后，本类同步承载 NFR-02 的自适应间隔调度。
 *
 * T1-13 起间隔语义为「**两轮之间的休息时间**」：处理结束时回填 [markProcessedEnd] 作为新基准，
 * 下一轮最早在「本轮结束 + intervalMs」放行。这样间隔与单轮耗时互不干扰——
 * 若基准停在「开始时刻」，当间隔小于单轮耗时（大窗口信号真机单轮 ≈270ms）时，
 * 判定会在处理期间就满足，退化为**连续满负荷**处理。
 */
class FrameThrottle(private var intervalMs: Long) {

    private var lastProcessedAt: Long? = null

    /**
     * 调整最小处理间隔（NFR-02 自适应：识别循环按「变化 / 稳定」切换两档）。
     * 仅影响后续判定，不改变已记录的处理时间戳。
     */
    fun setInterval(intervalMs: Long) {
        require(intervalMs > 0) { "处理间隔必须为正：$intervalMs" }
        this.intervalMs = intervalMs
    }

    /** 距上次处理**结束**达到间隔则放行并记录本次开始时间；首次调用一律放行。 */
    fun shouldProcess(nowMs: Long): Boolean {
        val last = lastProcessedAt
        if (last != null && nowMs - last < intervalMs) return false
        lastProcessedAt = nowMs
        return true
    }

    /**
     * 处理结束回填（T1-13）：把节流基准推进到「本轮结束时刻」。
     *
     * 语义：intervalMs 表示两轮之间的**休息时间**，不含处理耗时。例如间隔 200ms + 单轮 270ms
     * → 实际周期 ≈470ms、占用率 ≈57%；若基准仍是开始时刻，周期会退化为 270ms 且持续满负荷。
     */
    fun markProcessedEnd(nowMs: Long) {
        lastProcessedAt = nowMs
    }
}
