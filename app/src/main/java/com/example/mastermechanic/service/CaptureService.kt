package com.example.mastermechanic.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Display
import java.util.Locale
import com.example.mastermechanic.log.MmLog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.mastermechanic.MainActivity
import com.example.mastermechanic.R
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.action.SystemKeys
import com.example.mastermechanic.action.TextInjector
import com.example.mastermechanic.patrol.FriendSearch
import com.example.mastermechanic.patrol.TextInjectorLike
import com.example.mastermechanic.action.ClickSource
import com.example.mastermechanic.action.PopupCloseController
import com.example.mastermechanic.action.PopupCloseSignal
import com.example.mastermechanic.action.PopupRoundInput
import com.example.mastermechanic.action.PopupRoundOutcome
import com.example.mastermechanic.action.PopupStep
import com.example.mastermechanic.action.PopupVerification
import com.example.mastermechanic.action.PreFireRecheck
import com.example.mastermechanic.action.ScreenSize
import com.example.mastermechanic.capture.FrameFreshness
import com.example.mastermechanic.calibration.CalibrationFramePool
import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.CalibrationReloadSignal
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.PatrolDrivenExpectedSignals
import com.example.mastermechanic.decision.PopupWatchExpectedSignals
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.patrol.FriendListOcrSignal
import com.example.mastermechanic.patrol.NameLocating
import com.example.mastermechanic.patrol.NameLocator
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.NameReader
import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.patrol.PatrolRunner
import com.example.mastermechanic.patrol.PatrolResultSignal
import com.example.mastermechanic.patrol.PatrolScenes
import com.example.mastermechanic.patrol.PatrolSession
import com.example.mastermechanic.patrol.PatrolStatus
import com.example.mastermechanic.capture.CalibrationActiveSignal
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.FrameExpectationSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.servers.ServerRotation
import com.example.mastermechanic.capture.FrameThrottle
import com.example.mastermechanic.capture.FrameTimingStats
import com.example.mastermechanic.capture.FrameSignature
import com.example.mastermechanic.capture.FrameUniformity
import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.patrol.RealmPickDecision
import com.example.mastermechanic.patrol.ServerListScan
import com.example.mastermechanic.patrol.SignalNames
import com.example.mastermechanic.servers.ServerListStore
import com.example.mastermechanic.decision.AnchorLocator
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.Verdict
import com.example.mastermechanic.decision.RoundResult
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.floating.FloatingScreen
import com.example.mastermechanic.foreground.ForegroundSignal
import com.example.mastermechanic.calibration.SelectionWindow
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.PixelBounds

/**
 * 采集会话服务（T1-2，ADR-001）：以 mediaProjection 类型前台服务承载一次采集会话。
 *
 * - 授权凭证经 Intent 一次性传入，不落盘、不复用（ADR-001 第 1 条）；
 * - Android 14+ 顺序要求：先 startForeground（mediaProjection 类型）再创建会话；
 * - 会话终止两条路径（系统侧回收 / 应用主动停止）均汇聚到 [CaptureSessionSignal]，
 *   输出带来源的终态日志（验收 A1 证据本体）；「用户切到别的应用」不触发终止（FR-09）；
 * - 帧管线（T1-4 起）：ImageReader 取帧 → [FrameThrottle] 节流（NFR-02 两档自适应）→
 *   帧转换 → [RecognitionLoop]（检测 / 映射 / 滞回）→ 状态变化输出 [UiStateSignal]
 *   （T1-7：悬浮窗等订阅方随识别结论展示）；每 10s 输出一条存活统计；单帧处理耗时按
 *   连续 100 帧窗口统计输出（T1-9，NFR-01）；全程零点击（红线 1/2）。
 *
 * 分辨率 / 密度运行时从系统读取（ADR-001 第 4 条：零设备常量）。
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameThrottle = FrameThrottle(ACTIVE_INTERVAL_MS)

    /**
     * 上一次**新帧**到达的时刻（只在 [processFrame] 里更新；静止兜底的重放**不算**）。
     *
     * 用途只有一个：把"帧流是不是还活着"写进心跳日志。2026-09-23 真机排障时，
     * "这批帧到底是新来的还是在重放旧帧"是唯一能把"镜像失效"与"帧是空的"分开的证据。
     */
    private var lastNewFrameAtMs = 0L

    /**
     * **帧回调探针**（2026-09-30 加，真机"零帧"事故）：`onImageAvailable` 被调用的次数。
     *
     * ## 为什么非要它（一条被日志掩盖的盲区）
     *
     * 真机实录（2026-09-30 21:02 那次会话）：整场**只有 1 帧、还是空壳**，此后 60s / 120s 心跳零新帧。
     * 可"**平台根本没往 surface 投喂**"与"**投喂了、我们却取不到 buffer**"在日志里**长得完全一样**：
     * [handleFrameAvailable] 里 `acquireLatestImage()` 返回 null / 抛 `IllegalStateException` 都是
     * **静默 return**，而 `framesReceived++` 在它之后 ⇒ 两种情况都不留痕。而它们的处置**相反**：
     * 前者要换表面 / 请用户重新授权，后者是我们自己的缓冲没归还（`MAX_IMAGES = 2` 的队列卡死）。
     * ⇒ 计数 + [logFrameProbeIfQuiet] 那一行，把这两件事分开（见那里的判读口径）。
     */
    private var frameCallbacks = 0L

    /** 与 [frameCallbacks] 配套：回调来了、却**取不到 buffer**（null / reader 已关）的次数。 */
    private var frameAcquiresEmpty = 0L

    /**
     * 「换镜像表面」已经试了几次（每个会话归零，见 [checkSurfaceRetry]）。
     *
     * 为什么要有上限：这是一次"死马当活马医"的动作（当前已经是零帧，试了最坏不更差），
     * 但**不能无限抽**——两次还不回来就说明这条通路救不回来，该如实让用户重新授权 / 重启 App。
     */
    private var surfaceRetries = 0

    /** 下一次允许尝试「换镜像表面」的时刻（0 = 立刻可以；冷却见 [SURFACE_RETRY_COOLDOWN_MS]）。 */
    private var nextSurfaceRetryAtMs = 0L

    /** 自救次数用尽是否已经说过（每个会话只说一次，避免每轮刷屏）。 */
    private var surfaceRetryGaveUp = false

    /**
     * **本次会话是什么时候建立的**（[attachMirror] 里赋值；0 = 还没有会话）。
     * 只给"首次投喂自检"用（[checkFirstFeed]）—— 它要判"建立会话后多久了"。
     */
    private var sessionStartedAtMs = 0L

    /**
     * 「首次投喂自检」是否已经说过（每个会话只说一次，避免每轮刷屏）。
     *
     * ⚠ 它不是"没投喂"的**判决缓存**：判决结果存在 `FrameFreshness.noteStarved()`（界面读的是那一份），
     * 这里只是"这句话说过了"。
     */
    private var firstFeedWarned = false

    /**
     * 最近一次**屏幕几何变化**的时刻（0 = 没有待确认的转屏）。
     *
     * 转屏后要用它确认帧流有没有跟着停（[checkMirrorStall]）—— 镜像不能重建（平台限制，见 [attachMirror]），
     * 只能事后确认；用完即清（一次性）。
     */
    private var mirrorRotationAtMs = 0L

    /**
     * **几何守卫**的起始时刻（0 = 当前没有不一致，elapsedRealtime）。
     *
     * 为什么要它（2026-09-23，与新方案配套）：转屏现在由 `VirtualDisplay.resize()` 跟进（[applyMirrorResize]），
     * 常态下**帧几何 == 屏幕几何 == 标定几何**，所以识别/点击那一堆"几何换算"已经退化成恒等。
     * 一旦 resize 没跟上（平台不给回调 / ROM 拒绝 / 老设备 API<34），帧几何就会和屏幕错开 ——
     * 此时识别读的像素、点击要打的坐标**都不再对应**，"继续跑"等于在错的坐标系里操作。
     * 所以：持续不一致就**如实结束会话**（与帧流看门狗同一条口径：宁可停下让人重建，也不静默乱跑）。
     *
     * 给 [GEOMETRY_MISMATCH_GRACE_MS] 的宽限：转屏那一瞬间两者必然短暂不一致。
     */
    private var geometryMismatchSinceMs = 0L


    /** 屏幕几何监听（转屏 → 重建镜像）；会话结束注销。 */
    private var displayManager: DisplayManager? = null

    /** 识别循环（T1-4 接入）：会话建立时加载标定产物（T1-5）；缺失 / 解析失败回退空配置。仅帧线程访问。 */
    private var recognitionLoop = RecognitionLoop.uncalibrated()

    /** 锚点定位器（T2-4）：随标定产物构建；null = 未标定产物（FR-01 不产生点击）。仅帧线程访问。 */
    private var anchorLocator: AnchorLocator? = null

    /**
     * 当前产物（2026-09-21）：给农场身份判据提供"用途 / 归属好友名"两个查询。
     * 仅帧线程访问；未标定为 null。
     */
    private var calibrationData: CalibrationData? = null

    /** 上一次已应用的产物写入计数（见 [CalibrationReloadSignal]）。仅帧线程访问。 */
    private var lastReloadTick: Long = CalibrationReloadSignal.tick

    /**
     * 上一次已消费的「读数」请求计数（见 [FriendListOcrSignal]，2026-09-23；现在只有「导入服务器」）。
     *
     * 悬浮窗与采集服务**互不持有引用**，只能靠这个计数告诉帧线程"有人在等一次读屏"；仅帧线程访问。
     */
    private var lastOcrTryTick: Long = FriendListOcrSignal.tick

    /**
     * 名称读取通道（M4-T4-3）：按名称定位那两步的**眼睛**，安卓侧实现是 ML Kit 中文识别。
     *
     * 只在第 5 / 9 步 + 对应的列表画面才会被调用（[namePlanOf]）—— 引擎初始化是懒的，
     * 不跑流程就不会付出这份代价。
     */
    private val nameReader: NameReader = MlKitNameReader()

    /**
     * ~~本次预设要拜访的好友名~~（2026-09-21 起改由 [PatrolSession.targetFriend] 承载）——
     * 目标好友是**一次执行**的属性（开始 / 停止随之变化），而采集服务跨多次执行存在，
     * 自己再存一份就会出现"两份状态互相漂移"。见 [PatrolSession.targetFriend]。
     *
     * 原字段已删除；此处保留说明，避免后来者又加一份。
     */

    /** FR-01 弹窗闭环（T2-4）：纯逻辑决策，会话重建即复位。仅帧线程访问。 */
    private var popupClose = PopupCloseController()

    /**
     * 遮挡屏"确认在屏、但一条锚点都没定位到"的**连续轮数**（2026-09-30 加，真机第 302 条）。
     *
     * 为什么要"连续"：画面**正在切换**的那 1~2 轮，锚点认不出是**正常现象**（旧画面已走、新画面还没定住），
     * 那时报"关弹窗受阻"是冤枉程序。真机实录 02:13：
     * ```
     * 02:13:06.005 点击下发 tutorial_hall_e2（新手大厅关掉了）
     * 02:13:07.086 状态标签: 「关弹窗受阻」        ← 误报：1 秒后 fr01-3 就把活动弹窗关了
     * ```
     * ⇒ 与状态机"连续 2 次命中才进入"同一口径：**连续 [OVERLAY_ANCHOR_MISS_ROUNDS] 轮都没锚点才报**。
     */
    private var overlayAnchorMissRounds = 0

    /** 见 [overlayAnchorMissRounds]：连续这么多轮都没锚点，才报"关弹窗受阻"。 */
    private val OVERLAY_ANCHOR_MISS_ROUNDS = 3

    /**
     * 跑号期间的期望集合（T4-4b）：**弹窗 ∪ 当前步骤要认的画面**。
     * 未跑号时它等价守护集合（只搜弹窗）——所以整条链路不必在"跑号 / 没跑号"之间换实现。仅帧线程访问。
     */
    private var patrolExpected: PatrolDrivenExpectedSignals? = null

    /** 跑号判定序号（NFR-05 审计链）：与 `MM-Click` 日志的「判定: patrol-N」对齐。仅帧线程访问。 */
    private var patrolSeq = 0L

    /** 上一次跑号结论（同一句话只记一次日志：等待类结论会连续多轮相同）。仅帧线程访问。 */
    private var lastPatrolNote: String? = null

    /**
     * 连续"点击被拒"的轮数（2026-09-30 加）：成功下发一枪 / 换了一步就归零。
     *
     * 用途只有一个：**连续 ≥ [CLICK_DENY_NOTE_STREAK] 次**才把原因挂到菜单上
     * （见 `PatrolSession.deniedNote`）—— 偶发一次（例如前台抖一下）下一轮就过去了，不值得占那一行。
     * 仅帧线程访问。
     */
    private var deniedStreak = 0

    /** 上一次"点击被拒"的原因（日志去重用：被拒会一轮一行，不去重会把日志刷满）。仅帧线程访问。 */
    private var lastClickDenyDetail: String? = null

    /**
     * 上一次**中止告警**的原因（同一句只播一次浮窗）。仅帧线程访问。
     *
     * **必须与 [lastPatrolNote] 分开**（2026-09-22 真机 P0：浮窗提示永不消失）：
     * 失败分支原本拿 `reason != lastPatrolNote` 判"是否已播报过"，但同一轮里 line 1066 的
     * `outcome.note` 去重又会把 `lastPatrolNote` 改成另一句话（占用同一个字段）⇒ 每轮都判成"变了"
     * ⇒ 每秒播报一次 ⇒ 浮窗的 3 秒计时被不断重置，切到桌面也一直在。
     * 教训：**两个"上次值"语义不同就必须两个字段**；共用字段的去重是"看起来对、实际每轮都放行"。
     */
    private var lastAbortAnnounced: String? = null

    /**
     * 上一次"待命期画面判定"的结论签名（`状态｜本轮命中`）：**变了才打**（2026-09-22）。
     * 仅帧线程访问。
     */
    /** 上一次"待命期画面诊断"的时刻（2026-09-22：改成**每秒一次 + 带分数**）。仅帧线程访问。 */
    private var lastStandbyReportAtMs = 0L

    /** 上一次跑文字识别的时刻（节流用；0 = 还没跑过）。仅帧线程访问。 */
    private var lastOcrAtMs = 0L

    /**
     * 上一次跑文字识别时**在哪一步**（2026-09-24）：复用只在**同一步**内成立 ——
     * 换了步骤，上一步的候选与这一步的列表毫无关系（拿别的画面的字去下结论就是猜）。仅帧线程访问。
     */
    private var lastOcrStep: PatrolFlow.Step? = null

    /** 上一次的文字识别结果（间隔内复用，见 [OCR_MIN_INTERVAL_MS]）。仅帧线程访问。 */
    private var lastOcrCandidates: List<NameCandidate> = emptyList()

    /** 一次文字读取的结果（[readNames]）：[fresh] = 这一轮真的读了（复用的结果不许下结论）。 */
    private data class NameReading(val candidates: List<NameCandidate>, val fresh: Boolean)

    /**
     * 上一次打过日志的文字识别输入区域（2026-09-22）：**只在区域变化时打**。
     *
     * 为什么要打：这块区域是"列表区域 ∩ 好友名称列"算出来的，真机上**看不见**——
     * 不把实际像素值打出来，"框了名称列但没生效 / 两框没对上"就只能靠猜（本项目踩过这类坑）。仅帧线程访问。
     */
    private var lastOcrRegionLogged: PixelBounds? = null

    /**
     * 上一次打过日志的**第 5 步**识别区域（2026-09-24）：两条列竖带各一块，同样**只在变化时打**。
     * 用拼串当键（两块区域 + 未框清单都要看得见）。仅帧线程访问。
     */
    private var lastServerRegionsLogged: String? = null

    /**
     * **第 5 步「逐屏滚动查找」的扫描状态**（2026-09-29 加，需求硬性要求 #4）：
     * 先回到列表顶部、再自上而下逐屏找，滑到"画面不再动"就如实放弃。
     *
     * 判据全在纯逻辑 [ServerListScan]（有单测）；这里只持有实例、按轮把观测喂进去，
     * 并在**滑动真的下发 / 被门禁拒**之后告知它（见 [stepPatrol]）。仅帧线程访问。
     */
    private val serverScan = ServerListScan()

    /** 上一次打过的"第 5 步扫描"那一行的依据（同一句只记一次，免得刷屏）。仅帧线程访问。 */
    private var lastServerScanNote: String? = null

    /** 上一次**新读**到的"行键 → 纵坐标"（量滑动位移用）。仅帧线程访问。 */
    private var lastServerRowYs: Map<String, Double>? = null

    /** 上一次"新读"之后是否下发过滑动（只测量用：没有滑动就不必量位移）。仅帧线程访问。 */
    private var serverScrollSinceRead = false

    /**
     * **第 9 步「逐屏滚动查找」的扫描状态**（2026-10-01，T4-10 刀 2；需求硬性要求 #4 已扩到好友列表）。
     *
     * 与第 5 步**各持一个实例**：同一套纯逻辑 [ServerListScan]，但"学到的顶部那一屏 / 已滑几下"各自独立
     * （两个列表的内容与位置毫无关系，共用会把一方的状态当成另一方的）。
     *
     * ⚠ **好友这条现在是"兜底"，不是主路径**（2026-10-01 两次口径变更后）：
     * 第 9 步的主路径是「**当前屏先找一次 → 没有就搜索**」（[friendSearch] + `friendPlanOf` ⑤），
     * 滑屏只在**搜索链走不了**时才会用到 —— 即 [FriendSearch.start] 给出 `UNAVAILABLE`
     * （三个搜索锚点没标定）或 `onTextWritten(false)`（写文字失败）。
     * ⚠ 而"**搜索没结果**"按用户口径是**如实停下**、**不**回退滑屏（见 [friendPlanOf] 里那段说明）。
     * 📌 同日的旧口径"命中前必须先回顶"（`atTopConfirmed` 拦命中）**已被用户推翻**（口径修订十一：
     * "每次都滑到顶再找有点费事")—— 它还会毁掉用户称赞的"**回顶路上看到就点**"手感，别再加回来。
     * 仅帧线程访问。
     */
    private val friendScan = ServerListScan(
        targetNoun = "好友",
        targetHint = "请确认好友的**备注名**（括号里那一段）与游戏里一致",
        // ⚠ **好友这一步要等更久**（2026-10-01 实测对齐）：点击门禁的间隔是从**手势结束**算的
        //（`ClickGate.spacingSatisfied` 用 `lastFinishedMs`），而好友的拖动时长是
        // [FRIEND_SCROLL_DRAG_MS] = 600ms ⇒ "手势结束 + 间隔 300ms" ≈ 900ms 之后才允许下一次手势。
        // 若仍用默认 800ms，**每一枪都会被门禁拒一次**（真机实录：`滑动拒绝（与上一次点击间隔不足）`
        // —— 那一下被拒本身没危险，但它会白等一轮，还曾因回告写漏把扫描卡死）。取 1200ms 留足余量。
        settleMs = FRIEND_SETTLE_MS,
    )

    /** 上一次打过的"第 9 步扫描"那一行的依据（同一句只记一次，免得刷屏）。仅帧线程访问。 */
    private var lastFriendScanNote: String? = null

    /**
     * 第 9 步**搜索式查找**的状态机（2026-10-01 用户拍板"当前屏没有就搜索"；顺序与口径见
     * [PatrolAnchors.FRIEND_SEARCH_ENTRY]）。走不了（三个锚点没标定）时退回滑屏找，见 [friendPlanOf]。
     */
    private val friendSearch = FriendSearch()

    /** 点下「搜索」的时刻（判"结果页给了几秒"；只在搜索链的 SENT 阶段有意义）。仅帧线程访问。 */
    private var friendSearchSentAtMs = 0L

    /**
     * 搜索发出一枪之后，**最多等结果页几秒**（ms，取 6000）——过了就按用户 2026-10-01 的口径
     * **如实停下**（"搜索都找不到就不再滑屏"）。取值依据：点「搜索」到结果页刷新通常 <1 秒，
     * 6 秒是给"网络/帧慢"留的余量，且仍在第 9 步的单步预算之内。
     */
    private val searchResultWaitMs = 6_000L

    /**
     * 搜索链**允许写几次**（2026-10-01，取 2）：`ACTION_SET_TEXT` + 系统返回这套不稳定（见
     * `FriendSearch.writeAttempts`），验不过就重写一次；两次都进不去框就按用户口径**如实停下**。
     */
    private val searchWriteMaxAttempts = 2

    /**
     * 搜索框写完字之后要点的**原生控件文字**（2026-10-01）：用户提示"输入框右侧有个确定按钮"，
     * 节点树实证它就在同一次会话里被我们抓到过（见 `TextInjector.dumpTreeVia`）。
     */
    private val searchConfirmLabel = "确定"

    /**
     * **写文字**（原生 `ACTION_SET_TEXT`，不弹键盘；见 `action/TextInjector` 的说明）。
     * 以函数注入的形式持有，`patrol` 层（[FriendSearch]）不直接依赖 `action` 层。
     */
    private val textWriter = TextInjectorLike { text -> TextInjector.write(text) }


    /**
     * 最近一帧的像素副本与几何（**画面静止时的兜底重放**用，见 [IDLE_ROUND_INTERVAL_MS]）。
     *
     * 为什么留副本而不是留 `Image` 引用：`Image` 在 [handleFrameAvailable] 末尾就 `close()` 了，
     * 存引用等于存了个失效对象；而静止时不会有新帧，兜底轮只能靠这份副本。
     */
    private var lastFrameBytes: ByteArray? = null
    private var lastFrameRowStride = 0

    /** 帧线程的 Handler（会话建立时赋值；静止兜底调度用）。 */
    private var frameHandler: Handler? = null

    /** 静止兜底的心跳计数（诊断用，每 [IDLE_HEARTBEAT_TICKS] 次留一行日志）。仅帧线程访问。 */
    private var idleTicks = 0L

    /**
     * 静止兜底链条是否已在本次会话里起过（**只为把"已启动"日志限一次**，2026-09-22）。
     *
     * 为什么需要它：`scheduleIdleRound()` 是**自递归**的（每次重排都再调一次自己），
     * 早先那行日志写在函数开头 ⇒ **每秒刷一行**"静止兜底调度已启动"，把真机日志淹了。
     * 会话结束时（[releaseSession]）复位，下次会话仍能看到一次启动确认。
     */
    private var idleScheduled = false

    /** 上一次缓存"最新画面"的时刻（降频用，见 [PIXEL_CACHE_INTERVAL_MS]）。仅帧线程访问。 */
    private var lastPixelCacheAtMs = 0L

    /**
     * **本轮识别用的那张画面**是什么时候拍到的（T4-9 事故后加，2026-09-24）。仅帧线程访问。
     *
     * 为什么需要它（用户 2026-09-24 追问后补的）：**"帧一直在来"不等于"本轮用的就是刚来的那一帧"**。
     * 两条路径都会喂给 [processPixels]：新帧到达（就是这一帧）与**静止兜底重放**（缓存副本，
     * 最多 [PIXEL_CACHE_INTERVAL_MS] 前抓的）。所以"这一轮读到的画面"可能比"现在"旧几百毫秒 ——
     * 判定落在**点击之前**看到的那张画面上，就会做出"已经过时"的决定（FR-01 补点那次即如此，
     * `docs/progress.md` 第 189 / 190 条）。要判"这一轮算不算新画面"，只能用**这张帧的拍摄时刻**，
     * 不能用"最近一帧的到达时刻"（帧流不停时它永远"很新"）。
     */
    private var currentFrameAtMs = 0L

    /** 本轮那张画面是不是"兜底重放"（true）/ "新帧"（false）—— 只用于日志与判据说明。仅帧线程访问。 */
    private var currentFrameReplayed = false

    /** 兜底缓存里那张画面的**拍摄时刻**（[cacheLatestPixels] / [processFrame] 存的时候更新）。仅帧线程访问。 */
    private var lastFrameBytesAtMs = 0L

    /** 全平帧被拒绝进兜底缓存的次数 / 本会话是否已告警（2026-09-24 真机 bug）。仅帧线程访问。 */
    private var flatCacheSkips = 0L
    private var flatCacheWarned = false

    /** 兜底缓存里那张帧被判定为全平、拒绝重放时是否已告警（同上）。仅帧线程访问。 */
    private var flatReplayWarned = false

    /** FR-01 判定序号（NFR-05 审计链）：与 `MM-Click` 日志的「判定: fr01-N」对齐。仅帧线程访问。 */
    private var actionRoundSeq = 0L

    /**
     * 上一次**遮挡屏闭环**不动作的原因（同一原因只记一次日志：遮挡屏长期在屏时"未标定锚点"会每轮成立）。
     * 三屏共用（活动弹窗 / 新手引导 / 新手大厅）。仅帧线程访问。
     */
    private var lastOverlaySkipNote: String? = null

    /**
     * 上一次"锚点命中即停"的取证行（2026-09-30）：命中一次记一行，之后就安静下来
     * （见 `stepPopupClose` 里那段 —— 每轮都记等于刷屏，而这一行的用处是"事后能看出提速生效了"）。
     * 仅帧线程访问。
     */
    private var lastAnchorNote: String? = null

    /**
     * 本段**"已停手"的原因**（2026-09-30 加）：`PopupStep.GiveUp` 每次都会带上它（三条停手路径各说各的），
     * 这里存一份最新值，供 [PopupCloseSignal.update] 每轮如实写给界面 —— 标签改说「关弹窗已停手」、
     * 原因进悬浮窗菜单，不再让"程序已经不再尝试了"这件事对用户无声（见 `PopupCloseSignal.gaveUpReason`
     * 里记的真机报障）。只在"有停手"时被读，所以不需要单独清（`gaveUp` 变假时调用方写空串）。仅帧线程访问。
     */
    private var lastGaveUpNote: String = ""

    /** 标定产物记录的帧尺寸（0 = 未标定）；与运行帧不同时按画面区归一（T1-11c）。仅帧线程访问。 */
    private var calibratedFrameWidth = 0
    private var calibratedFrameHeight = 0

    /**
     * 上一次推给点击通道的坐标换算（2026-09-20）：**值没变就不重复推**（每帧都算一次几何，
     * 但只有变化时才写日志、才替换）。仅帧线程访问。
     */
    private var lastScreenSize: ScreenSize? = null

    /**
     * 上一轮跑号的「相位签名」（步骤 | 结论 | 暂停）；变化 = **我们自己刚推动了画面** →
     * 节流立刻回快档（2026-09-20 手感修）。仅帧线程访问。
     */
    private var lastPatrolPhase: String? = null

    /** 本会话是否已记录过「运行画布与标定帧方向不同」（只记录一次）。仅帧线程访问。 */
    private var frameSizeWarned = false

    /** 会话终止来源；null 表示会话仍存活。仅主线程访问。 */
    private var stopSource: String? = null

    // 帧管线统计（仅帧线程访问）
    private var framesReceived = 0L
    private var framesProcessed = 0L
    private var cyclesRun = 0L
    private var lastStatsAt = 0L
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0

    // 搜索集合取证（T2-1，仅帧线程访问）：统计窗口内「实际参与匹配的信号数」分布
    private var searchedRounds = 0L
    private var multiSignalRounds = 0L
    private var idleRounds = 0L
    private var searchedSymbolTotal = 0L

    /**
     * 上轮实际搜索的信号名集合（null = 本会话尚未处理过）。集合变化即记一行日志——
     * 供真机核对期望集合注入：守护待命只搜「启动页」，命中后扩为弹窗期集合（T2-1）。仅帧线程访问。
     */
    private var lastSearchedNames: Set<String>? = null

    /** 单帧处理耗时统计（T1-9，NFR-01）：连续 100 帧一窗；总段 / 灰度段 / 识别段同窗同步记录。仅帧线程访问。 */
    private val timingStats = FrameTimingStats()
    private val grayTimingStats = FrameTimingStats()
    private val detectTimingStats = FrameTimingStats()

    /**
     * 逐信号匹配耗时统计（T1-13b）：**每个信号各自**连续 100 次为一窗，满窗输出一行（P95 / 平均 / 最大），
     * 用于真机核对「每步（每条信号）到底花多久」。仅帧线程访问。
     */
    private val signalCostStats = mutableMapOf<String, FrameTimingStats>()

    /** 节流目标档位上次生效值（T1-9 切换日志用）。仅帧线程访问。 */
    private var lastAppliedIntervalMs = ACTIVE_INTERVAL_MS

    /** 上轮是否冻结（null = 尚未处理过），用于冻结 / 恢复只记一次日志。仅帧线程访问。 */
    private var lastLoopFrozen: Boolean? = null

    /**
     * 上一轮记录的「帧几何 × 屏幕几何」签名（null = 本会话还没记过）：**只在变化时记一行**。
     *
     * 帧几何是**建会话那一刻**的屏幕尺寸（见 [attachMirror]），之后转屏不会跟随 ⇒ 两者的关系
     * （同向 / 互为转置 / 都不是）决定本轮走零换算快路径还是方向归一。2026-09-23 之前**没有任何一行
     * 日志写着它** —— 帧池里混着横竖两种帧时，只能靠读 PNG 头反推（真机排障实录）。
     */
    private var frameGeometrySignature: String? = null

    /** 连续「近乎全平」的新帧数（目标不在前台时清零，见 [noteFrameContent]）。 */
    private var flatFrameStreak = 0

    /** 本次连续全平是否已告警（一次异常只报一行，不刷屏）。 */
    private var flatFrameWarned = false

    /**
     * 本会话是否已经为「面板更新后没有新帧」记过诊断（见 [checkFrameExpectation]）。
     * 只记一行：静态页面 + 「共享一个应用」采集时，每次开面板都会命中这条，刷屏没有意义。
     */
    private var frameExpectationNoted = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 系统侧终止（用户在系统 UI 停止 / 会话被回收）：本回调是唯一信号（ADR-001 第 3 条）
            if (stopSource == null) stopSource = SOURCE_SYSTEM_STOP
            stopSelf()
        }

        /**
         * **转屏 ⇒ 调整镜像尺寸**（2026-09-23：先挂观测、确认平台会回调之后才切到这条路）。
         *
         * 平台在转屏 / 折叠 / 多窗口变化时会回调它；官方口径是"据此及时调整虚拟屏幕与 Surface 的尺寸，
         * **避免出现黑边**"—— 也就是 `VirtualDisplay.resize()` + 换 `ImageReader` surface，
         * **不是**重建 projection（第二次 `createVirtualDisplay` 会崩进程，见 [attachMirror]）。
         *
         * **真机实测（2026-09-23 23:16，45 秒内回调 17 次）**给了两条硬约束：
         * 1. **它会来，而且跟得上内容**：内容尺寸先变、`displayMetrics` 后变（转屏途中就报 3168x1440，
         *    而当时屏幕还是 1440x3168）⇒ 一律以**它报的尺寸**为准，不要拿屏幕尺寸反推；
         * 2. **同一个尺寸会连报 2~3 次** ⇒ 必须"只在真的变了时才动手"，否则会把 ImageReader 反复重建。
         *
         * 实际动作发给**帧线程**（[applyMirrorResize]）：回调在主线程、读帧在帧线程，
         * 换 reader 必须与帧处理互斥，否则会出现"读着一个刚被关掉的 reader"的竞态。
         *
         * 不调 `super`：本方法是 API 34 才有的，低版本根本不会被调用；而一旦在低版本调到 super 会
         * `NoSuchMethodError`（基类实现本来就是空的，没有语义需要继承）。
         */
        override fun onCapturedContentResize(width: Int, height: Int) {
            val metrics = resources.displayMetrics
            MmLog.i(
                TAG,
                "捕获内容尺寸变化: 平台报 ${width}x$height" +
                    "｜当前缓冲 ${imageReader?.width}x${imageReader?.height}" +
                    "｜当前屏幕 ${metrics.widthPixels}x${metrics.heightPixels}",
            )
            frameHandler?.post { applyMirrorResize(width, height) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            // FR-08：不静默失败——凭证缺失时明确记录并退出，不留下无会话的空服务
            MmLog.w(TAG, "采集会话启动失败：缺少授权凭证，服务退出（需在前台界面重新授权）")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startSession(resultCode, resultData)
        // 凭证一次性：进程被杀后重启无凭证可用，不自动复活（不同于守护服务）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (stopSource == null) stopSource = SOURCE_APP_STOP
        releaseSession()
        // 采集会话没了 = 没人再做识别与点击 → 进行中的跑号一并结束（否则会留一个"看起来还在跑"的空壳，
        // 之后重新建会话时它会从旧步骤继续，跳过了用户以为已经重来的那几步）
        PatrolSession.stop()
        CaptureSessionSignal.update(CaptureSessionStatus.INACTIVE, stopSource ?: SOURCE_APP_STOP)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(resultCode: Int, resultData: Intent) {
        releaseSession()
        stopSource = null

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Exception) {
            MmLog.w(TAG, "创建采集会话失败：${t.javaClass.simpleName} ${t.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            MmLog.w(TAG, "创建采集会话失败：系统未返回会话实例")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        // 帧线程提升为前台优先级（T1-10b）：降低后台调度把重处理挤到小核的概率
        val thread = HandlerThread("MM-CaptureFrames", Process.THREAD_PRIORITY_FOREGROUND)
        thread.start()
        frameThread = thread
        // 显式两行：`.also { frameHandler = it }` 里的 `frameHandler` 会解析成**正在初始化的局部变量**，
        // 字段拿不到值 ⇒ 静止兜底调度第一次就 `return`（链条整条起不来）。2026-09-22 踩过。
        val handler = Handler(thread.looper)
        frameHandler = handler
        // 画面静止时不会有新帧 ⇒ 单靠上面的回调会让识别整段停摆（真机根因，见 IDLE_ROUND_INTERVAL_MS）
        scheduleIdleRound()

        // 镜像（ImageReader + VirtualDisplay）：**抽成可重复调用的 [attachMirror]** —— 转屏时要重建它
        attachMirror()
        // 屏幕几何变化（转屏 / 折叠 / `wm size`）→ 重建镜像（2026-09-23）：
        // 会话几何是"建立那一刻定死"的，而真机出现过"转屏时镜像吐一张空帧、之后帧流停滞"的偶发故障 ——
        // 帧管线会一直重放那张空帧（模板分数全 `-`、OCR 读不到）**且不会自愈**，只能重建会话。
        registerDisplayRefresh()

        // 会话（重）建立：重建节流与识别循环（滞回状态不跨会话连续）；标定产物重新加载（保存后重开会话即生效）
        frameThrottle = FrameThrottle(ACTIVE_INTERVAL_MS)
        val calibration = try {
            // 运行时入口：**含窗口收紧**（界面侧读的是原始值，见 CalibrationStore.loadForRuntime 的说明）；
            // 例外：「只圈区域」的锚点（列表区域 / 好友名称列）**不收紧**——用户框多大就用多大
            CalibrationStore.loadForRuntime(this, PatrolAnchors.nonLocatableAnchors)
        } catch (t: IllegalArgumentException) {
            MmLog.w(TAG, "标定产物加载失败，回退未标定运行：${t.message}")
            null
        }
        // 跑号期望集合（T4-4b）：包一层"弹窗 ∪ 当前步骤要认的画面"。
        // **没跑号时它等价守护集合**（watch 为空）→ 整条链路不必在两种模式间切换实现。
        val markerRules = calibration?.stateRules.orEmpty()
            .filter { it.signalNames.isNotEmpty() }
            .map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        patrolExpected = calibration?.let {
            PatrolDrivenExpectedSignals(
                popup = PopupWatchExpectedSignals.fromRules(markerRules),
                rules = markerRules,
                // 待命期也要认得出"用户现在在哪一屏"，否则菜单的起点判定永远读到「未知」（2026-09-20 真机死锁）
                standbyStates = PatrolScenes.startStates,
            )
        }
        recognitionLoop = calibration?.toLoop(patrolExpected) ?: RecognitionLoop.uncalibrated()
        // 留一份产物：按用途查锚点、按状态取规则都要它（`anchorIdFor` / `purposeOf` / `friendOf`）
        calibrationData = calibration
        // 锚点定位器与 FR-01 决策随会话重建（T2-4）：重试计数不跨会话（与滞回状态不跨会话同一口径）
        // 排除「只圈区域」的锚点：它们只是 OCR 的输入范围，跟着定位会让帧线程单轮停摆 20 秒以上
        // （2026-09-22 真机：好友列表「列表区域」431x511 → 单轮 23.3 秒，根因见 AnchorLocator 构造参数）
        anchorLocator = calibration?.toAnchorLocator(PatrolAnchors.nonLocatableAnchors)
        popupClose = PopupCloseController()
        PopupCloseSignal.clear()
        actionRoundSeq = 0
        patrolSeq = 0
        lastPatrolNote = null
        lastAbortAnnounced = null
        lastOverlaySkipNote = null
        calibratedFrameWidth = calibration?.frameWidth ?: 0
        calibratedFrameHeight = calibration?.frameHeight ?: 0
        frameSizeWarned = false
        if (calibration != null) {
            // 锚点数量单独报（T2-4）：FR-01 到底能不能点，第一眼就看这条——0 个锚点时弹窗即使在屏，
            // 闭环也只会"跳过"（宁可不点，红线 3/7）
            val anchorCount = calibration.signals.count { it.role == SignalRole.ANCHOR }
            MmLog.i(
                TAG,
                "标定产物已加载：信号 ${calibration.signals.size} 个（其中锚点 $anchorCount 个），" +
                    "标定帧 ${calibration.frameWidth}x${calibration.frameHeight}；" +
                    "搜索窗口 = 模板 + 每边固定余量（标志 ${SelectionWindow.MARKER_MARGIN_PX} / " +
                    "锚点 ${SelectionWindow.ANCHOR_MARGIN_PX} px；加载期已对「可证居中」的轴重算，见 tightenedWindows）",
            )
            // 逐条列全（2026-09-19 用户口径：日志里的产物要能直接读懂）——之后任何一行
            // `MM-Click 锚点: hall_farm` 都能在这张清单里查到它是哪个界面、什么角色、什么用途
            calibration.signals.forEach { signal ->
                MmLog.i(TAG, "  · ${calibration.describe(signal)}")
            }
        } else {
            MmLog.i(TAG, "未加载标定产物（未标定），以空配置运行")
        }
        framesReceived = 0
        framesProcessed = 0
        cyclesRun = 0
        searchedRounds = 0
        multiSignalRounds = 0
        idleRounds = 0
        searchedSymbolTotal = 0
        lastLoopFrozen = null
        lastSearchedNames = null
        timingStats.reset()
        grayTimingStats.reset()
        detectTimingStats.reset()
        lastAppliedIntervalMs = ACTIVE_INTERVAL_MS
        lastStatsAt = SystemClock.elapsedRealtime()
        // 运行方式（演练 / 实点）与相关边界已整体移除（2026-09-22 用户口径：默认就是真实点击）：
        // 会话（重）建立不再需要"保留 / 重置模式"这类处理，点击能否发出只由 ClickGate 的硬性约束决定。
        CaptureSessionSignal.update(CaptureSessionStatus.ACTIVE, SOURCE_USER_CREATED)
    }

    /**
     * 屏幕尺寸同步（T4-6，2026-09-23；原 `syncClickGeometry`）：把**当前显示区域尺寸**推给 [ClickDispatch]。
     *
     * 换算为什么没了：转屏由 `VirtualDisplay.resize()` 跟进 ⇒ **帧恒与屏幕同向同尺寸**
     * （`docs/progress.md` 第 163 / 167 条）⇒ 帧坐标即屏幕坐标，原先那套"按画面区换算"整层删除。
     * 剩下的**越界判定**仍要屏幕尺寸：几何错配时落点可能整体落在屏外，那时**宁可不下发**
     * （NFR-05）。尺寸每帧读一次（≤5 次/秒）比"点歪了再排查"便宜得多；[lastScreenSize] 保证只在变化时推送与记日志。
     */
    private fun syncScreenSize() {
        val screen = FloatingScreen.spec(this)
        // **互为转置时按帧的几何判越界**（2026-09-30 真机修，见 [refreshScreenSizeForClick]）：
        // 判据、理由与真机实录全在 [ScreenSize.forFrame] 的注释里（一处维护）。
        val raw = ScreenSize(screen.width, screen.height)
        val next = ScreenSize.forFrame(raw, lastFrameWidth, lastFrameHeight)
        if (next == lastScreenSize) return
        lastScreenSize = next
        ClickDispatch.setScreenSize(next)
        MmLog.i(
            TAG,
            "屏幕尺寸已更新: ${next.describe()}" +
                if (next != raw) "（屏幕与帧互为转置 ⇒ 按帧几何判越界）" else "",
        )
    }

    /**
     * **下发点击 / 滑动之前刷一次屏幕尺寸**（2026-09-30 真机修，用户报"卡在了打开好友列表"）。
     *
     * 为什么不能只在"收到新帧"那一处刷（`onNewFrame` 里的 [syncScreenSize]）：
     * **「画面静止 ⇒ 平台不产帧」是常态**（静止兜底重放就是为此存在的）⇒ 尺寸可能长期停留在
     * **上一次那个方向**。真机实录：
     * ```
     * 20:10:28.796  屏幕尺寸已更新: 屏幕 1440x3168   ← 我们自己的 App 在前台（锁竖屏）时推入
     * （此后画面静止、没有新帧 ⇒ 尺寸再也没刷新过）
     * 20:10:57.963  点击拒绝（点击点落在屏幕外）｜farm_friends｜帧点 (2912, 429)  ← 2912 > 1440 ⇒ 误判
     * 20:11:39.735  屏幕尺寸已更新: 屏幕 3168x1440   ← 用户重新授权、游戏在前台，才恢复横屏
     * 20:11:46.767  点击下发 ✓ (2912, 429)          ← **同一个坐标**放行
     * ```
     * ⇒ 后果不只是"少点一下"：跑号把"被拒"当成"刚点过"，白等一整个步骤预算（16 秒）后中止，
     * 而用户看到的是"卡在了打开好友列表"。
     *
     * 成本：一次 `FloatingScreen.spec` 读 + 一次比较（值没变就什么都不做），相对一次真实点击可忽略。
     */
    private fun refreshScreenSizeForClick() {
        syncScreenSize()
    }

    private fun releaseSession() {
        detachDisplayRefresh()
        // 帧流看门狗的状态不跨会话
        mirrorRotationAtMs = 0L
        lastNewFrameAtMs = 0L
        FrameFreshness.reset()
        // 帧回调探针 / "停更自救"的计数不跨会话（否则新会话会被旧会话的"已试 2 次"直接锁死）
        frameCallbacks = 0L
        frameAcquiresEmpty = 0L
        lastStatsAt = 0L
        surfaceRetries = 0
        nextSurfaceRetryAtMs = 0L
        surfaceRetryGaveUp = false
        // 「首次投喂自检」的两项也随会话走（[attachMirror] 会重新起表）
        sessionStartedAtMs = 0L
        firstFeedWarned = false
        // "刚跑完的结果"同样不跨会话：否则新会话刚开始，标签/菜单就挂着上一段的「已完成」
        PatrolResultSignal.clear()
        frameExpectationNoted = false
        // "自上一枪以来画面变化"的基准不跨会话
        lastClickSignature = null
        FrameExpectationSignal.reached()
        // "本轮吃的是哪张画面"同样不跨会话（见 [currentFrameAtMs]）
        currentFrameAtMs = 0L
        currentFrameReplayed = false
        lastFrameBytesAtMs = 0L
        // 几何关系与内容自检的状态同样不跨会话（新会话的基准可能完全不同）
        frameGeometrySignature = null
        flatFrameStreak = 0
        flatFrameWarned = false
        geometryMismatchSinceMs = 0L
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        // 先注销回调再 stop：避免主动释放路径误报为「系统侧回收」
        mediaProjection?.unregisterCallback(projectionCallback)
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
            // 会话已被系统侧终止时 stop 可能抛异常，终止流程不受影响
        }
        mediaProjection = null
        frameThread?.quitSafely()
        frameThread = null
        // 兜底链条随会话结束，重新开会话时那条"已启动"日志要能再看到一次（见 [idleScheduled]）
        idleScheduled = false
        idleTicks = 0L
        // "坏帧不进缓存 / 不重放"的状态同样不跨会话（见 [cacheLatestPixels]、[replayLastFrame]）
        flatCacheSkips = 0L
        flatCacheWarned = false
        flatReplayWarned = false
        // 识别循环随会话终止停止更新：界面状态信号回到「未知」（不得残留旧状态误导展示）
        UiStateSignal.reset("采集会话终止")
        // FR-01 状态不跨会话（T2-4）：锚点定位器与重试计数一并作废
        anchorLocator = null
        popupClose = PopupCloseController()
        PopupCloseSignal.clear()
        lastOverlaySkipNote = null
        // 屏幕尺寸不跨会话（2026-09-20 口径沿用）：下一次会话按当时的屏幕尺寸重新推
        lastScreenSize = null
        ClickDispatch.setScreenSize(ScreenSize.UNKNOWN)
        // 跑号相位同样不跨会话（2026-09-20）
        lastPatrolPhase = null
    }

    /**
     * 记录「帧几何 × 屏幕几何」的关系（**只在变化时记一行**，2026-09-23）。
     *
     * 新方案（resize 跟进转屏）下**只有"同向"是正常的**，另外两种都意味着 resize 没跟上 ——
     * 它们的存在价值只剩"让日志一眼看出异常"，处置交给 [checkGeometryGuard]（持续不一致 3 秒 ⇒ 结束会话）。
     *
     * ## ⚠ 2026-09-29 修正：**结论必须看"目标在不在前台"**（否则日志会把人带偏）
     *
     * 真机实录（03:03:41 / 03:03:51）：用户回到本 App（锁竖屏 1440×3168），而镜像按**口径 A 故意保持横屏**
     * （3168×1440，见 [attachMirror]）⇒ 必然"互为转置"。这**不是故障**：同一份日志里
     * `帧几何持续…不一致 ⇒ 主动结束会话` **0 次**、`ACTIVE -> INACTIVE` **0 行** —— 因为
     * [checkGeometryGuard] 的"帧 vs 屏幕"那条判据**带"目标在前台"这个前提**（2026-09-24 修）。
     * 可这一行当时仍印着"异常：resize 没跟上；持续 3 秒会被几何守卫结束会话" ⇒
     * **排障时被它误导过一次**（把"用户重新授权导致的重启"误读成"几何守卫结束了会话"）。
     * ⇒ 现在按前台分岔，并把"目标在不在前台"直接写进这一行；签名也带上它（前台状态变了要重记一行）。
     */
    private fun noteFrameGeometry(frameWidth: Int, frameHeight: Int) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val foreground = ForegroundSignal.isForeground
        val where = if (foreground) "目标在前台" else "目标不在前台"
        // 前台状态也要进签名：它决定了同一组几何的"结论"怎么写（见上面那段）
        val signature = "$frameWidth x $frameHeight vs $screenWidth x $screenHeight @$where"
        if (signature == frameGeometrySignature) return
        frameGeometrySignature = signature
        val relation = when {
            frameWidth == screenWidth && frameHeight == screenHeight ->
                "同向 ✓（新方案的常态：零归一、零换算）"
            frameWidth == screenHeight && frameHeight == screenWidth ->
                if (foreground) {
                    "互为转置 ⚠（异常：resize 没跟上；持续 3 秒会被几何守卫结束会话）"
                } else {
                    "互为转置（**设计预期**：屏幕方向是本 App 的竖屏、镜像按口径 A 保持横屏 ⇒ " +
                        "几何守卫对这一条不判故障，见 [checkGeometryGuard]）"
                }
            else ->
                "既不同向也不转置 ⚠（内容多半被平台居中缩放塞进缓冲）" +
                    if (foreground) "；几何守卫会在宽限后结束会话" else "；目标不在前台时守卫不判（回前台仍不对才会被拦）"
        }
        MmLog.i(
            TAG,
            "帧几何关系（$where）: 帧 ${frameWidth}x$frameHeight ｜ 屏幕 ${screenWidth}x$screenHeight ⇒ $relation",
        )
    }

    /** 连续全平的起点时刻（0 = 当前不是全平）。口径 B 用它算"黑了多久"。仅帧线程访问。 */
    private var flatFrameSinceMs = 0L

    /**
     * 单帧内容自检 → 连续全平就告警（2026-09-23）；**2026-09-24 起升级为"持续就结束会话"**（口径 B，用户拍板）。
     *
     * 判据：目标在前台、且**持续 [FLAT_FRAME_END_SESSION_MS] 一直是全平** ⇒ **主动结束会话**并提示重建。
     * 依据：真机 10:36 实录 —— `resize()` 之后帧在来、但**帧里全是黑的**（连续 5 次告警：均值 0.2 / 标准差 3.9），
     * 识别恒「未知」、界面只反复说"认不出当前画面"，而告警原来**只记日志** ⇒ 用户在一个永远不会好的状态里空转。
     *
     * 为什么必须"持续一段时间"才动手：游戏里确实存在整屏偏黑的加载画面（那是**正常现象**，不该当故障处置；
     * 转屏看门狗 2026-09-23 误停过一次，教训就在这儿）—— 但加载页不会黑十几秒。
     */
    private fun noteFrameContent(sample: FrameUniformity.Sample) {
        val now = SystemClock.elapsedRealtime()
        // 目标不在前台时"全平"完全正常（停在 App / 桌面上）⇒ 不参与判定
        if (!ForegroundSignal.isForeground || !sample.flat) {
            flatFrameStreak = 0
            flatFrameWarned = false
            flatFrameSinceMs = 0L
            return
        }
        if (flatFrameStreak == 0) flatFrameSinceMs = now
        flatFrameStreak++
        if (flatFrameStreak < FLAT_FRAME_ALERT_STREAK) return
        if (!flatFrameWarned) {
            flatFrameWarned = true
            val detail = String.format(Locale.ROOT, "均值 %.1f，标准差 %.1f", sample.mean, sample.stdDev)
            MmLog.w(
                TAG,
                "画面连续 $flatFrameStreak 帧近乎全平（采样 ${sample.count} 点，$detail）—— " +
                    "目标在前台却读不到画面内容：镜像很可能在给黑帧（转屏后未重建）",
            )
        }
        val flatForMs = now - flatFrameSinceMs
        if (flatForMs >= FLAT_FRAME_END_SESSION_MS) {
            MmLog.w(
                TAG,
                "目标在前台已连续 ${flatForMs / 1000} 秒读不到画面内容（全是黑帧）" +
                    "⇒ 镜像已失效，主动结束会话，请在授权页重新建立采集",
            )
            stopSource = SOURCE_MIRROR_STALL
            stopSelf()
        }
    }

    /** 推迟 resize 的截止时刻（0 = 还没推迟过；成功应用后清零）。仅帧线程访问，常量见 [RESIZE_DEFER_LIMIT_MS]。 */
    private var resizeDeferDeadlineMs = 0L

    /**
     * 把镜像调整到新尺寸（**帧线程**调用，2026-09-23 起；转屏走这里，不重建 projection）。
     *
     * 顺序与理由：
     * 1. **先新建**尺寸正确的 `ImageReader`（旧的先留着：万一 resize 失败，继续用旧几何跑也比手里没 reader 强）；
     * 2. **先挂监听再换 surface**，避免中间到的那一帧没人接；
     * 3. `resize()` + `setSurface()`：逻辑尺寸与生产端一起换 —— 只换 surface 的话，平台仍会按旧尺寸
     *    把画面"居中缩放"塞进来（那正是归一这条路径的由来）；
     * 4. **最后**才关旧 reader（此时它已经不再是生产端）。
     *
     * 任何一步失败都**保持原样**并把原因写进日志：会话继续按旧几何跑，转屏看门狗还在，
     * 最坏情况退回"回授权页重建会话"。
     *
     * 成功之后顺手做两件事：**清掉待确认的转屏标记**（这次方向变化已经由我们处理掉了，
     * 不该再被判成"镜像失效"去结束会话）、**丢弃旧帧缓存**（旧几何的帧重放没有意义）。
     */
    private fun applyMirrorResize(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        val handler = frameHandler ?: return
        if (width <= 0 || height <= 0) return
        // **不接受"竖屏内容"的回调**（2026-09-24 口径 A）：镜像现在是**按横屏建**的（见 [attachMirror]），
        // 而本 App 锁竖屏 ⇒ 授权后 / 切回本 App 时平台会报竖屏内容尺寸（1440×3168）。照它 resize 就又踩上
        // 那条缺陷（无帧 / 黑帧）。竖向内容由平台按"等比缩放 + 居中"塞进横屏缓冲即可 —— 我们只服务横屏的
        // 游戏画面；游戏回来时报 3168×1440 与当前缓冲相同，会走到下面的"去重"直接返回。
        if (height > width) {
            MmLog.i(
                TAG,
                "镜像 resize 跳过：平台报的是竖屏内容 ${width}x$height（本 App 锁竖屏）⇒ 镜像保持横屏",
            )
            return
        }
        // ⚠ **先等帧流真正建立，再 resize**（2026-09-24 真机三次复现后的根因缓解）。
        //
        // 现象：会话建立后 ~1 秒内就收到"内容尺寸变化 ⇒ resize"，而 `resize()` + `setSurface()` 成功之后
        // **平台再也不产帧**（10:16 / 10:31 / 10:32 三次实录：resize 后 10 秒零新帧，由转屏看门狗如实
        // 结束会话）。而 2026-09-23 那次成功时，游戏已经在前台跑了一阵（帧流早就稳了）。
        // ⇒ 判据：**本次会话至少先收到 [RESIZE_MIN_FRAMES] 帧**再动手；没起来就推迟重试，最多等到
        // [RESIZE_DEFER_LIMIT_MS]（到点照常应用，之后由帧流看门狗兜底）。推迟期间本来也没有可用画面，
        // 不损失任何识别能力。
        val nowMs = SystemClock.elapsedRealtime()
        if (framesReceived < RESIZE_MIN_FRAMES) {
            if (resizeDeferDeadlineMs == 0L) {
                resizeDeferDeadlineMs = nowMs + RESIZE_DEFER_LIMIT_MS
                MmLog.i(
                    TAG,
                    "镜像 resize 推迟：本次会话才收到 $framesReceived 帧（等帧流建立后再 resize，" +
                        "最多等 ${RESIZE_DEFER_LIMIT_MS}ms）",
                )
            }
            if (nowMs < resizeDeferDeadlineMs) {
                handler.postDelayed({ applyMirrorResize(width, height) }, RESIZE_DEFER_STEP_MS)
                return
            }
            MmLog.w(TAG, "镜像 resize 推迟到期（本次会话只收到 $framesReceived 帧）⇒ 照常应用")
        }

        resizeDeferDeadlineMs = 0L
        // 去重：平台同一个尺寸会连报 2~3 次（真机实测），只在真的变了时才动
        val old = imageReader
        if (old != null && old.width == width && old.height == height) return
        val densityDpi = resources.displayMetrics.densityDpi
        val next = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        } catch (t: RuntimeException) {
            MmLog.w(TAG, "调整镜像尺寸被跳过：新建 ImageReader 失败（${t.javaClass.simpleName} ${t.message}）")
            return
        }
        next.setOnImageAvailableListener({ handleFrameAvailable() }, handler)
        try {
            display.resize(width, height, densityDpi)
            display.setSurface(next.surface)
        } catch (t: RuntimeException) {
            next.setOnImageAvailableListener(null, null)
            next.close()
            MmLog.w(TAG, "调整镜像尺寸失败（保持旧几何继续跑）：${t.javaClass.simpleName} ${t.message}")
            return
        }
        imageReader = next
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        // **改成"重新起表"，而不是清零**（2026-09-24 真机 bug）：
        // resize 只保证**几何**对齐，**不保证帧流会恢复**。真机实录（10:16：会话建立 0.3 秒后就 resize）：
        // resize 成功后**整整 102 秒一个新帧都没来**，唯一那帧是空缓冲（被"坏帧不进缓存"拦下）
        // ⇒ 识别恒「未知」，大厅/农场的起点判定一直"认不出当前页面"，而原来的写法把看门狗**解除了武装**
        // ⇒ 没有任何人去发现这件事。现在：resize 成功后**重新起表**，若 [MIRROR_STALL_TIMEOUT_MS] 内
        // （且目标在前台）仍无新帧 ⇒ 由 [checkMirrorStall] 如实结束会话（"请重新建立采集"），不静默失效。
        mirrorRotationAtMs = SystemClock.elapsedRealtime()
        // 旧帧是旧几何的，重放它没有意义（也免得"帧几何关系"沿用旧签名不再记录）
        frameGeometrySignature = null
        lastFrameBytes = null
        lastFrameWidth = 0
        lastFrameHeight = 0
        lastFrameRowStride = 0
        lastPixelCacheAtMs = 0L
        lastFrameBytesAtMs = 0L
        // 新 reader 的头几帧可能是空缓冲 ⇒ "坏帧不进缓存"的告警对新 reader 重新计一次（见 [cacheLatestPixels]）
        flatCacheSkips = 0L
        flatCacheWarned = false
        MmLog.i(
            TAG,
            "镜像已按内容尺寸调整: ${width}x$height（densityDpi=$densityDpi）" +
                "⇒ 之后帧与屏幕同向（零归一、零换算）",
        )
    }

    /**
     * 建立镜像（`ImageReader` + `VirtualDisplay`）。
     *
     * ## ⚠️ 只能在会话建立时调用**一次**
     *
     * **平台不允许对同一个 `MediaProjection` 再调一次 `createVirtualDisplay`**：第二次调用会在
     * 系统侧的 `MediaProjectionManagerService$MediaProjection.isValid` 抛 `RemoteException`
     * （`targetSdk` 37；调用点在帧线程上 ⇒ 未捕获 ⇒ **整个进程崩**，无障碍服务与采集一起没）。
     * 2026-09-23 真机踩过这条（当时的"转屏重建镜像"看门狗就是这么崩的）。
     *
     * ## 那转屏怎么办（2026-09-23 起：**先调整，再兜底**）
     *
     * **第一道（正路）**：平台在转屏时回调 `onCapturedContentResize` ⇒ [applyMirrorResize] 用
     * `VirtualDisplay.resize()` + 换 `ImageReader` surface 把镜像调整到新尺寸。真机实测（2026-09-23 23:16）
     * 这个回调会来、而且跟得上内容（45 秒 17 次）⇒ 调整成功后**帧与屏幕同向**，方向归一与点击换算
     * 都退化成兜底。会话建立那一刻定死的几何只影响"调整之前"的那一小段。
     *
     * **第二道（兜底）**：调整失败 / 平台没回调 / 调整后仍不正常时，退回"检测"路线 ——
     * [noteDisplayChanged] 记下转屏时刻，[checkMirrorStall] 在随后这段时间里确认帧流有没有跟着停，
     * 停了就**主动结束会话**（让状态如实变「未激活」，用户看到熟悉的"请到授权页重新授权"，
     * 而不是静默卡死）。真机上出现过"转屏时镜像吐一张空帧、之后帧流停滞、管线一直重放那张空帧且不自愈"，
     * 那正是第二道要兜的形态。
     */
    private fun attachMirror() {
        val projection = mediaProjection ?: return
        val handler = frameHandler ?: return
        val metrics = resources.displayMetrics
        // **按横屏建镜像**（2026-09-24 口径 A）：游戏恒为横屏，而本 App 锁竖屏 ⇒ 授权这一刻 `displayMetrics`
        // 是竖的（1440×3168）。照它建的话，用户切回游戏（3168×1440）时平台**必然**回调"内容尺寸变化" ⇒
        // 走 `resize()` + `setSurface()` —— 而这条路在这个 ROM 上有两种坏法：**完全不再产帧** /
        // **产的全是黑帧**（progress 第 170 / 172 条，10:16~10:36 五次实录，两种缓解都试过无效）。
        // ⇒ 直接按横屏建，切回游戏时**根本不需要 resize**，从源头绕开这条缺陷。
        // 标定产物记录的帧几何本来也是横屏（3168×1440），这里与它对齐。
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        val densityDpi = metrics.densityDpi

        // 先放旧的（顺序：虚拟显示持有 reader 的 surface，先释放它再关 reader）
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader
        reader.setOnImageAvailableListener({ handleFrameAvailable() }, handler)
        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
        // 重建后**不留旧帧**：留着的那张就是坏掉的那一张，兜底会一直重放它（这正是卡死的原因）。
        // 清掉之后，重放在新帧到来前什么也不做 —— 比"重放一张空白"诚实得多。
        lastFrameBytes = null
        lastFrameWidth = 0
        lastFrameHeight = 0
        lastFrameRowStride = 0
        lastPixelCacheAtMs = 0L
        lastFrameBytesAtMs = 0L
        lastNewFrameAtMs = SystemClock.elapsedRealtime()
        // **首次投喂自检**（2026-10-01，方案 2）：起表 + 撤掉上一会话的"没被投喂"结论。
        // ⚠ 放在 `createVirtualDisplay` **之后**：从这个时刻起，平台若投喂，`handleFrameAvailable` 就会被回调。
        sessionsInProcess++
        sessionStartedAtMs = SystemClock.elapsedRealtime()
        firstFeedWarned = false
        FrameFreshness.noteSessionStart()
        MmLog.i(
            TAG,
            "本进程第 $sessionsInProcess 次建立投屏会话" + (
                if (sessionsInProcess > 1) {
                    "（⚠ 同进程重建 —— 真机上这类会话经常**一帧都收不到**，见「首次投喂自检」；" +
                        "若 ${FrameFreshness.FIRST_FEED_DEADLINE_MS / 1000} 秒零帧请菜单「退出」重开）"
                } else {
                    "（本进程首次）"
                }
                ),
        )
        // 会话几何记一行（2026-09-23）：**帧几何 = 建立这一刻的屏幕尺寸**，之后转屏不会跟随。
        // 这一句以前没有 ⇒ "帧池里为什么混着横竖两种帧""产物基准到底是哪种几何"都只能靠反推。
        MmLog.i(
            TAG,
            "会话帧几何: ${width}x$height（${if (width >= height) "横" else "竖"}）｜densityDpi=$densityDpi" +
                "｜建会话这一刻的几何（转屏随后由 applyMirrorResize 跟进）",
        )
        frameGeometrySignature = null
        flatFrameStreak = 0
        flatFrameWarned = false
    }

    /**
     * 屏幕几何变化（转屏 / 折叠 / `wm size` / **我们自己这个竖屏 App 跑到前台**）：
     * **只记日志、不再给 [checkMirrorStall] 起表**（2026-09-29 真机事故后改）。
     *
     * **只看默认屏幕**：`createVirtualDisplay` 自己也会让显示列表变化，若对所有 displayId 都有反应，
     * 就会把虚拟显示自己的事件也算成转屏。
     *
     * ## ⚠ 为什么不再在这里 arm 看门狗（两次误杀换来的）
     *
     * 这个回调**不代表镜像被改过** —— 本 App 锁竖屏，它自己切到前台时"默认屏幕"就变成竖屏，
     * 于是这里也会被调到；而此时镜像仍是横屏、**一次 resize 都没发生**（日志里是
     * `镜像 resize 跳过：平台报的是竖屏内容 …`）。
     *
     * 2026-09-29 真机事故（用户报"采集会话在点击换号后选择区服后异常结束"）：
     * ```
     * 02:43:58.4  会话刚建立（用户重新授权）；画面正常刷新 ⇒ 这里给看门狗起了表
     * 02:44:09.9  转屏后 11 秒没有新帧 ⇒ 判"镜像已失效"并**主动结束会话**
     * ```
     * 而那时**镜像好得很**：用户停在游戏的一个**静态页面**上（"共享一个应用"采集时静态页不出新帧
     * 是设计内的，正是"静止兜底重放"存在的原因）⇒ 白杀一个会话，用户被迫重新授权。
     *
     * 现在看门狗**只在真的 `VirtualDisplay.resize()` 成功之后**起表（见 [applyMirrorResize]）——
     * 那时"10 秒还没有新帧"才是镜像失效的强信号（2026-09-24 那次"resize 成功后 102 秒无新帧"
     * 正是靠它抓到的，仍然保得住）。
     */
    private fun noteDisplayChanged() {
        if (mediaProjection == null) return
        val now = SystemClock.elapsedRealtime()
        // **只在"转屏前画面本来就在刷新"时才值得说一句**（2026-09-23 真机误报后加的判断）：
        // 画面若本来就是静止的（停在 App 界面 / 静态页），合成器本来就不产帧 —— 那时这条毫无信息量。
        if (now - lastNewFrameAtMs > FRAME_FRESH_MS) {
            MmLog.i(
                TAG,
                "屏幕几何变化：此前画面已静止 ${(now - lastNewFrameAtMs) / 1000}s，" +
                    "不确认帧流（静止画面不产帧属正常）",
            )
            return
        }
        MmLog.i(
            TAG,
            "屏幕几何变化（不重建镜像：平台只允许一个虚拟显示）" +
                "—— 不据此判镜像失效（那要看 [applyMirrorResize] 之后有没有新帧）",
        )
    }

    /** 「帧几何与标定产物不一致」已告警过：不匹配期间只吼一次，避免每帧刷屏（见 [processPixels]）。 */
    private var calibrationGeometryWarned = false

    /**
     * 运行帧是否与**标定产物**同几何。未标定（产物尺寸为 0）⇒ 视为成立 —— 没有可比的东西，
     * 而此时识别本来也不产生任何判定（空信号）。
     */
    private fun frameGeometryMatchesCalibration(frameWidth: Int, frameHeight: Int): Boolean {
        if (calibratedFrameWidth <= 0 || calibratedFrameHeight <= 0) return true
        return frameWidth == calibratedFrameWidth && frameHeight == calibratedFrameHeight
    }

    /**
     * **几何守卫**（静止兜底轮每秒调一次，2026-09-23 与新方案配套）：帧几何必须与**屏幕**几何一致。
     *
     * 为什么单独要这一条：转屏现在由 `VirtualDisplay.resize()` 跟进（[applyMirrorResize]），
     * 于是"帧 == 屏幕 == 标定"成为常态，识别与点击里那些几何换算退化成恒等。**但 resize 不保证一定成功**
     * （平台不给回调 / ROM 拒绝）—— 那种情况下帧几何会和屏幕错开，而识别读的像素、点击要打的坐标
     * 都按"两者一致"的前提在用 ⇒ 继续跑等于在错的坐标系里操作（看着像对的、点下去是错的）。
     *
     * **T4-6 起多看守一条**：帧还必须与**标定产物**同几何（[frameGeometryMatchesCalibration]）——
     * 方向归一拆掉后，这是模板尺度成立的唯一前提。两种不匹配都在 [processPixels] 里"不判不点"，
     * 这里负责在宽限期过后**如实结束会话**。
     *
     * 处置与帧流看门狗同一条口径：**主动结束会话**，让状态如实变「未激活」、用户回到熟悉的"重新授权"，
     * 而不是留一条自以为在工作的流水线。宽限 [GEOMETRY_MISMATCH_GRACE_MS]：转屏瞬间两者必然短暂不一致。
     */

    private fun checkGeometryGuard(now: Long) {
        val frameWidth = lastFrameWidth
        val frameHeight = lastFrameHeight
        val metrics = resources.displayMetrics
        // **"帧 vs 屏幕"这条判据必须带"目标在前台"这个前提**（2026-09-24 修：口径 A 的副作用）。
        // 回到我们 App 时屏幕变竖屏（1440×3168），而镜像**故意**保持横屏（见 [attachMirror] 的口径 A）
        // ⇒ 必然"互为转置"。真机实录：点「返回 App」后 3 秒会话被结束
        // （`10:42:17 采集会话状态变化: ACTIVE -> INACTIVE（来源: 帧几何与屏幕不一致）`，
        //   10:44:06 重授权后又来一次）。
        // 这条判据的本来目的就是"帧没跟上**游戏画面**"；游戏不在前台时屏幕方向是我们 App 的，
        // 帧保持横屏是**设计预期**，不是故障。产物那条（calibrationMismatch）不依赖屏幕方向，仍旧照判。
        val screenMismatch = ForegroundSignal.isForeground &&
            frameWidth > 0 && frameHeight > 0 &&
            (frameWidth != metrics.widthPixels || frameHeight != metrics.heightPixels)
        // **T4-6 增补**：帧还必须与**标定产物**同几何 —— 方向归一拆掉后，这是模板尺度成立的前提。
        // 不符时 [processPixels] 已经按"不判不点"处理，这里负责如实**结束会话**（比"一直未知"诚实）。
        val calibrationMismatch = frameWidth > 0 && frameHeight > 0 &&
            !frameGeometryMatchesCalibration(frameWidth, frameHeight)
        if (!screenMismatch && !calibrationMismatch) {
            geometryMismatchSinceMs = 0L
            return
        }
        if (geometryMismatchSinceMs == 0L) {
            geometryMismatchSinceMs = now
            val detail = if (calibrationMismatch) {
                "帧几何与**标定产物**不一致：帧 ${frameWidth}x$frameHeight｜产物 " +
                    "${calibratedFrameWidth}x$calibratedFrameHeight（T4-6 起不再做方向归一 ⇒ 这一轮不判不点）"
            } else {
                "帧几何与屏幕不一致：帧 ${frameWidth}x$frameHeight｜屏幕 " +
                    "${metrics.widthPixels}x${metrics.heightPixels}（多半是 resize 没跟上；" +
                    "目标不在前台时这条不判故障，见 [checkGeometryGuard] 的说明）"
            }
            MmLog.w(TAG, "$detail（等 ${GEOMETRY_MISMATCH_GRACE_MS / 1000} 秒确认）")
            return
        }
        if (now - geometryMismatchSinceMs < GEOMETRY_MISMATCH_GRACE_MS) return
        MmLog.w(
            TAG,
            "帧几何持续 ${(now - geometryMismatchSinceMs) / 1000} 秒与屏幕不一致 ⇒ 主动结束会话，" +
                "请在授权页重新建立采集",
        )
        geometryMismatchSinceMs = 0L
        stopSource = SOURCE_GEOMETRY_MISMATCH
        stopSelf()
    }

    /**
     * **镜像真的被 resize 过之后的**帧流确认（由静止兜底轮每秒调一次）。
     *
     * ⚠ **起表的人只有一个**：`VirtualDisplay.resize()` 成功那一刻（[applyMirrorResize]）——
     * 屏幕几何变化本身**不算**（见 [noteDisplayChanged] 里 2026-09-29 那次误杀的说明：那只是
     * "默认屏幕换了个方向"，镜像一次都没动过）。
     *
     * 为什么这样判：resize 会让生产端重新出帧，所以"resize 之后 [MIRROR_STALL_TIMEOUT_MS] 内
     * 一个新帧都没有"是镜像失效的**强信号**（而平时"静止画面不出帧"是正常的，不能拿它当判据 ——
     * 那正是 2026-09-23 与 2026-09-29 两次误杀的原因）。
     * 失效时**主动结束会话**：状态如实变「未激活」，界面提示"请到授权页重新授权采集" ——
     * 比静默卡死（状态永远「未知」、起点判定一直拦、界面毫无提示）诚实得多，也只多几步操作。
     */
    private fun checkMirrorStall() {
        val rotationAt = mirrorRotationAtMs
        if (rotationAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - rotationAt < MIRROR_STALL_TIMEOUT_MS) return
        mirrorRotationAtMs = 0L
        if (lastNewFrameAtMs > rotationAt) return
        // **第二道闸：目标必须在前台**（2026-09-23 误报后加）。
        // 不在前台时屏幕很可能是静止的（用户停在 App / 桌面上），"没有新帧"毫无信息量；
        // 而目标在前台意味着游戏正在渲染 ⇒ 帧流本该不停，此时还没有帧才是真失效。
        if (!ForegroundSignal.isForeground) {
            MmLog.i(
                TAG,
                "resize 后 ${(now - rotationAt) / 1000} 秒无新帧，但目标不在前台（静止画面属正常）" +
                    "→ 不判定为镜像失效",
            )
            return
        }
        MmLog.w(
            TAG,
            "resize 后 ${(now - rotationAt) / 1000} 秒没有新帧 —— 镜像已失效（画面只会重放旧帧）" +
                "⇒ 主动结束会话，请在授权页重新建立采集",
        )
        stopSource = SOURCE_MIRROR_STALL
        stopSelf()
    }

    /**
     * **目标离开前台之前，画面在不在刷新**（`frozen: true` 那一刻记下，回前台时交给 [FrameFreshness]）。
     *
     * 为什么记它（2026-10-01 真机事故，用户报"切出切回游戏，又认不出画面了"）：
     * 平台会在"切出到别的应用、再切回游戏"之后**停止投喂**（`onImageAvailable` 回调从 361 次/10 秒
     * 变成恒 0 次，而目标在前台）。这种断供与"屏幕本来静止"在日志里长得一样，唯独有一个区别：
     * **切出去之前画面在不在刷新** —— 在刷新 ⇒ 平台本来在产帧，而"切回来"这件事本身会让画面重绘
     * ⇒ 十几秒还没有新帧就不是"静止"，是**投喂断了**（判据与处置见 [FrameFreshness.FOREGROUND_RETURN_STALL_MS]）。
     */
    private var framesFlowingBeforeLeave = false


    /**
     * **首次投喂自检**（2026-10-01 加，方案 2；用户拍板"把 60 秒的懵等缩成开场就知道"）。
     *
     * ## 它补的是 [checkFrameStall] 的洞
     *
     * 那条看门狗要求"**曾经收到过新帧**"（没有基线就不判）—— 而真机卡住的那次
     * （2026-10-01 01:16）恰恰是**从会话建立起就没被投喂**：平台推了一帧就停，
     * 此后 `onImageAvailable` 回调 0 次 ⇒ 看门狗一句不说 ⇒ 用户干等 60 秒、
     * 而且**跑号已经跑到第 3 步**（点完大厅设置入口，画面却永远停在旧的大厅帧上）⇒ 白跑一轮。
     *
     * ⇒ 这里只用一条判据：**建立会话后 [FrameFreshness.FIRST_FEED_DEADLINE_MS] 内一帧都没来**
     *    （`onImageAvailable` 回调 0 次）。判出来就：
     * 1. `FrameFreshness.noteStarved()` ⇒ 界面立刻说「授权没被投喂·请退出重开」（待命期也说，见
     *    `FloatingLabelText.isIdle` 的说明）；
     * 2. 日志写清"平台没投喂"与**唯一验证过的处置**（换进程）。
     *
     * ## 为什么不再等久一点（比如 20 秒）
     *
     * 这一档的目的就是"**开场就知道**"：用户刚授完权、还没切回游戏 ⇒ 8 秒的代价几乎为零；
     * 而拖到 60 秒（停更看门狗）时他多半已经在游戏里跑流程了 —— 那时才发现，损失是一整轮。
     * 误报风险也低：**正常会话建立后立刻就有帧**（真机每次都如此），8 秒一帧不来不是"慢"，是"没投喂"。
     */
    private fun checkFirstFeed() {
        if (firstFeedWarned) return
        // **判据必须是"整场有没有收到过帧"**（`FrameFreshness.ageMs() == null` = 自会话建立起一帧真帧都没来过），
        // **不能**用 [frameCallbacks] —— 那是**每 10 秒窗口**的计数（[logFrameProbeIfQuiet] / [maybeLogStats]
        // 都会把它归零）⇒ 归零那一拍会被读成 0，于是误报"平台没投喂"。
        // 真机误报实录（2026-10-01 05:22）：前一行探针写着 `回调 3715 次`，紧接着本自检却说"一帧都没收到"
        // ⇒ 用户看到「授权没被投喂·请退出重开」，白重开了一轮（而当时投喂一直是好的）。
        if (FrameFreshness.ageMs() != null) return // 收到过真帧 ⇒ 平台在投喂，不是这一档
        val started = sessionStartedAtMs
        if (started == 0L) return // 还没有会话 ⇒ 无从判起
        // **目标不在前台时不判，并把起表顺延到回前台那一刻**（与 [checkFrameStall] 同一口径）：
        // 被采集的应用在后台时平台本来就不投喂（没内容可投），那是**正常**的，不是这一档要抓的"坏会话"
        // ⇒ 别拿"后台那段时间"去凑够 8 秒（用户 2026-10-01 报的正是"切后台一会儿、切回来就弹这句"）。
        if (!ForegroundSignal.isForeground) {
            sessionStartedAtMs = SystemClock.elapsedRealtime()
            return
        }
        val ageMs = SystemClock.elapsedRealtime() - started
        if (ageMs < FrameFreshness.FIRST_FEED_DEADLINE_MS) return
        firstFeedWarned = true
        FrameFreshness.noteStarved()
        MmLog.w(
            TAG,
            "这次会话 ${ageMs / 1000} 秒**从未收到过帧**（本窗 onImageAvailable 回调 $frameCallbacks 次 / " +
                "取空 $frameAcquiresEmpty 次）⇒ **平台没有投喂画面**：" +
                "识别与点击都无法工作。历史上这种会话**只能换进程**（菜单「退出」重开 App 再授权）——" +
                "同一个会话里重新授权照旧零帧（真机 2026-10-01 01:16 实录）" +
                "｜本进程第 $sessionsInProcess 次建会话" +
                "｜缓冲 ${imageReader?.width}x${imageReader?.height}" +
                "｜目标" + (if (ForegroundSignal.isForeground) "在前台" else "不在前台"),
        )
    }

    /**
     * **帧流停更看门狗**（2026-09-30 加，真机第 301 条；**同日按用户口径 L2 改写：只提示，不结束会话**）。
     *
     * 「**目标在前台** + **曾经收到过新帧** + 之后 ≥ [FrameFreshness.stallDeadlineMs] 没有新帧」
     * ⇒ **停止一切点击**（[ClickDispatch.setFramesStalled]）+ 如实记一行日志；**不结束会话**，
     * 界面由 [FrameFreshness] 说话（顶部标签「画面久未更新」、菜单里的原因行）。
     *
     * ⚠ 那条线**不是常数**：常规 60 秒，而"**切出切回且离开前画面在刷新**"那次是 12 秒
     * （事件证据 ⇒ 不再是"可能静止"，见 [FrameFreshness.FOREGROUND_RETURN_STALL_MS]）。
     *
     * ## 它原来是"结束会话"的（第 301 条），为什么改
     *
     * 当时的立论是 **"本游戏大厅有背景 CG 动画 ⇒ 画面一直在动 ⇒ 平台本该持续产帧"**（2026-09-30 用户拍板），
     * 据此把"有基线之后长时间零新帧"当成**故障证据**、直接结束会话。当天真机证明这条前提**不普遍成立**：
     * ```
     * 目标在前台（农场）+ 画面静止 + 零新帧 60 秒          ← 平台对静止画面不产帧（静止兜底重放正是为此存在）
     * ⇒ 判"镜像已失效"并结束会话 ⇒ 用户被要求重新授权，正在跑的流程整条白跑
     * ```
     * 根子上：**"没有新帧"分不出两件事** ——「屏幕真的没变」（平台按设计不产帧）与「镜像失效」（画面在变、
     * 我们却收不到）。它是**现象**，不是**证据**。⇒ 现在它只做两件不可能造成损失的事：
     * 停止点击（旧的点击闸，来一帧新画面就自愈）+ 说出来（[FrameFreshness] 驱动标签与菜单）。
     *
     * ⚠ **安全性不靠"结束会话"**：照着旧画面点的风险由 [ClickDispatch.setFramesStalled] 独立挡住
     * （`ClickDenyReason.STALE_FRAMES`）⇒ 自动结束会话不是安全措施，只是**体验**措施；而它误判的代价
     * （掐掉整条流程 + 要求重新授权）远大于收益。要判"镜像真的失效"必须有**事件证据**（例如我们刚刚真的
     * `resize()` 过镜像、前台应用切换过、内容尺寸报过变化）—— 那是另一轮的事。
     *
     * ## ⚠ 两条护栏（都来自真机教训，别删）
     *
     * 1. **必须"曾经收到过新帧"**（[lastNewFrameAtMs] > 0）：2026-09-28 那次误杀（刚授完权 4 秒就被要求
     *    重新授权）正是"**一帧都还没来就判失效**"。没有基线 ⇒ 不参与 —— 这不是故障证据，只是"还没开始"。
     * 2. **必须目标在前台**：不在前台时屏幕很可能静止（用户停在我们的 App / 桌面上），"没新帧"毫无信息量
     *    （2026-09-23 误杀的教训）。
     *
     * ⚠ **仍然会结束会话的两条**（都不是"无新帧"这一类，别混）：[checkMirrorStall]（我们**刚刚真的
     * `resize()` 过镜像**之后 10 秒无新帧 ⇒ 多一个事件前提）与黑帧兜底（[noteFrameContent]：帧在来、
     * 但**全是黑的** 12 秒 ⇒ "内容为空"是硬证据）。
     */
    private fun checkFrameStall() {
        val last = lastNewFrameAtMs
        if (last == 0L) return // 从来没收到过新帧（刚授权）⇒ 不参与
        if (!ForegroundSignal.isForeground) return // 不在前台 ⇒ 静止属正常
        val ageMs = SystemClock.elapsedRealtime() - last
        // 停更线由 [FrameFreshness] 给（**单一来源**）：常规 60 秒，而"切出切回且离开前画面在刷新"
        // 那次是 12 秒 —— 后者的依据与真机事故见 [FrameFreshness.FOREGROUND_RETURN_STALL_MS]。
        if (ageMs < FrameFreshness.stallDeadlineMs()) return
        // 与 [checkFrameExpectation] 共用同一道点击闸：置位期间**任何来源**的点击都被拒
        //（`ClickDenyReason.STALE_FRAMES`）—— 旧画面上绝不能开枪。真机实录（2026-09-29）：会话重建后
        // **整整 5 分钟没有一帧新画面**，程序却照着 **234 秒前**那张大厅画面点了一枪。
        // ⚠ 已经置位时**不再重复记日志**（这条判据此后每轮都会命中）：新帧到达时 [processFrame] 会解除，
        // 之后再停更会重新说一次 —— 日志条数与"真的又停了一次"一一对应。
        if (ClickDispatch.framesStalled) return
        ClickDispatch.setFramesStalled(true)
        MmLog.w(
            TAG,
            "采集画面已停更 ${ageMs / 1000} 秒（目标在前台，且之前一直有帧）⇒ **停止一切点击**" +
                "（手上那张画面是旧的，再点等于照着旧画面点）—— **不结束会话**：收到新画面自动恢复；" +
                "⚠ 停更的原因分不出来（屏幕本来静止 / 镜像失效）⇒ 界面只说「画面久未更新」、不判故障，" +
                "要一直不更新请自行重新授权采集｜探针：回调 $frameCallbacks 次 / 取空 $frameAcquiresEmpty 次" +
                "（回调 0 次 = 平台没投喂；有回调却取空 = 缓冲卡在我们这一侧）",
        )
    }

    /**
     * **帧迟迟不来时的探针行**（2026-09-30 加，见 [frameCallbacks]）：每 [STATS_WINDOW_MS] 一行，
     * **只在"这一窗一帧都没到"时**输出 —— 正常工作时那一行由 [maybeLogStats] 负责，不必重复刷屏。
     *
     * ## 怎么读它（一眼分清三种形态）
     *
     * | 探针行 | 结论 | 该往哪修 |
     * | --- | --- | --- |
     * | `回调 0 次` | **平台根本没往 surface 投喂** ⇒ 镜像通路停了 | [checkSurfaceRetry] 换表面 / 请用户重新授权 |
     * | `回调 N 次 / 取空 N 次 / 取到 0 帧` | **回调来了却拿不到 buffer** | 我们这一侧：缓冲没归还 / `MAX_IMAGES = $MAX_IMAGES` 队列卡死 |
     * | `回调 N 次 / 取到 N 帧` 而帧龄仍很大 | 三者不可能同时成立 | 说明还有别的口径（帧龄 / 计数）算错了，得查 |
     */
    private fun logFrameProbeIfQuiet(now: Long) {
        if (lastStatsAt == 0L) {
            lastStatsAt = now // 第一次只起表（与 [maybeLogStats] 同一口径：否则窗口是个假数）
            return
        }
        if (now - lastStatsAt < STATS_WINDOW_MS) return
        val ageSec = if (lastNewFrameAtMs == 0L) -1L else (now - lastNewFrameAtMs) / 1000
        val ageNote = if (ageSec < 0) "从未收到过帧" else "距上一新帧 ${ageSec}s"
        MmLog.w(
            TAG,
            "帧回调探针（这一窗没有新帧）: onImageAvailable 回调 $frameCallbacks 次 / " +
                "取到 $framesReceived 帧 / 取空 $frameAcquiresEmpty 次｜$ageNote｜" +
                "目标${if (ForegroundSignal.isForeground) "在前台" else "不在前台"}｜" +
                "缓冲 ${imageReader?.width}x${imageReader?.height}",
        )
        lastStatsAt = now
        framesReceived = 0
        framesProcessed = 0
        frameCallbacks = 0
        frameAcquiresEmpty = 0
    }

    /**
     * **停更时的自救：换一次镜像表面**（2026-09-30 加，真机"零帧"事故）。
     *
     * 触发：**目标在前台** + **有基线**（收到过新帧）+ **停更 ≥ [FrameFreshness.stallDeadlineMs]**
     * （与 L2 提示同一阈值；"切出切回"那次这条线是 12 秒，见 [FrameFreshness.FOREGROUND_RETURN_STALL_MS]）
     * + 还有次数（每会话 ≤ [SURFACE_RETRY_MAX] 次）+ 过了冷却（[SURFACE_RETRY_COOLDOWN_MS]）。
     *
     * ## 三条纪律（都别改）
     *
     * 1. **同尺寸、绝不 resize**：帧几何必须恒等于标定几何（横屏 3168x1440）—— 改了就会让"识别读的像素 /
     *    点击要打的坐标"整体错位。这里**只换 surface**，一个像素都不动（与 [applyMirrorResize] 里
     *    "跳过竖屏内容"是同一条纪律的另一半）。
     * 2. **不给 [checkMirrorStall] 起表**：那条判据会"10 秒无新帧 ⇒ 结束会话"，而第 326 条之后我们**不再**
     *    用"无新帧"结束会话 ⇒ 换完只由本函数自己的计数与 [logFrameProbeIfQuiet] 的探针说话。
     * 3. **有上限、有冷却、每次都留痕**：换成功与否都能从紧跟着的"帧回调探针"行看出来。
     *
     * ## 为什么要试（而不是干等）
     *
     * 平台**只允许对同一个 `MediaProjection` 调一次 `createVirtualDisplay`**（第二次会抛异常并崩进程，
     * 见 [attachMirror]）⇒ 换 surface 是我们唯一能做的"重新接上投喂通路"的动作；而当前状态已经是**零帧**，
     * 试了最坏也不更差（真机依据：2026-09-30 那次新会话从头到尾只推了一张空壳帧，见 progress 第 331 条）。
     */
    private fun checkSurfaceRetry(now: Long) {
        val last = lastNewFrameAtMs
        if (last == 0L) return // 还没有基线（刚授权）⇒ 这不是停更，别乱动
        if (!ForegroundSignal.isForeground) return // 不在前台：静止属正常，动了反而添乱
        if (now - last < FrameFreshness.stallDeadlineMs()) return
        if (surfaceRetries >= SURFACE_RETRY_MAX) {
            if (!surfaceRetryGaveUp) {
                surfaceRetryGaveUp = true
                MmLog.w(
                    TAG,
                    "换镜像表面已试 $surfaceRetries 次仍没有新帧 ⇒ 这条投喂通路救不回来：" +
                        "请重新授权采集（或重启 App 重建 MediaProjection）",
                )
            }
            return
        }
        if (nextSurfaceRetryAtMs != 0L && now < nextSurfaceRetryAtMs) return
        surfaceRetries++
        nextSurfaceRetryAtMs = now + SURFACE_RETRY_COOLDOWN_MS
        retryMirrorSurface(now - last)
    }

    /** 换一次镜像表面（同尺寸）：见 [checkSurfaceRetry] 的三条纪律。仅帧线程调用。 */
    private fun retryMirrorSurface(ageMs: Long) {
        val display = virtualDisplay ?: return
        val old = imageReader ?: return
        val handler = frameHandler ?: return
        val width = old.width
        val height = old.height
        val next = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        } catch (t: RuntimeException) {
            MmLog.w(TAG, "换镜像表面被跳过：新建 ImageReader 失败（${t.javaClass.simpleName} ${t.message}）")
            return
        }
        next.setOnImageAvailableListener({ handleFrameAvailable() }, handler)
        try {
            // **只换 surface**：不 resize、不碰 MediaProjection（那两样都会破坏几何前提 / 直接崩）
            display.setSurface(next.surface)
        } catch (t: RuntimeException) {
            next.setOnImageAvailableListener(null, null)
            next.close()
            MmLog.w(TAG, "换镜像表面失败（保持原样继续跑）：${t.javaClass.simpleName} ${t.message}")
            return
        }
        imageReader = next
        old.setOnImageAvailableListener(null, null)
        old.close()
        // 旧帧作废：它是停更前那张（可能十几分钟前），留着只会让兜底一直重放它
        lastFrameBytes = null
        lastFrameBytesAtMs = 0L
        lastPixelCacheAtMs = 0L
        flatCacheSkips = 0L
        flatCacheWarned = false
        flatReplayWarned = false
        MmLog.w(
            TAG,
            "采集画面已停更 ${ageMs / 1000} 秒 ⇒ **换一次镜像表面自救**（第 $surfaceRetries/$SURFACE_RETRY_MAX 次；" +
                "同尺寸 ${width}x$height，绝不动几何）—— 有没有救回来，看紧跟着的「帧回调探针」",
        )
    }

    /**
     * **"我们刚改过屏幕，该来一帧新帧了"的期待检查**（2026-09-25 加；由静止兜底轮每秒调一次）。
     *
     * ## 它原来是干什么的（第 211 条那次事故）
     *
     * 真机实录：11:23:29 起镜像**再也没有产生过新帧**，识别每轮都在重放同一张缓存画面 ——
     * 用户滚屏、开合面板全都"看不见"（"添加这一屏的服务器"连读四次读到的东西一模一样）。
     * 而 [checkMirrorStall] 只盯"**转屏之后**"那一种失效 ⇒ 这次没人发现，会话一直"看起来在工作"。
     *
     * ## ⚠ 2026-09-28 起**只记一行诊断日志，不再结束会话**（用户报障：刚授完权却被要求重新授权）
     *
     * 报障原话："在 app 中授予采集权限，切换到游戏后，出现未授予采集权限的提示。"
     * 日志实录（第 217 条）：会话 21:27:36.99 建立 → 21:27:37.02 挂上悬浮窗（arm「菜单内容更新」）
     * → 21:27:40.72 这条判据判"镜像已失效"并 `stopSelf()` → 用户在菜单上看到
     * "尚未建立采集会话：请先到「授权状态」页授予屏幕采集权限" ✗ —— 他 4 秒前刚授过权。
     *
     * **根因是这条判据的前提不成立**：它假设"我们自己的悬浮窗画在屏幕上 ⇒ 合成器必然出帧"，
     * 而授权弹窗里选「**共享一个应用**」时（App 里那句说明文案就是这么推荐的），采集内容**只有那一个 App
     * 的窗口**、我们的覆盖窗根本不在里面 ⇒ 面板怎么变都不会来新帧。再叠上小游戏那种**静态页面本身就不出帧**
     * （静止兜底重放是设计内的）⇒ "刚开一下面板就再也没新帧"在这套采集模式下**完全正常**。
     *
     * 现在它只做诊断：**每个会话最多记一行**（带帧龄），不结束会话、不弹提示（静态页面下它每次开面板都会命中，
     * 弹提示只会变成噪声）。会终止会话的判据只剩前提与采集模式无关的那三条：
     * [checkMirrorStall]（转屏后无新帧）/ [checkGeometryGuard]（几何错配）/ 黑帧兜底。
     */
    private fun checkFrameExpectation() {
        val armed = FrameExpectationSignal.armedAtMs
        if (armed == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - armed < FrameExpectationSignal.DEFAULT_TIMEOUT_MS) return
        val reason = FrameExpectationSignal.reason
        // **这份期待是不是"与采集模式无关"那一种**（我们刚下发过一击 / 滑动）——
        // 只有它才允许升级成"画面采集已停"那道硬闸（见 [FrameExpectationSignal.gateEligible]）。
        val gateEligible = FrameExpectationSignal.gateEligible
        FrameExpectationSignal.reached()
        if (lastNewFrameAtMs > armed) return
        // 与 [checkMirrorStall] 同一道闸：目标不在前台时屏幕很可能是静止的，"没有新帧"毫无信息量。
        if (!ForegroundSignal.isForeground) {
            MmLog.i(TAG, "「$reason」之后没有新帧，但目标不在前台（静止属正常）→ 不判定为镜像失效")
            return
        }
        val ageMs = if (lastNewFrameAtMs == 0L) -1L else now - lastNewFrameAtMs
        // **与采集模式无关的期待落空 + 目标在前台 ⇒ 停掉一切点击**（2026-09-29 用户报"换号 / 拜访时
        // 很多标志和锚点都认不出"后加）：这时手上那张画面是旧的，继续点就是"照着旧画面点" —— 真机实录：
        // 会话重建后**整整 5 分钟没有一帧新画面**，程序却照着 **234 秒前**那张大厅画面点了一枪。
        // ⚠ 只有"我们刚下发过一击 / 滑动"那种期待会走到这里（它们绑的是**被采集的那个 App 的内容**，
        // 必然出帧）；**我们自己的面板**那几处（悬浮窗展开 / 收起 / 内容变化）`gateEligible = false`
        // ⇒ 不会闸点击 —— 「共享一个应用」下我们的覆盖窗不在画面里，认它就会误杀（2026-09-28 的教训）。
        // ⚠ 也**不结束会话**（同一条理由）；一帧新画面来了它自己就解除（见 [processFrame]）。
        if (gateEligible && !ClickDispatch.framesStalled) {
            ClickDispatch.setFramesStalled(true)
            MmLog.w(
                TAG,
                "「$reason」之后 ${(now - armed) / 1000} 秒没有新帧（最近一帧 ${ageMs / 1000} 秒前）" +
                    "⇒ **判定「画面采集已停」：停止一切点击**（手上那张画面是旧的，再点等于照着旧画面点）" +
                    "—— 不结束会话；画面一恢复就自动解除，若一直不更新请重新授权采集",
            )
            return
        }
        if (frameExpectationNoted) return
        frameExpectationNoted = true
        MmLog.w(
            TAG,
            "「$reason」之后 ${(now - armed) / 1000} 秒没有新帧（最近一帧 ${ageMs / 1000} 秒前）" +
                "—— **仅诊断、不结束会话**：选「共享一个应用」采集时我们的覆盖窗不在画面里，" +
                "静态页不出新帧属正常；若之后画面一直不更新，请重新授权采集",
        )
    }

    /**
     * 产物里框选的**「服务器列表区域」**（运行帧坐标；没框过 / 读不出 ⇒ null）。
     *
     * 为什么"添加这一屏的服务器"只读它（2026-09-25 用户口径："读取产物配置中框选的服务器列表区域，
     * 绝对不会重复"）：读整屏会把屏幕别处的文字一起读进来 —— 真机事故就是**表头那颗"当前区服"药丸**
     * （`◆ 微信56区 白色死神`）也被当成一条服务器，还把列表里的同一台挤到了清单第一位。
     * 只读列表区域之后，药丸 / 侧边分区标签 / 顶部说明都不在输入里。
     */
    private fun serverListArea(width: Int, height: Int): PixelBounds? {
        val data = calibrationData ?: return null
        val locator = anchorLocator ?: return null
        val id = data.anchorIdFor(UiState.SERVER_SELECT, PatrolAnchors.SERVER_LIST_AREA) ?: return null
        return locator.windowInFrame(width, height, UiState.SERVER_SELECT, id)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            noteDisplayChanged()
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private fun registerDisplayRefresh() {
        val manager = getSystemService(DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = manager
        runCatching { manager.registerDisplayListener(displayListener, null) }
    }

    private fun detachDisplayRefresh() {
        displayManager?.let { runCatching { it.unregisterDisplayListener(displayListener) } }
        displayManager = null
    }

    /**
     * 帧到达回调（帧线程）：取最新帧并释放旧帧，节流后交给识别处理点（帧数据不落盘）。
     */
    //
    // ⚠ **「不在前台就释放镜像、回来再重建」这条路走不通，已回退**（2026-09-29 试过，别再照它写）。
    //
    // 想法很自然：镜像缓冲占 ~87MB，游戏不在前台时采到的也是我们自己的画面 ⇒ 放掉它省内存，回来再建。
    // 但**平台不允许**：
    // ```
    // SecurityException: Don't re-use the resultData to retrieve the same projection instance,
    // and don't use a token that has timed out. Don't take multiple captures by invoking
    // MediaProjection#createVirtualDisplay multiple times on the same instance.
    // ```
    // —— 同一个 MediaProjection **只能 createVirtualDisplay 一次**，`resultData` 也不能复用
    // ⇒ 一旦放掉虚拟显示，**想再采就必须重新授权**（真机后果：进程崩溃 ⇒ 无障碍 / 采集 / 悬浮窗
    // 一起没了，用户必须重新授权才能恢复）。
    // ⇒ 结论：**镜像一旦建立就持有到会话结束**；想省内存只能从"帧缓冲复用 / 更少的缓冲"下手，
    //    不能从"释放再重建"下手。
    //

    private fun handleFrameAvailable() {
        // **探针必须在所有提前 return 之前**（2026-09-30）：否则"回调来了但取不到 buffer"会不留痕，
        // 与"平台根本没投喂"在日志里长得一模一样（见 [frameCallbacks]）
        frameCallbacks++
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            frameAcquiresEmpty++
            return // 会话释放竞态：reader 已关闭
        }
        if (image == null) {
            // 回调来了却取不到 buffer：**第一次**单独说一句（真机排障时这一行价值极高 ——
            // 它直接证明"投喂通路是通的、卡在我们这边（缓冲没归还 / MAX_IMAGES=2 队列满）"）
            frameAcquiresEmpty++
            if (frameAcquiresEmpty == 1L) {
                MmLog.w(
                    TAG,
                    "帧回调来了、但 acquireLatestImage() 取不到 buffer（第一次）—— " +
                        "投喂通路是通的，问题在我们这一侧（缓冲未归还 / 队列满，MAX_IMAGES=$MAX_IMAGES）",
                )
            }
            return
        }

        // ⚠ 2026-09-29：**"工作台期间暂停取帧"这一版已回滚**（用户反馈"还是崩，而且以前没这个问题"）。
        // 真机数据：Java 堆 15 秒从 18MB 涨到 128MB（是留住了的增长，不是垃圾）⇒ 268MB 上限被打爆。
        // 在定位到那个"留住大数组"的地方之前，先把采集侧恢复成以前的写法做二分。
        framesReceived++
        val now = SystemClock.elapsedRealtime()
        // 标定帧录制（T1-5b）：旁路于识别节流，每秒最多 1 帧；仅目标前台时保存
        CalibrationFramePool.maybeCapture(this, image, now, ForegroundSignal.isForeground)
        // **留一份"最新画面"必须在节流判断之前**（2026-09-22 关键修复）：
        // 静止兜底重放用的就是这份像素。若它只在"节流通过"时更新，那么画面刚变过的那一帧
        // 很可能被节流丢掉 ⇒ 兜底轮永远在重放**旧画面**。
        // 真机实录：关掉好友列表后，兜底一直重放"列表开着"那一帧 ⇒ 判定永远读到「好友列表」，
        // 用户"停止后重新点只拜访"必被拦。
        cacheLatestPixels(image)
        // 内容自检（2026-09-23）：转屏后镜像可能"帧照来、内容全黑"，而看门狗只看有没有新帧、抓不到这一种。
        // 放在这里 = **只统计新帧**（静止兜底重放的是同一份像素，重复计数会把"一帧黑"报成"连续黑"）。
        lastFrameBytes?.let { noteFrameContent(FrameUniformity.sample(it)) }
        if (frameThrottle.shouldProcess(now)) {
            framesProcessed++
            lastFrameWidth = image.width
            lastFrameHeight = image.height
            processFrame(image)
        }
        image.close()
        maybeLogStats(now)
    }

    /**
     * **画面静止时的兜底重放**（2026-09-22，真机根因修复）：每 [IDLE_ROUND_INTERVAL_MS]
     * 检查一次，若节流允许就用**最近一帧的副本**再跑一轮识别。
     *
     * 为什么必须兜底：识别原本只由"新帧到达"驱动，而画面静止时系统不产生新帧 ⇒
     * 识别停摆 ⇒ 状态与"最近命中"停在旧值（真机数据见 [IDLE_ROUND_INTERVAL_MS]）。
     *
     * 为什么放在帧线程：与正常轮同一条线程、同一套节流，两个来源不会并发改识别状态。
     * 会话结束时 `imageReader` 为 null，调度自然停下（`frameThread.quitSafely()` 也会清掉挂起任务）。
     */
    private fun scheduleIdleRound() {
        val handler = frameHandler
        if (handler == null) {
            // 起不来就是整条兜底失效（真机踩过：字段没赋上值，静止时识别照样停摆）
            MmLog.w(TAG, "静止兜底调度未启动：帧 Handler 尚未就绪")
            return
        }
        // 本函数自递归 ⇒ 日志必须限一次，否则每秒一行（见 [idleScheduled]）
        if (!idleScheduled) {
            idleScheduled = true
            MmLog.i(TAG, "静止兜底调度已启动：每 ${IDLE_ROUND_INTERVAL_MS}ms 检查一次（画面静止也能刷新识别）")
        }
        handler.postDelayed({
            if (imageReader == null) return@postDelayed // 会话已结束
            // **不查共享节流**（2026-09-22 修，真机血压时刻）：
            // 原本这里调 `frameThrottle.shouldProcess`，而正常轮一直在刷新节流基准
            // ⇒ 兜底轮每次检查都"让路" ⇒ **一次也没重放**（心跳日志全是"节流让路"）
            // ⇒ 正常轮一因故停下（跑号刚结束那几秒），识别就彻底断档，
            //   判定只能读到旧结论（用户报障："停止后重新点只拜访被拦"）。
            // 兜底轮**自己就是**"每 IDLE_ROUND_INTERVAL_MS 一次"，不需要第二层节流。
            idleTicks++
            // 心跳留痕但**稀疏**：它的价值只剩"证明这条自递归链条没断"（2026-09-22 根因已定位，
            // 原先每 5 秒一行把真机日志淹了）。每分钟一行足够。
            if (idleTicks % IDLE_HEARTBEAT_TICKS == 0L) {
                // 「距上一新帧」是这一行最要紧的信息（2026-09-23 真机排障的唯一判据）：
                // 静止画面本来就不产生新帧（这正是兜底存在的原因），但**一直**没有新帧
                // 就说明帧流断了（转屏那一瞬的偶发故障，见 [attachMirror]）。
                val sinceNewFrameS = (SystemClock.elapsedRealtime() - lastNewFrameAtMs) / 1000
                // 「目标在不在前台」与「距上一新帧」必须一起看（2026-09-23 真机排障的教训）：
                // 静止画面不产帧是正常的，只有"目标在前台 + 长时间无新帧"才是镜像失效。
                val foreground = if (ForegroundSignal.isForeground) "在前台" else "不在前台"
                MmLog.i(
                    TAG,
                    "静止兜底心跳：第 $idleTicks 次（已重放，链条存活；" +
                        "距上一新帧 ${sinceNewFrameS}s；目标$foreground）",
                )
            }
            // 转屏后的帧流确认（一次性）：停在"转屏后一直没新帧"这种镜像失效上（见 checkMirrorStall）
            checkMirrorStall()
            // **不再只盯"resize 之后"**：任何"有基线之后长时间零新帧"都按失效处理（见 checkFrameStall）
            checkFrameStall()
            // **开场就零帧**（从建立起一帧都没来）⇒ 8 秒就给结论（见 checkFirstFeed；停更看门狗管不到这一档）
            checkFirstFeed()
            // "我们刚改过屏幕（面板展开 / 收起 / 内容变化）却没来新帧" ⇒ 镜像失效（见 checkFrameExpectation）
            checkFrameExpectation()
            val now = SystemClock.elapsedRealtime()
            // 停更时的**自救**：换一次镜像表面（有上限 / 有冷却 / 每次都留痕，见 checkSurfaceRetry）
            checkSurfaceRetry(now)
            // 帧一直不来时，每 10 秒把"回调 / 取空"的探针写出来（正常工作时那一行由 maybeLogStats 负责）
            logFrameProbeIfQuiet(now)
            // 几何守卫（每秒一次）：帧几何与屏幕几何必须一致（见 geometryMismatchSinceMs）
            checkGeometryGuard(now)
            framesProcessed++
            replayLastFrame()
            scheduleIdleRound()
        }, IDLE_ROUND_INTERVAL_MS)
    }

    /** 用最近一帧的像素副本再跑一轮识别（静止兜底，见 [IDLE_ROUND_INTERVAL_MS]）。 */
    private fun replayLastFrame() {
        val bytes = lastFrameBytes ?: return
        // **坏帧不重放**（2026-09-24 第二版兜底）：万一缓存里已经躺着一张全平帧（旧版本存进去的、
        // 或判据调整前留下的），重放它只会让整场识别一直读到"窗口无图案"（分数全 `-`），
        // 而且**看不出异常**（心跳照样说"已重放"）。这里再拦一道：全平就什么都不做。
        if (FrameUniformity.sample(bytes).flat) {
            if (!flatReplayWarned) {
                flatReplayWarned = true
                MmLog.w(TAG, "兜底缓存里的这一帧近乎全平 ⇒ 不重放（避免整场识别读到空白）")
            }
            return
        }
        // 重放的是**缓存副本**：本轮判定用的画面 = 它被拍下来的那一刻，不是"现在"（见 [currentFrameAtMs]）
        currentFrameAtMs = lastFrameBytesAtMs
        currentFrameReplayed = true
        processPixels(bytes, lastFrameWidth, lastFrameHeight, lastFrameRowStride)
    }

    /**
     * 本轮识别用的画面有多"旧"（T4-9 事故后加，2026-09-24）：`新帧（132ms 前拍到）` /
     * `兜底重放（812ms 前的画面）`。
     *
     * 为什么值得进每轮的判定日志：真机上"帧流活着"与"这一轮用的是新画面"是两件事，
     * 而"点了之后又看到旧画面 ⇒ 又点一次"这类事故**只有这条信息能定性**（`docs/progress.md` 第 189 条）。
     */
    private fun frameTag(): String {
        val ageMs = SystemClock.elapsedRealtime() - currentFrameAtMs
        val what = if (currentFrameReplayed) "兜底重放" else "新帧"
        return "$what（${ageMs}ms 前拍到）"
    }

    /**
     * 留一份**最新画面**给静止兜底重放（2026-09-22 关键修复，调用点必须在节流判断**之前**）。
     *
     * 为什么不能只在"节流通过"时更新：画面刚变过的那一帧很可能被节流丢掉，
     * 于是兜底轮一直重放旧画面（真机实录：关掉好友列表后仍读到「好友列表」）。
     *
     * 为什么降频到 [PIXEL_CACHE_INTERVAL_MS]：这里的像素提取是一次 memcpy（约 1.4MB），
     * 按 60fps 全量做没必要 —— 兜底轮本身也只要 1 秒一份。
     */
    /**
     * **取帧字节的复用缓冲**（2026-09-29，按每秒内存采样数据加回）。
     *
     * 一帧 3168×1440×4 ≈ **18MB**。真机每秒采样（`dumpsys meminfo`）：
     * ```
     * TOTAL=193MB Java=19MB Graphics=87MB   ← 静止（镜像缓冲 87MB 是固定的，降不下来）
     * TOTAL=256MB Java=71MB                 ← 几秒后撞上 256MB 上限 ⇒ 死
     * ```
     * 这台机器 `dalvik.vm.heapgrowthlimit=256m` ⇒ 静止 193MB 只剩 ~60MB 余量，
     * 而"每帧 new 一个 18MB"会让 Java 堆在几秒内冲高 50MB ⇒ 直接顶穿。
     * ⇒ 尺寸一致时**写回同一块**，不再每帧重新申请（尖峰消掉，只剩两块常驻 ≈36MB）。
     *
     * 只由帧线程访问（兜底重放也在这一条线程上），不存在并发窗口。
     */
    private var frameScratchA: ByteArray? = null

    /** 留存用的缓冲 B（[lastFrameBytes] 指向它；A 每帧被覆盖，不能当留存）。 */
    private var frameScratchB: ByteArray? = null

    /** 把一帧读进复用缓冲 A（无新分配）。 */
    private fun ingestFrameBytes(buffer: java.nio.ByteBuffer): ByteArray {
        val size = buffer.remaining()
        val target = frameScratchA?.takeIf { it.size == size }
            ?: ByteArray(size).also { frameScratchA = it }
        buffer.get(target)
        return target
    }

    /** 把 A 里的内容**拷进** B 并交给 [lastFrameBytes]（无新分配；A 下一帧还会被覆盖）。 */
    private fun retainFrameBytes(bytes: ByteArray): ByteArray {
        val target = frameScratchB?.takeIf { it.size == bytes.size }
            ?: ByteArray(bytes.size).also { frameScratchB = it }
        System.arraycopy(bytes, 0, target, 0, bytes.size)
        return target
    }

    private fun cacheLatestPixels(image: Image) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastPixelCacheAtMs < PIXEL_CACHE_INTERVAL_MS) return
        lastPixelCacheAtMs = nowMs
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val bytes = ingestFrameBytes(buffer)
        // **坏帧不进缓存**（2026-09-24 真机 bug 修复）：
        // `applyMirrorResize` 换掉的 ImageReader，其**头几帧可能是还没被画上内容的空缓冲**（全平）。
        // 若把它存进兜底缓存，而画面随后**静止不动**（静止不产新帧是常态）⇒ 兜底轮会**一直重放这张空帧**，
        // 整场识别全是 `-(NOT_MATCHED)`（窗口无图案）、起点判定一直"认不出当前画面"、试读读到 0 行。
        // 真机实录（2026-09-24 00:16）：`帧几何关系 ⇒ 同向` 一切正常，但分数全 `-`、距上一新帧 101 秒。
        // ⚠ **不设"前台才拦"这个例外**（2026-09-24 第二版）：第一版写了"目标不在前台时不拦"，
        // 理由是"非前台全平属正常"。但真机录音显示本会话是**在游戏到前台之前**建立的
        // （`前台状态变化: NOT_FOREGROUND -> FOREGROUND` 出现在会话建立**之后**）⇒ 那张空帧照样进了缓存，
        // 之后画面静止、没有新帧 ⇒ 兜底一直重放它。**全平帧对识别永远没有价值**（无论前台与否），
        // 一律不缓存；代价只是"画面一直不动时缓存不建立、兜底轮什么也不做"——比重放空白诚实。
        if (FrameUniformity.sample(bytes).flat) {
            flatCacheSkips++
            if (!flatCacheWarned) {
                flatCacheWarned = true
                MmLog.w(
                    TAG,
                    "这一帧近乎全平（多是 resize 后还没被画上内容的空缓冲）⇒ **不覆盖兜底缓存**，" +
                        "宁可继续重放上一张有内容的帧，也不把空帧一直重放下去",
                )
            }
            return
        }
        lastFrameBytes = bytes
        lastFrameWidth = image.width
        lastFrameHeight = image.height
        lastFrameRowStride = plane.rowStride
        // 缓存副本的**拍摄时刻**（T4-9 事故后加）：兜底轮重放它时，判定用的就是这一张画面 ⇒
        // "这一轮算不算新画面"必须拿它比（见 [currentFrameAtMs]）。上面那道"全平不缓存"的 return
        // **故意不更新**它：缓存里还是上一张有内容的帧，时刻也应当还是上一张的。
        lastFrameBytesAtMs = nowMs
    }

    /**
     * 帧处理点（T1-4）：帧 → 灰度 → 识别循环 → 状态机；按 NFR-02 自适应调整节流间隔；
     * 单帧处理耗时按 T1-9 口径测量（灰度转换 + 识别循环，含滞回）。
     *
     * 非前台轮由识别循环内部冻结（§1.2 不识别、FR-09 不消耗滞回计数）；
     * 调用方保证本方法返回前 [image] 未被关闭（buffer 有效）。
     */
    private fun processFrame(image: Image) {
        // 帧看门狗：只有**新帧**会更新这里（静止兜底的重放不算），心跳日志据此说明"帧流还活着没"
        val nowMs = SystemClock.elapsedRealtime()
        lastNewFrameAtMs = nowMs
        // 界面层也要读"画面有多旧"（见 FrameFreshness）：停更时起点判定不能再说"认不出当前画面"
        FrameFreshness.note()
        // 新帧到了 ⇒ "我们刚改过屏幕、该来新帧"的期待已满足（见 checkFrameExpectation）
        FrameExpectationSignal.reached()
        // 同时解除"画面采集已停"那道点击闸：一帧新画面 ⇒ 眼睛又能看见了（自愈，不必重开会话）
        if (ClickDispatch.framesStalled) {
            ClickDispatch.setFramesStalled(false)
            MmLog.i(TAG, "画面采集已恢复（收到新帧）⇒ 解除「画面采集已停」的点击闸")
        }
        // 新帧到了 ⇒ "停更自救"的计数归零（以后再停更可以从头再试，见 checkSurfaceRetry）
        if (surfaceRetries != 0) {
            MmLog.i(TAG, "收到新帧 ⇒ 「换镜像表面」自救计数归零（上次共试了 $surfaceRetries 次）")
        }
        surfaceRetries = 0
        nextSurfaceRetryAtMs = 0L
        surfaceRetryGaveUp = false
        if (calibratedFrameWidth != 0 && !frameSizeWarned &&
            (image.width != calibratedFrameWidth || image.height != calibratedFrameHeight)
        ) {
            frameSizeWarned = true
            MmLog.i(
                TAG,
                "运行画布 ${image.width}x${image.height} 与标定帧 " +
                    "${calibratedFrameWidth}x$calibratedFrameHeight 方向不同（建会话时机不同），" +
                    "已按画面区几何归一后继续识别（T1-11c）",
            )
        }
        // 屏幕尺寸（T4-6：换算层已删，只剩越界判定要用它）：一有变化就推给点击通道。
        // 放在识别之前，保证本轮任何点击都用得上**当轮**的尺寸（转屏 / 重开会话都自愈）
        syncScreenSize()
        // 帧几何 × **屏幕**几何（2026-09-23）：会话建立时记一条，之后只在变化时再记 ——
        // "同向 / 互为转置 / 都不是"这三种关系决定了本轮走快路径还是方向归一，排障第一眼就要看它。
        noteFrameGeometry(image.width, image.height)
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val bytes = ingestFrameBytes(buffer)
        // 留一份像素给"画面静止时的兜底重放"（见 [IDLE_ROUND_INTERVAL_MS]）—— 拷进 B，不留 A（A 下一帧就被覆盖）
        lastFrameBytes = retainFrameBytes(bytes)
        lastFrameRowStride = plane.rowStride
        lastFrameBytesAtMs = nowMs
        // 本轮判定用的就是**这一帧**（刚拍到），如实记下它的时刻（见 [currentFrameAtMs]）
        currentFrameAtMs = nowMs
        currentFrameReplayed = false
        processPixels(bytes, image.width, image.height, plane.rowStride)
    }

    /**
     * 帧处理主体：窗口化灰度 → 识别循环 → 决策。
     *
     * 由两条路径共用（2026-09-22 拆出）：
     * - **新帧到达**（[handleFrameAvailable]）；
     * - **画面静止时的兜底重放**（[scheduleIdleRound]）—— 静止时系统不产生新帧，
     *   不兜底的话识别会整段停摆，状态与"最近命中"停在旧值（真机实录见 [IDLE_ROUND_INTERVAL_MS]）。
     */
    private fun processPixels(bytes: ByteArray, width: Int, height: Int, rowStride: Int) {
        val startNs = SystemClock.elapsedRealtimeNanos()
        // **T4-6 的安全网：产物几何 ≠ 运行帧 ⇒ 这一轮不判、不点。**
        // 方向归一拆掉后，"模板尺度成立"只剩一个前提：帧与标定同几何。不符时任何判定都是在错配的
        // 坐标系里猜，而点击会照着这个错误结果下发（真机最贵的一类错，NFR-05）。这里直接跳过识别：
        // **没有判定就没有点击**（红线 1），随后由 [checkGeometryGuard] 在 3 秒后如实结束会话。
        if (!frameGeometryMatchesCalibration(width, height)) {
            if (!calibrationGeometryWarned) {
                calibrationGeometryWarned = true
                MmLog.w(
                    TAG,
                    "采集帧 ${width}x$height 与标定产物 ${calibratedFrameWidth}x$calibratedFrameHeight 几何不一致 ⇒ " +
                        "本轮起不判不点（T4-6 起不再做方向归一）；请在游戏前台（横屏）重新建立采集会话",
                )
            }
            return
        }
        calibrationGeometryWarned = false
        val gray = try {
            // 窗口化灰度（T1-10b）：仅转换识别所需区域，区域外置零不影响判定；
            // 区域已是「标定窗口按画面区换算到运行帧」的结果（T1-11c），供归一化采样
            RgbaToGray.toGrayRegions(
                bytes,
                width,
                height,
                rowStride,
                recognitionLoop.windowRegions(width, height) +
                    anchorRegions(width, height),
            )
        } catch (t: RuntimeException) {
            // 预期外的帧布局 / 数据不足：跳过本轮（不进入滞回），记录备查（不静默失败）
            MmLog.w(TAG, "帧转换失败，跳过本轮识别：${t.javaClass.simpleName} ${t.message}")
            return
        }
        val grayNs = SystemClock.elapsedRealtimeNanos() - startNs

        val result = recognitionLoop.process(gray, ForegroundSignal.isForeground)
        recordProcessingCost(SystemClock.elapsedRealtimeNanos() - startNs, grayNs)
        recordSignalCosts()
        cyclesRun++
        if (!result.frozen) {
            // T2-1：窗口内「实际参与匹配的信号数」分布，用于自证"每轮只搜期望集合"
            searchedRounds++
            searchedSymbolTotal += result.searched.size
            if (result.searched.isEmpty()) {
                idleRounds++
            } else if (result.searched.size > 1) {
                multiSignalRounds++
            }
        }
        // T2-1：搜索集合变化（阶段推进 / 流程换步）记一行——真机复演时可直接看出
        // 「守护待命搜入口标志（启动页 / 活动弹窗）→ 命中后扩为弹窗期集合 → 命中大厅后收回」
        if (result.searched != lastSearchedNames) {
            lastSearchedNames = result.searched
            MmLog.i(TAG, "本轮搜索集合变化: " + searchedText(result.searched))
        }

        if (result.frozen != lastLoopFrozen) {
            lastLoopFrozen = result.frozen
            if (result.frozen) {
                // 离开前台时先记住"当时画面在不在刷新"（回前台时这条就是"投喂是否断了"的事件证据）
                framesFlowingBeforeLeave = lastNewFrameAtMs != 0L &&
                    SystemClock.elapsedRealtime() - lastNewFrameAtMs <= FRAME_FRESH_MS
            } else {
                // 回到前台：把证据交给 [FrameFreshness] ⇒ 若十几秒仍无新帧，停更判定与处置
                // （停点击 / 换镜像表面 / 提示重新授权）**提前**发生，见 [FOREGROUND_RETURN_STALL_MS] 的说明。
                FrameFreshness.noteForegroundReturn(framesFlowingBeforeLeave)
                if (framesFlowingBeforeLeave) {
                    MmLog.i(
                        TAG,
                        "目标回到前台（离开前画面在刷新）⇒ 若 ${FrameFreshness.FOREGROUND_RETURN_STALL_MS / 1000} 秒" +
                            "内没有新帧，按「投喂已断」提前处置（停点击 + 换镜像表面 + 提示重新授权）",
                    )
                }
            }
            MmLog.i(
                TAG,
                if (result.frozen) {
                    "识别循环冻结：目标不在前台，本轮不消耗滞回计数（FR-09）"
                } else {
                    "识别循环恢复：目标回到前台"
                },
            )
        }

        // NFR-02 自适应两档（T1-13）：只有「确实稳定」（预期命中 ∧ 无候选累积 ∧ 无转移）才用长间隔；
        // 未达预期 / 画面正在变 / 刚转移一律短间隔——历史判据 transition == null 会把"白等预期"错当稳定而降频。
        //
        // **跑号期间恒用快档**（2026-09-24 用户拍板，A 杠杆）：跑号期每一轮都要重新判定"等 / 点 / 推进"
        // （`PatrolRunner` 每轮一次），画面本来就不"确实稳定"；而真机日志里跑号期照样会掉进 500ms 稳定档
        // （18:09:56.288 / 58.851 / 59.570 / 18:10:06.746…），每一步白等 ≈0.3s、整条换号 ≈2s。
        // 判据取 [PatrolFlow.State.isRunning]：**中止 / 暂停后自动回到两档自适应**（那时确实该省）。
        //
        // **弹窗闭环进行中使用最快的间隔**（2026-09-28 起用快档，2026-09-29 收到 120ms）：补枪要等
        // "画面换了一幅"（`renderedFloorMs` = 350ms），而那个证据要到**下一轮**才可能被看到 ⇒
        // 轮询间隔直接变成补枪延迟的一部分。真机实录（01:00 那段，已上"画面变了就认账"）：
        // ```
        // 01:00:09.559 第 1 枪
        // 01:00:10.103 (+0.54s) 画面已换了一幅（39.9%），但只比上一枪晚 223ms（< 350ms）⇒ 再等一拍
        // 01:00:10.391 (+0.83s) 第 2 枪    ← 这一拍就是轮询间隔
        // ```
        // 停手（`gaveUp`）后不再需要它：那时我们已经不点了，让它回两档自适应省电。
        if (!result.frozen) {
            val patrolRunning = PatrolSession.current?.isRunning == true
            val closingPopup = !popupClose.gaveUp &&
                (popupClose.attempt > 0 || popupClose.awaitingVerification)
            val target = when {
                closingPopup -> POPUP_INTERVAL_MS
                result.settled && !patrolRunning -> STABLE_INTERVAL_MS
                else -> ACTIVE_INTERVAL_MS
            }
            if (target != lastAppliedIntervalMs) {
                MmLog.i(
                    TAG,
                    "节流间隔切换: ${lastAppliedIntervalMs}ms -> ${target}ms" +
                        when {
                            closingPopup -> "（弹窗闭环进行中：逐帧级轮询）"
                            patrolRunning -> "（跑号进行中：恒用快档）"
                            result.settled -> "（确实稳定轮）"
                            else -> "（变化 / 过渡 / 未达预期轮）"
                        },
                )
                lastAppliedIntervalMs = target
                frameThrottle.setInterval(target)
            }
        }

        result.transition?.let {
            // T1-13 ⑥ 取证：转移轮记录本轮实际搜索的信号名（补 T1-12 判据①⑤的取证缺口）
            MmLog.i(
                TAG,
                "状态转移: ${it.from.label} -> ${it.to.label}（${it.reason}）；本轮搜索 " +
                    searchedText(result.searched),
            )
            UiStateSignal.update(it.to, it.reason)
        }

        // 标定页刚写入产物 ⇒ 本条帧就地重载（2026-09-21）：标定与复测之间不必再"停采集 / 重开"。
        // 放在识别与决策**之前**：本轮就用新模板，用户改完立刻能在日志里看到新分数。
        val reloadTick = CalibrationReloadSignal.tick
        if (reloadTick != lastReloadTick) {
            lastReloadTick = reloadTick
            reloadArtifact()
        }

        // 「导入服务器」（2026-09-23 起；原「试读好友名」已于 2026-09-30 按用户口径整体移除）：
        // 悬浮窗点一次 → 就在**当前这一帧**上读一次，把结果挂回信号（面板每 500ms 轮询一次，
        // 看到就显示）。同一计数只消费一次。
        //
        // 与 [reloadArtifact] 一样**同步**跑：这是用户显式点的一次动作（不是每轮都做），
        // 耗时被它自己的等待上限圈住；用它的时候人正停在选服页上看着，不在跑号。
        // 结果由 [FriendListOcrTryout] 自己兜住异常 ⇒ 这里一定能拿到一句话回给面板。
        val ocrTryTick = FriendListOcrSignal.tick
        if (ocrTryTick != lastOcrTryTick) {
            lastOcrTryTick = ocrTryTick
            // **按"要做什么"分派**（现在只剩一种）：导入服务器 —— 只读产物里框选的
            // **「服务器列表区域」** → 解析 → 产出**复核草稿**（不落盘，等用户在复核页点「写入清单」
            // ——先看后写，2026-09-25 用户口径）。
            when (FriendListOcrSignal.mode) {
                FriendListOcrSignal.Mode.CAPTURE_SERVERS -> FriendListOcrSignal.doneCapture(
                    FriendListOcrTryout.captureServers(
                        this,
                        recognitionLoop.state,
                        // 本轮这张画面是**什么时候拍的**（新帧 ≈0；静止兜底重放则是缓存副本那一刻）
                        // ⇒ 添加最怕"读了一张旧画面"（2026-09-25 真机事故，见 FriendListOcrTryout）
                        SystemClock.elapsedRealtime() - currentFrameAtMs,
                        serverListArea(width, height),
                        bytes,
                        width,
                        height,
                        rowStride,
                    ),
                )

                //（`Mode.TRY_READ`「试读这一屏」已于 2026-09-30 按用户口径整体移除 —— 只留导入服务器）
            }
        }

        // FR-01 弹窗闭环（T2-4）：识别 → 点击 → 验证（非前台轮由决策器自行跳过）。
        // **原始像素一并传下去**：那里要算"自上一枪以来**整帧**变了多少"，而识别层的 `gray` 是窗口化的
        // （窗口外全 0），拿它比只会得出"换了弹窗也几乎没变"的错误结论（2026-09-28 踩过，见 FrameSignature）
        stepPopupClose(result, gray, bytes, width, height, rowStride)

        // 最近一轮的**原始命中**（未经滞回）：起点判定要用它兜住"画面刚变、结论还没跟上"的窗口
        //（真机 2026-09-22：关掉好友列表立刻点菜单，被误拦"现在停在「好友列表」"）
        UiStateSignal.noteHits(result.hits)

        // 跑号一步（T4-4b）：与 FR-01 同一条帧线程、同一个出口（ClickDispatch），但各自独立判定。
        // 弹窗在屏时编排器自己会"等"，所以两者不会抢同一帧去点不同的东西。
        stepPatrol(result, gray)

        // 节流基准回填到「本轮结束」（T1-13）：使间隔成为两轮之间的休息时间，不与单轮耗时叠加、也不退化。
        frameThrottle.markProcessedEnd(SystemClock.elapsedRealtime())
    }


    /**
     * **按名称定位那两步"点哪里"**（第 9 步 2026-09-21；第 5 步 2026-09-24）：本层算，编排层只消费。
     *
     * 分派的判据是**当前步骤**（不是画面）：两步的输入区域、命中规则、点击点都不一样 ——
     * - [serverPlanOf]（第 5 步）：选服页两列各读一次 → 取「区」之后**全等** → 点名字框中心；
     * - [friendPlanOf]（第 9 步）：好友列表读名称列 → 取括号内备注**全等** → 点行尾拜访图标。
     *
     * **本步已经下过击 ⇒ 不再做文字识别**（2026-09-24 真机取证后加的，见下），其余情况返回
     * [PatrolRunner.NamePlan.Waiting]，由编排层的耐心逻辑去等（不误判成失败）。
     */
    private fun namePlanOf(gray: GrayImage, state: UiState): PatrolRunner.NamePlan {
        val current = PatrolSession.current ?: return PatrolRunner.NamePlan.Waiting
        // **口径 A 在采集层的贯彻**：本步一旦下过击，编排层接下来**只等期望画面**（不重新定位、不补点）
        // ⇒ 这一轮就算读出一堆名字也**没有任何用处**，却要占住帧线程：第 5 步两列 ≈0.5s、第 9 步 ≈0.3s。
        //
        // 真机实证（2026-09-24 18:02:19.146 点下区服后）：画面已切到启动页，而滞回结论还挂在
        // 「服务器列表」≥2 轮，这两轮各白读一次两列 OCR（18:02:21.026 / 18:02:23.241，读到的是
        // 启动页上的字！），把"状态转移 → 第 5 步通过 → 第 6 步点击"整段往后推 ≈1s。
        //
        // 不担心"点漏了没人补"：口径 A 本来就禁止补点（补点的风险见 `PatrolRunner.waitOrAbort`），
        // 等到本步预算超时就如实中止。
        if (current.lastActionAtMs > 0) return PatrolRunner.NamePlan.Waiting
        // 第 5 步的跨屏扫描状态：不在这一步、或画面已不是选服页 ⇒ 复位（下次从"当前这一屏"重新开始）
        if (current.step != PatrolFlow.Step.PICK_SERVER || state != UiState.SERVER_SELECT) {
            serverScan.reset()
            lastServerScanNote = null
            lastServerRowYs = null
            serverScrollSinceRead = false
        }
        // 第 9 步的跨屏扫描状态：同样只在"正在这一步 + 画面确实是好友列表"时有效
        //（离开列表 / 点完拜访 / 换一步 ⇒ 复位；下次进列表重新从"当前这一屏"开始，再按口径回顶）
        if (current.step != PatrolFlow.Step.VISIT_FRIEND || state != UiState.FRIEND_LIST) {
            friendScan.reset()
            lastFriendScanNote = null
        }
        return when (current.step) {
            PatrolFlow.Step.PICK_SERVER -> serverPlanOf(gray, state)
            PatrolFlow.Step.VISIT_FRIEND -> friendPlanOf(gray, state)
            else -> PatrolRunner.NamePlan.Waiting
        }
    }

    /**
     * **顺序轮换的游标推进**（2026-09-24）：第 5 步**真换成功**（选服 → 之后的步骤）时，
     * 把"当前在哪个区服"记到盘上（`ServerRotation.saveCursor`）。
     *
     * 为什么在"通过"这一刻写、而不是在启动那一刻写：**成功才推进** —— 失败 / 中止时游标不动，
     * 用户重试仍指向同一个区服；启动时就写会让"没换成"被悄悄跳过去（表现是"有个号一直轮不到"）。
     *
     * 写盘很小（一次覆盖写）且每次换号只发生一次，就地写，不另起线程（免得又给"谁在读盘"添乱）。
     */
    private fun commitServerRotationIfSwitched(before: PatrolFlow.Step, after: PatrolFlow.State?) {
        if (before != PatrolFlow.Step.PICK_SERVER) return
        if (after == null || after.step == PatrolFlow.Step.PICK_SERVER) return
        if (!PatrolSession.targetServerFromRotation) return
        val name = PatrolSession.targetServer ?: return
        try {
            ServerRotation.saveCursor(this, name)
            MmLog.i(TAG, "顺序轮换：已换到「$name」，游标已推进（下次「换一个」从这个的后面取）")
        } catch (e: Exception) {
            // 游标写不进去不影响本次换号（下一次会退回"从第一条开始"，并留一行日志说明）
            MmLog.w(TAG, "顺序轮换：游标写盘失败（本次换号本身没受影响）：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 「没找到」时补一句**本轮到底读到了哪些名字**（2026-09-24 真机报障后加）。
     *
     * 为什么值得加：真机上「莲动渔舟」这次的表现是日志里读成 `微信395区 莲动渔`（**少最后一个字**，
     * 同一列里 `碧海之眼` 也被读成 `碧海之`），而用户看到的提示只有一句"没找到「莲动渔舟」"——
     * 分不清是"目标不在这一屏"、"名字被读缺了字"还是"框错了"。把名字段摊在提示里，一眼就能对上。
     *
     * **只用于提示与日志，不参与任何判定**：判定仍是"区号为主 + 区名为辅 + 唯一"（红线 3，不许猜）。
     * 名字段（`NameLocator.realmNameOf` 的取值口径）最多列 [NAME_HINT_LIMIT] 个，避免刷屏。
     */
    private fun readNamesHint(candidates: List<NameCandidate>): String {
        val names = candidates.map { NameLocator.realmNameOf(it.text) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (names.isEmpty()) return ""
        val shown = names.take(NAME_HINT_LIMIT).joinToString("、") { "「$it」" }
        val more = if (names.size > NAME_HINT_LIMIT) "…（共 ${names.size} 个）" else ""
        return "；本轮读到的名字段：$shown$more"
    }

    /**
     * 第 5 步（选择区服）**点哪里**（T4-9，2026-09-24）：按名称在服务器列表里定位目标区服。
     *
     * 任何一步落空都**如实说清是哪一环**（不猜位置，红线 3）：
     * 1. 目标区服名来自菜单（[PatrolSession.targetServer]）；**没带就停下说明**（"按清单换下一个"
     *    需要一个持久化游标，尚未实现 —— 绝不猜一个区服出来）；
     * 2. 取「服务器列表区域」当**纵向范围**、两条列竖带当**横向限制器**（`输入区域 = 列表区域 ∩ 该列`，
     *    与「好友名称列」同口径）：列右侧的大片空白不进 OCR ⇒ 真机实测耗时从 1109ms 降到 761ms；
     * 3. 命中判定 = **区号为主、区名为辅**（判据全在 [RealmPickDecision]）：区号（同一行文本里的数字、平台内唯一）
     *    先定；区号没读到/读错才退回"取「区」之后 + 全等"（[NameLocating.locateRealm] 那条旧口径）；
     *    **矛盾与歧义一律停下**（命中必须唯一）；
     * 4. 点**识别给出的那个框的中心**（哪一列读到就在哪一列点，不做"左右行配对"推断）。
     */
    private fun serverPlanOf(gray: GrayImage, state: UiState): PatrolRunner.NamePlan {
        if (state != UiState.SERVER_SELECT) return PatrolRunner.NamePlan.Waiting
        val target = PatrolSession.targetServer
            ?: return PatrolRunner.NamePlan.Failed(
                // 顺序轮换已在**启动前**解析成具体名字（`PatrolRequestConsumer.resolveNextServer`），
                // 所以走到这里说明那份解析没成（清单空 / 读不了）——如实说清，不猜一个区服
                "没指定要换到哪个区服（「顺序轮换」没能从服务器清单里算出下一个：清单是空的 / 读不了；" +
                    "详见 MM-Patrol 那一行日志）",
            )
        val data = calibrationData ?: return PatrolRunner.NamePlan.Failed("没有标定产物")
        val locator = anchorLocator ?: return PatrolRunner.NamePlan.Failed("产物里没有锚点")
        // ① 识别区域：「服务器列表区域」是**纵向**范围，两条列竖带是**横向**限制器（T4-9 口径）
        val areaId = data.anchorIdFor(UiState.SERVER_SELECT, PatrolAnchors.SERVER_LIST_AREA)
            ?: return PatrolRunner.NamePlan.Failed(
                "没框「服务器列表区域」—— 请在选服页框一次列表可见的那一片（用途选「列表区域」）",
            )
        val listArea = locator.windowInFrame(gray.width, gray.height, UiState.SERVER_SELECT, areaId)
            ?: return PatrolRunner.NamePlan.Failed("「服务器列表区域」的窗口读不出来")
        val uncalibrated = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val regions = mutableListOf<PixelBounds>()
        for (purpose in PatrolAnchors.serverListColumnAnchors[UiState.SERVER_SELECT].orEmpty()) {
            val columnId = data.anchorIdFor(UiState.SERVER_SELECT, purpose)
            val column = columnId?.let {
                locator.windowInFrame(gray.width, gray.height, UiState.SERVER_SELECT, it)
            }
            if (column == null) {
                // 没框 / 窗口读不出来 ⇒ 只用框了的那条，并在日志里明说（不猜、不退回整块）
                uncalibrated += purpose
                continue
            }
            val region = NameLocating.visitRanges(listArea, column).ocrRegion
            if (region.isEmpty) {
                // 两框**横向**没对上（框错了）⇒ 如实停下：不夹成零宽列、也不退回整块去凑（红线 3 同口径）
                mismatched += purpose
                continue
            }
            regions += region
        }
        if (mismatched.isNotEmpty()) {
            return PatrolRunner.NamePlan.Failed(
                "「${mismatched.joinToString("、")}」的左右边界与「服务器列表区域」没有交集 —— 请检查这两个框" +
                    "（列**只取横向**：左右应落在列表区域之内；纵向不用管）",
            )
        }
        if (regions.isEmpty()) {
            return PatrolRunner.NamePlan.Failed(
                "两条列竖带都没框（${uncalibrated.joinToString("、")}）—— 请在选服页各框一条" +
                    "（**只取横向**：框住名字文字的左右边界即可，纵向随便框；框一次，与区服无关）",
            )
        }
        // 这块区域在屏幕上**看不见**（是算出来的）⇒ 一变就打一行，便于真机复核"框了到底生效没"
        val regionKey = regions.joinToString("|") { "${it.x0},${it.x1},${it.y0},${it.y1}" }
        if (regionKey != lastServerRegionsLogged) {
            lastServerRegionsLogged = regionKey
            MmLog.i(
                TAG,
                "第 5 步 OCR 输入区域：" +
                    regions.joinToString("；") { "x=[${it.x0},${it.x1}) y=[${it.y0},${it.y1})" } +
                    if (uncalibrated.isEmpty()) {
                        "（= 列表区域 × 左/右列竖带：各读一次，取「区」之后全等）"
                    } else {
                        "（未框：${uncalibrated.joinToString("、")}）"
                    },
            )
        }
        // ② 读文字（两列各读一次后合并；节流 + 复用见 [readNames]）
        val reading = readNames(gray, PatrolFlow.Step.PICK_SERVER, regions)
        val candidates = reading.candidates
        if (candidates.isEmpty()) {
            // 复用结果读空不算"没读到文字"（这一帧根本没再读）→ 只说"还在找"，不计失败
            return if (reading.fresh) {
                PatrolRunner.NamePlan.Failed(
                    "文字识别在服务器列表的输入区域里没读到任何文字" +
                        "（「服务器列表区域」/ 两条列竖带框得对不对？）",
                )
            } else {
                PatrolRunner.NamePlan.Waiting
            }
        }
        // **量一下这次滑动移动了多少**（只记日志，2026-09-29 用户追问"列表很长时怎么保证不漏"）：
        // 判"到顶 / 到底"用的是**重合度**，它只回答"变没变"；列表很长时唯一的漏找风险是
        // "一次滑动**越过一整屏**（中间那一屏从没被读过）"，而两屏**共同行**的纵坐标之差就是直接证据。
        val rowYs = rowYsOf(candidates)
        if (reading.fresh && serverScrollSinceRead) {
            serverScrollSinceRead = false
            lastServerRowYs?.let { previous ->
                val moved = ServerListScan.displacementOf(previous, rowYs)
                if (moved == null) {
                    MmLog.w(
                        TAG,
                        "第 5 步滑动位移：两屏**没有共同行**（可能整屏跳过、也可能读数花）" +
                            "——列表变长时这一行就是「有没有漏屏」的证据（位移 ≥ 列表区域高 ⇒ 漏屏）",
                    )
                } else {
                    val rows = (kotlin.math.abs(moved.deltaY) / 93).toInt()
                    MmLog.i(
                        TAG,
                        "第 5 步滑动位移: Δ=${String.format(Locale.US, "%.0f", moved.deltaY)}px" +
                            "｜共同行 ${moved.sharedRows} 条（行高 ≈93px ⇒ 约 $rows 行；" +
                            "≥ 列表区域高就说明**越过了一整屏**）",
                    )
                }
            }
        }
        if (reading.fresh) lastServerRowYs = rowYs

        // ③ 判据：**区号为主、区名为辅**（2026-09-25 用户口径）——判据全在 [RealmPickDecision]：
        //    区号（数字、平台内唯一）先定；区号没读到/读错才退回"区名全等 + 唯一"；矛盾与歧义一律停下。
        //    为什么区号当主：真机实测数字那一栏 **12/12 行全对**，而汉字区名会丢字/多字/形近错（第 208 条）。
        return when (val judged = realmPlanOf(candidates, target)) {
            is RealmPickDecision.Result.Hit -> {
                // **读到 ≠ 现在还在那儿**（2026-09-29 真机事故）：列表滑完还会惯性 / 回弹一下，
                // 命中后必须用**两次一致的新读**确认这一行没再动才允许下手 —— 否则点到相邻的另一个区服
                // （实录：目标「黑砂流瀑」读到在 (760, 1258)，实际点中的是「机仆阵列」）。
                //
                // ⚠ **命中这一支也必须"新读才算数"**（2026-09-29 用户又报"选第二屏最后一行「奇迹瀑布」、
                // 点到第一屏最后一行「漠地绿洲」"后补上的一道门）：下面 Miss 支本来就有 `reading.fresh`
                // 这道门，Hit 支漏了 ⇒ `readNames` 的 1 秒节流**复用同一份候选**时，
                // `onHit` 看到的是"同一个 y（差 0 ≤ 16px）+ 更新的帧时刻" ⇒ 判成"两次读数一致"、
                // 直接点 —— 而那个 y 来自最多 1 秒前那张画面，列表回弹一个步长（实测 Δ≈185px ≈ 2 行）
                // 恰好把"第二屏最后一行"打成"第一屏最后一行"（第 245 条同型，那次是 815ms 后下手）。
                // 现在：**复用轮不下结论**，等下一次真读（节流 1s ⇒ 最多多等 ≈1s，单步预算 15s 足够）。
                if (!reading.fresh) {
                    MmLog.i(TAG, "第 5 步命中（${judged.via}）但本轮是**复用**的识别结果 ⇒ 不下结论，等一次真读")
                    return PatrolRunner.NamePlan.Waiting
                }
                val row = judged.row
                val rowKey = row.number ?: row.name.filterNot { it.isWhitespace() }
                val stable = serverScan.onHit(rowKey, row.centerY, currentFrameAtMs)
                // **开火前复眼**（2026-09-30 用户口径"现在补复眼"）：稳定了还不够 —— 动手之前再拿
                // **现取的最新画面**确认"那一行还在原地"（判据与理由见 [rowFireRecheck]）。
                // 不做这一步的话，"读到"与"手指落下"之间那段（最坏 ≈1s）里列表一回弹就会点到隔壁区服。
                val recheck = if (stable) rowFireRecheck(row, regions) else null
                MmLog.i(
                    TAG,
                    "第 5 步命中（${judged.via}）：区号「${row.number ?: "—"}」｜名字段「${row.name}」" +
                        "｜原文「${row.line}」｜在 (${row.centerX.toInt()}, ${row.centerY.toInt()})" +
                        (if (stable) {
                            "｜两次读数一致 ⇒ 可以点"
                        } else {
                            "｜刚读到，再确认一帧（防列表还在动）"
                        }) +
                        (recheck?.note.orEmpty()) +
                        "｜帧：${frameTag()}",
                )
                if (!stable) return PatrolRunner.NamePlan.Waiting
                if (recheck?.allowed == false) {
                    // **拦下 ≠ 失败**：这一枪不发、等下一轮重新读（不 reset 扫描状态、不消耗重试次数、
                    // 也不报 Failed —— 那会走 `PatrolFlow.fail` 计重试，几轮就把这一步耗成中止）。
                    // 同一句话只记一次（列表持续在动时会每轮成立，逐轮记等于刷屏）。
                    if (recheck.note != lastRowFireNote) {
                        lastRowFireNote = recheck.note
                        MmLog.w(TAG, "第 5 步 复眼否决：${recheck.note}｜本轮不点，等下一轮重新读")
                    }
                    return PatrolRunner.NamePlan.Waiting
                }
                lastRowFireNote = null
                serverScan.reset() // 动手：这一段跨屏扫描结束（下次跑第 5 步从头来）
                // ④ 点**识别给出的名字框中心**（哪一列读到就点哪一列；不推断另一列、不做左右行配对）
                PatrolRunner.NamePlan.Click(
                    frameX = row.centerX,
                    frameY = row.centerY,
                    detail = "区号「${row.number ?: "—"}」/ 区名「${row.name}」在 " +
                        "(${row.centerX.toInt()}, ${row.centerY.toInt()})",
                )
            }

            // 复用结果不下结论：重试要花在**新的识别结果**上
            is RealmPickDecision.Result.Miss -> if (!reading.fresh) {
                PatrolRunner.NamePlan.Waiting
            } else {
                // **这一屏没有 ⇒ 问扫描器：回顶 / 继续往下 / 到底了就停下**（2026-09-29，硬性要求 #4）。
                // 注意：**不**在这里报 Failed —— 那会走 `PatrolFlow.fail` 计重试，3 屏就把这一步耗成中止；
                // 真正的"找不到"由 [ServerListScan] 在"滑动后画面不再动"或超上限时给出。
                val scanStep = serverScan.onMiss(rowKeysOf(candidates), SystemClock.elapsedRealtime())
                // 扫描的每一拍都留一句**依据**（用户 2026-09-29 追问："为什么滑到顶后还滑了好多下" ——
                // 原来只有"目标不在这一屏"这一句，看不出它为什么认为还没到顶）
                val scanNote = serverScan.lastDecisionNote
                if (scanStep !is ServerListScan.Step.Wait && scanNote != lastServerScanNote) {
                    lastServerScanNote = scanNote
                    MmLog.i(TAG, "第 5 步扫描: $scanNote｜帧：${frameTag()}")
                }
                when (scanStep) {
                    ServerListScan.Step.Wait -> PatrolRunner.NamePlan.Waiting
                    ServerListScan.Step.ScrollTowardsTop ->
                        scrollPlanOf(listArea, towardsTop = true, step = PatrolFlow.Step.PICK_SERVER)

                    ServerListScan.Step.ScrollDown ->
                        scrollPlanOf(listArea, towardsTop = false, step = PatrolFlow.Step.PICK_SERVER)
                    is ServerListScan.Step.GiveUp -> PatrolRunner.NamePlan.Failed(
                        "${scanStep.reason}｜帧：${frameTag()}｜" + judged.detail + readNamesHint(candidates),
                    )
                }
            }
        }
    }

    /**
     * 第 5 / 9 步跨屏查找要滑的那一下（**运行帧坐标**）：在框选的列表区域里纵向拖一段。
     *
     * **几何与方向口径都在纯逻辑 [NameLocating.scrollDragOf] 里**（2026-10-01 T4-10 刀 1 抽出去，含单测：
     * "手指向下划 = 回顶、向上划 = 往下找"这条**极容易写反**的口径由单测钉住）；这里只补一句给用户看的
     * 说明文案 + 传本类的调参常量（[SCROLL_EDGE_RATIO] / [SCROLL_DRAG_MS]，真机调这两个数）。
     */
    private fun scrollPlanOf(
        listArea: PixelBounds,
        towardsTop: Boolean,
        step: PatrolFlow.Step,
    ): PatrolRunner.NamePlan.Scroll {
        // **第 9 步（好友列表）的滑动更短、更慢**（2026-10-01 真机实测，见 [FRIEND_SCROLL_EDGE_RATIO]）：
        // 同样 546px 的手指位移，在好友列表里内容**走了一屏多**（日志实录"与上一屏重合 0/3 行（0%）"
        // ⇒ 目标好友容易被整屏跳过）；而 300ms 太快还会被当甩动。
        val friend = step == PatrolFlow.Step.VISIT_FRIEND
        val drag = NameLocating.scrollDragOf(
            listArea = listArea,
            towardsTop = towardsTop,
            edgeRatio = if (friend) FRIEND_SCROLL_EDGE_RATIO else SCROLL_EDGE_RATIO,
            durationMs = if (friend) FRIEND_SCROLL_DRAG_MS else SCROLL_DRAG_MS,
        )
        return PatrolRunner.NamePlan.Scroll(
            fromFrameX = drag.fromX,
            fromFrameY = drag.fromY,
            toFrameX = drag.toX,
            toFrameY = drag.toY,
            durationMs = drag.durationMs,
            detail = when {
                towardsTop -> "向上滚回列表顶部（手指向下划）"
                friend -> "向下滚一段继续找（手指向上划）"
                else -> "向下滚一屏继续找（手指向上划）"
            },
        )
    }

    /**
     * 这一屏读到的东西的**行键**（判"滑了一屏画面到底动没动"）。
     *
     * ## 为什么**优先取数字**
     *
     * 真机实录（2026-09-29 02:17，用户报"滑到顶后还滑了好多下"）：列表本来就在顶部，可**同一屏连读三次**
     * 行名一直在抖（`Lv.9T` / `Ly.9T`、`Lv.25v` / `Lv.25y`、`Lv.6T` / `Lv.6日`）⇒ 行键跟着名字走的话，
     * "这一屏与上一屏是不是同一屏"永远判不出来 ⇒ 回顶阶段一路滑到上限。
     * 而数字那一栏是**实测 12/12 行全对**的（`docs/progress.md` 第 208 条）⇒ 行键优先用数字，
     * 没有数字的行才退回名字段（[ServerListScan.SAME_SCREEN_OVERLAP] 再容忍那部分抖动）。
     *
     * 为什么不用整帧像素差：列表上的选中高亮 / 滚动条 / 头像动效都会让像素乱动。
     */
    private fun rowKeysOf(candidates: List<NameCandidate>): Set<String> = rowYsOf(candidates).keys

    /**
     * 同上的行键 → **纵坐标**（2026-09-29 加）：只给"这次滑动移动了多少像素"这条**只记日志**的测量用
     * （见 [ServerListScan.displacementOf] 与 [serverScrollSinceRead]）。
     */
    private fun rowYsOf(candidates: List<NameCandidate>): Map<String, Double> = candidates.associate { candidate ->
        // 行键的规则**上移到纯逻辑**（`ServerListScan.rowKeyOf`）：开火前复眼（[rowFireRecheck]）也要用
        // 同一把键认身份 —— 判据只写一处，免得两处各自漂移
        ServerListScan.rowKeyOf(candidate.text) to candidate.centerY
    }

    /** 开火前复眼的结果：是否放行 + 一行说明（放行时的说明追加在「第 5 步命中」那行后面）。 */
    private data class RowFireVerdict(val allowed: Boolean, val note: String)

    /** 第 5 步复眼连续被拦时"同一句话只记一次"（列表持续在动时会每轮成立，逐轮记等于刷屏）。 */
    private var lastRowFireNote: String? = null

    /** 第 9 步复眼连续被拦时的"同一句话只记一次"（同上，2026-10-01 刀 3）。 */
    private var lastFriendRowFireNote: String? = null

    /**
     * **第 5 步的开火前复眼**（2026-09-30 用户口径"现在补复眼"；判据本体是纯逻辑
     * [ServerListScan.stillOnSameRow]，有单测）：下发这一击之前**现取一张当时最新的画面**，
     * 只在**目标那一行所在的那条窄带**上再读一次，确认"还是同一行、还在原来那条纵线上"。
     *
     * ## 它挡的是什么（真机两次事故同型：progress 第 245 / 259 条）
     *
     * `ServerListScan.onHit` 的"两次读数一致"只保证"**读**的这两次是同一行"，而"读到"到"手指落下"
     * 之间还有一段（帧龄 + 单轮耗时 + 点击门禁的间隔等待，最坏 ≈1 秒）；列表在这段里**回弹一行**，
     * 坐标就落到**相邻的另一个区服**上（真实录：读到 (760,1258)，815ms 后手指落下时那一行已移开约一行）。
     * 与活动弹窗那次"判定用的画面比屏幕旧"是同一类：**动手依据必须是"当下"的画面**。
     *
     * ## 与 FR-01 那道复眼：同一类防线，判据不同
     *
     * FR-01 复核的是"某个**锚点模板**还在不在原处"（[preFireRecheck] + [PreFireRecheck]）；
     * 第 5 步点的是 **OCR 找到的列表行**、没有模板可匹配 ⇒ 复核方式 = **在最新帧上重读那一行**，
     * 用同一把**行键**（[ServerListScan.rowKeyOf]）认身份、同一个容差（[ServerListScan.ROW_STABLE_PX]）认位置。
     *
     * ## 三条口径（与 FR-01 刻意保持一致）
     *
     * - **取不到更新的帧 ⇒ 本条不参与**（`acquireLatestImage()` 给 null）：平台**只在内容变化时才产帧**
     *   （"静止兜底重放"正是因为这条而存在）⇒ 队列里没有新帧 = 屏幕自我们那张画面之后**没变过**，
     *   交给原有那几道闸判即可；
     * - **只读那一行所在的窄带**（[rowRecheckBand]）：左右沿用第 5 步原来那条**列竖带**，纵向收到目标行
     *   上下各 [ROW_RECHECK_HALF_PX] —— 比整列便宜得多（1119px 高的列 vs ≈112px 的带）；
     * - **不走 [readNames]**：那条路有"1 秒窗口内复用上次结果"的节流，而复眼必须**真读当下这一帧**
     *   （复用等于拿刚才那份候选自己确认自己，等于没查）。
     *
     * ⚠ **拦下不等于失败**：调用方按"这一枪不发、等下一轮重新读"处理（见 [serverPlanOf] 里的分支注释）。
     * 判据若在真机上过严（总被拦），日志每轮都会有说明 —— 届时调 [ROW_RECHECK_HALF_PX] 或这道闸的去留
     * 都按真机数据定，不靠猜。
     */
    private fun rowFireRecheck(
        row: RealmPickDecision.Row,
        regions: List<PixelBounds>,
    ): RowFireVerdict {
        val fresh = acquireFreshFrame() ?: return RowFireVerdict(true, "｜复眼：无更新帧（屏幕未变）")
        val band = rowRecheckBand(row, regions)
            ?: return RowFireVerdict(true, "｜复眼：算不出复核窄带（本条不参与）")
        val freshGray = RgbaToGray.toGrayRegions(
            fresh.bytes,
            fresh.width,
            fresh.height,
            fresh.rowStride,
            listOf(band),
        )
        val freshYs = rowYsOf(nameReader.read(freshGray, band))
        val key = ServerListScan.rowKeyOf(row.line)
        if (ServerListScan.stillOnSameRow(key, row.centerY, freshYs)) {
            return RowFireVerdict(true, "｜复眼 ✓（现取最新帧上那一行还在原地）")
        }
        val where = freshYs[key]?.let { "挪到了 y=${it.toInt()}" } ?: "已经不在了"
        return RowFireVerdict(
            false,
            "开火前复眼：**现取的最新画面**上那一行（行键 $key）$where（原 y=${row.centerY.toInt()}）" +
                "——列表动过，这一枪不发（宁可多等一轮，也不点到隔壁的区服）",
        )
    }

    /**
     * **第 9 步的开火前复眼**（2026-10-01，T4-10 刀 3；判据本体仍是纯逻辑
     * [ServerListScan.stillOnSameRow]，有单测）：下发这一击之前**现取一张当时最新的画面**，
     * 只在**那一行所在的行带**上再读一次，确认"还是同一个备注名、还在原来那条纵线上"。
     *
     * 与第 5 步那道（[rowFireRecheck]）是**同一类防线、两套字段**：区服认身份用"区号优先"的行键，
     * 好友认身份用**括号里的备注名**（[NameLocating.friendRowKeyOf]）。它挡的是同一件事 ——
     * `ServerListScan.onHit` 那种"两次读数一致"只保证"**读**的这两次是同一行"，而"读到"到"手指落下"
     * 之间还有最坏 ≈1 秒（帧龄 + 单轮耗时 + 点击门禁的间隔等待），列表在这段里**回弹一行**，
     * 坐标就落到**相邻的另一个好友**上（第 5 步真机实录：读到 (760,1258)，815ms 后那一行已移开约一行）。
     *
     * 三条口径与第 5 步刻意保持一致：
     * - **取不到更新的帧 ⇒ 本条不参与**（`acquireLatestImage()` 给 null）：平台只在内容变化时才产帧
     *   ⇒ 队列里没有新帧 = 屏幕自我们那张画面之后**没变过**；
     * - **只读那一行所在的行带**（[NameLocating.rowBandOf]：纵向 ±1 倍名字高 ⇒ 真机上 ≈45px 高、
     *   明显小于 ≈83px 的行距 ⇒ 列表整体挪一行时，那一行的字已经落在带外，正是该拦的情形）；
     * - **不走 [readNames]**：那条路有"1 秒窗口内复用上次结果"的节流，复眼必须**真读当下这一帧**。
     *
     * ⚠ **拦下不等于失败**：调用方按"这一枪不发、等下一轮重新读"处理（见 [friendPlanOf]）；
     * 取不到行键（备注名读不出）时本函数返回 null（= 本条不参与），不因此拦下。
     */
    private fun friendFireRecheck(
        hit: NameCandidate,
        ranges: NameLocating.VisitRanges,
        frameHeight: Int,
    ): RowFireVerdict? {
        // **认身份用"全部可接受键"**（第一把=括号里那一段，第二把=括号前去噪；见
        // [NameLocating.friendRowKeyAliasesOf]）：任一把在窄带里命中同一纵线 ⇒ 放行。
        // 这样即使窄带只读到半个括号（`、星月晚. (星月` ⇒ 括号字段成半截），也不会白等一轮
        // （2026-10-01 就是正解对不上、连否 55 秒）。
        val keys = NameLocating.friendRowKeyAliasesOf(hit).toList()
        if (keys.isEmpty()) return null
        val fresh = acquireFreshFrame() ?: return RowFireVerdict(true, "｜复眼：无更新帧（屏幕未变）")
        val band = NameLocating.rowBandOf(hit, ranges.rowX0, ranges.rowX1, frameHeight)
        val freshGray = RgbaToGray.toGrayRegions(
            fresh.bytes,
            fresh.width,
            fresh.height,
            fresh.rowStride,
            listOf(band),
        )
        val freshYs = NameLocating.friendRowYsOf(nameReader.read(freshGray, band))
        val matched = keys.firstOrNull { ServerListScan.stillOnSameRow(it, hit.y.toDouble(), freshYs) }
        if (matched != null) {
            return RowFireVerdict(true, "｜复眼 ✓（现取最新帧上「$matched」那一行还在原地）")
        }
        val primary = keys.first()
        val where = freshYs[primary]?.let { "挪到了 y=${it.toInt()}" } ?: "已经不在了"
        return RowFireVerdict(
            false,
            "开火前复眼：**现取的最新画面**上那一行（键「${keys.joinToString("／")}」）$where（原 y=${hit.y}）" +
                "——列表动过，这一枪不发（宁可多等一轮，也不点到隔壁的好友）",
        )
    }

    /**
     * 复眼要读的那条**窄带**（运行帧坐标）：左右沿用**目标行所在的那条列竖带**（列只当横向限制器，
     * 与第 5 步的输入区域同一条口径），纵向 = 目标行中心 ± [ROW_RECHECK_HALF_PX]，再夹进该列的纵向范围。
     *
     * 半高取 56px 的依据：真机**行距** ≈90px（列表区域 1119px / 12 行），而一行文字自身的框高 ≈40px
     * ⇒ ±56 装得下这一行、又明显小于一个行距 ⇒ **列表整体挪一行时，目标行的文字已经落在带外**
     * （那正是复眼该拦的情形）。
     */
    private fun rowRecheckBand(row: RealmPickDecision.Row, regions: List<PixelBounds>): PixelBounds? {
        val column = regions.firstOrNull { row.centerX >= it.x0 && row.centerX <= it.x1 }
            ?: regions.firstOrNull()
            ?: return null
        val y0 = (row.centerY - ROW_RECHECK_HALF_PX).toInt().coerceAtLeast(column.y0)
        val y1 = (row.centerY + ROW_RECHECK_HALF_PX).toInt().coerceAtMost(column.y1)
        if (y1 <= y0) return null
        return PixelBounds(column.x0, y0, column.x1, y1)
    }

    /**
     * 把"本屏读数 + 清单里的目标条目"交给 [RealmPickDecision] 判**点哪一行**（2026-09-25）。
     *
     * ## 判据：**区号为主、区名为辅**（用户口径）
     *
     * 真机实测（`docs/progress.md` 第 208 条）：同一屏里**数字那一栏 12/12 行全对**，而汉字区名会
     * 丢字 / 多字 / 形近错 ⇒ 区号（平台内唯一）当主判据，区名只做辅证/兜底。
     * 全部分支与拒识条件都写在 [RealmPickDecision] 里（本函数只负责"取数据"，不写判据）。
     *
     * ## 区号从哪来：**同一行文本里的数字**
     *
     * 不是模板 —— 离线采的那套数字模板已按用户口径**整体移除**（第 209 条）：实测它对读数字没有贡献，
     * 而文本里的数字读得又准又全（零成本、零维护）。
     *
     * 清单读不了 / 清单里没有这条目标 ⇒ 返回一句 Miss（调用方照常"停下并提示"，不猜）。
     */
    private fun realmPlanOf(candidates: List<NameCandidate>, target: String): RealmPickDecision.Result {
        val list = try {
            ServerListStore.load(this)
        } catch (t: Throwable) {
            MmLog.w(TAG, "服务器清单读不了（第 5 步没法判）：${t.message}")
            null
        } ?: return RealmPickDecision.Result.Miss("服务器清单读不了（第 5 步没法判）")
        val index = list.indexOfServerName(target)
        if (index < 0) {
            return RealmPickDecision.Result.Miss(
                "服务器清单里没有「$target」这一条（「顺序轮换」算出来的名字与清单对不上）",
            )
        }
        val entry = list.entries[index]
        val others = list.entries.filterIndexed { i, _ -> i != index }
            .map { RealmPickDecision.Entry(name = it.serverName, number = it.serverNo) }
        val rows = candidates.map {
            RealmPickDecision.Row(line = it.text, centerX = it.centerX, centerY = it.centerY)
        }
        return RealmPickDecision.decide(
            rows = rows,
            target = RealmPickDecision.Entry(name = entry.serverName, number = entry.serverNo),
            others = others,
        )
    }

    /**
     * 第 9 步（拜访好友）**点哪里**（2026-09-21）：按名称定位的落地实现。
     *
     * 三步走，任何一步落空都**如实说清是哪一环**（不猜位置，红线 3）：
     * 1. 取「列表区域」并按「好友名称列」的左右边界裁一次（各框一次；引擎需要一块明确输入）；
     * 2. 读文字 → 取**括号里的备注名**与目标**全等** → 拿到它所在的**那一行**；
     * 3. 在这一行内定位行尾的 `friend_visit` 图标 → 得到点击点。
     *
     * 只在"第 9 步 + 画面确认是好友列表"时计算；其余情况返回 [PatrolRunner.NamePlan.Waiting]，
     * 由编排层的耐心逻辑去等（不误判成失败）。
     */
    private fun friendPlanOf(gray: GrayImage, state: UiState): PatrolRunner.NamePlan {
        if (state != UiState.FRIEND_LIST) return PatrolRunner.NamePlan.Waiting
        val target = PatrolSession.targetFriend
            ?: return PatrolRunner.NamePlan.Failed("没指定要拜访的好友名（预设里没配好友）")
        val data = calibrationData ?: return PatrolRunner.NamePlan.Failed("没有标定产物")
        val locator = anchorLocator ?: return PatrolRunner.NamePlan.Failed("产物里没有锚点")
        // 文字识别尚未通过真机精度验收时**不启用**（FR-04 硬性要求 2：不满足前置条件就停，不做"试试看"）
        if (!NameLocating.ENABLED) {
            return PatrolRunner.NamePlan.Failed("按名称定位尚未启用（文字识别未通过真机精度验收）")
        }
        // ① 识别区域：「列表区域」按「好友名称列」的**左右边界**裁一次（各框一次；引擎需要一块明确输入，
        //    理由见 PatrolAnchors.FRIEND_LIST_AREA / FRIEND_LIST_NAME_COLUMN）
        //
        //    **窗口换算走 `locator.windowInFrame`**（2026-09-22 真机修）：标定窗口比例是相对标定画布定义的，
        //    直算会把比例乘到错的轴上 ⇒ OCR 区域框错位置；`windowInFrame` 与模板匹配走同一条换算路径，
        //    保证坐标系一致（T4-6 之后帧与标定同几何，这条换算已退化为恒等，入口保持不变）。
        val areaId = data.anchorIdFor(UiState.FRIEND_LIST, PatrolAnchors.FRIEND_LIST_AREA)
            ?: return PatrolRunner.NamePlan.Failed(
                "没框「列表区域」—— 请在好友列表里框一次列表可见的那一片（用途选「列表区域」）",
            )
        val listArea = locator.windowInFrame(gray.width, gray.height, UiState.FRIEND_LIST, areaId)
            ?: return PatrolRunner.NamePlan.Failed("「列表区域」的窗口读不出来")
        // 「好友名称列」（2026-09-22 用户口径，**可选**）：只把名字这一列送去读文字。行首的头像与装饰
        // 会被引擎读成文字（真机实录 `价`/`新`/`愈` 这类前缀），噪声块还会让"按行去重 / 行带"变脆。
        // **只取它的左右边界**（T4-3g）：纵向一律用「列表区域」⇒ 用户框它时不必与列表区域上下齐平。
        // 没框过 → 退回整片列表区域（不裁列），由下面的日志明说；**不自动猜一条列出来**。
        val nameColumn = data.anchorIdFor(UiState.FRIEND_LIST, PatrolAnchors.FRIEND_LIST_NAME_COLUMN)
            ?.let { id -> locator.windowInFrame(gray.width, gray.height, UiState.FRIEND_LIST, id) }
        val ranges = NameLocating.visitRanges(listArea, nameColumn)
        val region = ranges.ocrRegion
        // 两框**横向**没对上（交集为空）⇒ 如实停下说清楚，不拿整片区域去凑（"没对上"与"没标定"是两回事）
        if (region.isEmpty) {
            return PatrolRunner.NamePlan.Failed(
                "「好友名称列」的左右边界与「列表区域」没有交集 —— 请检查这两个框" +
                    "（名称列的左右应落在列表区域之内；**纵向不用管**，不参与判断）",
            )
        }
        // 这块区域在屏幕上**看不见**（是算出来的）⇒ 区域一变就打一行，便于真机复核"框了到底生效没"
        if (lastOcrRegionLogged != region) {
            lastOcrRegionLogged = region
            MmLog.i(
                TAG,
                "第 9 步 OCR 输入区域：x=[${region.x0},${region.x1}) y=[${region.y0},${region.y1})" +
                    if (nameColumn == null) {
                        "（只框了「列表区域」，未框「好友名称列」⇒ 行首头像/装饰会一起送进 OCR）"
                    } else {
                        "（= 列表区域 × 好友名称列：只取名称列的左右边界，纵向用列表区域）"
                    },
            )
        }
        // ② 读文字（引擎侧自己会放大；失败/超时返回空列表，按"未找到"处理，不猜；节流 + 复用见 [readNames]）
        val reading = readNames(gray, PatrolFlow.Step.VISIT_FRIEND, listOf(region))
        val candidates = reading.candidates
        if (candidates.isEmpty()) {
            // 复用结果读空不算"没读到文字"（这一帧根本没再读）→ 只说"还在找"，不计失败
            return if (reading.fresh) {
                PatrolRunner.NamePlan.Failed(
                    "文字识别在输入区域里没读到任何文字（「列表区域」/「好友名称列」框得对不对？）",
                )
            } else {
                PatrolRunner.NamePlan.Waiting
            }
        }
        // ③ **取括号内备注 + 全等**（2026-09-24 用户口径，见 `docs/progress.md` 第 183 条）：
        //    配的名字 = 游戏中的备注名 ⇒ 只比括号里那一段；读歪一个字 / 括号读坏就是"未找到"。
        //
        // ⚠ **命中 / 未命中都要"新读才算数"**（与第 5 步同一条门，2026-09-29 同型事故）：`readNames` 有 1 秒
        //    节流，复用来的候选可能来自最多 1 秒前那张画面 —— 那时列表若回弹了一行，命中的就是隔壁那一行。
        if (!reading.fresh) return PatrolRunner.NamePlan.Waiting
        val hit = (NameLocating.locateFriend(target, listOf(candidates)) as? NameLocator.Result.Found)?.payload

        // ④ **命中就采信**（用户 2026-10-01 二次拍板："**每次都滑到顶再找有点费事，改为在当前屏先找一次，
        //    找不到再滑到顶慢慢找**"）⇒ 行带 → 行尾拜访图标 → 点击；**不再要求"先确认在列表顶部"**。
        //
        //    📌 口径变更（同日两版，以本版为准）：前一版要求"命中前必须先回顶"（`atTopConfirmed`），
        //    为的是"同名好友取**整个列表**的最上面"。代价是**每次都白滑 1~2 秒**（用户体感："有点费事"）。
        //    现在按"**当前屏先找，命中即点**"，只在**当前屏没有**时才回顶、再自上而下找。
        //    ⇒ 放弃的是"**全局**最上面"这一点：若同一个备注在**更上面那一屏**也有（多个小号共用备注），
        //    我们会点在**当前屏**那一行。**同一屏上**出现多个同名时，仍按用户口径取**最上面那一行**
        //    （[NameLocating.locateFriend] 一个字没动）。
        if (hit != null) {
            // 横向用**列表区域**的左右边界（整行）：图标在行尾，只圈名字的话带里没有它
            // ⇒ 必须取 ranges.rowX0/rowX1（**不是** region 的左右 —— 那是裁过名称列的，会点不到）
            val band = NameLocating.rowBandOf(hit, ranges.rowX0, ranges.rowX1, gray.height)
            val icon = locator.locateWithin(gray, UiState.FRIEND_LIST, PatrolAnchors.FRIEND_VISIT, band)
                ?: return PatrolRunner.NamePlan.Failed(
                    "读到了「$target」，但这一行行尾的拜访图标没定位到" +
                        "（检查「拜访（行尾图标）」这条锚点是否已标定）",
                )
            // **开火前复眼**（2026-10-01，T4-10 刀 3）：认出来还不够 —— 动手之前再拿**现取的最新画面**
            // 确认"那一行的备注名还在原来的纵线上"（判据与理由见 [friendFireRecheck]）。
            // 不做这一步的话，"读到"与"手指落下"之间那段（最坏 ≈1 秒）里列表一回弹就会点到**隔壁的好友**。
            val recheck = friendFireRecheck(hit, ranges, gray.height)
            if (recheck?.allowed == false) {
                // **拦下 ≠ 失败**：这一枪不发、等下一轮重新读（不 reset 扫描状态、不消耗重试次数、也不报 Failed）
                if (recheck.note != lastFriendRowFireNote) {
                    lastFriendRowFireNote = recheck.note
                    MmLog.w(TAG, "第 9 步 复眼否决：${recheck.note}｜本轮不点，等下一轮重新读")
                }
                return PatrolRunner.NamePlan.Waiting
            }
            lastFriendRowFireNote = null
            friendScan.reset() // 动手：这一段跨屏扫描结束（下次进列表重新从"当前这一屏"开始）
            friendSearch.reset() // 同一道理：这一轮（含可能走过的搜索链）整段结束
            return PatrolRunner.NamePlan.Click(
                frameX = icon.frameX,
                frameY = icon.frameY,
                detail = "「$target」在 y=${hit.centerY.toInt()}（当前屏就命中，未回顶），" +
                    "图标在 (${icon.frameX.toInt()}, ${icon.frameY.toInt()})" +
                    recheck?.note.orEmpty(),
            )
        }

        // ⑤ **当前屏没命中** ⇒ 用户 2026-10-01 拍板：**走搜索**（"先看当前屏；没有就搜索"）。
        //    顺序见 [PatrolAnchors.FRIEND_SEARCH_ENTRY]：点「搜好友」→ 点「请输入好友昵称」→
        //    **原生写文字**（[TextInjector]：`ACTION_SET_TEXT`，不弹键盘）→ 点「搜索」
        //    ⇒ 结果页交给 ④ 的那套名称定位判（同名仍取最上面）。
        //    ⚠ 三个锚点没标定、或写文字失败 ⇒ [FriendSearch.Phase.UNAVAILABLE] ⇒ **退回原来的滑屏找**
        //      （不因缺锚点把整步卡死；这条也是"不回归"的保险）。
        //    ⚠ **搜索之后不再滑屏**（用户口径）：搜索都找不到 ⇒ 如实停下。
        val rowKeys = NameLocating.friendRowKeysOf(candidates)
        if (friendSearch.phase == FriendSearch.Phase.IDLE) {
            val started = friendSearch.start()
            MmLog.i(TAG, "第 9 步：当前屏没有「$target」⇒ ${friendSearch.note()}")
            if (started == FriendSearch.Phase.UNAVAILABLE) {
                MmLog.w(TAG, "第 9 步：搜索链走不了（三个搜索锚点没标定？）⇒ 退回滑屏找")
            }
        }
        // ①②④：该点哪个锚点就点它。
        // ⚠ **定位不到先等下一帧**（2026-10-01 真机三次实录：点完「搜好友」后面板还在展开动画里，
        // 这一帧的「搜索框」定位不到）—— 旧口径直接算"这一步失败"（`PatrolFlow.fail` 计重试），
        // 三次里两次靠重试自愈、`08:05` 那次**重试用完把整条跑号中止了** ✗。
        // ⇒ 现在：连续 [FriendSearch.ANCHOR_MISS_LIMIT] 帧都定位不到才如实停下（瞬态不再白耗重试次数）。
        friendSearch.anchorToClick?.let { planned ->
            val hit = locator.locate(gray, UiState.FRIEND_LIST).firstOrNull { it.name == planned }
            if (hit == null) {
                if (friendSearch.onAnchorMissed()) {
                    MmLog.w(
                        TAG,
                        "第 9 步搜索链：这一帧没定位到锚点「$planned」（第 ${friendSearch.anchorMisses}/" +
                            "${FriendSearch.ANCHOR_MISS_LIMIT} 次）⇒ 等下一帧再试" +
                            "（面板刚打开时画面还没稳，真机实测常见）",
                    )
                    return PatrolRunner.NamePlan.Waiting
                }
                return PatrolRunner.NamePlan.Failed(
                    "搜索式查找卡在锚点「$planned」（连续 ${friendSearch.anchorMisses} 帧没定位到：" +
                        "没标定？或这一屏确实没有）｜${friendSearch.note()}",
                )
            }
            friendSearch.onAnchorLocated()
            friendSearch.onAnchorPracticed()
            if (friendSearch.awaitingResults) friendSearchSentAtMs = SystemClock.elapsedRealtime()
            MmLog.i(
                TAG,
                "第 9 步搜索链：${friendSearch.note()}｜点 (${hit.frameX.toInt()}, ${hit.frameY.toInt()})",
            )
            return PatrolRunner.NamePlan.Click(
                frameX = hit.frameX,
                frameY = hit.frameY,
                detail = friendSearch.note(),
                // **搜索链的三下不算"本步已动手"**（2026-10-01 真机：算了的后果是编排层转入
                // "刚点过，等画面切换"、**不再问采集层** ⇒ 搜索链卡在第二下直到预算耗尽）；
                // 锚点名也照实写，审计里能看出点的是"搜好友／搜索框／搜索按钮"。
                advanceStep = false,
                anchorName = planned,
            )
        }
        // ③：**原生写文字**；诊断原样进日志（真机第一轮就靠它判断"这条路通不通"）
        if (friendSearch.phase == FriendSearch.Phase.TYPE) {
            val diag = textWriter.write(target)
            MmLog.i(TAG, "第 9 步搜索链 ③ 写「$target」：$diag")
            friendSearch.onTextWritten(diag.contains("成功"))
            return PatrolRunner.NamePlan.Waiting
        }
        // ③.5：**收输入法**（用户 2026-10-01 提示的那一步）——
        // 写完文字后游戏被输入法切成"全屏编辑"，画面被压缩 ⇒ 下一枪要点的「搜索」**不在画面上**
        // （真机实录：`搜索式查找卡在锚点「friend_search_go」`）⇒ 按一次系统返回把它收掉。
        if (friendSearch.phase == FriendSearch.Phase.DISMISS) {
            // **先点输入框右侧那个原生「确定」提交**（2026-10-01 用户提示 + 节点树实证：
            // 它是 `android.widget.Button clickable=true text=「确定」`）—— 比"IME 回车 / 系统返回"都确定
            // （后两者会被游戏当成**取消** ⇒ 字丢在原生控件里、游戏那边框还是空的）。
            val clicked = TextInjector.click(searchConfirmLabel)
            MmLog.i(TAG, "第 9 步搜索链 ③.5 提交：点原生「$searchConfirmLabel」⇒ $clicked")
            if (!clicked.contains("✓")) {
                // 点不到（节点树里没有这个文字）⇒ 退回"按系统返回收键盘"（至少把键盘收掉、能继续点搜索）
                val diag = SystemKeys.back()
                MmLog.w(TAG, "第 9 步搜索链 ③.5 退回系统返回：$diag")
            }
            friendSearch.onKeyboardDismissed()
            return PatrolRunner.NamePlan.Waiting
        }
        // 搜索已发出 ⇒ **只等结果、不再滑屏**；给结果页几秒，仍没有就如实停下（用户口径）
        if (friendSearch.awaitingResults) {
            val waitedMs = SystemClock.elapsedRealtime() - friendSearchSentAtMs
            if (waitedMs < searchResultWaitMs) return PatrolRunner.NamePlan.Waiting
            // **验不过就重来一次**（2026-10-01 真机：`ACTION_SET_TEXT` + 系统返回这套不稳定 ——
            // 返回有时被当成"提交"（文本进框 ✓）、有时被当成"取消"（框还是空的 ✗）：同一台机器
            // 07:55 那次找到了、07:56 那次"结果页里没有它"✗）。面板还开着 ⇒ 重来只花 ②③③.5④ 四步。
            if (friendSearch.retryOnce(searchWriteMaxAttempts)) {
                MmLog.w(
                    TAG,
                    "第 9 步搜索链：结果页没有「$target」⇒ **重写一次**" +
                        "（第 ${friendSearch.writeAttempts + 1}/$searchWriteMaxAttempts 次尝试；" +
                        "多半是写入没进框、系统返回被当成了取消）",
                )
                return PatrolRunner.NamePlan.Waiting
            }
            return PatrolRunner.NamePlan.Failed(
                "已用搜索查过「$target」（写了 ${friendSearch.writeAttempts} 次都没进搜索框），" +
                    "结果页里没有它（等了 ${waitedMs / 1000} 秒）⇒ 如实停下，不再滑屏" +
                    "｜本轮读到的行：" +
                    rowKeys.take(NAME_HINT_LIMIT).joinToString("、").ifEmpty { "（一个都没读到）" },
            )
        }

        // ⑥ **搜索链走不了** ⇒ 退回**跨屏扫描器**（原来的滑屏找；FR-04 硬性要求 #4）：
        //    先回顶（除非已知自己在顶部）、再自上而下逐屏找；到底了就如实停下。
        //    ⚠ **不在这里报 Failed**：那会走 `PatrolFlow.fail` 计重试，3 屏就把这一步耗成中止；
        //    真正的"找不到"由 [ServerListScan] 在"滑动后画面不再动"或超上限时给出（与第 5 步同一口径）。
        // **行键 → 纵坐标**一并交给扫描器：判"这次滑动动没动"不能只看行键重合度 ——
        // 一次拖动不足一屏时共同行很多（看着像"没动"），而共同行的**位移**才是直接证据
        // （2026-10-01 用户报："每次滑动的距离其实不够一屏，导致过早结束了，还是没有滑到顶"）。
        val rowYs = NameLocating.friendRowYsOf(candidates)
        // **刀 4：到顶记忆绑定当前登录服务器**（用户 2026-10-01 口径：不同小号的好友列表可能不同）——
        // 换了账号就作废学到的"顶部那一屏"，否则会把中途某一屏误认成顶部、把上面的条目整段漏掉。
        // ⚠ 「只拜访」时 `targetServer` 为 null（用户自己登的号，我们不知道是哪个）⇒ 本次不采信，老实回顶。
        friendScan.noteScope(PatrolSession.targetServer)
        // **换号流程（targetServer 非空）= 我们刚登录进这个号** ⇒ 按用户 2026-10-01 的观察
        // （"每次重新登录服务器，好友列表都会回到顶部"），**进来这一屏就是列表顶部** ⇒ 不必再空滑那一下
        // （省约 1.5~2s，也免得刚被 [noteScope] 清掉的记忆还没来得及重建）。「只拜访」不算：那是用户自己
        // 登的号，列表停在哪儿由他决定 ⇒ 老实回顶。
        friendScan.noteEntryAtTop(PatrolSession.targetServer != null)
        val scanStep = friendScan.onMiss(rowKeys, SystemClock.elapsedRealtime(), rowYs)
        // 走到这里一定是"**当前屏没命中**"（命中在 ④ 就点了，见那里的口径变更）⇒ 日志只写这一句
        val scanNote = "目标不在这一屏；" + friendScan.lastDecisionNote
        // 打日志的两种情形：① 有实质结论（回顶 / 往下 / 放弃）；② **卡在"等上一次滑动的回告"**
        //（2026-10-01：那种卡死在日志里原本完全看不见 —— 用户报"根本没滑屏"时只能靠翻代码找。
        //  `Wait` 的另外两种（稳定等待 / 等回告）里，稳定等待那条带着毫秒数、会逐轮变 ⇒ 不记它，免得刷屏）。
        val noteChanged = scanNote != lastFriendScanNote
        if (noteChanged && (scanStep !is ServerListScan.Step.Wait || friendScan.waitingForOutcome)) {
            lastFriendScanNote = scanNote
            MmLog.i(TAG, "第 9 步扫描: $scanNote｜帧：${frameTag()}")
        }
        return when (scanStep) {
            ServerListScan.Step.Wait -> PatrolRunner.NamePlan.Waiting
            ServerListScan.Step.ScrollTowardsTop ->
                scrollPlanOf(listArea, towardsTop = true, step = PatrolFlow.Step.VISIT_FRIEND)

            ServerListScan.Step.ScrollDown ->
                scrollPlanOf(listArea, towardsTop = false, step = PatrolFlow.Step.VISIT_FRIEND)

            // 如实停下并**把这一路读到的备注名摊出来**（2026-09-24 的老教训：只说"没找到"分不清是
            // "真不在列表里"还是"备注名被读歪了"；有这串名字，用户一眼就能对上）
            is ServerListScan.Step.GiveUp -> PatrolRunner.NamePlan.Failed(
                "${scanStep.reason}｜本轮读到的行：" +
                    rowKeys.take(NAME_HINT_LIMIT).joinToString("、").ifEmpty { "（一个都没读到）" } +
                    "｜帧：${frameTag()}",
            )
        }
    }

    /**
     * 读一次文字（第 5 / 9 步共用）：**节流 + 复用**（2026-09-22 真机修，见 [OCR_MIN_INTERVAL_MS]）。
     *
     * 这一步是**同步**的（引擎超时 1 秒），而这两步每轮都要判 —— 不节流的话整个识别循环会被拖到
     * "两秒一轮"（真机帧管线统计实录：24 秒只处理 11 帧），表现就是"关掉列表后要等好几秒才认出下一屏"。
     *
     * 两条口径：
     * - **同一步的 1 秒窗口内复用上次结果**（名字不会在零点几秒里变成另一个）；
     * - **换了步骤一定重读**：上一步的候选与这一步的列表毫无关系，复用等于拿别的画面的字去下结论。
     *
     * **复用来的结果不许下结论**（2026-09-22 真机 P0）：帧轮 200/500ms、而 OCR 每 ≥1000ms 才刷一次
     * ⇒ "一轮 = 一次重试"会把 `MAX_RETRIES = 3` 在 1 秒内烧完，而中间只刷到 1 个新结论 ——
     * 等于**没有重试**（真机实录：39.441 记第 1 次失败，40.468 已中止，中间只有 40.163 一帧新结果；
     * 紧接着 41.448 读到能命中的行，可惜已经停了）。所以调用方拿到 [NameReading.fresh] = false 时
     * 一律按"还在找"处理（[PatrolRunner.NamePlan.Waiting]）。
     *
     * @param regions 送去识别的区域（第 9 步一块；第 5 步两块 = 两条列竖带各一块），**只在这一轮真读时才用**。
     */
    private fun readNames(
        gray: GrayImage,
        step: PatrolFlow.Step,
        regions: List<PixelBounds>,
    ): NameReading {
        val nowMs = SystemClock.elapsedRealtime()
        if (lastOcrStep == step && nowMs - lastOcrAtMs < OCR_MIN_INTERVAL_MS) {
            return NameReading(lastOcrCandidates, fresh = false)
        }
        lastOcrAtMs = nowMs
        lastOcrStep = step
        lastOcrCandidates = regions.flatMap { nameReader.read(gray, it) }
        return NameReading(lastOcrCandidates, fresh = true)
    }

    /**
     * 标定页刚写入产物 ⇒ **就地重载**（2026-09-21）。见 [CalibrationReloadSignal] 的说明。
     *
     * 只换"产物相关"的东西（识别循环 / 锚点定位器 / 用途查询 / 期望集合 / 标定帧尺寸），
     * **不动节流、不动跑号进度、不重置重试计数** —— 用户可能正在跑号，
     * 标定页写一条模板不该把正在跑的流程打断。
     *
     * 只在帧线程调用（与识别循环同线程）⇒ 不存在"用到半成品产物"的并发窗口。
     */
    private fun reloadArtifact() {
        // **先腾内存**（2026-09-29 真机崩溃后加）：产物可能有几 MB（把很大一片区域框成模板就会出现），
        // 解码要同时吃下"base64 原文 + 它的字节数组 + 解出来的模板"几份拷贝；而这一刻我们手里还攥着
        // 兜底缓存那一整帧（3168×1440×4 ≈ 18MB，最多 300ms 前抓的）。它只是"静止时重放用"的副本，
        // 下一帧就能重建 ⇒ 先扔掉，把峰值让给解码。
        lastFrameBytes = null
        lastFrameBytesAtMs = 0L
        lastPixelCacheAtMs = 0L
        val calibration = try {
            CalibrationStore.loadForRuntime(this, PatrolAnchors.nonLocatableAnchors)
        } catch (t: IllegalArgumentException) {
            MmLog.w(TAG, "标定产物重载失败，继续用当前配置：${t.message}")
            return
        } catch (t: Throwable) {
            // ⚠ **连 OutOfMemoryError 一起接住**（2026-09-29 真机实录：产物 5.4MB，
            // `Base64.decode` 里申请 3.7MB 失败 ⇒ **帧线程直接崩掉，整个采集会话没了**）。
            // 口径与看门狗 / 几何守卫一致：**宁可这一份产物不生效并如实告警，也不让进程死**。
            // 这里**不动** recognitionLoop / anchorLocator ⇒ 继续用上一份能跑的产物。
            // 不自动重试（每次重载都要再吃一遍那几 MB）；用户在标定页**再写一次**就会重新触发。
            MmLog.w(
                TAG,
                "标定产物重载失败（${t.javaClass.simpleName}：${t.message}）⇒ **继续用当前这份产物**，" +
                    "本次不再自动重试（再写一次标定即会重试）。若是内存不足：把过大的那一块" +
                    "（例如整屏 / 整个列表区域）重新框小些再写。",
            )
            return
        }
        val markerRules = calibration?.stateRules.orEmpty()
            .filter { it.signalNames.isNotEmpty() }
            .map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        patrolExpected = calibration?.let {
            PatrolDrivenExpectedSignals(
                popup = PopupWatchExpectedSignals.fromRules(markerRules),
                rules = markerRules,
                standbyStates = PatrolScenes.startStates,
            )
        }
        recognitionLoop = calibration?.toLoop(patrolExpected) ?: RecognitionLoop.uncalibrated()
        calibrationData = calibration
        anchorLocator = calibration?.toAnchorLocator(PatrolAnchors.nonLocatableAnchors)
        calibratedFrameWidth = calibration?.frameWidth ?: 0
        calibratedFrameHeight = calibration?.frameHeight ?: 0
        frameSizeWarned = false
        val anchorCount = calibration?.signals?.count { it.role == SignalRole.ANCHOR } ?: 0
        MmLog.i(
            TAG,
            "标定产物已重载（标定页刚写入，无需重开会话）：信号 ${calibration?.signals?.size ?: 0} 个" +
                "（其中锚点 $anchorCount 个）",
        )
        // 逐条列全：重载后日志里那份清单必须和"标定页刚写进去的"一致，否则排障会对不上
        calibration?.signals?.forEach { MmLog.i(TAG, "  · ${calibration.describe(it)}") }
    }

    /**
     * 锚点窗口（T2-4，T2-3b 口径）：锚点按状态启用，采集层必须把它们的窗口**一并纳入灰度转换**——
     * 否则锚点在"只转了标志窗口"的帧上读到全零像素，静默失败（不报错，只是永远不命中）。
     *
     * 纳入「上一轮状态 ∪ 活动弹窗」：只纳入上一轮状态的话，弹窗**刚被确认的那一轮**锚点读到的是
     * 未转换的像素 → 白等一轮（真机 200ms~1s）。活动弹窗锚点通常只有 1~2 条小窗口，常驻纳入的成本可忽略。
     */
    /**
     * 跑号一步（T4-4b）：每轮把「画面 + 锚点 + 时钟」喂给编排器，拿到**等 / 点 / 进下一步 / 停**。
     *
     * 三件事刻意都在这里做，不散到别处：
     * - **期望集合跟着步骤走**（[PatrolDrivenExpectedSignals.watch]）：跑号激活时多搜"动作所在状态 ∪ 验证目标"
     *   的**标志**（锚点不进期望集合，它由 [AnchorLocator] 按当前状态另算）；没跑号时清空 → 等价守护口径；
     * - **锚点只在状态确认后定位**（红线 7）：未确认状态时给空列表，编排器据此只会"等"；
     * - **点击一律经 [ClickDispatch]**（红线 6）：本方法只出判定与日志，不下发。
     */
    private fun stepPatrol(result: RoundResult, gray: GrayImage) {
        // **先读代次、再读快照**（顺序有讲究，见 `PatrolSession.currentGeneration`）：
        // 本轮算完写回时对齐它 —— 期间用户按过停止 / 开始，这一轮结论就得作废，
        // 否则会把已经停掉的流程"复活"（真机 bug：停止 7 秒后又自己推进了一步）。
        val generation = PatrolSession.currentGeneration
        val current = PatrolSession.current
        val step = current?.step
        patrolExpected?.watch(
            if (step == null) {
                emptyList()
            } else {
                // **只搜"现在可能出现的画面"**（2026-10-01 用户口径："`hall_settings_e1` 肯定是在
                // `hall_settings` 找到后才出现的"）：有结论 ⇒ 当前屏 + 它的下一屏；认不出来 ⇒ 退回整步集合。
                // 判据与自愈阀都在 [PatrolScenes.watchStatesFor]（纯逻辑、有单测）。
                // ⚠ 遮挡屏不在这个集合里 —— 它们由守卫集合单独提供（FR-01/FR-02 的可见性不靠这里）。
                PatrolScenes.watchStatesFor(step, result.state)
            },
        )
        // 2026-09-22 诊断：**待命期每秒、跑号期每 5 秒**报一行"当前判定 + 本轮命中的画面 + 每条信号的分数"。
        //
        // 为什么需要（真机报障：关掉好友列表回农场后，点菜单仍被拦"现在停在「好友列表」"）：
        // 起点判定被拦时，日志只有一句"停在 XX"——**看不出是什么让它停在 XX**：
        // 是画面真的还没变，还是某个画面标志在别的画面上**误命中**把状态钉住了。
        // 这一行把"命中了哪几屏"摊开，一眼可辨（配合「本轮搜索集合变化」看被搜了哪些）。
        //
        // **跑号期也要有**（2026-09-24 真机教训——"进了选服页却卡住"那次）：整轮跑号只有
        // `跑号: 第 4 步：刚点过，画面还没看清（可能正在加载）…` 这一种文案，**分不出**
        // "游戏在加载"还是"选服页标志压根不匹配"；最后只能等用户停止后，从**待命期**那一行分数里
        // 才看出 `server_select_e2=0.32(NOT_MATCHED)`。跑号期每 [PATROL_REPORT_INTERVAL_MS] 记一行，
        // 这类"认不出画面"当场就能定性。
        run {
            val nowMs = SystemClock.elapsedRealtime()
            val interval = if (step == null) STANDBY_REPORT_INTERVAL_MS else PATROL_REPORT_INTERVAL_MS
            if (nowMs - lastStandbyReportAtMs >= interval) {
                lastStandbyReportAtMs = nowMs
                val hits = result.hits.joinToString("、") { it.label }.ifEmpty { "（一屏都没命中）" }
                // 分数取自 `best`（未命中时也保留，正是要看的数）+ 判定结论。
                // **不可信时把"竞争位置"一起打出来**（2026-09-29 加）：`UNRELIABLE` 只有两种成因 ——
                // ① 竞争位置也过了命中线；② 最高分与竞争分差得太近。只看最高分永远分不出
                // "框到了重复图案"还是"模板太糊"（真机当场问过："返回按钮和活动弹窗的关闭按钮看着
                // 没本质区别，为什么一个能识别一个不能？"）⇒ 把竞争的分数与坐标一并摆出来，一眼可辨。
                val scores = result.records.joinToString("，") { record ->
                    val competitor = if (record.verdict == Verdict.UNRELIABLE) {
                        record.competitor?.let { "（竞 ${it.score}@${it.x},${it.y}）" }.orEmpty()
                    } else {
                        ""
                    }
                    "${record.signalName}=${record.best?.score ?: "-"}(${record.verdict})$competitor"
                }.ifEmpty { "（本轮没搜任何信号）" }
                val head = if (step == null) {
                    "待命期画面判定"
                } else {
                    // 跑号期把"这一步在等哪一屏"一起打出来：认不出画面时这是唯一能对症的信息
                    "跑号第 ${step.number} 步画面判定（期望「${PatrolScenes.expectedState(step)?.label ?: "无"}」）"
                }
                // 「帧」这一项是本次新增（2026-09-24）：「帧流活着」≠「这一轮用的是新画面」——
                // 判定基于旧画面时，看到的分数与结论全都是过期的（FR-01 补点事故只能靠它定性）
                //
                // 「复用」只在真发生时才打（2026-09-24 第二刀）：它说明这一轮的耗时为什么明显偏低，
                // 也提示"画面这几条信号的窗口一直没动"（静止画面 / 兜底重放期的正常现象）
                val reuse = if (result.reusedSignals.isNotEmpty()) {
                    "｜复用 ${result.reusedSignals.size}/${result.searched.size} 条（窗口未变，未重复计算）"
                } else {
                    ""
                }
                MmLog.i(
                    TAG,
                    "$head：当前「${result.state.label}」｜帧：${frameTag()}$reuse｜命中：$hits｜分数：$scores",
                )
            }
        }

        if (current == null) {
            // **没在跑就到此为止**（2026-09-22 真机修，证据见下）。
            //
            // 期望集合已收回守护口径（上面 `watch(emptyList())`），这里必须 return ——
            // 若继续往下把 `current = null` 喂给 `PatrolRunner.onRound`，它照样会给出一个
            // "推进"结论并被 `commit` 写回 ⇒ **停止之后流程又复活**。
            //
            // 真机实录（`mm-log-20260922.txt`）：
            //   03:17:52.040  停止：停在第 8 步
            //   03:18:09.745  跑号: 第 8 步通过 → 第 9 步      ← 停止 17 秒后自己往前走
            // 后果不止"停不掉"：复活的流程把搜索集合锁死在那一屏要认的画面上，
            // 画面判定迟迟不更新 —— 这正是用户报的"要等很长时间"。
            return
        }

        // 2026-09-21 这里曾有一条"刚进入农场就报一次身份判据"的分支（含逐条身份锚点打分、
        // 并把结论递给菜单做起点判定）。**2026-09-23 随农场归属判定整块删除**：
        // 自己的农场与好友的农场换号 / 拜访路径完全一样，不需要知道"这是谁的农场"。

        // 这里原本还有一处 `if (current == null) return`：上面那处早退（见其注释）加进来之后漏删的重复判断，
        // 编译器一直报 "Condition is always 'false'"。删掉即可 —— `current` 已在上面收窄为非空。
        val confirmed = !result.frozen && result.state != UiState.UNKNOWN
        val anchors = if (confirmed && anchorLocator != null) {
            anchorLocator!!.locate(gray, result.state)
        } else {
            emptyList()
        }
        val now = SystemClock.elapsedRealtime()
        val outcome = PatrolRunner.onRound(
            current = current,
            input = PatrolRunner.RoundInput(
                foreground = !result.frozen,
                // "我们现在看不见画面"（与点击门禁**同一个信号**）：runner 会因此**暂停这一步的计时**，
                // 而不是把"收不到帧"升级成"这一步超时中止"（见 PatrolRunner.RoundInput.framesStalled）
                framesStalled = ClickDispatch.framesStalled,
                // 锚点名的**中文说法**（2026-10-01 用户口径）：菜单状态行 / 中止原因里出现锚点名时用它 ——
                // 用户看到的是「设置入口」，不是 `hall_settings`（备注 > 用途标签 > 「界面」的角色 ⇒ ID）
                anchorLabel = { id -> SignalNames.of(calibrationData, id) },
                state = result.state,
                popupHit = UiState.ACTIVITY_POPUP in result.hits,
                popupConfirmed = result.state == UiState.ACTIVITY_POPUP,
                anchors = anchors,
                nowMs = now,
                lastClickAtMs = PatrolSession.lastClickAtMs,
                targetFriend = PatrolSession.targetFriend,
                // 第 5 步的日志文案要用它（定位本身在 [serverPlanOf] 里读会话，不靠这个参数）
                targetServer = PatrolSession.targetServer,
                // 第 5 / 9 步"点哪里"由本层算好（需要灰度帧、锚点定位器与文字识别），编排层只消费结论
                namePlan = namePlanOf(gray, result.state),
                decisionId = "patrol-${patrolSeq + 1}",
            ),
        )
        val stepBefore = current.step
        PatrolSession.commit(outcome.state, generation)
        commitServerRotationIfSwitched(stepBefore, outcome.state)
        // 换了一步 ⇒ 上一句"点击被拒"过期了（那说的是上一步的事），顺手复位去重状态：
        // 下一步若又被拒，要能重新说一遍
        if (outcome.state?.step != stepBefore) {
            deniedStreak = 0
            lastClickDenyDetail = null
            PatrolSession.clearDeniedNote()
        }
        // 同一句话只记一次（等待类结论会连续多轮相同，逐轮记等于刷屏）
        outcome.note?.let {
            if (it != lastPatrolNote) {
                lastPatrolNote = it
                MmLog.i(TAG, "跑号: $it")
            }
        }
        PatrolSession.note(outcome.note)

        outcome.request?.let { request ->
            patrolSeq++
            lastPatrolNote = null
            lastAbortAnnounced = null
            // 下发前刷一次屏幕尺寸：画面静止时尺寸会停在旧方向 ⇒ 落点会被误判"屏外"而**静默拒绝**
            //（真实录见 [refreshScreenSizeForClick]）
            refreshScreenSizeForClick()
            val verdict = ClickDispatch.submit(
                request = request,
                gameForeground = !result.frozen,
                state = result.state,
            )
            MmLog.i(
                TAG,
                "跑号判定: 第 ${current.step.number} 步点「${request.anchorName}」" +
                    // 中文说法一并打出来（2026-10-01）：日志既要能 grep（英文契约名），也要能一眼读懂
                    "（${SignalNames.of(calibrationData, request.anchorName)}）" +
                    "(${request.frameX.toInt()}, ${request.frameY.toInt()})（运行帧坐标；真正下发的屏幕坐标见 " +
                    "MM-Click 那一行）→ ${verdict.detail}（判定 ${request.decisionId}）",
            )
            if (verdict.allowed) {
                // **只有真的发出去了**才算"点过"（2026-09-30 修，progress 第 325 条 b）
                PatrolSession.markClick(now)
                deniedStreak = 0
                lastClickDenyDetail = null
                PatrolSession.clearDeniedNote()
                // 刚打出去一枪 = 画面马上要变：立刻回快档（否则要等下一轮"未命中"才降档，白等 ≈1s）
                useFastGear("刚下发一击（第 ${current.step.number} 步）")
                // **屏幕必然变了 ⇒ 期待一帧新画面**（2026-09-29）：期待落空 = "画面采集已停"，
                // 那时会闸掉后续所有点击（见 [checkFrameExpectation]）——
                // 没有这一步，程序会照着几分钟前那张画面一直点（真机实录：5 分钟没新帧还在点）。
                // `gateEligible = true`：这一击打在被采集的那个 App 身上 ⇒ 内容必然变了、出帧是必然的，
                // 与采集模式无关 ⇒ 落空可以升级成硬闸 ✓（面板类期待不能，见 [FrameExpectationSignal]）
                FrameExpectationSignal.arm(
                    "刚下发一击（跑号第 ${current.step.number} 步）",
                    gateEligible = true,
                )
            } else {
                // **被拒 ≠ 点过了**（2026-09-30 修，progress 第 325 条 b）：`PatrolRunner` 在**提议**那一轮
                // 就把"这一步已点过"（`lastActionAtMs`）写进了状态，而 ⑤ 靠它判断"接下来只等期望画面"——
                // 于是被拒之后**整整一个步骤预算都白等**。真机实录（2026-09-30 20:10）：落点被误判"屏外"、
                // 静默拒绝 ⇒ 流程把它当"刚点过"⇒ 白等 16 秒后中止，用户看到的是"卡在了打开好友列表"。
                // 现在：① **撤销那个标记**（下一轮重新定位、重新提议）；② **把原因留下来给用户看**。
                deniedStreak++
                outcome.state?.let {
                    PatrolSession.commit(PatrolFlow.withoutPendingAction(it), generation)
                }
                // 仍然记下"这一轮试过了"：只用于点击间隔节流（别让"被拒"变成"没发生"而每轮猛提）
                PatrolSession.markClick(now)
                if (deniedStreak >= CLICK_DENY_NOTE_STREAK) {
                    // 连续被拒才值得占菜单那一行（偶发一次多半下一轮就过去了）
                    PatrolSession.noteDenied("点击被拒 $deniedStreak 次：${verdict.detail}")
                }
                if (verdict.detail != lastClickDenyDetail) {
                    // 同一原因只记一次（被拒会一轮一行，不去重会把日志刷满）
                    lastClickDenyDetail = verdict.detail
                    MmLog.w(
                        TAG,
                        "跑号: 第 ${current.step.number} 步点击**被拒**（${verdict.detail}）⇒ " +
                            "已撤销「已点过」标记，下一轮重新定位、重新提议（不再白等一个步骤预算）",
                    )
                }
            }
        }

        // **滑动**（第 5 步滚列表，2026-09-29）：与点击**同一条**门禁 + 审计链（红线 6）。
        // 门禁结果必须回告扫描器：真的滑出去了 ⇒ 从此刻起算"等这一屏稳定"；
        // 被拒（前台 / 状态 / 间隔 / 越界）⇒ **原样撤回这一步**，下一轮重新提议 —— 否则"没滑成"
        // 会被当成"滑了但画面没动"，扫描会误判成"到底了"而放弃。
        outcome.swipe?.let { swipe ->
            patrolSeq++
            lastPatrolNote = null
            lastAbortAnnounced = null
            // 同点击：滑动的**两端**都要过越界判定，尺寸旧了会整体被拒（见 [refreshScreenSizeForClick]）
            refreshScreenSizeForClick()
            val verdict = ClickDispatch.submitSwipe(
                request = swipe,
                gameForeground = !result.frozen,
                state = result.state,
            )
            // 这一次滑动属于**哪一步**的扫描状态：第 9 步（好友列表）与第 5 步（区服列表）各持一个实例
            // （关键：被门禁拒时要撤回到**那一个**扫描器上，否则"没滑成"会被当成"滑了、画面没动"⇒ 误判到底）
            val scan = if (current.step == PatrolFlow.Step.VISIT_FRIEND) friendScan else serverScan
            if (verdict.allowed) {
                PatrolSession.markClick(now)
                scan.onScrollDispatched(now)
                // 下一次"新读"要量一下这次滑动移动了多少（只记日志，见 serverPlanOf 里的位移测量；仅第 5 步有）
                if (current.step == PatrolFlow.Step.PICK_SERVER) serverScrollSinceRead = true
                // 滑动同样是"屏幕必然变了" ⇒ 期待一帧新画面（见 [checkFrameExpectation]）；
                // `gateEligible = true` 同理（滑的是被采集的那个 App 的列表）
                FrameExpectationSignal.arm(
                    "刚下发一次滑动（跑号第 ${current.step.number} 步）",
                    gateEligible = true,
                )
            } else {
                // ⚠ **必须回告到"这一次滑动属于的那一个"扫描器**（2026-10-01 真机事故：这里原本写死
                // `serverScan`，第 9 步被拒的那一下没撤回好友扫描器 ⇒ 它永远停在"等回告"⇒ 再也不提议
                // 任何滑动，用户看到的是"**根本没滑屏**"）。
                scan.onScrollRejected()
            }
            MmLog.i(
                TAG,
                "跑号判定: 第 ${current.step.number} 步滑动「${swipe.anchorName}」" +
                    "(${swipe.fromFrameX.toInt()}, ${swipe.fromFrameY.toInt()}) -> " +
                    "(${swipe.toFrameX.toInt()}, ${swipe.toFrameY.toInt()})" +
                    "（运行帧坐标；真正下发的屏幕坐标见 MM-Click 那一行）" +
                    "→ ${verdict.detail}（判定 ${swipe.decisionId}）",
            )
            if (verdict.allowed) useFastGear("刚下发滑动（第 ${current.step.number} 步滚列表）")
        }

        // 跑到区间终点 = 本次执行结束（FR-04：跑完即结束，要再来一次由用户再点一次菜单）
        if (outcome.finished) {
            // 用户不一定正盯着屏幕 —— 但**不再由这里喊话**了：底部浮窗提示 2026-09-29 已按用户口径移除，
            // "已完成 / 已中止 / 已暂停"由**顶部标签常驻显示**（`FloatingLabelText`），读屏播报也归它
            // （`FloatingWindow.announcePatrolOutcome`）⇒ 这里只留日志。
            // ⚠ **先把结果存进快照，再收流程**（2026-09-30 真机验收发现）：`stop()` 之后
            // `PatrolSession.current` 立刻变 null ⇒ 标签判"待命"、整条摘掉 ⇒ 用户**看不到「已完成」**
            // （实录：打完这一枪后 6ms 标签就没了，写的还是第 10 步的步骤名「完成」）。
            // 那句"由顶部标签常驻显示"因此一直没兑现，见 [PatrolResultSignal]。
            outcome.state?.let { PatrolResultSignal.show(it, SystemClock.elapsedRealtime()) }
            PatrolSession.stop()
            lastPatrolNote = null
            lastAbortAnnounced = null
            MmLog.i(TAG, "跑号: 已完成（区间终点）")
        } else if (outcome.failed) {
            // **同一句只记一次**（2026-09-20 修）：中止后流程停在 FAILED，但每轮还会走进来；
            // 不去重的后果是日志被同一行刷爆（真机一次测试 3445 行里大半是它），排障时把关键行冲掉。
            val reason = outcome.state?.reason
            if (reason != null && reason != lastAbortAnnounced) {
                lastAbortAnnounced = reason
                MmLog.w(TAG, "跑号: 已中止 —— $reason")
            }
        } else if (outcome.state?.paused == true && !current.paused) {
            // **刚**进入暂停（切到后台 / 离开游戏）：回游戏时用户要立刻知道"得手动点继续"（FR-05），
            // 标签会常驻显示"已暂停"，展开菜单就能点「继续」
            MmLog.i(TAG, "跑号: 已暂停 —— ${outcome.state.reason}")
        }
        // 跑号相位（启动 / 继续 / 步骤推进 / 停止）一变就回快档（2026-09-20 手感修）
        syncPatrolGear()
    }

    /**
     * 立刻回到快档（2026-09-20 真机手感修）：NFR-02 的"需要响应"那一侧。
     *
     * 为什么必须有：自适应降档只看**上一轮是否稳定**，于是它不知道"**我们自己刚推动了一下画面**"——
     * 真机实测白等两处：① 启动后第一枪晚 **1.84s**（那一刻还挂在 1s 慢档）；
     * ② 点完一枪后 **1.44s** 才切回快档（要等下一轮"未命中"才算变化轮）。
     *
     * 而这两处"屏幕马上会变"是**已知事实**，不必等画面告诉程序：点击下发、跑号进入某一步、
     * 暂停 / 继续、步骤推进 —— 发生即回快档，省掉一整轮白等。**不动需求参数**（快档本来就是需求里那一档）。
     */
    private fun useFastGear(reason: String) {
        if (lastAppliedIntervalMs == ACTIVE_INTERVAL_MS) return
        MmLog.i(TAG, "节流间隔切换: ${lastAppliedIntervalMs}ms -> ${ACTIVE_INTERVAL_MS}ms（$reason）")
        lastAppliedIntervalMs = ACTIVE_INTERVAL_MS
        frameThrottle.setInterval(ACTIVE_INTERVAL_MS)
    }

    /**
     * 跑号相位签名变化 = "画面刚被我们推动"：启动 / 继续（暂停→跑）/ 步骤推进 / 停止 全覆盖。
     *
     * 不做成"每轮都比一次当前状态"：那要靠放宽判据，而相位签名已经能覆盖**所有我们自己动手**的时刻
     * （别人动手的时刻——游戏自己弹窗、用户手点——由自动降档那条路径负责）。
     */
    private fun syncPatrolGear() {
        val s = PatrolSession.current
        val phase = if (s == null) "idle" else "${s.step.number}|${s.outcome}|${s.paused}"
        val previous = lastPatrolPhase
        lastPatrolPhase = phase
        // 本会话第一次见到相位（previous == null）不刷一行：那一轮还没建立"档位"的概念
        if (previous == null || previous == phase) return
        useFastGear("跑号相位变化（$previous → $phase）")
    }

    private fun anchorRegions(width: Int, height: Int): List<PixelBounds> {
        val locator = anchorLocator ?: return emptyList()
        // 当前状态（上一轮滞回结论）+ **遮挡屏**（常驻，见下）+ **农场**（常驻，见下）
        //
        // 农场为什么**常驻**而不是"当前状态就够"（2026-09-21 真机实录）：**刚进入农场的那一轮**，
        // "上一轮状态"还是「未知」⇒ 农场锚点（返回 / 好友入口）的窗口**根本没被灰度化**，读到全零 ⇒
        // 进去第一轮定位不到它们。离线探针在同一帧上测是满分、在线却近乎零分，差别就在这一行 ——
        // 所以农场锚点必须一直在。成本很小：农场锚点是几条几十像素的小窗口。
        //
        // 遮挡屏同理（2026-09-29 从"只活动弹窗"扩到新手两页）：它们出现的**那一刻**，上一轮结论
        // 多半还不是它（正是"刚冒出来"），不常驻就会读到全零、静默 miss ⇒ 关闭控件定位不到。
        val states = mutableSetOf(recognitionLoop.state, UiState.FARM)
        states += UiState.guardedOverlays
        // 2026-09-21 增：**跑号当前步骤可能动手的那些界面**的锚点窗口也要一起转换。
        //
        // 为什么：灰度只转"上一轮结论那个界面"的锚点窗口 → 刚切到新界面的那一轮，新界面的锚点读到的是
        // **全零像素**（静默失败，不报错）→ 必然 miss 一轮。真机实录（从大厅点「换号拜访」）：
        //   `00:02:17.216 大厅 -> 设置页`（状态已确认）→ `00:02:17.235 锚点「settings_logout」没定位到，重试 1/3`
        //   → `00:02:18.218` 才点下去 —— **每个界面都白等一轮**，用户感觉"对设置页 / 确认框的识别太慢"。
        // 加入"本步动作状态"后，该界面的锚点在同一轮就已转换 → 状态确认的那一轮即可定位并点击。
        PatrolSession.current?.let { states += PatrolScenes.actionStates(it.step) }
        return states.flatMap { locator.windowRegions(width, height, it) }
    }

    /**
     * FR-01 弹窗闭环（T2-4，B1）：每轮一次「验证上一枪 → 决定这一轮要不要下发」。
     *
     * 判定与下发结果都进日志——真机核对「为什么点 / 为什么没点」只看这两行（`MM-Capture` 的 FR-01 行 +
     * `MM-Click` 的审计行，判定 ID 对齐）。点击本身不在这里实现：一律经 [ClickDispatch]（红线 6）。
     *
     * `frame*` 四个参数是**本轮的原始帧**（RGBA + 跨度）：只给"自上一枪以来整帧变了多少"这条
     * **只记日志**的测量用（见 [frameChangeNote]）—— 识别层的 `gray` 是窗口化的，比不出整帧变化。
     */
    private fun stepPopupClose(
        result: RoundResult,
        gray: GrayImage,
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        frameStride: Int,
    ) {
        // **遮挡屏判定**（2026-09-29：不再只认活动弹窗）—— 见 [UiState.guardedOverlays]：
        // FR-01 活动弹窗 + FR-02 新手引导 / 新手大厅，三屏共用这同一套关闭闭环（[PopupCloseController]），
        // 区别只在"点击来源"（审计与日志用；红线 2 的合法来源）。
        val overlay = result.state.takeIf { it in UiState.guardedOverlays }
        val confirmed = overlay != null
        // **用户按下了"暂停自动关弹窗"**（2026-09-30 加，见 PopupCloseSignal.paused）：
        // 有些界面本来就有"关闭 / 返回"按钮（**不是活动弹窗**），那时不该自动点 ⇒ 用户要能随时停。
        // 暂停期间**识别照常**（状态照判、日志照记），只是**不动手**。
        if (PopupCloseSignal.paused) {
            val note = "已暂停自动关弹窗（悬浮窗菜单里恢复）：本轮只识别、不动手"
            if (note != lastOverlaySkipNote) {
                lastOverlaySkipNote = note
                MmLog.i(TAG, "FR-01/FR-02 暂停中：$note")
            }
            return
        }
        // ⚠ 没有遮挡屏的那一轮（段结束的收尾轮）**不许当成 FR-02**：原来写成 `else -> FR_02`，
        // 于是"人在服务器列表 / 启动页上"也会打出 `FR-02 不动作: …`，把排障带偏（2026-09-29 修）
        val source = when (overlay) {
            UiState.TUTORIAL_GUIDE, UiState.TUTORIAL_HALL -> ClickSource.FR_02
            else -> ClickSource.FR_01
        }
        val decisionPrefix = if (source == ClickSource.FR_01) "fr01" else "fr02"
        // 锚点只在状态**确认**后定位：未确认就定位，等于用未确认的画面去找点击点（红线 7）。
        // ⚠ 用 [AnchorLocator.locateFirstHit]（只回第一条命中的）：`PopupCloseController` 用的就是
        // `anchors.firstOrNull()` ⇒ 少扫的那几条本来就不会被用掉（真机实测活动弹窗名下 5 条 X 锚点、
        // 每条 ≈83ms ⇒ 每轮白跑 4 条 ≈330ms CPU，而弹窗在屏那段每轮都要定位）。
        // ⚠ **不要**反过来把 [AnchorLocator.locate] 也改成命中即停：巡逻流程按**名字**取锚点
        //（启动页的 `launch_switch` 与 `launch_login` 分别是第 4 / 6 步要的），2026-09-30 真机就是这样
        // 把换号卡在第 6 步（等满 30 秒中止，见 [AnchorLocator.locate] 的注释）。
        val locator = anchorLocator
        val anchors = if (confirmed && locator != null) locator.locateFirstHit(gray, result.state) else emptyList()
        // **"命中即停"的取证行**（2026-09-30，同一句话只记一次）：这次提速只体现在"少匹配了几条"上，
        // 而逐信号耗时统计**只覆盖标志**（锚点定位是另一条路径）⇒ 不记这一行，真机上没有任何地方
        // 看得出它生效了（用户问"到底省了没有"就只能靠猜）。
        if (anchors.isNotEmpty()) {
            val declared = locator?.available(result.state)?.size ?: 0
            val note = if (declared > 1) {
                "锚点定位: 用「${anchors.first().name}」（本状态共 $declared 条，命中即停 ⇒ 少匹配 ${declared - 1} 条）"
            } else {
                "锚点定位: 用「${anchors.first().name}」" +
                    "（${SignalNames.of(calibrationData, anchors.first().name)}）"
            }
            if (note != lastAnchorNote) {
                lastAnchorNote = note
                MmLog.i(TAG, "${source.tag} $note")
            }
        }
        // **遮挡屏在屏、却一条锚点都没定位到** ⇒ 十有八九是这一屏**压根没标过关闭控件锚点**
        // （2026-09-29 用户报"新手两屏没有自动关闭"就是这一种：只标了标志、没标锚点）。
        // 从"判决"上看不出区别（都只是一句 Skip），可用户看到的现象是"它不自动关" —— 所以单独说清，
        // 并直接指路到标定页。同一句话只记一次（否则每轮刷屏）。
        if (confirmed && anchors.isEmpty()) {
            // **两种"没有锚点"必须分开说**（2026-09-29 用户问"为什么新手两屏的锚点找不到，农场的就行"）：
            // ① 该状态**根本没标**可定位锚点（`locator.isEmpty`）⇒ 去标定页补一条；
            // ② 标了，但**本轮一条都没命中**（评分没到阈值 / 模板和当前画面不一样）⇒ 不是缺数据，
            //    而是"认不出"，处理办法是**重框紧一点**（只框控件本身，别把周围会变的东西圈进去）。
            // 以前这两种都说成"还没有可用的关闭控件锚点" ⇒ 用户会以为"我没标"，去标定页一看明明标了 ✗
            val markedButMissed = locator?.isEmpty == false
            val hint = if (markedButMissed) {
                "「${result.state.label}」标了关闭控件锚点，但**这一轮没认出来**（评分没到阈值）⇒ 本轮不点。" +
                    "若一直认不出：到标定页把这个锚点**重框紧一点**（只框它自己的文字 / 图标，" +
                    "别带周围会动的背景），或换一张该控件完全清晰、不动的帧来框"
            } else {
                "「${result.state.label}」还没有可用的**关闭控件锚点** ⇒ 不会自动关闭；" +
                    "到标定页把它那个「点一下就关掉 / 跳过 / 退出」的控件框成「锚点」即可"
            }
            if (hint != lastOverlaySkipNote) {
                lastOverlaySkipNote = hint
                MmLog.w(TAG, "${source.tag} 不动作: $hint")
            }
            // 标签改说「关弹窗受阻」（2026-09-29）：否则用户看到「正在关弹窗」却一直关不掉 ✗（报障原话）
            // **连续**若干轮都没锚点才报"受阻"（2026-09-30，见 [overlayAnchorMissRounds]）：
            // 画面正在切换时锚点认不出是正常的，单轮就报会冤枉程序（"报了受阻，实际都关了"）。
            overlayAnchorMissRounds++
            if (overlayAnchorMissRounds >= OVERLAY_ANCHOR_MISS_ROUNDS) {
                PopupCloseSignal.markBlocked(
                    if (markedButMissed) "锚点标了但没认出来（评分不够）" else "遮挡屏在屏，但关闭控件锚点没标",
                    // 标签据此分「去标定」还是「没认出」两说（处置完全相反，见 PopupCloseSignal）
                    needsCalibration = !markedButMissed,
                )
            }
        } else {
            // 有锚点 / 没有遮挡屏 ⇒ "没锚点"这件事断了 ⇒ 计数归零、受阻作废
            overlayAnchorMissRounds = 0
            if (!confirmed) {
                // 没有遮挡屏 ⇒ 本段结束（`PopupCloseController` 也就复位了）⇒ "受阻"作废
                PopupCloseSignal.clearBlocked()
            }
        }
        // 与"上一枪那张"的**整帧**变化：只给日志，以及"同一张画面不重复扣扳机"那道闸用
        // （不可比时 percent = null ⇒ 那道闸不参与；见 PopupCloseController）
        val change = frameChangeOf(frameBytes, frameWidth, frameHeight, frameStride)
        val outcome = popupClose.onRound(
            PopupRoundInput(
                foreground = !result.frozen,
                popupConfirmed = confirmed,
                // 三屏里**任一**在本轮原始命中即为"还在"（与状态机的候选优先级无关：这里只问"还挡着吗"）
                popupHit = result.hits.any { it in UiState.guardedOverlays },
                source = source,
                anchors = anchors,
                decisionId = "$decisionPrefix-${actionRoundSeq + 1}",
                nowMs = SystemClock.elapsedRealtime(),
                // **这一轮用的画面比上一枪新吗**（2026-09-24 真机事故）：判据必须是**本轮这张帧的拍摄时刻**，
                // 不是"最近一帧的到达时刻"—— 帧流不停时后者永远"很新"，而本轮用的可能是兜底重放的缓存副本
                // 或点击前就拍下的那一张；拿旧画面上的"弹窗仍在"去补枪 ⇒ 点到下层界面（真机点进了商城）
                frameAtMs = currentFrameAtMs,
                frameChangedPercent = change.percent,
                // **这一屏的身份**（2026-09-30 真机修）：换了它 = 新的一段 ⇒ 背靠背的两个遮挡屏
                // （新手引导 → 新手大厅）各点一次；未确认时传 null（= 没有遮挡屏，走原来的复位路径）
                overlayKey = overlay?.name,
            ),
        )
        logPopupVerification(outcome, change)
        when (val step = outcome.step) {
            is PopupStep.Skip -> {
                // 同一原因只记一次：遮挡屏长期在屏时"未标定锚点"这类原因会每轮成立，记全套等于刷屏
                if (step.note != lastOverlaySkipNote) {
                    lastOverlaySkipNote = step.note
                    MmLog.i(TAG, "${source.tag} 不动作: ${step.note}${frameChangeNote(change)}")
                }
            }

            is PopupStep.Click -> {
                // **开火前复眼**（2026-09-29，见 [preFireRecheck]）：判据过关才真的下发这一枪。
                // 它挡的是唯一一条"点进商城"的路径 —— 判定用的画面比真实屏幕旧（旧画面上是弹窗的 X，
                // 同一坐标在**当下**的屏幕上是大厅的商城入口；progress 第 189 条）。
                val preFire = preFireRecheck(
                    anchorName = step.request.anchorName,
                    frameX = step.request.frameX,
                    frameY = step.request.frameY,
                    state = result.state,
                )
                if (!preFire.allowed) {
                    if (preFire.note != lastOverlaySkipNote) {
                        lastOverlaySkipNote = preFire.note
                        MmLog.i(TAG, "${source.tag} 不动作: ${preFire.note}${frameChangeNote(change)}")
                    }
                    // 复眼否决 ⇒ 用户看到的应当是「关弹窗受阻」，而不是"正在关"（这一刻标签容易骗人）。
                    // ⚠ 这串话**会原样显示在悬浮窗菜单里**（2026-09-29 用户口径）⇒ 不许用内部黑话
                    //（原来写的是"开火前复眼：新画面上锚点不在原处"，玩家看不懂"复眼"是什么）。
                    PopupCloseSignal.markBlocked("关之前又看了一眼：那个控件已经不在原处了")
                } else {
                    actionRoundSeq++
                    lastOverlaySkipNote = null
                    // 下发前刷一次屏幕尺寸（见 [refreshScreenSizeForClick]）：同一条闸门也管弹窗那一枪
                    refreshScreenSizeForClick()
                    val verdict = ClickDispatch.submit(
                        request = step.request,
                        gameForeground = !result.frozen,
                        state = result.state,
                    )
                    MmLog.i(
                        TAG,
                        "${source.tag} 判定: 「${result.state.label}」已确认，关闭控件「${step.request.anchorName}」" +
                            "点击点 (${step.request.frameX.toInt()}, ${step.request.frameY.toInt()})" +
                            "（运行帧坐标；真正下发的屏幕坐标见 MM-Click 那一行），第 ${step.attempt} 次尝试" +
                            " → ${verdict.detail}（判定 ${step.request.decisionId}）" +
                            preFire.note +
                            frameChangeNote(change),
                    )
                    // 点掉遮挡屏这一枪之后画面同样会变：立刻回快档，好尽快确认"关掉没有"
                    if (verdict.allowed) {
                        // **真的发出去了** ⇒ 标签才配说「正在关弹窗」（2026-09-29：以前"给出 Click"就算，
                        // 于是被复眼 / 门禁挡住时标签照旧写"正在关"，用户看到"显示了正在关弹窗却没关掉"）
                        PopupCloseSignal.clearBlocked()
                        // 记下"这一枪是在哪张画面上打出去的"：后面几轮要算"自上一枪以来画面变了多少"
                        rememberClickSignature(frameBytes, frameWidth, frameHeight, frameStride)
                        useFastGear("刚下发一击（${source.tag} 关遮挡屏）")
                        // 屏幕必然变了 ⇒ 期待一帧新画面（期待落空 ⇒ 闸掉后续点击，见 [checkFrameExpectation]）；
                        // `gateEligible = true` 同理（点的是游戏里的遮挡屏）
                        FrameExpectationSignal.arm(
                            "刚下发一击（${source.tag} 关遮挡屏）",
                            gateEligible = true,
                        )
                    } else {
                        // 被门禁拒了（非前台 / 画面采集已停 / 状态未知 / 越界 / 串行 / 间隔）⇒ 同样算"受阻"
                        PopupCloseSignal.markBlocked(verdict.reason?.label ?: "点击被门禁拒绝")
                    }
                }
            }

            is PopupStep.GiveUp -> {
                // **停手的原因由决策器给出**（2026-09-30）：三条停手路径各说各的（次数到顶 / 时长到顶 /
                // 画面整幅换掉）。旧日志一律写"连续 N 次点击后仍命中，判为误匹配" —— 真机那次的 N 是 **1**、
                // 原因其实是"画面整幅换掉"，这条日志会把排障直接带偏（用户报："最后一次活动弹窗没有关掉"）。
                lastGaveUpNote = step.reason
                MmLog.w(
                    TAG,
                    "${source.tag} 停手: ${step.reason}（本段已点 ${step.attempts} 次）" +
                        "——停止点击，换屏或遮挡屏消失后自动复位（宁可漏关，不可错点）",
                )
            }
        }

        // 给界面（顶部状态标签）一个**只读的**"正在关弹窗"信号：attempts=0 且 gaveUpReason 非空 = 已停手。
        // 用户报障（2026-09-28）："自动关闭弹窗时没有显示提示信息" —— 那时标签对弹窗闭环一无所知。
        // 用户报障（2026-09-30）："**最后一次活动弹窗没有关掉**" —— 停手这条路没写进信号 ⇒ 标签从
        // 「正在关弹窗」退回「待命」并被整条摘掉，弹窗压在屏上而界面一个字都不说 ⇒ 现在必须说出来。
        // ⚠ `gaveUpReason` 只在控制器 `gaveUp` 为真时写：段复位（弹窗消失 / 换屏）**它自己就清空**，
        // 不会留下"永远已停手"的假象。
        PopupCloseSignal.update(
            attempts = if (popupClose.gaveUp) 0 else popupClose.attempt,
            gaveUpReason = if (popupClose.gaveUp) lastGaveUpNote else "",
        )

        // **弹窗段结束了就扔掉基线**（`attempt == 0` 即控制器已复位：没有"上一枪"可比了）。
        //
        // 为什么按这个判据（2026-09-28 真机抓到的一处泄漏）：段结束走的是"未见活动弹窗"那条路，
        // 而那时 `awaitingVerification` 可能已被上一轮消费掉 ⇒ `Closed` 观测不会产生 ⇒ 原先挂在
        // "Closed / GiveUp" 上的清理根本没执行。实录：23:25:48 还在打
        // `FR-01 不动作: 目标不在前台｜自上一枪整帧变化 89.5%` —— 那是拿几分钟前那张画面在比。
        if (popupClose.attempt == 0) lastClickSignature = null
    }

    /** 开火前复眼的结果：是否放行 + 一行说明（放行时的说明追加在 "FR-01 判定" 那行后面）。 */
    private data class PreFireVerdict(val allowed: Boolean, val note: String)

    /** 开火前复眼要复核的那张**当下画面**（[acquireFreshFrame] 现取，不排队）。 */
    private class FreshFrame(val bytes: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /**
     * **开火前复眼**（2026-09-29，用户提问"如何绝对防止在大厅里误点进入商城"）：
     * 下发这一枪之前**现取一张当时最新的画面**，在**同一个锚点**上再确认一次"关闭控件还在原处、位置没挪"。
     * 判据本体是纯逻辑 [PreFireRecheck]（有单测）。
     *
     * 为什么只有这一条路（progress 第 189 条）：**关闭控件（X）的模板在大厅上并不命中**
     * （回到大厅后 `activity_popup_e1/e2/e3` 只有 0.06~0.28）⇒ "点进商城"不可能来自
     * "在活的大厅画面上认出弹窗"，只能来自**判定用的画面比真实屏幕旧** —— 旧画面上是弹窗的 X，
     * 同一坐标在当下的屏幕上正好是商城入口（2026-09-24 18:51 / 2026-09-28 21:41 两次都是）。
     *
     * ## ⚠ 第一版是死代码（2026-09-29 真机实测抓出来的 —— 别再照那个思路写）
     *
     * 第一版拿的是 [lastFrameBytes]，那份像素副本**每 300ms 才更新一次**（[PIXEL_CACHE_INTERVAL_MS]，
     * 全帧 18MB 的 memcpy 不可能每帧做），而且它是在**同一帧上、送进识别之前**抓的 ⇒ 处理帧永远不比它旧，
     * "缓存比决策帧新"那句判断**恒不成立**。真机实录（01:31 那段连发 3 枪）：日志里**一次
     * `开火前复眼 ✓` 都没有**。更根本的原因：**判定就跑在帧线程上**，这一轮执行期间新帧回调全在排队
     * ⇒ "手上有没有更新的画面"这个问题，**只有主动去 ImageReader 现取**才会有答案（[acquireFreshFrame]）。
     *
     * 两条口径：
     * - **现取不到更新的帧**（`acquireLatestImage()` 给 null）⇒ **本条不参与**（`allowed = true`）：
     *   平台**只在内容变化时才产帧**（这也正是"静止兜底重放"存在的原因）⇒ 队列里没有新帧 = 屏幕自我们
     *   那张画面之后**没有变过**，交给原有那几道闸判即可，不必在这里多拦一次；
     * - **取到了更新的一帧** ⇒ 只转**这一条锚点的窗口**灰度、重跑一次该锚点的匹配（成本 ≈ 一次单信号
     *   匹配，几十毫秒）：不再命中 / 位置漂移超过 [PreFireRecheck.MAX_DRIFT_PX] ⇒ **不放行**。
     *
     * ⚠ 决策帧本身有多旧由 `PopupCloseController.maxFrameAgeMs`（500ms）负责 —— 取到的那一帧必定
     * **不比决策帧旧**（它在队列里排在后面），所以不需要在这里再设一道"帧龄上限"。
     */
    private fun preFireRecheck(
        anchorName: String,
        frameX: Double,
        frameY: Double,
        state: UiState,
    ): PreFireVerdict {
        val locator = anchorLocator ?: return PreFireVerdict(true, "")
        val fresh = acquireFreshFrame() ?: return PreFireVerdict(true, "｜复眼：无更新帧（屏幕未变）")

        // X 还在原处吗：只转这一个锚点的窗口灰度，重跑一次该锚点的匹配
        val region = locator.windowInFrame(fresh.width, fresh.height, state, anchorName)
            ?: return PreFireVerdict(true, "")
        val freshGray = RgbaToGray.toGrayRegions(
            fresh.bytes,
            fresh.width,
            fresh.height,
            fresh.rowStride,
            listOf(region),
        )
        val hit = locator.locateWithin(freshGray, state, anchorName, region)
        if (PreFireRecheck.agrees(frameX, frameY, hit?.frameX, hit?.frameY)) {
            return PreFireVerdict(true, "｜复眼 ✓（现取最新帧上仍是同一落点）")
        }
        val where = if (hit == null) {
            "已经不在了"
        } else {
            "挪到了 (${hit.frameX.toInt()}, ${hit.frameY.toInt()})"
        }
        return PreFireVerdict(
            false,
            "开火前复眼：**现取的最新画面**上「$anchorName」$where（原落点 " +
                "(${frameX.toInt()}, ${frameY.toInt()})）——落点已变，这一枪不发（宁可漏关，不可错点）",
        )
    }

    /**
     * 现取一张**当下**的画面（开火前复眼用）：`acquireLatestImage()` 丢开队列里更旧的那些、直接给最新
     * 的一张（真机 ≈55fps ⇒ 约 20~40ms 前），**不排队**、也不影响下一轮（下一轮照旧从队列取最新帧）。
     *
     * 为什么敢在这里直接向 ImageReader 取：**本方法跑在帧线程上**，与 `handleFrameAvailable` 是
     * **同一条线程**（判定就是在那一轮里做的）⇒ 不存在并发访问；取到的帧随即 `close()`。
     *
     * @return 像素副本与几何；会话已结束 / 队列里没有比我们那张更新的帧 / 读失败 ⇒ null
     *   （调用方按"屏幕没有变化"处理：交给原有那几道闸）
     */
    private fun acquireFreshFrame(): FreshFrame? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            return null // 会话释放竞态：reader 已关闭
        } ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            FreshFrame(bytes, image.width, image.height, plane.rowStride)
        } catch (t: RuntimeException) {
            MmLog.w(
                TAG,
                "开火前复眼：现取最新帧失败（按没有更新的画面处理）：${t.javaClass.simpleName} ${t.message}",
            )
            null
        } finally {
            image.close()
        }
    }

    /**
     * **"自上一枪以来整帧变了多少"**（2026-09-28 加，**只记日志、不参与任何判定**）。
     *
     * 为什么要有它：路线①（把"允许补枪"从"画面够老"改成"**画面真的换了一幅**"）需要一个**阈值**
     * —— "变多少才算换了弹窗"。阈值猜低了会退回 2026-09-24 那次"多补一枪进商城"，猜高了等于没改。
     * 所以先只测量、把数值写进日志，跑真机弹窗序列之后再据此定阈值。
     *
     * ## ⚠ 必须与**原始帧**比，不能与识别层那份 `gray` 比（第一次上榜就是这么量错的）
     *
     * 识别层的 `gray` 来自 `RgbaToGray.toGrayRegions` —— **只转换识别窗口，窗口外全 0**。
     * 2026-09-28 真机实录：三个**完全不同**的活动弹窗（金色「小黄鸭联动」→ 紫色「比翼连心家具礼包」
     * → 蓝色「三丽鸥家族联动时装」）被依次关掉，而按窗口化灰度算出来的"变化"只有 **0.0% ~ 3.6%**，
     * 我据此几乎否掉了一条本来可行的判据 —— 用户拿三张截图纠正："换弹窗后帧差距明显很大的"。
     *
     * ⇒ 改成用 [FrameSignature] 从**原始 RGBA** 隔点采样（整屏 57.0 万点），这个数才代表
     * "屏幕上真的换了多少"。
     *
     * 基线在**这一枪打出去的那一刻**拍下（[rememberClickSignature]），**弹窗段一结束就清空**
     * （判据：控制器 `attempt == 0`，见 [stepPopupClose] 末尾）—— 否则弹窗关完很久之后的 FR-01 行
     * 还会拿几分钟前的画面当基线（真机实录：23:25:48 打出过 `…｜自上一枪整帧变化 89.5%`）。
     */
    private var lastClickSignature: ByteArray? = null

    /** 记下"这一枪打在哪张画面上"（只留一份：够算"自上一枪以来"）。 */
    private fun rememberClickSignature(rgba: ByteArray, width: Int, height: Int, rowStride: Int) {
        lastClickSignature = FrameSignature.sample(rgba, width, height, rowStride)
    }

    /**
     * 本轮的"自上一枪以来"测量结果。
     *
     * @param percent 与上一枪那张画面的整帧变化（0..100）；`null` = **不可比**（还没开过枪 / 几何变了）
     * @param points 采样点数（0 = 没采样）
     */
    private data class FrameChange(val percent: Double?, val points: Int)

    /**
     * 与上一枪那张画面比：多少比例的采样点亮度差 ≥ [FrameSignature.PIXEL_DELTA]。
     *
     * 一轮只算一次（日志与 `PopupCloseController` 那道"同一张画面不重复点"的闸共用这个数）。
     * 没开过枪时直接返回（**不采样**）—— 平时这条测量不花任何代价。
     */
    private fun frameChangeOf(rgba: ByteArray, width: Int, height: Int, rowStride: Int): FrameChange {
        val before = lastClickSignature ?: return FrameChange(null, 0)
        val after = FrameSignature.sample(rgba, width, height, rowStride)
        return FrameChange(FrameSignature.changedPercent(before, after), before.size)
    }

    /**
     * 日志后缀。没有可比对象时给**空串**：宁可什么都不说，也不能拿"不可比"当"没变"。
     */
    private fun frameChangeNote(change: FrameChange): String {
        val percent = change.percent ?: return ""
        return "｜自上一枪整帧变化 ${String.format(Locale.US, "%.1f", percent)}%（采样 ${change.points} 点）"
    }

    /** FR-01 上一枪的验证结论（没有待验证的点击时不记）。 */
    private fun logPopupVerification(outcome: PopupRoundOutcome, change: FrameChange) {
        when (val verification = outcome.verification) {
            is PopupVerification.None -> Unit

            is PopupVerification.Closed -> {
                MmLog.i(
                    TAG,
                    "FR-01 弹窗已消失（本段共点击 ${verification.attempts} 次）",
                )
                // 这一段结束了：扔掉基线，免得后续 FR-01 行拿着几分钟前的画面继续报"变化 X%"
                lastClickSignature = null
            }

            // 只作观测，**不是"失败"**：弹窗仍在既可能是没关掉，也可能是前一个关掉后冒出了新的那个
            // （2026-09-13 真机实测二者在日志上无法区分，故不再由它决定是否停手）。
            // **但"同一张帧上不补点"**（2026-09-24 真机事故）：新帧没来就不再点，见 `PopupCloseController`。
            is PopupVerification.StillPresent -> MmLog.i(
                TAG,
                "FR-01 点击后仍命中弹窗（本段已点击 ${verification.attempts} 次）——" +
                    "可能没关掉、也可能是新弹窗；按上限继续，不做单次成败判定" +
                    "（本轮画面须晚于上一枪才补点，见 PopupCloseController）" +
                    frameChangeNote(change),
            )
        }
    }

    /**
     * 记录一帧处理耗时并输出汇总（T1-9，NFR-01）：满窗（连续 100 帧）输出一行；
     * 三个统计器逐帧同步记录（必须全部记录后再判断总段是否满窗，否则分段样本会漏记）。
     */
    private fun recordProcessingCost(totalNs: Long, grayNs: Long) {
        val gray = grayTimingStats.record(grayNs / 1_000_000.0)
        val detect = detectTimingStats.record((totalNs - grayNs) / 1_000_000.0)
        val total = timingStats.record(totalNs / 1_000_000.0) ?: return
        MmLog.i(
            TAG,
            "单帧处理耗时统计: 连续 ${total.sampleCount} 帧，总 P95 ${total.p95DisplayMs}ms" +
                "（灰度 ${gray?.p95DisplayMs}ms / 识别 ${detect?.p95DisplayMs}ms），" +
                "平均 ${total.avgDisplayMs}ms，最大 ${total.maxDisplayMs}ms（NFR-01 阈值 20ms）",
        )
    }

    /**
     * 逐信号匹配耗时统计（T1-13b）：每个信号**各自**连续 100 次为一窗，满窗输出一行。
     *
     * 口径（与离线探针 `SpeedupProbeTest.profilePerSignalCost` 一致）：**纯匹配耗时**——
     * 不含灰度转换与方向归一（轮级固定开销）、不含节流等待
     * （一轮搜几个信号就产生几个样本；本轮不搜则不产生样本，故"每轮只搜 1 个信号"时本行即该步的真实成本）。
     */
    private fun recordSignalCosts() {
        recognitionLoop.lastSignalCostMs.forEach { (name, costMs) ->
            val summary = signalCostStats.getOrPut(name) { FrameTimingStats() }.record(costMs)
                ?: return@forEach
            MmLog.i(
                TAG,
                "信号耗时统计: $name 连续 ${summary.sampleCount} 次，P95 ${summary.p95DisplayMs}ms，" +
                    "平均 ${summary.avgDisplayMs}ms，最大 ${summary.maxDisplayMs}ms",
            )
        }
    }

    private fun maybeLogStats(now: Long) {
        // 会话刚开始时 [lastStatsAt] 还是 0 ⇒ 若直接算"窗口"，打出来的是**距开机的毫秒数**，
        // 是个假数。真机实录（2026-09-29 02:59:12 / 03:03:51）：
        // `帧管线统计: 窗口 1382024208ms 接收 1 帧 / 处理 1 帧`（≈16 天，正是手机开机时长）
        // —— 会话建立那一刻管线已经在处理帧，而把 `lastStatsAt` 设成当前时间的
        // [startSession] 还没跑到（它之后才把状态置 ACTIVE）。
        // ⇒ 第一次只起表，不报窗口。
        if (lastStatsAt == 0L) {
            lastStatsAt = now
            return
        }
        if (now - lastStatsAt < STATS_WINDOW_MS) return
        val signalNote = if (recognitionLoop.signalCount == 0) "，未标定（无判定）" else ""
        // T2-1：搜索范围取证——"每轮只搜期望集合 / 待命期只搜启动页"可由本行直接读出
        val searchedNote =
            "实际参与匹配 $searchedSymbolTotal 次（$searchedRounds 轮：" +
                "单信号 ${searchedRounds - multiSignalRounds - idleRounds} 轮 / " +
                "多信号 $multiSignalRounds 轮 / 不搜 $idleRounds 轮）"
        MmLog.i(
            TAG,
            "帧管线统计: 窗口 ${now - lastStatsAt}ms 接收 $framesReceived 帧 / 处理 $framesProcessed 帧；" +
                "识别 $cyclesRun 轮（信号 ${recognitionLoop.signalCount} 个$signalNote），" +
                "$searchedNote；当前界面状态「${recognitionLoop.state.label}」；" +
                "最近帧 ${lastFrameWidth}x$lastFrameHeight",
        )
        lastStatsAt = now
        framesReceived = 0
        framesProcessed = 0
        cyclesRun = 0
        searchedRounds = 0
        multiSignalRounds = 0
        idleRounds = 0
        searchedSymbolTotal = 0
    }

    /** 搜索集合的日志文本（T2-1）：空集明示"不搜"，避免与"搜了没命中"混淆。 */
    private fun searchedText(names: Set<String>): String =
        if (names.isEmpty()) "（本轮不搜）" else "「${names.sorted().joinToString("、")}」"

    private fun startForegroundCompat() {
        val notification = buildNotification()
        // minSdk 34 起始终带类型（Android 14+ 的顺序要求：先 startForeground 再建会话）
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {

        private const val TAG = CaptureSessionSignal.TAG

        /**
         * **本进程建立过几次采集会话**（2026-10-01 加，方案 2 的取证那一半）。
         *
         * 为什么记它：真机 4 次"零投喂"（20:49 / 21:02 / 22:05 / 01:16）**全部**发生在"同一个进程里
         * 再次建立会话"之后 —— 而平台对同一个 `MediaProjection` 只允许一次 `createVirtualDisplay`
         * （第二次会抛异常崩进程，见 [attachMirror]）⇒ "重建"这条路本来就在平台的灰色地带。
         * 有了这个计数，日志里"本进程第 N 次建立投屏会话"就能与"是否零投喂"直接对上号。
         */
        @Volatile
        private var sessionsInProcess = 0

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 2
        private const val VIRTUAL_DISPLAY_NAME = "MM-Capture"

        /**
         * NFR-02 自适应两档（T1-13 人速口径）：间隔 = 两轮之间的**休息时间**（不含单轮耗时）。
         *
         * 取值依据：单信号真机 ≈62ms（小窗口）/ ≈270ms（大窗口），故一轮周期 ≈260~470ms，
         * 与人工「看到界面 → 点击」的 200~400ms 同量级（FR-04 硬性 #8 要求相邻点击 ≥300ms）。
         *
         * **跑号期间恒用这一档**（2026-09-24 用户拍板）：跑号期每轮都有事做，见 `processPixels` 的节流块。
         */
        private const val ACTIVE_INTERVAL_MS = 200L

        /**
         * 「没找到」提示里最多列几个读到的名字段（2026-09-24）：够看出"是不是被读缺了字"就行，
         * 列全套会把提示变成一屏（而且那一条已经进了 `MM-NameReader` 的"读到原文"日志）。
         */
        private const val NAME_HINT_LIMIT = 6

        /**
         * 待命期诊断日志的间隔（2026-09-22）：**每秒一行，且不带条件**。
         *
         * 之前做过"只在变化时打"和"每 5 秒打一次"，两种都**漏掉了要看的那一段** ——
         * 而连续几轮推断被真机证伪之后，唯一可行的做法就是**把每一轮都如实记下来**，
         * 让数据去区分三种可能：① 根本没跑（无新帧）；② 跑了但没搜这条信号；
         * ③ 搜了但没过线。**分数是唯一能区分①③的东西**，所以连分数一起打。
         */
        private const val STANDBY_REPORT_INTERVAL_MS = 1_000L

        /**
         * **跑号期**那行"画面判定 + 分数"的间隔（2026-09-24 加）：比待命期稀（5 秒一行）。
         *
         * 为什么跑号期也要这行：认不出画面时跑号只会说"画面还没看清（可能正在加载）"，
         * 而"加载慢"与"标志不匹配"的处理方式完全不同（等 / 去重框）—— 有分数就能当场分清。
         * 取 5 秒：跑号一轮 ≈0.2~0.5s，1 秒一行会把日志淹掉；5 秒足够看清"一直没过线"。
         */
        private const val PATROL_REPORT_INTERVAL_MS = 5_000L

        /**
         * 文字识别的**最小间隔**（2026-09-22）：每秒最多一次，间隔内复用上次结果。
         *
         * 为什么必须节流：这一步**同步**跑在帧线程上（引擎超时 1 秒），而第 5 / 9 步每轮都要判 ——
         * 真机实录帧管线统计 `窗口 24070ms / 处理 11 帧`（约 2.2 秒一轮），
         * 连带"关掉好友列表后多久能认出农场"一起变慢（用户报障"要等很长时间"）。
         * 第 5 步更贵：**两列各读一次**（真机合计 761ms / 上限 1000ms），所以复用与"换步骤即重读"两条
         * 都由 [readNames] 统一保证。
         *
         * 为什么复用安全：名字不会在零点几秒里变成另一个；这两步的结论本来就要等画面，
         * 多等不到 1 秒不改变任何判定语义（红线 3 依旧是全等 + 唯一）。
         */
        private const val OCR_MIN_INTERVAL_MS = 1_000L

        /**
         * **画面静止时的兜底重放间隔**（2026-09-22，真机根因修复）。
         *
         * 识别原本只由 `ImageReader` 的"新帧到达"驱动，而**画面静止时系统不产生新帧**
         * ⇒ 识别整段停摆 ⇒ `UiStateSignal.status` / `recentHits` 停在旧值 ⇒
         * 用户在"画面已变、识别未更新"的窗口里点菜单就被拦。
         *
         * 真机实录（用户报障"停止后重新点只拜访被拦 / 要等十几秒才正常"）：
         * ```
         * 03:49:53  当前「农场」…                     ← 最后一次识别
         *           ★ 之后 23 秒一行日志都没有 ★
         * 03:50:08  停止：停在第 8 步
         * 03:50:11  菜单被拦：现在停在「好友列表」｜最近命中：好友列表
         * 03:50:16  当前「好友列表」｜命中：农场       ← 识别一恢复就命中农场
         * ```
         * ⇒ 那 3 秒里判定读的是**停止前的旧结论**。兜底轮让静止画面也按此间隔刷新一次识别。
         */
        private const val IDLE_ROUND_INTERVAL_MS = 1_000L

        /**
         * 静止兜底心跳的间隔（按**次**计，2026-09-22 由 5 调成 60）：
         * 它的作用只是"证明这条自递归链条还活着"，根因定位后没必要每 5 秒刷一行。
         */
        private const val IDLE_HEARTBEAT_TICKS = 60L

        /**
         * "最新画面"的缓存间隔（2026-09-22）：像素提取是一次 memcpy（约 1.4MB），
         * 按 60fps 全量做没必要；兜底轮本身也只要 1 秒一份。300ms 足以覆盖
         * 「画面刚变过的那一帧」（真机出问题的正是这一帧）。
         */
        private const val PIXEL_CACHE_INTERVAL_MS = 300L

        /**
         * 稳定档：**500ms**（2026-09-20 用户口径从 1000ms 调小）。
         *
         * 依据：需求原文是「确实稳定时 **≤1s**」——**上界**，不是固定值；用户明确"这个 App 不会一直挂后台，
         * 跑完农场祝福就退出，不必太节省资源"。真机实测一次状态推进 ≈ 2 个轮次（§2.2 滞回要连续 2 轮命中），
         * 故稳定档每降 500ms，每次"画面变化 → 确认"就快 ~1s —— 这正是用户反馈的"识别有点慢"。
         *
         * 代价如实记录：待命期单轮处理 ≈0.93s（6 条信号），轮周期从 ≈1.93s 降到 ≈1.43s，
         * 单核占用约 +15 个百分点。**若将来要长时间挂机**，把它调回 1000ms 即可（唯一改动点）。
         */
        private const val STABLE_INTERVAL_MS = 500L

        /**
         * **弹窗闭环期间的轮询间隔**（ms，2026-09-29 加，取 120）。
         *
         * 为什么比快档（[ACTIVE_INTERVAL_MS] = 200）还快：补枪延迟里**有一段就是轮询间隔本身** ——
         * "画面换了一幅"的判据（`PopupCloseController.renderedFloorMs` = 350ms）要到**下一轮**才可能被看到，
         * 于是每枪多等一整拍。真机实录（01:00 那段，已上"画面变了就认账"那版）：
         * ```
         * 01:00:09.559 第 1 枪
         * 01:00:10.103 (+0.54s) 变化 39.9%，但只比上一枪晚 223ms（<350ms）⇒ 再等一拍
         * 01:00:10.391 (+0.83s) 第 2 枪   ← 这一拍就是间隔
         * ```
         * ⇒ 间隔从 200 收到 120（一轮实际 ≈识别 120ms + 间隔）之后，那一拍能早 ≈0.1~0.2s 到。
         * 代价：只在**弹窗闭环那几秒**里多耗一点 CPU（识别本来就 ~120ms/轮）；停手后立刻回两档自适应。
         */
        private const val POPUP_INTERVAL_MS = 120L

        /**
         * 第 5 步「跨屏查找列表」的滑动参数（2026-09-29）：**在列表区域里从 25% 处拖到 75% 处**，历时 300ms。
         *
         * `SCROLL_EDGE_RATIO = 0.25` ⇒ 拖动距离 = 列表可见高度的一半：大致换掉一屏，又不至于"甩"出去
         * （甩出去要等惯性停，还可能直接跳过目标）。`SCROLL_DRAG_MS = 300` 接近"人慢慢划一屏"；
         * 太短会被系统当甩动，太长会让一次滑动占掉近一秒。
         *
         * ⚠ **这两个数只能靠真机调**（不同列表的行高与惯性不同）：日志里每次滑动都会打
         * `滑动下发｜…屏幕点: (x, y) -> (x2, y2)` 与滑完那一屏读到的行，照它调即可。
         */
        private const val SCROLL_EDGE_RATIO = 0.25

        /** 一次列表拖动的手势时长（ms），见 [SCROLL_EDGE_RATIO]。 */
        private const val SCROLL_DRAG_MS = 300L

        /**
         * **好友列表**的滑动边距比（2026-10-01 真机实测后单独一套，取 **0.35**：0.35↔0.65 = 区域高的 30%）。
         *
         * ## ⚠ 这个数是"距离边缘多远"，**越小 = 手指行程越长**（行程 = 高 ×(1-2×ratio)）
         *
         * 演变（都记下来，免得再踩）：
         * - **0.35**（配慢划 600ms）：由"同样 546px 手指位移、内容却走了一屏多"推出，怕**跳屏漏人**；
         *   2026-10-01 用新加的**共同行位移**日志量出来：这一档**只走 79~539px**，而**一屏 ≈ 1119px**
         *   ⇒ 一次拖动不足半屏（用户两次报："每次滑动的距离其实不够一屏"）；
         * - **0.6**（同日试图放长 ✗）：**搞反了方向** —— 行程 = 高 ×(1-2×ratio)，>0.5 起止点交叉、
         *   手势方向直接翻过来（真机实录：回顶那一枪变成 `(2551,853) -> (2551,631)` 手指**向上**划，
         *   列表反而往下走，用户报"**好友列表根本没有往上移动**"）；
         * - **0.25（本条）**：行程 = 半个区域高 ≈ 560px ⇒ 内容位移预期 ≈ 0.6~0.8 屏（第 5 步一直用的就是 0.25）。
         * - ⚠ 目标是"**一次拖动 ≈ 一屏**"：既能少滑几下，又不会跳屏（每滑一下都会重读整屏）。
         *   下轮看日志 `共同行位移 Npx`：偏小（<600）再往下调（0.2），偏大（>1200，有跳屏风险）就收（0.3~0.35）。
         *   `NameLocating.scrollDragOf` 现在对 0.05~0.45 之外的值**当场报错**，不会再安静地反向划屏。
         */
        private const val FRIEND_SCROLL_EDGE_RATIO = 0.25

        /**
         * **好友列表**的拖动时长（ms，取 **700**；服务器列表是 [SCROLL_DRAG_MS] = 300）。
         *
         * 依据：真机实测 300ms 那一下在好友列表里明显带了惯性（滑完内容还在跑，日志里"两次读数一致"
         * 常常要等两轮才成立）。慢划 ≈ 人用手慢慢翻一页，既不带甩动，也给游戏时间把这一屏画完。
         * 2026-10-01 把 [FRIEND_SCROLL_EDGE_RATIO] 从 0.35 放长到 0.6 时，时长同时 600 → **700**：
         * **手指走得更远时要再慢一点**，否则"又快又远"会变成甩动（甩动会让位移不可控、可能跳屏）。
         */
        private const val FRIEND_SCROLL_DRAG_MS = 700L

        /**
         * **好友列表**滑完等画面稳定的时长（ms，取 **1200**；服务器列表用 [ServerListScan.SETTLE_MS] = 800）。
         *
         * 依据（2026-10-01 实测）：点击门禁的"两次手势之间的间隔"是从**上一次手势结束**算起的
         * （`ClickGate` 的 `lastFinishedMs` + 300ms），而好友的拖动时长是 600ms
         * ⇒ 从"滑动下发"到"允许下一次手势"实际是 600 + 300 ≈ **900ms**。
         * 800ms 会在**每一枪**都撞上「与上一次点击间隔不足」（真机日志实录过一次），
         * 白等一轮；取 1200ms 既过了门禁，也给游戏时间把这一屏画完。
         */
        private const val FRIEND_SETTLE_MS = 1_200L

        /**
         * 第 5 步**开火前复眼**那条窄带的半高（运行帧像素，取 **56**），见 [rowRecheckBand]。
         *
         * 依据：真机**行距** ≈90px（列表区域 1119px / 12 行）、一行文字自身的框高 ≈40px
         * ⇒ ±56 装得下这一行、又明显小于一个行距 ⇒ **列表整体挪一行时，目标行的文字已落在带外**
         *（那正是复眼该拦的情形）。**带越窄越严**：真机若发现正常情况也总被拦（读数被切坏），
         * 就往 72/90 调；反过来想更严就收到 40。与 `ServerListScan.ROW_STABLE_PX` 一起构成复眼口径。
         */
        private const val ROW_RECHECK_HALF_PX = 56


        private const val STATS_WINDOW_MS = 10_000L
        private const val MAX_IMAGES = 2

        /**
         * **resize 前至少要收到的帧数**（2026-09-24 真机三次复现后的缓解，见 [applyMirrorResize]）：
         * 会话刚建立就 `resize()` + `setSurface()` ⇒ 平台**再也不产帧**；等帧流稳下来再动就没有这个问题。
         */
        private const val RESIZE_MIN_FRAMES = 3

        /** 推迟 resize 的轮询步长。 */
        private const val RESIZE_DEFER_STEP_MS = 300L

        /** 推迟 resize 的总上限：到点照常应用（之后由帧流看门狗兜底，不再无限等）。 */
        private const val RESIZE_DEFER_LIMIT_MS = 6_000L

        /**
         * **「换镜像表面」自救的上限**（见 [checkSurfaceRetry]）：每会话最多几次。
         *
         * 取 2：一次可能是运气（合成器还没接上），两次还不回来就说明这条通路救不回来 ⇒
         * 不该继续抽（每次都要新建 ~18MB×2 的缓冲），该如实让用户重新授权 / 重启 App。
         */
        private const val SURFACE_RETRY_MAX = 2

        /** 两次「换镜像表面」之间的冷却（ms）：换完要给合成器一点时间，别连着抽。 */
        private const val SURFACE_RETRY_COOLDOWN_MS = 30_000L

        /**
         * 连续"点击被拒"几次才把原因挂到菜单上（见 [CLICK_DENY_NOTE_STREAK] 的用法）。
         *
         * 取 3 的理由与"关弹窗受阻"那条同一口径（连续 3 轮才 `markBlocked`）：偶发一次多半下一轮就过去了，
         * 连拒 3 次说明**卡住了**，这时才值得让用户看见"为什么它不动"。
         */
        private const val CLICK_DENY_NOTE_STREAK = 3

        /**
         * 转屏后**确认帧流**的等待时间（毫秒）：超过它还没有新帧 ⇒ 判定镜像失效并主动结束会话
         * （见 [checkMirrorStall]）。取值 10 秒：转屏后合成器出帧是毫秒级的，10 秒都没动静只能是失效；
         * 反过来它也不会把"正常的静止画面"误判（那种情况根本没有转屏事件，压根不走这条判据）。
         */
        private const val MIRROR_STALL_TIMEOUT_MS = 10_000L

        /**
         * "转屏前画面还在刷新"的判定窗（毫秒）：只有在这个窗内有过新帧，才认为转屏后**应该**继续出帧
         * （见 [noteDisplayChanged]）。取 3 秒：游戏在前台时它的帧间隔远小于它；
         * 而"停在静态界面上"的画面往往几十秒都不产帧（真机实录 86s），会被这道闸挡掉。
         */
        private const val FRAME_FRESH_MS = 3_000L

        /**
         * 「画面连续多少帧近乎全平」就告警（[noteFrameContent]）。
         *
         * 取 3：节流档位是 200 / 1000ms ⇒ 3 帧大约 0.6~3 秒，既不会被偶发的一帧黑画面骗到，
         * 也不至于等太久才说话。
         */
        private const val FLAT_FRAME_ALERT_STREAK = 3

        /**
         * 目标在前台却**持续全平这么久** ⇒ 判为镜像失效、**主动结束会话**（口径 B，2026-09-24 用户拍板）。
         * 取 12 秒：明显长于正常加载黑屏，又不用用户干等（见 [noteFrameContent]）。
         */
        private const val FLAT_FRAME_END_SESSION_MS = 12_000L

        /**
         * 几何不一致的宽限（ms）：转屏那一瞬间"帧还是旧几何、屏幕已经翻过去"是必然的，
         * 只要求**持续**不一致才判定（见 [checkGeometryGuard]）。取 3 秒：resize 正常时几十~上百毫秒就完成，
         * 剩下的余量留给"转屏途中连着翻几次"的情况，又不会让一条错几何的流水线跑太久。
         */
        private const val GEOMETRY_MISMATCH_GRACE_MS = 3_000L

        private const val EXTRA_RESULT_CODE = "capture_result_code"
        private const val EXTRA_RESULT_DATA = "capture_result_data"

        /**
         * "自上一枪以来整帧变化"的采样口径（步长 / 阈值）在 [FrameSignature] 里 ——
         * 它是纯逻辑、有单测，也**只能**用原始帧采样（识别层的 gray 是窗口化的，比不出整帧变化）。
         */

        private const val SOURCE_USER_CREATED = "用户授权会话建立"
        private const val SOURCE_SYSTEM_STOP = "系统侧回收"
        private const val SOURCE_APP_STOP = "应用主动停止"

        /**
         * 转屏后帧流停滞（镜像失效）⇒ 主动结束会话：**状态如实变「未激活」**，
         * 用户看到熟悉的"请到授权页重新授权"，而不是静默卡死（见 [checkMirrorStall]）。
         */
        private const val SOURCE_MIRROR_STALL = "镜像失效：画面不再更新（请重新建立采集）"

        /** 几何守卫判定的终止原因（帧几何与屏幕几何持续不一致，见 [checkGeometryGuard]）。 */
        private const val SOURCE_GEOMETRY_MISMATCH = "帧几何与屏幕不一致（请重新建立采集）"

        /** 由前台界面在用户完成系统采集授权后调用；凭证只经内存传递。 */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
