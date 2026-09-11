package com.example.mastermechanic.recognition

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 归一化互相关（NCC）模板匹配（ADR-003）：输入帧数据 → 输出相似度分数与位置。
 *
 * - 只在 [SearchWindow]（相对比例，随帧尺寸换算）内扫描；
 * - 分数对亮度 / 对比度归一化（±线性变换不改变结果），对局部遮罩与背景动态有容忍；
 * - 纯数值计算、固定遍历与排序顺序，同输入 + 同参数必然同结果（§5-4）；
 * - 不做运行期多尺度：匹配尺度由标定确定。
 */
object TemplateMatcher {

    /** 方差下限：低于此值的位置无相关性可言（近恒定区域），分数记 0。 */
    private const val VARIANCE_EPS = 1e-6

    /**
     * 在 [image] 的 [window] 范围内扫描 [template]，返回候选峰值（分数降序、彼此分离）。
     *
     * 峰值 = 经非极大抑制的局部最优位置：抑制半径 [MatchParams.peakMinDistance] 内的
     * 候选视为同一处、只保留最高分——最高与次高分构成「竞争位置」审计基础（§5-1/§5-2）。
     */
    fun findPeaks(image: GrayImage, template: Template, window: SearchWindow, params: MatchParams): List<MatchPeak> {
        if (template.centeredSumSq <= VARIANCE_EPS) return emptyList() // 常量模板：无有效纹理

        val bounds = window.pixelBounds(image.width, image.height)
        val maxX = bounds.x1 - template.width
        val maxY = bounds.y1 - template.height
        if (maxX < bounds.x0 || maxY < bounds.y0) return emptyList() // 窗口装不下模板

        val candidates = ArrayList<MatchPeak>()
        for (y in bounds.y0..maxY) {
            for (x in bounds.x0..maxX) {
                val score = nccScore(image, template, x, y)
                if (score > 0.0) candidates.add(MatchPeak(score, x, y))
            }
        }
        return suppressPeaks(candidates, params.peakMinDistance)
    }

    /** 位置 (x, y) 处模板与帧窗口的 NCC 分数；帧窗口近乎恒定记 0（排除除零与噪声放大）。 */
    private fun nccScore(image: GrayImage, template: Template, x: Int, y: Int): Double {
        val tw = template.width
        val th = template.height
        val centered = template.centered
        var sumP = 0.0
        var sumP2 = 0.0
        var dot = 0.0
        var rowStart = y * image.width + x
        var ti = 0
        for (ty in 0 until th) {
            var i = rowStart
            for (tx in 0 until tw) {
                val p = (image.pixels[i].toInt() and 0xFF).toDouble()
                sumP += p
                sumP2 += p * p
                dot += centered[ti] * p
                i++
                ti++
            }
            rowStart += image.width
        }
        val n = (tw * th).toDouble()
        val variance = sumP2 - sumP * sumP / n
        if (variance / n < VARIANCE_EPS) return 0.0
        // dot = Σ t'·p；因 Σt' = 0，它等于 Σ t'·(p − p̄)，即 NCC 分子
        return dot / sqrt(template.centeredSumSq * variance)
    }

    /**
     * 贪心非极大抑制：按分数降序（同分按 y、x 升序）依次选取峰值，
     * 抑制其 [minDistance] 半径内的其余候选。确定性 tie-break 保证可复现（§5-4）。
     */
    fun suppressPeaks(candidates: List<MatchPeak>, minDistance: Int): List<MatchPeak> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedWith(
            compareByDescending<MatchPeak> { it.score }.thenBy { it.y }.thenBy { it.x }
        )
        val picked = ArrayList<MatchPeak>()
        for (candidate in sorted) {
            val suppressed = picked.any {
                abs(it.x - candidate.x) < minDistance && abs(it.y - candidate.y) < minDistance
            }
            if (!suppressed) picked.add(candidate)
        }
        return picked
    }
}
