package com.example.mastermechanic.patrol

/**
 * 「这次执行现在什么情况」的**纯文案**（M4-T4-5）。
 *
 * 抽出来的理由与 [PatrolFlow] 一样：这是**判定**，不是绘制 —— "显示哪一行、那一行说什么、
 * 要不要给用户一个「继续」"都能在 JVM 里验（[PatrolStatusTest]），悬浮窗只管把结果画出来。
 *
 * 用户口径（FR-05 / FR-06）：**中止 / 暂停不静默** —— 必须说得出"停在第几步、为什么停"，
 * 以及"点了「继续」会从哪一步接着来"。
 *
 * 文案刻意用中文字面量（与 `PatrolRunner` 的 `note` 同源），不放 `strings.xml`：
 * 这两处的句子是**同一个概念的两种说法**，拆到两个地方就会各自演化成不一样的说法。
 */
object PatrolStatus {

    /**
     * 进度行：`第 3/10 步 · 退出登录`；**null = 没有在跑的流程**（菜单据此整块不显示）。
     *
     * 分母取 [PatrolFlow.lastStep]（**本次区间**的终点步，不是恒定的 10）：
     * 「只拜访」是从第 7 步到第 10 步，写成 `第 7/10 步` 会让用户以为还有三步要走；
     * 写成 `第 1/4 步`（区间内第几步）又会与日志里的步骤号对不上 —— 取舍是**与日志一致**
     * （排障时用户念一句、我们查一行），分母只用来交代"还有多远"。
     */
    fun progressLine(state: PatrolFlow.State?): String? {
        if (state == null) return null
        val total = PatrolFlow.lastStep(state.range).number
        return "第 ${state.step.number}/$total 步 · ${state.step.label}"
    }

    /**
     * 状态行：**需要用户知道 / 动手**的那一句；正常跑着时返回 [note]（"在等什么 / 刚点了什么"），
     * 没有在跑返回 null。
     *
     * 失败与暂停优先于 [note]：这两件事发生了却还在显示"正在等待好友列表"，等于骗人。
     */
    fun statusLine(state: PatrolFlow.State?, note: String?): String? = when {
        state == null -> null
        state.outcome == PatrolFlow.Outcome.FAILED ->
            "已中止：${state.reason ?: "原因未知"}"
        state.paused -> state.reason ?: "已暂停"
        state.outcome == PatrolFlow.Outcome.FINISHED -> "本次执行已完成"
        else -> note
    }

    /**
     * 要不要给用户一个「继续」（也是**手柄变色**的判据：收起态也要能看出"停在半路上了"）。
     *
     * 与 [PatrolSession.resume] 的准入条件**是同一件事**，所以由本方法单点判定：
     * 分成两处写，迟早会出现"菜单显示「继续」但点了没用"。
     */
    fun canResume(state: PatrolFlow.State?): Boolean =
        state != null &&
            (state.outcome == PatrolFlow.Outcome.FAILED || state.paused)

    /**
     * **要不要摆流程控制行**（2026-10-01 真机 bug 后单列，判据只此一处）。
     *
     * 只看**真在跑的那个流程**（调用方传 `PatrolSession.current`）：运行中 / 已中止 / 已暂停 都要摆
     * （「停止」或「继续 + 停止」），**已完成 / 从没跑过 一律不摆**。
     *
     * ## 为什么不能拿"界面正在显示的那份状态"来判（真机 bug 原文）
     *
     * 用户报："流程显示已完成，可一级菜单还显示「停止」，点它提示'没有进行中的流程'，
     * 直到「已完成」那个标签消失按钮才消失。"
     * 根因就是判据用错了源：跑完那一刻 `PatrolSession.current` 已经清空，但界面这一侧为了"能读到结果"
     * 还会用 [PatrolResultSignal] 的快照**继续显示 ≈30 秒的「已完成」** ⇒ 用 `progressLine(state) != null`
     * 那样的判据会把控制行画出来 ⇒ 里面只剩一个点了只会说"当前没有进行中的流程"的「停止」
     * （正是 2026-09-22 用户口径要避免的那件事："当前没有进行中的流程时，不该显示停止按钮"）。
     *
     * ⚠ 与 [progressLine] / [statusLine] 的分工：那两条要**显结果**（跑完的 30 秒里也得读得到），
     * 本方法只管**能不能动手**——"结果话"与"动手能力"是两件事，别共用一个判据（这次就是共用惹的祸）。
     *
     * `outcome == FINISHED` 也返回 false：调用方本该传"真在跑的那个"，但**万一它传了显示态**
     * （跑完的 30 秒里那份快照就是这个值），也不会再画出那个多余的控制行 —— 多一道兜底，代价为零。
     */
    fun showsControlRow(running: PatrolFlow.State?): Boolean =
        running != null && running.outcome != PatrolFlow.Outcome.FINISHED
}
