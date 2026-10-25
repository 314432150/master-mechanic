package com.example.mastermechanic.auth

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.service.MasterMechanicAccessibilityService

/**
 * **无障碍的开关能力 + 自愈**（2026-10-25）。
 *
 * ## 为什么我们碰得到这个开关
 *
 * 无障碍是唯一"每次覆盖安装都可能掉、本来必须钻进系统设置手点"的授权。用户发现 GKD 能自行开关，
 * 我读完它的脚本后用探针在同一台 vivo 上验证：**只要持有 `WRITE_SECURE_SETTINGS`，
 * 在本应用进程里写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 就会被系统接受**
 * （`adb shell settings put` 反而被 vivo 拦 —— 之前踩的就是这个坑）。
 *
 * ## 两种入口，同一套边界
 *
 * | 入口 | 方向 | 说明 |
 * | --- | --- | --- |
 * | 授权页那颗开关（[setEnabled]） | **双向** | 用户亲手拨的：拨开 = 加上自己，拨关 = **只摘掉自己** |
 * | 冷启动自愈（[ensureEnabled]） | 只开 | 兜底，不替用户做决定 |
 *
 * **只动自己**是硬边界：任何时候都不删除别人的无障碍服务（同一台机器上还有别的无障碍工具），
 * 也绝不改别的设置项。`mergedList` / `withoutSelf` 两个纯函数负责这件事，有单测钉着。
 *
 * ## 用户亲手关掉之后，别再偷偷打开
 *
 * 冷启动自愈会检查一份"用户主动关闭"的标记（[setEnabled] 关时写、开时清）：
 * 用户在开关上关掉 ⇒ 下次启动**不会**被我们抢回来；哪天想恢复就再拨一次。
 * 这条是"别跟用户对着干"的落点 —— 拔掉它，自愈会变成过一会儿就把关掉的开关又打开。
 *
 * ## 没有权限时
 *
 * `WRITE_SECURE_SETTINGS` 是 `development` 级、只能 adb 授予（`pm grant`，**覆盖安装保留**，卸载才需重授）。
 * 没有它 ⇒ [canSelfControl] = false，授权页那颗开关点击只把你送到系统设置页；自愈静默跳过。
 * 行为与加这条能力之前一字不差。
 *
 * ⚠ 该权限不通过 Google Play 分发时无需申报；若上架需单独评估（见 `ADR-010`）。
 */
object AccessibilitySelfHeal {

    private const val TAG = "MM-Auth"

    /** 用户主动关闭的标记（只在这一件事上用，所以自建一个小文件，不占业务配置）。 */
    private const val PREFS = "accessibility_switch"
    private const val KEY_USER_DISABLED = "user_disabled"

    /** 每进程只尝试一次（冷启动自愈是兜底，不是轮询）。 */
    @Volatile
    private var attempted = false

    /** 我们**能不能**直接改这个开关（= 是否持有那条件特权权限）。 */
    fun canSelfControl(context: Context): Boolean = hasWriteSecureSettings(context)

    /**
     * **授权页开关的落点**：把无障碍开成 [enabled]，返回"最终是不是这个状态"。
     *
     * 只动自己那一项：关的时候从列表里摘掉本包，别人的服务原样保留；
     * 同时记住"这是用户亲手关的"，让下次冷启动的自愈不要把它又打开。
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        if (!hasWriteSecureSettings(app)) {
            MmLog.i(TAG, "无障碍开关: 没有 WRITE_SECURE_SETTINGS，交给系统设置页处理")
            return false
        }
        val resolver = app.contentResolver
        val current = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val self = selfComponent(app)
        val target = if (enabled) mergedList(current, self) else withoutSelf(current, app.packageName)
        val error = runCatching {
            if (target != current.trim().trim(':')) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    target,
                )
            }
            // 只在"打开"时顺手把总开关拨到 1；关的时候不动它（它可能还撑着别的无障碍服务）
            if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.exceptionOrNull()
        setUserDisabled(app, !enabled)

        val after = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val actual = containsSelf(after, app.packageName)
        if (actual == enabled) {
            attempted = true
            MmLog.i(TAG, "无障碍开关: 已${if (enabled) "开启" else "关闭"}（列表 ${countOf(after)} 项）")
        } else {
            MmLog.w(
                TAG,
                "无障碍开关: 写入未被采纳（期望 ${if (enabled) "开" else "关"}，" +
                    "实际 ${if (actual) "开" else "关"}；${if (error == null) "无异常" else "抛错：${error.message}"}）" +
                    "—— 请在系统设置里手动处理",
            )
        }
        return actual == enabled
    }

    /**
     * **冷启动自愈**：发现被关掉就自己打开；返回"当前确实已启用"。
     *
     * @param force 诊断用（跳过"每进程一次"与"用户主动关闭"两条短路）。
     */
    fun ensureEnabled(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        val self = selfComponent(app)
        val resolver = app.contentResolver
        val current = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        // 已启用：什么都不做（也要 mark，避免同进程内重复读盘）
        if (containsSelf(current, app.packageName)) {
            attempted = true
            return true
        }
        if (attempted && !force) return false
        attempted = true

        if (!force && isUserDisabled(app)) {
            // 用户亲手关的：不抢。想恢复就在授权页把开关拨回来（或改这条标记）。
            MmLog.i(TAG, "无障碍自愈: 跳过 —— 上次是用户主动关闭的开关")
            return false
        }
        if (!hasWriteSecureSettings(app)) {
            // **静默回落**：没有这条权限时本类等于不存在（授权页照旧引导用户手点）
            MmLog.i(TAG, "无障碍自愈: 跳过 —— 未授予 WRITE_SECURE_SETTINGS（adb 一次性授权后可自动开启）")
            return false
        }

        val target = mergedList(current, self)
        val error = runCatching {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                target,
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.exceptionOrNull()
        // **只信回读**：put 不抛异常不代表系统采纳了
        val after = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val ok = containsSelf(after, app.packageName)
        if (ok) {
            MmLog.i(TAG, "无障碍自愈: 已自动开启无障碍服务（写入前 ${countOf(current)} 项 → 现在 ${countOf(after)} 项）")
        } else {
            MmLog.w(
                TAG,
                "无障碍自愈: 写入未被系统采纳（${if (error == null) "无异常" else "抛错：${error.message}"}）" +
                    "—— 请在授权页手动开启",
            )
        }
        return ok
    }

    /** 本应用的无障碍组件（`flattenToShortString` 与系统存的形态一致：`pkg/.service.X`）。 */
    internal fun selfComponent(context: Context): String =
        ComponentName(context, MasterMechanicAccessibilityService::class.java).flattenToShortString()

    internal fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 上次是不是**用户亲手**关掉的（冷启动自愈据此决定要不要抢回来）。 */
    internal fun isUserDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USER_DISABLED, false)

    internal fun setUserDisabled(context: Context, disabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_DISABLED, disabled)
            .apply()
    }

    /**
     * 列表里是否已经有**本包**的无障碍服务（比对包名前缀即可：同一个包里只会有这一个服务；
     * 系统存的是短名 `pkg/.service.X`，而 `flattenToString` 是长名 —— 只比包名才不会漏判）。
     */
    internal fun containsSelf(value: String, packageName: String): Boolean =
        value.split(':').any { it.startsWith("$packageName/") }

    /**
     * 把本服务**追加**到现有列表末尾（空列表就只写自己）。
     *
     * 只追加、不重排：别人的无障碍服务必须原样保留。
     */
    internal fun mergedList(current: String, self: String): String {
        val trimmed = current.trim().trim(':')
        return if (trimmed.isEmpty()) self else "$trimmed:$self"
    }

    /**
     * 从列表里**只摘掉本包**那一项（用户拨关时用）。
     *
     * ⚠ 别人的项必须原样保留 —— 这是"只动自己"那条边界的技术落点（有单测钉着）。
     */
    internal fun withoutSelf(current: String, packageName: String): String =
        current.split(':')
            .filter { it.isNotBlank() && !it.startsWith("$packageName/") }
            .joinToString(":")

    private fun countOf(value: String): Int = value.split(':').count { it.isNotBlank() }
}
