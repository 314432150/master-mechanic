package com.example.mastermechanic.settings

/**
 * 应用偏好编解码（ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（v1，`#` 开头与空行忽略）：
 * ```
 * format=mm-prefs                             格式标记
 * version=1                                   格式版本
 * keep-click-mode-on-session-start=false       采集会话建立后是否保留运行方式
 * ```
 *
 * 解析严格（与产物 / 巡查配置 / 悬浮窗位置同口径）：格式标记不符 / 版本不受支持 / 缺少必需行 /
 * 未知键 / 取值不是布尔 —— 一律拒绝（[IllegalArgumentException]，消息带行号）。
 * **偏好损坏的后果轻**：由 [AppPreferencesStore.loadOrRecover] 回落默认并重写（同"位置类数据"分流）。
 */
object AppPreferencesCodec {

    private const val FORMAT_TAG = "mm-prefs"

    /** 当前写出格式版本。 */
    private const val VERSION = 1

    private val ACCEPTED_VERSIONS = setOf(VERSION)

    internal const val KEY_KEEP_CLICK_MODE = "keep-click-mode-on-session-start"

    fun encode(preferences: AppPreferences): String = buildString {
        appendLine("# MasterMechanic 应用偏好（ADR-006）：程序该按什么习惯运行（每项一行 key=value）")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("$KEY_KEEP_CLICK_MODE=${preferences.keepClickModeOnSessionStart}")
    }

    fun decode(text: String): AppPreferences {
        var format: String? = null
        var version: Int? = null
        var keepClickMode: Boolean? = null

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("应用偏好第 $line 行：$reason")

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
                KEY_KEEP_CLICK_MODE -> {
                    if (keepClickMode != null) fail(index + 1, "重复的 $KEY_KEEP_CLICK_MODE 行")
                    keepClickMode = when (value.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> fail(index + 1, "$KEY_KEEP_CLICK_MODE 只能是 true / false：$value")
                    }
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException(
                "应用偏好格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        val declaredVersion = version ?: throw IllegalArgumentException("应用偏好缺少 version 行")
        if (declaredVersion !in ACCEPTED_VERSIONS) {
            throw IllegalArgumentException(
                "应用偏好版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，实际 $declaredVersion",
            )
        }
        return AppPreferences(
            keepClickModeOnSessionStart = keepClickMode
                ?: throw IllegalArgumentException("应用偏好缺少 $KEY_KEEP_CLICK_MODE 行"),
        )
    }
}
