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
     * "刚跑完一整轮"的冷却窗口（ms）：这段时间内再来一次「收集立牌」按**重复请求**忽略。
     *
     * 取 10 秒：重复投递几乎是同毫秒到达；而用户真想再跑一遍，从"看到完成"到"再点一下菜单"
     * 通常也要几秒（菜单还要展开）⇒ 10 秒不挡真人，只挡重复。
     */
    const val FULL_RERUN_QUIET_MS = 10_000L

    /**
     * 本次要跑的区服队列：**清单顺序**，剔除今日已收集的（纯逻辑，JVM 可测）。
     *
     * @param collected 该区服今天是否已收集完毕（生产 = [BadgeStore.hasCollected]）
     */
    fun queueOf(list: ServerList, collected: (String) -> Boolean): List<String> =
        list.entries.map { it.serverName }.filterNot(collected)

    /**
     * 真正开跑时用的队列：未收集的优先（**续跑**省时间）；**全都收集过 ⇒ 整轮重跑**。
     *
     * 为什么不直接拦（2026-10-22 用户口径："不应该阻止我重复搜集"）：收集完成标记的作用是
     * "中途停了接着跑"，不是"今天只许跑一次"。用户再点一次菜单，就是明确要再收集一遍
     * （牌可能被游戏刷新、或者想看新的分数），拦下来只会让人以为功能坏了。
     * 重复收集不会污染数据：`BadgeStore.upsert` 按码/同牌合并，`markCollected` 幂等。
     */
    fun runQueueOf(list: ServerList, pending: List<String>): List<String> =
        if (pending.isEmpty()) list.entries.map { it.serverName } else pending

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
        val pending = queueOf(list) { BadgeStore.hasCollected(recordsFile, it, today) }
        if (list.isEmpty) {
            return StartResult(false, "服务器清单是空的（先在配置页添加区服）")
        }
        // 全都收集过 ⇒ 本来要**整轮重跑**（2026-10-22 用户口径："不应该阻止我重复搜集"）。
        // ⚠ 但"刚跑完一整轮又来一次"多半是**同一次点击被重复投递**（2026-10-23 用户报障：
        // "收集完所有的服务器后又从头开始了"）⇒ 冷却窗口内的请求按重复忽略，不白白再跑 14 个区服。
        if (pending.isEmpty()) {
            val since = BadgeSession.msSinceEnded(nowMs)
            if (since in 0 until FULL_RERUN_QUIET_MS) {
                MmLog.w(
                    TAG,
                    "刚跑完一整轮（${since / 1000} 秒前，共 ${BadgeSession.current?.done ?: 0} 个区服）" +
                        " ⇒ 这次「收集立牌」按重复请求忽略，不再自动从头跑",
                )
                return StartResult(false, "刚跑完一整轮，稍等几秒再点")
            }
        }
        val planned = runQueueOf(list, pending)
        if (pending.isEmpty()) {
            MmLog.i(TAG, "今天全部区服都已收集 ⇒ 按整轮重跑（${planned.size} 个区服）")
        }
        // **整轮重跑要接着上次的位置**（2026-10-23 用户报障：收完一个区服、在换下一个的重登中途强制停止，
        // 再发起时又从清单第一个开始）。"全部已收集"时 `pending` 是空的，没有任何东西记得这一轮跑到哪，
        // 所以单独存一个游标（`round.txt`）。有未收集区服时不需要它 —— `pending` 本身就是续跑信息。
        val cursor = if (pending.isEmpty()) loadCursor(context, today) else null
        val queue = if (cursor != null && cursor in planned) {
            MmLog.i(TAG, "上次这一轮停在「$cursor」⇒ 从它接着收，不从头开始")
            BadgeSession.rotateTo(planned, cursor)
        } else {
            planned
        }
        if (switch == null) {
            return StartResult(false, "现在这个画面起步不了（要先回到大厅 / 农场 / 启动页）")
        }
        // 与巡查互斥：同一时刻只允许一条流程（两份"进行中的流程"会互相漂移）
        if (BadgeSession.isActive) {
            MmLog.i(TAG, "立牌任务已在跑 ⇒ 本次请求忽略")
            return StartResult(false, "立牌收集已经在跑了")
        }
        // `fullRound` 由队列规划给出：整轮重跑时才允许"人在哪个区服就从哪里开始"（见 `continueFrom`）
        BadgeSession.start(queue, switch, nowMs, fullRound = pending.isEmpty())
        // 记下游标：这一轮当前的目标区服（中途停止后再发起就从它开始）
        saveCursor(context, queue.firstOrNull(), today)
        MmLog.i(
            TAG,
            "立牌任务：清单 ${list.size} 个区服，今天要跑 ${queue.size} 个（跳过 ${list.size - queue.size} 个已收集）",
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
     * 游标文件名（与 `records.txt` 同目录，**单独一个文件**：不碰既有格式，删了也只是"从头跑"）。
     *
     * 内容 = `日期|区服名`，只回答一件事：**这一天这一轮，下一个该收哪个区服**。
     */
    private const val CURSOR_FILE = "round.txt"

    fun cursorFile(context: Context): File = File(BadgeStore.recordsFile(context).parentFile, CURSOR_FILE)

    /** 读今天的游标（没有 / 隔天的 ⇒ null）。 */
    fun loadCursor(context: Context, today: LocalDate = LocalDate.now()): String? =
        try {
            val file = cursorFile(context)
            if (!file.isFile) null else parseCursor(file.readText(Charsets.UTF_8), today)
        } catch (e: Exception) {
            MmLog.w(TAG, "续跑游标读不了（当作没有，从头跑）：${e.message}")
            null
        }

    /** 写 / 清游标：[server] 为空 ⇒ 删掉（整轮已跑完，下次是全新一轮）。 */
    fun saveCursor(context: Context, server: String?, today: LocalDate = LocalDate.now()) {
        val file = cursorFile(context)
        try {
            if (server.isNullOrBlank()) {
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(formatCursor(today, server), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            MmLog.w(TAG, "续跑游标没写成功（下次可能从头跑）：${e.message}")
        }
    }

    /** 游标序列化：`日期|区服名`（纯逻辑，便于单测）。 */
    internal fun formatCursor(today: LocalDate, server: String): String = "$today|$server"

    /** 游标解析：日期不是今天 ⇒ null（隔天的游标没有意义）。纯逻辑，便于单测。 */
    internal fun parseCursor(text: String, today: LocalDate): String? {
        val parts = text.trim().split('|', limit = 2)
        if (parts.size != 2) return null
        if (parts[0] != today.toString()) return null
        return parts[1].trim().ifBlank { null }
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
