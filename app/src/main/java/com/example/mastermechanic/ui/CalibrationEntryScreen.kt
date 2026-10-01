package com.example.mastermechanic.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRowScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.mastermechanic.R
import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.ui.list.DeleteActionButton
import com.example.mastermechanic.ui.list.SwipeActionRow
import com.example.mastermechanic.calibration.CalibrationFramePool
import com.example.mastermechanic.calibration.CalibrationFrames
import com.example.mastermechanic.calibration.CalibrationSignals
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.calibration.FrameEditMath
import com.example.mastermechanic.calibration.FrameHit
import com.example.mastermechanic.calibration.RatioRect
import com.example.mastermechanic.calibration.SelectionWindow
import com.example.mastermechanic.calibration.SignalRole
import com.example.mastermechanic.calibration.TemplateExtractor
import com.example.mastermechanic.calibration.ViewTransform
import com.example.mastermechanic.capture.CalibrationActiveSignal
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.patrol.PatrolAnchors
import com.example.mastermechanic.patrol.PatrolScenes
import com.example.mastermechanic.patrol.SignalNames
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.SignalDetector
import com.example.mastermechanic.recognition.SignalSpec
import com.example.mastermechanic.recognition.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun CalibrationScreen(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    artifact: ArtifactState,
    message: String?,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
    restorableCount: Int,
    onRestoreFrames: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onViewSignal: (String, SignalRole) -> Unit,
    onRemoveSignal: (String, SignalRole) -> Unit,
    onDeleteArtifact: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    /** 「说明」弹层开关（M5-U5：长说明不再常驻首屏）。 */
    var aboutOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 与清单页同一套：**inset 只由壳消费一次**（内层再吃一遍系统栏 ⇒ 顶/底各多一段空白，
        // 2026-10-02 用户报"顶部空白太宽"后的全库排查）。本页在壳里是"自带页头、壳不出标题栏"，
        // 系统栏那一条**壳已经算进 innerPadding** 了 ⇒ 这里同样清零。
        contentWindowInsets = WindowInsets(0.dp),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // M5-U5（ADR-009 决策五）：**标题与返回都收进壳**（用户 2026-10-01："标定页顶部为什么没有
            // 标题栏"）⇒ 页内不再有「返回授权页」，标题由壳的 `TopAppBar` 出（`nav_calibration` = 标定）。
            // 原来那行长副标题（`calibration_subtitle` ≈120 字）压成 **1 行要点 + 「说明」弹层**（验收 V2）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calibration_one_line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { aboutOpen = true }) {
                    Text(stringResource(R.string.calibration_about))
                }
            }
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            FramesCard(
                captureActive = captureActive,
                frames = frames,
                recording = recording,
                restorableCount = restorableCount,
                onToggleRecord = onToggleRecord,
                onClearFrames = onClearFrames,
                onRestoreFrames = onRestoreFrames,
            )

            ArtifactCard(
                artifact = artifact,
                framesExist = frames.isNotEmpty(),
                onOpenWorkbench = onOpenWorkbench,
                onViewSignal = onViewSignal,
                onRemoveSignal = onRemoveSignal,
                onDeleteArtifact = onDeleteArtifact,
            )

        }
    }

    if (aboutOpen) {
        CalibrationAboutSheet(onDismiss = { aboutOpen = false })
    }
}

/**
 * 「说明」弹层（标定入口页，M5-U5）。
 *
 * 与两个清单页**同一个版式**：长说明不再常驻首屏（V2：首屏 1 行、超 60 字必须可折叠且默认收起），
 * 这里承接原来 `calibration_subtitle`（≈120 字）里的三件事 —— 流程 / 产物地位 / 无设备参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationAboutSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_about),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.calibration_about_flow),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.calibration_about_source),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.calibration_about_params),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun FramesCard(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    restorableCount: Int,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
    onRestoreFrames: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.calibration_frames_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.calibration_frames_count, frames.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!captureActive) {
                Text(
                    text = stringResource(R.string.calibration_capture_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleRecord, enabled = recording || captureActive) {
                    Text(
                        text = stringResource(
                            if (recording) R.string.calibration_record_stop
                            else R.string.calibration_record_start
                        ),
                    )
                }
                OutlinedButton(onClick = onClearFrames, enabled = frames.isNotEmpty()) {
                    Text(text = stringResource(R.string.calibration_frames_clear))
                }
            }
            // 回收站非空 → 卡片上常驻一个「恢复」入口（2026-09-20 用户口径）：
            // Snackbar 只活几秒，而"清错了 49 帧"这件事可能过一会儿才发现。
            if (restorableCount > 0) {
                TextButton(onClick = onRestoreFrames, modifier = Modifier.align(Alignment.Start)) {
                    Text(text = stringResource(R.string.calibration_frames_restore, restorableCount))
                }
            }
            Text(
                text = stringResource(R.string.calibration_frames_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (frames.isEmpty()) {
                Text(
                    text = stringResource(R.string.calibration_frames_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * **破坏性操作的二次确认**（2026-09-20 用户口径）：清空帧池 / 删除整个产物都先问一次。
 *
 * 为什么这两处值得多一次点击（标定页里"删一行"就不需要）：代价不对等 ——
 * 删一行只是少一个元素、重框一次；**清空帧池**要回到游戏里再跑一遍各界面才能录回来，
 * **删除产物**要重框 11 条锚点 + 10 个界面标志。用户提的正是这个不对等。
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                // 确认按钮用 error 色：这一下点下去是要真的删东西（与「知道了」那种中性按钮区分开）
                Text(text = confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.calibration_cancel))
            }
        },
    )
}

@Composable
internal fun ArtifactCard(
    artifact: ArtifactState,
    framesExist: Boolean,
    onOpenWorkbench: () -> Unit,
    onViewSignal: (String, SignalRole) -> Unit,
    onRemoveSignal: (String, SignalRole) -> Unit,
    onDeleteArtifact: () -> Unit,
) {
    val data = artifact.data
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_artifact_title),
                style = MaterialTheme.typography.titleMedium,
            )
            // T1-5m：工作台入口属标定产物操作，移入本卡
            Button(
                onClick = onOpenWorkbench,
                enabled = framesExist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.calibration_workbench_open))
            }
            when {
                artifact.broken -> Text(
                    text = stringResource(R.string.calibration_artifact_broken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                data == null -> Text(
                    text = stringResource(R.string.calibration_artifact_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    // 按「所属界面」分组 + 顶部筛选 chip（2026-09-19 用户口径）：
                    // 行 = 一个**元素**（同一次框选的标志 + 锚点合成一行），筛选默认停在「全部」。
                    val groups = CalibrationArtifactGroups.of(data)
                    var filter by remember(data) { mutableStateOf<UiState?>(null) }
                    // 左滑删除的状态机（与服务器清单 / 好友清单同一套：复用 SwipeActionRow + 纯逻辑判据）。
                    // 只在一行露出、露出比例按元素 ID 索引；ID 在清单里唯一，正好当行的稳定标识。
                    val swipeScope = rememberCoroutineScope()
                    val haptics = LocalHapticFeedback.current
                    val density = LocalDensity.current.density
                    val revealWidthDp = ServerListGestures.REVEAL_WIDTH_DP.dp
                    val revealWidthPx = with(LocalDensity.current) { revealWidthDp.toPx() }
                    // ⚠ 2026-09-29 修（用户报"写入标志后返回产物列表，那条的删除按钮自己冒出来"，
                    // 且他**从未划过任何一行**）：原因是 `revealFractions`（每行拉开多少）不跟产物走 ——
                    // 写入会让产物变、[revealedKey] 归零，而比例还留着残留值 ⇒ 行被画成"半拉开"
                    // （`revealed=false` 但 `revealFraction≠0`）⇒ 删除按钮露在行尾。
                    // 半拉开本身很容易出现：手指离开页面（进工作台 / 点返回）时 [swipeScope] 被取消，
                    // 180ms 的吸附动画停在半路 ⇒ 比例卡在 0 与 1 之间 —— **不需要"故意划"**。
                    //
                    // ⚠ 修法**不能**是 `remember(data) { … }`（第一版就是这么写的，已回退）：
                    // 把 `data` 当钥匙会让这两个状态在**每次产物换身份时整体重建**，而它们被
                    // 列表行 / 位图的 `remember` 牵连 —— 真机上表现为主线程反复重活（ANR + 每半秒
                    // 100MB 垃圾）。**改用"写入后显式清一遍"**：状态只在原地复用，不换实例。
                    val revealFractions = remember { mutableStateMapOf<String, Float>() }
                    var revealedKey by remember(data) { mutableStateOf<String?>(null) }
                    val settleJobs = remember { mutableMapOf<String, Job>() }
                    // 产物一变（= 写入成功、删除、撤销）就把"露出"清干净：不留半拉开、也不留已露出。
                    // 用 [data] 当**副作用**的钥匙是安全的（只重跑这段清理，不重建任何状态实例）。
                    LaunchedEffect(data) {
                        settleJobs.values.forEach { it.cancel() }
                        settleJobs.clear()
                        revealFractions.clear()
                        revealedKey = null
                    }
                    val settle: (String, Float, Float) -> Unit = { key, from, target ->
                        settleJobs[key]?.cancel()
                        settleJobs[key] = swipeScope.launch {
                            Animatable(from).animateTo(target, tween(CalibrationSwipeSettleMillis, easing = FastOutSlowInEasing)) {
                                revealFractions[key] = value
                            }
                        }
                    }
                    val collapse: (String) -> Unit = { key ->
                        val from = revealFractions[key] ?: 0f
                        if (from != 0f) settle(key, from, 0f)
                        if (revealedKey == key) revealedKey = null
                    }
                    val roleCounts = CalibrationArtifactGroups.roleCounts(groups)
                    // **统计信息分两行**（用户 2026-10-01 口径）：一行说"多少条"、一行说"覆盖范围"。
                    // 挤作一行时后半截（界面数 / 帧尺寸）会被挤到换行处，读起来像两件无关的事。
                    Text(
                        text = stringResource(
                            R.string.calibration_artifact_summary,
                            // 「共 N 条」= **标志条数 + 锚点条数**（用户口径）：
                            // 口径收在 CalibrationArtifactGroups.roleCounts / rowCount，与分组标题、
                            // 筛选按钮**同源**；这里把两项都摆出来，省得"总条数到底数の什么"再对不上。
                            roleCounts.total,
                            roleCounts.marker,
                            roleCounts.anchor,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.calibration_artifact_scope,
                            groups.count { it.state != UiState.UNKNOWN },
                            data.frameWidth,
                            data.frameHeight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = filter == null,
                                onClick = { filter = null },
                                label = { Text(stringResource(R.string.calibration_filter_all)) },
                            )
                        }
                        items(groups, key = { it.state.name }) { group ->
                            FilterChip(
                                selected = filter == group.state,
                                onClick = { filter = group.state },
                                label = {
                                    // 计数与「总条数」同源：都数**记录行**（每个角色一行）
                                    Text("${group.state.label} ${CalibrationArtifactGroups.rowsOf(group).size}")
                                },
                            )
                        }
                    }
                    groups.filter { filter == null || it.state == filter }.forEach { group ->
                        if (filter == null) {
                            Text(
                                text = stringResource(
                                    R.string.calibration_group_title,
                                    group.state.label,
                                    CalibrationArtifactGroups.rowsOf(group).size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        val deleteLabel = stringResource(R.string.calibration_signal_remove)
                        // **行 = 一条记录**（2026-10-01 用户口径"标志和锚点分两行显示"）：左滑删除也按这一行的
                        // 角色走（删哪行删哪条）；行键带角色，同一 ID 的两条不会互相顶掉。
                        val rows = CalibrationArtifactGroups.rowsOf(group)
                        rows.forEachIndexed { index, row ->
                            val element = row.element
                            val key = row.key
                            // 删除 = **左滑 → 行尾压出按钮 → 点按钮**（2026-09-19 用户口径，与服务器 / 好友清单同一套）。
                            // 行本身不动，按钮盖在行尾上；读屏走 ArtifactRow 的自定义「删除」动作（同一个出口）。
                            SwipeActionRow(
                                key = key,
                                revealed = revealedKey == key,
                                anyRevealed = revealedKey != null,
                                revealWidthPx = revealWidthPx,
                                revealFraction = revealFractions[key] ?: 0f,
                                rowHeight = ArtifactRowHeight,
                                onDelete = { onRemoveSignal(element.id, row.role) },
                                onCollapse = { revealedKey?.let(collapse) },
                                onSwipeStart = { settleJobs[key]?.cancel() },
                                onSwipe = { revealFractions[key] = it },
                                onSwipeEnd = { fraction, velocityX ->
                                    val open = ServerListGestures.swipeRevealsDeleteButton(
                                        revealFraction = fraction,
                                        velocityXPx = velocityX,
                                        density = density,
                                    )
                                    if (open) {
                                        // 同一时刻只露出一行：把上一行收起，再震一下提示"这一行已露出"
                                        if (revealedKey != key) {
                                            revealedKey?.let(collapse)
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            revealedKey = key
                                        }
                                        settle(key, fraction, 1f)
                                    } else {
                                        if (revealedKey == key) revealedKey = null
                                        settle(key, fraction, 0f)
                                    }
                                },
                                action = {
                                    DeleteActionButton(
                                        label = deleteLabel,
                                        description = deleteLabel,
                                        // 只有停稳之后才可点：拖动途中不算，避免手指一划就删掉
                                        enabled = revealedKey == key,
                                        onDelete = { onRemoveSignal(element.id, row.role) },
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(revealWidthDp)
                                            .clip(
                                                RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                                            ),
                                    )
                                },
                            ) {
                                ArtifactRow(
                                    row = row,
                                    state = group.state,
                                    onTap = { onViewSignal(element.id, row.role) },
                                    onRemove = { onRemoveSignal(element.id, row.role) },
                                )
                            }
                            // 组内细分隔线（最后一行不画）：分隔的是"内容"，不是给每行套边框 ——
                            // 20 行各加边框/卡片底会变成一片线条噪声，且缩略图自带深色底会打架
                            if (index < rows.size - 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    OutlinedButton(onClick = onDeleteArtifact) {
                        Text(text = stringResource(R.string.calibration_delete))
                    }
                }
            }
            Text(
                text = stringResource(R.string.calibration_take_effect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 产物清单里的一行（2026-09-19 重构为移动端形态；**2026-10-01 起一行 = 一条记录**）：
 *
 * - **一行 = 一条记录**（标志或锚点各占一行，用户口径"分两行显示"）：缩略图 / 主文本（备注 > 用途 > 尺寸）
 *   / 角色徽标；**窗口坐标这类开发参数不进列表**（在详情里看），否则十几行会把列表撑成两屏以上；
 * - **点整行 = 看详情**（取代原先行尾常驻的「查看」按钮，同一入口不重复出现），详情看的是**这一行那条记录**；
 * - **行尾没有常驻按钮**（那是 PC 习惯）：删除走读屏的 `customActions`，列表末尾的「删除产物」走二次确认；
 * - 整行合成**一个**语义节点：读屏念「名字，类型，界面，模板 W 乘 H」，并可从中调出删除动作。
 */
@Composable
private fun ArtifactRow(
    row: ArtifactRow,
    state: UiState,
    onTap: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val entry = row.item.entry
    val template = entry.templates.first()
    // 缩略图 = 这一行的主信息（用户口径：用户认的是"我框的那个东西长什么样"，不是记录名）
    val bitmap = remember(template) { CalibrationFrames.templateBitmap(template).asImageBitmap() }
    val density = LocalDensity.current
    // 等比例放大：固定高度按模板宽高比算宽度（上限避免长条模板把行撑宽）
    val thumbHeight = 36.dp
    val thumbWidth = with(density) {
        (thumbHeight.toPx() * template.width / template.height).toDp()
    }.coerceAtMost(110.dp)
    // 一行一条记录 ⇒ 徽标只写**这一行**的角色（2026-10-01 分两行显示后，这里不再拼"标志 + 锚点"）
    val roles = row.role.label
    // 主文本 = **这一行那条记录的备注** > 用途 > 尺寸（用户不需要关心图片叫什么，2026-09-19 用户口径）
    val label = CalibrationArtifactGroups.primaryText(row, state)
    val description = stringResource(
        R.string.calibration_signal_row_desc,
        label,
        roles,
        state.label,
        template.width,
        template.height,
    )
    val removeLabel = stringResource(R.string.calibration_signal_remove)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(removeLabel) {
                        onRemove(entry.id)
                        true
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(thumbWidth, thumbHeight)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(2.dp),
        )
        Spacer(modifier = Modifier.size(12.dp))
        // 行内文本：备注 / 用途 / 尺寸。没有它，同组多行会长得一模一样（20 行等于没得扫读）
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        RoleBadge(text = roles)
    }
}

/** 类型徽标（标志 / 锚点）：列表行与详情弹窗共用，形态一致 = \"点开前后还是同一个东西\"。 */
@Composable
internal fun RoleBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * 详情弹窗的键值行：**label 定宽 + value 左对齐**。
 *
 * label 列**全表同一个宽度**（[ParamLabelWidth]）——每行的值才能落在同一条竖线上；
 * 之前参数行用 64dp、底部归属行用 84dp，两组的 value 起点差了一截，看着是歪的。
 */
@Composable
internal fun ParamRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(ParamLabelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一个**元素**的详情（T1-5l ④；T2-3d 起含角色；2026-09-19 起主键是 ID + 有备注）。
 *
 * 顺序刻意按"**离人由近及远**"排：
 *
 * 1. **身份**（标题）：所属界面 + **角色徽标**（同时是标志和锚点时显示「标志 + 锚点」）；
 * 2. **图像**：大图 + 尺寸 caption —— 两面共用，不区分角色；
 * 3. **备注**（可改，用户自己写的）—— 元素级，改一次两面都变；
 * 4. **参数**：搜索窗口 / 标定帧（工程细节）；
 * 5. **归属信息**（最下方，只两行，不占图像的视觉位置）：
 *    - `元素 ID` —— 这个东西是谁（标志与锚点**共用**同一个 ID，它就是清单里的一行）；
 *    - `用途（锚点）` —— 只属于锚点那一面的契约，写人话即可。
 *    不再列"记录"（`hall_farm · 标志` 这种）：知道元素 ID 就够了，那是冗余信息。
 *
 * [sidesDiffer]：标志与锚点分两次标、框得不一样时才为 true —— 那时提示"该删掉重标"，
 * 因为两面本该是同一次框选（共用窗口与模板），不一样说明中途有人改过一面。
 */
@Composable
internal fun TemplateDetailDialog(
    id: String,
    roles: List<SignalRole>,
    state: UiState?,
    purpose: String?,
    /** 归属好友名（2026-09-21）：好友头像显示成「某某的头像」，与清单行同一口径。 */
    friend: String? = null,
    note: String,
    template: Template,
    templateCount: Int,
    window: SearchWindow,
    frameWidth: Int,
    frameHeight: Int,
    sidesDiffer: Boolean = false,
    onSaveNote: (String) -> Unit,
    onClose: () -> Unit,
) {
    val bitmap = remember(id, template) { CalibrationFrames.templateBitmap(template) }
    val px = window.pixelBounds(frameWidth, frameHeight)
    var noteText by remember(id) { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { onSaveNote(noteText.trim()) }) {
                Text(text = stringResource(R.string.calibration_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.calibration_hint_close))
            }
        },
        title = {
            // 身份段：所属界面（大字号）+ 类型徽标 —— 用户刚点过那一行，不必再被告知一次"模板详情"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state?.label.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.size(8.dp))
                RoleBadge(roles.joinToString(" + ") { it.label })
            }
        },
        text = {
            val purposeLabel = purpose?.let {
                CalibrationArtifactGroups.purposeText(state ?: UiState.UNKNOWN, it, friend)
            }
            // 局部变量不能叫 paneTitle：会遮蔽 semantics 的扩展属性，`paneTitle = …` 就变成给 val 赋值
            val detailPaneTitle = stringResource(R.string.calibration_template_title)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // 弹窗身份仍要让读屏念出来（标题槽已换成"界面 + 徽标"，不再是"模板详情"）
                modifier = Modifier.semantics { paneTitle = detailPaneTitle },
            ) {
                // 图像段：图片是主要信息（用户口径），刻意放大；尺寸/样式条数作图的注释而不是独立一行
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = stringResource(R.string.calibration_detail_size, template.width, template.height) +
                        if (templateCount > 1) {
                            stringResource(R.string.calibration_detail_styles, templateCount)
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 备注紧贴图片下方（2026-09-19 用户口径）：看到图 → 顺手写一句"这是啥"，
                // 不用先读一屏工程参数才知道自己在改哪条
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(text = stringResource(R.string.calibration_note_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 参数段：像素级工程细节，两列键值（label 定宽左对齐，值才是要读的）
                ParamRow(
                    label = stringResource(R.string.calibration_detail_window_label),
                    value = stringResource(
                        R.string.calibration_detail_window_value,
                        px.x0,
                        px.y0,
                        px.x1,
                        px.y1,
                    ),
                )
                ParamRow(
                    label = stringResource(R.string.calibration_detail_frame_label),
                    value = stringResource(R.string.calibration_detail_size, frameWidth, frameHeight),
                )
                if (sidesDiffer) {
                    // 两面本该是同一次框选（共用窗口与模板）；不一样说明中途有人只改了一面
                    Text(
                        text = stringResource(R.string.calibration_detail_sides_differ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 归属信息放最下方，只留两行（2026-09-19 用户口径：知道元素 ID 就够，"记录"是冗余）：
                // ① **元素 ID** —— 这个东西是谁（标志与锚点共用同一个 ID，是清单里一行）；
                // ② **用途（锚点）** —— 只属于锚点那一面的契约（人话即可，不必再挂契约名）。
                ParamRow(
                    label = stringResource(R.string.calibration_detail_element_label),
                    value = id,
                )
                if (purposeLabel != null) {
                    ParamRow(
                        label = stringResource(R.string.calibration_detail_purpose_label),
                        // 同时是标志和锚点时要写明"用途属于哪一面"（放在**值**里，不撑宽 label 列：
                        // label 列越窄，值就有越多横向空间，长坐标才不会换行）
                        value = if (roles.size > 1) {
                            stringResource(
                                R.string.calibration_detail_purpose_with_role,
                                purposeLabel,
                                SignalRole.ANCHOR.label,
                            )
                        } else {
                            purposeLabel
                        },
                    )
                }
            }
        },
    )
}

/**
 * 「**识别参数（高级）**」卡（M5-U5 收尾；2026-10-02 用户："『匹配参数』藏在太靠下的位置，
 * 而且三个输入值太过抽象，看不出来是干嘛用的，怎么改也不知道"）。
 *
 * 三档处置里用户选了「**保留但收进「高级」折叠**」⇒ 本卡三条口径：
 * 1. **默认收起** —— 首屏只占一行标题（不点开就永远不占地方，也不必滑到底去"理解"它）；
 * 2. **术语换白话** —— 「命中线 / 差距线 / 峰值间距」⇒「认出画面的门槛 / 两处相像时的辨别线 /
 *    靠多近算同一处」，并且**每个值下面挂一行"什么时候该改"**（用户的原话是"怎么改也不知道"）；
 * 3. 值仍然**编辑合法即写入产物**（行为零回归、产物格式不变）。
 *
 * ⚠ 为什么不是"删掉、只用内置默认"（用户问过）：这三个是**唯一**能调识别松紧的旋钮 ——
 * 换设备后若出现"认不出 / 老判不可信"，没有它就只剩改代码重装一条路（见 `progress.md` 第 415 条）。
 */
