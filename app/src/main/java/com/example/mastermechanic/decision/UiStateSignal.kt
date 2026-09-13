package com.example.mastermechanic.decision

import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 界面状态信号：决策层对外的「当前界面状态（识别结论）」独立信号（T1-7）。
 *
 * - 由采集侧在识别循环产生状态转移时更新（来源 = 转移判据）；仅实际变化时输出
 *   日志与通知（变化日志含判据，即「状态展示随识别更新」的证据本体）；
 * - 初始与任何不可用情形（未标定 / 会话未建立）一律 [UiState.UNKNOWN]——没有
 *   识别结论时不得展示任何具体状态；
 * - 采集会话终止时由采集侧重置：识别结论停止更新，不得残留旧状态误导展示；
 * - 订阅方（如悬浮窗）通过 [addListener] 感知变化；回调发生在更新线程（采集帧
 *   线程），UI 侧订阅者需自行编组到主线程。
 */
object UiStateSignal {

    const val TAG = "MM-UiState"

    private val listeners = CopyOnWriteArraySet<(UiState) -> Unit>()

    @Volatile
    private var current: UiState = UiState.UNKNOWN

    /** 当前界面状态（识别结论；初始 / 重置后为「未知」）。 */
    val status: UiState get() = current

    fun addListener(listener: (UiState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (UiState) -> Unit) {
        listeners.remove(listener)
    }

    /** 更新状态；状态未变化时不产生任何输出（日志与通知只记录「变化」）。 */
    fun update(next: UiState, reason: String) {
        if (next == current) return
        val previous = current
        current = next
        Log.i(TAG, "界面状态变化: ${previous.label} -> ${next.label}（$reason）")
        listeners.forEach { it(next) }
    }

    /** 采集会话终止等场景：识别结论停止更新，回到「未知」。 */
    fun reset(reason: String) {
        update(UiState.UNKNOWN, reason)
    }
}
