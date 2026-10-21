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
            records = data.records.filter { it.recordedOn == today },
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
     * 记录一张立牌（**按分享码 upsert**：同一张牌重复收集 / 重跑补录都只留一条、以新为准）。
     * 返回写入后的当日数据。
     */
    fun upsert(file: File, record: BadgeRecord, today: LocalDate): BadgeData {
        val current = load(file, today)
        val next = current.copy(
            records = current.records.filter { it.code != record.code } + record,
        )
        save(file, next)
        return next
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
