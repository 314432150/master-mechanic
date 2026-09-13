package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.RecognitionLoop
import com.example.mastermechanic.decision.SignalStateMapping
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 后续提速手段评估探针（T1-10e，纯离线、可复跑）：在固定批次（104 帧 + calib-v1）上量化
 * **更稀疏采样粗搜（3×3）** 与 **部分和剪枝** 两种手段的耗时收益与判定一致性。
 *
 * 设计要点：
 * - 探针内为**参数化复刻**的匹配器（采样步长 / 网格步长 / 精搜锚点数 / 剪枝开关可调），
 *   生产实现 `TemplateMatcher.findPeaks` 保持不变；
 * - 先做「复刻基线 ≡ 生产实现」逐字段自校验，证明探针与生产口径一致；
 * - 剪枝为**上界剪枝**（Cauchy–Schwarz：剩余行点积 ≤ √(剩余模板能量 × 剩余窗口方差)），
 *   只跳过「不可能超过当前最优」的位置，**不改变最终分数与位置**（判定语义不变）；
 * - JVM 微基准只取**相对比值**（同条件同轮次、每帧取中位），绝对值受 JIT / 调度影响。
 *
 * 工作目录 `build/replay-work/`（不入版本库）：`calibration.txt` + `set-20260912-03/`（清单 + 灰度帧）。
 */
class SpeedupProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val batchDir = File(workDir, "set-20260912-03")
    private val artifactFile = File(workDir, "calibration.txt")
    /** 设备侧 7 信号产物副本（多信号成本测量用；不存在时该测量跳过）。 */
    private val multiSignalFile = File(workDir, "calibration-7sig.txt")
    private val outDir = File(workDir, "out")

    /** 分段耗时累加器（前缀和构建 / 粗搜 / 精搜）。 */
    private class Stats(var prefixNs: Long = 0, var coarseNs: Long = 0, var refineNs: Long = 0) {
        fun add(prefix: Long, coarse: Long, refine: Long) {
            prefixNs += prefix
            coarseNs += coarse
            refineNs += refine
        }
    }

    /** 变体：采样步长 / 网格步长 / 精搜锚点数 / 是否剪枝。 */
    private data class Variant(
        val name: String,
        val sampleStep: Int,
        val gridStep: Int,
        val topK: Int,
        val pruning: Boolean,
    )

    private data class Row(
        val name: String,
        val p50: Double,
        val p95: Double,
        val avg: Double,
        val ratio: Double,
        val verdictDiffs: Int,
        val bestPositionDiffs: Int,
        val bestScoreMaxDiff: Double,
        val competitorScoreMaxDiff: Double,
    )

    private val variants = listOf(
        Variant("基线 采样2 网格2 K64", sampleStep = 2, gridStep = 2, topK = 64, pruning = false),
        Variant("3x3采样 网格2 K64", sampleStep = 3, gridStep = 2, topK = 64, pruning = false),
        Variant("剪枝 采样2 网格2 K64", sampleStep = 2, gridStep = 2, topK = 64, pruning = true),
        Variant("3x3采样+剪枝 K64", sampleStep = 3, gridStep = 2, topK = 64, pruning = true),
        Variant("3x3采样+剪枝 K32", sampleStep = 3, gridStep = 2, topK = 32, pruning = true),
        Variant("3x3采样+剪枝 K16", sampleStep = 3, gridStep = 2, topK = 16, pruning = true),
    )

    @Test
    fun evaluateSpeedupVariants() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/calibration.txt 与 set-20260912-03/manifest.txt",
            artifactFile.isFile && File(batchDir, ReplayTool.MANIFEST_NAME).isFile,
        )
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val set = ReplaySetCodec.decode(File(batchDir, ReplayTool.MANIFEST_NAME).readText(Charsets.UTF_8))
        // 排除纯空帧（neg-plain）：画面近恒定、分数全 0，不参与耗时与一致性统计
        val samples = set.samples.filter { it.category.token != "neg-plain" }
        assumeTrue("有效样本不足（≥10）", samples.size >= 10)

        val spec = data.signals.first()
        val template = spec.templates.first()
        val window = spec.window
        // 预解码：同一帧被多个变体复用，避免 IO 混入耗时统计
        val frames: List<Pair<String, GrayImage>> = samples.map { sample ->
            sample.file to ReplayTool.decodeGrayPng(File(batchDir, sample.file))
        }

        // --- 自校验：复刻基线 ≡ 生产实现 ---
        var mismatches = 0
        frames.forEach { (_, gray) ->
            val replica = Matcher(2, 2, 64, false).findPeaks(gray, template, window, data.params)
            val produced = TemplateMatcher.findPeaks(gray, template, window, data.params)
            if (!samePeaks(replica, produced)) mismatches++
        }
        println("自校验：复刻基线 vs 生产实现，不一致帧数 = $mismatches / ${frames.size}")
        assertTrue("复刻基线必须与生产实现逐字段一致", mismatches == 0)

        // --- 基线（一致性比对基准）：判定结论 + 峰值序列（判定只取 best / competitor） ---
        val judge = SignalDetector(emptyList(), data.params)
        val baseline = HashMap<String, Pair<Verdict, List<MatchPeak>>>()
        frames.forEach { (name, gray) ->
            val peaks = Matcher(2, 2, 64, false).findPeaks(gray, template, window, data.params)
            baseline[name] = judge.judge(peaks) to peaks
        }

        // --- 逐变体：耗时 + 一致性 ---
        data class Measured(
            val variant: Variant,
            val times: List<Long>,
            val verdictDiffs: Int,
            val bestPositionDiffs: Int,
            val bestScoreMaxDiff: Double,
            val competitorScoreMaxDiff: Double,
        )
        val measured = ArrayList<Measured>()
        variants.forEach { variant ->
            val times = ArrayList<Long>(frames.size)
            var verdictDiffs = 0
            var bestPositionDiffs = 0
            var bestScoreMaxDiff = 0.0
            var competitorScoreMaxDiff = 0.0
            frames.forEach { (name, gray) ->
                val matcher = Matcher(variant.sampleStep, variant.gridStep, variant.topK, variant.pruning)
                val round = LongArray(ROUNDS)
                var peaks: List<MatchPeak> = emptyList()
                for (r in 0 until ROUNDS) {
                    val started = System.nanoTime()
                    peaks = matcher.findPeaks(gray, template, window, data.params)
                    round[r] = System.nanoTime() - started
                }
                round.sort()
                times += round[ROUNDS / 2] / 1_000_000

                val (baseVerdict, basePeaks) = baseline[name]!!
                if (judge.judge(peaks) != baseVerdict) verdictDiffs++
                val best = peaks.firstOrNull()
                val baseBest = basePeaks.firstOrNull()
                if (best == null || baseBest == null) {
                    if (best != baseBest) bestPositionDiffs++
                } else {
                    bestScoreMaxDiff = maxOf(bestScoreMaxDiff, abs(best.score - baseBest.score))
                    if (best.x != baseBest.x || best.y != baseBest.y) bestPositionDiffs++
                }
                val competitor = peaks.getOrNull(1)
                val baseCompetitor = basePeaks.getOrNull(1)
                if (competitor != null && baseCompetitor != null) {
                    competitorScoreMaxDiff = maxOf(
                        competitorScoreMaxDiff,
                        abs(competitor.score - baseCompetitor.score),
                    )
                }
            }
            measured += Measured(
                variant, times, verdictDiffs, bestPositionDiffs, bestScoreMaxDiff, competitorScoreMaxDiff,
            )
        }

        val baselineAvg = measured.first().times.average()
        val rows = measured.map { m ->
            val sorted = m.times.sorted()
            val p50 = sorted[sorted.size / 2].toDouble()
            val p95 = sorted[minOf(sorted.size - 1, ceil(sorted.size * 0.95).toInt() - 1)].toDouble()
            val avg = m.times.average()
            Row(
                name = m.variant.name,
                p50 = p50,
                p95 = p95,
                avg = avg,
                ratio = if (avg > 0.0) baselineAvg / avg else 0.0,
                verdictDiffs = m.verdictDiffs,
                bestPositionDiffs = m.bestPositionDiffs,
                bestScoreMaxDiff = m.bestScoreMaxDiff,
                competitorScoreMaxDiff = m.competitorScoreMaxDiff,
            )
        }

        val lines = mutableListOf<String>()
        lines += "T1-10e 提速手段评估（固定批次有效帧 ${frames.size}，信号 ${spec.name}，模板 ${template.width}x${template.height}）"
        lines += "口径：每帧每变体跑 $ROUNDS 轮取中位耗时；剪枝每 $PRUNE_CHECK_ROWS 行做一次上界检查；JVM 相对比值，非设备绝对值"
        lines += "自校验：复刻基线 ≡ 生产实现，不一致帧数 = $mismatches"
        lines += ""
        lines += "变体 | P50(ms) | P95(ms) | 平均(ms) | 提速比 | 结论不一致帧 | best位置不一致帧 | best分数最大差 | 竞争分最大差"
        rows.forEach { row ->
            lines += "${row.name} | ${fmt(row.p50)} | ${fmt(row.p95)} | ${fmt(row.avg)} | " +
                "x${String.format(Locale.ROOT, "%.2f", row.ratio)} | ${row.verdictDiffs} | " +
                "${row.bestPositionDiffs} | ${String.format(Locale.ROOT, "%.6f", row.bestScoreMaxDiff)} | " +
                String.format(Locale.ROOT, "%.6f", row.competitorScoreMaxDiff)
        }

        outDir.mkdirs()
        File(outDir, "t1-10e-speedup.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("应输出全部变体结果", rows.size == variants.size)
    }

    /**
     * 多信号成本与分段耗时剖析（T1-10f 前置测量）：回答「多信号时每帧到底慢多少、时间花在哪」。
     *
     * 逐档取产物前 N 条信号（1 / 2 / 4 / 全部）跑完整匹配，统计每帧总耗时与
     * 「前缀和构建 / 粗搜 / 精搜」三段占比。输出到 `out/t1-10f-cost-profile.txt`。
     */
    @Test
    fun profileMultiSignalCost() {
        assumeTrue(
            "需要 build/replay-work/calibration-7sig.txt 与 set-20260912-03/manifest.txt",
            multiSignalFile.isFile && File(batchDir, ReplayTool.MANIFEST_NAME).isFile,
        )
        val data = CalibrationCodec.decode(multiSignalFile.readText(Charsets.UTF_8))
        val set = ReplaySetCodec.decode(File(batchDir, ReplayTool.MANIFEST_NAME).readText(Charsets.UTF_8))
        val samples = set.samples.filter { it.category.token != "neg-plain" }
        assumeTrue("有效样本不足", samples.size >= 10)
        val frames: List<GrayImage> = samples.map {
            ReplayTool.decodeGrayPng(File(batchDir, it.file))
        }

        val counts = listOf(1, 2, 4, data.signals.size).distinct()
        val lines = mutableListOf<String>()
        lines += "多信号成本与分段耗时（固定批次有效帧 ${frames.size}；产物信号 ${data.signals.size} 条）"
        lines += "口径：逐档取产物前 N 条信号，每帧每档跑 3 轮取中位耗时；分段 = 前缀和构建 / 粗搜 / 精搜（3 轮累计，取占比）"
        lines += ""
        lines += "信号数 | P50(ms) | P95(ms) | 平均(ms) | 单信号均值(ms) | 前缀和% | 粗搜% | 精搜%"

        counts.forEach { n ->
            val signals = data.signals.take(n)
            val times = ArrayList<Long>(frames.size)
            val stats = Stats()
            frames.forEach { gray ->
                val round = LongArray(3)
                for (r in 0..2) {
                    val started = System.nanoTime()
                    signals.forEach { spec ->
                        spec.templates.forEach { template ->
                            Matcher(2, 2, 64, false).findPeaks(gray, template, spec.window, data.params, stats)
                        }
                    }
                    round[r] = System.nanoTime() - started
                }
                round.sort()
                times += round[1] / 1_000_000
            }
            val sorted = times.sorted()
            val p50 = sorted[sorted.size / 2].toDouble()
            val p95 = sorted[minOf(sorted.size - 1, ceil(sorted.size * 0.95).toInt() - 1)].toDouble()
            val avg = times.average()
            val total = (stats.prefixNs + stats.coarseNs + stats.refineNs).toDouble()
            val prefixPct = if (total > 0.0) stats.prefixNs * 100.0 / total else 0.0
            val coarsePct = if (total > 0.0) stats.coarseNs * 100.0 / total else 0.0
            val refinePct = if (total > 0.0) stats.refineNs * 100.0 / total else 0.0
            lines += "$n | ${fmt(p50)} | ${fmt(p95)} | ${fmt(avg)} | ${fmt(avg / n)} | " +
                "${String.format(Locale.ROOT, "%.1f", prefixPct)} | " +
                "${String.format(Locale.ROOT, "%.1f", coarsePct)} | " +
                String.format(Locale.ROOT, "%.1f", refinePct)
        }

        outDir.mkdirs()
        File(outDir, "t1-10f-cost-profile.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("应输出各档结果", counts.isNotEmpty())
    }

    /**
     * 用**设备采集帧 + 设备产物**直接测「整帧识别」耗时（T1-10f，替代真机长跑的桌面等价测量）。
     *
     * 走的是生产路径 `SignalDetector.detect`（全部信号 × 全部模板 + 判定），输入为设备帧池副本
     * `raw-67/`（灰度化到 `set-20260912-06/`）与设备 7 信号产物 `calibration-7sig.txt`。
     * 真机值按 T1-10d 标定的「设备 / 桌面 ≈ ×27」换算（估算，非实测）。
     */
    @Test
    fun measureOnDeviceFrames() {
        assumeTrue(
            "需要 build/replay-work/calibration-7sig.txt 与 raw-67/（设备帧池副本）",
            multiSignalFile.isFile && File(workDir, "raw-67").isDirectory,
        )
        val rawDir = File(workDir, "raw-67")
        val grayDir = File(workDir, "set-20260912-06")
        val existing = grayDir.listFiles()?.count { it.name.endsWith(".png") } ?: 0
        if (existing == 0) {
            val exported = ReplayTool.exportGrayPngs(rawDir, grayDir)
            println("灰度化导出 $exported 帧 → ${grayDir.name}")
        }
        val files = (grayDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedBy { it.name }
        assumeTrue("灰度帧不足（≥10）", files.size >= 10)

        val data = CalibrationCodec.decode(multiSignalFile.readText(Charsets.UTF_8))
        val detector = SignalDetector(
            data.signals.map { SignalSpec(it.name, it.window, it.templates) },
            data.params,
        )

        val times = ArrayList<Long>(files.size)
        val hits = LinkedHashMap<String, Int>()
        data.signals.forEach { hits[it.name] = 0 }
        val verdicts = LinkedHashMap<Verdict, Int>()
        files.forEach { file ->
            val gray = ReplayTool.decodeGrayPng(file)
            val round = LongArray(3)
            var records: List<DetectionRecord> = emptyList()
            for (r in 0..2) {
                val started = System.nanoTime()
                records = detector.detect(gray)
                round[r] = System.nanoTime() - started
            }
            round.sort()
            times += round[1] / 1_000_000
            records.forEach { record ->
                if (record.matched) hits[record.signalName] = (hits[record.signalName] ?: 0) + 1
                verdicts[record.verdict] = (verdicts[record.verdict] ?: 0) + 1
            }
        }

        val sorted = times.sorted()
        val p50 = sorted[sorted.size / 2].toDouble()
        val p95 = sorted[minOf(sorted.size - 1, ceil(sorted.size * 0.95).toInt() - 1)].toDouble()
        val avg = times.average()
        val max = sorted.last().toDouble()

        val lines = mutableListOf<String>()
        lines += "设备帧 + 设备产物 的整帧识别耗时（帧 ${files.size}，信号 ${data.signals.size} 条，模板 ${data.signals.sumOf { it.templates.size }} 个）"
        lines += "路径：生产 SignalDetector.detect（全部信号 × 全部模板 + 判定）；每帧 3 轮取中位"
        lines += "真机换算：×$DEVICE_DESKTOP_RATIO（T1-10d 标定的设备 / 桌面识别倍差，估算非实测）"
        lines += ""
        lines += "指标 | 桌面(ms) | 推算真机(ms)"
        lines += "P50 | ${fmt(p50)} | ${fmt(p50 * DEVICE_DESKTOP_RATIO)}"
        lines += "P95 | ${fmt(p95)} | ${fmt(p95 * DEVICE_DESKTOP_RATIO)}"
        lines += "平均 | ${fmt(avg)} | ${fmt(avg * DEVICE_DESKTOP_RATIO)}"
        lines += "最大 | ${fmt(max)} | ${fmt(max * DEVICE_DESKTOP_RATIO)}"
        lines += ""
        lines += "各信号命中帧数（matched / ${files.size}）："
        hits.forEach { (name, count) -> lines += "$name | $count" }
        lines += ""
        lines += "判定分布：" + verdicts.entries.joinToString(" / ") { "${it.key}=${it.value}" }

        outDir.mkdirs()
        File(outDir, "t1-10f-device-frames-67.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("应完成全部帧测量", times.size == files.size)
    }

    /**
     * 子集模式收益实测（T1-10g）：设备帧 + 设备 7 信号产物，
     * 对比「全扫」与「按状态启用子集」两种模式的耗时与**状态序列**。
     */
    @Test
    fun measureSubsetSavings() {
        assumeTrue(
            "需要 build/replay-work/calibration-7sig.txt 与灰度帧 set-20260912-06/",
            multiSignalFile.isFile && File(workDir, "set-20260912-06").isDirectory,
        )
        val grayDir = File(workDir, "set-20260912-06")
        val files = (grayDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedBy { it.name }
        assumeTrue("灰度帧不足（≥10）", files.size >= 10)

        val data = CalibrationCodec.decode(multiSignalFile.readText(Charsets.UTF_8))
        val rules = data.stateRules.map { SignalStateMapping.Rule(it.state, it.signalNames.toSet()) }
        val specs = data.signals.map { SignalSpec(it.name, it.window, it.templates) }

        fun loop(expected: ExpectedSignals): RecognitionLoop = RecognitionLoop(
            signals = specs,
            params = data.params,
            mapping = SignalStateMapping(rules),
            expectedSignals = expected,
        )

        /** 跑整套：第 1 轮预热，后 2 轮记录每帧耗时与状态（状态序列在两轮间应一致）。 */
        fun run(expected: ExpectedSignals): Triple<List<Long>, List<UiState>, List<Int>> {
            var times: List<Long> = emptyList()
            var states: List<UiState> = emptyList()
            var searched: List<Int> = emptyList()
            repeat(3) { pass ->
                val loop = loop(expected)
                val t = ArrayList<Long>(files.size)
                val s = ArrayList<UiState>(files.size)
                val n = ArrayList<Int>(files.size)
                files.forEach { file ->
                    val gray = ReplayTool.decodeGrayPng(file)
                    val started = System.nanoTime()
                    val result = loop.process(gray, isForeground = true)
                    t += (System.nanoTime() - started) / 1_000_000
                    s += result.state
                    n += result.searched.size
                }
                if (pass == 1) {
                    times = t
                    states = s
                    searched = n
                } else if (pass == 2) {
                    times = times.zip(t) { a, b -> (a + b) / 2 }
                }
            }
            return Triple(times, states, searched)
        }

        // T2-1：生产路径已改为「期望集合驱动」（不外扩、不兜底）。本探针为**对比搜索成本**保留三种试验口径：
        // ① 全集（ExpectedSignals.ALL）——等价改动前的「全扫」；
        // ② 试验性复刻旧「按状态子集」（仅供成本对比，生产已无此路径）；
        // ③ 子集 + 附加集（产物暂无 tutorial_* 信号，只能附加已标定的 popup_close）
        val attachedAll = UiState.entries.filter { it.isCandidate }.associateWith { setOf("popup_close") }
        val (fullTimes, fullStates, _) = run(ExpectedSignals.ALL)
        val (subsetTimes, subsetStates, subsetSearched) = run(StateSubsetExpected(rules))
        val (attachedTimes, attachedStates, attachedSearched) = run(StateSubsetExpected(rules, attachedAll))

        fun stats(times: List<Long>): Triple<Double, Double, Double> {
            val sorted = times.sorted()
            val p50 = sorted[sorted.size / 2].toDouble()
            val p95 = sorted[minOf(sorted.size - 1, ceil(sorted.size * 0.95).toInt() - 1)].toDouble()
            return Triple(p50, p95, times.average())
        }
        val full = stats(fullTimes)
        val sub = stats(subsetTimes)
        val diffFrames = fullStates.zip(subsetStates).count { (a, b) -> a != b }

        val lines = mutableListOf<String>()
        lines += "子集模式收益实测（设备帧 ${files.size}，设备产物 ${data.signals.size} 信号）"
        lines += "路径：RecognitionLoop.process（含方向归一 + 判定 + 滞回状态机）；每帧计时取后 2 轮均值（第 1 轮预热）"
        lines += "真机换算：×$DEVICE_DESKTOP_RATIO（T1-10d 标定的设备 / 桌面倍差，估算非实测）"
        lines += ""
        lines += "模式 | P50(ms) | P95(ms) | 平均(ms) | 推算真机P95(ms) | 提速比"
        lines += "全扫 7 信号 | ${fmt(full.first)} | ${fmt(full.second)} | ${fmt(full.third)} | " +
            "${fmt(full.second * DEVICE_DESKTOP_RATIO)} | x1.00"
        lines += "按状态子集 | ${fmt(sub.first)} | ${fmt(sub.second)} | ${fmt(sub.third)} | " +
            "${fmt(sub.second * DEVICE_DESKTOP_RATIO)} | x${String.format(Locale.ROOT, "%.2f", full.third / sub.third)}"
        val att = stats(attachedTimes)
        val attDiff = fullStates.zip(attachedStates).count { (a, b) -> a != b }
        lines += "子集+附加(每状态+popup_close) | ${fmt(att.first)} | ${fmt(att.second)} | ${fmt(att.third)} | " +
            "${fmt(att.second * DEVICE_DESKTOP_RATIO)} | x${String.format(Locale.ROOT, "%.2f", full.third / att.third)}"
        lines += ""
        lines += "状态序列差异帧数（子集 vs 全扫）：$diffFrames / ${files.size}"
        lines += "状态序列差异帧数（子集+附加 vs 全扫）：$attDiff / ${files.size}"
        lines += "附加集每帧搜索信号数分布：" + attachedSearched.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }.joinToString(" / ") { "${it.key}个=${it.value}帧" }
        lines += "附加集稳态（搜 2 个信号）平均耗时：" + fmt(
            attachedSearched.indices.filter { attachedSearched[it] == 2 }.map { attachedTimes[it] }.average(),
        ) + "ms（真机推算 " + fmt(
            attachedSearched.indices.filter { attachedSearched[it] == 2 }
                .map { attachedTimes[it] }.average() * DEVICE_DESKTOP_RATIO,
        ) + "ms）"
        lines += "全扫状态分布：" + fullStates.groupingBy { it }.eachCount().entries.joinToString(" / ") { "${it.key}=${it.value}" }
        lines += "子集状态分布：" + subsetStates.groupingBy { it }.eachCount().entries.joinToString(" / ") { "${it.key}=${it.value}" }
        lines += ""
        lines += "说明：采集图是**逐帧换场景**的序列，未知状态（无信号命中）必然搜全集，因此整体提速偏低；"
        lines += "T1-13 取消补扫、T2-1 改为期望集合驱动后，生产路径不再有「全扫兜底」——"
        lines += "本探针的「全扫 / 子集」两列只是**成本对比口径**（子集列由探针自带的模拟源复刻旧口径），"
        lines += "故「状态序列差异」变大属预期行为，不是回归。"
        lines += "真实运行时会在同一界面连续停留多轮，应以「稳态帧」为准："
        lines += ""
        lines += "子集模式「每帧实际搜索信号数」分布（7 = 全扫）：" +
            subsetSearched.groupingBy { it }.eachCount().entries.sortedBy { it.key }.joinToString(" / ") { "${it.key}个=${it.value}帧" }
        lines += "全扫帧占比：" + String.format(
            Locale.ROOT, "%.0f%%", subsetSearched.count { it >= data.signals.size } * 100.0 / subsetSearched.size,
        )
        lines += "按「本帧实际搜索信号数」分组的平均耗时：" + subsetSearched.indices
            .groupBy { subsetSearched[it] }.entries.sortedBy { it.key }
            .joinToString(" / ") { (n, idx) -> "搜${n}个(${idx.size}帧)=" + fmt(idx.map { subsetTimes[it] }.average()) + "ms" }
        lines += ""
        lines += "稳态帧（本帧状态 = 上一帧状态）分组 | 帧数 | 全扫均值(ms) | 子集均值(ms) | 提速比"
        val steady = fullStates.indices.filter { it > 0 && fullStates[it] == fullStates[it - 1] }
        steady.groupBy { fullStates[it] }.forEach { (state, indices) ->
            val f = indices.map { fullTimes[it] }.average()
            val s = indices.map { subsetTimes[it] }.average()
            lines += "$state | ${indices.size} | ${fmt(f)} | ${fmt(s)} | " +
                "x${String.format(Locale.ROOT, "%.2f", if (s > 0.0) f / s else 0.0)}"
        }

        outDir.mkdirs()
        File(outDir, "t1-10g-subset-savings.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("应完成测量", fullTimes.size == files.size && subsetTimes.size == files.size)
    }

    /**
     * 逐信号搜索耗时（T1-13b）：回答「每一步（每个状态对应一条信号）真正要花多久」，并定位哪条信号最贵。
     *
     * 口径（按要求制定）：
     * - **不含等待时间**：离线纯计算，无节流休息（节流是识别轮之间的安排，与单信号搜索无关）；
     * - **不含全扫兜底**：每个信号**单独**计时，绝不与其他信号混在同一次计时里
     *   （末尾另附一条全扫参照行，仅作对比，不计入逐信号表）；
     * - 不含灰度转换（输入已是灰度帧）与方向归一（帧几何 = 标定几何，走零拷贝快路径），与真机竖画布会话一致。
     *
     * 输入 `build/replay-work/calibration-7sig.txt` + `set-20260912-03/`（104 帧，覆盖各界面）；
     * 输出 `out/t1-13b-per-signal-cost.txt`。
     */
    @Test
    fun profilePerSignalCost() {
        assumeTrue(
            "需要 build/replay-work/calibration-7sig.txt 与 set-20260912-03/manifest.txt",
            multiSignalFile.isFile && File(batchDir, ReplayTool.MANIFEST_NAME).isFile,
        )
        val data = CalibrationCodec.decode(multiSignalFile.readText(Charsets.UTF_8))
        val set = ReplaySetCodec.decode(File(batchDir, ReplayTool.MANIFEST_NAME).readText(Charsets.UTF_8))
        val samples = set.samples.filter { it.category.token != "neg-plain" }
        assumeTrue("有效样本不足（≥10）", samples.size >= 10)
        val frames: List<GrayImage> = samples.map { ReplayTool.decodeGrayPng(File(batchDir, it.file)) }

        /** 单信号的逐帧耗时与分段累计（分段为该信号自身，绝不含其他信号）。 */
        data class PerSignal(
            val name: String,
            val windowW: Int,
            val windowH: Int,
            val templateW: Int,
            val templateH: Int,
            val templateCount: Int,
            val times: List<Long>,
            val prefixNs: Long,
            val coarseNs: Long,
            val refineNs: Long,
        )

        val matcher = Matcher(2, 2, 64, false)
        // 逐信号单独计时：每帧 ROUNDS 轮取中位（去 JIT / 调度噪声）
        val perSignal = data.signals.map { spec ->
            val template = spec.templates.first()
            val bounds = spec.window.pixelBounds(data.frameWidth, data.frameHeight)
            val stats = Stats()
            val times = ArrayList<Long>(frames.size)
            frames.forEach { gray ->
                val round = LongArray(ROUNDS)
                for (r in 0 until ROUNDS) {
                    val started = System.nanoTime()
                    spec.templates.forEach { matcher.findPeaks(gray, it, spec.window, data.params, stats) }
                    round[r] = System.nanoTime() - started
                }
                round.sort()
                times += round[ROUNDS / 2] / 1_000 // 微秒：亚毫秒信号（farm / friend_list）需要可分辨精度
            }
            PerSignal(
                name = spec.name,
                windowW = bounds.x1 - bounds.x0,
                windowH = bounds.y1 - bounds.y0,
                templateW = template.width,
                templateH = template.height,
                templateCount = spec.templates.size,
                times = times,
                prefixNs = stats.prefixNs,
                coarseNs = stats.coarseNs,
                refineNs = stats.refineNs,
            )
        }

        // 全扫参照（仅对比用，不计入逐信号表）
        val fullStats = Stats()
        val fullTimes = ArrayList<Long>(frames.size)
        frames.forEach { gray ->
            val round = LongArray(ROUNDS)
            for (r in 0 until ROUNDS) {
                val started = System.nanoTime()
                data.signals.forEach { spec ->
                    spec.templates.forEach { matcher.findPeaks(gray, it, spec.window, data.params, fullStats) }
                }
                round[r] = System.nanoTime() - started
            }
            round.sort()
            fullTimes += round[ROUNDS / 2] / 1_000
        }

        fun LongArray.p50(): Double = sorted()[size / 2].toDouble()
        fun LongArray.p95(): Double = sorted()[minOf(size - 1, ceil(size * 0.95).toInt() - 1)].toDouble()
        fun LongArray.maxUs(): Double = sorted().last().toDouble()

        /** 微秒 → 毫秒文本（3 位小数：farm / friend_list 等亚毫秒信号需要可分辨精度）。 */
        fun us(v: Double): String = String.format(Locale.ROOT, "%.3f", v / 1000.0)

        val framePixels = data.frameWidth.toDouble() * data.frameHeight
        val lines = mutableListOf<String>()
        lines += "逐信号搜索耗时（T1-13b）：帧 ${frames.size}（排除 neg-plain），信号 ${data.signals.size} 条，" +
            "标定帧 ${data.frameWidth}x${data.frameHeight}"
        lines += "口径：逐信号**单独**计时（一次只搜这一个信号 → 不含全扫兜底）；离线纯计算，" +
            "**不含等待时间**、灰度转换与方向归一；每帧 $ROUNDS 轮取中位；输入为设备帧副本"
        lines += "真机换算：×$DEVICE_DESKTOP_RATIO（T1-10d 设备 / 桌面倍差）；单位 ms"
        lines += ""
        lines += "信号 | 窗口px(WxH) | 窗口占帧% | 模板px | 模板数 | 网格点x采样点(万) | P50(ms) | P95(ms) | " +
            "平均(ms) | 最大(ms) | 真机推算平均(ms) | 前缀和% / 粗搜% / 精搜%"

        perSignal.sortedByDescending { it.times.average() }.forEach { s ->
            val total = (s.prefixNs + s.coarseNs + s.refineNs).toDouble()
            val prefixPct = if (total > 0.0) s.prefixNs * 100.0 / total else 0.0
            val coarsePct = if (total > 0.0) s.coarseNs * 100.0 / total else 0.0
            val refinePct = if (total > 0.0) s.refineNs * 100.0 / total else 0.0
            val avg = s.times.average()
            // 成本模型：粗搜网格点数 x 模板采样点数（验证耗时与「窗口 - 模板」双线性关系）
            val grid = (((s.windowW - s.templateW) / 2 + 1)).coerceAtLeast(0) *
                (((s.windowH - s.templateH) / 2 + 1)).coerceAtLeast(0)
            val taps = (s.templateW / 2) * (s.templateH / 2)
            lines += "${s.name} | ${s.windowW}x${s.windowH} | " +
                String.format(Locale.ROOT, "%.2f", (s.windowW.toDouble() * s.windowH) * 100.0 / framePixels) + " | " +
                "${s.templateW}x${s.templateH} | ${s.templateCount} | " +
                String.format(Locale.ROOT, "%.1f", grid.toDouble() * taps / 10_000.0) + " | " +
                "${us(s.times.toLongArray().p50())} | ${us(s.times.toLongArray().p95())} | ${us(avg)} | " +
                "${us(s.times.toLongArray().maxUs())} | ${us(avg * DEVICE_DESKTOP_RATIO)} | " +
                String.format(Locale.ROOT, "%.1f%% / %.1f%% / %.1f%%", prefixPct, coarsePct, refinePct)
        }

        val subAvg = perSignal.map { it.times.average() }.average()
        val fullAvg = fullTimes.average()
        lines += ""
        lines += "【对比参照 · 不计入上表】"
        lines += "7 信号全扫 | — | — | — | — | — | ${us(fullTimes.toLongArray().p50())} | " +
            "${us(fullTimes.toLongArray().p95())} | ${us(fullAvg)} | ${us(fullTimes.toLongArray().maxUs())} | " +
            "${us(fullAvg * DEVICE_DESKTOP_RATIO)} | —"
        lines += "逐信号平均之和 = ${us(perSignal.sumOf { it.times.average() })}ms（真机推算 " +
            "${us(perSignal.sumOf { it.times.average() } * DEVICE_DESKTOP_RATIO)}ms）；" +
            "单信号均值 = ${us(subAvg)}ms（真机推算 ${us(subAvg * DEVICE_DESKTOP_RATIO)}ms）"
        lines += "最贵 / 最便宜 = ${us(perSignal.maxOf { it.times.average() })}ms / " +
            "${us(perSignal.minOf { it.times.average() })}ms，倍差 x" +
            String.format(Locale.ROOT, "%.2f", perSignal.maxOf { it.times.average() } / perSignal.minOf { it.times.average() })

        outDir.mkdirs()
        File(outDir, "t1-13b-per-signal-cost.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("应完成全部信号测量", perSignal.size == data.signals.size && fullTimes.size == frames.size)
    }

    private fun samePeaks(a: List<MatchPeak>, b: List<MatchPeak>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (l, r) -> l.x == r.x && l.y == r.y && abs(l.score - r.score) <= 1e-12 }
    }

    // ------------------------------------------------------------------
    // 参数化复刻匹配器（与生产同口径：粗搜采样 + 网格 + 精搜全精度，可选上界剪枝）
    // ------------------------------------------------------------------

    /** 复刻匹配器：采样步长 [sampleStep]（交错抽取）、网格步长 [gridStep]、锚点上限 [topK]、[pruning] 上界剪枝。 */
    private class Matcher(
        private val sampleStep: Int,
        private val gridStep: Int,
        private val topK: Int,
        private val pruning: Boolean,
    ) {

        fun findPeaks(
            image: GrayImage,
            template: Template,
            window: SearchWindow,
            params: MatchParams,
            stats: Stats? = null,
        ): List<MatchPeak> {
            if (template.centeredSumSq <= VARIANCE_EPS) return emptyList()
            val bounds = window.pixelBounds(image.width, image.height)
            val maxX = bounds.x1 - template.width
            val maxY = bounds.y1 - template.height
            if (maxX < bounds.x0 || maxY < bounds.y0) return emptyList()

            val t0 = System.nanoTime()
            val prefix = WindowPrefixSums.build(image, bounds)
            val t1 = System.nanoTime()
            val sample = buildSample(template, image.width, sampleStep)
            val area = template.width * template.height
            // 剪枝上界所需：模板剩余行的 Σt'² 与 Σt'（后缀和）
            val rowSqSuffix = rowSquareSuffix(template)
            val rowSumSuffix = rowSumSuffix(template)

            val xs = grid(bounds.x0, maxX)
            val ys = grid(bounds.y0, maxY)
            val coarse = ArrayList<MatchPeak>(xs.size * ys.size)
            for (y in ys) {
                for (x in xs) {
                    val score = if (sample.sumSq > VARIANCE_EPS) {
                        coarseScore(image, sample, prefix, bounds, area, template, x, y)
                    } else {
                        // 采样点无纹理的退化模板：粗搜直接全精度保底
                        exactScore(
                            image, template, prefix, bounds, x, y,
                            rowSqSuffix, rowSumSuffix, bestSoFar = -1.0,
                        )
                    }
                    if (score > 0.0) coarse.add(MatchPeak(score, x, y))
                }
            }
            if (coarse.isEmpty()) {
                stats?.add(t1 - t0, System.nanoTime() - t1, 0)
                return emptyList()
            }
            val anchors = representatives(coarse, maxOf(params.peakMinDistance, gridStep), topK)

            val t2 = System.nanoTime()
            val radius = ceil(gridStep / 2.0).toInt()
            val candidates = ArrayList<MatchPeak>()
            var best = -1.0
            for (anchor in anchors) {
                val x0 = (anchor.x - radius).coerceAtLeast(bounds.x0)
                val x1 = (anchor.x + radius).coerceAtMost(maxX)
                val y0 = (anchor.y - radius).coerceAtLeast(bounds.y0)
                val y1 = (anchor.y + radius).coerceAtMost(maxY)
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val score = exactScore(
                            image, template, prefix, bounds, x, y, rowSqSuffix, rowSumSuffix, best,
                        )
                        if (score > best) best = score
                        if (score > 0.0) candidates.add(MatchPeak(score, x, y))
                    }
                }
            }
            val t3 = System.nanoTime()
            stats?.add(t1 - t0, t2 - t1, t3 - t2)
            return TemplateMatcher.suppressPeaks(candidates, params.peakMinDistance)
        }

        /** 网格点：步长 ≤ [gridStep]、末点必取，保证任一位置到最近网格点距离 ≤ ceil(step/2)。 */
        private fun grid(from: Int, to: Int): IntArray {
            val points = ArrayList<Int>((to - from) / gridStep + 2)
            var v = from
            while (v <= to) {
                points.add(v)
                v += gridStep
            }
            if (points.last() != to) points.add(to)
            return points.toIntArray()
        }

        private fun representatives(coarse: List<MatchPeak>, minSpacing: Int, limit: Int): List<MatchPeak> {
            val sorted = coarse.sortedWith(
                compareByDescending<MatchPeak> { it.score }.thenBy { it.y }.thenBy { it.x },
            )
            val picked = ArrayList<MatchPeak>(limit)
            for (candidate in sorted) {
                if (picked.size >= limit) break
                val near = picked.any {
                    abs(it.x - candidate.x) < minSpacing && abs(it.y - candidate.y) < minSpacing
                }
                if (!near) picked.add(candidate)
            }
            return picked
        }

        /** 采样模板（交错抽取；去均值按采样子集自身，避免粗搜分子的系统性偏移）。 */
        private fun buildSample(template: Template, imageWidth: Int, step: Int): Sample {
            var count = 0
            for (ty in 0 until template.height) {
                var tx = ty % step
                while (tx < template.width) {
                    count++
                    tx += step
                }
            }
            val offsets = IntArray(count)
            val values = DoubleArray(count)
            var k = 0
            for (ty in 0 until template.height) {
                var tx = ty % step
                while (tx < template.width) {
                    offsets[k] = ty * imageWidth + tx
                    values[k] = (template.pixels[ty * template.width + tx].toInt() and 0xFF).toDouble()
                    k++
                    tx += step
                }
            }
            var mean = 0.0
            for (v in values) mean += v
            mean /= count
            var sq = 0.0
            for (i in 0 until count) {
                val c = values[i] - mean
                values[i] = c
                sq += c * c
            }
            return Sample(offsets, values, sq)
        }

        private class Sample(
            val offsets: IntArray,
            val centeredAt: DoubleArray,
            val sumSq: Double,
        )

        private fun coarseScore(
            image: GrayImage,
            sample: Sample,
            prefix: WindowPrefixSums,
            bounds: PixelBounds,
            area: Int,
            template: Template,
            x: Int,
            y: Int,
        ): Double {
            val variance = variance(prefix, bounds, x, y, template.width, template.height, area)
            if (variance / area < VARIANCE_EPS) return 0.0
            var dot = 0.0
            val rowStart = y * image.width + x
            val offsets = sample.offsets
            val centered = sample.centeredAt
            for (i in offsets.indices) {
                dot += centered[i] * (image.pixels[rowStart + offsets[i]].toInt() and 0xFF)
            }
            return dot / sqrt(sample.sumSq * variance)
        }

        /**
         * 全精度分数；[pruning] 为真时按行做上界剪枝（[bestSoFar] 为当前已知最高分，<0 表示尚无）。
         * 剪枝返回负分数表示「已剪掉」——不入候选（生产语义下等价于该位置不可能是峰）。
         *
         * 上界推导（关键点）：Σt' = 0 只对**整个模板**成立，剩余行子集 Σt' 一般不为 0，因此
         * 剩余行点积应写作 Σ t'·p = p̄_rem·Σ_rem t' + Σ t'·(p − p̄_rem)，后一项用 Cauchy–Schwarz
         * 上界 √(Σ_rem t'² · Σ_rem(p − p̄_rem)²)；漏掉均值项会**误剪**真实峰（首版实测结论不一致 9 帧）。
         */
        private fun exactScore(
            image: GrayImage,
            template: Template,
            prefix: WindowPrefixSums,
            bounds: PixelBounds,
            x: Int,
            y: Int,
            rowSqSuffix: DoubleArray,
            rowSumSuffix: DoubleArray,
            bestSoFar: Double,
        ): Double {
            val area = template.width * template.height
            val variance = variance(prefix, bounds, x, y, template.width, template.height, area)
            if (variance / area < VARIANCE_EPS) return 0.0
            val denominator = sqrt(template.centeredSumSq * variance)
            if (denominator <= 0.0) return 0.0

            val centered = template.centered
            var dot = 0.0
            var rowStart = y * image.width + x
            var ti = 0
            for (ty in 0 until template.height) {
                var i = rowStart
                for (tx in 0 until template.width) {
                    dot += centered[ti] * (image.pixels[i].toInt() and 0xFF)
                    i++
                    ti++
                }
                rowStart += image.width
                if (pruning && bestSoFar > 0.0 && (ty + 1) % PRUNE_CHECK_ROWS == 0) {
                    val remaining = template.height - ty - 1
                    if (remaining > 0) {
                        // 剩余行矩形（x, y+ty+1, w, remaining）：均值（前缀和 O(1)）+ 方差 → Cauchy–Schwarz 上界
                        val remCount = template.width * remaining
                        val remSum = rectSum(prefix, bounds, x, y + ty + 1, template.width, remaining)
                        val remMean = remSum.toDouble() / remCount
                        val remVariance = variance(
                            prefix, bounds, x, y + ty + 1, template.width, remaining, remCount,
                        )
                        if (remVariance >= 0.0) {
                            val upper = (
                                dot + remMean * rowSumSuffix[ty + 1] +
                                    sqrt(rowSqSuffix[ty + 1] * remVariance)
                                ) / denominator
                            if (upper < bestSoFar) return -1.0 // 不可能超过当前最优 → 剪掉
                        }
                    }
                }
            }
            return dot / denominator
        }

        private fun variance(
            prefix: WindowPrefixSums,
            bounds: PixelBounds,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            area: Int,
        ): Double {
            val sumP = rectSum(prefix, bounds, x, y, w, h)
            val sumP2 = rectSumSq(prefix, bounds, x, y, w, h)
            return sumP2.toDouble() - sumP.toDouble() * sumP / area
        }

        private fun rectSum(
            prefix: WindowPrefixSums,
            bounds: PixelBounds,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ): Long = prefix.rectSum(x - bounds.x0, y - bounds.y0, w, h)

        private fun rectSumSq(
            prefix: WindowPrefixSums,
            bounds: PixelBounds,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ): Long = prefix.rectSumSq(x - bounds.x0, y - bounds.y0, w, h)

        /** 模板第 r 行及以后的 Σt'²（后缀和），供剪枝上界使用。 */
        private fun rowSquareSuffix(template: Template): DoubleArray {
            val rows = DoubleArray(template.height + 1)
            for (ty in template.height - 1 downTo 0) {
                var sq = 0.0
                val base = ty * template.width
                for (tx in 0 until template.width) {
                    val v = template.centered[base + tx]
                    sq += v * v
                }
                rows[ty] = rows[ty + 1] + sq
            }
            return rows
        }

        /** 模板第 r 行及以后的 Σt'（后缀和）：剩余行点积的均值修正项。 */
        private fun rowSumSuffix(template: Template): DoubleArray {
            val rows = DoubleArray(template.height + 1)
            for (ty in template.height - 1 downTo 0) {
                var sum = 0.0
                val base = ty * template.width
                for (tx in 0 until template.width) sum += template.centered[base + tx]
                rows[ty] = rows[ty + 1] + sum
            }
            return rows
        }
    }

    private companion object {
        const val ROUNDS = 5
        const val PRUNE_CHECK_ROWS = 4
        const val VARIANCE_EPS = 1e-6
        /** 设备 / 桌面识别倍差（T1-10d 实测 ≈27–28×，取 27 用于推算）。 */
        const val DEVICE_DESKTOP_RATIO = 27.0

        fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }

    /**
     * 试验用「按状态选子集」模拟源——**仅探针用于成本对比**，复刻 T1-10g 的旧口径
     * （状态未知 → 全集；否则当前状态的信号 ∪ 附加集；无信号的状态回落全集）。
     *
     * 生产路径已无此逻辑：T2-1 起搜索范围一律由外部注入的期望集合决定。
     * 状态近似用「上一轮命中」而非滞回结论（探针只关心每帧搜索几个信号的成本）。
     */
    private class StateSubsetExpected(
        private val rules: List<SignalStateMapping.Rule>,
        private val attached: Map<UiState, Set<String>> = emptyMap(),
    ) : ExpectedSignals {

        private var current: UiState = UiState.UNKNOWN

        override fun expected(known: Set<String>): Set<String> {
            if (current == UiState.UNKNOWN) return known
            val own = rules.firstOrNull { it.state == current }?.signalNames.orEmpty()
            val subset = own + attached[current].orEmpty()
            return if (subset.isEmpty()) known else subset
        }

        override fun onRound(hits: Set<UiState>) {
            hits.minByOrNull { it.ordinal }?.let { current = it }
        }
    }
}
