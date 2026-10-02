package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.action.SystemKeys
import com.example.mastermechanic.action.TextInjector
import com.example.mastermechanic.floating.FloatingWindow
import com.example.mastermechanic.foreground.ForegroundEvaluator
import com.example.mastermechanic.foreground.ForegroundSignal
import com.example.mastermechanic.foreground.ForegroundStatus
import com.example.mastermechanic.foreground.TargetApp
import com.example.mastermechanic.log.MmLog

/**
 * 无障碍服务：点击注入（ADR-002）、悬浮窗（ADR-004）、前台判定（ADR-005）的共同承载者。
 *
 * M0-T0-2 授权流所需的最小壳；T0-3 起承载前台判定（FR-09）；T0-5 起承载悬浮窗（FR-07，仅前台可见）；
 * T2-3c 起承载点击转发层的安装（服务连接时安装手势注入通道，断开即卸载 —— 服务不可用 = 不能点击）。
 */
class MasterMechanicAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * **前台查询线程**（2026-09-29）：`windows…root` 的建树代价**绝不能落在主线程上**。
     *
     * 真机实录（打开框选页 ⇒ 黑屏 + ANR）：从进入那一页起，`GC freed 91~96MB, 0(0B) LOS objects` 每 ~270ms
     * 一次（≈350MB/s）、并且**探针日志（跑在主线程）从那一刻起就不再打了** ⇒ 主线程正卡在**一句查询**里
     * （我们自己的 Compose 页，整棵语义树要在这里建出来）。所以：
     * ① 查询搬到本线程；② 一次没完**不发起第二次**（[foregroundQueryBusy]）。
     * 查询本身很便宜（实测 1~3ms / ≈0MB），放在这里只是为了"**主线程绝不碰它**"这条不变量。
     */
    private val foregroundThread = HandlerThread("MM-Foreground").apply { start() }
    private val foregroundHandler = Handler(foregroundThread.looper)

    /** 是否已有一次前台查询在飞（只被主线程读写）。 */
    private var foregroundQueryBusy = false

    /**
     * **"判不在前台"的首次怀疑时刻**（0 = 没有怀疑；见 [refreshForeground] 的两段式）。
     *
     * 2026-10-01 加（用户报"**游戏在前台，却提示不在前台**"）：vivo 桌面会短暂抢走"活动窗口"
     * （真机 2.70 / 2.99 秒两次），而游戏窗口一直在屏上；同日又发现**一次查询的瞬时结论本身也会错**
     * （`21:05:46` / `21:07:07` 报"游戏窗口 0 个"，1 秒后游戏窗口事件就把前台打回来了）。
     * ⇒ 现在**任何** NOT 结论都先当成"可疑"：**先闸点击**（安全侧不延迟）、
     * **等下一次复核确认**（≥ [FOREGROUND_SUSPECT_CONFIRM_MS]）才改判前台状态。
     */
    @Volatile
    private var foregroundSuspectSinceMs = 0L

    private val floatingWindow by lazy { FloatingWindow(this) }

    /**
     * 前台信号 → 悬浮窗可见性：仅 FOREGROUND 挂载，其余一律整窗移除（FR-07 / A4）。
     *
     * ⚠ **挂载 / 移除必须回到主线程**（2026-09-29）：前台状态现在可能由 [foregroundHandler] 上的
     * 查询线程更新（见 [refreshForeground]），而监听器是**在更新线程上同步回调**的 ——
     * `WindowManager.addView/removeView` 只能在主线程做，否则直接抛异常。
     */
    private val onForegroundChanged: (ForegroundStatus) -> Unit = { status ->
        handler.post {
            if (status == ForegroundStatus.FOREGROUND) floatingWindow.show() else floatingWindow.hide()
        }
    }

    /** 主动复核兜底：周期小于 2 秒，保证事件丢失时状态变化仍能在 2 秒内生效（ADR-005）。 */
    private val recheck = object : Runnable {
        override fun run() {
            refreshForeground("周期复核")
            handler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 点击通道（T2-3c）：注入实现 + 单调时钟 + 审计输出（只有真实点击一种行为，无模式开关）
        ClickDispatch.install(
            injector = AccessibilityGestureInjector(this),
            clock = SystemClock::elapsedRealtime,
            audit = ClickAuditLog::write,
        )
        // **写文字通道**（2026-10-01，第 9 步搜索式查找）：原生 `ACTION_SET_TEXT` 直接写进可编辑控件
        // （见 `TextInjector` 的说明：不走输入法 ⇒ 不弹键盘、也不用点"完成"收键盘）。
        TextInjector.install { text -> TextInjector.writeVia(this, text) }
        // **点原生控件**（2026-10-01）：搜索框写完字后点那个原生「确定」提交（见 `action/TextInjector.click`）
        TextInjector.installClicker { label -> TextInjector.clickVia(this, label) }
        // **系统返回**（2026-10-01）：第 9 步搜索链写完文字后用来**收输入法**（见 `action/SystemKeys`）
        SystemKeys.install { SystemKeys.backVia(this) }
        refreshForeground("服务已连接")
        ForegroundSignal.addListener(onForegroundChanged)
        onForegroundChanged(ForegroundSignal.status) // 初始同步（监听只覆盖后续变化）
        handler.postDelayed(recheck, RECHECK_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            // **事件自带的 `packageName` 就是"新获得活动的那个包"**（String，不用建任何节点树）⇒ 零成本路径
            //（2026-09-29 加：见 [refreshForeground] 里那句 `.root` 的代价说明）
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                // ⚠ **我们自己的包一律忽略**（2026-09-29 真机"悬浮窗一直在闪烁"的修复点）：
                // 我们自己的覆盖窗（手柄 / 菜单 / 状态标签）**也在 `TYPE_WINDOW_STATE_CHANGED` 的覆盖范围内**，
                // 包名就是本 App ⇒ 一旦认它就会把状态打成"不在前台" ⇒ 悬浮窗**自己把自己摘掉**，
                // 下一次游戏窗口事件又挂回来 ⇒ **每 ~1.4 秒反复挂载 / 移除**（日志实录：
                // `悬浮窗已挂载` → 0.15 秒后 `悬浮窗已移除`，如此循环）⇒ 每次 addView/removeView 都是主线程同步
                // binder ⇒ 5 秒内处理不了 MotionEvent ⇒ **ANR**（用户看到"闪烁 + 崩溃"）。
                // 「我们的界面到前台」这件事由周期复核覆盖（≤1.5 秒内生效，完全够用）⇒ 这里忽略不丢功能。
                if (packageName != null && packageName != this.packageName) {
                    val verdict = ForegroundEvaluator.evaluate(packageName)
                    // **不对称判定**（2026-09-30 真机第 309 条）：窗口事件判"**不在前台**"时，必须**权威复核**一次才生效。
                    //
                    // 依据（12:00 实录）：
                    // ```
                    // 12:00:16 前台: NOT_FOREGROUND -> FOREGROUND（来源: 周期复核）
                    // 12:00:44 前台: FOREGROUND -> **NOT_FOREGROUND（来源: 窗口事件）**   ← 某个非游戏的窗口事件
                    // 12:00:44 悬浮窗已移除 / 12:00:45 跑号: 游戏不在前台，已暂停        ← 副作用立刻发生
                    // 12:00:46 前台: NOT_FOREGROUND -> FOREGROUND（来源: 周期复核）      ← 1.4 秒后又对了
                    // ```
                    // ⇒ 游戏**一直在前台**，那是一次 1.4 秒的抖动，代价却是"悬浮窗被摘 + 流程被暂停（要用户手动点继续）"。
                    // ⇒ 判"不在前台"**立刻有副作用**（摘窗、暂停、冻结识别），值得比"回到前台"更保守；
                    //    而"回到前台"晚一点**没有代价** ⇒ 两个方向**不对称**处理。
                    if (verdict == ForegroundStatus.FOREGROUND) {
                        // **游戏自己发来的窗口事件 = 最权威的"它回来了"** ⇒ 顺手解除"前台可疑"（若有）
                        clearForegroundSuspect("窗口事件（$packageName）")
                        ForegroundSignal.update(verdict, "窗口事件")
                    } else {
                        // 复核走**权威查询**（活动窗口）：我们自己的覆盖窗不可聚焦 ⇒ 不会把自己算成活动窗口
                        refreshForeground("窗口事件复核（事件判 ${packageName} 不在前台）")
                    }
                }
            }
            // 这一类只说"窗口集合变了"，不给活动窗口的包名 ⇒ 走查询。
            // ⚠ 已**不再订阅**它（见 `accessibility_service_config.xml` 的注释）：它极频繁，而每次投递都可能
            // 让系统替我们建"事件源窗口的整棵节点树" ⇒ 实测 ≈350MB/s 的短命小对象 ⇒ ANR。留这一支只是兜底。
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshForeground("窗口事件")
        }
    }

    override fun onInterrupt() {
        stopRecheck()
        ClickDispatch.uninstall()
        TextInjector.uninstall()
        SystemKeys.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        clearForegroundSuspect("服务被中断")
        ForegroundSignal.reset("服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecheck()
        ClickDispatch.uninstall()
        TextInjector.uninstall()
        SystemKeys.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        ForegroundSignal.reset("服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopRecheck()
        ClickDispatch.uninstall()
        TextInjector.uninstall()
        SystemKeys.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        clearForegroundSuspect("服务已销毁")
        ForegroundSignal.reset("服务已销毁")
        super.onDestroy()
    }

    private fun stopRecheck() {
        handler.removeCallbacks(recheck)
        foregroundHandler.removeCallbacksAndMessages(null)
        foregroundThread.quitSafely()
    }

    /**
     * 主动查询活动（焦点）窗口包名；查询异常 / 结果为空 → 判定为非前台（FR-09）。
     *
     * ## ⚠ 这里**曾经**加过一条"我们自己在前台就直接判 NOT_FOREGROUND、不查"的短路，已回退（别再照它写）
     *
     * 那条短路的动机是省掉 `windows…root` 的建树代价，但**它自己成了更大的故障**（2026-09-29 真机）：
     * "我们自己的 Activity 是否 resumed"这个判据**一旦卡住**（计数器不再回零），周期复核就永远返回
     * "不在前台" ⇒ 游戏里悬浮窗被**每 ~1.4 秒摘掉一次**（下文日志），而下一次游戏窗口事件又把它挂回来 ⇒
     * **闪烁**；每次 `addView/removeView` 都是主线程同步 binder ⇒ 5 秒处理不了 MotionEvent ⇒ **ANR**。
     * ```
     * 07:33:54.747 悬浮窗已挂载…   07:33:54.893 悬浮窗已移除
     * 07:33:56.267 悬浮窗已挂载…   07:33:56.421 悬浮窗已移除   （如此循环）
     * 07:34:52 am_anr: Input dispatching timed out … Waited 5000ms for MotionEvent
     * ```
     * ⇒ 教训：**前台判定必须只看"系统事实"**（活动窗口 / 事件包名），不要引入"我们自己的生命周期状态"
     * 这种**可能卡住**的、需要与系统状态保持同步的镜像判据。
     * 查询本身很便宜（真机实测 1~3ms / ≈0MB），但**绝不能落在主线程**上 ⇒ 一律在 [foregroundThread] 上做。
     */
    private fun refreshForeground(source: String) {
        // **绝不并发发起第二次**：一次查询没结束就跳过这次（否则慢查询会排队，越堵越死）。
        if (foregroundQueryBusy) return
        foregroundQueryBusy = true
        foregroundHandler.post {
            // 一次查询顺手取三件事（都来自同一份 `windows`，不额外建树）：
            //   ① 活动窗口包名（判据本身）；② **目标游戏的窗口还在不在 / 可见吗**（判"真离开"与"只是焦点被抢"）；
            //   ③ 窗口总数（诊断：下次复发时一眼能看出那一屏到底有几个窗口）。
            // ⚠ ②③ 是 2026-10-01 加的：此前只记"事件判 X 不在前台"，**看不出**活动窗口到底是谁、
            // 游戏是不是还在屏上 ⇒ 用户报"游戏在前台却提示不在前台"时无法定论。
            var activePackage: String? = null
            var targetWindows = 0
            var targetVisible = false
            var windowCount = 0
            try {
                val list = windows.orEmpty()
                windowCount = list.size
                list.forEach { w ->
                    val pkg = w.root?.packageName?.toString()
                    if (pkg == TargetApp.PACKAGE_NAME) {
                        targetWindows++
                        // 用**节点**上的 `isVisibleToUser`（`AccessibilityWindowInfo.isVisible`
                        // 在这套 SDK 桩里取不到）：目标是"这一屏上还看得见游戏吗"——被别的窗口盖住
                        // 也算看得见（那只是焦点被抢），切走 / 回桌面才算看不见。
                        if (w.root?.isVisibleToUser == true) targetVisible = true
                    }
                    if (w.isActive) activePackage = pkg
                }
            } catch (e: Exception) {
                // 查询失败 ⇒ activePackage 留 null（ADR-005：判不了就按非前台）——
                // 但走的仍是下面的**两段式**：多核实一次才影响用户可见的状态。
            }
            val verdict = ForegroundEvaluator.evaluate(activePackage)
            val detail = "活动窗口 ${activePackage ?: "（查不到）"}｜游戏窗口 $targetWindows 个" +
                (if (targetVisible) "（可见）" else "（不可见）") + "｜本屏共 $windowCount 个窗口"
            if (verdict == ForegroundStatus.FOREGROUND) {
                clearForegroundSuspect("$source：$detail")
                ForegroundSignal.update(ForegroundStatus.FOREGROUND, source)
            } else {
                // **判"不在前台"一律要"下一次复核再确认"**（2026-10-01 两次真机教训合并后的口径）：
                // ① vivo 桌面会短暂抢走活动窗口（2.70 / 2.99 秒，游戏一直在屏上）；
                // ② **一次查询的瞬时结论本身也可能不对** —— 真机 `21:05:46` / `21:07:07` 都出现过
                //    `游戏窗口 0 个（不可见）`，而 1 秒后游戏窗口事件就把前台打回来了（用户就坐在游戏里）。
                // ⇒ 所以**不区分"窗口在不在"**：任何一次 NOT 都只算"可疑"，确认持续
                // [FOREGROUND_SUSPECT_CONFIRM_MS] 才真的改判（暂停跑号 + 摘悬浮窗）。
                // ⚠ **安全侧零延迟**：第一次怀疑就把点击闸住（`ClickDispatch.setForegroundSuspect`）
                // ⇒ 确认期间**一枪都不会打出去**，所以"晚 3.5 秒暂停"没有安全代价。
                val since = foregroundSuspectSinceMs
                val nowMs = SystemClock.elapsedRealtime()
                if (since == 0L) {
                    foregroundSuspectSinceMs = nowMs
                    ClickDispatch.setForegroundSuspect(true)
                    // **视觉上立刻收起悬浮窗**（2026-10-01 用户报"切到后台后顶部标签不会立即消失、
                    // 停留了一会儿"）：那 3.5 秒确认窗是为了**不误暂停跑号**而加的，但它顺手把
                    // "标签收起"也一起延后了 ✗ —— 用户已经切走了，标签留在别人屏幕上只会碍事。
                    // ⇒ 拆开：**看得见的后果**（收窗）**立刻**发生；**状态改判**（暂停跑号）等确认。
                    // ⚠ 不会回到 2026-09-29 那次"每 1.4 秒挂载 / 移除 ⇒ ANR"的循环：那条路径是
                    // "我们自己的覆盖窗事件被反复当成非前台"；这里只在真的出现非游戏窗口时发生一次，
                    // 且下一次复核（1.5 秒）就会纠正 ⇒ 最多"收起 → 挂回"一次。
                    handler.post { floatingWindow.hide() }
                    MmLog.w(
                        TAG,
                        "前台**可疑**（$source：$detail）⇒ 立刻收起悬浮窗、先不放行任何点击，" +
                            "等下一次复核确认是否真的离开（2026-10-01 加的护栏：桌面抢焦点 2.7 / 2.99 秒、" +
                            "以及「游戏窗口 0 个」这种一次性的瞬时结论，都不能只凭一票就暂停流程）",
                    )
                } else if (nowMs - since >= FOREGROUND_SUSPECT_CONFIRM_MS) {
                    // ⚠ **只在"真的还没改判"时记一次**（2026-10-01 真机日志刷屏修）：
                    // 复核每 1.5 秒一轮，而确认之后 `since` 一直不为 0 ⇒ 不加这道判断就会
                    // **每轮都重打一行 + 重复改判**（真机实录：`认下「不在前台」` 从"持续 4252ms"
                    // 一路刷到"持续 44767ms"，把日志淹掉、真出故障时反而不好查）。
                    if (ForegroundSignal.status != ForegroundStatus.NOT_FOREGROUND) {
                        val heldMs = nowMs - since
                        MmLog.w(
                            TAG,
                            "前台可疑持续 ${heldMs}ms ⇒ **认下「不在前台」**（$source：$detail）" +
                                "：暂停流程 + 摘悬浮窗（点击闸保持）",
                        )
                        ForegroundSignal.update(
                            ForegroundStatus.NOT_FOREGROUND,
                            "$source（持续 ${heldMs}ms：$detail）",
                        )
                    }
                }
            }
            handler.post { foregroundQueryBusy = false }
        }
    }

    /**
     * 撤销"前台可疑"：**解除点击闸**并清零计时（见 [refreshForeground] 的两段式）。
     *
     * 幂等：本来就没怀疑时什么都不做、也不打日志（周期复核每 1.5 秒一次，否则会刷屏）。
     */
    private fun clearForegroundSuspect(reason: String) {
        val since = foregroundSuspectSinceMs
        if (since == 0L && !ClickDispatch.foregroundSuspect) return
        foregroundSuspectSinceMs = 0L
        val heldMs = if (since == 0L) 0L else SystemClock.elapsedRealtime() - since
        if (ClickDispatch.foregroundSuspect) {
            ClickDispatch.setForegroundSuspect(false)
            // 可疑期间**立刻收起过悬浮窗**（见上面那条说明）⇒ 解除时要把**看得见的那部分还回去**：
            // 信号没变过（那时不会触发变更监听）⇒ 这里必须显式挂回，否则游戏还在前台却一直没标签。
            if (ForegroundSignal.isForeground) handler.post { floatingWindow.show() }
            MmLog.i(TAG, "前台可疑已解除（$reason）⇒ 恢复放行点击（可疑共持续 ${heldMs}ms）")
        }
    }

    private companion object {
        const val TAG = "MM-Foreground"

        const val RECHECK_INTERVAL_MS = 1500L

        /**
         * **"前台可疑"要持续多久才认下「不在前台」**（2026-10-01 加）。
         *
         * 取 3.5 秒的依据：真机里"桌面抢焦点"那两次分别持续 **2.70 / 2.99 秒**，而"这一次查询报了
         * 游戏窗口 0 个"那两次也只撑了 1~2 秒（下一次事件就纠回来了）⇒ 3.5 秒把这两类抖动**全部吸收**；
         * 而真正被盖住 / 切走的情形，晚 3.5 秒生效**没有安全代价**（这期间点击已经闸住，一枪都不打），
         * 只是"暂停 / 摘窗"晚一点而已。
         * ⚠ 因此 FR-09 那条「生效延迟 2 秒」对**判"不在前台"**这一侧放宽到 3.5 秒（口径修订一有记）；
         * 判"回到前台"仍是**立刻**生效、一根毛都不延迟。
         */
        const val FOREGROUND_SUSPECT_CONFIRM_MS = 3_500L
    }
}
