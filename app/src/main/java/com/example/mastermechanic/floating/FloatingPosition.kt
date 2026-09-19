package com.example.mastermechanic.floating

import kotlin.math.roundToInt

/**
 * 手柄吸附的屏幕边缘（FR-07 贴边）：左右两缘（竖屏 / 横屏都只有两侧最省事）。
 *
 * [token] 只用于**日志可读**（`手柄停靠 right`）。曾有的 `fromToken` 是给位置持久化编解码用的，
 * 随「取消状态标签 + 手柄不可拖动」一起删掉（2026-09-19：位置已不再落盘，没有反序列化入口了）。
 */
enum class FloatingSide(val token: String) {
    LEFT("left"),
    RIGHT("right"),
}

/**
 * **手柄**位置（FR-07，纯逻辑）：停靠侧 + 纵向位置（占屏高的比例）。
 *
 * 纵向存**比例**而不是像素：换分辨率 / 旋转后按比例还原仍落在相近的相对位置。
 * 状态标签窗已取消（2026-09-17 用户口径"取消状态标签，操作在日志里记录"），本类只剩**手柄**一项。
 */
data class FloatingPosition(
    val side: FloatingSide,
    val yRatio: Double,
) {

    init {
        require(yRatio in 0.0..1.0) { "纵向位置比例必须在 0..1：$yRatio" }
    }

    companion object {

        /**
         * 默认位置：**右侧边缘、纵向偏上**（2026-09-17 用户口径「适当将悬浮窗手柄的位置往上调整」）。
         *
         * 沿革：0.333333（"贴横屏右边缘上方三分之一"，2026-09-14）→ **0.200000**（再往上挪一点，
         * 离拇指热区更近一点）。比例取 6 位小数（= 编解码的写出精度）：默认值 → 写盘 → 读盘逐位相等。
         * 手柄**不支持拖动**，位置只由默认值决定；纵向存比例是为了跨分辨率还原。
         */
        const val DEFAULT_Y_RATIO = 0.200000

        val DEFAULT = FloatingPosition(FloatingSide.RIGHT, DEFAULT_Y_RATIO)
    }
}

/**
 * 悬浮窗几何换算（纯逻辑，T3-4 / T3-7 / FR-07）：把"手柄位置"换算成**两个窗口**的偏移量。
 *
 * 两个窗口（ADR-004 第 2 / 5 条；第 2 条于 2026-09-14 修订为"菜单独立成窗"，第 5 条
 * 于 2026-09-17 撤销"状态标签"独立成窗）：
 * - **手柄窗**（可触摸）：**可见竖条贴屏幕边缘、不随展开变化**；窗口里除了这条可见竖条，
 *   其余是**透明的触摸扩展区**（伸进屏内）——可见的 7dp 很难点中，
 *   所以"好点"交给透明区、"不挡视线"交给可见条（两者宽度分别传入，见 [handleX]）；
 * - **菜单窗**（可触摸）：展开时才挂载，贴着屏内边缘、与手柄纵向对齐。
 */
object FloatingLayout {

    /**
     * 手柄窗横向偏移。可见竖条紧贴屏幕边缘，窗口剩余部分（= 窗口宽 - 可见宽）是**透明触摸区**。
     *
     * 注意：这里**没有"一半移出屏幕"**了——移出屏幕的部分根本收不到触摸，
     * 正是"可见 7dp 又只能点那 7dp"的原因（真机反馈："点击很难被触发"）。
     */
    fun handleX(side: FloatingSide, windowWidth: Int, screenWidth: Int, visualWidth: Int): Int =
        when (side) {
            // 左贴边：窗口右缘 = 可见宽度（窗口左侧留出的透明区由窗口宽度自然决定）
            FloatingSide.LEFT -> -(windowWidth - visualWidth)
            // 右贴边：窗口右缘 = 屏幕右缘（可见竖条是窗口最右侧那一条）
            FloatingSide.RIGHT -> screenWidth - windowWidth
        }

    /**
     * 菜单窗横向偏移：**完全在屏内**（菜单不能被屏幕边缘裁掉）且**避开手柄可见的那一条**
     * （否则面板会压住手柄），另留 [margin] 内边距。
     */
    fun menuX(
        side: FloatingSide,
        menuWidth: Int,
        screenWidth: Int,
        handleVisualWidth: Int,
        margin: Int,
    ): Int {
        val gap = handleVisualWidth + margin
        val limit = (screenWidth - menuWidth - margin).coerceAtLeast(margin)
        return when (side) {
            FloatingSide.LEFT -> gap.coerceIn(margin, limit)
            FloatingSide.RIGHT -> (screenWidth - gap - menuWidth).coerceIn(margin, limit)
        }
    }

    /** 菜单窗纵向偏移：**与手柄中心对齐**，再 clamp 在屏内（手柄靠上 / 靠下时菜单不会出屏）。 */
    fun menuY(
        handleY: Int,
        handleHeight: Int,
        menuHeight: Int,
        screenHeight: Int,
        margin: Int,
    ): Int = (handleY + handleHeight / 2.0 - menuHeight / 2.0).roundToInt()
        .coerceIn(margin, (screenHeight - menuHeight - margin).coerceAtLeast(margin))

    /** 纵向偏移：按比例还原并 clamp 在当前屏内（换分辨率后仍可被找回）。 */
    fun y(yRatio: Double, height: Int, screenHeight: Int): Int =
        (yRatio * screenHeight).roundToInt().coerceIn(0, (screenHeight - height).coerceAtLeast(0))
}