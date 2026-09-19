package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.floating.FloatingWindow
import com.example.mastermechanic.foreground.ForegroundEvaluator
import com.example.mastermechanic.foreground.ForegroundSignal
import com.example.mastermechanic.foreground.ForegroundStatus
import com.example.mastermechanic.notify.FloatingNotifier

/**
 * 无障碍服务：点击注入（ADR-002）、悬浮窗（ADR-004）、前台判定（ADR-005）的共同承载者。
 *
 * M0-T0-2 授权流所需的最小壳；T0-3 起承载前台判定（FR-09）；T0-5 起承载悬浮窗（FR-07，仅前台可见）；
 * T2-3c 起承载点击转发层的安装（服务连接时安装手势注入通道，断开即卸载 —— 服务不可用 = 不能点击）。
 */
class MasterMechanicAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val floatingWindow by lazy { FloatingWindow(this) }

    /** 前台信号 → 悬浮窗可见性：仅 FOREGROUND 挂载，其余一律整窗移除（FR-07 / A4）。 */
    private val onForegroundChanged: (ForegroundStatus) -> Unit = { status ->
        if (status == ForegroundStatus.FOREGROUND) floatingWindow.show() else floatingWindow.hide()
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
        // 点击通道（T2-3c）：注入实现 + 单调时钟 + 审计输出；模式默认演练（B5：实点需显式开启）
        ClickDispatch.install(
            injector = AccessibilityGestureInjector(this),
            clock = SystemClock::elapsedRealtime,
            audit = ClickAuditLog::write,
        )
        // 菜单提示的浮窗只能从**服务上下文**取 WindowManager（token 才有效）
        FloatingNotifier.bindWindow(this)
        refreshForeground("服务已连接")
        ForegroundSignal.addListener(onForegroundChanged)
        onForegroundChanged(ForegroundSignal.status) // 初始同步（监听只覆盖后续变化）
        handler.postDelayed(recheck, RECHECK_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshForeground("窗口事件")
        }
    }

    override fun onInterrupt() {
        stopRecheck()
        ClickDispatch.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        FloatingNotifier.unbindWindow()
        ForegroundSignal.reset("服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecheck()
        ClickDispatch.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        FloatingNotifier.unbindWindow()
        ForegroundSignal.reset("服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopRecheck()
        ClickDispatch.uninstall()
        ForegroundSignal.removeListener(onForegroundChanged)
        floatingWindow.hide()
        FloatingNotifier.unbindWindow()
        ForegroundSignal.reset("服务已销毁")
        super.onDestroy()
    }

    private fun stopRecheck() {
        handler.removeCallbacks(recheck)
    }

    /** 主动查询活动（焦点）窗口包名；查询异常 / 结果为空 → 判定为非前台（FR-09）。 */
    private fun refreshForeground(source: String) {
        val activePackage = try {
            windows?.firstOrNull { it.isActive }?.root?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        ForegroundSignal.update(ForegroundEvaluator.evaluate(activePackage), source)
    }

    private companion object {
        const val RECHECK_INTERVAL_MS = 1500L
    }
}
