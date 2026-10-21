package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ShareCodeParser]：复制分享弹框三块文字（分数 / 立牌名称 / 分享码）逐块取值。
 * 容错只归一化；多个候选值 = 不可信 ⇒ null（红线 7）。
 */
class ShareCodeParserTest {

    // ---------------------------------------------------------------- 分数

    @Test
    fun scoreParsesPlainDigits() {
        assertEquals(877, ShareCodeParser.parseScore("877"))
    }

    @Test
    fun scoreToleratesFullWidthDigitsAndSpaces() {
        assertEquals(877, ShareCodeParser.parseScore("８７７"))
        assertEquals(877, ShareCodeParser.parseScore(" 877 "))
    }

    @Test
    fun scoreToleratesDecorationsAroundTheNumber() {
        assertEquals(877, ShareCodeParser.parseScore("价值877分"))
        assertEquals(877, ShareCodeParser.parseScore("分数：877"))
    }

    @Test
    fun scoreRejectsMultipleOrMissingNumbers() {
        // 非空白分隔的多个数字串 = 分不清哪个是分数 ⇒ 不可信（不许挑一个顺眼的）。
        // 注：空白分隔会按 OCR 噪声粘回（见 ShareCodeParser 文件头），此处用斜杠分隔
        assertNull(ShareCodeParser.parseScore("10/10"))
        assertNull(ShareCodeParser.parseScore("无"))
        assertNull(ShareCodeParser.parseScore(""))
    }

    // ---------------------------------------------------------------- 立牌名称

    @Test
    fun nameParsesPlainText() {
        assertEquals("墨子顶级立牌", ShareCodeParser.parseName("墨子顶级立牌"))
        assertEquals("风暴龙王高级立牌", ShareCodeParser.parseName("风暴龙王高级立牌"))
    }

    @Test
    fun nameStripsBracketsWhitespaceAndLabels() {
        assertEquals("墨子顶级立牌", ShareCodeParser.parseName("【墨子顶级立牌】"))
        assertEquals("墨子顶级立牌", ShareCodeParser.parseName("墨子 顶级 立牌"))
        assertEquals("墨子顶级立牌", ShareCodeParser.parseName("立牌名称墨子顶级立牌"))
        assertEquals("墨子顶级立牌", ShareCodeParser.parseName("名称：墨子顶级立牌"))
    }

    @Test
    fun nameRejectsEmptyOrNoChinese() {
        assertNull(ShareCodeParser.parseName(""))
        assertNull(ShareCodeParser.parseName("   "))
        assertNull(ShareCodeParser.parseName("1M09G2"))
    }

    // ---------------------------------------------------------------- 分享码

    @Test
    fun codeParsesPlainCode() {
        assertEquals("1M09G2", ShareCodeParser.parseCode("1M09G2"))
    }

    @Test
    fun codeNormalizesCaseAndSpaces() {
        assertEquals("1M09G2", ShareCodeParser.parseCode("1m09g2"))
        assertEquals("1M09G2", ShareCodeParser.parseCode("1M 09 G2"))
        assertEquals("1M09G2", ShareCodeParser.parseCode("分享码 1M09G2"))
        assertEquals("1M09G2", ShareCodeParser.parseCode("码：1M09G2"))
    }

    @Test
    fun codeRejectsShortOrMissingRuns() {
        assertNull(ShareCodeParser.parseCode("A1"))
        assertNull(ShareCodeParser.parseCode(""))
        // 注：空白分隔按 OCR 噪声粘回（"1M 09 G2" → "1M09G2"），所以"两个码被空白分开"
        // 在解析层判不出也不兜（块只该有一个值）—— 见 ShareCodeParser 文件头
    }
}
