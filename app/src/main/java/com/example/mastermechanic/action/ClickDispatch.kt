package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState

/**
 * 点击转发层的进程内入口（T2-3c）：无障碍服务在连接时 [install]、断开时 [uninstall]，
 * 调用方（FR-01 / 巡查主流程 / 悬浮窗菜单）一律经 [submit] 下发——**没有第二个下发口**（ADR-002）。
 *
 * - 服务未安装（无障碍不可用 / 授权被撤销）→ 返回「无障碍服务不可用」，调用方按"点不了"处理
 *   （ADR-002：服务不可用 = 不能点击，整体按"不可用即停"）；
 * - **只有真实点击一种行为**（2026-09-22 用户口径）：旧的「演练（只识别不下发）」模式与
 *   [enableLive] / [enableDrill] 两个开关，连同授权页的「运行方式」卡，已整体移除。
 *   一次点击是否真的发出去，只由 [ClickGate] 的硬性约束决定（守护 / 前台 / 状态 / 落点 / 间隔）。
 *
 * 跨线程：调用方在帧线程、服务在主线程，故共享状态用 @Volatile（写入都是整引用替换）。
 */
object ClickDispatch {

    @Volatile
    private var forwarder: ClickForwarder? = null

    val isInstalled: Boolean get() = forwarder != null

    /**
     * 当前屏幕尺寸（T4-6，2026-09-23）：采集会话在屏幕尺寸变化时推入，默认 [ScreenSize.UNKNOWN]
     * （未知 ⇒ 不做越界判定）。
     *
     * 原先是"帧 → 屏幕"的换算结果（`ClickGeometry`）；转屏由 `VirtualDisplay.resize()` 跟进后
     * 帧恒与屏幕同向同尺寸，换算整层被删除，这里只剩"屏幕多大"这一个事实。
     * 放这儿而不是让调用方各自持有：本对象是**进程内唯一下发口**，越界判定必须跟着"唯一"走。
     */
    @Volatile
    var screenSize: ScreenSize = ScreenSize.UNKNOWN
        private set

    /** 更新屏幕尺寸（采集会话在屏幕尺寸变化时推入；跨线程读，整引用替换）。 */
    fun setScreenSize(next: ScreenSize) {
        screenSize = next
    }

    /**
     * 常驻守护服务是否在跑（2026-09-20 用户口径：**它是运行自动化的必备项**）。
     *
     * 默认 **false**：进程刚起来、守护还没启动时，一律不许点击——这样"忘了启动守护"表现为
     * **明确的拒绝（审计里写原因）**，而不是"看着在跑、其实没人接单"的假运行态。
     * 由 `ResidentService.onCreate` / `onDestroy` 置位（跨线程读，整引用替换）。
     */
    @Volatile
    var guardRunning: Boolean = false
        private set

    fun setGuardRunning(running: Boolean) {
        guardRunning = running
    }

    /**
     * **画面采集是否已停**（2026-09-29 加）：采集侧判定"期待落空"后推入（见
     * `CaptureService.checkFrameExpectation`），**一帧新画面来了就自动清掉**。
     *
     * 置位期间**任何来源的点击都会被拒**（`ClickDenyReason.STALE_FRAMES`）—— 与 `guardRunning`
     * 同一套路（进程内唯一出口持有这个事实，门禁按它复核），这样 FR-01 / 跑号 / 菜单三条路
     * 都不必各自判断。默认 false ⇒ 既有行为不变。
     */
    @Volatile
    var framesStalled: Boolean = false
        private set

    fun setFramesStalled(stalled: Boolean) {
        framesStalled = stalled
    }

    /**
     * **前台"可疑"**（2026-10-01 加）：复核判"不到前台"，但游戏的窗口还在屏上（见
     * `ClickEnvironment.foregroundSuspect` 的说明与 `ClickDenyReason.FOREGROUND_SUSPECT`）。
     *
     * 置位期间**任何来源的点击都会被拒** —— 与 [framesStalled] 同一套路：安全侧立刻生效，
     * 而"暂停跑号 / 摘悬浮窗"那些用户可见的后果等下一次复核确认（两段式）。
     */
    @Volatile
    var foregroundSuspect: Boolean = false
        private set

    fun setForegroundSuspect(suspect: Boolean) {
        foregroundSuspect = suspect
    }

    /** 服务连接时安装（重复安装 = 重建，间隔计时随之清零：服务重启后不可能有在途手势）。 */
    fun install(
        injector: GestureInjector,
        clock: () -> Long,
        audit: (ClickEvent) -> Unit,
        screenSize: () -> ScreenSize = { ClickDispatch.screenSize },
    ) {
        forwarder = ClickForwarder(ClickGate(), injector, clock, audit, screenSize)
    }

    /** 服务断开 / 被中断 / 销毁时卸载：之后所有点击请求都会被拒（绝不试探）。 */
    fun uninstall() {
        forwarder = null
    }

    /**
     * 提交一次点击请求。[gameForeground] 取前台信号（ADR-005），[state] 取当前识别状态——
     * 两者都由调用方如实传入，门禁按红线 1 复核；调用方不得"猜"一个好看的值。
     */
    fun submit(request: ClickRequest, gameForeground: Boolean, state: UiState): ClickVerdict {
        val target = forwarder ?: return ClickVerdict(false, ClickDenyReason.NO_INJECTOR)
        return target.submit(
            request,
            ClickEnvironment(
                gameForeground,
                state,
                guardRunning = guardRunning,
                framesStalled = framesStalled,
                foregroundSuspect = foregroundSuspect,
            ),
        )
    }

    /**
     * 提交一次**滑动**请求（2026-09-29 加，第 5 步滚列表）：与 [submit] **同一条门禁与审计**
     * （红线 6：唯一的"下发口"概念是指"**手势**只有一个出口"，不是"只有一种手势"）。
     */
    fun submitSwipe(request: SwipeRequest, gameForeground: Boolean, state: UiState): ClickVerdict {
        val target = forwarder ?: return ClickVerdict(false, ClickDenyReason.NO_INJECTOR)
        return target.submitSwipe(
            request,
            ClickEnvironment(
                gameForeground,
                state,
                guardRunning = guardRunning,
                framesStalled = framesStalled,
                foregroundSuspect = foregroundSuspect,
            ),
        )
    }
}
