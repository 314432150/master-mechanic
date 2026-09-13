package com.example.mastermechanic.floating

import android.content.Context
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
 * 悬浮窗（FR-07 / ADR-004）：**两个部件、两个窗口**——
 *
 * 1. **状态标签窗**：显示「状态：X」与「动作：Y」（无动作时不显示第二行），**可拖动**、默认横屏底部居中；
 * 2. **手柄窗**：紧凑的**扁半圆**，贴边时一半移出屏幕；按住可拖动（位移超过系统触摸滑动阈值才算拖动），
 *    抬手吸附最近边缘并持久化；单击展开 / 收起菜单；菜单项只**发出请求事件**（M4 消费），
 *    M3 不产生任何游戏点击。
 *
 * 外观（2026-09-14 用户口径）：黑底游戏画面上必须看得清 → **近白底 + 亮蓝描边 + 深色文字**
 * （原先的半透明深色在暗画面上"就是一块更黑的东西"，看不清）。
 *
 * 触摸口径（2026-09-14 修订，见 ADR-004「修订」）：**状态标签也允许拦截自身覆盖范围内的触摸**
 * （因为它要能拖动）；"不拦截游戏操作"由**尺寸紧凑**承接——两个部件都只吞自己那一小块矩形。
 *
 * 可见性由调用方按前台信号驱动（非前台整窗移除、回前台以收起态重建）；本类自行编组到主线程。
 */
class FloatingWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 拖动阈值：在目标设备上运行时读取（FR-07；红线 4：不写死常量）。 */
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---- 状态标签窗（可拖动）----
    private var label: LinearLayout? = null
    private var statusText: TextView? = null
    private var actionText: TextView? = null

    // ---- 手柄窗（可触摸）----
    private var panel: LinearLayout? = null
    private var handle: TextView? = null
    private var menu: LinearLayout? = null
    private var reasonText: TextView? = null

    private var positions: FloatingPositions = FloatingPositions.DEFAULT
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

    /** 挂载两个窗口（收起态）；重复调用安全。 */
    fun show() {
        if (panel != null) return
        val screen = FloatingScreen.spec(context)
        val loaded = FloatingPositionStore.loadOrRecover(
            FloatingPositionStore.positionFile(context),
            screen.width,
            screen.height,
        )
        loaded.recoveredReason?.let { MmLog.w(TAG, "悬浮窗位置：$it") }
        positions = loaded.positions
        expanded = false
        dragging = false

        val labelView = buildLabel()
        val panelView = buildPanel()
        // 先登记字段（applyExpandedState / applyPositions 都依赖它们），再定形状与尺寸
        label = labelView
        panel = panelView
        applyExpandedState()
        // 挂载前先手工测一次尺寸：把初始位置算好再 addView，避免窗口先在屏幕左上角闪现一帧
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
            label = null
            panel = null
            return
        }
        applyExpandedState() // 此时 layoutParams 已就绪：把收起态的固定宽度真正应用上
        // 重建时以当前值初始化（监听只覆盖后续变化）
        applyStatusText(UiStateSignal.status)
        applyActionText(FloatingActionSignal.action)
        UiStateSignal.addListener(onStateChanged)
        FloatingActionSignal.addListener(onActionChanged)
        panelView.post { applyPositions() }
        MmLog.i(
            TAG,
            "悬浮窗已挂载（手柄停靠 ${positions.handle.side.token}，状态标签中心 " +
                "${positions.label.xRatio}x${positions.label.yRatio}）",
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
        handle = null
        menu = null
        reasonText = null
        expanded = false
        dragging = false
    }

    // ---- 视图构建 ----

    private fun buildLabel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        statusText = textView(12f, TEXT_COLOR).also { addView(it) }
        actionText = textView(11f, ACTION_COLOR).also {
            it.visibility = View.GONE
            addView(it)
        }
        background = roundedBackground(dp(LABEL_CORNER_DP))
        setOnClickListener { /* 标签不承担动作：点它不做任何事（拖动才是它的交互） */ }
        setOnTouchListener(::onLabelTouch)
    }

    private fun buildPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        handle = textView(14f, TEXT_COLOR).apply {
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.floating_handle_desc)
        }
        addView(handle)
        reasonText = textView(11f, REASON_COLOR).also {
            it.visibility = View.GONE
            it.maxWidth = dp(REASON_MAX_WIDTH_DP)
            it.setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(it)
        }
        menu = buildMenu().also { addView(it) }
        setOnClickListener { toggleExpanded() }
        setOnTouchListener(::onPanelTouch)
    }

    private fun buildMenu(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(menuItem(R.string.floating_menu_start_patrol) { onStartPatrol() })
    }

    private fun menuItem(textRes: Int, onClick: () -> Unit): TextView =
        textView(12f, TEXT_COLOR).apply {
            text = context.getString(textRes)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun textView(sizeSp: Float, color: Int): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
    }

    /** 常规圆角底（四角都圆）：状态标签与展开态面板用。 */
    private fun roundedBackground(radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx.toFloat()
        setColor(SURFACE_COLOR)
        setStroke(dp(STROKE_DP), STROKE_COLOR)
    }

    /** 贴边手柄底：**贴屏幕边缘的一侧是直角，朝向屏幕中心的一侧是大圆角**（扁半圆）。 */
    private fun edgeBackground(side: FloatingSide, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        val r = radiusPx.toFloat()
        // 顺序：topLeft、topRight、bottomRight、bottomLeft
        cornerRadii = when (side) {
            FloatingSide.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            FloatingSide.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        setColor(SURFACE_COLOR)
        setStroke(dp(STROKE_DP), STROKE_COLOR)
    }

    /** 收起 / 展开：形状、尺寸、文案、菜单可见性一次性切齐（T3-7 的扁半圆与收窄就在这里）。 */
    private fun applyExpandedState() {
        val panelView = panel ?: return
        val handleView = handle ?: return
        if (expanded) {
            handleView.text = context.getString(R.string.floating_handle_text)
            handleView.setPadding(dp(16), dp(8), dp(16), dp(8))
            handleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            panelView.setPadding(dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP))
            panelView.background = roundedBackground(dp(MENU_CORNER_DP))
            setPanelWidth(WindowManager.LayoutParams.WRAP_CONTENT)
        } else {
            // 收起态：只留一块贴边扁半圆 —— 无文字、固定小尺寸（一半在屏外，可见约 20dp）
            handleView.text = ""
            handleView.setPadding(0, 0, 0, 0)
            handleView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(HANDLE_HEIGHT_DP),
            )
            panelView.setPadding(0, 0, 0, 0)
            panelView.background = edgeBackground(positions.handle.side, dp(HANDLE_CORNER_DP))
            setPanelWidth(dp(HANDLE_WIDTH_DP))
        }
        menu?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun setPanelWidth(width: Int) {
        val panelView = panel ?: return
        val params = panelView.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.width == width) return
        params.width = width
        runCatching { windowManager.updateViewLayout(panelView, params) }
    }

    // ---- 触摸：手柄（拖动 / 单击）----

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
                    place(
                        view,
                        FloatingLayout.clampInside(
                            downWindowX + (event.rawX - downRawX).toInt(),
                            view.width,
                            screen.width,
                        ),
                        FloatingLayout.clampInside(
                            downWindowY + (event.rawY - downRawY).toInt(),
                            view.height,
                            screen.height,
                        ),
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    snapHandleAndPersist()
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    /** 抬手吸附最近的边缘并持久化（FR-07 贴边 / 位置持久化）。 */
    private fun snapHandleAndPersist() {
        val panelView = panel ?: return
        val screen = FloatingScreen.spec(context)
        val params = panelView.layoutParams as WindowManager.LayoutParams
        positions = positions.copy(
            handle = FloatingPosition.snap(
                windowCenterX = params.x + panelView.width / 2f,
                windowTopY = params.y,
                screenWidth = screen.width,
                screenHeight = screen.height,
            ),
        )
        applyExpandedState() // 换边后重画"外侧圆角"
        applyPositions()
        persist(screen, "手柄拖动结束，吸附 ${positions.handle.side.token} 边缘")
    }

    // ---- 触摸：状态标签（拖动）----

    private fun onLabelTouch(view: View, event: MotionEvent): Boolean {
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
                    place(
                        view,
                        FloatingLayout.clampInside(
                            downWindowX + (event.rawX - downRawX).toInt(),
                            view.width,
                            screen.width,
                        ),
                        FloatingLayout.clampInside(
                            downWindowY + (event.rawY - downRawY).toInt(),
                            view.height,
                            screen.height,
                        ),
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val screen = FloatingScreen.spec(context)
                    val params = view.layoutParams as WindowManager.LayoutParams
                    positions = positions.copy(
                        label = FloatingLayout.snapLabelCenter(
                            windowX = params.x,
                            windowY = params.y,
                            labelWidth = view.width,
                            labelHeight = view.height,
                            screenWidth = screen.width,
                            screenHeight = screen.height,
                        ),
                    )
                    applyPositions()
                    persist(screen, "状态标签拖动结束（中心 ${positions.label.xRatio}x${positions.label.yRatio}）")
                } else {
                    view.performClick()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun persist(screen: ScreenSpec, reason: String) {
        FloatingPositionStore.save(context, positions, screen.width, screen.height)
        MmLog.i(TAG, "悬浮窗位置已持久化：$reason")
    }

    // ---- 菜单 ----

    /** 单击手柄：展开 / 收起菜单（展开本身不执行任何操作）。 */
    private fun toggleExpanded() {
        val panelView = panel ?: return
        expanded = !expanded
        if (expanded) {
            reasonText?.text = ""
            reasonText?.visibility = View.GONE
        }
        applyExpandedState()
        panelView.post { applyPositions() }
        MmLog.i(TAG, if (expanded) "悬浮窗菜单已展开（等待用户选择）" else "悬浮窗已收起")
    }

    /** 收起（操作成功结束后立即收起，FR-07 收起时机）。 */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        reasonText?.visibility = View.GONE
        applyExpandedState()
        panel?.post { applyPositions() }
        MmLog.i(TAG, "悬浮窗已收起（操作成功结束）")
    }

    /** 保持展开并显示原因（失败时不静默，FR-07 收起时机 / §1.3）。 */
    private fun showReason(reason: String) {
        val view = reasonText ?: return
        view.text = reason
        view.visibility = View.VISIBLE
        expanded = true
        applyExpandedState()
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

    private fun computeOffsets(
        panelWidth: Int,
        panelHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
    ): Offsets {
        val screen = FloatingScreen.spec(context)
        val panelY = FloatingLayout.y(positions.handle.yRatio, panelHeight, screen.height)
        val panelX = if (expanded) {
            // 展开态临时完全进屏：否则菜单会被屏幕边缘裁掉（收起后恢复贴边）
            FloatingLayout.clampInside(
                FloatingLayout.handleX(positions.handle.side, panelWidth, screen.width),
                panelWidth,
                screen.width,
            )
        } else {
            FloatingLayout.handleX(positions.handle.side, panelWidth, screen.width)
        }
        val margin = dp(LABEL_MARGIN_DP)
        return Offsets(
            panelX = panelX,
            panelY = panelY,
            labelX = FloatingLayout.labelXByCenter(
                positions.label.xRatio,
                labelWidth,
                screen.width,
                margin,
            ),
            labelY = FloatingLayout.labelYByCenter(
                positions.label.yRatio,
                labelHeight,
                screen.height,
                margin,
            ),
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
        // LAYOUT_NO_LIMITS：允许窗口部分移出屏幕（贴边只露一半）；LAYOUT_IN_SCREEN：以整块屏幕为坐标原点。
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
        // 可触摸（2026-09-14：状态标签也要能拖动；ADR-004 第 2 条已修订）
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

        /**
         * 近白衬底 + 亮蓝描边 + 深色文字（2026-09-14 用户口径）：
         * 游戏画面多为深色，原先的"半透明黑"在黑底上等于隐形；亮色才看得清。
         */
        const val SURFACE_COLOR = 0xF0FFFFFF.toInt()
        const val STROKE_COLOR = 0xFF1E88E5.toInt()
        const val TEXT_COLOR = 0xFF1B1B1B.toInt()

        /** 动作文字（深蓝，与状态文字区分）。 */
        const val ACTION_COLOR = 0xFF0D47A1.toInt()

        /** 失败原因（浅底上用深红）。 */
        const val REASON_COLOR = 0xFFC62828.toInt()

        const val STROKE_DP = 2

        /** 收起态手柄尺寸（dp）：一半在屏外，可见约 20dp —— 贴边的扁半圆。 */
        const val HANDLE_WIDTH_DP = 40
        const val HANDLE_HEIGHT_DP = 36
        const val HANDLE_CORNER_DP = 18

        /** 展开态面板（四角圆角 + 内边距）。 */
        const val MENU_CORNER_DP = 14
        const val PANEL_PADDING_DP = 4

        /** 状态标签圆角与距屏幕边缘的边距（dp）。 */
        const val LABEL_CORNER_DP = 12
        const val LABEL_MARGIN_DP = 12

        /** 失败原因文字的最大宽度（dp）：避免窗口被长文案撑宽（FR-07 尺寸紧凑）。 */
        const val REASON_MAX_WIDTH_DP = 200
    }
}
