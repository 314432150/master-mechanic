package com.example.mastermechanic.patrol

/**
 * 名称定位（M4-T4-3）：在识别到的候选里，按名字找到**唯一**的那个。
 *
 * ## 两条路径，统一成一句口径：**去空白 → 取语义字段 → 全等**（2026-09-24 用户拍板）
 *
 * | 路径 | 语义字段 | 入口 |
 * | --- | --- | --- |
 * | **区服**（第 5 步） | 第一个「区」**之后**那一段 —— 屏幕行 `微信395区 莲动渔舟` → `莲动渔舟` | [locateRealmName] |
 * | **好友**（第 9 步） | 括号里的**备注名** —— 屏幕行 `克克雨儿(阿娜雅)` → `阿娜雅` | [friendRemarkHits] |
 *
 * 为什么都得"取字段"再比：屏幕上读到的是**整行**，而清单里配的只是名字本身
 * ⇒ 拿配置的名字去跟整行全等**必然落空**；而拿配置的名字去"包含"又会误命中
 * （`龙腾` 撞上 `龙腾盛世`）。
 *
 * 为什么判别力来自**全等**而不是长度闸：区服名有 **2 字 / 3 字**的（真机 `战利品`），
 * 靠"最小长度"会把合法名字一起误杀；全等本身既防误命中，又对短名字安全。
 *
 * ## "包含"这一档已于 2026-09-24 删除
 *
 * 曾经好友那条走"识别文本 ⊇ 目标"（用户 2026-09-22 口径：OCR 会把非文本读成文本）。
 * 现在 App 里配的名字 = 游戏中的**备注名**（无特殊字符）⇒ 病灶从数据侧消失；
 * 而"包含"留在手里会**把半行当成整行命中**（`微信399区 龙腾盛世` ⊇ `龙腾`），是误点错的隐患。
 * 见 `docs/progress.md` 第 181 / 183 条。
 *
 * ## 红线 3：**区服**仍"必须唯一"；**好友**多了一条用户口径的例外（2026-09-30）
 *
 * - **区服（第 5 步）**：0 个 → 未找到；≥2 个 → 未找到。绝不"挑最像的那个"——
 *   进错区服是不可逆的（[locateRealmName] / [locateAcrossRealmName] 的口径一个字没动）；
 * - **好友（第 9 步）**：屏幕上读到**多个完全同名**时 **取最上面那一个**（2026-09-30 用户口径，
 *   见 `docs/requirements.md`「口径修订十」）—— 真机原样是"同一好友的**多个小号共用同一个备注**"
 *   （`克克雨儿(阿娜雅)` / `克克洛儿(阿娜雅)`）。放宽的**只有"同名多行怎么选"**：
 *   命中判定仍是"取括号内备注 + 全等"，**不是**"挑最像的那个"（后者才是红线 3 要防的）。
 *
 * 纯逻辑：候选的"位置"用泛型 [T] 承载（真机上是坐标，单测里可以是任意标记），本文件不碰坐标 ——
 * 所以"取最上面那条"由调用方落地（[friendRemarkHits] 只交全部命中，见它的注释）。
 */
object NameLocator {

    /** 识别层给出的一条候选：[name] 是屏幕上的名字，[payload] 是拿它去点击所需的东西。 */
    data class Candidate<T>(val name: String, val payload: T)

    /** 定位结果。 */
    sealed interface Result<out T> {
        /** 唯一命中。 */
        data class Found<T>(val payload: T) : Result<T>

        /** 未找到：[detail] 说明是"一个都没有"还是"有好几个"，界面据此提示用户（不静默）。 */
        data class NotFound(val detail: String) : Result<Nothing>
    }

    /**
     * 滚动查找的次数上限（FR-04 硬性要求 4：超出即中止并提示）。
     *
     * **2026-10-01 由 10 改为 5（用户拍板）**：真机实测"一屏约 12 行"、目标通常 1~2 屏内，
     * 5 屏（≈5 次滑动 ≈8~10 秒）足够覆盖"列表被滑到别处 + 逐屏往下找"的正常情形；
     * 超出即**如实停下并提示**（红线 3：不许无限滚、不许猜）。第 5 / 9 步同一口径
     * （`ServerListScan.MAX_SCROLLS` 是它的孪生常量，两处必须一致）。
     */
    const val MAX_SCROLLS = 5

    /** 名字的归一化：只去首尾空白（中间的空格由 [squash] 另行处理）。 */
    fun normalize(name: String): String = name.trim()

    /**
     * 比对形态：**去掉所有空白**。
     *
     * 为什么连中间空格也去掉：OCR 常在字与字之间插空格 / 换行（真机同一批里
     * `微信395区 莲动渔舟` 有空格、`微信56区白色死神` 没有）⇒ 不统一去空白，
     * 就会出现"有的行能对上、有的行对不上"。
     * 只影响"怎么比"，不影响点击落点（落点用的是原始框）。
     */
    private fun squash(name: String): String = name.filterNot { it.isWhitespace() }

    /**
     * 在**一屏**的候选里精确定位 [target]（**全等**）。
     *
     * ⚠ 这是"整条候选文本 == 目标"的**底层原语**：两条真实路径（区服 / 好友）都先取语义字段
     * 再交给它比（[locateRealmName] / [friendRemarkHits]）。
     * **别把"取字段"的规则做进这里** —— 那是**调用方的策略**，做进来会连带改掉本函数
     * "整条文本全等"的语义（2026-09-24 试过一次，6 条既有用例一起红，已回退）。
     *
     * @return 唯一命中 → [Result.Found]；一个都没命中、或命中多个 → [Result.NotFound]。
     */
    fun <T> locate(target: String, candidates: List<Candidate<T>>): Result<T> {
        val wanted = normalize(target)
        require(wanted.isNotEmpty()) { "定位目标不能为空" }
        val hits = candidates.filter { normalize(it.name) == wanted }
        return when (hits.size) {
            1 -> Result.Found(hits[0].payload)
            0 -> Result.NotFound("没找到「$wanted」")
            else -> Result.NotFound("「$wanted」在屏幕上有 ${hits.size} 个，分不清是哪一个（停下就会点错）")
        }
    }

    /**
     * **区服名定位**（T4-9 批 3，2026-09-24 用户口径：**去空白 → 取第一个「区」之后 → 全等**）。
     *
     * 屏幕读到的是整行 `微信395区 莲动渔舟`，而服务器清单里配的是 `莲动渔舟`；
     * 取「区」之后那一段再全等 ⇒ `微信399区 龙腾盛世` 不会命中目标 `龙腾`（本想用长度闸防的就是这个，
     * 而长度闸会误杀 2 字 / 3 字的合法区服名）。用户确认：**「区」字一定存在**。
     *
     * ⚠ 去重（同一行的"整行 vs 行内词"）由调用方负责：`NameLocating.locateRealm` 会先按行合并，
     * 否则整行与行内词会各命中一次 ⇒ 被误判成"不唯一"。
     */
    fun <T> locateRealmName(target: String, candidates: List<Candidate<T>>): Result<T> {
        val wanted = squash(target)
        require(wanted.isNotEmpty()) { "定位目标不能为空" }
        return locate(wanted, candidates.map { Candidate(realmNameOf(it.name), it.payload) })
    }

    /**
     * 取一行里**第一个「区」之后**的部分（去空白）。没有"区" ⇒ 返回整行去空白（退化，不猜）。
     *
     * 公开给日志与提示用（2026-09-24）：第 5 步"没找到"时把**本轮读到的名字段**摊在提示里
     * （`CaptureService.readNamesHint`），用的就是这个函数 —— 保证用户看到的与匹配器看到的是同一份，
     * 不然"提示里的名字"与"判定用的名字"又是两套口径（真机排障时最容易踩这种坑）。
     * **它只取值、不判定**：命中规则仍是"取完字段 + 全等 + 唯一"。
     */
    fun realmNameOf(line: String): String {
        val flat = squash(line)
        val at = flat.indexOf('区')
        return if (at < 0) flat else flat.substring(at + 1)
    }

    /** 行首的"平台 + 区号"（`微信395区 …` / `QQ56区…`）。 */
    private val REALM_NUMBER = Regex("""(微信|QQ|qq)\s*(\d+)""")

    /**
     * 取一行里的**区号数字串**（`微信395区 莲动渔舟` ⇒ `395`）；取不到 = 空串。
     *
     * ## 它为什么存在（2026-09-25 实测后的口径）
     *
     * 真机实测（`docs/progress.md` 第 208 条）：**数字**这一栏文字识别**每次都读对**
     * （12/12 行与数字模板的读数完全一致），读错的只有**汉字的区服名**；而数字模板自己还有 ~17% 的行读不出。
     * ⇒ 第 5 步的"区号做第二字段"就很便宜：**直接从同一行的文本里取数字**，
     * 不需要任何模板（省掉采集 / 存储 / 覆盖率损失）。
     *
     * ⚠ 这里**只取值、不判定**：命中规则仍是"名称全等优先 → 名称没命中才看区号 → 矛盾/歧义一律停下"
     * （见 [RealmPickDecision]）。
     */
    fun realmNumberOf(line: String): String =
        REALM_NUMBER.find(line)?.groupValues?.get(2).orEmpty()

    /**
     * **好友（备注名）的全部命中**（T4-9 批 3 的字段口径 + 2026-09-30 的"多个同名"口径）。
     *
     * 行格式 = `游戏昵称(微信备注)`，而 App 里配的名字**就是游戏中的备注名** ⇒ 只比括号里那一段。
     * 括号规则与 [FriendListOcrProbe.remarkIn] 逐条一致（认 ASCII `()` 与全角 `（）`、取**最后一个**左括号、
     * 允许缺右括号）。**取不到字段（括号没读出来）⇒ 不命中 ⇒ 停下**，由日志给出证据（红线 3）。
     *
     * ## 为什么返回"全部命中"而不是唯一结论（2026-09-30 用户口径）
     *
     * 好友这条**不再要求"唯一"**：屏幕上读到**多个完全同名**时取**最上面**那一个
     * （用户原话："当屏幕上识别到多个相同的好友时，去最上面的那个"；`docs/requirements.md`「口径修订十」）。
     * 挑哪一条需要**位置**，而本类**故意不碰坐标**（位置由泛型 [T] 承载）⇒ 所以这里只把命中**原样交出去**，
     * 由调用方（`NameLocating.locateFriend`，它拿得到 `NameCandidate` 的 y）挑最上面那条。
     *
     * ⚠ **区服那条（[locateRealmName]）没有变**：0 个 / ≥2 个都算未找到（进错区服的代价不可逆）。
     * ⚠ 去重同 [locateRealmName]：调用方（`NameLocating.locateFriend`）先按行合并候选。
     */
    fun <T> friendRemarkHits(target: String, candidates: List<Candidate<T>>): List<Candidate<T>> {
        val wanted = squash(target)
        require(wanted.isNotEmpty()) { "定位目标不能为空" }
        return candidates.filter { squash(remarkOf(it.name).orEmpty()) == wanted }
    }

    /**
     * **取一行里的备注名**（取不到 ⇒ `null`；公开版）—— 与 [friendRemarkHits] 用的是同一套括号规则
     * （认 ASCII `()` 与全角 `（）`、取**最后一个**左括号、允许缺右括号）⇒ **字段提取只写一处**。
     *
     * 谁在用：好友列表**跨屏查找的行键**（`NameLocating.friendRowKeyOf`）。判"滑了一屏、画面到底动没动"
     * 靠的是**行身份**，而整行里带着在线状态 / 段位这些随时会变的字（同一行两次读出的整行往往不同
     * ⇒ 永远判成"画面动了"⇒ 扫描再也判不出"已到底"）⇒ 必须回到同一个语义字段：备注名。
     */
    fun friendRemarkOf(line: String): String? = remarkOf(line)

    /**
     * **取一行里的游戏昵称**（`克克雨儿(阿娜雅) 荣耀黄金 离线` ⇒ `克克雨儿`；取不到 ⇒ 整行去空白）。
     *
     * 谁在用：好友列表**跨屏查找的行键**（`NameLocating.friendRowKeyOf`）。为什么不拿备注名当行键
     * （2026-10-01 真机实测踩的坑）：**备注名不是每行都有**（很多好友没设备注）⇒ 一屏 11 行只凑出
     * **3 个键** ⇒ "这一屏与上一屏重合 2/3 行（66%）" 这种**2 行样本**就够触发"同一屏"判据
     * ⇒ 真机把"换了一屏"判成"**已到底**"、把列表中途当成底部，直接中止说"找不到"✗。
     * 昵称**每行都有**、且与备注一样是该行的身份 ⇒ 行键用它（备注仍只管命中判定，见 [friendRemarkHits]）。
     *
     * 括号规则与 [remarkOf] 同源（认 ASCII `()` 与全角 `（）`、取**第一个**左括号之前的全部内容）。
     *
     * ⚠ **没有括号时取"第一个空白分词"**（`李剑聪 荣耀黄金 离线` ⇒ `李剑聪`），而不是整行：
     * 整行里带着**在线状态 / 段位**这些随时会变的字，同一个人的行两次读出来就不同 ⇒ 行键失效
     * （那正是"判不出画面动没动"的老毛病）。行内的空格由 OCR 插入，真机行都带空格（`克克雨儿(阿娜雅) 荣耀黄金 离线`）；
     * 万一整行连一个空格都没有 ⇒ 退回整行（退化，不猜）。取不到有效内容 ⇒ `null`。
     */
    fun friendNicknameOf(line: String): String? {
        val flat = squash(line)
        if (flat.isEmpty()) return null
        val open = minOf(
            flat.indexOf('(').takeIf { it >= 0 } ?: flat.length,
            flat.indexOf('（').takeIf { it >= 0 } ?: flat.length,
        )
        if (open in 1 until flat.length) return flat.substring(0, open)
        // 没有括号（或括号就在开头）⇒ 取**原始行**的第一个空白分词
        // ⚠ 这一步必须用 `line` 而不是 `flat`：`squash` 已经把空格去掉了，拿它分词永远只有一个词 ✗
        val token = line.trim().split(' ', '\t', '\n').firstOrNull { it.isNotBlank() }
        return squash(token.orEmpty()).ifEmpty { flat }
    }

    /**
     * **好友列表的"行键"**：一行 → 一个身份字符串（取不到 ⇒ `null`）——"画面动没动""那一行还在不在原地"
     * 全靠它认身份（跨屏扫描的重合度判据、第 9 步**开火前复眼**都用同一把键，只写这一处）。
     *
     * ## 两级：**先括号里**（[friendRemarkOf]），**没有括号才退回括号前**（[friendNicknameOf] 去噪）
     *
     * 括号里那一段 = App 里配的目标名的出处（见 [friendRemarkHits]）⇒ 真机**每次读都一模一样**；
     * 括号前那一段是游戏昵称 + **行首那个小图标**，而那个图标会被 OCR 读成各种符号。
     *
     * ## 为什么改（2026-10-01 真机事故：搜索明明早找到了，却卡了 55 秒才点下去）
     *
     * 用户报"滑屏到了、也显示了好友，但**花了很长时间才找到**"。日志里目标 06:38:48 就已在屏上，
     * 而复眼从 06:38:48 一直否决到 06:39:43，同一行连续 30+ 次读出的**括号前**那一段一直在变：
     * ```
     * 、星月晚. (星月晚)      -星月晚. (星月晚)      "星月晚. (星月晚)      ·星月晚. (星月晚)
     * ```
     * （括号里那一段**每次都是 `星月晚`**）⇒ 旧口径拿"括号前"当行键 ⇒ 每一次都与命中时的键对不上
     * ⇒ 复眼每轮都判"那一行已经不在了"⇒ **一直不开火**（最后撞上第 9 步 60 秒超时才罢）。
     *
     * ## 为什么不是"只用括号里"
     *
     * 括号（备注）**不是每行都有**（很多好友没设备注）⇒ 只用它会丢键、样本不足以判"重合度"
     * （2026-10-01 踩过：一屏 11 行只凑出 3 个键 ⇒ 把"换了一屏"误判成"已到底"）。
     * 所以两级：**有括号用括号（稳），没括号用括号前并切掉首尾的非文字符号**（`、星月晚.` ⇒ `星月晚`）——
     * 既稳又每行都有键。⚠ 也不能用**整行**（带在线状态 / 段位，两次读就不同 ⇒ 永远判成"画面动了"）。
     */
    fun friendRowKeyOf(line: String): String? = friendRowKeyAliasesOf(line).firstOrNull()

    /**
     * 同一行的**全部可接受身份键**（有序：**第一个是正解** = [friendRowKeyOf] 的值）。
     *
     * ## 为什么不止一把
     *
     * ① **括号里那一段**是正解（App 里配的目标名的出处，真机每次读都一致）；
     * ② **括号前那一段（切掉首尾非文字符号）**是兜底 —— 用在两个地方：
     * - **没有括号的行**（很多好友没设备注）⇒ 只得靠它，否则该行无键（样本不够判"重合度"）；
     * - **复眼只在那一行的窄带里重读**（±1 个名字高）⇒ 万一只读到半个括号（`、星月晚. (星月`），
     *   括号字段会变成半截（`星月` ✗）⇒ 拿正解去比就"对不上"、又白等一轮；而括号前那把键
     *   （`星月晚` ✓）仍然认得出是同一行。
     *
     * ⚠ **命中判定（点谁）不看这个**：那条链只用括号里那一段并**全等**（[friendRemarkHits]），
     * 本文这只影响"画面动没动 / 那一行还在不在原地"，**永远不会因为兜底键而点到别人**。
     */
    fun friendRowKeyAliasesOf(line: String): Set<String> {
        val keys = LinkedHashSet<String>()
        // ① 括号里那一段（真机实测每次读都一致；它来自"配置的名字"，含不含汉字都算数）
        friendRemarkOf(line)?.let { remark ->
            squash(remark).takeIf { it.isNotEmpty() }?.let(keys::add)
        }
        // ② 括号前那一段（清洗见 [cleanNameKey]）
        friendNicknameOf(line)?.let { nickname ->
            val cleaned = cleanNameKey(squash(nickname))
            // ⚠ **不含汉字的行键不参与身份判断**：真机里那种是 OCR 噪声行（`出8400` / `8400` / `OO`），
            // 同一行两次读出来完全不同 ⇒ 只会把"同屏重合度"拉低、让回顶白滑、还会误判"画面在动"
            // （2026-10-01 真机：列表明明在顶部，4 次读数一模一样，重合度却只有 36~50%）。
            if (cleaned.isNotEmpty() && cleaned.any { isCjk(it) }) keys.add(cleaned)
        }
        return keys
    }

    /**
     * 行键清洗：① 切掉首尾的非文字符号（行首图标常被读成 `、` `-` `"` `·` `,`）；
     * ② 去掉**行首那个孤立的拉丁字母** —— 真机实录同一行被读成 `B东百万` / `p东百万` / `东百万`，
     * 就是它让"同一屏"的重合度掉到 36~50%（2026-10-01 用户报"滑到顶为什么重复滑了几次"）。
     * ⚠ 只在"**一个**字母 + 汉字"时才切（`Boss~~喵` 这种真名原样保留）。
     */
    private fun cleanNameKey(raw: String): String {
        val trimmed = raw.trim { !it.isLetterOrDigit() }
        val junkLetterPrefix = trimmed.length >= 2 && isAsciiLetter(trimmed[0]) && !isAsciiLetter(trimmed[1])
        return if (junkLetterPrefix) trimmed.substring(1) else trimmed
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

    /** 汉字（CJK 统一表意文字）—— 判"这个行键是不是噪声"用，见 [friendRowKeyAliasesOf]。 */
    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /** 取一行里**最后一个左括号之后**的内容（到右括号或行尾），去空白；取不到 / 空 ⇒ null。 */
    private fun remarkOf(line: String): String? {
        val flat = squash(line)
        val open = maxOf(flat.lastIndexOf('('), flat.lastIndexOf('（'))
        if (open < 0) return null
        val rest = flat.substring(open + 1)
        val asciiClose = rest.indexOf(')').takeIf { it >= 0 } ?: rest.length
        val wideClose = rest.indexOf('）').takeIf { it >= 0 } ?: rest.length
        val remark = rest.substring(0, minOf(asciiClose, wideClose))
        return remark.ifEmpty { null }
    }

    /**
     * **跨屏**定位（列表需要滚动时用）：把所有已看到的候选**合起来**判一次。
     *
     * 为什么必须合起来判：同一个名字如果在前一屏和后一屏各出现一次，那它仍然是"不唯一" ——
     * 看到第一个就点下去，恰恰是 FR-04 要防的那类误点。
     *
     * 调用方负责"滚几屏"，并用 [MAX_SCROLLS] 兜住上限。
     */
    fun <T> locateAcross(pages: List<List<Candidate<T>>>, target: String): Result<T> =
        locate(target, pages.flatten())

    /** [locateRealmName] 的跨屏版本（区服列表需要滚动时用）。唯一性要求同 [locateAcross]。 */
    fun <T> locateAcrossRealmName(pages: List<List<Candidate<T>>>, target: String): Result<T> =
        locateRealmName(target, pages.flatten())
}
