package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.decision.UiState
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 弹窗守护口径探针（T2-5 取证）：把整批设备帧喂进**生产识别路径**（`CalibrationData.toLoop()` 默认口径 =
 * [com.example.mastermechanic.decision.PopupWatchExpectedSignals]），验证两件事：
 *
 * 1. **负背景零误命中**：农场 / 好友农场 / 好友面板 / 大厅（无弹窗）帧上，活动弹窗的各条记录**全部未命中**；
 * 2. **命中对照**：大厅 + 活动弹窗帧上至少有一条记录命中（否则"只搜弹窗"就没意义了）。
 *
 * 为什么必须单独验证：T2-5 把守护期的搜索集合换成"活动弹窗的 3 条记录"，**每轮都搜**——
 * 程序停在自己农场、好友面板这些画面上时也在搜这 3 条。若这些背景上出现高分命中，
 * 就会在没有任何弹窗的画面里判出「活动弹窗」，进而走 FR-01 点击链路（红线 3 / 7 不允许）。
 *
 * 分组依据 = 批次 `manifest.txt` 的描述列（样本索引同源标注），不靠帧名猜测。
 *
 * 工作目录 `app/build/replay-work/`：
 * - `t2-2-artifact.txt`：设备产物副本（与识别侧同源，T2-5 复用）；
 * - `set-20260912-03/`：104 帧固定批次（含 manifest.txt）；
 * - 输出 `out/t2-5-popup-watch.txt`。
 *
 * 就绪条件不足时整体跳过（日常单测与 CI 不触发）。
 */
class PopupWatchProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "set-20260912-03")
    private val manifestFile = File(framesDir, "manifest.txt")
    private val outFile = File(workDir, "out/t2-5-popup-watch.txt")

    /** 一个帧分组：用于分开统计负样本（口径要求三类干扰分别统计，不合并成一个通过率）。 */
    private enum class Group(val title: String) {
        FARM("农场 / 好友农场"),
        FRIEND_PANEL("好友面板（列表 / 滚动）"),
        HALL_PLAIN("大厅（无弹窗）"),
        HALL_POPUP("大厅 + 活动弹窗（命中对照）"),
        OTHER("其他（不参与判定）"),
        ;
    }

    @Test
    fun popupWatchOnDeviceBatch() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 set-20260912-03/（manifest.txt + PNG）",
            artifactFile.isFile && manifestFile.isFile,
        )
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val popupNames = data.stateRules
            .filter { it.state == UiState.ACTIVITY_POPUP }
            .flatMap { it.signalNames }
            .toSet()
        assumeTrue("产物里没有活动弹窗记录，无法验证", popupNames.isNotEmpty())

        val descriptions = parseManifest(manifestFile)
        val samples = descriptions.keys
            .map { name -> File(framesDir, name) }
            .filter { it.isFile }
            .sortedBy { it.name }
        assumeTrue("set-20260912-03/ 下没有可读帧", samples.isNotEmpty())

        // 生产口径：`toLoop()` 默认注入 = 只搜活动弹窗（T2-5）。逐帧顺序处理，状态机（滞回）随之累积，
        // 与真机会话一致——注意弹窗帧连续出现时状态会被"确认"出来，这正是我们要观察的路径。
        val loop = data.toLoop()

        val perGroupMatched = LinkedHashMap<Group, Int>()
        val perGroupFrames = LinkedHashMap<Group, Int>()
        val lines = ArrayList<String>()
        lines += "# T2-5 弹窗守护口径：设备批次逐帧判定（生产默认口径 = 只搜活动弹窗）"
        lines += "- 产物：${artifactFile.name}（标定帧 ${data.frameWidth}x${data.frameHeight}，" +
            "信号 ${data.signals.size} 条，参数 ${describeParams(data.params)}）"
        lines += "- 活动弹窗记录：${popupNames.sorted().joinToString("、")}"
        lines += "- 批次：set-20260912-03，共 ${samples.size} 帧（分组依据 = manifest 描述列）"
        lines += "- 图例：`信号=结论:最高分@x,y`；MATCHED 命中 / NOT_MATCHED 未命中 / UNRELIABLE 不可信"
        lines += ""

        val searchedSizes = LinkedHashSet<Int>()
        samples.forEach { frame ->
            val sample = descriptions[frame.name] ?: Sample("", "")
            val group = classify(sample)
            val result = loop.process(ReplayTool.decodeGrayPng(frame), isForeground = true)
            searchedSizes += result.searched.size

            val popupHits = result.records.count { it.matched && it.signalName in popupNames }
            if (group != Group.OTHER) {
                perGroupFrames[group] = (perGroupFrames[group] ?: 0) + 1
                perGroupMatched[group] = (perGroupMatched[group] ?: 0) + popupHits
            }

            lines += "[${group.name}] ${frame.name}  搜索 ${result.searched.size} 条  " +
                "状态 ${result.state.label}  弹窗记录命中 $popupHits 条  （${sample.kind} / ${sample.desc}）"
            result.records.filter { it.signalName in popupNames }.forEach { record ->
                lines += "    ${record.signalName}=${record.verdict}:${describePeak(record.best)}" +
                    "  竞 ${describePeak(record.competitor)}"
            }
        }

        lines += ""
        lines += "## 分组统计（弹窗记录命中数 / 帧数）"
        Group.entries.filter { it != Group.OTHER }.forEach { group ->
            lines += "- ${group.title}：${perGroupMatched[group] ?: 0} / ${perGroupFrames[group] ?: 0} 帧"
        }
        lines += ""
        lines += "## 搜索集合"
        lines += "- 全批次每轮搜索条数取值：${searchedSizes.sorted().joinToString("、")}" +
            "（T2-5 口径 = 恒为弹窗记录条数，不含 launch_start）"

        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println(lines.joinToString("\n"))
        println("已写出：${outFile.absolutePath}")

        // 验收 3：负背景零误命中（农场 / 好友面板 / 大厅无弹窗，分组分别断言，不合并）
        listOf(Group.FARM, Group.FRIEND_PANEL, Group.HALL_PLAIN).forEach { group ->
            assertEquals(
                "${group.title} 上不应有弹窗记录命中（误命中会导致在没有弹窗的画面里走 FR-01 点击链路）",
                0,
                perGroupMatched[group] ?: 0,
            )
        }
        // 验收 3 的对照组：弹窗画面必须仍能命中（否则"只搜弹窗"失去意义）
        assertTrue(
            "大厅 + 活动弹窗帧上应至少有一条弹窗记录命中",
            (perGroupMatched[Group.HALL_POPUP] ?: 0) > 0,
        )
        // 验收 1：集合恒定，且不含 launch_start
        assertEquals("搜索集合应恒为 ${popupNames.size} 条", setOf(popupNames.size), searchedSizes)
        assertTrue("launch_start 不应出现在守护期搜索集合里", "launch_start" !in popupNames)
    }

    /** manifest 行：`sample=<文件名>|<类别>|<信号>|<窗口>|<描述>`。 */
    private data class Sample(val kind: String, val desc: String)

    private fun parseManifest(file: File): LinkedHashMap<String, Sample> {
        val map = LinkedHashMap<String, Sample>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            if (!line.startsWith("sample=")) return@forEach
            val parts = line.removePrefix("sample=").split("|")
            if (parts.size >= 5) map[parts[0].trim()] = Sample(parts[1].trim(), parts[4].trim())
        }
        return map
    }

    /**
     * 分组依据 = manifest 的**类别列 + 描述列**。
     *
     * 单看描述会错判：同款弹窗的重复帧写成「大厅+月度福利礼册（**同前**）」，描述里没有"弹窗"二字，
     * 但类别列是 `neg-mask`（目标被遮挡）——只按描述分类会把它们当成"大厅无弹窗"，看起来像误命中。
     */
    private fun classify(sample: Sample): Group {
        val desc = sample.desc
        return when {
            desc.contains("好友面板") -> Group.FRIEND_PANEL
            desc.contains("农场") -> Group.FARM
            desc.contains("加载页") || desc.contains("设置页") || desc.contains("新手") -> Group.OTHER
            desc.contains("弹窗") || (sample.kind == "neg-mask" && desc.contains("大厅")) -> Group.HALL_POPUP
            desc.contains("提示框") -> Group.OTHER
            desc.contains("大厅") -> Group.HALL_PLAIN
            else -> Group.OTHER
        }
    }

    private fun describePeak(peak: MatchPeak?): String =
        peak?.let { "${fmt(it.score)}@${it.x},${it.y}" } ?: "-"

    private fun describeParams(params: MatchParams): String =
        "命中线 ${fmt(params.matchThreshold)} / 差距线 ${fmt(params.ambiguityMargin)} / 峰距 ${params.peakMinDistance}"

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}
