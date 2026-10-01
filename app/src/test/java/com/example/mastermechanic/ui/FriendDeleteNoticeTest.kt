package com.example.mastermechanic.ui

import com.example.mastermechanic.R
import com.example.mastermechanic.friends.FriendReferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 删除好友时的**引用验证**（2026-09-22 用户口径：删除也要做引用验证，**有引用时先问一句再删**）。
 *
 * 两处文案各测一边：
 * - **删前**的确认框（[friendDeleteConfirmBodyOf]）：只有"有引用 / 没能确认"才问；
 * - **删后**的提示（[friendDeleteNoticeOf]）：撤销窗口里那一句，说清"还得去哪一页收拾"。
 *
 * 这里只测纯函数：打桩一份 [FriendReferences.Hits]（真查询要读文件、走 Android Context，
 * 那是设备上的事）—— 要钉住的是**分档**与**优先级**，尤其"查不成"绝不能与"没引用"撞在一起。
 */
class FriendDeleteNoticeTest {

    private fun hits(
        preset: Set<String> = emptySet(),
        anchors: Set<String> = emptySet(),
        anchorCount: Int = 0,
        failure: String? = null,
    ) = FriendReferences.Hits(presetNames = preset, anchorNames = anchors, anchorCount = anchorCount, failure = failure)

    // ---- 删后的提示（撤销窗口里的那一句）----

    @Test
    fun plainDeleteHasNoExtraNoise() {
        // 没引用 → 就是原来那句"已删除「X」"：别给干净的操作加噪音
        assertEquals(R.string.friend_deleted_with_name, friendDeleteNoticeOf(hits()))
    }

    @Test
    fun presetReferenceIsNamed() {
        assertEquals(R.string.friend_deleted_referenced_preset, friendDeleteNoticeOf(hits(preset = setOf("阿娜雅"))))
    }

    @Test
    fun anchorReferencesSayHowMany() {
        // 只有产物引用时要说**几条**：一条和八条的后续动作完全不同（改一个名 vs 重标一批）
        val one = hits(anchors = setOf("阿娜雅"), anchorCount = 3)
        assertEquals(R.string.friend_deleted_referenced_anchors, friendDeleteNoticeOf(one))
        assertEquals(3, one.arg)
    }

    @Test
    fun bothPlacesUseTheirOwnSentence() {
        val both = hits(preset = setOf("阿娜雅"), anchors = setOf("阿娜雅"), anchorCount = 2)
        assertEquals(R.string.friend_deleted_referenced_both, friendDeleteNoticeOf(both))
        // 四句话必须互不复用：后续动作不同（去改预设 / 去重标 / 两件事都做 / 什么都不用做）
        val used = setOf(
            friendDeleteNoticeOf(hits()),
            friendDeleteNoticeOf(hits(preset = setOf("阿娜雅"))),
            friendDeleteNoticeOf(hits(anchors = setOf("阿娜雅"), anchorCount = 1)),
            friendDeleteNoticeOf(both),
        )
        assertEquals(4, used.size)
    }

    @Test
    fun checkFailureWinsOverEverything() {
        // 读盘失败 = 结论不可信 → 必须报"没能确认"，**绝不**退化成"没引用"（那是把查不到说成没问题）
        val failed = hits(preset = setOf("阿娜雅"), anchorCount = 5, failure = "拜访规则：坏行 3")
        assertEquals(R.string.friend_deleted_ref_check_failed, friendDeleteNoticeOf(failed))
        // 第二格给的是**原因**（%2$s），不是条数
        assertEquals("拜访规则：坏行 3", failed.arg)
    }

    // ---- 删前的确认框（有引用 / 没能确认才问）----

    @Test
    fun cleanDeleteAsksNothing() {
        // 没引用 → null = 不打断（照旧"先做再给撤销"）
        assertNull(friendDeleteConfirmBodyOf(hits()))
    }

    @Test
    fun referencedDeleteAsksBeforeDeleting() {
        val preset = friendDeleteConfirmBodyOf(hits(preset = setOf("阿娜雅")))
        val anchors = friendDeleteConfirmBodyOf(hits(anchors = setOf("阿娜雅"), anchorCount = 2))
        val both = friendDeleteConfirmBodyOf(hits(preset = setOf("阿娜雅"), anchors = setOf("阿娜雅"), anchorCount = 2))
        assertEquals(R.string.friend_delete_body_preset, preset)
        assertEquals(R.string.friend_delete_body_anchors, anchors)
        assertEquals(R.string.friend_delete_body_both, both)
        // 四档互不复用（连"不弹"那一档也算：一共 4 种处理）
        assertEquals(4, setOf<Any?>(preset, anchors, both, friendDeleteConfirmBodyOf(hits())).size)
    }

    @Test
    fun unknownReferencesAlsoAsk() {
        // 查不成 = 结论不可信 → 按"有风险"处理，也要问一句（**不**当成"没问题"放过去）
        val failed = hits(failure = "标定产物：坏行 1")
        assertEquals(R.string.friend_delete_body_ref_unknown, friendDeleteConfirmBodyOf(failed))
        // 确认框正文与提示是两句不同的话：一句讲"删了会怎样"（动手前），一句讲"已删除，还得去哪收拾"
        assertNotEquals(friendDeleteNoticeOf(failed), friendDeleteConfirmBodyOf(failed))
    }

    @Test
    fun confirmBodyArgMatchesItsWording() {
        // 确认框正文与"第二格"必须配得上：条数那档给 Int（%1$d），失败那档给原因（%1$s）。
        // 配错 = `getString` 直接抛异常（真机上表现为一删就闪退），所以这里钉死。
        assertEquals(2, hits(anchors = setOf("阿娜雅"), anchorCount = 2).arg)
        assertEquals("标定产物：坏行 1", hits(failure = "标定产物：坏行 1").arg)
        // 预设那档正文没有占位符 → 第二格是 0（多余参数被忽略，不是错误）
        assertEquals(0, hits(preset = setOf("阿娜雅")).arg)
    }

    // ---- 清空确认框（引用信息放在**动手前**：单行删除默认不弹框，所以那一边是删后说）----

    @Test
    fun clearBodySaysHowManyAreStillReferenced() {
        assertEquals(R.string.friend_clear_body_referenced, friendClearBodyOf(FriendClearRef.Done(2)))
    }

    @Test
    fun clearBodyFallsBackWhenCleanOrUnknown() {
        // 一条都没被引用 → 普通那一句（不制造"清空很危险"的错觉）
        assertEquals(R.string.friend_clear_body, friendClearBodyOf(FriendClearRef.Done(0)))
        // 还在查 → 先用普通那一句（查得很快，不能让对话框干等）
        assertEquals(R.string.friend_clear_body, friendClearBodyOf(FriendClearRef.Checking))
        assertEquals(R.string.friend_clear_body, friendClearBodyOf(null))
        // 没查成 → **如实加一句**（不是拦住清空，也不能装作没这回事）
        assertEquals(R.string.friend_clear_body_ref_unknown, friendClearBodyOf(FriendClearRef.Failed("拜访规则：坏行 2")))
    }

    @Test
    fun clearReferencedCountIsDeduped() {
        // 预设 1 个 + 产物里 1 个（同一个人只算一次）：清空前那句"其中 N 位"不能把人算两遍
        val hits = hits(preset = setOf("阿娜雅"), anchors = setOf("阿娜雅", "小北"), anchorCount = 2)
        assertEquals(2, hits.referenced.size)
    }
}
