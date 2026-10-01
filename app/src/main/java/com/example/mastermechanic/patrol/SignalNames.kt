package com.example.mastermechanic.patrol

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState

/**
 * **标志 / 锚点的中文说法**（2026-10-01，用户口径："只用 `hall_settings` 这种英文名不好排查，所有产物都要加上"）。
 *
 * ## 取名优先级（越靠前越贴近用户自己写的字）
 *
 * 1. **产物备注** —— 用户手写的，或写入时自动填进去的功能描述（见 [defaultNoteFor]）；
 * 2. **用途的中文标签** —— `PatrolAnchors.purposesFor(state)` 里那一项（「设置入口」「拜访（行尾图标）」…）；
 * 3. **「界面」的角色** —— 兜底，至少说清"这是哪一屏上的标志、还是锚点"；
 * 4. 连产物都没有（未标定运行 / 旧产物且没有对应状态）→ 退回 ID 本身。
 *
 * ## 为什么集中在一处
 *
 * 悬浮窗的状态行、跑号的中止原因、锚点定位日志、标定清单**都要这句话** —— 分散在几处各写一套，
 * 迟早出现"菜单说 A、日志说 B"（本项目一贯口径：同一概念只能有一个出处）。
 */
object SignalNames {

    /**
     * 给 [id] 一个**中文短描述**（悬浮窗提示 / 排障日志用）。
     *
     * @param role 已知角色时传进来（同 ID 可能同时是标志与锚点）；null = 由产物自己判（取先声明的那条）
     */
    fun of(data: CalibrationData?, id: String, role: SignalRole? = null): String {
        val note = data?.noteOf(id)?.trim().orEmpty()
        if (note.isNotEmpty()) return note
        val state = data?.stateOf(id)
        if (state != null) {
            PatrolAnchors.purposesFor(state).firstOrNull { it.name == id }?.let { return it.label }
        }
        val roleLabel = role?.label ?: data?.roleOf(id)?.label
        return when {
            state != null && roleLabel != null -> "「${state.label}」的$roleLabel"
            state != null -> "「${state.label}」的标志"
            else -> id
        }
    }

    /**
     * **写进产物备注的默认值**（用户没自己填备注时用）—— 按**记录自己的角色**各给一句（2026-10-01）：
     *
     * - **标志**：`「<界面>」的界面标志`（标志没有用途，不参与用途查表）；
     * - **锚点**，两种：
     *   ① 选了用途（[purpose]）⇒ **该用途的中文标签**（「设置入口」「返回大厅」）——锚点的备注就该是用途；
     *   ② 没选用途 ⇒ 再分两种（看该屏有没有用途可选）：
     *      - 该屏**没有用途表**（[PatrolAnchors.purposesFor] 为空 ⇒ 全屏只有一个控件、自动关闭按状态取它，
     *        见那段说明）⇒ `「<界面>」的关闭控件`，直接说清它是干嘛的
     *        （用户 2026-10-01 口径：新手引导 / 新手大厅 / 活动弹窗这三屏的锚点也要"用途"）；
     *      - 该屏**有用途表**而这条没选 ⇒ 如实写 `「<界面>」的锚点`（不替用户猜一个用途）。
     *
     * ## 为什么按角色（真机踩过的坑）
     *
     * 原签名是 `roles: Collection<SignalRole>`：一次框选**同时写标志 + 锚点**时，两条记录算出**同一句话**
     * ⇒ 锚点的备注被写成「活动弹窗」的界面标志 —— 用户打开产物一眼就看出来了。
     * 备注是**每条记录**的属性 ⇒ 派生规则必须按记录的角色走；历史文本由 [autoNoteHistory] 认出来并纠正。
     *
     * 为什么要有默认值：备注是**标定清单行的主文本**，空着就只能显示"模板 77×79"这类开发参数；
     * 填上功能描述后，列表里一行就能念出"这是大厅的设置入口"。用户手写的备注永远优先
     * （`CalibrationSignals.upsert` 的取值顺序：手填 > 旧备注 > 本默认）。
     */
    fun defaultNoteFor(state: UiState, role: SignalRole, purpose: String?): String {
        if (role == SignalRole.ANCHOR) {
            purpose?.let { p ->
                PatrolAnchors.purposesFor(state).firstOrNull { it.name == p }?.let { return it.label }
            }
            return if (PatrolAnchors.purposesFor(state).isEmpty()) {
                "「${state.label}」的关闭控件"
            } else {
                "「${state.label}」的锚点"
            }
        }
        return "「${state.label}」的界面标志"
    }

    /**
     * **老规则派生的文本**（2026-10-01 当天的第一版：只看"元素有哪些角色"，不区分是哪条记录）。
     *
     * 只用来**认出来**，不要拿它写新产物（旧文本还躺在已落盘的产物里，全量重标不现实）。
     */
    fun legacyDefaultNoteFor(state: UiState, roles: Collection<SignalRole>, purpose: String?): String {
        purpose?.let { p ->
            PatrolAnchors.purposesFor(state).firstOrNull { it.name == p }?.let { return it.label }
        }
        return if (SignalRole.MARKER in roles) {
            "「${state.label}」的界面标志"
        } else {
            "「${state.label}」的锚点"
        }
    }

    /**
     * **程序可能自动写下的所有文本**（2026-10-01 一天内改了两版默认规则，这里是两版的并集加兜底句）。
     *
     * 用途只有一个：`CalibrationSignals.withDefaultNotes` 拿它判断"这句是程序写的，还是用户手打的" ——
     * 命中集合的备注可以放心改成 [defaultNoteFor] 的新文本，未命中的（用户写的）**一律不动**。
     *
     * ⚠ 代价要说清：用户若**逐字**手打了这些公式化的句子（如"「新手引导」的锚点"），也会被当成自动文本改掉。
     * 这些句子都是机器腔（带「」的整句），手打同样一串的概率极低；换来的是"改错文本不用用户重新标一遍"。
     */
    fun autoNoteHistory(state: UiState, roles: Collection<SignalRole>, purpose: String?): Set<String> = buildSet {
        add(legacyDefaultNoteFor(state, roles, purpose))
        add("「${state.label}」的界面标志")
        add("「${state.label}」的锚点")
        add("「${state.label}」的关闭控件")
        purpose?.let { p ->
            PatrolAnchors.purposesFor(state).firstOrNull { it.name == p }?.let { add(it.label) }
        }
    }
}
