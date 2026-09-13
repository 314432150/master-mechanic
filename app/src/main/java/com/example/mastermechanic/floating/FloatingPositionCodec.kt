package com.example.mastermechanic.floating

import java.util.Locale

/**
 * 悬浮窗位置编解码（FR-07 / ADR-006，纯逻辑）：文本行式格式，确定性（同数据 → 同文本）。
 *
 * 格式（**v3**，`#` 开头与空行忽略）：
 * ```
 * format=mm-floating              格式标记
 * version=3                       格式版本
 * label-x-ratio=0.500000          状态标签**中心点**横向比例（0.5 = 居中）
 * label-y-ratio=1.000000          状态标签中心点纵向比例（1.0 = 尽可能靠下，由布局按边距夹住）
 * screen=3168x1440                写入时的屏幕尺寸（仅供审计）
 * ```
 *
 * **v3 起不再存手柄位置**（2026-09-14）：手柄是**布局常量**，不是用户数据——存过它的代价很具体：
 * 旧文件里的值会一直压住新默认值（手柄出现在旧位置，只能靠"重置到默认位置"找回）。
 *
 * **版本兼容**：写出恒为 v3；读取接受 **v1**（只有 `side` / `y-ratio`，视为手柄位置 → 忽略，
 * 标签取默认"底部居中"）与 **v2**（含 `handle-*` 与 `label-*` → **只取标签**，手柄字段仅校验完整性）。
 * 各版本不得混用彼此的键、缺必需行则拒绝——兼容的是"旧格式"，不是"猜字段"（与标定产物同口径）。
 * 旧格式由 [FloatingPositionStore.loadOrRecover] 在挂载时顺手重写成 v3（[needsRewrite]）。
 *
 * 解析严格：格式标记不符 / 版本不受支持 / 缺少必需行 / 未知键 / 取值非法 → 拒绝
 * （[IllegalArgumentException]，消息带行号）。**但本数据的"损坏"后果轻**：
 * 由 [FloatingPositionStore.loadOrRecover] 回落默认位置（ADR-006）。
 */
object FloatingPositionCodec {

    private const val FORMAT_TAG = "mm-floating"

    /** 当前写出格式版本。 */
    private const val VERSION = 3

    /** 可读取的版本：v1 / v2（旧格式，读进来后按 v3 语义解释）与 v3。 */
    private val ACCEPTED_VERSIONS = setOf(1, 2, VERSION)

    fun encode(positions: FloatingPositions, screenWidth: Int, screenHeight: Int): String = buildString {
        appendLine("# MasterMechanic 悬浮窗位置（FR-07 / ADR-006）：只存状态标签中心点比例（手柄位置是布局常量，不存）")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("label-x-ratio=${num(positions.label.xRatio)}")
        appendLine("label-y-ratio=${num(positions.label.yRatio)}")
        appendLine("screen=${screenWidth}x$screenHeight")
    }

    /**
     * 文件是否需要按当前格式重写（旧版本 → 挂载时迁移）。
     * 只认 `version=` 行；内容非法（无 version / 非整数）返回 true，交给 [decode] 去报错。
     */
    fun needsRewrite(text: String): Boolean = versionOf(text) != VERSION

    private fun versionOf(text: String): Int? = text.split('\n')
        .firstOrNull { it.trim().startsWith("version=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toIntOrNull()

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
            legacySide ?: throw IllegalArgumentException("悬浮窗位置缺少 side 行")
            legacyYRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 y-ratio 行")
            // v1 只有手柄位置（已改为布局常量）→ 一律取默认
            return FloatingPositions(LabelPosition.DEFAULT)
        }

        if (declaredVersion == 2) {
            if (legacyUsed) throw IllegalArgumentException("v2 悬浮窗位置不得出现 v1 的 side / y-ratio 行")
            handleSide ?: throw IllegalArgumentException("悬浮窗位置缺少 handle-side 行")
            handleYRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 handle-y-ratio 行")
            // 手柄字段只为"文本完整性"校验，**取值一律忽略**：手柄位置是布局常量
            return FloatingPositions(labelOf(labelXRatio, labelYRatio))
        }

        if (legacyUsed || handleSide != null || handleYRatio != null) {
            throw IllegalArgumentException("v3 悬浮窗位置不得出现手柄字段（手柄位置是布局常量，不再持久化）")
        }
        return FloatingPositions(labelOf(labelXRatio, labelYRatio))
    }

    private fun labelOf(xRatio: Double?, yRatio: Double?): LabelPosition = LabelPosition(
        xRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 label-x-ratio 行"),
        yRatio ?: throw IllegalArgumentException("悬浮窗位置缺少 label-y-ratio 行"),
    )

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
