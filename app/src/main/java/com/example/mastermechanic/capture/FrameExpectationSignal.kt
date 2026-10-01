package com.example.mastermechanic.capture

import android.os.SystemClock

/**
 * **"我们刚刚改动了屏幕内容，理应马上来一帧新帧"这个期待**（2026-09-25 真机事故后加）。
 *
 * ## 为什么需要它（第 211 条那次事故）
 *
 * 真机实录：11:23:29 起镜像再也**没有产生过新帧**，而识别每轮都在重放同一张缓存画面 ——
 * 用户滚屏、开合悬浮窗面板全都"看不见"，"添加这一屏的服务器"连读四次读到的东西一模一样
 * （`帧：兜底重放（N ms 前拍到）`，N 从 808 涨到 16 万）。会话却一直活着，**没有任何人发现**。
 *
 * 已有的看门狗（`CaptureService.checkMirrorStall`）只盯"**转屏之后**"那一种失效，
 * 而这次失效跟转屏无关 ⇒ 漏掉了。
 *
 * ## ⚠ 2026-09-28：这个前提**只对"整个屏幕"采集成立**
 *
 * 判据的前提是"**悬浮窗面板展开 / 收起 / 内容变化 = 显示内容变化 ⇒ 合成器必然出帧**"。
 * 但在授权弹窗里选「**共享一个应用**」时（App 里那句说明文案就是这么推荐的），
 * 采集内容**只有那一个 App 的窗口** —— 我们的覆盖窗根本不在画面里，
 * 面板怎么变都不会有新帧 ⇒ 这条判据必然误报。
 * 真机实录（第 217 条）：用户授完权刚切到游戏，会话就被它当成"镜像失效"结束，
 * 菜单上出现"尚未建立采集会话：请先授予采集权限" ✗。
 *
 * ⇒ **`CaptureService.checkFrameExpectation` 现在只拿它记一行诊断日志，不结束会话。**
 * 本信号仍然保留：帧龄、面板动作的时序在真机排障时是唯一的线索
 * （`帧：新帧 / 兜底重放（N ms 前拍到）`）。
 *
 * 帧线程在收到**新帧**时调用 [reached] 清除期待；静止兜底的重放**不算**收到帧。
 */
object FrameExpectationSignal {

    /** 面板那种"改动很小"的期待，给它 3 秒就够（合成器出帧是毫秒级的）。 */
    const val DEFAULT_TIMEOUT_MS = 3_000L

    /** 期待被建立的那一刻（0 = 当前没有期待）。 */
    @Volatile
    var armedAtMs: Long = 0L
        private set

    /** 是什么动作建立了这份期待（日志里要能看出"为什么现在该有新帧"）。 */
    @Volatile
    var reason: String = ""
        private set

    /**
     * **这份期待是否"与采集模式无关"** —— 只有它才允许把"期待落空"升级成
     * `ClickDenyReason.STALE_FRAMES` 那道硬闸（2026-09-29）。
     *
     * - `true`：**我们刚下发过一击 / 一次滑动** —— 被采集的那个 App 的内容必然变了，
     *   出帧是必然的；「共享一个应用」下同样成立 ✓
     * - `false`（默认）：**我们自己的面板**展开 / 收起 / 内容变化 —— 选「共享一个应用」时
     *   我们的覆盖窗**不在采集内容里**，怎么变都不会来新帧 ⇒ 期待本身不成立，认它就会误杀
     *   （2026-09-28 第 217 条被降级掉的就是这一类）✗
     *
     * ⇒ 面板那几处（[com.example.mastermechanic.floating.FloatingWindow]）**故意不传这个参数**，
     * 于是它们只参与诊断日志、永远不会闸住点击。
     */
    @Volatile
    var gateEligible: Boolean = false
        private set

    /**
     * 建立期待。
     *
     * @param gateEligible 见 [gateEligible]：**默认 false**（面板类期待只做诊断）。
     *   只有"我们刚下发过一击 / 滑动"这种与被采集内容同源的期待才传 true。
     */
    fun arm(reason: String, gateEligible: Boolean = false) {
        armedAtMs = SystemClock.elapsedRealtime()
        this.reason = reason
        this.gateEligible = gateEligible
    }

    /** 收到**新帧** ⇒ 期待已满足（会话结束时也要清一次）。 */
    fun reached() {
        armedAtMs = 0L
        reason = ""
        gateEligible = false
    }

    /**
     * **期待是否已经落空**（纯函数）：建立了期待、过了 [timeoutMs] 还一帧新画面都没来。
     *
     * 为什么要有它（2026-09-29 用户报"换号 / 拜访时很多标志和锚点都认不出"）：真机实录 ——
     * 会话重建（用户回到游戏 / 重新授权）之后**整整 5 分钟没有一帧新画面**，程序继续用那张旧画面
     * 做判定 ⇒ "什么都认不出"，还**照着旧坐标点了一枪** ✗。已有的看门狗只管"转屏之后"那一种
     * （而这次 resize 被跳过 ⇒ 没起表），这条判据补的正是这个洞。
     *
     * ⚠ 判据本身**只说明"期待落空"**，不等于"镜像失效"：授权里选「共享一个应用」时，
     * 我们自己的面板根本不在采集内容里（那个期待本来就不成立，见类注释）⇒ 调用方**必须**只拿
     * 与采集模式无关的那些期待点来用它（"我们刚下发过一击"/"目标刚回到前台"，而不是"面板开合"）。
     */
    fun isOverdue(armedAtMs: Long, nowMs: Long, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        armedAtMs > 0L && nowMs - armedAtMs >= timeoutMs
}
