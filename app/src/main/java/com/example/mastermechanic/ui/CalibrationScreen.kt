package com.example.mastermechanic.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mastermechanic.R
import com.example.mastermechanic.calibration.CalibrationData
import com.example.mastermechanic.calibration.CalibrationDraft
import com.example.mastermechanic.calibration.CalibrationFramePool
import com.example.mastermechanic.calibration.CalibrationFrames
import com.example.mastermechanic.calibration.CalibrationStore
import com.example.mastermechanic.calibration.FrameEditMath
import com.example.mastermechanic.calibration.FrameHit
import com.example.mastermechanic.calibration.RatioRect
import com.example.mastermechanic.calibration.SelectionWindow
import com.example.mastermechanic.calibration.TemplateExtractor
import com.example.mastermechanic.calibration.ViewTransform
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.recognition.SearchWindow
import com.example.mastermechanic.recognition.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 模板最小边长（原图像素）：过小的模板缺少区分度，提取时拒绝。 */
private const val MIN_TEMPLATE_PX = 8

/**
 * 标定页入口（T1-5b）：样本录制 → 挑帧 → 框选 → 归属状态（自动定名） → 设参 → 保存产物。
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
    var draftVersion by remember { mutableIntStateOf(0) }
    var artifactTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    var thresholdText by remember { mutableStateOf(CalibrationDraft.matchThresholdText) }
    var marginText by remember { mutableStateOf(CalibrationDraft.ambiguityMarginText) }
    var minDistanceText by remember { mutableStateOf(CalibrationDraft.peakMinDistanceText) }

    /** 产物摘要：null = 无产物；-1 = 解析失败；其余 = 信号数。 */
    val artifactSignals: Int? = remember(resumeTick, artifactTick) {
        try {
            CalibrationStore.load(context)?.signals?.size
        } catch (_: IllegalArgumentException) {
            -1
        }
    }

    val draftSignals = remember(draftVersion) {
        CalibrationDraft.signals.map { Triple(it.entry.name, it.state, it.entry.templates.size) }
    }
    val paramsValid = CalibrationDraft.parseParams() != null

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

    CalibrationScreen(
        captureActive = captureActive,
        frames = frames,
        recording = recording,
        selectedFrame = selectedFrame,
        selection = selection,
        selectedState = selectedState,
        draftSignals = draftSignals,
        artifactSignals = artifactSignals,
        thresholdText = thresholdText,
        marginText = marginText,
        minDistanceText = minDistanceText,
        paramsValid = paramsValid,
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
        onSelectFrame = { selectedFrame = it },
        onSelectionChange = { selection = it },
        // T1-5g：信号名完全由归属状态决定（使用者无需关心命名）
        onStateSelect = { selectedState = it },
        onAddTemplate = {
            val frame = selectedFrame
            val state = selectedState
            val sel = selection
            val name = state?.defaultSignalName.orEmpty()
            if (frame != null && state != null && sel != null && name.isNotEmpty()) {
                scope.launch {
                    message = addTemplateFromSelection(context, frame, sel, name, state)
                    draftVersion++
                }
            }
        },
        onRemoveSignal = { name ->
            CalibrationDraft.removeSignal(name)
            draftVersion++
        },
        onClearDraft = {
            CalibrationDraft.clear()
            thresholdText = CalibrationDraft.matchThresholdText
            marginText = CalibrationDraft.ambiguityMarginText
            minDistanceText = CalibrationDraft.peakMinDistanceText
            message = null
            draftVersion++
        },
        onThresholdChange = {
            thresholdText = it
            CalibrationDraft.matchThresholdText = it
        },
        onMarginChange = {
            marginText = it
            CalibrationDraft.ambiguityMarginText = it
        },
        onMinDistanceChange = {
            minDistanceText = it
            CalibrationDraft.peakMinDistanceText = it
        },
        onSave = {
            val result = runCatching {
                val data = CalibrationDraft.toData(
                    CalibrationDraft.lastFrameWidth,
                    CalibrationDraft.lastFrameHeight,
                )
                CalibrationStore.save(context, data)
                data.signals.size
            }
            artifactTick++
            message = result.fold(
                { count -> context.getString(R.string.calibration_saved, count) },
                { t ->
                    context.getString(
                        R.string.calibration_save_failed,
                        t.message ?: t.javaClass.simpleName,
                    )
                },
            )
        },
        onDeleteArtifact = {
            CalibrationStore.delete(context)
            artifactTick++
            message = context.getString(R.string.calibration_deleted)
        },
    )
}

@Composable
private fun CalibrationScreen(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    selectedFrame: File?,
    selection: RatioRect?,
    selectedState: UiState?,
    draftSignals: List<Triple<String, UiState, Int>>,
    artifactSignals: Int?,
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
    message: String?,
    onBack: () -> Unit,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
    onSelectFrame: (File) -> Unit,
    onSelectionChange: (RatioRect?) -> Unit,
    onStateSelect: (UiState) -> Unit,
    onAddTemplate: () -> Unit,
    onRemoveSignal: (String) -> Unit,
    onClearDraft: () -> Unit,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
    onSave: () -> Unit,
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
                selectedFrame = selectedFrame,
                onToggleRecord = onToggleRecord,
                onClearFrames = onClearFrames,
                onSelectFrame = onSelectFrame,
            )

            if (selectedFrame != null) {
                AnnotateCard(
                    frame = selectedFrame,
                    frames = frames,
                    selection = selection,
                    selectedState = selectedState,
                    onSelectionChange = onSelectionChange,
                    onStateSelect = onStateSelect,
                    onSelectFrame = onSelectFrame,
                    onAddTemplate = onAddTemplate,
                )
            }

            DraftCard(
                draftSignals = draftSignals,
                onRemoveSignal = onRemoveSignal,
                onClearDraft = onClearDraft,
            )

            ParamsCard(
                thresholdText = thresholdText,
                marginText = marginText,
                minDistanceText = minDistanceText,
                paramsValid = paramsValid,
                onThresholdChange = onThresholdChange,
                onMarginChange = onMarginChange,
                onMinDistanceChange = onMinDistanceChange,
            )

            ArtifactCard(
                artifactSignals = artifactSignals,
                saveEnabled = draftSignals.isNotEmpty() && paramsValid,
                onSave = onSave,
                onDeleteArtifact = onDeleteArtifact,
            )
        }
    }
}

@Composable
private fun FramesCard(
    captureActive: Boolean,
    frames: List<File>,
    recording: Boolean,
    selectedFrame: File?,
    onToggleRecord: () -> Unit,
    onClearFrames: () -> Unit,
    onSelectFrame: (File) -> Unit,
) {
    val listState = rememberLazyListState()
    // 选中帧随动（T1-5h）：滑动预览切帧时滚动缩略图条，保持对应缩略图可见
    LaunchedEffect(selectedFrame) {
        val idx = frames.indexOf(selectedFrame)
        if (idx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            listState.animateScrollToItem(idx)
        }
    }
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
            } else {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        }
    }
}

@Composable
private fun AnnotateCard(
    frame: File,
    frames: List<File>,
    selection: RatioRect?,
    selectedState: UiState?,
    onSelectionChange: (RatioRect?) -> Unit,
    onStateSelect: (UiState) -> Unit,
    onSelectFrame: (File) -> Unit,
    onAddTemplate: () -> Unit,
) {
    var canvasOpen by remember(frame) { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    var loaded by remember(frame) { mutableStateOf(false) }
    val preview by produceState<CalibrationFrames.Preview?>(null, frame) {
        value = withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(frame, 1080) }
        loaded = true
    }
    val info = preview
    // 帧比例占位（T1-5i）：记住最近一次成功解码的宽高比，切换帧时预览画布高度不塌缩
    var lastRatio by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(info) {
        info?.let { lastRatio = it.frameWidth.toFloat() / it.frameHeight }
    }
    // 预览滑页（T1-5h）：左右滑动切换样本帧，与选中帧双向同步（缩略图随动）
    val frameList by rememberUpdatedState(frames)
    val pagerState = rememberPagerState(
        initialPage = frames.indexOf(frame).coerceAtLeast(0),
    ) { frameList.size }
    // 缩略图随动（T1-5j）：以 currentPage（过半即翻页）联动，避免等定格才同步造成缩略图明显滞后
    LaunchedEffect(pagerState.currentPage) {
        frameList.getOrNull(pagerState.currentPage)?.let { f ->
            if (f != frame) onSelectFrame(f)
        }
    }
    LaunchedEffect(frame) {
        if (!pagerState.isScrollInProgress) {
            val idx = frameList.indexOf(frame)
            if (idx >= 0 && idx != pagerState.currentPage) pagerState.scrollToPage(idx)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.calibration_annotate_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // 操作说明移入弹窗（T1-5f）：默认不占版面，点右侧 ⓘ 查看
                TextButton(onClick = { hintOpen = true }) {
                    Text(
                        text = stringResource(R.string.calibration_annotate_hint_icon),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 预览画布高度占位（T1-5i）：按最近解码帧比例预留高度，切换帧时不塌缩闪烁
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val containerWidth = maxWidth
                val placeholderRatio = lastRatio
                val previewHeight =
                    if (placeholderRatio != null) {
                        minOf(260.dp, containerWidth / placeholderRatio)
                    } else {
                        260.dp
                    }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { canvasOpen = true })
                        },
                ) {
                    if (loaded && info == null) {
                        // 当前帧不可读：保持占位高度，仅提示失败（T1-5i）
                        Text(
                            text = stringResource(R.string.calibration_preview_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        // 可滑动预览（T1-5h）：左右滑动切换样本帧；双击或点底部图标进入全屏框选
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val pageFile = frameList.getOrNull(page)
                            if (pageFile != null) {
                                SwipePreviewPage(
                                    file = pageFile,
                                    selection = if (pageFile == frame) selection else null,
                                    containerWidth = containerWidth,
                                    containerHeight = previewHeight,
                                )
                            }
                        }
                    }
                    // 全屏图标（T1-5h）：深色衬底 + 白色图标，置于画面正下方且与任意背景保持对比
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { canvasOpen = true }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fullscreen),
                            contentDescription = stringResource(R.string.calibration_annotate_open),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            val px = if (info != null) selection?.toPixels(info.frameWidth, info.frameHeight) else null
            Text(
                text = if (px != null) {
                    stringResource(R.string.calibration_selection_info, px[0], px[1], px[2], px[3])
                } else {
                    stringResource(R.string.calibration_selection_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.calibration_signal_state_label),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiState.entries.filter { it.isCandidate }.forEach { state ->
                    FilterChip(
                        selected = selectedState == state,
                        onClick = { onStateSelect(state) },
                        label = { Text(state.label) },
                    )
                }
            }
            // T1-5g：名称随归属状态自动确定（候选状态默认名均经单测校验合法）
            Button(
                onClick = onAddTemplate,
                enabled = selection != null && selectedState != null,
            ) {
                Text(text = stringResource(R.string.calibration_add_template))
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
            title = { Text(text = stringResource(R.string.calibration_annotate_title)) },
            text = { Text(text = stringResource(R.string.calibration_annotate_hint)) },
        )
    }
    if (canvasOpen && info != null) {
        FullScreenFrameCanvas(
            info = info,
            selection = selection,
            onSelectionChange = onSelectionChange,
            onClose = { canvasOpen = false },
        )
    }
}

/** 预览滑页（T1-5h）：每页独立解码对应样本帧，在容器上限内等比适配并居中。 */
@Composable
private fun SwipePreviewPage(
    file: File,
    selection: RatioRect?,
    containerWidth: Dp,
    containerHeight: Dp,
) {
    var loaded by remember(file) { mutableStateOf(false) }
    val preview by produceState<CalibrationFrames.Preview?>(null, file) {
        value = withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(file, 1080) }
        loaded = true
    }
    val info = preview
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            !loaded -> Text(
                text = stringResource(R.string.calibration_preview_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            info == null -> Text(
                text = stringResource(R.string.calibration_preview_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                val ratio = info.frameWidth.toFloat() / info.frameHeight
                FramePreview(
                    info = info,
                    selection = selection,
                    modifier = Modifier
                        .height(minOf(containerHeight, containerWidth / ratio))
                        .aspectRatio(ratio),
                )
            }
        }
    }
}

/** 静态帧预览（T1-5e）：仅展示画面与当前选框，不可缩放，不会遮挡页内其他区域。 */
@Composable
private fun FramePreview(
    info: CalibrationFrames.Preview,
    selection: RatioRect?,
    modifier: Modifier = Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    Box(
        modifier = modifier
            .background(Color(0xFF101010))
            .clipToBounds(),
    ) {
        Image(
            bitmap = info.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSelectionOverlay(
                rect = selection,
                scale = 1f,
                strokePx = strokePx,
                cornerHalf = 0f,
                edgeHalf = 0f,
                closeRadiusPx = 0f,
                showHandles = false,
            )
        }
    }
}

/** 全屏框选画布（T1-5e）：弹出式覆盖整屏，「完成」退出返回上一层；放大后的画面不再遮挡页内其他区域。 */
@Composable
private fun FullScreenFrameCanvas(
    info: CalibrationFrames.Preview,
    selection: RatioRect?,
    onSelectionChange: (RatioRect?) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.calibration_annotate_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = onClose) {
                        Text(text = stringResource(R.string.calibration_annotate_done))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    InteractiveFrameCanvas(
                        info = info,
                        selection = selection,
                        onSelectionChange = onSelectionChange,
                        modifier = Modifier.aspectRatio(
                            info.frameWidth.toFloat() / info.frameHeight,
                        ),
                    )
                }
                val px = selection?.toPixels(info.frameWidth, info.frameHeight)
                Text(
                    text = if (px != null) {
                        stringResource(R.string.calibration_selection_info, px[0], px[1], px[2], px[3])
                    } else {
                        stringResource(R.string.calibration_selection_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 可缩放 / 平移的画布（T1-5d 起，T1-5e 迁入全屏）：空白拖动新建；框内拖动移动；边 / 角拖动拉伸；左上角关闭钮清除选框。 */
@Composable
private fun InteractiveFrameCanvas(
    info: CalibrationFrames.Preview,
    selection: RatioRect?,
    onSelectionChange: (RatioRect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 显示层变换（重置于画布重建）：只影响显示与手势坐标换算，不改选框比例语义
    var transform by remember(info) { mutableStateOf(ViewTransform()) }
    val latestSelection = rememberUpdatedState(selection)
    val latestTransform = rememberUpdatedState(transform)
    val handleTouchPx = with(LocalDensity.current) { 20.dp.toPx() }
    val cornerHalf = with(LocalDensity.current) { 7.dp.toPx() }
    val edgeHalf = with(LocalDensity.current) { 5.dp.toPx() }
    val closeRadiusPx = with(LocalDensity.current) { 11.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
                        // 手柄 / 关闭钮热区（屏幕 20dp 半径 → 帧比例，随缩放补偿）
                        val rx = handleTouchPx / startTransform.scale / width
                        val ry = handleTouchPx / startTransform.scale / height
                        var closeCandidate = FrameEditMath.hitCloseButton(
                            startSelection, startRatio[0], startRatio[1], rx, ry,
                        )
                        val hit = if (closeCandidate) {
                            null
                        } else {
                            FrameEditMath.hitTest(
                                startSelection, startRatio[0], startRatio[1], rx, ry,
                            )
                        }
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
                                }
                                continue
                            }
                            val ratio = FrameEditMath.toRatio(
                                change.position.x, change.position.y, startTransform, width, height,
                            )
                            when (hit) {
                                is FrameHit.Handle -> {
                                    change.consume()
                                    startSelection?.let {
                                        onSelectionChange(
                                            FrameEditMath.resize(
                                                it,
                                                hit.handle,
                                                ratio[0] - startRatio[0],
                                                ratio[1] - startRatio[1],
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
                            scale = transform.scale,
                            strokePx = strokePx,
                            cornerHalf = cornerHalf,
                            edgeHalf = edgeHalf,
                            closeRadiusPx = closeRadiusPx,
                            showHandles = true,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FrameThumb(file: File, selected: Boolean, onClick: () -> Unit) {
    val preview by produceState<CalibrationFrames.Preview?>(null, file) {
        value = withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(file, 144) }
    }
    val info = preview
    // 缩略图固定小方块（T1-5f）：列表条矮化与预览大图同屏；裁切显示画面中部
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
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

@Composable
private fun DraftCard(
    draftSignals: List<Triple<String, UiState, Int>>,
    onRemoveSignal: (String) -> Unit,
    onClearDraft: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_draft_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (draftSignals.isEmpty()) {
                Text(
                    text = stringResource(R.string.calibration_draft_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                draftSignals.forEach { (name, state, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.calibration_draft_item,
                                name,
                                state.label,
                                count,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemoveSignal(name) }) {
                            Text(text = stringResource(R.string.calibration_draft_remove))
                        }
                    }
                }
            }
            TextButton(onClick = onClearDraft, enabled = draftSignals.isNotEmpty()) {
                Text(text = stringResource(R.string.calibration_draft_clear))
            }
        }
    }
}

@Composable
private fun ParamsCard(
    thresholdText: String,
    marginText: String,
    minDistanceText: String,
    paramsValid: Boolean,
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

@Composable
private fun ArtifactCard(
    artifactSignals: Int?,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onDeleteArtifact: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_artifact_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                artifactSignals == null -> Text(
                    text = stringResource(R.string.calibration_artifact_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                artifactSignals < 0 -> Text(
                    text = stringResource(R.string.calibration_artifact_broken),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = stringResource(R.string.calibration_artifact_present, artifactSignals),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = saveEnabled) {
                    Text(text = stringResource(R.string.calibration_save))
                }
                OutlinedButton(onClick = onDeleteArtifact, enabled = artifactSignals != null) {
                    Text(text = stringResource(R.string.calibration_delete))
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

/** 提取准备结果（IO 线程产出 → 主线程合并草稿）。 */
private class PreparedTemplate(
    val template: Template,
    val window: SearchWindow,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * 从选区提取模板并合并进草稿（同名信号追加模板、窗口取并集）；返回结果文本。
 * 状态冲突 / 解码失败 / 选区过小均以文本返回（本页以文字呈现，不中断流程）。
 */
private suspend fun addTemplateFromSelection(
    context: Context,
    frame: File,
    ratio: RatioRect,
    name: String,
    state: UiState,
): String {
    val existing = CalibrationDraft.findSignal(name)
    if (existing != null && existing.state != state) {
        return context.getString(
            R.string.calibration_error_signal_state_conflict,
            name,
            existing.state.label,
            state.label,
        )
    }
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
            PreparedTemplate(
                template = TemplateExtractor.extract(
                    rgba.pixels, rgba.width, rgba.height, rgba.width * 4, x0, y0, x1, y1,
                ),
                window = SelectionWindow.forSelection(rgba.width, rgba.height, x0, y0, x1, y1),
                frameWidth = rgba.width,
                frameHeight = rgba.height,
            )
        }

        val current = CalibrationDraft.findSignal(name)
        if (current != null) {
            val index = CalibrationDraft.signals.indexOfFirst { it.entry.name == name }
            CalibrationDraft.signals[index] = CalibrationDraft.DraftSignal(
                state,
                CalibrationData.SignalEntry(
                    name,
                    SelectionWindow.union(current.entry.window, prepared.window),
                    current.entry.templates + prepared.template,
                ),
            )
        } else {
            CalibrationDraft.signals.add(
                CalibrationDraft.DraftSignal(
                    state,
                    CalibrationData.SignalEntry(name, prepared.window, listOf(prepared.template)),
                ),
            )
        }
        CalibrationDraft.lastFrameWidth = prepared.frameWidth
        CalibrationDraft.lastFrameHeight = prepared.frameHeight
        val count = CalibrationDraft.findSignal(name)?.entry?.templates?.size ?: 1
        context.getString(R.string.calibration_added, name, count)
    } catch (t: IllegalArgumentException) {
        context.getString(R.string.calibration_extract_failed, t.message.orEmpty())
    } catch (t: RuntimeException) {
        context.getString(
            R.string.calibration_extract_failed,
            "${t.javaClass.simpleName} ${t.message}",
        )
    }
}

/**
 * 选区叠加（T1-5d 起，T1-5e 加左上角关闭钮）：框线 + 手柄 + 关闭钮。
 * 手柄 / 关闭钮尺寸与线宽按视图缩放反向补偿，屏幕视觉大小恒定；
 * [showHandles] = false 时仅画框线（静态预览用）。
 */
private fun DrawScope.drawSelectionOverlay(
    rect: RatioRect?,
    scale: Float,
    strokePx: Float,
    cornerHalf: Float,
    edgeHalf: Float,
    closeRadiusPx: Float,
    showHandles: Boolean,
) {
    if (rect == null) return
    val topLeft = Offset(rect.left * size.width, rect.top * size.height)
    val boxSize = Size(
        (rect.right - rect.left) * size.width,
        (rect.bottom - rect.top) * size.height,
    )
    drawRect(color = Color(0x33FF5252), topLeft = topLeft, size = boxSize)
    drawRect(
        color = Color(0xFFFF5252),
        topLeft = topLeft,
        size = boxSize,
        style = Stroke(width = strokePx / scale),
    )
    if (!showHandles) return
    val corner = cornerHalf / scale
    val edge = edgeHalf / scale
    val line = strokePx / scale
    val leftPx = rect.left * size.width
    val rightPx = rect.right * size.width
    val topPx = rect.top * size.height
    val bottomPx = rect.bottom * size.height
    val centerX = (leftPx + rightPx) / 2f
    val centerY = (topPx + bottomPx) / 2f
    // 左上角自 T1-5e 起为关闭钮，不再绘制拉伸锚点；其余三角 + 四边中点保留
    drawFrameHandle(Offset(rightPx, topPx), corner, line)
    drawFrameHandle(Offset(leftPx, bottomPx), corner, line)
    drawFrameHandle(Offset(rightPx, bottomPx), corner, line)
    drawFrameHandle(Offset(centerX, topPx), edge, line)
    drawFrameHandle(Offset(centerX, bottomPx), edge, line)
    drawFrameHandle(Offset(leftPx, centerY), edge, line)
    drawFrameHandle(Offset(rightPx, centerY), edge, line)
    drawCloseButton(Offset(leftPx, topPx), closeRadiusPx / scale, line)
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

/** 绘制单个选框手柄（白底红边小方块；尺寸与线宽已按缩放补偿，屏幕视觉恒定）。 */
private fun DrawScope.drawFrameHandle(center: Offset, half: Float, strokeWidth: Float) {
    val topLeft = Offset(center.x - half, center.y - half)
    val handleSize = Size(half * 2f, half * 2f)
    drawRect(color = Color.White, topLeft = topLeft, size = handleSize)
    drawRect(
        color = Color(0xFFFF5252),
        topLeft = topLeft,
        size = handleSize,
        style = Stroke(width = strokeWidth),
    )
}
