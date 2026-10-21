package com.example.mastermechanic.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.capture.FrameCostSignal
import com.example.mastermechanic.ui.run.RunPageLogic

/**
 * 「诊断」页的**无状态**部分（与 [DiagnosticsRoute] 分开：便于预览，也便于日后截图取证）。
 *
 * 四组只读读数：画面 / 当前画面 / 单帧处理耗时 / 日志 —— 每一项都**与 `MM-Capture` 日志同源**
 * （同一份信号 / 同一个 publish 点）⇒「诊断页数值与日志口径一致」结构上成立，不靠人工核对。
 *
 * 页面**刻意没有**改动入口（诊断 = 看问题；改东西去「设置」/「授权与权限」），也**不新造阈值**。
 */
@Composable
fun DiagnosticsScreen(
    captureActive: Boolean,
    frameState: RunPageLogic.FrameState,
    frameAgeMs: Long?,
    stateLabel: String,
    foreground: Boolean,
    popupAttempts: Int,
    popupStoppedReason: String,
    popupPaused: Boolean,
    cost: FrameCostSignal.Reading?,
    logFileName: String?,
    logFilePath: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        frameCard(captureActive, frameState, frameAgeMs)
        stateCard(stateLabel, foreground, popupAttempts, popupStoppedReason, popupPaused)
        costCard(cost)
        logCard(logFileName, logFilePath)
    }
}

/** ① 画面：会话 + 帧龄；判定**复用「运行」页的 [RunPageLogic.frameState]** ⇒ 两处说同一句话。 */
@Composable
private fun frameCard(
    captureActive: Boolean,
    frameState: RunPageLogic.FrameState,
    frameAgeMs: Long?,
) {
    DiagCard(
        title = stringResource(R.string.nav_diag_frame),
        lines = buildList {
            add(
                stringResource(
                    if (captureActive) {
                        R.string.nav_diag_session_active
                    } else {
                        R.string.nav_diag_session_down
                    },
                ),
            )
            add(
                if (frameAgeMs == null) {
                    stringResource(R.string.nav_diag_frame_never)
                } else {
                    stringResource(R.string.nav_diag_frame_age, frameAgeMs)
                },
            )
            add(
                // ⚠ 整个 when 必须**返回 String**：早前写成"when 返回 resId、两个分支又直接 stringResource"
                // ⇒ 分支类型不一致（Int 与 String 混在一起）编译不过
                when (frameState) {
                    RunPageLogic.FrameState.SESSION_DOWN ->
                        stringResource(R.string.run_frame_no_session)

                    RunPageLogic.FrameState.NEVER_FED -> stringResource(R.string.run_frame_starved)
                    RunPageLogic.FrameState.STALE ->
                        stringResource(R.string.run_frame_stale, (frameAgeMs ?: 0L) / 1000)

                    RunPageLogic.FrameState.LIVE ->
                        stringResource(R.string.run_frame_live, frameAgeMs ?: 0L)
                },
            )
        },
    )
}

/** ② 当前画面：识别结论 + 目标是否在前台 + 自动关弹窗在干什么。 */
@Composable
private fun stateCard(
    stateLabel: String,
    foreground: Boolean,
    popupAttempts: Int,
    popupStoppedReason: String,
    popupPaused: Boolean,
) {
    // 顺序即"从最需要知道往下"：暂停 > 停手 > 正在关 > 待命
    val popup = when {
        popupPaused -> stringResource(R.string.nav_diag_popup_paused)
        popupStoppedReason.isNotBlank() ->
            stringResource(R.string.nav_diag_popup_stopped, popupStoppedReason)

        popupAttempts > 0 -> stringResource(R.string.nav_diag_popup_closing, popupAttempts)
        else -> stringResource(R.string.nav_diag_popup_idle)
    }
    DiagCard(
        title = stringResource(R.string.nav_diag_state),
        lines = buildList {
            add(stringResource(R.string.nav_diag_state_value, stateLabel))
            add(
                stringResource(
                    R.string.nav_diag_foreground,
                    stringResource(
                        if (foreground) {
                            R.string.nav_diag_foreground_yes
                        } else {
                            R.string.nav_diag_foreground_no
                        },
                    ),
                ),
            )
            add(stringResource(R.string.nav_diag_popup, popup))
        },
    )
}

/** ③ 单帧处理耗时：与日志那行**同一个 publish 点**（`FrameCostSignal`）⇒ 页面与日志必然对得上。 */
@Composable
private fun costCard(cost: FrameCostSignal.Reading?) {
    val level = DiagnosticsLogic.costLevel(cost?.totalP95Ms)
    DiagCard(
        title = stringResource(R.string.nav_diag_cost),
        lines = buildList {
            if (cost == null || level == DiagnosticsLogic.CostLevel.UNKNOWN) {
                add(stringResource(R.string.nav_diag_cost_unknown))
            } else {
                add(
                    stringResource(
                        R.string.nav_diag_cost_value,
                        cost.sampleCount,
                        cost.totalP95Ms.toString(),
                        cost.grayP95Ms.toString(),
                        cost.detectP95Ms.toString(),
                        cost.avgMs.toString(),
                        cost.maxMs.toString(),
                    ),
                )
                add(
                    stringResource(
                        if (level == DiagnosticsLogic.CostLevel.OVER) {
                            R.string.nav_diag_cost_over
                        } else {
                            R.string.nav_diag_cost_ok
                        },
                    ),
                )
            }
        },
    )
}

/** ④ 日志：只报"在哪、今天那份叫什么"，**不给出任何按钮**（本页只读）。 */
@Composable
private fun logCard(logFileName: String?, logFilePath: String?) {
    DiagCard(
        title = stringResource(R.string.nav_diag_log),
        lines = buildList {
            if (logFileName == null) {
                add(stringResource(R.string.nav_diag_cost_unknown))
            } else {
                add(stringResource(R.string.nav_diag_log_file, logFileName))
                if (logFilePath != null) {
                    add(stringResource(R.string.nav_diag_log_path, logFilePath))
                }
            }
            add(stringResource(R.string.nav_diag_log_hint))
        },
    )
}

/** 诊断页的卡片外壳：标题 + 若干行（只有两种元素 ⇒ 不给每行再套样式）。 */
@Composable
private fun DiagCard(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

