package com.example.mastermechanic.friends

/**
 * 编码用的字段分隔符：与巡查配置 / 服务器清单同源口径 —— 字段里不允许出现，编码层显式拒收，
 * 不做静默替换。
 *
 * 现在一条只有一个字段，`|` 出现在里面其实没有歧义；**仍然禁**：它是这个行式格式里将来分隔字段用的符号，
 * 一旦给好友加第二个字段（备注之类），今天放进去的 `|` 就会把一条读成两条。
 */
internal const val FRIEND_FIELD_SEPARATOR = '|'

/**
 * 一条好友（FR-07「拜访」三级列表的数据源；2026-09-15 用户口径「新建独立好友清单」）。
 *
 * **为什么要单独这一份数据**（三选一用户拍板的结果，理由记下来避免以后重判）：
 * - 好友与**区服无关**（在哪个服务器上玩、和想拜访谁，是两件事）→ 塞进服务器清单会把两者绑死；
 * - 巡查项（FR-03）里的好友名是"给某个区服配一个好友"，拿来当三级列表的来源 → 同一份名字两处漂移；
 * - 代价（用户已知并接受）：多一处要手工维护的数据。
 *
 * [name] 会被**直接用于界面定位**（红线 3：名字不唯一或找不到就停下，不许挑"最像的"），
 * 因此只做**格式**校验 —— 非空、不含分隔符与换行，**绝不改写**用户输入
 * （改写了就不再是屏幕上的那个名字，定位必然失准）。
 */
data class FriendEntry(
    val name: String,
) {

    init {
        require(FriendList.isValidName(name)) {
            "好友条目非法：好友名称不得为空，且不得含「${FRIEND_FIELD_SEPARATOR}」或换行"
        }
    }

    companion object {

        /**
         * 从用户输入构造：**先去首尾空白再校验** —— 界面不该因为多敲一个空格就拒收，
         * 但中间的空格必须原样保留（游戏里的名字可能就带空格）。
         */
        fun of(name: String): FriendEntry = FriendEntry(name.trim())
    }
}

/**
 * 好友清单（FR-07 拜访三级列表的数据源，纯逻辑、无安卓依赖）：**有序**条目列表。
 *
 * - **同一个好友只能有一条**——重复录入没有意义（拜访对象重复只会让人误点两次），
 *   **唯一性是构造时不变量**（模仿 FR-10「一个区服只有一个小号」的做法）：构造 / 新增 / 插入 /
 *   改写 / 读盘只要出现重复就抛 [IllegalArgumentException]，界面据此**明确提示**，
 *   绝不静默去重（静默去重会让用户以为改了，实际被丢掉一条）；
 * - **空清单合法**（「整体清空」的结果）—— 与标定产物不同，这里没有"必须有内容"的前置；
 * - 编辑操作一律**返回新实例**（不可变），便于界面替换 state 触发重组、也便于单测逐项断言；
 * - 越界序号一律抛 [IllegalArgumentException]（按钮本就不该被点到，静默忽略会掩盖界面 bug）。
 */
data class FriendList(
    val entries: List<FriendEntry> = emptyList(),
) {

    init {
        val duplicated = entries.groupingBy { it.name }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
        require(duplicated == null) { "同一个好友只能有一条：${duplicated?.key}" }
    }

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    /** 追加到末尾。重名时由构造检查抛错（同一个好友只能有一条）。 */
    fun add(entry: FriendEntry): FriendList = copy(entries = entries + entry)

    /** 插入到指定位置（0..size）。 */
    fun insert(index: Int, entry: FriendEntry): FriendList {
        require(index in 0..entries.size) { "插入位置越界：$index（共 ${entries.size} 条）" }
        return copy(entries = buildList {
            addAll(entries.subList(0, index))
            add(entry)
            addAll(entries.subList(index, entries.size))
        })
    }

    /**
     * 改写指定条目。
     *
     * 改的是**自己这一条**（名字没动）→ 正常放行；把名字改成**别的条目**用着的 → 构造检查抛错。
     * （编辑时只允许改名字，所以"改自己"和"撞名"就这两种情况。）
     */
    fun update(index: Int, entry: FriendEntry): FriendList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        return copy(entries = entries.toMutableList().also { it[index] = entry })
    }

    /** 该好友名称在清单里的位置；没有则 -1（界面据此在保存前给出明确提示）。 */
    fun indexOfName(name: String): Int = entries.indexOfFirst { it.name == name }

    /** 删除指定条目。 */
    fun remove(index: Int): FriendList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        return copy(entries = entries.toMutableList().also { it.removeAt(index) })
    }

    /**
     * 调整顺序：[delta] = -1 前移 / +1 后移。
     * 已在端点时**原样返回**（不是错误 —— 界面上的端点按钮就该是无效动作）。
     */
    fun move(index: Int, delta: Int): FriendList {
        require(index in entries.indices) { "序号越界：$index（共 ${entries.size} 条）" }
        require(delta == -1 || delta == 1) { "只支持前移（-1）/ 后移（+1）：$delta" }
        val target = index + delta
        if (target !in entries.indices) return this
        val moved = entries.toMutableList()
        val entry = moved.removeAt(index)
        moved.add(target, entry)
        return copy(entries = moved)
    }

    /** 整体清空。 */
    fun clear(): FriendList = copy(entries = emptyList())

    companion object {

        val EMPTY = FriendList()

        /** 好友名称的格式合法性：非空、不含分隔符与换行（它会直接用于界面定位，要求严一点）。 */
        fun isValidName(name: String): Boolean = name.isNotBlank() &&
            name.none { it == FRIEND_FIELD_SEPARATOR || it == '\n' || it == '\r' }
    }
}
