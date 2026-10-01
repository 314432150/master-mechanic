package com.example.mastermechanic.calibration

/**
 * 「标定产物刚被写入」的通知（2026-09-21）：让**正在跑的采集会话就地重载产物**。
 *
 * ## 为什么要有它
 *
 * 原来的口径是"标定保存后，重开会话才生效"（[CalibrationStore] 的注释一直这么写）。
 * 真机上这个口径很容易踩：重框了头像、回到农场一看，**分数和重框前一模一样** ——
 * 因为跑的还是内存里那份旧产物（2026-09-21 实录：重框前 0.5789 / 重框后 0.5788，
 * 数值几乎一致，白跑一轮才知道模板没换）。
 *
 * 现在 [CalibrationStore.save] 写完就 [request] 一次，采集侧在帧线程上按 [tick] 变化重载。
 * 标定 → 复测之间不再需要"停采集、重开"这一手。
 *
 * 用计数器而不是布尔位：短时间内连续写两次（改备注 + 写模板）也不会漏掉后一次。
 */
object CalibrationReloadSignal {

    /** 写入次数；**只用来判断"有没有变过"**，值本身没有含义。 */
    @Volatile
    var tick: Long = 0L
        private set

    /** 记一次"产物已写入"。由 [CalibrationStore.save] 调用，界面不必也不该手动调。 */
    fun request() {
        tick++
    }
}
