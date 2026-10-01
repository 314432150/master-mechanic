package com.example.mastermechanic.patrol

import android.content.Context
import android.os.SystemClock
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
                -> start(request.kind, request.friendName, request.serverName)

            PatrolRequestSignal.Kind.RESUME -> {
                if (PatrolSession.resume(SystemClock.elapsedRealtime())) {
                    MmLog.i(TAG, "继续：从${PatrolSession.current?.step?.number}步接着来")
                } else {
                    MmLog.i(TAG, "继续：没有可继续的流程（未在跑，或没有失败 / 暂停）")
                }
            }

            PatrolRequestSignal.Kind.STOP -> {
                val running = PatrolSession.current
                PatrolSession.stop()
                MmLog.i(
                    TAG,
                    if (running == null) {
                        "停止：没有进行中的流程"
                    } else {
                        "停止：停在第 ${running.step.number} 步"
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
            // 顺序轮换时这里已经是**具体名字**，并记下"它是轮换算出来的"——
            // 第 5 步真换成功后才推进游标（见 PatrolSession.targetServerFromRotation）
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
                "${kind.briefText}：顺序轮换 → 本次换到「${resolution.serverName}」（${resolution.detail}）",
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
        PatrolRequestSignal.Kind.RESUME,
        PatrolRequestSignal.Kind.STOP,
            -> false
    }
}
