package com.example.mastermechanic.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mastermechanic.R
import com.example.mastermechanic.calibration.CalibrationData
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
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.MatchParams
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 模板最小边长（原图像素）：过小的模板缺少区分度，提取时拒绝。 */
private const val MIN_TEMPLATE_PX = 8

/** 画布预览解码的最大宽度（只影响显示清晰度，不影响模板像素——模板取自原分辨率帧）。 */
private const val PREVIEW_MAX_WIDTH = 1080

/** 关闭钮中心相对选框左上角的外置距离（屏幕 dp；T1-5l 保留 T1-5k 外置口径，避免压住选框角）。 */
private val CLOSE_BUTTON_MARGIN = 16.dp

/** 关闭钮热区半径（屏幕 dp）。 */
private val CLOSE_BUTTON_TOUCH = 20.dp

/**
 * 工作台衬底是近黑（`0xFF101010` + 半透明黑浮层）：其上的文字**不能沿用主题色**——
 * 应用是亮色主题，`primary` / `onSurfaceVariant` 在黑底上都是深色，真机上「看不清」（2026-09-13 用户反馈）。
 * 深色衬底上的文字统一走这两个常量。
 */
private val ON_DARK_SECONDARY = Color.White.copy(alpha = 0.8f)

/** 深色衬底的错误提示色（亮色主题的 `error` 为深红，黑底不可读）。 */
private val ON_DARK_ERROR = Color(0xFFFF8A80)

/** 选框主色 —— 标志（判状态）：沿用 T1-5k 的红色。 */
private val MARKER_ACCENT = Color(0xFFFF5252)

/** 选框主色 —— 锚点（点击位置）：青色（与红色色相相距最远，黑底上同样醒目）。 */
private val ANCHOR_ACCENT = Color(0xFF4DD0E1)

/**
 * 标定页（T1-5b 起；T1-5l 按真机反馈重构；T2-2 起支持同状态多条记录）：**产物即唯一数据源**——
 * 全屏工作台里挑帧 / 框选 / 选归属状态，选中状态即写入产物（名称取默认名、重名自动追加序号，
 * 无草稿、无「保存产物」两步）；页面本体只保留采集控制、产物清单（可查看 / 可删除）与产物级参数
 * （合法即写入）。
 *
 * 产物在采集会话建立时加载进识别循环（CaptureService），本页不直接驱动识别。
 */
@Composable
fun CalibrationRoute(resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var captureActive by remember { mutableStateOf(CaptureSessionSignal.isActive) }
    var frames by remember { mutableStateOf(CalibrationFramePool.listFrames(context)) }
    var recording by remember { mutableStateOf(CalibrationFramePool.isRecording) }
    var selectedFrame by remember { mutableStateOf<File?>(null) }
    var selection by remember(selectedFrame) { mutableStateOf<RatioRect?>(null) }
    var selectedState by remember(selectedFrame) { mutableStateOf<UiState?>(null) }
    // T2-3d：本次框选写入的角色（标志 = 判状态 / 锚点 = 点击位置），默认标志
    var selectedRole by remember(selectedFrame) { mutableStateOf(SignalRole.MARKER) }
    var workbenchOpen by remember { mutableStateOf(false) }
    // T1-5m：工作台是否处于「框选模式」（浏览 = 滑页挑帧；框选 = 禁滑页 + 状态条 + ✕/✓）
    var selectMode by remember { mutableStateOf(false) }
    var viewingSignal by remember { mutableStateOf<String?>(null) }
    var artifactTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val artifact = remember(resumeTick, artifactTick) { loadArtifact(context) }
    // 参数文本按产物当前值初始化（此后只随使用者的编辑走：编辑合法即写入产物，不回填覆盖输入）
    var thresholdText by remember { mutableStateOf(artifact.paramsText(0)) }
    var marginText by remember { mutableStateOf(artifact.paramsText(1)) }
    var minDistanceText by remember { mutableStateOf(artifact.paramsText(2)) }
    val editedParams = CalibrationSignals.parseParams(thresholdText, marginText, minDistanceText)

    // 采集会话状态以真实信号为准（与授权页一致）
    DisposableEffect(Unit) {
        val listener: (CaptureSessionStatus) -> Unit = {
            captureActive = it == CaptureSessionStatus.ACTIVE
        }
        CaptureSessionSignal.addListener(listener)
        onDispose { CaptureSessionSignal.removeListener(listener) }
    }

    // 帧池刷新：进入页面立即刷新；录制中每秒刷新（停止录制时再收尾刷新一次）
    LaunchedEffect(recording, resumeTick) {
        frames = CalibrationFramePool.listFrames(context)
        recording = CalibrationFramePool.isRecording
        while (CalibrationFramePool.isRecording) {
            delay(1000)
            frames = CalibrationFramePool.listFrames(context)
        }
        frames = CalibrationFramePool.listFrames(context)
    }

    /**
     * 参数编辑（T1-5l ⑤）：**合法即写入产物**（无产物时不写，参数随首个信号写入）。
     *
     * 关键：解析必须用编辑后的**实时**文本（局部委托属性的读取是即时的），且基线产物必须现读盘上内容——
     * 若沿用组合期计算出的旧值（`editedParams` / `artifact`），写入会落后一次编辑（真机核对实录：
     * 界面 0.86、产物 0.80）。
     */
    fun applyParams() {
        val params = CalibrationSignals.parseParams(thresholdText, marginText, minDistanceText) ?: return
        if (!CalibrationStore.exists(context)) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = CalibrationStore.load(context) ?: return@withContext false
                    val updated = CalibrationSignals.withParams(current, params) ?: return@withContext false
                    CalibrationStore.save(context, updated)
                    true
                }
            }
                .onSuccess { saved ->
                    if (saved) {
                        artifactTick++
                        message = context.getString(R.string.calibration_param_applied)
                    }
                }
                .onFailure {
                    message = context.getString(
                        R.string.calibration_write_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    CalibrationScreen(
        captureActive = captureActive,
        frames = frames,
        recording = recording,
        artifact = artifact,
        thresholdText = thresholdText,
        marginText = marginText,
        minDistanceText = minDistanceText,
        paramsValid = editedParams != null,
        message = message,
        onBack = onBack,
        onToggleRecord = {
            val target = !recording
            CalibrationFramePool.setRecording(context, target)
            recording = target
        },
        onClearFrames = {
            CalibrationFramePool.clear(context)
            frames = CalibrationFramePool.listFrames(context)
            selectedFrame = null
            message = null
        },
        onOpenWorkbench = {
            selectedFrame = selectedFrame ?: frames.firstOrNull()
            selection = null
            message = null
            workbenchOpen = true
        },
        onThresholdChange = { thresholdText = it; applyParams() },
        onMarginChange = { marginText = it; applyParams() },
        onMinDistanceChange = { minDistanceText = it; applyParams() },
        onViewSignal = { viewingSignal = it },
        onRemoveSignal = { name ->
            // 读盘上现产物再删（T1-5l ②）：不依赖组合期捕获的快照，避免与刚写入的信号错位
            scope.launch {
                val removed = runCatching {
                    withContext(Dispatchers.IO) {
                        val current = CalibrationStore.load(context) ?: return@withContext false
                        val remaining = CalibrationSignals.remove(current, name)
                        if (remaining == null) CalibrationStore.delete(context)
                        else CalibrationStore.save(context, remaining)
                        true
                    }
                }
                removed
                    .onSuccess { done ->
                        if (done) {
                            if (viewingSignal == name) viewingSignal = null
                            artifactTick++
                            message = context.getString(R.string.calibration_signal_deleted, name)
                        }
                    }
                    .onFailure {
                        message = context.getString(
                            R.string.calibration_write_failed,
                            it.message ?: it.javaClass.simpleName,
                        )
                    }
            }
        },
        onDeleteArtifact = {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { CalibrationStore.delete(context) } }
                viewingSignal = null
                artifactTick++
                message = context.getString(R.string.calibration_deleted)
            }
        },
    )

    if (workbenchOpen) {
        // T1-5m：框选模式由工作台回调驱动（进入清旧选框；✓ 写入成功后回浏览模式）
        CalibrationWorkbench(
            frames = frames,
            selectedFrame = selectedFrame,
            selection = selection,
            selectedState = selectedState,
            selectedRole = selectedRole,
            message = message,
            selectMode = selectMode,
            onSelectFrame = { selectedFrame = it },
            onSelectionChange = { selection = it },
            onStateSelect = { selectedState = it },
            onRoleSelect = { selectedRole = it },
            onEnterSelect = {
                selection = null
                message = null
                selectMode = true
            },
            onExitSelect = {
                selection = null
                selectMode = false
            },
            onWriteSignal = {
                if (selectedState == null) {
                    message = context.getString(R.string.calibration_need_state)
                }
                val frame = selectedFrame
                val sel = selection
                val state = selectedState
                if (frame != null && sel != null && state != null) {
                    scope.launch {
                        val result = writeSignalFromSelection(
                            context = context,
                            frame = frame,
                            ratio = sel,
                            state = state,
                            role = selectedRole,
                            // 实时读取编辑框内容（局部委托属性读取即时，不是组合快照）
                            params = CalibrationSignals.parseParams(
                                thresholdText,
                                marginText,
                                minDistanceText,
                            ),
                        )
                        message = result.message
                        if (result.saved) {
                            selection = null
                            artifactTick++
                            selectMode = false
                        }
                    }
                }
            },
            onClose = { workbenchOpen = false },
        )
    }

    if (viewingSignal != null) {
        val data = artifact.data
        val entry = data?.signals?.firstOrNull { it.name == viewingSignal }
        if (data != null && entry != null) {
            TemplateDetailDialog(
                name = entry.name,
                role = entry.role,
                state = CalibrationSignals.stateOf(data, entry.name),
                template = entry.templates.first(),
                templateCount = entry.templates.size,
                window = entry.window,
                frameWidth = data.frameWidth,
                frameHeight = data.frameHeight,
                onClose = { viewingSignal = null },
            )
        }
    }
}

@Composable
private fun CalibrationScreen(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    artifact: ArtifactState,
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    message: String?,
    onBack: () -> Unit,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
    onViewSignal: (String) -> Unit,
    onRemoveSignal: (String) -> Unit,
    onDeleteArtifact: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.calibration_back))
                }
            }
            Text(
                text = stringResource(R.string.calibration_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.calibration_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                onToggleRecord = onToggleRecord,
                onClearFrames = onClearFrames,
            )

            ArtifactCard(
                artifact = artifact,
                framesExist = frames.isNotEmpty(),
                onOpenWorkbench = onOpenWorkbench,
                onViewSignal = onViewSignal,
                onRemoveSignal = onRemoveSignal,
                onDeleteArtifact = onDeleteArtifact,
            )

            ParamsCard(
                thresholdText = thresholdText,
                marginText = marginText,
                minDistanceText = minDistanceText,
                paramsValid = paramsValid,
                hasArtifact = artifact.data != null,
                onThresholdChange = onThresholdChange,
                onMarginChange = onMarginChange,
                onMinDistanceChange = onMinDistanceChange,
            )
        }
    }
}

@Composable
private fun FramesCard(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
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

@Composable
private fun ArtifactCard(
    artifact: ArtifactState,
    framesExist: Boolean,
    onOpenWorkbench: () -> Unit,
    onViewSignal: (String) -> Unit,
    onRemoveSignal: (String) -> Unit,
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
                    Text(
                        text = stringResource(
                            R.string.calibration_artifact_present,
                            data.signals.size,
                            data.frameWidth,
                            data.frameHeight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    data.signals.forEach { entry ->
                        val px = entry.window.pixelBounds(data.frameWidth, data.frameHeight)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        R.string.calibration_signal_item,
                                        entry.name,
                                        entry.role.label,
                                        CalibrationSignals.stateOf(data, entry.name)?.label.orEmpty(),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.calibration_signal_meta,
                                        entry.templates.first().width,
                                        entry.templates.first().height,
                                        px.x0,
                                        px.y0,
                                        px.x1,
                                        px.y1,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onViewSignal(entry.name) }) {
                                Text(text = stringResource(R.string.calibration_view))
                            }
                            TextButton(onClick = { onRemoveSignal(entry.name) }) {
                                Text(text = stringResource(R.string.calibration_signal_remove))
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

/** 产物中一条信号的详情（T1-5l ④；T2-3d 起含角色）：模板灰度图 + 尺寸 + 搜索窗口（帧像素）+ 归属状态。 */
@Composable
private fun TemplateDetailDialog(
    name: String,
    role: SignalRole,
    state: UiState?,
    template: Template,
    templateCount: Int,
    window: SearchWindow,
    frameWidth: Int,
    frameHeight: Int,
    onClose: () -> Unit,
) {
    val bitmap = remember(name, template) { CalibrationFrames.templateBitmap(template) }
    val px = window.pixelBounds(frameWidth, frameHeight)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.calibration_hint_close))
            }
        },
        title = { Text(text = stringResource(R.string.calibration_template_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(4.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    text = stringResource(
                        R.string.calibration_template_meta,
                        role.label,
                        state?.label.orEmpty(),
                        template.width,
                        template.height,
                        templateCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.calibration_template_window,
                        frameWidth,
                        frameHeight,
                        px.x0,
                        px.y0,
                        px.x1,
                        px.y1,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun ParamsCard(
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    hasArtifact: Boolean,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_param_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParamField(
                    label = stringResource(R.string.calibration_param_threshold),
                    value = thresholdText,
                    onValueChange = onThresholdChange,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                ParamField(
                    label = stringResource(R.string.calibration_param_margin),
                    value = marginText,
                    onValueChange = onMarginChange,
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                ParamField(
                    label = stringResource(R.string.calibration_param_min_distance),
                    value = minDistanceText,
                    onValueChange = onMinDistanceChange,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!paramsValid) {
                Text(
                    text = stringResource(R.string.calibration_param_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!hasArtifact) {
                Text(
                    text = stringResource(R.string.calibration_param_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ParamField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

/**
 * 标定工作台（T1-5l ③ 起；T1-5m 按用户参考系统相册定稿交互）：大图占满全屏（黑底等比居中），
 * 缩略图条与工具栏绝对定位悬浮于屏幕底部。
 *
 * 两种模式：
 * - 浏览：左右滑动大图切换帧（缩略图「过半即同步」，T1-5j 口径）；工具栏「框选」进入框选模式；
 * - 框选：禁用滑页、隐藏缩略图条；工具栏左 ✕（放弃本次框选：清选框并回浏览）/
 *   右 ✓（确认写入：需已框选 + 已选归属状态）；工具栏上方为归属状态单选滑动条，再上一行为选区信息
 *   （单行全宽居中：原先夹在 ✕ / ✓ 之间会被挤得折行）。
 *
 * 写入时机（T1-5m 修订 T1-5l 口径）：点 ✓ 按选中状态写入产物（同名信号覆盖），成功后回浏览模式。
 */
@Composable
private fun CalibrationWorkbench(
    frames: List<File>,
    selectedFrame: File?,
    selection: RatioRect?,
    selectedState: UiState?,
    selectedRole: SignalRole,
    message: String?,
    selectMode: Boolean,
    onSelectFrame: (File) -> Unit,
    onSelectionChange: (RatioRect?) -> Unit,
    onStateSelect: (UiState) -> Unit,
    onRoleSelect: (SignalRole) -> Unit,
    onEnterSelect: () -> Unit,
    onExitSelect: () -> Unit,
    onWriteSignal: () -> Unit,
    onClose: () -> Unit,
) {
    var hintOpen by remember { mutableStateOf(false) }
    var loaded by remember(selectedFrame) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val frameIndex = frames.indexOf(selectedFrame)
    val preview by produceState<CalibrationFrames.Preview?>(null, selectedFrame) {
        loaded = false
        value = selectedFrame?.let {
            withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(it, PREVIEW_MAX_WIDTH) }
        }
        loaded = true
    }
    // 选中帧随动：滑到缩略图条对应位置
    LaunchedEffect(selectedFrame) {
        if (frameIndex >= 0 &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == frameIndex }
        ) {
            listState.animateScrollToItem(frameIndex)
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101010)) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 大图区：占满全屏（T1-5m ②）
                when {
                    selectedFrame == null -> Text(
                        text = stringResource(R.string.calibration_frames_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = ON_DARK_SECONDARY,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    selectMode -> {
                        val info = preview
                        when {
                            info == null -> Text(
                                text = stringResource(
                                    if (loaded) R.string.calibration_preview_failed
                                    else R.string.calibration_preview_loading
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (loaded) ON_DARK_ERROR else ON_DARK_SECONDARY,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            else -> InteractiveFrameCanvas(
                                info = info,
                                selection = selection,
                                role = selectedRole,
                                onSelectionChange = onSelectionChange,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    else -> {
                        // 浏览：滑页切帧，过半即同步缩略图（T1-5h/j 口径回填，T1-5m ③）
                        val frameList by rememberUpdatedState(frames)
                        val pagerState = rememberPagerState(
                            initialPage = frames.indexOf(selectedFrame).coerceAtLeast(0),
                        ) { frameList.size }
                        LaunchedEffect(pagerState.currentPage) {
                            frameList.getOrNull(pagerState.currentPage)?.let { page ->
                                if (page != selectedFrame) onSelectFrame(page)
                            }
                        }
                        LaunchedEffect(selectedFrame) {
                            if (!pagerState.isScrollInProgress) {
                                val idx = frameList.indexOf(selectedFrame)
                                if (idx >= 0 && idx != pagerState.currentPage) {
                                    pagerState.scrollToPage(idx)
                                }
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            FramePage(file = frameList.getOrNull(page))
                        }
                    }
                }

                // 顶部条 + 消息（悬浮）
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.calibration_workbench_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (frameIndex >= 0) {
                                Text(
                                    text = stringResource(
                                        R.string.calibration_workbench_frame_index,
                                        frameIndex + 1,
                                        frames.size,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                            TextButton(onClick = { hintOpen = true }) {
                                Text(
                                    text = stringResource(R.string.calibration_annotate_hint_icon),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                )
                            }
                            Button(onClick = onClose) {
                                Text(text = stringResource(R.string.calibration_workbench_done))
                            }
                        }
                    }
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                // 底部悬浮：缩略图条（浏览）/ 角色 + 归属状态条（框选）+ 工具栏（T1-5m ②④；T2-3d 加角色）
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f)),
                ) {
                    if (selectMode) {
                        // 两个维度分两行、各自带标签（T2-3f）：原先「角色 chip」与「状态 chip」混排成一行，
                        // 看起来像同一组同级选项，容易被误读（2026-09-13 用户反馈）。
                        // 顺序：归属状态在上（高频、每条记录都要选），角色在下（低频、紧邻 ✓ 按钮）
                        LabeledRow(label = stringResource(R.string.calibration_signal_state_label)) {
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                items(UiState.entries.filter { it.isCandidate }) { state ->
                                    val selected = selectedState == state
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onStateSelect(state) },
                                        label = {
                                            Text(
                                                text = state.label,
                                                // 未选中默认色是 onSurfaceVariant（亮色主题下深灰）→ 黑底上看不清，必须显式给亮色
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    Color.White
                                                },
                                            )
                                        },
                                        // 未选中的描边默认也是深色，黑底上看不出 chip 轮廓 → 统一浅白描边
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                    )
                                }
                            }
                        }
                        LabeledRow(label = stringResource(R.string.calibration_role_label)) {
                            // 角色用分段控件（不是 chip）：与状态在形态上就区分开；
                            // 选中的是「这次框选写成什么」，与右侧 ✓ 按钮上的角色名一致
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 12.dp)) {
                                val roles = SignalRole.entries
                                roles.forEachIndexed { index, role ->
                                    SegmentedButton(
                                        selected = selectedRole == role,
                                        onClick = { onRoleSelect(role) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = roles.size,
                                        ),
                                        colors = SegmentedButtonDefaults.colors(
                                            activeContainerColor = MaterialTheme.colorScheme.primary,
                                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                            activeBorderColor = Color.Transparent,
                                            inactiveContainerColor = Color.Transparent,
                                            inactiveContentColor = Color.White,
                                            inactiveBorderColor = Color.White.copy(alpha = 0.5f),
                                        ),
                                        label = { Text(text = role.label) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    } else {
                        LazyRow(
                            state = listState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            items(frames, key = { it.name }) { frame ->
                                FrameThumb(
                                    file = frame,
                                    selected = frame == selectedFrame,
                                    onClick = { onSelectFrame(frame) },
                                )
                            }
                        }
                    }
                    if (selectMode) {
                        // 选区信息单独一行（T2-2 真机反馈）：原先夹在 ✕ 与 ✓ 之间只剩窄条，坐标一长就折行
                        val info = preview
                        val px = if (info != null) {
                            selection?.toPixels(info.frameWidth, info.frameHeight)
                        } else {
                            null
                        }
                        Text(
                            text = if (px != null) {
                                stringResource(
                                    R.string.calibration_selection_info,
                                    px[0], px[1], px[2], px[3],
                                )
                            } else {
                                stringResource(R.string.calibration_selection_none)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ON_DARK_SECONDARY,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onExitSelect) {
                                Text(
                                    text = stringResource(R.string.calibration_workbench_cancel),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                )
                            }
                            Button(
                                onClick = onWriteSignal,
                                enabled = selection != null,
                                // 禁用态默认是 onSurface 12%（亮色主题=深色）→ 黑底上几乎看不见
                                // （T2-2 真机反馈），显式给白系
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = Color.White.copy(alpha = 0.12f),
                                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                                ),
                            ) {
                                // 按钮上直接写角色（✓ 写入标志 / ✓ 写入锚点）：最后一刻也能看清会写成什么
                                Text(
                                    text = stringResource(
                                        R.string.calibration_workbench_confirm,
                                        selectedRole.label,
                                    ),
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            ToolIconButton(
                                icon = R.drawable.ic_frame_select,
                                label = stringResource(R.string.calibration_workbench_select),
                                enabled = selectedFrame != null,
                                onClick = onEnterSelect,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (hintOpen) {
        AlertDialog(
            onDismissRequest = { hintOpen = false },
            confirmButton = {
                TextButton(onClick = { hintOpen = false }) {
                    Text(text = stringResource(R.string.calibration_hint_close))
                }
            },
            title = { Text(text = stringResource(R.string.calibration_workbench_title)) },
            text = { Text(text = stringResource(R.string.calibration_annotate_hint)) },
        )
    }
}

/**
 * 底部工具栏按钮（T1-5m）：**图标在上、文字在下**（参照系统相册大图底部工具栏样式），
 * 深色衬底上用白色图标与白色文字；禁用态整体降透明度（与 Material 禁用态一致）。
 */
@Composable
private fun ToolIconButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f)
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
        }
    }
}

/** 浏览模式的整页大图（每页独立解码，黑底等比居中）。 */
@Composable
private fun FramePage(file: File?) {
    var loaded by remember(file) { mutableStateOf(false) }
    val preview by produceState<CalibrationFrames.Preview?>(null, file) {
        loaded = false
        value = file?.let {
            withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(it, PREVIEW_MAX_WIDTH) }
        }
        loaded = true
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val info = preview
        when {
            info == null -> Text(
                text = stringResource(
                    if (loaded) R.string.calibration_preview_failed
                    else R.string.calibration_preview_loading
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (loaded) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Image(
                bitmap = info.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * 可缩放 / 平移的框选画布（T1-5d 起；T1-5l 移除全部锚点与拉伸）：空白拖动新建；框内拖动整体移动；
 * 左上角外置关闭钮清除选框后可重画。双指捏合缩放 / 平移画面（小标志先放大再框选）。
 */
@Composable
private fun InteractiveFrameCanvas(
    info: CalibrationFrames.Preview,
    selection: RatioRect?,
    role: SignalRole,
    onSelectionChange: (RatioRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 显示层变换（重置于画布重建）：只影响显示与手势坐标换算，不改选框比例语义
    var transform by remember(info) { mutableStateOf(ViewTransform()) }
    val latestSelection = rememberUpdatedState(selection)
    val latestTransform = rememberUpdatedState(transform)
    val touchPx = with(LocalDensity.current) { CLOSE_BUTTON_TOUCH.toPx() }
    val closeRadiusPx = with(LocalDensity.current) { 8.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val closeMarginPx = with(LocalDensity.current) { CLOSE_BUTTON_MARGIN.toPx() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(info.frameWidth.toFloat() / info.frameHeight)
                .background(Color(0xFF101010))
                .clipToBounds()
                .pointerInput(info) {
                    awaitEachGesture {
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (width <= 0f || height <= 0f) return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startTransform = latestTransform.value
                        val startSelection = latestSelection.value
                        val startRatio = FrameEditMath.toRatio(
                            down.position.x, down.position.y, startTransform, width, height,
                        )
                        // 关闭钮热区（屏幕 20dp → 帧比例，随缩放补偿）；按钮中心在选框左上角外侧
                        val rx = touchPx / startTransform.scale / width
                        val ry = touchPx / startTransform.scale / height
                        val mx = closeMarginPx / startTransform.scale / width
                        val my = closeMarginPx / startTransform.scale / height
                        var closeCandidate = FrameEditMath.hitCloseButton(
                            startSelection, startRatio[0], startRatio[1], mx, my, rx, ry,
                        )
                        // 拉伸带与关闭钮同源（同一 margin）：白线两侧各一指宽都能抓到，见 FrameEditMath.hitTest
                        var hit: FrameHit? = if (closeCandidate) {
                            null
                        } else {
                            FrameEditMath.hitTest(startSelection, startRatio[0], startRatio[1], mx, my)
                        }
                        // 拉伸时选框最小边长 = 模板最小边长（拉出更小的框也提取不出模板，写了必失败）
                        val minRatioX = MIN_TEMPLATE_PX / info.frameWidth.toFloat()
                        val minRatioY = MIN_TEMPLATE_PX / info.frameHeight.toFloat()
                        val touchSlop = viewConfiguration.touchSlop

                        var transforming = false
                        var createStarted = false
                        var lastCentroid = Offset.Zero
                        var lastDistance = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // 双指：缩放 / 平移（以双指焦点为中心；此后不再回到框操作）
                                closeCandidate = false
                                val centroid =
                                    pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                                        pressed.size.toFloat()
                                val distance =
                                    (pressed[0].position - pressed[1].position).getDistance()
                                if (!transforming) {
                                    transforming = true
                                    lastCentroid = centroid
                                    lastDistance = distance.coerceAtLeast(0.001f)
                                } else {
                                    val zoom =
                                        if (lastDistance > 0f) distance / lastDistance else 1f
                                    transform = FrameEditMath.zoomBy(
                                        transform,
                                        centroid.x,
                                        centroid.y,
                                        zoom,
                                        centroid.x - lastCentroid.x,
                                        centroid.y - lastCentroid.y,
                                        width,
                                        height,
                                    )
                                    lastCentroid = centroid
                                    lastDistance = distance.coerceAtLeast(0.001f)
                                }
                                pressed.forEach { it.consume() }
                                continue
                            }
                            if (transforming) {
                                pressed.forEach { it.consume() }
                                continue
                            }
                            val change = pressed[0]
                            if (closeCandidate) {
                                // 关闭钮：拖动超过滑动阈值即取消；否则抬手时清除选框
                                change.consume()
                                if ((change.position - down.position).getDistance() > touchSlop) {
                                    closeCandidate = false
                                    // 拖离关闭钮后不再"什么都不做"：按起点重判命中——左上角那一格
                                    // 落在拉伸带上（T2-3f），从关闭钮拖出去就等于拖外框角
                                    hit = FrameEditMath.hitTest(
                                        startSelection, startRatio[0], startRatio[1], mx, my,
                                    )
                                }
                                continue
                            }
                            val ratio = FrameEditMath.toRatio(
                                change.position.x, change.position.y, startTransform, width, height,
                            )
                            when (hit) {
                                // 拖外框白线 = 拉伸（T2-3f；不画手柄，白线本身是抓手）
                                is FrameHit.Resize -> {
                                    change.consume()
                                    startSelection?.let {
                                        onSelectionChange(
                                            FrameEditMath.resize(
                                                it,
                                                hit as FrameHit.Resize,
                                                ratio[0] - startRatio[0],
                                                ratio[1] - startRatio[1],
                                                minRatioX,
                                                minRatioY,
                                            ),
                                        )
                                    }
                                }
                                FrameHit.Inside -> {
                                    change.consume()
                                    startSelection?.let {
                                        onSelectionChange(
                                            FrameEditMath.move(
                                                it,
                                                ratio[0] - startRatio[0],
                                                ratio[1] - startRatio[1],
                                            ),
                                        )
                                    }
                                }
                                FrameHit.Outside -> {
                                    // 空白拖动：超过 touch slop 才接管
                                    if (!createStarted) {
                                        createStarted =
                                            (change.position - down.position).getDistance() >
                                                touchSlop
                                    }
                                    if (createStarted) {
                                        change.consume()
                                        val left = minOf(startRatio[0], ratio[0])
                                        val top = minOf(startRatio[1], ratio[1])
                                        val right = maxOf(startRatio[0], ratio[0])
                                        val bottom = maxOf(startRatio[1], ratio[1])
                                        // 抑制误触（点击级抖动不产生选区）
                                        if (right - left > 0.002f || bottom - top > 0.002f) {
                                            onSelectionChange(RatioRect(left, top, right, bottom))
                                        }
                                    }
                                }
                                null -> change.consume()
                            }
                        }
                        if (closeCandidate) onSelectionChange(null)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX
                        translationY = transform.offsetY
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                Image(
                    bitmap = info.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                val current = selection
                if (current != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawSelectionOverlay(
                            rect = current,
                            role = role,
                            scale = transform.scale,
                            strokePx = strokePx,
                            closeRadiusPx = closeRadiusPx,
                            closeMarginPx = closeMarginPx,
                        )
                    }
                }
            }
        }
        Text(
            text = if (transform.scale > 1.01f) {
                stringResource(
                    R.string.calibration_zoom_label,
                    "%.1f".format(transform.scale),
                )
            } else {
                " "
            },
            style = MaterialTheme.typography.bodySmall,
            color = ON_DARK_SECONDARY,
        )
    }
}

/** 带前缀标签的一行（T2-3f）：标签固定不滚动，内容占满剩余宽度（用于「归属状态 / 写入为」两行）。 */
@Composable
private fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ON_DARK_SECONDARY,
            modifier = Modifier.padding(start = 16.dp),
        )
        content()
    }
}

@Composable
private fun FrameThumb(file: File, selected: Boolean, onClick: () -> Unit) {
    val preview by produceState<CalibrationFrames.Preview?>(null, file) {
        value = withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(file, 144) }
    }
    val info = preview
    // 缩略图固定小方块：列表条矮化与画布同屏；裁切显示画面中部
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                // 缩略图条压在近黑浮层上：选中用纯白（主题 primary 是深紫、黑底上显不出选中），未选中用弱白
                color = if (selected) Color.White else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(6.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (info != null) {
            Image(
                bitmap = info.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 产物读取结果（T1-5l）：[broken] = 文件存在但解析失败（不静默降级为「无产物」）。 */
private class ArtifactState(val data: CalibrationData?, val broken: Boolean) {

    /** 参数文本初值（按序号取：0 命中线 / 1 差距线 / 2 峰值间距）。 */
    fun paramsText(index: Int): String {
        val params = data?.params ?: return when (index) {
            0 -> CalibrationSignals.DEFAULT_THRESHOLD_TEXT
            1 -> CalibrationSignals.DEFAULT_MARGIN_TEXT
            else -> CalibrationSignals.DEFAULT_PEAK_DISTANCE_TEXT
        }
        return when (index) {
            0 -> params.matchThreshold.toString()
            1 -> params.ambiguityMargin.toString()
            else -> params.peakMinDistance.toString()
        }
    }
}

private fun loadArtifact(context: Context): ArtifactState = try {
    ArtifactState(CalibrationStore.load(context), false)
} catch (_: IllegalArgumentException) {
    ArtifactState(null, true)
}

/** 写入结果：[saved] = 是否已落盘（成功才刷新产物列表与清空选框）。 */
private class WriteResult(val message: String, val saved: Boolean)

/** 提取准备结果（IO 线程产出 → 主线程写入产物）。 */
private class PreparedSignal(
    val template: Template,
    val window: SearchWindow,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * 框选 → 写入产物（T1-5l ①；T2-2 方案 A 起为**追加**）：从选区提取模板与搜索窗口，按归属状态
 * 默认名写入新记录——默认名已被占用时自动追加序号（`popup_close2`…，见 [CalibrationSignals.nextName]），
 * 因此同一状态可积累多条记录（多种样式，各带自己的窗口与模板，任一命中即该状态命中）。
 *
 * 几何校验（T1-5l ⑥）：产物已记录的标定帧几何与本次标定帧不一致时拒绝写入——窗口按整幅比例
 * 记录、模板按像素记录，混几何会让同一产物内的信号互相矛盾。解码失败 / 选区过小 / 几何不一致
 * 均以文本返回（本页以文字呈现，不中断流程）。
 */
private suspend fun writeSignalFromSelection(
    context: Context,
    frame: File,
    ratio: RatioRect,
    state: UiState,
    role: SignalRole,
    params: MatchParams?,
): WriteResult {
    return try {
        val prepared = withContext(Dispatchers.IO) {
            val rgba = CalibrationFrames.readRgba(frame)
                ?: throw IllegalArgumentException(
                    context.getString(R.string.calibration_error_frame_decode)
                )
            val px = ratio.toPixels(rgba.width, rgba.height)
            val x0 = px[0].coerceIn(0, rgba.width - 1)
            val x1 = px[2].coerceIn(x0 + 1, rgba.width)
            val y0 = px[1].coerceIn(0, rgba.height - 1)
            val y1 = px[3].coerceIn(y0 + 1, rgba.height)
            if (x1 - x0 < MIN_TEMPLATE_PX || y1 - y0 < MIN_TEMPLATE_PX) {
                throw IllegalArgumentException(
                    context.getString(
                        R.string.calibration_error_selection_too_small,
                        MIN_TEMPLATE_PX,
                        MIN_TEMPLATE_PX,
                    )
                )
            }
            PreparedSignal(
                template = TemplateExtractor.extract(
                    rgba.pixels, rgba.width, rgba.height, rgba.width * 4, x0, y0, x1, y1,
                ),
                window = SelectionWindow.forSelection(rgba.width, rgba.height, x0, y0, x1, y1),
                frameWidth = rgba.width,
                frameHeight = rgba.height,
            )
        }

        val current = withContext(Dispatchers.IO) { CalibrationStore.load(context) }
        // 几何校验（T1-5l ⑥）：与产物记录的画布几何不一致时拒绝写入并说明原因
        if (CalibrationSignals.geometryError(current, prepared.frameWidth, prepared.frameHeight) != null) {
            val existing = requireNotNull(current)
            return WriteResult(
                message = context.getString(
                    R.string.calibration_geometry_mismatch,
                    prepared.frameWidth,
                    prepared.frameHeight,
                    existing.frameWidth,
                    existing.frameHeight,
                ),
                saved = false,
            )
        }

        // T2-2 方案 A：同状态可有多条记录（多种样式），名字在默认名基础上自动追加序号，不覆盖既有记录
        // T2-3d：默认名按角色区分（锚点 = 默认名 + _anchor）→ 标志与锚点各写各的记录，互不覆盖
        val name = CalibrationSignals.nextName(current, CalibrationSignals.defaultNameFor(state, role))
        val written = withContext(Dispatchers.IO) {
            val data = CalibrationSignals.upsert(
                current = current,
                name = name,
                state = state,
                window = prepared.window,
                template = prepared.template,
                params = params ?: current?.params ?: CalibrationSignals.defaultParams(),
                frameWidth = prepared.frameWidth,
                frameHeight = prepared.frameHeight,
                role = role,
            )
            CalibrationStore.save(context, data)
            data
        }
        // 同角色计数：标志数 / 锚点数各自累计（不要混着数，否则「该状态现有 N 条」会误导标定）
        val existingCount = written.stateRules.firstOrNull { it.state == state }
            ?.let { if (role == SignalRole.ANCHOR) it.anchorNames.size else it.signalNames.size }
            ?: 1
        WriteResult(
            message = context.getString(
                R.string.calibration_signal_added,
                role.label,
                name,
                state.label,
                prepared.template.width,
                prepared.template.height,
                existingCount,
            ),
            saved = true,
        )
    } catch (t: IllegalArgumentException) {
        WriteResult(context.getString(R.string.calibration_write_failed, t.message.orEmpty()), false)
    } catch (t: RuntimeException) {
        WriteResult(
            context.getString(
                R.string.calibration_write_failed,
                "${t.javaClass.simpleName} ${t.message}",
            ),
            false,
        )
    }
}

/**
 * 选框叠加（T1-5l 起）：**只有内层真实选区**（填充 + 框线，边缘与角不被任何手柄遮挡）
 * 与**左上角外置关闭钮**。线宽与关闭钮尺寸按视图缩放反向补偿，屏幕视觉大小恒定。
 *
 * 框线颜色跟角色走（T2-3f）：标志 = 红、锚点 = 青 —— 画布上直接反映"这次框选会写成什么"，
 * 配合底栏的分段控件与「✓ 写入X」按钮，避免把两种角色混着标。
 *
 * [closeMarginPx] 为关闭钮中心相对选框左上角的外扩屏幕像素（与命中测试同一口径，
 * 见 [FrameEditMath.closeButtonCenter]）；它同时是**外框拉伸带**的宽度来源（T2-3f）。
 */
private fun DrawScope.drawSelectionOverlay(
    rect: RatioRect?,
    role: SignalRole,
    scale: Float,
    strokePx: Float,
    closeRadiusPx: Float,
    closeMarginPx: Float,
) {
    if (rect == null || size.width <= 0f || size.height <= 0f) return
    val accent = if (role == SignalRole.ANCHOR) ANCHOR_ACCENT else MARKER_ACCENT
    val topLeft = Offset(rect.left * size.width, rect.top * size.height)
    val boxSize = Size(
        (rect.right - rect.left) * size.width,
        (rect.bottom - rect.top) * size.height,
    )
    drawRect(color = accent.copy(alpha = 0.2f), topLeft = topLeft, size = boxSize)
    drawRect(
        color = accent,
        topLeft = topLeft,
        size = boxSize,
        style = Stroke(width = strokePx / scale),
    )
    val marginX = closeMarginPx / scale / size.width
    val marginY = closeMarginPx / scale / size.height
    // 外层白色细线（T1-5m 恢复，仅视觉：不参与命中，锚点仍无；线宽减半与内层红框区分）
    val outer = FrameEditMath.outerFrame(rect, marginX, marginY)
    drawRect(
        color = Color(0xCCFFFFFF),
        topLeft = Offset(outer.left * size.width, outer.top * size.height),
        size = Size(
            (outer.right - outer.left) * size.width,
            (outer.bottom - outer.top) * size.height,
        ),
        style = Stroke(width = strokePx / scale / 2f),
    )
    val close = FrameEditMath.closeButtonCenter(rect, marginX, marginY)
    drawCloseButton(
        Offset(close.first * size.width, close.second * size.height),
        closeRadiusPx / scale,
        strokePx / scale,
    )
}

/** 绘制选框左上角关闭钮（白底红圈 + 红叉；尺寸与线宽已按缩放补偿）。 */
private fun DrawScope.drawCloseButton(center: Offset, radius: Float, strokeWidth: Float) {
    drawCircle(color = Color.White, radius = radius, center = center)
    drawCircle(
        color = Color(0xFFFF5252),
        radius = radius,
        center = center,
        style = Stroke(width = strokeWidth),
    )
    val arm = radius * 0.45f
    drawLine(
        color = Color(0xFFFF5252),
        start = Offset(center.x - arm, center.y - arm),
        end = Offset(center.x + arm, center.y + arm),
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = Color(0xFFFF5252),
        start = Offset(center.x + arm, center.y - arm),
        end = Offset(center.x - arm, center.y + arm),
        strokeWidth = strokeWidth,
    )
}
