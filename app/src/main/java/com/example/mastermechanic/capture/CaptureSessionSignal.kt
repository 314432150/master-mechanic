package com.example.mastermechanic.capture

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 采集会话信号：平台适配层对外的「采集会话是否进行中」独立信号（ADR-001 / FR-09）。
 *
 * - 初始与任何不可用情形一律 [CaptureSessionStatus.INACTIVE]；
 * - 「会话终止」的唯一来源：系统侧 MediaProjection 回调或应用主动停止，两条路径均汇聚到 [update]；
 *   「用户切到别的应用」不改变本信号（FR-09 的会话区分）；
 * - 状态每次**变化**输出一条日志，即 T1-2 验收证据本体。
 */
object CaptureSessionSignal {

    const val TAG = "MM-Capture"

    private val listeners = CopyOnWriteArraySet<(CaptureSessionStatus) -> Unit>()

    @Volatile
    private var current: CaptureSessionStatus = CaptureSessionStatus.INACTIVE

    val status: CaptureSessionStatus get() = current

    val isActive: Boolean get() = current == CaptureSessionStatus.ACTIVE

    fun addListener(listener: (CaptureSessionStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (CaptureSessionStatus) -> Unit) {
        listeners.remove(listener)
    }

    /** 更新状态；状态未变化时不产生任何输出（日志与时序证据只记录"变化"）。 */
    fun update(next: CaptureSessionStatus, source: String) {
        if (next == current) return
        val previous = current
        current = next
        Log.i(TAG, "采集会话状态变化: $previous -> $next（来源: $source）")
        listeners.forEach { it(next) }
    }
}
