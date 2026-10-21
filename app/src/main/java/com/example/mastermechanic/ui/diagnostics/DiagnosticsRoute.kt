package com.example.mastermechanic.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.mastermechanic.action.PopupCloseSignal
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.FrameCostSignal
import com.example.mastermechanic.capture.FrameFreshness
import com.example.mastermechanic.decision.UiStateSignal
import com.example.mastermechanic.foreground.ForegroundSignal
import com.example.mastermechanic.foreground.ForegroundStatus
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.ui.run.RunPageLogic
import kotlinx.coroutines.delay

/** 轮询周期（ms）：与「运行」页同节奏（诊断页是"看现场"，半秒足够）。 */
private const val POLL_MS = 500L

/**
 * 「诊断」页（M5-U6 第二段，2026-10-03）：**四组只读读数** —— 画面 / 当前画面 / 单帧处理耗时 / 日志。
 *
 * ## 本页**不持有任何状态**（与「运行」页同一做法）
 *
 * 每 500ms 读一次现场，全部来自既有信号：`CaptureSessionSignal` / `FrameFreshness` /
 * `UiStateSignal` / `ForegroundSignal` / `PopupCloseSignal` / `FrameCostSignal` / `MmLog.currentFile()`。
 * ⇒ 它**不可能**与悬浮窗或日志说出两句不同的话（"两处说法不一致"是本项目最怕的失败方式）。
 *
 * @param resumeTick 每次回到前台自增（从系统设置页返回时也要刷新）。
 */
@Composable
fun DiagnosticsRoute(resumeTick: Int) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(resumeTick) {
        while (true) {
            delay(POLL_MS)
            tick++
        }
    }
    // 读数刻意在**每次重组**现读（而不是 remember 缓存）：那些都是普通对象在别的线程里写的，
    // 缓存起来反而可能读到上一轮的值（"最多晚 0.5 秒看到变化"人眼不可辨，缓存只会更糟）。
    @Suppress("UNUSED_EXPRESSION")
    tick

    val captureActive = CaptureSessionSignal.isActive
    DiagnosticsScreen(
        captureActive = captureActive,
        frameState = RunPageLogic.frameState(
            captureActive = captureActive,
            starved = FrameFreshness.isStarved(),
            stale = FrameFreshness.isStale(),
        ),
        frameAgeMs = FrameFreshness.ageMs(),
        stateLabel = UiStateSignal.status.label,
        foreground = ForegroundSignal.status == ForegroundStatus.FOREGROUND,
        popupAttempts = PopupCloseSignal.attempts,
        popupStoppedReason = PopupCloseSignal.gaveUpReason,
        popupPaused = PopupCloseSignal.paused,
        cost = FrameCostSignal.latest,
        logFileName = MmLog.currentFile()?.name,
        logFilePath = MmLog.currentFile()?.absolutePath,
    )
}

