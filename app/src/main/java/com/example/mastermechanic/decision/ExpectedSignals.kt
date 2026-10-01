package com.example.mastermechanic.decision

/**
 * 期望集合来源（T2-1，识别层新口径）：每轮由外部注入「**这一步预期出现的信号名集合**」。
 *
 * 用户口径（2026-09-13）：取消「全扫 / 补扫」，改为预期集合驱动——每轮只搜注入的集合，
 * **不外扩、不兜底**：集合内全不命中就是「这一步没等到」（连续 [UiStateMachine.LEAVE_STREAK]
 * 次未命中 → 转「未知」）。识别层不再负责"发现新状态"，那是流程的职责。
 *
 * 两个实现口径：
 * - [ALL]：显式声明全集（M1 演练 / 回归口径，等价改动前的「全扫」，但**不含**"未标定即全扫"这条路径）；
 * - [PopupWatchExpectedSignals]：FR-01 生产路径（每轮只搜活动弹窗自己的标志记录，**无阶段**，T2-5）。
 */
interface ExpectedSignals {

    /**
     * 本轮期望搜索的信号名。
     *
     * 返回**空集 = 本轮不搜**：不产生任何判定记录，也不消耗滞回计数（该轮对状态机如同不存在）。
     *
     * @param known 已标定信号名（真正可匹配的全部）；期望里出现未标定的名字时由
     *   [ActiveSignalSelector] 剔除，避免把"没有的能力"记成本轮搜索量
     */
    fun expected(known: Set<String>): Set<String>

    /**
     * 本轮判定结果（仅前台轮调用一次；冻结轮不调用——冻结轮如同不存在，FR-09）。
     * 固定集合的实现不需要关心；阶段型实现据此推进阶段。
     */
    fun onRound(hits: Set<UiState>) = Unit

    companion object {

        /** M1 演练 / 回归口径：**显式声明全集**（调用方未注入期望时的默认）。 */
        val ALL: ExpectedSignals = object : ExpectedSignals {
            override fun expected(known: Set<String>): Set<String> = known
        }

        /** 空集合 = 不搜（无流程注入的时刻）。 */
        val NONE: ExpectedSignals = object : ExpectedSignals {
            override fun expected(known: Set<String>): Set<String> = emptySet()
        }
    }
}

/** 固定集合（单测 / 演练显式声明）：每轮都搜同一组信号名（未标定的名字仍会被剔除）。 */
class FixedExpectedSignals(private val names: Set<String>) : ExpectedSignals {

    override fun expected(known: Set<String>): Set<String> = names

    companion object {

        fun of(vararg names: String): FixedExpectedSignals = FixedExpectedSignals(names.toSet())
    }
}

/**
 * 弹窗守护期望集合（T2-5 / FR-01）：运行期每轮**只搜活动弹窗自己的标志记录**，**没有阶段**。
 *
 * **为什么删掉"阶段"**：旧实现用「命中启动页 → 进弹窗期；命中大厅 → 收回」推断弹窗是否存在，
 * 但 2026-09-13 现场日志（`_tmp_check/mm-dump-all.txt`）证明这条推断不可靠——
 * 弹窗**物理上一定在大厅之后**出现，而程序**可能未及时识别到大厅**（大厅一闪即被弹窗盖住，
 * 甚至未达滞回确认门槛），于是窗口在弹窗出现**之前**就已关掉：当天 17:54:59 命中大厅收回窗口，
 * 17:56~18:02 弹窗停在屏幕上 2.5 分钟，程序每轮只搜 1 条 `launch_start`，**一眼都没搜过它**。
 *
 * 结论：判定"弹窗该不该管"的依据只能是"**弹窗自己出现了没有**"，不能依赖"是否已识别到大厅"。
 *
 * 因此本实现**每轮恒定**搜活动弹窗的全部标志记录（多条样式记录 → 全部纳入，任一命中即命中，§2.1），
 * [onRound] 不再参与任何决策——结构上消除了"一次误判永久关闭搜索"这个脆弱点。
 *
 * 代价（如实记录）：守护期待命**不再认识启动页 / 大厅** → 状态恒显「未知」（日志与悬浮窗可读性下降）；
 * 守护模式下除 FR-01 外不产生任何点击（需求 §4-5），功能无损失。
 * 收益：待命期 4 条（≈490ms，其中大窗口 `launch_start` 单轮 ≈283ms）→ **3 条（≈207ms）**。
 *
 * ## 2026-09-20 补记：本类自己不管"起点画面"，但别的地方必须管
 *
 * 上面那条代价在真机上撞出了**死锁**：状态恒「未知」→ 而"用户点菜单那一刻起点成不成立"
 * 必须先知道画面（`PatrolFlow.firstStep`）→ **菜单点了永远不启动**。
 * 修法不在本类（本类只管弹窗那一组，恒定不变），而在包着它的
 * [PatrolDrivenExpectedSignals]：**待命期额外搜"能当起点的 5 屏"**（`standbyStates`）。
 *
 * 成本账随之变了 —— 但**不是因为多搜那几条，而是因为状态终于能收敛**：
 * - 修之前：待命期状态恒「未知」→ `RoundResult.settled` 恒 false → **节流永远停在 200ms 快档**
 *   （真机日志里一条「节流间隔切换」都没有，1 条弹窗标志 ≈80ms/轮，单核占用 ≈29%）；
 * - 修之后：状态稳定命中 → 降到 1000ms 慢档（+5 条 ≈ +350~500ms/轮，单核占用 ≈33%）。
 *
 * 结论：**净代价约 +4 个百分点（单核）**，买的是"点菜单即刻判定起点、零延迟、同步"。
 *
 * 产物里没有活动弹窗记录（未标定）→ 空集 = **不搜**（不会退化成全扫）。
 *
 * ## 2026-09-29：从"只管活动弹窗"扩到**全部遮挡屏**（FR-02 恢复）
 *
 * 用户口径：新手引导 / 新手大厅**只在新手号出现**、登录后出现在活动弹窗之前，要"看到就点掉"。
 * 与活动弹窗同理 —— 能不能管它们，只取决于"**它们自己出现了没有**"，不依赖任何阶段推断。
 * 所以这里改成取 [UiState.guardedOverlays]（活动弹窗 + 新手两页）**各自的全部标志名并集**。
 *
 * ⚠ **名字里的 `Popup` 是历史叫法**（改名的成本是 4 个实现文件 + 3 个测试 + 若干文档里的引用，
 * 与其为名字翻一遍，不如在这里说清楚）：本类现在覆盖的是**遮挡屏守护集合**，不只是弹窗。
 */
class PopupWatchExpectedSignals private constructor(
    /** 守护遮挡屏的全部标志记录（产物声明顺序无关，作为集合使用）。 */
    private val popupNames: Set<String>,
) : ExpectedSignals {

    override fun expected(known: Set<String>): Set<String> = popupNames

    companion object {

        /**
         * 由标定产物的状态—信号规则构造：取 [UiState.guardedOverlays] **每一个**状态在产物里的
         * **全部**标志名（任一命中即命中，§2.1）。
         */
        fun fromRules(rules: List<SignalStateMapping.Rule>): PopupWatchExpectedSignals =
            PopupWatchExpectedSignals(
                popupNames = rules
                    .filter { it.state in UiState.guardedOverlays }
                    .flatMap { it.signalNames }
                    .toSet(),
            )
    }
}
