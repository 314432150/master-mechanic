package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放误报指标单测（T1-6b）：三类分列统计（不合并）、零误报上界公式、
 * 有误报时的点估计、样本下限闸门（总数 < 60 不得声称「零误报」）。
 */
class ReplayMetricsTest {

    private var seq = 0

    private fun negativeSample(category: ReplayCategory): ReplaySample {
        seq += 1
        return ReplaySample("f-%03d.png".format(seq), category, null, null, "")
    }

    private fun negatives(
        category: ReplayCategory,
        count: Int,
        falsePositives: Int = 0,
    ): List<NegativeObservation> = (0 until count).map { i ->
        NegativeObservation(
            negativeSample(category),
            if (i < falsePositives) listOf("target_signal") else emptyList(),
        )
    }

    @Test
    fun upperBoundMatchesFormulaForKnownSampleSizes() {
        val one = ReplayMetrics.negativeMetrics(negatives(ReplayCategory.NEGATIVE_PLAIN, 1))
            .stats.first { it.category == ReplayCategory.NEGATIVE_PLAIN }
        assertEquals(0.95, one.zeroFalsePositiveUpperBound!!, 1e-12)

        val sixty = ReplayMetrics.negativeMetrics(negatives(ReplayCategory.NEGATIVE_MASK, 60))
            .stats.first { it.category == ReplayCategory.NEGATIVE_MASK }
        assertEquals(0.0487029, sixty.zeroFalsePositiveUpperBound!!, 1e-5)
    }

    @Test
    fun zeroFalsePositivesUseUpperBoundNotPointEstimate() {
        val metrics = ReplayMetrics.negativeMetrics(negatives(ReplayCategory.NEGATIVE_MASK, 60))
        val stat = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_MASK }

        assertEquals(60, stat.samples)
        assertEquals(0, stat.falsePositives)
        assertNull(stat.pointEstimate)
        assertTrue(stat.zeroFalsePositiveUpperBound!! < 0.05)
        assertEquals(0, metrics.totalFalsePositives)
        assertTrue(metrics.sufficient)
    }

    @Test
    fun falsePositivesReportPointEstimateAndNoBound() {
        val metrics = ReplayMetrics.negativeMetrics(
            negatives(ReplayCategory.NEGATIVE_MASK, 50, falsePositives = 2),
        )
        val stat = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_MASK }

        assertEquals(2, stat.falsePositives)
        assertEquals(0.04, stat.pointEstimate!!, 1e-12)
        assertNull(stat.zeroFalsePositiveUpperBound)
        assertEquals(2, metrics.totalFalsePositives)
    }

    @Test
    fun sampleFloorGateCountsTotalAcrossCategories() {
        val below = negatives(ReplayCategory.NEGATIVE_MASK, 30) +
            negatives(ReplayCategory.NEGATIVE_SIMILAR, 29)
        assertFalse(ReplayMetrics.negativeMetrics(below).sufficient)

        val atLimit = negatives(ReplayCategory.NEGATIVE_MASK, 30) +
            negatives(ReplayCategory.NEGATIVE_SIMILAR, 30)
        assertTrue(ReplayMetrics.negativeMetrics(atLimit).sufficient)
    }

    @Test
    fun categoriesAreReportedSeparatelyAndNotMerged() {
        val observations = negatives(ReplayCategory.NEGATIVE_MASK, 10, falsePositives = 1) +
            negatives(ReplayCategory.NEGATIVE_SIMILAR, 20) +
            negatives(ReplayCategory.NEGATIVE_PLAIN, 30)

        val metrics = ReplayMetrics.negativeMetrics(observations)

        // 固定四行（三类干扰 + 普通负样本），未采集的类别以 n = 0 出现
        assertEquals(
            listOf(
                ReplayCategory.NEGATIVE_MASK,
                ReplayCategory.NEGATIVE_MOTION,
                ReplayCategory.NEGATIVE_SIMILAR,
                ReplayCategory.NEGATIVE_PLAIN,
            ),
            metrics.stats.map { it.category },
        )
        val mask = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_MASK }
        assertEquals(10, mask.samples)
        assertEquals(1, mask.falsePositives)
        val motion = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_MOTION }
        assertEquals(0, motion.samples)
        assertNull(motion.zeroFalsePositiveUpperBound)
        val similar = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_SIMILAR }
        assertEquals(20, similar.samples)
        assertEquals(0, similar.falsePositives)
        val plain = metrics.stats.first { it.category == ReplayCategory.NEGATIVE_PLAIN }
        assertEquals(30, plain.samples)

        // 总量汇总但不合并结论：60 达到口径下限
        assertEquals(60, metrics.totalSamples)
        assertEquals(1, metrics.totalFalsePositives)
        assertTrue(metrics.sufficient)
    }

    @Test
    fun rejectsPositiveObservationAndInvalidStat() {
        val positive = ReplaySample(
            "p.png",
            ReplayCategory.POSITIVE,
            "sig",
            SearchWindow(0.1, 0.1, 0.2, 0.2),
            "",
        )
        assertThrows(IllegalArgumentException::class.java) {
            NegativeObservation(positive, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NegativeCategoryStat(ReplayCategory.NEGATIVE_MASK, samples = 3, falsePositives = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NegativeCategoryStat(ReplayCategory.POSITIVE, samples = 3, falsePositives = 0)
        }
    }
}
