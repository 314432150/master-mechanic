package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 锚点定位复核探针（M4-T4-3d）：把**真机帧**喂进锚点定位路径，逐帧打印"这一屏点不点得动"。
 *
 * ## 为什么需要它（2026-09-20 用户提出）
 *
 * 已有的 [CalibrationReviewProbeTest] 只回答"这是哪个界面"（标志），**回答不了"这一步点得下去吗"**。
 * 而锚点比标志脆得多，两处已知的坑都只有这么量得出来：
 *
 * 1. **农场是 3D 动态场景**：人物一走，背景整片在变（用户 2026-09-20 原话）。
 *    如果框选时把背景像素带进了模板，同一屏在不同时刻的得分就会**漂移**——
 *    真机上的表现是"这一步偶尔点得动、偶尔卡住"，事后极难排查；
 * 2. **两个农场的同名控件长得一样**（同样是用户口径）：`farm_exit` 与 `friend_farm_exit` 是两个模板，
 *    得分别确认各自在自己那屏上都能命中（而不是"一个能命中就以为另一个也行"）。
 *
 * 判据：**同一屏的多帧之间分数应当稳定**（本探针把每屏的分数区间打出来）；
 * 分数忽高忽低或直接掉到命中线以下 = 模板带进了动态内容，回标定页重框**只框按钮本体**。
 *
 * 工作目录与用料同 [CalibrationReviewProbeTest]：`app/build/replay-work/`
 * （`t2-2-artifact.txt` 与 `t2-2-frames` 目录下的 PNG，由 `_tmp_check/replay-setup.ps1` 摆放；
 * 注释里刻意不写"斜杠 + 星号"的通配路径 —— Kotlin 的块注释会**嵌套**，那样会把整个文件注释掉）；
 * 输出 `out/t2-3-anchor-locate.txt`。就绪条件不足整体跳过（日常单测与 CI 不触发）。
 */
class AnchorLocateProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "t2-2-frames")
    private val outFile = File(workDir, "out/t2-3-anchor-locate.txt")

    @Test
    fun locateAnchorsOnDeviceFrames() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/*.png",
            artifactFile.isFile && framesDir.isDirectory,
        )
        // **与生产同源**：生产在 `CalibrationStore.loadForRuntime` 里把窗口重算成"模板 + 每边固定余量"
        // （2026-09-29 起；此前是 0.45 比例收紧），所以探针也必须套上它 ——
        // 否则这里量的是"重算前"的表现，给出的结论对不上真机。
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
            .tightenedWindows(PatrolAnchors.nonLocatableAnchors)
        val frames = (framesDir.listFiles { file -> file.isFile && file.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        assumeTrue("t2-2-frames/ 下没有 PNG 帧", frames.isNotEmpty())

        // 每个状态 → 它的标志规格：用来判断"这一帧确实是这个画面"（不靠帧名，靠识别自己说话）
        val markersByName = data.markerSpecs().associateBy { it.name }
        val markerSpecs = data.markerSpecs()
        val stateMarkers = data.stateRules.mapNotNull { rule ->
            val specs = rule.signalNames.mapNotNull { markersByName[it] }
            if (specs.isEmpty()) null else rule.state to specs.map { it.name }.toSet()
        }
        val anchorStates = UiState.entries.filter { data.anchorsFor(it).isNotEmpty() }

        val markerDetector = SignalDetector(markerSpecs, data.params)
        val lines = ArrayList<String>()
        lines += "# 锚点定位复核：真机帧 × 每一屏的可点目标"
        lines += "- 产物：${artifactFile.name}（标定帧 ${data.frameWidth}x${data.frameHeight}，" +
            "锚点状态 ${anchorStates.size} 个：" + anchorStates.joinToString("、") { it.label } + "）"
        lines += "- 帧：${frames.size} 帧；图例：`锚点=MATCHED:分数@x,y`，`竞` = 竞争峰"
        lines += ""

        // 每个锚点：出现在多少帧里、命中多少帧、分数区间（3D 背景漂移会在这里暴露）
        val seen = LinkedHashMap<String, Int>()
        val matched = LinkedHashMap<String, Int>()
        val bestScores = LinkedHashMap<String, MutableList<Double>>()

        frames.forEach { file ->
            val gray = ReplayTool.decodeGrayPng(file)
            val markerRecords = markerDetector.detect(gray)
            val present = stateMarkers.filter { (_, names) ->
                markerRecords.any { it.signalName in names && it.matched }
            }.map { it.first }.toSet()
            lines += "${file.name}  画面=${present.joinToString("、") { it.label }.ifEmpty { "（没认出任何界面）" }}"
            anchorStates.filter { it in present }.forEach { state ->
                val specs = data.anchorSpecs(state)
                val detector = SignalDetector(specs, data.params)
                specs.forEach { spec ->
                    val record = detector.detectSignal(gray, spec)
                    seen[spec.name] = (seen[spec.name] ?: 0) + 1
                    if (record.matched) {
                        matched[spec.name] = (matched[spec.name] ?: 0) + 1
                        record.best?.let { bestScores.getOrPut(spec.name) { mutableListOf() } += it.score }
                    }
                    lines += "    ${spec.name}=${record.verdict}:${describePeak(record.best)}" +
                        "  竞 ${describePeak(record.competitor)}"
                }
            }
        }

        lines += ""
        lines += "## 汇总（在「属于该画面」的帧里，各锚点的命中率与分数区间）"
        lines += "| 锚点 | 出现帧数 | 命中帧数 | 最低分 | 最高分 |"
        lines += "| --- | --- | --- | --- | --- |"
        seen.keys.sorted().forEach { name ->
            val scores = bestScores[name].orEmpty()
            lines += "| $name | ${seen[name]} | ${matched[name] ?: 0} | " +
                "${scores.minOrNull()?.let { fmt(it) } ?: "-"} | " +
                "${scores.maxOrNull()?.let { fmt(it) } ?: "-"} |"
        }

        val text = lines.joinToString("\n") + "\n"
        outFile.parentFile?.mkdirs()
        outFile.writeText(text, Charsets.UTF_8)
        println(text)
        println("已写出：${outFile.absolutePath}")
    }

    private fun describePeak(peak: MatchPeak?): String =
        peak?.let { "${fmt(it.score)}@${it.x},${it.y}" } ?: "-"

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}
