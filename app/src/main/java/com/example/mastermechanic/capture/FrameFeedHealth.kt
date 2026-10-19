package com.example.mastermechanic.capture

/**
 * **帧供给体检**（2026-10-19）：回答"画面跟不上，是平台送得慢，还是我们自己算得慢"。
 *
 * ## 它补的是哪个洞
 *
 * 现有看门狗只管**两种极端**：
 * - `FrameFreshness.FIRST_FEED_DEADLINE_MS`（8 秒零帧 ⇒ 这次授权没被投喂）；
 * - `FrameFreshness.STALE_MS`（60 秒没有新帧 ⇒ 停更，只提示 + 停点击）。
 *
 * 真机 2026-10-19 那两轮「换号+拜访」正好落在两者之间：第一轮 **64.2 秒**、第二轮 **39.3 秒**
 * （同一条流程、同一份标定），日志差异是
 * `帧管线统计: 窗口 11270ms 接收 37 帧 / 处理 4 轮`（第一轮，每轮 ≈2.8s）
 * vs `窗口 10044ms 接收 341 帧 / 处理 33 轮`（第二轮，每轮 ≈0.3s）。
 * 这种"帧在来、但一轮要算 1~3 秒"的状态既不触发"零帧"、也不触发"停更"
 * ⇒ **没有任何人发现，也没有任何自救**，用户只能自己重新授权碰运气。
 *
 * ## 为什么不能看"回调次数/秒"（第一版就是这么错的）
 *
 * 回调与识别**跑在同一条帧线程**上：一轮算 2.8 秒的期间，回调根本没机会被处理
 * （`ImageReader` 只有 `MAX_IMAGES = 2` 个缓冲）⇒ "回调 3.3 次/秒"既可能是**平台只喂 3 帧/秒**，
 * 也可能是**我们算得慢把自己堵住了**。两种原因的处置**相反**（换镜像表面 vs 减每轮成本），
 * 拿这个数去下结论必然误判一半。
 *
 * ## 本类的判据：**我们空闲下来之后，平台多久给下一帧**
 *
 * 取时点很关键 —— **一轮识别刚结束的那一刻**我们一定是空闲的：
 * - 平台真有 30fps ⇒ 队列里早就有帧在等 ⇒ 间隔几个毫秒（[FAST_GAP_MS] 以内）；
 * - 平台只喂 3fps ⇒ 还要等 ≈330 毫秒以上（[SLOW_GAP_MS] 以上）。
 *
 * 于是：
 * | 观测 | 结论 | 处置（调用方） |
 * | --- | --- | --- |
 * | 轮次结束后 ≤ [FAST_GAP_MS] 就来帧，反复如此 | 平台跟得上 | 什么都不做；**并清掉之前的慢计数**（自愈证据） |
 * | 轮次结束后 ≥ [SLOW_GAP_MS] 才来帧，连续 [SLOW_HITS_TO_JUDGE] 次 | **平台送得慢** | 换镜像表面（采集侧唯一的自救） |
 * | 轮次结束后**根本不来帧** | 屏幕静止，或停更 | **本类不表态**（交给 `FrameFreshness` 两条既有判据） |
 *
 * ⚠ 第三行是刻意的：`FrameFreshness` 的类注释已经说透"停更是现象、不是证据"
 * （静止页面本来就不产帧）⇒ 这里绝不把"没有下一帧"算成慢，否则每个静态页都会被反复判故障。
 *
 * ## 口径
 *
 * - **纯逻辑、无 Android 依赖**（可直接单测）：采集层只喂两个时刻，判定在这里；
 * - **只由帧线程调用**（与 `CaptureService` 的帧回调同一条线程）；
 * - 计数有**时效**（[JUDGE_WINDOW_MS]）：慢过一次之后平台恢复了，结论要自己过期 ——
 *   否则一次冷启动会被记一整场。
 */
class FrameFeedHealth {

    /** 一次观测（只用于日志：把"等了多久"如实写出来）。 */
    data class Observation(val gapMs: Long, val slow: Boolean, val hits: Int)

    private var lastRoundEndMs = 0L
    private var slowHits = 0
    private var lastSlowAtMs = 0L
    private var lastFastGapMs = -1L

    /**
     * 一轮识别**结束**了（含静止兜底那一轮；调用点必须在 `processPixels` 返回之后）。
     *
     * 只记"最近一次结束时刻"：下一次回调与它的差就是**平台把下一帧交到我们手上要多久**。
     */
    fun noteRoundEnd(nowMs: Long) {
        lastRoundEndMs = nowMs
    }

    /**
     * 平台回调了一次（`onImageAvailable`）。
     *
     * ⚠ 只在**上一轮已经结束**时才参与判定：一轮算到一半时来的回调，间隔里混着我们的处理耗时，
     * 拿它算等于把"我们慢"记成"平台慢"（第一版的错，见类注释）。
     *
     * @return 本次观测（没有可比基线时返回 null）
     */
    fun noteCallback(nowMs: Long): Observation? {
        val roundEnd = lastRoundEndMs
        if (roundEnd <= 0L) return null
        lastRoundEndMs = 0L
        val gap = nowMs - roundEnd
        return when {
            gap >= SLOW_GAP_MS -> {
                slowHits++
                lastSlowAtMs = nowMs
                Observation(gap, slow = true, hits = slowHits)
            }

            gap <= FAST_GAP_MS -> {
                // 平台跟得上 ⇒ 之前攒的慢计数作废（"慢"必须是**当前**状态，不能是历史包袱）
                slowHits = 0
                lastFastGapMs = gap
                Observation(gap, slow = false, hits = 0)
            }

            else -> Observation(gap, slow = false, hits = slowHits)
        }
    }

    /**
     * **当前是否判"平台送得慢"**：连续 [SLOW_HITS_TO_JUDGE] 次慢，且最近一次慢在 [JUDGE_WINDOW_MS] 内。
     *
     * 为什么要"连续"：冷启动头一两帧慢很正常（会话刚建、合成器还没接上），
     * 单次慢就换表面等于每场都白抽一次（每次要新建 18MB×2 的缓冲）。
     */
    fun isSlow(nowMs: Long): Boolean {
        if (slowHits < SLOW_HITS_TO_JUDGE) return false
        if (lastSlowAtMs <= 0L) return false
        return nowMs - lastSlowAtMs <= JUDGE_WINDOW_MS
    }

    /** 最近一次"快"的间隔（ms，-1 = 还没见过一次快帧）：只用于日志。 */
    fun lastFastGapMs(): Long = lastFastGapMs

    /** 连续慢了几次（只用于日志）。 */
    fun slowHits(): Int = slowHits

    /** 会话结束 / 重建：观测不跨会话（新会话的空闲间隔没有可比性）。 */
    fun reset() {
        lastRoundEndMs = 0L
        slowHits = 0
        lastSlowAtMs = 0L
        lastFastGapMs = -1L
    }

    companion object {
        /**
         * "平台跟得上"的间隔上限（ms）。
         *
         * 取 120：真机 60fps 的会话里，一轮结束到下一帧送达是两个调度周期的事（≈数十毫秒）；
         * 这个门限足够宽松，不会把正常的调度抖动记成"慢"。
         */
        const val FAST_GAP_MS = 120L

        /**
         * "平台送得慢"的间隔下限（ms）。
         *
         * 取 400：它等价于"平台连 2.5fps 都不到"。真机第一轮那种状态（每轮 2.8 秒、帧龄 1~2.8 秒）
         * 必然命中；而正常的 30/60fps 会话永远到不了这个数。
         */
        const val SLOW_GAP_MS = 400L

        /** 连续几次"慢"才下结论（见 [isSlow]）。 */
        const val SLOW_HITS_TO_JUDGE = 3

        /** 慢结论的时效（ms）：平台恢复之后，结论自己过期（见类注释）。 */
        const val JUDGE_WINDOW_MS = 8_000L
    }
}
