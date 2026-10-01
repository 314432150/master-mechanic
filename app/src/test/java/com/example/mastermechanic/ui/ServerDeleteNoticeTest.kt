package com.example.mastermechanic.ui

import com.example.mastermechanic.R
import com.example.mastermechanic.servers.ServerReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 删除服务器时的**引用验证**（2026-09-22 用户口径，与好友清单同一套）。
 *
 * 只测纯函数：分档与优先级 —— 尤其"查不成"不能与"没引用"撞在一起（区服名是唯一参与定位的字段，
 * 红线 3：悬空引用 = 跑号在换号那一步永远找不到区服）。
 */
class ServerDeleteNoticeTest {

    private fun hits(preset: Set<String> = emptySet(), failure: String? = null) =
        ServerReferences.Hits(presetNames = preset, failure = failure)

    // ---- 删后的提示（撤销窗口里的那一句）----

    @Test
    fun plainDeleteHasNoExtraNoise() {
        assertEquals(R.string.server_deleted_with_name, serverDeleteNoticeOf(hits()))
    }

    @Test
    fun referencedServerIsNamed() {
        // "固定区服"还指着它 → 删完必须让用户知道得去拜访规则改掉
        assertEquals(R.string.server_deleted_referenced_preset, serverDeleteNoticeOf(hits(setOf("龙腾"))))
    }

    @Test
    fun checkFailureWinsOverEverything() {
        val failed = hits(preset = setOf("龙腾"), failure = "拜访规则：坏行 2")
        assertEquals(R.string.server_deleted_ref_check_failed, serverDeleteNoticeOf(failed))
        assertEquals("拜访规则：坏行 2", failed.arg)
        // 三句话互不复用
        val used = setOf(
            serverDeleteNoticeOf(hits()),
            serverDeleteNoticeOf(hits(setOf("龙腾"))),
            serverDeleteNoticeOf(failed),
        )
        assertEquals(3, used.size)
    }

    // ---- 删前的确认框（有引用 / 没能确认才问）----

    @Test
    fun cleanDeleteAsksNothing() {
        assertNull(serverDeleteConfirmBodyOf(hits()))
    }

    @Test
    fun referencedDeleteAsksBeforeDeleting() {
        assertEquals(R.string.server_delete_body_preset, serverDeleteConfirmBodyOf(hits(setOf("龙腾"))))
        // 查不成也问（按"有风险"处理），且与"没引用"那一档不同（null vs 正文）
        assertEquals(
            R.string.server_delete_body_ref_unknown,
            serverDeleteConfirmBodyOf(hits(failure = "拜访规则：坏行 2")),
        )
        // 第二格与文案配对：失败那档给原因（%1$s），预设那档正文没有占位符 → 给 0
        assertEquals("拜访规则：坏行 2", hits(failure = "拜访规则：坏行 2").arg)
        assertEquals(0, hits(setOf("龙腾")).arg)
    }

    // ---- 清空确认框（引用信息放在**动手前**）----

    @Test
    fun clearBodySaysHowManyAreStillReferenced() {
        assertEquals(R.string.server_clear_body_referenced, serverClearBodyOf(ServerClearRef.Done(1)))
    }

    @Test
    fun clearBodyFallsBackWhenCleanOrUnknown() {
        assertEquals(R.string.server_clear_body, serverClearBodyOf(ServerClearRef.Done(0)))
        assertEquals(R.string.server_clear_body, serverClearBodyOf(ServerClearRef.Checking))
        assertEquals(R.string.server_clear_body, serverClearBodyOf(null))
        assertEquals(R.string.server_clear_body_ref_unknown, serverClearBodyOf(ServerClearRef.Failed("拜访规则：坏行 2")))
    }
}
