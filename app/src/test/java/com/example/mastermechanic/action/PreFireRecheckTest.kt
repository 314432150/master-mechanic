package com.example.mastermechanic.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开火前复眼的判据（2026-09-29）：口径与来由见 [PreFireRecheck] 类注释。
 *
 * 这里是"能不能开枪"的最后一道纯逻辑闸，所以边界（刚好 16px、刚好 400ms）都要钉住。
 */
class PreFireRecheckTest {

    @Test
    fun missingHitOnTheFreshFrameIsNotAgreement() {
        // 最新画面上已经不命中（X 没了 / 弹窗已被关掉）⇒ 绝不认作"同一落点"
        assertFalse(PreFireRecheck.agrees(2680.0, 197.0, null, null))
        assertFalse(PreFireRecheck.agrees(2680.0, 197.0, 2680.0, null))
        assertFalse(PreFireRecheck.agrees(2680.0, 197.0, null, 197.0))
    }

    @Test
    fun sameTargetWithSmallDriftAgrees() {
        assertTrue(PreFireRecheck.agrees(2680.0, 197.0, 2680.0, 197.0))
        assertTrue(PreFireRecheck.agrees(2680.0, 197.0, 2694.0, 209.0))
    }

    @Test
    fun driftBoundaryIsInclusive() {
        val tol = PreFireRecheck.MAX_DRIFT_PX
        assertTrue(PreFireRecheck.agrees(0.0, 0.0, tol, 0.0))
        assertTrue(PreFireRecheck.agrees(0.0, 0.0, 0.0, tol))
        assertFalse(PreFireRecheck.agrees(0.0, 0.0, tol + 0.001, 0.0))
    }

    @Test
    fun nonFiniteHitDoesNotAgree() {
        assertFalse(PreFireRecheck.agrees(0.0, 0.0, Double.NaN, 0.0))
        assertFalse(PreFireRecheck.agrees(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun pinTheCaliber() {
        assertEquals(16.0, PreFireRecheck.MAX_DRIFT_PX, 1e-9)
    }
}
