package com.example.mastermechanic.calibration

import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Template
import java.util.Base64
import java.util.Locale

/**
 * 标定产物编解码（T1-5a，纯逻辑）：文本行式格式，确定性（同数据 → 同文本，§5-4）。
 *
 * 格式（v1，每行一条记录；`#` 开头与空行忽略）：
 * ```
 * format=mm-calibration                     格式标记
 * version=1                                 格式版本
 * frame=<宽>x<高>                             标定帧尺寸（像素）
 * params=<命中线>,<差距线>,<峰值间距>           匹配参数
 * state=<UiState 枚举名>|<信号名>[,<信号名>…]     信号—状态映射
 * signal=<信号名>|<left>,<top>,<right>,<bottom>  搜索窗口（帧尺寸比例，0..1）
 * template=<信号名>|<宽>|<高>|<Base64 灰度像素>   标志模板
 * ```
 *
 * 解析严格：未知键 / 版本不符 / 字段非法 / 重复定义一律拒绝（IllegalArgumentException，
 * 消息带行号），不做静默兼容——产物格式漂移必须显式暴露，禁止按猜测继续（红线 3 同源口径）。
 */
object CalibrationCodec {

    private const val FORMAT_TAG = "mm-calibration"
    private const val VERSION = 1
    private const val SEP = "|"
    private const val LIST_SEP = ","

    /** 产物 → 文本（单例字段随后段信号记录，顺序固定，保证确定性）。 */
    fun encode(data: CalibrationData): String = buildString {
        appendLine("# MasterMechanic 标定产物：模板 / 搜索窗口 / 匹配参数全部来自目标设备标定（T1-5）")
        appendLine("format=$FORMAT_TAG")
        appendLine("version=$VERSION")
        appendLine("frame=${data.frameWidth}x${data.frameHeight}")
        appendLine(
            "params=${num(data.params.matchThreshold)},${num(data.params.ambiguityMargin)}" +
                ",${data.params.peakMinDistance}",
        )
        data.stateRules.forEach { rule ->
            appendLine("state=${rule.state.name}$SEP${rule.signalNames.joinToString(LIST_SEP)}")
        }
        data.signals.forEach { signal ->
            appendLine(
                "signal=${signal.name}$SEP${num(signal.window.left)},${num(signal.window.top)}" +
                    ",${num(signal.window.right)},${num(signal.window.bottom)}",
            )
            signal.templates.forEach { template ->
                appendLine(
                    "template=${signal.name}$SEP${template.width}$SEP${template.height}$SEP" +
                        Base64.getEncoder().encodeToString(template.pixels),
                )
            }
        }
    }

    /** 文本 → 产物（严格校验；任何非法都抛 [IllegalArgumentException]，消息含行号）。 */
    fun decode(text: String): CalibrationData {
        var format: String? = null
        var version: Int? = null
        var frameWidth = 0
        var frameHeight = 0
        var params: MatchParams? = null
        val stateRules = mutableListOf<CalibrationData.StateRule>()
        val windows = LinkedHashMap<String, SearchWindow>()
        val templates = LinkedHashMap<String, MutableList<Template>>()

        fun fail(line: Int, reason: String): Nothing =
            throw IllegalArgumentException("标定产物第 $line 行：$reason")

        text.split('\n').forEachIndexed { index, rawLine ->
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
                    val names = signalsText.split(LIST_SEP)
                    try {
                        stateRules.add(CalibrationData.StateRule(state, names))
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "规则非法")
                    }
                }
                "signal" -> {
                    val (name, windowText) = splitPair(value) ?: fail(index + 1, "signal 行缺少分隔：$value")
                    if (!CalibrationData.isValidName(name)) fail(index + 1, "信号名非法：$name")
                    if (windows.containsKey(name)) fail(index + 1, "信号重复定义：$name")
                    val parts = windowText.split(LIST_SEP)
                    if (parts.size != 4) fail(index + 1, "搜索窗口应为四项：$windowText")
                    val nums = parts.map { it.toDoubleOrNull() ?: fail(index + 1, "窗口值非数字：$it") }
                    windows[name] = try {
                        SearchWindow(nums[0], nums[1], nums[2], nums[3])
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "窗口非法")
                    }
                }
                "template" -> {
                    val parts = value.split(SEP)
                    if (parts.size != 4) fail(index + 1, "template 行应为「名称|宽|高|像素」：$value")
                    val (name, widthText, heightText, pixelsText) = parts
                    if (!CalibrationData.isValidName(name)) fail(index + 1, "信号名非法：$name")
                    val width = widthText.toIntOrNull() ?: fail(index + 1, "模板宽非整数：$widthText")
                    val height = heightText.toIntOrNull() ?: fail(index + 1, "模板高非整数：$heightText")
                    val pixels = try {
                        Base64.getDecoder().decode(pixelsText)
                    } catch (_: IllegalArgumentException) {
                        fail(index + 1, "模板像素不是合法 Base64")
                    }
                    val template = try {
                        Template(width, height, pixels)
                    } catch (e: IllegalArgumentException) {
                        fail(index + 1, e.message ?: "模板非法")
                    }
                    templates.getOrPut(name) { mutableListOf() }.add(template)
                }
                else -> fail(index + 1, "未知键：$key")
            }
        }

        if (format != FORMAT_TAG) {
            throw IllegalArgumentException("标定产物格式标记不符：期待「$FORMAT_TAG」，实际「${format ?: "缺失"}」")
        }
        if (version != VERSION) {
            throw IllegalArgumentException("标定产物版本不符：期待 $VERSION，实际 ${version ?: "缺失"}")
        }
        if (windows.isEmpty()) {
            throw IllegalArgumentException("标定产物没有任何 signal 行")
        }
        val entries = windows.map { (name, window) ->
            val own = templates[name] ?: throw IllegalArgumentException("信号「$name」没有任何模板")
            CalibrationData.SignalEntry(name, window, own)
        }
        templates.keys.forEach { name ->
            if (name !in windows) throw IllegalArgumentException("template 引用了未定义的信号：$name")
        }
        return CalibrationData(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            params = params ?: throw IllegalArgumentException("标定产物缺少 params 行"),
            signals = entries,
            stateRules = stateRules,
        )
    }

    private fun splitPair(value: String): Pair<String, String>? {
        val i = value.indexOf(SEP)
        if (i <= 0 || i == value.length - 1) return null
        return value.substring(0, i) to value.substring(i + 1)
    }

    /** 固定小数点格式化（Locale.ROOT：与系统区域无关，保证产物文本确定）。 */
    private fun num(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
