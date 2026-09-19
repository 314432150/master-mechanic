package com.example.mastermechanic.notify

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolRequestSignal

/**
 * 菜单操作的浮窗式提示（2026-09-17 用户口径）。
 *
 * 实现：用 WindowManager 直接挂一个浮窗子视图，避开 Android Toast 系统在 API 33+ 收紧导致的不显示问题。
 *
 * **关键点**：宽度 / 高度必须**显式按像素**写进 LayoutParams（不能用 WRAP_CONTENT —— 在 WindowManager 上
 * 经常被测成 0×0、addView 成功但 view 不可见）。先手动 `view.measure(...)` 让 view 有 measuredWidth /
 * measuredHeight，再把这两个数值当 width / height 传给 LayoutParams。
 */
object FloatingNotifier {

    private const val TAG = "MM-Notifier"
    private const val SHOW_DURATION_MS = 2000L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var installed = false
    @Volatile private var appContext: Context? = null
    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var overlayView: View? = null
    @Volatile private var overlayText: TextView? = null
    @Volatile private var overlayAttached = false
    @Volatile private var overlayWidth: Int = 0
    @Volatile private var overlayHeight: Int = 0

    private val listener: (PatrolRequestSignal.Request) -> Unit = { request ->
        mainHandler.post { showOverlay(request) }
    }

    /** 只做"注册监听 + 建好视图"：此时**不能**去拿 WindowManager（见 [bindWindow]）。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        appContext = app
        val view = buildOverlayView(app)
        overlayView = view
        overlayText = view.findViewById(android.R.id.text1)
        PatrolRequestSignal.addListener(listener)
        MmLog.i(TAG, "浮窗式提示已就绪（等待无障碍服务上下文）")
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        PatrolRequestSignal.removeListener(listener)
        unbindWindow()
        overlayText = null
        overlayView = null
        appContext = null
        MmLog.i(TAG, "浮窗式提示已卸载")
    }

    /**
     * 绑定**无障碍服务上下文**（服务连接时调用）。
     *
     * 为什么必须用服务上下文：`TYPE_ACCESSIBILITY_OVERLAY` 只认"无障碍服务自己的"窗口 token。
     * 从 Application 上下文 `getSystemService(WINDOW_SERVICE)` 拿到的 WindowManager，token 是 null，
     * addView 会抛 `BadTokenException: Unable to add window -- token null is not valid`
     * （2026-09-19 真机 logcat 已证）。所以窗口只能从
     * [android.accessibilityservice.AccessibilityService] 的上下文里取。
     */
    fun bindWindow(serviceContext: Context) {
        if (windowManager != null) return
        val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        windowManager = wm
        MmLog.i(TAG, if (wm != null) "浮窗式提示已绑定服务上下文" else "浮窗式提示绑定服务上下文失败：无 WINDOW_SERVICE")
    }

    /** 服务断开 / 中断 / 销毁时解绑：先摘窗口，再丢弃 WindowManager（下次连接重新绑定）。 */
    fun unbindWindow() {
        removeOverlayNow()
        windowManager = null
    }

    private fun showOverlay(request: PatrolRequestSignal.Request) {
        val view = overlayView ?: return
        val text = overlayText ?: return
        val wm = windowManager ?: run {
            MmLog.w(TAG, "浮窗式提示未绑定服务上下文，丢弃本次提示：${request.briefText()}")
            return
        }
        val ctx = appContext ?: return

        text.text = request.briefText()
        // 文字变了之后**重新 measure** —— 之前装时测的尺寸可能不够装新文字
        val w = ctx.resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        overlayWidth = view.measuredWidth
        overlayHeight = view.measuredHeight
        val params = overlayParams(ctx)
        MmLog.i(TAG, "准备挂载浮窗 ${overlayWidth}x${overlayHeight} @ ${params.gravity}")

        try {
            if (!overlayAttached) {
                wm.addView(view, params)
                overlayAttached = true
                MmLog.i(TAG, "addView 成功：${request.briefText()}")
            } else {
                runCatching { wm.updateViewLayout(view, params) }
                MmLog.i(TAG, "updateViewLayout 成功：${request.briefText()}")
            }
        } catch (e: WindowManager.BadTokenException) {
            // 单独 catch：这是最常见的"权限 / 类型不符"，被 try-all 吞掉就完全看不到原因
            Log.e(TAG, "BadTokenException: ${e.message}", e)
            MmLog.e(TAG, "浮窗式提示挂载失败 BadTokenException：${e.message}", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "addView 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            MmLog.e(TAG, "浮窗式提示挂载失败 ${e.javaClass.simpleName}：${e.message}", e)
            return
        }

        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, SHOW_DURATION_MS)
    }

    private val hideRunnable = Runnable { removeOverlayNow() }

    private fun removeOverlayNow() {
        mainHandler.removeCallbacks(hideRunnable)
        val view = overlayView ?: return
        val wm = windowManager ?: return
        if (!overlayAttached) return
        runCatching { wm.removeView(view) }
        overlayAttached = false
    }

    private fun overlayParams(ctx: Context): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            // 显式按像素写 width / height —— WRAP_CONTENT 在 WindowManager 上经常被测成 0×0
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不抢触摸（提示只是给你看一眼，不能拦截游戏触摸）；不聚焦。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 贴底（2026-09-19 用户口径）：只留 12dp 视觉间距，再叠上系统栏高度，
            // 否则在带导航栏 / 手势条的机型上会被盖住。
            y = bottomInsetPx(ctx) + dpPx(ctx, 12)
        }

    /**
     * 系统栏底部高度（导航栏 / 手势条）。
     *
     * API 30+ 走 `WindowInsets.Type.systemBars()`；API 28/29 读系统资源 `navigation_bar_height`。
     * 取不到一律按 0 处理（退化成"贴屏幕底"，不会因为异常丢提示）。
     */
    private fun bottomInsetPx(ctx: Context): Int = runCatching {
        val wm = windowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
        }
    }.getOrDefault(0)

    private fun buildOverlayView(ctx: Context): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = compactBackground(ctx)
            val hPad = dpPx(ctx, 20)
            val vPad = dpPx(ctx, 10)
            setPadding(hPad, vPad, hPad, vPad)
        }
        val text = TextView(ctx).apply {
            id = android.R.id.text1
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 0.9f)
            isSingleLine = false
        }
        container.addView(text)
        return container
    }

    private fun compactBackground(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(0xCC000000.toInt())
        cornerRadius = dpPx(ctx, 6).toFloat()
    }

    private fun dpPx(ctx: Context, value: Int): Int {
        val density = ctx.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}