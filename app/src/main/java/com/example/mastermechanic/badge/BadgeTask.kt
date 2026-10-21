package com.example.mastermechanic.badge

import android.content.Context
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListStore
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
        val pending = queueOf(list) { BadgeStore.hasCollected(recordsFile, it, today) }
        if (list.isEmpty) {
            return StartResult(false, "服务器清单是空的（先在配置页添加区服）")
        }
        // 全都收集过 ⇒ **照跑不误**（整轮重跑，2026-10-22 用户口径），只留一条日志说明为什么跑了全量
        val queue = runQueueOf(list, pending)
        if (pending.isEmpty()) {
            MmLog.i(TAG, "今天全部区服都已收集 ⇒ 按整轮重跑（${queue.size} 个区服）")
        }
        if (switch == null) {
            return StartResult(false, "现在这个画面起步不了（要先回到大厅 / 农场 / 启动页）")
        }
        // 与巡查互斥：同一时刻只允许一条流程（两份"进行中的流程"会互相漂移）
        if (BadgeSession.isActive) {
            MmLog.i(TAG, "立牌任务已在跑 ⇒ 本次请求忽略")
            return StartResult(false, "立牌收集已经在跑了")
        }
        BadgeSession.start(queue, switch, nowMs)
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
