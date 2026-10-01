package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「试读这一屏 / 添加这一屏的服务器」这条**一跳信号**的单测（2026-09-25）。
 *
 * 重点钉住 [FriendListOcrSignal.isCaptureFlow]：**它决定结果页有没有"滚屏不收起、收起能回来"这两条待遇**
 * （真机踩过两次同一个坑，根因都是"漏了那个模式"）⇒ 判据只写一处。
 *
 * ⚠ 原来的第三种模式「采集区号数字模板」已按用户口径**整体移除**（`docs/progress.md` 第 209 条）——
 * 真机实测文字识别读数字 12/12 全对，模板对读数字没有贡献。
 */
class FriendListOcrSignalTest {

    @Test
    fun onlyTheCaptureFlowGetsTheReadingPageTreatment() {
        assertTrue(
            "添加这一屏的服务器是采集型：操作流要滚屏再读 ⇒ 享" +
                "\u201c滚屏不收起 + 收起后能回到读数页\u201d两条待遇",
            FriendListOcrSignal.isCaptureFlow(FriendListOcrSignal.Mode.CAPTURE_SERVERS),
        )
        // 2026-09-30：`TRY_READ`（试读这一屏）已整体移除 ⇒ 这条对照断言随之删除（只剩采集型一种）
    }

    @Test
    fun requestAndDoneBookkeeping() {
        // 面板靠 running 显示"进行中…"、靠 report 显示结果；漏了 done 会让面板永远停在"进行中"
        FriendListOcrSignal.request(FriendListOcrSignal.Mode.CAPTURE_SERVERS)
        assertEquals(FriendListOcrSignal.Mode.CAPTURE_SERVERS, FriendListOcrSignal.mode)
        assertTrue(FriendListOcrSignal.running)
        assertNull("新请求要清掉上一次的报告", FriendListOcrSignal.report)

        FriendListOcrSignal.done("报告正文")
        assertFalse(FriendListOcrSignal.running)
        assertEquals("报告正文", FriendListOcrSignal.report)

        // 收尾：别把这条全局状态留给后面的用例（默认模式 = 导入服务器）
        FriendListOcrSignal.request(FriendListOcrSignal.Mode.CAPTURE_SERVERS)
        FriendListOcrSignal.done("")
    }
}
