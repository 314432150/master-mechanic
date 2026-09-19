package com.example.mastermechanic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片字段展示口径单测（M3-T3-9 第十一轮「区号后跟"区"」/ 第十三轮「等级前加 "Lv."」）。
 *
 * 前缀 / 后缀在测试里直接给字面量（产品里来自资源），测的是**补不补、补几次**：
 * 这两条规则的坑都在"用户已经自己写了"的那一侧，单测主要就是钉住它们。
 */
class ServerListLabelsTest {

    // ---------------------------------------------------------------- 区号后缀

    @Test
    fun regionCodeAppendsSuffixOnce() {
        assertEquals("256区", ServerListLabels.regionCode("256", "区"))
        assertEquals("8区", ServerListLabels.regionCode("8", "区"))
        // 用户自己写了「区」→ 不再补第二个
        assertEquals("256区", ServerListLabels.regionCode("256区", "区"))
    }

    @Test
    fun regionCodeKeepsEmptyEmpty() {
        assertEquals("", ServerListLabels.regionCode("", "区"))
        // 后缀本身是空的（资源没配 / 换了个语言）→ 不补，也不崩
        assertEquals("256", ServerListLabels.regionCode("256", ""))
    }

    // ---------------------------------------------------------------- 等级前缀

    @Test
    fun levelPrependsPrefixOnce() {
        assertEquals("Lv.30", ServerListLabels.level("30", "Lv."))
        // 用户填中文也照样补（等级字段是自由文本，不是数字）
        assertEquals("Lv.三十", ServerListLabels.level("三十", "Lv."))
    }

    @Test
    fun levelKeepsAlreadyPrefixedRegardlessOfSpelling() {
        // 已有前缀的各种写法都认（忽略大小写、不要求后面的点 / 冒号一致）
        assertEquals("Lv.50", ServerListLabels.level("Lv.50", "Lv."))
        assertEquals("lv50", ServerListLabels.level("lv50", "Lv."))
        assertEquals("LV.50", ServerListLabels.level("LV.50", "Lv."))
        assertEquals("Lv:50", ServerListLabels.level("Lv:50", "Lv."))
        // 判据是"前缀的字母部分"，不是"以 l 开头"：`level 30` 的开头是 `le` ≠ `lv` → 照补
        assertEquals("Lv.level 30", ServerListLabels.level("level 30", "Lv."))
    }

    @Test
    fun levelKeepsEmptyEmpty() {
        assertEquals("", ServerListLabels.level("", "Lv."))
        assertEquals("30", ServerListLabels.level("30", ""))
    }
}
