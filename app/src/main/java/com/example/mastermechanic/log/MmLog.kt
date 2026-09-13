package com.example.mastermechanic.log

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 业务日志双写（T2-7）：**logcat + 应用私有文件**。
 *
 * 为什么需要它：设备上存在高频噪音日志（实测某应用每几毫秒一条 `HWUI BlurEffectManager`），
 * 会把 logcat 的 `main` 缓冲在**数秒内**刷满——2026-09-13 抓到的 677 行快照里 `MM-` 记录为 **0 条**，
 * 事后抓 logcat 取不到任何证据。关键日志必须自己落盘：不依赖 adb、不怕缓冲被挤爆或回卷。
 * M5 的全流程点击审计同样依赖这条通道。
 *
 * - 落盘位置：`files/logs/mm-log-yyyyMMdd.txt`（应用私有目录，随应用卸载清除；按天分文件）；
 * - 线程：logcat 写在调用线程（与原来一致，开销可忽略），**文件落盘走单线程队列**——调用方（帧线程 / 主线程）只入队；
 * - 有界：单文件超过 [MAX_BYTES] 时保留后半（按行切），不无限增长；
 * - 未注入 Context（纯 JVM / 单测）时**只写 logcat**，不抛异常。
 *
 * 用法：与 `android.util.Log` 同签名（`d/i/w/e`），应用启动时由 `MasterMechanicApp` 注入 Context。
 */
object MmLog {

    private const val DIR = "logs"
    private const val FILE_PREFIX = "mm-log-"
    private const val MAX_BYTES = 512 * 1024
    private const val ROLL_HEADER = "……（文件超过上限，已清理前半，以下为最近记录）"
    private const val FALLBACK_TAG = "MM-Log"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mm-log").apply { isDaemon = true }
    }
    private val fileLock = Any()

    @Volatile
    private var appContext: Context? = null

    /** 应用启动时注入（`MasterMechanicApp.onCreate`）。未注入 = 只写 logcat。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 当前落盘文件（供取证 / 日志说明用）；未注入 Context 返回 null。 */
    fun currentFile(): File? {
        val ctx = appContext ?: return null
        return File(File(ctx.filesDir, DIR), "$FILE_PREFIX${MmLogFormat.dayKey(System.currentTimeMillis())}.txt")
    }

    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message, null)

    fun d(tag: String, message: String, throwable: Throwable?) = write(Log.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)

    fun i(tag: String, message: String, throwable: Throwable?) = write(Log.INFO, tag, message, throwable)

    fun w(tag: String, message: String) = write(Log.WARN, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable?) = write(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String) = write(Log.ERROR, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable?) = write(Log.ERROR, tag, message, throwable)

    private fun write(priority: Int, tag: String, message: String, throwable: Throwable?) {
        when (priority) {
            Log.DEBUG -> Log.d(tag, message)
            Log.INFO -> Log.i(tag, message)
            Log.WARN -> Log.w(tag, message)
            else -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
        val ctx = appContext ?: return
        val nowMs = System.currentTimeMillis()
        executor.execute { appendToFile(ctx, tag, message, nowMs) }
    }

    private fun appendToFile(context: Context, tag: String, message: String, nowMs: Long) {
        try {
            val dir = File(context.filesDir, DIR)
            if (!dir.exists() && !dir.mkdirs()) return
            val file = File(dir, "$FILE_PREFIX${MmLogFormat.dayKey(nowMs)}.txt")
            synchronized(fileLock) {
                file.appendText("${MmLogFormat.line(tag, message, nowMs)}\n", Charsets.UTF_8)
                if (file.length() > MAX_BYTES) {
                    val trimmed = MmLogFormat.trimIfNeeded(
                        file.readText(Charsets.UTF_8),
                        MAX_BYTES,
                        ROLL_HEADER,
                    )
                    if (trimmed != null) file.writeText("$trimmed\n", Charsets.UTF_8)
                }
            }
        } catch (t: Throwable) {
            // 只能用系统 Log：回调 MmLog 会造成递归（此分支本身就是"落盘失败"）。
            Log.w(FALLBACK_TAG, "日志落盘失败：${t.message}")
        }
    }
}
