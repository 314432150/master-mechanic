package com.example.mastermechanic.badge

import android.content.Context
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListStore
import java.io.File
import java.time.LocalDate

/**
 * 立牌任务的**跨区服编排**（临时需求 temp/badge-share-1021，2026-10-21）：
 * 读服务器清单 → 剔除今日已收集 → 交给 [BadgeSession] 逐个区服跑。
 *
 * 与 [BadgeSession] 的分工：本对象只做**盘上 IO 与队列规划**（可注入文件，纯逻辑部分可测），
 * 会话状态一律在 [BadgeSession]（唯一一处），不在这里另存一份。
 *
 * ## 顺序 = **服务器清单顺序**（FR-T1，用户口径）
 *
 * 不用 `ServerRotation` 的游标：那是巡查「换下一个」的语义；本需求是"当天把清单跑一遍"，
 * 与游标无关（也不该被它影响）。清单里今日已收集的区服直接跳过（FR-T2）。
 */
object BadgeTask {

    private const val TAG = "MM-Badge"

    /**
     * 一轮收集的**盘上状态**（`round.txt`）：回答"这次点「收集立牌」，是接着上一轮还是开新的一轮"。
     *
     * 为什么不再看"今天已收集过"：那个标记是**一天一次**的布尔值，14 个区服跑完就全打上了
     * ⇒ 中途停止再发起时它说不清"上一轮跑到哪了"（2026-10-23 真机：收完 2 个再发起又从第 1 个开始）。
     * 也不用"刚跑完 10 秒内忽略"这种时间猜测（**用户口径：去掉它，用显式标记说清**）。
     *
     * ⚠ **记的是"本轮已收过哪些"，不是"下一个是谁"**（2026-10-23 用户报障后改）：
     * 只记一个游标就得靠"轮换队列"把它转到队首，于是清单里**排在它前面的区服**会被排到这一轮最后 ——
     * 用户看到的是"已经收完的「发条权杖」又跑了一遍"，像从头开始、永远跑不完。
     * 改成记已收集集合：续跑 = **清单原顺序 + 跳过本轮已收的**，一轮严格单调向前，跑完即止。
     */
    enum class RoundState {
        /** 本轮还没跑完（含"中途停止 / 中止"）：再发起就接着收没跑过的。 */
        RUNNING,

        /** 本轮已跑完最后一个区服：再发起就是全新一轮。 */
        DONE,
    }

    /** 盘上的一轮状态：[done] 只在 [RoundState.RUNNING] 时有意义（= 本轮**已经收过**的区服）。 */
    data class RoundMarker(val state: RoundState, val done: Set<String> = emptySet())

    /** 启动结论：失败时 [reason] 是给人看的一句话（菜单/日志直接显示，不静默）。 */
    data class StartResult(val started: Boolean, val reason: String? = null)

    /**
     * 开始一次立牌收集任务。
     *
     * @param switch 换号段的起始进度；null = 起点不成立（调用方先做过 [PatrolFlow.firstStep] 判定）
     */
    fun start(
        context: Context,
        switch: PatrolFlow.State?,
        nowMs: Long,
        today: LocalDate = LocalDate.now(),
    ): StartResult {
        val list = try {
            ServerListStore.load(context)
        } catch (e: IllegalArgumentException) {
            return StartResult(false, "服务器清单读不了：${e.message}")
        } ?: return StartResult(false, "还没有服务器清单（先在配置页添加区服）")
        val names = list.entries.map { it.serverName }
        if (names.isEmpty()) {
            return StartResult(false, "服务器清单是空的（先在配置页添加区服）")
        }

        val recordsFile = BadgeStore.recordsFile(context)
        // **先清过期数据**（2026-10-23 用户口径："今日之前的已经过期了"）：读侧过滤只是"看不见"，
        // 文件会一直长、而且下次写盘基于含旧数据的快照还会把它们写回去 ⇒ 每次发起时清干净。
        val pruned = try {
            BadgeStore.pruneBefore(recordsFile, today)
        } catch (e: Exception) {
            MmLog.w(TAG, "清过期立牌数据没成功（不影响本次收集）：${e.message}")
            0
        }
        if (pruned > 0) MmLog.i(TAG, "清掉今天之前的过期立牌数据：$pruned 条")

        // **这一轮要收哪些**（2026-10-23 用户口径）：清单原顺序，**跳过本轮已经收过的**。
        // - 上一轮还没结束（RUNNING）⇒ 只收没跑过的（严格单调向前，不会回头重收）；
        // - 上一轮已结束 / 今天还没跑过（DONE / 无标记）⇒ 全新一轮，清单全部。
        val marker = loadMarker(context, today)
        val resuming = marker?.state == RoundState.RUNNING
        val done = if (resuming) marker!!.done else emptySet()
        val remaining = names.filterNot { it in done }
        val freshRound = !resuming || remaining.isEmpty()
        val queue = if (remaining.isEmpty()) names else remaining
        when {
            freshRound && done.isNotEmpty() ->
                MmLog.w(TAG, "上一轮记着 ${done.size} 个区服、清单里一个不剩（结束标记没写成）⇒ 按新的一轮从第 1 个开始")

            freshRound ->
                MmLog.i(TAG, "上一轮已结束（或今天还没跑过）⇒ 新的一轮，从第 1 个区服「${names.first()}」开始")

            else -> MmLog.i(
                TAG,
                "上一轮还没结束：已收 ${done.size} 个、还剩 ${remaining.size} 个 ⇒ 从「${remaining.first()}」接着收" +
                    "（不会回头重收已收过的）",
            )
        }
        if (switch == null) {
            return StartResult(false, "现在这个画面起步不了（要先回到大厅 / 农场 / 启动页）")
        }
        // 与巡查互斥：同一时刻只允许一条流程（两份"进行中的流程"会互相漂移）
        if (BadgeSession.isActive) {
            MmLog.i(TAG, "立牌任务已在跑 ⇒ 本次请求忽略")
            return StartResult(false, "立牌收集已经在跑了")
        }
        // `fullRound`：**新开一轮**时才允许"人在哪个区服就从哪里开始"（见 `continueFrom`）；
        // 续跑那一轮不该轮换（否则会把"还没收的"绕到后面去）。
        BadgeSession.start(queue, switch, nowMs, fullRound = freshRound, roundDone = if (freshRound) emptySet() else done)
        // **本轮进行中标记**：从这一刻起，没跑完之前再发起都会接着收没跑过的（而不是从第 1 个重来）。
        saveRunning(context, if (freshRound) emptySet() else done, today)
        MmLog.i(
            TAG,
            "立牌任务：清单 ${names.size} 个区服，本轮要收 ${queue.size} 个（从「${queue.first()}」开始，" +
                "${if (freshRound) "新的一轮" else "续上一轮"}）",
        )
        return StartResult(true)
    }

    /**
     * 记录一个区服"今天收集完了"（FR-T2 的跳过判据）。
     *
     * 返回是否**真的写成功**：写不进去只影响"下次会不会重跑"，不该把已经收到的分享码丢掉 ⇒
     * 只告警、不中止（同 `BadgeStore` 的坏文件口径）。
     */
    fun markServerCollected(context: Context, serverName: String, today: LocalDate = LocalDate.now()): Boolean =
        try {
            BadgeStore.markCollected(BadgeStore.recordsFile(context), serverName, today)
            true
        } catch (e: Exception) {
            MmLog.w(TAG, "收集标记没写成功（「$serverName」下次会重跑）：${e.message}")
            false
        }

    /**
     * 一个区服收完后：池子里该区服**只保留这一轮**读到的牌（2026-10-23 用户口径）。
     *
     * 今天已经跑过一次 ⇒ 池里可能留着该区服的旧码 / 旧分数（分数会被游戏刷新、旧码可能失效）。
     * 用户要"以这一次为准"，所以在收尾时用本轮记录替换掉该区服的全部旧行。
     *
     * @param round 本轮真正读到并落盘的记录（`BadgeSession.roundRecords`）
     * @return 删掉的旧条数；写盘失败返回 -1（只告警，不影响已收到的数据）
     */
    fun keepOnlyRound(context: Context, serverName: String, round: List<BadgeRecord>): Int =
        try {
            BadgeStore.keepOnlyRound(BadgeStore.recordsFile(context), serverName, round)
        } catch (e: Exception) {
            MmLog.w(TAG, "清「$serverName」的旧立牌数据没成功（池子里会留着旧记录）：${e.message}")
            -1
        }

    // ---------------------------------------------------------------- 整轮重跑的续跑游标

    /**
     * 轮次标记文件名（与 `records.txt` 同目录，**单独一个文件**：不碰既有格式，删了也只是"从第 1 个跑"）。
     *
     * 内容 = `日期|RUNNING|区服名` 或 `日期|DONE|`，只回答两件事：这一轮**结束没有**、下一个该收谁。
     */
    private const val ROUND_FILE = "round.txt"

    fun roundFile(context: Context): File = File(BadgeStore.recordsFile(context).parentFile, ROUND_FILE)

    /** 读今天的一轮状态（没有 / 隔天的 ⇒ null = 当成"上一轮已结束"）。 */
    fun loadMarker(context: Context, today: LocalDate = LocalDate.now()): RoundMarker? =
        try {
            val file = roundFile(context)
            if (!file.isFile) null else parseMarker(file.readText(Charsets.UTF_8), today)
        } catch (e: Exception) {
            MmLog.w(TAG, "轮次标记读不了（当作上一轮已结束，从第 1 个开始）：${e.message}")
            null
        }

    /** 标记「本轮进行中」，并记下**本轮已经收过的区服**（中途停止 / 中止后据此续跑、且不回头重收）。 */
    fun saveRunning(context: Context, done: Set<String>, today: LocalDate = LocalDate.now()) =
        writeMarker(context, formatMarker(today, RoundState.RUNNING, done))

    /** 标记「本轮结束」（最后一个区服已收完）：下次点「收集立牌」就是全新一轮。 */
    fun saveDone(context: Context, today: LocalDate = LocalDate.now()) =
        writeMarker(context, formatMarker(today, RoundState.DONE, emptySet()))

    private fun writeMarker(context: Context, text: String) {
        try {
            val file = roundFile(context)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            MmLog.w(TAG, "轮次标记没写成功（下次可能从第 1 个重跑）：${e.message}")
        }
    }

    /** 标记序列化：`日期|RUNNING/DONE|已收1,已收2,…`（纯逻辑，便于单测）。 */
    internal fun formatMarker(today: LocalDate, state: RoundState, done: Set<String>): String =
        "$today|${state.name}|${done.joinToString(",")}"

    /**
     * 标记解析（纯逻辑，便于单测）：
     * - 日期不是今天 ⇒ null（隔天的标记没有意义）；
     * - 旧格式 `日期|区服名`（2026-10-23 早些时候那个"游标"）⇒ **当成已结束**：
     *   它只记了"下一个是谁"，说不出哪些已经收过 ⇒ 宁可干净地重跑一轮，也不要凭猜测跳过。
     */
    internal fun parseMarker(text: String, today: LocalDate): RoundMarker? {
        val parts = text.trim().split('|')
        if (parts.size < 2 || parts[0] != today.toString()) return null
        return when (parts[1]) {
            RoundState.RUNNING.name -> RoundMarker(
                RoundState.RUNNING,
                parts.getOrNull(2).orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
            )

            RoundState.DONE.name -> RoundMarker(RoundState.DONE)

            else -> RoundMarker(RoundState.DONE)
        }
    }

    /** 记一张立牌（按分享码 upsert；返回是否写成功）。 */
    fun saveRecord(context: Context, record: BadgeRecord, today: LocalDate = LocalDate.now()): Boolean =
        try {
            BadgeStore.upsert(BadgeStore.recordsFile(context), record, today)
            true
        } catch (e: Exception) {
            MmLog.w(TAG, "立牌记录没写成功（${record.cardName} / ${record.code}）：${e.message}")
            false
        }
}
