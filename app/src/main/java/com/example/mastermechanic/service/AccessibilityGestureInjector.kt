package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.example.mastermechanic.action.GestureInjector

/**
 * 手势注入的安卓侧实现（T2-3c，ADR-002）：无障碍 `dispatchGesture` 的薄适配，只做三件事——
 * 把一次点击画成**短行程 stroke**、交给系统、把"完成 / 被取消"回抛给转发层。
 *
 * 不含任何策略：门禁、间隔、串行、审计都在 [com.example.mastermechanic.action.ClickForwarder]。
 * 服务不可用时 `dispatchGesture` 返回 false，转发层据此按"点击未完成"处理（不盲重试，红线 5）。
 */
class AccessibilityGestureInjector(
    private val service: AccessibilityService,
) : GestureInjector {

    override fun click(frameX: Double, frameY: Double, onResult: (Boolean) -> Unit): Boolean {
        val x = frameX.toFloat()
        val y = frameY.toFloat()
        // 短行程（1 像素）：部分机型对"零长路径"的判定不稳定，留一点位移让点击稳定成立
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + TAP_TRAVEL_PX, y + TAP_TRAVEL_PX)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onResult(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onResult(false)
                }
            },
            null,
        )
    }

    private companion object {
        const val TAP_DURATION_MS = 50L
        const val TAP_TRAVEL_PX = 1f
    }
}
