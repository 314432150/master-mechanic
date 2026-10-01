package com.example.mastermechanic.ui

import com.example.mastermechanic.R
import com.example.mastermechanic.friends.FriendRenameSync
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 改名提示的**分档**（2026-09-22 用户口径：改名要同步下游引用）。
 *
 * 为什么要分四句、而不是统一"已改名"一句：用户真正要知道的是**还有没有别的地方需要我去改** ——
 * "别处没引用"（无事）与"下游没改成功"（要去排查）是两种完全不同的后续动作。
 */
class FriendRenameNoticeTest {

    @Test
    fun nothingReferencedSaysSo() {
        assertEquals(
            R.string.friend_rename_synced_none,
            renameNoticeOf(FriendRenameSync.Report()),
        )
    }

    @Test
    fun presetOnlyMentionsThePreset() {
        assertEquals(
            R.string.friend_rename_synced_preset,
            renameNoticeOf(FriendRenameSync.Report(presetRenamed = true)),
        )
    }

    @Test
    fun anchorsOnlyTellsHowMany() {
        assertEquals(
            R.string.friend_rename_synced_anchors,
            renameNoticeOf(FriendRenameSync.Report(anchorsRenamed = 2)),
        )
    }

    @Test
    fun bothMentionsBoth() {
        assertEquals(
            R.string.friend_rename_synced_both,
            renameNoticeOf(FriendRenameSync.Report(presetRenamed = true, anchorsRenamed = 1)),
        )
    }

    @Test
    fun failureWinsOverSuccesses() {
        // 一处改成了、另一处没改成 → **必须**报失败那一句，否则用户以为全同步完了就不再管
        assertEquals(
            R.string.friend_rename_sync_failed,
            renameNoticeOf(
                FriendRenameSync.Report(
                    presetRenamed = true,
                    anchorsRenamed = 3,
                    failure = "标定产物：xx",
                ),
            ),
        )
    }

    @Test
    fun everyCaseHasItsOwnWords() {
        val ids = listOf(
            renameNoticeOf(FriendRenameSync.Report()),
            renameNoticeOf(FriendRenameSync.Report(presetRenamed = true)),
            renameNoticeOf(FriendRenameSync.Report(anchorsRenamed = 2)),
            renameNoticeOf(FriendRenameSync.Report(presetRenamed = true, anchorsRenamed = 1)),
            renameNoticeOf(FriendRenameSync.Report(failure = "xx")),
        )
        assertEquals("五个分支各自有文案，不能互相复用", 5, ids.distinct().size)
    }

    @Test
    fun reportChangedFlagMatchesWhatHappened() {
        // changed 是"有没有下游跟着改"，界面 / 文案都靠它判"要不要多说一句"
        assertEquals(false, FriendRenameSync.Report().changed)
        assertEquals(false, FriendRenameSync.Report(failure = "xx").changed)
        assertEquals(true, FriendRenameSync.Report(presetRenamed = true).changed)
        assertEquals(true, FriendRenameSync.Report(anchorsRenamed = 1).changed)
    }
}
