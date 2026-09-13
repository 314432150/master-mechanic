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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.mastermechanic.MainActivity
import com.example.mastermechanic.R
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.action.PopupCloseController
import com.example.mastermechanic.action.PopupRoundInput
import com.example.mastermechanic.action.PopupRoundOutcome
import com.example.mastermechanic.action.PopupStep
import com.example.mastermechanic.action.PopupVerification
import com.example.mastermechanic.calibration.CalibrationFramePool
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.capture.FrameThrottle
import com.example.mastermechanic.capture.FrameTimingStats
import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.decision.AnchorLocator
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.RoundResult
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.foreground.ForegroundSignal
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

    /** 识别循环（T1-4 接入）：会话建立时加载标定产物（T1-5）；缺失 / 解析失败回退空配置。仅帧线程访问。 */
    private var recognitionLoop = RecognitionLoop.uncalibrated()

    /** 锚点定位器（T2-4）：随标定产物构建；null = 未标定产物（FR-01 不产生点击）。仅帧线程访问。 */
    private var anchorLocator: AnchorLocator? = null

    /** FR-01 弹窗闭环（T2-4）：纯逻辑决策，会话重建即复位。仅帧线程访问。 */
    private var popupClose = PopupCloseController()

    /** FR-01 判定序号（NFR-05 审计链）：与 `MM-Click` 日志的「判定: fr01-N」对齐。仅帧线程访问。 */
    private var actionRoundSeq = 0L

    /** 上一次 FR-01 不动作的原因（同一原因只记一次日志：弹窗长期在屏时"未标定锚点"会每轮成立）。仅帧线程访问。 */
    private var lastFr01SkipNote: String? = null

    /** 标定产物记录的帧尺寸（0 = 未标定）；与运行帧不同时按画面区归一（T1-11c）。仅帧线程访问。 */
    private var calibratedFrameWidth = 0
    private var calibratedFrameHeight = 0

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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 系统侧终止（用户在系统 UI 停止 / 会话被回收）：本回调是唯一信号（ADR-001 第 3 条）
            if (stopSource == null) stopSource = SOURCE_SYSTEM_STOP
            stopSelf()
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
            Log.w(TAG, "采集会话启动失败：缺少授权凭证，服务退出（需在前台界面重新授权）")
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
            Log.w(TAG, "创建采集会话失败：${t.javaClass.simpleName} ${t.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            Log.w(TAG, "创建采集会话失败：系统未返回会话实例")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader

        // 帧线程提升为前台优先级（T1-10b）：降低后台调度把重处理挤到小核的概率
        val thread = HandlerThread("MM-CaptureFrames", Process.THREAD_PRIORITY_FOREGROUND)
        thread.start()
        frameThread = thread
        val frameHandler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ handleFrameAvailable() }, frameHandler)

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            frameHandler,
        )

        // 会话（重）建立：重建节流与识别循环（滞回状态不跨会话连续）；标定产物重新加载（保存后重开会话即生效）
        frameThrottle = FrameThrottle(ACTIVE_INTERVAL_MS)
        val calibration = try {
            CalibrationStore.load(this)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "标定产物加载失败，回退未标定运行：${t.message}")
            null
        }
        recognitionLoop = calibration?.toLoop() ?: RecognitionLoop.uncalibrated()
        // 锚点定位器与 FR-01 决策随会话重建（T2-4）：重试计数不跨会话（与滞回状态不跨会话同一口径）
        anchorLocator = calibration?.toAnchorLocator()
        popupClose = PopupCloseController()
        actionRoundSeq = 0
        lastFr01SkipNote = null
        calibratedFrameWidth = calibration?.frameWidth ?: 0
        calibratedFrameHeight = calibration?.frameHeight ?: 0
        frameSizeWarned = false
        if (calibration != null) {
            // 锚点数量单独报（T2-4）：FR-01 到底能不能点，第一眼就看这条——0 个锚点时弹窗即使在屏，
            // 闭环也只会"跳过"（宁可不点，红线 3/7）
            val anchorCount = calibration.signals.count { it.role == SignalRole.ANCHOR }
            Log.i(
                TAG,
                "标定产物已加载：信号 ${calibration.signals.size} 个（其中锚点 $anchorCount 个），" +
                    "标定帧 ${calibration.frameWidth}x${calibration.frameHeight}",
            )
        } else {
            Log.i(TAG, "未加载标定产物（未标定），以空配置运行")
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
        CaptureSessionSignal.update(CaptureSessionStatus.ACTIVE, SOURCE_USER_CREATED)
    }

    private fun releaseSession() {
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
        // 识别循环随会话终止停止更新：界面状态信号回到「未知」（不得残留旧状态误导展示）
        UiStateSignal.reset("采集会话终止")
        // FR-01 状态不跨会话（T2-4）：锚点定位器与重试计数一并作废
        anchorLocator = null
        popupClose = PopupCloseController()
        lastFr01SkipNote = null
    }

    /**
     * 帧到达回调（帧线程）：取最新帧并释放旧帧，节流后交给识别处理点（帧数据不落盘）。
     */
    private fun handleFrameAvailable() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            return // 会话释放竞态：reader 已关闭
        } ?: return

        framesReceived++
        val now = SystemClock.elapsedRealtime()
        // 标定帧录制（T1-5b）：旁路于识别节流，每秒最多 1 帧；仅目标前台时保存
        CalibrationFramePool.maybeCapture(this, image, now, ForegroundSignal.isForeground)
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
     * 帧处理点（T1-4）：帧 → 灰度 → 识别循环（含方向归一，T1-11c）→ 状态机；按 NFR-02 自适应调整节流间隔；
     * 单帧处理耗时按 T1-9 口径测量（灰度转换 + 识别循环含归一，含滞回）。
     *
     * 非前台轮由识别循环内部冻结（§1.2 不识别、FR-09 不消耗滞回计数）；
     * 调用方保证本方法返回前 [image] 未被关闭（buffer 有效）。
     */
    private fun processFrame(image: Image) {
        if (calibratedFrameWidth != 0 && !frameSizeWarned &&
            (image.width != calibratedFrameWidth || image.height != calibratedFrameHeight)
        ) {
            frameSizeWarned = true
            Log.i(
                TAG,
                "运行画布 ${image.width}x${image.height} 与标定帧 " +
                    "${calibratedFrameWidth}x$calibratedFrameHeight 方向不同（建会话时机不同），" +
                    "已按画面区几何归一后继续识别（T1-11c）",
            )
        }
        val startNs = SystemClock.elapsedRealtimeNanos()
        val gray = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // 窗口化灰度（T1-10b）：仅转换识别所需区域，区域外置零不影响判定；
            // 区域已是「标定窗口按画面区换算到运行帧」的结果（T1-11c），供归一化采样
            RgbaToGray.toGrayRegions(
                bytes,
                image.width,
                image.height,
                plane.rowStride,
                recognitionLoop.windowRegions(image.width, image.height) +
                    anchorRegions(image.width, image.height),
            )
        } catch (t: RuntimeException) {
            // 预期外的帧布局 / 数据不足：跳过本轮（不进入滞回），记录备查（不静默失败）
            Log.w(TAG, "帧转换失败，跳过本轮识别：${t.javaClass.simpleName} ${t.message}")
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
        // 「守护待命只搜启动页 → 命中启动页后扩为弹窗期集合 → 命中大厅后收回」
        if (result.searched != lastSearchedNames) {
            lastSearchedNames = result.searched
            Log.i(TAG, "本轮搜索集合变化: " + searchedText(result.searched))
        }

        if (result.frozen != lastLoopFrozen) {
            lastLoopFrozen = result.frozen
            Log.i(
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
        if (!result.frozen) {
            val target = if (result.settled) STABLE_INTERVAL_MS else ACTIVE_INTERVAL_MS
            if (target != lastAppliedIntervalMs) {
                Log.i(
                    TAG,
                    "节流间隔切换: ${lastAppliedIntervalMs}ms -> ${target}ms" +
                        "（${if (result.settled) "确实稳定轮" else "变化 / 过渡 / 未达预期轮"}）",
                )
                lastAppliedIntervalMs = target
                frameThrottle.setInterval(target)
            }
        }

        result.transition?.let {
            // T1-13 ⑥ 取证：转移轮记录本轮实际搜索的信号名（补 T1-12 判据①⑤的取证缺口）
            Log.i(
                TAG,
                "状态转移: ${it.from.label} -> ${it.to.label}（${it.reason}）；本轮搜索 " +
                    searchedText(result.searched),
            )
            UiStateSignal.update(it.to, it.reason)
        }

        // FR-01 弹窗闭环（T2-4）：识别 → 点击 → 验证（非前台轮由决策器自行跳过）
        stepPopupClose(result, gray)

        // 节流基准回填到「本轮结束」（T1-13）：使间隔成为两轮之间的休息时间，不与单轮耗时叠加、也不退化。
        frameThrottle.markProcessedEnd(SystemClock.elapsedRealtime())
    }

    /**
     * 锚点窗口（T2-4，T2-3b 口径）：锚点按状态启用，采集层必须把它们的窗口**一并纳入灰度转换**——
     * 否则锚点在"只转了标志窗口"的帧上读到全零像素，静默失败（不报错，只是永远不命中）。
     *
     * 纳入「上一轮状态 ∪ 活动弹窗」：只纳入上一轮状态的话，弹窗**刚被确认的那一轮**锚点读到的是
     * 未转换的像素 → 白等一轮（真机 200ms~1s）。活动弹窗锚点通常只有 1~2 条小窗口，常驻纳入的成本可忽略。
     */
    private fun anchorRegions(width: Int, height: Int): List<PixelBounds> {
        val locator = anchorLocator ?: return emptyList()
        return locator.windowRegions(width, height, recognitionLoop.state) +
            locator.windowRegions(width, height, UiState.ACTIVITY_POPUP)
    }

    /**
     * FR-01 弹窗闭环（T2-4，B1）：每轮一次「验证上一枪 → 决定这一轮要不要下发」。
     *
     * 判定与下发结果都进日志——真机核对「为什么点 / 为什么没点」只看这两行（`MM-Capture` 的 FR-01 行 +
     * `MM-Click` 的审计行，判定 ID 对齐）。点击本身不在这里实现：一律经 [ClickDispatch]（红线 6）。
     */
    private fun stepPopupClose(result: RoundResult, gray: GrayImage) {
        val confirmed = result.state == UiState.ACTIVITY_POPUP
        // 锚点只在状态**确认**后定位：未确认就定位，等于用未确认的画面去找点击点（红线 7）
        val locator = anchorLocator
        val anchors = if (confirmed && locator != null) locator.locate(gray, result.state) else emptyList()
        val outcome = popupClose.onRound(
            PopupRoundInput(
                foreground = !result.frozen,
                mode = ClickDispatch.mode,
                popupConfirmed = confirmed,
                popupHit = UiState.ACTIVITY_POPUP in result.hits,
                anchors = anchors,
                decisionId = "fr01-${actionRoundSeq + 1}",
            ),
        )
        logPopupVerification(outcome)
        when (val step = outcome.step) {
            is PopupStep.Skip -> {
                // 同一原因只记一次：弹窗长期在屏时"未标定锚点"这类原因会每轮成立，记全套等于刷屏
                if (step.note != lastFr01SkipNote) {
                    lastFr01SkipNote = step.note
                    Log.i(TAG, "FR-01 不动作: ${step.note}")
                }
            }

            is PopupStep.Click -> {
                actionRoundSeq++
                lastFr01SkipNote = null
                val verdict = ClickDispatch.submit(
                    request = step.request,
                    gameForeground = !result.frozen,
                    state = result.state,
                )
                Log.i(
                    TAG,
                    "FR-01 判定: 活动弹窗已确认，关闭控件「${step.request.anchorName}」" +
                        "点击点 (${step.request.frameX.toInt()}, ${step.request.frameY.toInt()})" +
                        "（运行帧坐标），第 ${step.attempt} 次尝试 → ${verdict.detail}" +
                        "（判定 ${step.request.decisionId}）",
                )
            }

            is PopupStep.GiveUp -> Log.w(
                TAG,
                "FR-01 放弃: 连续 ${step.attempts} 次点击后弹窗仍命中，判为误匹配，**停止点击**并记录" +
                    "（FR-01 失败处理：宁可漏关，不可错点）；弹窗消失后自动复位",
            )
        }
    }

    /** FR-01 上一枪的验证结论（没有待验证的点击时不记）。 */
    private fun logPopupVerification(outcome: PopupRoundOutcome) {
        when (val verification = outcome.verification) {
            is PopupVerification.None -> Unit

            is PopupVerification.Closed -> Log.i(
                TAG,
                "FR-01 验证通过: 弹窗已消失，上一枪生效（本次共点击 ${verification.attempts} 次）",
            )

            is PopupVerification.StillPresent -> Log.i(
                TAG,
                "FR-01 验证未通过: 点击后弹窗仍命中（已尝试 ${verification.attempts} 次）",
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
        Log.i(
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
            Log.i(
                TAG,
                "信号耗时统计: $name 连续 ${summary.sampleCount} 次，P95 ${summary.p95DisplayMs}ms，" +
                    "平均 ${summary.avgDisplayMs}ms，最大 ${summary.maxDisplayMs}ms",
            )
        }
    }

    private fun maybeLogStats(now: Long) {
        if (now - lastStatsAt < STATS_WINDOW_MS) return
        val signalNote = if (recognitionLoop.signalCount == 0) "，未标定（无判定）" else ""
        // T2-1：搜索范围取证——"每轮只搜期望集合 / 待命期只搜启动页"可由本行直接读出
        val searchedNote =
            "实际参与匹配 $searchedSymbolTotal 次（$searchedRounds 轮：" +
                "单信号 ${searchedRounds - multiSignalRounds - idleRounds} 轮 / " +
                "多信号 $multiSignalRounds 轮 / 不搜 $idleRounds 轮）"
        Log.i(
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 2
        private const val VIRTUAL_DISPLAY_NAME = "MM-Capture"

        /**
         * NFR-02 自适应两档（T1-13 人速口径）：间隔 = 两轮之间的**休息时间**（不含单轮耗时）。
         *
         * 取值依据：单信号真机 ≈62ms（小窗口）/ ≈270ms（大窗口），故一轮周期 ≈260~470ms，
         * 与人工「看到界面 → 点击」的 200~400ms 同量级（FR-04 硬性 #8 要求相邻点击 ≥300ms）。
         */
        private const val ACTIVE_INTERVAL_MS = 200L
        private const val STABLE_INTERVAL_MS = 1000L
        private const val STATS_WINDOW_MS = 10_000L
        private const val MAX_IMAGES = 2

        private const val EXTRA_RESULT_CODE = "capture_result_code"
        private const val EXTRA_RESULT_DATA = "capture_result_data"

        private const val SOURCE_USER_CREATED = "用户授权会话建立"
        private const val SOURCE_SYSTEM_STOP = "系统侧回收"
        private const val SOURCE_APP_STOP = "应用主动停止"

        /** 由前台界面在用户完成系统采集授权后调用；凭证只经内存传递。 */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
