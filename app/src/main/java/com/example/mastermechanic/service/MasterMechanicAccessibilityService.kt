package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：点击注入（ADR-002）、悬浮窗（ADR-004）、前台判定（ADR-005）的共同承载者。
 *
 * 当前为 M0-T0-2 授权流所需的最小壳；窗口事件与手势能力在 T0-3 / T0-5 接入。
 */
class MasterMechanicAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 前台判定（ADR-005）在 M0-T0-3 接入
    }

    override fun onInterrupt() {
        // 无中断处理需求
    }
}
