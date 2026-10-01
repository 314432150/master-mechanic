package com.example.mastermechanic.ui

import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.patrol.PatrolScenes

/** 一条记录 + 它在文件里的下标（撤销要插回原位）。 */
data class ArtifactItem(val index: Int, val entry: CalibrationData.SignalEntry)

/**
 * 产物清单的**一行 = 一个元素**（2026-09-19 用户口径）：同一次框选写出来的
 * 「标志 + 锚点」是同一个东西的两面，列表里**合成一行**；只标了其中一样时另一侧为 null
 * （这不是错误状态，不提示、不占位）。
 */
data class ArtifactElement(val marker: ArtifactItem?, val anchor: ArtifactItem?) {

    /** 这一行包含的记录（1 条或 2 条）。 */
    val items: List<ArtifactItem> = listOfNotNull(marker, anchor)

    /** 元素 ID（锚点 = 用途名，其余 = `<界面>_e[n]`）；**删除粒度 = 整行**，所以删除按它来（两条一起删）。 */
    val id: String = items.first().entry.id

    /** 备注（元素级：同一 ID 的两条记录共用一份；都为空则空串）。 */
    val note: String = items.map { it.entry.note }.firstOrNull { it.isNotBlank() }.orEmpty()

    /** 用途（锚点的契约名；标志没有用途）。 */
    val purpose: String? = items.mapNotNull { it.entry.purpose }.firstOrNull()

    /** 归属好友名（"这是谁的头像"；只有好友头像这类用途有值）。 */
    val friend: String? = items.mapNotNull { it.entry.friend }.firstOrNull()

    /** 行序 = 这一行里最早那条记录在文件里的位置（与标定顺序一致）。 */
    val firstIndex: Int = items.minOf { it.index }
}

/** 一个分组：所属界面 + 该界面的元素行。 */
data class ArtifactGroup(val state: UiState, val elements: List<ArtifactElement>)

/**
 * 清单里**真正画出来的一行 = 一条记录**（2026-10-01 用户口径："将产物列表里的标志和锚点改为分两行显示"）。
 *
 * 为什么要拆：标志与锚点是**两份不同的窗口 / 可能不同的模板**，职责也不同（标志用来辨认这一屏、
 * 锚点用来点击）。合成一行时只能写「标志 + 锚点」一个徽标，看不出"这一屏到底有没有标志"——
 * 用户就是这么发现「大厅只剩锚点、没有标志」的（那一屏当时已经认不出来了）。
 *
 * 元素的**身份**仍是 [ArtifactElement]（同一 ID 的两条记录是一行还是两行，只是展示问题）：
 * 备注、用途、归属好友名都是元素级的，两行读同一份。
 */
data class ArtifactRow(val element: ArtifactElement, val item: ArtifactItem) {

    /** 行的稳定标识（左滑删除按它索引；同一 ID 的两条记录要能分开）。 */
    val key: String = "${item.entry.id}|${item.entry.role.token}"

    /** 这一行的角色（徽标显示它）。 */
    val role: SignalRole = item.entry.role
}

/**
 * 产物清单的分组与配对（纯逻辑，可 JVM 单测）：
 *
 * - **按「所属界面」分组**，组顺序 = 流程顺序（[PatrolScenes.calibrationOrder]，与工作台清单同一套）；
 *   两个农场合一后，**旧产物里写着「好友的农场」的记录并入「农场」组**（2026-09-21）；
 * - **组内把「标志 + 锚点」配成一行**：判据是**同一个元素 ID**（2026-09-19 用户口径）——
 *   同一次框选写出来的两条记录**共用同一个 ID**，所以"这两条是同一个东西的两面"是**直接相等**，
 *   不需要再靠"像素逐字节比对"或"名字看起来像一对"这种间接判据；
 * - 归属界面认不出来的记录不丢：归到最后一组（[UiState.UNKNOWN]）。
 */
object CalibrationArtifactGroups {

    /** 清单里**标志 / 锚点各多少条**；[total] 恒等于两者之和（摘要那句"共 N 条"读的就是它）。 */
    data class RoleCounts(val marker: Int, val anchor: Int) {
        val total: Int get() = marker + anchor
    }

    /**
     * 清单的**总条数**口径 = **标志条数 + 锚点条数**（用户 2026-10-01 口径："产物总条数统计为标记+锚点的总条数"）。
     *
     * 不是"数一数列表里有几行"就算 —— 而是**由角色各自数出来再相加**：这样"总条数 = 标志 + 锚点"是
     * **结构上成立的**（[rowCount] 就是 [RoleCounts.total]），不靠"两边碰巧都按行数数"这种巧合。
     * 摘要、分组标题、筛选按钮全部与它同源。
     *
     * ## 口径变过两次，别拿旧结论套
     *
     * - 2026-09-23 修的是"摘要写 `data.signals.size`（**记录**条数）、分组写**元素**行数"导致的
     *   "总条数对不上分组合计"（双角色元素被并成一行 ⇒ 差额）；
     * - 2026-10-01 先按"标志和锚点分两行显示"把每行改成一条记录，再明确**总数 = 标志 + 锚点**
     *   （元素与行不再一一对应；"总行数 == 各分组行数之和"这条不变量照旧，单测钉着）。
     *
     * **删除的粒度**由每行的角色决定（删哪行删哪条记录），文案里的条数也按**记录**说 —— 同一个数、同一件事。
     */
    fun roleCounts(groups: List<ArtifactGroup>): RoleCounts = RoleCounts(
        marker = groups.sumOf { group -> rowsOf(group).count { it.role == SignalRole.MARKER } },
        anchor = groups.sumOf { group -> rowsOf(group).count { it.role == SignalRole.ANCHOR } },
    )

    /** 总条数 = 标志 + 锚点（见 [roleCounts]）。 */
    fun rowCount(groups: List<ArtifactGroup>): Int = roleCounts(groups).total

    /**
     * 一个分组里的**展示行**：元素 → 它的每条记录（标志 / 锚点）各一行。
     *
     * **排序**（用户 2026-10-01 口径）：**先标志、后锚点**。同一角色内保持原顺序
     * （元素顺序 = 该元素最早那条记录在文件里的位置，与标定先后一致）——
     * `sortedBy` 是稳定排序，所以这里只需给出"标志排前面"这一个键。
     *
     * 为什么要分段而不是按元素交错：一个界面里标志和锚点是**两种东西**（标志判状态、锚点管点击），
     * 交错着看要一行一行认徽标；分段之后"这一屏有几个标志、几个锚点"一眼就数得出来。
     */
    fun rowsOf(group: ArtifactGroup): List<ArtifactRow> = group.elements
        .flatMap { element -> element.items.map { ArtifactRow(element, it) } }
        .sortedBy { row -> if (row.role == SignalRole.MARKER) 0 else 1 }

    fun of(data: CalibrationData): List<ArtifactGroup> {
        val byState = data.signals.withIndex().groupBy(
            keySelector = { groupState(CalibrationSignals.stateOf(data, it.value.id)) },
            valueTransform = { ArtifactItem(it.index, it.value) },
        )
        // 展示顺序 = 流程顺序；流程表里没有的状态（旧产物写的、或将来新增的）**补在后面**，
        // 宁可多出一组，也不让任何已存在的记录从清单里悄悄消失（看不见 ≠ 不存在，反而更危险）。
        val ordered = PatrolScenes.calibrationOrder.filter { it in byState } +
            UiState.entries.filter { it.isCandidate && it !in PatrolScenes.calibrationOrder && it in byState } +
            listOfNotNull(UiState.UNKNOWN.takeIf { it in byState })
        return ordered.map { state -> ArtifactGroup(state, pairOf(byState.getValue(state))) }
    }

    /**
     * 分组用的界面：认不出归属 → [UiState.UNKNOWN]；**「好友的农场」并入「农场」**（2026-09-21）。
     *
     * 并入而不是单列：两个农场已经是同一个场景（[PatrolScenes.read]），旧产物里那几条
     * （`friend_farm` 标志、`friend_farm_exit` / `friend_farm_friends`）本来就是农场的东西 ——
     * 单独留一组只会让人以为"还有第二个农场要标"。
     */
    private fun groupState(state: UiState?): UiState = when (state) {
        null -> UiState.UNKNOWN
        UiState.FRIEND_FARM -> UiState.FARM
        else -> state
    }

    /** 用途的中文说明（[PatrolAnchors.purposesFor] 里查；查不到就退回用途名本身，不猜别的）。 */
    fun purposeLabel(state: UiState, purpose: String): String =
        PatrolAnchors.purposesFor(state).firstOrNull { it.name == purpose }?.label ?: purpose

    /**
     * 用途**给人看的说法**（2026-09-21 用户口径）：
     *
     * 写了归属好友名的（好友头像）显示「**某某的头像**」——同名用途在清单里有好几条，
     * 只显示「好友头像」分不出谁是谁；没写的照旧显示用途名。
     */
    fun purposeText(state: UiState, purpose: String, friend: String?): String {
        val label = purposeLabel(state, purpose)
        return if (friend.isNullOrBlank()) label else "${friend}的头像"
    }

    /**
     * 一行（或详情弹窗）的**主文本**（2026-09-19 用户口径：用户不需要关心图片叫什么）：
     *
     * **备注（用户自己写的）> 用途（这个控件干什么用的；好友头像显示「某某的头像」）> 模板尺寸**。
     *
     * 尺寸只作最后兜底 —— 同界面同尺寸还可能撞（两个 131×37 的小按钮），所以它排在最后；
     * 真撞了也不影响使用：ID 才是身份，列表能删能点，只是那一行文字看起来一样。
     */
    fun primaryText(element: ArtifactElement, state: UiState): String =
        element.note.ifBlank {
            element.purpose?.let { purposeText(state, it, element.friend) } ?: run {
                val template = element.items.first().entry.templates.first()
                "${template.width}×${template.height}"
            }
        }

    /**
     * **一行（= 一条记录）的主文本**（2026-10-01 用户口径"标志与锚点各说各的"）：
     * 这一行**自己的备注** > 整个元素的备注 > 用途（锚点；好友头像显示「某某的头像」）> 模板尺寸。
     *
     * 为什么要先看行自己的：自动备注是**按角色**派生的（标志 = 「大厅」的界面标志、锚点 = 「设置入口」），
     * 若只看元素级备注，两行会显示同一句话 —— 那用户把行拆成两行就白拆了。
     */
    fun primaryText(row: ArtifactRow, state: UiState): String =
        row.item.entry.note.trim().ifBlank { primaryText(row.element, state) }

    /** 同一界面的记录按 ID 归行：一个 ID 至多两条（每个角色一条），缺哪一半就哪一半为 null。 */
    private fun pairOf(items: List<ArtifactItem>): List<ArtifactElement> =
        items.groupBy { it.entry.id }
            .map { (_, sameElement) ->
                ArtifactElement(
                    marker = sameElement.firstOrNull { it.entry.role == SignalRole.MARKER },
                    anchor = sameElement.firstOrNull { it.entry.role == SignalRole.ANCHOR },
                )
            }
            .sortedBy { it.firstIndex }
}
