package com.example.mastermechanic.patrol

import com.example.mastermechanic.servers.ServerCaptureOutcome

/**
 * 「试读好友名」的**请求 / 结果**一跳（2026-09-23），与 [com.example.mastermechanic.calibration.CalibrationReloadSignal]
 * 同构：**悬浮窗只写请求，采集服务在帧线程上消费一次**，再把结果文本放回来。
 *
 * ## 为什么用"计数器 + 回挂结果"而不是回调
 *
 * 悬浮窗（无障碍服务的覆盖窗）与采集服务虽然同进程，但**互不持有引用**（两者都只通过进程内单例信号通信，
 * 这是既有约定：`PatrolRequestSignal` / `CalibrationReloadSignal` / `UiStateSignal` 都是这个形态）。
 * 试读要读的是**当前实机画面的那一帧**，只有采集服务的帧线程拿得到 ⇒ 只能请它代跑。
 *
 * 悬浮窗每 500ms 轮询一次（`refreshStatus`），把 [tick] / [running] / [report] 一起编进状态签名，
 * 于是"报告到了"这件事会自然触发一次重画面板，不需要额外的通知通道。
 */
object FriendListOcrSignal {

    /**
     * 请求**要采集层做什么**（2026-09-24 扩展；现在只剩一种）。
     *
     * [CAPTURE_SERVERS]：**导入服务器**——只读产物里框选的「服务器列表区域」→ 解析 →
     * 产出**复核草稿**（[capture]），**等用户在复核页点「写入清单」才落盘**（先看后写，2026-09-25 用户口径）。
     *
     * （已按用户口径**整体移除**的两种：2026-09-25 的第三种 `DIGIT_TEMPLATES`「采集区号数字模板」——
     * 真机实测文字识别读数字 12/12 全对，模板对读数字没有贡献，第 5 步直接用文本里的区号判；
     * 2026-09-30 的 `TRY_READ`「试读这一屏」——菜单入口、报告渲染路径、`FriendListOcrTryout` 的试读入口
     * 与本模式枚举一并在同一批改掉。）
     */
    enum class Mode {
        /** **导入服务器**（读产物里框选的「服务器列表区域」→ 解析 → 复核草稿 → 用户确认后写清单）。 */
        CAPTURE_SERVERS,
    }

    /** 请求计数：帧线程拿它做"有没有新请求"的比对（只在值变化时消费一次）。 */
    @Volatile
    private var requestTick = 0L

    /** 最近一次请求的模式（结果页的标题按它取词；帧线程按它分派动作）。 */
    @Volatile
    var mode: Mode = Mode.CAPTURE_SERVERS
        private set

    /** 下一次采集结果**并进**当前草稿（= 刚点过「再读一屏」）。 */
    @Volatile
    private var appendNextResult = false

    /** 最近一次试读报告（null = 还没出结果）；多行文本，直接给面板显示。 */
    @Volatile
    var report: String? = null
        private set

    /** 是否正在等结果（面板据此显示"试读中…"，而不是把上一次的旧报告再显示一遍）。 */
    @Volatile
    var running: Boolean = false
        private set

    /**
     * **最近一次采集（[Mode.CAPTURE_SERVERS]）的产物**：复核草稿，或一句"为什么没有东西可复核"。
     *
     * 复核页靠它画：草稿在 → 画逐条标记 + 「写入清单」；[ServerCaptureOutcome.Nothing] → 只画说明 + 「再读一屏」。
     * 草稿**只是待写数据**，写盘由面板确认后自己落（`FloatingWindow.writeCaptureDraft`）——
     * 采集层不再碰服务器清单。
     */
    @Volatile
    var capture: ServerCaptureOutcome? = null
        private set

    /**
     * 这个模式的结果页是不是"**采集型读数页**"（2026-09-25 补第二个模式时定的）。
     *
     * 采集型（[Mode.CAPTURE_SERVERS]）的操作流是"**读一屏 → 在游戏里滚一屏 → 再读一屏**"，
     * 所以那一页有两个特殊待遇
     * （见 `FloatingMenu.isCaptureResultPage`）：
     *
     * 1. **不因"点面板之外"收起** —— `ACTION_OUTSIDE` 分不清"点击"与"在游戏里滑动"；
     * 2. **收起后再展开要回到读数页** —— 否则「再读一屏」就被藏进根菜单了。
     *
     * （原「试读这一屏」没有"再读一屏"这条流 ⇒ 当年不享受这两条；该模式已于 2026-09-30 整体移除。）
     *
     * ⚠ 放在这里而不是各处直接写 `mode == CAPTURE_SERVERS`：**漏了一个模式就是"又踩一遍同一个坑"**
     * （2026-09-25 真机：采集区号的结果页就是因为这个漏判，一滚屏面板就没了、再点手柄回到一级菜单）。
     */
    fun isCaptureFlow(mode: Mode): Boolean = mode == Mode.CAPTURE_SERVERS

    val tick: Long get() = requestTick

    /**
     * 悬浮窗点某个"读一次"的动作：记下**要做什么**、清掉上一次结果并计数 +1
     * （帧线程下一轮就会消费）。
     *
     * [mode] 默认 [Mode.TRY_READ]：试读那条路（好友名 / 服务器名）的调用点一个字都不用改。
     */
    fun request(mode: Mode = Mode.CAPTURE_SERVERS) {
        this.mode = mode
        report = null
        // **不在这里清草稿**：草稿的生命周期归复核页（「再读一屏」就是"往这份草稿里再加一屏"）
        // —— 由面板在开始新一轮/放弃时显式清（见 `FloatingWindow`）。
        running = true
        requestTick++
    }

    /**
     * **「再读一屏」**（2026-09-25 用户口径）：读下一屏，把结果**并进**当前草稿，而不是替换它。
     *
     * 为什么必须累积：一屏只看得到 12 台（两列网格），14 台要读两屏 ——
     * 用户的用法就是"滚一段再点一次"，然后**一次性**写入（老流程是每屏写一次，
     * 于是第二屏的读数被当成"已存在"、用户以为没读到）。
     */
    fun requestMore() {
        mode = Mode.CAPTURE_SERVERS
        appendNextResult = true
        running = true
        requestTick++
    }

    /** 帧线程跑完采集型请求：把**复核草稿**（或说明）放回来并结束"进行中"。 */
    fun doneCapture(outcome: ServerCaptureOutcome) {
        capture = if (appendNextResult) {
            appendNextResult = false
            val previous = (capture as? ServerCaptureOutcome.Draft)?.draft
            when {
                previous == null -> outcome
                // 这一屏读到了东西 ⇒ 并进草稿（先看后写的"攒"，见 ServerCaptureDraft.plus）
                outcome is ServerCaptureOutcome.Draft ->
                    ServerCaptureOutcome.Draft(previous.plus(outcome.draft.rows), outcome.notice)

                // 这一屏什么都没读到（画面不对 / 空画面）⇒ **草稿照留**，只把说明挂上去，
                // 免得用户"滚过头再读一次"把已经攒好的那 12 台全丢了
                else -> ServerCaptureOutcome.Draft(
                    previous,
                    (outcome as? ServerCaptureOutcome.Nothing)?.message,
                )
            }
        } else {
            outcome
        }
        running = false
    }

    /** 面板放弃这次复核 / 写入完成：把草稿清掉（下次采集从空开始）。 */
    fun clearCapture() {
        capture = null
    }

    /**
     * 帧线程跑完（**无论成功还是失败都必须调**）：把报告放回来并结束"进行中"。
     *
     * 漏调这一口的后果很具体：面板会永远停在"试读中…"，用户只能以为功能坏了
     * （所以采集服务那边把整段包在 runCatching 里，失败也走这里回一句原因）。
     */
    fun done(text: String) {
        report = text
        running = false
    }
}
