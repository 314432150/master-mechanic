package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.ReplayTool
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.Template
import com.example.mastermechanic.recognition.TemplateMatcher
import com.example.mastermechanic.recognition.Verdict
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * T4-3「名称定位」的**先测量**（2026-09-21，用户口径："先在帧池里评估"）。
 *
 * 要回答的问题：第 5 / 9 步的"把名字变成屏幕上的位置"，用**名称样本模板化**还是**文字识别**？
 * 而真机情况是：服务器列表**左右两列**、每屏约 12 行、十几个小号**要滚动**才看得全。
 *
 * 本探针只测量、不下结论，全部输入取自**真机帧池**（`t2-2-frames/` 76 帧）与**设备产物**
 * （`t2-2-artifact.txt`），不含任何设备常量：画面由产物里的标志自己判出来，区域由**产物里那条
 * 标志的命中位置**推出（面板左缘 ≈ 标题左缘），阈值取区域亮度的分位数（自适应）。
 *
 * 量四件事：
 * 1. **文字行几何**：每屏几行、行距、名字行字高（= 文字识别能不能读的前提）；
 * 2. **同一段文字在左右两列的渲染一致性**：拿 A 列第 1 行的头两个字当模板，去 B 列同一行找
 *    —— 分数接近 1 说明"同一个名字换个位置照样能匹配"（模板路线的命门）；
 * 3. **名字之间的区分度**：拿整块名字当模板在整片列表里找，看**次好分数**有多高
 *    —— 这直接对应 §5-3 ③「一堆外形高度一致的目标里挑一个」的干扰强度；
 * 4. **跨帧稳定性**与**负样本对照**（在非该画面的帧上匹配，分数应明显更低）。
 *
 * 输出：`build/replay-work/out/t4-3-name-locate-probe.txt` + 裁剪图 `out/t4-3-crops/`（供目视核对）。
 */
class NameLocateProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "t2-2-frames")
    private val outFile = File(workDir, "out/t4-3-name-locate-probe.txt")
    private val cropDir = File(workDir, "out/t4-3-crops")

    @Test
    fun evaluateNameLocatingRoutesOnDeviceFrames() {
        assumeTrue(
            "需要 build/replay-work/t2-2-artifact.txt 与 t2-2-frames/（真机帧池）",
            artifactFile.isFile && framesDir.isDirectory,
        )
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val frames = (framesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
        assumeTrue("帧池为空", frames.isNotEmpty())

        val markers = data.signals.filter { it.role == SignalRole.MARKER }
        val detector = SignalDetector(markers.map { SignalSpec(it.id, it.window, it.templates) }, data.params)
        val serverSignalName = data.stateRules.first { it.state == UiState.SERVER_SELECT }.signalNames.first()
        val serverSpec = markers.first { it.id == serverSignalName }
            .let { SignalSpec(it.id, it.window, it.templates) }

        val decoded = frames.map { it.name to ReplayTool.decodeGrayPng(it) }
        val listFrames = decoded.filter { detector.detectSignal(it.second, serverSpec).verdict == Verdict.MATCHED }
        assumeTrue("帧池里没有「服务器列表」帧（产物标志判不出来）", listFrames.isNotEmpty())

        val lines = mutableListOf<String>()
        lines += "# T4-3 名称定位先测量（真机帧池）"
        lines += "- 产物：${artifactFile.name}（标定帧 ${data.frameWidth}x${data.frameHeight}）"
        lines += "- 帧池：${frames.size} 帧，其中「服务器列表」${listFrames.size} 帧（按产物标志判定，不靠帧名）"
        lines += "- 命中线 ${data.params.matchThreshold} / 差距线 ${data.params.ambiguityMargin} / 峰间距 ${data.params.peakMinDistance}"
        lines += ""

        val (firstName, first) = listFrames.first()
        // T4-6：帧与标定同几何 ⇒ 画面区恒等于整帧（原先由已删除的 CanvasGeometry.contentArea 提供）
        val area = Area(0, 0, first.width, first.height)
        val titlePeak = requireNotNull(detector.detectSignal(first, serverSpec).best) {
            "标题标志应该命中（否则拿不到面板左缘）"
        }
        val titleHeight = serverSpec.templates.first().height
        lines += "## 0. 定位基准（由产物标志的命中位置推出，无硬编码坐标）"
        lines += "- 首帧：$firstName；画面区：(${area.x0}, ${area.y0})-(${area.x1}, ${area.y1})"
        lines += "- 标题标志「$serverSignalName」命中：分数 ${fmt(titlePeak.score)} @ (${titlePeak.x}, ${titlePeak.y})，模板高 $titleHeight"
        lines += ""

        // 列表区域 = 标题之下 → 画面区底部；左缘取标题左缘（面板左缘）
        val regionX0 = titlePeak.x
        val regionY0 = titlePeak.y + titleHeight + 4
        val regionX1 = area.x1
        val regionY1 = area.y1
        val threshold = otsuThreshold(first, regionX0, regionY0, regionX1, regionY1)
        lines += "## 1. 文字行几何（Otsu 阈值 $threshold，自适应）"
        val rowCounts = rowCounts(first, regionX0, regionY0, regionX1, regionY1, threshold)
        val rowBands = runs(rowCounts, (rowCounts.max() * 0.2).toInt(), gap = 2)
        lines += "- 列表区域：($regionX0, $regionY0)-($regionX1, $regionY1)"
        lines += "- 检出文字行簇 ${rowBands.size} 个（每行含「名字行 + 信息行」两小段）："
        rowBands.take(20).forEachIndexed { index, band ->
            val sub = rowCounts.copyOfRange(band.first, band.last + 1)
            val subBands = runs(sub, (sub.max() * 0.5).toInt(), gap = 1)
            val text = subBands.joinToString(" + ") { "${it.last - it.first + 1}px" }
            lines += "  [$index] y ${band.first + regionY0}..${band.last + regionY0}" +
                "（高 ${band.last - band.first + 1}px；内部文字段高：$text）"
        }
        if (rowBands.size >= 2) {
            val pitch = rowBands.zipWithNext { a, b -> b.first - a.first }
            lines += "- 行距：${pitch.joinToString(", ")}（中位 ${pitch.sorted()[pitch.size / 2]}px）"
        }
        lines += ""
        if (rowBands.isEmpty()) {
            writeResult(lines)
            return
        }

        // 首行的名字行（row 簇里的第一小段 = 名字行；下面的小段是角色信息行）
        val firstBand = rowBands.first()
        val firstBandRows = rowCounts.copyOfRange(firstBand.first, firstBand.last + 1)
        val nameSub = runs(firstBandRows, (firstBandRows.max() * 0.5).toInt(), gap = 1).first()
        val nameBandY0 = firstBand.first + nameSub.first + regionY0
        val nameBandY1 = firstBand.first + nameSub.last + 1 + regionY0
        val nameHeight = nameBandY1 - nameBandY0
        lines += "- 首行「名字行」：y $nameBandY0..${nameBandY1 - 1}（**字高 ≈ $nameHeight px**）"
        lines += ""

        val colProfile = colCounts(first, regionX0, regionX1, nameBandY0, nameBandY1, threshold)
        val clusters = runs(colProfile, (colProfile.max() * 0.05).toInt(), gap = 24)
        lines += "## 2. 列切分（首行名字行的 x 簇，间隙容差 24px）"
        clusters.forEachIndexed { index, c ->
            lines += "  [$index] x ${c.first + regionX0}..${c.last + regionX0}" +
                "（宽 ${c.last - c.first + 1}px）"
        }
        lines += ""

        val left = clusters.firstOrNull()
        // 第 2 簇 = 右列的文字（更右边的簇是行尾的图标，不是文字）
        val right = clusters.getOrNull(1)
        if (left == null) {
            writeResult(lines)
            return
        }

        // 头两个字符 = 模板（两列都有「微信」/「QQ」前缀，同一段文字，可用它量跨列一致性）
        val charRuns = runs(colProfile.copyOfRange(left.first, left.last + 1), 1, gap = 2)
        val prefixEnd = charRuns.getOrNull(1)?.last ?: charRuns.first().last
        val prefixWidth = prefixEnd - charRuns.first().first + 1
        val prefixTemplate = template(first, left.first + regionX0, nameBandY0, prefixWidth, nameBandY1 - nameBandY0)
        lines += "## 3. 同一段文字在左右两列的渲染一致性（模板 = 名字行头 $prefixWidth px）"
        lines += "- 模板：${prefixTemplate.width}x${prefixTemplate.height} @ x ${left.first + regionX0}, y $nameBandY0"
        lines += "  模板右缘：列A 起点 ${left.first + regionX0}；列B 起点 ${right?.let { it.first + regionX0 } ?: -1}"
        // 在**整片列表**里搜这段前缀（不只本行）：两列垂直错开，所以右列的名字行在别的 y 上
        val rowWindow = SearchWindow(
            regionX0.toDouble() / first.width,
            regionY0.toDouble() / first.height,
            regionX1.toDouble() / first.width,
            regionY1.toDouble() / first.height,
        )
        val peaks = TemplateMatcher.findPeaks(first, prefixTemplate, rowWindow, data.params)
            .sortedByDescending { it.score }
            .let {
                TemplateMatcher.suppressPeaks(
                    it,
                    TemplateMatcher.effectiveSuppressRadius(
                        data.params, prefixTemplate.width, prefixTemplate.height,
                    ),
                )
            }
        lines += "- 全列表匹配结果（前 8 个峰；同一段文字应在本列各行的同一 x、以及右列各行的另一 x 上重复出现）："
        peaks.take(8).forEach { lines += "  分数 ${fmt(it.score)} @ (${it.x}, ${it.y})" }
        lines += "  → 若右列（x ≈ ${clusters.getOrNull(1)?.let { it.first + regionX0 } ?: -1} 一带）也出现高分，"
        lines += "    说明「同一个名字换个位置照样能匹配」成立（模板路线的命门）"
        lines += ""

        // 整块名字（含名字行）在整片列表里的区分度：次好分数越低，越不容易挑错行
        val wideWidth = minOf(left.last - left.first + 1, 160)
        val wideTemplate = template(first, left.first + regionX0, nameBandY0, wideWidth, nameBandY1 - nameBandY0)
        val listWindow = SearchWindow(
            regionX0.toDouble() / first.width,
            regionY0.toDouble() / first.height,
            regionX1.toDouble() / first.width,
            regionY1.toDouble() / first.height,
        )
        lines += "## 4. 名字之间的区分度（模板 = 整块名字 ${wideTemplate.width}x${wideTemplate.height}）"
        val widePeaks = TemplateMatcher.findPeaks(first, wideTemplate, listWindow, data.params)
            .sortedByDescending { it.score }
            .let {
                TemplateMatcher.suppressPeaks(
                    it,
                    TemplateMatcher.effectiveSuppressRadius(
                        data.params, wideTemplate.width, wideTemplate.height,
                    ),
                )
            }
        widePeaks.take(4).forEach { lines += "  分数 ${fmt(it.score)} @ (${it.x}, ${it.y})" }
        lines += "  → 第 1 名是它自己；第 2 名分数就是「挑错一行」的风险（越低越好；命中线 ${data.params.matchThreshold}）"
        lines += ""

        // 跨帧稳定性：同一模板在其余服务器列表帧上的最佳分数
        lines += "## 5. 跨帧稳定性（其余 ${listFrames.size - 1} 帧服务器列表）"
        listFrames.drop(1).forEach { (frameName, image) ->
            val p = TemplateMatcher.findPeaks(image, wideTemplate, listWindow, data.params)
                .maxByOrNull { it.score }
            lines += "  $frameName：最高 ${p?.let { fmt(it.score) } ?: "-"} @ (${p?.x}, ${p?.y})"
        }
        lines += ""

        // 负样本对照：同一模板在非「服务器列表」的帧上（应明显更低）
        lines += "## 6. 负样本对照（非服务器列表的帧，前 8 帧）"
        decoded.filter { (name, image) -> detector.detectSignal(image, serverSpec).verdict != Verdict.MATCHED }
            .take(8)
            .forEach { (frameName, image) ->
                val p = TemplateMatcher.findPeaks(image, wideTemplate, listWindow, data.params)
                    .maxByOrNull { it.score }
                lines += "  $frameName：最高 ${p?.let { fmt(it.score) } ?: "-"}"
            }

        // 7. 位置微移的敏感度：**滚动后行会落在非整数 y、换列会落在另一个 x**，
        //    所以"同一个名字挪一点位置还像不像"是模板路线的生死线
        lines += "## 7. 位置微移的敏感度（命中线 ${data.params.matchThreshold}；滚动后行会落在非整数位置）"
        val baseX = left.first + regionX0
        val shapes = listOf(
            "只名字行 85x$nameHeight" to Pair(85, nameHeight),
            "名字行+信息行 85x${nameHeight * 3}" to Pair(85, nameHeight * 3),
            "整行名字 355x$nameHeight" to Pair(355, nameHeight),
            "整行两块 355x${nameHeight * 3}" to Pair(355, nameHeight * 3),
        )
        shapes.forEach { (label, size) ->
            val block = template(first, baseX, nameBandY0, size.first, size.second)
            val scores = listOf(0, 1, -1, 2).joinToString("  ") { dy ->
                "${if (dy >= 0) "+$dy" else dy}px=${fmt(ncc(first, block, baseX, nameBandY0 + dy))}"
            }
            val lateral = (1..2).joinToString("  ") { dx ->
                "+${dx}px=${fmt(ncc(first, block, baseX + dx, nameBandY0))}"
            }
            lines += "  $label｜纵向：$scores｜横向：$lateral"
        }
        lines += "  → 微移后若掉到命中线以下，滚动 / 换列就会「找不到」——这是模板路线最大的风险"
        lines += ""

        // 裁剪图：两列首行 + 模板放大 4 倍（供目视判断"字够不够清楚"）
        writeCrop(first, left.first + regionX0, nameBandY0, minOf(left.last - left.first + 1, 260), nameBandY1 - nameBandY0, "colA-row1", scale = 4)
        right?.let {
            writeCrop(first, it.first + regionX0, nameBandY0, minOf(it.last - it.first + 1, 260), nameBandY1 - nameBandY0, "colB-row1", scale = 4)
        }
        writeCrop(first, left.first + regionX0, nameBandY0, prefixTemplate.width, prefixTemplate.height, "prefix", scale = 8)

        writeResult(lines)
    }

    /**
     * Otsu 阈值：把区域内亮度直方图最优二分成「底色 / 文字」两团（自适应、无设备常量）。
     *
     * 用分位数（上一版取 92 分位）会只切到笔画最亮的核、把字高量小一半；Otsu 按两团方差最大化选阈值，
     * 对"深底浅字"这种双峰分布最合适。
     */
    private fun otsuThreshold(image: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val hist = IntArray(256)
        for (y in y0 until y1) {
            for (x in x0 until x1) hist[image.pixels[y * image.width + x].toInt() and 0xFF]++
        }
        val total = (x1 - x0) * (y1 - y0)
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 0
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val meanB = sumB / wB
            val meanF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (meanB - meanF) * (meanB - meanF)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return threshold
    }

    /** 每行的亮像素计数。 */
    private fun rowCounts(image: GrayImage, x0: Int, y0: Int, x1: Int, y1: Int, threshold: Int): IntArray =
        IntArray(y1 - y0) { row ->
            var count = 0
            for (x in x0 until x1) {
                if ((image.pixels[(row + y0) * image.width + x].toInt() and 0xFF) >= threshold) count++
            }
            count
        }

    /** 每列的亮像素计数。 */
    private fun colCounts(image: GrayImage, x0: Int, x1: Int, y0: Int, y1: Int, threshold: Int): IntArray =
        IntArray(x1 - x0) { col ->
            var count = 0
            for (y in y0 until y1) {
                if ((image.pixels[y * image.width + col + x0].toInt() and 0xFF) >= threshold) count++
            }
            count
        }

    /** 计数序列里超过 [min] 的连续段；相邻段空隙 ≤ [gap] 时合并（字与字之间的小空隙）。 */
    private fun runs(counts: IntArray, min: Int, gap: Int): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = -1
        for (i in counts.indices) {
            val on = counts[i] >= min && counts[i] > 0
            if (on && start < 0) {
                start = i
            } else if (!on && start >= 0) {
                if (result.isNotEmpty() && i - result.last().last - 1 <= gap) {
                    result[result.size - 1] = result.last().first..(i - 1)
                } else {
                    result += start until i
                }
                start = -1
            }
        }
        if (start >= 0) result += start until counts.size
        return result
    }

    private fun template(image: GrayImage, x0: Int, y0: Int, width: Int, height: Int): Template {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(image.pixels, (y + y0) * image.width + x0, pixels, y * width, width)
        }
        return Template(width, height, pixels)
    }

    private fun writeCrop(image: GrayImage, x0: Int, y0: Int, width: Int, height: Int, name: String, scale: Int) {
        if (width <= 0 || height <= 0) return
        val out = ByteArray(width * scale * height * scale)
        for (y in 0 until height * scale) {
            for (x in 0 until width * scale) {
                out[y * width * scale + x] = image.pixels[(y0 + y / scale) * image.width + x0 + x / scale]
            }
        }
        ReplayTool.writeGrayPng(GrayImage(width * scale, height * scale, out), File(cropDir, "$name-x$scale.png"))
    }

    /**
     * 归一化互相关（NCC）在**指定位置**的取值：用于量"模板挪 1~2 像素还像不像"
     * （滚动 / 换列时行会落在非整数位置，这是模板路线的生死线）。
     */
    private fun ncc(image: GrayImage, template: Template, x0: Int, y0: Int): Double {
        val width = template.width
        val height = template.height
        val size = width * height
        var sumT = 0.0
        for (i in 0 until size) sumT += template.pixels[i].toInt() and 0xFF
        val meanT = sumT / size
        var sumI = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) sumI += image.pixels[(y0 + y) * image.width + x0 + x].toInt() and 0xFF
        }
        val meanI = sumI / size
        var num = 0.0
        var denT = 0.0
        var denI = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = (template.pixels[y * width + x].toInt() and 0xFF) - meanT
                val v = (image.pixels[(y0 + y) * image.width + x0 + x].toInt() and 0xFF) - meanI
                num += t * v
                denT += t * t
                denI += v * v
            }
        }
        if (denT <= 0.0 || denI <= 0.0) return 0.0
        return num / sqrt(denT * denI)
    }

    private fun writeResult(lines: List<String>) {
        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        lines.forEach { println(it) }
    }

    private fun fmt(value: Double): String = String.format("%.4f", value)
}
