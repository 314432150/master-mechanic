package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ShareCodeParser]：容错只限空白 / 全角 / 大小写；其余一律按未识别（红线 7）。 */
class ShareCodeParserTest {

    @Test
    fun exactSampleParses() {
        val parsed = ShareCodeParser.parse("【墨子顶级立牌1M09G2】价值877分")
        assertEquals("墨子顶级立牌", parsed?.cardName)
        assertEquals("1M09G2", parsed?.code)
        assertEquals(877, parsed?.score)
    }

    @Test
    fun surroundedByOtherOcrLinesStillParses() {
        val parsed = ShareCodeParser.parse("复制分享\n【墨子顶级立牌1M09G2】价值877分\n复制分享码")
        assertEquals("1M09G2", parsed?.code)
    }

    @Test
    fun fullWidthDigitsLettersAndSpacesNormalize() {
        val parsed = ShareCodeParser.parse("【墨子顶级立牌 １Ｍ０９Ｇ２】　价值 ８７７ 分")
        assertEquals("1M09G2", parsed?.code)
        assertEquals(877, parsed?.score)
    }

    @Test
    fun lowercaseCodeUppercased() {
        val parsed = ShareCodeParser.parse("【墨子顶级立牌1m09g2】价值877分")
        assertEquals("1M09G2", parsed?.code)
    }

    @Test
    fun latinInCardNameKeepsSplitAtLastChineseChar() {
        val parsed = ShareCodeParser.parse("【KPL限定立牌1M09G2】价值877分")
        assertEquals("KPL限定立牌", parsed?.cardName)
        assertEquals("1M09G2", parsed?.code)
    }

    @Test
    fun missingScoreUnitIsUnrecognized() {
        assertNull(ShareCodeParser.parse("【墨子顶级立牌1M09G2】价值877"))
    }

    @Test
    fun missingBracketsIsUnrecognized() {
        assertNull(ShareCodeParser.parse("墨子顶级立牌1M09G2 价值877分"))
    }

    @Test
    fun emptyCardNameIsUnrecognized() {
        assertNull(ShareCodeParser.parse("【1M09G2】价值877分"))
    }

    @Test
    fun codeShorterThanFourIsUnrecognized() {
        assertNull(ShareCodeParser.parse("【墨子顶级立牌1M9】价值877分"))
    }

    @Test
    fun twoMatchesAreUntrusted() {
        val text = "【墨子顶级立牌1M09G2】价值877分\n【狂暴普通立牌X1Y2Z3】价值199分"
        assertNull(ShareCodeParser.parse(text))
    }

    @Test
    fun garbageIsUnrecognized() {
        assertNull(ShareCodeParser.parse("随便一段没有立牌的话"))
    }
}
