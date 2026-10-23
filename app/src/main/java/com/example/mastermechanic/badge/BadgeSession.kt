package com.example.mastermechanic.badge

import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolFlow

/**
 * 当前**正在跑的那次立牌收集任务**（临时需求 temp/badge-share-1021，2026-10-21）。
 *
 * 与 `PatrolSession` 是**互斥的两个会话**：同一时刻只允许一条流程（启动前一方必须 stop 另一方）。
 * 为什么不合进 `PatrolSession`：它的 `State` 绑死 `PatrolFlow`，而本任务是"换号段 + 立牌支线"的两阶段
 * 组合；混进去会让巡查的类型被临时需求的字段污染（FR-T6 要求整支可删）。
 *
 * ## 线程
 *
 * - **主线程**（悬浮窗）开始 / 停止；
 * - **帧线程**（`CaptureService`）每轮读快照 → 编排 → 写回；
 * - 因此一律 `@Volatile` + **整引用替换**（[Task] 不可变），同 [PatrolSession] 的理由；
 * - [generation] 同款：取快照时记住代次，写回时对齐 —— 否则停止后旧快照会把流程"复活"。
 *
 * ## 两阶段（[Phase]）
 *
 * - [Phase.SWITCH]：复用 `PatrolFlow(Range.SWITCH_ONLY)` 换到目标区服并进大厅（第 3~6 步）；
 * - [Phase.COLLECT]：换号段跑到大厅后交接给 [BadgeFlow]（立牌五屏）。
 *
 * 一个区服收尾（[finishServer]）⇒ 从 [Task.queue] 取下一个；队列空了 ⇒ 整个任务完成。
 */
object BadgeSession {

    /**
     * 「还在确认当前是哪个区服」这句给用户看的话（2026-10-23 用户报"与实际不符"）。
     *
     * 顶部标签与菜单进度行**共用这一份**：两处各写一遍，迟早会一个说"确认区服"另一个还在说"退出登录"。
     */
    const val IDENTITY_TEXT = "确认当前区服"

    /**
     * 「已完成 / 已中止」在**顶部标签**上还留多久（2026-10-23 用户口径："几秒就够，不要一直挂着"）。
     *
     * 与换号（巡查）那边 `PatrolResultSignal` 的 30 秒窗口同一个意思，只是这里按用户口径缩短：
     * 立牌任务结束得快，顶着一句结果话挡视线没有收益。
     * **菜单不受这个窗口限制**：中止后要能在展开菜单里回看"停在第几步、为什么停"。
     */
    const val OUTCOME_LABEL_MS = 5_000L

    private const val TAG = "MM-Badge"

    /** 任务阶段。 */
    enum class Phase {
        /** 换号段（复用巡查第 3~6 步）。 */
        SWITCH,

        /** 立牌支线（[BadgeFlow]）。 */
        COLLECT,
    }

    /** 整个任务（跨区服）的结局。 */
    enum class Outcome { RUNNING, FINISHED, FAILED }

    /** 一次任务（不可变快照）。 */
    data class Task(
        val phase: Phase,
        /** 换号段的进度（[Phase.SWITCH] 时非空）。 */
        val switch: PatrolFlow.State? = null,
        /** 立牌支线的进度（[Phase.COLLECT] 时非空）。 */
        val collect: BadgeFlow.State? = null,
        /** 还没跑的区服（**含当前这个**，顺序 = 清单顺序）。 */
        val queue: List<String> = emptyList(),
        /** 当前区服名（= 小号名，记录时用它；不读游戏昵称）。 */
        val current: String? = null,
        /** 已经收完的区服数（日志 / 展示用）。 */
        val done: Int = 0,
        /**
         * 这一轮是不是**新开的一轮**（上一轮已结束 / 今天还没跑过，见 `BadgeTask.RoundMarker`）。
         *
         * 2026-10-23 用它决定"人已经在某个区服时，从它开始跑"合不合法：只有新开一轮才敢把队列
         * **轮换**到当前区服（集合不变、只换起点）；续跑那一轮已经按盘上游标轮换过，
         * 再按"当前区服"轮换会跳过中间没跑的区服。
         */
        val fullRound: Boolean = false,
        /**
         * **本轮已经收过的区服**（2026-10-23 用户口径）：续跑时据此跳过，保证一轮严格单调向前、
         * 不会"跑到最后又回头重收第 1 个"。与盘上的 `BadgeTask.RoundMarker.done` 同步。
         */
        val roundDone: Set<String> = emptySet(),
        /**
         * 「是不是已经在该区服」**判过了**（2026-10-23）。
         *
         * 一次任务只判一次：判完（无论结论）就置真，免得帧线程每轮都去 OCR 昵称。
         * 新一轮任务 / 换下一个区服都是新快照 ⇒ 重新判（换区服后确实要重新判）。
         */
        val identityChecked: Boolean = false,
        val outcome: Outcome = Outcome.RUNNING,
        val reason: String? = null,
    ) {
        val isRunning: Boolean get() = outcome == Outcome.RUNNING
        val finished: Boolean get() = outcome == Outcome.FINISHED
        val failed: Boolean get() = outcome == Outcome.FAILED

        /** 已暂停（游戏不在前台 / 用户点过暂停）：悬浮窗据此决定要不要显示「继续」。 */
        val isPaused: Boolean get() = switch?.paused == true || collect?.paused == true

        /**
         * 还在**确认"是不是已经在这个区服"**（2026-10-23 用户报障："与实际不符"）。
         *
         * 这一小段（约 0.6 秒，等昵称第二次读数）里我们**还没决定要不要退出登录**，
         * 所以顶部标签与菜单都**不该说「退出登录」** —— 人可能就在目标区服上，一步都不用动。
         */
        val pendingIdentity: Boolean
            get() = phase == Phase.SWITCH && !identityChecked && switch?.step == PatrolFlow.Step.LOGOUT

        /** 当前阶段的步骤说明（悬浮窗状态行用）。 */
        val stepText: String
            get() = when (phase) {
                Phase.SWITCH -> switch?.let { "换号第 ${it.step.number} 步（${it.step.label}）" } ?: "换号"
                Phase.COLLECT -> collect?.let { "立牌第 ${it.step.number} 步（${it.step.label}）" } ?: "立牌"
            }
    }

    @Volatile
    private var state: Task? = null

    /**
     * 任务**结束**（完成 / 中止）的时刻（0 = 还没结束）：顶部标签据此只留 [OUTCOME_LABEL_MS] 几秒。
     * 与 [state] 一起只在帧线程 / 主线程读，标量本身 `@Volatile` 够用。
     */
    @Volatile
    private var endedAtMs = 0L

    /** 代次（开始 / 停止各递增一次，见 [commit]）。 */
    @Volatile
    private var generation: Long = 0L

    /** 上一次点击的时刻（0 = 本次任务还没点过）：与 `PatrolSession.lastClickAtMs` 同义。 */
    @Volatile
    var lastClickAtMs: Long = 0L

    /**
     * **本轮这个区服**真正读到并落盘的记录（2026-10-23 用户口径）。
     *
     * 用途：一个区服收集结束时，该区服在池子里只保留这一轮收到的牌（旧码 / 旧分数清掉）。
     * 与 `BadgeStore.upsert` 的关系：upsert 是"写进去"（可能和旧记录合并），
     * 这里是"这一轮到底读到了什么"的清单，收尾时用它替换掉该区服的旧行。
     */
    @Volatile
    var roundRecords: List<BadgeRecord> = emptyList()
        private set

    /** 最近一句给人看的话（悬浮窗顶部标签用）。 */
    @Volatile
    var lastNote: String? = null
        private set

    /**
     * **已经读过的卡**（多实例消歧用，红线 3）：键 = 分享按钮中心的帧坐标 `"x,y"`。
     *
     * 为什么按坐标而不是"第几张"：卡片顺序不固定（真机两帧里先后不同），而**位置是稳定的** ——
     * 关闭复制分享回到「我的立牌」后，同一个按钮仍在同一处；点过的那一处再命中就说明"关闭没生效"。
     * 采集层读写、每个区服开始时清空。
     */
    @Volatile
    var cardsRead: Set<String> = emptySet()
        private set

    /** 当前任务；null = 没有在跑的立牌任务。 */
    val current: Task? get() = state

    /** 当前代次（取快照的一方**必须先读它、再读 [current]**，同 `PatrolSession.currentGeneration`）。 */
    val currentGeneration: Long get() = generation

    /** 是否正在跑（与 [PatrolSession] 互斥，由启动方保证）。 */
    val isActive: Boolean get() = state?.isRunning == true



    /** 是否已暂停（见 [Task.isPaused]）。 */
    val isPaused: Boolean get() = state?.isPaused == true

    /**
     * 开始一次任务。
     *
     * @param queue 本次要跑的区服名（**已按清单顺序、已剔除今日收集过的**，见 [BadgeTask]）
     * @param switch 换号段的起始进度（`PatrolFlow.start(scene, SWITCH_ONLY)`）
     */
    fun start(
        queue: List<String>,
        switch: PatrolFlow.State,
        nowMs: Long,
        fullRound: Boolean = false,
        roundDone: Set<String> = emptySet(),
    ): Task {
        lastClickAtMs = 0L
        endedAtMs = 0L
        cardsRead = emptySet()
        roundRecords = emptyList()
        val task = Task(
            phase = Phase.SWITCH,
            switch = switch,
            queue = queue,
            current = queue.firstOrNull(),
            fullRound = fullRound,
            roundDone = roundDone,
        )
        // 先写 state 再递增代次（帧线程"先读代次、后读 state"，同 `PatrolSession.start`）
        state = task
        generation++
        lastNote = "换到「${task.current}」：${task.stepText}"
        MmLog.i(TAG, "立牌任务开始：共 ${queue.size} 个区服，先换到「${task.current}」")
        return task
    }

    /** 把编排后的进度写回；[expectedGeneration] 不匹配 ⇒ 整轮作废（用户已停止 / 又开了一轮）。 */
    fun commit(next: Task?, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        state = next
        if (next == null || !next.isRunning) {
            lastClickAtMs = 0L
        }
    }

    /**
     * 换号段跑完（已在大厅）⇒ 交接给立牌支线。
     *
     * @param note 换一句日志与状态行（2026-10-23：**不换号直接进支线**那条路要说明原因，
     *   否则日志里只看到"换号段完成"会误以为真的换过号）
     */
    fun toCollect(nowMs: Long, note: String? = null): Task? {
        val task = state ?: return null
        if (!task.isRunning || task.phase != Phase.SWITCH) return task
        val next = task.copy(
            phase = Phase.COLLECT,
            switch = null,
            collect = BadgeFlow.start(nowMs),
            identityChecked = true,
        )
        state = next
        lastNote = note ?: "「${task.current}」：进入立牌活动"
        MmLog.i(TAG, note ?: "「${task.current}」：换号段完成（已在大厅）⇒ 开始收立牌")
        return next
    }

    /**
     * **人已经在某个区服**时就从这个区服接着跑（2026-10-23 用户报障）。
     *
     * 场景：整轮重跑（今天全都收过）中途停止后重新发起 —— 队列本来永远从清单第一个开始，
     * 于是明明人在第 2 个区服，也要退出去重登到第 1 个。
     *
     * 这里做的是**顺序轮换**（集合不变、只换起点）：`[A,B,C]` + 人在 B ⇒ `[B,C,A]`，
     * 既不丢任何一个区服，也不必为了"从第一个开始"白重登一次。
     * 只在整轮重跑时用（见 [Task.fullRound]）：有未收集的区服时轮换会把排在前面的漏掉。
     */
    fun continueFrom(server: String, nowMs: Long): Task? {
        val task = state ?: return null
        if (!task.isRunning) return task
        roundRecords = emptyList()
        val rotated = rotateTo(task.queue, server)
        val next = task.copy(
            phase = Phase.COLLECT,
            switch = null,
            collect = BadgeFlow.start(nowMs),
            queue = rotated,
            current = rotated.firstOrNull() ?: server,
            identityChecked = true,
        )
        state = next
        lastNote = "「$server」：从这里接着收"
        MmLog.i(
            TAG,
            "已在该区服「$server」⇒ 队列从它开始（整轮重跑，顺序轮换）：${rotated.joinToString("、")}",
        )
        return next
    }

    /**
     * 把 [server] 转到队列首位（纯逻辑，便于单测）。
     *
     * 不在队列里 / 本来就在首位 ⇒ **原样返回**（不猜、不动别人的顺序）。
     */
    internal fun rotateTo(queue: List<String>, server: String): List<String> {
        val index = queue.indexOf(server)
        return if (index <= 0) queue else queue.drop(index) + queue.take(index)
    }

    /**
     * 标记"是不是已经在该区服"**已经判过**（2026-10-23）。
     *
     * 判不出（缺昵称锚点 / 清单没填角色名 / 读数不一致）时也要标：这一轮照常换号即可，
     * 不必每帧重试 —— 帧线程一轮最多 200ms，重试只会把 OCR 成本叠上去。
     */
    fun markIdentityChecked(nowMs: Long): Task? {
        val task = state ?: return null
        if (task.identityChecked) return task
        val next = task.copy(identityChecked = true)
        state = next
        return next
    }

    /**
     * 一个区服从立牌支线收尾（已回到大厅）⇒ 记收集标记并换下一个区服。
     *
     * @param marked 已落盘的收集标记结果（由 [BadgeTask] 写，本对象只推进内存状态）
     * @return 新的任务快照；队列空 ⇒ 返回 `finished` 的任务（调用方据 [Task.finished] 收尾）
     */
    fun finishServer(marked: Boolean, nowMs: Long): Task? {
        val task = state ?: return null
        if (!task.isRunning || task.phase != Phase.COLLECT) return task
        // 这个区服收完了 ⇒ 本轮记录已由调用方写进池子（`BadgeStore.keepOnlyRound`），清空备用
        roundRecords = emptyList()
        val rest = task.queue.drop(1)
        val done = task.done + 1
        // **本轮已收集合**：续跑时据此跳过（盘上标记也用它）
        val roundDone = task.roundDone + listOfNotNull(task.current)
        if (rest.isEmpty()) {
            val next = task.copy(
                phase = Phase.COLLECT,
                collect = null,
                queue = emptyList(),
                done = done,
                roundDone = roundDone,
                outcome = Outcome.FINISHED,
            )
            state = next
            endedAtMs = nowMs
            lastNote = "立牌收集完成：共 $done 个区服"
            MmLog.i(
                TAG,
                "立牌任务完成：共 $done 个区服（本轮结束，不会再自动重跑）" +
                    if (marked) "" else "（⚠ 收集标记没写成功，重跑会重复收集）",
            )
            return next
        }
        // 下一个区服：起点在**大厅**（上一个区服已返回大厅）⇒ PatrolFlow 从「退出登录」开始
        val target = rest.first()
        val switch = PatrolFlow.start(
            PatrolFlow.Scene.LOBBY,
            PatrolFlow.Range.SWITCH_ONLY,
            nowMs,
        )
        if (switch == null) {
            val next = task.copy(
                outcome = Outcome.FAILED,
                reason = "换到「$target」前算不出起点的第几步（不在大厅？）",
            )
            state = next
            endedAtMs = nowMs
            lastNote = next.reason
            return next
        }
        val next = task.copy(
            phase = Phase.SWITCH,
            switch = switch,
            collect = null,
            queue = rest,
            current = target,
            done = done,
            roundDone = roundDone,
        )
        state = next
        lastClickAtMs = 0L
        cardsRead = emptySet()
        lastNote = "换到「$target」：${next.stepText}"
        MmLog.i(TAG, "「${task.current}」收完 ⇒ 换到下一个区服「$target」（还剩 ${rest.size} 个）")
        return next
    }

    /**
     * 任务级中止（FR-T5：停下并说清原因，**不盲跳下一个区服**）。
     *
     * **已中止就直接返回**（真机教训 2026-10-22）：帧线程每轮都会读到同一个失败结论，
     * 不去重就会每秒刷好几条"中止"、并把已经中止的任务重新写一遍。
     */
    fun fail(reason: String, nowMs: Long = 0L): Task? {
        val task = state ?: return null
        if (!task.isRunning) return task
        val next = task.copy(outcome = Outcome.FAILED, reason = reason)
        state = next
        endedAtMs = nowMs
        lastNote = "已中止：$reason"
        MmLog.w(TAG, "立牌任务中止：$reason")
        return next
    }

    /**
     * **顶部标签**该不该还显示这个任务（2026-10-23 用户口径："已完成 / 已中止只显示几秒"）。
     *
     * 跑着 ⇒ 显示；跑完 ⇒ 只在 [OUTCOME_LABEL_MS] 内显示，之后当作"没有任务"（标签自己摘掉）。
     * 与巡查的差别：那边用 `PatrolResultSignal` 的 30 秒窗口，这里按用户口径收到 5 秒。
     */
    fun displayTask(nowMs: Long): Task? {
        val task = state ?: return null
        return if (outcomeLabelVisible(task.isRunning, endedAtMs, nowMs)) task else null
    }

    /**
     * 标签还该不该显示这次任务（纯逻辑，便于单测）：
     * 跑着 ⇒ 是；已经结束 ⇒ 只有结束后 [OUTCOME_LABEL_MS] 内才是。
     * [endedAtMs] ≤ 0（没记下结束时刻的旧路径）⇒ 照旧显示，宁可多说一句也不要把结果吞掉。
     */
    internal fun outcomeLabelVisible(isRunning: Boolean, endedAtMs: Long, nowMs: Long): Boolean =
        isRunning || endedAtMs <= 0L || nowMs - endedAtMs < OUTCOME_LABEL_MS

    /**
     * **菜单**该不该还显示这个任务：中止后**一直保留**（用户要能回看"停在第几步、为什么停"），
     * 其余口径与 [displayTask] 相同（跑着 / 刚完成的那几秒）。
     */
    fun menuTask(nowMs: Long): Task? {
        val task = state ?: return null
        if (task.failed) return task
        return displayTask(nowMs)
    }

    /** 记下"点击已下发"（节流用，与 `PatrolSession.markClick` 同义）。 */
    fun markClick(nowMs: Long) {
        lastClickAtMs = nowMs
    }

    /** 记下这一句给人看的话。 */
    fun note(text: String?) {
        lastNote = text
    }

    /** 记一张"已读过的卡"（键 = 分享按钮中心帧坐标，见 [cardsRead]）。 */
    fun markCardRead(key: String) {
        cardsRead = cardsRead + key
    }

    /** 记一条本轮读到的记录（同一分享码只留一条；收尾时用它替换该区服的旧数据）。 */
    fun markRecord(record: BadgeRecord) {
        if (roundRecords.any { it.code == record.code }) {
            roundRecords = roundRecords.map { if (it.code == record.code) record else it }
        } else {
            roundRecords = roundRecords + record
        }
    }

    /** 停止：什么都不跑，清空全部会话状态。 */
    fun stop() {
        val was = state
        state = null
        endedAtMs = 0L
        lastClickAtMs = 0L
        lastNote = null
        cardsRead = emptySet()
        roundRecords = emptyList()
        // **最后**递增代次（同 `PatrolSession.stop` 的理由：先 ++ 再清 state 会漏一种竞态）
        generation++
        if (was != null) MmLog.i(TAG, "立牌任务停止：停在第 ${was.done + 1} 个区服「${was.current}」")
    }
}
