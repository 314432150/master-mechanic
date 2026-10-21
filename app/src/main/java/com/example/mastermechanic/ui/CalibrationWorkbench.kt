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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalibrationWorkbench(
    frames: List<File>,
    selectedFrame: File?,
    selection: RatioRect?,
    selectedState: UiState?,
    selectedRoles: Set<SignalRole>,
    selectedPurpose: String?,
    message: String?,
    selectMode: Boolean,
    /**
     * 页面层的 Snackbar 宿主：**必须传进来**（2026-09-23 用户报"确认删除后底部没有出现撤销"）。
     *
     * 工作台是全屏 `Dialog` = 独立窗口，标定页那个 `SnackbarHost` 在**下面那层窗口**里 ——
     * 弹出来了也看不见。删帧的撤销要出现在用户眼前，就只能在**工作台自己的窗口里**再挂一个 host。
     * 同一个 [SnackbarHostState] 挂两处没有问题：数据是同一个 `currentSnackbarData`，
     * 被遮住的那一份不可见但无害（也正因为这样，删帧的撤销在关掉工作台后照样能点）。
     */
    snackbarHostState: SnackbarHostState,
    onSelectFrame: (File) -> Unit,
    /**
     * 请求删除某一帧（缩略图上 / 下滑或长按触发）。
     *
     * 工作台**只发起**：确认弹窗与真删都在页面层（`CalibrationRoute`）——那里才有 SnackbarHost
     * 与"删除后可撤销"的窗口，工作台里再放一套就得分叉成两份撤销逻辑。
     */
    onDeleteFrame: (File) -> Unit,
    onSelectionChange: (RatioRect?) -> Unit,
    onStateSelect: (UiState) -> Unit,
    onRoleToggle: (SignalRole) -> Unit,
    onPurposeSelect: (String) -> Unit,
    onEnterSelect: () -> Unit,
    onExitSelect: () -> Unit,
    onWriteSignal: () -> Unit,
    onClose: () -> Unit,
) {
    var hintOpen by remember { mutableStateOf(false) }
    // 写入条件不足 → 弹窗列缺项（用户 2026-09-13：从顶部条文案改为弹窗提示）
    var missingWrite by remember { mutableStateOf<List<Int>?>(null) }
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
                // 底部悬浮条（框选模式下是"选区坐标 + 角色 + 归属状态 + ✕/✓"那一摞）要占屏幕下沿。
                // 它画在画布**之上**，所以画布得知道自己要给它让出多少 —— 否则画布底部那条
                // 「适应画面」入口会被压在条下面（看不见、也不敢点）。这里把它量出来传给画布。
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }
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
                                roles = selectedRoles,
                                onSelectionChange = onSelectionChange,
                                // 画布占满全屏（用户 2026-09-13 撤销上一条"只准画在两条之间"的限制）：
                                // 顶/底条只是半透明浮层，压住的部分仍能看见框线，限制可画区域反而少了一截可用画面。
                                modifier = Modifier.fillMaxSize(),
                                // 唯独画布自己那条「适应画面」入口不能压在底栏下面（它是**要点**的，不是看的）
                                bottomInsetPx = bottomBarHeightPx,
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
                            // 2026-09-23 这里曾有一枚「试读好友名」（用户要求：验证 OCR 能不能读出好友名）。
                            // **同日搬到悬浮窗**（用户口径）：试读要读的是**当前实机画面**，而工作台看的是帧池里的
                            // 静态帧 —— 在悬浮窗里点一次才是"我现在屏幕上的好友名读得出吗"。
                            // 实现搬到了 `patrol/FriendListOcrSignal` + `service/FriendListOcrTryout` + 悬浮窗根菜单。
                            // M5-U5：原来这里是一枚 ⓘ（打开 ≈640 字长文）⇒ 改成写清动作的「用法」；
                            // 长文拆六条搬进底部弹层（V2：超 60 字必须默认收起、入口要说明白是什么）。
                            TextButton(onClick = { hintOpen = true }) {
                                Text(
                                    text = stringResource(R.string.calibration_usage),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                )
                            }
                            Button(onClick = onClose) {
                                Text(text = stringResource(R.string.calibration_workbench_done))
                            }
                        }
                    }
                    // 框选模式的操作说明（T2-3h）：一句话放进顶部条，不必再点 ⓘ 翻长文案。
                    // `fillMaxWidth` 钉住测量宽度 + 允许换行（maxLines=2 兜底）——**不能 softWrap=false**：
                    // 那样文字既不换行也顶出屏幕（2026-09-13 真机反馈）。
                    if (selectMode) {
                        Text(
                            text = stringResource(R.string.calibration_select_hint_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = ON_DARK_SECONDARY,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    } else {
                        // M5-U5：**浏览态也常驻 1 行要点**（原来是框选态才有）—— 用户第一次进来看到的
                        // 就是这一行："怎么翻帧、怎么开始框、怎么缩放"，三件事不点「用法」也能上手（V2）。
                        Text(
                            text = stringResource(R.string.calibration_browse_hint_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = ON_DARK_SECONDARY,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
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
                        .background(Color.Black.copy(alpha = 0.65f))
                        // 高度回传给画布（见上：画布要给自己留出这条的高度）
                        .onSizeChanged { bottomBarHeightPx = it.height },
                ) {
                    if (selectMode) {
                        // 选区坐标（原夹在角色行与 ✓ 按钮之间，用户 2026-09-13 要求上移到「归属状态」上方并居中）：
                        // 画框时最常核对的就是这四个数，紧贴画布下沿更好读。
                        // 无选区时**不写文案但仍占位**（不换行空格）：整行消失会让下方状态/角色行整体上跳，
                        // 画出第一个框时又跳回来 —— 底栏高度恒定（T2-3g 那条 BUG 的教训）。
                        val selectionInfo = preview
                        val selectionPx = if (selectionInfo != null) {
                            selection?.toPixels(selectionInfo.frameWidth, selectionInfo.frameHeight)
                        } else {
                            null
                        }
                        Text(
                            text = if (selectionPx != null) {
                                stringResource(
                                    R.string.calibration_selection_info,
                                    selectionPx[0], selectionPx[1], selectionPx[2], selectionPx[3],
                                )
                            } else {
                                "\u00A0"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = ON_DARK_SECONDARY,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        // 两个维度分两行、各自带标签（T2-3f）：原先「角色 chip」与「状态 chip」混排成一行，
                        // 看起来像同一组同级选项，容易被误读（2026-09-13 用户反馈）。
                        // 顺序：归属状态在上（高频、每条记录都要选），角色在下（低频、紧邻 ✓ 按钮）
                        LabeledRow(label = stringResource(R.string.calibration_signal_state_label)) {
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                // 展示顺序按**流程顺序**（PatrolScenes.calibrationOrder），
                                // 与枚举声明顺序（= 状态候选优先级，功能）分开；三个"异常屏"
                                // （新手引导 / 新手大厅 / 活动弹窗）置首并加分隔：它们不属于流程中的任何一站，
                                // 而是"随时可能出现"的异常（新手那两屏只在**新手号**出现，最容易漏标）。
                                items(PatrolScenes.calibrationOrder) { state ->
                                    // **一个 item 里只放一个直接子节点**（整条用 Row 包住）。
                                    // 2026-09-23 用户反馈「启动页按钮左边框有一条灰色竖线向上延伸」：
                                    // 起因就是这里把分隔线 Box 与 FilterChip 并列成 item 的两个兄弟节点 ——
                                    // LazyRow 的一个 item 只保证"这一个 item 占多宽/多高"，**不保证两个兄弟节点
                                    // 的相对位置**，于是那条 1dp 竖线被摆到了 chip 上方而不是它的左侧。
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // 分隔线落在「弹窗」与第一个流程站之间：左边是"随时可能出现"的异常，右边是流程站点
                                        if (state == UiState.LAUNCH_PAGE) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = 1.dp, height = 24.dp)
                                                    .background(Color.White.copy(alpha = 0.35f)),
                                            )
                                            // 补上被吞掉的那侧间距（原先是"分隔线自己占一个 item"，两侧各有 8dp）
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
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
                        }
                        LabeledRow(label = stringResource(R.string.calibration_role_label)) {
                            // 角色用分段控件（不是 chip）：与状态在形态上就区分开；
                            // 多选（T2-3g）：同一个元素既要判状态又要点击时勾两个 —— ✓ 按钮上的角色名同步显示两个
                            // 注意：这行**不能**再往右侧塞第二个元素（2026-09-13 真机 BUG）——分段按钮内部按
                            // weight 抢占整行剩余宽度，同行的其他控件会被挤成 0 宽（文字逐字换行 → 整条底栏变高）。
                            // 一个都不勾时**不加文字提示**（用户口径）：按钮上的角色名 + 本行标签「角色（可多选）」
                            // 已经说明一切；缺条件由点 ✓ 后的弹窗兜住。
                            RoleToggleRow(
                                selectedRoles = selectedRoles,
                                onRoleToggle = onRoleToggle,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                            )
                        }
                        // T4-3a：锚点用途。**这一行的高度恒定**（2026-09-23 用户反馈：勾/不勾锚点、
                        // 或换「所属界面」时它出现或消失 ⇒ 底栏高度跟着变 ⇒ 界面上下闪）。
                        // 口径与上面那行「选区坐标」一致 —— **不可用时也占住这一行**，只是把内容换成
                        // 一句话说清"现在为什么没得选"（空着会让人以为界面坏了；这比直接隐藏更省心）。
                        // 单控件界面（活动弹窗）没有用途可挑 ⇒ 提示"没有用途可选"，落到 `<界面>_e1` 槽位。
                        val anchorPurposes = selectedState?.let { PatrolAnchors.purposesFor(it) }.orEmpty()
                        val purposeSelectable = SignalRole.ANCHOR in selectedRoles && anchorPurposes.isNotEmpty()
                        val effective = effectiveAnchorPurpose(selectedState, selectedRoles, selectedPurpose)
                        val purposePrefix = stringResource(R.string.calibration_purpose_label) + "："
                        LabeledRow(label = stringResource(R.string.calibration_purpose_label)) {
                            if (purposeSelectable) {
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    items(anchorPurposes, key = { it.name }) { purpose ->
                                        val selected = effective == purpose.name
                                        // 读屏要能分清这是"用途"而不是"归属状态"（两行 chip 形态相同）
                                        val description = purposePrefix + purpose.label
                                        FilterChip(
                                            selected = selected,
                                            onClick = { onPurposeSelect(purpose.name) },
                                            label = {
                                                Text(
                                                    // 选中态额外加 ✓：用途选错 = 跑号点错控件，
                                                    // 不能只靠颜色传达（与列表页"选中行加 ✓"同一口径）
                                                    text = if (selected) "✓ ${purpose.label}" else purpose.label,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.onSecondaryContainer
                                                    } else {
                                                        Color.White
                                                    },
                                                )
                                            },
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                            modifier = Modifier.semantics {
                                                contentDescription = description
                                                role = Role.RadioButton
                                            },
                                        )
                                    }
                                }
                            } else {
                                // 占位说明：**必须用同一个 FilterChip 承载**（2026-09-23 二次修，用户复报"高度还是不一样"）。
                                //
                                // 第一版写成"固定 32dp 的 Box + 一段 bodySmall 文字"—— 那是我**猜**的 chip 高度。
                                // 猜错就永远差一截：真机上勾了「锚点」之后底栏仍会往上跳一下。
                                // 换成**禁用态 FilterChip** 之后，高度 / 内边距 / 圆角 / 字号全由控件自己决定，
                                // 与可选的 chip 行**逐像素同源** —— 不依赖任何常量，也不再受系统字号缩放影响。
                                FilterChip(
                                    selected = false,
                                    onClick = {},
                                    // 禁用而不是"点了没反应"：让它一眼看出"这不是个选项"
                                    enabled = false,
                                    label = {
                                        Text(
                                            text = stringResource(
                                                when {
                                                    selectedState == null ->
                                                        R.string.calibration_purpose_hint_pick_state
                                                    anchorPurposes.isEmpty() ->
                                                        R.string.calibration_purpose_hint_no_purpose
                                                    else -> R.string.calibration_purpose_hint_pick_role
                                                },
                                            ),
                                            // 显式给色：默认禁用色在黑底上几乎看不见（这里只是说明，不是选项）
                                            color = ON_DARK_SECONDARY.copy(alpha = 0.7f),
                                            maxLines = 1,
                                        )
                                    },
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                        // 2026-09-21 这里曾有一档「这是谁的头像」好友单选（写进 `SignalEntry.friend`）。
                        // **2026-09-23 随农场归属判定整块删除**：它的存在只为"好友头像"这一个用途
                        // （每位好友一条模板），用途撤销后它再也没有触发条件。
                        // 数据层的 `SignalEntry.friend` 与产物 v4 的 `friend` 段**保留** —— 旧产物还要能读。
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
                                    onDelete = { onDeleteFrame(frame) },
                                )
                            }
                        }
                    }
                    if (selectMode) {
                        // 写入条件不再常驻显示（用户 2026-09-13）：改成点 ✓ 之后在顶部条报「还差：…」，
                        // 见 onWriteSignal —— 少一行文案，工具条正好矮一截。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
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
                                // **始终可点**（用户 2026-09-13）：缺条件也不置灰 —— 置灰按钮收不到点击事件
                                // （Compose 语义），"为什么不能点"就只能靠猜。点下去缺条件 → 弹窗列缺项，
                                // 齐了才真正写入。
                                onClick = {
                                    val missing = missingWriteConditions(
                                        hasBox = selection != null,
                                        state = selectedState,
                                        roles = selectedRoles,
                                        requiresPurpose = requiresAnchorPurpose(selectedState, selectedRoles),
                                        hasPurpose = effectiveAnchorPurpose(
                                            selectedState,
                                            selectedRoles,
                                            selectedPurpose,
                                        ) != null,
                                    )
                                    if (missing.isEmpty()) onWriteSignal() else missingWrite = missing
                                },
                            ) {
                                // 按钮上直接写角色 + 用途（✓ 写入标志 + 锚点 · 农场入口）：最后一刻也能看清会写成什么
                                val writtenPurpose = effectiveAnchorPurpose(
                                    selectedState,
                                    selectedRoles,
                                    selectedPurpose,
                                )
                                val writtenLabel = writtenPurpose?.let { name ->
                                    selectedState?.let { state ->
                                        PatrolAnchors.purposesFor(state)
                                            .firstOrNull { it.name == name }
                                            ?.label
                                    }
                                }
                                Text(
                                    text = if (selectedRoles.isEmpty()) {
                                        stringResource(R.string.calibration_workbench_confirm_none)
                                    } else if (writtenLabel == null) {
                                        stringResource(
                                            R.string.calibration_workbench_confirm,
                                            roleSummaryLabel(selectedRoles),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.calibration_workbench_confirm_with_purpose,
                                            roleSummaryLabel(selectedRoles),
                                            writtenLabel,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
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
                // 删帧的撤销窗口画在**工作台自己的窗口里** —— 标定页那个 SnackbarHost 在下面那层窗口，
                // 被这个全屏 Dialog 整个盖住（2026-09-23 用户："确认删除后底部没有出现撤销"）。
                // 位置抬到底栏**之上**：底栏是"正在看的东西"，Snackbar 压在帧条上就把它挡了。
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = with(LocalDensity.current) { bottomBarHeightPx.toDp() } + 8.dp),
                )
            }
        }
    }
    if (hintOpen) {
        // M5-U5：`AlertDialog`（一屏高的长文）⇒ **底部弹层 + 六条**（每条 ≤60 字、一条一句）。
        // 弹层自带下滑关闭（点外部 / 返回键也可），所以不需要「关闭」按钮 —— 与两个清单页的
        // 「说明」弹层同一个版式（V2）。
        ModalBottomSheet(onDismissRequest = { hintOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.calibration_usage),
                    style = MaterialTheme.typography.titleMedium,
                )
                listOf(
                    R.string.calibration_usage_gestures,
                    R.string.calibration_usage_write,
                    R.string.calibration_usage_purpose,
                    R.string.calibration_usage_color,
                    R.string.calibration_usage_zoom,
                    R.string.calibration_usage_delete,
                ).forEach { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    val blocked = missingWrite
    if (blocked != null) {
        AlertDialog(
            onDismissRequest = { missingWrite = null },
            confirmButton = {
                TextButton(onClick = { missingWrite = null }) {
                    Text(
                        text = stringResource(R.string.calibration_hint_close),
                        color = Color.White,
                    )
                }
            },
            // 深色容器：工作台整体是近黑衬底（见 ON_DARK_* 注释），亮色弹窗上琥珀黄几乎看不见
            containerColor = Color(0xFF1C1C1C),
            titleContentColor = Color.White,
            title = { Text(text = stringResource(R.string.calibration_write_blocked_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.calibration_write_missing,
                        blocked.map { stringResource(it) }
                            .joinToString(stringResource(R.string.calibration_missing_sep)),
                    ),
                    // 警告色（用户 2026-09-13）：沿用原常驻「还差：…」那行的琥珀黄
                    color = PENDING_ACCENT,
                )
            },
        )
    }
}

/**
 * 底部工具栏按钮（T1-5m）：**图标在上、文字在下**（参照系统相册大图底部工具栏样式），
 * 深色衬底上用白色图标与白色文字；禁用态整体降透明度（与 Material 禁用态一致）。
 */
@Composable
internal fun ToolIconButton(
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
internal fun FramePage(file: File?) {
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
 *
 * ## 2026-09-23：视口从「中央横带」提升为整屏
 *
 * 改造前**手势区 = 画面区 = 按帧宽高比切出来的一块**（横屏帧在竖屏机上就是屏幕中央一条带，
 * 上下全是不可操作的空白），叠加平移被夹在"恰好铺满这块带"的范围里 ⇒ 用户反馈
 * 「放大后仍无法超出这个区域，操作区域太小」。
 *
 * 现在两层解耦：
 * - **视口层**（本函数的 Box）= 整屏可用区，负责手势与 `clipToBounds`；
 * - **内容层**（里层 Box）= 由 [FrameEditMath.fitRect] 算出的内接矩形，按帧比例居中。
 *   1 倍时视觉与改造前**逐像素一致**（帧仍居中、上下留黑边、大小不变），但放大后可以拖进黑边区。
 *
 * 所有坐标换算都交给 [FrameEditMath]：`toRatio` / `zoomBy` 收的都是**视口坐标**，
 * 适配矩形的原点偏移在那两处统一扣掉 —— **别在这里自己减偏移**，漏一处就是手指与框整体错位。
 */
