package com.example.mastermechanic.badge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeConfirmation]：分享码"两次读一致才落盘"（2026-10-22）。
 *
 * 现实里的样本：同一张牌被读成 `QGG4ZS`（6 位，对）与 `GG4Z9`（5 位，漏读）——
 * 旧逻辑按第一次读数落盘，池子里就多出一张假牌。
 */
class CodeConfirmationTest {

    @Test
    fun twoIdenticalReadsConfirm() {
        val confirm = CodeConfirmation()
        assertEquals(CodeConfirmation.Verdict.NeedMore(1, "QGG4ZS"), confirm.offer("QGG4ZS"))
        assertEquals(CodeConfirmation.Verdict.Confirmed("QGG4ZS", 2), confirm.offer("QGG4ZS"))
    }

    @Test
    fun malformedCodeNeverConfirmsEvenTwice() {
        // 5 位 = 漏读：读多少次都不许被确认（否则会写出一个永远提交失败的码）
        val confirm = CodeConfirmation()
        confirm.offer("GG4Z9")
        confirm.offer("GG4Z9")
        val verdict = confirm.offer("GG4Z9")
        assertTrue("到上限仍不合格 ⇒ 不可信：$verdict", verdict is CodeConfirmation.Verdict.Conflict)
    }

    @Test
    fun differentlyMisreadCodesConflictInsteadOfPickingOne() {
        // 真机同款：两次各错一点 ⇒ 不挑"最像的"，按不可信处理（红线 7）
        val confirm = CodeConfirmation()
        confirm.offer("QGG4ZS")
        confirm.offer("GG4Z9")
        val verdict = confirm.offer("GG4Z9")
        assertTrue("两次码不同且到上限 ⇒ 冲突：$verdict", verdict is CodeConfirmation.Verdict.Conflict)
        assertEquals(
            listOf("QGG4ZS", "GG4Z9", "GG4Z9"),
            (verdict as CodeConfirmation.Verdict.Conflict).reads,
        )
    }

    @Test
    fun thirdReadCanStillConfirm() {
        // 第一次读歪、后两次一致 ⇒ 仍然能确认（别让一次抖动白跑一个区服）
        val confirm = CodeConfirmation()
        confirm.offer("GG4Z9")
        confirm.offer("QGG4ZS")
        assertEquals(CodeConfirmation.Verdict.Confirmed("QGG4ZS", 3), confirm.offer("QGG4ZS"))
    }

    @Test
    fun nullReadCountsAsAReadButNeverAgrees() {
        // "这一块没解析出唯一值"也要占一次读数：否则一块永远读不出来的屏会无限重试
        val confirm = CodeConfirmation()
        confirm.offer(null)
        confirm.offer(null)
        val verdict = confirm.offer(null)
        assertTrue(verdict is CodeConfirmation.Verdict.Conflict)
    }

    @Test
    fun resetStartsAFreshVote() {
        val confirm = CodeConfirmation()
        confirm.offer("QGG4ZS")
        confirm.reset()
        assertEquals(0, confirm.readCount)
        assertEquals(CodeConfirmation.Verdict.NeedMore(1, "QGG4ZS"), confirm.offer("QGG4ZS"))
    }
}
