package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.example.mastermechanic.foreground.ForegroundEvaluator
import com.example.mastermechanic.foreground.ForegroundSignal

/**
 * 无障碍服务：点击注入（ADR-002）、悬浮窗（ADR-004）、前台判定（ADR-005）的共同承载者。
 *
 * M0-T0-2 授权流所需的最小壳；T0-3 起承载前台判定（FR-09）；手势能力在 T0-5 接入。
 */
class MasterMechanicAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 主动复核兜底：周期小于 2 秒，保证事件丢失时状态变化仍能在 2 秒内生效（ADR-005）。 */
    private val recheck = object : Runnable {
        override fun run() {
            refreshForeground("周期复核")
            handler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshForeground("服务已连接")
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
        ForegroundSignal.reset("服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecheck()
        ForegroundSignal.reset("服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopRecheck()
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
