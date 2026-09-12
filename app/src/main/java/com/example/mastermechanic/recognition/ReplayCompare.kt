package com.example.mastermechanic.recognition

/**
 * 同数据重跑比对（T1-6c，纯逻辑）：同一数据集 + 同一套参数多次运行 → 判定记录逐字段比对（§5-4）。
 *
 * 沿用 T1-3 口径（信号名 / 结论 / 最高分位置与分数 / 竞争位置与分数，逐字段一致），
 * 扩展至真实样本集：比对单元细化到样本，差异明细定位到「样本 + 信号 + 字段」。
 * 以第一次运行为基准，其余各次逐一比对；无差异 = 该批次可复现。
 *
 * 可比性前置（[ReplayComparator.compare] 校验）：各次运行必须使用同一数据集
 * （[ReplayRun.sampleSet]）与同一套参数（[ReplayRun.paramsKey]）；不一致直接拒绝，
 * 不产出比对结果——拿不同数据或不同参数的运行互比没有意义。
 */

/** 单次回放的单样本结果：样本文件 + 该样本全部信号的判定记录（信号遍历顺序固定）。 */
data class ReplaySampleResult(val file: String, val records: List<DetectionRecord>)

/**
 * 一次回放的完整结果。
 *
 * [sampleSet] = 样本集版本标识（如 `set-YYYYMMDD-NN`）；[paramsKey] = 参数集版本 / 指纹
 * （调用方给出、报告引用）。二者构成各次运行的「可比性」条件。
 */
data class ReplayRun(
    val sampleSet: String,
    val paramsKey: String,
    val results: List<ReplaySampleResult>,
)

/** 一次比对中的一条差异：定位到「样本 + 信号 + 字段」，记基准值与实际值。 */
data class FieldDifference(
    val file: String,
    val signalName: String?,
    val field: String,
    val runIndex: Int,
    val baseline: String,
    val actual: String,
) {
    /** 单行呈现（确定性文本，供报告与测试引用）。 */
    fun describe(): String =
        "第 ${runIndex + 1} 次运行：样本=$file 信号=${signalName ?: "—"} " +
            "字段=$field 基准=$baseline 实际=$actual"
}

/** 同数据重跑比对器（T1-6c）。 */
object ReplayComparator {

    /**
     * 逐字段比对多次运行（以第一次运行为基准），返回差异明细列表；空列表 = 逐字段一致。
     *
     * @throws IllegalArgumentException 少于两次运行，或样本集 / 参数集不一致。
     */
    fun compare(runs: List<ReplayRun>): List<FieldDifference> {
        require(runs.size >= 2) { "重跑比对至少需要两次运行：${runs.size}" }
        val baseline = runs.first()
        for (runIndex in 1 until runs.size) {
            val run = runs[runIndex]
            require(run.sampleSet == baseline.sampleSet) {
                "第 ${runIndex + 1} 次运行样本集不一致：${run.sampleSet}（基准 ${baseline.sampleSet}）"
            }
            require(run.paramsKey == baseline.paramsKey) {
                "第 ${runIndex + 1} 次运行参数集不一致：${run.paramsKey}（基准 ${baseline.paramsKey}）"
            }
        }

        val differences = mutableListOf<FieldDifference>()
        val baselineFiles = baseline.results.map { it.file }.toSet()
        for (runIndex in 1 until runs.size) {
            val actualByFile = runs[runIndex].results.associateBy { it.file }

            baseline.results.forEach { baseSample ->
                val actSample = actualByFile[baseSample.file]
                if (actSample == null) {
                    differences += FieldDifference(baseSample.file, null, "样本", runIndex, "存在", "缺失")
                    return@forEach
                }
                compareRecords(baseSample, actSample, runIndex, differences)
            }
            runs[runIndex].results.forEach { actSample ->
                if (actSample.file !in baselineFiles) {
                    differences += FieldDifference(actSample.file, null, "样本", runIndex, "缺失", "存在")
                }
            }
        }
        return differences
    }

    /** 差异明细 → 文本；无差异时给出固定表述（报告引用）。 */
    fun describeAll(differences: List<FieldDifference>): String =
        if (differences.isEmpty()) "逐字段一致（无差异）" else differences.joinToString("\n") { it.describe() }

    private fun compareRecords(
        baseSample: ReplaySampleResult,
        actSample: ReplaySampleResult,
        runIndex: Int,
        out: MutableList<FieldDifference>,
    ) {
        val baseRecords = baseSample.records
        val actRecords = actSample.records
        val common = minOf(baseRecords.size, actRecords.size)
        for (i in 0 until common) {
            compareRecord(baseSample.file, baseRecords[i], actRecords[i], runIndex, out)
        }
        if (actRecords.size < baseRecords.size) {
            for (i in common until baseRecords.size) {
                out += FieldDifference(baseSample.file, baseRecords[i].signalName, "判定记录", runIndex, "存在", "缺失")
            }
        } else if (actRecords.size > baseRecords.size) {
            for (i in common until actRecords.size) {
                out += FieldDifference(baseSample.file, actRecords[i].signalName, "判定记录", runIndex, "缺失", "多余")
            }
        }
    }

    private fun compareRecord(
        file: String,
        base: DetectionRecord,
        act: DetectionRecord,
        runIndex: Int,
        out: MutableList<FieldDifference>,
    ) {
        fun add(field: String, baseline: String, actual: String) {
            out += FieldDifference(file, base.signalName, field, runIndex, baseline, actual)
        }
        if (base.signalName != act.signalName) add("信号名", base.signalName, act.signalName)
        if (base.verdict != act.verdict) add("结论", base.verdict.name, act.verdict.name)
        comparePeak("最高分", file, base.signalName, base.best, act.best, runIndex, out)
        comparePeak("竞争位置", file, base.signalName, base.competitor, act.competitor, runIndex, out)
    }

    private fun comparePeak(
        prefix: String,
        file: String,
        signalName: String,
        base: MatchPeak?,
        act: MatchPeak?,
        runIndex: Int,
        out: MutableList<FieldDifference>,
    ) {
        fun add(field: String, baseline: String, actual: String) {
            out += FieldDifference(file, signalName, field, runIndex, baseline, actual)
        }
        if (base == null || act == null) {
            if (base != act) add(prefix, renderPeak(base), renderPeak(act))
            return
        }
        if (base.score != act.score) add("$prefix·分数", base.score.toString(), act.score.toString())
        if (base.x != act.x) add("$prefix·x", base.x.toString(), act.x.toString())
        if (base.y != act.y) add("$prefix·y", base.y.toString(), act.y.toString())
    }

    private fun renderPeak(peak: MatchPeak?): String =
        if (peak == null) "无" else "分数=${peak.score} x=${peak.x} y=${peak.y}"
}
