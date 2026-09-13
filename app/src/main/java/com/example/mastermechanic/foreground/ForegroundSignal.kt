package com.example.mastermechanic.foreground

import com.example.mastermechanic.log.MmLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 前台状态信号：平台适配层对外的"目标应用是否在前台"独立信号（FR-09）。
 *
 * - 初始状态与任何不可用情形（服务未连接、查询失败、服务销毁）一律为 [ForegroundStatus.NOT_FOREGROUND]
 *   ——判定失败按非前台处理（ADR-005）；
 * - 状态每次**变化**输出一条日志（经 logcat 呈现），即 T0-3 验收证据本体；
 * - 订阅方（如悬浮窗可见性）通过 [addListener] 感知变化。
 */
object ForegroundSignal {

    const val TAG = "MM-Foreground"

    private val listeners = CopyOnWriteArraySet<(ForegroundStatus) -> Unit>()

    @Volatile
    private var current: ForegroundStatus = ForegroundStatus.NOT_FOREGROUND

    /** 当前状态；执行层下发点击前必须读取本信号（红线 1 运行时闸门）。 */
    val status: ForegroundStatus get() = current

    /** 便捷读取：是否前台。 */
    val isForeground: Boolean get() = current == ForegroundStatus.FOREGROUND

    fun addListener(listener: (ForegroundStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ForegroundStatus) -> Unit) {
        listeners.remove(listener)
    }

    /** 更新状态；状态未变化时不产生任何输出（日志与时序证据只记录"变化"）。 */
    fun update(next: ForegroundStatus, source: String) {
        if (next == current) return
        val previous = current
        current = next
        MmLog.i(TAG, "前台状态变化: $previous -> $next（来源: $source）")
        listeners.forEach { it(next) }
    }

    /** 服务不可用等场景：回到非前台（判定失败即非前台）。 */
    fun reset(reason: String) {
        update(ForegroundStatus.NOT_FOREGROUND, "重置（$reason）")
    }
}
