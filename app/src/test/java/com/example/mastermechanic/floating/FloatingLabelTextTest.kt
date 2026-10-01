package com.example.mastermechanic.floating

import com.example.mastermechanic.patrol.PatrolFlow
import com.example.mastermechanic.patrol.PatrolFlow.Outcome
import com.example.mastermechanic.patrol.PatrolFlow.Range
import com.example.mastermechanic.patrol.PatrolFlow.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部状态标签显示哪一句（2026-09-28；纯逻辑）。
 *
 * 这里钉两件事：
 * 1. **优先级** —— 说错话的后果不是不好看，而是**骗人**（"已中止"之后标签还写着"在等"）；
 * 2. **长度** —— 真机报障原话："有些内容太长了，显示不完，而且太长了看不过来"
 *    （第一版直接显示 `PatrolSession.lastNote`，那句 26 字，标签被截断）⇒ 每条都必须 ≤ [FloatingLabelText.MAX_CHARS]。
 */
class FloatingLabelTextTest {

    private val words = FloatingLabelText.Words(
        idle = "待命",
        failed = "已中止",
        paused = "已暂停",
        finished = "已完成",
        // 文案与 `strings.xml` 的 `floating_label_frame_stalled` 一致（本对象不碰安卓资源 ⇒ 这里手写）
        frameStalled = "画面久未更新",
        frameStalledAction = "画面久未更新·请重授权",
        // 与 `strings.xml` 的 `floating_label_starved` 一致（本对象不碰安卓资源 ⇒ 这里手写）
        frameStarved = "授权没被投喂·请退出重开",
    )

    private fun label(
        reading: String? = null,
        closing: String? = null,
        patrol: PatrolFlow.State? = null,
        frameStale: FloatingLabelText.FrameStale = FloatingLabelText.FrameStale.NONE,
    ): String = FloatingLabelText.of(reading, closing, patrol, words, frameStale)

    @Test
    fun nothingRunningShowsIdle() {
        assertEquals("待命", label())
    }

    @Test
    fun readingTheWholeScreenWinsOverEverything() {
        // 试读 / 读取服务器期间**面板是收起的**（读屏要让它让开）⇒ 标签是唯一还能说"我在读"的地方
        val aborted = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
            .copy(outcome = Outcome.FAILED, reason = "找不到「换区」按钮")

        assertEquals("正在试读", label(reading = "正在试读", closing = "正在关弹窗", patrol = aborted))
        // 空串等于"没有"（调用方解析不出来时给空串，不该显示成一格空白）
        assertEquals("待命", label(reading = ""))
    }

    @Test
    fun stoppedPatrolWinsOverClosingPopupAndRunningText() {
        // 中止 / 暂停是"**需要你决定**"的状态（手柄同时转暖橙红）⇒ 压过"我正在关弹窗"
        val aborted = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
            .copy(outcome = Outcome.FAILED, reason = "找不到「换区」按钮")
        val paused = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
            .copy(paused = true)

        assertEquals("已中止", label(closing = "正在关弹窗", patrol = aborted))
        assertEquals("已暂停", label(closing = "关弹窗 第3次", patrol = paused))
    }

    @Test
    fun closingPopupShowsWithoutAPatrol() {
        // 真机回归（2026-09-29 用户报障"**关闭弹窗依旧显示待命**"）：登录后被自动关掉的那一串弹窗，
        // 这时候**没有跑号流程**（patrol == null）—— "关弹窗"必须排在"没流程 ⇒ 待命"之前。
        assertEquals("正在关弹窗", label(closing = "正在关弹窗"))
        assertEquals("关弹窗 第3次", label(closing = "关弹窗 第3次"))
    }

    @Test
    fun closingPopupWinsOverRunningAndFinished() {
        // 用户报障："自动关闭弹窗时没有显示提示信息" ⇒ 这一句必须出现；
        // "已完成"只是结果话，让位给正在进行的事
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val finished = running.copy(outcome = Outcome.FINISHED)

        assertEquals("正在关弹窗", label(closing = "正在关弹窗", patrol = running))
        assertEquals("正在关弹窗", label(closing = "正在关弹窗", patrol = finished))
    }

    @Test
    fun runningPatrolShowsOnlyWhatItIsDoing() {
        // 用户口径（2026-09-29）："去除「第x步」、步骤序号这种不重要的信息，**关键是目前在做什么**"
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))

        // 大厅起点 = 第 3 步「退出登录」⇒ 标签只说动作名（序号展开菜单就能看到）
        assertEquals("退出登录", label(patrol = running))
        assertEquals("退出登录", FloatingLabelText.runningText(running))
        // 换一步同样只有动作名
        assertEquals("打开好友列表", FloatingLabelText.runningText(running.copy(step = PatrolFlow.Step.OPEN_FRIENDS)))
    }

    @Test
    fun finishedPatrolSaysFinished() {
        val finished = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
            .copy(outcome = Outcome.FINISHED)

        assertEquals("已完成", label(patrol = finished))
    }

    // ------------------------------------------------ 待命 ⇒ 标签整条藏掉（2026-09-30 用户口径）

    @Test
    fun idleMeansTheLabelHasNothingToSay() {
        // 用户口径：待命期把标签整条藏掉（只藏标签、手柄留着）。
        // 判据 = 三件（读数动作 / 关弹窗 / 跑号流程）都没有 —— 与 `of` 同一套输入。
        assertTrue("什么都没在跑 ⇒ 没有话说", FloatingLabelText.isIdle(null, null, null))
        assertTrue("空串等同没有", FloatingLabelText.isIdle("", "", null))

        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val cases = listOf(
            "正在读服务器" to FloatingLabelText.isIdle("正在读服务器", null, null),
            "正在关弹窗" to FloatingLabelText.isIdle(null, "正在关弹窗", null),
            "跑号进行中" to FloatingLabelText.isIdle(null, null, running),
            "已完成" to FloatingLabelText.isIdle(null, null, running.copy(outcome = Outcome.FINISHED)),
            "已中止" to FloatingLabelText.isIdle(null, null, running.copy(outcome = Outcome.FAILED)),
            "已暂停" to FloatingLabelText.isIdle(null, null, running.copy(paused = true)),
        )
        cases.forEach { (what, idle) ->
            assertFalse("「$what」还有话要说，不能跟着待命一起被藏掉", idle)
        }
    }

    @Test
    fun isIdleAgreesWithTheIdleWordFromTheTextPicker() {
        // 两处判据必须永远一致：`of` 给出「待命」⇔ `isIdle` 为真。
        // 将来若有人只改了一处，这条会先炸 —— 比"界面忽然不显示标签了"好定位得多
        //（尤其"已完成"那条：它必须留在屏上）。
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val combos = listOf(
            Triple(null, null, null),
            Triple("正在读服务器", null, null),
            Triple(null, "正在关弹窗", null),
            Triple(null, null, running),
            Triple(null, "关弹窗 第2次", running.copy(paused = true)),
            Triple(null, null, running.copy(outcome = Outcome.FINISHED)),
            Triple(null, null, running.copy(outcome = Outcome.FAILED, reason = "找不到按钮")),
        )
        combos.forEach { (reading, closing, patrol) ->
            assertEquals(
                "reading=$reading closing=$closing patrol=$patrol",
                label(reading, closing, patrol) == words.idle,
                FloatingLabelText.isIdle(reading, closing, patrol),
            )
        }
    }

    // ------------------------------------------------ 画面久未更新（2026-09-30，L2）

    @Test
    fun staleFramesTakeTheLabelOnlyWhenSomethingIsWaitingForThem() {
        // 真机（2026-09-30）："停更 60 秒 ⇒ 判镜像失效"那条判据**静默结束了会话**，用户只看到
        // "跑号跑到一半被要求重新授权"；那一刻程序其实**已经停止了一切点击**，界面却一个字都没说。
        // 现在改成"只提示、不停会话" ⇒ 这句话必须出现在标签上（有活儿在等画面时）。
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val recent = FloatingLabelText.FrameStale.RECENT

        assertEquals("跑号在等新画面 ⇒ 说「画面久未更新」", "画面久未更新", label(patrol = running, frameStale = recent))
        assertEquals("关弹窗在等新画面 ⇒ 也说", "画面久未更新", label(closing = "正在关弹窗", frameStale = recent))
        // ⚠ 压过"在做什么"（那句是**旧画面上的结论**），但**让位给**"已中止 / 已暂停"（那是要用户决定的事）
        assertEquals("已中止", label(patrol = running.copy(outcome = Outcome.FAILED), frameStale = recent))
        assertEquals("已暂停", label(patrol = running.copy(paused = true), frameStale = recent))
        // ⚠ 也压不过"正在读整屏"（读屏期间面板让开，标签是唯一还能说"我在读"的地方）
        assertEquals("正在读服务器", label(reading = "正在读服务器", patrol = running, frameStale = recent))
    }

    @Test
    fun aProlongedStallSaysWhatTheUserCanDo() {
        // 用户口径（2026-09-30）："停更持续时，提示里给'能做的事'" —— 到 120 秒（自动"换镜像表面"已试过
        // 两轮）就该说**处置**，而不是继续说"画面久未更新"（真机报障原话：看到那句话，不知道要做什么）。
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val prolonged = FloatingLabelText.FrameStale.PERSISTENT

        assertEquals(
            "停更很久 ⇒ 说处置",
            "画面久未更新·请重授权",
            label(patrol = running, frameStale = prolonged),
        )
        // ⚠ 让位规则与"刚过 60 秒"那一档**完全一致**（只有文案变了，优先级一个字都没动）
        assertEquals("已中止", label(patrol = running.copy(outcome = Outcome.FAILED), frameStale = prolonged))
        assertEquals("已暂停", label(patrol = running.copy(paused = true), frameStale = prolonged))
        // ⚠ 没给处置文案 ⇒ 退回只说事实（宁可只说事实，也不要让标签变成空胶囊）
        assertEquals(
            "没给处置文案 ⇒ 退化成事实句",
            "画面久未更新",
            FloatingLabelText.of(null, null, running, words.copy(frameStalledAction = ""), prolonged),
        )
    }

    // ------------------------------------------------ 这次授权没被投喂（2026-10-01，方案 2）

    @Test
    fun theLastStepAlreadySaysFinished() {
        // 用户 2026-10-01 报："流程结束后，显示了一个「完成」，接着又显示了一个「已完成」，重复了。"
        // 第 10 步（`Step.DONE`）的步骤名就叫「完成」，它从跑起来到 `outcome = FINISHED` 之间会先闪一下
        // 步骤名 ⇒ 只留结果话那句（两步说的是同一件事）。
        val done = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.VISIT_ONLY))
            .copy(step = PatrolFlow.Step.DONE, outcome = Outcome.RUNNING)
        assertEquals("第 10 步（完成）⇒ 直接说「已完成」，不闪步骤名", "已完成", label(patrol = done))
        assertEquals("收尾成 FINISHED 后仍是同一句", "已完成", label(patrol = done.copy(outcome = Outcome.FINISHED)))
    }

    @Test
    fun aSessionThatNeverGotAFrameSaysSoEvenWithNothingRunning() {
        // 真机（2026-10-01 01:16）：新会话建好后平台一帧都没投喂 ⇒ 停更看门狗（要求"**曾经**有帧"）
        // 一句不说，用户干等，跑号还跑到第 3 步才发现整轮白跑（卡在退出登录页）。这一档就是为它加的。
        val starved = FloatingLabelText.FrameStale.NEVER
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))

        assertEquals(
            "待命 + 从没被投喂 ⇒ 直接说处置（这一档**不要求**'有活儿在等画面'：那时正是最该说的时刻）",
            "授权没被投喂·请退出重开",
            label(frameStale = starved),
        )
        assertEquals(
            "跑号中被投喂不了 ⇒ 说这句（比「第几步」重要）",
            "授权没被投喂·请退出重开",
            label(patrol = running, frameStale = starved),
        )
        // ⚠ 让位规则：正在读整屏 / 已中止 / 已暂停 仍更高（它们说的是"我在做 / 需要你决定"）
        assertEquals("正在读服务器", label(reading = "正在读服务器", patrol = running, frameStale = starved))
        assertEquals("已中止", label(patrol = running.copy(outcome = Outcome.FAILED), frameStale = starved))
        assertEquals("已暂停", label(patrol = running.copy(paused = true), frameStale = starved))
        // ⚠ 这一档**不算待命**：否则标签整条被摘掉、用户什么都看不到 —— 而这正是要他看见的场景
        assertFalse(FloatingLabelText.isIdle(null, null, null, frameStarved = true))
        // ⚠ 没给这句文案 ⇒ 走别的档（宁可说别的，也不要让标签变成空胶囊）
        assertEquals(
            "没给这句文案 ⇒ 退回别的档",
            "待命",
            FloatingLabelText.of(null, null, null, words.copy(frameStarved = ""), starved),
        )
    }

    @Test
    fun staleFramesStaySilentWhenNothingWaitsForThem() {
        // "屏幕静止 ⇒ 平台不产帧"是常态（2026-09-30 真机实测：目标在前台、农场静止、60 秒零新帧）
        // ⇒ 待命 / 已完成时挂这句话只是噪声，还会把"已完成"这类**结果话**挤掉（两档都一样）。
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val finished = running.copy(outcome = Outcome.FINISHED)
        val recent = FloatingLabelText.FrameStale.RECENT
        val prolonged = FloatingLabelText.FrameStale.PERSISTENT

        assertEquals("待命 + 停更 ⇒ 仍是待命（标签整条摘掉）", "待命", label(frameStale = recent))
        assertEquals("待命 + 停更很久 ⇒ 仍是待命", "待命", label(frameStale = prolonged))
        assertEquals(
            "已完成 + 停更 ⇒ 仍是「已完成」（结果话不该被挤掉）",
            "已完成",
            label(patrol = finished, frameStale = recent),
        )
        // 与 `isIdle` 一致：上面那两种正是"没有话说"（`isIdle` 只看三件在不在 —— 「画面久未更新」
        // 只在有活儿等画面时出现，见 `FloatingLabelText.isIdle` 的说明）
        assertTrue(FloatingLabelText.isIdle(null, null, null))
        // ⚠ 没给这句文案时**不显示**（否则标签会变成一个空胶囊，用户会以为界面坏了）
        assertEquals(
            "给了 frameStale 但没给文案 ⇒ 走别的档",
            "退出登录",
            FloatingLabelText.of(null, null, running, words.copy(frameStalled = ""), recent),
        )
    }

    @Test
    fun staleFramesTextFitsTheGlanceLimit() {
        // 真机报障（2026-09-29）："宽度有点窄了，`关闭弹窗` 都无法显示完全" ⇒ 每句都要能在 12 字内念完
        assertEquals("画面久未更新", words.frameStalled)
        assertEquals("画面久未更新·请重授权", words.frameStalledAction)
        assertEquals("授权没被投喂·请退出重开", words.frameStarved)
        assertTrue(
            "「没被投喂」那句也不能超上限：${words.frameStarved}",
            words.frameStarved.length <= FloatingLabelText.MAX_CHARS,
        )
        assertTrue(words.frameStalled.length <= FloatingLabelText.MAX_CHARS)
        assertTrue(
            "处置那句也不能超上限：${words.frameStalledAction}",
            words.frameStalledAction.length <= FloatingLabelText.MAX_CHARS,
        )
    }

    @Test
    fun everyLabelFitsTheGlanceLimit() {
        // 抽一个最长的组合：跑号在跑 + 关弹窗（走分支最多的那几条）都必须在 12 字以内
        val running = requireNotNull(PatrolFlow.start(Scene.LOBBY, Range.SWITCH_ONLY))
        val samples = listOf(
            label(),
            label(reading = "正在读服务器"),
            label(closing = "正在关弹窗", patrol = running),
            // 最长的两种：关弹窗第 N 次、菜单里最长的步骤名
            label(closing = "关弹窗 第12次", patrol = running),
            label(patrol = running),
            label(patrol = running.copy(outcome = Outcome.FINISHED)),
        )
        samples.forEach { text ->
            assertTrue("「$text」超过 ${FloatingLabelText.MAX_CHARS} 字", text.length <= FloatingLabelText.MAX_CHARS)
        }
    }

    @Test
    fun tooLongTextIsShortenedAsALastResort() {
        // 兜底：将来谁再加一句长文案（新来源），也不会把标签撑到截断
        val long = "第 3 步：刚点过，等画面切换，继续等（已等 5 秒）"

        val short = FloatingLabelText.shorten(long)

        assertEquals(FloatingLabelText.MAX_CHARS, short.length)
        assertTrue(short.endsWith("…"))
        // 短文本原样返回（不该被动手脚）
        assertEquals("待命", FloatingLabelText.shorten("待命"))
    }
}
