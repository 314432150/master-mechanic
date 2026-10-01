package com.example.mastermechanic.action

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在关弹窗」信号单测（2026-09-29 加"受阻"两态）。
 *
 * 用户报障原话："**显示了正在关弹窗，但实际上没关掉**" —— 起因是标签只看
 * [PopupCloseSignal.attempts]，而它是"给出一次点击就计一次"，**真的发得出去**还要过开火前复眼与
 * `ClickGate` ⇒ 被拦住时标签照旧写"正在关"，等于骗人。这里钉住三件事：
 * 1. 受阻要说出来（标签据此改显示「关弹窗受阻」）；
 * 2. **受阻比 attempts 活得久**：段停手（attempts 归 0）之后弹窗还在，就该继续显示"受阻"；
 * 3. 真的发出去一枪 / 弹窗消失 / 会话重建 ⇒ 受阻作废（否则会留下"永远受阻"的假象）。
 */
class PopupCloseSignalTest {

    @After
    fun tearDown() {
        // 单例是全局状态：每个用例之后清干净，免得污染别的用例
        PopupCloseSignal.clear()
    }

    @Test
    fun blockedSaysSoEvenWhileNothingWasDispatched() {
        // 真机形态：复眼否决 ⇒ attempts 被记成 1（给出过 Click）但**一枪都没出去** ——
        // 这时标签必须说"受阻"，不能说"正在关"
        PopupCloseSignal.update(1)
        PopupCloseSignal.markBlocked("开火前复眼：新画面上锚点不在原处")

        assertTrue(PopupCloseSignal.isClosing)
        assertEquals("开火前复眼：新画面上锚点不在原处", PopupCloseSignal.blockedReason)
    }

    @Test
    fun blockedOutlivesTheAttemptCounter() {
        // 段停手（gaveUp）⇒ 调用方把 attempts 写 0，但弹窗还在 ⇒ "受阻"必须留着
        PopupCloseSignal.markBlocked("开火前复眼：那个控件已经不在原处了")
        PopupCloseSignal.update(0)

        assertFalse("没有在关（attempts=0）", PopupCloseSignal.isClosing)
        assertEquals("但受阻仍在，标签该继续写「关弹窗受阻」", "开火前复眼：那个控件已经不在原处了", PopupCloseSignal.blockedReason)
    }

    @Test
    fun gaveUpIsReadableByTheUiAndDiesWithTheSegment() {
        // 2026-09-30 真机报障："我已经完成换号操作，但是最后一次活动弹窗没有关掉"
        // —— 日志里程序**主动停手**了（`FR-01 停手: 本轮画面整幅换掉了…`），可界面上：
        //    「正在关弹窗」→「待命」→ 整条摘掉，弹窗压在屏上而界面一个字都不说。
        // 根子：停手那条路（画面整幅换掉）当时只回 Skip，既不写 attempts 也不写 blockedReason。
        PopupCloseSignal.update(attempts = 0, gaveUpReason = "本轮画面整幅换掉了（整帧变化 50.0%）")

        assertFalse("停手后不该再说「正在关弹窗」", PopupCloseSignal.isClosing)
        assertTrue("停手必须能被界面读到（标签「关弹窗已停手」）", PopupCloseSignal.gaveUpReason.isNotEmpty())

        // 段复位（弹窗消失 / 换屏 ⇒ 控制器 gaveUp 变假）⇒ 调用方写空串 ⇒ 停手作废
        PopupCloseSignal.update(attempts = 0, gaveUpReason = "")
        assertEquals("", PopupCloseSignal.gaveUpReason)

        // 会话重建 / 终止 ⇒ 一并清空（否则会留下"永远已停手"的假象）
        PopupCloseSignal.update(attempts = 0, gaveUpReason = "某原因")
        PopupCloseSignal.clear()
        assertEquals("", PopupCloseSignal.gaveUpReason)
    }

    @Test
    fun dispatchingAShotClearsTheGaveUpNote() {
        // 真的又发出去了一个 Click（新的一段）⇒ 停手说明作废，标签回到「正在关弹窗」
        PopupCloseSignal.update(attempts = 0, gaveUpReason = "本轮画面整幅换掉了（整帧变化 50.0%）")
        PopupCloseSignal.clearBlocked()

        assertEquals("", PopupCloseSignal.gaveUpReason)
    }

    @Test
    fun dispatchingAShotOrLosingThePopupClearsTheBlock() {
        PopupCloseSignal.markBlocked("某原因")

        PopupCloseSignal.clearBlocked() // 真的发了一枪
        assertEquals("", PopupCloseSignal.blockedReason)

        PopupCloseSignal.markBlocked("某原因")
        PopupCloseSignal.clear() // 会话重建 / 终止
        assertEquals("", PopupCloseSignal.blockedReason)
        assertEquals(0, PopupCloseSignal.attempts)
    }
}
