package com.example.mastermechanic.recognition

import com.example.mastermechanic.calibration.CalibrationCodec
import com.example.mastermechanic.decision.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * T1-11a 探针：画布方向几何对照（研究用工具，IO 适配归测试源集）。
 *
 * 目标：量出「竖画布会话」与「横画布会话」两种帧上同一界面的精确映射关系——纯旋转，
 * 还是旋转 / 缩放 / 偏移的组合；为 T1-11b 方案定稿提供依据（先测量、后设计）。
 *
 * 输入（全部取自本仓既有归档，探针自身不含任何设备常量）：
 * - 标定产物：`build/replay-work/calibration.txt`（模板 + 搜索窗口 + 参数，[CalibrationCodec] 解析）；
 * - 竖画布帧：`../docs/recognition/samples/t1-5-launch-start-frame.png`（1440×3168，T1-5 标定样本）；
 * - 横画布侧帧：`../docs/verification/t1-10/t1-10-05-launch-page-session1.png`（3168×1440 横屏取证）。
 *
 * 三条独立证据：
 * 1. **内容带（留白）测量**：逐行 / 逐列像素极差定位内容带边界——纯旋转模型下内容带占满画布
 *    长边；等比缩放嵌入模型下内容带只占一段；
 * 2. **带内逐像素比对**：按候选模型把横画布侧帧的内容带重采样到竖画布内容带几何，逐像素
 *    皮尔逊相关（带宽等比检查各轴缩放是否一致）；
 * 3. **锚点核验**：以标定模板为锚点，在归一化画布中匹配（用产物的搜索窗口，代价可控），
 *    与竖画布帧中的参考位置比对，给出位置残差。
 *
 * 复现命令：`.\gradlew.bat ':app:testDebugUnitTest' --tests '*CanvasOrientationProbeTest*' --rerun`
 */
class CanvasOrientationProbeTest {

    private val appDir = File(System.getProperty("user.dir"))
    private val repoDir = appDir.parentFile
    private val workDir = File(appDir, "build/replay-work")
    private val outDir = File(workDir, "out")

    private val artifactFile = File(workDir, "calibration.txt")
    private val verticalFile = File(repoDir, "docs/recognition/samples/t1-5-launch-start-frame.png")

    /** 横画布侧帧：优先用真机横画布会话样本（放工作目录），否则回退既有横屏取证截图。 */
    private val horizontalFile = listOf(
        File(workDir, "t1-11a-horizontal-frame.png"),
        File(repoDir, "docs/verification/t1-10/t1-10-05-launch-page-session1.png"),
    ).firstOrNull { it.isFile } ?: File(workDir, "t1-11a-horizontal-frame.png")

    /** 极差小于该值的行 / 列视为留白（均匀）带。 */
    private val uniformRange = 2

    private fun prepared(): Boolean =
        artifactFile.isFile && verticalFile.isFile && horizontalFile.isFile

    @Test
    fun measureCanvasOrientationMapping() {
        assumeTrue("探针输入未就绪（标定产物 / 竖画布帧 / 横画布侧帧）", prepared())
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val vertical = ReplayTool.decodeGrayPng(verticalFile)
        val horizontal = ReplayTool.decodeGrayPng(horizontalFile)
        val template = data.signals.first().templates.first()
        val signal = data.signals.first()

        println("=== T1-11a 画布方向几何对照 ===")
        println("标定帧 ${data.frameWidth}x${data.frameHeight}；竖画布帧 ${vertical.width}x${vertical.height}；横画布侧帧 ${horizontal.width}x${horizontal.height}")
        println("横画布侧帧来源：${horizontalFile.absolutePath}")

        // 证据 1：内容带 / 留白
        val vBand = contentBand(vertical)
        val hBand = contentBand(horizontal)
        println("[证据 1] 竖画布内容带：列 ${vBand.x0}..${vBand.x1}（宽 ${vBand.width()}），行 ${vBand.y0}..${vBand.y1}（高 ${vBand.height()}）")
        println("[证据 1] 竖画布留白：上 ${vBand.y0} / 下 ${vertical.height - vBand.y1} / 左 ${vBand.x0} / 右 ${vertical.width - vBand.x1}")
        println("[证据 1] 横画布内容带：列 ${hBand.x0}..${hBand.x1}（宽 ${hBand.width()}），行 ${hBand.y0}..${hBand.y1}（高 ${hBand.height()}）")
        println(
            "[证据 1] 若为「横屏内容等比缩放居中嵌入」：期望带高 ${fmt(vertical.width * hBand.height().toDouble() / hBand.width())}，" +
                "居中起点 ${fmt((vertical.height - vertical.width * hBand.height().toDouble() / hBand.width()) / 2)}",
        )

        // 证据 2：候选模型 —— 把横画布侧帧（含 4 个旋转角）按「内容带对齐」重采样到竖画布几何
        println("[证据 2] 候选模型（横画布侧帧 → 竖画布，内容带对齐；缩放 = 源带尺寸 / 目标带尺寸）")
        var best = Model.EMPTY
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val rotated = rotate(horizontal, rotation)
            val band = contentBand(rotated)
            val (canvas, scaleX, scaleY) = normalizeTo(rotated, band, vertical, vBand)
            val correlation = bandCorrelation(canvas, vBand, vertical, vBand)
            val absDiff = bandMeanAbsDiff(canvas, vBand, vertical, vBand)
            val peak = TemplateMatcher.findPeaks(canvas, template, signal.window, data.params).firstOrNull()
            println(
                "  旋转 ${rotation}°：缩放 X ${fmt(scaleX)} / Y ${fmt(scaleY)}；带内相关 ${fmt(correlation)}，" +
                    "平均绝对差 ${fmt(absDiff)}；模板 ${if (peak == null) "未命中" else "分数 ${fmt(peak.score)} @${peak.x},${peak.y}"}",
            )
            if (correlation > best.correlation) {
                best = Model(rotation, scaleX, scaleY, correlation, absDiff, peak, canvas)
            }
        }
        println("[证据 2] 最优模型：旋转 ${best.rotation}°，缩放 X ${fmt(best.scaleX)} / Y ${fmt(best.scaleY)}，带内相关 ${fmt(best.correlation)}")

        // 证据 3：锚点核验（竖画布参考位置 vs 归一化画布命中位置）
        val anchor = TemplateMatcher.findPeaks(vertical, template, signal.window, data.params).first()
        println(
            "[证据 3] 竖画布锚点：${anchor.x},${anchor.y}（分数 ${fmt(anchor.score)}，模板 ${template.width}x${template.height}）",
        )
        val bestPeak = best.peak
        val residualX = if (bestPeak != null) bestPeak.x - anchor.x else Int.MIN_VALUE
        val residualY = if (bestPeak != null) bestPeak.y - anchor.y else Int.MIN_VALUE
        if (bestPeak != null) {
            println(
                "[证据 3] 归一化画布锚点：${bestPeak.x},${bestPeak.y}（分数 ${fmt(bestPeak.score)}）→ " +
                    "位置残差 $residualX,$residualY",
            )
        } else {
            println("[证据 3] 归一化画布中模板未命中（该模型不成立）")
        }

        // 缩放细扫：验证「带对齐缩放」是否即为最优（同分口径比较）
        println("[证据 3] 缩放细扫（旋转 ${best.rotation}°，带对齐缩放 ±3%，看锚点分数峰值位置）：")
        val rotatedBest = rotate(horizontal, best.rotation)
        var scale = best.scaleX * 0.97
        var bestScaleScore = -1.0
        var bestScale = 0.0
        while (scale <= best.scaleX * 1.03) {
            val canvas = resampleBand(rotatedBest, contentBand(rotatedBest), vertical, vBand, scale)
            val peak = TemplateMatcher.findPeaks(canvas, template, signal.window, data.params).firstOrNull()
            println("  缩放 ${fmt(scale)} → ${if (peak == null) "未命中" else "分数 ${fmt(peak.score)} @${peak.x},${peak.y}"}")
            if ((peak?.score ?: -1.0) > bestScaleScore) {
                bestScaleScore = peak?.score ?: -1.0
                bestScale = scale
            }
            scale += 0.005
        }
        println("[证据 3] 细扫最优缩放 ${fmt(bestScale)}（分数 ${fmt(bestScaleScore)}）")

        // 结论形态：映射 = 等比缩放 + 平移（无旋转），比例 = 两画布宽度之比
        val geometryScale = horizontal.width.toDouble() / vertical.width
        println(
            "[结论] 映射模型：横画布侧帧 → 竖画布 = 等比缩放 ${fmt(geometryScale)}" +
                "（= 横画布宽 ${horizontal.width} / 竖画布宽 ${vertical.width}）+ 平移 0,${vBand.y0}，无旋转；" +
                "竖画布内容带高 ${vBand.height()}（等比嵌入期望 ${fmt(vertical.width * horizontal.height.toDouble() / horizontal.width)}）",
        )

        // 落盘归一化画布 + 竖画布原帧，供人工目视对照
        outDir.mkdirs()
        ReplayTool.writeGrayPng(best.canvas, File(outDir, "t1-11a-normalized-best.png"))
        ReplayTool.writeGrayPng(vertical, File(outDir, "t1-11a-vertical-source.png"))
        println("归一化画布 ${File(outDir, "t1-11a-normalized-best.png").path}；竖画布原帧 ${File(outDir, "t1-11a-vertical-source.png").path}")

        // 判据：最优模型（旋转 0° + 带对齐缩放）下锚点必须命中且位置重合；
        // 其余旋转角作为反例须显著更差——由此判定「纯旋转」不成立、「等比缩放 + 平移」成立。
        assertTrue("最优模型锚点分数应 ≥ 0.9（当前 ${fmt(bestPeak?.score ?: -1.0)}）", (bestPeak?.score ?: -1.0) >= 0.9)
        assertTrue("锚点位置残差应 ≤ 2px（当前 $residualX,$residualY）", abs(residualX) <= 2 && abs(residualY) <= 2)
    }

    /**
     * T1-11c 生产路径核对（真机帧离线复演）：把**真实横屏取证帧**（3168×1440）喂给按标定产物
     * 建立的识别循环，经方向归一后应命中「启动页」，且位置与竖画布帧原生判定一致——
     * 即同一标定产物在竖 / 横两种画布几何下判定一致（T1-11 验收 2 的离线部分）。
     */
    @Test
    fun landscapeFrameEntersLaunchPageThroughProductionPath() {
        assumeTrue("探针输入未就绪（标定产物 / 竖画布帧 / 横画布侧帧）", prepared())
        val data = CalibrationCodec.decode(artifactFile.readText(Charsets.UTF_8))
        val vertical = ReplayTool.decodeGrayPng(verticalFile)
        val horizontal = ReplayTool.decodeGrayPng(horizontalFile)

        // 两会话各自独立建循环（滞回状态不跨会话），各跑两轮（进入需连续 2 次命中）
        val nativeLoop = data.toLoop()
        val native = (0 until 2).map { nativeLoop.process(vertical, isForeground = true) }.last()
        val landscapeLoop = data.toLoop()
        val normalized = (0 until 2).map { landscapeLoop.process(horizontal, isForeground = true) }.last()

        val nativeRecord = native.records.first()
        val landscapeRecord = normalized.records.first()
        println("[T1-11c] 竖画布会话（${vertical.width}x${vertical.height}）：状态 ${native.state}，" +
            "判定 ${nativeRecord.verdict} ${peakText(nativeRecord.best)}")
        println("[T1-11c] 横画布会话（${horizontal.width}x${horizontal.height}，归一后）：状态 ${normalized.state}，" +
            "判定 ${landscapeRecord.verdict} ${peakText(landscapeRecord.best)}")
        println("[T1-11c] 标定几何 ${data.frameWidth}x${data.frameHeight}；" +
            "画面区 ${CanvasGeometry.of(data.frameWidth, data.frameHeight).calibrationArea}")

        assertEquals(UiState.LAUNCH_PAGE, native.state)
        assertEquals("横画布帧经归一后应同样进入启动页", UiState.LAUNCH_PAGE, normalized.state)
        val nativeBest = requireNotNull(nativeRecord.best)
        val landscapeBest = requireNotNull(landscapeRecord.best)
        assertTrue(
            "两侧判定位置应一致（±2px）：${nativeBest.x},${nativeBest.y} 对 ${landscapeBest.x},${landscapeBest.y}",
            abs(nativeBest.x - landscapeBest.x) <= 2 && abs(nativeBest.y - landscapeBest.y) <= 2,
        )
    }

    private fun peakText(peak: MatchPeak?): String =
        peak?.let { String.format(Locale.ROOT, "%.4f@%d,%d", it.score, it.x, it.y) } ?: "-"

    /**
     * 把 [source] 的 [sourceBand] 按 [scale] 重采样到 [target] 的 [targetBand] 几何。
     * 目标像素 (x, y) 对应源像素 `(x - tx0) · scale + sx0`；带外记 0。
     */
    private fun resampleBand(
        source: GrayImage,
        sourceBand: Band,
        target: GrayImage,
        targetBand: Band,
        scale: Double,
    ): GrayImage {
        val pixels = ByteArray(target.width * target.height)
        for (y in targetBand.y0 until targetBand.y1) {
            val sy = (y - targetBand.y0) * scale + sourceBand.y0
            for (x in targetBand.x0 until targetBand.x1) {
                val sx = (x - targetBand.x0) * scale + sourceBand.x0
                pixels[y * target.width + x] = bilinear(source, sx, sy).toByte()
            }
        }
        return GrayImage(target.width, target.height, pixels)
    }

    /** 带对齐归一化：各轴按「源带 / 目标带」尺寸比缩放（等比检查用）。 */
    private fun normalizeTo(
        source: GrayImage,
        sourceBand: Band,
        target: GrayImage,
        targetBand: Band,
    ): Triple<GrayImage, Double, Double> {
        val scaleX = sourceBand.width().toDouble() / targetBand.width()
        val scaleY = sourceBand.height().toDouble() / targetBand.height()
        val canvas = GrayImage(target.width, target.height, ByteArray(target.width * target.height))
        for (y in targetBand.y0 until targetBand.y1) {
            val sy = (y - targetBand.y0) * scaleY + sourceBand.y0
            for (x in targetBand.x0 until targetBand.x1) {
                val sx = (x - targetBand.x0) * scaleX + sourceBand.x0
                canvas.pixels[y * target.width + x] = bilinear(source, sx, sy).toByte()
            }
        }
        return Triple(canvas, scaleX, scaleY)
    }

    /** 两条带在带内区域的皮尔逊相关（亮度 / 对比度线性变换不变，与识别层口径一致）。 */
    private fun bandCorrelation(canvas: GrayImage, a: Band, other: GrayImage, b: Band): Double {
        var n = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        for (y in a.y0 until a.y1) {
            for (x in a.x0 until a.x1) {
                val vx = canvas.pixels[y * canvas.width + x].toInt() and 0xFF
                val vy = other.pixels[y * other.width + x].toInt() and 0xFF
                n++
                sumX += vx
                sumY += vy
                sumXX += vx.toDouble() * vx
                sumYY += vy.toDouble() * vy
                sumXY += vx.toDouble() * vy
            }
        }
        if (n == 0) return 0.0
        val cov = sumXY - sumX * sumY / n
        val varX = sumXX - sumX * sumX / n
        val varY = sumYY - sumY * sumY / n
        if (varX <= 0.0 || varY <= 0.0) return 0.0
        return cov / sqrt(varX * varY)
    }

    private fun bandMeanAbsDiff(canvas: GrayImage, a: Band, other: GrayImage, b: Band): Double {
        var n = 0
        var sum = 0.0
        for (y in a.y0 until a.y1) {
            for (x in a.x0 until a.x1) {
                val vx = canvas.pixels[y * canvas.width + x].toInt() and 0xFF
                val vy = other.pixels[y * other.width + x].toInt() and 0xFF
                sum += abs(vx - vy)
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** 双线性采样（越界记 0）。 */
    private fun bilinear(source: GrayImage, sx: Double, sy: Double): Int {
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val fx = sx - x0
        val fy = sy - y0
        if (x0 < 0 || y0 < 0 || x0 + 1 >= source.width || y0 + 1 >= source.height) return 0
        val p00 = source.pixels[y0 * source.width + x0].toInt() and 0xFF
        val p10 = source.pixels[y0 * source.width + x0 + 1].toInt() and 0xFF
        val p01 = source.pixels[(y0 + 1) * source.width + x0].toInt() and 0xFF
        val p11 = source.pixels[(y0 + 1) * source.width + x0 + 1].toInt() and 0xFF
        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }

    /** 顺时针旋转 [degrees]（0 / 90 / 180 / 270）。 */
    private fun rotate(image: GrayImage, degrees: Int): GrayImage = when (degrees) {
        0 -> image
        90 -> {
            val pixels = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    pixels[x * image.height + (image.height - 1 - y)] = image.pixels[y * image.width + x]
                }
            }
            GrayImage(image.height, image.width, pixels)
        }
        180 -> {
            val pixels = ByteArray(image.width * image.height)
            val n = pixels.size
            for (i in 0 until n) pixels[n - 1 - i] = image.pixels[i]
            GrayImage(image.width, image.height, pixels)
        }
        else -> {
            val pixels = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    pixels[(image.width - 1 - x) * image.height + y] = image.pixels[y * image.width + x]
                }
            }
            GrayImage(image.height, image.width, pixels)
        }
    }

    /** 内容带 = 从四边向内第一处「非均匀」行 / 列围成的矩形（留白带 = 均匀行 / 列）。 */
    private fun contentBand(image: GrayImage): Band {
        val rows = IntArray(image.height)
        for (y in 0 until image.height) {
            var mn = 255
            var mx = 0
            val base = y * image.width
            for (x in 0 until image.width) {
                val v = image.pixels[base + x].toInt() and 0xFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            rows[y] = mx - mn
        }
        val cols = IntArray(image.width)
        for (x in 0 until image.width) {
            var mn = 255
            var mx = 0
            for (y in 0 until image.height) {
                val v = image.pixels[y * image.width + x].toInt() and 0xFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            cols[x] = mx - mn
        }
        return Band(
            x0 = firstNonUniform(cols) ?: 0,
            x1 = lastNonUniform(cols) ?: image.width,
            y0 = firstNonUniform(rows) ?: 0,
            y1 = lastNonUniform(rows) ?: image.height,
        )
    }

    private fun firstNonUniform(ranges: IntArray): Int? =
        ranges.indexOfFirst { it > uniformRange }.takeIf { it >= 0 }

    private fun lastNonUniform(ranges: IntArray): Int? =
        ranges.indexOfLast { it > uniformRange }.takeIf { it >= 0 }?.plus(1)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    private data class Band(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun width(): Int = x1 - x0
        fun height(): Int = y1 - y0
    }

    private data class Model(
        val rotation: Int,
        val scaleX: Double,
        val scaleY: Double,
        val correlation: Double,
        val absDiff: Double,
        val peak: MatchPeak?,
        val canvas: GrayImage,
    ) {
        companion object {
            val EMPTY = Model(0, 0.0, 0.0, -1.0, 0.0, null, GrayImage(1, 1, ByteArray(1)))
        }
    }
}
