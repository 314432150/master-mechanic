package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.mastermechanic.R

/**
 * 悬浮窗容器（FR-07 / ADR-004 的 M0 壳）：收起态紧凑手柄。
 *
 * - 无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY），由无障碍服务挂载，不引入额外授权；
 * - M0 无交互：整窗「不可触摸 + 不可聚焦」，触摸恒穿透，不拦截下方游戏操作（A5）；
 * - 可见性由调用方按前台信号驱动：非前台时整窗移除（不可见且不占触摸），回前台重建（A4）；
 * - 仅主线程调用（调用源：无障碍服务回调与窗口事件，均在主线程）。
 */
class FloatingWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: View? = null

    /** 挂载收起态手柄；重复调用安全。 */
    fun show() {
        if (view != null) return
        val handle = buildHandle()
        try {
            windowManager.addView(handle, layoutParams())
            view = handle
            Log.i(TAG, "悬浮窗已挂载")
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗挂载失败", e)
        }
    }

    /** 整窗移除：不可见且不占任何触摸（A4）；重复调用安全。 */
    fun hide() {
        val current = view ?: return
        try {
            windowManager.removeView(current)
            Log.i(TAG, "悬浮窗已移除")
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗移除失败", e)
        } finally {
            view = null
        }
    }

    private fun buildHandle(): View = TextView(context).apply {
        text = context.getString(R.string.floating_handle_text)
        textSize = 12f // sp
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xB3000000.toInt()) // 半透明黑
        }
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
