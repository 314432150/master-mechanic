package com.example.mastermechanic.badge

import android.content.Context
import com.example.mastermechanic.log.MmLog
import java.io.File
import java.time.LocalDate

/**
 * 立牌池的盘上存取（temp/badge-share-1021）：`filesDir/badge/records.txt`。
 *
 * ## 跨日清空（FR-T4，U8 同款）
 *
 * **读取时**把日期不是今天的条目丢掉 —— 不做定时任务：进程被杀 / 休眠一夜都不会漏
 * （下次用到时日期自然不一样了），也避开"定时器在哪个时区跑"的坑。
 * 与游戏「立牌每日 0 点清空」同步失效：游戏里的立牌没了，池子里的码也没意义了。
 * 旧条目要等下一次写盘才真正从文件里消失（读侧过滤已保证**看不见**，盘上残留无害）。
 *
 * ## 坏文件的处置
 *
 * 这份文件是**机器写机器读的收集物**（不是用户手维护的数据）：读不了 ⇒ **当空 + 告警**，
 * 不挡界面（用户数据才一律拒绝，ADR-006 的分界；同 `ServerRotation` 游标口径）。
 * 代价是那一天已收集的码找不回来，重跑流程还能再收 —— 为此挡流程不值。
 *
 * 写盘 tmp+rename（`ServerListStore` 同款）：读方（悬浮窗）与写方（收集流程）不在同一线程。
 */
object BadgeStore {

    private const val TAG = "MM-Badge"
    private const val DIR_NAME = "badge"
    private const val FILE_NAME = "records.txt"

    /** 立牌池文件（应用私有目录 `badge/records.txt`）。 */
    fun recordsFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 读当日数据（今天 = [today]；文件不存在 / 读不了 ⇒ 空）。 */
    fun load(context: Context, today: LocalDate = LocalDate.now()): BadgeData =
        load(recordsFile(context), today)

    /** 读当日数据（纯文件版，JVM 可测；与 [load] 共用同一实现）。 */
    fun load(file: File, today: LocalDate): BadgeData {
        if (!file.isFile) return BadgeData()
        val data = try {
            BadgeCodec.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            MmLog.w(TAG, "立牌池文件读不了（当作空）：${e.message}")
            return BadgeData()
        }
        return BadgeData(
            // 读侧也合并（2026-10-22）：盘上已经存在的重复条目要**当场看不见**，
            // 不能指望"下次写入时才会被冲掉"——用户打开立牌池就是现在。
            records = mergeSameCard(data.records.filter { it.recordedOn == today }),
            collected = data.collected.filter { it.on == today },
        )
    }

    /** 写盘（覆盖写；返回文件）。 */
    fun save(file: File, data: BadgeData): File {
        file.parentFile?.mkdirs()
        val text = BadgeCodec.encode(data)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
        return file
    }

    /**
     * 记录一张立牌（**upsert**：同一张牌重复收集 / 重跑补录都只留一条、以新为准）。
     *
     * 判"同一张牌"两条路（2026-10-22 真机 bug 之后）：
     * 1. **分享码相同** —— 最硬的一条（同一张牌只会有一个码）；
     * 2. 码读歪了但仍是同一张牌 —— 见 [isSameCard]（同区服 + 同分 + 名称/码都只差一点点）。
     *    只认码的话，两次读出的码不同就会在池子里留两条同名同分的牌（用户实测就是这样）。
     *
     * 返回写入后的当日数据。
     */
    fun upsert(file: File, record: BadgeRecord, today: LocalDate): BadgeData {
        val current = load(file, today)
        val hit = current.records.any { it.code == record.code || isSameCard(it, record) }
        val records = if (hit) {
            current.records.map { old ->
                if (old.code == record.code || isSameCard(old, record)) mergeReadings(old, record) else old
            }
        } else {
            current.records + record
        }
        val next = current.copy(records = records)
        save(file, next)
        return next
    }

    /**
     * 两条记录是不是**同一张牌的两个读法**（2026-10-22 真机：池里出现两张「刘备顶级立牌」，都是 981 分）。
     *
     * 那次的两条是：`刘备项级立牌/QGG4ZS` 与 `刘备顶级立牌/GG4Z9` —— 同一张牌，两次 OCR 各错一点：
     * 名称错一个字（项/顶），码少一位还把末位 S 读成 9。按码去重完全挡不住（红线 7：识别不可信，
     * 但不能"不可信"就当成两张牌摆给用户看）。
     *
     * 判据要求四条同时成立，宁可不合并也不许误合并（误合并会**丢掉一张真的牌**）：
     * - 同区服（不同区的同名牌是两个号的，各自有价值）；
     * - **同分**（每张牌的分数由牌本身决定，不同牌同分本来就少见）；
     * - 名称编辑距离 ≤ 1（只容一个字读错）；
     * - 分享码编辑距离 ≤ 2（两个随机 6 位码不可能只差两位 ⇒ 只差两位必是同一次读歪）。
     */
    private fun isSameCard(a: BadgeRecord, b: BadgeRecord): Boolean =
        a.serverName == b.serverName &&
            a.score == b.score &&
            withinEditDistance(a.cardName, b.cardName, 1) &&
            withinEditDistance(a.code, b.code, 2)

    /**
     * 合并同一张牌的两条读法：名称 / 分数 / 日期取**新的**（后一次读得更专注），
     * **分享码取更长的那条** —— 实测漏读会少一位（`GG4Z9` vs `QGG4ZS`），更长的更接近完整码。
     * 码一样长就按"以新为准"。
     */
    private fun mergeReadings(old: BadgeRecord, fresh: BadgeRecord): BadgeRecord {
        if (old.code.length <= fresh.code.length || old.code == fresh.code) return fresh
        MmLog.i(
            TAG,
            "立牌池：同一张牌两种读法「${old.cardName}/${old.code}」与「${fresh.cardName}/${fresh.code}」" +
                "（同区服同分）⇒ 只留一条，码取更长的 ${old.code}",
        )
        return fresh.copy(code = old.code)
    }

    /** 按 [isSameCard] 合并一份记录（保持**先出现的位置**，确定性：同数据同结果）。 */
    private fun mergeSameCard(records: List<BadgeRecord>): List<BadgeRecord> {
        val merged = mutableListOf<BadgeRecord>()
        records.forEach { record ->
            val index = merged.indexOfFirst { it.code == record.code || isSameCard(it, record) }
            if (index < 0) {
                merged += record
            } else {
                merged[index] = mergeReadings(merged[index], record)
            }
        }
        return merged
    }

    /** 编辑距离是否 ≤ [limit]（滚动两行 DP；超限提前退出 —— 每轮都会拿几十条互相比，别做无谓计算）。 */
    private fun withinEditDistance(a: String, b: String, limit: Int): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return false
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length] <= limit
    }

    /** 打收集完成标记（FR-T2：该区服今天整套收集过了 ⇒ 重跑跳过）。已打过去重。 */
    fun markCollected(file: File, serverName: String, today: LocalDate): BadgeData {
        val current = load(file, today)
        if (current.isCollected(serverName)) return current
        val next = current.copy(
            collected = current.collected + BadgeCollection(serverName, today),
        )
        save(file, next)
        return next
    }

    /** 该区服今天是否已收集完毕（FR-T2 的跳过判据）。 */
    fun hasCollected(file: File, serverName: String, today: LocalDate): Boolean =
        load(file, today).isCollected(serverName)
}
