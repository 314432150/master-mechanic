package com.example.mastermechanic.patrol

import com.example.mastermechanic.decision.UiState

/**
 * **第 9 步「搜索式查找」的状态机**（2026-10-01 用户拍板："先看当前屏；没有就搜索"）。
 *
 * ## 它管什么
 *
 * 用户校正过的点击顺序（见 [PatrolAnchors.FRIEND_SEARCH_ENTRY] 的注释）：
 * ```
 * ① 点「搜好友」→ ② 点「请输入好友昵称」→ ③ 输入昵称（原生写入）→ ④ 收输入法（多半用不上）→ ⑤ 点「搜索」
 * ```
 * 本类只回答"**下一步该干什么**"：该点哪个锚点、该写文字、还是"搜索已经发出去了"。
 * 具体怎么点（定位 + 下发 + 门禁）由调用方（`CaptureService`）落地 —— 纯逻辑，有单测。
 *
 * ## 口径（2026-10-01 用户拍板，写在 [PatrolAnchors.FRIEND_SEARCH_ENTRY] 里）
 *
 * - 搜索结果里**多个同名小号仍取最上面那一个**（沿用老口径，命中判定一个字没改）；
 * - **搜索之后不再滑屏**：搜索都找不到 ⇒ **如实停下并提示**（不许"再滑滑看"）。
 *
 * ## 与"滑屏找"的关系
 *
 * 搜索链只在"**当前屏先找一次没命中**"之后才启动；三个锚点**没标定**时 [start] 直接给
 * [Phase.UNAVAILABLE] ⇒ 调用方退回原来的滑屏找（不因缺锚点把整步卡死）。
 */
class FriendSearch(
    /** 三个锚点（顺序：入口 → 输入框 → 搜索按钮）；默认取声明表 [PatrolAnchors.searchFlowAnchors]。 */
    private val anchors: List<String> = PatrolAnchors.searchFlowAnchors[UiState.FRIEND_LIST].orEmpty(),
) {

    /** 搜索链走到哪了。 */
    enum class Phase {
        /** 还没开始（当前屏没命中时才 [start]）。 */
        IDLE,

        /** 该点「搜好友」了。 */
        ENTRY,

        /** 该点「请输入好友昵称」（搜索框）了。 */
        FIELD,

        /** 该**写文字**了（[TextInjectorLike]，由调用方执行）。 */
        TYPE,

        /**
         * 该**收输入法**了（2026-10-01 真机：写完文字后游戏进入"输入法全屏编辑"，
         * 画面被压缩 ⇒ 下一枪要点的「搜索」按钮**不在画面上**、
         * 锚点定位不到（实录 `搜索式查找卡在锚点「friend_search_go」`））。
         *
         * 做法：调用方按一次**系统返回**（`GLOBAL_ACTION_BACK`）——它**只收输入法、不会关搜索面板**。
         */
        DISMISS,

        /** 该点「搜索」了。 */
        GO,

        /** 搜索已发出 ⇒ 交给现有的名称定位去判结果页。 */
        SENT,

        /** 走不了（三个锚点没标定 / 写文字失败）⇒ 调用方退回滑屏或如实停下。 */
        UNAVAILABLE,
    }

    var phase: Phase = Phase.IDLE
        private set

    /** 走完整条链点了几下（日志用）。 */
    var clicks: Int = 0
        private set

    /**
     * **已经写了几次文字**（含失败重来）。
     *
     * 为什么要重来（2026-10-01 真机）：`ACTION_SET_TEXT` + 系统返回这套**不稳定** ——
     * 返回有时被游戏当成"提交"（文本进框 ✓）、有时被当成"取消"（框还是空的 ✗）。实录同一台机器：
     * `07:55` 那次结果页找到了目标 ✓、`07:56` 那次"结果页里没有它"✗ ⇒ 只能**验不过重来**。
     */
    var writeAttempts: Int = 0
        private set

    /** **这一步该点的锚点**（[Phase.TYPE] / [Phase.SENT] / 终态 ⇒ null）。 */
    val anchorToClick: String?
        get() = when (phase) {
            Phase.ENTRY -> anchors.getOrNull(0)
            Phase.FIELD -> anchors.getOrNull(1)
            Phase.GO -> anchors.getOrNull(2)
            else -> null
        }

    /** 是否已经走完点击链、正在等结果（调用方据此**不再滑屏**）。 */
    val awaitingResults: Boolean get() = phase == Phase.SENT

    /**
     * **当前该点的锚点，连续几帧没定位到**（成功定位即清零，见 [onAnchorLocated]）。
     *
     * 为什么需要它（2026-10-01 真机两次实录）：点完「搜好友」之后，搜索面板**还在展开动画里**，
     * 这一帧的 `friend_search_field` 定位不到 ⇒ 旧口径直接算"这一步失败"
     * （`PatrolFlow.fail` 计重试）：
     * ```
     * 08:02:23.410  第 9 步停下：搜索式查找卡在锚点「friend_search_field」…   ⇒ 重试后自愈
     * 08:05:22.342  第 9 步停下：搜索式查找卡在锚点「friend_search_field」…   ⇒ 重试用完 ⇒ 整条跑号中止 ✗
     * 08:24:56.231  第 9 步停下：搜索式查找卡在锚点「friend_search_field」…   ⇒ 重试后自愈（本轮最终跑通 ✓）
     * ```
     * 三次里两次都是**瞬态**（下一帧就能定位到）⇒ 现在改成"**先等下一帧**，连续
     * [ANCHOR_MISS_LIMIT] 帧都定位不到才如实停下"（不白耗单步重试次数，也让日志不再刷"停下"）。
     */
    var anchorMisses: Int = 0
        private set

    /** 开始搜索链。三个锚点不齐 ⇒ [Phase.UNAVAILABLE]（调用方退回滑屏找）。 */
    fun start(): Phase {
        phase = if (anchors.size >= 3 && anchors.none { it.isBlank() }) Phase.ENTRY else Phase.UNAVAILABLE
        clicks = 0
        anchorMisses = 0
        return phase
    }

    /** **这一帧没定位到该点的锚点**；@return true = 还能等下一帧再试，false = 连败到上限（调用方如实停下）。 */
    fun onAnchorMissed(): Boolean {
        anchorMisses++
        return anchorMisses < ANCHOR_MISS_LIMIT
    }

    /** **定位到了**（调用方即将点它）⇒ 连败计数清零。 */
    fun onAnchorLocated() {
        anchorMisses = 0
    }

    /** **已经点了当前这一步的锚点**（由调用方在提议点击之后调用）⇒ 推进到下一步。 */
    fun onAnchorPracticed() {
        clicks++
        phase = when (phase) {
            Phase.ENTRY -> Phase.FIELD
            Phase.FIELD -> Phase.TYPE
            Phase.GO -> Phase.SENT
            else -> phase
        }
    }

    /**
     * **写文字的结果**（调用方把 [TextInjectorLike] 的诊断传进来）：
     * 成功 ⇒ 先去 [Phase.DISMISS]**收输入法**（不然「搜索」按钮不在画面上）；失败 ⇒ 走不了。
     */
    fun onTextWritten(succeeded: Boolean) {
        if (phase != Phase.TYPE) return
        if (succeeded) {
            writeAttempts++
            phase = Phase.DISMISS
        } else {
            phase = Phase.UNAVAILABLE
        }
    }

    /**
     * **搜索没结果 ⇒ 从头再来一次**（面板还开着 ⇒ 直接回到 [Phase.FIELD]）。
     *
     * 依据见 [writeAttempts]：这套"原生写入 + 系统返回"**不稳定**，验不过只能重写。
     *
     * @return false = 重试次数已用完（调用方按用户口径**如实停下**）
     */
    fun retryOnce(maxAttempts: Int): Boolean {
        if (writeAttempts >= maxAttempts) return false
        phase = Phase.FIELD
        return true
    }

    /** **输入法已收起**（调用方按完系统返回、且画面回来了）⇒ 该点「搜索」了。 */
    fun onKeyboardDismissed() {
        if (phase == Phase.DISMISS) phase = Phase.GO
    }

    /**
     * 复位（离开这一步 / 重新跑一遍）。
     *
     * ⚠ **[writeAttempts] 必须一起清零**（2026-10-01 收尾时补的单测逮住的）：它原本只清
     * [phase] / [clicks] ⇒ 上一轮把两次写入额度用完（[retryOnce] 返回过 false）之后，用户**再跑一遍**
     * 时 `retryOnce` 会**立刻**返回 false ⇒ "重写一次"的机会被上一轮吃掉，表现为
     * "第二次跑，结果页只要没命中就直接停下、一次重写都不给"（真机不稳定，这个额度正是留给它的）。
     */
    fun reset() {
        phase = Phase.IDLE
        clicks = 0
        writeAttempts = 0
        anchorMisses = 0
    }

    /** 当前状态的一句话说明（日志 / 提示用）。 */
    fun note(): String = when (phase) {
        Phase.IDLE -> "搜索链未开始"
        Phase.ENTRY -> "搜索链 ① 点「搜好友」（第 1/3 下）"
        Phase.FIELD -> "搜索链 ② 点「请输入好友昵称」（第 2/3 下）"
        Phase.TYPE -> "搜索链 ③ 写好友昵称（原生 ACTION_SET_TEXT）"
        Phase.DISMISS -> "搜索链 ③.5 收输入法（系统返回；它把游戏切成了全屏编辑、搜索按钮看不到）"
        Phase.GO -> "搜索链 ④ 点「搜索」（第 3/3 下）"
        Phase.SENT -> "搜索已发出 ⇒ 等结果页（**不再滑屏**；结果里同名取最上面）"
        Phase.UNAVAILABLE -> "搜索链走不了（三个锚点没标定，或写文字失败）⇒ 退回滑屏找"
    }

    companion object {
        /**
         * **同一个锚点最多"连续几帧定位不到"才认输**（取 3；真机实测瞬态都是 1 帧，见 [anchorMisses]）。
         * 3 帧 ≈ 3 轮识别（真机 ≈0.3~0.5s/轮）⇒ 既兜住"面板展开动画"，又不至于真没标定时拖太久。
         */
        const val ANCHOR_MISS_LIMIT = 3
    }
}

/**
 * 占位接口（避免 `patrol` 层直接依赖 `action` 层）：调用方把"写文字"这件事注入进来。
 *
 * 真机上就是 `TextInjector.write(...)`（见那边的说明：原生 `ACTION_SET_TEXT`，不走输入法）。
 */
fun interface TextInjectorLike {
    /** @return 一行诊断（成功 / 失败的原因） */
    fun write(text: String): String
}
