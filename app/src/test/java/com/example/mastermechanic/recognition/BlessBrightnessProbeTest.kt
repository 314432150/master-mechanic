package com.example.mastermechanic.recognition

import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * M7-1 的第一道验收：**亮 / 灰能不能分清**（先证分得清，再谈点得对）。
 *
 * ## 用料
 *
 * `app/build/replay-work/m7-bless/` 下两帧真机原图（3168×1440）：
 *
 * - `bright.png` —— 同一格作物 `0/3`，祝福控件**亮**（剩余 N 仍 > 0）
 * - `gray.png`   —— **同一格**、同一站位、同一视角，祝福控件**灰**（该格已 `3/3`）
 *
 * 两帧只差"按钮亮 / 灰"与"边框绿 / 橙"，背景几乎逐像素相同 ⇒ 这是能做 A/B 的最干净一对。
 * 归档原件见 `docs/recognition/research/m7-farm-bless-20261028/`（`03` 与 `05`）。
 *
 * ## 它回答什么
 *
 * 1. 同一个矩形里，亮态与灰态的平均亮度差多少（绝对差 / 比值）；
 * 2. **窗口取多大时区分度最好** —— 太小会被噪声吃掉，太大会把背景（绿树 / 天空）算进来；
 * 3. 由此给出"标定时该框多大"的经验值（真值仍由用户标定，本探针只给量级）。
 *
 * ## 它不回答什么
 *
 * 不验证"模板匹配能不能命中"（那要等 `farm_bless_button` 标定完成后跑）。
 * 本探针只量亮度，不读产物、不占识别链路。
 *
 * 输出 `build/replay-work/m7-bless/out/bless-brightness.txt`。就绪条件不足整体跳过。
 */
class BlessBrightnessProbeTest {

    private val workDir = File(System.getProperty("user.dir"), "build/replay-work/m7-bless")
    private val brightFile = File(workDir, "bright.png")
    private val grayFile = File(workDir, "gray.png")
    private val outFile = File(workDir, "out/bless-brightness.txt")

    /**
     * 祝福控件中心（按帧宽 / 帧高的比例）。
     *
     * 2026-10-28 在该设备上实测约 `(2600, 800)` / `3168×1440`。**只在本探针里使用**：
     * 它是一条一次性的取证常量，不进入产品代码（产品的窗口来自标定产物，红线 4）。
     */
    private val centerXRatio = 2600.0 / 3168.0
    private val centerYRatio = 800.0 / 1440.0

    @Test
    fun measureBrightnessSeparation() {
        assumeTrue(
            "工作目录未就绪：需要 build/replay-work/m7-bless/bright.png 与 gray.png",
            brightFile.isFile && grayFile.isFile,
        )
        val bright = ReplayTool.decodeGrayPng(brightFile)
        val gray = ReplayTool.decodeGrayPng(grayFile)
        assumeTrue(
            "两帧尺寸必须相同（同一台设备、同一会话）",
            bright.width == gray.width && bright.height == gray.height,
        )

        val cx = (bright.width * centerXRatio).toInt()
        val cy = (bright.height * centerYRatio).toInt()
        val lines = ArrayList<String>()
        lines += "# M7-1 亮度可分性探针"
        lines += ""
        lines += "- 帧：${bright.width}×${bright.height}（bright=${brightFile.name} / gray=${grayFile.name}）"
        lines += "- 控件中心（本探针假设）：($cx, $cy)"
        lines += "- 判据：同一矩形内 `mean(亮) - mean(灰)`，越大越可分"
        lines += ""
        lines += "| 半边长 px | 亮 mean | 灰 mean | 差 | 比值 |"
        lines += "| --- | --- | --- | --- | --- |"

        var bestRadius = 0
        var bestDiff = Double.NEGATIVE_INFINITY
        var bestOn = 0.0
        var bestOff = 0.0
        val radii = listOf(20, 30, 40, 50, 60, 80, 100, 120, 150)
        for (r in radii) {
            val bounds = PixelBounds(cx - r, cy - r, cx + r, cy + r)
            val onMean = RegionBrightness.mean(bright, bounds) ?: continue
            val offMean = RegionBrightness.mean(gray, bounds) ?: continue
            val diff = onMean - offMean
            val ratio = if (offMean > 0.0) onMean / offMean else Double.NaN
            lines += String.format(
                Locale.ROOT,
                "| %d | %.1f | %.1f | %+.1f | %.3f |",
                r, onMean, offMean, diff, ratio,
            )
            if (diff > bestDiff) {
                bestDiff = diff
                bestRadius = r
                bestOn = onMean
                bestOff = offMean
            }
        }

        lines += ""
        lines += "## 结论"
        lines += ""
        lines += String.format(
            Locale.ROOT,
            "- 区分度最好的半边长：**%d px**（亮 %.1f / 灰 %.1f，差 %+.1f，比值 %.3f）",
            bestRadius, bestOn, bestOff, bestDiff, if (bestOff > 0.0) bestOn / bestOff else Double.NaN,
        )
        lines += String.format(Locale.ROOT, "- 该窗口下的标定分界（两点中点）：**%.1f**", (bestOn + bestOff) / 2.0)
        lines += "- 该分界只是**本次取证的参考值**：产品阈值必须由标定产物给出（红线 4）。"

        outFile.parentFile?.mkdirs()
        outFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        println(lines.joinToString("\n"))

        // 硬断言：同一控件两态的亮度必须能被单个阈值分开（否则整个 FR-11 没有判据）
        assertTrue("亮态必须明显亮于灰态（实测差 $bestDiff）", bestDiff > 10.0)
    }
}
