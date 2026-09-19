package com.example.mastermechanic.servers

/**
 * 服务器清单编解码（FR-10 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（**v2**，`#` 开头与空行忽略）：
 * ```
 * format=mm-servers                                      格式标记
 * version=2                                              格式版本
 * item=<平台>|<区号>|<区服名称>|<角色名>|<等级>           一条；**行的先后 = 切换顺序**
 * ```
 *
 * 五个字段**总是写出**（可留空的那几位为空时该位就是空的，四个分隔符仍照写）——
 * 解析按**位置**取值，不依赖"空字段被省略"，因此不存在"空字段挤掉后面的值"这类歧义。
 * 平台位写枚举的编码（`wechat` / `qq`），留空 = 用户没填；**不认别的写法**（不静默当空）。
 *
 * **v1 可读**（`item=<区服名称>|<角色名>|<等级>`，三个字段），读进来时平台与区号为空（旧文件不算损坏）；
 * 写入**一律 v2**（2026-09-14 用户口径补充：平台 + 区号）。字段形状由 `version` 决定 →
 * item 行先**收集**、等头部校验完再解析，不边读边定形状。
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少 format 或 version / 未知键 / 字段个数不符 /
 * 区服名称为空 / 平台不是 `wechat` / `qq` 也不是空 / 任一字段含分隔符换行 / **同一区服出现两次**
 * —— 一律拒绝（[IllegalArgumentException]，消息带行号）。**不做静默兼容**（ADR-006 第 3 条；红线 3 同源口径）。
 *
 * 与标定产物的差别：**空清单是合法的**（「整体清空」的结果），故不要求至少一条。
 */
object ServerListCodec {

    private const val FORMAT_TAG = "mm-servers"

    /** 当前写出格式版本（v2 = 平台 + 区号 + 区服名称 + 角色名 + 等级）。 */
    private const val VERSION = 2

    /** 可读的版本：v1 是三字段的旧格式，读进来时平台与区号为空。 */
    private val ACCEPTED_VERSIONS = setOf(1, VERSION)

    /** 某一版 item 行的字段个数。 */
    private fun fieldCount(version: Int): Int = if (version >= 2) 5 else 3

    /** item 行"该长什么样"（错误消息里写出来，用户手改文件时能对着看）。 */
    private fun itemShape(version: Int): String =
        if (version >= 2) "item=平台|区号|区服名称|角色名|等级" else "item=区服名称|角色名|等级"

    /** 清单 → 文本（顺序 = 切换顺序，逐行写出，保证确定性）。 */
    fun encode(list: ServerList): String = buildString {
        appendLine(
            "# MasterMechanic 服务器清单（FR-10 / ADR-006）：每条「平台|区号|区服名称|角色名|等级」，" +
                "除区服名称外都可留空；按行序即「按顺序切下一个」的顺序",
        )
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        list.entries.forEach { entry ->
            appendLine(
                "item=${entry.platform?.code.orEmpty()}$SERVER_FIELD_SEPARATOR" +
                    "${entry.serverNo}$SERVER_FIELD_SEPARATOR" +
                    "${entry.serverName}$SERVER_FIELD_SEPARATOR" +
                    "${entry.characterName}$SERVER_FIELD_SEPARATOR${entry.level}",
            )
        }
    }

    /** 文本 → 清单（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): ServerList {
        var format: String? = null
        var version: Int? = null
        // item 行先只收集（行号 → 值）：v1 / v2 的字段个数不同，形状要等 version 定下来才知道
        val items = mutableListOf<Pair<Int, String>>()

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
                "item" -> items += (index + 1) to value
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

        val entries = mutableListOf<ServerEntry>()
        items.forEach { (line, value) ->
            val entry = decodeItem(line, value, declaredVersion)
            // 一个区服只有一个小号 → 同一区服出现两次的文件一律拒收（手动改坏也一样，
            // 不静默去重：静默去重会让用户以为改动生效了，实际被丢掉一条）
            if (entries.any { it.serverName == entry.serverName }) {
                fail(line, "同一个区服只能有一条（一个区服只有一个小号）：${entry.serverName}")
            }
            entries += entry
        }
        return ServerList(entries)
    }

    private fun fail(line: Int, reason: String): Nothing =
        throw IllegalArgumentException("服务器清单第 $line 行：$reason")

    /** 解析一条 item 行：字段个数由 [version] 决定，字段值非法一律拒收。 */
    private fun decodeItem(line: Int, value: String, version: Int): ServerEntry {
        val fields = value.split(SERVER_FIELD_SEPARATOR)
        val expected = fieldCount(version)
        if (fields.size != expected) {
            fail(
                line,
                "${itemShape(version)} 应为 $expected 个字段（${expected - 1} 个" +
                    "「$SERVER_FIELD_SEPARATOR」分隔符），实际 ${fields.size} 个：$value",
            )
        }
        // v1 没有平台与区号这两位 → 读进来就是"没填"，旧文件不该被当成损坏
        val platformText = if (version >= 2) fields[0] else ""
        val serverNo = if (version >= 2) fields[1] else ""
        val offset = if (version >= 2) 2 else 0
        val serverName = fields[offset]
        val characterName = fields[offset + 1]
        val level = fields[offset + 2]

        val platform = if (platformText.isEmpty()) {
            null
        } else {
            ServerPlatform.fromCode(platformText) ?: fail(
                line,
                "平台只支持 ${ServerPlatform.entries.joinToString(" / ") { it.code }}（或留空）：$platformText",
            )
        }
        if (!ServerList.isValidOptionalField(serverNo)) {
            fail(line, "区号非法（可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行）：$serverNo")
        }
        if (!ServerList.isValidServerName(serverName)) {
            fail(line, "区服名称非法（不得为空，且不得含「$SERVER_FIELD_SEPARATOR」或换行）：$serverName")
        }
        if (!ServerList.isValidOptionalField(characterName)) {
            fail(line, "角色名非法（可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行）：$characterName")
        }
        if (!ServerList.isValidOptionalField(level)) {
            fail(line, "等级非法（可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行）：$level")
        }
        return ServerEntry(platform, serverNo, serverName, characterName, level)
    }
}
