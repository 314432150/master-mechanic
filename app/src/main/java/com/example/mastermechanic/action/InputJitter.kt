package com.example.mastermechanic.action

import kotlin.math.PI
import kotlin.random.Random

/**
 * 输入拟人化（2026-09-21 用户口径）：**每一次点击的"落点"与"按压过程"都不完全一样**。
 *
 * ## 叫什么
 *
 * 这类做法的通行叫法是 **输入拟人化 / 输入抖动**（human-like input / input jitter）；
 * 它对抗的是游戏的**反自动化检测**（anti-bot / anti-cheat）——"反爬"是网页爬虫语境的说法。
 * 机器人特征里最容易被抓的两条：**永远落在同一个像素**、**永远同一时长同一轨迹**。
 *
 * ## 与 ADR-002「不设计任何隐蔽注入」不冲突
 *
 * 注入途径没变（仍是公开的无障碍 `dispatchGesture` 这一条），也没有隐瞒"程序在点击"；
 * 变的只是落点与节奏的**取值分布**。这条口径已写进 ADR-002 的补记。
 *
 * ## 幅度必须受控（红线 3 / 7：落空是安全的，猜中才是危险的）
 *
 * 默认半径只有 [DEFAULT_RADIUS_PX] 屏幕像素 —— 目标是"不总是同一个像素"，**不是**"点得偏一点"。
 * 落点抖动由 [ClickForwarder] 在**换算成屏幕坐标之后**施加，并夹回显示区域内，
 * 所以不会因为抖动点到屏幕外，也不会偏离锚点中心太多。
 *
 * ## 可复现
 *
 * [random] 可注入：单测用固定种子 → 结果逐次可复现（§5-4「同数据同结果」的例外仅限本模块，
 * 见 ADR-002 补记；判定与识别链路仍然完全确定性）。
 */
class InputJitter(
    private val random: Random = Random.Default,
    /** 落点抖动半径（屏幕像素）；**0 = 不抖**（单测与排障需要"完全确定"时用）。 */
    val radiusPx: Int = DEFAULT_RADIUS_PX,
) {

    /** 落点抖动（屏幕像素）：均匀取在 `[-radiusPx, radiusPx]`（含端点）。 */
    fun offset(): Int = if (radiusPx <= 0) 0 else random.nextInt(-radiusPx, radiusPx + 1)

    /** 按压时长（ms）：真人的短按不是恒定值，取 [PRESS_MIN_MS] ~ [PRESS_MAX_MS]。 */
    fun pressDurationMs(): Long = random.nextLong(PRESS_MIN_MS, PRESS_MAX_MS + 1)

    /** 短行程长度（px）：**不留零长路径**，也不写死成同一条斜线。 */
    fun travelPx(): Int = random.nextInt(TRAVEL_MIN_PX, TRAVEL_MAX_PX + 1)

    /** 短行程方向（弧度，0 ~ 2π）。 */
    fun travelAngleRadians(): Double = random.nextDouble(0.0, 2 * PI)

    companion object {

        /** 落点抖动半径（屏幕像素）：3px 在 3168×1440 上约千分之一屏宽，肉眼与服务都不敏感。 */
        const val DEFAULT_RADIUS_PX = 3

        /** 按压时长区间（ms）：落在真人短按（约 40~80ms）区间内。 */
        const val PRESS_MIN_MS = 40L
        const val PRESS_MAX_MS = 80L

        /** 短行程长度区间（px）：1~3px，够让路径不退化成一个点。 */
        const val TRAVEL_MIN_PX = 1
        const val TRAVEL_MAX_PX = 3
    }
}
