package com.example.mastermechanic.friends

/**
 * 好友清单编解码（FR-07 拜访三级列表 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（**v1**，`#` 开头与空行忽略）：
 * ```
 * format=mm-friends                    格式标记
 * version=1                            格式版本
 * item=<好友名称>                       一条；行的先后 = 列表顺序
 * ```
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少 format 或 version / 未知键 / 好友名为空 /
 * 好友名含分隔符或换行 / **同一个好友出现两次** —— 一律拒绝（[IllegalArgumentException]，
 * 消息带行号），**不做静默兼容**（ADR-006 第 3 条；与 FR-03 / FR-10 同一套口径）。
 *
 * **空清单合法**（「整体清空」的结果），故不要求至少一条。
 */
object FriendListCodec {

    private const val FORMAT_TAG = "mm-friends"

    private const val VERSION = 1

    fun encode(list: FriendList): String = buildString {
        appendLine(
            "# MasterMechanic 好友清单（FR-07 拜访目标 / ADR-006）：每行「好友名称」，" +
                "名字会直接用于在好友列表里定位，要与游戏里的写法完全一致",
        )
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        list.entries.forEach { entry -> appendLine("item=${entry.name}") }
    }

    /** 文本 → 清单（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): FriendList {
        var format: String? = null
        var version: Int? = null
        // 字段形状可能在将来随版本变化 → item 行先收集（带行号），头部校验完再解析
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
                "好友清单格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        val declaredVersion = version ?: throw IllegalArgumentException("好友清单缺少 version 行")
        if (declaredVersion != VERSION) {
            throw IllegalArgumentException(
                "好友清单版本不受支持：可读 $VERSION，实际 $declaredVersion",
            )
        }

        val entries = mutableListOf<FriendEntry>()
        items.forEach { (line, value) ->
            val entry = decodeItem(line, value)
            // 同一个好友只能有一条 → 文件里出现两次一律拒收（手改坏了也一样，
            // 不静默去重：静默去重会让用户以为改动生效了，实际被丢掉一条）
            if (entries.any { it.name == entry.name }) {
                fail(line, "同一个好友只能有一条：${entry.name}")
            }
            entries += entry
        }
        return FriendList(entries)
    }

    private fun fail(line: Int, reason: String): Nothing =
        throw IllegalArgumentException("好友清单第 $line 行：$reason")

    private fun decodeItem(line: Int, value: String): FriendEntry {
        // v1 一条只有一个字段：整行余下部分就是名字（不留分割的机会）
        if (!FriendList.isValidName(value)) {
            fail(
                line,
                "好友名称非法（不得为空，且不得含「${FRIEND_FIELD_SEPARATOR}」或换行）：$value",
            )
        }
        return FriendEntry(value)
    }
}
