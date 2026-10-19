package com.example.mastermechanic.floating

/**
 * **「重置悬浮窗提示位置」的跨界面请求**（2026-10-19，M5-U6 第三段）。
 *
 * ## 为什么不是一个直接的方法调用
 *
 * 「重置」要动的是**悬浮窗自己内存里那份位置**（`FloatingWindow.labelRatios`）与它此刻挂着的窗口；
 * 而入口在 **App 设置页**（另一个进程内界面，拿不到 `FloatingWindow` 实例）。两者之间只有信号，
 * 没有对象引用 —— 这与 `PatrolRequestSignal` / `PopupCloseSignal` 是同一套分层：**界面只发请求，执行方自己读**。
 *
 * 悬浮窗本来就有 500ms 一拍的状态巡检（挂载期间一直跑，收起态也跑）⇒ 在那里判一次“计数变了没有”，
 * 代价是一次 `@Volatile` 读取，比跨线程回调的时序坑便宜得多。
 *
 * ## 为什么是计数而不是布尔
 *
 * 连点两次要各重置一次；而重置完不会因为“值还是 true”又重置一遍（与 `MasterMechanicApp` 的
 * `accountsAddTick` 同一个理由）。
 *
 * ## 只清落盘不够
 *
 * [FloatingLabelStore.clear] 只让**下次挂载**回默认位置（顶部居中）。标签此刻若正挂着，
 * 用户会看到“点了没反应”⇒ 必须由悬浮窗顺手当场重新落位（`FloatingWindow.resetLabelPosition`）。
 */
object FloatingLabelResetSignal {

    /** 累计请求次数（只增不减；悬浮窗记住上次处理到哪一次）。 */
    @Volatile
    var requested: Int = 0
        private set

    fun request() {
        requested++
    }
}
