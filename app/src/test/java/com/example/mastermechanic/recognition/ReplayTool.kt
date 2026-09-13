package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.capture.RgbaToGray
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor

/**
 * JVM 回放工具（T1-6d，测试源集承载 IO）：读样本集目录 → 跑识别 → 生成指标报告。
 *
 * 样本集目录 = `manifest.txt` 清单 + 画面文件（同目录）；识别信号与参数来自标定产物
 * （[CalibrationData]，调用方提供）。工具只做 IO 与编排：判定 / 统计 / 比对 / 渲染
 * 全部复用识别层纯逻辑（[SignalDetector] / [ReplayMetrics] / [ReplayComparator] /
 * [ReplayReportRenderer]），不内含任何与具体设备绑定的常量（红线 4）。
 */
object ReplayTool {

    /** 清单固定文件名（样本集目录约定，模板同步）。 */
    const val MANIFEST_NAME = "manifest.txt"

    /** 正样本命中判定允许的位置偏差（像素）——工具参数，不参与识别判定与误报口径。 */
    private const val POSITION_TOLERANCE_PX = 4

    /** PNG → 灰度帧（IO 适配：经 RGBA 通道复用 [RgbaToGray]，与采集端同一条亮度路径）。 */
    fun decodeGrayPng(file: File): GrayImage {
        val image = ImageIO.read(file) ?: throw IllegalArgumentException("无法解码 PNG：${file.name}")
        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)
        image.getRGB(0, 0, width, height, argb, 0, width) // 整幅批量取像素（逐像素 getRGB 对整帧过慢）
        val rgba = ByteArray(width * height * 4)
        for (i in argb.indices) {
            val v = argb[i]
            val j = i * 4
            rgba[j] = ((v shr 16) and 0xFF).toByte()
            rgba[j + 1] = ((v shr 8) and 0xFF).toByte()
            rgba[j + 2] = (v and 0xFF).toByte()
            rgba[j + 3] = ((v shr 24) and 0xFF).toByte()
        }
        return RgbaToGray.toGray(rgba, width, height, width * 4)
    }

    /** 灰度帧 → 不透明 RGB PNG（测试批次构造与入库导出用；写读往返无损）。 */
    fun writeGrayPng(gray: GrayImage, file: File) {
        file.parentFile?.mkdirs()
        val image = BufferedImage(gray.width, gray.height, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(gray.width)
        for (y in 0 until gray.height) {
            val base = y * gray.width
            for (x in 0 until gray.width) {
                val v = gray.pixels[base + x].toInt() and 0xFF
                row[x] = (v shl 16) or (v shl 8) or v
            }
            image.setRGB(0, y, gray.width, 1, row, 0, gray.width) // 整行批量写入
        }
        ImageIO.write(image, "png", file)
    }

    /**
     * 批次灰度化入仓导出（T1-6e）：读 [srcDir] 全部 PNG → 重写为同名灰度 PNG 到 [dstDir]。
     * 识别链路只用灰度（[RgbaToGray]），灰度化后重解码与原帧逐位等值（见单测），
     * 入库体积约减半；返回导出帧数。
     */
    fun exportGrayPngs(srcDir: File, dstDir: File): Int {
        val frames = srcDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }.orEmpty()
        dstDir.mkdirs()
        frames.forEach { writeGrayPng(decodeGrayPng(it), File(dstDir, it.name)) }
        return frames.size
    }

    /** 正样本命中：目标信号 MATCHED 且检测位置与期望位置（比例矩形左 / 上角换算）偏差在容忍范围内。 */
    fun positiveHit(sample: ReplaySample, records: List<DetectionRecord>, width: Int, height: Int): Boolean {
        val record = records.firstOrNull { it.signalName == sample.signalName } ?: return false
        if (!record.matched) return false
        val best = record.best ?: return false
        val expected = sample.expected ?: return false
        val expX = floor(expected.left * width).toInt()
        val expY = floor(expected.top * height).toInt()
        return abs(best.x - expX) <= POSITION_TOLERANCE_PX && abs(best.y - expY) <= POSITION_TOLERANCE_PX
    }

    /**
     * 生成指标报告：读清单与画面 → 每样本识别（共 [runs] 次）→ 误报统计 + 重跑比对 → 渲染。
     * [topic] 进标题；[date] / [reproCommand] 由调用方给出（报告确定性，§5-4）。
     */
    fun generateReport(
        setDir: File,
        data: CalibrationData,
        paramsKey: String,
        topic: String,
        date: String,
        reproCommand: String,
        runs: Int = 3,
    ): String {
        val manifest = File(setDir, MANIFEST_NAME)
        require(manifest.isFile) { "样本集缺少清单：${manifest.path}" }
        val set = ReplaySetCodec.decode(manifest.readText(Charsets.UTF_8))
        val detector = SignalDetector(
            data.signals.map { SignalSpec(it.name, it.window, it.templates) },
            data.params,
        )
        // 逐帧流式处理：解码一帧 → 跑 [runs] 次检测 → 立即丢弃灰帧（内存与批次规模解耦，
        // 大批次不因全量常驻超限）；运行间保序，结果与「全量解码 + 逐轮检测」等价。
        val frameSizes = ArrayList<Pair<Int, Int>>(set.samples.size)
        val recordsPerFrame = set.samples.map { sample ->
            val gray = decodeGrayPng(File(setDir, sample.file))
            frameSizes += gray.width to gray.height
            (0 until runs).map { detector.detect(gray) }
        }
        val runsResult = (0 until runs).map { runIndex ->
            ReplayRun(
                sampleSet = setDir.name,
                paramsKey = paramsKey,
                results = set.samples.mapIndexed { index, sample ->
                    ReplaySampleResult(sample.file, recordsPerFrame[index][runIndex])
                },
            )
        }
        val baseline = runsResult.first()

        val observations = mutableListOf<NegativeObservation>()
        var positiveSamples = 0
        var positiveMatched = 0
        set.samples.forEachIndexed { index, sample ->
            val records = baseline.results[index].records
            if (sample.category.isNegative) {
                observations += NegativeObservation(sample, records.filter { it.matched }.map { it.signalName })
            } else {
                positiveSamples += 1
                val (width, height) = frameSizes[index]
                if (positiveHit(sample, records, width, height)) positiveMatched += 1
            }
        }

        return ReplayReportRenderer.render(
            ReplayReportRenderer.Input(
                topic = topic,
                sampleSet = setDir.name,
                device = set.device ?: "未注明",
                scene = set.scene ?: "未注明",
                date = date,
                paramsKey = paramsKey,
                matchThreshold = data.params.matchThreshold,
                ambiguityMargin = data.params.ambiguityMargin,
                peakMinDistance = data.params.peakMinDistance,
                runs = runs,
                metrics = ReplayMetrics.negativeMetrics(observations),
                positiveSamples = positiveSamples,
                positiveMatched = positiveMatched,
                differences = ReplayComparator.compare(runsResult),
                reproCommand = reproCommand,
            ),
        )
    }

    /** 报告写盘：`report-YYYYMMDD-主题.md`（日期去连字符）；返回写入的文件。 */
    fun writeReport(reportDir: File, date: String, topic: String, text: String): File {
        reportDir.mkdirs()
        val file = File(reportDir, "report-${date.replace("-", "")}-$topic.md")
        file.writeText(text, Charsets.UTF_8)
        return file
    }
}
