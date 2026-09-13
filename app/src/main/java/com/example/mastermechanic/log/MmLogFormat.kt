package com.example.mastermechanic.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志行格式与滚动（T2-7，**纯函数**，JVM 可测）。
 *
 * 时间戳格式与 logcat 的 `-v threadtime` 中段一致（`MM-dd HH:mm:ss.SSS`），便于把文件行与真机日志逐行对照。
 */
object MmLogFormat {

    private const val STAMP_PATTERN = "MM-dd HH:mm:ss.SSS"

    /** 文件名的日期键（按天分文件）。 */
    fun dayKey(nowMs: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))

    /** 与 logcat 同格式的时间戳。 */
    fun stamp(nowMs: Long): String = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(nowMs))

    /** 一行落盘记录：`MM-dd HH:mm:ss.SSS TAG: message`。 */
    fun line(tag: String, message: String, nowMs: Long): String = "${stamp(nowMs)} $tag: $message"

    /**
     * 超限滚动：保留**后半部分的行**（不切半行，避免留下残缺记录）。
     *
     * @param maxBytes 上限（字节）；未超限返回 null，调用方据此跳过重写（避免每次写日志都读全文）。
     */
    fun trimIfNeeded(text: String, maxBytes: Int, header: String): String? {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return null
        val lines = text.split("\n")
        if (lines.size < 2) return null
        return "$header\n" + lines.drop(lines.size / 2).joinToString("\n")
    }
}
