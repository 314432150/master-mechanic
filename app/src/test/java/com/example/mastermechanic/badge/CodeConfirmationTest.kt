package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeConfirmation]：分享码"两次读一致才落盘"（2026-10-22 加固，2026-10-23 修正作用域与位数口径）。
 *
 * 现实里的样本：同一张牌被读成 `QGG4ZS`（6 位，对）与 `GG4Z9`（5 位，漏读）；
 * 另一张牌则**稳定**读成 5 位（`HDXQM` 连读 6 次都一样）。
 */
class CodeConfirmationTest {

    private val card = "7|机仆阵列|7|first"

    @Test
    fun twoIdenticalReadsConfirm() {
        val confirm = CodeConfirmation()
        assertEquals(CodeConfirmation.Verdict.NeedMore(1, "QGG4ZS"), confirm.offer(card, "QGG4ZS"))
        assertEquals(CodeConfirmation.Verdict.Confirmed("QGG4ZS", 2), confirm.offer(card, "QGG4ZS"))
    }

    @Test
    fun stableShortCodeStillConfirms() {
        // 2026-10-23：`HDXQM` 连读 6 次都是 5 位 —— 稳定读数就是这一串，
        // 以前"不是 6 位不算数"会让它永远确认不了、整轮任务被打死（真机 5 次中止）
        val confirm = CodeConfirmation()
        confirm.offer(card, "HDXQM")
        assertEquals(CodeConfirmation.Verdict.Confirmed("HDXQM", 2), confirm.offer(card, "HDXQM"))
    }

    @Test
    fun differentlyReadCodesConflictInsteadOfPickingOne() {
        // 两次各错一点 ⇒ 不挑"最像的"，按不可信处理（红线 7）
        val confirm = CodeConfirmation()
        confirm.offer(card, "QGG4ZS")
        confirm.offer(card, "GG4Z9")
        val verdict = confirm.offer(card, "QGG4ZT")
        assertTrue("到上限仍不一致 ⇒ 冲突：$verdict", verdict is CodeConfirmation.Verdict.Conflict)
        assertEquals(
            listOf("QGG4ZS", "GG4Z9", "QGG4ZT"),
            (verdict as CodeConfirmation.Verdict.Conflict).reads,
        )
    }

    @Test
    fun thirdReadCanStillConfirm() {
        // 第一次读歪、后两次一致 ⇒ 仍然能确认（别让一次抖动白跑一个区服）
        val confirm = CodeConfirmation()
        confirm.offer(card, "GG4Z9")
        confirm.offer(card, "QGG4ZS")
        assertEquals(CodeConfirmation.Verdict.Confirmed("QGG4ZS", 3), confirm.offer(card, "QGG4ZS"))
    }

    @Test
    fun nullReadCountsAsAReadButNeverAgrees() {
        // "这一块没解析出唯一值"也要占一次读数：否则一块永远读不出来的屏会无限重试
        val confirm = CodeConfirmation()
        confirm.offer(card, null)
        confirm.offer(card, null)
        val verdict = confirm.offer(card, null)
        assertTrue(verdict is CodeConfirmation.Verdict.Conflict)
    }

    @Test
    fun newScopeStartsAFreshVote() {
        // 2026-10-23 真机 bug：投票只用「画面 + 步骤号」当作用域，而「我的立牌」两张牌同一步骤号
        // ⇒ 第二张牌的第一读被第一张牌留下的读数污染，日志出现
        // "读数不一致（16RGRB / 16RGRB / Y9P4CZ）"，整轮任务被打死
        val confirm = CodeConfirmation()
        confirm.offer("7|机仆阵列|7|first", "16RGRB")
        confirm.offer("7|机仆阵列|7|first", "16RGRB")
        val verdict = confirm.offer("7|机仆阵列|10|(1200,800)", "Y9P4CZ")
        assertEquals("换牌 ⇒ 从头开始投", CodeConfirmation.Verdict.NeedMore(1, "Y9P4CZ"), verdict)
    }

    @Test
    fun resetStartsAFreshVote() {
        val confirm = CodeConfirmation()
        confirm.offer(card, "QGG4ZS")
        confirm.reset()
        assertEquals(0, confirm.readCount)
        assertEquals(CodeConfirmation.Verdict.NeedMore(1, "QGG4ZS"), confirm.offer(card, "QGG4ZS"))
    }
}
