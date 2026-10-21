package com.example.mastermechanic.badge

import java.time.LocalDate

/**
 * 立牌池文件编解码（temp/badge-share-1021，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（**v1**，`#` 开头与空行忽略）：
 * ```
 * format=mm-badge                                   格式标记
 * version=1                                         格式版本
 * record=<区服名>|<立牌名>|<分享码>|<分数>|<日期>     一张立牌的分享码记录
 * collected=<区服名>|<日期>                          收集完成标记（FR-T2 跳过判据）
 * ```
 *
 * 解析严格（ServerListCodec 同款口径，ADR-006 第 3 条）：格式标记不符 / 版本不受支持 /
 * 缺少 format 或 version / 未知键 / 字段个数不符 / 任一字段为空或含分隔符或换行 / 分数非非负整数 /
 * 日期不是 `yyyy-MM-dd` / **同一分享码出现两次** / **同一区服的收集标记出现两次** ——
 * 一律拒绝（[IllegalArgumentException]，消息带行号）。**不做静默兼容**。
 *
 * 写入侧同样校验（[encode] 里 [require]）：盘上文件只会由 [BadgeStore] 写，
 * 但 OCR 来的立牌名若混进分隔符，宁可在写入那一刻炸掉，也不悄悄写出一份将来读不回的文件。
 *
 * 与 `ServerListCodec` 的差别：这份文件是**机器写机器读的收集物**（不是用户手维护的数据）——
 * 严格性留在本层；"坏了怎么办"的处置在 [BadgeStore.load]（当空 + 告警，不挡界面）。
 */
object BadgeCodec {

    private const val FORMAT_TAG = "mm-badge"

    /** 当前写出格式版本。 */
    private const val VERSION = 1

    private const val FIELD_SEPARATOR = '|'
    private const val KEY_RECORD = "record"
    private const val KEY_COLLECTED = "collected"

    /** 数据 → 文本（确定性：按列表原序逐行写出）。 */
    fun encode(data: BadgeData): String = buildString {
        appendLine(
            "# MasterMechanic 立牌池（temp/badge-share-1021）：当日收集的分享码与收集完成标记；" +
                "读取按「日期=今天」过滤（跨日清空，与游戏「立牌每日 0 点清空」同步失效）",
        )
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        data.records.forEach { record ->
            requireFields(
                listOf(record.serverName, record.cardName, record.code),
                record.recordedOn.toString(),
            )
            require(record.score >= 0) { "立牌分数不能是负数：${record.score}" }
            appendLine(
                "$KEY_RECORD=${record.serverName}$FIELD_SEPARATOR${record.cardName}$FIELD_SEPARATOR" +
                    "${record.code}$FIELD_SEPARATOR${record.score}$FIELD_SEPARATOR${record.recordedOn}",
            )
        }
        data.collected.forEach { mark ->
            requireFields(listOf(mark.serverName), mark.on.toString())
            appendLine("$KEY_COLLECTED=${mark.serverName}$FIELD_SEPARATOR${mark.on}")
        }
    }

    /** 文本 → 数据（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): BadgeData {
        // 开头的 BOM 先去掉（ServerListCodec 同款：外部工具写一遍不该把好文件算成损坏）
        val body = text.removePrefix("\uFEFF")
        var format: String? = null
        var version: Int? = null
        val recordLines = mutableListOf<Pair<Int, String>>()
        val collectedLines = mutableListOf<Pair<Int, String>>()

        body.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq <= 0) fail(index + 1, "缺少「key=value」结构：$line")
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)

            when (key) {
                "format" -> {
                    if (format != null) fail(index + 1, "重复的 format 行")
                    format = value
                }
                "version" -> {
                    if (version != null) fail(index + 1, "重复的 version 行")
                    version = value.toIntOrNull() ?: fail(index + 1, "版本号非整数：$value")
                }
                KEY_RECORD -> recordLines += (index + 1) to value
                KEY_COLLECTED -> collectedLines += (index + 1) to value
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException(
                "立牌池文件格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        when (version) {
            null -> throw IllegalArgumentException("立牌池文件缺少 version 行")
            VERSION -> Unit
            else -> throw IllegalArgumentException("立牌池文件版本不受支持：可读 $VERSION，实际 $version")
        }

        val records = mutableListOf<BadgeRecord>()
        recordLines.forEach { (line, value) ->
            val record = decodeRecord(line, value)
            if (records.any { it.code == record.code }) {
                fail(line, "同一个分享码只能有一条：${record.code}")
            }
            records += record
        }
        val collected = mutableListOf<BadgeCollection>()
        collectedLines.forEach { (line, value) ->
            val mark = decodeCollected(line, value)
            if (collected.any { it.serverName == mark.serverName }) {
                fail(line, "同一个区服的收集标记只能有一条：${mark.serverName}")
            }
            collected += mark
        }
        return BadgeData(records = records, collected = collected)
    }

    private fun decodeRecord(line: Int, value: String): BadgeRecord {
        val fields = value.split(FIELD_SEPARATOR)
        if (fields.size != 5) fail(line, "record 字段个数应为 5（区服名|立牌名|分享码|分数|日期）：$value")
        val (serverName, cardName, code, scoreText, dateText) = fields
        requireFields(listOf(serverName, cardName, code), dateText, line = line)
        val score = scoreText.toIntOrNull() ?: fail(line, "分数不是整数：$scoreText")
        if (score < 0) fail(line, "分数不能是负数：$score")
        return BadgeRecord(
            serverName = serverName,
            cardName = cardName,
            code = code,
            score = score,
            recordedOn = parseDate(line, dateText),
        )
    }

    private fun decodeCollected(line: Int, value: String): BadgeCollection {
        val fields = value.split(FIELD_SEPARATOR)
        if (fields.size != 2) fail(line, "collected 字段个数应为 2（区服名|日期）：$value")
        val (serverName, dateText) = fields
        requireFields(listOf(serverName), dateText, line = line)
        return BadgeCollection(serverName = serverName, on = parseDate(line, dateText))
    }

    private fun parseDate(line: Int, text: String): LocalDate =
        runCatching { LocalDate.parse(text) }.getOrElse { fail(line, "日期不是 yyyy-MM-dd：$text") }

    /** 字段非空、不含分隔符与换行（分隔符混进字段 = 一份永远读不回的文件）。 */
    private fun requireFields(fields: List<String>, vararg rest: String, line: Int = 0) {
        (fields + rest).forEach { field ->
            if (field.isEmpty()) fail(line, "字段不能为空")
            if (field.contains(FIELD_SEPARATOR) || field.contains('\n') || field.contains('\r')) {
                fail(line, "字段含分隔符或换行：$field")
            }
        }
    }

    private fun fail(line: Int, detail: String): Nothing =
        throw IllegalArgumentException("立牌池文件${if (line > 0) "第 $line 行" else "写入"}：$detail")
}
