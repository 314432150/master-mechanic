package com.example.mastermechanic.servers

/**
 * 服务器清单编解码（FR-10 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（v1，`#` 开头与空行忽略）：
 * ```
 * format=mm-servers                            格式标记
 * version=1                                    格式版本
 * item=<区服名称>|<角色名>|<等级>                一条；**行的先后 = 切换顺序**
 * ```
 *
 * 三个字段**总是写出**（角色名 / 等级为空时该位就是空的，两个分隔符仍照写）——
 * 解析按**位置**取值，不依赖"空字段被省略"，因此不存在"空字段挤掉后面的值"这类歧义。
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少 format 或 version / 未知键 / 分隔符不足 2 个 /
 * 区服名称为空 / 任一字段含分隔符换行 —— 一律拒绝（[IllegalArgumentException]，消息带行号）。
 * **不做静默兼容**：清单被改坏必须显式暴露（ADR-006 第 3 条；红线 3 同源口径）。
 *
 * 与标定产物的差别：**空清单是合法的**（「整体清空」的结果），故不要求至少一条。
 */
object ServerListCodec {

    private const val FORMAT_TAG = "mm-servers"

    /** 当前写出格式版本。 */
    private const val VERSION = 1

    private val ACCEPTED_VERSIONS = setOf(VERSION)

    /** 清单 → 文本（顺序 = 切换顺序，逐行写出，保证确定性）。 */
    fun encode(list: ServerList): String = buildString {
        appendLine("# MasterMechanic 服务器清单（FR-10 / ADR-006）：每条「区服名称|角色名|等级」，角色名与等级可留空；按行序即「按顺序切下一个」的顺序")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        list.entries.forEach { entry ->
            appendLine(
                "item=${entry.serverName}$SERVER_FIELD_SEPARATOR" +
                    "${entry.characterName}$SERVER_FIELD_SEPARATOR${entry.level}",
            )
        }
    }

    /** 文本 → 清单（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): ServerList {
        var format: String? = null
        var version: Int? = null
        val entries = mutableListOf<ServerEntry>()

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("服务器清单第 $line 行：$reason")

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
                    val first = value.indexOf(SERVER_FIELD_SEPARATOR)
                    if (first < 0) {
                        fail(
                            index + 1,
                            "item 行应为「区服名称${SERVER_FIELD_SEPARATOR}" +
                                "角色名${SERVER_FIELD_SEPARATOR}等级」（缺分隔符）：$value",
                        )
                    }
                    val second = value.indexOf(SERVER_FIELD_SEPARATOR, first + 1)
                    if (second < 0) {
                        fail(
                            index + 1,
                            "item 行应为「区服名称${SERVER_FIELD_SEPARATOR}" +
                                "角色名${SERVER_FIELD_SEPARATOR}等级」（分隔符只有 1 个，角色名与等级两位都要写）：$value",
                        )
                    }
                    val serverName = value.substring(0, first)
                    val characterName = value.substring(first + 1, second)
                    val level = value.substring(second + 1)
                    if (!ServerList.isValidServerName(serverName)) {
                        fail(
                            index + 1,
                            "区服名称非法（不得为空，且不得含「$SERVER_FIELD_SEPARATOR」或换行）：$serverName",
                        )
                    }
                    if (!ServerList.isValidOptionalField(characterName)) {
                        fail(index + 1, "角色名非法（可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行）：$characterName")
                    }
                    if (!ServerList.isValidOptionalField(level)) {
                        fail(index + 1, "等级非法（可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行）：$level")
                    }
                    entries += ServerEntry(serverName, characterName, level)
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException(
                "服务器清单格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        val declaredVersion = version ?: throw IllegalArgumentException("服务器清单缺少 version 行")
        if (declaredVersion !in ACCEPTED_VERSIONS) {
            throw IllegalArgumentException(
                "服务器清单版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，实际 $declaredVersion",
            )
        }
        return ServerList(entries)
    }
}
