package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.calibration.CalibrationData
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 本地批次运行入口（T1-6e 工程侧，测试源集承载 IO）。
 *
 * 约定工作目录 `<模块>/build/replay-work/`（位于 build/ 下，不入版本库）：
 * - `calibration.txt`——标定产物（真实文本格式，[CalibrationCodec] 解析）；
 * - `set-YYYYMMDD-NN/`——样本集目录（`manifest.txt` + 灰度画面文件，同 [ReplayTool] 约定；
 *   当前批次由 [batchDir] 指向）；
 * - `raw/`（可选）——设备原始帧；存在时由 `exportRawBatchToGray` 灰度化导出到样本集目录。
 *
 * 两个入口：`diagnoseBatch` 输出逐样本判定与分数诊断表（含识别耗时，供批次核对）；
 * `generateWorkReport` 走完整报告链路。工作文件未就绪时整体跳过（日常单测与 CI 不触发）。
 */
class ReplayBatchRunTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val batchDir = File(workDir, "set-20260912-03")
    private val artifactFile = File(workDir, "calibration.txt")
    private val outDir = File(workDir, "out")

    private fun prepared(): Boolean =
        artifactFile.isFile && File(batchDir, ReplayTool.MANIFEST_NAME).isFile

    private fun loadData(): CalibrationData =
        CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))

    private fun buildDetector(data: CalibrationData): SignalDetector = SignalDetector(
        data.signals.map { SignalSpec(it.name, it.window, it.templates) },
        data.params,
    )

    /** 原始帧入库导出：`raw/` → 样本集目录同名灰度重写（入库体积约减半，识别输入逐位等值）。 */
    @Test
    fun exportRawBatchToGray() {
        val rawDir = File(workDir, "raw")
        assumeTrue("raw 目录未就绪：把设备原始帧放入 build/replay-work/raw/ 后重跑", rawDir.isDirectory)
        val count = ReplayTool.exportGrayPngs(rawDir, batchDir)
        println("已灰度化 $count 帧：${rawDir.path} → ${batchDir.path}")
        assertTrue("应至少导出一帧", count > 0)
    }

    /** 逐样本诊断：读帧 → 识别 → 记录「结论 / 最高分 / 竞争分 / 位置 / 耗时」到 out/diagnostics.txt。 */
    @Test
    fun diagnoseBatch() {
        assumeTrue("工作目录未就绪：放入 calibration.txt 与 batch/manifest.txt 后重跑", prepared())
        val data = loadData()
        val set = ReplaySetCodec.decode(
            File(batchDir, ReplayTool.MANIFEST_NAME).readText(Charsets.UTF_8),
        )
        val signalDetector = buildDetector(data)
        val lines = mutableListOf(
            "产物帧尺寸 ${data.frameWidth}x${data.frameHeight}；参数 命中线=" +
                "${data.params.matchThreshold} 差距线=${data.params.ambiguityMargin} " +
                "峰值间距=${data.params.peakMinDistance}",
            "样本 | 类别 | 信号 | 结论 | 最高分@x,y | 竞争分@x,y | 识别耗时(ms)",
        )
        set.samples.forEach { sample ->
            val gray = ReplayTool.decodeGrayPng(File(batchDir, sample.file))
            val startedAt = System.nanoTime()
            val records = signalDetector.detect(gray)
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            records.forEach { record ->
                lines += "${sample.file} | ${sample.category.token} | ${record.signalName} | " +
                    "${record.verdict} | ${peakText(record.best)} | ${peakText(record.competitor)} | $ms"
            }
        }
        outDir.mkdirs()
        File(outDir, "diagnostics.txt").writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach(::println)
        assertTrue("诊断表应至少含表头与一个样本行", lines.size >= 3)
    }

    /** 完整报告链路：清单 + 产物 → 三次运行 → 报告写入 out/。 */
    @Test
    fun generateWorkReport() {
        assumeTrue("工作目录未就绪：放入 calibration.txt 与 batch/manifest.txt 后重跑", prepared())
        val report = ReplayTool.generateReport(
            setDir = batchDir,
            data = loadData(),
            paramsKey = "calib-v1",
            topic = "t16e-batch01",
            date = "2026-09-12",
            reproCommand = ".\\gradlew.bat ':app:testDebugUnitTest' --tests '*ReplayBatchRunTest*' --rerun",
            runs = 3,
        )
        val file = ReplayTool.writeReport(outDir, "2026-09-12", "t16e-batch01", report)
        println(report)
        assertTrue("报告应写入工作目录出目录：${file.path}", file.isFile)
    }

    private fun peakText(peak: MatchPeak?): String =
        peak?.let { String.format(Locale.ROOT, "%.4f@%d,%d", it.score, it.x, it.y) } ?: "-"
}
