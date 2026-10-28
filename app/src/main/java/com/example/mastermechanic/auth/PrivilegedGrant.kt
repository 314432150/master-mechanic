package com.example.mastermechanic.auth

import android.content.Context
import com.example.mastermechanic.log.MmLog
import java.io.File

/**
 * **「命令授权」脚本生成器**（2026-10-25）：让用户用一条 adb 命令，把"能自己开关无障碍"的能力授给我们。
 *
 * ## 为什么要有它
 *
 * 无障碍那颗开关只有持有 `WRITE_SECURE_SETTINGS` 才能真的拨动（见 [AccessibilitySelfHeal]）。
 * 没有这条权限时，用户有两条路：
 * 1. **手动授权**：去系统设置里手点开无障碍（每次覆盖安装都要来一次）；
 * 2. **命令授权**：在电脑上跑一条 adb 命令，之后 App 就能自己开关（**覆盖安装保留**，只有卸载才需重跑）。
 *
 * 这条 UX 是照 GKD 的「命令授权」做的：它把一串 `pm grant` / `appops set` 写成一个 `.sh` 放到
 * 应用的外部私有目录，弹窗里给出 `adb shell sh <路径>` —— 一条命令、可复制、无需用户理解每一步。
 * 我们照抄这个形态（同样的路径规则、同样的可读性），只把内容换成我们自己需要的三条。
 *
 * ## 为什么是脚本而不是直接给一行命令
 *
 * 三条命令逐个复制容易漏、也容易被终端换行截断；一条 `adb shell sh <path>` 最不容易出错，
 * 且以后要加/减命令时**界面文案不用动**（用户永远只复制那一行）。
 */
object PrivilegedGrant {

    private const val TAG = "MM-Auth"

    /** 脚本文件相对外部私有目录的路径（与 GKD 同一套规则：`files/sh/<name>`）。 */
    private const val SCRIPT_DIR = "sh"
    private const val SCRIPT_NAME = "grant.sh"

    /**
     * 脚本内容（纯函数，单测按行钉住）。
     *
     * ⚠ 三条命令的取舍：
     * - `pm grant WRITE_SECURE_SETTINGS` 是**真正让开关可用**的那一条；
     * - 两条 `appops set` 在部分 ROM（含本机 vivo）会被忽略、也不影响结果，但别的 ROM / 系统版本上
     *   缺了它们可能仍被"受限设置"拦住 ⇒ 一并写上，失败也不影响第一条已生效。
     */
    internal fun scriptLines(packageName: String): List<String> = listOf(
        "#!/system/bin/sh",
        "# MasterMechanic 一次性授权：让 App 能自行开关无障碍（覆盖安装保留，卸载后需重跑）",
        "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
        "appops set $packageName ACCESS_ACCESSIBILITY allow",
        "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow",
        "echo '授权完成：回到 MasterMechanic，无障碍开关即可由 App 自己控制'",
    )

    /**
     * 把脚本写到应用外部私有目录并返回它的**绝对路径**；写失败返回 null（界面会退化成只给手动授权）。
     *
     * 路径形如 `/storage/emulated/0/Android/data/<pkg>/files/sh/grant.sh` —— adb shell（uid 2000）
     * 有权读这个目录，所以用户直接 `adb shell sh <路径>` 就能跑。
     */
    fun ensureScript(context: Context): String? {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null), SCRIPT_DIR)
        val file = File(dir, SCRIPT_NAME)
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            file.writeText(scriptLines(app.packageName).joinToString("\n") + "\n")
            file.absolutePath
        }.onFailure {
            MmLog.w(TAG, "命令授权脚本写入失败：${it.message}")
        }.getOrNull()
    }

    /** 弹窗里给用户复制的那一行。 */
    fun commandFor(scriptPath: String): String = "adb shell sh $scriptPath"

    /** 脚本写不出来时的退路：只给最关键的那条命令（用户自己粘贴也能成）。 */
    fun fallbackCommand(packageName: String): String =
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
}
