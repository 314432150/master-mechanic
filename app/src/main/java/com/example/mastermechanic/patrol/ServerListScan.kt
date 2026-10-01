package com.example.mastermechanic.patrol

/**
 * **第 5 步「服务器列表逐屏滚动查找」的状态机**（纯逻辑，2026-09-29）。
 *
 * ## 需求依据（这条要求一直在，只是从没实现）
 *
 * `docs/requirements.md` **硬性要求 #4**（§7 待定事项 #3 同一句）：
 * > 服务器列表需要滚动时，逐屏滚动查找；滚动次数设上限，超出即中止并提示。
 *
 * 在此之前：第 5 步**只读当前可见的那一屏**（`CaptureService.serverPlanOf` → `RealmPickDecision.decide`，
 * 判据签名就是单屏），而动作层**只有点击**（`GestureInjector`）⇒ 目标落在第二屏时必然 `Miss`
 * ⇒ `NamePlan.Failed` ⇒ 3 次重试 / 15s 后中止。用户 2026-09-29 报障：*"如果选择的区服名在第二屏，
 * 程序会报找不到而中止。"*
 *
 * ## 顺序（用户 2026-09-29 拍板）：**先回到列表顶部，再自上而下逐屏找**
 *
 * 为什么先回顶：列表可能被上一次操作停在半路，只往下找会**漏掉它上面的条目**。
 * 又因为用户当前只有 14 个区服、一屏能放 12 个 ⇒ **常见情况第一屏就命中，一次也不滑**
 * （所以本类第一个阶段是 [Phase.CURRENT]：先认账当前这一屏）。
 *
 * ## 怎么知道"到顶了 / 到底了"—— ⚠ 判据是**行键重合度**，不是"集合完全相等"
 *
 * 真机实录（02:17，用户报："**滑到顶后还滑了好多下**"）：列表**本来就在顶部**，但同一个画面连读三次，
 * OCR 每次都会抖一两个字（`Lv.9T` / `Ly.9T`、`Lv.25v` / `Lv.25y`、`Lv.6T` / `Lv.6日`）
 * ⇒ 严格相等永远判"画面还在动" ⇒ 回顶阶段一路滑到上限（10 屏）⇒ 白白中止。
 * ⇒ 现在用 **重合度**（[SAME_SCREEN_OVERLAP] = 60%）判"还是同一屏"，抖动几个字照样认得出。
 *
 * 行键由调用方生成（**优先取数字**：真机实测数字那一栏 12/12 行全对，汉字会丢字 / 形近错）。
 * 不用整帧像素差：列表上的选中高亮、滚动条、头像动效都会让像素乱动。
 *
 * ## 再加一道结构性护栏：回顶有自己的额度（[MAX_TO_TOP_SCROLLS] + [MAX_TO_TOP_PX]）
 *
 * 即便"到顶了没有"这件事还判错，也不能让它把预算烧光；到点就**当作已到顶**转为"往下找"（往下找会把
 * 剩下的全扫一遍）。⚠ 2026-10-01 起：① 这本额度**独立于**"往下找"的 [MAX_SCROLLS]（两本账）；
 * ② 主要看**累计上移的像素**而不是"滑了几下" —— 真机一次拖动只走 1/3~1/2 屏（实录 79~539px，
 * 一屏 ≈1119px），只数次数会**半路放弃**（用户报"没有滑到顶就开始往底部滑了"）。
 *
 * ## ⚠ 调用约定（不遵守就会卡在 [Step.Wait]）
 *
 * - 只在**新读**（不是复用上一轮结论的那一次）上调用 [onMiss]；
 * - 滑动**真的下发**之后调 [onScrollDispatched]（`CaptureService` 在门禁返回后调）；
 *   被门禁**拒绝**则调 [onScrollRejected] 把这步撤回去（下次重新提议）；
 * - 点中目标、离开这一步、或重新跑一遍，都要 [reset]。
 */
class ServerListScan(
    /** 累计滑动次数上限（需求："滚动次数设上限"）。 */
    private val maxScrolls: Int = MAX_SCROLLS,
    /** "回顶"阶段最多滑几下（结构护栏，见类注释）。 */
    private val maxTopScrolls: Int = MAX_TO_TOP_SCROLLS,
    /** 一次拖动之后等画面稳定的时长（ms）：滑完立刻读会读到动画中间那一帧。 */
    private val settleMs: Long = SETTLE_MS,
    /**
     * 目标是什么（只用于"找不到"的提示；默认"区服"）。第 9 步传"好友"（2026-10-01）：
     * 真机日志里那句"仍未找到这个**区服**"出现在好友列表里 —— 文案串了，用户一眼就知道不对。
     */
    private val targetNoun: String = "区服",
    /** "找不到"时给用户的排查建议（默认按区服写；第 9 步按好友写）。 */
    private val targetHint: String = "请确认平台 / 区服名是否与游戏里一致",
) {

    init {
        require(maxScrolls >= 1) { "滚动上限至少为 1：$maxScrolls" }
        require(maxTopScrolls >= 1) { "回顶上限至少为 1：$maxTopScrolls" }
        require(settleMs >= 0) { "稳定等待不能为负：$settleMs" }
    }

    /** 扫描阶段。 */
    enum class Phase {
        /** 先看**当前这一屏**（区服常在第一屏 ⇒ 命中就一次也不滑）。 */
        CURRENT,

        /** 当前屏没中 ⇒ 先**回到顶部**（手指向下划）。 */
        TO_TOP,

        /** 已在顶部（或回顶次数到点）⇒ **自上而下逐屏找**（手指向上划）。 */
        DOWN,
    }

    /** 这一轮该做什么。 */
    sealed interface Step {

        /** 别动：滑动刚下发、这一屏还没稳定（等下一轮）。 */
        data object Wait : Step

        /** **回到顶部**：手指**向下**划（内容下移 ⇒ 看到更靠前的条目）。 */
        data object ScrollTowardsTop : Step

        /** **继续往下找**：手指**向上**划（内容上移 ⇒ 看到更靠后的条目）。 */
        data object ScrollDown : Step

        /** 停下并说清为什么（调用方转成"如实中止并提示"）。 */
        data class GiveUp(val reason: String) : Step
    }

    var phase: Phase = Phase.CURRENT
        private set

    /**
     * **是否已经确认"列表在顶部"**（判据 = **已经进入 [Phase.DOWN]**）。
     *
     * 到达这一阶段的三条路都意味着"顶部这件事已经交代过了"：
     * ① 回顶时画面不再动（真在顶部）；② 学到的"顶部那一屏"与当前屏一致（跳过无谓空滑，见 [learnedTopKeys]）；
     * ③ 回顶已滑到 `maxTopScrolls` 上限（护栏：当作已到顶）。
     *
     * ⚠ **第 9 步（好友）从 2026-10-01 起不再拿它拦命中**（用户二次拍板："每次都滑到顶再找有点费事，
     * 改为**在当前屏先找一次，找不到再滑到顶**慢慢找"）⇒ 好友那一步**命中即点**，不看这个标志
     * （见 `CaptureService.friendPlanOf` ④）。它现在只**描述扫描器自己的状态**（给日志与单测看）。
     * 第 5 步（区服）也不用它：区服那条是**跨屏合并判唯一**（看到第一个就点是 FR-04 要防的），
     * 口径与好友相反，见 `NameLocating.locateFriend` 的说明。
     */
    val atTopConfirmed: Boolean get() = phase == Phase.DOWN

    /**
     * **是否正卡在"等上一次滑动的回告"**（只读，给调用方写日志用）。
     *
     * 2026-10-01 加它的理由：那种"回告没来"的卡死**在日志里完全看不见**（[Step.Wait] 本来就不打日志）
     * ⇒ 用户报"根本没滑屏"时我们只能靠翻代码找。现在调用方据它把这句"等回告"也打进日志。
     */
    val waitingForOutcome: Boolean get() = awaitingOutcome

    /**
     * **上一屏的"行键 → 纵坐标"**（[onMiss] 的第三个入参，可空）。
     *
     * 用途：判"这次滑动到底动没动"。只看**行键重合度**在"一次拖动不足一屏"时会骗人（共同行很多 ⇒ 像"没动"
     * ⇒ 误判到顶，2026-10-01 用户报："每次滑动的距离其实不够一屏，导致过早结束了，还是没有滑到顶"）；
     * 而共同行的**位移**是直接证据（见 [ROW_SHIFT_PX]）。
     */
    private var previousYs: Map<String, Double> = emptyMap()

    /** [onMiss] 里的"提议前现场"之一（被拒时连同 [previousYs] 一起撤回）。 */
    private var rollbackYs: Map<String, Double> = emptyMap()

    /**
     * **回顶阶段累计上移了多少像素**（2026-10-01 加）—— 判"回顶额度用完没有"用它，而不是只看"滑了几下"：
     * 真机一次拖动可能只走 1/3 屏（实录 79~539px / 一屏 ≈1119px），只数次数会半路放弃（见 [MAX_TO_TOP_PX]）。
     */
    private var topMovedPx = 0.0

    /** 已下发的滑动次数（含"回顶"那几下）。 */
    var scrolls: Int = 0
        private set

    /**
     * 这一轮判断的**一句话依据**（写进日志用，2026-09-29 加）。
     *
     * 为什么单独立一个可读字段：用户问"为什么滑到顶后还滑了好多下"，而日志里原来只有
     * "目标不在这一屏，向上滚回顶部"这一句 —— 看不出它**为什么认为还没到顶**。
     */
    var lastDecisionNote: String = ""
        private set

    /** 上一次**读到的一屏**的行键（判"画面动没动"）；null = 还没读过。 */
    private var previousKeys: Set<String>? = null

    /**
     * **学到的"列表顶部那一屏"长什么样**（2026-09-29 用户建议后加）。
     *
     * ## 为什么值得记
     *
     * 现在的顺序是"当前屏 → **回顶** → 往下找"：目标不在当前屏时，若列表**本来就在顶部**，
     * 那一下"回顶"就是**空滑**（滑不动），却要花掉一次拖动 + 等待 + 一次整屏读数 ≈ **1.5~2s**。
     * 而它只为了回答一个问题："我现在在不在顶部"。
     * 这个问题**上一次跑号已经回答过了**（当时那一屏就是顶部屏）⇒ 记下来，下次当前屏与它一致就直接往下找。
     *
     * ## 为什么安全（会不会误判成"在顶部"而漏掉上面的条目）
     *
     * 只有**当前屏读到的行与"顶部那一屏"重合 ≥ [SAME_SCREEN_OVERLAP]** 才跳过回顶 —— 也就是说
     * 屏幕上摆的就是当时顶部那一屏的那些区服。列表被滑到别处还与之重合，得有两屏内容几乎一样，
     * 本游戏（区服名唯一）不会出现。
     *
     * 内容变了（用户加了区服 / 行读法变了）⇒ 匹配不上 ⇒ 老老实实回顶（并重新学习）⇒ **自愈**。
     * ⚠ [reset] **不清**它（跨跑号有效）；换账号 / 平台后内容不同也会自然失配。
     */
    private var learnedTopKeys: Set<String>? = null

    /** [learnedTopKeys] 属于哪一份列表（由 [noteScope] 告知）；见那里的说明。 */
    private var learnedScope: String? = null
    private var scopeKnown = false

    /**
     * **进来的这一屏就是列表顶部**（由 [noteEntryAtTop] 告知）。
     *
     * 用于"我们刚登录进这个号"的场合（用户 2026-10-01 观察：**每次重新登录服务器，好友列表都会回到顶部**
     * —— 那是游戏自己的行为）⇒ 第一屏直接**当作顶部屏**：不必再滑那一下空滑（一次拖动 + 稳定等待 +
     * 一次整屏读数 ≈ **1.5~2s**），也不必等"学到的顶部屏"（换号每次换一个号 ⇒ 记忆刚被 [noteScope] 清掉）。
     */
    private var entryAtTop = false

    /**
     * 告知"进来这一屏是不是列表顶部"（见 [entryAtTop]）。**每轮都由调用方如实给**（它知道这一轮是哪条流程）；
     * [reset] 会清掉它。
     */
    fun noteEntryAtTop(atTop: Boolean) {
        entryAtTop = atTop
    }

    /** 已提议一次滑动、还没等到它的下发结果（避免同一屏连着提两次）。 */
    private var awaitingOutcome = false

    /**
     * 上一次**提议**滑动的时刻（[awaitingOutcome] 为真时有效）。
     *
     * 用途只有一个：给"回告一直没来"兜底判超时（见 [OUTCOME_PENDING_TIMEOUT_MS] 与 [onMiss]）——
     * 2026-10-01 真机事故：调用方某个分支漏了 `onScrollRejected()`，扫描就永远停在"等回告"、
     * 再也不提议任何滑动（用户原话"这次根本就没滑屏"）。
     */
    private var awaitingSinceMs = 0L

    /**
     * **上一次提议的滑动到底有没有真下发**（[onScrollDispatched] 置真；[onScrollRejected] / 撤回 / 新提议置假）。
     *
     * 用途只有一个：**"画面没动"这句话只有在"我们真的滑过"之后才有意义**。
     * 被门禁拒、或手势被游戏吞掉的那一次次"没动"，说明不了任何事 —— 列表可能离边缘还远。
     */
    private var lastProposalDispatched = false

    /**
     * **连续几次"真的滑过、但画面没动"**（见 [EDGE_PROBE_MAX]）。
     *
     * 2026-10-01 真机事故：好友列表**没滑到顶**就被判成"已经在顶部"、转头往下找（用户报
     * "**实际上根本没有滑到顶，就开始往底部移动了**"）。日志原样：
     * ```
     * 06:55:40.487  首读 ⇒ 回顶（第 1/3 下）
     * 06:55:41.845  与上一屏重合 4/6 行（66%）⇒ 已经在顶部     ← 只滑了一下就判到顶 ✗
     * 06:56:17.382  与上一屏重合 4/6 行（66%）⇒ 已到底         ← 往下只一下就说到底 ✗
     * ```
     * 三种成因都指向"**别拿一次'没动'当结论**"：① 那一枪**被门禁拒**（没下发）；② 手势**被游戏吞**（下发
     * 了但列表没滚）；③ 行键样本小（真机只有 6 个键，一个键的差就是 17%）+ 阈值宽 ⇒ 轻微移动也像"没动"。
     */
    private var noMovementStreak = 0

    /** 上一轮"命中"那一行的身份与纵坐标（确认"行还在原处"，见 [onHit]）；null = 还没命中过。 */
    private var lastHitKey: String? = null
    private var lastHitY = 0.0
    private var lastHitFrameAtMs = 0L

    /** 提议之前的状态：滑动被拒时要**原样撤回**（否则"没滑成"会被当成"滑了、画面没动"）。 */
    private var rollbackPhase: Phase = Phase.CURRENT
    private var rollbackKeys: Set<String>? = null
    private var rollbackTopScrolls = 0
    private var lastProposalScrolled = false

    /**
     * **"回顶"阶段已滑的次数**（只读，给日志 / 单测看）。
     *
     * ⚠ 它**不属于** [scrolls] 那本"往下找"的账（2026-10-01 分开记账）：真机一次拖动可能只走 1/3 屏，
     * 回顶要滑六七下 —— 记进同一本账会把 5 屏额度烧光、整步卡死（见 [propose]）。
     */
    var topScrolls: Int = 0
        private set

    private var lastScrollAtMs = 0L

    /** 点中目标 / 离开这一步 / 重新跑一遍时复位（**不清 [learnedTopKeys]**：那是跨跑号学到的）。 */
    fun reset() {
        phase = Phase.CURRENT
        scrolls = 0
        topScrolls = 0
        lastScrollAtMs = 0L
        previousKeys = null
        previousYs = emptyMap()
        awaitingOutcome = false
        awaitingSinceMs = 0L
        lastProposalDispatched = false
        noMovementStreak = 0
        topMovedPx = 0.0
        lastDecisionNote = ""
        rollbackPhase = Phase.CURRENT
        rollbackKeys = null
        rollbackTopScrolls = 0
        lastProposalScrolled = false
        // "进来这一屏就是顶部"是**每轮由调用方给的指令**（见 [noteEntryAtTop]）：复位即清，等下一轮重新告知
        entryAtTop = false
        clearHit()
    }

    private fun clearHit() {
        lastHitKey = null
        lastHitY = 0.0
        lastHitFrameAtMs = 0L
    }

    /**
     * **这一屏没找到**（调用方刚用 [RealmPickDecision] 判过、且是**新读**）—— 问接下来干什么。
     *
     * @param rowKeys 这一屏读到的行键（调用方生成；**优先取数字**，见类注释）
     * @param nowMs 单调时钟（与 [onScrollDispatched] 同一来源）
     */
    fun onMiss(rowKeys: Set<String>, nowMs: Long, rowYs: Map<String, Double> = emptyMap()): Step {
        if (awaitingOutcome) {
            val pendingMs = nowMs - awaitingSinceMs
            if (pendingMs < OUTCOME_PENDING_TIMEOUT_MS) {
                lastDecisionNote = "等上一次滑动的结果（还没回告）"
                return Step.Wait
            }
            // **回告一直没来 ⇒ 当作被拒**（2026-10-01 真机事故的兜底）：
            // 被门禁拒的那一步，若调用方的某个分支写漏了、没调 [onScrollRejected]，这里就会永远停在
            // "等回告" ⇒ 扫描**再也不提议任何滑动**（真机表现：OCR 每 1.2 秒读一次，但一次都不滑，
            // 用户原话"这次根本就没滑屏"）。⇒ 等够 [OUTCOME_PENDING_TIMEOUT_MS] 就原样撤回、重新提议，
            // 宁可多发一次滑动，也不要卡死不动。
            lastDecisionNote = "上一次滑动的回告 ${pendingMs}ms 没来 ⇒ 当作被拒，撤回这一步重新提议"
            rollbackProposal()
        }
        if (lastScrollAtMs > 0L && nowMs - lastScrollAtMs < settleMs) {
            lastDecisionNote = "滑动刚下发 ${nowMs - lastScrollAtMs}ms（稳定等待 ${settleMs}ms 未到）"
            return Step.Wait
        }

        // 这一屏没有目标 ⇒ 之前那次"命中"的坐标不再有意义（列表马上就动）
        clearHit()

        // 提议之前先存一份现场（被拒时按它撤回）
        rollbackPhase = phase
        rollbackKeys = previousKeys
        rollbackYs = previousYs
        rollbackTopScrolls = topScrolls
        val previous = previousKeys
        val previousY = previousYs
        // **"有没有动"看两件事，任一成立就算动过**：
        // ① **共同行的位置**（位移，最可信）：一次拖动**不足一屏**时，共同的行很多、可位置明显变了
        //    —— 只看重合度会判成"没动 ⇒ 到顶"（2026-10-01 用户报："**每次滑动的距离其实不够一屏，
        //    导致过早结束了，还是没有滑到顶**"）；
        // ② 行键重合度低 ⇒ 换了一屏（见 [sameScreen] 与两条阈值）。
        val signedShift = displacementOf(previousY, rowYs)?.deltaY ?: 0.0
        val shift = kotlin.math.abs(signedShift)
        val shifted = shift >= ROW_SHIFT_PX
        // **方向体检**（2026-10-01 真机事故）：回顶应当让内容**下移**（行 y 变大 ⇒ 位移为负），
        // 往下找反之。方向反了说明手势几何写反了（实录：edgeRatio 0.6 ⇒ 起止点交叉 ⇒ 回顶却向上划，
        // 列表越滑越往下、用户报"根本没有往上移动"）—— 这种情况**必须在日志里喊出来**。
        val reversed = if (phase == Phase.TO_TOP) signedShift > ROW_SHIFT_PX else signedShift < -ROW_SHIFT_PX
        val moved = previous == null || shifted || !sameScreen(previous, rowKeys)
        // 回顶阶段用**更松**的一条线（见 [TO_TOP_SAME_SCREEN_OVERLAP]）：真机上"同一屏"的重合度只有
        // 36~50%（行键被 OCR 抖动），拿 0.6 去判会一路白滑到上限
        val movedTowardsTop = previous == null || shifted ||
            !sameScreen(previous, rowKeys, TO_TOP_SAME_SCREEN_OVERLAP)
        previousKeys = rowKeys
        if (rowYs.isNotEmpty()) previousYs = rowYs
        val overlap = if (previous == null) {
            "首读"
        } else {
            overlapText(previous, rowKeys) + shiftText(shift, rowYs.isNotEmpty(), reversed)
        }
        // **"画面没动"只有在"我们真的滑过"之后才有意义**（2026-10-01：被门禁拒 / 手势被游戏吞掉的
        // 那一次次"没动"，说明不了列表到没到边 —— 见 [noMovementStreak] 与 [EDGE_PROBE_MAX]）
        // 回顶的"累计上移"（px，见 [MAX_TO_TOP_PX]）：只累加**真滑过**那几次的位移
        if (phase == Phase.TO_TOP && lastProposalDispatched && shift > 0) topMovedPx += shift
        val noMoveThisRound = if (phase == Phase.TO_TOP) !movedTowardsTop else !moved
        noMovementStreak = if (noMoveThisRound && lastProposalDispatched) noMovementStreak + 1 else 0

        val step = when (phase) {
            // 当前屏没中 ⇒ 开始"回顶"（第一次就滑）—— 除非**这一屏就是上次见到的顶部那一屏**：
            // 那就没必要空滑一下，直接往下找（用户 2026-09-29 建议："区服名大多在第一屏……"）
            Phase.CURRENT -> {
                phase = Phase.DOWN
                val learned = learnedTopKeys
                when {
                    learned != null && sameScreen(learned, rowKeys) ->
                        proposeDown("这一屏与上次跑号见到的**列表顶部**那一屏一致（重合 ${overlapText(learned, rowKeys)}）⇒ 跳过回顶，直接往下找")

                    // **刚登录进来 ⇒ 列表天然在顶部**（用户 2026-10-01 观察：每次重新登录服务器，
                    // 好友列表都会回到顶部）⇒ 第一屏就是顶部屏（顺便学下来），省掉那一下空滑
                    entryAtTop -> {
                        learnTop(rowKeys)
                        proposeDown("刚登录进来，列表本来就在顶部 ⇒ 跳过回顶，直接往下找")
                    }

                    else -> {
                        phase = Phase.TO_TOP
                        proposeTowardsTop(overlap)
                    }
                }
            }

            // 还在回顶：画面还在动 ⇒ 继续向上滚；画面不动了 ⇒ 已经到顶（顺便记下"顶部那一屏"）
            Phase.TO_TOP -> when {
                // 画面还在动、额度没用完 ⇒ 继续向上滚（额度 = 次数安全阀 + **累计位移** [MAX_TO_TOP_PX]）
                movedTowardsTop && topScrolls < maxTopScrolls && topMovedPx < MAX_TO_TOP_PX ->
                    proposeTowardsTop(overlap)

                movedTowardsTop -> {
                    // 画面还在动、但额度到点（判据不可靠时的护栏）：当作已到顶，转为往下找
                    val moved = topMovedPx.toInt()
                    phase = Phase.DOWN
                    topMovedPx = 0.0
                    noMovementStreak = 0
                    learnTop(rowKeys)
                    proposeDown(
                        "回顶已达额度（滑 $topScrolls 下 / 累计上移 ${moved}px，上限 ${MAX_TO_TOP_PX.toInt()}px）" +
                            "⇒ 当作已到顶，转为往下找",
                    )
                }

                // **只"没动"一次不算到顶**（可能是手势被吞 / 被拒）：再滑一次确认（见 [EDGE_PROBE_MAX]）
                noMovementStreak < EDGE_PROBE_MAX -> {
                    val step = proposeTowardsTop(overlap)
                    if (step is Step.ScrollTowardsTop) {
                        lastDecisionNote = "这一屏与上一屏重合 $overlap ⇒ 画面**没动**，" +
                            "但真的滑过的才 $noMovementStreak 次 ⇒ **再滑一次确认**（别急着判到顶）"
                    }
                    step
                }

                else -> {
                    val streak = noMovementStreak
                    phase = Phase.DOWN
                    noMovementStreak = 0
                    topMovedPx = 0.0
                    learnTop(rowKeys)
                    proposeDown("这一屏与上一屏重合 $overlap ⇒ 已经在顶部（连续 $streak 次真的滑过都没动）")
                }
            }

            // 自上而下：换了一屏就继续往下；画面不动了 = 到底了
            Phase.DOWN -> if (moved) {
                proposeDown("换了一屏（与上一屏重合 $overlap）⇒ 继续往下找")
            } else if (noMovementStreak < EDGE_PROBE_MAX) {
                // **只"没动"一次不算到底**（可能是手势被吞 / 被门禁拒）：再滑一次确认（见 [EDGE_PROBE_MAX]）
                // ⚠ 到底这一侧误判代价最大 —— 会直接说"找不到"，把没翻到的那些条目全放过。
                proposeDown(
                    "这一屏与上一屏重合 $overlap ⇒ 画面**没动**，但真的滑过的才 $noMovementStreak 次" +
                        " ⇒ **再滑一次确认**（别急着判到底）",
                )
            } else {
                lastProposalScrolled = false
                lastDecisionNote =
                    "这一屏与上一屏重合 $overlap ⇒ 已到底（连续 $noMovementStreak 次真的滑过都没动）"
                Step.GiveUp(
                    "已翻到列表底部（连续 $noMovementStreak 次真的滑过画面都不动，共滑动 $scrolls 次）" +
                        "仍未找到这个$targetNoun——$targetHint",
                )
            }
        }
        // 本轮刚提议过一次滑动（[propose] 里置的 `awaitingOutcome`）⇒ 记下时刻，
        // 供"回告一直没来"的兜底判超时（见 [OUTCOME_PENDING_TIMEOUT_MS]）
        if (awaitingOutcome) awaitingSinceMs = nowMs
        return step
    }

    /**
     * **命中之后、动手之前**：确认这一行**在更新的那张画面上还在原处**（2026-09-29 真机事故后加）。
     *
     * ## 为什么必须有它
     *
     * 真机实录（02:12:19，用户报："选的是第二屏最后一行的黑砂流瀑，结果点到了第一屏最后一行的机仆阵列"）：
     * ```
     * 02:12:19.679  滑动下发（拖动 300ms）→ 19.982 手势完成
     * 02:12:20.797  第 5 步命中：「黑砂流瀑」在 (760, 1258)
     * 02:12:20.798  点 (760, 1258) → 允许下发          ← 读到手后 **815ms** 就下手
     * ```
     * 列表在滑动之后**还有惯性 / 回弹**，那一行在这 0.8 秒里又移开了**一行左右** ⇒ 坐标落到**相邻的
     * 另一个区服**上。列表每一行长得都一样，"读到过"**不等于**"现在还在那儿"（与活动弹窗那次"判定用的
     * 画面比屏幕旧"是同一类问题：动手的依据必须是**当下**的画面）。
     *
     * ⇒ 判据：**连续两次新读**都命中同一行（区号/名字段一致）且**纵坐标几乎没动**，才允许点。
     * 代价是命中后多等一轮（≈0.3s）；一旦列表停住就立刻满足，不会反复等。
     *
     * @param key 行的身份（**区号优先**：数字那一栏实测 12/12 行全对，名字会丢字 —— 与 [RealmPickDecision] 同口径）
     * @param y 这一行在本轮画面上的纵坐标
     * @param frameAtMs 本轮那张画面的**拍摄时刻**（必须比上一轮新，才算新证据）
     * @return true = 可以点；false = 再等一轮确认
     */
    fun onHit(key: String, y: Double, frameAtMs: Long): Boolean {
        val stable = lastHitKey == key &&
            kotlin.math.abs(lastHitY - y) <= ROW_STABLE_PX &&
            frameAtMs > lastHitFrameAtMs
        lastHitKey = key
        lastHitY = y
        lastHitFrameAtMs = frameAtMs
        return stable
    }

    /** 滑动**真的下发了**（门禁允许）：从此刻起算"等这一屏稳定"。 */
    fun onScrollDispatched(nowMs: Long) {
        awaitingOutcome = false
        awaitingSinceMs = 0L
        lastProposalDispatched = true
        lastScrollAtMs = nowMs
        clearHit() // 列表马上要动：上一轮那点坐标作废
    }

    /** 滑动**被门禁拒了**（前台 / 状态 / 间隔 / 越界 / 无注入器）：把这一步原样撤回，下一轮重新提议。 */
    fun onScrollRejected() {
        if (!awaitingOutcome) return
        rollbackProposal()
        lastDecisionNote = "上一次滑动被门禁拒了 ⇒ 撤回这一步"
    }

    /**
     * **原样撤回上一次提议**（不改 [lastDecisionNote]）——被 [onScrollRejected] 与"回告一直没来"的
     * 兜底（[onMiss] 里那条超时）共用，保证两条路的撤回语义**完全一致**（撤回后下一轮重新提议同一件事）。
     */
    private fun rollbackProposal() {
        awaitingOutcome = false
        awaitingSinceMs = 0L
        // 没下发 ⇒ 这一次"画面没动"不算数，也不能计入"到边"的连续次数（见 [noMovementStreak]）
        lastProposalDispatched = false
        if (lastProposalScrolled) {
            scrolls = (scrolls - 1).coerceAtLeast(0)
            if (phase == Phase.TO_TOP || rollbackPhase == Phase.TO_TOP) {
                topScrolls = rollbackTopScrolls
            }
        }
        phase = rollbackPhase
        previousKeys = rollbackKeys
        previousYs = rollbackYs
        lastProposalScrolled = false
    }

    /** 记下"列表顶部那一屏"（跨跑号复用，见 [learnedTopKeys]）。 */
    private fun learnTop(rowKeys: Set<String>) {
        if (rowKeys.isNotEmpty()) learnedTopKeys = rowKeys
    }

    /**
     * **告诉扫描器"这一份列表属于哪个登录账号 / 服务器"**（2026-10-01 用户口径：
     * "到顶记忆绑定当前登录服务器，不同小号的好友列表可能不同"）。
     *
     * ## 为什么必须绑定（不绑定会静默漏找）
     *
     * [learnedTopKeys] 是**这一份列表的内容指纹**（"顶部那一屏长什么样"）。不同小号的列表内容不同 ⇒
     * 拿甲的指纹去判乙的列表，可能把**中途某一屏**误认成"已经在顶部"⇒ 那一下"跳过回顶"就把上面的条目
     * **整段漏掉**了（而好友这条恰恰依赖"从顶上往下看"才能取到"最上面"那一个）。
     *
     * ⚠ `scope == null`（**不知道当前登录的是哪个号**，例如「只拜访」时是用户自己登的）⇒
     * **学到的作废、且本次不采信**（`learnedTopKeys` 清空 ⇒ 一定会老实滑那一下回顶，≈1.5s）：
     * 宁可多滑一下，也不要拿别人的列表指纹去跳过回顶（红线 3：不猜）。
     *
     * 只在**真的换了账号**时清空（同一个账号反复跑号不受影响，跳过回顶的收益照旧）。
     */
    fun noteScope(scope: String?) {
        if (scopeKnown && learnedScope == scope) return
        scopeKnown = true
        learnedScope = scope
        learnedTopKeys = null
    }

    private fun proposeTowardsTop(overlap: String): Step {
        val step = propose(Step.ScrollTowardsTop)
        if (step is Step.ScrollTowardsTop) {
            topScrolls++
            lastDecisionNote = "还没到顶（与上一屏重合 $overlap）⇒ 继续向上滚（第 $topScrolls/$maxTopScrolls 下）"
        }
        return step
    }

    private fun proposeDown(note: String): Step {
        val step = propose(Step.ScrollDown)
        if (step is Step.ScrollDown) lastDecisionNote = note
        return step
    }

    /**
     * 计数 + 上限（需求："滚动次数设上限，超出即中止并提示"）。
     *
     * ⚠ **两本账分开记**（2026-10-01 真机事故）：好友列表**一次拖动可能只走 1/3~1/2 屏**
     * （实录 `共同行位移 79 / 539 / 390 / 182px`，一屏 ≈1119px）⇒ 回顶那几下若也记进这本 5 屏的账，
     * "回顶 3 下 + 往下 2 下"就把额度烧光 ⇒ 之后每一轮都"已达滑动上限 5 屏"、整步卡死 ✗
     * （用户 2026-10-01 报："没有滑到顶就开始往底部滑了，由于上限 5 屏的限制吗？" —— 正是它）。
     * ⇒ 这本账**只记"往下找"**（需求 FR-04 硬性要求 #4 说的就是"逐屏滚动查找"）；
     * 回顶另有一本：次数 [maxTopScrolls] + **累计上移 [MAX_TO_TOP_PX]**（见 [proposeTowardsTop]）。
     */
    private fun propose(step: Step): Step {
        val searchingDown = step is Step.ScrollDown
        if (searchingDown && scrolls >= maxScrolls) {
            lastProposalScrolled = false
            lastDecisionNote = "已达滑动上限 $maxScrolls 屏"
            return Step.GiveUp("已滚动 $scrolls 屏仍未找到（上限 $maxScrolls 屏）⇒ 如实停下，不猜")
        }
        if (searchingDown) scrolls++
        lastProposalScrolled = true
        awaitingOutcome = true
        // 刚提议、还没下发 ⇒ "这一屏没动"暂时说明不了任何事（等 [onScrollDispatched] 置真）
        lastProposalDispatched = false
        return step
    }

    /**
     * 两次读到的"这一屏"是不是同一屏（容忍 OCR 抖动）—— 见类注释。
     *
     * ⚠ **样本太小就不判"同一屏"**（2026-10-01 真机事故，`docs/progress.md` 第 368 条）：
     * 行键是 OCR 读出来的，**读不到的行没有键**。好友列表第一版拿"备注名"当键（很多好友没设备注）
     * ⇒ 一屏读到 11 行却只凑出 **3 个键** ⇒ "重合 **2/3 行（66%）**"这种**2 行样本**就触发了
     * "同一屏"判据 ⇒ 把"换了一屏"误判成"**已到底**"、直接中止说"找不到"✗。
     * ⇒ 现在两个前提：**两边都要至少 [MIN_KEYS_FOR_SAME_SCREEN] 个键**，否则不能据此说"同一屏"
     * （返回 false = 当作"画面动过"，继续按阶段走；宁可多滑几下、滑到上限如实停下，也不误判到底）。
     */
    private fun sameScreen(
        previous: Set<String>,
        current: Set<String>,
        threshold: Double = SAME_SCREEN_OVERLAP,
    ): Boolean {
        if (previous.size < MIN_KEYS_FOR_SAME_SCREEN || current.size < MIN_KEYS_FOR_SAME_SCREEN) return false
        return overlapRatio(previous, current) >= threshold
    }

    private fun overlapRatio(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / maxOf(a.size, b.size)
    }

    private fun overlapText(a: Set<String>, b: Set<String>): String {
        val shared = a.count { it in b }
        val percent = (overlapRatio(a, b) * 100).toInt()
        return "$shared/${maxOf(a.size, b.size)} 行（$percent%）"
    }

    /**
     * 位移那段日志（跟着重合度一起写）：`，共同行位移 45px` / `（无共同行，量不出位移）`。
     *
     * @param measured 这一轮调用方**有没有给行坐标**（没给 ⇒ 本轮的"动没动"只看了重合度，别误导读日志的人）
     */
    private fun shiftText(shiftPx: Double, measured: Boolean, reversed: Boolean = false): String = when {
        !measured -> ""
        shiftPx <= 0.0 -> "（无共同行，量不出位移）"
        reversed -> "，共同行位移 ${shiftPx.toInt()}px ⚠**方向反了**（回顶该让内容下移 —— 查手势几何 edgeRatio）"
        else -> "，共同行位移 ${shiftPx.toInt()}px"
    }

    companion object {

        /** 两屏之间"共同行"的纵坐标位移（px）：回答"这次滑动移动了多少 / 有没有跳过整屏"。 */
        data class Displacement(val sharedRows: Int, val deltaY: Double)

        /**
         * **用两屏共同出现的行当标尺**，量出这次滑动移动了多少像素（2026-09-29 加，**只用于日志**）。
         *
         * 为什么需要它：判"到顶 / 到底 / 换了一屏"用的是**重合度**，它只回答"变没变"，**回答不了"变了多少"**。
         * 而"一次滑动会不会**滑过一整屏**（于是中间那屏从没被读过）"正是列表很长（比如 10 屏）时唯一的
         * 漏找风险 —— 两次读数的位移就是它的直接证据：
         * - **有共同行** ⇒ 两次读到的屏是**重叠**的 ⇒ 没有跳过（位移 < 一屏高度）；
         * - **位移 ≥ 列表区域高度** ⇒ 一次滑动越过了一整屏 ⇒ 中间那些行**没被读过**（该缩小步长）；
         * - **完全没有共同行** ⇒ 要么读数花得厉害、要么就是跳屏 —— 都该当"可疑"看。
         *
         * 取**中位数**（不是平均）抗单行读歪。行高可参考真机数据：列表区域高 1119px / 12 行 ≈ 93px。
         *
         * @return null = 两屏没有共同行（见上：可疑）
         */
        fun displacementOf(previous: Map<String, Double>, current: Map<String, Double>): Displacement? {
            val deltas = previous.entries.mapNotNull { (key, y) -> current[key]?.let { y - it } }
            if (deltas.isEmpty()) return null
            val sorted = deltas.sorted()
            return Displacement(sharedRows = deltas.size, deltaY = sorted[sorted.size / 2])
        }

        /**
         * 累计滑动次数上限，取 **5**（2026-10-01 用户拍板）—— 与 `NameLocator.MAX_SCROLLS`
         * （跨屏查找时的同一口径）**必须一致**。
         *
         * 为什么是 5：一屏约 12 行，用户当前 14 个区服 / 十几个小号 ⇒ 正常最多滑 2~4 次就够
         * （回顶 1~2 次 + 往下 1~2 次）；而"列表真的很长、目标在很后面"时，5 屏（≈8~10 秒）也够
         * 覆盖绝大多数情形 —— 再往下找就不如**如实停下让用户看一眼**（红线 3：不猜、不无限滚）。
         * ⚠ 好友列表**回顶那几下也计入**这一个上限（同一本账，不另开额度）。
         */
        const val MAX_SCROLLS = 5

        /**
         * "回顶"阶段最多滑几下，取 **3**（结构护栏）。
         *
         * 依据：用户 14 个区服 / 一屏 12 个 ⇒ 列表最多离顶**一屏**，每次拖动又换掉约半屏以上
         * ⇒ 3 下足够回到顶。到点即**当作已到顶**转为往下找 —— 宁可少滑（往下找会把剩下的扫一遍），
         * 也不能像 02:17 那次一样把 10 次预算全烧在"回顶"上（用户报："滑到顶后还滑了好多下"）。
         */
        const val MAX_TO_TOP_SCROLLS = 8

        /**
         * **回顶阶段"累计上移"的上限**（px，取 **3600** ≈ 3.2 屏，2026-10-01 加）。
         *
         * 依据：好友列表**一屏 ≈ 1119px**，而真机一次拖动只走 **79~539px**（实录，见
         * [ROW_SHIFT_PX] 与 [proposeTowardsTop]）⇒ 只按"滑了几下"收尾会**半路放弃**：
         * 实录 `回顶已滑 3 下（上限 3）⇒ 当作已到顶` 时其实只上移了约 1.5 屏 ✗（用户报
         * "**没有滑到顶就开始往底部滑了**"）。⇒ 改成**看累计位移**：走了 3 屏还没到顶才当作到顶
         * （真实好友列表最多也就几屏 ⇒ 这个额度只当安全阀）。
         */
        const val MAX_TO_TOP_PX = 3_600.0

        /**
         * 一次拖动后等画面稳定的时长（ms），取 **800**。
         *
         * 依据：拖动本身 300ms（见 `CaptureService.SCROLL_DRAG_MS`），而每次读文字 ≈0.2~0.3s、
         * 轮询 200ms ⇒ 800ms 让"读到的行"大致属于稳定后的那一屏。
         *
         * ⚠ **它不保证列表已经停住**（真机 02:12 那次：滑动后 815ms 读到的行，等手指落下时又移开了一行
         * —— 列表还有惯性 / 回弹）⇒ "能不能点"另由 [onHit] 用**两次读数一致**来确认，本常量只用来
         * 避免"滑动刚下发就连着提第二次"。
         */
        const val SETTLE_MS = 800L

        /**
         * 判定"这一行还在原处"允许的纵坐标漂移（**运行帧像素**，取 **16**）。
         *
         * 同一行在两张画面上重读，纵坐标的正常抖动只有几像素；16px 远小于行高（列表里每行 ≈90px，
         * 见真机 `第 5 步 OCR 输入区域` 的 y 跨度 1119px / 12 行），所以"移开一行"一定判得出来。
         *
         * [onHit]（两次读数一致才允许点）与 [stillOnSameRow]（开火前复眼）**共用**这一个容差。
         */
        const val ROW_STABLE_PX = 16.0

        /**
         * 判定"还是同一屏"的行键重合度下限（**60%**）。
         *
         * 依据：真机同一屏连读三次，行名抖动率约 1/3（`Lv.9T`/`Ly.9T`、`Lv.25v`/`Lv.25y`、`Lv.6T`/`Lv.6日`）⇒
         * 60% 能容忍这种抖动；而**换一屏时重合度接近 0**（12 行里最多 1~2 行还在屏上）⇒ 两头都分得开。
         */
        const val SAME_SCREEN_OVERLAP = 0.6

        /**
         * **"回顶"阶段判"这一屏没动"的重合度**（取 **0.35**，2026-10-01 加，比往下找那条松）。
         *
         * ## 为什么要跟 [SAME_SCREEN_OVERLAP] 分开（真机实测：同屏 36~50%，换屏 16~27%）
         *
         * 真机（2026-10-01 06:43，用户报"**滑到顶为什么重复滑了几次才停下**"）：列表**早就在顶部**，
         * 连续四次读到的内容一模一样，可重合度只算出 `5/10（50%）`、`4/9（44%）`、`36%` ✗ ——
         * 达不到 60% ⇒ 判"还没到顶"⇒ 白滑到 3 下上限才停（上限本身写着"当作已到顶"）。而**真换了屏**
         * 的那几次是 `27%` / `16%` / `25%` ⇒ 两档分得开，**0.35 正好卡在中间**。
         *
         * ## 为什么只放宽"回顶"，不放宽"往下找"
         *
         * 两种误判的代价不对称：
         * - **回顶**判错（把"没动"当成"动了"）⇒ 只是多滑一下（正是用户抱怨的那个）；
         * - **往下找**判错（把"动了"当成"没动"⇒ 判到底）⇒ **直接漏人、说找不到** ✗ 不可接受。
         * ⇒ 回顶松（0.35）、往下紧（0.6）。⚠ 第 5 步（区服列表）行键是**区号数字**、稳得多，
         * 两侧重合度天然分得很开 ⇒ 这个放宽对它没有任何影响。
         */
        const val TO_TOP_SAME_SCREEN_OVERLAP = 0.35

        /**
         * **判"到边（到顶 / 到底）"之前，要求连续几次"真滑过但没动"**（取 **2**，2026-10-01 真机事故后加）。
         *
         * 一次"没动"可能是：① 那一枪被门禁拒（压根没下发）；② **手势被游戏吞掉**（下发了、列表没滚）；
         * ③ 行键样本太小（真机好友列表只有 6 个键，一个键就占 17%）+ 阈值宽 ⇒ 轻微移动也像"没动"。
         * ⇒ 只认"**真的滑过**"的没动（见 [lastProposalDispatched]），而且要**连续 2 次**才下"到边"的结论；
         * 差一次时只是**再滑一次确认**（多花一次手势 ≈1.2 秒，换"不误判到顶/到底"—— 误判到顶会漏掉上面的好友，
         * 误判到底会直接说"找不到"）。
         */
        const val EDGE_PROBE_MAX = 2

        /**
         * 判"这一屏与上一屏是同一屏"所需的**最少行键数**（取 3，2026-10-01 加）。
         *
         * 为什么需要它：行键靠 OCR，读不到的行没有键 ⇒ 样本可能很小，而"2/3 = 66%"这种**2 行样本**
         * 完全可能是抖动而非"同一屏"（真机就是这样把"换了一屏"判成了"已到底"）。
         * 取 3 是保底：既要挡住"1~2 行样本"这种噪声，又不至于让正常的 7~12 行读数用不上这条判据。
         */
        const val MIN_KEYS_FOR_SAME_SCREEN = 3

        /**
         * **判"这次滑动真的动过"的共同行位移阈值**（px，取 **32**，2026-10-01 加）。
         *
         * 依据：列表**行距 ≈ 90px**（真机：列表区域 1119px / 12 行 ≈ 93px，见 [Displacement] 的注释），
         * 而 OCR 给出的行中心抖动只有几 px ⇒ 共同行整体挪了 ≥ 32px（约 1/3 行距）就确定"动过"。
         *
         * ## 为什么必须有它（用户 2026-10-01 报："**每次滑动的距离其实不够一屏，导致过早结束了，
         * 还是没有滑到顶**"）
         *
         * 判"动没动"原来只看**行键重合度**：一次拖动若只走了半屏，两屏共同的行很多 ⇒ 重合度高
         * ⇒ 被判成"没动 ⇒ 已经在顶部"✗，于是**根本没到顶就转头往下走**。而共同行的**位移**是直接证据。
         */
        const val ROW_SHIFT_PX = 32.0

        /**
         * **提议一次滑动后，最多等多久它的回告**（ms，取 5000，2026-10-01 加）。
         *
         * 正常回告（[onScrollDispatched] / [onScrollRejected]）都在同一轮里、几十毫秒内到；
         * 而"回告一直不来"意味着调用方的某个分支漏了回告 ⇒ 扫描会**永远停在"等回告"**、
         * 再也不提议任何滑动（真机表现：OCR 一直读，但一次都不滑 —— 用户原话"这次根本就没滑屏"）。
         * ⇒ 等够 [OUTCOME_PENDING_TIMEOUT_MS] 就当作"被拒"（原样撤回、下一轮重新提议），宁可多发一次滑动。
         * ⚠ 它**只兜底**，正常情况下永远用不到（真用到了，日志会在"第 N 步扫描"里写明是超时撤回）。
         */
        const val OUTCOME_PENDING_TIMEOUT_MS = 5_000L

        /**
         * 一行文本 → **行键**：**优先取数字**（区号那一栏真机实测 12/12 行全对），没有数字才退回名字段。
         *
         * 口径与 [RealmPickDecision.Row] 同源。2026-09-30 上移到纯逻辑，是因为**开火前复眼**
         * （[stillOnSameRow]）也要用同一把键 —— 原来这段规则只写在 `CaptureService.rowYsOf` 里，
         * 两处各写一遍就会各自漂移（同"不比较文案"那条教训：判据只写一处）。
         */
        fun rowKeyOf(text: String): String {
            val digits = text.filter { it.isDigit() }.take(6)
            if (digits.isNotEmpty()) return "n$digits"
            val row = RealmPickDecision.Row(text, 0.0, 0.0)
            return "t" + row.name.filterNot { it.isWhitespace() }
        }

        /**
         * **第 5 步的开火前复眼**（2026-09-30 用户口径"现在补复眼"）：
         * **在动手前现取的那张最新画面上**，目标那一行**还认得出、且还在原来那条纵线上**吗？
         *
         * ## 与 FR-01 那道复眼的关系（同一类防线，判据不同）
         *
         * FR-01 的复眼复核的是"某个**锚点模板**还在不在原处"（[com.example.mastermechanic.action.PreFireRecheck]）；
         * 第 5 步点的是**OCR 找到的列表行**、没有模板可匹配 ⇒ 复核方式改成**再读一次那一行**，
         * 用同一把**行键**（[rowKeyOf]）认身份、同一个容差（[ROW_STABLE_PX]）认位置。
         *
         * ## 它挡的是什么（真机两次事故：第 245 条 / 第 259 条）
         *
         * [onHit] 的"两次读数一致"只保证"**读**的这两次是同一行"，而"读"到"手指落下"之间还有一段
         * （帧龄 + 单轮耗时 + 点击门禁的间隔等待，最坏 ≈1 秒）—— 列表在这段里**回弹一行**，
         * 坐标就落到**相邻的另一个区服**上。所以动手依据必须是**当下**的画面。
         *
         * ⚠ **拦下 ≠ 失败**：调用方按"这一枪不发、等下一轮重新读"处理（不消耗重试、不判失败），
         * 列表停住后自然会放行。取不到更新的帧时这条**不参与**（平台只在内容变化时产帧 ⇒ 队列里
         * 没有新帧 = 屏幕自我们那张画面之后没变过）。
         *
         * @param freshYs **最新那帧上**读到的 `行键 → 纵坐标`（键由 [rowKeyOf] 生成）
         * @return true = 那一行还在原处，可以动手
         */
        fun stillOnSameRow(expectedKey: String, expectedY: Double, freshYs: Map<String, Double>): Boolean {
            val freshY = freshYs[expectedKey] ?: return false
            return kotlin.math.abs(freshY - expectedY) <= ROW_STABLE_PX
        }
    }
}
