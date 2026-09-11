package com.example.mastermechanic.capture

/**
 * 帧节流器（纯逻辑，JVM 可测）：按最小间隔决定是否处理本帧。
 *
 * T1-2 以固定间隔维持帧管线（不消费全部帧，避免无谓开销）；
 * 识别循环接入后，本类同步承载 NFR-02 的自适应间隔调度。
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

    /** 距上次处理达到间隔则放行并记录本次时间；首次调用一律放行。 */
    fun shouldProcess(nowMs: Long): Boolean {
        val last = lastProcessedAt
        if (last != null && nowMs - last < intervalMs) return false
        lastProcessedAt = nowMs
        return true
    }
}
