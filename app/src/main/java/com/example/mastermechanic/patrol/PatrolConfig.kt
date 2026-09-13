package com.example.mastermechanic.patrol

/**
 * 编码用的字段分隔符：名称中不允许出现（编码层显式拒收，不做静默替换）。
 * 真机上的区服名 / 好友名不会含这个字符；一旦输入里出现，说明用户想表达的不是"一个名称"。
 */
internal const val PATROL_NAME_SEPARATOR = '|'

/**
 * 一个巡查项（FR-03）：**一个区服名称 + 一个目标好友名称**。
 *
 * 名称只做**格式**校验（非空、去首尾空白、不含分隔符与换行）；「能不能在界面上唯一匹配到」
 * 属 M4 的名称定位（红线 3：不唯一即停下）。构造失败会抛 [IllegalArgumentException] —— 不静默丢弃。
 */
data class PatrolItem(
    val serverName: String,
    val friendName: String,
) {

    init {
        require(PatrolConfig.isValidName(serverName)) {
            "巡查项非法：区服名称不得为空，且不得含「$PATROL_NAME_SEPARATOR」或换行"
        }
        require(PatrolConfig.isValidName(friendName)) {
            "巡查项非法：目标好友名称不得为空，且不得含「$PATROL_NAME_SEPARATOR」或换行"
        }
    }

    companion object {

        /**
         * 从用户输入构造：**先去首尾空白再校验**——界面不该因为多敲了一个空格就拒收。
         * 输入全为空白时校验失败并抛出（由界面转成"名称不得为空"的提示）。
         */
        fun of(serverName: String, friendName: String): PatrolItem =
            PatrolItem(serverName.trim(), friendName.trim())
    }
}

/**
 * 巡查配置（FR-03，纯逻辑、无安卓依赖）：**有序**巡查项列表，按列表顺序依次执行。
 *
 * - 允许同一区服重复出现（同一区服拜访多个好友，FR-03「重复」）；
 * - **空列表合法**（「整体清空」的结果）——与标定产物不同，这里不存在"必须有内容"的前置；
 * - 编辑操作一律**返回新实例**（不可变）：界面只需替换 state 即可触发重组，也便于单测逐项断言；
 * - 越界序号一律抛 [IllegalArgumentException]（界面按钮本就不该被点到，静默忽略会掩盖界面 bug）。
 */
data class PatrolConfig(
    val items: List<PatrolItem> = emptyList(),
) {

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    val isNotEmpty: Boolean get() = items.isNotEmpty()

    /** 追加到末尾（FR-03「增」）。 */
    fun add(item: PatrolItem): PatrolConfig = copy(items = items + item)

    /** 插入到指定位置（0..size）。 */
    fun insert(index: Int, item: PatrolItem): PatrolConfig {
        require(index in 0..items.size) { "插入位置越界：$index（共 ${items.size} 项）" }
        return copy(items = buildList {
            addAll(items.subList(0, index))
            add(item)
            addAll(items.subList(index, items.size))
        })
    }

    /** 改写指定项（FR-03「改」）。 */
    fun update(index: Int, item: PatrolItem): PatrolConfig {
        require(index in items.indices) { "序号越界：$index（共 ${items.size} 项）" }
        return copy(items = items.toMutableList().also { it[index] = item })
    }

    /** 删除指定项（FR-03「删」）。 */
    fun remove(index: Int): PatrolConfig {
        require(index in items.indices) { "序号越界：$index（共 ${items.size} 项）" }
        return copy(items = items.toMutableList().also { it.removeAt(index) })
    }

    /**
     * 调整顺序（FR-03「调整顺序」）：[delta] = -1 上移 / +1 下移。
     * 已在端点时**原样返回**（不是错误——界面上端点按钮就该是无效动作）。
     */
    fun move(index: Int, delta: Int): PatrolConfig {
        require(index in items.indices) { "序号越界：$index（共 ${items.size} 项）" }
        require(delta == -1 || delta == 1) { "只支持上移（-1）/ 下移（+1）：$delta" }
        val target = index + delta
        if (target !in items.indices) return this
        val moved = items.toMutableList()
        val item = moved.removeAt(index)
        moved.add(target, item)
        return copy(items = moved)
    }

    /** 整体清空（FR-03「整体清空」）。 */
    fun clear(): PatrolConfig = copy(items = emptyList())

    companion object {

        val EMPTY = PatrolConfig()

        /** 名称的格式合法性：非空、不含分隔符与换行（编码层的硬约束，见 [PatrolConfigCodec]）。 */
        fun isValidName(name: String): Boolean = name.isNotBlank() &&
            name.none { it == PATROL_NAME_SEPARATOR || it == '\n' || it == '\r' }
    }
}
