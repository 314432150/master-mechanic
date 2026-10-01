package com.example.mastermechanic.floating

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
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
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.mastermechanic.MainActivity
import com.example.mastermechanic.R
import com.example.mastermechanic.action.PopupCloseSignal
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.FrameExpectationSignal
import com.example.mastermechanic.capture.FrameFreshness
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.friends.FriendList
import com.example.mastermechanic.friends.FriendListStore
import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset
import com.example.mastermechanic.preset.VisitPresetStore
import com.example.mastermechanic.servers.ServerCaptureDraft
import com.example.mastermechanic.servers.ServerCaptureOutcome
import com.example.mastermechanic.servers.ServerEntry
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListCapture
import com.example.mastermechanic.servers.ServerListStore
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.patrol.FriendListOcrSignal
import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.patrol.PatrolRequestConsumer
import com.example.mastermechanic.patrol.PatrolRequestSignal
import com.example.mastermechanic.patrol.PatrolResultSignal
import com.example.mastermechanic.patrol.PatrolSession
import com.example.mastermechanic.patrol.PatrolStartGate
import com.example.mastermechanic.patrol.PatrolStatus
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * 悬浮窗（FR-07 / ADR-004）：**三个窗口**——
 *
 * 1. **手柄窗**：贴屏幕边缘的一条**窄竖条**（半透明琥珀黄），位置固定、不可拖动、单击展开 / 收起菜单；
 *    跑号中止 / 暂停时**换暖橙红**（T4-5：收起态唯一还能传"有事发生"的通道）；
 * 2. **菜单窗**：展开时才挂载的深色圆角面板（参考 vivo 游戏魔盒形态：图标 + 文案的功能行）；
 *    菜单项只**发出请求事件**（M4 消费）；根菜单顶部在跑号时多一块**进度块**（第几步 / 为什么停 / 继续）；
 * 3. **状态标签窗**（2026-09-28 用户口径恢复）：**顶部正中心**一条很小的圆角标签，显示**当前正在进行的操作**
 *    （`正在关弹窗` / `退出登录` / `已中止` / 没在跑时 `待命`），
 *    **可以拖动**（悬浮窗里唯一可拖的部件），位置以比例落盘（[FloatingLabelStore]）。
 *
 * 标签的来历（为什么取消过又要回来）：2026-09-17 曾按用户口径**取消状态标签**，理由是"常驻文字压在
 * 游戏画面上挡视线"，改由"底部浮窗提示 + 展开菜单看进度"承接；2026-09-28 用户重新要了一条，但换了口径：
 * **放在顶部正中心**（他确认过那块位置没有需要识别的内容）、**极短**（单行 + 限宽）、**可拖走**（挡了就自己挪）。
 * 于是它不是当年那条标签的复刻，而是"一眼能看到 / 挡了能挪开"的版本。
 *
 * 手感口径（2026-09-14 用户反馈"点击很难被触发"）：**可见区与触摸区分离**——
 * 窗口里只有贴边的那一条是可见的（7dp，不挡视线），其余是**透明的触摸扩展区**（伸进屏内 16dp、上下各 6dp，
 * 2026-09-20 试过扩到 48dp 被真机实测否掉，见 [HANDLE_TOUCH_EXTRA_WIDTH_DP]）。
 * 曾经的做法是"窗口一半移出屏幕"，但移出屏幕的部分**收不到触摸**，等于只让点那 7dp。
 * 另外窗口还向系统**声明手势排除区**（`systemGestureExclusionRects`，API 29+）：
 * 手柄紧贴屏幕边缘，正是"侧滑返回"手势的起手区，不排除的话系统可能先一步把手势吃掉。
 *
 * 触摸口径（2026-09-14 修订，见 ADR-004「修订」）：手柄的透明触摸区会拦截自身覆盖范围内的触摸。
 * "不拦截游戏操作"由**紧凑**承接——这里如实记录代价：手柄会吞掉屏幕右缘一条
 * **23×44dp** 的触摸（比可见的 7dp 条宽）。这条边界 2026-09-20 用真机划清过一次：
 * 一旦加宽（48dp）就压到游戏右缘的可点区域，**"不遮挡"优先于"好点"**。
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

    /** 贴边可见条（T4-5：中止 / 暂停时换警示色 + 加高 + 脉冲一次，收起态也要看得出"停在半路了"）。 */
    private var handleBar: TextView? = null

    /** 手柄当前是不是告警态（只在**变化**时换背景 / 动效 / 播报，别每 0.5s 重来一遍）。 */
    private var handleAlert = false

    /** 手柄的告警脉冲动画（离屏 / 恢复正常要取消，否则会在看不见的窗口上继续跑）。 */
    private var handlePulse: ObjectAnimator? = null

    /**
     * 上一次画出来的**状态签名**（T4-5）：进度巡检只在签名变化时才重画。
     *
     * 有了它，跑号每推进一步步数变一次 → 重画一次；正常跑着的那几秒里一个字都不动。
     */
    private var lastStatusSignature: String? = null

    /** 手柄位置 = 布局常量（2026-09-17 用户口径"取消状态标签"后，唯一剩下的位置）。 */
    private val positions: FloatingPosition = FloatingPosition.DEFAULT
    private var expanded = false

    // ---- 状态标签窗（顶部居中、可拖动；2026-09-28）----

    /** 标签视图（挂着的时候非空）。 */
    private var label: TextView? = null

    /** 标签窗是否已挂到 WindowManager（读整屏时会被摘掉，读完再挂回来）。 */
    private var labelAttached = false

    /** 标签当前写着的文案：同一句话不重复写（写文字要重新测量 + 重新落位）。 */
    private var labelText: String? = null

    /**
     * 标签位置（比例）。null = 用户没拖过 ⇒ 顶部居中；
     * **每次落位后都会更新它**，所以它永远等于"标签现在在哪"——[applyPositions] 按它重算不会把
     * 用户拖出来的位置或"按中心对齐"后的位置跳回去。
     */
    private var labelRatios: FloatingLabelPosition.Ratios? = null

    /** 拖动过程中的按下点（raw 坐标）与按下那一刻的窗口位置。 */
    private var labelDownRawX = 0f
    private var labelDownRawY = 0f
    private var labelDownX = 0
    private var labelDownY = 0

    /** 这一轮触摸有没有越过拖动阈值（没越过的抬手 = 单击，本标签**不做任何事**）。 */
    private var labelDragging = false

    /**
     * 上一次给标签算位置时用的屏幕尺寸。
     *
     * **只有它变了才重算**（转屏 / 改分辨率）：[applyPositions] 会被"视图尺寸变化"频繁触发，
     * 每次都按比例重算会把"刚拖好的位置"和"刚按中心对齐的位置"跳回去。
     */
    private var labelScreenAt: Pair<Int, Int>? = null

    /**
     * 收起时记下的"再展开要回哪儿"（2026-09-25）：为真 = 回到「添加这一屏的服务器」的读数页
     * （`menuState` 在收起那一刻就被清成根了，所以标志必须**在收起前**算好并单独存着）。
     */
    private var resumeCaptureOnExpand = false

    /**
     * 「读整屏」的动作（现只剩 [onCaptureServers]）把面板收起来之后，**最迟**什么时候必须把面板放回来（elapsedRealtime）。
     *
     * 那条路读的是**整块屏幕的镜像**，我们自己的面板也在屏幕上 ⇒ 面板要**让开**（见 [onCaptureServers]）。
     * 而"结果永远不来"这条路必须有人兜住（帧管线卡住 / 采集会话刚断）：面板若就此消失，
     * 用户看到的就是"功能坏了"。到点照旧展开回读数页 —— 那一页自己会写"读取中…"和该看哪儿。
     */
    private var ocrResumeDeadlineMs = 0L

    /** 菜单层级 / 原因 / 待确认好友 —— 判定全在纯逻辑 [FloatingMenu] 里，这里只是持有它。 */
    private var menuState = FloatingMenu.State()

    /** 菜单里可选的服务器 / 好友（展开时读一次清单；名字只能来自它们 —— 引用口径）。 */
    private var serverNames: List<String> = emptyList()
    private var friendNames: List<String> = emptyList()

    /** 当前这一页里的**可滚区**（[boundedScroll] 每次建都记下来）：给 [fitMenuToScreen] 收矮用。 */
    private var lastBuiltScroll: ScrollView? = null

    /**
     * 复核页里用户挑过的单选（键 = `ServerCaptureDraft.choiceKey`）。
     *
     * 放在面板这一侧而不是草稿里：它是**界面选择**，草稿只认"读到了什么"；
     * 新一轮采集 / 放弃 / 写入后清空（[clearCaptureChoices]）。
     * **每个字段各一把键**（同一张卡片里「服务器名」与「用户名」互不影响）。
     */
    private val captureChoices = mutableMapOf<String, String>()

    private fun clearCaptureChoices() = captureChoices.clear()

    /** 一键拜访的预设（展开时读一次）：一级「拜访」点一次就按它执行。 */
    private var visitPreset: VisitPreset = VisitPreset.EMPTY

    /** 菜单窗是否已挂到 WindowManager（展开 = 挂上，收起 = 摘掉）。 */
    private var menuAttached = false

    /** 屏幕尺寸 / 方向变化 → 重新落位（转屏后窗口尺寸可能不变，位置却已失效）。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.post {
                // **几何一变就重画**（2026-09-25 用户报障：切后台再回前台，面板高度溢出屏幕）。
                // 原因：可滚区的高度是**建这一页时按当时的屏幕高**算死的（`boundedScroll`），
                // 切到我们的 App（竖屏 792dp）时重建过一版，回游戏（横屏 360dp）后还挂着那版高度
                // ⇒ 内容比屏幕高。只 `applyPositions`（改 x/y）救不了尺寸，必须按新几何重建。
                renderMenu()
                applyPositions()
            }
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
        // 状态标签（第三窗）：用户拖过的位置从盘里读回来（没拖过 = 顶部居中）
        labelRatios = FloatingLabelStore.load(context)
        attachLabel()
        applyPositions()
        startStatusWatch()
        MmLog.i(
            TAG,
            "悬浮窗已挂载（手柄停靠 ${positions.side.token}、纵向比例 ${positions.yRatio}）",
        )
    }

    /** 整窗移除：不可见且不占任何触摸；回前台以**收起态**重建（FR-07）。重复调用安全。 */
    fun hide() {
        val panelView = panel ?: return
        detachDisplayRefresh()
        stopStatusWatch()
        detachLabel()
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
        handlePulse?.cancel()
        handlePulse = null
        handleBar = null
        // 下次以收起态重建时按当时的实际状态重新判定（不留"上一轮的告警"）
        handleAlert = false
        expanded = false
    }

    // ---- 进度巡检（T4-5：把手柄与菜单的"现状"跟住跑号）----

    /**
     * 每 [STATUS_TICK_MS] 看一眼**状态签名**，变了才重画。
     *
     * 为什么是"轮询 + 签名比较"而不是"流程主动通知悬浮窗"：跑号跑在帧线程上（`CaptureService`），
     * 它不该知道悬浮窗存在（M3 起的分层：菜单只发请求、流程只写 [PatrolSession]）。
     * 轮询的代价 = 主线程每 0.5s 读几个 `@Volatile` 字段拼一个短字符串，比跨线程回调的时序坑便宜得多。
     *
     * 挂载期间**一直**跑（收起态也跑）：手柄要在收起时变色 —— 那正是"用户没在看菜单、最需要提醒"的时候。
     */
    private val statusTick = object : Runnable {
        override fun run() {
            if (panel == null) return
            refreshStatus()
            mainHandler.postDelayed(this, STATUS_TICK_MS)
        }
    }

    private fun startStatusWatch() {
        mainHandler.removeCallbacks(statusTick)
        lastStatusSignature = null
        mainHandler.post(statusTick)
    }

    private fun stopStatusWatch() {
        mainHandler.removeCallbacks(statusTick)
        lastStatusSignature = null
    }

    /**
     * **现在该显示的那次执行**：正在跑的优先；没有在跑时用"刚跑完的结果快照"（[PatrolResultSignal]）。
     *
     * 用户不一定正盯着屏幕，而"跑完了"这件事**唯一的可见信号**就是顶部标签（底部浮窗提示 2026-09-29 已按
     * 用户口径整体移除）—— 可流程一到终点就 `PatrolSession.stop()` ⇒ `current == null` ⇒ 标签判"待命"、
     * **整条摘掉**：真机实录（2026-09-30 验收）**用户从来没看到过「已完成」**（打完那一枪后 6ms 标签就没了，
     * 那 6ms 里写的还是第 10 步的步骤名「完成」）。
     *
     * ⇒ 界面这一侧统一走本方法：**标签、菜单进度块、状态签名、读屏播报**四处同一个来源，
     * 不会出现"标签说完成、菜单说没有流程"。快照的窗口与作废口径见 [PatrolResultSignal]。
     *
     * ⚠ 只给"显示 / 播报"用：**"能不能继续"与"用户点了停止、停在第几步"仍必须读 [PatrolSession.current]**
     * —— 那两处问的是"真在跑的那个流程"，快照答不了（也没有「继续」可点，"已完成"是终态）。
     */
    private fun patrolForDisplay(): PatrolFlow.State? =
        PatrolResultSignal.displayState(PatrolSession.current, SystemClock.elapsedRealtime())

    /**
     * 状态签名：进度 + 状态行 + 能不能继续。
     *
     * **不含"最近一句话"之外的逐轮噪声** —— 签名里出现的东西一变就会重画面板，所以只放"用户需要
     * 看到它变"的三件事（步数、为什么停、有没有「继续」）。
     */
    private fun statusSignature(): String {
        val state = patrolForDisplay()
        return listOf(
            PatrolStatus.progressLine(state).orEmpty(),
            PatrolStatus.statusLine(state, PatrolSession.lastNote).orEmpty(),
            PatrolStatus.canResume(state).toString(),
            // **控制行在不在**（2026-10-01）：它只看"真在跑的那个流程"（见 `PatrolStatus.showsControlRow`），
            // 与上面那两行（进度 / 状态，跑完的 30 秒里还要显示结果）**不是同一个判据** ⇒ 单独进签名，
            // 否则跑完那一刻面板不会重画、那个多余的控制行会一直挂着。
            "ctrl=${PatrolStatus.showsControlRow(PatrolSession.current)}",
            // 读数请求（2026-09-23）：请求计数 / 是否在跑 / 报告指纹 —— 结果到货就靠这一项触发重画
            "ocr=${FriendListOcrSignal.tick}|${FriendListOcrSignal.running}|" +
                "${FriendListOcrSignal.report?.hashCode() ?: -1}" +
                // 采集复核（2026-09-25）：草稿到货 / 被清掉同样要触发重画（复核页靠它刷新）
                "|cap=${FriendListOcrSignal.capture?.hashCode() ?: -1}" +
                // **「关弹窗受阻 / 已停手」的原因**（2026-09-29 / 2026-09-30）：它是**帧线程**写的
                //（`PopupCloseSignal`），一变就要让根菜单那一行跟着变（原来只在日志里，菜单永远不知道）✓
                // ⚠ 停手原因必须在签名里：真机报障"弹窗没关掉、界面一句话都没有"里，恰恰是它变了而签名没变。
                "|popupBlocked=${PopupCloseSignal.blockedReason}" +
                    "|popupGaveUp=${PopupCloseSignal.gaveUpReason}" +
                    // **画面停更**（2026-09-30，L2）：它一变，菜单最上面那一行就要出现 / 消失。
                    // ⚠ 只放**布尔**，不放秒数 —— 秒数每秒都变，进签名 = 每 0.5s 重画整个面板。
                    "|frameStale=${FrameFreshness.isStale()}" +
                    // **这次授权没被投喂**（2026-10-01，方案 2）：它一变，标签与菜单最上面那一行就要跟着变。
                    // 同样只放**布尔**（放秒数会每 0.5s 重画整个面板）。
                    "|frameStarved=${FrameFreshness.isStarved()}" +
                    // **"点击被拒"的原因**（2026-09-30）：它一变，菜单状态行那一句就要跟着变
                    "|denied=${PatrolSession.deniedNote}",
        ).joinToString("|")
    }

    private fun refreshStatus() {
        // **放在签名短路之前**：读整屏"让开"的收尾有一条出口是**超时**（签名不会变），
        // 放在后面就永远等不到它 —— 面板会就此消失。
        resumeOcrPanelIfReady()
        // 状态标签与菜单各自独立（标签收起态也在），同样放在签名短路之前
        refreshLabel()
        // 中止 / 暂停 / 完成说一句给读屏（底部浮窗提示移除后，这条通道改由标签承担）
        announcePatrolOutcome()
        val signature = statusSignature()
        if (signature == lastStatusSignature) return
        lastStatusSignature = signature
        applyHandleAlert()
        // 根菜单画进度块；读数页（复核页）也必须跟着刷 —— 结果（草稿）是**异步**到货的（帧线程写完挂信号），
        // 不重画就永远停在"读取中…"。（签名只在真变化时改，所以不会把用户正在滚的列表反复重置。）
        // 其它层级是"选目标"的临时界面，重画会打断用户读列表
        // （进层级时本来就把进度块留在根上，回到根立刻是最新的）
        if (expanded &&
            (menuState.level == FloatingMenu.Level.ROOT || menuState.level == FloatingMenu.Level.OCR_RESULT)
        ) {
            rerender()
        }
    }

    /**
     * 「读整屏」期间"面板让开"的收尾：把面板展开回读数页。
     *
     * **三条出口都必须放回来**（少一条的后果都是"面板莫名其妙没了"，看起来就是功能坏了）：
     * ① 结果到货（`FriendListOcrSignal.report`）；② 帧线程回了话（`running=false` —— 失败也回话）；
     * ③ 兜底超时（帧管线卡住 / 采集会话刚断 ⇒ ①② 都不会发生）。
     *
     * 只在"面板是收起的 + 当前层级是读数页"时动手：用户中途自己点开手柄（层级回到根）
     * 或关了悬浮窗，都不该被这一口抢回去。
     */
    private fun resumeOcrPanelIfReady() {
        if (expanded) return
        if (menuState.level != FloatingMenu.Level.OCR_RESULT) return
        val ready = FriendListOcrSignal.report != null ||
            !FriendListOcrSignal.running ||
            SystemClock.elapsedRealtime() >= ocrResumeDeadlineMs
        if (!ready) return
        expanded = true
        renderMenu()
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, "读整屏后的面板已放回（结果到货或等过头）")
    }

    /**
     * 手柄的**告警三通道**（T4-5）：配色 琥珀黄 → 暖橙红、形状 32dp → 44dp、进入告警态时**脉冲 3 次**。
     *
     * 收起态不写字（2026-09-17 用户口径"取消状态标签"），"停在半路上"这件事就只能靠手柄自己传出去。
     * 交互顾问（2026-09-20）明确指出**颜色一个人扛不住**（色觉障碍、背景同为暖色、根本没扫到右缘），
     * 所以补上形状与一次性动效：三取其一能被感知到就够了。
     *
     * 动效**只做一次**（约 1.2s）然后停在静态高亮：常驻闪烁会挡视线、耗电，也违背"不制造视觉噪音"的既定口径。
     * 读屏那一侧用 `contentDescription` + 一次主动播报（`announceForAccessibility`）。
     */
    private fun applyHandleAlert() {
        val bar = handleBar ?: return
        val alert = PatrolSession.canResume
        if (alert == handleAlert) return
        val enteringAlert = alert
        handleAlert = alert
        bar.background = handleBackground(positions.side, alert)
        bar.contentDescription = context.getString(
            if (alert) R.string.floating_handle_desc_alert else R.string.floating_handle_desc,
        )
        // 形状（宽 7dp 不变、只加高）：色觉障碍 / 暖色背景下的第二通道。窗高 48dp 装得下 48dp 的条
        (bar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.height = dp(if (alert) HANDLE_ALERT_HEIGHT_DP else HANDLE_VISUAL_HEIGHT_DP)
            bar.layoutParams = lp
        }
        if (enteringAlert) {
            pulseHandle(bar)
            // 中止 / 暂停是"需要用户决策"的事件：主动播报一句（不依赖面板里的 live region —— 那一块
            // 是每次重画新建的视图，读屏未必认为它"内容变了"）
            bar.announceForAccessibility(context.getString(R.string.floating_handle_desc_alert))
        } else {
            handlePulse?.cancel()
            handlePulse = null
            bar.alpha = 1f
        }
    }

    /** 呼吸 3 次（[HANDLE_PULSE_HALF_MS] 半周期 × 往返）：告警的第三通道，做完就停在高亮态。 */
    private fun pulseHandle(bar: View) {
        handlePulse?.cancel()
        handlePulse = ObjectAnimator.ofFloat(bar, View.ALPHA, 1f, HANDLE_PULSE_LOW_ALPHA).apply {
            duration = HANDLE_PULSE_HALF_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = HANDLE_PULSE_COUNT
            start()
        }
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
        handleBar = visualBar
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
        // 面板宽度默认由内容撑（`WRAP_CONTENT`）：复核页会临时把它改成**半屏宽**（见 [renderCaptureReview]），
        // 换层级时必须先还原 —— 否则那一页的宽度会跟着跑到别的页面上。
        container.minimumWidth = 0
        lastBuiltScroll = null
        when (menuState.level) {
            FloatingMenu.Level.ROOT -> renderRoot(container)
            FloatingMenu.Level.PICK_SERVER -> renderPickServer(container)
            FloatingMenu.Level.PICK_FRIEND -> renderPickFriend(container)
            FloatingMenu.Level.MORE -> renderMore(container)
            FloatingMenu.Level.CONFIG -> renderConfig(container)
            FloatingMenu.Level.CONFIG_SERVER -> renderConfigServerPick(container)
            FloatingMenu.Level.CONFIG_FRIEND -> renderConfigFriendPick(container)
            FloatingMenu.Level.OCR_RESULT -> renderOcrResult(container)
        }
        // 底部信息条：**失败原因**（红）或**提示 / 回执**（中性色）—— 同一条位置，二者互斥
        //（不另开 Toast：玩家在游戏里不会去看系统通知）。
        // **配色是语义**（用户 2026-10-01 口径："提示类信息不应该使用红色"）：红只留给"你被拒了 /
        // 当前不可用"；"提示位置已重置""当前没有进行中的巡查"这类**只是告知**的话走中性色。
        val note = menuState.reason ?: menuState.notice
        reasonText = textView(11f, if (menuState.reason != null) REASON_COLOR else NOTICE_COLOR).also {
            it.maxWidth = dp(REASON_MAX_WIDTH_DP)
            it.setPadding(dp(6), dp(2), dp(6), dp(2))
            it.text = note.orEmpty()
            it.visibility = if (note == null) View.GONE else View.VISIBLE
            container.addView(it)
        }
        // 内容换完**立刻按新内容量一次**（2026-09-19 修「原因条不显示」）：
        // [applyPositions] 读的是 `measuredWidth/measuredHeight`（见 [viewSize]），这里不重新量的话，
        // 它拿到的还是**上一版内容**的尺寸 → 窗口高度不跟着长 → 新加的原因条被裁在窗口外，
        // 用户看到的是"点了保存毫无反应"（真机 logcat 里 `fail` 明明打了一串）。
        // 放在本方法内而不是各调用点，是让"内容一变就重新量"成为 renderMenu 的固有语义，避免再漏。
        measureSelf(container)
        // **内容比屏幕高就就地压回去**（2026-09-25 用户两次报障）：详见 [fitMenuToScreen]
        fitMenuToScreen(container)
        // 画完这一版就等于"已跟上当前状态"：巡检据此跳过无变化的重画（T4-5）
        lastStatusSignature = statusSignature()
        // 面板**内容变了**同样是"显示内容变化" ⇒ 该来新帧（见 `FrameExpectationSignal`）
        FrameExpectationSignal.arm("菜单内容更新")
    }

    /**
     * 根层，顺序 = 换号 / 拜访 / 换号+拜访 / 更多 / 返回 App（+ 有流程时的进度块与控制行）
     * （高频在上，误触代价最大的「停止」放最底）。
     *
     * 2026-09-30 用户口径：**根菜单再精简一行** —— 「拜访规则」撤掉，
     * 那份预设的入口改挂在「换号+拜访」行尾的 ⚙ 上（预设只被这一行用）。
     *
     * 「换号+拜访」点一次就执行整条流程（换号 + 拜访），目标来自**预设** ——
     * 用户最高频的场景是"在同一个好友的农场里轮流换不同服务器的小号来看他"，
     * 每点一次都先选服务器再选好友太费事（2026-09-16 用户口径）⇒ 这一行**保持 1 击执行**，
     * 参数改走行尾 ⚙（2026-09-30 口径）。
     *
     * 三行的命名（2026-09-19 / 2026-09-30 用户口径）：「拜访」= 不换号、在当前区服直接去（先选好友）；
     * 「换号」= 只换号（先选区服）；**「换号+拜访」= 整条走完**（按预设，1 击）。
     *
     * **跑号进度块**（T4-5）排在最上：展开菜单的人第一眼要回答的是"现在跑到哪了 / 为什么停"，
     * 而不是"我还能点什么"。没在跑时它整块不出现。
     *
     * **「继续」不在这里，它在最底下的流程控制行**（[controlRow]）：交互顾问指出，把「继续」放在顶部
     * 会让面板在"中止那一刻"长高 48dp，正好是用户可能正在点上面那几行的一刻 ——
     * 行位移 = 误点相邻项（例如误触「换号+拜访」直接开一局）。放进底部的固定行，竖直方向**零位移**。
     */
    private fun renderRoot(container: LinearLayout) {
        // **画面久未更新**（2026-09-30，L2）：放在**最上面** —— 帧停更时下面那些行（"跑到第几步了"）
        // 本身也建立在旧画面上（识别读的是重放的旧帧）。进度块与原因行照常显示，只是先把这件事说清楚。
        // 判决器在采集层（`CaptureService.checkFrameStall`）：停更 ⇒ 停止一切点击，**不结束会话**；
        // 这里只是把"为什么现在什么都不动"如实说出来（用户 2026-09-29 口径：原因要能在菜单里看到）。
        // ⚠ 只把**布尔**放进 `statusSignature`（见那里）：秒数每秒都在变，进了签名就会每 0.5s 重画整个
        // 面板（用户可能正在滚列表）。秒数取"画这一行的那一刻"的值 ⇒ **打开菜单**时看到的是当时的真实帧龄。
        // **这次授权没被投喂**（2026-10-01，方案 2）：比"久未更新"更确定（建立会话起零帧），
        // 处置也不同（换进程 vs 只重新授权）⇒ 单独一行、放在最上面（它不与下面那条同时出现：
        // 从没收到帧时 `ageMs()` 是 null ⇒ `isStale()` 为假）。
        if (FrameFreshness.isStarved()) {
            container.addView(
                reportRow(
                    context.getString(
                        R.string.floating_frame_starved_notice,
                        FrameFreshness.FIRST_FEED_DEADLINE_MS / 1000,
                    ),
                ),
            )
        }
        val frameAgeMs = FrameFreshness.ageMs()
        if (frameAgeMs != null && FrameFreshness.isStale()) {
            container.addView(
                reportRow(context.getString(R.string.floating_frame_stalled_notice, frameAgeMs / 1000)),
            )
        }
        // **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入）：判据在纯逻辑
        // [FloatingMenu.shouldOfferReauthorize] —— 只在"会话没了 / 画面停更 / 这次授权没被投喂"时出现；
        // 正常跑着时**不占这一行**（面板长高会让正在点的行整体位移 ⇒ 误点相邻项）。
        // 位置紧挨在它要解决的那条原因行下面：**看完原因，顺手就点**（原来得"收起面板 → 回 App →
        // 找授权页 → 点建立采集"四步）。常驻通路在「更多」层（见 [renderMore]）。
        if (FloatingMenu.shouldOfferReauthorize(
                captureActive = CaptureSessionSignal.isActive,
                frameStale = FrameFreshness.isStale(),
                frameStarved = FrameFreshness.isStarved(),
            )
        ) {
            container.addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_reauthorize),
                    R.string.floating_menu_reauthorize,
                ) { onRequestReauthorize() },
            )
        }
        // 进度块（T4-5）：**有流程才出现** —— 没在跑时面板与 M3 完全一样，不多占一行；
        // 刚跑完的那 30 秒里也要能在这里读到结果（"第 10/10 步 · 完成 / 本次执行已完成"），
        // 见 [patrolForDisplay]。（这是第 327 条真机验收发现的补课：跑完那一刻标签与菜单一起空了。）
        val patrol = patrolForDisplay()
        if (patrol != null) {
            container.addView(statusBlock(patrol))
        }
        // **「关弹窗受阻 / 已停手」的原因**（2026-09-29、2026-09-30 用户口径："把原因也显示在悬浮窗菜单里"）。
        // 顶部标签只能写 12 个字（`关弹窗受阻` / `关弹窗已停手`），原因一直只落在日志里 ⇒ 玩家在游戏里
        // 看不到"为什么关不掉"。
        // **两层原因只显示一行、前缀随状态变**（2026-09-30）：停手（不再尝试）优先于受阻（还在找机会），
        // 两行一起显示只会互相打架；停手原因由 `PopupCloseSignal.gaveUpReason` 给出。
        // 用 [reportRow] 而不是 [noteRow]：原因是整句话，菜单窗是 `WRAP_CONTENT`，不限宽会被撑到近 880dp
        //（真机踩过，见 [reportRow] 的注释）。`PopupCloseSignal` 清掉原因（弹窗消失 / 换屏 / 真的发了一枪）时，
        // 这行自己就没了 —— 由 [statusSignature] 触发重画。
        val popupNotice = FloatingMenu.popupBlockedNotice(
            PopupCloseSignal.gaveUpReason.ifEmpty { PopupCloseSignal.blockedReason },
        )
        popupNotice?.let { reason ->
            container.addView(
                reportRow(
                    context.getString(
                        if (PopupCloseSignal.gaveUpReason.isNotEmpty()) {
                            R.string.floating_popup_gave_up_reason
                        } else {
                            R.string.floating_popup_blocked_reason
                        },
                        reason,
                    ),
                ),
            )
        }
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
                // 2026-09-30：只拜访改成**先选好友**（不再按预设直接走）⇒ 文案收成「拜访」（用户口径），
                // 且这一行**必须走 openGroup**（原来直接调 onVisitOnly() ⇒ 二级菜单永远不弹 —— 第一版就漏在这儿）。
                context.getString(R.string.floating_menu_visit_pick),
            ) { openGroup(FloatingMenu.Group.VISIT) },
        )
        // 换号 + 拜访：整条走完。
        //
        // **两个可点区域**（2026-09-30 用户口径，交互顾问评审后定）：
        // - **整行** = 按当前预设**直接执行**（1 击，保住最高频场景的手感）；
        // - **行尾 ⚙** = 进参数层改服务器 / 好友（原根菜单的「拜访规则」那一行**撤掉** ——
        //   那份预设只被这一行用，配置就该挂在它身上）。
        // ⚠ 行尾按钮是**菜单里原本没有的范式**（其余行要么整行开下一级、要么整行执行）：用户已同意。
        val switchVisitMain = context.getString(R.string.floating_menu_switch_visit_main)
        // 目标（服务器 => 好友）走**副文**（2026-09-30 用户报障"这个文案太长了"）：
        // 原来拼在同一行（`换号+拜访 顺序轮换→阿娜雅`）又长又挤 ⇒ 拆两行后主文与其它行齐平，
        // 目标仍然一眼看得到（**不是**藏进参数层）。
        val switchVisitSub = if (visitPreset.isReady) {
            val serverText = visitPreset.serverName
                .takeIf { visitPreset.serverChoice == ServerChoice.FIXED && it.isNotBlank() }
                ?: context.getString(R.string.floating_menu_server_next)
            context.getString(
                R.string.floating_menu_switch_visit_target,
                serverText,
                visitPreset.friendName,
            )
        } else {
            context.getString(R.string.floating_menu_switch_visit_unset)
        }
        container.addView(
            menuRowWithTrailing(
                glyph = context.getString(R.string.floating_menu_glyph_switch_visit),
                text = switchVisitMain,
                subText = switchVisitSub,
                // 读屏：整行念一句（主文 + 目标），⚙ 另算一个节点
                description = context.getString(
                    R.string.floating_menu_switch_visit_desc,
                    switchVisitMain,
                    switchVisitSub,
                ),
                trailingDescription = context.getString(R.string.floating_menu_edit_params),
                onTrailing = { openSwitchVisitConfig() },
            ) { onVisitPreset() },
        )
        // **「更多」**（2026-09-30 用户口径）：主菜单只留高频项；「导入服务器」「暂停自动关弹窗」「退出」这类
        // **不启动流程 / 只维护数据**的动作都在这一层（原来它们与高频项并排占好几行）。
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_more),
                R.string.floating_menu_more,
            ) { openGroup(FloatingMenu.Group.MORE) },
        )
        //（「退出」已按用户口径挪进「更多」层 —— 主菜单再短一行。）
        // **返回 App**（T4-7，2026-09-24 用户要求）：把本 App 拉到前台。
        // 位置：在「更多」之后、**流程控制行之前** —— 控制行"永远在最底、竖直零位移"是既定口径
        // （见 [controlRow]：面板长高一行会让正在点的行整体位移 ⇒ 可能误触「换号+拜访」⇒ 立刻开一局）。
        // 它是**动作**、误触代价为零（最坏就是回到 App），所以不必挤到最上面。
        // `context` 是无障碍 Service ⇒ **必须带 FLAG_ACTIVITY_NEW_TASK**；CLEAR_TOP 让已在栈里时回到原地。
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_open_app),
                R.string.floating_menu_open_app,
            ) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            },
        )
        // ⚠ 「⟳ 重新授权采集」这一行**已按用户口径取消**（2026-09-30）：它要做的事
        //（回本 App 的授权页）「返回 App」本来就能做到，留着只是重复一行。
        // **暂停 / 恢复「自动关弹窗」**（2026-09-30 加；**同日挪进「更多」** —— 主菜单只留高频项）：有些界面本来就有"关闭 / 返回"按钮
        // （**不是活动弹窗**），那时不该自动点 ⇒ 用户要能**随时**停掉这条自动化、再随时恢复。
        // ⚠ 它不像"停止流程"那样只在有流程时才出现：**随时都得能按**（这正是它的用途）。
        // 暂停期间识别照常（状态照判、日志照记），只是**不动手**。
        //（「暂停/恢复自动关弹窗」已按用户口径挪进「更多」层 —— 主菜单只留高频项。）
        // 流程控制行（T4-5）：**有流程才出现**（2026-09-22 用户口径："当前没有进行中的流程时，
        // 不该显示停止按钮"）。没流程时这一行整块不摆 —— 一个点了只会弹"当前没有进行中的流程"的
        // 按钮是纯噪声，还让人以为"有东西没停掉"。
        // ⚠ 判据读 `PatrolSession.current`（**真在跑的那个**），**不能**读上面那份 `patrol`（界面显示态）：
        // 跑完那一刻 `current` 已经清空，而显示态还会供 ≈30 秒的「已完成」快照 ⇒ 用显示态判就会画出
        // 一个点了只说"当前没有进行中的流程"的「停止」（2026-10-01 用户报的 bug，见 `PatrolStatus.showsControlRow`）。
        val running = PatrolSession.current
        if (PatrolStatus.showsControlRow(running)) {
            container.addView(controlRow(resumable = PatrolStatus.canResume(running)))
        }
    }

    /**
     * 流程控制行（T4-5）：**此行的位置在面板里永远不变**（最底一行），变的只是行内几个按钮。
     *
     * 为什么必须这样（交互顾问 2026-09-20 指出）：中止 / 暂停是**用户可能正在点菜单的一刻**发生的，
     * 如果那时面板"长高一行「继续」"，正在点的行会整体位移 48dp → 可能误点相邻的「换号拜访」，
     * 而那个误触会**立刻开一局拜访**。把它收进固定控制行，竖直方向零位移，风险归零。
     *
     * 运行中不摆「继续」：一个点了没用的按钮会让人以为"卡住了点一下就好"。
     * 也没做"常驻但置灰"：横屏 360dp 下每一行都金贵（交互顾问同样建议不做）。
     */
    private fun controlRow(resumable: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        if (!resumable) {
            addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_stop),
                    R.string.floating_menu_stop,
                ) { onStop() },
                halfRowParams(),
            )
        } else {
            addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_resume),
                    R.string.floating_menu_resume,
                    // 读屏要说清"从哪继续"：光念「继续」，用户不知道是接着第 3 步还是从头再来
                    description = context.getString(
                        R.string.floating_menu_resume_desc,
                        PatrolSession.current?.step?.number ?: 0,
                    ),
                ) { onResume() },
                halfRowParams(),
            )
            addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_stop),
                    R.string.floating_menu_stop,
                ) { onStop() },
                halfRowParams(),
            )
        }
    }

    /** 控制行里半边按钮的布局参数（各占一半宽）。 */
    private fun halfRowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    /**
     * 换号 → 选服务器：首行「下一个」（点一下立刻换下一个），下面才是服务器清单。
     *
     * **这一层只有"换号"相关的东西**（2026-09-25 用户口径）：添加服务器**不属于**换号的下级动作
     * ⇒ 已搬到根菜单（与「试读这一屏」并排）。这里保留的判据是"这一层做的事 = 选一个区服切过去"。
     */
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
                    pickRow(name) { onSwitchServer(name) }
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
     * 保存设置：写盘（临时文件 + rename），**写成功就直接按预设开始跑**（2026-10-01 用户口径：
     * 「保存」= **保存并触发动作**；按钮文案同步改成「保存并开始」）。
     *
     * 三条边界（都没变）：
     * - 草稿不完整（没选好友 / 指定区服却没选服）⇒ **不写盘、不触发**，原地说明缺什么；
     * - 写盘失败 ⇒ 原地说明原因，草稿不丢（可以直接再试一次，不用重新选一遍）；
     * - 触发被前置检查拦下（没有采集会话 / 跑号清单读不了）⇒ [fail] 停在原地说明原因 ——
     *   **预设此时已经保存好了**，那只影响"这一次开跑"。
     *
     * 写完顺手刷新内存里的预设 —— 一级「换号+拜访」那一行的副文立刻跟着变。
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
        // 保存成功 → **直接开始跑**（2026-10-01 用户口径：「保存」改成「保存并触发动作」）。
        //
        // 为什么：进这一层的**唯一目的**就是"这一次换到哪个服、去谁家" —— 配完还要退回一级再点一次
        // 「换号+拜访」是多余的一步。提示语仍然留一句（万一触发被前置检查拦下，[fail] 会盖掉它并说明原因）。
        menuState = FloatingMenu.configSaved(
            menuState,
            context.getString(
                R.string.floating_menu_config_saved,
                serverSummary(draft),
                draft.friendName,
            ),
        )
        MmLog.i(TAG, "保存成功 ⇒ 按预设直接开始（保存并触发动作）")
        // 触发走**与整行点击同一条路**（[onVisitPreset]）：同一套前置检查（采集会话 / 清单可读）、
        // 同一套"失败就停在原地说明原因"；成功则由 [sendStartRequest] 收起面板开始跑。
        onVisitPreset()
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
     * 读数页（2026-09-23 起）：一枚返回 + 页面主体。
     *
     * 现在**只剩一种读数动作**：**导入服务器**（[FriendListOcrSignal.Mode.CAPTURE_SERVERS]）
     * ⇒ [renderCaptureReview] 的**复核页**（逐条标记 + 「写入清单」），2026-09-25 用户口径"用户确认后导入"。
     *
     * （原「试读这一屏」的报告页 [renderProbeReport] 已随该功能整体移除，见 2026-09-30 用户口径。）
     * 它与复核页曾共用同一层（`Level.OCR_RESULT`）与同一套"读数页"待遇（见 [FloatingMenu.isCaptureResultPage]）。
     */
    private fun renderOcrResult(container: LinearLayout) {
        container.addView(
            menuRow(context.getString(R.string.floating_menu_glyph_back), ocrTitleRes()) {
                // 返回**根菜单**：入口在一级（「更多 → 导入服务器」），所以"回上一级"就是回根。
                // 沿革：2026-09-25 之前「一键添加服务器」挂在「换号 → 选服务器」下，那时返回要回那一层
                // （否则想再读下一屏得重走"换号 → 一键添加"）；入口搬到一级之后那条口径自然消失，
                // 判据从"按模式分派"退回"一律回根"（少一处会因为新增模式而漏的地方）。
                backToRoot()
            },
        )
        renderCaptureReview(container)
    }

    /**
     * **采集复核页**（2026-09-25 用户口径：**先看后写**；用户原话"重新设计用户复核识别结果的交互，
     * 需要用户确认后导入"）。
     *
     * 页面结构（自上而下）：
     * ```
     * ⚠ 这一帧是 40 秒前拍的…          ← 有旧画面 / 说明时才有
     * 已读到 12 台：新增 3 ｜ 补全 2 ｜ 已存在 7 ｜ 不写入 0
     * 1 白色死神        ＋新增
     *   56区 · Lv.30 · 卧龙山小当家
     * …
     * ⚠ 读成「漢地绿洲」，清单里是「漠地绿洲」（同 409 区）—— 不写入，请核对
     * [ 写入清单（5 条） ]
     * [ 放弃这次采集 ]
     * [ 再读一屏 ]                     ← 滚一屏再读，**并进同一份草稿**
     * ```
     *
     * 三条口径：
     * 1. **面板里不能改字**（悬浮窗没有输入法）：要改去 App 的服务器清单页；这里只保证"看得清 + 按一下"；
     * 2. **写入是唯一的落盘点**（[writeCaptureDraft]）：不点写入，一个字节都不动；
     * 3. **「再读一屏」是累积**：一屏只看得到 12 台，14 台必须读两屏 —— 攒齐了再一次性写入。
     */
    private fun renderCaptureReview(container: LinearLayout) {
        if (FriendListOcrSignal.running) {
            container.addView(noteRow(context.getString(R.string.ocr_probe_running)))
            return
        }
        val outcome = FriendListOcrSignal.capture
        val draft = (outcome as? ServerCaptureOutcome.Draft)?.draft
        (outcome as? ServerCaptureOutcome.Draft)?.notice?.let { container.addView(noteRow(it)) }
        if (draft == null) {
            container.addView(
                noteRow(
                    (outcome as? ServerCaptureOutcome.Nothing)?.message
                        ?: context.getString(R.string.capture_review_nothing),
                ),
            )
            container.addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_capture),
                    R.string.floating_menu_capture_more,
                ) { onCaptureMore() },
            )
            return
        }
        // 与现有清单对一遍（判据全在 `ServerCaptureDraft.review` → `ServerListCapture.merge`，这里只管画）
        val existing = runCatching { ServerListStore.load(context) ?: ServerList.EMPTY }.getOrElse {
            MmLog.w(TAG, "服务器清单读取失败：复核页只显示读数（不判断新增/补全）", it)
            ServerList.EMPTY
        }
        val review = draft.review(existing, captureChoices)
        // **半屏宽**（2026-09-28 用户口径："将添加服务器列表悬浮窗改为半屏宽"）：两列卡片与一行三个按钮
        // 都要靠这个宽度对齐，所以先把它定下来（算式是纯逻辑，在 [FloatingMenu] 里、有单测）。
        val screenWidthDp =
            (FloatingScreen.spec(context).width / context.resources.displayMetrics.density).toInt()
        val cardWidthPx = dp(FloatingMenu.reviewCardWidth(screenWidthDp, PANEL_PADDING_DP))
        val actionWidthPx = dp(FloatingMenu.reviewActionWidth(screenWidthDp, PANEL_PADDING_DP))
        container.minimumWidth = dp(FloatingMenu.reviewPanelWidth(screenWidthDp))
        // 统计**只报读到的台数**（2026-09-28 用户口径：不要"新增/补全/已存在"那一串）
        container.addView(noteRow(context.getString(R.string.capture_review_summary, review.items.size)))
        // **两列**摆放（2026-09-28 用户口径："可以用两列显示，就跟截图中布局类似"）：
        // 读法照抄游戏里那张网格 —— 左→右、然后下一行；序号仍然是读到的先后。
        val cards = review.items.mapIndexed { index, item -> captureCard(index, item) }
        val cardRows = cards.chunked(FloatingMenu.REVIEW_COLUMNS)
            .map { row -> captureCardRow(row, cardWidthPx) }
        container.addView(
            boundedScroll(
                rows = cardRows,
                rowCount = cardRows.size,
                chromeDp = CAPTURE_REVIEW_CHROME_DP,
                rowHeightDp = CARD_ROW_HEIGHT_DP,
            ),
        )
        // 三个动作**并排一行**（2026-09-28 用户口径："『写入清单』『放弃』『再读一屏』放在同一行"）。
        // 顺带按同一轮口径**去掉**了"共 N 台 · 上下滑动看全部"那句提示 —— 可滚区自己带滚动条，
        // "还有内容"看得出来，不必再占一行。
        container.addView(captureActionRow(actionWidthPx))
    }

    /**
     * 复核页里的一张**卡片**（2026-09-28 用户口径："按读取顺序、卡片式排列；精简排版"）：
     *
     * ```
     * ┌──────────────────┐ ┌──────────────────┐
     * │ 1 白色死神        │ │ 2 莲动渔舟        │
     * │   56区 · Lv.30 ·  │ │   395区 · Lv.25 · │
     * │   卧龙山小当家     │ │   卧龙山小麦       │
     * └──────────────────┘ └──────────────────┘
     * ┌──────────────────┐
     * │ 9 409区 · 2 种写法 │
     * │   ◉ 漠地绿洲      │
     * │   ◯ 漢地綠洲      │
     * └──────────────────┘
     * ```
     *
     * 四条口径：
     * - **一张卡片 = 一台**（同一区号的多种写法归在一张卡里 ⇒ 一个区号**只会写入一条**）；
     * - 卡片按**读取顺序**排（第 1 台在最上），序号就是读到的先后；
     * - **不与清单里已有的信息作对比**（用户口径）：卡片上只有"读到了什么"；
     * - **两个字段各选各的**（2026-09-28 用户口径："同一个服务器卡片中应支持多信息独立选中"）：
     *   有得选的字段画成一格单选（见 [fieldBlock]）⇒ 一张卡里"**名字取自这条读数、用户名取自那条读数**"
     *   是被允许的；没有需要定夺的字段时退回**紧凑两行**（`序号 名字` + `区号 · Lv. · 用户名`）。
     *
     * 卡片自己**不设 layoutParams**：宽高由 [captureCardRow] 给（两列等宽、行内等高）。
     */
    private fun captureCard(index: Int, item: ServerCaptureDraft.Item): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        // 没有需要定夺的字段 ⇒ 走**紧凑两行**（绝大多数卡片都是这样，别让它们白占高度）
        if (!item.hasChoices) {
            card.addView(
                cardText(
                    context.getString(R.string.capture_card_title, index + 1, item.chosen.serverName),
                    CARD_TITLE_SP,
                    MENU_TEXT_COLOR,
                ),
            )
            val detail = detailOf(item.chosen)
            if (detail.isNotEmpty()) card.addView(cardText(detail, CARD_DETAIL_SP, MENU_GLYPH_COLOR))
            return card
        }
        // 有字段要选 ⇒ 抬头用**区号**（名字还没定下来，不能拿某一条读数的名字当抬头）
        val head = if (item.chosen.hasLevel) {
            context.getString(
                R.string.capture_card_title_level,
                index + 1,
                item.title,
                item.chosen.level,
            )
        } else {
            context.getString(R.string.capture_card_title, index + 1, item.title)
        }
        card.addView(cardText(head, CARD_TITLE_SP, MENU_TEXT_COLOR))
        // **两个字段各一块**：有得选的画单选，没得选（或没读到）的按 [fieldBlock] 的规则处理
        card.addView(
            fieldBlock(
                item = item,
                field = ServerCaptureDraft.Field.NAME,
                labelRes = R.string.capture_field_server_name,
                options = item.nameOptions,
                chosen = item.chosen.serverName,
            ),
        )
        card.addView(
            fieldBlock(
                item = item,
                field = ServerCaptureDraft.Field.CHARACTER,
                labelRes = R.string.capture_field_character_name,
                options = item.characterOptions,
                chosen = item.chosen.characterName,
            ),
        )
        return card
    }

    /**
     * 卡片里**一个可独立选的字段块**（2026-09-28 用户口径："同一个服务器卡片中应支持多信息独立选中"）：
     * 小标签（`服务器名` / `用户名`）+ 每个候选一行。
     *
     * - 候选 **≥2** ⇒ 每行带圆点，点一下 = "这一台的这个字段用这个写法"；
     * - 候选 **1** ⇒ 只一行普通文字（没得选还画单选圈，只会让人以为可以点）；
     * - 候选 **0**（这次没读到这个字段）⇒ **整块不画**（不留一个空标签）。
     */
    private fun fieldBlock(
        item: ServerCaptureDraft.Item,
        field: ServerCaptureDraft.Field,
        labelRes: Int,
        options: List<String>,
        chosen: String,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (options.isEmpty()) return@apply
        addView(cardText(context.getString(labelRes), CARD_DETAIL_SP, MENU_GLYPH_COLOR))
        options.forEach { option ->
            if (options.size == 1) {
                addView(cardText(option, CARD_DETAIL_SP, MENU_TEXT_COLOR))
            } else {
                addView(optionLine(item, field, labelRes, option, selected = option == chosen))
            }
        }
    }

    /**
     * 复核页里的**一行 = 最多两张卡片并排**（2026-09-28 用户口径："可以用两列显示，就跟截图中布局类似"）。
     *
     * ⚠ **宽度写固定 dp，不能用 `weight`**：菜单窗是 `WRAP_CONTENT`，而 [measureSelf] 用 **UNSPECIFIED**
     * 规格量内容 —— 这种测量下"宽 0 + 权重"的子视图会算成 **0 宽**（卡片直接看不见）。
     * 固定宽还有个好处：两列严格等宽、与游戏里那张网格的观感一致；宽度由"半屏"算出来
     * （[FloatingMenu.reviewCardWidth]，调用方传进来）。
     *
     * 行内卡片高度用 `MATCH_PARENT`：`LinearLayout` 横向测量时会把行内子视图的高度**统一成最高的那个**
     * （`forceUniformHeight`）⇒ 一高一矮两张卡并排时底边齐平，不会出现"半截空框"。
     */
    private fun captureCardRow(cards: List<View>, cardWidthPx: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(CARD_GAP_DP) }
            cards.forEach { card ->
                addView(
                    card,
                    LinearLayout.LayoutParams(
                        cardWidthPx,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    ).apply { rightMargin = dp(CARD_GAP_DP) },
                )
            }
            // 最后一行的第二格空着就补一个占位：否则那张卡会被"居中"（视觉上跑到中间去）
            if (cards.size < FloatingMenu.REVIEW_COLUMNS) {
                addView(View(context), LinearLayout.LayoutParams(cardWidthPx, 1))
            }
        }

    /** 卡片里的普通文字行（抬头 / 明细）。 */
    private fun cardText(text: String, sizeSp: Float, color: Int): TextView =
        textView(sizeSp, color).apply {
            this.text = text
            maxWidth = dp(REASON_MAX_WIDTH_DP)
            setPadding(0, dp(1), 0, dp(1))
        }

    /** 卡片底：面板同色系再亮一档 + 圆角（一眼分得清"哪几行是同一台"）。 */
    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(CARD_CORNER_DP).toFloat()
        setColor(CARD_FILL_COLOR)
    }

    /**
     * 卡片里的一行候选：`◉ 漠地绿洲`（点一下 = **这个字段**用这个写法）。
     *
     * 候选**只有"读到的写法"**（2026-09-28 用户口径：不对比清单里已有的信息）。
     * 用自绘圆点而不是 Material 的 RadioButton：面板是深色窄条，系统的单选圈在这里既占宽又看不清。
     *
     * 点的是"哪个字段"由 [field] 决定 ⇒ 同一张卡片里「服务器名」与「用户名」**互不影响**
     * （键是 `ServerCaptureDraft.choiceKey`）。
     */
    private fun optionLine(
        item: ServerCaptureDraft.Item,
        field: ServerCaptureDraft.Field,
        labelRes: Int,
        option: String,
        selected: Boolean,
    ): LinearLayout {
        val color = if (selected) MENU_TEXT_COLOR else MENU_GLYPH_COLOR
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = tapFeedbackBackground()
            isClickable = true
            setPadding(0, dp(2), 0, dp(2))
            addView(
                textView(CARD_DETAIL_SP, color).apply {
                    text = context.getString(
                        if (selected) R.string.capture_review_mark_on else R.string.capture_review_mark_off,
                    )
                    setPadding(0, 0, dp(6), 0)
                },
            )
            addView(
                textView(CARD_DETAIL_SP, color).apply {
                    text = option
                    maxWidth = dp(REASON_MAX_WIDTH_DP)
                },
            )
            // 读屏要念出"这是哪个字段"：一张卡里有两组圆点，只念"已选"分不清是名字还是用户名
            contentDescription = context.getString(labelRes) + " " + context.getString(
                if (selected) R.string.capture_review_option_selected else R.string.capture_review_option_unselected,
                option,
            )
            setOnClickListener {
                captureChoices[ServerCaptureDraft.choiceKey(item.key, field)] = option
                rerender()
            }
        }
    }

    /**
     * 复核页底部三个动作**并排一行**（2026-09-28 用户口径："『写入清单』『放弃』『再读一屏』
     * 三个菜单放在同一行"）。
     *
     * ⚠ 宽度写**固定 dp**（`widthPx` 由 [FloatingMenu.reviewActionWidth] 按半屏宽三等分算出来）：
     * 面板是 `WRAP_CONTENT` + UNSPECIFIED 测量，`weight` 在这种测量下会算成 **0 宽**（按钮直接消失）。
     * 三个等宽 + 两处间隙 = 内容区宽度 ⇒ 与上面的两列网格**左右对齐**。
     *
     * 「写入清单」用琥珀色：它是**唯一的落盘点**，另外两个是"不写"和"接着读"。
     */
    private fun captureActionRow(widthPx: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(CARD_GAP_DP) }
        addView(actionButton(R.string.capture_write, widthPx, MENU_GLYPH_COLOR) { writeCaptureDraft() })
        addView(actionButton(R.string.capture_discard, widthPx, MENU_TEXT_COLOR) { discardCapture() })
        addView(
            actionButton(R.string.floating_menu_capture_more, widthPx, MENU_TEXT_COLOR) { onCaptureMore() },
        )
    }

    /** 复核页底部的一个动作按钮（同宽的纯文字按钮，带点按反馈与居中）。 */
    private fun actionButton(
        textRes: Int,
        widthPx: Int,
        color: Int,
        onClick: () -> Unit,
    ): TextView = textView(CARD_DETAIL_SP, color).apply {
        text = context.getString(textRes)
        gravity = Gravity.CENTER
        background = tapFeedbackBackground()
        isClickable = true
        setOnClickListener { onClick() }
        minHeight = dp(CARD_BUTTON_HEIGHT_DP)
        layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(CARD_GAP_DP) }
    }

    /** 条目第 2 行：区号 · Lv.等级 · 角色名（都是空就不占这一行）。 */
    private fun detailOf(entry: ServerEntry): String {
        val parts = buildList {
            if (entry.hasServerNo) add(context.getString(R.string.capture_review_no, entry.serverNo))
            if (entry.hasLevel) add("${context.getString(R.string.server_level_prefix)}${entry.level}")
            if (entry.hasCharacterName) add(entry.characterName)
        }
        return parts.joinToString(" · ")
    }

    /**
     * 把列表包进**限高**的 ScrollView：横屏可用高只有约 360dp，菜单不能顶满屏。
     * 高度按「行数 × 行高」取用、超过上限才限住 —— 行数与上限都走纯逻辑 [FloatingMenu]（已有单测）。
     *
     * @param chromeDp 这一层**除滚动区以外**的固定高度（保守估算，宁可少显示一行也不顶破屏幕）
     * @param rowHeightDp 一"行"有多高（列表行 28dp；复核页那些卡片更高，见 [CARD_ROW_HEIGHT_DP]）
     */
    private fun boundedScroll(
        rows: List<View>,
        rowCount: Int,
        chromeDp: Int = 150,
        rowHeightDp: Int = FloatingMenu.PICK_ROW_HEIGHT_DP,
    ): ScrollView {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            rows.forEach { addView(it) }
        }
        val density = context.resources.displayMetrics.density
        val screenHeightDp = (FloatingScreen.spec(context).height / density).toInt()
        // 除列表以外的固定高度（面板内边距 + 面包屑 + 快捷行 + 原因条）保守按 **150dp** 估算 ——
        // 刻意取大：宁可列表少显示一行，也不让菜单顶破屏幕。
        val maxDp = FloatingMenu.listMaxHeight(screenHeightDp, chromeDp)
        val heightDp = FloatingMenu.listHeight(rowCount, rowHeightDp, maxDp)
        return ScrollView(context).apply {
            isFillViewport = false
            // 滚动条**开着**：窄面板里它是"下面还有内容"的唯一视觉线索（2026-09-25 用户口径）
            isVerticalScrollBarEnabled = true
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp),
            )
            lastBuiltScroll = this
        }
    }

    /** 纯说明行（不可点）：清单为空、待确认提示等。 */
    private fun noteRow(text: String): TextView = textView(12f, MENU_TEXT_COLOR).apply {
        this.text = text
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    /**
     * 试读报告里的一行：**必须限宽**（与 [noteRow] 同一套观感，只多一个宽度上限）。
     *
     * 2026-09-23 真机：报告里既有 `区域：x=[963,1201) y=[1342,1843) …` 这种长句，也有整段说明文字，
     * 而菜单窗是 `WRAP_CONTENT`（`FloatingMenu.PANEL_WIDTH_DP` 从来没真正作用到窗口上）——
     * 不限宽时面板被最长那行撑到 **2310px ≈ 880dp**，几乎盖住整块屏幕（悬浮窗落位日志实录）。
     */
    private fun reportRow(text: String): TextView = noteRow(text).apply {
        maxWidth = dp(REASON_MAX_WIDTH_DP)
    }

    /**
     * 进度块（T4-5）：两行小字（进度 + 状态 / 原因），**合成一个语义节点**给读屏 ——
     * 要听到的是"第 3/10 步 · 退出登录，正在等大厅"这一句完整的话，而不是两行被拆开念、
     * 每推进一步还各播报一次。
     *
     * **行数恒定**（第二行缺内容时兜「运行中」）：行数一变面板高度就变，正在点菜单的人会点错行。
     */
    private fun statusBlock(state: PatrolFlow.State): LinearLayout {
        val progress = PatrolStatus.progressLine(state).orEmpty()
        // **"点击被拒"优先于普通 note**（2026-09-30）：那一句是"为什么它不动"的答案，而 note 每轮都被
        // 编排层重写（只能活 200~500ms，用户事后打开菜单早没了）⇒ 它单独存一份、粘在菜单上
        // （见 `PatrolSession.deniedNote`）。
        val status = PatrolSession.deniedNote
            ?: PatrolStatus.statusLine(state, PatrolSession.lastNote)
            ?: context.getString(R.string.floating_menu_running)
        val alerted = PatrolStatus.canResume(state)
        val progressView = statusRow(progress, STATUS_COLOR)
        val statusView = statusRow(status, if (alerted) REASON_COLOR else STATUS_COLOR)
        // 两行都只作为"上面那个语义节点的一部分"（不分别可聚焦）；中止 / 暂停那一句另有
        // 手柄上的主动播报（[applyHandleAlert]），不依赖这里的 live region。
        progressView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        statusView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "$progress，$status"
            addView(progressView)
            addView(statusView)
        }
    }

    /**
     * 跑号进度 / 状态行（T4-5，不可点）：
     *
     * - **单行 + 省略号**：长句子（"已中止：连续 3 次没等到好友列表"）不能把面板撑宽 ——
     *   面板宽是常量（[FloatingMenu.PANEL_WIDTH_DP]），变宽会多吞游戏触摸；
     * - 左内边距与功能行文字**对齐**（8 + 图标槽 + 图标后间距），缩进在图标槽里，
     *   读上去像"这组行的表头"。
     */
    private fun statusRow(text: String, color: Int): TextView = textView(11f, color).apply {
        this.text = text
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // **限宽用 [STATUS_MAX_WIDTH_DP]，不是 [REASON_MAX_WIDTH_DP]**（2026-09-29 用户报障：
        // "悬浮窗根菜单顶部步骤说明显示不完全"）。原因条那 200dp 是给**异常长句**兜底的；
        // 而这里放的是"第 10/10 步 · 点区服「发条权杖」"这类**必须看全**的步骤说明 ——
        // 200dp 在 11sp 下只装得下 ≈18 个汉字，多一步就吃掉尾巴。
        // 仍保持**单行 + 省略号**：行数一变面板高度就变，正在点菜单的人会点错行（见本函数的对象注释）。
        maxWidth = dp(STATUS_MAX_WIDTH_DP)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8 + MENU_GLYPH_SLOT_DP + 6), dp(2), dp(8), dp(2))
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
    private fun menuRow(
        glyph: String,
        textRes: Int,
        description: String? = null,
        onClick: () -> Unit,
    ): LinearLayout = menuRow(glyph, context.getString(textRes), description, onClick)

    /**
     * 文案已经是字符串的重载（一级「拜访」要显示预设里的好友名，写不成固定资源）。
     *
     * [description] 非空时**整行只算一个语义节点**：读屏念这一句，行内的图标与文字都不再单独念
     * （否则「继续」会被念成"↻，继续"）。默认 `null` = 按各自内容念。
     */
    private fun menuRow(
        glyph: String,
        text: CharSequence,
        description: String? = null,
        onClick: () -> Unit,
    ): LinearLayout = menuRowShell(glyph, text, subText = null, description, onClick)

    /**
     * 功能行的**骨架**：图标槽 + 文案列（可带一行副文）+（可选）读屏整句。整行可点。
     *
     * 三处口径（前两处都踩过真机）：
     * 1. 图标槽**固定宽度**（[MENU_GLYPH_SLOT_DP]）—— 不同字形的自然宽度差得很远（`☺` 甚至会被
     *    渲染成双宽 emoji），不固定就会出现"每一行的文字起点都不一样"（2026-09-16 用户报障）；
     * 2. [description] 非空 ⇒ **整行算一个语义节点**（读屏只念这一句，行内图标与文字都不再单独念）；
     * 3. [subText] 非空 ⇒ 文案列变成**两行**（主文 + 小一号的弱化副文）：主文**不限宽**（它是行名，
     *    截断比变宽更糟），只有副文限宽（它是"目标 / 提示"那类可能很长的句子）。
     */
    private fun menuRowShell(
        glyph: String,
        text: CharSequence,
        subText: CharSequence?,
        description: String?,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 上下间距 2 → 5dp（2026-09-17 用户口径「适当增加 1、2 级菜单项的上下间距」）：
        // 功能行挨得太紧时，一眼扫过去分不清是"五项"还是"三块"。列表行保持自己的 28dp 行高。
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = tapFeedbackBackground()
        addView(menuGlyphView(glyph))
        val labelView = menuLabelView(text, subText)
        addView(labelView)
        if (description != null) {
            contentDescription = description
            labelView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        setOnClickListener { onClick() }
    }

    /** 功能行左侧的**图标槽**：固定宽度 ⇒ 各行文字左对齐；装饰件 ⇒ 读屏不念。 */
    private fun menuGlyphView(glyph: String): TextView = textView(12f, MENU_GLYPH_COLOR).apply {
        this.text = glyph
        gravity = Gravity.CENTER
        // 图标是装饰（⇄ / ☺ / ■ / ★）：读屏念它只会多一句噪声
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(
            dp(MENU_GLYPH_SLOT_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, 0, dp(6), 0)
    }

    /**
     * 功能行的**文案列**：没有副文 ⇒ 就是一个 TextView（行高与宽度都与原来一致）；
     * 有副文 ⇒ 竖向两行（主文 12sp 亮色 + 副文 [MENU_SUB_TEXT_SIZE_SP] 弱化色，单行 + 末尾省略）。
     *
     * 副文的来历（2026-09-30 用户报障"这个文案太长了"）：「换号+拜访」那一行原来把目标拼在同一行
     * （`换号+拜访 顺序轮换→阿娜雅`）⇒ 一行又长又挤，和别的行不是一个调子。
     * 拆成两行后主文与其它行齐平，**目标仍然一眼看得到**（不是藏进参数层）。
     */
    private fun menuLabelView(text: CharSequence, subText: CharSequence?): View {
        val main = textView(12f, MENU_TEXT_COLOR).apply { this.text = text }
        if (subText == null) return main
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(main)
            addView(
                textView(MENU_SUB_TEXT_SIZE_SP, STATUS_COLOR).apply {
                    this.text = subText
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    // 副文是"可能很长的句子"（好友名可以很长）⇒ 限宽，别把面板顶宽
                    maxWidth = dp(MENU_SUB_TEXT_MAX_WIDTH_DP)
                },
            )
        }
    }

    /**
     * 功能行 + **行尾小按钮**（2026-09-30 用户口径：「换号+拜访」整行点击 = 执行，行尾 ⚙ = 改参数）。
     *
     * ⚠ 这是菜单里**原本没有的范式**（其余行要么整行开下一级、要么整行执行）——用户已明确同意。
     *
     * 三条口径：
     * 1. **整行仍然是主动作**（点行身执行，与其它行一致，触摸目标最大）；
     * 2. 行尾那颗**自己吞掉点击**（子视图先拿到事件 ⇒ 不会连带触发行身的执行）；
     * 3. **读屏分成两个节点**：行 = [description]（含目标），⚙ = [trailingDescription]。
     *    不给 ⚙ 设 `IMPORTANT_FOR_ACCESSIBILITY_NO`（那会把它藏掉）——
     *    否则读屏用户就只剩"执行"这一条路，改参数的入口等于消失。
     */
    private fun menuRowWithTrailing(
        glyph: String,
        text: CharSequence,
        subText: CharSequence?,
        description: String,
        trailingDescription: String,
        onTrailing: () -> Unit,
        onClick: () -> Unit,
    ): LinearLayout = menuRowShell(
        glyph = glyph,
        text = text,
        subText = subText,
        description = description,
        onClick = onClick,
    ).apply {
        addView(menuTrailingView(trailingDescription, onTrailing))
    }

    /** 行尾小按钮（⚙）：自己吞掉点击、自己的按压反馈、自己的读屏节点。 */
    private fun menuTrailingView(description: String, onClick: () -> Unit): TextView =
        textView(TRAILING_GLYPH_TEXT_SIZE_SP, MENU_GLYPH_COLOR).apply {
            this.text = context.getString(R.string.floating_menu_glyph_setting)
            gravity = Gravity.CENTER
            contentDescription = description
            // 自己也有按压反馈：点它时整行不会亮（父级收不到这次按下）
            background = tapFeedbackBackground()
            setPadding(dp(TRAILING_GLYPH_PADDING_H_DP), dp(4), dp(4), dp(4))
            isClickable = true
            isFocusable = true
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

    // ---- 状态标签窗（顶部居中、可拖动；2026-09-28 用户口径）----

    /**
     * 建标签视图：**一行小字 + 半透明圆角胶囊**（复用 2026-09-14 那版的底与配色）。
     *
     * 三条尺寸口径（FR-07「紧凑」「不挡游戏」）：
     * - `maxWidth = REASON_MAX_WIDTH_DP`（200dp）+ 单行 + 末尾省略 ⇒ 文案再长也只有一条，
     *   不会把窗口撑成一大块（真机上曾把面板撑到 880dp，见 [statusRow] 的教训）；
     * - **行数恒定**（永远一行）⇒ 高度恒定 ⇒ 落位不跳（[applyPositions] 依赖尺寸）；
     * - 触摸区 = 它自己那一小块，而且**可以拖走**（用户口径：挡了自己挪）。
     */
    private fun buildLabel(): TextView = TextView(context).apply {
        background = labelBackground()
        setTextColor(LABEL_TEXT_COLOR)
        textSize = LABEL_TEXT_SIZE_SP
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(REASON_MAX_WIDTH_DP)
        setPadding(
            dp(LABEL_PADDING_H_DP),
            dp(LABEL_PADDING_V_DP),
            dp(LABEL_PADDING_H_DP),
            dp(LABEL_PADDING_V_DP),
        )
        // 纵向：去掉字体自带的上下内边距（不然 11sp 的行会被撑成 23dp 高，标签发胖）
        includeFontPadding = false
        // 只做拖动（单击不改任何状态）：见 [onLabelTouch] 的说明
        setOnTouchListener(::onLabelTouch)
    }

    /**
     * 挂标签窗（顶部居中；用户拖过就回到他放的地方）。重复调用安全。
     *
     * 尺寸必须**显式按像素**写进 LayoutParams（`WRAP_CONTENT` 在 WindowManager 上常被测成 0×0、
     * addView 成功但看不见 —— `FloatingNotifier` 真机踩过），所以先 `measureSelf` 再落位。
     */
    private fun attachLabel() {
        if (labelAttached || label != null || panel == null) return
        // 正在读整屏 ⇒ 这一拍先不挂（读完 [refreshLabel] 会自己挂回来）
        if (FriendListOcrSignal.running) return
        // **待命期没有要说的 ⇒ 不挂**（2026-09-30 用户口径；判据见 [labelContent]）
        val text = labelContent() ?: return
        val view = buildLabel()
        applyLabelText(view, text)
        measureSelf(view)
        val width = labelWidthFor(view, text)
        val height = labelHeightFor(view)
        val at = labelPlacement(width, height)
        val params = labelParams().apply {
            this.width = width
            this.height = height
            x = at.x
            y = at.y
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            MmLog.w(TAG, "状态标签挂载失败", e)
            return
        }
        label = view
        labelAttached = true
        // 记下"这次是按哪个屏幕算的"：之后 [applyPositions] 才会在转屏时重算、平时不动它
        placeLabel(view, at, width, height)
        // 尺寸变（文案变长）→ 重新落位；顺带声明手势排除区（顶部那块别被下拉通知栏抢走触摸）
        attachLayoutRefresh(view)
        MmLog.i(TAG, "状态标签已挂载: 「${view.text}」(${at.x},${at.y}) ${width}x$height")
    }

    /** 摘标签窗（hide / 读整屏让开 / 挂载失败都用它；重复调用安全）。 */
    private fun detachLabel() {
        val view = label ?: return
        label = null
        labelText = null
        labelDragging = false
        if (labelAttached) {
            labelAttached = false
            runCatching { windowManager.removeView(view) }
            MmLog.i(TAG, "状态标签已移除")
        }
    }

    /**
     * 让标签跟住"当前正在进行的操作"（每 500ms 一拍，与 [refreshStatus] 同一条轮询）。
     *
     * **读整屏期间让开**：导入服务器读的是**整块屏幕的镜像**，我们自己的像素也会被读进去
     * （菜单面板为同一件事先收起，见 [onCaptureServers]）；标签虽小，道理一样 —— 读的那几秒先摘掉，
     * 读完由这一拍自己挂回来（不需要在动作处各写一遍收尾）。
     *
     * **待命期整条摘掉**（2026-09-30 用户口径）：没有任何话要说的那一刻，常驻文字只剩"挡视线"。
     * 直接切换、不做淡入淡出（用户口径）；摘掉之后这一拍会一直走到 [detachLabel]，
     * 而它自己认"已经摘了"就直接返回 ⇒ 不会每 500ms 刷日志。
     */
    private fun refreshLabel() {
        if (panel == null) return
        if (FriendListOcrSignal.running) {
            detachLabel()
            return
        }
        val text = labelContent()
        if (text == null) {
            if (labelAttached) MmLog.i(TAG, "状态标签: 待命期没有要说的 ⇒ 摘掉（用户 2026-09-30 口径）")
            detachLabel()
            return
        }
        val view = label
        if (!labelAttached || view == null) {
            attachLabel()
            return
        }
        updateLabelText(view, text)
    }

    /** 上一次给读屏播报过的"跑号结局"（同一结局只说一次；null = 没说过 / 已复位）。 */
    private var lastAnnouncedOutcome: String? = null

    /**
     * 跑号"停下来"的三种结局**说一句给读屏**（T4-5 口径：中止 / 暂停不能只对看得见屏幕的人说）。
     *
     * 为什么由标签来说：底部浮窗提示 2026-09-29 按用户口径**整体移除**（"既然顶部增加了悬浮窗,
     * 那么底部的toast是不是可以去除了?"），而它原来承担着"中止 / 暂停 / 完成"的读屏播报
     * （`accessibilityLiveRegion`）—— 那件事不能跟着消失，改由标签在结局**变化**时播一次。
     *
     * 只说一遍（[lastAnnouncedOutcome] 去重）：跑号停在 FAILED 后每轮都会走进来，不去重就成了循环播报
     * （那是"视觉噪音"的听觉版本）。跑号结束（`PatrolSession.current == null`）时自动复位，
     * 下一次中止照样会说。
     */
    private fun announcePatrolOutcome() {
        val state = patrolForDisplay()
        val outcome = when {
            state == null -> null
            state.outcome == PatrolFlow.Outcome.FAILED -> PatrolStatus.statusLine(state, null)
            state.paused -> PatrolStatus.statusLine(state, null)
            state.outcome == PatrolFlow.Outcome.FINISHED -> PatrolStatus.statusLine(state, null)
            else -> null
        }
        if (outcome == lastAnnouncedOutcome) return
        lastAnnouncedOutcome = outcome
        if (outcome != null) label?.announceForAccessibility(outcome)
    }

    /**
     * 本轮该显示的那句话（取舍逻辑在纯逻辑 [FloatingLabelText] 里，有单测）；
     * **null = 这一拍没有要说的（待命）⇒ 标签整条不挂**。
     *
     * ⚠ **不要直接显示 `PatrolSession.lastNote`**：那句是给日志与菜单写的完整句子
     * （真机实录 `第 3 步：刚点过，等画面切换，继续等（已等 5 秒）`，26 字），
     * 第一版就那么干 ⇒ 标签被撑到 200dp 上限并截断（用户报障"太长了，显示不完、也看不过来"）。
     * 标签自己造短句：`退出登录`（完整句仍留在菜单进度块与日志里）。
     */
    private fun labelContent(): String? {
        // **采集会话不在 ⇒ 这句话比任何进度都重要**（2026-09-30 真机第 302 条）：
        // 用户从系统侧终止了屏幕采集，日志是 `采集会话 ACTIVE -> INACTIVE（来源: 系统侧回收）`，
        // 而**界面一句话都没有** ⇒ 他等了几分钟都不知道"程序已经看不见了"。
        // 采集没了，后面的"正在关弹窗 / 第 N 步"全都不成立 ⇒ 标签先说这件事，并指路去重建。
        if (!CaptureSessionSignal.isActive) {
            return context.getString(R.string.floating_label_capture_stopped)
        }
        val patrol = patrolForDisplay()
        val reading = currentReadingText()
        val closing = currentClosingPopupText()
        // **画面久未更新**（2026-09-30，L2）：60 秒没有新帧 ⇒ 「画面久未更新」那一档要占标签
        //（下面每一句"正在关弹窗 / 拜访 X 的农场"说的都是**建立在旧画面上**的结论，而程序此刻其实点不动：
        // 采集侧已置位 `ClickDispatch.framesStalled` ⇒ 任何点击都被拒成 `STALE_FRAMES`）。
        // **取舍在纯逻辑 [FloatingLabelText.of]（有单测）**，这里只把事实递进去；
        // 背景见 `FrameFreshness`：停更是**现象**、不是"镜像失效"的证据 ⇒ 只提示 + 停止点击，
        // **不再结束会话**（原来那条 60 秒判据会静默掐掉会话 ⇒ 用户只看到"跑号跑到一半被要求重新授权"）。
        // ⚠ 不需要给 [FloatingLabelText.isIdle]：这一档只在"有活儿在等画面"时出现，
        // 而那时三件（读数 / 关弹窗 / 跑号）里至少有一件在 ⇒ 本来就非待命（见那里的说明）。
        // 停更分两段（2026-09-30 用户口径："停更持续时，提示里给'能做的事'"）：刚过 60 秒只说事实
        //（"屏幕本来静止"与"镜像坏了"分不出来），到 120 秒（自动"换镜像表面"已试过两轮）才说处置。
        // 换算放在界面层：纯逻辑 [FloatingLabelText] 不碰 `FrameFreshness`（那是个安卓侧信号）。
        val frameStale = when {
            // **这次授权根本没被投喂**（2026-10-01，方案 2）排最前：它比"停更"更确定，处置也不同
            //（换进程 vs 只重新授权）——判据在采集层（`CaptureService.checkFirstFeed`）。
            FrameFreshness.isStarved() -> FloatingLabelText.FrameStale.NEVER
            !FrameFreshness.isStale() -> FloatingLabelText.FrameStale.NONE
            FrameFreshness.isProlonged() -> FloatingLabelText.FrameStale.PERSISTENT
            else -> FloatingLabelText.FrameStale.RECENT
        }
        val frameStarved = FrameFreshness.isStarved()
        // **跑号时把"在做什么 / 对谁做"说出来**（2026-09-30 用户口径）：
        // 「选择 xxxx」/「拜访 xxx 的农场」—— 原来只写"退出登录"这类动作名，看不出目标是哪个号 / 哪个好友。
        // 步号含义见 `PatrolFlow`：第 5 步 = 选区服；第 7 步起 = 进好友农场（FR-04「只拜访」区间）。
        // ⚠ 取舍逻辑本该在纯逻辑 [FloatingLabelText]（它有单测）；这次先落在界面层（它读得到
        // [PatrolSession] 的目标名），下一步搬进纯逻辑并补单测 —— 已在回复里如实说明。
        if (patrol != null) {
            val friend = PatrolSession.targetFriend
            val server = PatrolSession.targetServer
            // **只在真正"拜访"那一步说「拜访 X 的农场」**（2026-09-30 用户报："提示太早了"）：
            // 第 7 步是「进入农场」（自己的）、第 8 步是「打开好友列表」，都还没到拜访那一下 ——
            // 旧判据写成 `step.number >= 7`，于是从第 7 步起就一直挂着"拜访 X 的农场"，
            // 与标签"显示**当前**正在进行的操作"的口径不符（用户原话：应该是先进自己的农场，
            // 然后打开好友列表，最后拜访好友的农场）。第 10 步（完成）由 FloatingLabelText 说"已完成"。
            if (patrol.step == PatrolFlow.Step.VISIT_FRIEND && !friend.isNullOrBlank()) {
                return context.getString(R.string.floating_label_visiting_farm, friend)
            }
            if (patrol.step.number == 5 && !server.isNullOrBlank()) {
                return context.getString(R.string.floating_label_picking_server, server)
            }
            // **第 6 步（登录游戏）：出现遮挡屏 = 已经进入游戏**（2026-09-30 用户给的领域知识）：
            // 新手引导出现得最早，其次是新手大厅 / 活动弹窗；**它们都不是必然出现**
            //（新号才有引导、弹窗看活动）⇒ 只当"证据"用、**不当"关卡"**。
            // ⚠ 本轮**只改措辞**（判据与流程一行不改，见 progress 第 307 条）——但正是这一处让人以为
            // 程序还在催他登录（真机报障："已经登录游戏，仍然提示登录游戏"）。
            if (patrol.step.number == 6 && UiStateSignal.status in UiState.guardedOverlays) {
                return context.getString(
                    R.string.floating_label_entered_game,
                    UiStateSignal.status.label,
                )
            }
        }
        // **待命期没有要说的 ⇒ 不挂标签**（2026-09-30 用户口径：只藏标签、手柄留着；直接切换、不做动画）。
        // 判据是纯逻辑 [FloatingLabelText.isIdle]（与 [FloatingLabelText.of] 逐条对应，有单测钉住），
        // **不靠比较文案** —— 「待命」是取舍表的兜底档，它出现就意味着这一刻没有任何信息可给。
        if (FloatingLabelText.isIdle(reading, closing, patrol, frameStarved = frameStarved)) return null
        return FloatingLabelText.of(
            readingText = reading,
            closingPopupText = closing,
            patrol = patrol,
            frameStale = frameStale,
            words = FloatingLabelText.Words(
                idle = context.getString(R.string.floating_label_idle),
                failed = context.getString(R.string.floating_label_patrol_failed),
                paused = context.getString(R.string.floating_label_patrol_paused),
                finished = context.getString(R.string.floating_label_patrol_finished),
                frameStalled = context.getString(R.string.floating_label_frame_stalled),
                frameStalledAction = context.getString(R.string.floating_label_frame_stalled_action),
                frameStarved = context.getString(R.string.floating_label_starved),
            ),
        )
    }

    /** 正在跑的"读整屏"动作 → 短文案；没在跑给 null。 */
    private fun currentReadingText(): String? {
        if (!FriendListOcrSignal.running) return null
        // 2026-09-30：试读已移除 ⇒ 只剩"导入服务器"这一种读数
        val res = R.string.floating_label_read_servers
        return context.getString(res)
    }

    /**
     * FR-01 正在关弹窗 → 短文案；没在关给 null。
     *
     * 用户报障（2026-09-28）："**自动关闭弹窗时没有显示提示信息**" —— 弹窗是程序自己点掉的，
     * 而标签当时在说别的（甚至 `待命`）。第 2 次起带上次数：多弹窗连着关时能看到"它在关第几个"。
     *
     * ## ⚠ "在关"与"关不成"必须分开说（2026-09-29 用户报障）
     *
     * 原话："**显示了正在关弹窗，但实际上没关掉**" —— 那时 [PopupCloseSignal.attempts] 是
     * "给出一次点击就计一次"，而**真的发得出去**还要过开火前复眼与 `ClickGate` ⇒ 标签写"正在关"时
     * 用户合理地以为"它点了" ✗。现在：**被拦住 ⇒ 写「关弹窗受阻」**（原因在菜单里，标签放不下）。
     *
     * ## ⚠ "受阻"与"已停手"更要分开说（2026-09-30 用户报障）
     *
     * 原话："**我已经完成换号操作，但是最后一次活动弹窗没有关掉**" —— 那时程序其实**主动停手**了
     * （日志：`FR-01 停手: 本轮画面整幅换掉了…`），可"停手"这条路当时只回一句 `Skip` ⇒ 标签从
     * 「正在关弹窗」退回「待命」并**整条摘掉**：弹窗压在屏上，界面一个字都不说。
     * 现在三态各说各的：**正在关**（真的发出去过枪）/ **受阻**（想点但点不成 ⇒ 去标定 / 重框）/
     * **已停手**（不再尝试 ⇒ 这个弹窗得你自己关）。
     */
    private fun currentClosingPopupText(): String? {
        // 用户暂停了自动关弹窗：只在"本来要说关弹窗那件事"的时候才占位（不抢跑号 / 待命的文案）
        if (PopupCloseSignal.paused &&
            (PopupCloseSignal.isClosing ||
                PopupCloseSignal.blockedReason.isNotEmpty() ||
                PopupCloseSignal.gaveUpReason.isNotEmpty())
        ) {
            return context.getString(R.string.floating_label_close_paused)
        }
        // **已停手**（2026-09-30 用户报障："我已经完成换号操作，但是最后一次活动弹窗没有关掉"）：
        // 停手后 attempts 被写 0，而"画面整幅换掉 ⇒ 停手"那条路**不走** markBlocked ⇒ 早先这里返回 null
        // ⇒ 标签按待命整条摘掉，弹窗压在屏上而界面一个字都不说（用户只能自己猜"是不是坏了"）。
        // **它排在"受阻"之前**：停手是终态（"我不再尝试了"），而「受阻」读起来像"还在找机会"；
        // 为什么停手在菜单里说（停手原因同样优先于受阻原因，见 [renderRoot]）。
        if (PopupCloseSignal.gaveUpReason.isNotEmpty()) {
            return context.getString(R.string.floating_label_popup_gave_up)
        }
        if (PopupCloseSignal.blockedReason.isNotEmpty()) {
            // **受阻也分两种**，处置完全相反（2026-09-30 用户口径："听起来像它被挡住了，其实是我还没标"）：
            // 没标 ⇒ 去标定页框一条；标了没认出 ⇒ 重框紧一点 / 换张清晰的帧。
            return context.getString(
                if (PopupCloseSignal.blockedNeedsCalibration) {
                    R.string.floating_label_popup_blocked_uncalibrated
                } else {
                    R.string.floating_label_popup_blocked_unmatched
                },
            )
        }
        val attempts = PopupCloseSignal.attempts
        return when {
            attempts <= 0 -> null
            attempts == 1 -> context.getString(R.string.floating_label_closing_popup)
            else -> context.getString(R.string.floating_label_closing_popup_nth, attempts)
        }
    }

    /** 写文字（顺带更新读屏内容）；文案没变时不调用它 —— 写文字要重新测量。 */
    private fun applyLabelText(view: TextView, text: String) {
        labelText = text
        view.text = text
        view.contentDescription = context.getString(R.string.floating_label_desc, text)
    }

    /** 文案变了：写进去，并**以原来的中心为准**重新落位（宽度变了，不重算就会偏）。 */
    private fun updateLabelText(view: TextView, text: String) {
        if (text == labelText) return
        applyLabelText(view, text)
        measureSelf(view)
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val width = labelWidthFor(view, text)
        val height = labelHeightFor(view)
        val screen = FloatingScreen.spec(context)
        // 用户拖过的位置：跟着中心一起平移；**默认态（没拖过 / 刚重置）= 按屏幕中心重新居中**
        //（用户 2026-10-01 口径："重置后**不受文字内容长度变化影响**" —— 只"保持旧中心"在有外力挪过时
        // 会留偏差，直接以屏幕中心为锚最稳；拖过的仍按用户意图跟着旧中心平移）。
        val centerX = if (labelRatios == null) {
            screen.width / 2
        } else {
            params.x + params.width / 2
        }
        placeLabel(
            view = view,
            at = FloatingLabelPosition.At(
                x = FloatingLabelPosition.keepCenterX(centerX, width, screen.width),
                y = FloatingLabelPosition.clampY(params.y, screen.height, height, topInsetPx()),
            ),
            width = width,
            height = height,
        )
        // 每次换文案记一行（含**实际窗口尺寸**）：真机报"字显示不全"时，这一行就能看出
        // 是"文案被上限截了"还是"窗口给窄了"（2026-09-29 那次只能靠宽度反推，绕了远路）
        MmLog.i(TAG, "状态标签: 「$text」${width}x$height")
    }

    /**
     * 标签该放哪：**用户拖过就用他放的地方**（按比例还原 + 钳进屏内），没拖过就是**顶部正中心**。
     *
     * 位置算错的两条后果都很直观：跑到屏外（用户以为没做出来）、或压在状态栏上（下拉通知栏抢触摸）。
     */
    private fun labelPlacement(width: Int, height: Int): FloatingLabelPosition.At {
        val screen = FloatingScreen.spec(context)
        val inset = topInsetPx()
        return FloatingLabelPosition.fromRatios(
            ratios = labelRatios,
            screenWidth = screen.width,
            screenHeight = screen.height,
            labelWidth = width,
            labelHeight = height,
            topInset = inset,
        ) ?: FloatingLabelPosition.defaultAt(
            screenWidth = screen.width,
            topInset = inset,
            labelWidth = width,
            marginPx = dp(LABEL_MARGIN_DP),
        )
    }

    /**
     * **把提示标签放回默认位置**（用户 2026-10-01 口径：「更多」里加一个"重置提示位置"，
     * 回到"垂直靠近屏幕顶部、水平居中"，且**不受文字内容长度变化影响**）。
     *
     * 做三件事：
     * 1. **清掉拖动留下的比例**（内存 + 落盘）：这一步才是"不受文字长度影响"的关键 ——
     *    拖过的位置是按**左上角比例**还原的（[FloatingLabelPosition.toRatios]），换个更长的文案时
     *    它会"往右长"，看着就不居中了；清空之后走 [FloatingLabelPosition.defaultAt]（按当前宽度居中）⇒
     *    以后每次换文案都重新居中（见 [updateLabelText]）；
     * 2. 标签**此刻挂着**就立刻重落位（不挂着也没关系：[attachLabel] 时会按默认口径算一次）；
     * 3. 在菜单里说一句 + 记一行日志（含落位坐标与尺寸）—— 待命期标签本来就没挂，
     *    不吭声的话用户会以为点了没反应。
     */
    private fun resetLabelPosition() {
        labelRatios = null
        FloatingLabelStore.clear(context)
        val view = label
        if (view == null || !labelAttached) {
            MmLog.i(TAG, "提示位置已重置为默认（顶部居中）：标签当前未挂着，下次挂载按默认落位")
            note(context.getString(R.string.floating_menu_reset_label_done))
            return
        }
        measureSelf(view)
        val text = labelText.orEmpty()
        val width = labelWidthFor(view, text)
        val height = labelHeightFor(view)
        val at = labelPlacement(width, height)
        placeLabel(view, at, width, height)
        MmLog.i(TAG, "提示位置已重置为默认（顶部居中）：(${at.x},${at.y}) ${width}x$height")
        note(context.getString(R.string.floating_menu_reset_label_done))
    }

    /**
     * 标签窗口该有多高（px）：单行文本，量出来是常量。
     *
     * 量不到（0）就按字体度量兜一个 —— **高度 0 会让标签整个看不见**（比宽度偏窄严重得多）。
     */
    private fun labelHeightFor(view: TextView): Int =
        viewSize(view).second.takeIf { it > 0 }
            ?: (
                view.paddingTop + view.paddingBottom +
                    ceil(view.paint.fontMetrics.let { it.descent - it.ascent }).toInt()
                )

    /** 标签窗口该有多宽（px）：**直接量文本**，不读视图的测量缓存。 */
    private fun labelWidthFor(view: TextView, text: String): Int {
        // ⚠ 真机 bug（2026-09-29，用户截图上 `关弹窗 第2次` 被截成 `关弹窗…`）：原先用 `viewSize(view)`
        // （= 视图的 `measuredWidth`）当窗口宽度，而**标签视图挂着的时候会被父级按"上一次的窗口宽"
        // 重新测量** ⇒ 下一次换文案时读回的还是旧宽度，`placeLabel` 一看"没变"就提前返回 ⇒ 宽度**冻死**，
        // 之后的文案全被 `ellipsize` 截断（日志实录：宽度连续 6 次都是 288px，文案却从 2 字变成 8 字）。
        // 文本宽度本来就能直接算（`paint.measureText` + 内边距）⇒ 不走测量缓存就不会有这个问题。
        val textWidth = ceil(view.paint.measureText(text)).toInt()
        val desired = textWidth + view.paddingLeft + view.paddingRight
        // +2px 余量：measureText 的小数被 ceil 吃掉一点，留一像素免得最后一个字刚好被裁
        return min(desired + 2, dp(REASON_MAX_WIDTH_DP))
    }

    /**
     * 一次性提交"尺寸 + 位置"（与菜单窗同一条理由：分两次提交会有一帧错位闪），
     * 并记下**这次是按哪个屏幕算的** —— 只有屏幕尺寸变了才重算（转屏），
     * 否则 [applyPositions] 会把"刚按中心对齐 / 刚拖好"的位置跳回去。
     */
    private fun placeLabel(view: View, at: FloatingLabelPosition.At, width: Int, height: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val screen = FloatingScreen.spec(context)
        labelScreenAt = screen.width to screen.height
        if (params.width == width && params.height == height && params.x == at.x && params.y == at.y) {
            return
        }
        params.width = width
        params.height = height
        params.x = at.x
        params.y = at.y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 标签的触摸：**只做拖动**。
     *
     * 为什么单击什么都不做：这是"看一眼当前在做什么"的标签，不是按钮；而且它就在游戏画面正上方，
     * 顺手点一下本该是"点游戏"（虽然按用户口径那一块没有需要识别的 UI）。要开菜单有手柄。
     *
     * 拖动用**按下时的窗口位置 + 总位移**（不是"上一帧 + 增量"）：拖到边缘再拖回来时不会累积漂移，
     * 手感才对。越阈值才算拖动（[touchSlop] 运行时读，红线 4：不写死阈值）。
     * 抬手才落盘：拖的过程里不必反复写偏好。
     */
    private fun onLabelTouch(view: View, event: MotionEvent): Boolean {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                labelDownRawX = event.rawX
                labelDownRawY = event.rawY
                labelDownX = params.x
                labelDownY = params.y
                labelDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - labelDownRawX
                val dy = event.rawY - labelDownRawY
                if (!labelDragging && hypot(dx, dy) > touchSlop) labelDragging = true
                if (labelDragging) {
                    val (width, height) = viewSize(view)
                    val screen = FloatingScreen.spec(context)
                    placeLabel(
                        view = view,
                        at = FloatingLabelPosition.dragTo(
                            startX = labelDownX,
                            startY = labelDownY,
                            dx = dx.toInt(),
                            dy = dy.toInt(),
                            screenWidth = screen.width,
                            screenHeight = screen.height,
                            labelWidth = width,
                            labelHeight = height,
                            topInset = topInsetPx(),
                        ),
                        width = width,
                        height = height,
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (labelDragging) {
                    val screen = FloatingScreen.spec(context)
                    val ratios = FloatingLabelPosition.toRatios(
                        x = params.x,
                        y = params.y,
                        screenWidth = screen.width,
                        screenHeight = screen.height,
                    )
                    labelRatios = ratios
                    FloatingLabelStore.save(context, ratios)
                    MmLog.i(TAG, "状态标签已移到 (${params.x},${params.y})，位置已记住")
                }
                labelDragging = false
                return true
            }
        }
        return false
    }

    /**
     * 状态栏高度：标签**不许压在上面**（那里是系统手势的起手区，也会被下拉通知栏抢触摸）。
     * 取不到一律按 0（退化成"贴屏幕顶"，不算故障）。
     */
    private fun topInsetPx(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.systemBars()).top
    }.getOrDefault(0)

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
    private fun handleBackground(side: FloatingSide, alert: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            val r = dp(HANDLE_VISUAL_WIDTH_DP) / 2f
            // 顺序：topLeft、topRight、bottomRight、bottomLeft
            cornerRadii = when (side) {
                FloatingSide.RIGHT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                FloatingSide.LEFT -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            // 形状不变、只换色（T4-5）：手柄的位置 / 尺寸 / 触摸范围一律不许因为这些状态改动
            if (alert) {
                setColor(HANDLE_ALERT_FILL_COLOR)
                setStroke(dp(1), HANDLE_ALERT_STROKE_COLOR)
            } else {
                setColor(HANDLE_FILL_COLOR)
                setStroke(dp(1), HANDLE_STROKE_COLOR)
            }
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
                // 覆盖窗刚消失 = 显示内容变了 ⇒ 合成器该出一帧新帧（"该有新帧却没有"就是镜像失效，
                // 见 `FrameExpectationSignal` / `CaptureService.checkFrameExpectation`）
                FrameExpectationSignal.arm("悬浮窗收起")
            }
            return
        }
        if (menuAttached) return
        FrameExpectationSignal.arm("悬浮窗展开")
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
        // **读数页不因"点外部"收起**（2026-09-25 真机报障）：`ACTION_OUTSIDE` 分不清"点击外部"与
        // "在游戏里滑动" —— 用户在选服页一滚屏，面板就被这条收掉了（日志实录：
        // `点了菜单面板之外，已收起` → `悬浮窗已收起（操作成功结束）`）。
        // 这一页本来就是"对着游戏画面干活"的页（滚一屏 → 点「再读一屏」），滚屏是正常操作。
        // 收起它请用手柄单击或左上角返回（两条都还在）。
        if (FloatingMenu.isCaptureResultPage(menuState, FriendListOcrSignal.isCaptureFlow(FriendListOcrSignal.mode))) {
            MmLog.i(
                TAG,
                "面板之外来了触摸，但这是采集型读数页（${FriendListOcrSignal.mode}，滚屏属正常操作）⇒ 不收起",
            )
            return false
        }
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
            // 每次展开都**回到根**并重读两份清单：不记住"上次停在选服务器那层"（避免误点上次的目标）。
            // ⚠ 例外（2026-09-25 用户报障）：**「添加这一屏的服务器」的读数页要能回来** ——
            // 那条流的操作是"读一屏 → 在游戏里滚一屏 → 再读一屏"，收起一次、再点手柄若落回根菜单，
            // 「再读一屏」就白给了。判断必须用**收起时记下的那个标志**：`menuState` 在收起那一刻
            // 已经被清成根了，此刻再看它的 level 永远是"不在读数页"（第一版就是这么错的）。
            menuState = if (resumeCaptureOnExpand) {
                resumeCaptureOnExpand = false
                FloatingMenu.resumeCaptureResult(FloatingMenu.State(level = FloatingMenu.Level.OCR_RESULT))
            } else {
                FloatingMenu.State()
            }
            loadMenuOptions()
            renderMenu()
        } else {
            rememberResumeTarget()
            menuState = FloatingMenu.collapsed()
        }
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, if (expanded) "悬浮窗菜单已展开（等待用户选择）" else "悬浮窗已收起")
    }

    /** 收起（操作成功结束后立即收起，FR-07 收起时机）：**层级栈一并清空**。 */
    private fun collapse() {
        if (!expanded) return
        rememberResumeTarget()
        expanded = false
        menuState = FloatingMenu.collapsed()
        reasonText?.visibility = View.GONE
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, "悬浮窗已收起（操作成功结束）")
    }

    /**
     * **收起前**记下：再展开要不要回到「添加这一屏的服务器」的读数页（2026-09-25）。
     *
     * 必须在清层级**之前**调用 —— 收起动作本身会把 `menuState` 清成根菜单，
     * 之后再判断就等于"永远不在读数页"（第一版踩过：用户点手柄收起 → 再点 ⇒ 仍回根菜单）。
     */
    private fun rememberResumeTarget() {
        resumeCaptureOnExpand = FloatingMenu.isCaptureResultPage(
            menuState,
            FriendListOcrSignal.isCaptureFlow(FriendListOcrSignal.mode),
        )
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

    /**
     * **只是告诉用户一件事**（回执 / 说明）：与 [fail] 同一条显示通道（停在当前层级、把那句话显示出来），
     * 但**用中性色**（用户 2026-10-01 口径："提示类信息不应该使用红色"）。
     *
     * 判据一句话：**用户需要动手吗？** 需要 ⇒ [fail]（红）；只是告知 ⇒ 这里。
     * 例：`提示位置已重置`（做完了一件事）、`当前没有进行中的巡查`（他点「停止」时本来就没在跑）。
     */
    private fun note(text: String) {
        menuState = FloatingMenu.notice(menuState, text)
        renderMenu()
        expanded = true
        applyExpandedState()
        applyPositions()
        MmLog.i(TAG, "菜单说明（提示）：$text")
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
     * 请求由 [com.example.mastermechanic.patrol.PatrolRequestConsumer] 消费并真的去操作游戏。
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
        MmLog.i(TAG, "已发出「${kind.logText}」（第 $count 次）")
        if (!keepExpanded) collapse()
    }

    /**
     * **启动类动作**的统一前置（2026-09-20 真机修）：画面不在起点表里 → **不发出请求**，
     * 改在菜单里挂原因条（FR-07：失败保持展开、说清原因）。
     *
     * 为什么要在菜单这一侧拦：判据原本只在消费方（`PatrolRequestConsumer`），而那边**只写日志**，
     * 菜单照常收起 → 用户看到的是"点了完全没反应"（真机上第一次跑号就是这么卡住的）。
     * 判据与消费方**同源**（[PatrolStartGate]）→ 不会出现"菜单放行、消费方拦下"的漂移。
     */
    private fun sendStartRequest(
        kind: PatrolRequestSignal.Kind,
        serverName: String? = null,
        friendName: String? = null,
    ) {
        // 常驻守护的检查在 [startBlockReason]（所有动作统一入口）；这里只管"起点成不成立"。
        val reason = PatrolStartGate.blockReason(
            uiState = UiStateSignal.status,
            kind = kind,
            // 滞回要连续 2 轮才转移 ⇒ "刚退出好友列表就点菜单"会被误拦（真机 2026-09-22）
            recentHits = UiStateSignal.recentHits,
        )
        if (reason != null) {
            // **画面停更时，原因不是"认不出"，而是"眼睛早就看不见了"**（2026-09-30 真机第 301 条，
            // 见 [FrameFreshness]）：那时手上的是几分钟前的**旧画面**，起点判定根本无从谈起；
            // 说"认不出当前画面（请先回到大厅）"会把人带向**完全相反**的处置（回大厅 vs 重建采集）。
            // ⇒ 如实报帧龄，并指路到"重建采集"。
            val staleAgeMs = FrameFreshness.ageMs()
            val shownReason = if (FrameFreshness.isStale() && staleAgeMs != null) {
                context.getString(R.string.floating_reason_frame_stalled, staleAgeMs / 1000)
            } else {
                reason
            }
            // 诊断（2026-09-22）：把**判定依据**一起打出来。只说"停在 X"，看不出是
            // "画面真没变"还是"某个误命中的标志把状态钉住了" —— 真机排障吃过这个亏：
            // 15 秒里状态一动不动，而农场其实早已显示（是"好友列表标志在农场上也命中"）。
            val status = UiStateSignal.status
            val hits = UiStateSignal.recentHits.joinToString("、") { it.label }.ifEmpty { "（一屏都没命中）" }
            MmLog.w(
                TAG,
                "菜单动作被拦下（起点不成立）：$shownReason｜判定依据：滞回结论「${status.label}」，最近命中：$hits",
            )
            fail(shownReason)
            return
        }
        sendRequest(kind, serverName = serverName, friendName = friendName)
    }

    /**
     * 「换号+拜访」行尾 ⚙：进**参数层**（2026-09-30 用户口径："调整服务器 / 好友并执行动作的入口
     * 移入『换号+拜访』下层"）。
     *
     * 进的就是原来那个设置页（[FloatingMenu.Level.CONFIG]，以**当前预设**为草稿）——
     * 根菜单那一行「拜访规则」撤掉后，这份预设只有这一个入口（它也只被「换号+拜访」用）。
     */
    private fun openSwitchVisitConfig() {
        menuState = FloatingMenu.openConfig(menuState, visitPreset)
        rerender()
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
        sendStartRequest(
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
    /**
     * 「只拜访 → 选好友」（2026-09-30 加）：**与「换号 → 选服务器」对称** —— 列好友清单，点一个就去。
     *
     * 原来这一项点下去是按预设直接出发；预设只有一个好友，而手上常常想临时去别人家 ⇒ 改成先选。
     * 选完立刻发请求（不换号，直接进好友农场）；清单为空 / 读不了 ⇒ 如实说，不猜。
     */
    private fun renderPickFriend(container: LinearLayout) {
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_back),
                context.getString(R.string.floating_menu_visit_pick),
            ) {
                menuState = FloatingMenu.State()
                rerender()
            },
        )
        val names = try {
            FriendListStore.load(context)?.entries?.map { it.name }?.filter { it.isNotBlank() }.orEmpty()
        } catch (e: Exception) {
            MmLog.w(TAG, "好友清单读不出来（选好友页为空）：${e.message}")
            emptyList<String>()
        }
        if (names.isEmpty()) {
            container.addView(
                menuRow(
                    context.getString(R.string.floating_menu_glyph_visit),
                    context.getString(R.string.floating_menu_no_friend),
                ) { fail(context.getString(R.string.floating_reason_friend_list_empty)) },
            )
            return
        }
        names.forEach { name ->
            container.addView(
                menuRow(context.getString(R.string.floating_menu_glyph_visit), name) {
                    if (!ensureReady()) return@menuRow
                    sendStartRequest(kind = PatrolRequestSignal.Kind.VISIT_ONLY, friendName = name)
                },
            )
        }
    }

    private fun onVisitOnly() {
        if (!ensureReady()) return
        if (visitPreset.friendName.isEmpty()) {
            fail(context.getString(R.string.floating_reason_preset_unset))
            return
        }
        sendStartRequest(
            kind = PatrolRequestSignal.Kind.VISIT_ONLY,
            friendName = visitPreset.friendName,
        )
    }

    /**
     * **没有采集会话**时给用户的那句话（2026-09-28 用户报障后分两句）。
     *
     * 报障原话："在 app 中授予采集权限，切换到游戏后，出现未授予采集权限的提示。"
     * —— 用户明明刚授过权，界面却说"尚未建立采集会话：请先到「授权状态」页授予屏幕采集权限"，
     * 读起来像"你从来没授权"，于是他只能再去授一遍（可那并不是问题所在）。
     *
     * 判据：`CaptureSessionSignal.lastEndSource != null` ⇒ 本进程里**有过会话**（现在断了）
     * ⇒ 说"**重新**授予"；null ⇒ 真的一次都没建起来，才说"先授予"。
     * （凭证一次性，两条路都得回授权页，差别只在话**对不对得上用户刚做过的事**。）
     */
    private fun noSessionReason(): String = if (CaptureSessionSignal.lastEndSource == null) {
        context.getString(R.string.floating_reason_no_session)
    } else {
        context.getString(R.string.floating_reason_session_ended)
    }

    /**
     * **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入）：把本 App 拉到前台**并带上标记**，
     * App 收到后**自动**拉起系统的屏幕采集授权弹窗 ⇒ 用户只需在弹窗里选「共享一个应用」+ 选中游戏。
     *
     * 为什么必须经 App 这一趟：**系统授权弹窗只能由 Activity 用 `startActivityForResult` 拉起**，
     * 而凭证不落盘、只经内存交给 `CaptureService`（ADR-001）—— 悬浮窗是无障碍 Service，没有 result 通道。
     * 效果上仍是"一键"：菜单点一下 ⇒ 弹窗直接出现在眼前（不再需要"返回 App → 找授权页 → 点建立采集"三步）。
     * FR-08「授权必须由用户在前台界面主动确认」仍然成立：**确认这个动作永远由用户在弹窗里做**。
     *
     * ⚠ 若这次授权**一帧都没被投喂**（`FrameFreshness.isStarved()`），同一进程里重建会话常常救不回来
     * —— 那时菜单最上面那条原因行已经写清"要用「退出」重开 App"，本入口仍给（用户可能想再试一次）。
     */
    private fun onRequestReauthorize() {
        MmLog.i(
            TAG,
            "菜单：点「重新授权采集」⇒ 拉起 App 并自动请求系统采集授权" +
                "（弹窗里选「共享一个应用」+ 选中游戏）",
        )
        // 先收起（操作已经结束）：App 马上到前台，回来时手柄仍在原位、以收起态重建
        collapse()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_REAUTH_CAPTURE, true),
        )
    }

    /**
     * 「更多」层（2026-09-30 用户口径）：**低频维护类**动作 —— 导入服务器（试读这一屏已整体移除）。
     *
     * 前两版它们直接摆在主菜单上；现在主菜单只留高频（换号 / 拜访 / 换号拜访 / 拜访规则 / 更多 / 退出 / 停止），
     * 好处是"误触代价最大的那几行"（停止、换号拜访）周围更干净。
     */
    private fun renderMore(container: LinearLayout) {
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_back),
                context.getString(R.string.floating_menu_more),
            ) {
                menuState = FloatingMenu.State()
                rerender()
            },
        )
        // 导入服务器（2026-09-30 改文案："添加这一屏的服务器" → "导入服务器"）
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_capture),
                R.string.floating_menu_import_servers,
            ) { onCaptureServers() },
        )
        // **一键重新授权采集**（2026-10-01 用户口径，M5 期间插入）：与根菜单那条**条件行**是同一个动作
        //（见 [renderRoot] 里 [FloatingMenu.shouldOfferReauthorize] 的调用点），这里是**常驻**通路 ——
        // 标定产物改过之后"下次建立采集会话时生效"（`calibration_take_effect`）需要一条随时可用的
        // 重建入口，不能等出了故障才给。放在「导入服务器」旁边：两者都与采集 / 数据有关。
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_reauthorize),
                R.string.floating_menu_reauthorize,
            ) { onRequestReauthorize() },
        )
        //（「试读这一屏」已按用户口径**移除**，含后续要删的功能逻辑 —— 见 progress 第 313 条。）
        // **暂停 / 恢复「自动关弹窗」**（2026-09-30，同日从主菜单挪进「更多」）：
        // 有些界面本来就有"关闭 / 返回"按钮（**不是活动弹窗**），那时不该自动点 ⇒ 用户要能**随时**停掉这条自动化。
        // 暂停期间识别照常（状态照判、日志照记），只是**不动手**。
        container.addView(
            menuRow(
                context.getString(
                    if (PopupCloseSignal.paused) {
                        R.string.floating_menu_glyph_resume_close
                    } else {
                        R.string.floating_menu_glyph_pause_close
                    },
                ),
                if (PopupCloseSignal.paused) {
                    R.string.floating_menu_resume_close
                } else {
                    R.string.floating_menu_pause_close
                },
            ) {
                val nowPaused = !PopupCloseSignal.paused
                PopupCloseSignal.paused = nowPaused
                MmLog.i(
                    TAG,
                    if (nowPaused) "用户暂停了自动关弹窗（只识别、不动手）" else "用户恢复了自动关弹窗",
                )
                rerender()
            },
        )
        // **重置提示位置**（2026-10-01 用户口径）：把顶部那条提示标签放回默认位置（垂直靠近屏顶、水平居中）。
        // 标签是唯一可拖动的部件（手柄不可拖），拖远之后没法"用眼睛对准正中" ⇒ 给一个一键归位；
        // 归位后**换文案也不会偏**（走默认口径按当前宽度重新居中，见 [resetLabelPosition]）。
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_reset_label),
                R.string.floating_menu_reset_label,
            ) { resetLabelPosition() },
        )
        // **「退出」**（2026-09-30 用户口径：从主菜单挪进「更多」，icon 用关闭形）：停所有流程 + 摘悬浮窗 + 结束本进程
        //（本进程死掉 = 无障碍服务也停 ⇒ 悬浮窗不会再被重新挂回来）。
        container.addView(
            menuRow(
                context.getString(R.string.floating_menu_glyph_exit),
                R.string.floating_menu_exit,
            ) { onExitApp() },
        )
    }

    /**
     * 「退出」（2026-09-30 用户口径）：**停掉所有流程 + 摘掉悬浮窗 + 结束本 App 进程**。
     *
     * 为什么直接杀进程：无障碍服务、采集服务、悬浮窗都在**本进程**里 —— 进程一死，
     * 三者一起停，且不会出现"只剩服务在跑、悬浮窗没了"的半死状态。
     * 先发一次「停止」请求（让消费方把流程状态清干净、日志留痕），摘窗，再延迟一小拍杀进程
     *（给摘窗的同步 binder 与日志落盘留时间）。
     */
    private fun onExitApp() {
        MmLog.i(TAG, "用户点了「退出」：停止流程 + 移除悬浮窗 + 结束 App 进程")
        runCatching { sendRequest(PatrolRequestSignal.Kind.STOP, keepExpanded = false) }
        hide()
        mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 400L)
    }

    /**
     * **导入服务器**（2026-09-24 加；2026-09-30 从根菜单挪进「更多」并改名）：
     * 读产物里框选的「服务器列表区域」→ 解析出条目 → 产出**复核草稿**（先看后写，落盘在 [writeCaptureDraft]）。
     *
     * 为什么这里也要**先收起面板**：识别读的是整块屏幕的镜像，而我们自己的菜单面板就画在屏幕上
     * （它压着的是列表右半边）—— 不收起来，那一片像素就是面板本身，识别自然读不到或读错。
     * 收尾（草稿到货 / 超时）共用 [resumeOcrPanelIfReady]。
     *
     * 与三个"执行型"动作不同：它**不启动流程、不点击游戏**，只改服务器清单；
     * 但同样要求**采集会话在**（没有会话就没有帧、结果永远不会到 ⇒ 当场说清，别让人以为坏了）。
     *
     * ⚠ 入口在一级菜单（「更多」）⇒ 用户可能**在任何画面**点它。所以采集端加了一道前置判据
     * （见 `FriendListOcrTryout.captureServers` 的 `state` 参数）：不在「服务器列表」就
     * **一个字节都不写**并如实说清现在是什么画面。
     */
    private fun onCaptureServers() {
        if (!CaptureSessionSignal.isActive) {
            MmLog.w(TAG, "添加这一屏的服务器被拦下：没有采集会话（没有帧可读）")
            fail(noSessionReason())
            return
        }
        // **重新开始一轮采集**：丢掉上一份草稿与单选（「再读一屏」那条路才累积，见 [onCaptureMore]）
        FriendListOcrSignal.clearCapture()
        clearCaptureChoices()
        FriendListOcrSignal.request(FriendListOcrSignal.Mode.CAPTURE_SERVERS)
        // 层级先切到结果页（不立刻 rerender：面板马上要收掉，这一帧谁也看不见）
        menuState = FloatingMenu.openOcrResult(menuState)
        expanded = false
        applyExpandedState()
        applyPositions()
        detachLabel()
        // 整屏读取是**分块**跑的（4 块 × 单块上限 2s），比试读慢 ⇒ 面板回来的兜底时间另取一个值
        ocrResumeDeadlineMs = SystemClock.elapsedRealtime() + CAPTURE_RESUME_TIMEOUT_MS
        MmLog.i(
            TAG,
            "已请求添加这一屏的服务器（第 ${FriendListOcrSignal.tick} 次）：面板先收起让开画面，结果出来再展开",
        )
    }

    /**
     * **「再读一屏」**：读当前屏幕并**并进**已在复核的那份草稿（[FriendListOcrSignal.requestMore]）。
     *
     * 与 [onCaptureServers] 的差别只有一处：**不清草稿**。滚一段再点一次，攒齐 14 台后一次性写入。
     */
    private fun onCaptureMore() {
        if (!CaptureSessionSignal.isActive) {
            MmLog.w(TAG, "「再读一屏」被拦下：没有采集会话（没有帧可读）")
            fail(noSessionReason())
            return
        }
        FriendListOcrSignal.requestMore()
        expanded = false
        applyExpandedState()
        applyPositions()
        detachLabel()
        ocrResumeDeadlineMs = SystemClock.elapsedRealtime() + CAPTURE_RESUME_TIMEOUT_MS
        MmLog.i(
            TAG,
            "已请求再读一屏（第 ${FriendListOcrSignal.tick} 次）：结果并进当前复核草稿",
        )
    }

    /**
     * **复核页上唯一落盘的那一下**（2026-09-25 用户口径：确认后才导入）。
     *
     * 三条硬口径：
     * 1. **清单读不了就一个字节都不写**（ADR-006：不拿坏文件当空清单 —— 那会把用户原有条目悄悄抹掉）；
     * 2. 合并走 [ServerListCapture.merge]（**只做加法 + 区号护栏**；用户填过的字段一个字都不改）；
     * 3. 写成功才清草稿、刷新菜单里的名字，并**回根菜单**留一行"已写入…"（看得见结果）。
     */
    private fun writeCaptureDraft() {
        val draft = (FriendListOcrSignal.capture as? ServerCaptureOutcome.Draft)?.draft ?: return
        val existing = try {
            ServerListStore.load(context) ?: ServerList.EMPTY
        } catch (t: Throwable) {
            MmLog.w(TAG, "写入服务器清单失败（读不了清单，一个字节都没写）：${t.message}")
            fail(context.getString(R.string.capture_write_failed, t.message ?: t.javaClass.simpleName))
            return
        }
        // **只写用户采纳的那批读数**（同一区号读出的多种写法里选中的那一个；选了"清单里的"就不写）
        val merge = ServerListCapture.merge(existing, draft.review(existing, captureChoices).toWrite)
        try {
            if (merge.list != existing) ServerListStore.save(context, merge.list)
        } catch (t: Throwable) {
            MmLog.w(TAG, "写入服务器清单失败：${t.message}")
            fail(context.getString(R.string.capture_write_failed, t.message ?: t.javaClass.simpleName))
            return
        }
        MmLog.i(
            TAG,
            "复核确认写入服务器清单：新增 ${merge.added.size} 条｜补全 ${merge.filled.size} 条｜" +
                "已存在未改 ${merge.skipped.size} 条｜冲突未写 ${merge.conflicts.size} 条｜现共 ${merge.list.size} 条",
        )
        FriendListOcrSignal.clearCapture()
        clearCaptureChoices()
        // 菜单里的服务器名（以及"下一个是谁"的底稿）要跟着刷新
        loadMenuOptions()
        // 结果只报"写没写 + 现共几条"（2026-09-28 用户口径：不要那串统计）；冲突单独补一句短的
        menuState = FloatingMenu.State(
            reason = context.getString(
                if (merge.added.isEmpty() && merge.filled.isEmpty()) {
                    R.string.capture_written_none
                } else {
                    R.string.capture_written
                },
                merge.list.size,
            ) + if (merge.conflicts.isEmpty()) {
                ""
            } else {
                context.getString(R.string.capture_written_conflict, merge.conflicts.size)
            },
        )
        rerender()
    }

    /** 放弃这次采集：清草稿、回根菜单（不留原因条 —— 用户是自己点的放弃，不需要解释）。 */
    private fun discardCapture() {
        MmLog.i(TAG, "放弃这次采集复核（服务器清单没有改动）")
        FriendListOcrSignal.clearCapture()
        clearCaptureChoices()
        backToRoot()
    }

    /** 读数页的标题：与菜单里那一行同名（现在只剩「导入服务器」这一种读数动作）。 */
    private fun ocrTitleRes(): Int = R.string.floating_menu_capture_servers

    /**
     * 换号：按清单顺序换下一个（不指定目标）。
     *
     * **清单为空时先给看得见的提示**（2026-09-24）："下一个是谁"完全由清单顺序决定，
     * 空清单根本没有下一个 —— 消费方也会拦一道（不启动 + 日志），但那边**没有界面出口**，
     * 用户只会觉得"点了没动静"。这里的判据与消费方同源：都只看"清单空不空"。
     */
    private fun onSwitchNext() {
        if (!ensureReady()) return
        if (serverNames.isEmpty()) {
            fail(context.getString(R.string.floating_menu_no_server))
            return
        }
        sendStartRequest(PatrolRequestSignal.Kind.SWITCH_NEXT)
    }

    /** 换号到指定区服（服务器清单里点某一行）。 */
    private fun onSwitchServer(serverName: String) {
        if (!ensureReady()) return
        sendStartRequest(PatrolRequestSignal.Kind.SWITCH_SERVER, serverName = serverName)
    }

    /**
     * 停止（T4-5 起**真的能停**）：跑着就发请求并收起（消费方 `PatrolSession.stop()`）；
     * 没跑则照常发出、**保持展开**并说明"当前没有进行中的流程"。
     *
     * 两种情形分开，是因为 M3 只有后一种：那时点了永远只看到"没有进行中的流程"，
     * 现在真有流程可停，再一律这么说就成了假话。
     */
    private fun onStop() {
        if (!ensureReady()) return
        // **广播之前先读**：消费方（PatrolRequestConsumer）在广播里就把流程清掉了，
        // 之后再读 `PatrolSession.current` 永远是 null，"刚才停在哪一步"就没了
        val running = PatrolSession.current
        sendRequest(PatrolRequestSignal.Kind.STOP, keepExpanded = running == null)
        if (running == null) {
            // **只是告知**："你没在跑，所以我没停任何东西"（用户口径：这种情况也要说出来）⇒ 中性提示色
            note(context.getString(R.string.floating_menu_nothing_running))
        } else {
            MmLog.i(TAG, "停止：停在第 ${running.step.number} 步（面板已收起）")
        }
    }

    /**
     * 继续（T4-5 / FR-05）：从**失败或暂停的那一步**接着来，不重跑前面的步骤。
     *
     * ⚠ **必须收起面板**（2026-10-01 真机缺陷，用户报"重新授权后点继续，没能完成流程"）：
     * 它原先特意 `keepExpanded = true`（理由："用户点完就想看见真的接着跑了"），但那条理由撞上了
     * **物理事实** —— 面板就画在游戏画面上，而恢复后这一步要点的锚点**正好落在面板矩形内**：
     * ```
     * 20:37:03.663  用户请求继续 ⇒ 面板仍展开（(2036,32) 1072x869 ⇒ 覆盖 x∈[2036,3108] y∈[32,901]）
     * 20:37:04.303  第 8 步点 farm_friends (2914,431) ⇒ **落在面板里** ⇒ 被自己的面板吃掉 ✗
     * 20:37:13.932  等了 9.6 秒毫无动静 ⇒ 用户手动停止
     * （对照同一分钟重新发起：20:37:17.482 悬浮窗已收起 ⇒ 20:37:18.558 同一击 ⇒ 2 秒切到好友列表 ✓）
     * ```
     * "真的接着跑了"这件事**由顶部状态标签承接**（跑号中它一直写着 `打开好友列表` 这类当前动作），
     * 不需要面板在场；面板留着只会挡住自己的手。顺带与 FR-07「收起时机：操作成功结束后立即收起」一致。
     */
    private fun onResume() {
        if (!ensureReady()) return
        val resumable = PatrolSession.canResume
        // **没有**可继续的流程时保持展开说明原因（同 [onStop]：那种情况只是"告知"，不是动作）
        sendRequest(PatrolRequestSignal.Kind.RESUME, keepExpanded = !resumable)
        if (resumable) {
            // 收起已由 [sendRequest] 完成；把上一次的原因条清掉（下次展开时只剩进度）
            menuState = FloatingMenu.State()
        } else {
            // 同「停止」：没有可继续的流程只是**告知**，不是失败 ⇒ 中性提示色
            note(context.getString(R.string.floating_menu_nothing_to_resume))
        }
    }

    /** 执行前的前置检查：**常驻守护在** + 采集会话在 + 预设文件可读（内容是否配好由各动作自己判定）。 */
    private fun startBlockReason(): String? {
        // 常驻守护是**必备项**（2026-09-20 用户口径）：没有它 = 菜单请求没人接、点击也一律被拒
        // （`ClickGate` 的 GUARD_NOT_RUNNING）。放在最前面，是因为它是"整个自动化在不在"的总开关。
        if (!PatrolRequestConsumer.isInstalled) {
            return context.getString(R.string.floating_menu_no_resident)
        }
        if (!CaptureSessionSignal.isActive) {
            return noSessionReason()
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
        // 状态标签（第三窗）：**只在屏幕尺寸变了时重算**（转屏 / 改分辨率）——
        // 它有自己的落位与拖动逻辑，见 [placeLabel]；文本变化走 [updateLabelText] 的"保中心"那条路。
        val labelView = label?.takeIf { labelAttached }
        val screen = FloatingScreen.spec(context)
        if (labelView != null && labelScreenAt != (screen.width to screen.height)) {
            val (labelWidth, labelHeight) = viewSize(labelView)
            placeLabel(labelView, labelPlacement(labelWidth, labelHeight), labelWidth, labelHeight)
        }
        // 取证 / 排障用途：把"用了多大的屏、把各窗口放到了哪"记进日志——
        // 悬浮窗看不见或位置不对时，一行日志就能判断是尺寸错了还是位置值错了（真机排障踩过坑）。
        val menuTrace = if (menuView != null) {
            " ｜ 菜单 (${offset.menuX},${offset.menuY}) ${menuWidth}x$menuHeight"
        } else {
            ""
        }
        val labelTrace = if (labelView != null) {
            val params = labelView.layoutParams as WindowManager.LayoutParams
            " ｜ 标签 (${params.x},${params.y}) ${params.width}x${params.height}"
        } else {
            ""
        }
        val trace = "屏 ${screen.width}x${screen.height} ｜ 手柄 (${offset.panelX},${offset.panelY})" +
            " ${panelWidth}x$panelHeight$menuTrace$labelTrace"
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

    /**
     * **内容比屏幕高就就地压回去**（2026-09-25 用户两次报障：多屏采集后提示行变多 ⇒ 面板顶破屏幕；
     * 切后台再回前台 ⇒ 同样溢出）。
     *
     * 为什么会有这一出：菜单窗是 `WRAP_CONTENT`，而 [measureSelf] 用的是 **UNSPECIFIED** 规格
     * （量的是"内容想要多高"），所以内容一多窗口就长到屏幕之外；而可滚区的高度又是**建页那一刻**
     * 按当时屏幕高算死的（[boundedScroll]）⇒ 几何一变（转屏 / 切 App 回来）就会算出"旧几何的高度"。
     *
     * 做法：量一次；超出可用高（[FloatingMenu.panelMaxHeight]）就把**可滚区按差值收矮**再量一次 ——
     * 固定行（摘要 / 按钮 / 冲突说明）全都留着，代价只是列表多滚一点。
     */
    private fun fitMenuToScreen(container: LinearLayout) {
        val density = context.resources.displayMetrics.density
        val screenHeightDp = (FloatingScreen.spec(context).height / density).toInt()
        val maxPx = dp(FloatingMenu.panelMaxHeight(screenHeightDp))
        val overflow = container.measuredHeight - maxPx
        if (overflow <= 0) return
        val scroll = lastBuiltScroll
        if (scroll == null) {
            // 没有可滚区的层级（根菜单等）：正常内容远低于这个上限，真溢出说明有 bug —— 如实记一条
            MmLog.w(
                TAG,
                "菜单内容比屏幕可用高高出 ${(overflow / density).toInt()}dp，但这一层没有可滚区（无法收矮）",
            )
            return
        }
        val params = scroll.layoutParams as LinearLayout.LayoutParams
        val minPx = dp(MIN_SCROLL_HEIGHT_DP)
        val shrunk = (params.height - overflow).coerceAtLeast(minPx)
        if (shrunk >= params.height) return
        params.height = shrunk
        scroll.layoutParams = params
        measureSelf(container)
        MmLog.i(
            TAG,
            "菜单内容高于屏幕可用高 ${(overflow / density).toInt()}dp ⇒ 可滚区收矮到 ${(shrunk / density).toInt()}dp" +
                "（避免面板顶破屏幕）",
        )
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

    /**
     * 状态标签窗参数：**可触摸**（它是悬浮窗里唯一可拖动的部件，2026-09-28 用户口径"可以拖动的悬浮窗"），
     * 不聚焦（不抢输入法 / 焦点）。尺寸按 [attachLabel] 量好后**显式写像素**（WRAP_CONTENT 在
     * WindowManager 上常被测成 0×0）；不挂 `FLAG_NOT_TOUCHABLE`（挂了就没法拖）。
     */
    private fun labelParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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

        /**
         * 手柄**需要用户处理**（跑号中止 / 暂停）时的配色：换暖橙红（T4-5）。
         *
         * 为什么靠颜色而不是加个字：2026-09-17 用户口径已经定过"取消状态标签"（收起态不写字），
         * 而"停在半路上"恰恰是**最需要在收起态被发现**的一刻。颜色是这条约束下唯一还空着的通道
         * （读屏那一侧由 `contentDescription` 补，见 [applyHandleAlert]）。
         */
        const val HANDLE_ALERT_FILL_COLOR = 0x66FF7043.toInt()
        const val HANDLE_ALERT_STROKE_COLOR = 0xCCFF5722.toInt()

        /** 展开菜单面板（参考 vivo 游戏魔盒）：深色半透明 + 淡琥珀描边 + 白字。 */
        const val MENU_FILL_COLOR = 0xE61C1C1C.toInt()
        const val MENU_STROKE_COLOR = 0x80FFC107.toInt()
        const val MENU_TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val MENU_GLYPH_COLOR = 0xFFFFC107.toInt()

        /**
         * 失败原因（深底浅底通用：亮红）= **L1 失败 / 被拒**（`docs/decisions/ADR-008-信息级别与配色.md`）。
         *
         * **只用于"用户需要动手"的话**（做不了 / 被拦下 / 当前不可用）—— 判据与其余级别见 [NOTICE_COLOR]。
         */
        const val REASON_COLOR = 0xFFFF8A80.toInt()

        /**
         * 提示 / 回执（**浅青蓝**）= **L3 提示 / 回执**（`docs/decisions/ADR-008-信息级别与配色.md`）：
         * **"只是告诉你一件事"**的话用这个。
         *
         * 用户 2026-10-01 口径：①"**提示类信息不应该使用红色**"（红是"要你处理"的信号，用在回执上
         * 会让人以为出了错）；②"**中性白不够明显**"。
         *
         * ## 为什么不是白（第一版踩的坑）
         *
         * 第一版用 `0xCCFFFFFF`（白 80%）——**问题不是亮度，是色相没差别**：菜单里功能行本来就是一整片白字，
         * 再亮也只是"更白的白"，眼睛不会把它拎出来。要有区别，就得**换色相**。
         *
         * ## 为什么是青蓝
         *
         * - **与红拉得开**（失败 / 需要你处理 ⇒ [REASON_COLOR]）——"提示"与"故障"一眼分得清；
         * - **与白拉得开**（普通文字 / 状态行）——它自己成一块；
         * - **与面板的琥珀**（描边 + 行首图标）也拉得开 ⇒ 不会被读成"某个按钮"；
         * - 观感上就是"信息"而不是"警告"（绿会读成"成功"、黄会读成"警告"）。
         * ⚠ 深底上的对比：`#80D8FF` on `#1C1C1C` ≈ 12:1，11sp 也读得清。
         */
        const val NOTICE_COLOR = 0xFF80D8FF.toInt()

        /** 进度行 / 状态行的文字 = **L6 状态 / 次要**（比功能行弱一档：它是"报状态"，不是可点的动作）。 */
        const val STATUS_COLOR = 0xB3FFFFFF.toInt()

        /** 进度巡检周期（ms）：0.5s 跟得上"步数变化"，又不至于频繁建字符串（T4-5）。 */
        const val STATUS_TICK_MS = 500L

        /**
         * 「**导入服务器**」的面板兜底时间（2026-09-24）：它的读取是**整屏分 4 块**跑的
         * （单块上限 2s，另有解析），最坏 ≈8~9 秒 —— 面板在读到一半就被拍回来不行
         * （正好压在列表上，还可能挡住后续要读的画面）。取 15s 兜住"什么都不会发生"。
         */
        const val CAPTURE_RESUME_TIMEOUT_MS = 15_000L

        /**
         * 复核页里"除滚动区以外的固定高度"（传 [boundedScroll] 的 `chromeDp`）。
         *
         * 沿革：190dp（那时是"返回行 + 摘要行 + 滚动提示行 + **三个各占一行的按钮** + 冲突说明"）
         * → **150dp**（2026-09-28：三个按钮改成**并排一行**、滚动提示行去掉 ⇒ 固定部分矮了一大截，
         * 把省下的高度还给卡片列表）。仍然偏保守（宁可少显示一条，也不让面板顶破屏幕）。
         */
        const val CAPTURE_REVIEW_CHROME_DP = 150

        /**
         * 可滚区**最少留多高**（[fitMenuToScreen] 收矮时的下限）。
         *
         * 44dp ≈ 一条半列表行：再矮就没法"看出还有内容"了；到这一步说明固定行本身太多
         * （真发生就是设计问题，日志里会写清收矮到了多少）。
         */
        const val MIN_SCROLL_HEIGHT_DP = 44

        /** 复核页卡片：圆角 / 卡片底色（比面板底亮一档）/ 卡与卡之间的间隙 / 卡内字号。 */
        const val CARD_CORNER_DP = 8
        const val CARD_FILL_COLOR = 0x1AFFFFFF
        const val CARD_GAP_DP = 4
        const val CARD_TITLE_SP = 12f
        const val CARD_DETAIL_SP = 11f

        /**
         * 复核页的**行高**与**按钮高**（2026-09-28）。
         *
         * 列数 / 列间隙 / 卡片宽**不在这里**：它们是"半屏宽"的函数（[FloatingMenu.reviewCardWidth]）
         * —— 面板宽度按用户口径定成**半屏**，卡片宽跟着算出来，两列才永远严格等宽。
         * 行高按 **56dp** 估（两行小字 + 卡片内边距；**有字段要选的卡**更高，估小了由 [fitMenuToScreen] 兜底收矮）。
         */
        const val CARD_ROW_HEIGHT_DP = 56

        /** 复核页底部按钮的高度（三个并排一行，见 [captureActionRow]）。 */
        const val CARD_BUTTON_HEIGHT_DP = 26

        /**
         * 手柄（dp）：**可见条**与**透明触摸扩展**分开定义（2026-09-14）。
         *
         * 沿革：28×64（可见 14）→ 14×32（可见 7）→ 19×32 一半在屏外（可见约 10）→
         * **可见条 7×32 + 触摸扩展 16×12（窗口 23×44）** → 41×16（窗口 48×48）→ **回到 16×12**。
         *
         * **48dp 那一步被真机实测否掉（2026-09-20 用户口径："太宽了，会遮挡操作，必须缩减"）**：
         * 交互顾问按"Android 最小触控目标 48dp"建议扩宽，方向没错（原来 23dp 确实偏小），
         * 但**横向遮挡的代价被低估了**——多吞的那 25dp 正好压在游戏右缘的可点区域上，
         * 而"是否挡住游戏"只有真机能判。**结论：横向宽度以不遮挡为准（回到 23dp），
         * 命中率不再靠加宽，靠纵向（见 [HANDLE_TOUCH_EXTRA_HEIGHT_DP]）与手势排除区兜**。
         *
         * 为什么不再"一半移出屏幕"：移出去的部分**收不到触摸**，等于只让用户点那 7dp。
         */
        const val HANDLE_VISUAL_WIDTH_DP = 7
        const val HANDLE_VISUAL_HEIGHT_DP = 32
        const val HANDLE_TOUCH_EXTRA_WIDTH_DP = 16

        /**
         * 触摸区的**纵向**扩展：12dp（窗口高 44dp）。
         *
         * 刻意比横向保守、也比横向大：**纵向多占一点几乎无感**（游戏右缘横着排的按钮少），
         * 而"点不准"多半是**竖向滑出去**（手指从条上滑开）。所以命中率主要靠这一维挣，
         * 横向只求不遮挡 —— 这是 2026-09-20 那次"扩到 48dp 被实测否掉"换来的认识。
         */
        const val HANDLE_TOUCH_EXTRA_HEIGHT_DP = 12

        /**
         * **告警态**的可见条高度（T4-5）：32 → 44dp。
         *
         * 44dp 是窗口的整个高度（[HANDLE_VISUAL_HEIGHT_DP] + [HANDLE_TOUCH_EXTRA_HEIGHT_DP]）——
         * 即"条变长到填满触摸区"，**窗口尺寸 / 位置 / 触摸范围一点不动**（不改布局，就不会有落位时序）。
         * 交互顾问指出颜色不能是唯一通道：色觉障碍、背景同为暖色的农场画面都会让色差失效。
         */
        const val HANDLE_ALERT_HEIGHT_DP = 44

        /** 告警脉冲：半周期 200ms、往返 3 次 ≈ 1.2s，谷值透明度 0.55（够显眼又不闪得烦）。 */
        const val HANDLE_PULSE_HALF_MS = 200L
        const val HANDLE_PULSE_COUNT = 5
        const val HANDLE_PULSE_LOW_ALPHA = 0.55f

        /** 菜单面板（独立窗口：圆角 + 内边距 + 与屏幕 / 手柄的间距）。 */
        const val MENU_CORNER_DP = 14
        /** 菜单图标槽位宽度（dp）：固定住才能让每行文字左对齐（见 [menuRow]）。 */
        const val MENU_GLYPH_SLOT_DP = 18

        /**
         * 功能行**行尾小按钮**的字号与左右内边距（dp）（2026-09-30 加的范式，见 [menuRowWithTrailing]）。
         *
         * 比行文字（12sp）大一档：`⚙` 这类符号在 12sp 下太小，看着像噪点；
         * 左右各 [TRAILING_GLYPH_PADDING_H_DP] 的内边距把它从行文字里"分出去"（不至于和文字粘在一起），
         * 同时把触摸宽度撑开（行高本身 48dp 量级，宽度靠内边距补）。
         */
        const val TRAILING_GLYPH_TEXT_SIZE_SP = 15f
        const val TRAILING_GLYPH_PADDING_H_DP = 12

        /**
         * 功能行**副文**（第二行小字）的字号与限宽（dp）（2026-09-30，见 [menuLabelView]）。
         *
         * 字号比主文（12sp）小一档、颜色用 [STATUS_COLOR]（与进度行同一档"弱化但不隐"）；
         * 限宽与原因条同源（[REASON_MAX_WIDTH_DP] 那一套）：副文是可能很长的句子（好友名），
         * 面板宽度是**内容撑出来的** ⇒ 不限宽会被撑宽。
         */
        const val MENU_SUB_TEXT_SIZE_SP = 10f
        const val MENU_SUB_TEXT_MAX_WIDTH_DP = 180

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

        /**
         * 状态标签的字号与内边距。
         *
         * 字号 **11sp** 与菜单里的状态行（[statusRow]）同一档：标签要"一眼看出在做什么"，
         * 又不能挡游戏画面 —— 比正文小一档、比最小的可读字号大一档。
         *
         * 内边距 **14×4dp**（横向从 10dp 加到 14dp，2026-09-29 用户报障"宽度有点窄了，
         * 某些字都无法显示完全"）：标签宽度是"按内容量出来的"，一个字紧贴到圆角边缘时，
         * 最后一个字看着就像被切掉 ⇒ 左右各多留 4dp 透气。
         */
        const val LABEL_TEXT_SIZE_SP = 11f
        const val LABEL_PADDING_H_DP = 14
        const val LABEL_PADDING_V_DP = 4

        /** 失败原因文字的最大宽度（dp）：避免窗口被长文案撑宽（FR-07 尺寸紧凑）。 */
        const val REASON_MAX_WIDTH_DP = 200

        /**
         * 根菜单顶部**步骤说明**两行（进度 / 状态）的最大宽度（dp）。
         *
         * 比 [REASON_MAX_WIDTH_DP] 宽：那 200dp 是给**异常长句**兜底的，而这里放的是
         * "第 10/10 步 · 点区服「发条权杖」"这类**必须看全**的步骤说明 ——
         * 200dp 在 11sp 下只装得下 ≈18 个汉字（2026-09-29 用户报障："悬浮窗根菜单顶部步骤说明显示不完全"）。
         * 仍保持**单行 + 省略号**：行数一变菜单高度就变，正在点菜单的人会点错行（见 [statusRow]）。
         */
        const val STATUS_MAX_WIDTH_DP = 260
    }
}
