package com.example.mastermechanic.patrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 5 步「逐屏滚动查找」状态机（2026-09-29）：口径与来由见 [ServerListScan] 类注释。
 *
 * 覆盖：先回顶 → 到顶转向下 → 到底放弃 / 上限 / 稳定等待 / 滑动被拒要原样撤回。
 */
class ServerListScanTest {

    private val settle = ServerListScan.SETTLE_MS

    /**
     * 一屏的行键（用真实形态：区号 + 名字段）。
     *
     * ⚠ **每个输入会自动补足到 3 个键**（按输入确定性生成，同名输入补出来的一样）：
     * 2026-10-01 起"判这一屏与上一屏是不是同一屏"要求**两边至少 [ServerListScan.MIN_KEYS_FOR_SAME_SCREEN] 个键**
     * —— 真机那次事故正是"2 行样本（2/3 = 66%）"被判成了"同一屏"⇒ 把"换了一屏"误判成"已到底"。
     * 这些用例关心的是**扫描的动力学**（怎么滚、什么时候放弃），不该被样本大小左右 ⇒ 这里补足，
     * 语义与原来一致（同样的输入 ⇒ 同样的键集合；不同输入 ⇒ 不重合）。
     */
    private fun screen(vararg keys: String): Set<String> =
        keys.toSet() + keys.flatMap { listOf("pad-$it-1", "pad-$it-2") }

    private fun newScan(
        maxScrolls: Int = ServerListScan.MAX_SCROLLS,
        maxTopScrolls: Int = ServerListScan.MAX_TO_TOP_SCROLLS,
    ) = ServerListScan(maxScrolls = maxScrolls, maxTopScrolls = maxTopScrolls)

    /** 一屏 12 行的行键（数字形态，与真机口径一致）。 */
    private fun rows(from: Int, count: Int = 12): Set<String> = (from until from + count).map { "n$it" }.toSet()

    /**
     * 走完"回顶到顶"这一程：**连续两次"真的滑过、但画面没动"才判到顶**（2026-10-01 起，
     * 见 `ServerListScan.EDGE_PROBE_MAX` —— 一次"没动"可能是手势被游戏吞掉 / 被门禁拒，
     * 说明不了到没到顶；真机就是因此"没滑到顶就转头往下走"）。
     *
     * 前提：调用前刚下发过一次滑动、且当前屏与"滑之前那一屏"相同（= 第一次"没动"）。
     *
     * @return 判到顶那一轮给出的步骤（正常是 [ServerListScan.Step.ScrollDown]）
     */
    private fun finishTopConfirmation(
        scan: ServerListScan,
        atTop: Set<String>,
        startMs: Long,
    ): ServerListScan.Step {
        assertEquals("第 1 次「没动」只该再滑一次确认", ServerListScan.Step.ScrollTowardsTop, scan.onMiss(atTop, startMs))
        scan.onScrollDispatched(startMs)
        return scan.onMiss(atTop, startMs + settle)
    }

    @Test
    fun theFirstMissStartsGoingBackToTheTop() {
        // 用户口径："先滚到顶再自上而下找" ⇒ 第一屏没中就先回顶（手指向下划）
        val scan = newScan()

        val step = scan.onMiss(screen("415碧海之眼", "416火鹰重炮"), 1_000L)

        assertEquals(ServerListScan.Step.ScrollTowardsTop, step)
        assertEquals(ServerListScan.Phase.TO_TOP, scan.phase)
        assertEquals(1, scan.topScrolls)
        assertEquals("回顶不占「往下找」那本 5 屏的额度", 0, scan.scrolls)
    }

    @Test
    fun keepsGoingUpWhileTheScreenStillMovesThenTurnsDownAtTheTop() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L)
        scan.onScrollDispatched(1_000L)

        // 滑完换了一屏 ⇒ 还在回顶路上
        val stillMoving = scan.onMiss(screen("b"), 1_000L + settle)
        assertEquals(ServerListScan.Step.ScrollTowardsTop, stillMoving)
        scan.onScrollDispatched(1_000L + settle)

        // 再滑一屏画面**没变**：第 1 次"没动"只该"再滑一次确认"（见 EDGE_PROBE_MAX）
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(screen("b"), 1_000L + 2 * settle))
        scan.onScrollDispatched(1_000L + 2 * settle)

        // 第 2 次仍没动 ⇒ 到顶了 ⇒ 转"自上而下"
        val atTop = scan.onMiss(screen("b"), 1_000L + 3 * settle)
        assertEquals(ServerListScan.Step.ScrollDown, atTop)
        assertEquals(ServerListScan.Phase.DOWN, scan.phase)
    }

    @Test
    fun givesUpWhenTheListStopsMovingWhileScanningDown() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L) // 当前屏没中 ⇒ 回顶
        scan.onScrollDispatched(1_000L)
        // 到顶（连续两次"真的滑过但没动"，见 finishTopConfirmation）⇒ 转往下找
        val atTop = finishTopConfirmation(scan, screen("a"), 1_000L + settle)
        assertEquals(ServerListScan.Step.ScrollDown, atTop)
        scan.onScrollDispatched(1_000L + 2 * settle)

        // 向下换了一屏 ⇒ 继续
        assertEquals(ServerListScan.Step.ScrollDown, scan.onMiss(screen("b"), 1_000L + 3 * settle))
        scan.onScrollDispatched(1_000L + 3 * settle)

        // 画面没动（第 1 次）⇒ **再滑一次确认**（一次"没动"可能是手势被吞 ⇒ 不足以说"到底"）
        assertEquals(ServerListScan.Step.ScrollDown, scan.onMiss(screen("b"), 1_000L + 4 * settle))
        scan.onScrollDispatched(1_000L + 4 * settle)

        // 第 2 次仍没动 ⇒ 到底了 ⇒ 如实放弃（红线 3，不猜）
        val bottom = scan.onMiss(screen("b"), 1_000L + 5 * settle)
        assertTrue("必须是 GiveUp：$bottom", bottom is ServerListScan.Step.GiveUp)
        assertTrue((bottom as ServerListScan.Step.GiveUp).reason.contains("底部"))
    }

    @Test
    fun respectsTheScrollCap() {
        // 这本"2 屏"的账**只记"往下找"**（回顶另有一本，见 ServerListScan.propose 的说明）
        val scan = newScan(maxScrolls = 2)
        scan.noteEntryAtTop(true) // 刚登录 ⇒ 直接往下找
        scan.onMiss(rows(100), 1_000L); scan.onScrollDispatched(1_000L)
        assertEquals(1, scan.scrolls)
        scan.onMiss(rows(200), 1_000L + settle); scan.onScrollDispatched(1_000L + settle)
        assertEquals(2, scan.scrolls)

        // 第三次提议时已达上限 ⇒ 放弃并说清"上限"
        val capped = scan.onMiss(rows(300), 1_000L + 2 * settle)
        assertTrue("必须是 GiveUp：$capped", capped is ServerListScan.Step.GiveUp)
        assertTrue((capped as ServerListScan.Step.GiveUp).reason.contains("上限"))
        assertEquals(2, scan.scrolls)
    }

    @Test
    fun goingBackToTheTopDoesNotEatTheSearchBudget() {
        // 2026-10-01 真机事故（用户："没有滑到顶就开始往底部滑了，**由于上限 5 屏的限制吗？**"）：
        // 一次拖动只走 1/3~1/2 屏 ⇒ 回顶那几下若也记进"5 屏"这本账，"回顶 3 下 + 往下 2 下"就烧光额度
        // ⇒ 之后每一轮都"已达滑动上限 5 屏"、整步卡死（实录 07:04:49 起连续 10+ 轮都是它）。
        // ⇒ 两本账分开：**回顶不占"往下找"的额度**。
        val scan = newScan(maxScrolls = 2)
        var at = 1_000L
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(rows(100), at))
        repeat(4) { i ->
            scan.onScrollDispatched(at)
            at += settle
            assertEquals(
                "回顶第 ${i + 2} 下：额度够就继续往上",
                ServerListScan.Step.ScrollTowardsTop,
                scan.onMiss(rows(100 + (i + 1) * 30), at),
            )
        }
        assertEquals("回顶一下都不该记进'往下找'那本账", 0, scan.scrolls)
    }

    @Test
    fun goingUpEndsByDistanceNotBySwipeCount() {
        // 2026-10-01 真机：`回顶已滑 3 下（上限 3）⇒ 当作已到顶`，其实只上移了约 1.5 屏 ✗（用户报
        // "没有滑到顶就开始往底部滑了"）⇒ 现在回顶按**累计上移**（MAX_TO_TOP_PX ≈ 3.2 屏）收尾。
        // 下面每一枪只上移 400px（≈1/3 屏），连续 4 枪（累计 1600px）都不许被掐断。
        val scan = newScan()
        var at = 1_000L
        val keys = (1..12).map { "n$it" }.toSet()
        var ys = keys.associateWith { 100.0 + 90.0 * it.removePrefix("n").toInt() }

        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(keys, at, ys))
        repeat(4) { i ->
            scan.onScrollDispatched(at)
            at += settle
            ys = ys.mapValues { (_, y) -> y - 400.0 } // 整体上移 400px（行还是那些行 ⇒ 只有位移能证明动了）
            assertEquals(
                "只上移了约 ${(i + 2) * 400}px（上限 ${ServerListScan.MAX_TO_TOP_PX.toInt()}px）⇒ 继续回顶",
                ServerListScan.Step.ScrollTowardsTop,
                scan.onMiss(keys, at, ys),
            )
        }
    }

    @Test
    fun doesNotProposeAgainBeforeTheScreenSettles() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L)
        scan.onScrollDispatched(1_000L)

        // 稳定等待内：只说"等"，不动计数器、也不污染"上一屏"
        assertEquals(ServerListScan.Step.Wait, scan.onMiss(screen("a"), 1_000L + 100))
        assertEquals(1, scan.topScrolls)

        // 等到稳定之后，同一屏（真的没动）⇒ **第一次只再滑一次确认**（到边要连续两次，见 EDGE_PROBE_MAX）
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(screen("a"), 1_000L + settle))
    }

    @Test
    fun waitsWhileTheScrollOutcomeIsStillPending() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L)

        // 还没告诉它"滑出去了没" ⇒ 这一轮别重复提议
        assertEquals(ServerListScan.Step.Wait, scan.onMiss(screen("a"), 1_000L + settle))
        assertEquals(1, scan.topScrolls)
    }

    @Test
    fun aRejectedScrollIsRolledBackCompletely() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L)
        scan.onScrollRejected()

        // 计数、阶段、上一屏都回到提议之前 ⇒ 下一轮提议的是同一件事
        assertEquals(0, scan.topScrolls)
        assertEquals(ServerListScan.Phase.CURRENT, scan.phase)

        val again = scan.onMiss(screen("a"), 2_000L)
        assertEquals(ServerListScan.Step.ScrollTowardsTop, again)
        assertEquals(1, scan.topScrolls)
    }

    @Test
    fun resetStartsFromTheCurrentScreenAgain() {
        val scan = newScan()
        scan.onMiss(screen("a"), 1_000L)
        scan.onScrollDispatched(1_000L)
        scan.onMiss(screen("b"), 1_000L + settle)

        scan.reset()

        assertEquals(0, scan.scrolls)
        assertEquals(ServerListScan.Phase.CURRENT, scan.phase)
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(screen("b"), 5_000L))
    }

    // ------------------------------------------- 滑动位移（"有没有跳过整屏"的证据）

    @Test
    fun displacementIsMeasuredFromTheSharedRows() {
        // 两屏共同出现的行 = 标尺：它的 y 之差就是这次滑动移动了多少
        val previous = mapOf("n1" to 300.0, "n2" to 400.0, "n3" to 500.0)
        val current = mapOf("n1" to -100.0, "n2" to 0.0, "n4" to 900.0)

        val moved = requireNotNull(ServerListScan.displacementOf(previous, current))

        assertEquals(2, moved.sharedRows)
        assertEquals(400.0, moved.deltaY, 1e-9)
    }

    @Test
    fun noSharedRowsIsSuspicious() {
        // 一条共同行都没有 ⇒ 可能整屏跳过（中间那屏从没读过），也可能读数花 —— 都得当"可疑"看
        assertNull(ServerListScan.displacementOf(mapOf("n1" to 1.0), mapOf("n9" to 2.0)))
    }

    @Test
    fun oneBadlyReadRowDoesNotSpoilTheMeasurement() {
        // 取**中位数**：单行读歪（这里 -1000 那条）不该带偏"这次滑了多少"
        val previous = mapOf("n1" to 300.0, "n2" to 400.0, "n3" to 500.0)
        val current = mapOf("n1" to -100.0, "n2" to 0.0, "n3" to 1500.0)

        val moved = requireNotNull(ServerListScan.displacementOf(previous, current))

        assertEquals(400.0, moved.deltaY, 1e-9)
    }

    @Test
    fun pinTheCaliber() {
        // 2026-10-01 用户拍板：滚动上限由 10 改为 **5 屏**（与 NameLocator.MAX_SCROLLS 同一口径）
        assertEquals(5, ServerListScan.MAX_SCROLLS)
        // 2026-10-01：回顶改成"**看累计上移**"（一次拖动可能不足一屏 ⇒ 只数次数会半路放弃），
        // 次数上限抬到 8 只当安全阀；"往下找"那本账仍是 5 屏、且回顶不占它。
        assertEquals(8, ServerListScan.MAX_TO_TOP_SCROLLS)
        assertEquals(3_600.0, ServerListScan.MAX_TO_TOP_PX, 1e-9)
        assertEquals(32.0, ServerListScan.ROW_SHIFT_PX, 1e-9)
        assertEquals(2, ServerListScan.EDGE_PROBE_MAX)
        assertEquals(0.35, ServerListScan.TO_TOP_SAME_SCREEN_OVERLAP, 1e-9)
        assertEquals(800L, ServerListScan.SETTLE_MS)
        assertEquals(16.0, ServerListScan.ROW_STABLE_PX, 1e-9)
        assertEquals(0.6, ServerListScan.SAME_SCREEN_OVERLAP, 1e-9)
        assertFalse(ServerListScan.Step.GiveUp("x").reason.isBlank())
    }

    @Test
    fun aMissingScrollOutcomeIsTreatedAsRejectedAfterAWhile() {
        // 2026-10-01 真机事故：第 9 步第二次滑动**被门禁拒了**，而调用方那条分支漏了回告（只回告给了
        // 服务器扫描器）⇒ 好友扫描器永远停在"等回告" ⇒ **再也不提议任何滑动**
        // —— 用户看到的是"这次根本就没滑屏"（OCR 每 1.2 秒读一次，却一次都不滑）。
        // ⇒ 现在兜底：等够 OUTCOME_PENDING_TIMEOUT_MS 就当作被拒、原样撤回、下一轮重新提议。
        val scan = newScan()
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(rows(100), 1_000L))
        assertTrue("提议之后就在等回告", scan.waitingForOutcome)

        // 还没到超时 ⇒ 继续等（不重复提议、不动计数）
        assertEquals(ServerListScan.Step.Wait, scan.onMiss(rows(100), 1_000L + 500))
        assertEquals(1, scan.topScrolls)

        // 超过超时 ⇒ 当作被拒：撤回这一步 + **重新提议同一件事**（卡死自愈）
        val again = scan.onMiss(rows(100), 1_000L + ServerListScan.OUTCOME_PENDING_TIMEOUT_MS + settle)
        assertEquals(ServerListScan.Step.ScrollTowardsTop, again)
        assertEquals("撤回后计数不虚增（提议 1 次 → 撤回 → 再提议 1 次）", 1, scan.topScrolls)
    }

    @Test
    fun theTopIsRecognisedEvenWhenOcrNoiseLeavesOnlyFortyPercentOverlap() {
        // 2026-10-01 真机（用户报"好友列表滑到顶为什么重复滑了几次才停下"）：列表**早就在顶部**，
        // 连续 4 次读到的内容一模一样，可行键被 OCR 抖动拉低（`B东百万`/`-东百万`/`p东百万`、噪声行 `出8400`）
        // ⇒ 重合度只算出 50% / 44% / 36% ⇒ 拿 60% 去判就一路白滑到 3 下上限。
        // 而**真换了屏**的那几次是 27% / 16% / 25% ⇒ 回顶这条线取 0.35 卡在中间。
        val scan = newScan()
        val atTop = (1..10).map { "n$it" }.toSet()
        val noisySame = (1..4).map { "n$it" }.toSet() + (11..16).map { "x$it" } // 4/10 = 40% 重合
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(atTop, 1_000L))
        scan.onScrollDispatched(1_000L)
        // 连续两次 40% 重合 ⇒ 判「已经在顶部」（一次不够：可能只是手势被吞，2026-10-01 真机事故）
        assertEquals(
            ServerListScan.Step.ScrollDown,
            finishTopConfirmation(scan, noisySame, 1_000L + settle),
        )

        // 对照组：真换了屏（重合 0%）⇒ 继续回顶（这条线不能松到把"动了"当成"没动"）
        val moved = newScan()
        moved.onMiss(atTop, 1_000L)
        moved.onScrollDispatched(1_000L)
        assertEquals(
            ServerListScan.Step.ScrollTowardsTop,
            moved.onMiss((20..29).map { "y$it" }.toSet(), 1_000L + settle),
        )
    }

    @Test
    fun aSwipeThatMovesLessThanOneScreenStillCountsAsMoved() {
        // 2026-10-01 真机（用户："**每次滑动的距离其实不够一屏，导致过早结束了，还是没有滑到顶**"）：
        // 一次拖动只走半屏 ⇒ 两屏共同的行很多（重合度高）⇒ 只看重合度就判成"没动 ⇒ 已经在顶部"✗，
        // 于是**根本没到顶就转头往下走**。现在：共同行的**位置**动了（位移 ≥ ROW_SHIFT_PX）就一律算"动了"。
        val scan = newScan()
        val before = (1..12).map { "n$it" }.toSet()
        val ysBefore = before.associateWith { 100.0 + 90.0 * it.removePrefix("n").toInt() }
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(before, 1_000L, ysBefore))
        scan.onScrollDispatched(1_000L)

        // 拖动后：共同行还是那 12 行（重合 12/16 = 75%，很高），但每一行都往上走了 45px（**不足一屏**）
        val after = before + (13..16).map { "x$it" }
        val ysAfter = ysBefore.mapValues { (_, y) -> y - 45.0 } +
            (13..16).associate { "x$it" to (100.0 + 90.0 * it) }
        assertEquals(
            "共同行位移 45px ⇒ 必须算「动了」⇒ 继续回顶",
            ServerListScan.Step.ScrollTowardsTop,
            scan.onMiss(after, 1_000L + settle, ysAfter),
        )
        assertTrue(
            "位移测出来动过 ⇒ 说明里不该出现「没动」：${scan.lastDecisionNote}",
            scan.lastDecisionNote.contains("继续向上滚"),
        )

        // 对照组：**位置一动不动**（位移 0）⇒ 才算"没动"⇒ 走"两次确认"通道（不直接判到顶）
        val still = newScan()
        still.onMiss(before, 1_000L, ysBefore)
        still.onScrollDispatched(1_000L)
        assertEquals(ServerListScan.Step.ScrollTowardsTop, still.onMiss(before, 1_000L + settle, ysBefore))
        assertTrue(
            "真的没动才说「没动」：${still.lastDecisionNote}",
            still.lastDecisionNote.contains("没动"),
        )
    }

    @Test
    fun aRejectedSwipeNeverCountsAsTheListBeingAtTheEdge() {
        // 2026-10-01 真机事故（用户："**实际上根本没有滑到顶，就开始往底部移动了**"）的另一半成因：
        // 那一枪**被门禁拒**（压根没下发）时，下一轮读到的还是同一屏 ⇒ 旧逻辑把它当成"画面没动 ⇒ 到顶"✗。
        // ⇒ "没动"只有在"**真的滑过**"之后才有意义（[ServerListScan] 的 lastProposalDispatched）。
        val scan = newScan()
        scan.onMiss(rows(100), 1_000L)
        scan.onScrollRejected() // 被拒：原样撤回

        // 这一屏与上一屏相同，但**我们根本没滑过** ⇒ 只能重新提议"回顶"，绝不能判"已经在顶部"
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(rows(100), 2_000L))
        assertEquals(ServerListScan.Phase.TO_TOP, scan.phase)

        // 再被拒一次也一样（连续被拒不等于"到顶"）
        scan.onScrollRejected()
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(rows(100), 3_000L))
        assertEquals(ServerListScan.Phase.TO_TOP, scan.phase)
    }

    @Test
    fun aTinyRowKeySampleIsNeverJudgedAsTheSameScreen() {
        // 2026-10-01 真机事故：一屏读到 11 行、行键却只有 3 个（好友列表第一版拿"备注名"当键，
        // 而很多好友没设备注）⇒ "重合 **2/3 行（66%）**"这种 2 行样本就触发了"同一屏"判据
        // ⇒ 把"换了一屏"误判成"**已到底**"、直接中止说"找不到"。
        // ⇒ 现在两边都要至少 MIN_KEYS_FOR_SAME_SCREEN 个键，才允许判"同一屏"。
        val scan = newScan()
        scan.onMiss(setOf("a", "b", "c"), 1_000L)
        scan.onScrollDispatched(1_000L)
        assertEquals(
            "3 个键（达到阈值）+ 完全一致 ⇒ 同一屏 ⇒ 第一次只再滑一次确认",
            ServerListScan.Step.ScrollTowardsTop,
            scan.onMiss(setOf("a", "b", "c"), 1_000L + settle),
        )
        scan.onScrollDispatched(1_000L + settle)
        assertEquals(
            "再滑一次仍完全一致 ⇒ 才判已经在顶部",
            ServerListScan.Step.ScrollDown,
            scan.onMiss(setOf("a", "b", "c"), 1_000L + 2 * settle),
        )

        val small = newScan()
        small.onMiss(setOf("a", "b"), 1_000L)
        small.onScrollDispatched(1_000L)
        assertEquals(
            "只有 2 个键 ⇒ 样本太小，不许据此判『同一屏』（当作画面动过，继续回顶）",
            ServerListScan.Step.ScrollTowardsTop,
            small.onMiss(setOf("a", "b"), 1_000L + settle),
        )
    }

    @Test
    fun learnedTopIsForgottenWhenTheLoggedInAccountChanges() {
        // 用户 2026-10-01 口径：**到顶记忆绑定当前登录服务器**（不同小号的好友列表可能不同）。
        val scan = newScan()
        scan.noteScope("白色死神")

        // 学到"顶部那一屏"（第一屏没中 → 回顶 → 画面不动 ⇒ 到顶 + 学会）
        scan.onMiss(rows(100), 1_000L)
        scan.onScrollDispatched(1_000L)
        finishTopConfirmation(scan, rows(100), 1_000L + settle) // 到顶（连续两次滑不动）
        scan.reset() // 一轮跑号结束

        // 同一账号再跑：这一屏与学到的顶部屏一致 ⇒ 直接往下（省掉那一下空滑）
        assertEquals(ServerListScan.Step.ScrollDown, scan.onMiss(rows(100), 2_000L))
        assertTrue(scan.atTopConfirmed)

        // **换账号** ⇒ 学到的作废 ⇒ 又要老实回顶（不能拿甲的指纹跳过乙的回顶，否则会漏掉上面的条目）
        scan.noteScope("阿娜雅")
        scan.reset() // 新一轮
        assertEquals(
            "换了账号 ⇒ 必须重新回顶",
            ServerListScan.Step.ScrollTowardsTop,
            scan.onMiss(rows(100), 3_000L),
        )
        assertFalse(scan.atTopConfirmed)

        // **账号未知**（「只拜访」时是用户自己登的号）⇒ 同样不采信
        val unknown = newScan()
        unknown.noteScope(null)
        assertEquals(
            "不知道是哪个号 ⇒ 老实回顶（宁可多滑一下）",
            ServerListScan.Step.ScrollTowardsTop,
            unknown.onMiss(rows(100), 1_000L),
        )
        assertFalse(unknown.atTopConfirmed)
    }

    @Test
    fun aFreshLoginEntryCountsAsTheTopWithoutTheRedundantScroll() {
        // 用户 2026-10-01 观察："**每次重新登录服务器时，好友列表都会回到顶部**"（游戏自己的行为）⇒
        // 换号流程进来这一屏**就是顶部屏** ⇒ 不必再空滑那一下（省一次拖动 + 稳定等待 + 整屏读数 ≈1.5~2s，
        // 而且刚被 noteScope 清掉的记忆也来不及重建）。「只拜访」不算：列表停在哪儿由用户决定。
        val fresh = newScan()
        fresh.noteEntryAtTop(true)

        assertEquals(
            "刚登录进来 ⇒ 第一屏直接往下找（不回顶）",
            ServerListScan.Step.ScrollDown,
            fresh.onMiss(rows(100), 1_000L),
        )
        assertTrue("并且这个第一屏就被当成顶部（后面读到的命中可以采信）", fresh.atTopConfirmed)

        val visitOnly = newScan()
        visitOnly.noteEntryAtTop(false)
        assertEquals(
            "只拜访 ⇒ 老实回顶",
            ServerListScan.Step.ScrollTowardsTop,
            visitOnly.onMiss(rows(100), 1_000L),
        )
        assertFalse(visitOnly.atTopConfirmed)
    }

    @Test
    fun atTopIsOnlyConfirmedAfterTheTopHasBeenSettled() {
        // 扫描器自己的状态：**"顶部这件事交代过了没有"** = 已经进入"往下找"阶段。
        // ⚠ 好友那一步（第 9 步）从 2026-10-01 起**不再拿它拦命中**（用户口径："在当前屏先找一次，
        // 找不到再滑到顶"⇒ 命中即点）—— 这个标志现在只描述状态，见 [ServerListScan.atTopConfirmed]。
        val scan = newScan()

        // ① 刚进列表（还没读过任何一屏）⇒ 不能采信
        assertFalse("首读之前不算已到顶", scan.atTopConfirmed)

        // ② 第一屏没中 ⇒ 提议"回顶"（此刻仍未确认）
        assertEquals(ServerListScan.Step.ScrollTowardsTop, scan.onMiss(rows(100), 1_000L))
        assertFalse("正在回顶，还没确认", scan.atTopConfirmed)

        // ③ 回顶之后画面不再动（与上一屏重合）⇒ **连续两次才确认在顶部** + 转"往下找"
        scan.onScrollDispatched(1_000L)
        assertEquals(ServerListScan.Step.ScrollDown, finishTopConfirmation(scan, rows(100), 1_000L + settle))
        assertTrue("画面不动了 ⇒ 确认在顶部", scan.atTopConfirmed)

        // ④ 学到的"顶部那一屏"与当前屏一致 ⇒ 直接算已到顶（跳过那一下无谓的空滑，见 learnedTopKeys）
        val learned = newScan()
        learned.onMiss(rows(100), 1_000L) // 第一次：提议回顶
        learned.onScrollDispatched(1_000L)
        finishTopConfirmation(learned, rows(100), 1_000L + settle) // 到顶 + 顺便学会"顶部那一屏"
        learned.reset() // 换一步 / 重新跑一遍（learnedTopKeys 跨跑号保留）
        assertFalse("复位后从零开始", learned.atTopConfirmed)
        learned.onMiss(rows(100), 2_000L)
        assertTrue("这一屏与学到的顶部屏一致 ⇒ 直接算已到顶", learned.atTopConfirmed)
    }

    // ------------------------------------------- 到顶判据（2026-09-29：滑到顶还狂滑的根因）

    @Test
    fun ocrJitterOnTheSameScreenStillCountsAsTheSameScreen() {
        // 真机实录（02:17）：列表本来就在顶部，同一屏连读三次行名一直抖（Lv.9T/Ly.9T、Lv.6T/Lv.6日）
        // ⇒ 原来"集合完全相等"的判据永远说"还在动"，于是一路滑到上限（用户："滑到顶后还滑了好多下"）。
        // 现在按**重合度**判：抖掉 4/12 行仍认作同一屏 ⇒ 到顶 ⇒ 转"往下找"。
        val scan = newScan()
        scan.onMiss(rows(100), 1_000L)
        scan.onScrollDispatched(1_000L)

        val jittered = rows(104, 8) + (108..111).map { "x$it" }.toSet() // 8 行照旧 + 4 行读花了
        val step = scan.onMiss(jittered, 1_000L + settle)

        assertEquals("抖动不该被当成'画面还在动'（第一次只再滑一次确认）", ServerListScan.Step.ScrollTowardsTop, step)
        scan.onScrollDispatched(1_000L + settle)
        assertEquals(
            "再滑一次仍认作同一屏 ⇒ 才判到顶、转往下找",
            ServerListScan.Step.ScrollDown,
            scan.onMiss(jittered, 1_000L + 2 * settle),
        )
        assertEquals(ServerListScan.Phase.DOWN, scan.phase)
    }

    @Test
    fun theTopPhaseIsCappedSoTheBudgetIsNotBurnedGoingUp() {
        // 结构护栏：即便"到顶了没有"还判错，回顶也不会把 10 次预算全烧掉（用户 14 个区服 / 一屏 12 个 ⇒
        // 最多离顶一屏，2~3 下足够）
        val scan = newScan(maxTopScrolls = 2)
        scan.onMiss(rows(100), 1_000L)
        scan.onScrollDispatched(1_000L)
        scan.onMiss(rows(50), 1_000L + settle)
        scan.onScrollDispatched(1_000L + settle)

        // 第三次仍然"画面在动"，但回顶已达上限 ⇒ 当作已到顶，转为往下找
        val capped = scan.onMiss(rows(10), 1_000L + 2 * settle)

        assertEquals(ServerListScan.Step.ScrollDown, capped)
        assertEquals(ServerListScan.Phase.DOWN, scan.phase)
        assertTrue("要说清为什么转往下：${scan.lastDecisionNote}", scan.lastDecisionNote.contains("当作已到顶"))
    }

    @Test
    fun aLearnedTopScreenSkipsThePointlessSwipeUp() {
        // 用户建议（2026-09-29）："区服名大多都在第一屏……可以先直接在当前屏查找一轮，如果找不到再滑"。
        // 当前屏先查**本来就有**（Phase.CURRENT）；这里再省掉那一下**空滑**：
        // 上一次跑号已经知道"顶部那一屏长什么样"，这次当前屏就是它 ⇒ 直接往下找，不必先滑一下去验证。
        // ⚠ 复用**同一个**扫描器（生产里 `CaptureService` 全程一个实例，跑号之间只 `reset()`）——
        // 学到的"顶部那一屏"是**跨跑号**的，`reset()` 不清它。
        val scan = newScan()
        scan.onMiss(rows(100), 1_000L) // 当前屏没中 ⇒ 先回顶（此时还不知道顶部那一屏长什么样）
        scan.onScrollDispatched(1_000L)
        // 连续两次滑不动 ⇒ 到顶 ⇒ 学到"顶部这一屏" + 转往下（见 finishTopConfirmation）
        assertEquals(ServerListScan.Step.ScrollDown, finishTopConfirmation(scan, rows(100), 1_000L + settle))

        scan.reset() // 下一次跑号

        val step = scan.onMiss(rows(100), 2_000L) // 与学到的顶部那一屏一致 ⇒ 直接往下找

        assertEquals(ServerListScan.Step.ScrollDown, step)
        assertEquals(ServerListScan.Phase.DOWN, scan.phase)
        assertEquals("只花了往下那一下", 1, scan.scrolls)
        assertTrue("要说清跳过了回顶：${scan.lastDecisionNote}", scan.lastDecisionNote.contains("跳过回顶"))
    }

    @Test
    fun aLearnedTopScreenDoesNotMatchAnotherScreen() {
        // 安全侧：当前屏不是"顶部那一屏" ⇒ 老老实实先回顶（否则会漏掉它上面的条目）
        val scan = newScan()
        scan.onMiss(rows(100), 1_000L)
        scan.onScrollDispatched(1_000L)
        // 学到顶部那一屏（连续两次滑不动 ⇒ 到顶 ⇒ 转往下，见 finishTopConfirmation）
        finishTopConfirmation(scan, rows(100), 1_000L + settle)
        scan.reset()

        val step = scan.onMiss(rows(500), 2_000L) // 另一屏

        assertEquals(ServerListScan.Step.ScrollTowardsTop, step)
        assertEquals(ServerListScan.Phase.TO_TOP, scan.phase)
    }

    @Test
    fun everyDecisionCarriesAReadableReason() {
        // 用户追问"为什么滑到顶后还滑了好多下" ⇒ 每一拍都要能读懂依据
        val scan = newScan()
        scan.onMiss(rows(100), 1_000L)
        assertTrue("第一次没中要有依据：${scan.lastDecisionNote}", scan.lastDecisionNote.contains("还没到顶"))

        scan.onScrollDispatched(1_000L)
        scan.onMiss(rows(50), 1_000L + settle)
        assertTrue(
            "回顶路上要说清还剩几下：${scan.lastDecisionNote}",
            scan.lastDecisionNote.contains("2/${ServerListScan.MAX_TO_TOP_SCROLLS}"),
        )
    }

    // ------------------------------------------- 命中之后"能不能点"（2026-09-29 真机事故）

    @Test
    fun aSingleHitIsNotEnoughToClick() {
        // 实录：滑动后 815ms 读到「黑砂流瀑」在 (760,1258) 就下手了，而列表还有惯性/回弹 ⇒
        // 那一行又移开一行，点中了相邻的「机仆阵列」⇒ 必须先确认"这一行还在原处"
        val scan = newScan()

        assertFalse("第一次读到不算确认", scan.onHit("410", 1258.0, 10_000L))
    }

    @Test
    fun theSameRowInTheSamePlaceOnANewerFrameAllowsTheClick() {
        val scan = newScan()
        scan.onHit("410", 1258.0, 10_000L)

        assertTrue("同一行、原地、更新的画面 ⇒ 可以点", scan.onHit("410", 1262.0, 10_400L))
    }

    @Test
    fun aRowThatMovedIsNotTheSameRowAnyMore() {
        val scan = newScan()
        scan.onHit("410", 1258.0, 10_000L)

        // 移开约一行（行高 ≈90px）⇒ 这不是"还在原处"，再等
        assertFalse(scan.onHit("410", 1168.0, 10_400L))
    }

    @Test
    fun theSameFrameIsNotNewEvidence() {
        val scan = newScan()
        scan.onHit("410", 1258.0, 10_000L)

        // 同一张画面上重读一次不是"两次读数"（帧时刻没变）——否则等于自己给自己背书
        assertFalse(scan.onHit("410", 1258.0, 10_000L))
    }

    @Test
    fun anotherRowIsNotTheSameRow() {
        val scan = newScan()
        scan.onHit("410", 1258.0, 10_000L)

        assertFalse("换了区号就不是同一行", scan.onHit("411", 1258.0, 10_400L))
    }

    @Test
    fun aMissClearsThePendingHit() {
        val scan = newScan()
        scan.onHit("410", 1258.0, 10_000L)

        // 这一屏又读不到了（列表动了）⇒ 之前那次命中作废，重新确认
        scan.onMiss(screen("a"), 11_000L)

        assertFalse(scan.onHit("410", 1258.0, 12_000L))
    }

    // --------------------------- 开火前复眼（2026-09-30：动手之前再确认"那一行还在原地"）

    @Test
    fun theFireRecheckPassesWhileTheSameRowHoldsItsLine() {
        // 现取的最新帧上仍读到同一行、纵坐标没动（容差内）⇒ 放行（这一枪可以发）
        assertTrue(ServerListScan.stillOnSameRow("n426", 891.0, mapOf("n426" to 891.0)))
        assertTrue("几像素的读数抖动不算移动", ServerListScan.stillOnSameRow("n426", 891.0, mapOf("n426" to 899.0)))
        assertTrue(
            "正好落在容差上也算同一行",
            ServerListScan.stillOnSameRow("n426", 891.0, mapOf("n426" to 891.0 + ServerListScan.ROW_STABLE_PX)),
        )
    }

    @Test
    fun theFireRecheckBlocksWhenTheRowMovedAFullRow() {
        // 真机实录（02:12）：读到那一行之后 **815ms** 手指才落下，而列表在这段时间里回弹了**一行**
        //（行距 ≈90px）⇒ 坐标落到**相邻的另一个区服**上。"读到" ≠ "现在还在那儿" —— 这条就是拦它的。
        assertFalse(ServerListScan.stillOnSameRow("n426", 891.0, mapOf("n426" to 981.0)))
        assertFalse("反过来（列表往上弹）同样要拦", ServerListScan.stillOnSameRow("n426", 981.0, mapOf("n426" to 891.0)))
    }

    @Test
    fun theFireRecheckBlocksWhenTheRowIsNotInTheFreshFrame() {
        // 读不到那一行（列表换了一屏 / 这一带读数失败）⇒ 一律不放行：
        // 宁可多等一轮重读，也不点到别的行上（红线 3 的取向：不许猜）。
        assertFalse("那一行不在了", ServerListScan.stillOnSameRow("n426", 891.0, mapOf("n425" to 891.0)))
        assertFalse("一行都没读到", ServerListScan.stillOnSameRow("n426", 891.0, emptyMap()))
    }

    @Test
    fun theRowKeyPrefersDigitsSoBothJudgementsAgreeOnIdentity() {
        // 行键**只写一处**（`rowKeyOf`）：数字优先（真机数字栏 12/12 全对，汉字会丢字），
        // 没有数字才退回名字段。扫描（判"这一屏动没动"）与开火前复眼（判"还是同一行吗"）
        // 共用这一把键 —— 两处各写一遍就会各自漂移（判据只写一处的老教训）。
        assertEquals("n426", ServerListScan.rowKeyOf("微信426区 战利品"))
        assertEquals("n56", ServerListScan.rowKeyOf("微信56区白色死神"))
        assertEquals("末尾空白不影响", ServerListScan.rowKeyOf("微信426区 战利品"), ServerListScan.rowKeyOf("微信426区 战利品 "))
        assertEquals("没有数字就退回名字段（取「区」之后）", "t战利品", ServerListScan.rowKeyOf("战利品"))
    }
}
