package com.example.mastermechanic.patrol

import com.example.mastermechanic.log.MmLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 「用户请求开始巡查」事件（FR-07 菜单出口；M3-T3-5）。
 *
 * 悬浮窗菜单的「开始巡查」只**把这件事广播出去并留痕**，自身不做任何动作——巡查主流程（FR-04）
 * 属 M4，由流程层订阅本信号后接管。因此 M3 全程**不产生任何游戏点击**：
 * 红线 2 的第三类来源是「用户经悬浮窗菜单发起的操作」，而这里只是"收到用户指令"这一步，
 * 真正的点击由 M4 的巡查步骤（第二类来源）产生。
 *
 * 回调发生在调用线程（悬浮窗主线程）；订阅方自行编组。
 */
object PatrolRequestSignal {

    const val TAG = "MM-Patrol"

    private val listeners = CopyOnWriteArraySet<(Long) -> Unit>()

    @Volatile
    private var count = 0

    /** 累计收到多少次用户请求（审计用；进程内计数）。 */
    val requestCount: Int get() = count

    fun addListener(listener: (Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Long) -> Unit) {
        listeners.remove(listener)
    }

    /** 记录一次用户请求，返回这是第几次。 */
    fun request(nowMs: Long): Int {
        count++
        MmLog.i(TAG, "用户请求开始巡查（第 $count 次）——本版本只发出请求，巡查主流程属 M4")
        listeners.forEach { it(nowMs) }
        return count
    }
}
