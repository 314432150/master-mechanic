package com.example.mastermechanic.ui

import kotlin.math.abs

/**
 * 标定工作台**缩略图上 / 下滑删除**的纯逻辑（2026-09-23 用户要求：帧列表里有很多不需要的无效帧，
 * 希望"缩略图上划或者下划弹出删除确认"）。
 *
 * 为什么单独拿出来（与 [ServerListGestures] 同一个理由）：手势本身（谁抢事件、跟不跟手、回弹顺不顺）
 * 只能在真机上核；但"划多远、甩多快才算数"是纯算术，一律放进 JVM 单测
 * （[com.example.mastermechanic.ui.FrameThumbSwipeTest]）。
 *
 * **横向手势不在这里判**：缩略图条是**横向滚动列表**，横向位移必须让给帧条自己滚 ——
 * 这件事交给 `Modifier.draggable(orientation = Orientation.Vertical)`：它只在纵向占优时才开始拖，
 * 横向占优时整个手势原样交还给父级。所以本对象只回答一个问题：
 * **纵向划了这么远、甩了这么快，算不算一次删除意图**。
 *
 * 阈值单位说明与 [ServerListGestures] 一致：`dp` 常量乘屏幕密度换算成像素后与位移比较；
 * 时间 / 触摸容差一律走 `ViewConfiguration`（系统给的），**不自己写毫秒数**。
 */
internal object FrameThumbSwipe {

    /**
     * 触发删除确认的最小纵向位移（dp）。
     *
     * 取 40dp（≈ 缩略图 64dp 的六成）：比一半多一点，意图明确，又不至于"要划得很远才认"。
     */
    const val DELETE_DISTANCE_DP = 40f

    /**
     * 甩动速度阈值（dp/s）：位移不够但甩得够快也算 —— "快速一划"是常见操作习惯
     * （与清单左滑那条捷径同一口径，见 [ServerListGestures.SWIPE_FLING_DP_PER_SECOND]）。
     */
    const val DELETE_FLING_DP_PER_SECOND = 300f

    /**
     * 甩动这条捷径的**最小位移**（dp）：只认"真的划出去一段"，
     * 否则手指微抖 + 抬手速度大也会弹确认框（抬手瞬间的速度本来就不稳定）。
     */
    const val FLING_MIN_DISTANCE_DP = 12f

    /**
     * 拖动时缩略图最多被拽走多远（dp）：要有跟手感，但不能让它跑出帧条、盖住旁边的帧。
     */
    const val MAX_PULL_DP = 20f

    /**
     * 松手时是否要弹删除确认。
     *
     * **方向不限**（用户口径"上划或者下划"）：帧条里这两个方向没有不同含义，
     * 所以一律取绝对值判定 —— 只有横向会被让给帧条（那件事由 `draggable` 的方向判定负责）。
     *
     * @param dyPx 本次手势累计的纵向位移（上滑为负、下滑为正）
     * @param velocityYPx 松手瞬间的纵向速度（px/s）
     * @param density 屏幕密度（dp → px）
     */
    fun isDeleteSwipe(dyPx: Float, velocityYPx: Float, density: Float): Boolean {
        val distance = abs(dyPx)
        if (distance >= DELETE_DISTANCE_DP * density) return true
        return abs(velocityYPx) >= DELETE_FLING_DP_PER_SECOND * density &&
            distance >= FLING_MIN_DISTANCE_DP * density
    }
}
