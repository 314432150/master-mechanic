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

    /**
     * 由判定记录得出命中的状态集合（按规则声明顺序确定性遍历）。
     *
     * ## ⚠ 2026-09-29 改口径：**标志只问"在不在"，位置歧义不算"不在"**
     *
     * 用户报障（原话）："在'新手引导页'框选'开局送英雄!'文字作为标志，这个标志在全图都只有
     * 这个地方有 …… 甚至在写入标志后，连当前帧都搜不到这个标志。" 真机日志（写入试读）：
     * ```
     * 写入 tutorial_guide_e1｜MARKER｜试读 ⚠ 不可信（最高 1.00，但竞争位置 (204, 38) 也有 0.90）
     * ```
     * 判定其实是**对**的 —— 模板在**正确位置**是 1.00（像素级满分），别处 0.90；问题在旧口径
     * "不可信（UNRELIABLE）按未识别处理"（红线 7）**是为"要点击的锚点"定的**：锚点点歪会点到
     * 别的按钮，所以位置歧义必须一票否决。但**标志不点任何地方**，它只回答"这一屏有没有它"——
     * 最高分 1.00 与 0.90 的差距已经明确回答了这个问题的答案。
     * ⇒ 于是这面标志被整条废掉：新手引导页**永远认不出**（FR-01/FR-02 也就关不掉它），
     * 真机上表现为"在登录页点换号拜访，没进农场反而进了商城"。
     *
     * ⇒ 现在：[present]（命中，或"不可信但最高分够高"）即算在场。
     * **锚点那条路不受影响**（[com.example.mastermechanic.decision.AnchorLocator.hitOf] 仍只认
     * `matched`）—— 位置歧义继续由它一票否决。
     */
    fun resolve(records: List<DetectionRecord>): Set<UiState> =
        rules.filter { rule -> records.any { it.present && it.signalName in rule.signalNames } }
            .mapTo(LinkedHashSet()) { it.state }
}
