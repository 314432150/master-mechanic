package com.example.mastermechanic.servers

/**
 * 编码用的字段分隔符：与巡查配置同源口径 —— 字段里不允许出现，编码层显式拒收，不做静默替换。
 */
internal const val SERVER_FIELD_SEPARATOR = '|'

/**
 * 平台（2026-09-14 用户口径补充：微信 / QQ）。
 *
 * [code] 是**落到盘上的稳定标识**（ASCII）：清单文件是给人看、也可能被人手改的，
 * 中文值在文件里反而容易因为编辑器编码出岔子；界面文案走 `strings.xml`（不在这里写死中文）。
 * 可空 = 用户没填——它和区号一样只用于辨识，不参与定位。
 */
enum class ServerPlatform(val code: String) {
    WECHAT("wechat"),
    QQ("qq"),
    ;

    companion object {
        /** 盘上的编码 → 枚举；未知编码返回 null（调用方自行决定拒收还是当空，不静默兜底）。 */
        fun fromCode(code: String): ServerPlatform? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 一条服务器清单条目（FR-10）：**平台 + 区号 + 区服名称 + 角色名 + 等级**。
 *
 * - [serverName] 是**唯一参与定位**的字段——换号时按它到界面上找（红线 3：找不到或不唯一就停下）；
 * - [platform]（微信 / QQ）、[serverNo]（区号，如 `392`）、[characterName]、[level]
 *   **只用于辨识**（帮用户认出这是哪个小号、也方便按区号找它），**全部允许留空**，不参与任何定位与匹配；
 * - 区号与等级一样存**文本**：游戏里的写法并不统一（「Lv.50」「王者」/「392」「S12」），
 *   既然不参与定位，就没有必要编一套数值规则出来，更不该因此拒收用户输入；
 * - 平台用**枚举**而不是自由文本：只有微信 / QQ 两种，不给自由书写的机会
 *   （否则会出现"wx""WeChat""微信 "三种写法混在一起，还怎么看）。
 *
 * 校验只做**格式**（区服名非空、各字段不含分隔符与换行）；构造失败抛 [IllegalArgumentException]，
 * 不静默丢弃（与 FR-03 同一套口径）。
 */
data class ServerEntry(
    val platform: ServerPlatform? = null,
    val serverNo: String = "",
    val serverName: String,
    val characterName: String = "",
    val level: String = "",
) {

    init {
        require(ServerList.isValidServerName(serverName)) {
            "服务器条目非法：区服名称不得为空，且不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
        require(ServerList.isValidOptionalField(serverNo)) {
            "服务器条目非法：区号可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
        require(ServerList.isValidOptionalField(characterName)) {
            "服务器条目非法：角色名可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
        require(ServerList.isValidOptionalField(level)) {
            "服务器条目非法：等级可留空，但不得含「$SERVER_FIELD_SEPARATOR」或换行"
        }
    }

    /** 是否填了平台。 */
    val hasPlatform: Boolean get() = platform != null

    /** 是否填了区号。 */
    val hasServerNo: Boolean get() = serverNo.isNotEmpty()

    /** 是否填了角色名（界面用它决定显示内容还是「未填」占位）。 */
    val hasCharacterName: Boolean get() = characterName.isNotEmpty()

    /** 是否填了等级。 */
    val hasLevel: Boolean get() = level.isNotEmpty()

    companion object {

        /**
         * 从用户输入构造：**逐字段先去首尾空白再校验**——界面不该因为多敲一个空格就拒收。
         * 区服名去空白后为空则抛错（由界面转成"区服名称不得为空"的提示）。
         */
        fun of(
            platform: ServerPlatform? = null,
            serverNo: String = "",
            serverName: String,
            characterName: String = "",
            level: String = "",
        ): ServerEntry = ServerEntry(
            platform = platform,
            serverNo = serverNo.trim(),
            serverName = serverName.trim(),
            characterName = characterName.trim(),
            level = level.trim(),
        )
    }
}

/**
 * 服务器清单（FR-10，纯逻辑、无安卓依赖）：**有序**条目列表，顺序即"按顺序切下一个"的顺序。
 *
 * - **同一区服只能有一条**（一个区服只有一个小号，用户口径 2026-09-14）——**唯一性只认区服名称**
 *   （平台 / 区号只是附加的辨识信息，不参与判重，用户口径 2026-09-14）；**唯一性是构造时不变量**：
 *   构造 / 新增 / 插入 / 改写 / 读盘只要出现重复区服名就抛 [IllegalArgumentException]（界面据此
 *   **明确提示**），绝不静默去重、也绝不悄悄覆盖已有条目；
 * - **空清单合法**（「整体清空」的结果）——与标定产物不同，这里没有"必须有内容"的前置；
 * - 编辑操作一律**返回新实例**（不可变），便于界面替换 state 触发重组与单测逐项断言；
 * - 越界序号一律抛 [IllegalArgumentException]（按钮本就不该被点到，静默忽略会掩盖界面 bug）。
 */
data class ServerList(
    val entries: List<ServerEntry> = emptyList(),
) {

    init {
        // 一个区服只有一个小号 → 同一区服名称不得出现两次（**唯一性是这个类型的不变量**，
        // 而不是界面上的一道校验：构造点唯一，增 / 插 / 改 / 读盘全部被它盖住）。
        val duplicated = entries.groupingBy { it.serverName }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicated == null) {
            "同一个区服只能有一条（一个区服只有一个小号）：${duplicated?.key}"
        }
    }

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    /** 追加到末尾（FR-10「增」）。区服重名时由构造检查抛错（一个区服只有一个小号）。 */
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

    /**
     * 改写指定条目（FR-10「改」）。
     *
     * 改的是**自己这一条**（区服名没动、只改角色名 / 等级）→ 正常放行；
     * 把区服名改成**别的既有条目**用着的 → 构造检查抛错（一个区服只有一个小号）。
     */
    fun update(index: Int, entry: ServerEntry): ServerList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        return copy(entries = entries.toMutableList().also { it[index] = entry })
    }

    /** 该区服名称在清单里的位置；没有则 -1（界面据此在保存前给出明确提示）。 */
    fun indexOfServerName(serverName: String): Int =
        entries.indexOfFirst { it.serverName == serverName }

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

    /**
     * 把 [from] 位置的条目**一次搬到** [to]（FR-10「调整顺序」，拖动排序用）。
     *
     * 与 [move] 的区别：[move] 是"上移 / 下移一格"（按钮语义），这里是"一路拖到目标位置"。
     * 拖动过程中界面只改**内存里的预览顺序**，松手时用这一个方法**一次性落盘**——
     * 每跨一行就写一次盘的话，"写盘 → 重新读盘 → 重建列表"会把手指还按着的那一行顶掉
     * （盘上文件是唯一数据源，ADR-006）。
     *
     * [from] == [to] 时**原样返回**（拖动没跨过任何一行 = 没有顺序变化，不该写盘）；越界抛错。
     */
    fun moveTo(from: Int, to: Int): ServerList {
        require(from in entries.indices) { "序号越界：$from（共 ${entries.size} 条）" }
        require(to in entries.indices) { "目标位置越界：$to（共 ${entries.size} 条）" }
        if (from == to) return this
        val moved = entries.toMutableList()
        val entry = moved.removeAt(from)
        moved.add(to, entry)
        return copy(entries = moved)
    }

    companion object {

        val EMPTY = ServerList()

        /** 区服名称的格式合法性：非空、不含分隔符与换行（定位字段，要求与 FR-03 的区服名一致）。 */
        fun isValidServerName(name: String): Boolean = name.isNotBlank() &&
            name.none { it == SERVER_FIELD_SEPARATOR || it == '\n' || it == '\r' }

        /** 可选字段（区号 / 角色名 / 等级）的格式合法性：**允许为空**，非空时不得含分隔符与换行。 */
        fun isValidOptionalField(value: String): Boolean =
            value.none { it == SERVER_FIELD_SEPARATOR || it == '\n' || it == '\r' }
    }
}
