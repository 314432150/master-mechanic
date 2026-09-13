package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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
 * 1. **状态标签窗**：半透明黑底 + 白字，显示「状态：X」与「动作：Y」（无动作时不显示第二行）；
 *    **可拖动**、默认横屏底部居中；
 * 2. **手柄窗**：贴边的一条**窄竖条**（半透明琥珀黄 + 朝向屏幕中心的箭头），
 *    **一开始就贴边、拖不动到屏幕中间**（拖动只沿边缘上下走）；单击展开菜单；
 *    菜单项只**发出请求事件**（M4 消费），M3 不产生任何游戏点击。
 *
 * 外观口径（2026-09-14 用户定稿）：状态标签回到**半透明黑底 + 白字**；
 * 手柄用**半透明琥珀黄**、更窄（可见约 14dp）；展开菜单参考 vivo 手机游戏魔盒的形态——
 * 深色半透明圆角面板 + 「图标 + 文案」的功能行。
 *
 * 触摸口径（2026-09-14 修订，见 ADR-004「修订」）：状态标签也允许拦截自身覆盖范围内的触摸
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

    /** 菜单窗是否已挂到 WindowManager（展开 = 挂上，收起 = 摘掉）。 */
    private var menuAttached = false

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

    /** 屏幕尺寸 / 方向变化 → 重新落位（转屏后窗口尺寸可能不变，位置却已失效）。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.post { applyPositions() }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private var displayManager: DisplayManager? = null

    /** 上一次落位日志的内容（内容不变就不重复刷屏）。 */
    private var lastPlacementTrace: String? = null

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
        val menuView = buildMenu()
        // 先登记字段（applyExpandedState / applyPositions 都依赖它们），再定形状与尺寸
        label = labelView
        panel = panelView
        menu = menuView
        applyExpandedState()

        // 收起态尺寸是**常量**，不靠测量：空文字的 MATCH_PARENT 手柄测出来是 0 宽，
        // 会把初始位置算成"贴着屏幕外侧"（真机表现：看不到手柄与标签，直到位置被重置）。
        val panelWidth = dp(HANDLE_WIDTH_DP)
        val panelHeight = dp(HANDLE_HEIGHT_DP)
        measureSelf(labelView)
        val initial = computeOffsets(
            panelWidth,
            panelHeight,
            labelView.measuredWidth,
            labelView.measuredHeight,
            menuWidth = 0, // 挂载时一定是收起态：菜单窗还没挂
            menuHeight = 0,
        )
        var panelAdded = false
        try {
            windowManager.addView(
                panelView,
                panelParams().apply {
                    width = panelWidth
                    height = panelHeight
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
        // 布局变化（文案变长 / 菜单展开）后自动重新落位：不依赖一次性 post 的时序
        attachLayoutRefresh(panelView)
        attachLayoutRefresh(labelView)
        // 屏幕尺寸 / 方向变化后也要重新落位：转屏时窗口尺寸可能不变、位置却已失效
        // （真机踩过：挂载瞬间用的是转屏前的尺寸 → 两个窗口一起跑到屏幕外，只能靠"重置位置"才回来）
        attachDisplayRefresh()
        // 重建时以当前值初始化（监听只覆盖后续变化）
        applyStatusText(UiStateSignal.status)
        applyActionText(FloatingActionSignal.action)
        UiStateSignal.addListener(onStateChanged)
        FloatingActionSignal.addListener(onActionChanged)
        applyPositions()
        MmLog.i(
            TAG,
            "悬浮窗已挂载（手柄停靠 ${positions.handle.side.token}、纵向比例 ${positions.handle.yRatio}；" +
                "状态标签中心 ${positions.label.xRatio}x${positions.label.yRatio}）",
        )
    }

    /** 整窗移除：不可见且不占任何触摸；回前台以**收起态**重建（FR-07）。重复调用安全。 */
    fun hide() {
        val panelView = panel ?: return
        UiStateSignal.removeListener(onStateChanged)
        FloatingActionSignal.removeListener(onActionChanged)
        detachDisplayRefresh()
        lastPlacementTrace = null
        if (menuAttached) {
            menu?.let { view -> runCatching { windowManager.removeView(view) } }
            menuAttached = false
        }
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
        background = labelBackground()
        statusText = textView(12f, LABEL_TEXT_COLOR).also { addView(it) }
        actionText = textView(11f, LABEL_ACTION_COLOR).also {
            it.visibility = View.GONE
            addView(it)
        }
        setOnClickListener { /* 标签不承担动作：点它不做任何事（拖动才是它的交互） */ }
        setOnTouchListener(::onLabelTouch)
    }

    /**
     * 手柄窗 = 贴边窄条**本身**（浅琥珀黄 + 外侧圆角），**形态永不变化**。
     *
     * 2026-09-14 用户口径：位置固定贴在右侧边缘上方三分之一处；不支持拖动；
     * **点击展开菜单后手柄不消失**——所以菜单不再"长"在手柄上（见 [buildMenu]）。
     */
    private fun buildPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        handle = textView(13f, HANDLE_TEXT_COLOR).apply {
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.floating_handle_desc)
        }
        addView(handle)
        background = handleBackground(FloatingPosition.DEFAULT.side)
        setOnClickListener { toggleExpanded() }
    }

    /**
     * 菜单窗 = 独立窗口（深色圆角面板）：与手柄、状态标签同构。
     *
     * **为什么独立成窗**（2026-09-14 用户口径）：展开时手柄必须留在原位不动；
     * 若把菜单做成手柄的"展开形态"，手柄要么被菜单顶走、要么随窗口尺寸变化而抖动。
     * 独立成窗后手柄的尺寸 / 背景 / 触摸行为在整个展开过程中保持不变
     * （也顺手消掉了"展开态改窗口尺寸"这一类时序坑）。
     */
    private fun buildMenu(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP), dp(PANEL_PADDING_DP))
        background = panelBackground()
        addView(
            menuRow(
                glyph = context.getString(R.string.floating_menu_glyph_patrol),
                textRes = R.string.floating_menu_start_patrol,
            ) { onStartPatrol() },
        )
        reasonText = textView(11f, REASON_COLOR).also {
            it.visibility = View.GONE
            it.maxWidth = dp(REASON_MAX_WIDTH_DP)
            it.setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(it)
        }
        // 点菜单面板之外的任何地方 → 收起（FLAG_WATCH_OUTSIDE_TOUCH 送来 ACTION_OUTSIDE）
        setOnTouchListener(::onMenuTouch)
    }

    /** 菜单功能行（参考 vivo 游戏魔盒）：左侧图标 + 文案，整行可点。 */
    private fun menuRow(glyph: String, textRes: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(
                textView(12f, MENU_GLYPH_COLOR).apply {
                    text = glyph
                    setPadding(0, 0, dp(8), 0)
                },
            )
            addView(textView(12f, MENU_TEXT_COLOR).apply { text = context.getString(textRes) })
            setOnClickListener { onClick() }
        }

    private fun textView(sizeSp: Float, color: Int): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
    }

    /** 状态标签底：**半透明黑 + 白字**（2026-09-14 用户口径），描一道极淡白线以便在黑底上看出轮廓。 */
    private fun labelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(LABEL_CORNER_DP).toFloat()
        setColor(LABEL_FILL_COLOR)
        setStroke(dp(1), LABEL_STROKE_COLOR)
    }

    /** 展开态面板底（参考 vivo 游戏魔盒）：深色半透明圆角面板 + 淡琥珀描边。 */
    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(MENU_CORNER_DP).toFloat()
        setColor(MENU_FILL_COLOR)
        setStroke(dp(1), MENU_STROKE_COLOR)
    }

    /**
     * 贴边手柄底：**贴屏幕边缘的一侧是直角，朝向屏幕中心的一侧是大圆角**；
     * 半透明琥珀黄填充（2026-09-14 用户口径）。
     */
    private fun handleBackground(side: FloatingSide): GradientDrawable = GradientDrawable().apply {
        val r = dp(HANDLE_CORNER_DP).toFloat()
        // 顺序：topLeft、topRight、bottomRight、bottomLeft
        cornerRadii = when (side) {
            FloatingSide.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            FloatingSide.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        setColor(HANDLE_FILL_COLOR)
        setStroke(dp(1), HANDLE_STROKE_COLOR)
    }

    /**
     * 展开 / 收起：**只增删菜单窗**。
     *
     * 手柄的形态、尺寸、位置**都不随展开变化**（2026-09-14 用户口径：
     * "点击手柄弹出菜单面板后手柄不要消失"）——它始终是贴边窄条，菜单是独立窗口（[buildMenu]）。
     */
    private fun applyExpandedState() {
        val menuView = menu ?: return
        if (!expanded) {
            reasonText?.visibility = View.GONE
            if (menuAttached) {
                runCatching { windowManager.removeView(menuView) }
                menuAttached = false
            }
            return
        }
        if (menuAttached) return
        // 先量出尺寸再挂：否则首帧会落在 (0,0) 再跳一次
        measureSelf(menuView)
        val (menuX, menuY) = initialMenuOffset(menuView)
        val added = runCatching {
            windowManager.addView(
                menuView,
                menuParams().apply {
                    x = menuX
                    y = menuY
                },
            )
        }.isSuccess
        if (!added) {
            MmLog.w(TAG, "菜单面板挂载失败")
            return
        }
        menuAttached = true
        attachLayoutRefresh(menuView)
    }

    /** 菜单窗的初始偏移：以手柄**当前**落位为基准（展开时手柄一定已经落位）。 */
    private fun initialMenuOffset(menuView: View): Pair<Int, Int> {
        val panelView = panel ?: return 0 to 0
        val params = panelView.layoutParams as WindowManager.LayoutParams
        val screen = FloatingScreen.spec(context)
        val (menuWidth, menuHeight) = viewSize(menuView)
        val x = FloatingLayout.menuX(
            positions.handle.side,
            menuWidth,
            screen.width,
            params.width,
            dp(MENU_MARGIN_DP),
        )
        val y = FloatingLayout.menuY(
            params.y,
            params.height,
            menuHeight,
            screen.height,
            dp(MENU_MARGIN_DP),
        )
        return x to y
    }

    /** 视图尺寸：优先真实布局尺寸，未布局时退回测量值。 */
    private fun viewSize(view: View): Pair<Int, Int> =
        (if (view.width > 0) view.width else view.measuredWidth) to
            (if (view.height > 0) view.height else view.measuredHeight)

    /**
     * 菜单窗触摸：只关心 [MotionEvent.ACTION_OUTSIDE]——**点菜单面板之外任何地方即收起**
     * （2026-09-14 用户口径）。
     *
     * 点在**手柄**上时不算"点别处"：正常路由下那次触摸由手柄窗接走（不会产生 ACTION_OUTSIDE），
     * 这里再做一次命中判断，防的是"手柄半露在屏外"时边界上的 1px 级误差——
     * 否则会和手柄自己的单击切换打架（先收起、再被切换回来）。
     */
    private fun onMenuTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return false
        if (hitsHandle(event.rawX, event.rawY)) return false
        MmLog.i(TAG, "点了菜单面板之外，已收起")
        collapse()
        return false
    }

    /** 触点是否落在手柄窗内（含 2dp 容差）。 */
    private fun hitsHandle(rawX: Float, rawY: Float): Boolean {
        val panelView = panel ?: return false
        val location = IntArray(2)
        panelView.getLocationOnScreen(location)
        val slop = dp(2)
        return rawX >= location[0] - slop && rawX <= location[0] + panelView.width + slop &&
            rawY >= location[1] - slop && rawY <= location[1] + panelView.height + slop
    }

    // ---- 触摸：状态标签（自由拖动）----
    //
    // 手柄一侧**不再有任何拖动逻辑**（2026-09-14 用户口径）：位置固定贴在右侧边缘上方三分之一处，
    // 触摸只由 `setOnClickListener { toggleExpanded() }` 承担（单击展开 / 收起菜单）。

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
        expanded = !expanded
        if (expanded) {
            reasonText?.text = ""
            reasonText?.visibility = View.GONE
        }
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, if (expanded) "悬浮窗菜单已展开（等待用户选择）" else "悬浮窗已收起")
    }

    /** 收起（操作成功结束后立即收起，FR-07 收起时机）。 */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        reasonText?.visibility = View.GONE
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, "悬浮窗已收起（操作成功结束）")
    }

    /** 保持展开并显示原因（失败时不静默，FR-07 收起时机 / §1.3）。 */
    private fun showReason(reason: String) {
        val view = reasonText ?: return
        view.text = reason
        view.visibility = View.VISIBLE
        expanded = true
        applyExpandedState()
        applyPositions()
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

    /** 各窗口的偏移（参数均已按当前屏与当前位置算好）；菜单收起时为 null（窗口根本不存在）。 */
    private data class Offsets(
        val panelX: Int,
        val panelY: Int,
        val labelX: Int,
        val labelY: Int,
        val menuX: Int?,
        val menuY: Int?,
    )

    private fun computeOffsets(
        panelWidth: Int,
        panelHeight: Int,
        labelWidth: Int,
        labelHeight: Int,
        menuWidth: Int,
        menuHeight: Int,
    ): Offsets {
        val screen = FloatingScreen.spec(context)
        val side = positions.handle.side
        val panelY = FloatingLayout.y(positions.handle.yRatio, panelHeight, screen.height)
        // 手柄**永远贴边**（不再有"展开态临时进屏"：菜单已独立成窗，手柄不需要让位）
        val panelX = FloatingLayout.handleX(side, panelWidth, screen.width)
        val margin = dp(LABEL_MARGIN_DP)
        val menuX = if (menuWidth > 0) {
            FloatingLayout.menuX(side, menuWidth, screen.width, panelWidth, dp(MENU_MARGIN_DP))
        } else {
            null
        }
        val menuY = if (menuHeight > 0) {
            FloatingLayout.menuY(panelY, panelHeight, menuHeight, screen.height, dp(MENU_MARGIN_DP))
        } else {
            null
        }
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
            menuX = menuX,
            menuY = menuY,
        )
    }

    /** 重新落位（尺寸未就绪时跳过；布局 / 屏幕变化后会被再次触发）。 */
    private fun applyPositions() {
        val panelView = panel ?: return
        val labelView = label ?: return
        val panelWidth = panelView.width.takeIf { it > 0 } ?: dp(HANDLE_WIDTH_DP)
        val panelHeight = panelView.height.takeIf { it > 0 } ?: dp(HANDLE_HEIGHT_DP)
        if (labelView.width == 0) return
        val menuView = menu?.takeIf { expanded && menuAttached }
        val (menuWidth, menuHeight) = menuView?.let { viewSize(it) } ?: (0 to 0)
        val offset = computeOffsets(
            panelWidth,
            panelHeight,
            labelView.width,
            labelView.height,
            menuWidth,
            menuHeight,
        )
        place(panelView, offset.panelX, offset.panelY)
        place(labelView, offset.labelX, offset.labelY)
        if (menuView != null && offset.menuX != null && offset.menuY != null) {
            place(menuView, offset.menuX, offset.menuY)
        }
        // 取证 / 排障用途：把"用了多大的屏、把各窗口放到了哪"记进日志——
        // 悬浮窗看不见或位置不对时，一行日志就能判断是尺寸错了还是位置值错了（真机排障踩过坑）。
        val screen = FloatingScreen.spec(context)
        val menuTrace = if (menuView != null) {
            " ｜ 菜单 (${offset.menuX},${offset.menuY}) ${menuWidth}x$menuHeight"
        } else {
            ""
        }
        val trace = "屏 ${screen.width}x${screen.height} ｜ 手柄 (${offset.panelX},${offset.panelY})" +
            " ${panelWidth}x$panelHeight ｜ 标签 (${offset.labelX},${offset.labelY})" +
            " ${labelView.width}x${labelView.height}$menuTrace"
        if (trace != lastPlacementTrace) {
            lastPlacementTrace = trace
            MmLog.i(TAG, "悬浮窗落位: $trace")
        }
    }

    /**
     * 尺寸一变就重新落位：比"挂载后 post 一次"可靠——
     * 首帧布局的时序不确定，只 post 一次可能读到 0 宽而算错位置（真机踩过：窗口贴到屏幕外侧）。
     */
    private fun attachLayoutRefresh(view: View) {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyPositions()
            }
        }
    }

    /**
     * 屏幕尺寸 / 方向变化时重新落位（转屏、`wm size`、折叠屏展开等）。
     *
     * 为什么必须有：挂载那一刻拿到的屏幕尺寸可能**不是**最终用于摆放窗口的坐标系
     * （真机现象：授权后刚挂载时用的还是转屏前的尺寸 → 两个窗口一起被摆到屏幕外，
     * 用户只能靠"重置到默认位置"把它们找回来）。
     */
    private fun attachDisplayRefresh() {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = manager
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
    }

    private fun detachDisplayRefresh() {
        displayManager?.let { runCatching { it.unregisterDisplayListener(displayListener) } }
        displayManager = null
    }

    /** 挂载前的手工测量（标签是 WRAP_CONTENT，宽度内容相关）。 */
    private fun measureSelf(view: View) {
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
    }

    private fun place(view: View, x: Int, y: Int) {
        val params = view.layoutParams as WindowManager.LayoutParams
        if (params.x == x && params.y == y) return
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

    /**
     * 菜单窗参数：`FLAG_WATCH_OUTSIDE_TOUCH` 让"点面板之外"能作为
     * [MotionEvent.ACTION_OUTSIDE] 送到菜单窗（2026-09-14 用户口径：点非菜单区域收起）。
     */
    private fun menuParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "MM-Floating"

        /** 状态标签：半透明黑底 + 白字（2026-09-14 用户口径），描一道极淡白线便于在黑底上看轮廓。 */
        const val LABEL_FILL_COLOR = 0xB3000000.toInt()
        const val LABEL_STROKE_COLOR = 0x40FFFFFF.toInt()
        const val LABEL_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val LABEL_ACTION_COLOR = 0xCCFFFFFF.toInt()

        /** 手柄：半透明琥珀黄（2026-09-14 用户口径：再透一些）；收起态不写字，只用形状。 */
        const val HANDLE_FILL_COLOR = 0xB3FFC107.toInt()
        const val HANDLE_STROKE_COLOR = 0xFFFFA000.toInt()
        const val HANDLE_TEXT_COLOR = 0xFF3E2723.toInt()

        /** 展开菜单面板（参考 vivo 游戏魔盒）：深色半透明 + 淡琥珀描边 + 白字。 */
        const val MENU_FILL_COLOR = 0xE61C1C1C.toInt()
        const val MENU_STROKE_COLOR = 0x80FFC107.toInt()
        const val MENU_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val MENU_GLYPH_COLOR = 0xFFFFC107.toInt()

        /** 失败原因（深底浅底通用：亮红）。 */
        const val REASON_COLOR = 0xFFFF8A80.toInt()

        /**
         * 收起态手柄尺寸（dp）：**窄竖条**，一半在屏外 → 可见约 9dp。
         * 沿革：28×64（可见 14）→ 14×32（可见 7）→ **19×32**（2026-09-14 用户口径"加宽三分之一"）。
         * **手感提示**：可见约 9dp 仍然偏小，若点不中，把 `FloatingPosition.REVEAL_RATIO` 调到 1.0
         * （整条都露在屏内 = 可见 19dp），再考虑加大本值。
         */
        const val HANDLE_WIDTH_DP = 19
        const val HANDLE_HEIGHT_DP = 32
        const val HANDLE_CORNER_DP = 9

        /** 菜单面板（独立窗口：圆角 + 内边距 + 与屏幕 / 手柄的间距）。 */
        const val MENU_CORNER_DP = 14
        const val PANEL_PADDING_DP = 4
        const val MENU_MARGIN_DP = 8

        /** 状态标签圆角与距屏幕边缘的边距（dp）。 */
        const val LABEL_CORNER_DP = 12
        const val LABEL_MARGIN_DP = 12

        /** 失败原因文字的最大宽度（dp）：避免窗口被长文案撑宽（FR-07 尺寸紧凑）。 */
        const val REASON_MAX_WIDTH_DP = 200
    }
}
