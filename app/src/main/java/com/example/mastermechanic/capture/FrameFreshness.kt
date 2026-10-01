package com.example.mastermechanic.capture

import android.os.SystemClock

/**
 * **"采集画面有多旧"**（2026-09-30 加，真机第 301 条）：进程级共享 —— 采集层写、界面层读。
 *
 * ## 为什么非要有它
 *
 * 真机实录（2026-09-30 01:51~02:02）：镜像帧流断了 **11 分钟**
 * ```
 * 静止兜底心跳 … 距上一新帧 60s → 660s；目标在前台
 * ```
 * 程序当时仍在**重放几分钟前的旧画面** ⇒ 状态卡「未知」⇒ 菜单只说
 * "认不出当前画面（识别还没结论）：请先回到农场 / 大厅 / 启动页再点" ——
 * 用户读起来是"我站错地方了"，而真相是"**眼睛早就瞎了**"。两者处置完全相反（回大厅 vs 重建采集）。
 *
 * ⇒ **凡是用重放帧得出的结论，都必须把帧龄一并交给用户**（用法见 `FloatingWindow.sendStartRequest`）。
 *
 * ## ⚠ 但"停更"**不等于**"镜像失效"（2026-09-30 同日改写，L2）
 *
 * 上面那次排障时用的判据是 **"本游戏大厅有背景 CG 动画 ⇒ 画面一直在动 ⇒ 平台本该持续产帧"**，
 * 于是"停更 60 秒"被当成**故障证据**去结束会话。当天真机把这个前提推翻了：
 * ```
 * 目标在前台（农场）+ 画面静止 + 零新帧 60 秒    ← 平台对静止画面不产帧（静止兜底重放正是为此存在）
 * ```
 * ⇒ **停更是"现象"，不是"证据"**：它分不出「屏幕真的没变」与「镜像失效（画面在变、我们却收不到）」。
 * 现在这条信号只驱动**提示**（标签 / 菜单）与**停止点击**，**不再**用来结束会话
 * （见 `CaptureService.checkFrameStall`）。
 *
 * ⚠ 只有**新帧**才更新（静止兜底的重放**不算**）—— 与 `CaptureService` 里 `lastNewFrameAtMs` 同一口径。
 * ⚠ [ageMs] 返回 `null` = **从未收到过帧**（刚授完权，还没开始）⇒ 那是"没有基线"，**不是停更**
 * （2026-09-28 那次误杀就是把"还没开始"当成了"已失效"）。
 */
object FrameFreshness {

    /**
     * 超过这个时长没有新帧 ⇒ 判"画面停更"（与 `CaptureService.checkFrameStall` 同一阈值，单一来源）。
     *
     * ⚠ 它的后果很轻（**提示 + 停止点击**，不结束会话）：取值只要"用户不用干等"即可，
     * **不要**拿它当"镜像失效"的判决线（那件事需要事件证据，见类注释）。
     */
    const val STALE_MS = 60_000L

    /**
     * **建立会话后多久还没收到任何一帧 ⇒ 判"这次授权没被投喂"**（ms，2026-10-01 加，方案 2；用户拍板）。
     *
     * ## 为什么必须有这一档（它填的正是 [isStale] 管不到的那个洞）
     *
     * [isStale] 要求**有基线**（曾经收到过帧）—— 而真机卡住的那次（2026-10-01 01:16）是**从会话
     * 建立起就没被投喂**：平台推了一帧就停，此后 `onImageAvailable` 回调 0 次 ⇒ 停更看门狗
     * 一句不说（它没有基线），用户只能**懵等 60 秒**、而且跑号已经跑到一半才发现白跑。
     * ⇒ 这一档把"开场就零帧"单独判出来，**8 秒**就给结论（用户口径：把 60 秒的懵等缩成开场就知道）。
     *
     * ⚠ 判据只有一处（`CaptureService.checkFirstFeed`）：本对象只**存**结论（[noteStarved]）与
     * 提供阈值，界面读 [isStarved]。
     */
    const val FIRST_FEED_DEADLINE_MS = 8_000L

    /**
     * **"停更很久了"的那条线**（ms，2026-09-30 加，取 [STALE_MS] 的 2 倍 = 120 秒）。
     *
     * 为什么要在 [STALE_MS] 之外再划一条：刚过 60 秒时**"屏幕本来静止"与"镜像失效"还分不出来**
     * （见类注释），那时叫人去重新授权是**猜**；而到这条线时，采集侧的自动自救
     * （`CaptureService.checkSurfaceRetry`：60s / 90s 各换一次镜像表面）已经试过两轮 ⇒
     * **该让用户动手了**（用户 2026-09-30 口径："停更持续时，提示里给'能做的事'"）。
     * 界面据此把标签从"只说事实"升到"说处置"（见 `FloatingLabelText.FrameStale`）。
     */
    const val PROLONGED_MS = 2 * STALE_MS

    /**
     * **"切出切回之后"的停更线**（ms，2026-10-01 加，真机事故）——从 [STALE_MS] 的 60 秒**提前到 12 秒**。
     *
     * ## 它凭什么能提前（这正是"停更分不出两件事"的那个例外）
     *
     * [STALE_MS] 之所以定得宽（60 秒），是因为"没有新帧"分不出「屏幕真的没变」与「投喂断了」，
     * 只能等。但有一条**事件证据**能把它们分开（口径与 `CaptureService.noteDisplayChanged` 同源）：
     * **目标离开前台之前画面正在刷新**（`lastNewFrameAtMs` 很新）—— 屏幕在动 ⇒ 平台在产帧；
     * 而用户**回到前台**这件事本身会让画面重绘、游戏恢复动画 ⇒ 正常情况下**必然**马上有新帧。
     * 所以"回到前台后十几秒仍然零新帧"不再是"可能静止"，而是**投喂通路断了的强信号**。
     *
     * 真机事故（2026-10-01 05:27，用户报"切出切回游戏，又认不出画面了，我在农场"）：
     * ```
     * 05:27:10 与上一新帧 1s（画面在刷新）· 回调 361 次/10s      ← 离开前一窗还有 361 次回调
     * 05:27:11 平台停止投喂（此后回调恒 0 次/10s，目标在前台）   ← 切回游戏之后
     * 05:27:55 用户点菜单 ⇒ "认不出当前画面：请先回到农场…"     ← 他就在农场，被带向相反的处置
     * 05:28:09 停更 60 秒才第一次自救 / 05:28:40 两轮失败才说"请重新授权"  ← 白等 89 秒
     * ```
     * ⇒ 事件证据在手，这套处置（停点击 / 换镜像表面 / 提示重新授权）本该在 **12 秒**就发生。
     *
     * ⚠ 只在**离开前画面确实在刷新**时才给这条短口径（[noteForegroundReturn] 的入参），
     * 否则退回 [STALE_MS] —— 静止页面上切来切去本来就不产帧，提前判会把正常情形报成故障。
     */
    const val FOREGROUND_RETURN_STALL_MS = 12_000L

    @Volatile
    private var lastNewFrameAtMs = 0L

    /**
     * **目标离开前台之前，画面是不是在刷新**（采集层在"回前台"那一刻告知，见 [noteForegroundReturn]）。
     *
     * 它是 [FOREGROUND_RETURN_STALL_MS] 那条短口径的**唯一依据**：为 true ⇒ 停更线 12 秒，false ⇒ 60 秒。
     * 收到新帧（[note]）或换会话（[noteSessionStart] / [reset]）即作废 —— 证据只对"这一次切回"有效。
     */
    @Volatile
    private var flowingBeforeLeave = false

    /** "这次会话一帧都没被投喂"（判据只有一处：`CaptureService.checkFirstFeed`，见 [FIRST_FEED_DEADLINE_MS]）。 */
    @Volatile
    private var starved = false

    /**
     * 收到一帧**新帧**时调用（采集层）。
     *
     * ⚠ 顺手撤掉 [starved]：投喂一旦开始，"没被投喂"这个结论就不再成立（自愈，与"收到新帧 ⇒ 解除
     * 点击闸"同一口径）。不撤的话，8 秒判过之后平台又开始投喂时，标签会一直挂着"请退出重开"。
     */
    fun note() {
        lastNewFrameAtMs = SystemClock.elapsedRealtime()
        starved = false
        // 帧来了 ⇒ "切出切回"那条事件证据不再有用（停更线回到 60 秒那条常规口径）
        flowingBeforeLeave = false
    }

    /**
     * **目标回到前台了**（采集层判定 `frozen: true → false` 时调用），并带上事件证据：
     * [flowingBeforeLeave] = **离开之前画面是不是在刷新**（采集层用 `lastNewFrameAtMs` 判）。
     *
     * 为 true ⇒ 停更线提前到 [FOREGROUND_RETURN_STALL_MS]（见那里的真机依据）；为 false ⇒ 不提前。
     */
    fun noteForegroundReturn(flowingBeforeLeave: Boolean) {
        this.flowingBeforeLeave = flowingBeforeLeave
    }

    /**
     * **当前生效的停更线**（ms）——[isStale] / [isProlonged] 与采集层（`CaptureService.checkFrameStall`、
     * `checkSurfaceRetry`）**都用它**，保证"界面开始说停更"与"采集侧开始处置"是同一条线。
     */
    fun stallDeadlineMs(): Long = if (flowingBeforeLeave) FOREGROUND_RETURN_STALL_MS else STALE_MS

    /**
     * **会话建立了**（采集层 `attachMirror` 结束后调用）：帧龄归零 + 撤掉上次会话留下的"没被投喂"结论。
     *
     * 为什么必须在这里清 [starved]：新会话**可能**被正常投喂，挂着上一次的结论会让标签一直喊
     * "请退出重开"，而其实这次是好的。
     */
    fun noteSessionStart() {
        lastNewFrameAtMs = 0L
        starved = false
        flowingBeforeLeave = false
    }

    /** 采集侧判出"这次会话一帧都没被投喂"之后置位（判据只有一处，见 [FIRST_FEED_DEADLINE_MS]）。 */
    fun noteStarved() {
        starved = true
    }

    /** 会话结束 / 重建：帧龄与"没被投喂"**都不跨会话**（否则"没在采集"会被读成"画面停更"）。 */
    fun reset() {
        lastNewFrameAtMs = 0L
        starved = false
        flowingBeforeLeave = false
    }

    /** 距上一新帧多久（ms）；**从未收到过帧 = null**（刚授权 ⇒ 不是停更）。 */
    fun ageMs(): Long? {
        val last = lastNewFrameAtMs
        if (last == 0L) return null
        return SystemClock.elapsedRealtime() - last
    }

    /** 是否"停更"：**有基线**且超过 [stallDeadlineMs]（常规 60 秒；"切出切回"那次 12 秒）。 */
    fun isStale(): Boolean {
        val age = ageMs() ?: return false
        return age >= stallDeadlineMs()
    }

    /**
     * 是否"停更很久"：**有基线**且超过 [PROLONGED_MS] —— 但按**当前生效的停更线**换算
     * （`2 × [stallDeadlineMs]`）"这条线的含义是**采集侧的自救（换镜像表面）已经试过两轮**"
     * （见 [PROLONGED_MS]），所以短口径下它也跟着提前到 24 秒：处置已经试完了，该让用户动手。
     */
    fun isProlonged(): Boolean {
        val age = ageMs() ?: return false
        return age >= 2 * stallDeadlineMs()
    }

    /**
     * 是否"**这次授权根本没被投喂**"：会话建立后过了 [FIRST_FEED_DEADLINE_MS] 还是**一帧都没收到**。
     *
     * 与 [isStale] 的分工（两者判据相反、处置也不同）：
     * - [isStale]：**有过帧、又停了** ⇒ 分不出"屏幕静止"与"镜像坏了" ⇒ 只提示"久未更新"；
     * - 本判据：**从头到尾零帧** ⇒ 平台没投喂（`onImageAvailable` 回调 0 次）⇒ 直接说清并叫用户换进程重开。
     */
    fun isStarved(): Boolean = starved
}
