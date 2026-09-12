package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.example.mastermechanic.R
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.decision.UiStateSignal

/**
 * 悬浮窗容器（FR-07 / ADR-004）：收起态紧凑手柄 + 识别状态文字（T1-7）。
 *
 * - 无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY），由无障碍服务挂载，不引入额外授权；
 * - 手柄文字随 [UiStateSignal] 更新（「状态：<识别结论>」，FR-07 展示口径的 M1 部分）；
 *   信号更新发生在采集帧线程，本类自行编组回主线程；
 * - 仍为不可触摸 + 不可聚焦（触摸恒穿透，A5 口径延续），文字更新不改变触摸特性；
 * - 可见性由调用方按前台信号驱动：非前台时整窗移除（不可见且不占触摸），回前台重建（A4）；
 *   重建时以信号当前值初始化文字（监听只覆盖后续变化）；
 * - 仅主线程调用挂载 / 移除（调用源：无障碍服务回调与窗口事件，均在主线程）。
 */
class FloatingWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var view: TextView? = null

    /** 识别状态更新（可能来自采集帧线程）：编组回主线程后应用到手柄文字。 */
    private val onStateChanged: (UiState) -> Unit = { state ->
        mainHandler.post { applyStateText(state) }
    }

    /** 挂载收起态手柄；重复调用安全。 */
    fun show() {
        if (view != null) return
        val handle = buildHandle()
        try {
            windowManager.addView(handle, layoutParams())
            view = handle
            applyStateText(UiStateSignal.status) // 重建时以当前识别状态初始化
            UiStateSignal.addListener(onStateChanged)
            Log.i(TAG, "悬浮窗已挂载（状态：${UiStateSignal.status.label}）")
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗挂载失败", e)
        }
    }

    /** 整窗移除：不可见且不占任何触摸（A4）；重复调用安全。 */
    fun hide() {
        val current = view ?: return
        UiStateSignal.removeListener(onStateChanged)
        try {
            windowManager.removeView(current)
            Log.i(TAG, "悬浮窗已移除")
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗移除失败", e)
        } finally {
            view = null
        }
    }

    private fun buildHandle(): TextView = TextView(context).apply {
        textSize = 12f // sp
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xB3000000.toInt()) // 半透明黑
        }
    }

    /** 状态文字（FR-07 展示口径的 M1 部分：当前状态；动作部分随 M3 扩展）；仅主线程调用。 */
    private fun applyStateText(state: UiState) {
        val handle = view ?: return
        handle.text = context.getString(R.string.floating_status_text, state.label)
    }

    /** 紧凑只包内容；默认停靠右上角（M3 再做拖动 / 贴边 / 持久化）。 */
    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(120)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "MM-Floating"
    }
}
