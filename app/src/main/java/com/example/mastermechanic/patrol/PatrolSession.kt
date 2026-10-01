package com.example.mastermechanic.patrol

import com.example.mastermechanic.log.MmLog

/**
 * 当前**正在跑的那一次流程**（M4-T4-4b）。
 *
 * 为什么需要一个独立的小对象：进度被两个线程碰 ——
 * - **帧线程**（`CaptureService`）：每轮读进度 → `PatrolRunner.onRound` → 写回新进度 / 记点击时刻；
 * - **主线程**（悬浮窗菜单）：开始 / 继续 / 停止。
 *
 * 因此共享状态一律用 `@Volatile` + **整引用替换**（`PatrolFlow.State` 是不可变 data class），
 * 不做就地修改——帧线程与主线程的读写各自看到的是某一份完整快照，不会读到改了一半的进度。
 *
 * 这里是**唯一**存放"进行中的流程"的地方，别处不得再存一份（两份就会漂移）。
 */
object PatrolSession {

    private const val TAG = "MM-Patrol"

    @Volatile
    private var state: PatrolFlow.State? = null

    /**
     * **代次**：每次「开始 / 停止」递增一次（2026-09-21 真机 bug 修）。
     *
     * ## 为什么非要有它（日志实录）
     *
     * ```
     * 01:15:46  停止：停在第 8 步          ← 用户在悬浮窗按了停止
     * 01:15:53  跑号: 第 8 步通过 → 第 9 步  ← 7 秒后流程自己又往前走了
     * 01:16:13  跑号: 第 9 步停下：没指定要拜访的好友名
     * ```
     *
     * 原因：帧线程读 [current] 拿到的是**停止前的快照**，算完（`PatrolRunner.onRound`）
     * 无条件 [commit] 回去 —— 把主线程刚清掉的流程又写了回来，**「停止」等于没按**。
     * 后果不止"停不掉"：`watching` 一直非空（按已停止那一步的画面搜），
     * 待命期的画面判定整个失效 → 状态滞留在旧结论（真机表现：关掉好友列表回农场后，
     * 菜单仍被拦"现在停在「好友列表」"）。
     *
     * ## 用法：拿快照时一并记住代次，写回时对齐
     *
     * ```kotlin
     * val snapshot = PatrolSession.current
     * val generation = PatrolSession.currentGeneration
     * ...  // 期间用户可能按了停止
     * PatrolSession.commit(next, generation)   // 代次变了 ⇒ 本轮结论作废，不写回
     * ```
     */
    @Volatile
    private var generation: Long = 0L

    /** 上一次点击的时刻（0 = 本次执行还没点过）：编排层据此判节流。 */
    @Volatile
    var lastClickAtMs: Long = 0L

    /** 最近一句给人看的话（等待 / 点了什么 / 为什么停）——T4-5 会把它挂到悬浮窗上。 */
    @Volatile
    var lastNote: String? = null
        private set

    /**
     * **最近一次"点击被拒"的说明**（2026-09-30 加，progress 第 325 条 b）：非空 = 这一步的点击被门禁拒了
     * （连续 ≥3 次才写，见 `CaptureService`），菜单状态行**优先显示它**。
     *
     * ## 为什么必须单独存一份，而不是塞进 [lastNote]
     *
     * [lastNote] **每一轮都被编排层重写**（`PatrolSession.note(outcome.note)`）⇒ 塞进去只能活 200~500ms，
     * 用户打开菜单时早没了。而"为什么它不动"恰恰是用户**事后**才会问的问题。
     *
     * 清空时机：真的下发成功一枪 / 换了一步 / 流程开始或停止（见 [clearDeniedNote] 与 [start] / [stop]）。
     */
    @Volatile
    var deniedNote: String? = null
        private set

    /** 记下一句"点击被拒"的说明（粘性：不会被每轮 [note] 覆盖）。 */
    fun noteDenied(text: String) {
        deniedNote = text
    }

    /** 清除"点击被拒"的说明（真的发出去了 / 换了一步 / 流程开始或停止）。 */
    fun clearDeniedNote() {
        deniedNote = null
    }

    /** 当前进度；null = 没有在跑的流程。 */
    val current: PatrolFlow.State? get() = state

    /**
     * 当前代次（见 [generation]）。取快照的一方**必须先读它、再读 [current]**：
     * 反过来的话，恰好夹在中间的那次「停止」会让快照配上一个**新的**代次，写回就被误当合法。
     */
    val currentGeneration: Long get() = generation

    /**
     * 本次要拜访的**好友名**（2026-09-21，第 9 步按名称定位要用）；null = 没指定。
     *
     * 与 [state] 同级共享（主线程写入、帧线程读取），所以同样是 `@Volatile` 整引用替换。
     * 名字来自用户请求（[PatrolRequestSignal.Request.friendName]），**不做任何改写** ——
     * 屏幕上的名字必须逐字符对得上（红线 3）。
     */
    @Volatile
    var targetFriend: String? = null
        private set

    /**
     * 本次要换到的**区服名**（2026-09-24，第 5 步按名称定位要用）；null = 没指定。
     *
     * 三种来路，**都不改写**（屏幕上要逐字符对得上，红线 3）：
     * - 用户点清单里某一行 ⇒ [PatrolRequestSignal.Request.serverName] 原样带过来；
     * - 预设「固定区服」⇒ 同上；
     * - **「换号 → 下一个」/ 预设「顺序轮换」⇒ 由 `ServerRotation` 按游标算出来**（见
     *   [targetServerFromRotation]）；清单为空 / 读不了时**解析不出来**，这时第 5 步如实停下说明
     *   （绝不猜一个区服顶上去）。
     */
    @Volatile
    var targetServer: String? = null
        private set

    /**
     * 本次的 [targetServer] 是不是**顺序轮换算出来的**（2026-09-24）。
     *
     * 用途只有一个：第 5 步**真的换成功**之后（流程走进第 6 步）才把游标推进到它
     * （`ServerRotation.saveCursor`）。**成功才推进**，所以中途失败 / 中止时游标不动 ——
     * 用户重试仍指向同一个区服，不会"悄悄跳过一个号"。
     */
    @Volatile
    var targetServerFromRotation: Boolean = false
        private set

    /** 记下本次的目标好友（[start] 时一并写入；[stop] 时清空）。 */
    fun setTargetFriend(name: String?) {
        targetFriend = name
    }

    /** 是否正在跑（暂停中不算）。 */
    val isActive: Boolean get() = state?.isRunning == true

    /**
     * 现在点「继续」会不会有用（T4-5）：中止或暂停中才算 —— 悬浮窗据此决定要不要显示那一项、
     * 清空态要不要把手柄变个色。判据在 [PatrolStatus.canResume]（与 [resume] 同一处，不会漂移）。
     */
    val canResume: Boolean get() = PatrolStatus.canResume(state)

    /**
     * 开始一次执行；**起点不成立时返回 null**（调用方据此提示用户，绝不猜测性启动，红线 2）。
     */
    fun start(
        scene: PatrolFlow.Scene,
        range: PatrolFlow.Range,
        nowMs: Long = 0L,
        friendName: String? = null,
        serverName: String? = null,
        serverFromRotation: Boolean = false,
    ): PatrolFlow.State? {
        lastClickAtMs = 0L
        targetFriend = friendName
        targetServer = serverName
        targetServerFromRotation = serverFromRotation
        val started = PatrolFlow.start(scene, range, nowMs)
        // 先写 state 再递增代次：帧线程"先读代次、后读 state" ⇒ 无论夹在哪一步都安全
        //（夹在中间时它读到的是"新代次 + 新 state"，写回会因为代次不同而被丢弃）
        state = started
        generation++
        lastNote = started?.let { "第 ${it.step.number} 步：等待画面" }
        // 上一段留下的"点击被拒"说明不跨流程（否则新一局一开始菜单就写着旧的拒绝原因）
        deniedNote = null
        return started
    }

    /**
     * 把编排层推进后的进度写回（传 null = 流程结束 / 已停止）。
     *
     * [expectedGeneration] = 取快照时读到的 [currentGeneration]。**不匹配就整轮作废**：
     * 说明这一轮算的期间，用户已经开始 / 停止了另一次执行 —— 这时把旧结论写回去就是
     * 把已经停掉的流程"复活"（真机 bug，见 [generation] 的说明）。
     */
    fun commit(next: PatrolFlow.State?, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        // 诊断（2026-09-22）：**从"没有流程"变成"有流程"**要留一笔。
        //
        // 真机日志里出现过这一幕：`停止：当前没有进行中的流程` → 0.76 秒后
        // `跑号: 第 8 步通过 → 第 9 步`。若这一行也出现，说明存在**别的写回路径**
        //（本类的代次保护只挡"旧轮次写回"，挡不住"谁又把它设成了非 null"）。
        if (state == null && next != null) {
            MmLog.w(
                TAG,
                "流程被写回：本已停止（代次 $expectedGeneration），却被写回第 ${next.step.number} 步",
            )
        }
        state = next
        if (next == null) {
            lastClickAtMs = 0L
            lastNote = null
        }
    }

    /** 记下这一句给人看的话。 */
    fun note(text: String?) {
        lastNote = text
    }

    /** 记下"点击已下发"（节流用）。 */
    fun markClick(nowMs: Long) {
        lastClickAtMs = nowMs
    }

    /** 用户点「继续」（FR-05）：从失败 / 暂停的那一步接着来；没有可继续的返回 false。 */
    fun resume(nowMs: Long = 0L): Boolean {
        val s = state ?: return false
        // 准入条件与「菜单要不要显示这一项」共用 [canResume]：分成两处写会出现"点了没用"的死按钮
        if (!canResume) return false
        state = PatrolFlow.resume(s, nowMs)
        lastNote = "已继续：第 ${state?.step?.number} 步"
        return true
    }

    /** 停止：什么都不跑，且清空点击时刻与提示。 */
    fun stop() {
        state = null
        lastClickAtMs = 0L
        lastNote = null
        deniedNote = null
        targetFriend = null
        targetServer = null
        targetServerFromRotation = false
        // **最后**递增代次：帧线程"先读代次、后读 state" ⇒ 它读到的组合必然是
        // ①旧代次 + null（看清了，不写回）、或②新代次 + null（不写回）、
        // 或③旧代次 + 旧 state（写了也会在 commit 里被代次挡掉）。
        // 反过来（先 ++ 再清 state）会漏一种：帧线程读到**新代次 + 旧 state**，写回被误当合法。
        generation++
    }
}
