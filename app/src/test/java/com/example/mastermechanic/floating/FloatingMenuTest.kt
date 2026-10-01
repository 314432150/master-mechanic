package com.example.mastermechanic.floating

import com.example.mastermechanic.preset.VisitPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun captureResultPageIsSpecialCasedOnlyForTheCaptureFlow() {
        // 2026-09-25 用户两次报障后的口径：**只有**"读数页（OCR_RESULT）+ 最近一次请求是一键添加"
        // 才享受两条特殊待遇 —— ① 不因"点面板之外"收起（滚屏也算那个事件，用户一滚面板就没了）；
        // ② 收起后再展开回到读数页（否则「再读一屏」被藏在根菜单里）。其余情况一律照旧。
        val onCaptureResult = FloatingMenu.State(level = FloatingMenu.Level.OCR_RESULT)

        assertTrue(
            "读数页 + 一键添加流程 ⇒ 特殊待遇生效",
            FloatingMenu.isCaptureResultPage(onCaptureResult, captureMode = true),
        )
        assertFalse(
            "试读的结果页不享受（它没有「再读一屏」这条流）",
            FloatingMenu.isCaptureResultPage(onCaptureResult, captureMode = false),
        )
        assertFalse(
            "别的层级一律照旧",
            FloatingMenu.isCaptureResultPage(
                FloatingMenu.State(level = FloatingMenu.Level.PICK_SERVER),
                captureMode = true,
            ),
        )

        // 回到读数页：保留层级，只清掉原因条
        val resumed = FloatingMenu.resumeCaptureResult(
            FloatingMenu.State(level = FloatingMenu.Level.OCR_RESULT, reason = "上一句提示"),
        )
        assertEquals(FloatingMenu.Level.OCR_RESULT, resumed.level)
        assertNull(resumed.reason)
    }

    // ---------------------------------------------------------------- 层级

    @Test
    fun rootStartsAtRootWithoutReason() {
        val state = FloatingMenu.State()
        assertEquals(FloatingMenu.Level.ROOT, state.level)
        assertNull(state.reason)
    }

    @Test
    fun switchAndVisitBothHaveASubLevel() {
        // 2026-09-30 用户口径：只拜访改成**先选好友**（与换号选服务器对称）——
        // 预设只有一个好友，而手上常常想临时去别人家。
        assertEquals(FloatingMenu.Level.PICK_SERVER, FloatingMenu.subLevelOf(FloatingMenu.Group.SWITCH))
        assertEquals(FloatingMenu.Level.PICK_FRIEND, FloatingMenu.subLevelOf(FloatingMenu.Group.VISIT))
        // 换号+拜访（**整行点击 = 执行**，参数走行尾 ⚙）与停止仍是"一级直接执行"——
        // ⚠ 它**不该**有下一级：有的话"点一下就跑一轮"就变成"点两下"，用户最高频的动作会变慢。
        assertNull(FloatingMenu.subLevelOf(FloatingMenu.Group.SWITCH_AND_VISIT))
        assertNull(FloatingMenu.subLevelOf(FloatingMenu.Group.STOP))
    }

    @Test
    fun theGearOnTheSwitchVisitRowOpensThePresetEditorWithACopyOfTheCurrentPreset() {
        // 2026-09-30 用户口径：「换号+拜访」**行尾 ⚙** = 改服务器 / 好友（原根菜单「拜访规则」行撤掉后，
        // 这是那份预设**唯一**的入口）。它以**当前预设**为草稿 ⇒ 改到一半取消 = 什么都没变。
        val current = VisitPreset.EMPTY
        val state = FloatingMenu.openConfig(FloatingMenu.State(), current)

        assertEquals(FloatingMenu.Level.CONFIG, state.level)
        assertEquals(current, state.draft)
        assertTrue("进了参数层就该显示「保存」行", state.isEditing)
        assertNull("上一次的原因条要清掉，别和参数挤在一起", state.reason)
    }

    @Test
    fun openingVisitGoesToPickFriend() {
        val next = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.VISIT)
        assertEquals(FloatingMenu.Level.PICK_FRIEND, next.level)
    }

    @Test
    fun openingSwitchGoesToOneLevelDown() {
        val next = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.SWITCH)
        assertEquals(FloatingMenu.Level.PICK_SERVER, next.level)
    }

    @Test
    fun openingAGroupWithoutSubLevelChangesNothing() {
        // 没有下一级的项点下去状态不变（动作由界面直接执行）
        // ⚠ 只拜访 2026-09-30 起**有**下一级（选好友），已挪到 openingVisitGoesToPickFriend
        val state = FloatingMenu.State()
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

    @Test
    fun noticeIsForInformationAndIsMutuallyExclusiveWithReason() {
        // 用户 2026-10-01 口径："提示类信息不应该使用红色" ⇒ 提示与失败**共用同一条显示位**，
        // 但语义不同（提示 = 只是告知 ⇒ 界面中性色；失败 = 你被拒了 ⇒ 界面红色）⇒ 两者必须互斥：
        // 否则用户会同时看到"刚才那件事失败了"和"这件事已经做完"，不知道哪句才是当前状态。
        val onMore = FloatingMenu.openGroup(FloatingMenu.State(), FloatingMenu.Group.MORE)
        val failed = FloatingMenu.fail(onMore, "现在不能这么做")

        val noticed = FloatingMenu.notice(failed, "提示位置已重置")

        assertEquals("提示停在当前层级", FloatingMenu.Level.MORE, noticed.level)
        assertEquals("提示位置已重置", noticed.notice)
        assertNull("挂提示要清掉上一条失败", noticed.reason)

        val failedAgain = FloatingMenu.fail(noticed, "又不行了")

        assertNull("反过来也一样：挂失败要清掉上一条提示", failedAgain.notice)
        assertEquals("又不行了", failedAgain.reason)
    }

    // ---------------------------------------------------------------- 试读结果页（2026-09-23）

    @Test
    fun openingTheOcrResultClearsTheReason() {
        // 试读结果页单开一层，把整块面板让给报告 —— 进这一层必须清掉上一次的原因条，
        // 否则上一次的失败文案会和报告挤在一起（面板只有 208dp 宽，两段文字一起就没法读了）
        val failed = FloatingMenu.fail(FloatingMenu.State(), "上一次的失败原因")

        val opened = FloatingMenu.openOcrResult(failed)

        assertEquals(FloatingMenu.Level.OCR_RESULT, opened.level)
        assertNull(opened.reason)
    }

    @Test
    fun theOcrResultGoesBackToRoot() {
        // 结果页没有下级：返回一律回根（报告看完就回主菜单），与「设置」那几层的返回同口径
        val opened = FloatingMenu.openOcrResult(FloatingMenu.State())

        assertEquals(FloatingMenu.Level.ROOT, FloatingMenu.back(opened).level)
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

    @Test
    fun reviewPanelIsHalfTheScreenWideWithAFloor() {
        // 真机横屏 792dp ⇒ 半屏 396dp（2026-09-28 用户口径："改为半屏宽"）
        assertEquals(396, FloatingMenu.reviewPanelWidth(792))
        // 窄屏兜底：半屏比下限还窄时取下限（否则两列卡片会挤成一条）
        assertEquals(FloatingMenu.REVIEW_MIN_WIDTH_DP, FloatingMenu.reviewPanelWidth(400))
    }

    @Test
    fun reviewCardsAndActionButtonsSplitTheInnerWidthEvenly() {
        val padding = 4
        val inner = FloatingMenu.reviewInnerWidth(792, padding)
        assertEquals(FloatingMenu.reviewPanelWidth(792) - padding * 2, inner)
        // 两列卡片 + 一处列间隙 ≤ 内容区（整数除法允许几 dp 零头；**绝不允许超出去**）
        val card = FloatingMenu.reviewCardWidth(792, padding)
        assertEquals(192, card)
        assertTrue(card * FloatingMenu.REVIEW_COLUMNS + FloatingMenu.COLUMN_GAP_DP <= inner)
        // 三个按钮 + 两处间隙 ≤ 内容区
        val action = FloatingMenu.reviewActionWidth(792, padding)
        assertEquals(126, action)
        assertTrue(action * FloatingMenu.REVIEW_ACTION_COUNT + FloatingMenu.COLUMN_GAP_DP * 2 <= inner)
    }

    @Test
    fun reviewSizesNeverGoNegativeOnAnAbsurdlyNarrowScreen() {
        assertEquals(0, FloatingMenu.reviewInnerWidth(0, 999))
        assertEquals(0, FloatingMenu.reviewCardWidth(0, 999))
        assertEquals(0, FloatingMenu.reviewActionWidth(0, 999))
    }

    // ---------------------------------------------------------------- 「关弹窗受阻」的原因（2026-09-29）

    @Test
    fun popupBlockedNoticeIsHiddenWhenThereIsNoReason() {
        // 用户口径："把「关弹窗受阻」的原因也显示在悬浮窗菜单里" —— 但**没有受阻时一行都不能多**：
        // 菜单是玩家在游戏里点的东西，凭空多一行"关弹窗受阻："（后面什么都没有）比不显示更费解。
        assertNull(FloatingMenu.popupBlockedNotice(null))
        assertNull(FloatingMenu.popupBlockedNotice(""))
        assertNull("纯空白也不算原因（否则会渲染出一行空壳）", FloatingMenu.popupBlockedNotice("   "))
    }

    @Test
    fun popupBlockedNoticeShowsTheReasonTrimmed() {
        // 原因直接来自帧线程（`PopupCloseSignal.markBlocked`），原样显示即可 —— 只去掉首尾空白，
        // 免得菜单里出现一行"…… ： 遮挡屏在屏"这种多一个空格的排版
        assertEquals(
            "遮挡屏在屏，但关闭控件锚点没命中",
            FloatingMenu.popupBlockedNotice("  遮挡屏在屏，但关闭控件锚点没命中  "),
        )
    }
}
