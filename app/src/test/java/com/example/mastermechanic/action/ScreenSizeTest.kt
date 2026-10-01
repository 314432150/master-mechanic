package com.example.mastermechanic.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越界判定（NFR-05「点错的代价远高于不点」在点击层的落实）＋ **互转置校正**。
 *
 * 2026-09-30 真机（用户报"卡在了打开好友列表"）：越界判定用的屏幕尺寸停在了**竖屏 1440×3168**
 * （我们自己的 App 在前台时推的），而落点来自**横屏帧 3168×1440** ⇒ `farm_friends` (2912, 429)
 * 被判"屏外"、点击被**静默拒绝**，跑号白等 16 秒后中止；同一坐标在"游戏前台"时又被放行。
 * 修法 = [ScreenSize.forFrame]（互为转置 ⇒ 按帧的几何判）。
 */
class ScreenSizeTest {

    /** 真机那一对：游戏横屏帧（3168×1440）vs 我们自己的 App 竖屏（1440×3168，本 App 锁竖屏）。 */
    private val portraitScreen = ScreenSize(1440, 3168)
    private val landscapeScreen = ScreenSize(3168, 1440)

    @Test
    fun transposedScreenIsCorrectedToTheFrameGeometry() {
        val fixed = ScreenSize.forFrame(portraitScreen, frameWidth = 3168, frameHeight = 1440)

        assertEquals("互为转置 ⇒ 按帧几何：3168x1440", landscapeScreen, fixed)
        assertTrue("真机那一枪的落点必须被放行", fixed.project(2912.0, 429.0).onScreen)
        // 对照（这就是那个 bug）：不校正时 2912 被判屏外
        assertFalse("竖屏尺寸会把 x>1440 的落点全判成屏外", portraitScreen.project(2912.0, 429.0).onScreen)
    }

    @Test
    fun sameOrientationIsLeftUntouched() {
        // 同向（游戏在前台的常态）⇒ 一个字不改；竖屏帧 + 竖屏屏幕同样不改
        assertEquals(landscapeScreen, ScreenSize.forFrame(landscapeScreen, frameWidth = 3168, frameHeight = 1440))
        assertEquals(portraitScreen, ScreenSize.forFrame(portraitScreen, frameWidth = 1440, frameHeight = 3168))
    }

    @Test
    fun unknownFrameSizeDoesNotRewriteTheScreen() {
        // 还没收到过帧（0×0）⇒ **不校正**：不拿"不知道"去顶掉一个已知值（与 UNKNOWN 的不拦口径同源）
        assertEquals(portraitScreen, ScreenSize.forFrame(portraitScreen, frameWidth = 0, frameHeight = 0))
        assertEquals(portraitScreen, ScreenSize.forFrame(portraitScreen, frameWidth = 3168, frameHeight = 0))
    }

    @Test
    fun unknownScreenSizeNeverBlocks() {
        // 既有口径（别在这次修正里被带偏）：尺寸未知 ⇒ 不做越界判定 ⇒ 不拦
        assertTrue(ScreenSize.UNKNOWN.project(9999.0, 9999.0).onScreen)
    }

    @Test
    fun inBoundsRuleIsStrictlyInsideTheFrame() {
        // 边界：右 / 下边界是**排他**的（与 project 的实现一致，单测把口径钉死）
        assertTrue(landscapeScreen.project(0.0, 0.0).onScreen)
        assertTrue(landscapeScreen.project(3167.0, 1439.0).onScreen)
        assertFalse(landscapeScreen.project(3168.0, 1439.0).onScreen)
        assertFalse(landscapeScreen.project(3167.0, 1440.0).onScreen)
        assertFalse("负坐标同样不合法", landscapeScreen.project(-1.0, 10.0).onScreen)
    }
}
