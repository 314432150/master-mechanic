package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同数据重跑比对单测（T1-6c）：逐字段一致判定、差异定位到「样本 + 信号 + 字段」、
 * 多轮次运行序号、样本缺失 / 多余与记录数不齐，以及可比性前置（数据 / 参数 / 次数）。
 */
class ReplayCompareTest {

    private fun record(
        signal: String = "launch_start",
        verdict: Verdict = Verdict.MATCHED,
        best: MatchPeak? = MatchPeak(0.91, 30, 40),
        competitor: MatchPeak? = MatchPeak(0.42, 300, 200),
    ) = DetectionRecord(signal, verdict, best, competitor)

    private fun sample(file: String, vararg records: DetectionRecord) =
        ReplaySampleResult(file, records.toList())

    private fun baselineRun(): ReplayRun = ReplayRun(
        sampleSet = "set-20260912-01",
        paramsKey = "launch_v1",
        results = listOf(
            sample(
                "frame-001.png",
                record("launch_start"),
                record("hall_entry", Verdict.NOT_MATCHED, MatchPeak(0.31, 88, 77), null),
            ),
            sample("frame-002.png", record("launch_start", Verdict.NOT_MATCHED, MatchPeak(0.22, 5, 6), null)),
        ),
    )

    /** 对某个样本的某条信号记录做字段级修改（保持其余数据与基准一致）。 */
    private fun ReplayRun.mutateRecord(file: String, signal: String, mutate: (DetectionRecord) -> DetectionRecord) =
        copy(
            results = results.map { s ->
                if (s.file == file) {
                    s.copy(records = s.records.map { r -> if (r.signalName == signal) mutate(r) else r })
                } else {
                    s
                }
            },
        )

    @Test
    fun identicalRunsHaveNoDifferences() {
        val differences = ReplayComparator.compare(listOf(baselineRun(), baselineRun(), baselineRun()))
        assertTrue(differences.isEmpty())
        assertEquals("逐字段一致（无差异）", ReplayComparator.describeAll(differences))
    }

    @Test
    fun differenceIsLocatedToSampleSignalAndField() {
        // 前两次一致，第三次结论翻转 → 仅第 3 次产生一条差异，定位到样本 + 信号 + 字段
        val third = baselineRun().mutateRecord("frame-001.png", "launch_start") {
            it.copy(verdict = Verdict.NOT_MATCHED)
        }

        val differences = ReplayComparator.compare(listOf(baselineRun(), baselineRun(), third))

        assertEquals(1, differences.size)
        val d = differences.single()
        assertEquals("frame-001.png", d.file)
        assertEquals("launch_start", d.signalName)
        assertEquals("结论", d.field)
        assertEquals(2, d.runIndex)
        assertEquals("MATCHED", d.baseline)
        assertEquals("NOT_MATCHED", d.actual)
        assertEquals(
            "第 3 次运行：样本=frame-001.png 信号=launch_start 字段=结论 基准=MATCHED 实际=NOT_MATCHED",
            d.describe(),
        )
    }

    @Test
    fun detectsScoreAndPositionDifferences() {
        val second = baselineRun().mutateRecord("frame-001.png", "launch_start") {
            it.copy(best = MatchPeak(0.9, 31, 40))
        }

        val differences = ReplayComparator.compare(listOf(baselineRun(), second))

        assertEquals(listOf("最高分·分数", "最高分·x"), differences.map { it.field })
        assertEquals("0.91", differences[0].baseline)
        assertEquals("0.9", differences[0].actual)
        assertEquals("30", differences[1].baseline)
        assertEquals("31", differences[1].actual)
    }

    @Test
    fun detectsCompetitorPresenceMismatch() {
        val second = baselineRun().mutateRecord("frame-001.png", "launch_start") {
            it.copy(competitor = null)
        }

        val differences = ReplayComparator.compare(listOf(baselineRun(), second))

        val d = differences.single()
        assertEquals("竞争位置", d.field)
        assertEquals("分数=0.42 x=300 y=200", d.baseline)
        assertEquals("无", d.actual)
    }

    @Test
    fun detectsMissingAndExtraSamples() {
        val baseline = baselineRun()
        val second = ReplayRun(
            sampleSet = baseline.sampleSet,
            paramsKey = baseline.paramsKey,
            results = listOf(
                baseline.results[1], // 缺 frame-001.png
                sample("frame-999.png", record("launch_start", Verdict.NOT_MATCHED, null, null)),
            ),
        )

        val differences = ReplayComparator.compare(listOf(baseline, second))

        assertEquals(2, differences.size)
        assertEquals("frame-001.png", differences[0].file)
        assertEquals("样本", differences[0].field)
        assertEquals("存在", differences[0].baseline)
        assertEquals("缺失", differences[0].actual)
        assertEquals("frame-999.png", differences[1].file)
        assertEquals("样本", differences[1].field)
        assertEquals("缺失", differences[1].baseline)
        assertEquals("存在", differences[1].actual)
    }

    @Test
    fun detectsRecordCountMismatch() {
        val baseline = baselineRun()
        val second = baseline.copy(
            results = listOf(
                // 多出一条信号记录
                baseline.results[0].copy(
                    records = baseline.results[0].records + record("extra_signal", Verdict.NOT_MATCHED, null, null),
                ),
                // 少掉唯一一条信号记录
                baseline.results[1].copy(records = emptyList()),
            ),
        )

        val differences = ReplayComparator.compare(listOf(baseline, second))

        assertEquals(2, differences.size)
        assertEquals("frame-001.png", differences[0].file)
        assertEquals("extra_signal", differences[0].signalName)
        assertEquals("判定记录", differences[0].field)
        assertEquals("缺失", differences[0].baseline)
        assertEquals("多余", differences[0].actual)
        assertEquals("frame-002.png", differences[1].file)
        assertEquals("launch_start", differences[1].signalName)
        assertEquals("存在", differences[1].baseline)
        assertEquals("缺失", differences[1].actual)
    }

    @Test
    fun rejectsMismatchedSampleSet() {
        val second = baselineRun().copy(sampleSet = "set-20260912-02")
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReplayComparator.compare(listOf(baselineRun(), second))
        }
        assertTrue(error.message!!.contains("样本集不一致"))
    }

    @Test
    fun rejectsMismatchedParams() {
        val second = baselineRun().copy(paramsKey = "launch_v2")
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReplayComparator.compare(listOf(baselineRun(), second))
        }
        assertTrue(error.message!!.contains("参数集不一致"))
    }

    @Test
    fun rejectsInsufficientRuns() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ReplayComparator.compare(listOf(baselineRun()))
        }
        assertTrue(error.message!!.contains("至少需要两次运行"))
    }
}
