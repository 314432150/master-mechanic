package com.example.mastermechanic.recognition

import kotlin.math.pow

/**
 * 回放误报指标（T1-6b，纯逻辑）：负样本按类别分列统计，不合并总通过率。
 *
 * 口径（requirements §5 验收误报率）：
 * - 负样本总量不少于 [ReplayMetrics.MIN_NEGATIVE_SAMPLES] 个；
 * - 某类别样本量 n、零误报时，真实误报率上界为 `1 − 0.05^(1/n)`（不得表述为「零误报」）；
 * - 负样本总量不足时不得声称「零误报」（[NegativeMetrics.sufficient] = false）。
 */
object ReplayMetrics {

    /** 验收口径要求的负样本总量下限（requirements §5：负样本不少于 60 个）。 */
    const val MIN_NEGATIVE_SAMPLES = 60

    /**
     * 负样本误报按类别分列统计（三类干扰各占一行，禁止合并）。
     *
     * 返回的 [NegativeMetrics.stats] 覆盖全部负样本类别（按枚举声明顺序），
     * 未采集的类别以 n = 0 出现——覆盖缺口在报告中显式可见。
     */
    fun negativeMetrics(observations: List<NegativeObservation>): NegativeMetrics {
        val byCategory = observations.groupBy { it.sample.category }
        val stats = ReplayCategory.entries
            .filter { it.isNegative }
            .map { category ->
                val group = byCategory[category].orEmpty()
                NegativeCategoryStat(
                    category = category,
                    samples = group.size,
                    falsePositives = group.count { it.falsePositive },
                )
            }
        return NegativeMetrics(
            stats = stats,
            totalSamples = stats.sumOf { it.samples },
            totalFalsePositives = stats.sumOf { it.falsePositives },
        )
    }
}

/** 单条负样本回放观测：[matchedSignals] 非空 = 该样本上出现命中（误报）。 */
data class NegativeObservation(val sample: ReplaySample, val matchedSignals: List<String>) {
    init {
        require(sample.category.isNegative) { "仅负样本参与误报统计：${sample.file}" }
    }

    /** 该样本是否误报。 */
    val falsePositive: Boolean get() = matchedSignals.isNotEmpty()
}

/** 某一类别的负样本统计（[samples] 个样本中 [falsePositives] 个误报）。 */
data class NegativeCategoryStat(
    val category: ReplayCategory,
    val samples: Int,
    val falsePositives: Int,
) {
    init {
        require(category.isNegative) { "负样本统计不接受非负类别：$category" }
        require(samples >= 0) { "样本量不得为负：$samples" }
        require(falsePositives in 0..samples) { "误报数 $falsePositives 超出样本量 $samples" }
    }

    /** 点估计（误报数 / 样本量）；零误报时为 null——零误报按上界表述。 */
    val pointEstimate: Double?
        get() = if (falsePositives > 0) falsePositives.toDouble() / samples else null

    /**
     * 零误报上界 `1 − 0.05^(1/n)`；有误报或没有样本时为 null。
     * 上界成立与否的口径闸门在 [NegativeMetrics.sufficient]（样本不足时不得声称零误报）。
     */
    val zeroFalsePositiveUpperBound: Double?
        get() = if (falsePositives == 0 && samples > 0) 1.0 - 0.05.pow(1.0 / samples) else null
}

/** 负样本整体指标：分列统计 + 总量是否达到验收口径。 */
data class NegativeMetrics(
    /** 按类别分列（不合并；含未采集的 n = 0 类别）。 */
    val stats: List<NegativeCategoryStat>,
    val totalSamples: Int,
    val totalFalsePositives: Int,
) {
    /** 总负样本是否达到口径下限；false = 样本不足，不得声称「零误报」。 */
    val sufficient: Boolean get() = totalSamples >= ReplayMetrics.MIN_NEGATIVE_SAMPLES
}
