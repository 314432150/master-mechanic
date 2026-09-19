package com.example.mastermechanic.preset

/** 预设文件里字段分隔符（与其它配置同源口径）：字段值里不允许出现。 */
internal const val PRESET_FIELD_SEPARATOR = '|'

/**
 * 「一键拜访」预设的编解码（ADR-006 同一套口径：行式文本、**严格拒收**、错误消息带行号）。
 *
 * 格式（`files/preset/visit.txt`）：
 * ```
 * # MasterMechanic 一键拜访预设（FR-07 / ADR-006）
 * format=mm-visit-preset
 * version=1
 * serverChoice=NEXT
 * serverName=
 * friendName=星月晚
 * ```
 * 未知键一律拒收（不静默忽略）：多出来的键多半意味着"写的时候版本不对"，静默忽略会让用户以为存档了。
 */
object VisitPresetCodec {

    const val FORMAT = "mm-visit-preset"
    const val VERSION = 1

    private const val HEADER = "# MasterMechanic 一键拜访预设（FR-07 / ADR-006）：菜单一级点一次就执行的" +
        "「服务器策略 + 好友」"

    fun encode(preset: VisitPreset): String = buildString {
        appendLine(HEADER)
        appendLine("format=$FORMAT")
        appendLine("version=$VERSION")
        appendLine("serverChoice=${preset.serverChoice.name}")
        appendLine("serverName=${preset.serverName}")
        appendLine("friendName=${preset.friendName}")
    }

    /** 解析失败一律抛 [IllegalArgumentException]，消息里带行号（用户能直接定位到哪一行写坏了）。 */
    fun decode(text: String): VisitPreset {
        var format: String? = null
        var version: Int? = null
        var serverChoice: ServerChoice? = null
        var serverName: String? = null
        var friendName: String? = null

        text.split('\n').forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "第 $lineNo 行不是 `键=值`：$line" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "format" -> format = value
                "version" -> version = value.toIntOrNull()
                    ?: throw IllegalArgumentException("第 $lineNo 行版本号不是整数：$value")
                "serverChoice" -> serverChoice = ServerChoice.entries.firstOrNull { it.name == value }
                    ?: throw IllegalArgumentException("第 $lineNo 行服务器策略不认识：$value")
                "serverName" -> serverName = value
                "friendName" -> friendName = value
                else -> throw IllegalArgumentException("第 $lineNo 行出现了不认识的键：$key")
            }
        }

        require(format == FORMAT) { "不是一键拜访预设文件（format=$format）" }
        require(version == VERSION) { "预设文件版本不支持：$version（本版本只认 $VERSION）" }
        require(serverChoice != null) { "预设文件缺少 serverChoice" }
        require(serverName != null) { "预设文件缺少 serverName" }
        require(friendName != null) { "预设文件缺少 friendName" }
        return VisitPreset(serverChoice = serverChoice, serverName = serverName, friendName = friendName)
    }
}
