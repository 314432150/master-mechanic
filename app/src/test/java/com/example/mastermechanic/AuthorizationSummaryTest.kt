package com.example.mastermechanic

import com.example.mastermechanic.auth.AUTHORIZATION_DISPLAY_ORDER
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationSummaryTest {

    @Test
    fun `全部授权就绪时汇总为就绪`() {
        val statuses = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.GRANTED),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
        )

        assertTrue(AuthorizationSummary.isAllReady(statuses))
        assertEquals(emptyList<AuthItem>(), AuthorizationSummary.missingItems(statuses))
    }

    @Test
    fun `缺一项时汇总为不就绪且列出缺失项`() {
        val statuses = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.MISSING),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.GRANTED),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
        )

        assertFalse(AuthorizationSummary.isAllReady(statuses))
        assertEquals(listOf(AuthItem.ACCESSIBILITY), AuthorizationSummary.missingItems(statuses))
    }

    @Test
    fun `全部缺失时缺失列表保持传入顺序`() {
        val statuses = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.MISSING),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.MISSING),
        )

        assertEquals(
            listOf(AuthItem.ACCESSIBILITY, AuthItem.SCREEN_CAPTURE, AuthItem.NOTIFICATIONS),
            AuthorizationSummary.missingItems(statuses),
        )
    }

    @Test
    fun `授权页顺序把屏幕采集放在最后`() {
        // 2026-09-20 用户口径：点「屏幕采集」会跳到王者荣耀，其它三项必须排在它上面
        assertEquals(AuthItem.SCREEN_CAPTURE, AUTHORIZATION_DISPLAY_ORDER.last())
        assertEquals(
            "顺序常量必须不重不漏地覆盖全部授权项",
            AuthItem.entries.toSet(),
            AUTHORIZATION_DISPLAY_ORDER.toSet(),
        )
        assertEquals(
            "顺序常量本身不得重复",
            AuthItem.entries.size,
            AUTHORIZATION_DISPLAY_ORDER.size,
        )
    }

    @Test
    fun `常驻守护未启动时不算就绪`() {
        // 2026-09-20 用户口径：守护是**必备项** —— 其它都就绪、就它没起，也不许显示"全部就绪"
        val statuses = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.GRANTED),
            AuthStatus(AuthItem.RESIDENT, AuthState.MISSING),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.GRANTED),
        )

        assertFalse(AuthorizationSummary.isAllReady(statuses))
        assertEquals(listOf(AuthItem.RESIDENT), AuthorizationSummary.missingItems(statuses))
    }
}
