package com.example.mastermechanic.floating

import java.util.Locale

/**
 * 悬浮窗位置编解码（FR-07 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（v1，`#` 开头与空行忽略）：
 * ```
 * format=mm-floating              格式标记
 * version=1                       格式版本
 * side=right                      停靠侧（left / right）
 * y-ratio=0.080000                纵向位置占屏高的比例（0..1）
 * screen=1440x3168                写入时的屏幕尺寸（仅供审计，读取时不作校验依据）
 * ```
 *
 * 解析严格（与产物 / 巡查配置同口径）：格式标记不符 / 版本不受支持 / 缺少必需行 / 未知键 /
 * 停靠侧取值非法 / 比例越界 —— 一律拒绝（[IllegalArgumentException]，消息带行号），不猜测。
 * **但本数据的"损坏"后果轻**：由 [FloatingPositionStore.loadOrRecover] 回落默认位置（ADR-006）。
 */
object FloatingPositionCodec {

    private const val FORMAT_TAG = "mm-floating"

    /** 当前写出格式版本。 */
    private const val VERSION = 1

    private val ACCEPTED_VERSIONS = setOf(VERSION)

    fun encode(position: FloatingPosition, screenWidth: Int, screenHeight: Int): String = buildString {
        appendLine("# MasterMechanic 悬浮窗位置（FR-07 / ADR-006）：停靠侧 + 纵向比例 + 写入时的屏幕尺寸")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("side=${position.side.token}")
        appendLine("y-ratio=${num(position.yRatio)}")
        appendLine("screen=${screenWidth}x$screenHeight")
    }

    fun decode(text: String): FloatingPosition {
        var format: String? = null
        var version: Int? = null
        var side: FloatingSide? = null
        var yRatio: Double? = null
        var screenSeen = false

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("悬浮窗位置第 $line 行：$reason")

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
                "side" -> {
                    if (side != null) fail(index + 1, "重复的 side 行")
                    side = FloatingSide.fromToken(value) ?: fail(index + 1, "停靠侧取值非法：$value")
                }
                "y-ratio" -> {
                    if (yRatio != null) fail(index + 1, "重复的 y-ratio 行")
                    val parsed = value.toDoubleOrNull() ?: fail(index + 1, "纵向比例非数字：$value")
                    if (parsed < 0.0 || parsed > 1.0) {
                        fail(index + 1, "纵向比例必须在 0..1：$value")
                    }
                    yRatio = parsed
                }
                "screen" -> {
                    if (screenSeen) fail(index + 1, "重复的 screen 行")
                    val parts = value.split('x')
                    if (parts.size != 2 || parts.any { it.toIntOrNull() == null }) {
                        fail(index + 1, "屏幕尺寸应为「宽x高」：$value")
                    }
                    screenSeen = true
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException(
                "悬浮窗位置格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」",
            )
        }
        val declaredVersion = version ?: throw IllegalArgumentException("悬浮窗位置缺少 version 行")
        if (declaredVersion !in ACCEPTED_VERSIONS) {
            throw IllegalArgumentException(
                "悬浮窗位置版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，实际 $declaredVersion",
            )
        }
        val declaredSide = side ?: throw IllegalArgumentException("悬浮窗位置缺少 side 行")
        val declaredRatio = yRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 y-ratio 行")
        if (!screenSeen) throw IllegalArgumentException("悬浮窗位置缺少 screen 行")
        return FloatingPosition(declaredSide, declaredRatio)
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
