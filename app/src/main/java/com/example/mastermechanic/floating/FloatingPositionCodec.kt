package com.example.mastermechanic.floating

import java.util.Locale

/**
 * 悬浮窗位置编解码（FR-07 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（**v2**，`#` 开头与空行忽略）：
 * ```
 * format=mm-floating              格式标记
 * version=2                       格式版本
 * handle-side=right               手柄停靠侧（left / right）
 * handle-y-ratio=0.080000         手柄纵向位置占屏高的比例
 * label-x-ratio=0.500000          状态标签**中心点**横向比例（0.5 = 居中）
 * label-y-ratio=1.000000          状态标签中心点纵向比例（1.0 = 尽可能靠下，由布局按边距夹住）
 * screen=3168x1440                写入时的屏幕尺寸（仅供审计）
 * ```
 *
 * **版本兼容（T3-7）**：写出恒为 v2；读取接受 v1（只有 `side` / `y-ratio` 两个键——
 * 视为**手柄**位置，状态标签取默认"底部居中"）。v1 文本里出现标签字段、或 v2 文本缺字段，
 * 一律拒绝——兼容的是"旧格式"，不是"猜字段"（与标定产物同口径）。
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少必需行 / 未知键 / 取值非法 → 拒绝
 * （[IllegalArgumentException]，消息带行号）。**但本数据的"损坏"后果轻**：
 * 由 [FloatingPositionStore.loadOrRecover] 回落默认位置（ADR-006）。
 */
object FloatingPositionCodec {

    private const val FORMAT_TAG = "mm-floating"

    /** 当前写出格式版本。 */
    private const val VERSION = 2

    /** 可读取的版本：v1（只有手柄位置）与 v2。 */
    private val ACCEPTED_VERSIONS = setOf(1, VERSION)

    fun encode(positions: FloatingPositions, screenWidth: Int, screenHeight: Int): String = buildString {
        appendLine("# MasterMechanic 悬浮窗位置（FR-07 / ADR-006）：手柄（停靠侧 + 纵向比例）+ 状态标签（中心点比例）")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("handle-side=${positions.handle.side.token}")
        appendLine("handle-y-ratio=${num(positions.handle.yRatio)}")
        appendLine("label-x-ratio=${num(positions.label.xRatio)}")
        appendLine("label-y-ratio=${num(positions.label.yRatio)}")
        appendLine("screen=${screenWidth}x$screenHeight")
    }

    fun decode(text: String): FloatingPositions {
        var format: String? = null
        var version: Int? = null
        var handleSide: FloatingSide? = null
        var handleYRatio: Double? = null
        var labelXRatio: Double? = null
        var labelYRatio: Double? = null
        var legacySide: FloatingSide? = null
        var legacyYRatio: Double? = null
        var screenSeen = false

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("悬浮窗位置第 $line 行：$reason")

        fun ratio(line: Int, value: String): Double {
            val parsed = value.toDoubleOrNull() ?: fail(line, "比例非数字：$value")
            if (parsed < 0.0 || parsed > 1.0) fail(line, "比例必须在 0..1：$value")
            return parsed
        }

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
                "handle-side" -> {
                    if (handleSide != null) fail(index + 1, "重复的 handle-side 行")
                    handleSide = FloatingSide.fromToken(value) ?: fail(index + 1, "手柄停靠侧取值非法：$value")
                }
                "handle-y-ratio" -> {
                    if (handleYRatio != null) fail(index + 1, "重复的 handle-y-ratio 行")
                    handleYRatio = ratio(index + 1, value)
                }
                "label-x-ratio" -> {
                    if (labelXRatio != null) fail(index + 1, "重复的 label-x-ratio 行")
                    labelXRatio = ratio(index + 1, value)
                }
                "label-y-ratio" -> {
                    if (labelYRatio != null) fail(index + 1, "重复的 label-y-ratio 行")
                    labelYRatio = ratio(index + 1, value)
                }
                // v1 的键
                "side" -> {
                    if (legacySide != null) fail(index + 1, "重复的 side 行")
                    legacySide = FloatingSide.fromToken(value) ?: fail(index + 1, "停靠侧取值非法：$value")
                }
                "y-ratio" -> {
                    if (legacyYRatio != null) fail(index + 1, "重复的 y-ratio 行")
                    legacyYRatio = ratio(index + 1, value)
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
        if (!screenSeen) throw IllegalArgumentException("悬浮窗位置缺少 screen 行")
        val legacyUsed = legacySide != null || legacyYRatio != null
        val modernUsed = handleSide != null || handleYRatio != null || labelXRatio != null || labelYRatio != null

        if (declaredVersion == 1) {
            // 兼容的是"旧格式"，不是"猜字段"：v1 里出现 v2 的键说明文本被改过
            if (modernUsed) throw IllegalArgumentException("v1 悬浮窗位置不得出现 handle-* / label-* 行")
            val side = legacySide ?: throw IllegalArgumentException("悬浮窗位置缺少 side 行")
            val yRatio = legacyYRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 y-ratio 行")
            return FloatingPositions(FloatingPosition(side, yRatio), LabelPosition.DEFAULT)
        }

        if (legacyUsed) throw IllegalArgumentException("v2 悬浮窗位置不得出现 v1 的 side / y-ratio 行")
        val side = handleSide ?: throw IllegalArgumentException("悬浮窗位置缺少 handle-side 行")
        return FloatingPositions(
            handle = FloatingPosition(side, handleYRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 handle-y-ratio 行")),
            label = LabelPosition(
                labelXRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 label-x-ratio 行"),
                labelYRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 label-y-ratio 行"),
            ),
        )
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
