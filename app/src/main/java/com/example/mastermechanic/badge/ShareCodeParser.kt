package com.example.mastermechanic.badge

/**
 * 复制分享弹框上**三块文字** → 立牌字段（temp/badge-share-1021，纯逻辑）。
 *
 * ## 口径（2026-10-21 真机确认 + 用户拍板）
 *
 * 弹框上立牌信息**分三块**：分数 / 立牌名称 / 分享码 —— Slice 3 逐块文字识别，
 * 每块文本喂给本对象对应的 parse。
 *
 * 剪贴板里那种 `【墨子顶级立牌1M09G2】价值877分` **不考虑**：那是点「复制分享码」
 * 才写进剪贴板的文本，而本流程不点那颗按钮（Android 10+ 后台也读不了剪贴板）。
 *
 * ## 取不出唯一值就是解析不出（红线 7）
 *
 * 容错只做归一化：全角 → 半角、**去所有空白**（空白一律当 OCR 噪声粘回 —— 码里的 `1M 09 G2`
 * 要粘回 `1M09G2`）、字母转大写、剥掉连标签一起 OCR 进来的前缀（框圈得偏大时）。
 * 多个候选值只在有**非空白分隔**时才判得出来（如 `10/10`）⇒ 这种情况按不可信 ⇒ null，
 * 调用方停下报原因，不带残缺记录继续。「框串了两个码 / 两个分数」这种人为失误解析层不兜
 * （块只该有一个值）—— 靠真机核对与立牌池展示层的人眼兜。
 */
object ShareCodeParser {

    /** 块框得偏大、把标签一起 OCR 进来时，剥掉的开头标签（按长度降序试剥，防「名称」抢先）。 */
    private val LEADING_LABELS = listOf("立牌名称", "名称", "分享码", "分数", "价值")

    /** 数字串。 */
    private val DIGIT_RUN = Regex("[0-9]+")

    /** 字母数字串（归一化后字母全大写）。 */
    private val ALNUM_RUN = Regex("[A-Z0-9]+")

    /** 分数块文本 → 分数；块里取不出**唯一**数字串 ⇒ null（多个 = 不可信）。 */
    fun parseScore(text: String): Int? {
        val runs = DIGIT_RUN.findAll(normalize(text)).map { it.value }.toList()
        if (runs.size != 1) return null
        return runs.single().toIntOrNull()
    }

    /** 立牌名称块文本 → 立牌名：剥【】与空白，至少含一个汉字，非空。 */
    fun parseName(text: String): String? {
        val name = stripLeadingLabels(normalize(text)).removeSurrounding("【", "】").trim()
        if (name.isEmpty()) return null
        if (name.none { it in '\u4e00'..'\u9fff' }) return null
        return name
    }

    /** 分享码块文本 → 分享码：**唯一**一串 ≥4 位字母数字（大写归一；标签是汉字，天然被隔开）。 */
    fun parseCode(text: String): String? {
        val runs = ALNUM_RUN.findAll(normalize(text)).filter { it.value.length >= 4 }.toList()
        if (runs.size != 1) return null
        return runs.single().value
    }

    /** 归一化：全角 ASCII → 半角、字母转大写、去掉所有空白（全角空格一并去）。 */
    private fun normalize(raw: String): String {
        val unified = buildString {
            for (ch in raw) {
                when {
                    ch == '\u3000' -> Unit
                    ch in '\uFF01'..'\uFF5E' -> append(ch - 0xFEE0)
                    !ch.isWhitespace() -> append(ch)
                }
            }
        }
        return unified.uppercase()
    }

    /** 从开头剥标签（剥完还可能剩一个，循环到没有为止；标签后的分隔符一并削掉）。 */
    private fun stripLeadingLabels(text: String): String {
        var out = text
        while (true) {
            val label = LEADING_LABELS.firstOrNull { out.startsWith(it) } ?: return out
            out = out.removePrefix(label).trimStart(':', '·', '-', '/', '.', ' ')
        }
    }
}
