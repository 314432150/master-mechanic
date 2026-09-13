package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.SyntheticRgba
import com.example.mastermechanic.calibration.TemplateExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM 回放工具端到端单测（T1-6d）：合成固定批次（正样本 + 相似干扰负样本 +
 * 普通负样本）→ 清单落盘 → 读回跑识别 → 生成指标报告 → 写盘核对。
 * 同时验证报告确定性（同数据 + 同参数 → 同文本，§5-4）。
 */
class ReplayToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val params = MatchParams(matchThreshold = 0.85, ambiguityMargin = 0.1, peakMinDistance = 5)

    @Test
    fun syntheticBatchGeneratesReportEndToEnd() {
        val setDir = temp.newFolder("set-20260912-99")
        val width = 240
        val height = 180

        // 合成批次：正样本（目标在位）+ 相似干扰负样本（异纹理块）+ 普通负样本（无目标）
        val patch = SyntheticImages.pattern(16, 12, seed = 302)
        val decoy = SyntheticImages.pattern(16, 12, seed = 777)
        val positive = SyntheticImages.background(width, height, seed = 301)
        SyntheticImages.drawPattern(positive, 50, 60, 16, 12, patch)
        val similar = SyntheticImages.background(width, height, seed = 301)
        SyntheticImages.drawPattern(similar, 150, 100, 16, 12, decoy)
        val plain = SyntheticImages.background(width, height, seed = 301)

        ReplayTool.writeGrayPng(positive, File(setDir, "f-001.png"))
        ReplayTool.writeGrayPng(similar, File(setDir, "f-002.png"))
        ReplayTool.writeGrayPng(plain, File(setDir, "f-003.png"))

        // 标定产物（模拟）：模板从正样本同位置提取
        val rgba = SyntheticRgba.fromGray(positive.pixels, width, height)
        val template = TemplateExtractor.extract(rgba, width, height, width * 4, 50, 60, 66, 72)
        val data = CalibrationData(
            frameWidth = width,
            frameHeight = height,
            params = params,
            signals = listOf(
                CalibrationData.SignalEntry(
                    "launch_start",
                    SearchWindow(0.0, 0.0, 1.0, 1.0),
                    listOf(template),
                ),
            ),
            stateRules = emptyList(),
        )

        // 清单落盘（严格编解码往返）
        val set = ReplaySampleSet(
            device = "测试设备",
            scene = "合成批次",
            samples = listOf(
                ReplaySample(
                    "f-001.png",
                    ReplayCategory.POSITIVE,
                    "launch_start",
                    SearchWindow(50.0 / width, 60.0 / height, 66.0 / width, 72.0 / height),
                    "目标在位",
                ),
                ReplaySample("f-002.png", ReplayCategory.NEGATIVE_SIMILAR, null, null, "异纹理干扰"),
                ReplaySample("f-003.png", ReplayCategory.NEGATIVE_PLAIN, null, null, "无目标"),
            ),
        )
        File(setDir, ReplayTool.MANIFEST_NAME).writeText(ReplaySetCodec.encode(set), Charsets.UTF_8)

        val report = ReplayTool.generateReport(
            setDir = setDir,
            data = data,
            paramsKey = "synthetic_v1",
            topic = "synthetic-io",
            date = "2026-09-12",
            reproCommand = "gradlew :app:test --tests ReplayToolTest",
        )

        // 正样本命中（信号 + 位置）；负样本零误报；可复现一致；样本不足闸门生效
        assertTrue(report.contains("样本集：set-20260912-99"))
        assertTrue(report.contains("- 命中 1 / 1"))
        assertTrue(report.contains("| 半透明遮罩（干扰①） | 0 | — | 未采集 |"))
        assertTrue(report.contains("| 动背景（干扰②） | 0 | — | 未采集 |"))
        assertTrue(report.contains("| 相似多目标（干扰③） | 1 | 0 | 上界 0.950000 |"))
        assertTrue(report.contains("| 普通负样本 | 1 | 0 | 上界 0.950000 |"))
        assertTrue(report.contains("| 合计（仅供样本量核对，不作为总通过率结论） | 2 | 0 | — |"))
        assertTrue(report.contains("样本不足：低于口径下限 60，不得声称「零误报」"))
        assertTrue(report.contains("- 逐字段比对：逐字段一致（无差异）"))
        assertTrue(report.contains("## 复现命令"))

        // 确定性：同数据 + 同参数重跑 → 同文本
        val again = ReplayTool.generateReport(
            setDir = setDir,
            data = data,
            paramsKey = "synthetic_v1",
            topic = "synthetic-io",
            date = "2026-09-12",
            reproCommand = "gradlew :app:test --tests ReplayToolTest",
        )
        assertEquals(report, again)

        // 写盘：文件名 `report-YYYYMMDD-主题.md`，内容一致
        val outDir = temp.newFolder("reports")
        val file = ReplayTool.writeReport(outDir, "2026-09-12", "synthetic-io", report)
        assertEquals("report-20260912-synthetic-io.md", file.name)
        assertEquals(report, file.readText(Charsets.UTF_8))
    }

    @Test
    fun exportGrayPngsKeepsRecognitionInputBitExact() {
        val rawDir = temp.newFolder("raw")
        val grayDir = temp.newFolder("out-gray")
        val frame = SyntheticImages.background(64, 48, seed = 901)
        SyntheticImages.drawPattern(frame, 10, 12, 16, 12, SyntheticImages.pattern(16, 12, seed = 902))
        ReplayTool.writeGrayPng(frame, File(rawDir, "f-001.png"))

        val count = ReplayTool.exportGrayPngs(rawDir, grayDir)

        assertEquals(1, count)
        val exported = File(grayDir, "f-001.png")
        assertTrue(exported.isFile)
        // 识别输入逐位等值：灰度化入库后重解码的灰度帧与原帧完全一致（报告可复现于入库数据）
        val original = ReplayTool.decodeGrayPng(File(rawDir, "f-001.png"))
        val reloaded = ReplayTool.decodeGrayPng(exported)
        assertEquals(original.width, reloaded.width)
        assertEquals(original.height, reloaded.height)
        assertArrayEquals(original.pixels, reloaded.pixels)
    }
}
