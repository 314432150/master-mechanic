package com.example.mastermechanic.floating

import com.example.mastermechanic.log.MmLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 悬浮窗「当前动作」展示位（FR-07 展示口径；M3-T3-5）。
 *
 * FR-07 要求悬浮窗显示「当前状态与当前动作」（例：「状态：农场 / 拜访好友」）。状态来自识别
 * （`decision/UiStateSignal`），而**动作来自流程层**——巡查主流程（FR-04）属 M4。
 * 因此 M3 只提供这个展示位与更新通道，**不臆造动作内容**：没人写入就是 null，悬浮窗只显示状态。
 */
object FloatingActionSignal {

    const val TAG = "MM-Floating"

    private val listeners = CopyOnWriteArraySet<(String?) -> Unit>()

    @Volatile
    private var current: String? = null

    /** 当前动作（无人写入时为 null = 只显示状态）。 */
    val action: String? get() = current

    fun addListener(listener: (String?) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String?) -> Unit) {
        listeners.remove(listener)
    }

    /** 更新动作；未变化时不产生输出（只记录变化）。 */
    fun update(next: String?, reason: String) {
        if (next == current) return
        current = next
        MmLog.i(TAG, "悬浮窗动作变化: ${current ?: "（无）"}（$reason）")
        listeners.forEach { it(next) }
    }

    /** 流程结束 / 会话终止：动作清空。 */
    fun clear(reason: String) = update(null, reason)
}
