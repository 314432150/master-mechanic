package com.example.mastermechanic.recognition

import kotlin.math.ceil
import kotlin.math.floor

/**
 * 识别层数据类型（T1-3，纯逻辑：不依赖安卓框架，可在 JVM 离线重跑）。
 *
 * 输入帧数据 = [GrayImage]；模板 / 搜索窗口 / 阈值全部来自标定产物（T1-5），
 * 本包不内置任何与具体设备绑定的分辨率、密度、位置或阈值（红线 4、§5-5）。
 */

/** 灰度帧：识别层的画面数据单位（单通道 0..255），与采集实现解耦。 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "帧尺寸必须为正：${width}x${height}" }
        require(pixels.size == width * height) { "像素数 ${pixels.size} 与尺寸 ${width}x${height} 不符" }
    }
}

/**
 * 标志模板：标定阶段从目标设备画面采集的灰度小块；同一标志可配置多个模板（ADR-003）。
 * 构造时预计算均值与去均值数据（NCC 分子分母的常量项），匹配期间不再重复计算。
 */
class Template(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0) { "模板尺寸必须为正：${width}x${height}" }
        require(pixels.size == width * height) { "像素数 ${pixels.size} 与尺寸 ${width}x${height} 不符" }
    }

    /** 模板像素去均值后的序列（Σ(centered) = 0）。 */
    internal val centered: DoubleArray

    /** Σ(centered²)：NCC 分母的模板项。 */
    internal val centeredSumSq: Double

    init {
        val n = pixels.size
        var sum = 0.0
        for (i in 0 until n) sum += pixels[i].toInt() and 0xFF
        val mean = sum / n
        val c = DoubleArray(n)
        var sq = 0.0
        for (i in 0 until n) {
            val v = (pixels[i].toInt() and 0xFF) - mean
            c[i] = v
            sq += v * v
        }
        centered = c
        centeredSumSq = sq
    }
}

/** 像素范围闭开区间 [x0, x1) × [y0, y1)（由 [SearchWindow] 按帧尺寸换算而来）。 */
data class PixelBounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/** 搜索窗口：相对帧尺寸的比例矩形（0..1）。像素范围按帧尺寸换算，因此零设备常量。 */
data class SearchWindow(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "比例必须在 0..1：($left, $top, $right, $bottom)"
        }
        require(right >= left && bottom >= top) { "窗口右/下不得小于左/上" }
    }

    /** 换算为像素范围并裁剪到帧内。换算用 floor/ceil，保证确定性（§5-4）。 */
    fun pixelBounds(imageWidth: Int, imageHeight: Int): PixelBounds = PixelBounds(
        x0 = floor(left * imageWidth).toInt().coerceIn(0, imageWidth),
        y0 = floor(top * imageHeight).toInt().coerceIn(0, imageHeight),
        x1 = ceil(right * imageWidth).toInt().coerceIn(0, imageWidth),
        y1 = ceil(bottom * imageHeight).toInt().coerceIn(0, imageHeight),
    )
}

/**
 * 匹配参数：全部由调用方（标定产物）提供，代码不内置具体值。
 *
 * - [matchThreshold] 命中线：最高分低于此值即未命中；§5-2 的"多处高分"判据亦以此为准
 *   （竞争位置也达到命中线 → 画面中存在多个可能命中处）；
 * - [ambiguityMargin] 差距线：最高分与竞争分差距小于此值即不可信（§5-2"差距过小"）；
 * - [peakMinDistance] 峰值最小间距：距离小于此值的候选视为同一处（非极大抑制半径）。
 */
data class MatchParams(
    val matchThreshold: Double,
    val ambiguityMargin: Double,
    val peakMinDistance: Int,
) {
    init {
        require(matchThreshold > 0.0 && matchThreshold <= 1.0) { "命中线须在 (0, 1]：$matchThreshold" }
        require(ambiguityMargin >= 0.0) { "差距线不得为负：$ambiguityMargin" }
        require(peakMinDistance >= 1) { "峰值间距至少为 1：$peakMinDistance" }
    }
}

/** 信号规格：一个界面标志 = 名称 + 搜索窗口 + 一个或多个模板。 */
class SignalSpec(val name: String, val window: SearchWindow, val templates: List<Template>) {
    init {
        require(name.isNotBlank()) { "信号名称不得为空" }
        require(templates.isNotEmpty()) { "信号至少需要一个模板" }
    }
}

/** 候选峰值：相似度分数（置信度）+ 位置（帧像素坐标，为模板左上角）。 */
data class MatchPeak(val score: Double, val x: Int, val y: Int)

/** 判定结论：命中 / 未命中 / 不可信（不可信按未识别处理，§5-2、红线 7）。 */
enum class Verdict { MATCHED, NOT_MATCHED, UNRELIABLE }

/**
 * 判定记录（§5-1）：命中的标志、位置、置信度、同画面最强竞争位置，四项齐全。
 *
 * [best] 为画面最高分候选（未命中时也保留，供审计）；[competitor] 为经峰值抑制后
 * 的次高分离候选，不存在时为 null。
 */
data class DetectionRecord(
    val signalName: String,
    val verdict: Verdict,
    val best: MatchPeak?,
    val competitor: MatchPeak?,
) {
    /** 是否命中（不可信与未命中均不算命中）。 */
    val matched: Boolean get() = verdict == Verdict.MATCHED
}
