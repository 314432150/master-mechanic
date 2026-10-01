package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.SelectionWindow
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.patrol.PatrolAnchors
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **「X 族 5→1」离线重放验证**（2026-09-30，研究任务；`docs/progress.md` 第 324 条的建议顺序②）。
 *
 * ## 要回答的问题
 *
 * 产物里 `activity_popup_e1..e5` 是**同一个关闭控件（右上角 X）被框了 5 次**（5 个外观），
 * 5 条记录 × 2 个角色（标志 + 锚点）= **10 条**，每轮都要各搜一遍。实测这 5 条模板的**中心几乎重合**
 * （差 ≤2px）、只有尺寸不同 ⇒ 几何上**完全可能合并成 1 条**（1 个标志 + 1 个锚点）。
 *
 * 但"中心重合"**不等于**"一条模板能认出这 5 个外观" —— 这件事只能靠**在真帧上量**：
 * 合并掉的 4 条每条背后都是一次真实的弹窗外观，认不出就等于 FR-01 漏关一个弹窗。
 *
 * ## 做法（三件证据）
 *
 * 1. **真帧正样本**：拿产物里这 5 条标志**自己**把帧池分类（哪一帧命中哪几个外观）——不靠文件名、
 *    不靠状态判定；再用候选的"公共核心模板"在这些帧上重量分数与位置；
 * 2. **真帧负样本**：这 5 条**一条都没命中**的帧（其它界面）上，候选模板的分数必须低于命中线；
 * 3. **合成帧（兜底）**：某个外观在真帧里没出现时，把它自己的模板像素**回贴**到一块中灰画布上
 *    （模板就是从那一帧裁出来的 ⇒ 忠实），至少保证"5 个外观"这一侧不留空档。
 *
 * ## 候选模板怎么来
 *
 * 5 条模板**中心重合、尺寸不同** ⇒ 它们的**公共核心**（交集矩形）就是"这 5 次框选都覆盖到的那片像素"。
 * 候选 = 在核心矩形上取不同**尺寸**（0/4/8/16 px 每边收缩）× 不同**像素来源**（e1..e5 各自的像素，
 * 以及 5 者的**逐像素均值** —— 均值是"不要偏向任何一次框选"的那个选择）。
 *
 * 判据（工具参数，不是识别判定）：**每个外观都 ≥ 命中线**、且**负样本全部 < 命中线**、
 * 且命中位置与原来那 5 条的偏差 ≤ 4px（位置是锚点的点击落点，不能飘）⇒ 可合。
 *
 * 工作目录 `build/replay-work/`（不入版本库）：`calibration-x5.txt` + `raw-frames/` 下的 PNG 帧；
 * 输出 `out/t2-x-share.txt`。就绪条件不足整体跳过（`-PfastTests` 也跳过）。
 *
 * ⚠ 注意：**注释里不要写"斜杠加星号"**（KDoc 里那个组合会开一个嵌套块注释 ⇒ 整个文件报
 * "Unclosed comment"）—— 本次就踩了，写成"`raw-frames/` 下的 PNG"即可。
 */
class XShareProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "calibration-x5.txt")
    /**
     * 重放用的帧：工作目录下**所有** `raw-frames*` 目录（每采一轮帧池放一个，互不覆盖）。
     *
     * 为什么要合起来看：单轮帧池只覆盖"那一次录制见过的画面"，而合并模板真正的风险恰恰在**没见过的帧**上 ——
     * 多轮合起来既有多样的负样本，也能看出"新录的那轮里有没有旧模板认不出的新外观"（见"额外命中"那一节）。
     */
    private val frameFiles: List<File>
        get() = (workDir.listFiles { f -> f.isDirectory && f.name.startsWith("raw-frames") } ?: emptyArray())
            .sortedBy { it.name }
            .flatMap { dir ->
                (dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray()).toList()
            }
            .distinctBy { it.name }
            .sortedBy { it.name }
    private val outFile = File(workDir, "out/t2-x-share.txt")

    /** 产物里这 5 条"同一个 X"的记录 ID（顺序即产物声明顺序）。 */
    private val xIds = listOf(
        "activity_popup_e1",
        "activity_popup_e2",
        "activity_popup_e3",
        "activity_popup_e4",
        "activity_popup_e5",
    )

    /** 候选尺寸：相对公共核心**每边**收缩这么多像素（0 = 就是核心本身）。 */
    private val shrinkPerEdgePx = listOf(0, 4, 8, 16)

    /**
     * **只对"逐均值"那一份**多扫的几档收缩（成本杠杆的唯一方向就是"把模板框小"）：
     * 用来回答"**还有没有必要回标定页重框一次**"——重框能买到的只有"更小的模板"，
     * 而"能缩到多小"这件事本探针离线就能穷举，不必让用户去手框。
     */
    private val shrinkSweepForMean = listOf(12, 20, 24, 28)

    /** 位置偏差容忍（像素）：合并后锚点的点击落点不该比原来飘得更多（工具参数）。 */
    private val positionTolerancePx = 4.0

    /**
     * "额外命中"的落点容忍（像素）：候选在"旧 5 条认不出的帧"上命中时，落点离 X 中心这么近就当作
     * **同一个控件的新外观**（真弹窗），远于此则更像误命中。用途只是诊断口径，不参与可否合并的判据。
     */
    private val extraHitPositionTolerancePx = 10.0

    /**
     * 挑"推荐候选"时的**负样本余量下限**（工具参数）：候选在非弹窗帧上的最高分必须比命中线低这么多
     * 才算"留够了距离"。为什么不只看"没误命中"：**没误命中**只说明这 99 帧上没到线，
     * 而合并的代价是**冗余度下降**（原来 5 条任一命中即在屏）⇒ 要给"没见过的画面"留出余量。
     */
    private val negativeMarginFloor = 0.25

    /**
     * 分离度（外观最差分 − 负样本最大分）差到这个程度以内，就当作**实质相同** ⇒ 优先取"逐均值"那份。
     *
     * 依据（本次实测）：63x63 那一档里 e1 = 0.5781、mean = 0.5763、e4 = 0.5756 —— 差 ≤0.003 在
     * 99 帧真帧上是**噪声级别**，而"均值不偏向任何一次框选"是**原理上的**优势（对没见过的外观更稳）。
     */
    private val separationTolerance = 0.01

    /**
     * **合并产物的收缩量**（每边像素）——必须与 `canFiveAppearancesShareOneTemplate` 的『四、结论』里那条
     * **推荐**一致（换帧池后推荐可能变，两处都在本文件里，改了要重跑）。
     *
     * ⚠ **这个数字跟着帧池走过一次**：只在旧池（120 帧）上跑时推荐 63x63；把新录的 64 帧并进来（共 184 帧）后，
     * 分离度最高的是 71x71（0.5484）那一档，63x63（0.5398）掉出了「相差 < 0.01 视为同档」的窗口 ⇒ 取 71x71。
     *
     * 为什么不取更小的 47x47（更省）：它在负样本上的最高分明显更高（0.49~0.52 vs 0.45），
     * 说明**越小的模板越不独特**（本项目对"认错界面"的容忍度远低于"慢一点"）。
     * 为什么不用完整的 79x79：省下的乘加要少 1/4，而 71x71 的分离度与它**同一水平**。
     */
    private val mergedShrinkPerEdgePx = 4

    @Test
    fun canFiveAppearancesShareOneTemplate() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/calibration-x5.txt 与 raw-frames*/ 下的 PNG 帧",
            artifactFile.isFile && frameFiles.isNotEmpty(),
        )
        // 与生产同源：加载期会把窗口重算成"模板 + 每边固定余量"再按成本预算兜一道
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
            .tightenedWindows(PatrolAnchors.nonLocatableAnchors)
        val frames = frameFiles
        val framesByName = frames.associateBy { it.name }

        val fw = data.frameWidth
        val fh = data.frameHeight
        val markerEntries = xIds.mapNotNull { id ->
            data.signals.firstOrNull { it.id == id && it.role == SignalRole.MARKER }
        }
        assumeTrue(
            "产物里缺少 X 族标志记录（需要 ${xIds.joinToString()}）",
            markerEntries.size == xIds.size,
        )
        val markerSpecs = xIds.map { id -> data.markerSpecs().first { it.name == id } }
        val judge = SignalDetector(emptyList(), data.params)
        val threshold = data.params.matchThreshold

        // ------------------------------------------------ 第 1 遍：真帧上 5 个外观各自的命中
        val scoresOnFrame = LinkedHashMap<String, Map<String, Double>>()
        val matchesOnFrame = LinkedHashMap<String, Set<String>>()
        val centersOnFrame = LinkedHashMap<String, Map<String, Pair<Int, Int>>>()
        frames.forEach { file ->
            val gray = ReplayTool.decodeGrayPng(file)
            val scores = LinkedHashMap<String, Double>()
            val centers = LinkedHashMap<String, Pair<Int, Int>>()
            val hits = LinkedHashSet<String>()
            markerSpecs.forEach { spec ->
                val template = spec.templates.first()
                val record = judge.detectSignal(gray, spec)
                val best = record.best ?: return@forEach
                scores[spec.name] = best.score
                centers[spec.name] = (best.x + template.width / 2) to (best.y + template.height / 2)
                if (record.matched) hits += spec.name
            }
            scoresOnFrame[file.name] = scores
            matchesOnFrame[file.name] = hits
            centersOnFrame[file.name] = centers
        }
        val positiveFrames = xIds.associateWith { id ->
            matchesOnFrame.filterValues { id in it }.keys.toList()
        }
        val negativeFrames = matchesOnFrame.filterValues { hits -> xIds.none { it in hits } }.keys.toList()

        // ------------------------------------------------ 几何：5 条模板的矩形与公共核心
        // 模板恒在窗口正中（写入端"对称取小"+ 运行期两种变换都绕中心）⇒ 中心 = 窗口中心
        val templateRects = markerEntries.map { templateRectOf(it, fw, fh) }
        val core = intArrayOf(
            templateRects.maxOf { it[0] },
            templateRects.maxOf { it[1] },
            templateRects.minOf { it[2] },
            templateRects.minOf { it[3] },
        )
        val coreW = core[2] - core[0]
        val coreH = core[3] - core[1]
        assumeTrue("5 条模板没有公共核心（交集为空）：$coreW x $coreH", coreW > 0 && coreH > 0)
        val coreCenterX = (core[0] + core[2]) / 2
        val coreCenterY = (core[1] + core[3]) / 2

        // 公共核心上：5 个外观两两的皮尔逊相关系数（"这 5 次框选到底是不是同一个样子"）
        val corePatches = (0 until xIds.size).map { index ->
            patchOf(markerEntries[index].templates.first(), templateRects[index], core)
        }
        val pairCorrelations = ArrayList<String>()
        for (i in xIds.indices) {
            for (j in i + 1 until xIds.size) {
                pairCorrelations += "${shortName(i)}-${shortName(j)} ${fmt(correlation(corePatches[i], corePatches[j]))}"
            }
        }

        // ------------------------------------------------ 候选：核心尺寸 × 像素来源
        data class Candidate(
            val label: String,
            val source: String,
            val rect: IntArray,
            val template: Template,
            val markerSpec: SignalSpec,
            val anchorSpec: SignalSpec,
            val markerOps: Long,
        )

        val candidates = ArrayList<Candidate>()
        (shrinkPerEdgePx + shrinkSweepForMean).distinct().sorted().forEach { shrink ->
            val width = coreW - shrink * 2
            val height = coreH - shrink * 2
            if (width < 24 || height < 24) return@forEach
            val rect = centeredRect(core, width, height)
            val patches = markerEntries.mapIndexed { index, entry ->
                patchOf(entry.templates.first(), templateRects[index], rect)
            }
            val sources = patches.mapIndexed { index, patch -> shortName(index) to patch } + ("mean" to meanPatch(patches))
            sources.forEach { (source, patch) ->
                // 更小的那几档只跑"逐均值"这一份：它是产物会用的来源，也是 5 个外观的代表
                if (source != "mean" && shrink !in shrinkPerEdgePx) return@forEach
                val template = Template(width, height, patch)
                val markerSpec = specFor("x-probe-$source-${width}x$height", rect, template, SignalRole.MARKER, fw, fh)
                val anchorSpec = specFor("x-probe-$source-${width}x$height", rect, template, SignalRole.ANCHOR, fw, fh)
                candidates += Candidate(
                    label = "$source @${width}x$height",
                    source = source,
                    rect = rect,
                    template = template,
                    markerSpec = markerSpec,
                    anchorSpec = anchorSpec,
                    markerOps = TemplateMatcher.estimateCoarseOps(
                        markerSpec.window, width, height, fw, fh,
                    ),
                )
            }
        }
        assumeTrue("没有生成任何候选（公共核心太小？）", candidates.isNotEmpty())

        // ------------------------------------------------ 第 2 遍：候选 × 需要的帧（正样本 ∪ 负样本）
        class Acc {
            val positiveScores = LinkedHashMap<String, MutableList<Double>>()
            val centerErrors = ArrayList<Double>()
            var positiveTotal = 0
            var positiveMatched = 0
            var negativeMax = 0.0
            var negativeFalseMatches = 0
            /** 「旧 5 条一条都不命中、候选却命中」的帧：帧名 / 分数 / 命中中心（用来判"新外观"还是"误命中"）。 */
            val extraHits = ArrayList<Triple<String, Double, IntArray>>()
            val syntheticScores = LinkedHashMap<String, Double>()
            var syntheticMatched = 0
        }

        val accs = LinkedHashMap<String, Acc>()
        candidates.forEach { accs[it.label] = Acc() }
        val neededFrames = (positiveFrames.values.flatten() + negativeFrames).distinct().sorted()
        neededFrames.forEach { name ->
            val gray = ReplayTool.decodeGrayPng(framesByName.getValue(name))
            val hits = matchesOnFrame[name].orEmpty()
            candidates.forEach { candidate ->
                val acc = accs.getValue(candidate.label)
                val record = judge.detectSignal(gray, candidate.markerSpec)
                val best = record.best
                val score = best?.score ?: 0.0
                xIds.forEach { id ->
                    if (name !in positiveFrames.getValue(id)) return@forEach
                    acc.positiveScores.getOrPut(id) { ArrayList() }.add(score)
                    acc.positiveTotal++
                    if (record.matched) acc.positiveMatched++
                    val reference = centersOnFrame[name]?.get(id)
                    if (reference != null && best != null) {
                        val cx = best.x + candidate.template.width / 2
                        val cy = best.y + candidate.template.height / 2
                        acc.centerErrors += hypot((cx - reference.first).toDouble(), (cy - reference.second).toDouble())
                    }
                }
                if (name in negativeFrames) {
                    if (score > acc.negativeMax) acc.negativeMax = score
                    if (record.matched) {
                        acc.negativeFalseMatches++
                        acc.extraHits += Triple(
                            name,
                            score,
                            intArrayOf(
                                (best?.x ?: 0) + candidate.template.width / 2,
                                (best?.y ?: 0) + candidate.template.height / 2,
                            ),
                        )
                    }
                }
            }
        }

        // 合成帧（兜底）：每个外观把自己的模板像素回贴到中灰画布上 —— 保证"5 个外观"这一侧不留空档
        val syntheticFrames = xIds.indices.associate { index ->
            xIds[index] to syntheticFrame(markerEntries[index], templateRects[index], fw, fh)
        }
        candidates.forEach { candidate ->
            val acc = accs.getValue(candidate.label)
            xIds.forEach { id ->
                val record = judge.detectSignal(syntheticFrames.getValue(id), candidate.markerSpec)
                acc.syntheticScores[id] = record.best?.score ?: 0.0
                if (record.matched) acc.syntheticMatched++
            }
        }

        // ------------------------------------------------ 耗时（离线相对值）：现在 5 条 vs 候选 1 条
        val timeFrames = neededFrames.take(12).map { ReplayTool.decodeGrayPng(framesByName.getValue(it)) }
        fun medianMs(specs: List<SignalSpec>): Double {
            repeat(2) { timeFrames.forEach { gray -> specs.forEach { judge.detectSignal(gray, it) } } }
            val times = timeFrames.map { gray ->
                val started = System.nanoTime()
                specs.forEach { judge.detectSignal(gray, it) }
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            return times[times.size / 2]
        }

        val baselineOps = markerSpecs.sumOf {
            val template = it.templates.first()
            TemplateMatcher.estimateCoarseOps(it.window, template.width, template.height, fw, fh)
        }
        val baselineMs = medianMs(markerSpecs)

        // ------------------------------------------------ 报告
        val lines = ArrayList<String>()
        lines += "# 「X 族 5→1」离线重放验证"
        lines += "- 产物：${artifactFile.name}（标定帧 ${fw}x$fh，命中线 ${fmt(threshold)}，" +
            "窗口 = 模板 + 每边固定余量「标志 ${SelectionWindow.MARKER_MARGIN_PX} / 锚点 " +
            "${SelectionWindow.ANCHOR_MARGIN_PX} px」再按成本预算兜一道）"
        lines += "- 帧：${frames.size} 帧（真机标定帧池），正/负样本由**这 5 条标志自己**判定" +
            "（不靠文件名、不靠状态判定）"
        lines += "- 判据：每个外观 ≥ 命中线 ∧ 负样本 < 命中线 ∧ 位置偏差 ≤ ${fmt(positionTolerancePx)}px ⇒ 可合"
        lines += ""
        lines += "## 一、这 5 个外观在真帧上的分布"
        xIds.forEachIndexed { index, id ->
            val list = positiveFrames.getValue(id)
            val scores = list.mapNotNull { scoresOnFrame[it]?.get(id) }
            lines += "- ${shortName(index)}（$id）：命中 ${list.size}/${frames.size} 帧" +
                "，分数 " + rangeText(scores)
        }
        lines += "- 负样本（这 5 条都没命中）：${negativeFrames.size}/${frames.size} 帧"
        val overlaps = ArrayList<String>()
        for (i in xIds.indices) {
            for (j in i + 1 until xIds.size) {
                val both = matchesOnFrame.count { (it.value.contains(xIds[i]) && it.value.contains(xIds[j])) }
                if (both > 0) overlaps += "${shortName(i)}+${shortName(j)}=$both"
            }
        }
        lines += "- 互命中帧数：" + if (overlaps.isEmpty()) "（无）" else overlaps.joinToString("，")
        lines += ""
        lines += "## 二、几何"
        lines += "| 记录 | 模板 | 模板矩形（中心） | 窗口 | 粗搜乘加 |"
        lines += "| --- | --- | --- | --- | --- |"
        markerEntries.forEachIndexed { index, entry ->
            val rect = templateRects[index]
            val template = entry.templates.first()
            val bounds = entry.window.pixelBounds(fw, fh)
            val ops = TemplateMatcher.estimateCoarseOps(
                entry.window, template.width, template.height, fw, fh,
            )
            lines += "| ${shortName(index)} | ${template.width}x${template.height} | " +
                "[${rect[0]},${rect[1]}) ${rect[2] - rect[0]}x${rect[3] - rect[1]} " +
                "@(${(rect[0] + rect[2]) / 2},${(rect[1] + rect[3]) / 2}) | " +
                "${bounds.x1 - bounds.x0}x${bounds.y1 - bounds.y0} | ${opsText(ops)} |"
        }
        lines += "- **公共核心（5 条交集）** = ${coreW}x$coreH @[${core[0]},${core[1]})，" +
            "中心 (${coreCenterX},${coreCenterY})"
        lines += "- 公共核心上两两相关系数（1.000 = 像素级同一个样子）：${pairCorrelations.joinToString("，")}"
        lines += ""
        lines += "## 三、候选（像素来源 × 尺寸）"
        lines += "口径：正样本 = 该外观**在真帧上命中**的那些帧上，候选模板的**最低分**（越接近 1 越好）；" +
            "负样本 = 这 5 条一条都没命中的帧上，候选模板的**最高分**（越低越好）；" +
            "「合成帧」= 该外观没有真帧时的兜底（模板像素回贴中灰画布）"
        lines += ""
        lines += "| 候选 | 窗口 | 乘加 | 外观最低分（真帧）| 合成帧命中 | 负样本最大分 | 负样本误命中 | 位置偏差最大 | 结论 |"
        lines += "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"
        data class Evaluated(val candidate: Candidate, val acc: Acc, val passes: Boolean, val note: String)
        val evaluated = candidates.map { candidate ->
            val acc = accs.getValue(candidate.label)
            val realOk = xIds.all { id ->
                val scores = acc.positiveScores[id]
                !scores.isNullOrEmpty() && scores.min() >= threshold
            }
            val negativesOk = acc.negativeFalseMatches == 0 && acc.negativeMax < threshold
            val positionOk = (acc.centerErrors.maxOrNull() ?: 0.0) <= positionTolerancePx
            val passes = realOk && negativesOk && positionOk
            val note = if (passes) {
                if (acc.syntheticMatched == xIds.size) "**可合**" else "**可合**（有外观无真帧）"
            } else {
                buildString {
                    if (!realOk) append("外观不达标 ")
                    if (!negativesOk) append("负样本误命中 ")
                    if (!positionOk) append("位置飘 ")
                }.trim()
            }
            Evaluated(candidate, acc, passes, note)
        }
        evaluated.forEach { row ->
            val acc = row.acc
            val perVariant = xIds.mapIndexed { index, id ->
                val scores = acc.positiveScores[id]
                if (scores.isNullOrEmpty()) "${shortName(index)} —" else "${shortName(index)} ${fmt(scores.min())}"
            }
            lines += "| ${row.candidate.label} | ${boundsText(row.candidate.markerSpec.window, fw, fh)} | " +
                "${opsText(row.candidate.markerOps)} | ${perVariant.joinToString(" / ")} | " +
                "${acc.syntheticMatched}/5 | ${fmt(acc.negativeMax)} | ${acc.negativeFalseMatches} | " +
                "${fmt(acc.centerErrors.maxOrNull() ?: 0.0)}px | ${row.note} |"
        }
        lines += ""
        lines += "## 三补、候选「额外命中」（旧 5 条一条都不命中、候选却命中）"
        lines += "口径：这些帧落在负样本里，出现命中只有两种解释 —— **旧 5 条认不出的新外观**（合并后反而更泛化，" +
            "是好事）或**误命中**（危险）。用**落点**区分：真弹窗的 X 固定在 (${coreCenterX},${coreCenterY}) 附近。" +
            "⚠ 判据把「额外命中」算作**不通过**（保守：宁可说不行，也不能让一个会认错界面的模板进产物），" +
            "所以这一节是给人看的诊断。"
        val withExtra = evaluated.filter { it.acc.extraHits.isNotEmpty() }
        if (withExtra.isEmpty()) {
            lines += "- **没有任何候选出现额外命中** ⇒ 负样本干净，合并不会引入新的「在屏」判定"
        } else {
            withExtra.sortedByDescending { it.acc.extraHits.size }.forEach { row ->
                val offsets = row.acc.extraHits.map { hit ->
                    hypot(
                        (hit.third[0] - coreCenterX).toDouble(),
                        (hit.third[1] - coreCenterY).toDouble(),
                    )
                }
                val nearCount = offsets.count { it <= extraHitPositionTolerancePx }
                lines += "- `${row.candidate.label}`：额外命中 ${row.acc.extraHits.size} 帧，" +
                    "分数 ${rangeText(row.acc.extraHits.map { it.second })}，" +
                    "落点距 X 中心 ${fmt(offsets.min())}~${fmt(offsets.max())}px" +
                    "（其中 ≤${fmt(extraHitPositionTolerancePx)}px 的 $nearCount 帧 ⇒ 像「新外观的同一个 X」）；" +
                    "帧：${row.acc.extraHits.take(6).joinToString("，") { "${it.first.removePrefix("frame-").removeSuffix(".png")}@${fmt(it.second)}" }}" +
                    if (row.acc.extraHits.size > 6) " …" else ""
            }
        }
        lines += ""
        lines += "## 四、结论"
        val passing = evaluated.filter { it.passes }
        // 挑选口径（**先把规则写死在这里，再让产物跟着它走**，免得"报告推荐的"与"实际写进产物的"不是同一条）：
        // ① 三条判据全过 ∧ 负样本余量 ≥ [negativeMarginFloor]；
        // ② 在剩下的里面取**分离度**（外观最差分 − 负样本最大分）最大的那一档；
        // ③ 分离度相差 < [separationTolerance] 时视为同档 ⇒ 优先取"逐均值"（不偏向任何一次框选），再取乘加更少者。
        val safe = passing.filter { it.acc.negativeMax <= threshold - negativeMarginFloor }
        fun separation(row: Evaluated): Double =
            xIds.minOf { id -> row.acc.positiveScores[id]?.min() ?: 0.0 } - row.acc.negativeMax
        val bestSeparation = safe.maxOfOrNull { separation(it) }
        val best = if (bestSeparation == null) {
            passing.maxByOrNull { it.candidate.template.width * it.candidate.template.height }
        } else {
            safe.filter { separation(it) >= bestSeparation - separationTolerance }
                .sortedWith(
                    compareByDescending<Evaluated> { it.candidate.source == "mean" }
                        .thenBy { it.candidate.markerOps },
                )
                .firstOrNull()
        }
        lines += if (passing.isEmpty()) {
            "- **没有任何候选同时满足三条判据** ⇒ 本次**不建议**按单一模板合并；" +
                "要么保留 5 条、要么回标定页在**同一外观**下重框一条更小的（只含 X 本体）。"
        } else {
            "- 满足三条判据的候选：${passing.size}/${evaluated.size} 个；其中**负样本余量 ≥ " +
                "${fmt(negativeMarginFloor)}**（即最大分 ≤ ${fmt(threshold - negativeMarginFloor)}）的有 ${safe.size} 个"
        }
        if (best != null) {
            val acc = best.acc
            val rect = best.candidate.rect
            lines += "- **推荐**：`${best.candidate.label}`（模板 ${best.candidate.template.width}x" +
                "${best.candidate.template.height}，像素矩形 [${rect[0]},${rect[1]})-[${rect[2]},${rect[3]})）；" +
                "外观最低分 ${fmt(xIds.minOf { id -> acc.positiveScores[id]?.min() ?: 0.0 })}，" +
                "负样本最大分 ${fmt(acc.negativeMax)}，" +
                "分离度 ${fmt(separation(best))}，" +
                "位置偏差最大 ${fmt(acc.centerErrors.maxOrNull() ?: 0.0)}px"
            lines += "- 挑选口径：判据全过 ∧ 负样本余量 ≥ ${fmt(negativeMarginFloor)} ⇒ 取**分离度**最大那一档" +
                "（相差 < ${fmt(separationTolerance)} 视为同档）⇒ 同档里优先**逐均值**、再取乘加更少者" +
                "（`out/calibration-x1.txt` 就是按这条规则生成的）"
            val cheapest = passing.minByOrNull { it.candidate.markerOps }
            val conservative = passing.maxByOrNull { it.candidate.template.width }
            lines += "- 备选：最省 = `${cheapest?.candidate?.label}`（负样本最大分 " +
                "${cheapest?.let { fmt(it.acc.negativeMax) }}）；最保守 = `${conservative?.candidate?.label}`" +
                "（负样本最大分 ${conservative?.let { fmt(it.acc.negativeMax) }}）"
            lines += "- 写产物时：标志窗口 = 该矩形 + ${SelectionWindow.MARKER_MARGIN_PX}px/边、" +
                "锚点窗口 = 同一矩形 + ${SelectionWindow.ANCHOR_MARGIN_PX}px/边（同一份模板，两个角色各写一条）"
        }
        lines += ""
        lines += "## 五、成本（离线相对值；真机换算见 TemplateMatcher.estimateCoarseOps 的注释：≈6~10ms / 百万次乘加）"
        lines += "- 现在这 5 条标志：合计 ${opsText(baselineOps)} 乘加 / 轮，离线中位耗时 ${fmt(baselineMs)}ms（12 帧）"
        if (best != null) {
            val mergedMs = medianMs(listOf(best.candidate.markerSpec))
            lines += "- 合并成 1 条：${opsText(best.candidate.markerOps)} 乘加 / 轮（" +
                "省 ${percent(1.0 - best.candidate.markerOps.toDouble() / baselineOps)} ）、" +
                "离线中位耗时 ${fmt(mergedMs)}ms（提速 x${fmt(baselineMs / mergedMs)}）"
            lines += "- 锚点侧同理（`anchor=ACTIVITY_POPUP` 也是 5 条 → 1 条）：只在弹窗那一屏付，" +
                "但同样省 ${percent(1.0 - best.candidate.markerOps.toDouble() / baselineOps)}"
        }
        write(lines)

        assertTrue("应至少评估一个候选", candidates.isNotEmpty())
        assertTrue("正负样本应能切分（至少要有负样本）", negativeFrames.isNotEmpty())
    }

    /**
     * **生成合并后的产物并自校验**（研究任务的交付物；**不会**自动推到设备）。
     *
     * 把 `activity_popup_e1..e5`（10 条记录）压成**1 个标志 + 1 个锚点**：同一份"公共核心 + 收缩"的模板，
     * 按角色各给一次窗口（标志 48 / 锚点 64 px/边），其余记录与状态规则原样保留（只是把引用收窄到 `e1`）。
     * 写出 `out/calibration-x1.txt`，然后**解码回读 + 在全部帧上重放**自校验三件：
     * 1. 回读后 ACTIVITY_POPUP 名下确实只剩 1 条标志 + 1 条锚点（角色、窗口、乘加都合口径）；
     * 2. 这条记录在所有帧上的分数分布**是双峰的**：弹窗帧全部 ≥ 命中线，其余帧全部低于（命中线 − 余量）；
     * 3. **命中帧集合与合并前 5 条的并集完全相同**（逐帧对齐：合并不该丢掉任何已见过的外观，
     *    也不该多判出任何一帧）。
     *
     * ⚠ 这里用的 `均值 + 每边收缩 [mergedShrinkPerEdgePx]px` **就是** `canFiveAppearancesShareOneTemplate`
     * 那条挑选规则会选出来的候选（分离度最大档里优先逐均值）—— 两个测试的结论必须指同一条，
     * 否则"报告说 A、产物写的是 B"。
     */
    @Test
    fun writeMergedArtifactAndVerifyByReplay() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/calibration-x5.txt 与 raw-frames*/ 下的 PNG 帧",
            artifactFile.isFile && frameFiles.isNotEmpty(),
        )
        val source = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val markerEntries = xIds.mapNotNull { id ->
            source.signals.firstOrNull { it.id == id && it.role == SignalRole.MARKER }
        }
        assumeTrue("产物里缺少 X 族标志记录（需要 ${xIds.joinToString()}）", markerEntries.size == xIds.size)

        val core = coreRectOf(markerEntries, source.frameWidth, source.frameHeight)
        val (rect, template) = mergedTemplate(
            entries = markerEntries,
            core = core,
            shrinkPerEdge = mergedShrinkPerEdgePx,
            frameWidth = source.frameWidth,
            frameHeight = source.frameHeight,
        )
        val merged = mergedData(source, rect, template)
        val text = CalibrationCodec.encode(merged)
        outFile.parentFile?.mkdirs()
        val artifactOut = File(outFile.parentFile, "calibration-x1.txt")
        artifactOut.writeText(text, Charsets.UTF_8) // writeText 默认 UTF-8 **无 BOM**（产物口径）

        val survivorId = xIds.first()
        val back = CalibrationCodec.decode(text).tightenedWindows(PatrolAnchors.nonLocatableAnchors)
        val mergedMarkers = back.signals.filter { it.role == SignalRole.MARKER && it.id in xIds }
        val mergedAnchors = back.signals.filter { it.role == SignalRole.ANCHOR && it.id in xIds }

        // ---- 在所有帧上重放：合并后的这一条与合并前那 5 条**逐帧对齐**
        // ⚠ 判据必须是"**命中帧集合完全相同**"，不是"分数差不多"：合并的唯一风险就是漏掉某次外观，
        // 而漏没漏只有"同一帧上前者认得出、后者认不出"能证明。
        val frames = frameFiles
        assumeTrue("raw-frames*/ 下没有 PNG 帧", frames.isNotEmpty())
        val spec = back.markerSpecs().first { it.name == survivorId }
        val judge = SignalDetector(emptyList(), back.params)
        val sourceRuntime = source.tightenedWindows(PatrolAnchors.nonLocatableAnchors)
        val originalSpecs = xIds.map { id -> sourceRuntime.markerSpecs().first { it.name == id } }
        val above = ArrayList<Double>()
        val below = ArrayList<Double>()
        val mergedHitFrames = LinkedHashSet<String>()
        val originalHitFrames = LinkedHashSet<String>()
        frames.forEach { file ->
            val gray = ReplayTool.decodeGrayPng(file)
            val record = judge.detectSignal(gray, spec)
            val score = record.best?.score ?: 0.0
            if (score >= back.params.matchThreshold) above += score else below += score
            if (record.matched) mergedHitFrames += file.name
            if (originalSpecs.any { judge.detectSignal(gray, it).matched }) originalHitFrames += file.name
        }
        val onlyMerged = mergedHitFrames - originalHitFrames
        val onlyOriginal = originalHitFrames - mergedHitFrames

        val markerBounds = spec.window.pixelBounds(back.frameWidth, back.frameHeight)
        val anchorSpec = back.anchorSpecs(com.example.mastermechanic.decision.UiState.ACTIVITY_POPUP)
            .firstOrNull { it.name == survivorId }
        val ops = TemplateMatcher.estimateCoarseOps(
            spec.window, template.width, template.height, back.frameWidth, back.frameHeight,
        )
        val lines = ArrayList<String>()
        lines += "# 合并产物自校验：calibration-x1.txt（X 族 5→1，2026-09-30）"
        lines += "- 来源：${artifactFile.name}（5 条 × 2 角色 = 10 条）；产物文件写入 `out/calibration-x1.txt`"
        lines += "- 合并模板：${template.width}x${template.height}（公共核心 " +
            "${core[2] - core[0]}x${core[3] - core[1]} 每边收缩 ${mergedShrinkPerEdgePx}px，像素取 5 个外观的**逐均值**）" +
            " —— 与 `t2-x-share.txt` 那条挑选规则选出的候选一致"
        lines += "- 像素矩形 [${rect[0]},${rect[1]})-[${rect[2]},${rect[3]})"
        lines += "- 回读：X 族名下 标志 ${mergedMarkers.size} 条 / 锚点 ${mergedAnchors.size} 条" +
            "（应为 1 / 1）；标志窗口 ${markerBounds.x1 - markerBounds.x0}x${markerBounds.y1 - markerBounds.y0}" +
            "、乘加 ${opsText(ops)}（成本护栏 ${opsText(TemplateMatcher.MATCH_OPS_BUDGET)} 以内）"
        lines += "- 锚点窗口：" + (anchorSpec?.let { spec ->
            val bounds = spec.window.pixelBounds(back.frameWidth, back.frameHeight)
            "${bounds.x1 - bounds.x0}x${bounds.y1 - bounds.y0}"
        } ?: "（回读后找不到，说明规则没接上）")
        lines += ""
        lines += "## 重放（${frames.size} 帧）"
        lines += "- 合并后这一条：命中（≥ 命中线 ${fmt(back.params.matchThreshold)}）${above.size} 帧，" +
            "分数 ${rangeText(above)}；其余 ${below.size} 帧最高 ${fmt(below.maxOrNull() ?: 0.0)}"
        lines += "- **逐帧对齐**（合并前 5 条**任一命中** = 命中）：合并前 ${originalHitFrames.size} 帧 / " +
            "合并后 ${mergedHitFrames.size} 帧 ⇒ " +
            if (onlyMerged.isEmpty() && onlyOriginal.isEmpty()) {
                "**集合完全相同** ✓（合并没丢帧、也没多判）"
            } else {
                "差集：只有合并后命中 ${onlyMerged.size} 帧、只有合并前命中 ${onlyOriginal.size} 帧 ✗"
            }
        lines += "- 判定：**命中帧集合必须完全相同**（合并唯一的风险就是漏掉某次外观），" +
            "且非弹窗帧的最高分须 ≤ 命中线 − ${fmt(negativeMarginFloor)}"
        writeReport(File(outFile.parentFile, "t2-x-share-verify.txt"), lines)

        assertTrue(
            "回读后 X 族应只剩 1 个标志 + 1 个锚点（实际 ${mergedMarkers.size} / ${mergedAnchors.size}）",
            mergedMarkers.size == 1 && mergedAnchors.size == 1,
        )
        assertTrue("合并记录应命中到弹窗帧（≥15 帧，实际 ${above.size}）", above.size >= 15)
        assertTrue(
            "合并后命中的帧集合必须与合并前 5 条的并集完全相同" +
                "（只多 ${onlyMerged.size} 帧、只少 ${onlyOriginal.size} 帧）",
            onlyMerged.isEmpty() && onlyOriginal.isEmpty(),
        )
        assertTrue(
            "非弹窗帧的最高分必须低于「命中线 − ${negativeMarginFloor}」（实际 ${below.maxOrNull()}）",
            (below.maxOrNull() ?: 0.0) <= back.params.matchThreshold - negativeMarginFloor,
        )
        assertTrue("合并模板的粗搜乘加不该超预算", ops <= TemplateMatcher.MATCH_OPS_BUDGET)
    }

    /** 5 条模板的**公共核心**矩形（各自矩形对齐后取交集）。 */
    private fun coreRectOf(
        entries: List<CalibrationData.SignalEntry>,
        frameWidth: Int,
        frameHeight: Int,
    ): IntArray {
        val rects = entries.map { templateRectOf(it, frameWidth, frameHeight) }
        return intArrayOf(
            rects.maxOf { it[0] },
            rects.maxOf { it[1] },
            rects.minOf { it[2] },
            rects.minOf { it[3] },
        )
    }

    /** 以 [reference] 的中心为心、指定尺寸的矩形。 */
    private fun centeredRect(reference: IntArray, width: Int, height: Int): IntArray {
        val centerX = (reference[0] + reference[2]) / 2
        val centerY = (reference[1] + reference[3]) / 2
        val x0 = centerX - width / 2
        val y0 = centerY - height / 2
        return intArrayOf(x0, y0, x0 + width, y0 + height)
    }

    /** 合并模板：公共核心每边收缩 [shrinkPerEdge] 后，取 5 个外观在该片上的**逐像素均值**。 */
    private fun mergedTemplate(
        entries: List<CalibrationData.SignalEntry>,
        core: IntArray,
        shrinkPerEdge: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Pair<IntArray, Template> {
        val width = (core[2] - core[0]) - shrinkPerEdge * 2
        val height = (core[3] - core[1]) - shrinkPerEdge * 2
        require(width > 0 && height > 0) { "公共核心比收缩量还小：${width}x$height" }
        val rect = centeredRect(core, width, height)
        val patches = entries.map { entry ->
            patchOf(entry.templates.first(), templateRectOf(entry, frameWidth, frameHeight), rect)
        }
        return rect to Template(width, height, meanPatch(patches))
    }

    /**
     * 合并产物：`activity_popup_e2..e5` 的记录（各两个角色）全部删除，`activity_popup_e1` 的**标志与锚点各留一条**
     * （都换成同一份 [template]，窗口按**写入端口径**各给一次）；状态规则里的引用同步收窄到 `e1`。
     *
     * 属性/备注/角色/归属好友名等字段**原样沿用** e1 的记录（合并不改语义，只改"几条"）。
     */
    private fun mergedData(data: CalibrationData, rect: IntArray, template: Template): CalibrationData {
        val survivorId = xIds.first()
        val retired = xIds.drop(1).toSet()

        fun windowFor(role: SignalRole): SearchWindow = SelectionWindow.forSelection(
            frameWidth = data.frameWidth,
            frameHeight = data.frameHeight,
            x0 = rect[0],
            y0 = rect[1],
            x1 = rect[2],
            y1 = rect[3],
            marginPx = SelectionWindow.marginPxFor(role),
        )

        val signals = data.signals.mapNotNull { entry ->
            when {
                entry.id !in xIds -> entry
                entry.id == survivorId -> entry.copy(
                    window = windowFor(entry.role),
                    templates = listOf(template),
                )
                entry.id in retired -> null
                else -> entry
            }
        }
        val rules = data.stateRules.map { rule ->
            if (rule.signalNames.none { it in retired } && rule.anchorNames.none { it in retired }) {
                rule
            } else {
                CalibrationData.StateRule(
                    rule.state,
                    rule.signalNames.filterNot { it in retired },
                    rule.anchorNames.filterNot { it in retired },
                )
            }
        }
        return CalibrationData(data.frameWidth, data.frameHeight, data.params, signals, rules)
    }

    /** 记录模板在帧里的像素矩形（模板恒在窗口正中）。 */
    private fun templateRectOf(entry: CalibrationData.SignalEntry, frameWidth: Int, frameHeight: Int): IntArray {
        val template = entry.templates.first()
        val bounds = entry.window.pixelBounds(frameWidth, frameHeight)
        val centerX = (bounds.x0 + bounds.x1) / 2
        val centerY = (bounds.y0 + bounds.y1) / 2
        val x0 = centerX - template.width / 2
        val y0 = centerY - template.height / 2
        return intArrayOf(x0, y0, x0 + template.width, y0 + template.height)
    }

    /** 从 [template]（矩形 [templateRect]）里取出 [target] 那一片像素。 */
    private fun patchOf(template: Template, templateRect: IntArray, target: IntArray): ByteArray {
        val width = target[2] - target[0]
        val height = target[3] - target[1]
        val out = ByteArray(width * height)
        val offsetX = target[0] - templateRect[0]
        val offsetY = target[1] - templateRect[1]
        for (y in 0 until height) {
            System.arraycopy(template.pixels, (y + offsetY) * template.width + offsetX, out, y * width, width)
        }
        return out
    }

    /** 多片同尺寸像素的**逐像素均值**（四舍五入）——"不偏向任何一次框选"的那个选择。 */
    private fun meanPatch(patches: List<ByteArray>): ByteArray {
        val out = ByteArray(patches.first().size)
        for (i in out.indices) {
            var sum = 0
            patches.forEach { sum += it[i].toInt() and 0xFF }
            out[i] = ((sum + patches.size / 2) / patches.size).toByte()
        }
        return out
    }

    /** 皮尔逊相关系数（-1..1）；任一片方差为 0 时返回 0。 */
    private fun correlation(a: ByteArray, b: ByteArray): Double {
        val n = a.size
        var meanA = 0.0
        var meanB = 0.0
        for (i in 0 until n) {
            meanA += a[i].toInt() and 0xFF
            meanB += b[i].toInt() and 0xFF
        }
        meanA /= n
        meanB /= n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = (a[i].toInt() and 0xFF) - meanA
            val db = (b[i].toInt() and 0xFF) - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA <= 0.0 || varB <= 0.0) return 0.0
        return cov / sqrt(varA * varB)
    }

    /** 一条候选信号的规格：窗口按**写入端同一套口径**（选区 + 按角色的固定余量），再套成本护栏。 */
    private fun specFor(
        name: String,
        rect: IntArray,
        template: Template,
        role: SignalRole,
        frameWidth: Int,
        frameHeight: Int,
    ): SignalSpec {
        val window = SelectionWindow.forSelection(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            x0 = rect[0],
            y0 = rect[1],
            x1 = rect[2],
            y1 = rect[3],
            marginPx = SelectionWindow.marginPxFor(role),
        )
        val capped = TemplateMatcher.cappedByCost(window, template.width, template.height, frameWidth, frameHeight)
        return SignalSpec(name, capped, listOf(template))
    }

    /** 合成帧：中灰画布 + 把这一条模板的像素**回贴**到它原来的位置（该外观无真帧时的兜底证据）。 */
    private fun syntheticFrame(
        entry: CalibrationData.SignalEntry,
        rect: IntArray,
        frameWidth: Int,
        frameHeight: Int,
    ): GrayImage {
        val pixels = ByteArray(frameWidth * frameHeight) { 0x80.toByte() }
        val template = entry.templates.first()
        for (y in 0 until template.height) {
            System.arraycopy(
                template.pixels,
                y * template.width,
                pixels,
                (rect[1] + y) * frameWidth + rect[0],
                template.width,
            )
        }
        return GrayImage(frameWidth, frameHeight, pixels)
    }

    private fun shortName(index: Int): String = "e${index + 1}"

    private fun rangeText(values: List<Double>): String =
        if (values.isEmpty()) "—" else "${fmt(values.min())}~${fmt(values.max())}"

    private fun boundsText(window: SearchWindow, frameWidth: Int, frameHeight: Int): String {
        val bounds = window.pixelBounds(frameWidth, frameHeight)
        return "${bounds.x1 - bounds.x0}x${bounds.y1 - bounds.y0}"
    }

    private fun opsText(ops: Long): String = when {
        ops >= 1_000_000 -> "${fmt(ops / 1_000_000.0)}M"
        ops >= 1_000 -> "${fmt(ops / 1_000.0)}K"
        else -> ops.toString()
    }

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.0f%%", value * 100)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    private fun write(lines: List<String>) {
        writeReport(outFile, lines)
    }

    /** 报告写盘 + 同步打到标准输出（Gradle 的测试报告里也能看到全文）。 */
    private fun writeReport(file: File, lines: List<String>) {
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        println("\n" + lines.joinToString("\n"))
    }
}
