package com.example.mastermechanic.floating

import kotlin.math.hypot
import kotlin.math.roundToInt

/** 悬浮窗吸附的屏幕边缘（FR-07 贴边）：左右两缘（竖屏使用场景）。 */
enum class FloatingSide(val token: String) {
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromToken(token: String): FloatingSide? = entries.firstOrNull { it.token == token }
    }
}

/**
 * 悬浮窗位置（FR-07，纯逻辑）：**停靠侧 + 纵向位置（占屏高的比例）**。
 *
 * 纵向为什么存**比例**而不是像素：FR-07 要求位置持久化且"始终可被找回"——换分辨率 / 旋转后按比例还原
 * 仍落在相近的相对位置，再 clamp 回屏内即可；若存像素，换分辨率后会被 clamp 到底部（相对位置丢失）。
 *
 * 贴边口径（2026-09-13 用户确认）：吸附**左右边缘**，**仅露出约 40% 宽度**（其余移出屏幕），
 * 纵向位置 clamp 在当前屏内。
 */
data class FloatingPosition(
    val side: FloatingSide,
    val yRatio: Double,
) {

    init {
        require(yRatio in 0.0..1.0) { "纵向位置比例必须在 0..1：$yRatio" }
    }

    companion object {

        /** 收起态手柄露出的宽度比例（FR-07「仅露出约 40% 宽度」）。 */
        const val REVEAL_RATIO = 0.4

        /**
         * 默认位置：右侧、纵向 8% 屏高。
         * 两个都是**逻辑值**（不是设备绑定常量：不写任何具体分辨率 / 密度 / 像素坐标，红线 4）。
         */
        val DEFAULT = FloatingPosition(FloatingSide.RIGHT, 0.08)

        /**
         * 拖动结束 → 吸附**最近的**屏幕边缘（FR-07）：按窗口**中心**落在左半屏还是右半屏决定；
         * 纵向按当前屏高折算比例（供持久化与跨分辨率还原）。
         */
        fun snap(
            windowCenterX: Float,
            windowTopY: Int,
            screenWidth: Int,
            screenHeight: Int,
        ): FloatingPosition {
            require(screenWidth > 0 && screenHeight > 0) { "屏幕尺寸必须为正：${screenWidth}x$screenHeight" }
            val side = if (windowCenterX * 2f <= screenWidth) FloatingSide.LEFT else FloatingSide.RIGHT
            return FloatingPosition(side, (windowTopY.toDouble() / screenHeight).coerceIn(0.0, 1.0))
        }
    }
}

/**
 * 悬浮窗几何换算（纯逻辑，T3-4）：把 [FloatingPosition] 换算成**两个窗口**的偏移量。
 *
 * 两个窗口（ADR-004 第 2 / 5 条）：
 * - **手柄窗**（可触摸）：贴边、仅露出约 40% 宽度 —— 可触摸区域只吸附在这块胶丸上；
 * - **标签窗**（不可触摸、恒穿透）：显示状态与动作，**完整落在屏内**（否则文字被裁掉就没法读）。
 */
object FloatingLayout {

    /** 手柄窗横向偏移：贴边 + 露出约 40% 宽度（其余移出屏幕）。 */
    fun handleX(side: FloatingSide, handleWidth: Int, screenWidth: Int): Int = when (side) {
        FloatingSide.LEFT ->
            -((handleWidth * (1.0 - FloatingPosition.REVEAL_RATIO)).roundToInt())
        FloatingSide.RIGHT ->
            screenWidth - (handleWidth * FloatingPosition.REVEAL_RATIO).roundToInt()
    }

    /** 纵向偏移：按比例还原并 clamp 在当前屏内（换分辨率后仍可被找回）。 */
    fun y(yRatio: Double, height: Int, screenHeight: Int): Int =
        (yRatio * screenHeight).roundToInt().coerceIn(0, (screenHeight - height).coerceAtLeast(0))

    /**
     * 标签窗横向偏移：贴着手柄**可见部分的内侧**，并 clamp 在屏内。
     *
     * [handleX] / [handleWidth] 传手柄窗（展开态 = 整个面板）的当前位置与宽度，
     * 因此收起态（手柄大部分移出屏幕）与展开态（面板完全进屏）共用同一套换算。
     */
    fun labelX(
        side: FloatingSide,
        handleX: Int,
        handleWidth: Int,
        labelWidth: Int,
        screenWidth: Int,
        gap: Int,
    ): Int {
        val visibleEdge = when (side) {
            FloatingSide.LEFT -> handleX + handleWidth
            FloatingSide.RIGHT -> handleX
        }
        val x = when (side) {
            FloatingSide.LEFT -> visibleEdge + gap
            FloatingSide.RIGHT -> visibleEdge - gap - labelWidth
        }
        return x.coerceIn(0, (screenWidth - labelWidth).coerceAtLeast(0))
    }

    /** 标签窗纵向偏移：位于手柄/面板上方，并 clamp 在屏内。 */
    fun labelY(panelY: Int, labelHeight: Int, panelHeight: Int, screenHeight: Int, gap: Int): Int {
        val above = panelY - gap - labelHeight
        val below = panelY + panelHeight + gap
        // 上方放不下（贴屏幕顶部）时改放下方；两处都放不下则 clamp 在屏内
        return if (above >= 0) above else below.coerceIn(0, (screenHeight - labelHeight).coerceAtLeast(0))
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
