package com.example.mastermechanic.patrol

/**
 * 巡查配置编解码（FR-03 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（v1，`#` 开头与空行忽略）：
 * ```
 * format=mm-patrol                                格式标记
 * version=1                                       格式版本
 * item=<区服名称>|<目标好友名称>                     一个巡查项；**行的先后 = 执行顺序**
 * ```
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少 format 或 version / 未知键 / 项缺少分隔符 /
 * 名称为空或含分隔符换行 —— 一律拒绝（[IllegalArgumentException]，消息带行号）。
 * **不做静默兼容**：配置被改坏必须显式暴露（ADR-006 第 3 条；红线 3 同源口径）。
 *
 * 与标定产物的差别：**空配置是合法的**（「整体清空」的结果），故不要求至少一项。
 */
object PatrolConfigCodec {

    private const val FORMAT_TAG = "mm-patrol"

    /** 当前写出格式版本。 */
    private const val VERSION = 1

    private val ACCEPTED_VERSIONS = setOf(VERSION)

    /** 配置 → 文本（顺序 = 执行顺序，逐行写出，保证确定性）。 */
    fun encode(config: PatrolConfig): String = buildString {
        appendLine("# MasterMechanic 巡查配置（FR-03 / ADR-006）：每项「区服名称|目标好友名称」，按行序依次执行")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        config.items.forEach { item ->
            appendLine("item=${item.serverName}$PATROL_NAME_SEPARATOR${item.friendName}")
        }
    }

    /** 文本 → 配置（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): PatrolConfig {
        var format: String? = null
        var version: Int? = null
        val items = mutableListOf<PatrolItem>()

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("巡查配置第 $line 行：$reason")

        text.split('\n').forEachIndexed { index, rawLine ->
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
                "item" -> {
                    val sep = value.indexOf(PATROL_NAME_SEPARATOR)
                    if (sep < 0) {
                        fail(
                            index + 1,
                            "item 行应为「区服名称$PATROL_NAME_SEPARATOR" +
                                "目标好友名称」（缺分隔符）：$value",
                        )
                    }
                    val serverName = value.substring(0, sep)
                    val friendName = value.substring(sep + 1)
                    if (!PatrolConfig.isValidName(serverName)) {
                        fail(index + 1, "区服名称非法（不得为空，且不得含「$PATROL_NAME_SEPARATOR」或换行）：$serverName")
                    }
                    if (!PatrolConfig.isValidName(friendName)) {
                        fail(index + 1, "目标好友名称非法（不得为空，且不得含「$PATROL_NAME_SEPARATOR」或换行）：$friendName")
                    }
                    items += PatrolItem(serverName, friendName)
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException(
                "巡查配置格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        val declaredVersion = version ?: throw IllegalArgumentException("巡查配置缺少 version 行")
        if (declaredVersion !in ACCEPTED_VERSIONS) {
            throw IllegalArgumentException(
                "巡查配置版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，实际 $declaredVersion",
            )
        }
        return PatrolConfig(items)
    }
}
