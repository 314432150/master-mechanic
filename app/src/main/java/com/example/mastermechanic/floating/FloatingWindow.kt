package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mastermechanic.R
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolConfigStore
import com.example.mastermechanic.patrol.PatrolRequestSignal

/**
 * 悬浮窗（FR-07 / ADR-004）：**两个窗口**——
 *
 * 1. **标签窗**（不可触摸 `FLAG_NOT_TOUCHABLE`，恒穿透）：显示「状态：X」与「动作：Y」（无动作时不显示第二行），
 *    完整落在屏内，保证文字可读；
 * 2. **手柄窗**（可触摸）：紧凑胶丸「[floating_handle_text]」，贴边**仅露出约 40% 宽度**；
 *    按住可拖动（位移超过系统触摸滑动阈值才算拖动），抬手吸附最近边缘并持久化位置；
 *    单击展开 / 收起菜单；菜单项「开始巡查」只**发出请求事件**（M4 消费），M3 不产生任何游戏点击。
 *
 * 触摸口径（2026-09-13 用户确认）：ADR-004 第 2 / 5 条——**可触摸区域只吸附在手柄这一块胶丸上**，
 * 状态标签恒穿透；"紧凑只包内容"正是为了让被吞掉的触摸范围尽量小。
 *
 * 可见性由调用方按前台信号驱动（非前台整窗移除、回前台以收起态重建）；本类自行编组到主线程。
 */
class FloatingWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 拖动阈值：在目标设备上运行时读取（FR-07；红线 4：不写死常量）。 */
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---- 标签窗（恒穿透）----
    private var label: LinearLayout? = null
    private var statusText: TextView? = null
    private var actionText: TextView? = null

    // ---- 手柄窗（可触摸）----
    private var panel: LinearLayout? = null
    private var menu: LinearLayout? = null
    private var reasonText: TextView? = null

    private var position: FloatingPosition = FloatingPosition.DEFAULT
    private var expanded = false

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0

    /** 识别状态更新（可能来自采集帧线程）：编组回主线程后更新标签。 */
    private val onStateChanged: (UiState) -> Unit = { state ->
        mainHandler.post { applyStatusText(state) }
    }

    /** 流程层写入的当前动作（M4）：无动作时不显示第二行。 */
    private val onActionChanged: (String?) -> Unit = { action ->
        mainHandler.post { applyActionText(action) }
    }

    /** 挂载标签窗 + 手柄窗（收起态）；重复调用安全。 */
    fun show() {
        if (panel != null) return
        val screen = FloatingScreen.spec(context)
        val loaded = FloatingPositionStore.loadOrRecover(
            FloatingPositionStore.positionFile(context),
            screen.width,
            screen.height,
        )
        loaded.recoveredReason?.let { MmLog.w(TAG, "悬浮窗位置：$it") }
        position = loaded.position
        expanded = false
        dragging = false

        val labelView = buildLabel()
        val panelView = buildPanel()
        // 先在本地测一次尺寸：把初始位置算好再挂载——否则窗口会先在屏幕左上角闪现一帧（贴边位置尚未生效）
        measureSelf(panelView)
        measureSelf(labelView)
        val initial = computeOffsets(
            panelView.measuredWidth,
            panelView.measuredHeight,
            labelView.measuredWidth,
            labelView.measuredHeight,
        )
        var panelAdded = false
        try {
            windowManager.addView(
                panelView,
                panelParams().apply {
                    x = initial.panelX
                    y = initial.panelY
                },
            )
            panelAdded = true
            windowManager.addView(
                labelView,
                labelParams().apply {
                    x = initial.labelX
                    y = initial.labelY
                },
            )
        } catch (e: Exception) {
            MmLog.w(TAG, "悬浮窗挂载失败", e)
            if (panelAdded) runCatching { windowManager.removeView(panelView) }
            return
        }
        label = labelView
        panel = panelView
        // 重建时以当前值初始化（监听只覆盖后续变化）
        applyStatusText(UiStateSignal.status)
        applyActionText(FloatingActionSignal.action)
        UiStateSignal.addListener(onStateChanged)
        FloatingActionSignal.addListener(onActionChanged)
        panelView.post { applyPositions() }
        MmLog.i(
            TAG,
            "悬浮窗已挂载（停靠 ${position.side.token}，纵向比例 ${position.yRatio}；" +
                "手柄可见宽度约 ${(FloatingPosition.REVEAL_RATIO * 100).toInt()}%）",
        )
    }

    /** 整窗移除：不可见且不占任何触摸；回前台以**收起态**重建（FR-07）。重复调用安全。 */
    fun hide() {
        val panelView = panel ?: return
        UiStateSignal.removeListener(onStateChanged)
        FloatingActionSignal.removeListener(onActionChanged)
        label?.let { view -> runCatching { windowManager.removeView(view) } }
        runCatching { windowManager.removeView(panelView) }
        MmLog.i(TAG, "悬浮窗已移除")
        label = null
        statusText = null
        actionText = null
        panel = null
        menu = null
        reasonText = null
        expanded = false
        dragging = false
    }

    // ---- 视图构建 ----

    private fun buildLabel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = pillBackground(PANEL_COLOR)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        statusText = textView(12f, Color.WHITE).also { addView(it) }
        actionText = textView(11f, ACTION_COLOR).also {
            it.visibility = View.GONE
            addView(it)
        }
    }

    private fun buildPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = pillBackground(PANEL_COLOR)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(handleView())
        reasonText = textView(11f, REASON_COLOR).also {
            it.visibility = View.GONE
            it.maxWidth = dp(REASON_MAX_WIDTH_DP)
            addView(it)
        }
        menu = buildMenu().also { addView(it) }
        setOnClickListener { toggleExpanded() }
        setOnTouchListener(::onPanelTouch)
    }

    private fun handleView(): TextView = textView(13f, Color.WHITE).apply {
        text = context.getString(R.string.floating_handle_text)
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.floating_handle_desc)
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    private fun buildMenu(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(menuItem(R.string.floating_menu_start_patrol) { onStartPatrol() })
    }

    private fun menuItem(textRes: Int, onClick: () -> Unit): TextView =
        textView(12f, Color.WHITE).apply {
            text = context.getString(textRes)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun textView(sizeSp: Float, color: Int): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
    }

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    // ---- 触摸：拖动 / 单击（FR-07）----

    /**
     * 手柄窗触摸处理：**抬手时**裁决"拖动还是单击"——
     * 位移超过系统触摸滑动阈值 = 拖动（跟手移动），未超过 = 单击（展开 / 收起）。
     * 不用"位移是否非零"判断：手指轻微抖动不应让菜单永远展不开。
     */
    private fun onPanelTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                val params = view.layoutParams as WindowManager.LayoutParams
                downWindowX = params.x
                downWindowY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    dragging = FloatingGesture.isDrag(
                        event.rawX - downRawX,
                        event.rawY - downRawY,
                        touchSlop,
                    )
                }
                if (dragging) {
                    val screen = FloatingScreen.spec(context)
                    val x = FloatingLayout.clampInside(
                        downWindowX + (event.rawX - downRawX).toInt(),
                        view.width,
                        screen.width,
                    )
                    val y = FloatingLayout.clampInside(
                        downWindowY + (event.rawY - downRawY).toInt(),
                        view.height,
                        screen.height,
                    )
                    place(view, x, y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    snapAndPersist()
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    /** 抬手吸附最近的边缘并持久化位置（FR-07 贴边 / 位置持久化）。 */
    private fun snapAndPersist() {
        val panelView = panel ?: return
        val screen = FloatingScreen.spec(context)
        val params = panelView.layoutParams as WindowManager.LayoutParams
        position = FloatingPosition.snap(
            windowCenterX = params.x + panelView.width / 2f,
            windowTopY = params.y,
            screenWidth = screen.width,
            screenHeight = screen.height,
        )
        applyPositions()
        FloatingPositionStore.save(context, position, screen.width, screen.height)
        MmLog.i(
            TAG,
            "悬浮窗拖动结束：吸附 ${position.side.token} 边缘，纵向比例 ${position.yRatio}（已持久化）",
        )
    }

    /** 单击手柄：展开 / 收起菜单（展开本身不执行任何操作）。 */
    private fun toggleExpanded() {
        val panelView = panel ?: return
        expanded = !expanded
        if (expanded) {
            reasonText?.text = ""
            reasonText?.visibility = View.GONE
        }
        menu?.visibility = if (expanded) View.VISIBLE else View.GONE
        panelView.post { applyPositions() }
        MmLog.i(TAG, if (expanded) "悬浮窗菜单已展开（等待用户选择）" else "悬浮窗已收起")
    }

    /** 收起（操作成功结束后立即收起，FR-07 收起时机）。 */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        menu?.visibility = View.GONE
        reasonText?.visibility = View.GONE
        panel?.post { applyPositions() }
        MmLog.i(TAG, "悬浮窗已收起（操作成功结束）")
    }

    /** 保持展开并显示原因（失败时不静默，FR-07 收起时机 / §1.3）。 */
    private fun showReason(reason: String) {
        val view = reasonText ?: return
        view.text = reason
        view.visibility = View.VISIBLE
        expanded = true
        menu?.visibility = View.VISIBLE
        panel?.post { applyPositions() }
    }

    /**
     * 菜单「开始巡查」：先检查前置条件——
     * ① 采集会话已建立；② 巡查配置非空且可读。
     * 任一不满足 → **保持展开并显示原因**；都满足 → 发出「用户请求开始巡查」事件（M4 消费）并收起。
     */
    private fun onStartPatrol() {
        val reason = startBlockReason()
        if (reason != null) {
            MmLog.w(TAG, "开始巡查被拒绝：$reason")
            showReason(reason)
            return
        }
        val count = PatrolRequestSignal.request(SystemClock.elapsedRealtime())
        MmLog.i(TAG, "已发出「用户请求开始巡查」（第 $count 次）；本版本不执行流程、不产生点击")
        collapse()
    }

    private fun startBlockReason(): String? {
        if (!CaptureSessionSignal.isActive) {
            return context.getString(R.string.floating_reason_no_session)
        }
        val config = try {
            PatrolConfigStore.load(context)
        } catch (e: IllegalArgumentException) {
            return context.getString(
                R.string.floating_reason_config_broken,
                e.message ?: e.javaClass.simpleName,
            )
        }
        if (config == null || config.isEmpty) {
            return context.getString(R.string.floating_reason_config_empty)
        }
        return null
    }

    // ---- 定位 ----

    /** 两个窗口各自的偏移（参数均已按当前屏与当前位置算好）。 */
    private data class Offsets(val panelX: Int, val panelY: Int, val labelX: Int, val labelY: Int)

    /**
     * 位置换算（纯计算，不依赖视图是否已挂载）：标签窗始终跟着手柄 / 面板走——同一停靠侧，
     * 贴在可见部分的内侧、纵向放在面板上方（贴顶时改放下方）。
     */
    private fun computeOffsets(
        panelWidth: Int,
        panelHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
    ): Offsets {
        val screen = FloatingScreen.spec(context)
        val gap = dp(GAP_DP)
        val panelY = FloatingLayout.y(position.yRatio, panelHeight, screen.height)
        val panelX = if (expanded) {
            // 展开态临时完全进屏：否则菜单会被屏幕边缘裁掉（收起后恢复贴边）
            FloatingLayout.clampInside(
                FloatingLayout.handleX(position.side, panelWidth, screen.width),
                panelWidth,
                screen.width,
            )
        } else {
            FloatingLayout.handleX(position.side, panelWidth, screen.width)
        }
        return Offsets(
            panelX = panelX,
            panelY = panelY,
            labelX = FloatingLayout.labelX(
                position.side,
                panelX,
                panelWidth,
                labelWidth,
                screen.width,
                gap,
            ),
            labelY = FloatingLayout.labelY(panelY, labelHeight, panelHeight, screen.height, gap),
        )
    }

    /** 按当前控件尺寸重新落位（尺寸未就绪时跳过，等下一次触发）。 */
    private fun applyPositions() {
        val panelView = panel ?: return
        val labelView = label ?: return
        if (panelView.width == 0 || labelView.width == 0) return
        val offsets = computeOffsets(
            panelView.width,
            panelView.height,
            labelView.width,
            labelView.height,
        )
        place(panelView, offsets.panelX, offsets.panelY)
        place(labelView, offsets.labelX, offsets.labelY)
    }

    /** 挂载前的手工测量（WRAP_CONTENT 窗口的固有尺寸）。 */
    private fun measureSelf(view: View) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
    }

    private fun place(view: View, x: Int, y: Int) {
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun applyStatusText(state: UiState) {
        statusText?.text = context.getString(R.string.floating_status_text, state.label)
    }

    private fun applyActionText(action: String?) {
        val view = actionText ?: return
        if (action == null) {
            view.visibility = View.GONE
        } else {
            view.text = context.getString(R.string.floating_action_text, action)
            view.visibility = View.VISIBLE
        }
    }

    private fun panelParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 可触摸（FR-07 交互）；不聚焦，避免抢占输入法 / 焦点。
        // LAYOUT_NO_LIMITS：允许窗口部分移出屏幕（贴边只露出约 40%）；LAYOUT_IN_SCREEN：以整块屏幕为坐标原点。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun labelParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 标签恒不可触摸（ADR-004 第 2 条）：展示区域永不拦截游戏操作。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "MM-Floating"

        /** 半透明黑衬底（沿用 M0 的观感）。 */
        const val PANEL_COLOR = 0xB3000000.toInt()

        /** 动作文字色（深底必须显式给亮色，否则亮色主题下不可读）。 */
        const val ACTION_COLOR = 0xCCFFFFFF.toInt()

        /** 失败原因文字色（浅红；深底上的错误提示）。 */
        const val REASON_COLOR = 0xFFFF8A80.toInt()

        /** 手柄与标签之间的间隙（dp）。 */
        const val GAP_DP = 6

        /** 失败原因文字的最大宽度（dp）：避免窗口被长文案撑宽（FR-07 尺寸紧凑）。 */
        const val REASON_MAX_WIDTH_DP = 180
    }
}
