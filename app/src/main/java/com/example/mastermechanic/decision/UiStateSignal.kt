package com.example.mastermechanic.decision

import com.example.mastermechanic.log.MmLog
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

    /**
     * **最近一轮命中的画面**（未经滞回，2026-09-22 真机需求）。
     *
     * 滞回要**连续 2 轮**命中才转移（§2.2）—— 这段时间里「画面已经变了」但 [status] 还停在旧结论。
     * 真机实录（`files/logs/mm-log-20260922.txt`）：
     *
     * ```
     * 02:24:16.368  待命期画面判定：当前「好友列表」｜本轮命中：农场   ← 农场已经命中了
     * 02:24:16.647  状态转移: 好友列表 -> 农场（连续 2 次命中（进入））   ← 0.3 秒后 status 才跟上
     * ```
     *
     * 而用户"关掉好友列表 → 立刻点菜单"就落在这个窗口里，被拦下
     * （真机报障原话："我是在农场点击的只拜访"）。
     *
     * 所以起点判定（`PatrolStartGate`）除 [status] 外**也看这一份**：
     * 最近一轮画面里只要出现能当起点的画面，就按它放行 —— 不必等滞回确认。
     */
    @Volatile
    private var latestHits: Set<UiState> = emptySet()

    /** 最近一轮命中的画面（未经滞回；空集 = 那一轮没命中任何画面 / 还没跑过）。 */
    val recentHits: Set<UiState> get() = latestHits

    /** 由采集侧每轮写入（仅帧线程调用）。 */
    fun noteHits(hits: Set<UiState>) {
        latestHits = hits
    }

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
        MmLog.i(TAG, "界面状态变化: ${previous.label} -> ${next.label}（$reason）")
        listeners.forEach { it(next) }
    }

    /** 采集会话终止等场景：识别结论停止更新，回到「未知」。 */
    fun reset(reason: String) {
        latestHits = emptySet()
        update(UiState.UNKNOWN, reason)
    }
}
