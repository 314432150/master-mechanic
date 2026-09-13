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
 * - [PopupPhaseExpectedSignals]：FR-01 弹窗阶段（命中「启动页」才注入弹窗期集合）。
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
 * 弹窗阶段期望集合（FR-01 / T2-1，用户拍板口径：**命中「启动页」才开始注入弹窗期期望集合**）。
 *
 * - **守护待命**（[Phase.STANDBY]）：只搜「启动页」——它是本阶段的**入口标志**，
 *   不搜它就没有任何办法知道游戏进入了「启动页 → 进入大厅」这一段；成本 = 一个小窗口信号
 *   （T1-13b 真机 7~22ms），守护期间可长期承担；
 * - **弹窗期**（[Phase.POPUP]）：扩为 {启动页, 选择服务器, 活动弹窗, 大厅}——活动弹窗与选择服务器
 *   都出现在这一段，**大厅是离开该段的标志**（FR-01 触发行：弹窗只在这之间出现）；
 *   带上「启动页」是因为它可能再次出现（返回启动页 / 换区），且滞回确认要走完 2 次命中。
 *
 * 阶段推进只看**本轮命中**（[onRound] 收到的原始命中集合，不是滞回结论）：
 * 命中即注入，若等滞回确认（连续 2 次）才注入，刚弹出来的弹窗在本轮与下一轮都不会被搜索；
 * 提前注入的代价只是多搜几个已标定信号。
 *
 * 阶段状态在进程内保持、不落盘：采集会话重建即从「守护待命」重来（与滞回状态不跨会话一致）。
 */
class PopupPhaseExpectedSignals private constructor(
    /** 守护待命期望：启动页信号名。 */
    private val standbyNames: Set<String>,
    /** 弹窗期期望：启动页 + 选择服务器 + 活动弹窗 + 大厅。 */
    private val popupNames: Set<String>,
) : ExpectedSignals {

    /** 当前阶段。 */
    enum class Phase {

        /** 守护待命：只在等「启动页」。 */
        STANDBY,

        /** 弹窗期：已见「启动页」、尚未见「大厅」。 */
        POPUP,
        ;
    }

    var phase: Phase = Phase.STANDBY
        private set

    override fun expected(known: Set<String>): Set<String> =
        if (phase == Phase.STANDBY) standbyNames else popupNames

    override fun onRound(hits: Set<UiState>) {
        when {
            phase == Phase.STANDBY && UiState.LAUNCH_PAGE in hits -> phase = Phase.POPUP
            phase == Phase.POPUP && UiState.HALL in hits -> phase = Phase.STANDBY
        }
    }

    companion object {

        /**
         * 由标定产物的状态—信号规则构造：各状态取自己在产物里的**全部**信号名
         * （同一状态多条样式记录 → 全部纳入期望，任一命中即该状态命中，§2.1）。
         * 产物里没有的状态（未标定）取空集，即"该状态在本产品上不可识别"。
         */
        fun fromRules(rules: List<SignalStateMapping.Rule>): PopupPhaseExpectedSignals {
            fun namesOf(state: UiState): Set<String> =
                rules.firstOrNull { it.state == state }?.signalNames.orEmpty()
            val launch = namesOf(UiState.LAUNCH_PAGE)
            return PopupPhaseExpectedSignals(
                standbyNames = launch,
                popupNames = launch +
                    namesOf(UiState.SERVER_SELECT) +
                    namesOf(UiState.ACTIVITY_POPUP) +
                    namesOf(UiState.HALL),
            )
        }
    }
}
