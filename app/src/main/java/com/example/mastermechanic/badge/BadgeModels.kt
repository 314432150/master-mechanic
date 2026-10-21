package com.example.mastermechanic.badge

import java.time.LocalDate

/**
 * 立牌池的数据形状（**临时需求** temp/badge-share-1021：活动结束随分支整体移除，见
 * `docs/plans/temp-badge-share.md`）。
 *
 * 用户口径（2026-10-21）：「记录分享码、分数、名字数据」——名字指**立牌名**（如「墨子顶级立牌」）；
 * 「小号名」在展示层用**当前区服名**（切服上下文里现成的，不读游戏昵称）。
 *
 * - [BadgeRecord]：一张立牌的分享码记录；[BadgeRecord.recordedOn] 是**跨日清空的唯一判据**
 *   （FR-T4，U8 同款：读取时与今天比，不做定时任务 —— 与游戏「立牌每日 0 点清空」同步失效）。
 * - [BadgeCollection]：**收集完成标记**（FR-T2）：该区服今天已经整套收集过 ⇒ 重跑跳过。
 *   与记录分开存的原因：中途失败时记录可能只有半套（两张只记到一张就断了），
 *   只有走完整条收集流程才配打标记 —— 否则半套会被当成"收集过了"，缺的那张永远补不上。
 * - [BadgeData]：一份文件里的全部（记录 + 标记）；[BadgeStore.load] 返回的**只含当天条目**。
 */
data class BadgeRecord(
    val serverName: String,
    val cardName: String,
    val code: String,
    val score: Int,
    val recordedOn: LocalDate,
)

/** 收集完成标记：[serverName] 在 [on] 这天已经收集完毕（判据见 [BadgeData.isCollected]）。 */
data class BadgeCollection(
    val serverName: String,
    val on: LocalDate,
)

/** 当日数据：记录 + 收集完成标记（"当日"由 [BadgeStore.load] 的日期过滤保证）。 */
data class BadgeData(
    val records: List<BadgeRecord> = emptyList(),
    val collected: List<BadgeCollection> = emptyList(),
) {
    /** 该区服今天是否已收集完毕（FR-T2 的跳过判据）。 */
    fun isCollected(serverName: String): Boolean = collected.any { it.serverName == serverName }
}
