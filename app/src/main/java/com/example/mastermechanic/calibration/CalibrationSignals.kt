package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Template

/**
 * 标注选区 → 搜索窗口（T1-5b 起，T1-5l 随草稿模型一并迁至此，纯逻辑）：标定流程采用的窗口起点策略。
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
}

/**
 * 标定产物的直接写入（T1-5l，纯逻辑）：**产物即唯一数据源**，不再有进程内草稿。
 *
 * 真机反馈定稿口径：框选 + 归属状态确定即写产物（框完一条就落盘），同名信号**覆盖**；
 * 因此本对象只提供「覆盖写入 / 删除 / 改参数」三种原子编辑，产物校验仍由 [CalibrationData]
 * 构造时全量执行（构造即合法），写盘由 [CalibrationStore] 负责。
 *
 * 不含任何安卓依赖与设备绑定常量，可在 JVM 离线单测。
 */
object CalibrationSignals {

    /** 参数编辑的默认起点（无产物时使用；识别运行时只使用产物中的数值，红线 4）。 */
    const val DEFAULT_THRESHOLD_TEXT = "0.85"
    const val DEFAULT_MARGIN_TEXT = "0.10"
    const val DEFAULT_PEAK_DISTANCE_TEXT = "5"

    /** 默认参数（无产物且编辑框内容非法时的兜底；与三个 `DEFAULT_*_TEXT` 同源，只用于写入产物）。 */
    fun defaultParams(): MatchParams = requireNotNull(
        parseParams(DEFAULT_THRESHOLD_TEXT, DEFAULT_MARGIN_TEXT, DEFAULT_PEAK_DISTANCE_TEXT),
    ) { "内置默认参数非法（常量应始终合法）" }

    /**
     * 几何一致性检查（T1-5l）：产物已记录的标定帧几何必须与本次标定帧一致。
     *
     * 不一致说明两次标定发生在不同画布几何下（横 / 竖画布会话，T1-11）——窗口按整幅比例记录、
     * 模板按像素记录，混几何会让同一产物内的信号互相矛盾。返回 null = 一致（或尚无产物）。
     */
    fun geometryError(current: CalibrationData?, frameWidth: Int, frameHeight: Int): String? = when {
        current == null -> null
        current.frameWidth != frameWidth || current.frameHeight != frameHeight ->
            "帧 ${frameWidth}x$frameHeight 与产物 ${current.frameWidth}x${current.frameHeight} 几何不一致"
        else -> null
    }

    /**
     * 新增或覆盖一条信号（同名信号**整体替换**）：模板与搜索窗口取本次标定值，不追加、不合并；
     * 归属状态随之更新（同名信号换状态无需先删除）。
     *
     * 非法输入抛 [IllegalArgumentException]（几何不一致 / 未知状态 / 帧尺寸非正），由界面转为提示文本。
     */
    fun upsert(
        current: CalibrationData?,
        name: String,
        state: UiState,
        window: SearchWindow,
        template: Template,
        params: MatchParams,
        frameWidth: Int,
        frameHeight: Int,
    ): CalibrationData {
        require(state != UiState.UNKNOWN) { "「未知」不能作为归属状态" }
        require(frameWidth > 0 && frameHeight > 0) { "标定帧尺寸必须为正：${frameWidth}x$frameHeight" }
        geometryError(current, frameWidth, frameHeight)?.let { throw IllegalArgumentException(it) }

        val signals = current?.signals.orEmpty().filterNot { it.name == name } +
            CalibrationData.SignalEntry(name, window, listOf(template))
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params,
            signals = signals,
            stateRules = rebuildRules(signals, current?.stateRules, name to state),
        )
    }

    /**
     * 删除一条信号：剩余为空返回 null（调用方据此删除产物文件——空产物无意义）。
     * 名称不存在时返回等价产物（幂等，不报错）。
     */
    fun remove(current: CalibrationData, name: String): CalibrationData? {
        val signals = current.signals.filterNot { it.name == name }
        if (signals.isEmpty()) return null
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = current.params,
            signals = signals,
            stateRules = rebuildRules(signals, current.stateRules, null),
        )
    }

    /** 更新产物参数；**无产物或参数未变化返回 null**（无产物时参数随首个信号写入，避免空产物文件）。 */
    fun withParams(current: CalibrationData?, params: MatchParams): CalibrationData? {
        current ?: return null
        if (current.params == params) return null
        return CalibrationData(
            frameWidth = current.frameWidth,
            frameHeight = current.frameHeight,
            params = params,
            signals = current.signals,
            stateRules = current.stateRules,
        )
    }

    /** 信号名 → 归属状态（由状态规则反查；产物中必有，缺失返回 null）。 */
    fun stateOf(data: CalibrationData, name: String): UiState? =
        data.stateRules.firstOrNull { name in it.signalNames }?.state

    /** 参数文本 → 参数；任一非法返回 null（界面据此提示并拒绝写入）。 */
    fun parseParams(
        thresholdText: String,
        marginText: String,
        minDistanceText: String,
    ): MatchParams? {
        val threshold = thresholdText.trim().toDoubleOrNull() ?: return null
        val margin = marginText.trim().toDoubleOrNull() ?: return null
        val minDistance = minDistanceText.trim().toIntOrNull() ?: return null
        return try {
            MatchParams(threshold, margin, minDistance)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 由信号列表重建状态规则：以既有规则给出各信号的归属状态，[assigned] 覆盖刚写入的那条。
     * 规则顺序按信号首次出现顺序（确定性，产物文本可稳定复现）。
     */
    private fun rebuildRules(
        signals: List<CalibrationData.SignalEntry>,
        previous: List<CalibrationData.StateRule>?,
        assigned: Pair<String, UiState>?,
    ): List<CalibrationData.StateRule> {
        val stateOf = HashMap<String, UiState>()
        previous?.forEach { rule -> rule.signalNames.forEach { stateOf[it] = rule.state } }
        assigned?.let { (name, state) -> stateOf[name] = state }
        return signals
            .groupBy(
                keySelector = {
                    requireNotNull(stateOf[it.name]) { "信号「${it.name}」缺少归属状态" }
                },
                valueTransform = { it.name },
            )
            .map { (state, names) -> CalibrationData.StateRule(state, names) }
    }
}
