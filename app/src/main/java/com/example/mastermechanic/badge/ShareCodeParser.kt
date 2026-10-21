package com.example.mastermechanic.badge

/**
 * 分享弹框 OCR 文本 → 立牌数据（temp/badge-share-1021，纯逻辑）。
 *
 * ## 背景（2026-10-21 用户拍板）
 *
 * 立牌数据**从弹框文本读**（OCR），不读剪贴板：Android 10+ 起后台应用读剪贴板被系统禁止
 * （无障碍服务也不例外），而分享弹框上 `【墨子顶级立牌1M09G2】价值877分` 是明文 ——
 * 弹框里那颗「复制分享码」按钮因此**也不用点**（少了误触，也不污染用户剪贴板）。
 *
 * ## 口径
 *
 * - 容错**只有**三层：全角 ASCII 归半角、去所有空白、字母转大写（分享码不分大小写）。
 *   除此之外**解析不出就返回 null**（红线 7「识别不可信按未识别」）——不猜截断、不补字段。
 * - 一段文本里必须**恰好一条** `【…】价值…分`：0 条 = 读错了画面；**多条 = 不可信**
 *   （分不清是哪张立牌的码），都按未识别处理。
 * - 分享码 = 括号内容里**最后一个汉字之后的字母数字串**（≥4 位，样例 `1M09G2` 为 6 位）；
 *   前面是立牌名（至少含一个汉字，样例 `墨子顶级立牌`）。码比 4 位短不像分享码 ⇒ 拒收。
 *   名里带拉丁字母也成立（如 `KPL限定立牌`：切点仍在**最后一个汉字**之后）。
 */
object ShareCodeParser {

    /** 一份解析结果：立牌名 / 分享码 / 价值分数（字段口径见文件头）。 */
    data class ParsedBadge(
        val cardName: String,
        val code: String,
        val score: Int,
    )

    /** `【立牌名+分享码】价值<分数>分`；在归一化后的文本里**恰好一条**才算数。 */
    private val BADGE_LINE = Regex("【([^【】]+)】价值(\\d+)分")

    /** 括号内容 = 立牌名（以汉字收尾）+ 分享码（≥4 位字母数字）。 */
    private val NAME_AND_CODE = Regex("^(.*[\\u4e00-\\u9fff])([A-Z0-9]{4,})$")

    /**
     * 解析一段 OCR 文本（可能是整块弹框文本，允许夹带「复制分享」之类的无关行）。
     * 解析不出 / 不可信 ⇒ null（调用方按未识别处理，**不带残缺记录继续**）。
     */
    fun parse(text: String): ParsedBadge? {
        val body = normalize(text)
        val matches = BADGE_LINE.findAll(body).toList()
        if (matches.size != 1) return null
        val badge = matches.single()
        val inner = NAME_AND_CODE.matchEntire(badge.groupValues[1]) ?: return null
        val score = badge.groupValues[2].toIntOrNull() ?: return null
        return ParsedBadge(
            cardName = inner.groupValues[1],
            code = inner.groupValues[2],
            score = score,
        )
    }

    /**
     * 归一化：全角 ASCII → 半角、字母转大写、**去掉所有空白**。
     * 全角空格（U+3000）一并当空白去掉；其余字符原样保留（立牌名是汉字，不做任何改写）。
     */
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
}
