package com.example.mastermechanic.capture

/**
 * **"我们这个进程现在算得动吗"**（2026-10-19，用户口径：重新授权后第一轮不该让用户自己等）。
 *
 * ## 为什么需要它（真机证据）
 *
 * 同一次进程运行里，**灰度换算**（一段只做读像素 + 平均的循环）的 P95：
 *
 * | 时刻 | 会话 | 灰度 P95 | 识别 P95 | 每轮识别 |
 * | --- | --- | --- | --- | --- |
 * | 23:43:40 | 第 1 次（进程刚起） | 20.9ms | 369ms | 慢 |
 * | 23:48:21 | 第 2 次（重授权后的第一轮） | **35.5ms** | **697ms** | **≈2.8s** |
 * | 23:49:15 起（稳定期） | 同上 | 3.7~4.2ms | 78~82ms | ≈0.3s |
 *
 * 灰度慢 9 倍、识别也慢 9 倍 ⇒ **不是算法/页面内容的问题，是这个进程这一段时间拿不到 CPU**
 * （JIT 预热或系统合成抢占）。用户看到的就是"重新授权后的第一轮特别慢"，而 A（点击闸）/B（慢供给）
 * 都不会出手 —— 它们管的是"帧旧"和"平台送得慢"，这件事是"**我们自己算得慢**"。
 *
 * ## 口径
 *
 * - **只看本轮总耗时**（灰度 + 识别），不看是谁慢：两种情况（预热 / 被抢）处置一样 —— 等一等再开跑；
 * - 用**最近几轮的中位数**而不是单次、也不是 P95：样本只有 6 个时 P95 ≈ 最大值，
 *   而第 5 步 / 第 9 步要读好友名（OCR 那几轮天然 300~400ms）⇒ 用 P95 会把"正常巡逻"判成"没热好"。
 *   中位数才能回答"这台机器现在整体上算不算得动"，又不受一两轮 OCR 影响；
 * - 样本有**时效**（[STALE_MS]）：十分钟前的耗时不代表现在；
 * - 纯逻辑、无 Android 依赖，可直接单测。
 */
class RoundCostWatch(
    private val capacity: Int = CAPACITY,
    private val minSamples: Int = MIN_SAMPLES,
    private val warmTypicalMs: Double = WARM_TYPICAL_MS,
    private val staleMs: Long = STALE_MS,
) {

    /** 见类注释。 */
    enum class Status {
        /** 样本不足 / 都过期了：**没有证据就不拦**（不能因为"不知道"把用户卡住）。 */
        NO_DATA,

        /** 算得动：可以开跑。 */
        WARM,

        /** 明确算不动（最近几轮 P95 超阈值）：先等它降下来，别在慢动作里点第一枪。 */
        WARMING,
    }

    private class Sample(val atMs: Long, val costMs: Double)

    private val samples = ArrayDeque<Sample>()

    /** 记录一轮的总耗时（毫秒）。 */
    fun record(atMs: Long, costMs: Double) {
        samples.addLast(Sample(atMs, costMs))
        while (samples.size > capacity) samples.removeFirst()
    }

    /** 当前结论（[nowMs] 用于判断样本是否过期）。 */
    fun status(nowMs: Long): Status {
        val typical = typicalMs(nowMs) ?: return Status.NO_DATA
        return if (typical <= warmTypicalMs) Status.WARM else Status.WARMING
    }

    /** 最近几轮（未过期）的**中位数**耗时；样本不足返回 null。 */
    fun typicalMs(nowMs: Long): Double? {
        val fresh = freshSamples(nowMs)
        if (fresh.size < minSamples) return null
        val sorted = fresh.map { it.costMs }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** 参与判定的样本数（未过期）。 */
    fun sampleCount(nowMs: Long): Int = freshSamples(nowMs).size

    fun reset() = samples.clear()

    private fun freshSamples(nowMs: Long): List<Sample> =
        samples.filter { nowMs - it.atMs <= staleMs }

    companion object {
        /** 取最近 6 轮：够平掉 OCR 那种偶发慢，又不至于把"刚变慢"埋掉。 */
        const val CAPACITY = 6

        /** 至少 2 轮才下结论：单轮可能是 OCR / 首次调用。 */
        const val MIN_SAMPLES = 2

        /**
         * "算得动"的门限（ms，最近几轮**中位数**）。
         *
         * 真机稳定期：灰度 3.7ms + 识别 78ms ≈ **82ms**；正常巡逻含 OCR 时 P95 为 **122~160ms**（最大 232ms）；
         * 出问题那一段是 **697ms**（每轮 2.8 秒）。取 250ms 把"正常"与"出问题"分开，
         * 中位数又不会被一两轮 OCR 拉过去。
         */
        const val WARM_TYPICAL_MS = 250.0

        /** 样本时效（ms）：10 秒前的耗时不代表现在（用户可能刚切了页面 / 系统刚恢复）。 */
        const val STALE_MS = 10_000L
    }
}

/**
 * 进程级读数（2026-10-19）：帧线程写、主线程读（"开跑前自检"在菜单线程）。
 *
 * 与 `FrameFreshness` 同一种形态（object + 静态方法），因为两边跨包、且都只有一个采集会话。
 */
object FrameReadiness {

    private val watch = RoundCostWatch()

    /** 帧线程：每轮识别结束记一笔。 */
    @Synchronized
    fun noteRoundCost(atMs: Long, costMs: Double) = watch.record(atMs, costMs)

    /** 结论（见 [RoundCostWatch.Status]）。 */
    @Synchronized
    fun status(nowMs: Long): RoundCostWatch.Status = watch.status(nowMs)

    /** 最近几轮的**中位数**耗时（ms）；样本不足返回 null。 */
    @Synchronized
    fun typicalMs(nowMs: Long): Double? = watch.typicalMs(nowMs)

    /** 参与判定的样本数。 */
    @Synchronized
    fun sampleCount(nowMs: Long): Int = watch.sampleCount(nowMs)

    /** 会话重建 / 结束：读数不跨会话。 */
    @Synchronized
    fun reset() = watch.reset()
}
