package com.example.mastermechanic

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
    fun `无需授权的项不阻塞就绪判定`() {
        val statuses = listOf(
            AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
            AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.GRANTED),
            AuthStatus(AuthItem.NOTIFICATIONS, AuthState.NOT_APPLICABLE),
        )

        assertTrue(AuthorizationSummary.isAllReady(statuses))
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
}
