package com.example.mastermechanic.action

import kotlin.math.abs

/**
 * **"开火前复眼"的判据**（纯逻辑，2026-09-29）。
 *
 * ## 它要解决的那件事（用户原话："**如何绝对防止在大厅里误点进入商城**"）
 *
 * 先进的事实（`docs/progress.md` 第 189 条）：**关闭控件（X）的模板在大厅上并不命中** ——
 * 回到大厅之后 `activity_popup_e1/e2/e3` 只有 0.06~0.28。⇒ 所以"点进商城"**不可能**是
 * "在活的大厅画面上认出弹窗"造成的，它只有一条路径：
 *
 * > **判定用的那张画面比真实屏幕旧**：旧画面上是弹窗的 X，而**同一个坐标**在当下的屏幕上
 * > 正好是大厅的商城入口（两次事故 2026-09-24 18:51 / 2026-09-28 21:41 都是这一形态）。
 *
 * 于是防线可以归结成一句话：**开火前，手里的画面必须还是"现在"**。分两层：
 *
 * 1. `PopupCloseController.maxFrameAgeMs`（500ms）：**决策帧本身**不能太旧；
 * 2. **本类**：下发之前，让**采集层现取一张当时最新的画面**（`CaptureService.acquireFreshFrame`，
 *    `ImageReader.acquireLatestImage()`，真机约 20~40ms 前），在**同一个锚点**上再确认一次
 *    "X 还在原处"；不在了 / 挪了 ⇒ **这一枪不发**。
 *
 * ⚠ 采集层**拿不到更新的一帧**时（`acquireLatestImage()` 给 null）**本条不参与**：平台
 * **只在内容变化时才产帧**（"静止兜底重放"正是因为这条而存在）⇒ 队列里没有新帧就说明屏幕自我们那张
 * 画面之后没变过，交给原有那几道闸判即可。
 *
 * ## 为什么是"重新命中 + 位置一致"而不是"像素差异"
 *
 * 判据直接复用**决策本身用的那套证据**（模板命中 + 命中线），因此不需要任何新阈值、也不会
 * 被弹窗自身的小动画骗到（那些动画会让"像素差"乱跳，却动不了 X 的命中位置）。位置容差
 * [MAX_DRIFT_PX] 只吸收"同一张画面上重跑一次匹配"的正常抖动。
 *
 * ## ⚠ 剩余边界（如实记）
 *
 * **"最新一帧拍下" → "手指落下"之间那段手势延迟**（几十到 ~150ms，`dispatchGesture` 注入 +
 * 平台派发）是物理上消除不掉的。也就是说，除非屏幕**恰好在这 0.1 秒里自己变了**（弹窗自动消失 /
 * 用户同时手动点了它），否则打不偏。
 */
object PreFireRecheck {

    /**
     * **同一锚点在两张画面上允许的位置漂移**（运行帧像素，取 16）。
     *
     * 同一帧上重跑一次匹配的正常抖动只有 ±几像素（真机实测 `hall_settings` 的命中位置
     * 与窗口中心差 ≈20px，但那是**窗口框选偏离**，两次匹配之间不会变）；16px 足够吸收抖动，
     * 又远小于"弹窗 X / 商城入口"这类不同控件的间距。
     */
    const val MAX_DRIFT_PX = 16.0

    /**
     * 复眼结论：最新画面上**重新命中**的落点，与决策帧上的落点是不是"同一个"。
     *
     * @param freshX / [freshY] 最新画面上的命中点；**null = 那张画面上已经不命中**（X 没了）⇒ 不通过
     */
    fun agrees(
        originalX: Double,
        originalY: Double,
        freshX: Double?,
        freshY: Double?,
        maxDriftPx: Double = MAX_DRIFT_PX,
    ): Boolean {
        if (freshX == null || freshY == null) return false
        if (!freshX.isFinite() || !freshY.isFinite()) return false
        return abs(freshX - originalX) <= maxDriftPx && abs(freshY - originalY) <= maxDriftPx
    }
}
