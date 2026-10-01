package com.example.mastermechanic.action

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * **把一段文字直接写进"当前可编辑控件"**（2026-10-01，第 9 步「搜索式查找」的"填字"这一步）。
 *
 * ## 为什么走**安卓原生**而不是输入法
 *
 * 用户发现（判断很可能正确）：点好友列表的「请输入好友昵称」后弹出的是**安卓原生输入控件** ——
 * Unity 的 `TouchScreenKeyboard` 正是用原生 `EditText` 实现。既然如此，就没必要"驱动输入法"：
 *
 * 1. **找节点**：从 `rootInActiveWindow` 起递归找 `isEditable == true` 的节点（通常是 `android.widget.EditText`）；
 * 2. **写入**：`ACTION_FOCUS` 后用 **`ACTION_SET_TEXT`** 一次写完整段中文 —— **不需要**按键、**不需要**候选词；
 * 3. ⇒ 大概率**不会弹键盘**，于是"输入法全屏编辑盖住面板 / 还要点完成键收键盘"这些问题**一并消失**
 *    （真的一直弹了键盘，调用方再用 `GLOBAL_ACTION_BACK` 收，见 `CaptureService` 的搜索分支）。
 *
 * ## 与其他注入口径的关系
 *
 * 它是 [ClickDispatch] 的**同族**：同样由无障碍服务在连接时 [install]、断开时 [uninstall]，
 * 调用方一律经 [write] —— **没有第二个写入口**。区别只有一处：点击是"注入手势"，
 * 这里是"**直接把值写进控件**"（`ACTION_SET_TEXT`），**不伪造任何输入事件**（与 ADR-002 的口径一致）。
 *
 * ## 返回的是"一行诊断"
 *
 * [write] 返回一句给人看的话（成没成、写到哪个节点上、节点文本变成了什么）——
 * 真机第一轮就是靠这句话确认"这条路通不通"，所以它**不是**可有可无的日志。
 */
object TextInjector {

    /** 服务连接时安装；断开即卸载（服务不可用 = 写不了，与"点不了"同一条口径）。 */
    @Volatile
    private var writer: ((String) -> String)? = null

    fun install(block: (String) -> String) {
        writer = block
    }

    fun uninstall() {
        writer = null
        clicker = null
    }

    val isInstalled: Boolean get() = writer != null

    /**
     * 把 [text] 写进当前可编辑控件。
     *
     * @return 一行**诊断**（成功：说明了写在哪；失败：说明了卡在哪一步）
     */
    fun write(text: String): String =
        writer?.invoke(text) ?: "无障碍服务未安装（写不了文字；请检查授权页的「无障碍」）"

    // ---------------------------------------------------------------- 服务侧实现

    /**
     * 服务侧的实际动作（由 `MasterMechanicAccessibilityService` 在 [install] 里接上）。
     *
     * 顺序：找可编辑节点 → `ACTION_FOCUS` → `ACTION_SET_TEXT` → 回读节点文本**核对**。
     * 每一步都写进返回的诊断里，成/败一眼可见。
     */
    fun writeVia(service: AccessibilityService, text: String): String {
        val root = service.rootInActiveWindow
            ?: return "拿不到窗口节点树（rootInActiveWindow = null）；可能前台不是游戏"
        val node = findEditable(root)
            ?: return "没找到可编辑控件（整棵树里没有 isEditable 节点）｜根节点包名=${root.packageName}"
        val where = describe(node)
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // **提交（回车）**——用户 2026-10-01 的判断，也是这次时灵时不灵的根因：
        // `ACTION_SET_TEXT` 只是把值放进原生控件，**游戏要等"输入完成（回车/完成键）"事件才把它读进去**；
        // 而我们原来靠"系统返回"提交 ⇒ 返回有时被当成**取消**（真机：同一次跑号一次进框 ✓、一次框是空的 ✗）。
        // ⇒ 这里补一次 **IME 回车**（`ACTION_IME_ENTER`，API 30+）＝"按输入法回车/完成键"的等价动作；
        // 成不成写进诊断，调用方仍会按一次系统返回兜底（那时的返回只是收键盘，不再承担"提交"职责）。
        val commitNote = if (!ok) {
            ""
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                "｜**已发回车提交**（ACTION_IME_ENTER ✓）"
            } else {
                "｜IME 回车被拒（将由系统返回兜底提交 —— 那次**可能丢字**）"
            }
        } else {
            "｜系统 API < 30，没有 ACTION_IME_ENTER（只能靠返回兜底）"
        }
        val now = node.text?.toString().orEmpty()
        // **附一份节点树摘要**（2026-10-01 用户提示"输入框右侧有个确定按钮"）：把当前窗口里
        // 可点 / 可编辑 / 带文字的原生节点列出来 ⇒ 下一轮日志就能看出"确定"是不是原生控件、
        // 能不能直接 `ACTION_CLICK`（是 ⇒ 我们点它提交；不是 ⇒ 只能加一个标定框去点）。
        val tree = dumpTreeVia(service)
        return if (ok) {
            "ACTION_SET_TEXT 成功$commitNote｜节点：$where｜写入「$text」⇒ 现在节点文本「$now」｜$tree"
        } else {
            "ACTION_SET_TEXT 被拒（performAction = false）｜节点：$where｜当前文本「$now」｜$tree"
        }
    }

    /**
     * **当前窗口原生节点树的摘要**（2026-10-01）：只列"有点意思"的节点（可点 / 可编辑 / 带文字 / 带描述），
     * 每行给类名、是否可点、viewId、文本、描述。
     *
     * 用途：用户提示"输入框右侧有个**确定**按钮" ⇒ 先用它确认那个按钮**是不是原生控件** ——
     * 是 ⇒ 我们直接 `ACTION_CLICK` 点它提交（比"IME 回车 / 系统返回"都确定）；不是 ⇒ 只能加标定框。
     */
    fun dumpTreeVia(service: AccessibilityService, maxNodes: Int = 60): String {
        val root = service.rootInActiveWindow ?: return "节点树为空（rootInActiveWindow = null）"
        val sb = StringBuilder("节点树：")
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
                sb.append("\n  ").append("·".repeat(depth)).append(" ")
                    .append(node.className)
                    .append(" clickable=").append(node.isClickable)
                    .append(" editable=").append(node.isEditable)
                    .append(" id=").append(node.viewIdResourceName)
                    .append(" text=「").append(text).append("」")
                    .append(" desc=「").append(desc).append("」")
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return sb.toString()
    }

    // ---------------------------------------------------------------- 按文字点原生控件（提交用）

    /** 服务连接时安装"点原生控件"（与写文字同一套生命周期；[uninstall] 一起清）。 */
    @Volatile
    private var clicker: ((String) -> String)? = null

    fun installClicker(block: (String) -> String) {
        clicker = block
    }

    /**
     * **按文字点原生控件**（2026-10-01 用户提示 + 节点树实证）：搜索框输入后要提交，
     * 而输入框右侧那个「确定」是**原生控件** —— 真机节点树原样：
     * `·· android.widget.Button clickable=true editable=false id=null text=「确定」`
     * ⇒ 直接 `ACTION_CLICK` 点它（比"IME 回车 / 系统返回"都确定；后者会被游戏当成取消 ⇒ 丢字）。
     *
     * @return 一行诊断（点到没有、点了谁）
     */
    fun click(label: String): String = clicker?.invoke(label) ?: "无障碍服务未安装（点不了原生控件）"

    /** 服务侧实现：按 `text` / `contentDescription` 找**可点的**节点并 `ACTION_CLICK`。 */
    fun clickVia(service: AccessibilityService, label: String): String {
        val root = service.rootInActiveWindow ?: return "拿不到窗口节点树（rootInActiveWindow = null）"
        val target = findClickableByLabel(root, label)
            ?: return "没找到原生控件「$label」（节点树里没有 text/desc 匹配的可点节点）"
        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "已点原生控件「$label」（${target.className} clickable=true）✓"
        } else {
            "原生控件「$label」ACTION_CLICK 被拒（${target.className}）"
        }
    }

    private fun findClickableByLabel(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (node.isClickable && (text.contains(label) || desc.contains(label))) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findClickableByLabel(it, label)?.let { hit -> return hit } }
        }
        return null
    }

    /** 深度优先找第一个 `isEditable` 的节点（真实可编辑的那一个，不是"看起来像输入框"的容器）。 */
    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    /** 一行节点身份（类名 / 包名 / viewId / 是否聚焦 / 现有文本），写进诊断用。 */
    private fun describe(node: AccessibilityNodeInfo): String {
        val id = node.viewIdResourceName ?: "—"
        return "${node.className}｜pkg=${node.packageName}｜id=$id｜focused=${node.isFocused}" +
            "｜text=「${node.text?.toString().orEmpty()}」"
    }
}

/**
 * **系统级按键/导航**（2026-10-01）：目前只有"**按一次返回**"，用途是**收输入法** ——
 * 第 9 步搜索链写完文字后，游戏被输入法切成"全屏编辑"，下一枪要点的「搜索」按钮**不在画面上**
 * （实录：`搜索式查找卡在锚点「friend_search_go」`）⇒ 先按一次返回把它收掉（Back **只收输入法、
 * 不会关搜索面板**，这正是它比"点输入法「完成」键"更稳的原因：不依赖键盘布局）。
 *
 * 与 [TextInjector] / `ClickDispatch` 同一套生命周期：服务连接时装、断开时卸；调用方一律经 [back]。
 */
object SystemKeys {

    @Volatile
    private var backAction: (() -> String)? = null

    fun install(block: () -> String) {
        backAction = block
    }

    fun uninstall() {
        backAction = null
    }

    val isInstalled: Boolean get() = backAction != null

    /** @return 一行诊断（成没成、为什么） */
    fun back(): String = backAction?.invoke() ?: "无障碍服务未安装（按不了返回）"

    /**
     * 服务侧实现（由 `MasterMechanicAccessibilityService` 在 [install] 里接上）：
     * `GLOBAL_ACTION_BACK` —— **只收输入法、不会关掉底下的搜索面板**；失败则说明系统拒绝了。
     */
    fun backVia(service: AccessibilityService): String =
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            "已按系统返回（收输入法；只收键盘、不关面板）"
        } else {
            "系统返回被拒（performGlobalAction = false）"
        }
}
