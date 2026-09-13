package com.example.mastermechanic.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回放指标报告渲染单测（T1-6d）：模板—渲染一致性（节标题与公式逐字对齐）、
 * 三类分列 / 合计行 / 样本不足闸门 / 点估计与上界单元格、差异明细与确定性。
 */
class ReplayReportRendererTest {

    private fun stat(category: ReplayCategory, samples: Int, falsePositives: Int = 0) =
        NegativeCategoryStat(category, samples, falsePositives)

    private fun metrics(vararg given: NegativeCategoryStat): NegativeMetrics {
        val all = ReplayCategory.entries.filter { it.isNegative }.map { category ->
            given.firstOrNull { it.category == category } ?: NegativeCategoryStat(category, 0, 0)
        }
        return NegativeMetrics(all, all.sumOf { it.samples }, all.sumOf { it.falsePositives })
    }

    private fun input(
        metrics: NegativeMetrics = metrics(stat(ReplayCategory.NEGATIVE_MASK, 60)),
        differences: List<FieldDifference> = emptyList(),
        runs: Int = 3,
        positiveSamples: Int = 5,
        positiveMatched: Int = 5,
    ) = ReplayReportRenderer.Input(
        topic = "启动页负样本批次",
        sampleSet = "set-20260912-01",
        device = "vivo V2463A",
        scene = "启动页 / 大厅",
        date = "2026-09-12",
        paramsKey = "launch_v1",
        matchThreshold = 0.85,
        ambiguityMargin = 0.1,
        peakMinDistance = 5,
        runs = runs,
        metrics = metrics,
        positiveSamples = positiveSamples,
        positiveMatched = positiveMatched,
        differences = differences,
        reproCommand = "gradlew :app:test --tests ReplayToolTest",
    )

    /** 从工作目录向上找到仓库内文件（单测工作目录不保证为仓库根）。 */
    private fun repoFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("未找到仓库文件：$relative（工作目录 ${File("").absolutePath}）")
    }

    @Test
    fun templateAndRenderAgreeOnSectionsAndFormula() {
        val template = repoFile("docs/recognition/reports/report-template.md").readText(Charsets.UTF_8)
        val report = ReplayReportRenderer.render(input())

        ReplayReportRenderer.SECTION_HEADERS.forEach { header ->
            assertTrue("模板缺少节：$header", template.contains(header))
            assertTrue("渲染缺少节：$header", report.contains(header))
        }
        assertTrue(template.contains(ReplayReportRenderer.UPPER_BOUND_FORMULA))
        assertTrue(report.contains(ReplayReportRenderer.UPPER_BOUND_FORMULA))
        assertTrue(template.contains("不得声称「零误报」"))
        assertTrue(report.contains("不得声称「零误报」"))
    }

    @Test
    fun sufficientBatchRendersUpperBoundsSeparatelyAndTotalRow() {
        val report = ReplayReportRenderer.render(
            input(
                metrics = metrics(
                    stat(ReplayCategory.NEGATIVE_MASK, 20),
                    stat(ReplayCategory.NEGATIVE_MOTION, 20),
                    stat(ReplayCategory.NEGATIVE_SIMILAR, 20),
                ),
            ),
        )

        assertTrue(report.contains("- 负样本合计：60（达到口径下限 ≥ 60）"))
        assertTrue(report.contains("| 半透明遮罩（干扰①） | 20 | 0 | 上界 0.139108 |"))
        assertTrue(report.contains("| 动背景（干扰②） | 20 | 0 | 上界 0.139108 |"))
        assertTrue(report.contains("| 相似多目标（干扰③） | 20 | 0 | 上界 0.139108 |"))
        assertTrue(report.contains("| 普通负样本 | 0 | — | 未采集 |"))
        assertTrue(report.contains("| 合计（仅供样本量核对，不作为总通过率结论） | 60 | 0 | — |"))
        assertTrue(report.contains("- 命中 5 / 5"))
        assertTrue(!report.contains("样本不足：低于口径下限"))
    }

    @Test
    fun insufficientBatchMarksGate() {
        val report = ReplayReportRenderer.render(
            input(metrics = metrics(stat(ReplayCategory.NEGATIVE_MASK, 59))),
        )
        assertTrue(report.contains("样本不足：低于口径下限 60，不得声称「零误报」"))
    }

    @Test
    fun falsePositiveCellsUsePointEstimate() {
        val report = ReplayReportRenderer.render(
            input(metrics = metrics(stat(ReplayCategory.NEGATIVE_MASK, 50, falsePositives = 2))),
        )
        assertTrue(report.contains("| 半透明遮罩（干扰①） | 50 | 2 | 点估计 0.040000 |"))
    }

    @Test
    fun differencesAreListedWithSampleAndField() {
        val difference = FieldDifference("f-002.png", "launch_start", "结论", 1, "MATCHED", "NOT_MATCHED")
        val report = ReplayReportRenderer.render(input(differences = listOf(difference)))

        assertTrue(report.contains("- 逐字段比对：存在差异（1 条）"))
        assertTrue(
            report.contains(
                "  - 第 2 次运行：样本=f-002.png 信号=launch_start 字段=结论 基准=MATCHED 实际=NOT_MATCHED",
            ),
        )
    }

    @Test
    fun renderIsDeterministicAndValidatesInput() {
        assertEquals(ReplayReportRenderer.render(input()), ReplayReportRenderer.render(input()))
        assertThrows(IllegalArgumentException::class.java) {
            ReplayReportRenderer.render(input(runs = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplayReportRenderer.render(input(positiveSamples = 3, positiveMatched = 4))
        }
    }
}
