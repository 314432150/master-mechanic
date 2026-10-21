package com.example.mastermechanic.patrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.mastermechanic.action.Haptics
import com.example.mastermechanic.badge.BadgeSession
import com.example.mastermechanic.badge.BadgeTask
import com.example.mastermechanic.capture.FrameFreshness
import com.example.mastermechanic.capture.FrameReadiness
import com.example.mastermechanic.capture.RoundCostWatch
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.servers.ServerRotation

/**
 * 悬浮窗菜单请求 → 跑号流程（M4-T4-4c）：把"用户点了菜单"翻译成"开始 / 继续 / 停止"。
 *
 * M3 只把请求**广播并留痕**（`PatrolRequestSignal`），不做任何动作；这里才是真正的消费方。
 *
 * 两条口径：
 * - **起点不成立就不启动**：画面认不出来（或停在好友列表 / 设置页这类不在起点表里的界面）时，
 *   只提示用户先退出来，**绝不猜测性启动**（红线 2 —— 启动后点的第一下就是错的那一下）；
 * - **继续不等于重来**：`PatrolFlow.resume` 从失败 / 暂停的那一步接着来，不重跑前面的步骤（FR-05）。
 *
 * 回调发生在**悬浮窗主线程**，本对象只改 [PatrolSession] 的 `@Volatile` 字段，帧线程读到的仍是完整快照。
 */
object PatrolRequestConsumer {

    private const val TAG = PatrolRequestSignal.TAG

    @Volatile
    private var installed = false

    /**
     * 应用上下文（**顺序轮换**要在启动前读服务器清单与游标，见 [resolveNextServer]）。
     * 由 [install] 从常驻守护服务注入；null = 还没装（那种情况下"下一个"解析不了，不启动并写日志）。
     */
    @Volatile
    private var appContext: Context? = null

    /** 延后重试用的主线程 Handler（只在"等画面就绪"时用到，见 [awaitFirstFrame]）。 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 正在等第一帧的起点时刻（0 = 没在等）。 */
    @Volatile
    private var waitingSinceMs = 0L

    /** "等画面就绪"这句话是不是已经说过（同一段等待只留一行）。 */
    @Volatile
    private var waitNoteLogged = false

    /** 正在等"算得动"的起点时刻（0 = 没在等，见 [awaitWarm]）。 */
    @Volatile
    private var warmWaitSinceMs = 0L

    /** "识别预热中"这句话是不是已经说过（同一段等待只留一行）。 */
    @Volatile
    private var warmNoteLogged = false

    /**
     * 起始请求的代次（2026-10-19）：等画面期间用户可能按了「停止」/又点了别的流程 ——
     * 那时**这一份待启动的请求必须作废**，否则 10 秒后它会凭空开跑（与 `PatrolSession.generation` 同一类保护）。
     */
    @Volatile
    private var startToken = 0L

    /**
     * 消费方是否已就位（T4-5 真机修，2026-09-20）：**常驻守护服务没起 = 菜单请求没人接单**。
     *
     * 悬浮窗在执行启动类动作前问一句，好在菜单里直接说清"为什么点了没反应"——
     * 真机上正是这么卡住的：请求广播出去了、日志也写了，用户那边却只是"菜单收起了、什么都没发生"。
     */
    val isInstalled: Boolean get() = installed

    /**
     * 订阅菜单请求（幂等：重复调用不会重复注册）。
     *
     * 带 [context] 是因为「换号 → 下一个」/ 预设「顺序轮换」要**在启动前**把"下一个区服"解析出来
     * （读服务器清单 + 游标，见 [resolveNextServer]）——那两份都在盘上。
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        PatrolRequestSignal.addListener(::onRequest)
    }

    /** 退订（服务停止时用；不做也无泄漏，但显式一点更干净）。 */
    fun uninstall() {
        if (!installed) return
        installed = false
        PatrolRequestSignal.removeListener(::onRequest)
    }

    private fun onRequest(request: PatrolRequestSignal.Request) {
        when (request.kind) {
            PatrolRequestSignal.Kind.VISIT_PRESET,
            PatrolRequestSignal.Kind.SWITCH_NEXT,
            PatrolRequestSignal.Kind.SWITCH_SERVER,
            PatrolRequestSignal.Kind.VISIT_ONLY,
                -> {
                // 新请求作废之前那份"还在等画面"的待启动（见 [startToken]）
                startToken++
                start(request.kind, request.friendName, request.serverName)
            }

            // **换号取立牌**（temp/badge-share-1021）：走自己的任务（换号段 + 立牌支线），
            // 不进 `PatrolSession`（两条流程互斥、各自的状态机不同）
            PatrolRequestSignal.Kind.BADGE_COLLECT -> {
                startToken++
                startBadge()
            }

            PatrolRequestSignal.Kind.RESUME -> {
                if (PatrolSession.resume(SystemClock.elapsedRealtime())) {
                    MmLog.i(TAG, "继续：从${PatrolSession.current?.step?.number}步接着来")
                } else {
                    MmLog.i(TAG, "继续：没有可继续的流程（未在跑，或没有失败 / 暂停）")
                }
            }

            PatrolRequestSignal.Kind.STOP -> {
                // 停止同样作废"还在等画面"的待启动
                startToken++
                waitingSinceMs = 0L
                waitNoteLogged = false
                warmWaitSinceMs = 0L
                warmNoteLogged = false
                val running = PatrolSession.current
                PatrolSession.stop()
                // 立牌任务（temp/badge-share-1021）同样受这一个「停止」管：它可能**没有**巡查流程
                // （换号段之外），不在这里停掉就会出现"点了停止还在点"。
                val badge = BadgeSession.current
                BadgeSession.stop()
                MmLog.i(
                    TAG,
                    when {
                        running != null -> "停止：停在第 ${running.step.number} 步"
                        badge != null -> "停止：立牌任务停在「${badge.current}」${badge.stepText}"
                        else -> "停止：没有进行中的流程"
                    },
                )
            }
        }
    }

    /**
     * 试着开始一次执行：先问"现在这个画面能不能当起点"。
     *
     * 判据在 [PatrolStartGate]（**与悬浮窗共用同一处**）；这里是**兜底**一道 ——
     * 菜单已在自己那一侧拦过，但广播与判定之间画面可能变了，所以再问一次（不通过就写日志）。
     * 弹窗遮挡 / 认不出来都不启动：这两种情况下"第一下点哪里"根本没有依据（红线 2）。
     */
    private fun start(
        kind: PatrolRequestSignal.Kind,
        targetFriend: String? = null,
        targetServer: String? = null,
    ) {
        val range = PatrolStartGate.rangeFor(kind) ?: return
        // **等本次会话的第一帧**（2026-10-19，C）：同进程重建会话时真机**经常一帧都收不到**
        //（见 `CaptureService.attachMirror` 的说明），而识别结论还挂着上一场会话的「大厅」
        // ⇒ 起点判定放行、流程照跑，前几步全在空画面上白等。这里先等一眼，**不要求用户记住"等几秒"**。
        if (!awaitFirstFrame(kind, targetFriend, targetServer, startToken)) return
        // **等这个进程"算得动"再开跑**（2026-10-19，2）：重新授权后的 1~2 分钟里，连灰度换算都慢 9 倍
        //（见 `RoundCostWatch` 的真机表），这时开跑就是每步多花好几秒。判据来自采集侧每轮实测耗时。
        if (!awaitWarm(kind, targetFriend, targetServer, startToken)) return
        // 顺序轮换：菜单没带区服名 ⇒ 现在把它解析成一个**具体**的区服名（第一步就要用到它）
        val rotation = targetServer == null && kind.usesServerRotation()
        val resolved = if (rotation) {
            when (val pick = resolveNextServer(kind)) {
                is ServerRotation.Resolution.Next -> pick.serverName
                else -> return // 清单空 / 读不了 / 没装上下文：已写日志，**不启动**（不许猜一个区服）
            }
        } else {
            targetServer
        }
        val uiState = UiStateSignal.status
        // 滞回要连续 2 轮才转移 ⇒ 也看"最近一轮命中的画面"（见 PatrolStartGate.recentHits 的说明）
        val recentHits = UiStateSignal.recentHits
        val blocked = PatrolStartGate.blockReason(
            uiState = uiState,
            kind = kind,
            recentHits = recentHits,
        )
        if (blocked != null) {
            // 判定依据一起留痕（2026-09-22）：与悬浮窗那一侧同一口径，便于对账
            val hits = recentHits.joinToString("、") { it.label }.ifEmpty { "（一屏都没命中）" }
            MmLog.w(TAG, "${kind.briefText}：$blocked（不启动）｜滞回结论「${uiState.label}」，最近命中：$hits")
            return
        }
        // 起点画面同样按"有效画面"取：只从滞回结论读的话，刚退出上一屏时会读到 NOT_A_START
        // ⇒ 判据放行了、这里却启动不起来（两处必须用同一个画面）
        val effective = PatrolStartGate.effectiveState(uiState, recentHits)
        val scene = (PatrolScenes.read(effective) as? PatrolScenes.Reading.Ready)?.scene
        val started = if (scene == null) {
            null
        } else {
            // 带上时刻：每一步的时间预算从这里起算（PatrolFlow.STEP_TIMEOUT_MS）
            // 带上目标好友：第 9 步要在好友列表里按这个名字定位（2026-09-21）
            // 带上目标区服：第 5 步要在选服页里按这个名字定位（2026-09-24）；
            // 「下一个」时这里已经是**具体名字**，并记下"它是算出来的"——只用于日志；
            // 游标推进**不看来路**（2026-10-19 U8）：任何真换到的区服都写，见 ServerRotation.switchedTo
            PatrolSession.start(
                scene,
                range,
                SystemClock.elapsedRealtime(),
                targetFriend,
                resolved,
                serverFromRotation = rotation,
            )
        }
        if (started == null) {
            MmLog.w(TAG, "${kind.briefText}：起点不成立（当前画面「${uiState.label}」）——不启动")
            return
        }
        // ⚠ 这里要打**判定用的那个画面**（`effective`，滞回 + 最近命中修过），不是原始识别结论：
        // 两者可以不一致（滞回把「未知」按最近命中提升成「大厅」）。2026-09-30 排障时日志是
        // `换号拜访：从第 3 步开始（未知）` —— 让人一眼误判成"未知也能启动"（其实启动用的是「大厅」）。
        val sceneNote = if (effective == uiState) {
            "起点 ${effective.label}"
        } else {
            "起点 ${effective.label}（识别结论「${uiState.label}」，取最近命中）"
        }
        MmLog.i(TAG, "${kind.briefText}：从第 ${started.step.number} 步开始（$sceneNote）")
        // **震动反馈**（2026-10-20 用户口径"流程开始和结束时各一个"）：放在"真的启动起来了"之后 ——
        // 起点不成立那条提前 return 了，不该震（那说明什么都没开始）。结束那一下在
        // `CaptureService.stepPatrol`（完成 / 中止各一次，节奏与这里不同，见 [Haptics]）。
        appContext?.let { Haptics.flowStarted(it) }
    }

    /**
     * 开始一次**立牌收集任务**（临时需求 temp/badge-share-1021，2026-10-21）。
     *
     * 与 [start] 同一套前置（起点判定 / 给用户看得懂的日志），差别只有两处：
     * - 起点区间固定 `SWITCH_ONLY`（换号段）；
     * - 进度写进 [BadgeSession] 而不是 [PatrolSession]（两条流程互斥，启动前先停巡查）。
     *
     * 刻意**不**做"等第一帧 / 等预热"那两道等待：它们按 [PatrolRequestSignal.Kind] 回调 [start]，
     * 而这里走另一条启动路径；起点判定本身会在状态未知时拦下（红线 1），不会误动作。
     */
    private fun startBadge() {
        val context = appContext ?: run {
            MmLog.w(TAG, "换号取立牌：消费方还没拿到应用上下文 ⇒ 不启动")
            return
        }
        val uiState = UiStateSignal.status
        val recentHits = UiStateSignal.recentHits
        val blocked = PatrolStartGate.blockReason(
            uiState = uiState,
            kind = PatrolRequestSignal.Kind.BADGE_COLLECT,
            recentHits = recentHits,
        )
        if (blocked != null) {
            val hits = recentHits.joinToString("、") { it.label }.ifEmpty { "（一屏都没命中）" }
            MmLog.w(TAG, "换号取立牌：$blocked（不启动）｜滞回结论「${uiState.label}」，最近命中：$hits")
            return
        }
        val effective = PatrolStartGate.effectiveState(uiState, recentHits)
        val scene = (PatrolScenes.read(effective) as? PatrolScenes.Reading.Ready)?.scene
        if (scene == null) {
            MmLog.w(TAG, "换号取立牌：起点画面「${effective.label}」读不出场景 ⇒ 不启动")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val switch = PatrolFlow.start(scene, PatrolFlow.Range.SWITCH_ONLY, now)
        if (switch == null) {
            MmLog.w(TAG, "换号取立牌：起点不成立（当前「${effective.label}」）⇒ 不启动")
            return
        }
        if (PatrolSession.current != null) {
            MmLog.i(TAG, "换号取立牌：先停掉正在跑的巡查流程（同一时刻只允许一条流程）")
            PatrolSession.stop()
        }
        val result = BadgeTask.start(context, switch, now)
        if (!result.started) {
            MmLog.w(TAG, "换号取立牌：${result.reason}（不启动）")
            return
        }
        MmLog.i(TAG, "换号取立牌：起点 ${effective.label}，从换号第 ${switch.step.number} 步开始")
        Haptics.flowStarted(context)
    }

    /**
     * **开跑前"等第一帧"**（2026-10-19，C；用户口径："别要求用户主动重新授权后等几秒再跑流程"）。
     *
     * ## 它挡的是哪一种启动
     *
     * `FrameFreshness.ageMs() == null` = **本次会话从建立起一帧都没收到过**。这时
     * `UiStateSignal` 还挂着上一场会话的结论（真机：重新授权后仍报「大厅」）⇒ 起点判定放行，
     * 流程照跑，而前几步全在空画面上等 —— 用户看到的是"点了没反应 / 卡半天"。
     *
     * ## 为什么**只等"从没收到帧"**，不等"帧旧"
     *
     * 帧龄大有两种完全不同的原因：① 会话刚建、通路还没热（该等）；② **静态页本来就不产帧**
     * （`设置页` 就是，真机实录见 `PatrolFlow` 的注释：`兜底重放（8615ms 前）` 是正常现象）。
     * ②上等下去**永远等不到**，只会让每次点击「拜访」都白等几秒 ⇒ 只认 ①。
     *
     * ## 纪律
     *
     * - 有上限 [FIRST_FRAME_WAIT_MS]（与 `FrameFreshness.FIRST_FEED_DEADLINE_MS` 同一档）：
     *   到点照常尝试，起点判定会如实说明为什么没启动 —— 绝不无限挂着；
     * - 每 [FIRST_FRAME_POLL_MS] 复查一次，帧一来立刻开跑（用户感觉不到延迟）；
     * - 期间用户按「停止」/ 又点了别的流程 ⇒ [startToken] 变了，这一份待启动**作废**（不凭空开跑）。
     *
     * @return true = 可以继续（已就绪 / 超时放弃等待）；false = 已排好下一次复查，本次不启动
     */
    private fun awaitFirstFrame(
        kind: PatrolRequestSignal.Kind,
        targetFriend: String?,
        targetServer: String?,
        token: Long,
    ): Boolean {
        if (FrameFreshness.ageMs() != null) {
            if (waitingSinceMs != 0L) {
                waitingSinceMs = 0L
                waitNoteLogged = false
                MmLog.i(TAG, "${kind.briefText}：画面已就绪（本次会话收到画面了）⇒ 开始")
            }
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (waitingSinceMs == 0L) waitingSinceMs = now
        val waited = now - waitingSinceMs
        if (waited >= FIRST_FRAME_WAIT_MS) {
            waitingSinceMs = 0L
            waitNoteLogged = false
            MmLog.w(
                TAG,
                "${kind.briefText}：等了 ${waited / 1000} 秒仍没收到本次会话的第一帧 ⇒ " +
                    // 处置只有一种（2026-10-20 与 `floating_frame_starved_notice` / `run_frame_starved` 对齐）：
                    // 2026-10-01 真机实证"同一次会话里重新授权照样零帧"⇒ 别把"重新授权"写进提示，那是白试一轮
                    "照常尝试（起点判定会如实说明；若界面提示「没被投喂」，只重新授权通常没用，需菜单「退出」后重开 App）",
            )
            return true
        }
        if (!waitNoteLogged) {
            waitNoteLogged = true
            MmLog.i(
                TAG,
                "${kind.briefText}：本次会话还没收到任何画面 ⇒ 先等第一帧" +
                    "（每 ${FIRST_FRAME_POLL_MS}ms 复查，最多 ${FIRST_FRAME_WAIT_MS / 1000} 秒，帧一到就开始）",
            )
        }
        mainHandler.postDelayed({
            if (token != startToken) return@postDelayed // 期间被停止 / 被新请求顶掉
            start(kind, targetFriend, targetServer)
        }, FIRST_FRAME_POLL_MS)
        return false
    }

    /**
     * **等这个进程"算得动"再开跑**（2026-10-19，用户口径："别要求用户自己等"）。
     *
     * ## 它挡的是哪一段
     *
     * 真机同一次进程运行里的对照（详见 `RoundCostWatch` 的类注释）：重新授权后的头一两分钟，
     * 每轮识别 **≈2.8 秒**（灰度 P95 从 3.7ms 涨到 35.5ms，识别从 78ms 涨到 697ms ——
     * 连最笨的灰度循环都慢 9 倍，说明是**进程拿不到 CPU**，不是算法/页面变复杂）。
     * 这时开跑，第 3 步要 22 秒；等它落回 ≈82ms 再跑，同一步 6 秒。
     *
     * ## 判据与纪律
     *
     * - 判据是采集侧**每轮实测耗时的中位数**（[FrameReadiness]），不是"有没有帧"——
     *   C 之前用"帧龄"是选错了指标：这段时间帧一直在来，只是我们算不动；
     * - **没有证据就不拦**（[RoundCostWatch.Status.NO_DATA]：样本不足 / 已过期 ⇒ 直接放行）；
     * - 最多等 [WARM_WAIT_MS]（60 秒），每秒复查；到点照常开跑并把真实读数写进日志 ——
     *   宁可慢也不无限挂着（用户看得见原因）；
     * - 期间「停止」/ 新请求 ⇒ [startToken] 变了，这份待启动作废。
     *
     * @return true = 可以继续；false = 已排好下一次复查，本次不启动
     */
    private fun awaitWarm(
        kind: PatrolRequestSignal.Kind,
        targetFriend: String?,
        targetServer: String?,
        token: Long,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val status = FrameReadiness.status(now)
        if (status != RoundCostWatch.Status.WARMING) {
            if (warmWaitSinceMs != 0L) {
                val samples = FrameReadiness.sampleCount(now)
                val typical = FrameReadiness.typicalMs(now)?.toInt()
                warmWaitSinceMs = 0L
                warmNoteLogged = false
                MmLog.i(TAG, "${kind.briefText}：预热完成（最近 $samples 轮中位数 ${typical}ms）⇒ 开始")
            }
            return true
        }
        if (warmWaitSinceMs == 0L) warmWaitSinceMs = now
        val waited = now - warmWaitSinceMs
        val samples = FrameReadiness.sampleCount(now)
        val typical = FrameReadiness.typicalMs(now)?.toInt()
        if (waited >= WARM_WAIT_MS) {
            warmWaitSinceMs = 0L
            warmNoteLogged = false
            MmLog.w(
                TAG,
                "${kind.briefText}：等了 ${waited / 1000} 秒仍然算得慢（最近 $samples 轮中位数 ${typical}ms，" +
                    "门限 ${RoundCostWatch.WARM_TYPICAL_MS.toInt()}ms）⇒ 照常开始：" +
                    "每一步会更慢，日志会写明原因（多半是重新授权后头一两分钟，或系统在抢占 CPU）",
            )
            return true
        }
        if (!warmNoteLogged) {
            warmNoteLogged = true
            MmLog.i(
                TAG,
                "${kind.briefText}：**识别预热中**（最近 $samples 轮中位数 ${typical}ms > " +
                    "${RoundCostWatch.WARM_TYPICAL_MS.toInt()}ms，多出现在重新授权后的头一两分钟）⇒ " +
                    "先等它降下来，最多 ${WARM_WAIT_MS / 1000} 秒，**不需要你操作**",
            )
        }
        mainHandler.postDelayed({
            if (token != startToken) return@postDelayed // 期间被停止 / 被新请求顶掉
            start(kind, targetFriend, targetServer)
        }, WARM_POLL_MS)
        return false
    }

    /**
     * 把「顺序轮换」解析成**一个具体区服名**（读服务器清单 + 游标，见 `ServerRotation`）。
     *
     * **不猜**：清单为空 / 读不了 / 消费方还没拿到上下文 —— 一律写清日志并**不启动流程**
     * （起点判定那套"不成立就不启动"同一口径）。用户看到的是"点了没动静 + 一行原因"，
     * 而不是跑完五步才在选服页停下。
     */
    private fun resolveNextServer(kind: PatrolRequestSignal.Kind): ServerRotation.Resolution {
        val context = appContext
            ?: return ServerRotation.Resolution.Failed("消费方还没拿到应用上下文（不该发生，请重开守护）")
        val resolution = try {
            ServerRotation.resolveNext(context)
        } catch (e: Exception) {
            // 读盘失败（权限 / 目录异常）：如实回传，别把"读不了"演成"没有下一个"
            ServerRotation.Resolution.Failed("服务器清单 / 游标读不了：${e.message ?: e.javaClass.simpleName}")
        }
        when (resolution) {
            is ServerRotation.Resolution.Next -> MmLog.i(
                TAG,
                "${kind.briefText}：「下一个」→ 本次换到「${resolution.serverName}」（${resolution.detail}）",
            )

            ServerRotation.Resolution.EmptyList -> MmLog.w(
                TAG,
                "${kind.briefText}：服务器清单是空的 ⇒ 不启动（先在配置页添加一条）",
            )

            is ServerRotation.Resolution.Failed -> MmLog.w(
                TAG,
                "${kind.briefText}：${resolution.reason} ⇒ 不启动",
            )
        }
        return resolution
    }

    /**
     * 这个菜单项是不是"**按顺序轮换换下一个**"（区服名由游标决定，而不是用户点的某一行）。
     *
     * 只看两项：「换号 → 下一个」；以及预设为「顺序轮换」时的一键拜访（那种请求**不带**区服名，
     * 见 `FloatingWindow.onVisitPreset` —— 带名字 = 预设是"固定区服"）。
     * 「只拜访」不换号，`SWITCH_SERVER` 自带名字，都不在此列。
     */
    private fun PatrolRequestSignal.Kind.usesServerRotation(): Boolean = when (this) {
        PatrolRequestSignal.Kind.SWITCH_NEXT,
        PatrolRequestSignal.Kind.VISIT_PRESET,
            -> true

        PatrolRequestSignal.Kind.SWITCH_SERVER,
        PatrolRequestSignal.Kind.VISIT_ONLY,
        // 立牌任务按**清单顺序**整轮跑，与"下一个"的游标语义无关
        PatrolRequestSignal.Kind.BADGE_COLLECT,
        PatrolRequestSignal.Kind.RESUME,
        PatrolRequestSignal.Kind.STOP,
            -> false
    }

    /**
     * "等第一帧"的上限（ms，2026-10-19）：**与 `FrameFreshness.FIRST_FEED_DEADLINE_MS` 同一个数**。
     *
     * 取同一个数不是巧合：那条判据 8 秒判"这次授权没被投喂"并让界面改说处置 ⇒ 这里等同样长，
     * 两边的结论在时间上对得上（用户不会看到"界面说没被投喂、流程却说还在等"）。
     */
    private const val FIRST_FRAME_WAIT_MS = FrameFreshness.FIRST_FEED_DEADLINE_MS

    /** 等第一帧时的复查间隔（ms）：帧一到就开跑，别让用户感觉到延迟。 */
    private const val FIRST_FRAME_POLL_MS = 300L

    /**
     * "等算得动"的上限（ms，2026-10-19）：真机重新授权后的慢窗口长约 **60~90 秒**
     *（23:47:13 建立会话 → 23:49:15 的读数才回到 82ms）。
     *
     * 取 60 秒：覆盖大部分窗口，又不会让"这台机器一直慢"的情况下无限挂着 ——
     * 到点照跑，并把真实读数写进日志（用户看得见为什么慢）。
     */
    private const val WARM_WAIT_MS = 60_000L

    /** 等"算得动"时的复查间隔（ms）：1 秒一次（与采集侧静止兜底轮同节奏，读数才有更新）。 */
    private const val WARM_POLL_MS = 1_000L
}
