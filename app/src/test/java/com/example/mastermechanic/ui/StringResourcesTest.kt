package com.example.mastermechanic.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **M5-U7 / 验收 V3「零死文案」的守卫**（2026-10-01）：`strings.xml` 里每一条都必须**有人用** ——
 * 要么代码里 `R.string.X`，要么资源 / 清单里 `@string/X`。
 *
 * ## 为什么要一条单测来钉（而不是"删完就算"）
 *
 * 2026-10-01 数出来：`strings.xml` 里 61 条「跑号清单」时代的文案（`patrol_*` +
 * `auth_open_patrol_config`）在 `app/src` 里**零引用** —— 那套「跑号项」界面在 M4 被
 * 「拜访规则」（悬浮窗 CONFIG 层 + `preset/`）取代后留下的壳。死文案的代价不是"占几 KB"：
 * 它会让**下一个人**（包括 AI）在"M5 要精简文案"时把力气花在**不存在的界面**上，
 * 也会让"这条到底还有没有用"变成一个要靠考古回答的问题 ⇒ 加一道门，**没人用的文案进不来**。
 *
 * ## 判据（两条，缺一不可）
 *
 * 1. 扫 `src/main/res/values/strings.xml` 的 `<string name="…">` ⇒ 待检集合；
 * 2. 扫 `src/` 下的全部 `kt` / `java` / `xml`（**除 strings.xml 自己**）⇒ 收集
 *    `R.string.X` 与 `@string/X` 两种写法里出现过的名字 ⇒ 引用集合。
 *
 * 差集必须为空，否则用例失败并**把没人用的那几条列出来**（照实说，不含糊）。
 *
 * ## 已知的不覆盖（如实记录，不当成"通过"）
 *
 * - **动态取名**（`getIdentifier("x", "string", …)`）认不出来 ⇒ 真出现这种写法时本用例会**误报**，
 *   那时应当改成显式引用（而不是把这里放宽）；
 * - 只检默认 `values/`（本工程目前只有中文一套；将来加 `values-en/` 要同步扩这里）；
 * - `<string-array>` / `<plurals>` 的**条目**不在待检集合里（它们没有独立名字），
 *   但数组 / plurals 自己若被写成 `<string name=…>` 之外的形态也不在检查范围 —— 见 §"不覆盖"。
 */
class StringResourcesTest {

    /**
     * **术语守卫：用户可见文案里不许再出现「跑号」**（2026-10-03 用户报："现在的悬浮窗菜单文案是
     * 换号和拜访，已经没有跑号了"）。
     *
     * ## 为什么单独钉一道
     *
     * 「跑号」是 **M4 之前**的旧叫法（那时的功能叫"跑号"、清单叫"跑号清单"）；M4 之后用户看到的是
     * **换号**（换区服 + 重新登录那套流程）与**拜访**（去好友农场）。所以用户可见文案必须写"换号 / 拜访"，
     * 否则界面上会出现"菜单写换号、提示里写跑号"的自相矛盾（用户看着两个词，不知道说的是不是同一件事）。
     *
     * ## 判据范围（刻意窄）
     *
     * **只查 `<string>` 的正文** ⇒ `strings.xml` 里的**注释**可以继续写"跑号"（那些是给后来人看的历史说明，
     * 例如"这里原有 61 条跑号清单时代的文案，已全部删除"）；**日志与 KDoc 也不在本用例范围内** ——
     * 日志是内部排障口径（`MM-Capture` 至今仍写 `跑号: 第 N 步`，是有意保留的，见下）。
     */
    @Test
    fun noUserVisibleStringStillSaysPaohao() {
        val stringsFile = File(findModuleDir(), "src/main/res/values/strings.xml")
        assertTrue("找不到 strings.xml：${stringsFile.absolutePath}", stringsFile.isFile)

        val stale = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(stringsFile.readText())
            .filter { it.groupValues[2].contains("跑号") }
            .map { it.groupValues[1] }
            .toList()

        assertTrue(
            "用户可见文案里还有 ${stale.size} 条写着「跑号」（旧叫法）⇒ 改成「换号 / 拜访」：" +
                "\n（注释里可以写跑号，那是历史说明；本用例只看 <string> 正文）\n" +
                stale.joinToString("\n") { "  · $it" },
            stale.isEmpty(),
        )
    }

    @Test
    fun everyStringResourceIsReferenced() {
        val moduleDir = findModuleDir()
        val stringsFile = File(moduleDir, "src/main/res/values/strings.xml")
        assertTrue("找不到 strings.xml（工作目录不对？）：${stringsFile.absolutePath}", stringsFile.isFile)

        val entries = Regex("<string\\s+name=\"([^\"]+)\"")
            .findAll(stringsFile.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("strings.xml 里一条 <string> 都没读到（解析写错了？）", entries.isNotEmpty())

        val referenced = Regex("""(?:R\.string\.|@string/)([A-Za-z0-9_]+)""")
            .findAll(referenceHaystack(moduleDir, stringsFile))
            .map { it.groupValues[1] }
            .toSet()

        val unused = entries.filterNot { it in referenced }
        assertTrue(
            "有 ${unused.size} 条文案在 src/ 里没人用（V3「零死文案」）⇒ 删掉它们，" +
                "或把该有的引用补上（**不要**为了让它变绿而放宽本用例）：\n" +
                unused.joinToString("\n") { "  · $it" },
            unused.isEmpty(),
        )
    }

    @Test
    fun theBottomBarLabelsStayShortEnoughToNotGetTruncated() {
        // 用户 2026-10-01："「**拜访设置**」「**账号与好友**」作为导航栏**太长了**" ⇒
        // 底栏一律 2 字（运行 / 拜访 / 清单 / 标定），**页面标题**仍用全名。
        // 这里钉住"短名真的短"：底栏宽度只够 2 字，超了会被截断成"拜访设…"——比短名更糟。
        val moduleDir = findModuleDir()
        val text = File(moduleDir, "src/main/res/values/strings.xml").readText()
        val shortNames = listOf(
            "nav_run",
            "nav_visit_settings_short",
            "nav_accounts_short",
            "nav_calibration",
        )
        shortNames.forEach { name ->
            val value = Regex("<string name=\"$name\">([^<]*)</string>")
                .find(text)
                ?.groupValues?.get(1)
            assertTrue("strings.xml 里找不到「$name」", value != null)
            assertTrue(
                "底栏标签「$name」=「$value」超了 2 字 ⇒ 底栏会被截断（短名见 nav_*_short；全名留给页面标题）",
                (value?.length ?: 0) <= 2,
            )
        }
    }

    /** 把 `src/` 下所有可能引用文案的文件拼成一大块文本（`strings.xml` 自己排除掉）。 */
    private fun referenceHaystack(moduleDir: File, stringsFile: File): String {
        val extensions = setOf("kt", "java", "xml")
        return File(moduleDir, "src")
            .walkTopDown()
            .filter { it.isFile && it.extension in extensions && it.canonicalFile != stringsFile.canonicalFile }
            .joinToString("\n") { it.readText() }
    }

    /**
     * 定位模块目录（`app/`）。
     *
     * Gradle 跑单测时的工作目录**通常是模块目录**，但不同环境（IDE 里直接跑、CI 里换目录）会变
     * ⇒ 两种都试一遍，试不到就明确报错（宁可红，也不要"静默跳过"—— 静默跳过的守卫等于没有守卫）。
     */
    private fun findModuleDir(): File {
        val candidates = listOf(File("."), File("app"), File("../app"))
        return candidates.firstOrNull { File(it, "src/main/res/values/strings.xml").isFile }
            ?: File(".")
    }
}
