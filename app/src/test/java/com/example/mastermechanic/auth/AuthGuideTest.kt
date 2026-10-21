package com.example.mastermechanic.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权引导的顺序口径（2026-10-02 用户报障后抽出）：**采集授权永远是最后一步**。
 *
 * 用户原话："点击『去授权与权限』的逻辑有问题，目前是直接拉起采集授权操作，**一同意就跳转到游戏了**，
 * 此时**无障碍和守护可能还没启动**，又要切换回 app 来启动这两项。"
 *
 * ⚠ 这里验的是**纯顺序判据**；"真机上点一下到底弹不弹采集窗"要在真机走查（见 `docs/verification/`）。
 */
class AuthGuideTest {

    /** 造一份授权现场：列出的项 = 缺失，其余 = 已就绪（顺序按 [AUTHORIZATION_DISPLAY_ORDER]）。 */
    private fun statuses(vararg missing: AuthItem): List<AuthStatus> =
        AUTHORIZATION_DISPLAY_ORDER.map {
            AuthStatus(it, if (it in missing) AuthState.MISSING else AuthState.GRANTED)
        }

    @Test
    fun captureIsBlockedUntilEverythingAboveItIsDone() {
        // 页面顺序 = 无障碍 → 常驻守护 → 通知 → 屏幕采集（采集排最后：点它会跳到游戏）
        val allMissing = statuses(
            AuthItem.ACCESSIBILITY,
            AuthItem.RESIDENT,
            AuthItem.NOTIFICATIONS,
            AuthItem.SCREEN_CAPTURE,
        )
        assertEquals(
            "前三项都缺 ⇒ 它们都挡在采集前面（顺序就是页面自上而下）",
            listOf(AuthItem.ACCESSIBILITY, AuthItem.RESIDENT, AuthItem.NOTIFICATIONS),
            AuthGuide.blockingCapture(allMissing),
        )
        assertFalse(
            "无障碍还没开 ⇒ 不许直接拉采集（否则用户被送去游戏，还得切回来开无障碍）",
            AuthGuide.captureIsTheOnlyMissing(allMissing),
        )

        val onlyCapture = statuses(AuthItem.SCREEN_CAPTURE)
        assertTrue("只剩采集 ⇒ 这是最后一步，可以一键拉起", AuthGuide.captureIsTheOnlyMissing(onlyCapture))
        assertEquals("只剩采集时没有挡路的项", emptyList<AuthItem>(), AuthGuide.blockingCapture(onlyCapture))

        assertFalse(
            "采集自己**不算**自己的挡路项（否则永远拉不起来）",
            AuthGuide.blockingCapture(onlyCapture).contains(AuthItem.SCREEN_CAPTURE),
        )
    }

    @Test
    fun allReadyMeansNothingToGuide() {
        val ready = statuses()
        assertTrue("四项都好 ⇒ 没有挡路的项", AuthGuide.blockingCapture(ready).isEmpty())
        assertNull("也没有「下一步」", AuthGuide.nextStep(ready))
        assertFalse("采集已授权 ⇒ 谈不上「只剩采集」", AuthGuide.captureIsTheOnlyMissing(ready))
    }

    @Test
    fun nextStepWalksTheSameOrderThePageShows() {
        assertEquals(
            "第一个缺的是守护（无障碍在它上面且已就绪）",
            AuthItem.RESIDENT,
            AuthGuide.nextStep(statuses(AuthItem.RESIDENT, AuthItem.SCREEN_CAPTURE)),
        )
        assertEquals(
            "通知排在采集前面 ⇒ 下一步是通知（不能跳到采集）",
            AuthItem.NOTIFICATIONS,
            AuthGuide.nextStep(statuses(AuthItem.NOTIFICATIONS, AuthItem.SCREEN_CAPTURE)),
        )
        assertEquals(
            "挡着采集的正是通知那一条",
            listOf(AuthItem.NOTIFICATIONS),
            AuthGuide.blockingCapture(statuses(AuthItem.NOTIFICATIONS, AuthItem.SCREEN_CAPTURE)),
        )
    }

    @Test
    fun theOrderHasExactlyOneSourceOfTruth() {
        // 引导**不许自成一套顺序**：它必须与授权页显示顺序（`AUTHORIZATION_DISPLAY_ORDER`）同源，
        // 否则迟早出现"页面从上往下排的"与"引导念的"不一致
        assertEquals(
            AUTHORIZATION_DISPLAY_ORDER.filter { it != AuthItem.SCREEN_CAPTURE },
            AuthGuide.BEFORE_CAPTURE,
        )
        assertEquals("采集永远是最后一项（点它会跳到游戏）", AuthItem.SCREEN_CAPTURE, AUTHORIZATION_DISPLAY_ORDER.last())
    }
}
