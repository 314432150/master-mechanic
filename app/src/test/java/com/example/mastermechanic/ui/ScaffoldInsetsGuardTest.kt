package com.example.mastermechanic.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫（M5-U1 补）：**页面级 `Scaffold` 必须把系统栏 inset 清零**。
 *
 * ## 为什么要有这道守卫
 *
 * 外壳 `Scaffold`（`ui/nav/MasterMechanicApp.kt`）已经按「标题栏 + 导航栏 + 系统栏」算好 `innerPadding`
 * 交给 `NavHost`；**页面里那些只为"给 Snackbar 落脚"的 `Scaffold` 默认会再按系统栏加一遍** ⇒
 * 顶部（和底部）各多出一段空白。这个坑 **2026-10-01 / 10-02 连着咬了两次**：
 * - 第一次：用户"服务器距离顶部太高了，适当缩减" ⇒ 我把 8dp 改成 2dp（**只值 6dp，没治到根** ✗）；
 * - 第二次：用户"顶部空白**还是**太宽" ⇒ 才发现是 inset 被算了两遍；修的时候**好友页还漏了**，
 *   又被用户逮到一次（"好友页顶部空白你为什么没按服务器页一样改"✗）。
 *
 * ⇒ 规矩：`ui/` 下**除壳以外**每一处 `Scaffold(` 都必须显式写 `contentWindowInsets = WindowInsets(0.dp)`。
 *
 * ## 已知不覆盖（静默跳过的守卫等于没有守卫）
 * - 只扫 `app/src/main/java/com/example/mastermechanic/ui/`（其它模块 / 目录不管）；
 * - 只认源码文本，**不校验值对不对**（写 `WindowInsets(0.dp)` 之外的合法值也算通过吗？—— 不算：
 *   这里只要求"出现该参数"，因为本项目里唯一正确的写法就是这个，别写成别的）；
 * - 扫描前会**剥掉注释**（`//` 与 `/* */`，含 KDoc）—— 注释里解释这个坑时会写到 `Scaffold(` 字样，
 *   不剥就会误报。
 */
class ScaffoldInsetsGuardTest {

    /** 壳的 `Scaffold`：它**就是**消费 inset 的那一个，唯一允许不写该参数的地方。 */
    private val shellFile = "ui/nav/MasterMechanicApp.kt"

    private val requiredParam = "contentWindowInsets"

    @Test
    fun everyPageLevelScaffoldZeroesSystemBarInsets() {
        val uiDir = File("src/main/java/com/example/mastermechanic/ui")
        assertTrue("找不到 ui 目录（工作目录应为 app 模块）：${uiDir.absolutePath}", uiDir.isDirectory)

        val offenders = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                // 工作目录是 **app 模块**（`src/main/java/...`），所以这里按包名后缀取名，别看有没有 `/app/` 前缀
                val relative = file.path.replace('\\', '/').substringAfterLast("mastermechanic/")
                if (relative == shellFile) return@forEach
                val source = stripComments(file.readText())
                var idx = source.indexOf("Scaffold(")
                while (idx >= 0) {
                    val end = source.indexOf(") {", idx).let { if (it < 0) source.length else it }
                    val head = source.substring(idx, end)
                    if (!head.contains(requiredParam)) {
                        val line = source.substring(0, idx).count { it == '\n' } + 1
                        offenders += "$relative:$line（缺 `$requiredParam = WindowInsets(0.dp)`）"
                    }
                    idx = source.indexOf("Scaffold(", idx + 1)
                }
            }

        assertTrue(
            "下列页面级 `Scaffold` 没把系统栏 inset 清零 —— 壳已经消费过一次，" +
                "内层再吃一遍会让顶部（和底部）多出一段空白（2026-10-01/02 连咬两次的坑）：\n" +
                offenders.joinToString("\n") { "  · $it" },
            offenders.isEmpty(),
        )
    }

    /** 剥掉 `//` 行注释与 `/* */` 块注释（含 KDoc），避免注释里的字样造成误报。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
