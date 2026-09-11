package com.example.mastermechanic.auth

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.mastermechanic.service.MasterMechanicAccessibilityService

/** 屏幕采集为"会话制"授权（ADR-001）：每次采集会话都需用户重新授予，由界面持有最近一次结果。 */
enum class CaptureSessionState { NOT_GRANTED, GRANTED }

/** 采集各授权项的实时状态。任何一项检查失败都按"缺失"处理，不允许静默通过（FR-08）。 */
object AuthorizationChecks {

    fun collect(context: Context, captureSession: CaptureSessionState): List<AuthStatus> = listOf(
        AuthStatus(AuthItem.ACCESSIBILITY, accessibilityState(context)),
        AuthStatus(AuthItem.SCREEN_CAPTURE, captureState(captureSession)),
        AuthStatus(AuthItem.NOTIFICATIONS, notificationState(context)),
    )

    /** 无障碍服务是否已在系统设置中开启；按组件名精确匹配，避免把其他无障碍服务算进来。 */
    fun accessibilityState(context: Context): AuthState {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return AuthState.MISSING
        val expected = ComponentName(context, MasterMechanicAccessibilityService::class.java)
        val enabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == expected.packageName &&
                    serviceInfo.name == expected.className
            }
        return if (enabled) AuthState.GRANTED else AuthState.MISSING
    }

    fun captureState(session: CaptureSessionState): AuthState =
        if (session == CaptureSessionState.GRANTED) AuthState.GRANTED else AuthState.MISSING

    /** 通知：Android 13 起需要运行时授权；13 以下视为无需授权。 */
    fun notificationState(context: Context): AuthState = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> AuthState.NOT_APPLICABLE
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED -> AuthState.GRANTED
        else -> AuthState.MISSING
    }
}
