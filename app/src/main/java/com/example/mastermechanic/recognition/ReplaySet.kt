package com.example.mastermechanic.recognition

import java.util.Locale

/**
 * 回放样本集（T1-6a，纯逻辑）：固定批次样本的清单模型与文本编解码。
 *
 * 样本集 = 清单（本文件定义的文本格式）+ 若干画面文件（同目录）。目录按
 * `docs/recognition/README.md` 版本化约定命名（`set-YYYYMMDD-NN`）。
 *
 * 格式（v1，每行一条记录；`#` 开头与空行忽略）：
 * ```
 * format=mm-replay-set                       格式标记
 * version=1                                  格式版本
 * device=<设备>                               采集设备（报告引用，可选）
 * scene=<场景>                                场景说明（报告引用，可选）
 * sample=<文件>|<类别>|<信号名>|<矩形>|<备注>    样本（负样本信号名与矩形留空）
 * ```
 *
 * 类别 token（清单用）与 label（报告用）：`positive` / 正样本（必须给出目标信号名与
 * 期望位置）、`neg-mask` / `neg-motion` / `neg-similar`（§5「抗干扰」三类干扰：
 * 半透明遮罩 / 动背景 / 相似多目标）、`neg-plain`（其余负样本）。
 *
 * 期望位置为比例矩形（0..1，`left,top,right,bottom`，语义同 [SearchWindow]），
 * 与帧尺寸解耦（零设备绑定）。解析严格：未知键 / 未知类别 / 字段非法 / 文件重名
 * 一律拒绝（IllegalArgumentException，消息带行号），不做静默兼容。
 */
enum class ReplayCategory(val token: String, val label: String, val isNegative: Boolean) {
    POSITIVE("positive", "正样本", false),

    /** 干扰①：目标上叠半透明遮罩。 */
    NEGATIVE_MASK("neg-mask", "半透明遮罩（干扰①）", true),

    /** 干扰②：目标所在区域背景在动。 */
    NEGATIVE_MOTION("neg-motion", "动背景（干扰②）", true),

    /** 干扰③：同一画面多个外观相近目标，只有一个位置是目标。 */
    NEGATIVE_SIMILAR("neg-similar", "相似多目标（干扰③）", true),

    /** 其余负样本：画面中不存在目标标志。 */
    NEGATIVE_PLAIN("neg-plain", "普通负样本", true),
    ;

    companion object {
        /** 按 token 查类别；未知 token 返回 null（由调用方带行号报错）。 */
        fun fromToken(token: String): ReplayCategory? = entries.firstOrNull { it.token == token }
    }
}

/**
 * 一个回放样本：画面文件（相对样本集目录）+ 类别 + 正样本的目标信号与期望位置。
 *
 * [expected] 仅正样本存在（比例矩形，0..1）；负样本两项均为 null。
 */
data class ReplaySample(
    val file: String,
    val category: ReplayCategory,
    val signalName: String?,
    val expected: SearchWindow?,
    val note: String,
) {
    init {
        if (category.isNegative) {
            require(signalName == null && expected == null) { "负样本不得携带信号名或期望位置：$file" }
        } else {
            require(!signalName.isNullOrEmpty()) { "正样本必须给出目标信号名：$file" }
            requireNotNull(expected) { "正样本必须给出期望位置：$file" }
        }
    }
}

/** 固定批次样本集（T1-6a）：元信息 + 样本列表（保持清单声明顺序）。 */
data class ReplaySampleSet(
    val device: String?,
    val scene: String?,
    val samples: List<ReplaySample>,
)

/** 样本集清单编解码（T1-6a，纯逻辑）：同数据 → 同文本（§5-4 确定性）。 */
object ReplaySetCodec {

    private const val FORMAT_TAG = "mm-replay-set"
    private const val VERSION = 1
    private const val SEP = "|"
    private const val LIST_SEP = ","
    private val NAME_PATTERN = Regex("[a-z0-9_]{1,64}")

    /** 样本集 → 文本（顺序与字段固定，保证确定性）。 */
    fun encode(set: ReplaySampleSet): String = buildString {
        appendLine("# MasterMechanic 回放样本集清单（T1-6）：固定批次，供 JVM 离线回放与误报指标计算")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        set.device?.let { appendLine("device=$it") }
        set.scene?.let { appendLine("scene=$it") }
        set.samples.forEach { sample ->
            val rect = sample.expected?.let {
                "${num(it.left)},${num(it.top)},${num(it.right)},${num(it.bottom)}"
            }.orEmpty()
            appendLine(
                "sample=${sample.file}$SEP${sample.category.token}$SEP" +
                    "${sample.signalName.orEmpty()}$SEP$rect$SEP${sample.note}",
            )
        }
    }

    /** 文本 → 样本集（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): ReplaySampleSet {
        var format: String? = null
        var version: Int? = null
        var device: String? = null
        var scene: String? = null
        val samples = mutableListOf<ReplaySample>()
        val files = mutableSetOf<String>()

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("回放样本集第 $line 行：$reason")

        text.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val lineNo = index + 1
            val eq = line.indexOf('=')
            if (eq <= 0) fail(lineNo, "缺少「key=value」结构：$line")
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)

            when (key) {
                "format" -> {
                    if (format != null) fail(lineNo, "重复的 format 行")
                    format = value
                }
                "version" -> {
                    if (version != null) fail(lineNo, "重复的 version 行")
                    version = value.toIntOrNull() ?: fail(lineNo, "版本号非整数：$value")
                }
                "device" -> {
                    if (device != null) fail(lineNo, "重复的 device 行")
                    if (value.isBlank()) fail(lineNo, "device 不得为空")
                    device = value
                }
                "scene" -> {
                    if (scene != null) fail(lineNo, "重复的 scene 行")
                    if (value.isBlank()) fail(lineNo, "scene 不得为空")
                    scene = value
                }
                "sample" -> {
                    val parts = value.split(SEP)
                    if (parts.size != 5) fail(lineNo, "sample 行应为「文件|类别|信号名|矩形|备注」：$value")
                    val file = parts[0]
                    val categoryToken = parts[1]
                    val signalName = parts[2]
                    val rectText = parts[3]
                    val note = parts[4]
                    if (file.isBlank() || file.contains('/') || file.contains('\\')) {
                        fail(lineNo, "样本文件名非法（应为同目录文件名）：$file")
                    }
                    if (!files.add(file)) fail(lineNo, "样本文件重复：$file")
                    if (note.contains(SEP)) fail(lineNo, "备注不得包含「$SEP」：$note")
                    val category = ReplayCategory.fromToken(categoryToken)
                        ?: fail(lineNo, "未知类别：$categoryToken")
                    if (category.isNegative) {
                        if (signalName.isNotEmpty()) fail(lineNo, "负样本信号名必须留空：$signalName")
                        if (rectText.isNotEmpty()) fail(lineNo, "负样本矩形必须留空：$rectText")
                        samples.add(ReplaySample(file, category, null, null, note))
                    } else {
                        if (!NAME_PATTERN.matches(signalName)) fail(lineNo, "信号名非法：$signalName")
                        val nums = rectText.split(LIST_SEP)
                        if (nums.size != 4) fail(lineNo, "期望位置应为四项：$rectText")
                        val values = nums.map { it.toDoubleOrNull() ?: fail(lineNo, "位置值非数字：$it") }
                        val expected = try {
                            SearchWindow(values[0], values[1], values[2], values[3])
                        } catch (e: IllegalArgumentException) {
                            fail(lineNo, e.message ?: "期望位置非法")
                        }
                        samples.add(ReplaySample(file, category, signalName, expected, note))
                    }
                }
                else -> fail(lineNo, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException("回放样本集格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」")
        }
        if (version != VERSION) {
            throw IllegalArgumentException("回放样本集版本不符：期待 $VERSION，实际 ${version ?: "缺失"}")
        }
        if (samples.isEmpty()) {
            throw IllegalArgumentException("回放样本集没有任何 sample 行")
        }
        return ReplaySampleSet(device, scene, samples)
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证清单文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
