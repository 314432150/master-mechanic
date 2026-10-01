package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.ReplayTool
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.Template
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * 「农场 / 好友农场**合并成一个状态**」的可行性先测量（2026-09-21，用户提出）。
 *
 * ⚠️ **类名是历史名**（2026-09-23 注）：这里的 `Identity` 指当年那套"农场归属判据"，而**那套东西
 * 已在 2026-09-23 按用户口径整块删除**（`FarmIdentity` / `identityAnchors` / `own_avatar` /
 * `friend_avatar` / `farm_name` 全没了）。本探针**没有随之作废** —— 它真正量的是**合并的前提**：
 * "两个农场能否共用同一条返回 / 好友入口锚点"，与 `FarmMergeProbeTest` 同源（后者量交叉命中）。
 *
 * 想法很实在：两个画面本质上是同一个场景，只有左上角「头像 + xxx的农场」不同；
 * 合并后能砍掉一半的标志与锚点标定（`farm_exit` / `friend_farm_exit`、`farm_friends` / `friend_farm_friends`）。
 *
 * 但合并有个**硬前提**必须先看清楚（第 9 步的验证会因此变弱）：
 * - 现在：两个状态分开 ⇒ 拜访失败（其实还在自己的农场）时，状态是"自己的农场"≠"好友的农场" → 验证失败 ✓
 * - 合并后：两个农场都判成"农场" ⇒ 拜访失败也会被当成"已到达好友的农场" ✗
 * ⇒ 只有在"能可靠区分**这是谁的农场**"时，合并才是安全的。
 *
 * 本探针量三件事：
 * 1. **现状区分得准不准**：`farm_e1` / `friend_farm_e1` 两类标志在两类帧上的交叉分数；
 * 2. **左上角带不带得动区分**：左上角区域的"同类帧相似度" vs "异类帧相似度"；
 * 3. **文字识别靠不靠谱**：那块区域的文字行高度（字太小就别指望 OCR）。
 */
class FarmIdentityProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work")
    private val artifactFile = File(workDir, "t2-2-artifact.txt")
    private val framesDir = File(workDir, "t2-2-frames")
    private val outFile = File(workDir, "out/t4-3-farm-identity-probe.txt")
    private val cropDir = File(workDir, "out/t4-3-crops")

    @Test
    fun evaluateMergingTheTwoFarms() {
        assumeTrue("需要 t2-2-artifact.txt 与 t2-2-frames/", artifactFile.isFile && framesDir.isDirectory)
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val markers = data.signals.filter { it.role == SignalRole.MARKER }
        val detector = SignalDetector(markers.map { SignalSpec(it.id, it.window, it.templates) }, data.params)

        val ownSpec = specOf(data, markers, UiState.FARM)
        val friendSpec = specOf(data, markers, UiState.FRIEND_FARM)
        assumeTrue("产物里没有农场 / 好友农场的标志", ownSpec != null && friendSpec != null)

        val frames = (framesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to ReplayTool.decodeGrayPng(it) }

        val own = frames.filter { detector.detectSignal(it.second, ownSpec!!).verdict == verdictMatched() }
        val friend = frames.filter { detector.detectSignal(it.second, friendSpec!!).verdict == verdictMatched() }

        val lines = mutableListOf<String>()
        lines += "# 农场 / 好友农场合并可行性（真机帧池）"
        lines += "- 帧池 ${frames.size} 帧：自己的农场 ${own.size} 帧、好友的农场 ${friend.size} 帧（按产物标志判定）"
        lines += "- 标志：${ownSpec!!.name}（自己） / ${friendSpec!!.name}（好友）；命中线 ${data.params.matchThreshold}"
        lines += ""

        // ① 交叉分数：现状两个状态区分得准不准
        lines += "## 1. 现状：两类标志在两类帧上的分数（交叉越高越容易混）"
        lines += "- 自己的农场帧："
        own.take(6).forEach { (name, image) ->
            lines += "  $name：自己 ${fmt(score(detector, image, ownSpec))} / 好友 ${fmt(score(detector, image, friendSpec))}"
        }
        lines += "- 好友的农场帧："
        friend.take(6).forEach { (name, image) ->
            lines += "  $name：自己 ${fmt(score(detector, image, ownSpec))} / 好友 ${fmt(score(detector, image, friendSpec))}"
        }
        lines += ""

        // ② 左上角：同类 vs 异类相似度
        val first = own.firstOrNull() ?: friend.firstOrNull()
        if (first == null) {
            write(lines)
            return
        }
        val area = Area(0, 0, first.second.width, first.second.height)
        val region = PixelRegion(
            x0 = area.x0,
            // 左上角：画面区宽的 45%、高的 16%（头像 + 「xxx的农场」那一带）
            y0 = area.y0 + (area.height * 0.02).toInt(),
            width = (area.width * 0.45).toInt(),
            height = (area.height * 0.16).toInt(),
        )
        lines += "## 2. 左上角整块区域 $region 的相似度（同类 vs 异类；含大量相同背景，会被稀释）"
        val ownTemplate = crop(first.second, region)
        val friendFirst = friend.firstOrNull()
        if (friendFirst != null) {
            val friendTemplate = crop(friendFirst.second, region)
            lines += "- 自己↔自己：${own.drop(1).take(4).joinToString(", ") { fmt(ncc(ownTemplate, crop(it.second, region))) }}"
            lines += "- 好友↔好友：${friend.drop(1).take(4).joinToString(", ") { fmt(ncc(friendTemplate, crop(it.second, region))) }}"
            lines += "- **自己↔好友（异类）**：${friend.take(4).joinToString(", ") { fmt(ncc(ownTemplate, crop(it.second, region))) }}"
        }
        lines += ""

        // ③ 文字行：**逐行对比度**定位（不假设文字是亮还是暗 —— 上一版用 Otsu 假设"字是亮的"，
        //    结果整块浅色面板都被当成字，量不出字高）
        val contrast = contrastRows(first.second, region)
        val flags = IntArray(contrast.size) { if (contrast[it] >= contrast.max() * 0.45) 1 else 0 }
        val bands = runs(flags, 1, gap = 2)
        lines += "## 3. 左上角的文字行（逐行对比度定位）"
        bands.take(8).forEachIndexed { index, band ->
            lines += "  [$index] y ${region.y0 + band.first}..${region.y0 + band.last}（高 ${band.last - band.first + 1}px）"
        }
        lines += "  → 参考：服务器列表名字行 ≈15px；字越高，文字识别越有把握"

        // ④ 只比"文字那一条"：同类 vs 异类（排除背景稀释）
        val band = bands.maxByOrNull { it.last - it.first }
        if (band != null && friendFirst != null) {
            val textRegion = PixelRegion(region.x0, region.y0 + band.first, region.width, band.last - band.first + 1)
            val ownText = crop(own.firstOrNull()?.second ?: first.second, textRegion)
            val friendText = crop(friendFirst.second, textRegion)
            lines += ""
            lines += "## 4. 只比「昵称的农场」那一条 $textRegion"
            lines += "- 自己↔自己：${own.drop(1).take(4).joinToString(", ") { fmt(ncc(ownText, crop(it.second, textRegion))) }}"
            lines += "- 好友↔好友：${friend.drop(1).take(4).joinToString(", ") { fmt(ncc(friendText, crop(it.second, textRegion))) }}"
            lines += "- **自己↔好友**：${friend.take(4).joinToString(", ") { fmt(ncc(ownText, crop(it.second, textRegion))) }}"
            lines += "  → 同类高、异类低 ⇒ 这条字**稳定且能区分**（两人的昵称不同）；"
            lines += "    若同类也不高 ⇒ 字太小 / 有动画，文字识别与模板匹配都悬"

            // ⑤ 文字的实际宽度：昵称长短不同 ⇒ 宽度不同（对 OCR 无妨，对"整条当模板"有影响）
            val ownCols = textSpan(own.firstOrNull()?.second ?: first.second, textRegion)
            val friendCols = textSpan(friendFirst.second, textRegion)
            lines += ""
            lines += "## 5. 那条字在两类帧上的横向跨度"
            lines += "- 自己的农场：${ownCols}"
            lines += "- 好友的农场：${friendCols}"
            lines += "  → 跨度不同 = 昵称长短不同；**整条当模板会因此失配**，但文字识别不受影响"
        }

        write(lines)
    }

    /**
     * 裁出「好友面板的头像列」与「好友农场的左上角头像」，供目视比对：
     * **两者的尺寸 / 样式是否一致** —— 这决定"第 9 步能不能只用列表里裁下的头像，
     * 进农场后精确确认'就是这一位好友'"（不需要用户为每位好友补框）。
     */
    @Test
    fun dumpFriendAvatarVersusFarmAvatar() {
        assumeTrue("需要 t2-2-artifact.txt 与 t2-2-frames/", artifactFile.isFile && framesDir.isDirectory)
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val markers = data.signals.filter { it.role == SignalRole.MARKER }
        val detector = SignalDetector(markers.map { SignalSpec(it.id, it.window, it.templates) }, data.params)
        val frames = (framesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to ReplayTool.decodeGrayPng(it) }

        val panelSpec = specOf(data, markers, UiState.FRIEND_LIST)
        val friendSpec = specOf(data, markers, UiState.FRIEND_FARM)!!
        val panel = panelSpec?.let { s -> frames.firstOrNull { detector.detectSignal(it.second, s).verdict == verdictMatched() } }
        val farm = frames.first { detector.detectSignal(it.second, friendSpec).verdict == verdictMatched() }
        assumeTrue("帧池里没有好友面板帧", panel != null)

        val area = Area(0, 0, farm.second.width, farm.second.height)
        cropDir.mkdirs()
        // ① 好友面板：列表左侧那一列（头像所在）
        dumpCrop(
            "friend-panel-left",
            panel!!.second,
            PixelRegion(
                area.x0 + (area.width * 0.20).toInt(),
                area.y0 + (area.height * 0.20).toInt(),
                (area.width * 0.28).toInt(),
                (area.height * 0.60).toInt(),
            ),
            scale = 2,
        )
        // ② 好友农场：左上角头像那块
        dumpCrop(
            "friend-farm-avatar",
            farm.second,
            PixelRegion(area.x0, area.y0, (area.width * 0.20).toInt(), (area.height * 0.10).toInt()),
            scale = 3,
        )
    }

    private fun dumpCrop(name: String, image: GrayImage, region: PixelRegion, scale: Int) {
        val pixels = ByteArray(region.width * region.height)
        for (y in 0 until region.height) {
            System.arraycopy(image.pixels, (region.y0 + y) * image.width + region.x0, pixels, y * region.width, region.width)
        }
        val big = ByteArray(region.width * scale * region.height * scale)
        for (y in 0 until region.height * scale) {
            for (x in 0 until region.width * scale) {
                big[y * region.width * scale + x] = pixels[(y / scale) * region.width + x / scale]
            }
        }
        ReplayTool.writeGrayPng(
            GrayImage(region.width * scale, region.height * scale, big),
            File(cropDir, "$name-x$scale.png"),
        )
    }

    /**
     * 把「左上角名字条」与「右上角按钮区」裁剪放大存盘（供目视判读：写了什么、字多大、
     * 「回家」按钮长什么样）。区域按画面区**相对位置**取（无设备常量）。
     */
    @Test
    fun dumpFarmTopCrops() {
        assumeTrue("需要 t2-2-artifact.txt 与 t2-2-frames/", artifactFile.isFile && framesDir.isDirectory)
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val markers = data.signals.filter { it.role == SignalRole.MARKER }
        val detector = SignalDetector(markers.map { SignalSpec(it.id, it.window, it.templates) }, data.params)
        val ownSpec = specOf(data, markers, UiState.FARM)!!
        val friendSpec = specOf(data, markers, UiState.FRIEND_FARM)!!
        val frames = (framesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to ReplayTool.decodeGrayPng(it) }
        val own = frames.first { detector.detectSignal(it.second, ownSpec).verdict == verdictMatched() }
        val friend = frames.first { detector.detectSignal(it.second, friendSpec).verdict == verdictMatched() }
        val area = Area(0, 0, own.second.width, own.second.height)

        val crops = listOf(
            // 名字条（画面区顶部左侧 55% 宽 × 12% 高）
            Triple("own-name", own.second, PixelRegion(area.x0, area.y0, (area.width * 0.55).toInt(), (area.height * 0.12).toInt())),
            Triple("friend-name", friend.second, PixelRegion(area.x0, area.y0, (area.width * 0.55).toInt(), (area.height * 0.12).toInt())),
            // 右上角按钮区（画面区右侧 30% 宽 × 12% 高；好友农场应有「回家」）
            Triple(
                "own-buttons",
                own.second,
                PixelRegion(area.x1 - (area.width * 0.30).toInt(), area.y0, (area.width * 0.30).toInt(), (area.height * 0.12).toInt()),
            ),
            Triple(
                "friend-buttons",
                friend.second,
                PixelRegion(area.x1 - (area.width * 0.30).toInt(), area.y0, (area.width * 0.30).toInt(), (area.height * 0.12).toInt()),
            ),
        )
        cropDir.mkdirs()
        crops.forEach { (name, image, region) ->
            val scaled = PixelRegion(
                x0 = region.x0,
                y0 = region.y0,
                width = region.width,
                height = region.height,
            )
            val pixels = ByteArray(scaled.width * scaled.height)
            for (y in 0 until scaled.height) {
                System.arraycopy(
                    image.pixels,
                    (scaled.y0 + y) * image.width + scaled.x0,
                    pixels,
                    y * scaled.width,
                    scaled.width,
                )
            }
            // 放大 3 倍（最近邻）便于目视
            val big = ByteArray(scaled.width * 3 * scaled.height * 3)
            for (y in 0 until scaled.height * 3) {
                for (x in 0 until scaled.width * 3) {
                    big[y * scaled.width * 3 + x] = pixels[(y / 3) * scaled.width + x / 3]
                }
            }
            ReplayTool.writeGrayPng(
                com.example.mastermechanic.recognition.GrayImage(scaled.width * 3, scaled.height * 3, big),
                File(cropDir, "$name-x3.png"),
            )
        }
    }

    // ---------------------------------------------------------------- 小工具

    private data class PixelRegion(val x0: Int, val y0: Int, val width: Int, val height: Int)

    private fun specOf(
        data: com.example.mastermechanic.calibration.CalibrationData,
        markers: List<com.example.mastermechanic.calibration.CalibrationData.SignalEntry>,
        state: UiState,
    ): SignalSpec? {
        val name = data.stateRules.firstOrNull { it.state == state }?.signalNames?.firstOrNull() ?: return null
        val entry = markers.firstOrNull { it.id == name } ?: return null
        return SignalSpec(entry.id, entry.window, entry.templates)
    }

    private fun verdictMatched() = com.example.mastermechanic.recognition.Verdict.MATCHED

    /** 与生产同一条判定路径（同一个 detector、同一套参数）。 */
    private fun score(detector: SignalDetector, image: GrayImage, spec: SignalSpec): Double =
        detector.detectSignal(image, spec).best?.score ?: 0.0

    private fun crop(image: GrayImage, region: PixelRegion): Template {
        val pixels = ByteArray(region.width * region.height)
        for (y in 0 until region.height) {
            System.arraycopy(image.pixels, (region.y0 + y) * image.width + region.x0, pixels, y * region.width, region.width)
        }
        return Template(region.width, region.height, pixels)
    }

    /** 两块同尺寸区域的归一化互相关（亮度/对比度归一化，与识别层同一口径）。 */
    private fun ncc(a: Template, b: Template): Double {
        val size = a.width * a.height
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until size) {
            sumA += a.pixels[i].toInt() and 0xFF
            sumB += b.pixels[i].toInt() and 0xFF
        }
        val meanA = sumA / size
        val meanB = sumB / size
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in 0 until size) {
            val va = (a.pixels[i].toInt() and 0xFF) - meanA
            val vb = (b.pixels[i].toInt() and 0xFF) - meanB
            num += va * vb
            denA += va * va
            denB += vb * vb
        }
        if (denA <= 0.0 || denB <= 0.0) return 0.0
        return num / sqrt(denA * denB)
    }

    private fun otsu(image: GrayImage, region: PixelRegion): Int {
        val hist = IntArray(256)
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                hist[image.pixels[(region.y0 + y) * image.width + region.x0 + x].toInt() and 0xFF]++
            }
        }
        val total = region.width * region.height
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

    /**
     * 逐行**对比度**（该行内最亮与最暗之差）：文字行的对比度显著高于纯背景行。
     *
     * 为什么不按"亮 = 字"来找：左上角是浅色面板时整块都亮（上一版 Otsu 就是这么翻车的），
     * 而"有没有笔画"跟明暗方向无关，只跟**反差**有关。
     */
    private fun contrastRows(image: GrayImage, region: PixelRegion): DoubleArray =
        DoubleArray(region.height) { row ->
            var min = 255
            var max = 0
            for (x in 0 until region.width) {
                val v = image.pixels[(region.y0 + row) * image.width + region.x0 + x].toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }
            (max - min).toDouble()
        }

    /** 一条文字带里**有内容的横向跨度**（逐列对比度过半 → 认为有笔画），用来看昵称长短。 */
    private fun textSpan(image: GrayImage, region: PixelRegion): String {
        val cols = DoubleArray(region.width) { col ->
            var min = 255
            var max = 0
            for (y in 0 until region.height) {
                val v = image.pixels[(region.y0 + y) * image.width + region.x0 + col].toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }
            (max - min).toDouble()
        }
        val peak = cols.max()
        val on = cols.indices.filter { cols[it] >= peak * 0.5 }
        if (on.isEmpty()) return "（没检出笔画）"
        return "x ${on.first()}..${on.last()}（宽 ${on.last() - on.first() + 1}px，峰值对比度 ${fmt(peak)}）"
    }

    private fun rowCounts(image: GrayImage, region: PixelRegion, threshold: Int): IntArray =
        IntArray(region.height) { row ->
            var count = 0
            for (x in 0 until region.width) {
                if ((image.pixels[(region.y0 + row) * image.width + region.x0 + x].toInt() and 0xFF) >= threshold) count++
            }
            count
        }

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

    private fun write(lines: List<String>) {
        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun fmt(value: Double): String = String.format("%.4f", value)
}
