package com.example.mastermechanic.floating

import kotlin.math.hypot
import kotlin.math.roundToInt

/** 手柄吸附的屏幕边缘（FR-07 贴边）：左右两缘（竖屏 / 横屏都只有两侧最省事）。 */
enum class FloatingSide(val token: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromToken(token: String): FloatingSide? = entries.firstOrNull { it.token == token }
    }
}

/**
 * **手柄**位置（FR-07，纯逻辑）：停靠侧 + 纵向位置（占屏高的比例）。
 *
 * 纵向存**比例**而不是像素：FR-07 要求位置持久化且"始终可被找回"——换分辨率 / 旋转后按比例还原
 * 仍落在相近的相对位置，再 clamp 回屏内即可；若存像素，换分辨率后会被 clamp 到底部（相对位置丢失）。
 *
 * 贴边口径（2026-09-14 用户口径修订）：手柄**始终贴在左 / 右边缘**、**一半移出屏幕**
 * （窄竖条，可见约 14dp），拖动只沿边缘上下走、**不允许被拖到屏幕中间**；
 * 停靠侧由重置位置决定，不由拖动切换。
 */
data class FloatingPosition(
    val side: FloatingSide,
    val yRatio: Double,
) {

    init {
        require(yRatio in 0.0..1.0) { "纵向位置比例必须在 0..1：$yRatio" }
    }

    /**
     * 贴边拖动结束：**只更新纵向比例**（[side] 保持不变）。
     *
     * 2026-09-14 用户口径：手柄**始终贴边、不允许被拖到屏幕中间**——所以拖动只沿边缘上下走，
     * 换边不由拖动承担（要换边就重置位置）。纵向按当前屏高折算比例，供持久化与跨分辨率还原。
     */
    fun withTopY(windowTopY: Int, screenHeight: Int): FloatingPosition {
        require(screenHeight > 0) { "屏幕高度必须为正：$screenHeight" }
        return copy(yRatio = (windowTopY.toDouble() / screenHeight).coerceIn(0.0, 1.0))
    }

    companion object {

        /** 贴边时手柄露出的宽度比例（2026-09-14 用户定稿：一半在屏外）。 */
        const val REVEAL_RATIO = 0.5

        /**
         * 默认位置：右侧、纵向 8% 屏高。
         * 两个都是**逻辑值**（不是设备绑定常量：不写任何具体分辨率 / 密度 / 像素坐标，红线 4）。
         */
        val DEFAULT = FloatingPosition(FloatingSide.RIGHT, 0.08)
    }
}

/**
 * **状态标签**位置（FR-07，纯逻辑；2026-09-14 用户新增：状态标签也要能拖动）：
 * 用标签**中心点**在屏幕上的比例表示——与手柄的"停靠侧"不同，标签是自由位置，
 * 中心点比例天然表达"底部居中"（0.5, 1.0）且跨分辨率可还原。
 */
data class LabelPosition(
    val xRatio: Double,
    val yRatio: Double,
) {

    init {
        require(xRatio in 0.0..1.0) { "横向位置比例必须在 0..1：$xRatio" }
        require(yRatio in 0.0..1.0) { "纵向位置比例必须在 0..1：$yRatio" }
    }

    companion object {

        /** 默认：**横屏底部居中**（纵向 1.0 = "尽可能靠下"，由布局按边距夹住）。 */
        val DEFAULT = LabelPosition(0.5, 1.0)
    }
}

/** 两个部件的当前位置（持久化单元）。 */
data class FloatingPositions(
    val handle: FloatingPosition,
    val label: LabelPosition,
) {

    companion object {
        val DEFAULT = FloatingPositions(FloatingPosition.DEFAULT, LabelPosition.DEFAULT)
    }
}

/**
 * 悬浮窗几何换算（纯逻辑，T3-4 / T3-7）：把位置换算成**两个窗口**的偏移量。
 *
 * 两个窗口（ADR-004 第 2 / 5 条；第 2 条于 2026-09-14 修订）：
 * - **手柄窗**（可触摸）：贴边、露出 [FloatingPosition.REVEAL_RATIO] 宽度（扁半圆）；
 * - **状态标签窗**（可触摸、可拖动）：自由位置，默认底部居中。
 */
object FloatingLayout {

    /** 手柄窗横向偏移：贴边 + 露出 [FloatingPosition.REVEAL_RATIO] 宽度（其余移出屏幕）。 */
    fun handleX(side: FloatingSide, handleWidth: Int, screenWidth: Int): Int = when (side) {
        FloatingSide.LEFT ->
            -((handleWidth * (1.0 - FloatingPosition.REVEAL_RATIO)).roundToInt())
        FloatingSide.RIGHT ->
            screenWidth - (handleWidth * FloatingPosition.REVEAL_RATIO).roundToInt()
    }

    /** 纵向偏移：按比例还原并 clamp 在当前屏内（换分辨率后仍可被找回）。 */
    fun y(yRatio: Double, height: Int, screenHeight: Int): Int =
        (yRatio * screenHeight).roundToInt().coerceIn(0, (screenHeight - height).coerceAtLeast(0))

    /** 标签窗横向偏移：由**中心点比例**还原，并留出边距、clamp 在屏内。 */
    fun labelXByCenter(xRatio: Double, labelWidth: Int, screenWidth: Int, margin: Int): Int =
        (xRatio * screenWidth - labelWidth / 2.0).roundToInt()
            .coerceIn(margin, (screenWidth - labelWidth - margin).coerceAtLeast(margin))

    /** 标签窗纵向偏移：同上（默认比例 1.0 → 贴着底边内边距）。 */
    fun labelYByCenter(yRatio: Double, labelHeight: Int, screenHeight: Int, margin: Int): Int =
        (yRatio * screenHeight - labelHeight / 2.0).roundToInt()
            .coerceIn(margin, (screenHeight - labelHeight - margin).coerceAtLeast(margin))

    /** 拖动结束 → 把标签的当前偏移折回**中心点比例**（供持久化与跨分辨率还原）。 */
    fun snapLabelCenter(
        windowX: Int,
        windowY: Int,
        labelWidth: Int,
        labelHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): LabelPosition {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕尺寸必须为正：${screenWidth}x$screenHeight" }
        return LabelPosition(
            ((windowX + labelWidth / 2.0) / screenWidth).coerceIn(0.0, 1.0),
            ((windowY + labelHeight / 2.0) / screenHeight).coerceIn(0.0, 1.0),
        )
    }

    /** 拖动过程中的抖动保护：始终把窗口留在屏内（贴边是**抬手**时才算，见 [FloatingGesture]）。 */
    fun clampInside(value: Int, size: Int, limit: Int): Int =
        value.coerceIn(0, (limit - size).coerceAtLeast(0))
}

/**
 * 触摸手势判据（纯逻辑，FR-07）：
 * **位移超过系统触摸滑动阈值才算拖动**，未超过阈值一律按"单击"处理——
 * 不得用"位移是否非零"判断（手指抖动会让菜单永远展不开）。
 * 阈值由调用方在目标设备上运行时读取（`ViewConfiguration`，红线 4：不写死常量）。
 */
object FloatingGesture {

    fun isDrag(dx: Float, dy: Float, touchSlop: Float): Boolean {
        require(touchSlop >= 0f) { "触摸滑动阈值不得为负：$touchSlop" }
        return hypot(dx, dy) >= touchSlop
    }
}
