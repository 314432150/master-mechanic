package com.example.mastermechanic.action

/**
 * **"FR-01 正在自动关弹窗"**（2026-09-28 加）：给界面（顶部状态标签）一个**只读**的"我在干什么"。
 *
 * ## 为什么需要它
 *
 * 用户报障原话（2026-09-28）："**自动关闭弹窗时没有显示提示信息**" —— 弹窗闭环跑在帧线程上
 * （`CaptureService.stepPopupClose`），而顶部标签只读 `PatrolSession` / `FriendListOcrSignal` 这些信号，
 * 对"正在关第几个弹窗"一无所知；用户看到的是弹窗一个个自己关掉，标签却写着别的（甚至 `待命`）。
 *
 * ## 与 `PatrolSession` / `FriendListOcrSignal` 同构
 *
 * 只做"写一个数 + 读一个数"：帧线程写（[update]），悬浮窗每 500ms 那条巡检读（`FloatingWindow.refreshLabel`）。
 * 不持有任何帧数据、不做判定 —— 判定仍在 `PopupCloseController`（纯逻辑）里。
 *
 * ⚠ **必须能被清掉**：帧线程可能在任何一刻消失（会话结束 / 目标退到后台），所以会话重建与终止那两处都要
 * [clear]，否则标签会永远停在"关闭弹窗中…"（那正是"静默地骗人"）。
 */
object PopupCloseSignal {

    /** 本段**已下发的点击次数**；0 = 当前没有弹窗闭环在进行（标签据此决定要不要写这一句）。 */
    @Volatile
    var attempts: Int = 0
        private set

    /** 有没有正在关的弹窗。 */
    val isClosing: Boolean get() = attempts > 0

    /**
     * **"想关但关不成"的原因**（空 = 没有受阻，2026-09-29 加）。
     *
     * ## 为什么必须区分"在关"和"关不成"（用户报障原话）
     *
     * "**显示了正在关弹窗，但实际上没关掉**" —— 真机实录：
     * ```
     * 06:57:43 FR-02 不动作: 开火前复眼：现取的最新画面上「tutorial_guide_e2」已经不在了 ⇒ 这一枪不发
     * 06:57:44 状态标签: 「正在关弹窗」      ← 标签亮了，可是**一条 `MM-Click 点击下发` 都没有** ✗
     * ```
     * 根子在 [attempts] 的口径：它是"**给出一次 Click 就计一次**"（见 `PopupCloseController` 的计数口径），
     * 而**真的发得出去**还要过开火前复眼与 `ClickGate` ⇒ 标签写"正在关"时，用户合理地以为"它点了" ✗。
     * ⇒ 现在**发了枪**才算"正在关"；被拦住就由本字段说明，标签改说「关弹窗受阻」。
     *
     * ⚠ 它**故意比 [attempts] 活得久**：段停手（`gaveUp`）之后弹窗还在，就该继续显示"受阻"，
     * 否则用户看到的是"标签没了、弹窗还在"，比"正在关弹窗"更费解。**弹窗消失 / 真的发了一枪**才清。
     */
    @Volatile
    var blockedReason: String = ""
        private set

    /**
     * **受阻的是哪一类**（2026-09-30 加，真机第 302 条）：`true` = **这一屏压根没标关闭控件**
     * （该去标定页框一条）；`false` = **标了但没认出来**（该重框紧一点 / 换张清晰的帧）。
     *
     * 为什么单独存一个标记而不是让界面去匹配 [blockedReason] 的文字：那两种情形的**处置完全相反**
     * （去标定 vs 重框），靠字符串匹配去猜是脆的 —— 文案一改就错位。判据在采集侧就已知（"标没标"是
     * 产物里的事实），就该**由它直接给出来**。
     */
    @Volatile
    var blockedNeedsCalibration: Boolean = false
        private set

    /**
     * **用户按下的"暂停自动关弹窗"**（2026-09-30 加）。
     *
     * 为什么要有它：有些界面本来就有"关闭 / 返回"按钮（**不是活动弹窗**），程序却可能把它当成弹窗去点
     * ⇒ 用户要能**随时**把这条自动化停掉、再随时恢复（识别照常，只是不动手）。
     *
     * ⚠ 这是**用户意图**，不是会话状态 ⇒ [clear]（会话重建 / 终止）**不清它**
     * （重开一次采集就偷偷恢复自动点击，是不是用户想要的 —— 不是）。
     */
    @Volatile
    var paused: Boolean = false

    /**
     * **本段"已停手"的原因**（空 = 没有停手，2026-09-30 加）。
     *
     * ## 为什么它必须单独存在（真机报障）
     *
     * 用户原话："**我已经完成换号操作，但是最后一次活动弹窗没有关掉**"。日志里程序其实**主动停手**了
     * （`FR-01 停手: 本轮画面整幅换掉了…`），可标签当时是这样走的：`正在关弹窗` → `待命` → **整条摘掉**
     * —— 因为停手后 [attempts] 被写 0、而"停手"这条路**不走** [markBlocked]（它发生在"真的发了一枪之后"，
     * 当时确实**发出去过**，不是"受阻"）。结果就是弹窗明明白白压在屏上，界面一个字的交代都没有。
     *
     * ## 为什么不复用 [blockedReason]
     *
     * 两者对用户的**处置相反**：受阻说的是"**想点但点不成**"（没标 / 没认出 ⇒ 去标定页补一条、或重框紧一点，
     * 程序还在等机会）；停手说的是"**我不再尝试了**"（画面整幅换掉 / 次数到顶 / 时长到顶 ⇒ 这个弹窗得**你自己关**）。
     * 混成一句会让标签指向错误的处置 —— 真机那次停手的原因与"认不出锚点"无关。
     *
     * ⚠ 生命周期与 [blockedReason] 同源：**段复位**（弹窗消失 / 换屏 ⇒ 控制器 `gaveUp` 变假）或**真的发出
     * 一枪**就作废（见 [update] / [clear]），否则会留下"永远已停手"的假象。
     */
    @Volatile
    var gaveUpReason: String = ""
        private set

    /** 帧线程每轮如实写一次（`attempts` = 0 表示本段已结束 / 已停手；`gaveUpReason` 空 = 没有停手）。 */
    fun update(attempts: Int, gaveUpReason: String = "") {
        this.attempts = attempts
        this.gaveUpReason = gaveUpReason
    }

    /** 记下"想关却没关成"的原因（标签据此改显示；同一原因由调用方负责去重记日志）。 */
    fun markBlocked(reason: String, needsCalibration: Boolean = false) {
        blockedReason = reason
        blockedNeedsCalibration = needsCalibration
    }

    /** 真的发出去了一枪（或弹窗已消失）⇒ 不再是"受阻"，也不再是"已停手"。 */
    fun clearBlocked() {
        blockedReason = ""
        blockedNeedsCalibration = false
        gaveUpReason = ""
    }

    /** 会话重建 / 终止时清空（不清就会留下"永远在关弹窗"的假象）。 */
    fun clear() {
        attempts = 0
        blockedReason = ""
        blockedNeedsCalibration = false
        gaveUpReason = ""
    }
}
