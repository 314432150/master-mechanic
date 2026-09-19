package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/** 屏幕像素尺寸。 */
data class ScreenSpec(val width: Int, val height: Int)

/**
 * 屏幕尺寸读取（T3-4）：悬浮窗定位与位置持久化**共用同一来源**。
 *
 * 红线 4：尺寸一律在**运行时**从系统读取，代码里不出现任何具体分辨率 / 密度数值。
 * 主路径用 `currentWindowMetrics`（API 30+，返回整块显示区域）；API 28 / 29 回退到 `getRealSize`
 * （`requirements.md` §1.3：Android 9.0 起）。
 */
object FloatingScreen {

    fun spec(context: Context): ScreenSpec {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenSpec(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            ScreenSpec(point.x, point.y)
        }
    }
}
