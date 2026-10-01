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
 * 两个农场**合一**的交叉取证探针（2026-09-21）。
 *
 * ## 它要回答的问题
 *
 * 产物里农场与好友农场各有一套（标志 + 返回 + 好友入口）。两个农场合一后只留农场那套，
 * 前提是：**农场那套在好友农场画面上也认得出、点得动**。
 *
 * 这件事不能靠"两个控件长得一样"下结论 —— 控件一样**不代表标志一样**：真机帧上
 * 好友农场画面只会报「好友的农场」，说明农场那条标志在好友农场**并不命中**，
 * 照"看着像"直接删掉好友农场那套，第 9 步（拜访）就会卡在"认不出这是农场"。
 *
 * ## 做法（不靠状态判定，直接交叉量）
 *
 * 1. 用标志自己把帧分成两组（农场标志命中 / 好友农场标志命中）—— 不靠文件名、不靠状态结论；
 * 2. 在**好友农场帧**上量农场那套（标志 / 返回 / 好友入口）的命中率与分数区间；
 * 3. 在**农场帧**上量好友农场那套（对称性：旧的这套是不是早就双向可用）。
 *
 * 判据：命中率 **100% 且分数明显高于命中线** ⇒ 那一条可以直接退役；
 * 命中率不足 ⇒ 不能删（要迁移归属，或回标定页重框一条两边都命中的）。
 *
 * 工作目录与用料同 `AnchorLocateProbeTest`（`app/build/replay-work/`）；
 * 输出 `out/t4-3-farm-merge-cross.txt`。就绪条件不足整体跳过（`-PfastTests` 也跳过）。
 */
class FarmMergeProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "t2-2-frames")
    private val outFile = File(workDir, "out/t4-3-farm-merge-cross.txt")

    @Test
    fun crossCheckTheTwoFarms() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/*.png",
            artifactFile.isFile && framesDir.isDirectory,
        )
        // 与生产同源：生产在加载时把窗口重算成"模板 + 每边固定余量"，探针也必须套上
        //（否则量的是重算前的表现）
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
            .tightenedWindows(PatrolAnchors.nonLocatableAnchors)
        val frames = (framesDir.listFiles { file -> file.isFile && file.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        assumeTrue("t2-2-frames/ 下没有 PNG 帧", frames.isNotEmpty())

        val markerDetector = SignalDetector(data.markerSpecs(), data.params)
        val specsByName = LinkedHashMap<String, SignalSpec>()
        data.markerSpecs().forEach { specsByName[it.name] = it }
        UiState.entries.forEach { state ->
            data.anchorSpecs(state).forEach { specsByName[it.name] = it }
        }

        /** 一帧：灰度 + 这一帧上**确实命中**的标志名（不经过状态判定）。 */
        val table = frames.map { file ->
            val gray = ReplayTool.decodeGrayPng(file)
            val hits = markerDetector.detect(gray).filter { it.matched }.map { it.signalName }.toSet()
            file.name to (gray to hits)
        }

        val farmMarkers = data.stateRules.firstOrNull { it.state == UiState.FARM }?.signalNames.orEmpty()
        val friendMarkers =
            data.stateRules.firstOrNull { it.state == UiState.FRIEND_FARM }?.signalNames.orEmpty()
        val farmAnchors = data.anchorsFor(UiState.FARM)
        val friendAnchors = data.anchorsFor(UiState.FRIEND_FARM)

        val lines = ArrayList<String>()
        lines += "# 两个农场合一：交叉取证"
        lines += "- 产物：${artifactFile.name}（标定帧 ${data.frameWidth}x${data.frameHeight}，" +
            "命中线 ${fmt(data.params.matchThreshold)}，窗口 = 模板 + 每边固定余量）"
        lines += "- 帧：${frames.size} 帧；按**标志自己**分组（不靠文件名 / 状态结论）"
        lines += "- 判据：命中率 100% 且分数明显高于命中线 ⇒ 这一条可以退役；否则不能删"
        lines += ""

        if (friendMarkers.isEmpty() || farmMarkers.isEmpty()) {
            lines += "（产物里缺少农场 / 好友农场的标志规则，无法交叉）"
            write(lines)
            return
        }

        val friendFrames = table.filter { (_, pair) -> friendMarkers.any { it in pair.second } }
        val farmFrames = table.filter { (_, pair) -> farmMarkers.any { it in pair.second } }

        lines += "## 好友农场帧 ${friendFrames.size} 帧（由 ${friendMarkers.joinToString()} 判定）"
        lines += "→ 量**农场那套**在好友农场画面上还认不认得出 / 点不点得动："
        lines += report(friendFrames, farmMarkers + farmAnchors, specsByName, data)
        lines += ""
        lines += "## 农场帧 ${farmFrames.size} 帧（由 ${farmMarkers.joinToString()} 判定）"
        lines += "→ 对称地量旧的**好友农场那套**（它是不是早就双向可用）："
        lines += report(farmFrames, friendMarkers + friendAnchors, specsByName, data)
        lines += ""

        // 结论直接写在文件末尾：这份文件是要给人看的证据，不该让人自己去比对数字
        lines += "## 结论"
        lines += conclusion(friendFrames, farmMarkers + farmAnchors, specsByName, data)
        write(lines)
    }

    /** 在 [frames] 上逐条量 [names]，给出命中率与分数区间。 */
    private fun report(
        frames: List<Pair<String, Pair<GrayImage, Set<String>>>>,
        names: List<String>,
        specsByName: Map<String, SignalSpec>,
        data: com.example.mastermechanic.calibration.CalibrationData,
    ): List<String> {
        if (frames.isEmpty()) return listOf("（没有这一组帧，跳过）")
        val lines = ArrayList<String>()
        names.forEach { name ->
            val spec = specsByName[name]
            if (spec == null) {
                lines += "  $name  （产物里没有这条记录）"
                return@forEach
            }
            val detector = SignalDetector(listOf(spec), data.params)
            var hit = 0
            val scores = ArrayList<Double>()
            frames.forEach { (_, pair) ->
                val record = detector.detectSignal(pair.first, spec)
                if (record.matched) hit++
                record.best?.let { scores += it.score }
            }
            val range = if (scores.isEmpty()) {
                "—"
            } else {
                "${fmt(scores.min())}~${fmt(scores.max())}"
            }
            val verdict = if (hit == frames.size) "可退役" else "**不能删**"
            lines += "  %-22s %d/%d  分数 %-14s %s".format(
                Locale.US, name, hit, frames.size, range, verdict,
            )
        }
        return lines
    }

    /** 农场的标志能不能单独撑住"这是农场"——这是"好友农场那套能否全删"的开关。 */
    private fun conclusion(
        friendFrames: List<Pair<String, Pair<GrayImage, Set<String>>>>,
        names: List<String>,
        specsByName: Map<String, SignalSpec>,
        data: com.example.mastermechanic.calibration.CalibrationData,
    ): List<String> {
        if (friendFrames.isEmpty()) return listOf("（没有好友农场帧，无法判断）")
        val detector = SignalDetector(names.mapNotNull { specsByName[it] }, data.params)
        val markers = names.filter { it in data.markerSpecs().map { spec -> spec.name } }
        val ok = markers.filter { marker ->
            val spec = specsByName[marker] ?: return@filter false
            friendFrames.all { (_, pair) -> detector.detectSignal(pair.first, spec).matched }
        }
        return if (ok.isEmpty()) {
            listOf(
                "- 农场那套的**标志**在好友农场画面上**一条都不命中**：好友农场那套**不能**整包删除；",
                "  正确做法是把它**改归属到农场状态**（保留识别能力、不丢已标的东西），",
                "  或在好友农场画面补框一条两边都命中的农场标志。",
            )
        } else {
            listOf("- 以下农场标志在好友农场画面上 100% 命中：${ok.joinToString()}",
                "  ⇒ 好友农场那套**可以**整包删除（返回 / 好友入口另看上面各自那一行）。")
        }
    }

    private fun write(lines: List<String>) {
        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println("\n" + lines.joinToString("\n"))
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)
}
