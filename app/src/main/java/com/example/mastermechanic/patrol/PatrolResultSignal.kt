package com.example.mastermechanic.patrol

/**
 * **刚跑完的那一次的结果快照**（2026-09-30 加，真机验收发现）。
 *
 * ## 为什么非要有它
 *
 * "跑完了"这件事**唯一的可见信号**是顶部标签（底部浮窗提示 2026-09-29 已按用户口径整体移除），
 * 可流程一到区间终点就 `PatrolSession.stop()` ⇒ `PatrolSession.current == null` ⇒ 标签判"待命"、
 * **整条摘掉**。真机实录（两次跑完都一样）：
 * ```
 * 20:36:43.660 状态标签: 「完成」202x84        ← 第 10 步（Step.DONE）的步骤名
 * 20:36:44.154 跑号: 已完成
 * 20:36:44.160 状态标签: 待命期没有要说的 ⇒ 摘掉   ← 6ms 后
 * ```
 * ⇒ 用户**从来没看到过「已完成」**（那 6ms 窗口里标签写的还是步骤名），而 `FloatingLabelText.isIdle`
 * 的注释里明明写着"「已完成」绝不能跟着待命一起被藏掉" —— 那条意图被"结束即清状态"绕过了。
 * （「已中止」不受影响：FAILED 时 `PatrolSession` 不清，真机确实常驻显示了。）
 *
 * ## 它解决什么
 *
 * 流程照常收掉（`PatrolSession` 的生命周期**一点不动**），只把**结果**留一份快照：[SHOW_MS] 之内
 * （且没有新的流程接替）界面把这份快照当成"当前这次执行"用 ⇒
 * 标签显示「已完成」、菜单进度块显示"第 10/10 步 · 完成 / 本次执行已完成"、读屏也终于播报得到。
 *
 * ## 口径（三条都别改，改了就会出现"两个来源打架"）
 *
 * 1. **[SHOW_MS] = 3 秒**（用户 2026-10-01 口径："30 秒太长了，3 秒就够了"）：跑完那一刻用户就在看屏幕，
 *    3 秒足够扫到标签；不无限停（一条陈旧的"已完成"长期占着标签就成了噪声）。
 * 2. **有新的流程开始** ⇒ 快照自动不显示（[displayState] 里"正在跑的"永远优先）⇒ 不需要谁去清它。
 * 3. **采集会话结束 / 重建**时由采集层 [clear]（与其它跨会话状态同一处置）。
 */
object PatrolResultSignal {

    /**
     * 快照保留多久（ms）：够看清，又不至于长期占着标签。
     *
     * ⚠ 历史：2026-09-30 定的 **30 秒**（那时怕"用户没抬头看就错过"）；2026-10-01 用户看了真机后拍板
     * **3 秒就够** —— 跑完那一刻人就在屏幕前。别再改回长窗口：那条"已完成"会与下一步操作抢注意力。
     */
    const val SHOW_MS = 3_000L

    @Volatile
    private var state: PatrolFlow.State? = null

    @Volatile
    private var showAtMs = 0L

    /** 记一次**跑完**的结果（只由采集层在"区间终点"那一处调用）。 */
    fun show(finished: PatrolFlow.State, nowMs: Long) {
        state = finished
        showAtMs = nowMs
    }

    /**
     * 现在该显示的那次执行：**正在跑的优先**，没有在跑时给刚跑完的快照（超出 [SHOW_MS] 给 null）。
     *
     * 界面只调这一个方法 —— 别自己拼"先看 `PatrolSession.current` 再看快照"：那种拼接迟早会在某处漂移。
     */
    fun displayState(running: PatrolFlow.State?, nowMs: Long): PatrolFlow.State? =
        running ?: recent(nowMs)

    /** 快照本身（超出 [SHOW_MS] ⇒ null）；给单测与"只想知道刚跑完没有"的调用方。 */
    fun recent(nowMs: Long): PatrolFlow.State? {
        val snapshot = state ?: return null
        if (nowMs - showAtMs > SHOW_MS) return null
        return snapshot
    }

    /** 采集会话结束 / 重建：结果**不跨会话**（否则新会话刚开始就挂着上一段的"已完成"）。 */
    fun clear() {
        state = null
        showAtMs = 0L
    }
}
