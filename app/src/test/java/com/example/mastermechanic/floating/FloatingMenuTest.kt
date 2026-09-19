package com.example.mastermechanic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗菜单的纯逻辑。
 *
 * 2026-09-16：跑号 / 巡查与"好友二次确认"已按用户口径移除（一级三项 = 拜访 / 换号 / 停止），
 * 本文件随之重写 —— 只覆盖**仍然适用**的判定：层级、开组、返回、收起清栈、失败保持、尺寸计算。
 */
class FloatingMenuTest {

    // ---------------------------------------------------------------- 层级

    @Test
    fun rootStartsAtRootWithoutReason() {
        val state = FloatingMenu.State()
        assertEquals(FloatingMenu.Level.ROOT, state.level)
        assertNull(state.reason)
    }

    @Test
    fun onlySwitchHasASubLevel() {
        // 拜访是"一级直接执行"（目标来自预设），停止也是直接执行 —— 只有换号要选服务器
        assertEquals(FloatingMenu.Level.PICK_SERVER, FloatingMenu.subLevelOf(FloatingMenu.Group.SWITCH))
        assertNull(FloatingMenu.subLevelOf(FloatingMenu.Group.VISIT))
        assertNull(FloatingMenu.subLevelOf(FloatingMenu.Group.SWITCH_AND_VISIT))
        assertNull(FloatingMenu.subLevelOf(FloatingMenu.Group.STOP))
        assertEquals(FloatingMenu.Level.CONFIG, FloatingMenu.subLevelOf(FloatingMenu.Group.SETTING))
    }

    @Test
    fun openingSwitchGoesToOneLevelDown() {
        val next = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.SWITCH)
        assertEquals(FloatingMenu.Level.PICK_SERVER, next.level)
    }

    @Test
    fun openingAGroupWithoutSubLevelChangesNothing() {
        // 没有下一级的项点下去状态不变（动作由界面直接执行）
        val state = FloatingMenu.State()
        assertEquals(state, FloatingMenu.openGroup(state, FloatingMenu.Group.VISIT))
        assertEquals(state, FloatingMenu.openGroup(state, FloatingMenu.Group.SWITCH_AND_VISIT))
        assertEquals(state, FloatingMenu.openGroup(state, FloatingMenu.Group.STOP))
    }

    @Test
    fun openingAGroupClearsPreviousReason() {
        val failed = FloatingMenu.fail(FloatingMenu.State(), "上一次的失败原因")
        val opened = FloatingMenu.openGroup(failed, FloatingMenu.Group.SWITCH)
        assertNull(opened.reason)
    }

    @Test
    fun backReturnsToRootAndDropsReason() {
        val deep = FloatingMenu.fail(
            FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.SWITCH),
            "失败了",
        )
        val back = FloatingMenu.back(deep)
        assertEquals(FloatingMenu.Level.ROOT, back.level)
        assertNull(back.reason)
    }

    @Test
    fun collapsingClearsTheWholeStack() {
        // 收起即清栈：下次展开回到根，不会停在"上次选服务器"那层（避免误点上次的目标）
        val deep = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.SWITCH)
        assertEquals(FloatingMenu.State(), FloatingMenu.collapsed())
        assertEquals(FloatingMenu.Level.PICK_SERVER, deep.level)
    }

    @Test
    fun failKeepsTheCurrentLevelAndAttachesReason() {
        // 失败**停在当前层级**并给原因（不收起、不隐藏、不静默）
        val onServer = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.SWITCH)
        val failed = FloatingMenu.fail(onServer, "当前没有进行中的流程")
        assertEquals(FloatingMenu.Level.PICK_SERVER, failed.level)
        assertEquals("当前没有进行中的流程", failed.reason)
    }

    // ---------------------------------------------------------------- 尺寸

    @Test
    fun panelNeverExceedsTheScreenMinusMargins() {
        assertEquals(344, FloatingMenu.panelMaxHeight(360)) // 横屏可用高 360dp
        assertEquals(776, FloatingMenu.panelMaxHeight(792)) // 竖屏
        assertEquals(0, FloatingMenu.panelMaxHeight(0)) // 极端小屏也不算出负数
    }

    /** 明显超过上限的行数：按当前行高算出来，免得下回调行高又把用例写死。 */
    private val rowsOverCap = 344 / FloatingMenu.PICK_ROW_HEIGHT_DP + 2

    @Test
    fun listHeightGrowsWithContentUntilItHitsTheCap() {
        assertEquals(
            FloatingMenu.PICK_ROW_HEIGHT_DP * 3,
            FloatingMenu.listHeight(3, FloatingMenu.PICK_ROW_HEIGHT_DP, 344),
        )
        assertEquals(344, FloatingMenu.listHeight(rowsOverCap, FloatingMenu.PICK_ROW_HEIGHT_DP, 344))
    }

    @Test
    fun scrollIsOnlyNeededWhenContentIsTallerThanTheCap() {
        assertTrue(!FloatingMenu.needsScroll(3, FloatingMenu.PICK_ROW_HEIGHT_DP, 344))
        assertTrue(FloatingMenu.needsScroll(rowsOverCap, FloatingMenu.PICK_ROW_HEIGHT_DP, 344))
    }

    @Test
    fun visibleRowCountIsAtLeastOne() {
        assertEquals(
            344 / FloatingMenu.PICK_ROW_HEIGHT_DP,
            FloatingMenu.visibleRowCount(344, FloatingMenu.PICK_ROW_HEIGHT_DP),
        )
        assertEquals(1, FloatingMenu.visibleRowCount(10, FloatingMenu.PICK_ROW_HEIGHT_DP))
    }

    @Test
    fun listMaxHeightSubtractsThePanelChrome() {
        assertEquals(248, FloatingMenu.listMaxHeight(360, 96))
    }
}
