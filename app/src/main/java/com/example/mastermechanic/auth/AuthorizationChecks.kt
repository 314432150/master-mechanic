package com.example.mastermechanic.auth

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.service.MasterMechanicAccessibilityService
import com.example.mastermechanic.service.ResidentService

/** 屏幕采集为"会话制"授权（ADR-001）：每次采集会话都需用户重新授予，由界面持有最近一次结果。 */
enum class CaptureSessionState { NOT_GRANTED, GRANTED }

/** 采集各授权项的实时状态。任何一项检查失败都按"缺失"处理，不允许静默通过（FR-08）。 */
object AuthorizationChecks {

    /**
     * 采集各授权项的实时状态；**返回值顺序 = 授权页显示顺序**（[AUTHORIZATION_DISPLAY_ORDER]，
     * 屏幕采集在最后：点它会跳到游戏，见该常量的说明）。
     */
    fun collect(context: Context, captureSession: CaptureSessionState): List<AuthStatus> =
        AUTHORIZATION_DISPLAY_ORDER.map { item ->
            AuthStatus(
                item,
                when (item) {
                    AuthItem.ACCESSIBILITY -> accessibilityState(context)
                    AuthItem.SCREEN_CAPTURE -> captureState(captureSession)
                    // 常驻守护（2026-09-20 起算必备项）：跑号与自动关弹窗都靠它
                    AuthItem.RESIDENT -> residentState(context)
                    AuthItem.NOTIFICATIONS -> notificationState(context)
                },
            )
        }

    /** 常驻守护是否在运行（查询实现见 `ResidentService.isRunning`）。 */
    fun residentState(context: Context): AuthState =
        if (ResidentService.isRunning(context)) AuthState.GRANTED else AuthState.MISSING

    /** 无障碍服务是否已在系统设置中开启；按组件名精确匹配，避免把其他无障碍服务算进来。 */
    fun accessibilityState(context: Context): AuthState {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return AuthState.MISSING.also { logAccessibilityChange(it, "取不到 AccessibilityManager") }
        val expected = ComponentName(context, MasterMechanicAccessibilityService::class.java)
        val services = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val enabled = services.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == expected.packageName &&
                serviceInfo.name == expected.className
        }
        val state = if (enabled) AuthState.GRANTED else AuthState.MISSING
        // 只在**变化时**记一行：这行是"两处读数不一致"时唯一能分清的现场。
        // 2026-10-19 就是靠它定案的：某个时刻 `系统返回 0 项`（连别的无障碍服务都不在），1.5s 后
        // 又「命中本应用组件」—— 与 `uiautomator dump` 同一秒出现 ⇒ **取证动作自己把无障碍服务
        // 临时摘掉了**，不是应用误报。核对授权读数请用截图，别用 dump（见 `docs/verification/README.md` 记录规则 4）。
        logAccessibilityChange(
            state = state,
            detail = if (enabled) {
                "命中本应用组件"
            } else {
                "系统返回 ${services.size} 项：" +
                    services.joinToString { it.resolveInfo?.serviceInfo?.name ?: "?" }
            },
        )
        return state
    }

    /** 上一次记录的结论 / 说明（只在结论或说明变化时落一行，避免 500ms 轮询刷屏）。 */
    @Volatile
    private var lastAccessibilityNote: String? = null

    private fun logAccessibilityChange(state: AuthState, detail: String) {
        val note = "$state|$detail"
        if (note == lastAccessibilityNote) return
        lastAccessibilityNote = note
        MmLog.i("MM-Auth", "无障碍查询 → ${if (state == AuthState.GRANTED) "已开启" else "未开启"}（$detail）")
    }

    fun captureState(session: CaptureSessionState): AuthState =
        if (session == CaptureSessionState.GRANTED) AuthState.GRANTED else AuthState.MISSING

    /** 通知：Android 13 起需要运行时授权（minSdk 34 起"低版本无需授权"那条分支已不存在）。 */
    fun notificationState(context: Context): AuthState = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED -> AuthState.GRANTED
        else -> AuthState.MISSING
    }
}
