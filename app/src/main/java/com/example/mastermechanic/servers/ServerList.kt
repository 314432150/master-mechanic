package com.example.mastermechanic.servers

/**
 * 编码用的字段分隔符：与巡查配置同源口径 —— 字段里不允许出现，编码层显式拒收，不做静默替换。
 */
internal const val SERVER_FIELD_SEPARATOR = '|'

/**
 * 一条服务器清单条目（FR-10）：**一个区服名称 + 角色名 + 等级**。
 *
 * - [serverName] 是**唯一参与定位**的字段——换号时按它到界面上找（红线 3：找不到或不唯一就停下）；
 * - [characterName] / [level] **只用于辨识**（帮用户认出这是哪个小号），**允许留空**，
 *   不参与任何定位与匹配；
 * - 等级存**文本**而非数字：游戏里形如「Lv.50」「王者」等并不统一，它既然不参与定位，
 *   就没有必要编一套数值规则出来，更不该因此拒收用户输入。
 *
 * 校验只做**格式**（区服名非空、各字段不含分隔符与换行）；构造失败抛 [IllegalArgumentException]，
 * 不静默丢弃（与 FR-03 同一套口径）。
 */
data class ServerEntry(
    val serverName: String,
    val characterName: String = "",
    val level: String = "",
) {

    init {
        require(ServerList.isValidServerName(serverName)) {
            "服务器条目非法：区服名称不得为空，且不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
        require(ServerList.isValidOptionalField(characterName)) {
            "服务器条目非法：角色名可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
        require(ServerList.isValidOptionalField(level)) {
            "服务器条目非法：等级可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
    }

    /** 是否填了角色名（界面用它决定显示内容还是「未填」占位）。 */
    val hasCharacterName: Boolean get() = characterName.isNotEmpty()

    /** 是否填了等级。 */
    val hasLevel: Boolean get() = level.isNotEmpty()

    companion object {

        /**
         * 从用户输入构造：**逐字段先去首尾空白再校验**——界面不该因为多敲一个空格就拒收。
         * 区服名去空白后为空则抛错（由界面转成"区服名称不得为空"的提示）。
         */
        fun of(serverName: String, characterName: String = "", level: String = ""): ServerEntry =
            ServerEntry(serverName.trim(), characterName.trim(), level.trim())
    }
}

/**
 * 服务器清单（FR-10，纯逻辑、无安卓依赖）：**有序**条目列表，顺序即"按顺序切下一个"的顺序。
 *
 * - 允许同一区服多条（一个区服里可能有多个小号）；
 * - **空清单合法**（「整体清空」的结果）——与标定产物不同，这里没有"必须有内容"的前置；
 * - 编辑操作一律**返回新实例**（不可变），便于界面替换 state 触发重组与单测逐项断言；
 * - 越界序号一律抛 [IllegalArgumentException]（按钮本就不该被点到，静默忽略会掩盖界面 bug）。
 */
data class ServerList(
    val entries: List<ServerEntry> = emptyList(),
) {

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    /** 追加到末尾（FR-10「增」）。 */
    fun add(entry: ServerEntry): ServerList = copy(entries = entries + entry)

    /** 插入到指定位置（0..size）。 */
    fun insert(index: Int, entry: ServerEntry): ServerList {
        require(index in 0..entries.size) { "插入位置越界：$index（共 ${entries.size} 条）" }
        return copy(entries = buildList {
            addAll(entries.subList(0, index))
            add(entry)
            addAll(entries.subList(index, entries.size))
        })
    }

    /** 改写指定条目（FR-10「改」）。 */
    fun update(index: Int, entry: ServerEntry): ServerList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        return copy(entries = entries.toMutableList().also { it[index] = entry })
    }

    /** 删除指定条目（FR-10「删」）。 */
    fun remove(index: Int): ServerList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        return copy(entries = entries.toMutableList().also { it.removeAt(index) })
    }

    /**
     * 调整顺序（FR-10「调整顺序」）：[delta] = -1 上移 / +1 下移。
     * 已在端点时**原样返回**（不是错误——界面上的端点按钮就该是无效动作）。
     */
    fun move(index: Int, delta: Int): ServerList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        require(delta == -1 || delta == 1) { "只支持上移（-1）/ 下移（+1）：$delta" }
        val target = index + delta
        if (target !in entries.indices) return this
        val moved = entries.toMutableList()
        val entry = moved.removeAt(index)
        moved.add(target, entry)
        return copy(entries = moved)
    }

    /** 整体清空（FR-10「整体清空」）。 */
    fun clear(): ServerList = copy(entries = emptyList())

    companion object {

        val EMPTY = ServerList()

        /** 区服名称的格式合法性：非空、不含分隔符与换行（定位字段，要求与 FR-03 的区服名一致）。 */
        fun isValidServerName(name: String): Boolean = name.isNotBlank() &&
            name.none { it == SERVER_FIELD_SEPARATOR || it == '\n' || it == '\r' }

        /** 可选字段（角色名 / 等级）的格式合法性：**允许为空**，非空时不得含分隔符与换行。 */
        fun isValidOptionalField(value: String): Boolean =
            value.none { it == SERVER_FIELD_SEPARATOR || it == '\n' || it == '\r' }
    }
}
