package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow

/**
 * 标注选区 → 搜索窗口（T1-5b，纯逻辑）：标定流程采用的窗口起点策略。
 *
 * 窗口 = 选区矩形各方向外扩「选区自身宽 / 高」的 1 倍（总区域约为选区的 3×3），
 * 并裁剪到帧内。局部窗口既满足性能与防误匹配需要，也是 FR-01「仅右上角局部范围」
 * 语义的载体——最终值写入产物，可在产物文本中人工微调（本策略不含任何设备具体值）。
 */
object SelectionWindow {

    fun forSelection(
        frameWidth: Int,
        frameHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): SearchWindow {
        require(frameWidth > 0 && frameHeight > 0) { "帧尺寸必须为正：${frameWidth}x$frameHeight" }
        require(x0 >= 0 && y0 >= 0 && x1 <= frameWidth && y1 <= frameHeight && x0 < x1 && y0 < y1) {
            "选区 [$x0,$x1)×[$y0,$y1) 超出帧 ${frameWidth}x$frameHeight 或为空"
        }
        val marginX = x1 - x0
        val marginY = y1 - y0
        return SearchWindow(
            left = (x0 - marginX).coerceAtLeast(0).toDouble() / frameWidth,
            top = (y0 - marginY).coerceAtLeast(0).toDouble() / frameHeight,
            right = (x1 + marginX).coerceAtMost(frameWidth).toDouble() / frameWidth,
            bottom = (y1 + marginY).coerceAtMost(frameHeight).toDouble() / frameHeight,
        )
    }

    /** 两个窗口的并集（同一信号追加不同位置的模板时保证都能搜到；结果仍写回产物）。 */
    fun union(a: SearchWindow, b: SearchWindow): SearchWindow = SearchWindow(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )
}

/**
 * 标定草稿（T1-5b，进程内）：标注中的信号与模板，保存产物前不落盘。
 *
 * 以单例承载是为了跨页面切换存活（「授权与运行」 ↔ 「识别标定」）；
 * 进程被杀即清空——重新标注即可，不影响已保存的产物。参数以文本形式承载（原样保留编辑态）。
 */
object CalibrationDraft {

    class DraftSignal(val state: UiState, val entry: CalibrationData.SignalEntry)

    val signals = mutableListOf<DraftSignal>()

    /** 参数编辑文本：经验保守起点（识别运行时只使用产物中的数值，红线 4）。 */
    var matchThresholdText = "0.85"
    var ambiguityMarginText = "0.10"
    var peakMinDistanceText = "5"

    /** 最近一次成功提取模板的帧尺寸（记录进产物，供运行帧对照）；0 = 尚未提取。 */
    var lastFrameWidth = 0
    var lastFrameHeight = 0

    fun clear() {
        signals.clear()
        matchThresholdText = "0.85"
        ambiguityMarginText = "0.10"
        peakMinDistanceText = "5"
        lastFrameWidth = 0
        lastFrameHeight = 0
    }

    fun removeSignal(name: String) {
        signals.removeAll { it.entry.name == name }
    }

    fun findSignal(name: String): DraftSignal? = signals.firstOrNull { it.entry.name == name }

    /** 参数解析；任一非法返回 null（界面据此禁用保存并提示）。 */
    fun parseParams(): MatchParams? {
        val threshold = matchThresholdText.trim().toDoubleOrNull() ?: return null
        val margin = ambiguityMarginText.trim().toDoubleOrNull() ?: return null
        val minDistance = peakMinDistanceText.trim().toIntOrNull() ?: return null
        return try {
            MatchParams(threshold, margin, minDistance)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 草稿 → 产物数据（frameWidth / frameHeight 取自当前标注帧）。 */
    fun toData(frameWidth: Int, frameHeight: Int): CalibrationData {
        val params = requireNotNull(parseParams()) { "匹配参数非法" }
        val rules = signals
            .groupBy({ it.state }, { it.entry.name })
            .map { (state, names) -> CalibrationData.StateRule(state, names) }
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params,
            signals = signals.map { it.entry },
            stateRules = rules,
        )
    }
}
