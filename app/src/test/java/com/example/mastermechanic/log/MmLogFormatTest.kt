package com.example.mastermechanic.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志落盘格式与滚动（T2-7）：纯函数部分。
 *
 * 为什么要测：2026-09-13 实测证明 logcat 取证不可靠（设备高频噪音把 main 缓冲数秒刷满，
 * 677 行快照里 `MM-` 0 条）——落盘文件成为**唯一取证通道**，其格式与滚动行为必须有据可查。
 */
class MmLogFormatTest {

    /** 时间戳格式与 logcat `-v threadtime` 中段一致，便于把文件行与真机日志逐行对照。 */
    @Test
    fun lineCarriesLogcatCompatibleTimestamp() {
        val nowMs = 1_756_000_000_000L // 固定时刻，避免依赖真实时钟
        val line = MmLogFormat.line("MM-Click", "点击拒绝（演练模式）", nowMs)
        assertTrue(
            "行首应为 MM-dd HH:mm:ss.SSS 时间戳：$line",
            Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ").containsMatchIn(line),
        )
        assertTrue("行内应含 TAG", line.contains("MM-Click: "))
        assertTrue("行内应含消息", line.endsWith("点击拒绝（演练模式）"))
    }

    @Test
    fun dayKeyIsEightDigits() {
        assertEquals(8, MmLogFormat.dayKey(1_756_000_000_000L).length)
    }

    /** 未超限不滚动（调用方据此跳过"读全文 + 重写"，避免每次写日志都读整个文件）。 */
    @Test
    fun trimReturnsNullWhenUnderLimit() {
        val text = (1..10).joinToString("\n") { "line-$it" }
        assertNull(MmLogFormat.trimIfNeeded(text, maxBytes = 4 * 1024, header = "H"))
    }

    /** 超限保留后半，且**按行切**——不得留下半行残句。 */
    @Test
    fun trimKeepsSecondHalfOnWholeLines() {
        val text = (1..200).joinToString("\n") { "line-$it-${"x".repeat(20)}" }
        val trimmed = MmLogFormat.trimIfNeeded(text, maxBytes = 512, header = "HEADER")
        assertNotNull("超限应返回裁剪结果", trimmed)
        val body = trimmed!!.lines()
        assertEquals("首行为滚动说明", "HEADER", body.first())
        assertTrue("应保留后半行数", body.size in 90..110)
        val kept = body.drop(1)
        assertTrue("每一行都必须是完整记录", kept.all { it.startsWith("line-") && it.endsWith("x") })
        assertTrue("保留的是后半（末行应为最后一条）", kept.last().startsWith("line-200-"))
    }
}
