package com.example.mastermechanic.decision

/**
 * **起点探测窗口**（2026-10-18 用户口径："待命期只要关掉弹窗，发起流程时才需要认出当前在什么场景"）。
 *
 * ## 它解决什么
 *
 * 3-B 把待命期的搜索集合从「遮挡屏 4 条 + 起点候选 4 条」收窄成**只有遮挡屏 4 条**
 * （见 [PatrolDrivenExpectedSignals]）。省下的正是那 4 条"能当起点的画面"，代价是
 * **待命期认不出当前场景**（状态恒「未知」）。
 *
 * 而"用户点菜单那一刻起点成不成立"必须先知道画面（[com.example.mastermechanic.patrol.PatrolFlow.firstStep]）
 * —— 2026-09-20 真机撞过这个死锁：不搜起点画面 ⇒ 状态恒「未知」⇒ **菜单点了永远启动不了**。
 *
 * ⇒ 所以：**用户去挑目标的这段时间里，把起点候选临时加回集合**，等挑完点"开始"时状态早已收敛。
 *
 * ## 为什么用"时间窗"而不是"菜单开合状态"
 *
 * 菜单开合只存在于 [com.example.mastermechanic.floating.FloatingWindow]（主线程 / UI 层），
 * 而这里要在**帧线程**被 [PatrolDrivenExpectedSignals.expected] 读。传一个跨线程的实时布尔会引出
 * "收起事件丢了 ⇒ 永久开着"的泄漏，而泄漏的代价是**性能优化完全失效**（每轮都白搜 4 条）。
 * ⇒ 用**截止时刻**：写方只负责"往后延 [WINDOW_MS]"，到期自动失效，与收起事件解耦、不会泄漏。
 *
 * ## 为什么不随菜单收起而关闭
 *
 * 收起就关 ⇒ 用户"展开看一眼 → 收起 → 过一会儿再点开始"会重新失忆、再等一轮。
 * 而留下的代价只是**菜单展开后的 [WINDOW_MS] 内**每轮多搜 4 条（≈+50~80ms/轮），
 * 远小于长期挂机时省下的 4 条。⇒ 宁可多开一会儿，不给用户添"再点一次菜单"的步骤。
 */
object StartProbeSignal {

    /**
     * 探测窗口时长。
     *
     * 取 60 秒：用户"展开菜单 → 翻区服列表 / 挑好友 → 点开始"通常几秒到几十秒；
     * **超时后**状态会退回「未知」，此时点"开始"会被 [com.example.mastermechanic.patrol.PatrolStartGate]
     * 拦下并说明——**再点一次菜单即可**（那次会重新开窗）。不做得更长（分钟级）是因为
     * 白搜的代价随时钟线性累积，而收益只到"用户点开始"那一刻为止。
     */
    const val WINDOW_MS = 60_000L

    /** 探测窗口的截止时刻（0 = 未开启）。 */
    @Volatile
    private var untilMs: Long = 0L

    /**
     * 现在的时刻（毫秒，单调）。
     *
     * 用 `System.nanoTime()` 而不是 `SystemClock.elapsedRealtime()`：这里要的只是
     * **时长**（"窗口开了多久"），不需要与设备开机时刻对齐；而前者是纯 JVM 可用的，
     * 单测能直接跑（`SystemClock` 在 JVM 单测里未 mock，会抛异常）。
     */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** 开启（或续期）探测窗口；由"菜单展开"那类用户动作调用。 */
    fun open(nowMs: Long = nowMs()) {
        untilMs = nowMs + WINDOW_MS
    }

    /** 立即关闭（流程已启动 / 用户明确不需要时）。 */
    fun close() {
        untilMs = 0L
    }

    /**
     * 仅当窗口开着时才关，并**如实报告是不是真关了**（2026-10-18 用户口径"流程收尾就直接关掉"）。
     *
     * 为什么要返回值而不是直接 [close]：调用方要打一行日志说明"窗口已关"。
     * 若它本来就是关着的还打"已关"，日志就会骗人 —— 而这条日志正是判断"流程收尾后成本有没有
     * 立刻回落"的唯一证据（真机 2026-10-18 靠它确认）。
     */
    fun closeIfOpen(): Boolean = closeIfOpenAt(nowMs())

    /**
     * 同 [closeIfOpen]，但由调用方指定"现在"（供单测钉住"**已过期**"那条分支：
     * 一个已经自然过期的窗口与一个从未开过的窗口，对日志而言都是"没得关"）。
     */
    fun closeIfOpenAt(nowMs: Long): Boolean {
        if (!isOpen(nowMs)) return false
        untilMs = 0L
        return true
    }

    /** 探测窗口是否仍然开着（**由帧线程每轮问一次**）。 */
    fun isOpen(nowMs: Long = nowMs()): Boolean = nowMs < untilMs

    /** 剩余毫秒数（供日志/诊断；已关闭时为 0）。 */
    fun remainingMs(nowMs: Long = nowMs()): Long =
        (untilMs - nowMs).coerceAtLeast(0L)
}