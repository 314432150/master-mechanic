package com.example.mastermechanic.badge

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BadgeCodec]：行式确定性编解码；解析严格、带行号，不做静默兼容（ServerListCodec 同款口径）。 */
class BadgeCodecTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 21)

    private val sample = BadgeData(
        records = listOf(
            BadgeRecord("发条权杖", "墨子顶级立牌", "1M09G2", 877, day),
            BadgeRecord("莲动渔舟", "狂暴普通立牌", "X1Y2Z3", 199, day),
        ),
        collected = listOf(BadgeCollection("发条权杖", day)),
    )

    @Test
    fun roundTrip() {
        assertEquals(sample, BadgeCodec.decode(BadgeCodec.encode(sample)))
    }

    @Test
    fun encodeIsDeterministic() {
        val once = BadgeCodec.encode(sample)
        assertEquals(once, BadgeCodec.encode(BadgeCodec.decode(once)))
    }

    @Test
    fun emptyDataRoundTrips() {
        val empty = BadgeData()
        assertEquals(empty, BadgeCodec.decode(BadgeCodec.encode(empty)))
    }

    @Test
    fun commentsAndBlankLinesIgnored() {
        val text = "# 注释\n\n${BadgeCodec.encode(sample)}"
        assertEquals(sample, BadgeCodec.decode(text))
    }

    @Test
    fun leadingBomTolerated() {
        assertEquals(sample, BadgeCodec.decode("\uFEFF${BadgeCodec.encode(sample)}"))
    }

    @Test
    fun rejectsUnknownKey() {
        val text = "format=mm-badge\nversion=1\nwhat=1"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("未知键"))
    }

    @Test
    fun rejectsBadFormatTag() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BadgeCodec.decode("format=nope\nversion=1")
        }
        assertTrue(e.message!!.contains("格式标记不符"))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BadgeCodec.decode("format=mm-badge\nversion=9")
        }
        assertTrue(e.message!!.contains("版本不受支持"))
    }

    @Test
    fun rejectsMissingVersion() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BadgeCodec.decode("format=mm-badge")
        }
        assertTrue(e.message!!.contains("缺少 version"))
    }

    @Test
    fun rejectsWrongRecordFieldCount() {
        val text = "format=mm-badge\nversion=1\nrecord=发条权杖|1M09G2|877|2026-10-21"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("第 3 行"))
    }

    @Test
    fun rejectsDuplicateCode() {
        val text = "format=mm-badge\nversion=1\n" +
            "record=发条权杖|立牌甲|1M09G2|877|2026-10-21\n" +
            "record=莲动渔舟|立牌乙|1M09G2|199|2026-10-21"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("同一个分享码"))
    }

    @Test
    fun rejectsDuplicateCollected() {
        val text = "format=mm-badge\nversion=1\n" +
            "collected=发条权杖|2026-10-21\n" +
            "collected=发条权杖|2026-10-21"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("收集标记"))
    }

    @Test
    fun rejectsNonIntegerScore() {
        val text = "format=mm-badge\nversion=1\nrecord=发条权杖|立牌甲|1M09G2|八百|2026-10-21"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("分数不是整数"))
    }

    @Test
    fun rejectsNegativeScore() {
        val text = "format=mm-badge\nversion=1\nrecord=发条权杖|立牌甲|1M09G2|-1|2026-10-21"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("负数"))
    }

    @Test
    fun rejectsBadDate() {
        val text = "format=mm-badge\nversion=1\nrecord=发条权杖|立牌甲|1M09G2|877|昨天"
        val e = assertThrows(IllegalArgumentException::class.java) { BadgeCodec.decode(text) }
        assertTrue(e.message!!.contains("日期"))
    }

    @Test
    fun rejectsSeparatorInFieldOnEncode() {
        val data = BadgeData(
            records = listOf(BadgeRecord("发条|权杖", "立牌甲", "1M09G2", 877, day)),
        )
        assertThrows(IllegalArgumentException::class.java) { BadgeCodec.encode(data) }
    }
}
