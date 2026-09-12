package com.example.mastermechanic.recognition

import java.util.Locale

/**
 * 回放指标报告渲染（T1-6d，纯逻辑）：报告文本生成，字段与
 * `docs/recognition/reports/report-template.md` 一一对应（一致性由单测校验）。
 *
 * 口径（§5 验收）：误报按上界公式表述、三类干扰分列（不合并）、样本不足不得声称「零误报」。
 * 确定性：同输入 → 同文本——日期与复现命令由调用方给出，渲染器不取系统时间、不读文件。
 */
object ReplayReportRenderer {

    /** 报告节标题（模板—渲染一致性由单测校验，渲染输出即用这些常量）。 */
    val SECTION_HEADERS: List<String> = listOf(
        H_CALIBER,
        H_SAMPLE_SIZE,
        H_NEGATIVE_TABLE,
        H_POSITIVE,
        H_REPRODUCIBILITY,
        H_COMMAND,
    )

    /** 零误报上界公式的固定表述（与模板同款）。 */
    const val UPPER_BOUND_FORMULA = "1 − 0.05^(1/n)"

    /** 报告输入：字段与模板一致。 */
    data class Input(
        val topic: String,
        val sampleSet: String,
        val device: String,
        val scene: String,
        val date: String,
        val paramsKey: String,
        val matchThreshold: Double,
        val ambiguityMargin: Double,
        val peakMinDistance: Int,
        val runs: Int,
        val metrics: NegativeMetrics,
        val positiveSamples: Int,
        val positiveMatched: Int,
        val differences: List<FieldDifference>,
        val reproCommand: String,
    )

    /** 渲染报告文本（Markdown）。 */
    fun render(input: Input): String {
        require(input.runs >= 2) { "可复现比对至少需要两次运行：${input.runs}" }
        require(input.positiveMatched in 0..input.positiveSamples) {
            "正样本命中数 ${input.positiveMatched} 超出样本量 ${input.positiveSamples}"
        }
        return buildString {
            appendLine("# 回放指标报告：${input.topic}")
            appendLine()
            appendLine("- 样本集：${input.sampleSet}")
            appendLine("- 采集设备：${input.device}")
            appendLine("- 采集场景：${input.scene}")
            appendLine("- 报告日期：${input.date}")
            appendLine(
                "- 参数集版本：${input.paramsKey}（命中线 ${num(input.matchThreshold)}" +
                    " / 差距线 ${num(input.ambiguityMargin)} / 峰值间距 ${input.peakMinDistance}）",
            )
            appendLine("- 运行次数：${input.runs}")
            appendLine()
            appendLine(H_CALIBER)
            appendLine()
            CALIBER_LINES.forEach { appendLine(it) }
            appendLine()
            appendLine(H_SAMPLE_SIZE)
            appendLine()
            val gate = if (input.metrics.sufficient) {
                "达到口径下限 ≥ ${ReplayMetrics.MIN_NEGATIVE_SAMPLES}"
            } else {
                "样本不足：低于口径下限 ${ReplayMetrics.MIN_NEGATIVE_SAMPLES}，不得声称「零误报」"
            }
            appendLine("- 负样本合计：${input.metrics.totalSamples}（$gate）")
            appendLine("- 正样本合计：${input.positiveSamples}")
            appendLine()
            appendLine(H_NEGATIVE_TABLE)
            appendLine()
            appendLine("| 类别 | 样本量 n | 误报数 | 点估计 / 上界 |")
            appendLine("| --- | --- | --- | --- |")
            input.metrics.stats.forEach { appendLine(statRow(it)) }
            appendLine(
                "| 合计（仅供样本量核对，不作为总通过率结论） | ${input.metrics.totalSamples}" +
                    " | ${input.metrics.totalFalsePositives} | — |",
            )
            appendLine()
            appendLine(H_POSITIVE)
            appendLine()
            if (input.positiveSamples == 0) {
                appendLine("- 未采集正样本。")
            } else {
                appendLine("- 命中 ${input.positiveMatched} / ${input.positiveSamples}")
            }
            appendLine()
            appendLine(H_REPRODUCIBILITY)
            appendLine()
            appendLine("- 运行次数：${input.runs}")
            if (input.differences.isEmpty()) {
                appendLine("- 逐字段比对：逐字段一致（无差异）")
            } else {
                appendLine("- 逐字段比对：存在差异（${input.differences.size} 条）")
                input.differences.forEach { appendLine("  - ${it.describe()}") }
            }
            appendLine()
            appendLine(H_COMMAND)
            appendLine()
            appendLine("```text")
            appendLine(input.reproCommand)
            appendLine("```")
        }
    }

    private const val H_CALIBER = "## 口径与公式"
    private const val H_SAMPLE_SIZE = "## 样本量"
    private const val H_NEGATIVE_TABLE = "## 三类干扰分列（不得合并）"
    private const val H_POSITIVE = "## 正样本（参照）"
    private const val H_REPRODUCIBILITY = "## 可复现比对"
    private const val H_COMMAND = "## 复现命令"

    private val CALIBER_LINES = listOf(
        "- 误报定义：负样本上出现任一信号命中（MATCHED）即为该样本误报；不可信（UNRELIABLE）按未识别处理，不计误报。",
        "- 零误报上界：n 个样本 0 次误报 → 真实误报率上界 = $UPPER_BOUND_FORMULA（95% 置信）。",
        "- 负样本总量下限 ${ReplayMetrics.MIN_NEGATIVE_SAMPLES}；不足时不得声称「零误报」。",
        "- 三类干扰分别统计，不得合并成一个总通过率。",
    )

    private fun statRow(stat: NegativeCategoryStat): String {
        val tail = stat.pointEstimate?.let { "点估计 ${num(it)}" }
            ?: stat.zeroFalsePositiveUpperBound?.let { "上界 ${num(it)}" }
            ?: "未采集"
        val falsePositives = if (stat.samples == 0) "—" else stat.falsePositives.toString()
        return "| ${stat.category.label} | ${stat.samples} | $falsePositives | $tail |"
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证报告文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
