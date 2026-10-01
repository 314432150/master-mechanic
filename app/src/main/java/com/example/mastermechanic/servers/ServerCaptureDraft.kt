package com.example.mastermechanic.servers

/**
 * **一次采集的产物**：要么有一份可复核的草稿，要么只有一句"为什么没有东西可复核"。
 *
 * 放在 `servers`（纯逻辑、不依赖安卓）：帧线程（`service`）产出它、信号（`patrol`）搬运它、
 * 悬浮窗（`floating`）画它 —— 三方都只依赖这一个类型，不会互相依赖。
 */
sealed interface ServerCaptureOutcome {

    /**
     * 读到了东西 ⇒ 交给用户复核（[notice] 非空时是**顶部提示**，例如"这一帧是 N 秒前拍的"）。
     */
    data class Draft(val draft: ServerCaptureDraft, val notice: String? = null) : ServerCaptureOutcome

    /** 没有可复核的内容（画面不对 / 没框选区域 / 什么都没解析出来）⇒ 只说明情况。 */
    data class Nothing(val message: String) : ServerCaptureOutcome
}

/**
 * **采集复核草稿**（2026-09-25 用户口径：**先看后写**）：把"读到的条目"攒成一份草稿，
 * 由用户在看清楚之后**按一下才落盘**。
 *
 * ## 为什么要它
 *
 * 老流程是"读完直接写清单"：真机出过两次事故 —— 缺字读数被写进清单（第 196 条）、
 * 读的是旧画面却报"新增 0 条"（第 211 条）。根子是**写成了一次不可复核的副作用**。
 * 现在采集只产出这份草稿，写盘要用户点「写入清单」；顺带也就有了"滚一屏再读、攒齐了再写"的能力
 * （一屏只看得到 12 台，14 台必须读两屏）。
 *
 * ## 与 [ServerListCapture.merge] 的关系
 *
 * 逐条结论（新增 / 补全 / 已存在 / 冲突）**不另写一套判据**：直接拿现有清单跑一遍
 * [ServerListCapture.merge]，再按结果给每条读数盖章（区号护栏、只补空字段这些口径都在那里）。
 *
 * 纯逻辑（JVM 可测）：不碰安卓、不碰盘。
 */
data class ServerCaptureDraft(val rows: List<ServerEntry> = emptyList()) {

    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * **再读一屏**：把新读到的条目并进来（用户口径：攒齐了再一次性写入）。
     *
     * 同名条目只留**字段更全**的那条；字段一样多时留**新读到的**那条（用户刚刚对着它读的，
     * 而且它来自更晚的那一屏 —— 滚过屏之后同一台的行位置会更"正"）。
     * 其余保持先来后到的顺序（= 清单顺序，也就是游戏里的顺序）。
     */
    fun plus(captured: List<ServerEntry>): ServerCaptureDraft {
        val merged = LinkedHashMap<String, ServerEntry>()
        rows.forEach { merged[it.serverName] = it }
        captured.forEach { entry ->
            val old = merged[entry.serverName]
            if (old == null || fieldCount(entry) >= fieldCount(old)) merged[entry.serverName] = entry
        }
        return ServerCaptureDraft(merged.values.toList())
    }

    /**
     * 与现有清单对一遍：**按"平台 + 区号"归组**后逐条定夺 + 计数。
     *
     * ## 为什么要归组、为什么要单选（2026-09-25 / 09-28 用户口径）
     *
     * 同一个区号可能被读出**几种不同的名字**（`漠地绿洲` / `漢地绿洲` —— 少读 / 形近错），
     * 老做法把它们当成两台不同的服务器并排显示，用户根本不知道哪个是真的、也可能两个都写进去。
     * 现在归成一条：**这一台 = 这些读数里你选的那些字段**。
     *
     * ## 「服务器名」与「用户名」**各自独立选**（2026-09-28 用户口径）
     *
     * 同一次采集里，同一台的两个字段可能**各自**被读错（名字 `漠地绿洲`/`漢地綠洲`、
     * 用户名 `卧龙山蓝莓`/`卧龙山蓝梅`）。把它们绑在"整条读数"上就只能整条二选一 ——
     * 而用户真正想要的可能是"**这条的名字** + **那条的用户名**"。所以候选按**字段**分组（[Field]），
     * 两个字段各选各的；[choices] 的键带上字段（见 [choiceKey]）。
     *
     * 默认值（用户没点过任何单选时）在 [pickOf] 里：**先定名字**（它也是"这行是不是别的服"的依据），
     * 用户名默认跟着选中的那条读数走。
     *
     * **候选只列"读到的写法"**（2026-09-28 用户口径："不需要对比服务器中已经存在的信息"）：
     * 清单里同区号的那条**不进候选**（以前它会作为"保留清单里的"选项出现）。
     * 万一选中的读数与清单同区号却不同名，仍然由 [ServerListCapture.merge] 的**区号护栏**拦下
     * （`conflicts` 里能查到，写入后如实报一句），界面不再为此铺一堆对比文字。
     *
     * @param choices 用户在单选里挑过的项（键 = [choiceKey]，值 = 该字段选中的文本）；
     *   没挑过的字段走 [pickOf] 里的默认；挑了一个**这批读数里没有的值**时也退回默认（不猜）。
     */
    fun review(existing: ServerList, choices: Map<String, String> = emptyMap()): Review {
        val picked = groupsOf().map { group -> pickOf(group, existing, choices) }
        val toWrite = picked.map { it.chosen }
        val merge = ServerListCapture.merge(existing, toWrite)
        val added = merge.added.map { it.serverName }.toSet()
        val filled = merge.filled.toSet()
        val conflicted = merge.conflicts.map { it.captured.serverName }.toSet()
        val items = picked.map { pick ->
            val status = when (pick.chosen.serverName) {
                in conflicted -> Status.CONFLICT
                in added -> Status.NEW
                in filled -> Status.FILL
                else -> Status.SAME
            }
            Item(
                key = pick.group.key,
                title = pick.group.title,
                nameOptions = pick.nameOptions,
                characterOptions = pick.characterOptions,
                chosen = pick.chosen,
                status = status,
            )
        }
        return Review(
            items = items,
            toWrite = toWrite,
            added = added.size,
            filled = filled.size,
            same = items.count { it.status == Status.SAME },
            conflicts = merge.conflicts,
        )
    }

    /**
     * 一组读数 → 一条复核项：**先定名字、再定用户名**（名字是主键，也是"这行是不是别的服"的依据）。
     *
     * 默认值只在**读出来的这几种写法**里挑，界面上不显示任何"清单里的"对比：
     * - **名字**：① 与清单里**同区号那条同名**的读数（用户之前录入 / 确认过的名字最可能对，
     *   这么选还能顺手把它的空字段补上，也不会撞上区号护栏）；② 否则名字**更长**的
     *   （OCR 的错法以"少读"为主）；③ 再否则**最后读到的**（用户刚对着它读过）。
     * - **用户名**：**与选中的名字同一次读到的**那个（一次读数里两个字段最可能互相对应）；
     *   那次没读到就取最长的，再否则留空。
     * - **等级**：跟选中名字同一次读到的；再否则任一条非空的（等级是纯数字，读得最准）。
     */
    private fun pickOf(
        group: Group,
        existing: ServerList,
        choices: Map<String, String>,
    ): Pick {
        val names = group.readings.map { it.serverName }.distinct()
        val inListName = existing.entries.firstOrNull {
            it.hasServerNo && group.serverNo.isNotEmpty() && it.serverNo == group.serverNo &&
                it.platform == group.platform
        }?.serverName
        val longest = names.maxByOrNull { it.length }!!
        val defaultName = names.lastOrNull { it == inListName } ?: names.last { it.length == longest.length }
        val name = choices[choiceKey(group.key, Field.NAME)]?.takeIf { it in names } ?: defaultName

        val characters = group.readings.mapNotNull { it.characterName.ifEmpty { null } }.distinct()
        // 同一名字最多只有一条读数（[groupsOf] 已按名字去重）⇒ `lastOrNull` 取到的就是那条
        val sameRead = group.readings.lastOrNull { it.serverName == name && it.characterName.isNotEmpty() }
        val defaultCharacter = sameRead?.characterName ?: characters.maxByOrNull { it.length }.orEmpty()
        val character = choices[choiceKey(group.key, Field.CHARACTER)]
            ?.takeIf { it in characters } ?: defaultCharacter

        val level = group.readings.firstOrNull { it.serverName == name && it.level.isNotEmpty() }?.level
            ?: group.readings.firstOrNull { it.level.isNotEmpty() }?.level.orEmpty()
        val base = group.readings.lastOrNull { it.serverName == name } ?: group.readings.first()
        return Pick(
            group = group,
            nameOptions = names,
            characterOptions = characters,
            chosen = base.copy(serverName = name, characterName = character, level = level),
        )
    }

    /**
     * 复核里**可以独立选**的两类字段（同一张卡片里各选各的）。
     *
     * 为什么不是"整条读数二选一"：一次采集里两个字段可能**分别**读错，而用户要的往往是
     * "**这条的名字** + **那条的用户名**"（2026-09-28 用户口径："同一个服务器卡片中应支持
     * 多信息独立选中"）。
     */
    enum class Field {
        /** 服务器名（区服名）。 */
        NAME,

        /** 用户名（角色名）。 */
        CHARACTER,
    }

    companion object {

        /**
         * 单选的键：一张卡片（[Item.key]）里**每个字段各一把**，互不影响。
         *
         * 界面（`FloatingWindow`）往里写、[review] 从里读 —— 键的构造只有这一处，
         * 免得两边各写一套拼接规则。
         */
        fun choiceKey(groupKey: String, field: Field): String = "$groupKey|${field.name}"
    }

    /** 一组读数 + 它的字段候选 + 用户采纳的那条（[review] 内部的中间结果）。 */
    private data class Pick(
        val group: Group,
        val nameOptions: List<String>,
        val characterOptions: List<String>,
        val chosen: ServerEntry,
    )

    /** 一组读数（同一平台 + 同一区号）：[title] 是界面上那一行的抬头。 */
    private data class Group(
        val key: String,
        val title: String,
        val serverNo: String,
        val platform: ServerPlatform?,
        val readings: List<ServerEntry>,
    )

    /** 按"平台 + 区号"归组；**没读到区号的行各自成一组**（没法判断是不是同一台）。 */
    private fun groupsOf(): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<ServerEntry>>()
        rows.forEach { entry ->
            val key = keyOf(entry)
            val list = byKey.getOrPut(key) { mutableListOf() }
            if (list.none { it.serverName == entry.serverName }) list += entry
        }
        return byKey.map { (key, readings) ->
            val first = readings.first()
            Group(
                key = key,
                title = if (first.hasServerNo) "${first.serverNo}区" else first.serverName,
                serverNo = first.serverNo,
                platform = first.platform,
                readings = readings,
            )
        }
    }

    private fun keyOf(entry: ServerEntry): String =
        if (entry.hasServerNo) {
            "no:${entry.platform?.code ?: "?"}:${entry.serverNo}"
        } else {
            "name:${entry.serverName}"
        }

    /** 一条复核项：**一个区号一条**（= 界面上的一张卡片），带两个字段各自的候选与当前选中的那条读数。 */
    data class Item(
        val key: String,
        val title: String,
        /** 「服务器名」候选 = **读到的写法**（同一区号被读出几种名字，就列几个）。 */
        val nameOptions: List<String>,
        /** 「用户名」候选（同上）。 */
        val characterOptions: List<String>,
        /** 用户**当前采纳**的那条读数（名字 / 用户名 / 等级按上面的选择拼好）。 */
        val chosen: ServerEntry,
        val status: Status,
    ) {
        /** 「服务器名」要不要画单选（只有一种写法就没得选）。 */
        val needsNameChoice: Boolean get() = nameOptions.size > 1

        /** 「用户名」要不要画单选。 */
        val needsCharacterChoice: Boolean get() = characterOptions.size > 1

        /** 这张卡片里有没有需要用户定夺的字段（没有就走**紧凑的两行样式**）。 */
        val hasChoices: Boolean get() = needsNameChoice || needsCharacterChoice
    }

    /** 复核结论（界面按它盖章：`＋新增 / ↻补全 / ＝已存在 / ⚠冲突`）。 */
    enum class Status {
        /** 清单里没有 ⇒ 会新增。 */
        NEW,

        /** 清单里已有、这次把空字段补上（用户填过的一个字都不改）。 */
        FILL,

        /** 清单里已有且字段齐全 ⇒ 什么都不做。 */
        SAME,

        /** 同平台同区号但名字不同（多半是同一台的另一种读法）⇒ **不写入**，由人复核。 */
        CONFLICT,
    }

    /** 复核结果：逐条 + 计数 + "真要写进清单的那批读数"。 */
    data class Review(
        val items: List<Item>,
        /** 用户**采纳**的那批读数 —— `writeCaptureDraft` 就是拿它去 merge 的（没选读数的不在这里）。 */
        val toWrite: List<ServerEntry>,
        val added: Int,
        val filled: Int,
        val same: Int,
        val conflicts: List<ServerListCapture.Conflict>,
    ) {
        /** 这次点「写入清单」真的会改动清单吗（0 条时按钮就没有意义）。 */
        val writesAnything: Boolean get() = added > 0 || filled > 0
    }

    private fun fieldCount(entry: ServerEntry): Int =
        (if (entry.hasPlatform) 1 else 0) +
            (if (entry.hasServerNo) 1 else 0) +
            (if (entry.hasCharacterName) 1 else 0) +
            (if (entry.hasLevel) 1 else 0)
}
