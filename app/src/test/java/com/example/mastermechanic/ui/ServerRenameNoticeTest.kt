package com.example.mastermechanic.ui

import com.example.mastermechanic.R
import com.example.mastermechanic.servers.ServerRenameSync
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 区服改名提示的**分档**（2026-09-22 用户口径：改名要同步下游引用）。
 *
 * 比好友那份少两档（区服名只被"拜访规则的固定区服"引用一处），但同样**不是一句"已改名"**：
 * 用户真正要知道的是**还有没有别的地方需要我去改** —— "没引用"（无事）与"没改成"（去排查）
 * 是两种完全不同的后续动作。
 */
class ServerRenameNoticeTest {

    @Test
    fun nothingReferencedSaysSo() {
        assertEquals(
            R.string.server_rename_synced_none,
            serverRenameNoticeOf(ServerRenameSync.Report()),
        )
    }

    @Test
    fun presetMentionedWhenItFollowed() {
        assertEquals(
            R.string.server_rename_synced_preset,
            serverRenameNoticeOf(ServerRenameSync.Report(presetRenamed = true)),
        )
    }

    @Test
    fun failureWinsOverSuccess() {
        // 同步失败 → **必须**报失败那一句，否则用户以为改完了就不再管（预设指着不存在的区服）
        assertEquals(
            R.string.server_rename_sync_failed,
            serverRenameNoticeOf(ServerRenameSync.Report(failure = "拜访规则：xx")),
        )
    }

    @Test
    fun everyCaseHasItsOwnWords() {
        val ids = listOf(
            serverRenameNoticeOf(ServerRenameSync.Report()),
            serverRenameNoticeOf(ServerRenameSync.Report(presetRenamed = true)),
            serverRenameNoticeOf(ServerRenameSync.Report(failure = "xx")),
        )
        assertEquals("三个分支各自有文案，不能互相复用", 3, ids.distinct().size)
    }

    @Test
    fun reportChangedFlagMatchesWhatHappened() {
        // changed 是"有没有下游跟着改"，与好友那份同一含义
        assertEquals(false, ServerRenameSync.Report().changed)
        assertEquals(false, ServerRenameSync.Report(failure = "xx").changed)
        assertEquals(true, ServerRenameSync.Report(presetRenamed = true).changed)
    }
}
