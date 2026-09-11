package com.example.mastermechanic.decision

import com.example.mastermechanic.recognition.DetectionRecord

/**
 * 信号—状态映射（T1-4）：把判定记录解析为「命中的界面状态集合」。
 *
 * 一个状态的判定依据可为多个界面标志，任一命中即该状态命中
 * （§2.1「对战 / 排位 / 农场入口，任一命中即为大厅」）。
 * 信号名与状态的对应关系来自标定产物（T1-5）；本类不内置任何具体信号名。
 * 仅统计结论为 MATCHED 的记录——未命中与不可信均不算命中（§5-2）。
 */
class SignalStateMapping(rules: List<Rule>) {

    /** 一条映射规则：状态 + 构成它的信号名集合。 */
    class Rule(val state: UiState, val signalNames: Set<String>) {
        init {
            require(state != UiState.UNKNOWN) { "「未知」没有标志，不能作为映射目标" }
            require(signalNames.isNotEmpty()) { "规则至少包含一个信号名" }
        }
    }

    private val rules: List<Rule> = rules.toList()

    /** 由判定记录得出命中的状态集合（按规则声明顺序确定性遍历）。 */
    fun resolve(records: List<DetectionRecord>): Set<UiState> =
        rules.filter { rule -> records.any { it.matched && it.signalName in rule.signalNames } }
            .mapTo(LinkedHashSet()) { it.state }
}
