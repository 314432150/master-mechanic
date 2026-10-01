package com.example.mastermechanic.preset

/**
 * 拜访时「用哪个服务器」的策略（2026-09-16 用户口径：服务器与好友都是**配置项**）。
 *
 * 用户的真实场景：**在同一个好友的农场里，轮流换不同服务器的小号来看他** ——
 * 所以最常用的策略是 [NEXT]（按服务器清单顺序换下一个），而不是每次手选。
 */
enum class ServerChoice {
    /** 按**服务器清单顺序**换下一个（日常路径）。 */
    NEXT,

    /** 固定用某个区服（配了 [VisitPreset.serverName]）。 */
    FIXED,
}

/**
 * 「一键拜访」的预设（2026-09-16 用户口径「建立预设配置」）。
 *
 * **为什么不再复用跑号清单**：跑号清单是「区服 → 好友」的**一对多**映射表（一个好友会配好几个区服），
 * 从菜单上点"拜访"时**定位不到具体是哪个好友** —— 所以这里单独存**一个**明确的预设：
 * "用哪个服务器（策略）+ 见谁"，菜单一级点一次就执行整条流程。
 *
 * 名字只做**格式**校验（非空或留空、不含分隔符与换行）—— 它会被直接拿去界面定位
 * （红线 3：名字不唯一/找不到就停下，绝不挑"最像的"），所以**绝不改写**用户输入。
 * `friendName` 允许为空，表示"还没配好"（界面据此提示去配，而不是静默失败）。
 */
data class VisitPreset(
    val serverChoice: ServerChoice = ServerChoice.NEXT,
    /** 只有 [ServerChoice.FIXED] 时有值。 */
    val serverName: String = "",
    /** 要拜访的好友；空 = 还没配。 */
    val friendName: String = "",
) {

    /** 配好了没：好友必填；固定区服时区服名也必填。 */
    val isReady: Boolean
        get() = friendName.isNotEmpty() &&
            (serverChoice == ServerChoice.NEXT || serverName.isNotEmpty())

    /**
     * 好友改名后**跟着改**（2026-09-22 用户口径：改名不能只在好友清单里生效）。
     *
     * 名字是**跨文件的引用键**：这里存的名字会被丢给界面定位（红线 3：全等 + 唯一），
     * 好友清单一改名、这里不改，预设就指向一个**不存在的人** —— 表现是运行时永远认不出目标好友，
     * 而两处文件看起来都"没问题"（事后极难排查）。
     *
     * 只改**正好等于**旧名的那一项（不做模糊匹配，与红线 3 同一口径）；没引用则原样返回
     * （调用方据此决定要不要落盘）。
     */
    fun renamedFriend(oldName: String, newName: String): VisitPreset =
        if (oldName != newName && friendName == oldName) copy(friendName = newName) else this

    /**
     * 区服改名后**跟着改**（2026-09-22 用户口径，与 [renamedFriend] 同一套口径）。
     *
     * 区服名是**唯一参与定位的字段**（红线 3）—— 这里存的是清单里那一份的副本，清单改了它不改，
     * 换号那一步就会拿着一个**不存在的区服名**去找，表现是"永远找不到区服、流程中止"。
     *
     * 只改 [serverName]：**策略（顺序轮换 / 固定区服）与好友一概不动** ——
     * 改名 = 同一台小号换了区服名，"见谁""怎么选服"都不该跟着变。
     * 策略是顺序轮换时 [serverName] 本来就是空串 → 天然不命中（这是"没引用"，不是错误）。
     */
    fun renamedServer(oldName: String, newName: String): VisitPreset =
        if (oldName != newName && serverName == oldName) copy(serverName = newName) else this

    init {
        require(isValidName(serverName)) { "预设非法：区服名称不得含「$PRESET_FIELD_SEPARATOR」或换行" }
        require(isValidName(friendName)) { "预设非法：好友名称不得含「$PRESET_FIELD_SEPARATOR」或换行" }
        // 注意：**不要求** FIXED 时一定有区服名 —— 设置页里的草稿本来就会经过"已切成指定、
        // 还没选区服"这个中间态（2026-09-16 真机闪退就是这个约束把草稿拒了）。
        // "能不能执行"由 [isReady] 判定，而不是靠构造时抛异常。
    }

    companion object {

        val EMPTY = VisitPreset()

        /** 名称的格式合法性：允许为空（= 未配置），但不允许分隔符与换行。 */
        fun isValidName(name: String): Boolean =
            name.none { it == PRESET_FIELD_SEPARATOR || it == '\n' || it == '\r' }

        /** 从用户输入构造：去首尾空白后校验（界面不该因为多敲一个空格就拒收）。 */
        fun of(
            serverChoice: ServerChoice,
            serverName: String,
            friendName: String,
        ): VisitPreset = VisitPreset(
            serverChoice = serverChoice,
            serverName = serverName.trim(),
            friendName = friendName.trim(),
        )
    }
}
