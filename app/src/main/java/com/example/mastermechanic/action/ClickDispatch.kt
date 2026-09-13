package com.example.mastermechanic.action

import com.example.mastermechanic.decision.UiState

/**
 * 点击转发层的进程内入口（T2-3c）：无障碍服务在连接时 [install]、断开时 [uninstall]，
 * 调用方（FR-01 / 巡查主流程 / 悬浮窗菜单）一律经 [submit] 下发——**没有第二个下发口**（ADR-002）。
 *
 * - 服务未安装（无障碍不可用 / 授权被撤销）→ 返回「无障碍服务不可用」，调用方按"点不了"处理
 *   （ADR-002：服务不可用 = 不能点击，整体按"不可用即停"）；
 * - 模式（演练 / 实点）默认**演练**，只能由用户显式开启实点（B5）；本层不做任何"自动升级为实点"。
 *
 * 跨线程：调用方在帧线程、服务在主线程，故共享状态用 @Volatile（写入都是整引用替换）。
 */
object ClickDispatch {

    @Volatile
    private var forwarder: ClickForwarder? = null

    @Volatile
    var mode: ClickMode = ClickMode.DRILL
        private set

    val isInstalled: Boolean get() = forwarder != null

    /** 服务连接时安装（重复安装 = 重建，间隔计时随之清零：服务重启后不可能有在途手势）。 */
    fun install(injector: GestureInjector, clock: () -> Long, audit: (ClickEvent) -> Unit) {
        forwarder = ClickForwarder(ClickGate(), injector, clock, audit)
    }

    /** 服务断开 / 被中断 / 销毁时卸载：之后所有点击请求都会被拒（绝不试探）。 */
    fun uninstall() {
        forwarder = null
        mode = ClickMode.DRILL // 服务重启后回到安全默认态，实点需要用户再次显式开启
    }

    /** 用户显式开启实点（B5）。 */
    fun enableLive() {
        mode = ClickMode.LIVE
    }

    /** 回到演练（只识别）。 */
    fun enableDrill() {
        mode = ClickMode.DRILL
    }

    /**
     * 提交一次点击请求。[gameForeground] 取前台信号（ADR-005），[state] 取当前识别状态——
     * 两者都由调用方如实传入，门禁按红线 1 复核；调用方不得"猜"一个好看的值。
     */
    fun submit(request: ClickRequest, gameForeground: Boolean, state: UiState): ClickVerdict {
        val target = forwarder ?: return ClickVerdict(false, ClickDenyReason.NO_INJECTOR)
        return target.submit(request, ClickEnvironment(gameForeground, state, mode))
    }
}
