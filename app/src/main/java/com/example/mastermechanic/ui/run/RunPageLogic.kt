package com.example.mastermechanic.ui.run

import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationSummary
import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.patrol.PatrolStatus

/**
 * **「运行」页的判据**（M5-U2，2026-10-01）——**纯逻辑，可 JVM 单测**。
 *
 * ## 这个页面只回答三件事（用户口径：开 App 最先要知道"现在能不能跑 / 跑到哪了"）
 *
 * 1. **授权齐不齐**（缺哪几项去「授权与权限」补）；
 * 2. **画面在不在来**（采集会话在不在 / 这次授权有没有被投喂 / 停更多久）；
 * 3. **流程到哪一步、结局是什么**（进度行 + 状态行，**与日志同源**：[PatrolStatus]），
 *    以及**停止 / 继续**这两个动作能不能按。
 *
 * ## 为什么不在这里新造状态（U2 卡的原话）
 *
 * 三条读数全部来自现有信号：`AuthorizationChecks`（授权）、`FrameFreshness` + `CaptureSessionSignal`（画面）、
 * `PatrolSession` / `PatrolStatus` / `PatrolResultSignal`（流程）。**本页一个状态都不持有**
 * —— 界面只是"把现场读出来"，这样它不可能与悬浮窗标签说出两句不同的话。
 *
 * ## 与悬浮窗的关系
 *
 * 「停止 / 继续」走的是**同一条**路：[PatrolRequestSignal] ⇒ `PatrolRequestConsumer`
 * （与悬浮窗控制行一模一样的两个 `Kind`），所以日志、留痕、`canResume` 的准入条件全都不必再写一遍。
 */
object RunPageLogic {

    /**
     * 「画面」这一项现在属于哪种情形 —— **顺序即优先级（从最严重往下）**：
     *
     * ```
     * 采集会话不在  >  这次授权一帧都没被投喂  >  画面停更  >  正常
     * ```
     *
     * 为什么强调顺序（真机教训）：三种毛病的**处置不一样**，说错了用户就白折腾 ——
     * - **会话不在**（系统侧回收 / 用户停掉）⇒ 重建采集是唯一出路；
     * - **没被投喂**（`isStarved`）⇒ 多半要**换进程/重开 App**（同进程里重建常常救不回来）；
     * - **停更**（`isStale`）⇒ 先试着重新授权采集，或让游戏画面动一下。
     *
     * 所以哪怕"会话不在"同时伴随"停更"（它必然伴随），也**先说是会话不在** —— 说"停更 N 秒"
     * 会把用户引向"再等等看"，而这时等是没用的。
     */
    enum class FrameState {
        /** 采集会话不在：程序现在**看不见游戏**。 */
        SESSION_DOWN,

        /** 会话在，但这次授权**一帧都没被投喂**过（`FrameFreshness.isStarved`）。 */
        NEVER_FED,

        /** 画面停更（`FrameFreshness.isStale`）。 */
        STALE,

        /** 正常：有新帧在来。 */
        LIVE,
    }

    fun frameState(captureActive: Boolean, starved: Boolean, stale: Boolean): FrameState = when {
        !captureActive -> FrameState.SESSION_DOWN
        starved -> FrameState.NEVER_FED
        stale -> FrameState.STALE
        else -> FrameState.LIVE
    }

    /**
     * 本页**能做什么 / 要指路做什么**。
     *
     * @param stop 要不要给「停止」按钮。判据**直接借** [PatrolStatus.showsControlRow]
     *   ——"有没有流程可停"的唯一出处；在这里另写一遍迟早出现"App 显示能按、按了却说没有流程"。
     * @param resumeInGame 要不要显示**"回游戏点「继续」"那句提示**（**不是按钮**，见下）。
     *
     * ## 为什么「继续」在 App 里不做成按钮（用户 2026-10-01 真机反馈）
     *
     * 用户原话："**在 App 里出现继续按钮没有意义，因为游戏不在前台**"。
     * 对：跑号的每一步都要**游戏在前台**才成立（点击门禁 + 前置判定都以此为前提），
     * 而在 App 里按「继续」的那一刻，前台恰恰是**我们自己的 App** ⇒ 那一枪要么被门禁拦下，
     * 要么刚恢复就因"游戏不在前台"再暂停一次 —— 按钮等于在骗人。
     * 这跟"**发起只在悬浮窗**"（用户同日拍板）是同一条口径：**要游戏在前台才能做的动作，入口就留在游戏里**。
     *
     * ⇒ 本页只负责**说清现状并指路**：`已停在第 N 步：请回到游戏，点悬浮窗的「继续」`。
     * 「停止」不受影响：它不依赖前台（停一下在任何地方都成立），所以照旧做成按钮。
     *
     * @param running **真在跑的那个**流程（`PatrolSession.current`），不是界面显示态。
     */
    data class Actions(val stop: Boolean, val resumeInGame: Boolean)

    fun actions(running: PatrolFlow.State?): Actions = Actions(
        stop = PatrolStatus.showsControlRow(running),
        resumeInGame = PatrolStatus.canResume(running),
    )

    /** 四项授权是否全部就绪。 */
    fun authReady(statuses: List<AuthStatus>): Boolean = AuthorizationSummary.isAllReady(statuses)

    /** 还缺几项授权（0 = 全就绪）。 */
    fun missingAuthCount(statuses: List<AuthStatus>): Int =
        AuthorizationSummary.missingItems(statuses).size
}
