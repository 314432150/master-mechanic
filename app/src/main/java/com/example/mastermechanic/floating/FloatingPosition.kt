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
 * 贴边口径（2026-09-14 用户口径修订）：手柄**固定在右侧边缘、纵向三分之一处**、
 * **一半移出屏幕**（窄竖条，可见约 9dp）；**手柄不支持拖动**——位置只由默认值 / 重置决定。
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
         * 默认位置：**右侧边缘、纵向三分之一**（2026-09-14 用户口径："贴横屏右边缘上方三分之一"）。
         * 手柄**不支持拖动**，位置只由默认值 / 重置决定；纵向存比例是为了跨分辨率还原。
         * 两个都是**逻辑值**（不是设备绑定常量：不写任何具体分辨率 / 密度 / 像素坐标，红线 4）。
         *
         * 比例取 **6 位小数**（= 编解码的写出精度）：这样"默认值 → 写盘 → 读盘"逐位相等，
         * 不会因为 `1.0/3.0` 这种无限小数被截断而每次挂载都判定成"位置变了"。
         */
        const val DEFAULT_Y_RATIO = 0.333333

        val DEFAULT = FloatingPosition(FloatingSide.RIGHT, DEFAULT_Y_RATIO)
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

/**
 * 悬浮窗位置（持久化单元，2026-09-14 修订）：**只存状态标签**。
 *
 * 为什么不再存手柄：手柄自"不可拖动"起就是**布局常量**，不是用户数据。存过它的代价很具体——
 * 旧文件里的值会一直压住新默认值（真机现象：默认位置改成"上方三分之一"后，手柄仍出现在
 * 旧文件记录的位置，只有点一次「重置到默认位置」才回来）。
 * 手柄位置现在只由 [FloatingPosition.DEFAULT] 决定。
 */
data class FloatingPositions(val label: LabelPosition) {

    /** 手柄位置 = **布局常量**（不持久化，见类注释）。 */
    val handle: FloatingPosition get() = FloatingPosition.DEFAULT

    companion object {
        val DEFAULT = FloatingPositions(LabelPosition.DEFAULT)
    }
}

/**
 * 悬浮窗几何换算（纯逻辑，T3-4 / T3-7 / T3-7 修订）：把位置换算成**三个窗口**的偏移量。
 *
 * 三个窗口（ADR-004 第 2 / 5 条；第 2 条于 2026-09-14 修订为"菜单独立成窗"）：
 * - **手柄窗**（可触摸）：**可见竖条贴屏幕边缘、不随展开变化**；窗口里除了这条可见竖条，
 *   其余是**透明的触摸扩展区**（伸进屏内）——可见的 7dp 很难点中，
 *   所以"好点"交给透明区、"不挡视线"交给可见条（两者宽度分别传入，见 [handleX]）；
 * - **菜单窗**（可触摸）：展开时才挂载，贴着屏内边缘、与手柄纵向对齐；
 * - **状态标签窗**（可触摸、可拖动）：自由位置，默认底部居中。
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
