package com.example.mastermechanic.patrol

import com.example.mastermechanic.action.ClickRequest
import com.example.mastermechanic.action.ClickSource
import com.example.mastermechanic.action.SwipeRequest
import com.example.mastermechanic.decision.AnchorHit
import com.example.mastermechanic.decision.UiState

/**
 * 跑号的**每轮编排**（M4-T4-4a，纯逻辑、可 JVM 重跑）。
 *
 * 职责边界（与邻层的关系，同 [com.example.mastermechanic.action.PopupCloseController] 的切法）：
 * - **不产生点击**：只给出 [ClickRequest]，下发一律经 `ClickDispatch.submit`（红线 6 的唯一实现点）；
 * - **不做识别**：画面是什么、锚点定位到哪，由识别循环与 `AnchorLocator` 喂进来（本类的输入）；
 * - **不负责节流的实现**：只按 [PatrolFlow.CLICK_GAP_MS] 判断"这一轮能不能点"，真正的间隔由 `ClickGate` 兜底
 *   （两层都要有：这里决定"要不要点"，那里保证"点了也满足 300ms"）。
 *
 * ## 一轮只做一件事
 *
 * 每帧进来一次 [onRound]，结论只有四种：**等 / 点 / 进下一步 / 停下并说明原因**。
 * 刻意没有"连续补点"——FR-04 硬性要求 1：**每一击之间都要验证**，验证不过就计一次重试，
 * 到 [PatrolFlow.MAX_RETRIES] 次即中止（不静默重试整条流程，FR-05）。
 *
 * ## 三条容易做错的地方
 *
 * 1. **刚点完不等于没生效**：点完画面要时间切换，所以有 [SETTLE_WAIT_MS] 的观察窗口；
 *    窗口内一律"等"，窗口过了还没变成 `expect` 才计失败（否则一帧就算一次失败，3 次瞬间耗尽）。
 * 2. **弹窗优先**：弹窗盖住控件时点了也是白点，所以弹窗在屏（哪怕只是本轮命中）就先等 FR-01 关掉。
 * 3. **锚点按 ID 精确取**：`anchors.firstOrNull { it.name == action.anchor }`——
 *    定位器给出多个锚点时不挑"最像的"（红线 3：落空是安全的，猜中才是危险的）。
 */
object PatrolRunner {

    /**
     * 一击之后留给画面切换的观察窗口（毫秒）——**上限**，不是固定等待（2026-09-21）。
     *
     * 取值依据：帧周期 200ms（变化轮）/ 500ms（稳定轮），而游戏里"点设置 → 弹出确认框"通常 0.3~1s；
     * 2s 足以让画面切过来，又不会让"点错地方"的白等拖太久（3 次失败 ≈ 6s 后中止）。
     *
     * ## 为什么要能提前结束（真机实测）
     *
     * 原来这里是**一律等满 2s**。真机时间线（2026-09-21 00:23 会话）暴露了代价：
     * 点「大厅→设置」这一击在 0.96s 后就已确认画面变成「设置页」，但下一步**硬等到 2.1s** 才点 ——
     * **每一步白等 ≈1.1~1.4s**，一串 7 步就是 8~10s 的纯等待（用户反馈"整个流程还是有点慢"的直接来源）。
     *
     * 现在的判据：**窗口内一律等，除非画面确实换了另一屏、且新画面上这一步有事可做**（见 [screenMovedOn]）。
     * 保护一分不减：画面**没变**（可能点歪 / 还没生效）、或变成 `未知`（正在加载）时，照旧等满这 2s，
     * 失败计时与重试节奏跟以前完全一致 —— 快只快在"确实成功了"这条路上。
     */
    const val SETTLE_WAIT_MS = 2_000L

    /** 一轮的全部输入（全部来自识别层 / 定位器 / 时钟，本类不自己取）。 */
    data class RoundInput(
        /** 目标是否前台（冻结轮 = false → 暂停，不动作、不计数）。 */
        val foreground: Boolean,
        /**
         * **我们现在"看不见"画面**（连续收不到新帧 ⇒ 点击门禁会拒掉所有点击，2026-09-30 加）。
         *
         * 采集层传的是 `ClickDispatch.framesStalled`（与门禁**同一个信号**，不另立一套）。
         * 为 true 时本类什么都不判、什么都不点，并且**把这一步的计时顺延到此刻**（闹钟暂停）——
         * 那一刻"屏幕真的没变"与"采集坏了"分不出来，把"看不见"升级成"这一步超时中止"就是误判
         * （用户口径 2026-09-30："防止误判导致影响流程"）。
         *
         * 默认 false ⇒ 既有调用点与单测行为不变。
         */
        val framesStalled: Boolean = false,
        /**
         * **锚点名的中文说法**（2026-10-01 用户口径）：note / 中止原因里出现锚点名时用它 —
         * 用户看到的是「设置入口」，而不是 `hall_settings` 这种只对代码有意义的英文契约名。
         *
         * 由采集层注入（`SignalNames.of(产物, id)`：备注 > 用途标签 > 「界面」的角色 ⇒ ID）。
         * 默认恒等 ⇒ 既有调用点与单测行为不变。
         */
        val anchorLabel: (String) -> String = { it },
        /** 本轮识别出的界面（滞回结论）。 */
        val state: UiState,
        /** 本轮**原始命中**含活动弹窗（比滞回结论更灵敏：刚弹出来就别点）。 */
        val popupHit: Boolean,
        /** 滞回结论 = 活动弹窗（FR-01 正在关）。 */
        val popupConfirmed: Boolean,
        /** 当前状态定位到的锚点（按产物声明顺序）。 */
        val anchors: List<AnchorHit>,
        val nowMs: Long,
        /** 上一次点击的时刻（0 = 本次执行还没点过）。 */
        val lastClickAtMs: Long,
        /**
         * 本次预设要拜访的**好友名**（第 9 步按名称定位用）；null = 不指定。
         *
         * 2026-09-23 起不再用于"到达验证"（见下 ④b 的删除说明），只剩 ⑦ 的日志文案用它。
         */
        val targetFriend: String? = null,
        /**
         * 本次要换到的**区服名**（第 5 步按名称定位用，2026-09-24 接线）；null = 菜单没带
         * —— 例如「换号 → 下一个」要按清单取下一个，那个游标还没实现，这时第 5 步如实停下说明。
         */
        val targetServer: String? = null,
        /**
         * 第 5 / 9 步"点哪里"（2026-09-21 起；2026-09-24 起第 5 步也走这条路）：**由采集层算好**，本类只消费。
         *
         * 为什么不在这里算：定位需要**灰度帧 + 锚点定位器 + 文字识别**（采集层才有），而本类是纯逻辑
         * （同输入同结果、可单测）。所以采集层负责"读名字 → 命中哪一项 / 哪一行 → 点哪里"，
         * 本类负责"收到位置就点、收到失败原因就停"。见 [NamePlan]。
         */
        val namePlan: NamePlan = NamePlan.Waiting,
        /** 判定记录 ID（NFR-05 审计链：与 `MM-Click` 日志对齐）。 */
        val decisionId: String,
    )

    /**
     * **按名称定位那两步"点哪里"**：采集层定位后的产物（第 9 步 2026-09-21；第 5 步 2026-09-24）。
     *
     * 三种状态各自对应一句**说得清的话**：还没算（等）、算好了（点）、算不出来（停 + 原因）。
     * 绝不出现"猜一个位置"这一档（红线 3）。
     */
    sealed interface NamePlan {

        /** 还没到能算的时候（画面不是那张列表 / 不是这两步之一）→ 等。 */
        data object Waiting : NamePlan

        /**
         * 定位成功：点这个运行帧坐标（第 9 步 = 行尾拜访图标中心；第 5 步 = 区服名框中心）。
         *
         * ## [advanceStep] / [anchorName]（2026-10-01：第 9 步的**搜索链**）
         *
         * 第 9 步现在会"**先看当前屏；没有就点三下开搜索框**"（[PatrolAnchors.FRIEND_SEARCH_ENTRY]）。
         * 那三下**不是这一步的"动手"** —— 它们只是把搜索框打开，之后还要写文字、还要再点一下。
         * 若照旧走"收到 Click 就 [PatrolFlow.acted]"（标记已动手、转入"只等期望画面"）⇒ 采集层**再也
         * 收不到问话** ⇒ 搜索链卡在第二下、直到单步预算耗尽（真机实录 07:50:07~22：
         * `搜索链 ② 点「请输入好友昵称」` 之后一直是 `刚点过，等画面切换` ⇒ `已中止`）。
         *
         * ⇒ [advanceStep] = false 表示"**这一击不算这一步的动手**"：照常下发这一击，但**不**标记已动、
         * 下一轮照旧问采集层要计划（搜索链就能自己往下走）；[anchorName] 只影响审计日志里的锚点名
         * （默认保持原来的"拜访图标 / 区服名"）。
         */
        data class Click(
            val frameX: Double,
            val frameY: Double,
            val detail: String,
            /** false = 这一击**不**算"本步已动手"（搜索链用）；见类说明。 */
            val advanceStep: Boolean = true,
            /** 审计里写的锚点名；null ⇒ 按老规矩（第 9 步 `friend_visit` / 第 5 步 `server_name`）。 */
            val anchorName: String? = null,
        ) : NamePlan

        /**
         * **这一屏没有，还要滚一屏再找**（第 5 步「逐屏滚动查找」，2026-09-29 加）：
         * 把"这一枪"换成一次**滑动**。起止点由采集层按框选的「服务器列表区域」算好
         * （本类只负责下发，不碰几何；方向口径见 [SwipeRequest]）。
         */
        data class Scroll(
            val fromFrameX: Double,
            val fromFrameY: Double,
            val toFrameX: Double,
            val toFrameY: Double,
            val durationMs: Long,
            val detail: String,
        ) : NamePlan

        /** 定位失败：停下并说清是哪一环（没标区域 / 没框列 / 没找到 / 有好几个）。 */
        data class Failed(val reason: String) : NamePlan
    }

    /** 一轮的结论。 */
    data class RoundResult(
        /** 推进后的进度；null = 没有在跑的流程（调用方据此什么都不做）。 */
        val state: PatrolFlow.State?,
        /** 本轮要下发的点击；null = 这一轮不动手。 */
        val request: ClickRequest?,
        /** 给用户看的一句话（挂在悬浮窗 / 日志上）：在等什么、点了什么、为什么停。 */
        val note: String?,
        /** 本轮要下发的**滑动**（第 5 步滚列表）；null = 没有。与 [request] 互斥：一轮只下一个手势。 */
        val swipe: SwipeRequest? = null,
    ) {
        val finished: Boolean get() = state?.outcome == PatrolFlow.Outcome.FINISHED
        val failed: Boolean get() = state?.outcome == PatrolFlow.Outcome.FAILED
    }

    /** 没有在跑的流程：什么都不做（不是"空转"，而是明确的不动作）。 */
    private val idle = RoundResult(state = null, request = null, note = null)

    /**
     * 一轮编排：看画面 → 决定"等 / 点 / 进下一步 / 停下"。
     *
     * @param current 当前进度；null = 没有在跑的流程
     */
    fun onRound(current: PatrolFlow.State?, input: RoundInput): RoundResult {
        if (current == null) return idle

        // ① 前台：不在前台 → 暂停。**回前台不自动继续**（FR-05），等用户点「继续」
        if (!input.foreground) {
            return RoundResult(PatrolFlow.pause(current), null, "游戏不在前台，已暂停——回前台后点「继续」")
        }
        if (current.paused) return RoundResult(current, null, "已暂停，等用户点「继续」")
        if (!current.isRunning) return RoundResult(current, null, current.reason)

        // ⓪ **我们暂时"看不见"画面**（连续收不到新帧）：什么都不判、什么都不点，
        //    并且**把这一步的计时顺延到此刻**（闹钟暂停）—— 见 [RoundInput.framesStalled]。
        //    画面一回来，从那时起重新给足这一步的预算。
        if (input.framesStalled) {
            return RoundResult(
                current.copy(stepEnteredAtMs = input.nowMs),
                null,
                "画面久未更新（收不到新帧）：先不动手，这一步的计时暂停，等画面回来",
            )
        }

        // ② 弹窗优先：弹窗盖着的时候点了也是白点，等 FR-01 关掉。
        //
        // ⚠ **同时把刚才那一枪作废**（2026-10-01 真机：卡在"进入农场"）：
        // 第 7 步点「农场入口」后约 1 秒弹出活动弹窗，FR-01 花 2 秒把它关掉，关掉后画面**回到大厅**
        // （那一枪被弹窗吞了，一步没走成）。而 ⑤ 的口径是"本步下过击 ⇒ 只等期望画面、不再补点"
        // （2026-09-24）⇒ 它只能干等满 15 秒然后如实中止，用户看到的就是"卡在进入农场、最后停在大厅"。
        // 遮挡屏盖着的那段时间画面本来就不作数 ⇒ "点了"这个结论也不该留着：作废之后，
        // 弹窗消失时若还没看到期望画面，就会走 ⑧→⑪ **重发这一枪**（受点击间隔与这一步的时间预算约束）。
        //
        // 为什么不会退化成"慢切换时连点"（2026-09-24 修复过的那个毛病）：作废**只在有遮挡屏时**发生，
        // 而且 ④ 的验证在弹窗消失后**先**跑 —— 那一枪若其实成功了、画面已经前进 ⇒ 直接推进、不会补点。
        // 复用 [PatrolFlow.withoutPendingAction]（它本来就是"撤销这一击已发出"的出口，另一处调用在门禁拒绝后）。
        if (input.popupConfirmed || input.popupHit) {
            val swallowed = current.lastActionAtMs > 0
            return RoundResult(
                state = if (swallowed) PatrolFlow.withoutPendingAction(current) else current,
                request = null,
                note = "等弹窗关掉再继续第 ${current.step.number} 步" +
                    if (swallowed) "（刚才那一枪作废：遮挡屏期间画面不作数，关掉后没到期望画面就重发）" else "",
            )
        }

        // ③ 区间外的步骤直接跳过（不执行也不验证）
        if (!PatrolFlow.inRange(current.step, current.range)) {
            return RoundResult(
                PatrolFlow.advance(current),
                null,
                "第 ${current.step.number} 步不在本次区间内，跳过",
            )
        }

        // ④ 验证：看到预期画面 → 进下一步（红线 5：验证通过才推进）
        if (PatrolScenes.check(current.step, input.state, input.anchorLabel) is PatrolScenes.Check.Passed) {
            // ④b 第 9 步的**精确验证**（2026-09-21 加、**2026-09-23 删**）：
            //     原先要求"确认已进入**目标好友**的农场"（靠农场左上角是谁的头像/名字）——
            //     用户拍板整块删掉：自己的农场与好友的农场换号 / 拜访路径完全一样，
            //     不需要知道"这是谁的农场"；流程的结束节点就是**在好友列表点下「拜访」**。
            //     ⇒ 第 9 步的完成判据回到"看到农场画面"（`PatrolScenes.check`）这一层，
            //       代价是"点错人"不再有回头拦截（点的是哪一行由第 9 步的名称定位保证，见红线 3）。
            val next = PatrolFlow.advance(current, input.nowMs)
            val note = if (next.outcome == PatrolFlow.Outcome.FINISHED) {
                "已完成"
            } else {
                "第 ${current.step.number} 步通过 → 第 ${next.step.number} 步"
            }
            return RoundResult(next, null, note)
        }

        // ⑤ **本步已经下过击 ⇒ 在耐心期内只等期望画面**：不再重新定位、更不补点（**口径 A，2026-09-24**）。
        //
        // 为什么要改成这样：原来这里是"固定观察窗口（[SETTLE_WAIT_MS]）内一律等，窗口一过照常判定"，
        // 而"照常判定"= 拿**滞后的状态标签**重新推出同一条子击（⑧ [PatrolAnchors.actionFor] 是按当前画面查的）
        // ⇒ 又定位一次、**又点一次**。真机代价（progress 第 171 条）：换号第 3 步点下「确认退出」后
        // 游戏重启 4.9 秒，这期间对话框已消失 ⇒ 锚点自然定位不到 ⇒ 旧逻辑把它当"没命中"烧重试，
        // 3 次在 3 秒内烧完 ⇒ **在「启动页」出现前 1.4 秒中止**。
        //
        // 用户口径（2026-09-24）：**点了之后，接下来只该判定期望画面**。补点换来的收益（真点漏时自动补一枪）
        // 抵不上它的风险（慢切换时对"还挂在原位"的锚点连点 —— FR-04-1 每一击之间都要验证 / NFR-05）。
        // 所以：只等；等到画面确实前进（[screenMovedOn]）就继续往下走，等不到就到预算由 [waitOrAbort] 如实中止。
        //
        // 2026-09-20 的原始缺陷仍被这条覆盖：窗口内绝不连点（现在连"窗口过后"也不补点了）。
        if (current.lastActionAtMs > 0 && !screenMovedOn(current, input)) {
            val what = if (input.state == UiState.UNKNOWN) {
                "第 ${current.step.number} 步：刚点过，画面还没看清（可能正在加载）"
            } else {
                "第 ${current.step.number} 步：刚点过，等画面切换"
            }
            return waitOrAbort(current, input, what)
        }

        // ⑥ **画面还没看清**（`未知`，多半是游戏在加载 / 切场景）：等，**不消耗重试次数**；
        //    超过这一步的时间预算才中止（见 [PatrolFlow.timeoutFor]，2026-09-20 修）。
        if (input.state == UiState.UNKNOWN) {
            return if (PatrolFlow.timedOut(current, input.nowMs)) {
                val reason = timeoutReason(current, input)
                RoundResult(PatrolFlow.fail(current, reason, input.nowMs), null, "已中止：$reason")
            } else {
                RoundResult(
                    current,
                    null,
                    "第 ${current.step.number} 步：画面还没看清（可能正在加载），继续等" +
                        "（已等 ${PatrolFlow.waitedMs(current, input.nowMs) / 1000} 秒）",
                )
            }
        }

        // ⑦ **按名称定位的两步**（第 5 步选区服 / 第 9 步拜访好友）：位置由采集层算好（[RoundInput.namePlan]）——
        //     定位要**灰度帧 + 文字识别**（采集层才有），本类只消费结论："收到位置就点、收到原因就停"。
        //     第 9 步 2026-09-21 落地；**第 5 步 2026-09-24 接线**（此前这里是一句"文字识别尚未落地"的停下）。
        if (PatrolAnchors.isNameLocated(current.step)) {
            val pickingServer = current.step == PatrolFlow.Step.PICK_SERVER
            val who = if (pickingServer) {
                input.targetServer ?: "目标区服"
            } else {
                input.targetFriend ?: "目标好友"
            }
            val place = if (pickingServer) "选服页" else "好友列表"
            return when (val plan = input.namePlan) {
                // 还没算（画面还不是那张列表）→ 等 ⑥/⑧ 的耐心逻辑去做，这里只说一句
                PatrolRunner.NamePlan.Waiting -> RoundResult(
                    current,
                    null,
                    "第 ${current.step.number} 步：在「$place」里找「$who」",
                )

                is PatrolRunner.NamePlan.Failed -> RoundResult(
                    PatrolFlow.fail(current, "第 ${current.step.number} 步：${plan.reason}", input.nowMs),
                    null,
                    "第 ${current.step.number} 步停下：${plan.reason}",
                )

                is PatrolRunner.NamePlan.Click -> RoundResult(
                    // **这一击算不算"本步已动手"**：搜索链那三下不算（见 [NamePlan.Click] 的说明）——
                    // 不标记 ⇒ 下一轮照旧问采集层要计划，搜索链自己往下走
                    if (plan.advanceStep) PatrolFlow.acted(current, input.nowMs, input.state) else current,
                    ClickRequest(
                        decisionId = input.decisionId,
                        source = ClickSource.PATROL_STEP,
                        // 第 9 步点的是**行尾拜访图标**（名字与「申请」都不点）；
                        // 第 5 步点的是**识别给出的名字框本身**（那一屏没有锚点 ⇒ 审计用说明性名字）
                        anchorName = plan.anchorName ?: if (pickingServer) {
                            PatrolAnchors.SERVER_NAME
                        } else {
                            PatrolAnchors.FRIEND_VISIT
                        },
                        frameX = plan.frameX,
                        frameY = plan.frameY,
                    ),
                    if (pickingServer) {
                        "第 ${current.step.number} 步：点区服「$who」（${plan.detail}）"
                    } else if (!plan.advanceStep) {
                        "第 ${current.step.number} 步：${plan.detail}"
                    } else {
                        "第 ${current.step.number} 步：点「$who」那一行的拜访图标（${plan.detail}）"
                    },
                )

                // **滚一屏再找**（第 5 步跨屏查找，2026-09-29）：这一步**不是**点击，也**不调
                // [PatrolFlow.acted]** —— 那会把"滚了一屏"记成"这一步已经动过手"，于是采集层
                // 不再读文字（`namePlanOf`："本步已下过击就不再 OCR"），扫描当场卡死。
                is PatrolRunner.NamePlan.Scroll -> RoundResult(
                    state = current,
                    request = null,
                    note = "第 ${current.step.number} 步：目标不在这一屏，${plan.detail}",
                    swipe = SwipeRequest(
                        decisionId = input.decisionId,
                        source = ClickSource.PATROL_STEP,
                        // 审计用说明性名字：这一下动的是框选的「服务器列表区域」本身
                        anchorName = PatrolAnchors.SERVER_LIST_AREA,
                        fromFrameX = plan.fromFrameX,
                        fromFrameY = plan.fromFrameY,
                        toFrameX = plan.toFrameX,
                        toFrameY = plan.toFrameY,
                        durationMs = plan.durationMs,
                    ),
                )
            }
        }

        // ⑦b **第 7 步（进入农场）刚进大厅时先"让路"**（2026-10-01 加，真机两次误点事故）。
        //
        // ## 为什么只卡这一步
        //
        // 游戏行为（用户口述 + 两次真机实录）：**进游戏后先短暂显示大厅，紧接着弹出活动层**。
        // 落在这个窗口里的那一枪，同一个落点（`hall_farm` 帧点 945,1083，两次都一样）会有两种坏结果：
        // - `01:42` 那一枪被弹层**吞掉** ⇒ 画面回到大厅 ⇒ 白等 15 秒后中止；
        // - `03:26` 那一枪把游戏**点进了活动页** —— 我们没标定那一屏 ⇒ 认不出来 ⇒ 只能如实中止
        //   （**没有乱点**是对的，但这一轮已经废了）。
        // ⇒ 与其和弹层赛跑，不如**等那一下过去再点**：弹层由 FR-01 关掉、大厅仍在原地。
        //
        // ## 边界
        //
        // - **只影响第 7 步**：其它步一秒都不加（第 3/4/5/6 步没有"紧跟登录弹层"这个前提）；
        // - 起算点是 [PatrolFlow.State.stepEnteredAtMs] = **第 6 步通过那一刻**（= 大厅被认出来那一刻）；
        // - **不消耗重试次数**、也不是失败：只是"这一步现在还不该动手"（同 ⑧ 那句"不是要动手的地方"）；
        // - `stepEnteredAtMs == 0`（纯逻辑调用方 / 单测没给时刻）⇒ 窗口恒过 ⇒ 老行为不变。
        if (current.step == PatrolFlow.Step.ENTER_FARM &&
            input.nowMs - current.stepEnteredAtMs < ENTER_FARM_SETTLE_MS
        ) {
            return RoundResult(
                current,
                null,
                "第 ${current.step.number} 步：刚进大厅，先让它稳 ${ENTER_FARM_SETTLE_MS / 1000} 秒" +
                    "（这一步紧跟登录后的弹层，抢着点会点到弹层 / 活动页）" +
                    "（已等 ${PatrolFlow.waitedMs(current, input.nowMs) / 1000} 秒）",
            )
        }

        // ⑧ 这一步在当前画面上有没有要点的东西；没有 = 画面既不是"该动手的地方"也不是验证目标
        val action = PatrolAnchors.actionFor(current.step, input.state)
        if (action == null) {
            return waitOrAbort(
                current,
                input,
                "第 ${current.step.number} 步：画面是「${input.state.label}」，不是要动手的地方",
            )
        }

        // ⑨ 锚点定位：按 ID 精确取，不挑"最像的"
        val hit = input.anchors.firstOrNull { it.name == action.anchor }
        if (hit == null) {
            return waitOrAbort(
                current,
                input,
                "第 ${current.step.number} 步：锚点「${input.anchorLabel(action.anchor)}」没定位到（未标定或没命中）",
            )
        }

        // ⑩ 节流（FR-04 硬性要求 8 / NFR-06）：相邻点击 ≥300ms，不够就等下一轮
        if (input.nowMs - input.lastClickAtMs < PatrolFlow.CLICK_GAP_MS) {
            return RoundResult(current, null, "点击间隔未满 ${PatrolFlow.CLICK_GAP_MS}ms，等下一轮")
        }

        // ⑪ 点这一下（真正下发由 ClickDispatch 负责，本类只出判定）
        //
        // 记下**点击那一刻的画面**：下一击的观察窗口据此判断"画面到底换没换"（见 [screenMovedOn]）。
        // 日志上顺带标一句"提前结束"——否则真机上看不出观察窗口是否被缩短了（提速就靠这条可见）。
        val earlyExit = input.nowMs - current.lastActionAtMs < SETTLE_WAIT_MS
        return RoundResult(
            PatrolFlow.acted(current, input.nowMs, input.state),
            ClickRequest(
                decisionId = input.decisionId,
                source = ClickSource.PATROL_STEP,
                anchorName = action.anchor,
                frameX = hit.frameX,
                frameY = hit.frameY,
            ),
            "第 ${current.step.number} 步：点「${input.anchorLabel(action.anchor)}」，" +
                "之后应看到「${action.expect.label}」" +
                if (earlyExit) "（画面已切换，观察窗口提前结束）" else "",
        )
    }

    /**
     * 「这一步还没等到」（⑤ 刚点过等画面切换 / ⑧ 画面不是要动手的地方 / ⑨ 锚点没定位到）：
     * **等，不消耗重试次数**；只有超过本步时间预算（[PatrolFlow.STEP_TIMEOUT_MS]）才如实中止，并说明等了多久。
     *
     * **2026-09-24 真机修**（用户报"大厅点换号，退出登录到启动页后流程就中止了"）：
     * 第 3 步子步骤 = "点「logout_confirm_ok」，之后应看到启动页"。点下「确认退出」后游戏**重启**，
     * **4.9 秒**才回到启动页；这期间旧对话框已经消失 ⇒ 锚点自然定位不到 ⇒ 旧逻辑把它当"没命中"
     * 记一次重试 ⇒ 3 次重试在 3 秒内烧完 ⇒ **在「启动页」出现前 1.4 秒中止**。
     * 日志铁证：`10:21:07.523 已中止 —— 第 3 步：锚点「logout_confirm_ok」没定位到`
     * 紧接 `10:21:08.890 状态转移: 未知 -> 启动页`。
     *
     * 为什么"重试"与"再等一轮"在这里是同一件事：每一轮都会重新判定（画面换了、锚点又在了就照常点），
     * 所以次数上限没有额外价值，只该保留耐心 —— 与 ⑥（画面还没看清）口径统一，也让"刚点过就别再点"
     * 那条要求（FR-04-1：每一击之间都要验证）在慢切换下不会退化成连点。
     */
    /**
     * **这一步等超时**的原因（给日志与菜单里"已中止：…"那一行看）。
     *
     * ## 只陈述事实、**不猜成因**（用户口径，2026-09-30 真机验收问题 A）
     *
     * 原话："**不需要过于明确的指引 —— 核心都是长时间识别不到待识别元素，用户自己能看到画面的变化**"。
     * 所以这里只说三件事实：**等了多久** / **这一步在等哪一屏** / **现在看到的是什么**。
     * 不写"多半是风控被下线 / 请手动登录"这类判断：成因有很多（游戏加载 / 风控下线 / 走错界面 /
     * 采集停更），而它们对用户的处置**完全不同**，猜错就把人带偏 —— 用户抬眼看一眼游戏就知道了。
     *
     * ⚠ 期望取 [PatrolScenes.expectedState]（**真实的验证目标**），不要用 `step.label`：
     * 第 3 步的动作叫「退出登录」而验证目标是「启动页」、第 4 步动作「换区」而目标是「服务器列表」——
     * 两者不是一回事（真机日志里那句 `期望「启动页」` 就是这个来源）。
     */
    private fun timeoutReason(current: PatrolFlow.State, input: RoundInput): String {
        val waitedSec = PatrolFlow.waitedMs(current, input.nowMs) / 1000
        val expected = PatrolScenes.expectedState(current.step)?.label ?: current.step.label
        // ⚠ **说清是从哪一刻起算的**（2026-10-03 乙方案）：耐心现在从"最后一次真动手"起算（[PatrolFlow.budgetBase]），
        // 而"还没动手"的老口径仍从进入这一步起算 —— 两种口径的 N 秒含义不同，日志必须读得出是哪一种，
        // 否则真机上看到"等了 15 秒"会以为是同一件事（项目老规矩：日志要能读出用的是哪条判据）。
        val since = if (PatrolFlow.budgetBase(current) > current.stepEnteredAtMs) {
            "点下去之后等了"
        } else {
            "等了"
        }
        return "第 ${current.step.number} 步（${current.step.label}）：$since $waitedSec 秒仍未识别出" +
            "要等的画面（期望「$expected」，现在${PatrolScenes.describe(input.state)}）"
    }

    private fun waitOrAbort(current: PatrolFlow.State, input: RoundInput, what: String): RoundResult {
        val waitedSec = PatrolFlow.waitedMs(current, input.nowMs) / 1000
        return if (PatrolFlow.timedOut(current, input.nowMs)) {
            // 中止原因走 [timeoutReason]（三件事实，口径统一）；`what` 只留给"继续等"那条每轮提示
            val reason = timeoutReason(current, input)
            RoundResult(PatrolFlow.fail(current, reason, input.nowMs), null, "已中止：$reason")
        } else {
            RoundResult(current, null, "$what，继续等（已等 $waitedSec 秒）")
        }
    }

    /**
     * 画面是否已经**确实换了另一屏**（观察窗口据此提前结束，2026-09-21）。
     *
     * 三条都要成立，缺一不可：
     * 1. 有"点击那一刻的画面"可比（没记录 → 退化为老行为，纯逻辑调用方不受影响）；
     * 2. 当前画面**不是 `未知`** —— 正在加载时什么也判不出，交给 ⑥ 的耐心逻辑（等，不计失败）；
     * 3. 当前画面**不是点击时那一屏**，**且这一步在它上面确实有事可做**（[PatrolAnchors.actionFor] 非空）。
     *
     * 为什么第 3 条要带上"有事可做"：只要求"画面变了"的话，点到一半切到别的界面（点歪了）也会立刻放行，
     * 于是失败会被**提前**记进重试（原来是等满 2s 再记）。带上它之后，只有"画面变了、而且正是该动手的地方"
     * 才提前 —— **快只快在成功那条路上，失败路径的耐心一点没减**。
     */
    private fun screenMovedOn(current: PatrolFlow.State, input: RoundInput): Boolean {
        val clickedState = current.stateAtLastAction ?: return false
        if (input.state == UiState.UNKNOWN) return false
        if (input.state == clickedState) return false
        return PatrolAnchors.actionFor(current.step, input.state) != null
    }

    /**
     * **第 7 步刚进大厅时的"让路"时长**（ms，2026-10-01 加，见 ⑦b 的说明）。
     *
     * 取值依据（真机两次事故的实测）：大厅被认出 → 活动层出现 ≈ **+1.1~1.5 秒**；FR-01 再花 ≈1~2 秒关掉它。
     * 取 2.5 秒 = 覆盖"弹层出现"这一段并留一点余量；之后若弹层**已被识别**，② 分支还会继续拦住点击
     * （它等弹层确认消失）—— 两道叠起来，这一枪才不会落在弹层上。
     *
     * ⚠ 这是**稳健性**换**速度**的一处刻意取舍（用户 2026-10-01："速度维持现状"）：只让第 7 步慢 2.5 秒，
     * 换来"不会再点进活动页"⇒ 整轮不会废。要调就调这一个数。
     */
    private const val ENTER_FARM_SETTLE_MS = 2_500L
}
