package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Template
import java.util.Base64
import java.util.Locale

/**
 * 标定产物编解码（T1-5a / T2-3，纯逻辑）：文本行式格式，确定性（同数据 → 同文本，§5-4）。
 *
 * 格式（**v4**，每行一条记录；`#` 开头与空行忽略）：
 * ```
 * format=mm-calibration                           格式标记
 * version=4                                       格式版本
 * frame=<宽>x<高>                                   标定帧尺寸（像素）
 * params=<命中线>,<差距线>,<峰值间距>                 匹配参数
 * state=<UiState 枚举名>|<标志 ID>[,<ID>…]           界面标志 → 状态（参与状态判定）
 * anchor=<UiState 枚举名>|<锚点 ID>[,<ID>…]          动作锚点 → 归属状态（按当前状态启用，只回答点哪里）
 * signal=<ID>|<角色>|<窗口>|<用途>|<归属好友>|<备注>  搜索窗口（比例 0..1）；角色 = marker / anchor
 * template=<ID>|<角色>|<宽>|<高>|<Base64 灰度像素>   模板（同一记录可多个样式）
 * ```
 *
 * **v4 相对 v3 只多了一段「归属好友」**（可空，2026-09-21）：好友名是开放集、可能含空格与特殊字符
 * （实例 `Boss~~喵`），不能当用途名，而又需要"这条头像模板是谁的"来区分多位好友 ⇒ 单列一段。
 * **v3 产物仍可读**（缺这一段 = 没写归属好友），所以这次升版**不需要把已标定内容重做一遍**。
 *
 * **ID = 元素槽位名**（`CalibrationSignals.targetFor`）：锚点用**用途名**（`hall_farm`），
 * 标志用 `<界面>_e[n]`；同一个元素的两条记录（标志 + 锚点）**共用同一个 ID**，
 * 这也是列表页把两条凑成一行的依据。用途（仅锚点，给机器看的契约）与备注（可空、可重复，
 * 给人看的文字）恒定占位（空则空串）以保证段数固定。
 *
 * 版本（T2-3 / 2026-09-19）：**写出恒为 v3、也只读 v3**。v1（无角色）与 v2（名字即主键）已弃——
 * 用户口径是"旧产物全部删掉重标"，与其让读出来的东西与新的身份规则对不上，不如明确报错并说清怎么办。
 *
 * 其余解析严格：格式标记不符 / 版本不受支持 / 字段非法 / 重复定义一律拒绝（IllegalArgumentException，
 * 消息带行号），不做静默兼容——产物格式漂移必须显式暴露，禁止按猜测继续（红线 3 同源口径）。
 */
object CalibrationCodec {

    private const val FORMAT_TAG = "mm-calibration"

    /** 当前写出格式版本。 */
    private const val VERSION = 4

    /**
     * 可读取的版本：**v3（旧）与 v4（当前）**。
     *
     * v1（无角色）与 v2（名字即主键）**不再兼容**（2026-09-19 用户口径：旧产物全部删除重标）。
     *
     * v4 相对 v3 只是在 signal 行**多了一段「归属好友名」**（可空），
     * 所以 **v3 照样能读**（缺这一段 = 没写归属好友）—— 不必为了新增一个字段让用户把标定全部重做一遍。
     */
    private val ACCEPTED_VERSIONS = setOf(3, VERSION)

    private const val SEP = "|"
    private const val LIST_SEP = ","

    /** 产物 → 文本（单例字段随后段记录，顺序固定，保证确定性）。 */
    fun encode(data: CalibrationData): String = buildString {
        appendLine("# MasterMechanic 标定产物：模板 / 搜索窗口 / 匹配参数全部来自目标设备标定（T1-5、T2-3）")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("frame=${data.frameWidth}x${data.frameHeight}")
        appendLine(
            "params=${num(data.params.matchThreshold)},${num(data.params.ambiguityMargin)}" +
                ",${data.params.peakMinDistance}",
        )
        data.stateRules.forEach { rule ->
            // 只有标志进 state= 行；锚点单独一行（同一状态可无标志——例如只在弹窗上点一下才标了锚点）
            if (rule.signalNames.isNotEmpty()) {
                appendLine("state=${rule.state.name}$SEP${rule.signalNames.joinToString(LIST_SEP)}")
            }
        }
        data.stateRules.forEach { rule ->
            if (rule.anchorNames.isNotEmpty()) {
                appendLine("anchor=${rule.state.name}$SEP${rule.anchorNames.joinToString(LIST_SEP)}")
            }
        }
        data.signals.forEach { signal ->
            // 用途 / 归属好友 / 备注**恒定占位**（空则空串）：段数固定才能按版本号分开解析
            appendLine(
                "signal=${signal.id}$SEP${signal.role.token}$SEP${num(signal.window.left)}" +
                    ",${num(signal.window.top)},${num(signal.window.right)},${num(signal.window.bottom)}" +
                    "$SEP${signal.purpose.orEmpty()}$SEP${signal.friend.orEmpty()}$SEP${signal.note}",
            )
            signal.templates.forEach { template ->
                // 模板行按「ID + 角色」归属：同一个 ID 的标志与锚点是两条记录，各有自己的模板
                appendLine(
                    "template=${signal.id}$SEP${signal.role.token}$SEP${template.width}$SEP" +
                        "${template.height}$SEP${Base64.getEncoder().encodeToString(template.pixels)}",
                )
            }
        }
    }

    /** 文本 → 产物（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): CalibrationData {
        // 预扫版本：signal 行的段数随版本变化（v4 起多一段「归属好友名」），得先知道版本才能分段
        val declaredVersionInText = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("version=") }
            ?.removePrefix("version=")
            ?.trim()
            ?.toIntOrNull()
        // **版本先验**：版本不对就别往下解析了 —— 否则旧版产物会先撞上"段数不对"这类误导性错误，
        // 而不是"这是旧版、请删除产物后重新标注"这句能照着做的提示（2026-09-19 用户口径）
        if (declaredVersionInText != null && declaredVersionInText !in ACCEPTED_VERSIONS) {
            val hint = if (declaredVersionInText < VERSION) {
                "——这是旧版标定结果（$declaredVersionInText），本版不再兼容：请在标定页删除标定结果后重新标注"
            } else {
                ""
            }
            throw IllegalArgumentException(
                "标定结果版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，" +
                    "实际 $declaredVersionInText$hint",
            )
        }
        // 开头的 BOM 先去掉（2026-09-25，与两个清单 codec 同一口径）：BOM 会让第 1 行那句
        // 「# 注释」变成"缺少 key=value 结构"的**假损坏**（本项目自己的取证脚本 push/pull 就可能带上它）。
        // 真正的格式错误照旧一律拒绝。
        val body = text.removePrefix("\uFEFF")
        var format: String? = null
        var version: Int? = null
        var frameWidth = 0
        var frameHeight = 0
        var params: MatchParams? = null
        val markersByState = LinkedHashMap<UiState, List<String>>()
        val anchorsByState = LinkedHashMap<UiState, List<String>>()
        val windows = LinkedHashMap<EntryKey, SearchWindow>()
        val templates = LinkedHashMap<EntryKey, MutableList<Template>>()
        val purposes = LinkedHashMap<EntryKey, String>()
        val notes = LinkedHashMap<EntryKey, String>()
        /** v4 起：锚点的**归属好友名**（"这条头像模板是谁的"）。 */
        val friends = LinkedHashMap<EntryKey, String>()

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("标定结果第 $line 行：$reason")

        body.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq <= 0) fail(index + 1, "缺少「key=value」结构：$line")
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)

            when (key) {
                "format" -> {
                    if (format != null) fail(index + 1, "重复的 format 行")
                    format = value
                }
                "version" -> {
                    if (version != null) fail(index + 1, "重复的 version 行")
                    version = value.toIntOrNull() ?: fail(index + 1, "版本号非整数：$value")
                }
                "frame" -> {
                    if (frameWidth != 0) fail(index + 1, "重复的 frame 行")
                    val parts = value.split('x')
                    if (parts.size != 2) fail(index + 1, "帧尺寸应为「宽x高」：$value")
                    frameWidth = parts[0].toIntOrNull() ?: fail(index + 1, "帧宽非整数：${parts[0]}")
                    frameHeight = parts[1].toIntOrNull() ?: fail(index + 1, "帧高非整数：${parts[1]}")
                }
                "params" -> {
                    if (params != null) fail(index + 1, "重复的 params 行")
                    val parts = value.split(LIST_SEP)
                    if (parts.size != 3) fail(index + 1, "参数应为三项：$value")
                    val threshold = parts[0].toDoubleOrNull() ?: fail(index + 1, "命中线非数字：${parts[0]}")
                    val margin = parts[1].toDoubleOrNull() ?: fail(index + 1, "差距线非数字：${parts[1]}")
                    val minDistance = parts[2].toIntOrNull() ?: fail(index + 1, "峰值间距非整数：${parts[2]}")
                    params = try {
                        MatchParams(threshold, margin, minDistance)
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "参数非法")
                    }
                }
                "state" -> {
                    val (stateName, signalsText) = splitPair(value) ?: fail(index + 1, "state 行缺少分隔：$value")
                    val state = try {
                        UiState.valueOf(stateName)
                    } catch (_: IllegalArgumentException) {
                        fail(index + 1, "未知状态名：$stateName")
                    }
                    if (markersByState.containsKey(state)) fail(index + 1, "状态重复定义：$stateName")
                    val names = signalsText.split(LIST_SEP)
                    try {
                        CalibrationData.StateRule(state, names)
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "规则非法")
                    }
                    markersByState[state] = names
                }
                "anchor" -> {
                    val (stateName, namesText) = splitPair(value) ?: fail(index + 1, "anchor 行缺少分隔：$value")
                    val state = try {
                        UiState.valueOf(stateName)
                    } catch (_: IllegalArgumentException) {
                        fail(index + 1, "未知状态名：$stateName")
                    }
                    if (anchorsByState.containsKey(state)) fail(index + 1, "状态的锚点重复定义：$stateName")
                    val names = namesText.split(LIST_SEP)
                    try {
                        CalibrationData.StateRule(state, emptyList(), names)
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "锚点声明非法")
                    }
                    anchorsByState[state] = names
                }
                "signal" -> {
                    val segments = value.split(SEP)
                    // v4 起 signal 行多一段「归属好友名」；**v3 仍可读**（缺这一段 = 没写归属好友）
                    val withFriend = (declaredVersionInText ?: 3) >= 4
                    val expected = if (withFriend) 6 else 5
                    if (segments.size != expected) {
                        fail(
                            index + 1,
                            "signal 行应为「ID|角色|窗口|用途${if (withFriend) "|归属好友" else ""}|备注」" +
                                "（${expected} 段）：$value",
                        )
                    }
                    val id = segments[0]
                    if (!CalibrationData.isValidId(id)) fail(index + 1, "记录 ID 非法：$id")
                    val role = SignalRole.fromToken(segments[1]) ?: fail(index + 1, "未知角色：${segments[1]}")
                    val key = EntryKey(id, role)
                    if (windows.containsKey(key)) {
                        fail(index + 1, "同一 ID 的同一角色重复定义：$id / ${role.token}")
                    }
                    val purpose = segments[3]
                    if (purpose.isNotEmpty() && role != SignalRole.ANCHOR) {
                        fail(index + 1, "只有锚点能带用途：$id")
                    }
                    if (purpose.isNotEmpty() && !CalibrationData.isValidId(purpose)) {
                        fail(index + 1, "用途名非法：$purpose")
                    }
                    val friend = if (withFriend) segments[4] else ""
                    if (friend.isNotEmpty() && role != SignalRole.ANCHOR) {
                        fail(index + 1, "只有锚点能带归属好友名：$id")
                    }
                    if (friend.isNotEmpty() && !CalibrationData.isValidFriendName(friend)) {
                        fail(index + 1, "归属好友名非法：$friend")
                    }
                    val note = segments[expected - 1]
                    if (!CalibrationData.isValidNote(note)) {
                        fail(index + 1, "备注含保留字符或超长：$note")
                    }
                    purposes[key] = purpose
                    friends[key] = friend
                    notes[key] = note
                    val windowText = segments[2]
                    val parts = windowText.split(LIST_SEP)
                    if (parts.size != 4) fail(index + 1, "搜索窗口应为四项：$windowText")
                    val nums = parts.map { it.toDoubleOrNull() ?: fail(index + 1, "窗口值非数字：$it") }
                    windows[key] = try {
                        SearchWindow(nums[0], nums[1], nums[2], nums[3])
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "窗口非法")
                    }
                }
                "template" -> {
                    val parts = value.split(SEP)
                    if (parts.size != 5) {
                        fail(index + 1, "template 行应为「ID|角色|宽|高|像素」（五段）：$value")
                    }
                    val id = parts[0]
                    if (!CalibrationData.isValidId(id)) fail(index + 1, "记录 ID 非法：$id")
                    val role = SignalRole.fromToken(parts[1]) ?: fail(index + 1, "未知角色：${parts[1]}")
                    val width = parts[2].toIntOrNull() ?: fail(index + 1, "模板宽非整数：${parts[2]}")
                    val height = parts[3].toIntOrNull() ?: fail(index + 1, "模板高非整数：${parts[3]}")
                    val pixels = try {
                        Base64.getDecoder().decode(parts[4])
                    } catch (_: IllegalArgumentException) {
                        fail(index + 1, "模板像素不是合法 Base64")
                    }
                    val template = try {
                        Template(width, height, pixels)
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "模板非法")
                    }
                    templates.getOrPut(EntryKey(id, role)) { mutableListOf() }.add(template)
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException("标定结果格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」")
        }
        val declaredVersion = version ?: throw IllegalArgumentException("标定结果缺少 version 行")
        if (declaredVersion !in ACCEPTED_VERSIONS) {
            val hint = if (declaredVersion < VERSION) {
                "——这是旧版标定结果（$declaredVersion），本版不再兼容：请在标定页删除标定结果后重新标注"
            } else {
                ""
            }
            throw IllegalArgumentException(
                "标定结果版本不受支持：可读 ${ACCEPTED_VERSIONS.sorted().joinToString(" / ")}，实际 $declaredVersion$hint",
            )
        }
        if (windows.isEmpty()) {
            throw IllegalArgumentException("标定结果没有任何 signal 行")
        }
        val entries = windows.map { (key, window) ->
            val own = templates[key]
                ?: throw IllegalArgumentException("记录「${key.id}」（${key.role.token}）没有任何模板")
            CalibrationData.SignalEntry(
                id = key.id,
                window = window,
                templates = own,
                role = key.role,
                purpose = purposes[key].orEmpty().ifEmpty { null },
                friend = friends[key].orEmpty().ifEmpty { null },
                note = notes[key].orEmpty(),
            )
        }
        templates.keys.forEach { key ->
            if (key !in windows) {
                throw IllegalArgumentException("template 引用了未定义的记录：${key.id}（${key.role.token}）")
            }
        }
        // 标志与锚点分别声明，同一状态可只出现在其中一行：按状态合并成一条规则
        val rulesByState = LinkedHashMap<UiState, CalibrationData.StateRule>()
        markersByState.forEach { (state, names) ->
            rulesByState[state] = CalibrationData.StateRule(state, names, anchorsByState[state].orEmpty())
        }
        anchorsByState.forEach { (state, names) ->
            rulesByState.getOrPut(state) { CalibrationData.StateRule(state, emptyList(), names) }
        }
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params ?: throw IllegalArgumentException("标定结果缺少 params 行"),
            signals = entries,
            stateRules = rulesByState.values.toList(),
        )
    }

    private fun splitPair(value: String): Pair<String, String>? {
        val i = value.indexOf(SEP)
        if (i <= 0 || i == value.length - 1) return null
        return value.substring(0, i) to value.substring(i + 1)
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证产物文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    /**
     * 记录的定位键：**ID + 角色**。
     *
     * 同一个 ID 可以出现两次（标志与锚点各一条：一个元素的两面），所以单靠 ID 不能定位一条记录。
     */
    private data class EntryKey(val id: String, val role: SignalRole)
}
