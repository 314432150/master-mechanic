package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.decision.ExpectedSignals
import com.example.mastermechanic.decision.UiState
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 标定复核探针（T2-2 取证）：把**设备帧池导出的原始帧**喂进**生产识别路径**，
 * 逐帧打印「本轮搜索集合 + 每条被搜信号的判定明细（结论 / 最高分 / 位置 / 竞争分）」。
 *
 * 解决的问题：真机日志只报「状态转移 + 本轮搜索集合」，**不报是集合里的哪条信号命中**——
 * 同一状态多条样式记录（T2-2 方案 A，`popup_close` ~ `popup_close4`）时，
 * 「这条弹窗到底被哪条记录认出来」无法从日志读出。本探针用同源帧离线补上这一段。
 *
 * 口径：
 * - 输入帧为设备私有目录 `files/calibration/frames/` 导出的**原始帧**（PNG，ARGB）；
 *   灰度按 [ReplayTool.decodeGrayPng] → [com.example.mastermechanic.capture.RgbaToGray]，
 *   与采集端**同一条亮度路径**（红线 4：不引入任何设备常量）；
 * - 走 `CalibrationData.toLoop()` 的**默认期望集合**（生产口径 = FR-01 弹窗阶段源），
 *   与真机会话同一套「待命搜入口标志（启动页 / 活动弹窗）→ 命中后扩为弹窗期集合 → 命中大厅收回」；
 * - 帧与标定帧同几何时走零拷贝快路径；不同几何时自动按画面区归一（T1-11c）。
 *
 * 工作目录 `app/build/replay-work/`（不入版本库）：
 * - `t2-2-artifact.txt`：设备产物副本（与识别侧同源）；
 * - `t2-2-frames/`：帧池导出的原始帧；
 * - 输出 `out/t2-2-per-signal.txt`。
 *
 * 就绪条件不足时整体跳过（日常单测与 CI 不触发）。
 */
class CalibrationReviewProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "t2-2-frames")
    private val outFile = File(workDir, "out/t2-2-per-signal.txt")

    @Test
    fun reviewDeviceFramesPerSignal() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/*.png",
            artifactFile.isFile && framesDir.isDirectory,
        )
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val frames = (framesDir.listFiles { file -> file.isFile && file.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        assumeTrue("t2-2-frames/ 下没有 PNG 帧", frames.isNotEmpty())
        val grays = frames.map { it.name to ReplayTool.decodeGrayPng(it) }

        val lines = ArrayList<String>()
        lines += "# T2-2 标定复核：设备帧池逐帧判定明细"
        lines += "- 产物：${artifactFile.name}（标定帧 ${data.frameWidth}x${data.frameHeight}，" +
            "信号 ${data.signals.size} 条，参数 ${describeParams(data.params)}）"
        lines += "- 信号：${data.signals.joinToString("、") { it.name }}"
        lines += "- 帧：${frames.size} 帧（${frames.first().name} ~ ${frames.last().name}）"
        lines += "- 图例：`信号=结论:最高分@x,y`；结论 MATCHED 命中 / NOT_MATCHED 未命中 / UNRELIABLE 不可信"
        lines += ""

        // ① 生产口径（默认期望集合 = FR-01 弹窗阶段源）：真机会话看到的就是这一列的「搜索 N 条」——
        //    待命期搜入口标志（启动页 + 活动弹窗，4 条）→ 命中任一扩为弹窗期集合（6 条）→ 命中大厅收回。
        lines += "## ① 生产口径（默认 = 弹窗阶段期望集合）"
        val production = data.toLoop()
        grays.forEach { (name, gray) ->
            val result = production.process(gray, isForeground = true)
            lines += "${name}  搜索 ${result.searched.size} 条  " +
                "状态 ${result.state.label}  命中 ${result.hits.joinToString("、") { it.label }.ifEmpty { "无" }}"
            result.records.forEach { record ->
                lines += "    ${record.signalName}=${record.verdict}:${describePeak(record.best)}" +
                    "  竞 ${describePeak(record.competitor)}"
            }
        }

        // ② 全集口径（演练 / 回归）：每帧搜产物里的全部信号——用于「这条弹窗被哪条样式记录认出来」的归因。
        lines += ""
        lines += "## ② 全集口径（显式声明全集，逐信号判定明细 —— 归因用）"
        val matchedCount = LinkedHashMap<String, Int>()
        val allLoop = data.toLoop(ExpectedSignals.ALL)
        grays.forEach { (name, gray) ->
            val result = allLoop.process(gray, isForeground = true)
            result.records.filter { it.matched }.forEach { record ->
                matchedCount[record.signalName] = (matchedCount[record.signalName] ?: 0) + 1
            }
            lines += "${name}  状态 ${result.state.label}  " +
                "命中 ${result.hits.joinToString("、") { it.label }.ifEmpty { "无" }}"
            result.records.forEach { record ->
                lines += "    ${record.signalName}=${record.verdict}:${describePeak(record.best)}" +
                    "  竞 ${describePeak(record.competitor)}"
            }
        }

        lines += ""
        lines += "## 命中归因（全集口径下各信号在 ${frames.size} 帧中的 MATCHED 次数）"
        data.signals.forEach { signal ->
            lines += "- ${signal.name}：${matchedCount[signal.name] ?: 0}"
        }

        val text = lines.joinToString("\n") + "\n"
        outFile.parentFile?.mkdirs()
        outFile.writeText(text, Charsets.UTF_8)
        println(text)
        println("已写出：${outFile.absolutePath}")

        val popupNames = data.stateRules
            .filter { it.state == UiState.ACTIVITY_POPUP }
            .flatMap { it.signalNames }
            .toSet()
        assertTrue(
            "这批帧里应至少有一帧把「活动弹窗」判出来（否则取证前提不成立）：$popupNames",
            matchedCount.keys.any { it in popupNames },
        )
    }

    private fun describePeak(peak: MatchPeak?): String =
        peak?.let { "${fmt(it.score)}@${it.x},${it.y}" } ?: "-"

    protected fun describeParams(params: MatchParams): String =
        "命中线 ${fmt(params.matchThreshold)} / 差距线 ${fmt(params.ambiguityMargin)} / 峰距 ${params.peakMinDistance}"

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}
