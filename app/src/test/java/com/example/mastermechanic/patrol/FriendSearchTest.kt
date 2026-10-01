package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 9 步「搜索式查找」状态机（2026-10-01）：口径与来由见 [FriendSearch] 类注释。
 *
 * 覆盖：三个锚点齐不齐 / 五小步的顺序与锚点名 / 写失败要退回滑屏 / 搜索发出后不再滑屏 /
 * 结果页没有就重写一次（上限 2）/ 一轮结束后重写额度要还回来。
 */
class FriendSearchTest {

    private val three = arrayOf(
        PatrolAnchors.FRIEND_SEARCH_ENTRY,
        PatrolAnchors.FRIEND_SEARCH_FIELD,
        PatrolAnchors.FRIEND_SEARCH_GO,
    )

    private fun newSearch(vararg anchors: String) = FriendSearch(anchors.toList())

    /** 走到"点完「搜索」、正在等结果页"（① ② ③ ③.5 ④ 五小步）。 */
    private fun sent(vararg anchors: String): FriendSearch =
        newSearch(*anchors).also { search ->
            search.start()
            search.onAnchorPracticed()
            search.onAnchorPracticed()
            search.onTextWritten(succeeded = true)
            search.onKeyboardDismissed()
            search.onAnchorPracticed()
        }

    @Test
    fun theChainIsUnavailableUntilAllThreeAnchorsAreCalibrated() {
        // 三个锚点没标定齐 ⇒ UNAVAILABLE：调用方据此**退回滑屏找**（不因缺锚点把整步卡死，
        // 见 `CaptureService.friendPlanOf` ⑥）。
        assertEquals(
            "一个都没标定 ⇒ 走不了",
            FriendSearch.Phase.UNAVAILABLE,
            newSearch().start(),
        )
        assertEquals(
            "只标了两个 ⇒ 走不了",
            FriendSearch.Phase.UNAVAILABLE,
            newSearch(three[0], three[1]).start(),
        )
        assertEquals(
            "标了三个但有一个是空白（产物里没名字）⇒ 走不了",
            FriendSearch.Phase.UNAVAILABLE,
            newSearch(three[0], three[1], "   ").start(),
        )
        assertEquals("三个齐了 ⇒ 从「点搜好友」开始", FriendSearch.Phase.ENTRY, newSearch(*three).start())
        assertEquals(
            "默认锚点表 = PatrolAnchors.searchFlowAnchors 里那三个",
            FriendSearch.Phase.ENTRY,
            FriendSearch().start(),
        )
    }

    @Test
    fun theThreeClicksWalkTheWholeChainAndNameOneAnchorEach() {
        val search = newSearch(*three)
        assertEquals(FriendSearch.Phase.ENTRY, search.start())
        assertEquals(PatrolAnchors.FRIEND_SEARCH_ENTRY, search.anchorToClick)

        search.onAnchorPracticed() // ① 点「搜好友」
        assertEquals(FriendSearch.Phase.FIELD, search.phase)
        assertEquals(PatrolAnchors.FRIEND_SEARCH_FIELD, search.anchorToClick)

        search.onAnchorPracticed() // ② 点搜索框（随后由无障碍原生写入）
        assertEquals(FriendSearch.Phase.TYPE, search.phase)
        assertNull("写文字这一步没有可点的锚点", search.anchorToClick)

        search.onTextWritten(succeeded = true) // ③ 原生写入成功
        assertEquals(
            "写完先去收输入法（它是全屏编辑，不收掉的话「搜索」根本不在画面上）",
            FriendSearch.Phase.DISMISS,
            search.phase,
        )
        assertFalse("还没发出搜索", search.awaitingResults)
        assertNull(search.anchorToClick)

        search.onKeyboardDismissed() // ③.5 输入法已收起
        assertEquals(FriendSearch.Phase.GO, search.phase)
        assertEquals(PatrolAnchors.FRIEND_SEARCH_GO, search.anchorToClick)

        search.onAnchorPracticed() // ④ 点「搜索」
        assertEquals(FriendSearch.Phase.SENT, search.phase)
        assertTrue("搜索已发出 ⇒ 调用方**只等结果、不再滑屏**", search.awaitingResults)
        assertNull("终态没有可点的锚点（结果页交给名称定位判）", search.anchorToClick)
        assertEquals("整条链正好三下", 3, search.clicks)
        assertEquals(1, search.writeAttempts)
    }

    @Test
    fun aFailedWriteFallsBackToScrolling() {
        // 写文字失败（`ACTION_SET_TEXT` 找不到可编辑节点）⇒ UNAVAILABLE ⇒ 调用方退回滑屏找。
        val search = newSearch(*three)
        search.start()
        search.onAnchorPracticed()
        search.onAnchorPracticed()

        search.onTextWritten(succeeded = false)

        assertEquals(FriendSearch.Phase.UNAVAILABLE, search.phase)
        assertFalse(search.awaitingResults)
        assertTrue("日志要说清是「走不了」，别让用户以为「搜过了」", search.note().contains("走不了"))
        assertEquals("失败的那次不算写过", 0, search.writeAttempts)
    }

    @Test
    fun writingAnOutcomeOutsideTheTypingStepChangesNothing() {
        // 幂等护栏：调用方是"每轮按 phase 问一次"，写文字的结果只在 TYPE 阶段有意义。
        val search = newSearch(*three)
        search.start()

        search.onTextWritten(succeeded = true)

        assertEquals(FriendSearch.Phase.ENTRY, search.phase)
        assertEquals(0, search.writeAttempts)
    }

    @Test
    fun aSearchWithoutResultsRewritesOnceAndThenGivesUp() {
        // 真机（2026-10-01）：`ACTION_SET_TEXT` + 提交这套**不稳定** —— 同一台机器 07:55 那次结果页
        // 找到了、07:56 那次"结果页里没有它"⇒ 面板还开着时允许**重写一次**（回 ②，只花 ②③③.5④ 四步）；
        // 两次都进不去框 ⇒ 调用方按用户口径**如实停下**（不再滑屏）。
        val search = sent(*three)
        assertEquals(1, search.writeAttempts)

        assertTrue("第一次还能重写", search.retryOnce(maxAttempts = 2))
        assertEquals("重写从「点搜索框」开始（面板还开着）", FriendSearch.Phase.FIELD, search.phase)

        search.onAnchorPracticed() // ②
        search.onTextWritten(succeeded = true) // ③
        search.onKeyboardDismissed() // ③.5
        search.onAnchorPracticed() // ④
        assertEquals(2, search.writeAttempts)
        assertEquals(FriendSearch.Phase.SENT, search.phase)

        assertFalse("两次都没结果 ⇒ 额度用完，如实停下", search.retryOnce(maxAttempts = 2))
        assertEquals("用完额度后原地不动（仍是 SENT，由调用方报 Failed）", FriendSearch.Phase.SENT, search.phase)
    }

    @Test
    fun aTransientAnchorMissWaitsForTheNextFrameInsteadOfFailing() {
        // 2026-10-01 真机三次实录：点完「搜好友」后面板还在展开动画里，这一帧「搜索框」定位不到：
        //   `08:02:23.410` / `08:05:22.342` / `08:24:56.231` 都打了"卡在锚点 friend_search_field"。
        // 旧口径直接算**这一步失败**（`PatrolFlow.fail` 计重试）⇒ 08:05 那次重试用完**把整条跑号中止了** ✗；
        // 现在改成"先等下一帧，连续 ANCHOR_MISS_LIMIT 帧都定位不到才认输"。
        val search = newSearch(*three)
        search.start()

        assertTrue("第 1 帧没定位到 ⇒ 等下一帧", search.onAnchorMissed())
        assertTrue("第 2 帧还没定位到 ⇒ 继续等", search.onAnchorMissed())
        assertFalse("第 3 帧仍没定位到 ⇒ 认输（调用方如实停下并写清卡在哪个锚点）", search.onAnchorMissed())
        assertEquals(FriendSearch.ANCHOR_MISS_LIMIT, search.anchorMisses)
    }

    @Test
    fun locatingTheAnchorClearsTheMissStreakAndResetToo() {
        // 定位到一次 ⇒ 连败清零（串行两次瞬态不该被累加成"失败"）
        val search = newSearch(*three)
        search.start()
        assertTrue(search.onAnchorMissed())
        search.onAnchorLocated()
        assertEquals("定位到了 ⇒ 计数清零", 0, search.anchorMisses)
        assertTrue("清零后又能重新等 3 帧", search.onAnchorMissed())

        // 一轮结束（reset）也要清零：否则上一轮的残留会吃掉下一轮的等待额度
        search.reset()
        assertEquals(0, search.anchorMisses)
        search.start()
        assertEquals(0, search.anchorMisses)
    }

    @Test
    fun aNewRunGetsItsWriteBudgetBack() {
        // 2026-10-01 收尾时补的单测逮住的缺陷：`reset()` 原本**没清 writeAttempts** ⇒
        // 上一轮把两次额度用完之后，用户**再跑一遍**时 `retryOnce` 会**立刻**返回 false
        // ⇒"重写一次"的机会被上一轮吃掉（表现为：第二次跑，结果页只要没命中就直接停下）。
        val search = sent(*three)
        search.retryOnce(maxAttempts = 2)
        search.onAnchorPracticed()
        search.onTextWritten(succeeded = true)
        search.onKeyboardDismissed()
        search.onAnchorPracticed()
        assertEquals(2, search.writeAttempts)

        search.reset() // 离开这一步 / 用户重新点一次

        assertEquals(FriendSearch.Phase.IDLE, search.phase)
        assertEquals(0, search.clicks)
        assertNull(search.anchorToClick)
        assertFalse(search.awaitingResults)
        assertEquals("下一轮要有完整的两写额度", 0, search.writeAttempts)
        assertTrue("而且下一轮真的还能重写一次", sent(*three).retryOnce(maxAttempts = 2))
    }

    @Test
    fun practisingAnAnchorOutsideItsStepDoesNotAdvanceTheChain() {
        // 幂等护栏：同一阶段被重复回调（调用方每轮都会问一次）不该把链走乱。
        val search = newSearch(*three)
        search.start()
        search.onAnchorPracticed()
        search.onAnchorPracticed() // → TYPE

        search.onAnchorPracticed() // 写文字阶段没有锚点可点 ⇒ 不推进
        assertEquals(FriendSearch.Phase.TYPE, search.phase)

        search.onTextWritten(succeeded = true)
        search.onKeyboardDismissed()
        search.onAnchorPracticed() // → SENT

        search.onAnchorPracticed() // 已发出 ⇒ 再点也不推进
        assertEquals(FriendSearch.Phase.SENT, search.phase)
    }
}
