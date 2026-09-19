package com.example.mastermechanic.ui

import kotlin.math.roundToInt

/**
 * 服务器清单**行手势的纯逻辑**（M3-T3-9 交互修订第五轮）：阈值判据、"拖到了第几位"的换算、
 * 左滑的**露出比例**、以及"按下时手指是不是落在已露出的删除按钮上"。
 *
 * 单独拿出来是因为这些事**不需要安卓环境**就能验：
 * 手势本身（谁抢事件、动画顺不顺、拖过头有没有回弹）只能在真机上核，
 * 但"滑过多少算露出""拖了 150px 该落在第几位"是纯算术，一律放进 JVM 单测
 * （[com.example.mastermechanic.ui.ServerListGesturesTest]）。
 *
 * 阈值单位说明：`dp` 常量乘 [androidx.compose.ui.unit.Density.density] 换算成像素后与手指位移比较；
 * 时间 / 触摸容差一律走 `ViewConfiguration`（系统给的），**不自己写毫秒数**。
 */
internal object ServerListGestures {

    /** 左滑露出的删除按钮宽度（dp）：比 48dp 最小触摸目标宽，容得下「删除」两个字。 */
    const val REVEAL_WIDTH_DP = 88f

    /** 松手吸附到"露出"的主判据：**滑过按钮宽的一半**就算（意图已经明确，剩下的交给吸附动画补完）。 */
    const val SWIPE_OPEN_FRACTION = 0.5f

    /** 甩动速度阈值（dp/s）：距离不够但甩得够快也算露出（"快速一划"是常见操作习惯）。 */
    const val SWIPE_FLING_DP_PER_SECOND = 300f

    /**
     * 快速一划的**最小露出比例**（很小，约 8dp 的位移）：甩出去时手指只走几十像素就抬起来了，
     * 门槛定大了这条捷径等于不存在。
     */
    const val SWIPE_FLING_MIN_REVEAL = 0.1f

    /** 拖动中的放大比例（视觉上"拎起来"一点，成本最低的抬起反馈）。 */
    const val DRAG_SCALE = 1.02f

    /**
     * 拖动位移对应**目标位置**：按落位单位（行高 + 行距）分出走了几行，再加到起点上，最后夹在清单范围内。
     *
     * @param from 拖动开始时该行的序号
     * @param dragOffsetYPx 手指纵向位移（**相对当前落位**，界面每次换位后会把已走的那几行高度减掉）
     * @param rowPitchPx 落位单位 = 行高 + 行距（从 `LazyListState.layoutInfo` 取，**不写死像素值**）
     * @param size 清单条数
     */
    fun dragTargetIndex(from: Int, dragOffsetYPx: Float, rowPitchPx: Float, size: Int): Int {
        if (size <= 0) return 0
        if (rowPitchPx <= 0f) return from
        val steps = (dragOffsetYPx / rowPitchPx).roundToInt()
        return (from + steps).coerceIn(0, size - 1)
    }

    /**
     * 多列网格里拖动位移对应的**目标位置**（M3-T3-9 第九轮：列表改成自适应多列卡片）。
     *
     * 一维列表里"走了几行"就够了（[dragTargetIndex]）；网格里横向那次位移同样要算：
     * **横向按列距、纵向按行距**分别折算成列步数 / 行步数，加到起点所在的格子（行、列）上，
     * 得到目标格，再换算回线性下标（`行 × 列数 + 列`）。
     *
     * 两处必须夹住：**列**夹在 `0..cols-1`（拖过头不能"绕行"到上一行的末尾），
     * **下标**夹在清单范围内（最后一行常常不满，按格子算出来的位置可能落到清单外面）。
     *
     * @param from 拖动开始时该条的序号（线性下标）
     * @param dragOffsetXPx 手指横向累计位移（**相对当前落位**，界面每次换位后会把已跨过的列距减掉）
     * @param dragOffsetYPx 手指纵向累计位移（同上，按行）
     * @param colPitchPx 落位单位 = 卡片宽 + 列距
     * @param rowPitchPx 落位单位 = 行高 + 行距
     * @param cols 当前列数（由可用宽度算出来的）
     * @param size 清单条数
     */
    fun dragTargetIndexInGrid(
        from: Int,
        dragOffsetXPx: Float,
        dragOffsetYPx: Float,
        colPitchPx: Float,
        rowPitchPx: Float,
        cols: Int,
        size: Int,
    ): Int {
        if (size <= 0) return 0
        // 单列（竖屏窄到只剩一列时）就是原来那件事，走同一条逻辑，不另立一套
        if (cols <= 1) return dragTargetIndex(from, dragOffsetYPx, rowPitchPx, size)
        if (rowPitchPx <= 0f) return from
        val colSteps = if (colPitchPx > 0f) (dragOffsetXPx / colPitchPx).roundToInt() else 0
        val rowSteps = (dragOffsetYPx / rowPitchPx).roundToInt()
        val lastRow = (size - 1) / cols
        val targetRow = (from / cols + rowSteps).coerceIn(0, lastRow)
        val targetCol = (from % cols + colSteps).coerceIn(0, cols - 1)
        return (targetRow * cols + targetCol).coerceIn(0, size - 1)
    }

    /**
     * 手指横向位移对应的**按钮露出比例**（0 = 完全收起，1 = 按钮完全盖住行尾）。
     *
     * 界面拿它驱动按钮"从行尾压进来"的位移——**被盖住的那一行自己一动不动**
     * （2026-09-14 用户口径：行不该跟着手指滑走，按钮直接覆盖在行上）。
     * 所以这里没有"卡的位移"，只有"按钮盖了多少"。
     *
     * 左滑（[dxPx] 为负）比例变大；**已经露出时右滑（正）比例变小**——天然就是收起，
     * 不需要另立一套"拖回去"的状态。
     *
     * @param dxPx 本次手势累计的横向位移（左为负）
     * @param baseFraction 起手那一刻的露出比例（0 或 1；拖到一半被打断时可能介于两者之间）
     * @param revealWidthPx 按钮宽度（px）
     */
    fun revealFraction(dxPx: Float, baseFraction: Float, revealWidthPx: Float): Float {
        val from = baseFraction.coerceIn(0f, 1f)
        if (revealWidthPx <= 0f) return from
        return (from - dxPx / revealWidthPx).coerceIn(0f, 1f)
    }

    /**
     * 松手时是否吸附到**露出删除按钮**（true = 停在露出位置，false = 回弹收起）。
     *
     * 判据看的是**最终比例**（不是本次手势滑了多远）——"已经露出时往回拖"天然就成了收起，
     * 不需要另一套状态。
     *
     * @param revealFraction 松手瞬间的露出比例（0 ~ 1）
     * @param velocityXPx 松手瞬间的横向速度（左为负）
     * @param density 屏幕密度（dp → px）
     */
    fun swipeRevealsDeleteButton(
        revealFraction: Float,
        velocityXPx: Float,
        density: Float,
    ): Boolean {
        if (revealFraction <= 0f) return false
        // 往回甩（往右）→ 回弹收起，哪怕已经露出大半
        if (velocityXPx >= SWIPE_FLING_DP_PER_SECOND * density) return false
        // 快速一划 → 露出（只看"有没有真的压出来一点"，不看压了多宽）
        if (velocityXPx <= -SWIPE_FLING_DP_PER_SECOND * density &&
            revealFraction >= SWIPE_FLING_MIN_REVEAL
        ) {
            return true
        }
        return revealFraction >= SWIPE_OPEN_FRACTION
    }

    /**
     * 手指按下时是否落在**已露出的删除按钮**上。
     *
     * x 必须用**行内坐标**（`change.position.x`），不能用屏幕坐标——列表有滚动偏移，屏幕坐标一定算错。
     * 命中这一条时，行的手势状态机要**整块退出**，把事件让给按钮自己的点击处理
     * （父级 `pointerInput` 先于子级拿到事件，抢着消费的话按钮永远点不到）。
     *
     * 按钮盖的是**行尾**（角色名 / 等级那一头），所以命中区永远贴右缘。
     */
    fun isOnRevealedDeleteButton(
        xPx: Float,
        rowWidthPx: Float,
        revealWidthPx: Float,
        revealed: Boolean,
    ): Boolean {
        if (!revealed || revealWidthPx <= 0f || rowWidthPx <= 0f) return false
        return xPx >= rowWidthPx - revealWidthPx
    }
}
