package com.example.mastermechanic.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.auth.AuthItem
import com.example.mastermechanic.auth.AuthState
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.AuthorizationSummary
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.ui.theme.MasterMechanicTheme

/**
 * 授权流入口（M0-T0-2）：展示各关键授权状态，并提供一键前往授予。
 *
 * @param resumeTick 每次回到前台自增，用于从系统设置页返回后刷新状态。
 */
@Composable
fun AuthorizationRoute(resumeTick: Int) {
    val context = LocalContext.current
    var captureSession by rememberSaveable { mutableStateOf(CaptureSessionState.NOT_GRANTED) }
    var refreshTick by remember { mutableStateOf(0) }

    val statuses = remember(resumeTick, captureSession, refreshTick) {
        AuthorizationChecks.collect(context, captureSession)
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            captureSession = CaptureSessionState.GRANTED
            // 授权凭证不落盘、不复用（ADR-001）；采集会话的创建与维持由 T0-4 服务壳接入
        }
        refreshTick++
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    AuthorizationScreen(
        statuses = statuses,
        onOpenAccessibilitySettings = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onRequestCapture = {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            captureLauncher.launch(manager.createScreenCaptureIntent())
        },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}

@Composable
fun AuthorizationScreen(
    statuses: List<AuthStatus>,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.auth_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            statuses.forEach { status ->
                AuthorizationCard(
                    status = status,
                    onAction = when (status.item) {
                        AuthItem.ACCESSIBILITY -> onOpenAccessibilitySettings
                        AuthItem.SCREEN_CAPTURE -> onRequestCapture
                        AuthItem.NOTIFICATIONS -> onRequestNotifications
                    },
                )
            }
            SummaryText(statuses = statuses)
        }
    }
}

@Composable
private fun AuthorizationCard(status: AuthStatus, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(itemLabel(status.item)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(itemStatusText(status)),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (status.state) {
                        AuthState.GRANTED -> MaterialTheme.colorScheme.primary
                        AuthState.MISSING -> MaterialTheme.colorScheme.error
                        AuthState.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(itemDescription(status.item)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.state == AuthState.MISSING) {
                Spacer(modifier = Modifier.height(2.dp))
                Button(onClick = onAction) {
                    Text(text = stringResource(itemActionText(status.item)))
                }
            }
        }
    }
}

@Composable
private fun SummaryText(statuses: List<AuthStatus>) {
    if (AuthorizationSummary.isAllReady(statuses)) {
        Text(
            text = stringResource(R.string.auth_summary_ready),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        val missingLabels = AuthorizationSummary.missingItems(statuses)
            .map { stringResource(itemLabel(it)) }
        Text(
            text = stringResource(R.string.auth_summary_missing, missingLabels.joinToString("、")),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@StringRes
private fun itemLabel(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_label
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_label
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_label
}

@StringRes
private fun itemDescription(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_desc
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_desc
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_desc
}

@StringRes
private fun itemActionText(item: AuthItem): Int = when (item) {
    AuthItem.ACCESSIBILITY -> R.string.auth_accessibility_action
    AuthItem.SCREEN_CAPTURE -> R.string.auth_capture_action
    AuthItem.NOTIFICATIONS -> R.string.auth_notifications_action
}

@StringRes
private fun itemStatusText(status: AuthStatus): Int = when (status.item) {
    AuthItem.ACCESSIBILITY -> if (status.state == AuthState.GRANTED) {
        R.string.auth_status_enabled
    } else {
        R.string.auth_status_disabled
    }
    AuthItem.SCREEN_CAPTURE -> if (status.state == AuthState.GRANTED) {
        R.string.auth_capture_status_granted
    } else {
        R.string.auth_capture_status_missing
    }
    AuthItem.NOTIFICATIONS -> when (status.state) {
        AuthState.GRANTED -> R.string.auth_notifications_status_granted
        AuthState.MISSING -> R.string.auth_notifications_status_missing
        AuthState.NOT_APPLICABLE -> R.string.auth_status_not_applicable
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthorizationScreenPreview() {
    MasterMechanicTheme {
        AuthorizationScreen(
            statuses = listOf(
                AuthStatus(AuthItem.ACCESSIBILITY, AuthState.GRANTED),
                AuthStatus(AuthItem.SCREEN_CAPTURE, AuthState.MISSING),
                AuthStatus(AuthItem.NOTIFICATIONS, AuthState.MISSING),
            ),
            onOpenAccessibilitySettings = {},
            onRequestCapture = {},
            onRequestNotifications = {},
        )
    }
}
