package com.example.mastermechanic.log

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * **主线程卡死看门狗**（2026-09-29）：主线程一旦超过 [STUCK_MS] 没有心跳，就把它**卡在哪一行**记进日志。
 *
 * ## 为什么需要它（这条线踩了太多次）
 *
 * 到 2026-09-29 为止，"黑屏 + 崩溃"这一族故障已经被证实**不止一个原因**：
 * ```
 * ① GC 风暴（≈350MB/s 的短命小对象）→ 主线程被 GC 拖死   ← 帧预览重复解码，已修
 * ② 前台判定抖动 → 悬浮窗反复挂载/移除（主线程同步 binder）← 已修
 * ③ 剩下这一种：探针显示分配 0~1.7MB/s、查询 1~3ms、**一切平静**，7 秒后主线程仍然卡死 5 秒
 * ```
 * 第 ③ 种没有任何"量"上的先兆 ⇒ **必须有栈**。而 ANR 栈拿不到（`/data/anr` 要 root，
 * 主缓冲区又常被 GC 日志冲掉）⇒ 就在**进程内**自己取：
 *
 * ## 怎么工作
 *
 * - 本看门狗线程每 [BEAT_INTERVAL_MS] 往**主线程**投一个"心跳"任务（`handler.post`）；
 * - 主线程正常 ⇒ 心跳被及时执行 ⇒ 间隔很小；
 * - 主线程被卡住（锁 / binder / 卡在某个调用里）⇒ 心跳排不上队 ⇒ 间隔就是**卡住的时长**；
 *   > [STUCK_MS] 时打印**主线程当时的调用栈**（[Thread.getStackTrace]，只取栈顶若干帧，绝不复读）。
 *
 * ## 代价
 *
 * 两次 `Handler.post` + 一个 `Volatile` 读/写每秒 —— 与"再也查不出原因"相比可以忽略。
 * 只在卡住时写日志（写一次就等它恢复，避免刷屏）。
 */
object MainThreadWatchdog {

    /** 心跳间隔。 */
    private const val BEAT_INTERVAL_MS = 500L

    /** 超过这么久没有心跳 ⇒ 判定主线程卡住（ANR 阈值是 5 秒，这里提前 2 秒报，留出读日志的时间）。 */
    private const val STUCK_MS = 3_000L

    /** 栈最多打这么多帧（够定位到"卡在哪一层"，又不至于把日志写爆）。 */
    private const val MAX_FRAMES = 25

    /** 最近一次"主线程还活着"的时刻。 */
    @Volatile
    private var lastBeatAt = SystemClock.elapsedRealtime()

    @Volatile
    private var started = false

    /** 启动（可由 `Application.onCreate` 调用；重复调用安全）。 */
    fun start() {
        if (started) return
        started = true
        lastBeatAt = SystemClock.elapsedRealtime()
        Thread({ loop() }, "MM-MainWatch").apply { isDaemon = true }.start()
    }

    /** 取一次主线程栈（失败只记一行，不让看门狗自己抛）。 */
    private fun snapshot(thread: Thread): String = try {
        thread.stackTrace.take(MAX_FRAMES).joinToString("\n    at ") { it.toString() }
    } catch (t: Throwable) {
        "（取栈失败：${t.javaClass.simpleName}）"
    }

    private fun loop() {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        var reported = false
        while (true) {
            main.post { lastBeatAt = SystemClock.elapsedRealtime() }
            try {
                Thread.sleep(BEAT_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastBeatAt
            if (gap < STUCK_MS) {
                reported = false
                continue
            }
            // 已经报过就等它恢复：卡死期间每一轮都打会把日志刷爆（而且那时也写不快）
            if (reported) continue
            reported = true
            // **连打两张栈**（2026-09-29）：一张栈分不清"在跑"还是"在等" ——
            // 隔 1 秒再取一次：两张栈**不同** ⇒ 主线程在某个循环里跑（CPU 型）；
            // 两张**完全相同** ⇒ 它停在同一个调用上（锁 / binder / 等待型）。这一条能省掉一整轮猜测。
            val first = snapshot(mainThread)
            try {
                Thread.sleep(1_000L)
            } catch (interrupted: InterruptedException) {
                return
            }
            val second = snapshot(mainThread)
            val same = first == second
            val heap = Debug.getNativeHeapAllocatedSize() / 1_000_000.0
            MmLog.w(
                "MM-Watch",
                "主线程已 ${gap}ms 没有心跳（ANR 阈值 5 秒）⇒ ${if (same) "**停在同一个调用上（等待型）**" else "**在跑（CPU 型）**"}；" +
                    "原生堆 ${String.format(Locale.US, "%.0f", heap)}MB\n【第 1 张】\n    at $first\n【第 2 张（1 秒后）】\n    at $second",
            )
        }
    }
}
