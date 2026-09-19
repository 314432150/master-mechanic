package com.example.mastermechanic.floating

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ScrollView
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mastermechanic.R
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.friends.FriendList
import com.example.mastermechanic.friends.FriendListStore
import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset
import com.example.mastermechanic.preset.VisitPresetStore
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListStore
import com.example.mastermechanic.patrol.PatrolRequestSignal

/**
 * 悬浮窗（FR-07 / ADR-004）：**三个窗口**——
 *
 * 1. **状态标签窗**：半透明黑底 + 白字，显示「状态：X」与「动作：Y」（无动作时不显示第二行）；
 *    **可拖动**、默认横屏底部居中；
 * 2. **手柄窗**：贴屏幕边缘的一条**窄竖条**（半透明琥珀黄），位置固定、不可拖动、单击展开 / 收起菜单；
 * 3. **菜单窗**：展开时才挂载的深色圆角面板（参考 vivo 游戏魔盒形态：图标 + 文案的功能行）；
 *    菜单项只**发出请求事件**（M4 消费），M3 不产生任何游戏点击。
 *
 * 手感口径（2026-09-14 用户反馈"点击很难被触发"）：**可见区与触摸区分离**——
 * 窗口里只有贴边的那一条是可见的（7dp，不挡视线），其余是**透明的触摸扩展区**（伸进屏内 16dp、上下各 6dp）。
 * 曾经的做法是"窗口一半移出屏幕"，但移出屏幕的部分**收不到触摸**，等于只让点那 7dp。
 * 另外窗口还向系统**声明手势排除区**（`systemGestureExclusionRects`，API 29+）：
 * 手柄紧贴屏幕边缘，正是"侧滑返回"手势的起手区，不排除的话系统可能先一步把手势吃掉。
 *
 * 触摸口径（2026-09-14 修订，见 ADR-004「修订」）：状态标签也允许拦截自身覆盖范围内的触摸
 * （因为它要能拖动）；手柄的透明触摸区同理。"不拦截游戏操作"由**紧凑**承接——
 * 这里如实记录代价：手柄确实会吞掉屏幕右缘一条 23dp×44dp 的触摸（比可见的 7dp 条宽）。
 *
 * 可见性由调用方按前台信号驱动（非前台整窗移除、回前台以收起态重建）；本类自行编组到主线程。
 */
class FloatingWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 拖动阈值：在目标设备上运行时读取（FR-07；红线 4：不写死常量）。 */
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---- 手柄窗（可触摸）----
    private var panel: FrameLayout? = null
    private var menu: LinearLayout? = null
    private var reasonText: TextView? = null

    /** 手柄位置 = 布局常量（2026-09-17 用户口径"取消状态标签"后，唯一剩下的位置）。 */
    private val positions: FloatingPosition = FloatingPosition.DEFAULT
    private var expanded = false

    /** 菜单层级 / 原因 / 待确认好友 —— 判定全在纯逻辑 [FloatingMenu] 里，这里只是持有它。 */
    private var menuState = FloatingMenu.State()

    /** 菜单里可选的服务器 / 好友（展开时读一次清单；名字只能来自它们 —— 引用口径）。 */
    private var serverNames: List<String> = emptyList()
    private var friendNames: List<String> = emptyList()

    /** 一键拜访的预设（展开时读一次）：一级「拜访」点一次就按它执行。 */
    private var visitPreset: VisitPreset = VisitPreset.EMPTY

    /** 菜单窗是否已挂到 WindowManager（展开 = 挂上，收起 = 摘掉）。 */
    private var menuAttached = false

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

    /** 挂载手柄窗与菜单窗（收起态）；重复调用安全。 */
    fun show() {
        if (panel != null) return
        expanded = false

        val panelView = buildPanel()
        val menuView = buildMenu()
        panel = panelView
        menu = menuView
        renderMenu()
        applyExpandedState()

        // 手柄窗尺寸是**常量**（可见条 + 透明触摸扩展），不靠测量：空文字 + MATCH_PARENT 的手柄
        // 曾被测成 0 宽，把初始位置算成"贴着屏幕外侧"（真机表现：看不到手柄，直到位置被重置）。
        val panelWidth = handleWindowWidth()
        val panelHeight = handleWindowHeight()
        val initial = computeOffsets(
            panelWidth,
            panelHeight,
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
        } catch (e: Exception) {
            MmLog.w(TAG, "悬浮窗挂载失败", e)
            if (panelAdded) runCatching { windowManager.removeView(panelView) }
            panel = null
            return
        }
        // 布局变化（菜单展开 / 文案变长）后自动重新落位：不依赖一次性 post 的时序
        attachLayoutRefresh(panelView)
        attachDisplayRefresh()
        applyPositions()
        MmLog.i(
            TAG,
            "悬浮窗已挂载（手柄停靠 ${positions.side.token}、纵向比例 ${positions.yRatio}）",
        )
    }

    /** 整窗移除：不可见且不占任何触摸；回前台以**收起态**重建（FR-07）。重复调用安全。 */
    fun hide() {
        val panelView = panel ?: return
        detachDisplayRefresh()
        lastPlacementTrace = null
        if (menuAttached) {
            menu?.let { view -> runCatching { windowManager.removeView(view) } }
            menuAttached = false
        }
        runCatching { windowManager.removeView(panelView) }
        MmLog.i(TAG, "悬浮窗已移除")
        panel = null
        menu = null
        reasonText = null
        expanded = false
    }

    // ---- 视图构建 ----

    /**
     * 手柄窗 = **透明触摸区（整窗）+ 贴边可见窄条（子视图）**，形态永不变化。
     *
     * 为什么要分开（2026-09-14 用户反馈"点击很难被触发"）：可见窄条只有 7dp 宽，
     * 用户根本点不中；窗口内的透明部分照样能接触摸，于是把窗口向**屏内**多留出
     * [HANDLE_TOUCH_EXTRA_WIDTH_DP] × [HANDLE_TOUCH_EXTRA_HEIGHT_DP] 的"好点"区域，
     * 而视觉上还是那一条 7dp 的窄条。
     */
    private fun buildPanel(): FrameLayout {
        val side = FloatingPosition.DEFAULT.side
        // 可见竖条：贴在**屏幕边缘那一侧**（右贴边 = 窗口最右侧）
        val visual = FrameLayout.LayoutParams(dp(HANDLE_VISUAL_WIDTH_DP), dp(HANDLE_VISUAL_HEIGHT_DP)).apply {
            gravity = when (side) {
                FloatingSide.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                FloatingSide.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
            }
        }
        val visualBar = TextView(context).apply {
            layoutParams = visual
            contentDescription = context.getString(R.string.floating_handle_desc)
            background = handleBackground(side)
        }
        return FrameLayout(context).apply {
            // 根视图**不画任何东西**（否则透明的触摸区会露出来）
            addView(visualBar)
            setOnClickListener { toggleExpanded() }
        }
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
        // 内容由 [renderMenu] 按菜单层级填（M3-T3-8：四组 + 两级列表，**原地替换**，不开第二个窗口 ——
        // 面板吞掉的游戏触摸区域因此恒定）。
        // 点菜单面板之外的任何地方 → 收起（FLAG_WATCH_OUTSIDE_TOUCH 送来 ACTION_OUTSIDE）
        setOnTouchListener(::onMenuTouch)
    }

    // ---- 菜单内容（M3-T3-8：四组 + 两级列表，**原地替换**）----

    /**
     * 按 [menuState] 重建菜单内容。
     *
     * 层级与"点了会不会收起"由纯逻辑 [FloatingMenu] 判定（已进 JVM 单测），这里只管画：
     * ROOT = 四组；PICK_SERVER / PICK_FRIEND = 面包屑 + 一个快捷项 + 列表。
     * 内容变化会改变菜单尺寸 → 依赖 [attachLayoutRefresh] 重新落位。
     */
    private fun renderMenu() {
        val container = menu ?: return
        container.removeAllViews()
        when (menuState.level) {
            FloatingMenu.Level.ROOT -> renderRoot(container)
            FloatingMenu.Level.PICK_SERVER -> renderPickServer(container)
            FloatingMenu.Level.CONFIG -> renderConfig(container)
            FloatingMenu.Level.CONFIG_SERVER -> renderConfigServerPick(container)
            FloatingMenu.Level.CONFIG_FRIEND -> renderConfigFriendPick(container)
        }
        // 原因条：失败 / 当前不可用**就挂在这儿**（不另开 Toast —— 玩家在游戏里不会去看系统通知）
        reasonText = textView(11f, REASON_COLOR).also {
            it.maxWidth = dp(REASON_MAX_WIDTH_DP)
            it.setPadding(dp(6), dp(2), dp(6), dp(2))
            it.text = menuState.reason.orEmpty()
            it.visibility = if (menuState.reason == null) View.GONE else View.VISIBLE
            container.addView(it)
        }
        // 内容换完**立刻按新内容量一次**（2026-09-19 修「原因条不显示」）：
        // [applyPositions] 读的是 `measuredWidth/measuredHeight`（见 [viewSize]），这里不重新量的话，
        // 它拿到的还是**上一版内容**的尺寸 → 窗口高度不跟着长 → 新加的原因条被裁在窗口外，
        // 用户看到的是"点了保存毫无反应"（真机 logcat 里 `fail` 明明打了一串）。
        // 放在本方法内而不是各调用点，是让"内容一变就重新量"成为 renderMenu 的固有语义，避免再漏。
        measureSelf(container)
    }

    /**
     * 根层五项，顺序 = 换号 / 只拜访 / 换号拜访 / 拜访规则 / 停止
     * （高频在上，误触代价最大的「停止」放最底）。
     *
     * 「换号拜访」点一次就执行整条流程（换号 + 拜访），目标来自**预设** ——
     * 用户最高频的场景是"在同一个好友的农场里轮流换不同服务器的小号来看他"，
     * 每点一次都先选服务器再选好友太费事（2026-09-16 用户口径）。
     *
     * 两个拜访项的命名（2026-09-19 用户口径）：「只拜访」= 不换号、在当前区服直接去；
     * 「换号拜访」= 整条走完。用「只」和「换号」作对照，且与 Toast 文案（[PatrolRequestSignal.Kind.briefText]）一致。
     */
    private fun renderRoot(container: LinearLayout) {
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_switch),
                R.string.floating_menu_switch,
            ) { openGroup(FloatingMenu.Group.SWITCH) },
        )
        // 只拜访：跳过换号，在当前区服直接去见好友
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_visit),
                if (visitPreset.friendName.isNotEmpty()) {
                    context.getString(R.string.floating_menu_visit, visitPreset.friendName)
                } else {
                    context.getString(R.string.floating_menu_visit_unset)
                },
            ) { onVisitOnly() },
        )
        // 换号拜访：整条走完
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_switch_visit),
                if (visitPreset.isReady) {
                    context.getString(R.string.floating_menu_switch_visit, visitPreset.friendName)
                } else {
                    context.getString(R.string.floating_menu_switch_visit_unset)
                },
            ) { onVisitPreset() },
        )
        // 设置：**在悬浮窗里就能改预设**（2026-09-16 用户口径：不能逼用户切回 App 去配）
        // 进设置页时以**当前预设**为草稿（不是空面板）
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_setting),
                R.string.floating_menu_setting,
            ) {
                menuState = FloatingMenu.openConfig(menuState, visitPreset)
                rerender()
            },
        )
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_stop),
                R.string.floating_menu_stop,
            ) { onStop() },
        )
    }

    /** 换号 → 选服务器：首行「下一个」（点一下立刻换下一个），下面才是服务器清单。 */
    private fun renderPickServer(container: LinearLayout) {
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_back),
                R.string.floating_menu_switch,
            ) { backToRoot() },
        )
        // 与下面的服务器名**同级、同一套行样式**（不带图标）—— 见 renderConfigServerPick 里的说明
        container.addView(
            pickRow(context.getString(R.string.floating_menu_switch_next)) { onSwitchNext() },
        )
        if (serverNames.isEmpty()) {
            container.addView(noteRow(context.getString(R.string.floating_menu_no_server)))
            return
        }
        container.addView(
            boundedScroll(
                rows = serverNames.map { name ->
                    pickRow(name) { sendRequest(PatrolRequestSignal.Kind.SWITCH_SERVER, serverName = name) }
                },
                rowCount = serverNames.size,
            ),
        )
    }

    /**
     * 设置页：**在悬浮窗里直接配**（2026-09-16 用户口径 —— 不能逼用户每次切回 App 去配）。
     *
     * 改的是**草稿**：点「保存并返回」才落盘，直接返回什么都不变。
     */
    private fun renderConfig(container: LinearLayout) {
        val draft = menuState.draft ?: return
        container.addView(
            menuRow(context.getString(R.string.floating_menu_glyph_back), R.string.floating_menu_setting) {
                menuState = FloatingMenu.back(menuState)
                rerender()
            },
        )
        // 「服务器」**只占一行**：点它进三级菜单（下一个 / 服务器清单）——
        // 之前这里并排放了两行、文案还一模一样（一行切策略、一行选具体），
        // 用户看到的是"出现了两个「服务器：（点这里选一个）」"（2026-09-16 bug）
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_switch),
                when {
                    draft.serverChoice == ServerChoice.NEXT ->
                        context.getString(R.string.floating_menu_config_server_next)
                    draft.serverName.isEmpty() ->
                        context.getString(R.string.floating_menu_config_server_unset)
                    else -> context.getString(R.string.floating_menu_config_server_fixed, draft.serverName)
                },
            ) {
                menuState = FloatingMenu.openConfigPick(menuState, FloatingMenu.Level.CONFIG_SERVER)
                rerender()
            },
        )
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_visit),
                if (draft.friendName.isEmpty()) {
                    context.getString(R.string.floating_menu_config_friend_unset)
                } else {
                    context.getString(R.string.floating_menu_config_friend, draft.friendName)
                },
            ) {
                menuState = FloatingMenu.openConfigPick(menuState, FloatingMenu.Level.CONFIG_FRIEND)
                rerender()
            },
        )
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_setting),
                R.string.floating_menu_config_save,
            ) { saveConfig() },
        )
    }

    /** 设置页 → 选服务器（三级菜单）：首行「顺序轮换」，下面是服务器清单。 */
    private fun renderConfigServerPick(container: LinearLayout) {
        container.addView(
            menuRow(context.getString(R.string.floating_menu_glyph_back), R.string.floating_menu_switch) {
                menuState = FloatingMenu.openConfigPick(menuState, FloatingMenu.Level.CONFIG)
                rerender()
            },
        )
        // 「顺序轮换」与下面的服务器名**同级、同一套行样式**：它不该带图标 ——
        // 功能行才有图标槽，列表行没有；带图标会让它比服务器名多出一段、看起来像多了一层缩进
        //（2026-09-16 用户口径）。当前策略就是"顺序轮换"时，它自己也带勾。
        //
        // 这里用 `floating_menu_config_server_auto`（"顺序轮换"）而**不是** `floating_menu_switch_next`
        // （"下一个"）：本行是**策略选择**（把预设切回 NEXT），"点一下立刻换下一个"是换号那一层的动作
        //（2026-09-19 用户口径：策略名与动作名分开取词）。
        container.addView(
            pickRow(
                context.getString(R.string.floating_menu_config_server_auto),
                selected = menuState.draft?.serverChoice == ServerChoice.NEXT,
            ) {
                menuState = FloatingMenu.draftNextServer(menuState)
                rerender()
            },
        )
        if (serverNames.isEmpty()) {
            container.addView(noteRow(context.getString(R.string.floating_menu_no_server)))
            return
        }
        // 当前草稿里选中的那个 → 加勾号高亮
        val selectedServer = menuState.draft?.serverName
        container.addView(
            boundedScroll(
                rows = serverNames.map { name ->
                    pickRow(name, selected = name == selectedServer) {
                        menuState = FloatingMenu.draftServer(menuState, name)
                        rerender()
                    }
                },
                rowCount = serverNames.size,
            ),
        )
    }

    /** 设置页 → 从好友清单里挑一个（挑完回设置页）。 */
    private fun renderConfigFriendPick(container: LinearLayout) {
        container.addView(
            menuRow(context.getString(R.string.floating_menu_glyph_back), R.string.floating_menu_visit_title) {
                menuState = FloatingMenu.openConfigPick(menuState, FloatingMenu.Level.CONFIG)
                rerender()
            },
        )
        if (friendNames.isEmpty()) {
            container.addView(noteRow(context.getString(R.string.floating_menu_no_friend)))
            return
        }
        // 当前草稿里选中的那个 → 加勾号高亮
        val selectedFriend = menuState.draft?.friendName
        container.addView(
            boundedScroll(
                rows = friendNames.map { name ->
                    pickRow(name, selected = name == selectedFriend) {
                        menuState = FloatingMenu.draftFriend(menuState, name)
                        rerender()
                    }
                },
                rowCount = friendNames.size,
            ),
        )
    }

    /**
     * 保存设置：写盘（临时文件 + rename）。**成功才收起**；失败保持展开并说明原因，
     * 草稿不丢（用户可以直接再试一次，不用重新选一遍）。
     * 写完顺手刷新内存里的预设 —— 一级「拜访」那一行的文案立刻跟着变。
     */
    private fun saveConfig() {
        val draft = menuState.draft ?: return
        // 非空验证（2026-09-19 用户口径）：好友必填；「指定区服」策略下区服名也必填。
        // 「顺序轮换」是**合法策略**（不是"没选"），所以服务器这一项在 NEXT 下不需要选。
        if (!draft.isReady) {
            fail(context.getString(R.string.floating_menu_config_incomplete))
            return
        }
        val failure = runCatching { VisitPresetStore.save(context, draft) }.exceptionOrNull()
        if (failure != null) {
            fail(
                context.getString(
                    R.string.floating_menu_config_save_failed,
                    failure.message ?: failure.javaClass.simpleName,
                ),
            )
            return
        }
        visitPreset = draft
        MmLog.i(
            TAG,
            "一键拜访预设已保存：${draft.serverChoice}/${draft.serverName}/${draft.friendName}",
        )
        // 保存成功 → **回到上级菜单**（不是收起面板）：用户刚配完，最可能接着点「拜访」执行一次
        // 提示里**服务器与好友都写出来**（2026-09-19 用户口径：只报好友看不出服务器配成什么了）
        menuState = FloatingMenu.configSaved(
            menuState,
            context.getString(
                R.string.floating_menu_config_saved,
                serverSummary(draft),
                draft.friendName,
            ),
        )
        rerender()
    }

    /** 服务器摘要（保存提示用）：「顺序轮换」或具体区服名。 */
    private fun serverSummary(preset: VisitPreset): String =
        when (preset.serverChoice) {
            ServerChoice.NEXT -> context.getString(R.string.floating_menu_config_server_auto)
            ServerChoice.FIXED -> preset.serverName
        }

    /** 进下一级 / 回根：只改状态再重画（同一扇窗，原地替换）。 */
    private fun openGroup(group: FloatingMenu.Group) {
        menuState = FloatingMenu.openGroup(menuState, group)
        rerender()
    }

    private fun backToRoot() {
        menuState = FloatingMenu.back(menuState)
        rerender()
    }

    /**
     * 只改内容的重新渲染（层级切换走这里）。
     *
     * **换完内容先同步量一次，再算落位**（2026-09-16 用户口径「层级切换时闪烁」）：
     * 新内容的尺寸和旧内容不一样，若不先量，窗口会先按**旧尺寸**显示一帧、
     * 等 [applyPositions] 把新尺寸与新位置写进去后才跳到正确位置 —— 那就是肉眼看到的闪烁。
     * 先 `measureSelf` 把尺寸算出来，尺寸与位置就在同一次 `updateViewLayout` 里一起生效。
     */
    private fun rerender() {
        // 测量已在 renderMenu 内部完成（"内容一变就重新量"，见那里的说明）—— 这里只负责落位
        renderMenu()
        applyPositions()
    }

    /**
     * 把列表包进**限高**的 ScrollView：横屏可用高只有约 360dp，菜单不能顶满屏。
     * 高度按「行数 × 行高」取用、超过上限才限住 —— 行数与上限都走纯逻辑 [FloatingMenu]（已有单测）。
     */
    private fun boundedScroll(rows: List<View>, rowCount: Int): ScrollView {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEach { addView(it) }
        }
        val density = context.resources.displayMetrics.density
        val screenHeightDp = (FloatingScreen.spec(context).height / density).toInt()
        // 除列表以外的固定高度（面板内边距 + 面包屑 + 快捷行 + 原因条）保守按 **150dp** 估算 ——
        // 刻意取大：宁可列表少显示一行，也不让菜单顶破屏幕。
        val maxDp = FloatingMenu.listMaxHeight(screenHeightDp, 150)
        val heightDp = FloatingMenu.listHeight(rowCount, FloatingMenu.PICK_ROW_HEIGHT_DP, maxDp)
        return ScrollView(context).apply {
            isFillViewport = false
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp),
            )
        }
    }

    /** 纯说明行（不可点）：清单为空、待确认提示等。 */
    private fun noteRow(text: String): TextView = textView(12f, MENU_TEXT_COLOR).apply {
        this.text = text
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    /**
     * 三级列表里的一行（服务器 / 好友名）：整行可点。
     *
     * [selected] = 这一项就是**当前选中的那个**：琥珀色 + 前置勾号（2026-09-16 用户口径
     * 「被选中的服务器和好友应该增加高亮选中标记」）—— 面板窄、行又密，靠颜色 + 勾号才一眼看得出。
     *
     * 与上级功能行逐项对齐：左内边距留出图标槽位那一段，文字落在同一条竖线上。
     */
    private fun pickRow(
        name: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): TextView = textView(12f, if (selected) MENU_GLYPH_COLOR else MENU_TEXT_COLOR).apply {
        text = if (selected) "✓ $name" else name
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        // 左内边距 = 功能行的（8 + 图标槽 18 + 图标后间距 6）→ 文字起点对齐
        setPadding(dp(8 + MENU_GLYPH_SLOT_DP + 6), dp(2), dp(8), dp(2))
        // 行高比功能行高一档（2026-09-16 用户口径「列表太紧凑」）：文字在行内垂直居中
        minHeight = dp(FloatingMenu.PICK_ROW_HEIGHT_DP)
        // 点按反馈与功能行同一套
        background = tapFeedbackBackground()
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * 展开时读一次两份清单：菜单里能选的目标**只能来自清单**（引用口径 —— 手打名字是
     * "名字对不上 → 定位失败"的唯一来源）。读失败只是"这一层没得选"，不影响其它组。
     * 文件是 KB 级、且只在用户点开菜单时读一次。
     */
    private fun loadMenuOptions() {
        serverNames = runCatching {
            (ServerListStore.load(context) ?: ServerList.EMPTY).entries.map { it.serverName }
        }.getOrElse {
            MmLog.w(TAG, "服务器清单读取失败：菜单里不给选（其它组不受影响）", it)
            emptyList()
        }
        friendNames = runCatching {
            (FriendListStore.load(context) ?: FriendList.EMPTY).entries.map { it.name }
        }.getOrElse {
            MmLog.w(TAG, "好友清单读取失败：设置页里不给选（其它项不受影响）", it)
            emptyList()
        }
        visitPreset = runCatching {
            VisitPresetStore.load(context) ?: VisitPreset.EMPTY
        }.getOrElse {
            MmLog.w(TAG, "预设读取失败：一级「拜访」会提示去设置（其它项不受影响）", it)
            VisitPreset.EMPTY
        }
    }

    /**
     * 菜单功能行（参考 vivo 游戏魔盒）：左侧图标 + 文案，整行可点。
     *
     * 图标占一个**固定宽度**的槽位：不同字形的自然宽度差得很远（`☺` 甚至会被渲染成双宽的 emoji），
     * 不固定就会出现"每一行的文字起点都不一样"（2026-09-16 用户报「拜访的文字左边距更宽、没和其他
     * 菜单左对齐」）。槽位固定 18dp + 居中对齐后，所有行的文字起点一律相同。
     */
    /**
     * 菜单行的**点按反馈**：按下的那一行整行变亮（2026-09-16 用户口径
     * 「点击时改对应菜单背景色高亮」）。
     *
     * 试过系统水波纹（`RippleDrawable`）—— 在悬浮窗里它按**窗口边界**铺开，整块面板一起亮，
     * 根本分不清点的是哪一行（用户原话「无法区分」）。
     * `StateListDrawable` + `state_pressed` 的高亮范围**就是这一行自己的 bounds**：
     * 按下变亮、抬手恢复，既不越界也不会影响别的行。
     */
    private fun tapFeedbackBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(MENU_PRESSED_COLOR))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** 文案来自资源 id 的重载（绝大多数行）。 */
    private fun menuRow(glyph: String, textRes: Int, onClick: () -> Unit): LinearLayout =
        menuRow(glyph, context.getString(textRes), onClick)

    /** 文案已经是字符串的重载（一级「拜访」要显示预设里的好友名，写不成固定资源）。 */
    private fun menuRow(glyph: String, text: CharSequence, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 上下间距 2 → 5dp（2026-09-17 用户口径「适当增加 1、2 级菜单项的上下间距」）：
            // 功能行挨得太紧时，一眼扫过去分不清是"五项"还是"三块"。列表行保持自己的 28dp 行高。
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = tapFeedbackBackground()
            addView(
                textView(12f, MENU_GLYPH_COLOR).apply {
                    // 必须写 this.text：外层的参数也叫 text，不写就会被解析成"给参数赋值"
                    this.text = glyph
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        dp(MENU_GLYPH_SLOT_DP),
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(0, 0, dp(6), 0)
                },
            )
            addView(textView(12f, MENU_TEXT_COLOR).apply { this.text = text })
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
     * 贴边手柄可见条的底：**贴屏幕边缘的一侧是直角，朝向屏幕中心的一侧是半圆角**；
     * 半透明琥珀黄填充（2026-09-14 用户口径）。
     *
     * 圆角半径跟着可见宽度走（= 可见宽度的一半）：它现在只有 7dp 宽，写死大半径会画成畸形
     * （这条是 2026-09-14 缩短宽度时发现的：半径必须 ≤ 可见宽度的一半）。
     */
    private fun handleBackground(side: FloatingSide): GradientDrawable = GradientDrawable().apply {
        val r = dp(HANDLE_VISUAL_WIDTH_DP) / 2f
        // 顺序：topLeft、topRight、bottomRight、bottomLeft
        cornerRadii = when (side) {
            FloatingSide.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            FloatingSide.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        setColor(HANDLE_FILL_COLOR)
        setStroke(dp(1), HANDLE_STROKE_COLOR)
    }

    /** 手柄窗尺寸（px）：**可见条 + 透明触摸扩展**（见 [buildPanel]）。 */
    private fun handleWindowWidth(): Int = dp(HANDLE_VISUAL_WIDTH_DP + HANDLE_TOUCH_EXTRA_WIDTH_DP)

    private fun handleWindowHeight(): Int = dp(HANDLE_VISUAL_HEIGHT_DP + HANDLE_TOUCH_EXTRA_HEIGHT_DP)

    /**
     * 向系统声明"这块区域别当手势起手区"（API 29+）。
     *
     * 为什么需要：手柄紧贴屏幕边缘，正是各家 ROM"侧滑返回"的手势起手区，
     * 系统可能先一步把触摸判成手势 → 用户感觉"点了没反应"（2026-09-14 真机反馈）。
     * 这是**尽力而为**：不同 ROM 是否真的采纳无法保证，也不影响其它行为（采纳不了就退化成"点得更准"）。
     */
    private fun excludeFromSystemGestures(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        view.systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
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
            positions.side,
            menuWidth,
            screen.width,
            dp(HANDLE_VISUAL_WIDTH_DP), // 避让的是**可见条**（窗口其余部分是透明触摸区）
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

    /**
     * 视图尺寸：优先**测量值**（`measureSelf` 刚量过 = 最新内容），量不到才退回已布局尺寸。
     *
     * 顺序不能反：菜单窗换过内容后、系统重排之前，`view.width` 还是**旧尺寸**，
     * 用它算落位就会算出错位的位置（层级切换闪烁的一半原因）。
     */
    private fun viewSize(view: View): Pair<Int, Int> =
        (if (view.measuredWidth > 0) view.measuredWidth else view.width) to
            (if (view.measuredHeight > 0) view.measuredHeight else view.height)

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

    // ---- 菜单 ----

    /** 单击手柄：展开 / 收起菜单（展开本身不执行任何操作）。 */
    private fun toggleExpanded() {
        expanded = !expanded
        if (expanded) {
            // 每次展开都**回到根**并重读两份清单：不记住"上次停在选服务器那层"（避免误点上次的目标）
            menuState = FloatingMenu.State()
            loadMenuOptions()
            renderMenu()
        } else {
            menuState = FloatingMenu.collapsed()
        }
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, if (expanded) "悬浮窗菜单已展开（等待用户选择）" else "悬浮窗已收起")
    }

    /** 收起（操作成功结束后立即收起，FR-07 收起时机）：**层级栈一并清空**。 */
    private fun collapse() {
        if (!expanded) return
        expanded = false
        menuState = FloatingMenu.collapsed()
        reasonText?.visibility = View.GONE
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, "悬浮窗已收起（操作成功结束）")
    }

    /**
     * 失败 / 当前不可用：**停在当前层级**并显示原因（FR-07 收起时机 / §1.3）——
     * 不收起、不隐藏、不静默（用户口径：「停止」在没有进行中的巡查时也要照常说出来）。
     */
    private fun fail(reason: String) {
        menuState = FloatingMenu.fail(menuState, reason)
        renderMenu()
        expanded = true
        applyExpandedState()
        applyPositions()
        MmLog.w(TAG, "菜单保持展开并说明原因：$reason")
    }

    /** 执行前的统一前置检查（采集会话 + 跑号清单可读）；不满足 → 说明原因并返回 false。 */
    private fun ensureReady(): Boolean {
        val reason = startBlockReason() ?: return true
        MmLog.w(TAG, "菜单动作被拒绝：$reason")
        fail(reason)
        return false
    }

    /**
     * 发一次用户请求：**成功才收起**（FR-07 收起时机）。
     * M3 全程只发事件、**不产生任何游戏点击**（红线 2）。
     *
     * @param keepExpanded 「停止」这类"发出去了但当前没有可停的流程"的情形：不收起，由调用方补原因说明。
     */
    private fun sendRequest(
        kind: PatrolRequestSignal.Kind,
        serverName: String? = null,
        friendName: String? = null,
        keepExpanded: Boolean = false,
    ) {
        val count = PatrolRequestSignal.request(
            PatrolRequestSignal.Request(
                kind = kind,
                nowMs = SystemClock.elapsedRealtime(),
                serverName = serverName,
                friendName = friendName,
            ),
        )
        MmLog.i(TAG, "已发出「${kind.logText}」（第 $count 次）；本版本不执行流程、不产生点击")
        if (!keepExpanded) collapse()
    }

    /**
     * 一键拜访（**一级直接执行**）：按预设的"服务器策略 + 好友"走完整条流程。
     *
     * 这是用户最高频的路径 —— 预设没配好时**不静默**，直接说明去配（并保持展开）。
     */
    private fun onVisitPreset() {
        if (!ensureReady()) return
        val preset = visitPreset
        if (!preset.isReady) {
            fail(context.getString(R.string.floating_reason_preset_unset))
            return
        }
        sendRequest(
            kind = PatrolRequestSignal.Kind.VISIT_PRESET,
            // 只有"固定区服"才带区服名；为空 = 按清单换下一个（消费方据此区分两种策略）
            serverName = preset.serverName.takeIf { preset.serverChoice == ServerChoice.FIXED },
            friendName = preset.friendName,
        )
    }

    /**
     * 只拜访：**跳过全部换号步骤**，在当前所在的区服直接进好友农场拜访预设里的好友
     * （FR-04「只拜访」区间：第 7 步 → 第 10 步）。
     *
     * 它只需要"拜访谁"，**不需要服务器** —— 所以校验只看好友（比 [onVisitPreset] 松一档）。
     */
    private fun onVisitOnly() {
        if (!ensureReady()) return
        if (visitPreset.friendName.isEmpty()) {
            fail(context.getString(R.string.floating_reason_preset_unset))
            return
        }
        sendRequest(
            kind = PatrolRequestSignal.Kind.VISIT_ONLY,
            friendName = visitPreset.friendName,
        )
    }

    /** 换号：按清单顺序换下一个（不指定目标）。 */
    private fun onSwitchNext() {
        if (!ensureReady()) return
        sendRequest(PatrolRequestSignal.Kind.SWITCH_NEXT)
    }

    /**
     * 停止：M3 **没有进行中的流程可停**（巡查主流程属 M4）。
     * 按用户口径：照常发出请求、**照常显示这一组**，点了**保持展开**并说明"当前没有进行中的巡查" ——
     * 不隐藏、不静默、不制造"点了没反应"的哑按钮。
     */
    private fun onStop() {
        if (!ensureReady()) return
        sendRequest(PatrolRequestSignal.Kind.STOP, keepExpanded = true)
        fail(context.getString(R.string.floating_menu_nothing_running))
    }

    /** 执行前的前置检查：采集会话在 + 预设文件可读（内容是否配好由各动作自己判定）。 */
    private fun startBlockReason(): String? {
        if (!CaptureSessionSignal.isActive) {
            return context.getString(R.string.floating_reason_no_session)
        }
        return try {
            VisitPresetStore.load(context)
            null
        } catch (e: IllegalArgumentException) {
            context.getString(
                R.string.floating_reason_preset_broken,
                e.message ?: e.javaClass.simpleName,
            )
        }
    }

    // ---- 定位 ----

    /** 各窗口的偏移（参数均已按当前屏与当前位置算好）；菜单收起时为 null（窗口根本不存在）。 */
    private data class Offsets(
        val panelX: Int,
        val panelY: Int,
        val menuX: Int?,
        val menuY: Int?,
    )

    private fun computeOffsets(
        panelWidth: Int,
        panelHeight: Int,
        menuWidth: Int,
        menuHeight: Int,
    ): Offsets {
        val screen = FloatingScreen.spec(context)
        val side = positions.side
        val panelY = FloatingLayout.y(positions.yRatio, panelHeight, screen.height)
        // 手柄**永远贴边**（不再有"展开态临时进屏"：菜单已独立成窗，手柄不需要让位）
        // 注意传入的是**可见条宽度**：窗口里剩下的部分是透明触摸区，菜单只需避开看得见的那一条
        val handleVisualWidth = dp(HANDLE_VISUAL_WIDTH_DP)
        val panelX = FloatingLayout.handleX(side, panelWidth, screen.width, handleVisualWidth)
        val menuX = if (menuWidth > 0) {
            FloatingLayout.menuX(side, menuWidth, screen.width, handleVisualWidth, dp(MENU_MARGIN_DP))
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
            menuX = menuX,
            menuY = menuY,
        )
    }

    /** 重新落位（尺寸未就绪时跳过；布局 / 屏幕变化后会被再次触发）。 */
    private fun applyPositions() {
        val panelView = panel ?: return
        val panelWidth = panelView.width.takeIf { it > 0 } ?: handleWindowWidth()
        val panelHeight = panelView.height.takeIf { it > 0 } ?: handleWindowHeight()
        val menuView = menu?.takeIf { expanded && menuAttached }
        val (menuWidth, menuHeight) = menuView?.let { viewSize(it) } ?: (0 to 0)
        val offset = computeOffsets(
            panelWidth,
            panelHeight,
            menuWidth,
            menuHeight,
        )
        place(panelView, offset.panelX, offset.panelY)
        if (menuView != null && offset.menuX != null && offset.menuY != null) {
            // 菜单窗**尺寸与位置一次提交**（2026-09-16 修「层级切换闪烁」）：
            // 菜单窗按 WRAP_CONTENT 挂载，内容一换尺寸就变；只改位置的话，系统会先用**旧尺寸**
            // 排一帧、下一帧才按新尺寸重排，而位置已经按新尺寸算过了 → 那一帧就是肉眼看到的闪。
            // 把量好的尺寸显式写进参数、与 x/y 在同一次 updateViewLayout 里生效，中间就没有错位帧。
            val menuParams = menuView.layoutParams as WindowManager.LayoutParams
            if (menuParams.width != menuWidth || menuParams.height != menuHeight) {
                menuParams.width = menuWidth
                menuParams.height = menuHeight
                menuParams.x = offset.menuX
                menuParams.y = offset.menuY
                runCatching { windowManager.updateViewLayout(menuView, menuParams) }
            } else {
                place(menuView, offset.menuX, offset.menuY)
            }
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
            " ${panelWidth}x$panelHeight$menuTrace"
        if (trace != lastPlacementTrace) {
            lastPlacementTrace = trace
            MmLog.i(TAG, "悬浮窗落位: $trace")
        }
    }

    /**
     * 尺寸一变就重新落位：比"挂载后 post 一次"可靠——
     * 首帧布局的时序不确定，只 post 一次可能读到 0 宽而算错位置（真机踩过：窗口贴到屏幕外侧）。
     * 顺带在这里声明手势排除区（尺寸就绪才能声明）。
     */
    private fun attachLayoutRefresh(view: View) {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            excludeFromSystemGestures(view)
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

        /**
         * 手柄：半透明琥珀黄，**再透一档**（2026-09-16 用户口径「增加悬浮窗手柄透明度」）。
         *
         * 沿革：不透明 → 70%（0xB3）→ **35%（0x59）**。手柄贴在游戏画面右缘，越透越不挡视野；
         * 但**完全看不见就等于找不到入口**，所以描边留一半不透明度（0x80）兜住轮廓。
         * 收起态不写字，只用形状。
         */
        const val HANDLE_FILL_COLOR = 0x59FFC107.toInt()
        const val HANDLE_STROKE_COLOR = 0x80FFA000.toInt()
        const val HANDLE_TEXT_COLOR = 0xFF3E2723.toInt()

        /** 展开菜单面板（参考 vivo 游戏魔盒）：深色半透明 + 淡琥珀描边 + 白字。 */
        const val MENU_FILL_COLOR = 0xE61C1C1C.toInt()
        const val MENU_STROKE_COLOR = 0x80FFC107.toInt()
        const val MENU_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val MENU_GLYPH_COLOR = 0xFFFFC107.toInt()

        /** 失败原因（深底浅底通用：亮红）。 */
        const val REASON_COLOR = 0xFFFF8A80.toInt()

        /**
         * 手柄（dp）：**可见条**与**透明触摸扩展**分开定义（2026-09-14）。
         *
         * 沿革：28×64（可见 14）→ 14×32（可见 7）→ 19×32 一半在屏外（可见约 10）→
         * **可见条 7×32 + 触摸扩展 16×12**（用户口径："缩短 1/4 宽度" + "点击很难被触发"）。
         *
         * 为什么不再"一半移出屏幕"：移出去的部分**收不到触摸**，等于只让用户点那 7dp。
         * 现在窗口 = 23×44dp，其中只有贴边的 7×32dp 可见，其余透明但可点。
         * **代价如实记录**：屏幕右缘这条 23×44dp 会吞掉游戏触摸（比可见的 7dp 条宽）。
         */
        const val HANDLE_VISUAL_WIDTH_DP = 7
        const val HANDLE_VISUAL_HEIGHT_DP = 32
        const val HANDLE_TOUCH_EXTRA_WIDTH_DP = 16
        const val HANDLE_TOUCH_EXTRA_HEIGHT_DP = 12

        /** 菜单面板（独立窗口：圆角 + 内边距 + 与屏幕 / 手柄的间距）。 */
        const val MENU_CORNER_DP = 14
        /** 菜单图标槽位宽度（dp）：固定住才能让每行文字左对齐（见 [menuRow]）。 */
        const val MENU_GLYPH_SLOT_DP = 18

        /**
         * 菜单行**按下**时的高亮底色（20% 白）：面板是深色的，亮一点才看得出来。
         *
         * 只是"这一行变亮"，不用水波纹 —— 水波纹在悬浮窗里会按窗口边界铺开，整块面板一起亮，
         * 分不清点的是哪一行（2026-09-16 用户口径）。
         */
        const val MENU_PRESSED_COLOR = 0x33FFFFFF
        const val PANEL_PADDING_DP = 4
        const val MENU_MARGIN_DP = 8

        /** 状态标签圆角与距屏幕边缘的边距（dp）。 */
        const val LABEL_CORNER_DP = 12
        const val LABEL_MARGIN_DP = 12

        /** 失败原因文字的最大宽度（dp）：避免窗口被长文案撑宽（FR-07 尺寸紧凑）。 */
        const val REASON_MAX_WIDTH_DP = 200
    }
}
