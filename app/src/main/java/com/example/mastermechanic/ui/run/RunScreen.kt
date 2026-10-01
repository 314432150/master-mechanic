package com.example.mastermechanic.ui.run

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.auth.AuthStatus
import com.example.mastermechanic.auth.AuthorizationChecks
import com.example.mastermechanic.auth.CaptureSessionState
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.FrameFreshness
import com.example.mastermechanic.patrol.PatrolRequestSignal
import com.example.mastermechanic.patrol.PatrolResultSignal
import com.example.mastermechanic.patrol.PatrolSession
import com.example.mastermechanic.patrol.PatrolStatus
import kotlinx.coroutines.delay

/** 轮询周期（ms）：与识别"稳定档"同一个节奏（需求：确实稳定时 ≤1s），不必更快。 */
private const val POLL_MS = 500L

/**
 * **「运行」页**（M5-U2，2026-10-01）：回答三件事 —— **能不能跑 / 跑到哪了 / 能不能动手停**。
 *
 * ```
 * ┌ 授权 ─────────────────────────────┐
 * │ 四项都就绪 / 还缺 N 项   [去「授权与权限」] │
 * ├ 画面 ─────────────────────────────┤
 * │ 正常（新帧 42 毫秒前）/ 停更 N 秒 / …  [重新授权采集] │
 * ├ 流程 ─────────────────────────────┤
 * │ 第 3/10 步 · 退出登录                 │
 * │ 刚点过，等画面切换                    │
 * │                    [停止] [继续]      │
 * └──────────────────────────────────┘
 * ```
 *
 * ## 三个刻意的取舍（都写在这里，免得后人"顺手改回去"）
 *
 * 1. **本页不持有任何状态**：三块内容全部来自现有信号（授权检查 / `FrameFreshness` + `CaptureSessionSignal` /
 *    `PatrolSession` + `PatrolStatus` + `PatrolResultSignal`）。这样它**不可能**与悬浮窗标签说出两句不同的话 ——
 *    而"两处说法不一致"正是 FR-05「不静默」最怕的失败方式。
 * 2. **500ms 轮询读现场**（不是订阅）：那些信号是普通对象（帧线程 / 服务线程在写），要订阅就得给它们加
 *    观察者接口 —— 为一张展示页改四处生产代码，不划算。代价是**最多晚 0.5 秒看到变化**（人眼不可辨）。
 * 3. **没有"发起执行"按钮**（用户 2026-10-01 拍板）：要跑就回游戏用悬浮窗点 —— App 不留第二个发起点，
 *    免得出现"在哪儿点的"两条口径。所以本页只有**停止 / 继续**两个动作，且走的正是悬浮窗那两个
 *    `PatrolRequestSignal.Kind`（同一条消费链，日志与留痕都不必重写）。
 */
@Composable
fun RunRoute(
    resumeTick: Int,
    onOpenAuth: () -> Unit,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumeTick) {
        while (true) {
            delay(POLL_MS)
            tick++
        }
    }

    // ---- 三块读数（每次 tick / 回前台各重算一遍）----
    val nowMs = remember(tick) { SystemClock.elapsedRealtime() }
    val statuses: List<AuthStatus> = remember(tick) {
        AuthorizationChecks.collect(
            context = context,
            captureSession = if (CaptureSessionSignal.isActive) {
                CaptureSessionState.GRANTED
            } else {
                CaptureSessionState.NOT_GRANTED
            },
        )
    }
    val running = PatrolSession.current
    val display = PatrolResultSignal.displayState(running, nowMs)
    val frameState = RunPageLogic.frameState(
        captureActive = CaptureSessionSignal.isActive,
        starved = FrameFreshness.isStarved(),
        stale = FrameFreshness.isStale(),
    )
    val frameAgeMs = FrameFreshness.ageMs() ?: 0L
    val actions = RunPageLogic.actions(running)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① 授权
        StatusCard(
            title = stringResource(R.string.run_card_auth),
            body = if (RunPageLogic.authReady(statuses)) {
                stringResource(R.string.run_auth_ready)
            } else {
                stringResource(R.string.run_auth_missing, RunPageLogic.missingAuthCount(statuses))
            },
        ) {
            TextButton(onClick = onOpenAuth) { Text(stringResource(R.string.run_open_auth)) }
        }

        // ② 画面
        StatusCard(
            title = stringResource(R.string.run_card_frame),
            body = when (frameState) {
                RunPageLogic.FrameState.SESSION_DOWN -> stringResource(R.string.run_frame_no_session)
                RunPageLogic.FrameState.NEVER_FED -> stringResource(R.string.run_frame_starved)
                RunPageLogic.FrameState.STALE ->
                    stringResource(R.string.run_frame_stale, frameAgeMs / 1000)

                RunPageLogic.FrameState.LIVE -> stringResource(R.string.run_frame_live, frameAgeMs)
            },
        ) {
            // 只有真出问题时才给这条入口（正常跑着时不给 —— 免得被当成"随手点一下"的按钮）
            if (frameState != RunPageLogic.FrameState.LIVE) {
                TextButton(onClick = onOpenAuth) {
                    Text(stringResource(R.string.floating_menu_reauthorize))
                }
            }
        }

        // ③ 流程
        val progress = PatrolStatus.progressLine(display)
        val status = PatrolStatus.statusLine(display, PatrolSession.lastNote)
        StatusCard(
            title = stringResource(R.string.run_card_flow),
            body = when {
                progress == null || status == null -> stringResource(R.string.run_flow_idle)
                // 两行拼成一句给读屏：进度 + 状态（视觉上仍分两行）
                else -> "$progress\n$status"
            },
        ) {
            // **"继续"只指路、不做按钮**（用户 2026-10-01："在 App 里出现继续按钮没有意义，
            // 因为游戏不在前台"）：跑号要游戏在前台才成立，而在 App 里按「继续」时前台正是我们自己
            // ⇒ 那一枪要么被门禁拦下、要么立刻再暂停一次。入口留在游戏（与"发起只在悬浮窗"同一条口径）。
            if (actions.resumeInGame) {
                Text(
                    text = stringResource(
                        R.string.run_resume_in_game,
                        PatrolSession.current?.step?.number ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (actions.stop) {
                Button(onClick = { requestPatrol(PatrolRequestSignal.Kind.STOP) }) {
                    // 文案与悬浮窗那一行**同一个字符串**（术语统一）
                    Text(stringResource(R.string.floating_menu_stop))
                }
            }
        }
    }
}

/**
 * 发一次流程控制请求 —— **与悬浮窗控制行走同一条链**（[PatrolRequestSignal] ⇒ `PatrolRequestConsumer`）：
 * 日志（`MM-Patrol`）、留痕、`canResume` 的准入条件、以及"没有进行中的流程"那句话，全都不必重写。
 */
private fun requestPatrol(kind: PatrolRequestSignal.Kind) {
    PatrolRequestSignal.request(
        PatrolRequestSignal.Request(kind = kind, nowMs = SystemClock.elapsedRealtime()),
    )
}

/** 卡片的统一外壳：标题 + 正文 + 右侧动作（三块内容长得一样，省得各写一套样式）。 */
@Composable
private fun StatusCard(
    title: String,
    body: String,
    actions: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
