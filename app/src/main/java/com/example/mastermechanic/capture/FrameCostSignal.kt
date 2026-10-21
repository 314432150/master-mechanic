package com.example.mastermechanic.capture

/**
 * 单帧处理耗时的**只读读数**（M5-U6「诊断」页，2026-10-03 新增）。
 *
 * ## 为什么要有这个信号（而不是让界面去解析日志）
 *
 * 诊断页要显示"单帧处理耗时"，而这个数原先**只出现在日志里**（`MM-Capture` 的「单帧处理耗时统计」，
 * 每满 100 帧一行）。让界面去读日志文件有三个问题：读文件要权限、读到的是**某一刻的旧值**、
 * 还得自己再解析一遍字符串 ⇒ 极易与日志对不上。而 U6 的验收要求恰恰是
 * **"诊断页数值与 `MM-Capture` 日志口径一致"**。
 *
 * ⇒ 这里让采集侧在**写那条日志的同一个位置**顺手 publish 一次（[Reading] 就是日志里那几个数），
 * 界面只读不算 ⇒ 两处永远同源。
 *
 * ## 口径（与日志逐字一致）
 *
 * 一窗 = 连续 [FrameTimingStats.WINDOW_SIZE] 帧（100），**样本不跨会话**（会话结束即 [clear]）：
 * P95 用 nearest-rank，均值 / 最大值同一窗。阈值仍是 NFR-01 的 **20 毫秒**（判定在
 * `ui/diagnostics/DiagnosticsLogic`，本类不判定）。
 */
object FrameCostSignal {

    /** 一窗的读数（毫秒；字段名与日志里那行一致，便于逐项对照）。 */
    data class Reading(
        val sampleCount: Int,
        val totalP95Ms: Double,
        val grayP95Ms: Double,
        val detectP95Ms: Double,
        val avgMs: Double,
        val maxMs: Double,
    )

    /** 最近一窗；`null` = 还没攒够一窗、或会话已结束（**不拿旧值冒充现在**）。 */
    @Volatile
    var latest: Reading? = null
        private set

    /** 采集侧每写一条耗时日志时顺手调一次。 */
    fun update(reading: Reading) {
        latest = reading
    }

    /** 会话结束 / 重建时清空（与 `FrameTimingStats.reset()` 同一时机）。 */
    fun clear() {
        latest = null
    }
}
