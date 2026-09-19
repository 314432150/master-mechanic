package com.example.mastermechanic.ui

/**
 * 服务器清单卡片里**字段怎么显示**的纯逻辑（M3-T3-9 展示口径：第十一轮区号后缀、第十三轮等级前缀）。
 *
 * 单独拿出来是因为这两条都是**用户口径**、而且都踩过同一个坑：**重复补一次**。
 * - 区号：填 `256` → 显示「256区」；用户自己写了「区」就不能再补（否则「256区区」）；
 * - 等级：填 `30` → 显示「Lv.30」；用户自己写了 `Lv.50` 就不能再补（实测同一份数据里两种写法都有，
 *   补了会变成「Lv.Lv.50」）。
 *
 * 前缀 / 后缀由调用方从资源取好传进来（**本文件不依赖 Android 资源**，才能进 JVM 单测：
 * [com.example.mastermechanic.ui.ServerListLabelsTest]）。两处都只在**展示层**生效 ——
 * 编辑页输入框和盘上文件里始终是用户填的原文。
 */
internal object ServerListLabels {

    /** 区号的显示文本：空还是空；已带后缀原样；否则补一个后缀。 */
    fun regionCode(raw: String, suffix: String): String = when {
        raw.isEmpty() -> ""
        suffix.isEmpty() -> raw
        raw.endsWith(suffix) -> raw
        else -> raw + suffix
    }

    /**
     * 等级的显示文本：空还是空；已带前缀（**忽略大小写**，只看前缀里的字母部分）原样；否则补前缀。
     *
     * "只看字母部分"是刻意的：前缀是 `Lv.` 时，`Lv50` / `lv.50` / `LV:50` 都算已带（用户怎么写都认），
     * 而 `30` / `三十` 会补成 `Lv.30` / `Lv.三十`。
     */
    fun level(raw: String, prefix: String): String = when {
        raw.isEmpty() -> ""
        prefix.isEmpty() -> raw
        raw.startsWith(prefixLetters(prefix), ignoreCase = true) -> raw
        else -> prefix + raw
    }

    /** 前缀里的**字母部分**（`Lv.` → `Lv`）；没有字母时（如中文前缀）原样返回，判据照样成立。 */
    private fun prefixLetters(prefix: String): String = prefix.takeWhile { it.isLetter() }
}
