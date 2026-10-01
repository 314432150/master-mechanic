package com.example.mastermechanic.floating

import android.content.Context
import android.view.WindowManager

/** 屏幕像素尺寸。 */
data class ScreenSpec(val width: Int, val height: Int)

/**
 * 屏幕尺寸读取（T3-4）：悬浮窗定位与位置持久化**共用同一来源**。
 *
 * 红线 4：尺寸一律在**运行时**从系统读取，代码里不出现任何具体分辨率 / 密度数值。
 * 走 `currentWindowMetrics`（API 30+，返回整块显示区域）—— minSdk 34 起不再需要 `getRealSize` 回退。
 */
object FloatingScreen {

    fun spec(context: Context): ScreenSpec {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = windowManager.currentWindowMetrics.bounds
        return ScreenSpec(bounds.width(), bounds.height())
    }
}
