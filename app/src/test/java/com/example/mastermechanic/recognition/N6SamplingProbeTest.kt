package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **N6-4 离线门槛**（2026-10-25）：把**标志**的粗搜采样调稀之后，`MATCHED` 结论会不会漂。
 *
 * ## 为什么拿真机产物 + 真机帧池评（而不是合成图）
 *
 * 合成图的分数都很"干净"（笔直的高分、干净的低分），判定边缘根本压不到。真正会出问题的是
 * 真机那种"界面在、但只像 0.86"的帧 —— 采样一稀，最高分往下掉一点，就可能掉到命中线以下。
 * ⇒ 数据源就是设备上那份 `calibration.txt` + `files/calibration/frames` 抽样的帧。
 *
 * ## 评什么（四件事，与 `CoarseSampling` 的 KDoc 一一对应）
 *
 * 1. **判定结论**（MATCHED / NOT_MATCHED / UNRELIABLE）：**必须零漂移** —— 这是硬门槛；
 * 2. best 位置漂多少（标志容忍 1~2px，锚点零容忍 ⇒ 锚点根本不在本档的适用范围里）；
 * 3. best 分数掉了多少（红线 7：分数往下掉会让"最高与次高差距"更早触底 ⇒ 要看一眼）；
 * 4. 相对耗时（同进程、同帧、同一轮循环，只看比值）。
 *
 * 数据准备（不随仓库走）：`app/build/n6-4-data/` 下放 `calibration.txt` + `frames` 目录里的 png；
 * 缺数据时本用例 `assume` 跳过而不是失败（与 `SpeedupProbeTest` 同一口径）。
 */
class N6SamplingProbeTest {

    private val dataDir = File(System.getProperty("user.dir"), "build/n6-4-data")
    private val artifactFile = File(dataDir, "calibration.txt")
    private val framesDir = File(dataDir, "frames")
    private val outDir = File(dataDir, "out")

    /** 三档：基线 / 只稀疏采样 / 稀疏采样 + 代表点减半。顺序即报告列顺序。 */
    private val variants = listOf(
        "基线 半采样 K64" to CoarseSampling.FULL,
        "3x3 K64" to CoarseSampling.SPARSE_WIDE,
        "3x3 K32" to CoarseSampling.SPARSE,
    )

    @Test
    fun sparseSamplingKeepsMarkerVerdicts() {
        assumeTrue("缺产物：${artifactFile.absolutePath}", artifactFile.isFile)
        val frameFiles = framesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("缺帧（至少 5 张）：${framesDir.absolutePath}", frameFiles.size >= 5)

        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val markers = data.markerSpecs()
        assumeTrue("产物里没有标志", markers.isNotEmpty())

        val grays = frameFiles.map { it.name to ReplayTool.decodeGrayPng(it) }
        val (_, probe) = grays.first()
        assumeTrue(
            "帧与产物几何不一致（换过设备 / 方向）：帧 ${probe.width}x${probe.height}，" +
                "产物 ${data.frameWidth}x${data.frameHeight}",
            probe.width == data.frameWidth && probe.height == data.frameHeight,
        )

        val lines = ArrayList<String>()
        lines += "N6-4 采样对照｜标志 ${markers.size} 条｜画面 ${grays.size} 帧（${probe.width}x${probe.height}）" +
            "｜产物 ${data.frameWidth}x${data.frameHeight}｜参数 命中线 ${data.params.matchThreshold} / 差距线 ${data.params.ambiguityMargin}"
        val header = "信号｜命中帧｜" + variants.drop(1).joinToString("｜") { (label, _) ->
            "$label 判定漂/位置漂/最大Δ分数/耗时"
        }
        lines += header

        var verdictDiffsOfTarget = 0
        var positivesTotal = 0
        var baseTotalMs = 0.0
        var targetTotalMs = 0.0

        for (spec in markers) {
            // 预热一遍（JIT + 采样视图构建），不计时
            variants.forEach { (_, sampling) ->
                val s = spec.withSampling(sampling)
                SignalDetector(listOf(s), data.params).detectSignal(grays.first().second, s)
            }
            val measured = variants.map { (_, sampling) ->
                val s = spec.withSampling(sampling)
                val detector = SignalDetector(listOf(s), data.params)
                val started = System.nanoTime()
                val records = grays.map { detector.detectSignal(it.second, s) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
                records to elapsedMs
            }
            val baseline = measured.first().first
            val positives = baseline.count { it.matched }
            positivesTotal += positives
            baseTotalMs += measured.first().second

            val cells = StringBuilder()
            for (index in 1 until measured.size) {
                val (label, _) = variants[index]
                val records = measured[index].first
                var verdictDiffs = 0
                var positionDiffs = 0
                var maxScoreDrop = 0.0
                records.indices.forEach { f ->
                    val base = baseline[f]
                    val other = records[f]
                    if (base.matched != other.matched || base.verdict != other.verdict) verdictDiffs++
                    val a = base.best
                    val b = other.best
                    if (a != null && b != null) {
                        if (a.x != b.x || a.y != b.y) positionDiffs++
                        maxScoreDrop = maxOf(maxScoreDrop, a.score - b.score)
                    } else if ((a == null) != (b == null)) {
                        verdictDiffs++
                    }
                }
                val ms = measured[index].second / grays.size
                cells.append("｜判 $verdictDiffs / 位 $positionDiffs / Δ ${"%.4f".format(maxScoreDrop)} / " +
                    "${"%.1f".format(ms)}ms ×${"%.2f".format(measured.first().second / measured[index].second)}")
                if (label == "3x3 K32") {
                    verdictDiffsOfTarget += verdictDiffs
                    targetTotalMs += measured[index].second
                }
            }
            lines += "${spec.name}｜$positives/${grays.size}$cells"
        }

        val summary = "小结｜命中帧合计 $positivesTotal｜3x3 K32 判定漂移合计 **$verdictDiffsOfTarget**" +
            "｜每帧总耗时 基线 ${"%.1f".format(baseTotalMs / grays.size)}ms → " +
            "3x3 K32 ${"%.1f".format(targetTotalMs / grays.size)}ms" +
            "（×${"%.2f".format(baseTotalMs / targetTotalMs)}，仅标志）"
        lines += summary

        outDir.mkdirs()
        val report = File(outDir, "n6-4-sampling.txt")
        report.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)

        assertTrue(
            "标志在 3x3 + K32 下的判定结论必须零漂移（报告见 ${report.absolutePath}）",
            verdictDiffsOfTarget == 0,
        )
    }

    private fun SignalSpec.withSampling(sampling: CoarseSampling) =
        SignalSpec(name, window, templates, sampling)
}
