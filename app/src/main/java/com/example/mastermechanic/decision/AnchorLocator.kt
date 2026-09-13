package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.CanvasGeometry
import com.example.mastermechanic.recognition.CanvasMapping
import com.example.mastermechanic.recognition.DetectionRecord
import com.example.mastermechanic.recognition.FrameNormalizer
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec

/**
 * 动作锚点定位（T2-3b，纯逻辑）：回答"当前这个界面要点哪里"，**只定位当前状态声明的锚点**。
 *
 * - **按当前状态启用**：只有「当前状态」规则的锚点参与匹配；别的状态的锚点这一轮根本不搜——
 *   既省成本，也不会把别界面的控件当成可点目标（红线 1 的"状态未知 → 零点击"由此延续：
 *   「未知」不允许声明锚点）；
 * - **点击点 = 匹配区域中心**：判定给出的位置是模板左上角，本类加上模板中心偏移；
 *   一律以**运行帧坐标**给点击层（ADR-002「坐标来自识别判定结果」），标定坐标同时带出供审计；
 * - **不可信按未命中处理**（红线 7）：多处高分 / 分差不足时宁可"这次点不了"，也不点偏；
 * - 方向归一复用 T1-11c 的同一条路径（[FrameNormalizer] + [CanvasGeometry]），与标志判定同源，
 *   保证"看到的位置"与"点的位置"不会各算一套。
 *
 * 本类不产生点击、不感知无障碍服务：点击由转发层（T2-3c）统一执行（红线 6）。
 */
class AnchorLocator(
    anchorsByState: Map<UiState, List<SignalSpec>>,
    private val params: MatchParams,
    /**
     * 画布几何（T1-11c）：与识别循环同源建立（标定帧尺寸）；null = 不做方向适配（同几何）。
     */
    private val geometry: CanvasGeometry? = null,
) {

    private val specsByState: Map<UiState, List<SignalSpec>> =
        anchorsByState.filterValues { it.isNotEmpty() }

    init {
        require(UiState.UNKNOWN !in specsByState) {
            "「未知」不能声明锚点：状态未知时零点击（红线 1）"
        }
        specsByState.forEach { (state, specs) ->
            val names = specs.map { it.name }
            require(names.distinct().size == names.size) { "状态「${state.name}」的锚点名重复：$names" }
            specs.forEach { spec ->
                val sizes = spec.templates.map { it.width to it.height }.distinct()
                require(sizes.size == 1) {
                    "锚点「${spec.name}」的多个样式模板尺寸不一致（$sizes）：点击点 = 匹配区域中心，" +
                        "尺寸不一致时中心无从确定——请拆成多条记录（一条一个样式）"
                }
            }
        }
    }

    /** 是否标了任何锚点（运行日志据此区分"没标锚点"与"锚点没命中"）。 */
    val isEmpty: Boolean = specsByState.isEmpty()

    /** 当前状态可用的锚点名；该状态未声明 → 空列表（不接受兜底到别的状态）。 */
    fun available(state: UiState): List<String> = specsByState[state].orEmpty().map { it.name }

    /**
     * 定位当前状态的锚点（一帧一次）：命中返回点击点，未命中 / 不可信不返回。
     *
     * 命中多个锚点时不在这里挑一个——"点哪个"属于流程决策（B1），本类只如实给出每个可点目标。
     */
    fun locate(gray: GrayImage, state: UiState): List<AnchorHit> {
        val specs = specsByState[state].orEmpty()
        if (specs.isEmpty()) return emptyList()
        val canvas = geometry?.mappingFor(gray.width, gray.height)
        val target = when {
            canvas == null || canvas.isIdentity -> gray
            else -> FrameNormalizer.normalize(
                gray,
                canvas,
                specs.map { it.window.pixelBounds(canvas.calibrationWidth, canvas.calibrationHeight) },
            )
        }
        val detector = SignalDetector(specs, params)
        return specs.mapNotNull { spec -> hitOf(spec, detector.detectSignal(target, spec), canvas) }
    }

    /**
     * 当前状态锚点所需的最小像素区域（**运行帧坐标系**）：采集层据此把锚点窗口一并纳入灰度转换
     * （T1-10b 同源口径）——漏掉它会让锚点在"只转换了标志窗口"的帧上读到全零像素，静默失败。
     */
    fun windowRegions(frameWidth: Int, frameHeight: Int, state: UiState): List<PixelBounds> {
        val specs = specsByState[state].orEmpty()
        if (specs.isEmpty()) return emptyList()
        val canvas = geometry?.mappingFor(frameWidth, frameHeight)
            ?: return specs.map { it.window.pixelBounds(frameWidth, frameHeight) }
        return specs.map { canvas.sourceBounds(it.window.pixelBounds(canvas.calibrationWidth, canvas.calibrationHeight)) }
    }

    private fun hitOf(spec: SignalSpec, record: DetectionRecord, canvas: CanvasMapping?): AnchorHit? {
        if (!record.matched) return null
        val best = record.best ?: return null
        // 模板尺寸在构造时已校验一致，取首个即可
        val template = spec.templates.first()
        val calibrationX = best.x + template.width / 2.0
        val calibrationY = best.y + template.height / 2.0
        return AnchorHit(
            name = spec.name,
            score = best.score,
            calibrationX = calibrationX,
            calibrationY = calibrationY,
            frameX = canvas?.toFrameX(calibrationX) ?: calibrationX,
            frameY = canvas?.toFrameY(calibrationY) ?: calibrationY,
        )
    }
}

/**
 * 一个可点击目标（T2-3b）：锚点名 + 置信度 + 点击点。
 *
 * [frameX] / [frameY] 是**运行帧像素坐标**（点击层直接使用，取整由点击层负责）；
 * [calibrationX] / [calibrationY] 是同一命中的标定坐标，供运行日志与离线复核解释"点从哪来"。
 */
data class AnchorHit(
    val name: String,
    val score: Double,
    val frameX: Double,
    val frameY: Double,
    val calibrationX: Double,
    val calibrationY: Double,
)
