package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * **N6-1：单条标志那 60~80ms 到底花在哪**（`docs/plans/m6-nfr-hardening.md` §1.2 / N6-1）。
 *
 * 成本模型 [TemplateMatcher.estimateCoarseOps] 只算**粗搜乘加**（健康标志 3~8M ops ⇒ 21~56ms），
 * 而真机逐信号 P95 是 **60~80ms** ⇒ **2~3 倍耗时不在模型里**。本探针把墙钟拆成：
 * `snapshot`（循环外的窗口像素拷贝）/ `prefix`（窗口前缀和，**随窗口面积**走）
 * / `coarse`（模型覆盖的那段）/ `pick` / `refine` / `suppress`。
 *
 * 输入：帧 = `docs/recognition/samples` 目录下的 .png（真实归档帧）；产物 = `_tmp_check/calibration.txt`
 * （设备当前产物）优先，否则 `_tmp_check/artifact*.txt` 最新一份。报告写 `out/n6-1-attribution.txt`。
 * 任一缺失即跳过（同其它探针约定）。
 */
class N6AttributionProbeTest {

    private class Recorder : TemplateMatcher.MatchCostProbe {
        val phases = LinkedHashMap<String, Double>()
        override fun phase(name: String, ms: Double, note: String) {
            phases[name] = (phases[name] ?: 0.0) + ms
        }
    }

    private class Row(
        val signal: String,
        val template: String,
        val window: String,
        val ops: Long,
        val snapshotMs: Double,
        val phases: Map<String, Double>,
        val totalMs: Double,
    ) {
        val inMatcher: Double get() = totalMs - snapshotMs
    }

    private fun artifactFile(): File? {
        val live = File("../_tmp_check/calibration.txt")
        if (live.isFile && live.length() > 1024) return live
        return File("../_tmp_check").listFiles { f ->
            f.name.startsWith("artifact") && f.name.endsWith(".txt")
        }?.maxByOrNull { it.lastModified() }
    }

    @Test
    fun attributeWhereThePerSignalMillisecondsGo() {
        // ⚠ 单测的工作目录是**模块目录**（`app/`）⇒ 仓库里的归档帧要从 `../docs/...` 找（同其它探针的约定）
        val samples = File("../docs/recognition/samples")
        val frames = samples.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        val artifact = artifactFile()
        assumeTrue("没有归档帧，跳过", frames.isNotEmpty())
        assumeTrue("没有可用产物（设备产物与归档都缺），跳过", artifact != null)

        val data = CalibrationCodec.decode(artifact!!.readText(Charsets.UTF_8))
        val specs = data.markerSpecs()
        assumeTrue("产物里没有标志记录", specs.isNotEmpty())
        val loop = data.toLoop()
        val out = StringBuilder()
        out.append("N6-1 识别耗时归因（单条标志，串行；探针不改判定值）\n")
        out.append("帧：${samples.path}（${frames.size} 张真实归档帧）\n")
        out.append("产物：${artifact.path}（标志 ${specs.size} 条，frame=${data.frameWidth}x${data.frameHeight}）\n\n")
        out.append("信号 | 模板 | 窗口 | ops(M) | snapshot | prefix | coarse | pick | refine | suppress | 合计\n")

        val rows = ArrayList<Row>()
        for (frame in frames) {
            val gray = ReplayTool.decodeGrayPng(frame)
            for (spec in specs) {
                val tpl = spec.templates.maxBy { it.width.toLong() * it.height.toLong() }
                val b = spec.window.pixelBounds(gray.width, gray.height)
                val runs = (1..3).map {
                    val rec = Recorder()
                    val s0 = System.nanoTime()
                    loop.windowSnapshot(gray, spec)
                    val snap = (System.nanoTime() - s0) / 1e6
                    val m0 = System.nanoTime()
                    TemplateMatcher.findPeaks(gray, tpl, spec.window, data.params, rec)
                    Triple(snap, (System.nanoTime() - m0) / 1e6, rec.phases)
                }
                // 取"匹配段最快"的那一轮（最少受调度 / GC 干扰）
                val best = runs.minBy { it.second }
                val row = Row(
                    signal = spec.name,
                    template = "${tpl.width}x${tpl.height}" + if (spec.templates.size > 1) "×${spec.templates.size}" else "",
                    window = "${b.x1 - b.x0}x${b.y1 - b.y0}",
                    ops = TemplateMatcher.estimateCoarseOps(spec.window, tpl.width, tpl.height, gray.width, gray.height),
                    snapshotMs = best.first,
                    phases = best.third,
                    totalMs = best.first + best.second,
                )
                rows += row
                out.append(
                    String.format(
                        Locale.ROOT, "%s | %s | %s | %.1f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f%n",
                        row.signal, row.template, row.window, row.ops / 1e6, row.snapshotMs,
                        row.phases[TemplateMatcher.PHASE_PREFIX] ?: 0.0,
                        row.phases[TemplateMatcher.PHASE_COARSE] ?: 0.0,
                        row.phases[TemplateMatcher.PHASE_PICK] ?: 0.0,
                        row.phases[TemplateMatcher.PHASE_REFINE] ?: 0.0,
                        row.phases[TemplateMatcher.PHASE_SUPPRESS] ?: 0.0,
                        row.totalMs,
                    ),
                )
            }
        }

        val inMatcherTotal = rows.sumOf { it.inMatcher }
        val snapshotTotal = rows.sumOf { it.snapshotMs }
        out.append("\n分段占比（分母 = 匹配段耗时合计 ${"%.1f".format(inMatcherTotal)}ms；snapshot 单列 ${"%.1f".format(snapshotTotal)}ms）\n")
        listOf(
            TemplateMatcher.PHASE_PREFIX, TemplateMatcher.PHASE_COARSE, TemplateMatcher.PHASE_PICK,
            TemplateMatcher.PHASE_REFINE, TemplateMatcher.PHASE_SUPPRESS,
        ).forEach { phase ->
            val ms = rows.sumOf { (it.phases[phase] ?: 0.0) }
            out.append(String.format(Locale.ROOT, "  %-9s %8.1f ms  %5.1f%%%n", phase, ms, ms / inMatcherTotal * 100))
        }
        out.append(String.format(Locale.ROOT, "  %-9s %8.1f ms%n", "snapshot", snapshotTotal))
        out.append("\n读表要点：ops 模型**只覆盖 coarse**；prefix 与 snapshot 随**窗口面积**走、与模板面积无关。\n")

        val report = File("out").apply { mkdirs() }.let { File(it, "n6-1-attribution.txt") }
        report.writeText(out.toString(), Charsets.UTF_8)
        println(out)
        println("报告：${report.absolutePath}")

        rows.filter { it.inMatcher > 0.5 }.forEach { r ->
            val ratio = r.phases.values.sum() / r.inMatcher
            assertTrue(
                "分段之和应约等于匹配段耗时（${r.signal}：${"%.2f".format(r.phases.values.sum())} vs ${"%.2f".format(r.inMatcher)}）",
                ratio in 0.85..1.15,
            )
        }
        assertTrue("应量到 coarse 段", rows.any { (it.phases[TemplateMatcher.PHASE_COARSE] ?: 0.0) > 0.0 })
        assertTrue("应量到 prefix 段", rows.any { (it.phases[TemplateMatcher.PHASE_PREFIX] ?: 0.0) > 0.0 })
    }
}
