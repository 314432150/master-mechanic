package com.example.mastermechanic.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.example.mastermechanic.action.GestureInjector
import com.example.mastermechanic.action.InputJitter
import kotlin.math.cos
import kotlin.math.sin

/**
 * 手势注入的安卓侧实现（T2-3c，ADR-002）：无障碍 `dispatchGesture` 的薄适配，只做三件事——
 * 把一次手势画成路径、交给系统、把"完成 / 被取消"回抛给转发层。
 *
 * 两种手势（2026-09-29 起）：
 * - **点击**：短行程 stroke（既避开"零长路径"，又不写死成同一条斜线 —— 固定 1px + 固定 50ms 本身就是
 *   可被识别的机器人指纹，2026-09-21）；行程与时长在 [InputJitter]（拟人化的唯一出处）。
 * - **滑动**：按住从起点拖到终点（第 5 步「服务器列表逐屏滚动查找」用）。时长由调用方给（`SwipeRequest`），
 *   本类不掺策略。
 *
 * 不含任何策略：门禁、间隔、串行、审计都在 [com.example.mastermechanic.action.ClickForwarder]。
 * 服务不可用时 `dispatchGesture` 返回 false，转发层据此按"手势未完成"处理（不盲重试，红线 5）。
 */
class AccessibilityGestureInjector(
    private val service: AccessibilityService,
    /** 输入拟人化（2026-09-21）：按压时长与短行程随机化，见 [InputJitter]。 */
    private val jitter: InputJitter = InputJitter(),
) : GestureInjector {

    override fun click(x: Double, y: Double, onResult: (Boolean) -> Unit): Boolean {
        // 入参已是**屏幕坐标**（`dispatchGesture` 要的就是它）：帧坐标 → 屏幕坐标的换算
        // 在转发层完成（2026-09-20），本类不做任何几何判断，只负责把这一枪打出去
        val px = x.toFloat()
        val py = y.toFloat()
        val travel = jitter.travelPx().toFloat()
        val angle = jitter.travelAngleRadians()
        val path = Path().apply {
            moveTo(px, py)
            lineTo(
                px + travel * cos(angle).toFloat(),
                py + travel * sin(angle).toFloat(),
            )
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, jitter.pressDurationMs())
        return dispatch(stroke, onResult)
    }

    override fun swipe(
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        durationMs: Long,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        // 一条**直线**拖动：起止点由调用方按框选的「服务器列表区域」算好（本类不做几何判断）。
        // 时长用调用方给的（别在注入层悄悄改：拖动时长直接决定"滑多远"，是策略不是实现细节）
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatch(stroke, onResult)
    }

    /** 下发一个单 stroke 手势；返回值 = 服务当时是否接受（false = 未发生）。 */
    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        onResult: (Boolean) -> Unit,
    ): Boolean {
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
    // 时长与行程的取值区间在 `InputJitter`（点击拟人化的唯一出处），本类不再自带常量
}
