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
 * 才写进剪贴板的文本。2026-10-22 真机实测过这条路（结论见 `docs/progress.md`）：
 * 复制出来的文本确实齐全（`王者荣耀【金蝉顶级立牌TCV3ZQ】价值688分，…`），但 **Android 10+ 只允许
 * 有窗口焦点的应用读剪贴板** —— 游戏前台时 `hasPrimaryClip` 直接为 false，想读就得抢焦点
 * （透明页闪一次、还会打断前台连续性）⇒ 不划算，仍走截图 OCR。
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

    /**
     * 分享码的**硬格式**：恰好 [CODE_LENGTH] 位（2026-10-22 加固）。
     *
     * 依据：真机一天 27 个码里 25 个是 6 位，另 2 个 5 位（`VEWKW` / `WNR6V`）回头对都是**漏读一位**
     * （同牌另一次读成 6 位、且剪贴板实测也被证实是 6 位）；同类漏读还出现过 `GG4Z9`（真值 `QGG4ZS`）。
     * 所以"不是 6 位"= **这次没读全**，不该当成一个可用的码。
     */
    const val CODE_LENGTH = 6

    /**
     * 分享码块文本 → 分享码：**唯一**一串 ≥4 位字母数字（大写归一；标签是汉字，天然被隔开）。
     *
     * ⚠ 这里只做"取出一串"，**不判格式**：格式不合格（位数不对）要在调用方按"不可信 ⇒ 重读"处理，
     * 那里才拿得到原始文本、能写清"读到几位"。判据见 [isWellFormedCode]。
     */
    fun parseCode(text: String): String? {
        val runs = ALNUM_RUN.findAll(normalize(text)).filter { it.value.length >= 4 }.toList()
        if (runs.size != 1) return null
        return runs.single().value
    }

    /**
     * 这串码像不像一个**完整**的分享码：恰好 [CODE_LENGTH] 位、且只含大写字母 / 数字。
     *
     * 只做"像不像"，**不猜形近字**（把 `S` 改成 `5` 是凭空造数据；红线 7：不可信就按未识别处理，
     * 由 [CodeConfirmation] 多读一次来定，而不是自己圆回去）。
     */
    fun isWellFormedCode(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in 'A'..'Z' || it in '0'..'9' }

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
