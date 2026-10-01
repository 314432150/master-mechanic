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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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

/** 模板最小边长（原图像素）：过小的模板缺少区分度，提取时拒绝。 */
private const val MIN_TEMPLATE_PX = 8

/**
 * 框选手势循环的**迭代硬上限**（2026-09-29，见 `InteractiveFrameCanvas` 里那段循环的注释）。
 *
 * 正常一次手势：按下 → 移动几帧 → 抬起 ≈ 几十次；长时间的拖拽/缩放最坏也就上千次。
 * 取 5000：既不会误伤任何正常手势，又能在"事件流异常密集（`awaitPointerEvent` 几乎不挂起）"时
 * 让循环**主动结束**，不至于把主线程占住 5 秒 ⇒ ANR ⇒ 用户看到"黑屏崩溃"。
 */
private const val GESTURE_MAX_ITERATIONS = 5_000



/** 日志标签（写入自检用，与采集侧同前缀便于一起抓）。 */
private const val TAG = "MM-Calibration"

/** 画布预览解码的最大宽度（只影响显示清晰度，不影响模板像素——模板取自原分辨率帧）。 */
private const val PREVIEW_MAX_WIDTH = 1080

/** 关闭钮中心相对选框左上角的外置距离（屏幕 dp；T1-5l 保留 T1-5k 外置口径，避免压住选框角）。 */
private val CLOSE_BUTTON_MARGIN = 16.dp

/** 关闭钮热区半径（屏幕 dp）。 */
private val CLOSE_BUTTON_TOUCH = 20.dp

/**
 * 画面被拖到视口外时，**每根轴至少保留可见的像素下限**（屏幕 dp，2026-09-23 设计评审口径）。
 *
 * 放开平移是为了让放大后的画面能拖出中央横带，但不能让它被拖到完全看不见
 * （用户会以为"把图拖丢了"）。48dp 是触控目标下限，也是"还看得见、还能把双指按上去抓回来"的下限；
 * 纯逻辑层不知道 dp，由这里换算后传给 [FrameEditMath.zoomBy]。
 */
private val MIN_VISIBLE_PX = 48.dp

/** 「适应画面」入口与底部悬浮条之间的空隙（屏幕 dp）：两者视觉上分开，免得被读成同一个东西。 */
private val ZOOM_CHIP_GAP = 8.dp

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
 * 选框主色 —— 「角色」一个都不勾（T2-3g）：琥珀黄（用户 2026-09-13 定稿）。
 * 含义是"还没决定写什么"，与标志红（色相 0°，差 45°）、锚点青（187°，差 142°）都拉得开。
 * **不要用白色**：外层命中带细线是 `#CCFFFFFF`、左上关闭钮是白底圆 —— 白框会跟它们糊在一起，
 * 真机上分不清框在哪（2026-09-13 真机反馈）。
 */
private val PENDING_ACCENT = Color(0xFFFFC107)

/**
 * 产物清单的行高（左滑行要固定高度，由 `SwipeActionRow` 统一约束）：
 * 缩略图 36dp + 上下各 10dp = 56dp，仍 ≥48dp 的触摸目标。
 */
private val ArtifactRowHeight = 56.dp

/** 左滑松手后的吸附时长（与服务器清单同一个手感）。 */
private const val SwipeSettleMillis = 180

/**
 * 详情弹窗键值行的 label 列宽：全表共用，值才能对齐成一条竖线。
 *
 * 取 **56dp**（≈ 最长的「搜索窗口」4 个字 + 余量），**不再按最长的标签撑满** ——
 * 标签越长的行越挤，值越容易换行；代价大的那一行（曾经的「用途（锚点）」）改成把角色放进**值**里。
 */
private val ParamLabelWidth = 56.dp

/**
 * 一次**可撤销的破坏性操作**：Snackbar 上那句话 + **操作前的整份产物**。
 *
 * 撤销 = 整份写回，而不是"按位置插回"：标定产物就一份、体积很小，
 * 整份快照既不会算错顺序，也不用为"插在哪一行"再写一套逻辑。
 *
 * 2026-09-20 起「删除整个产物」也走同一套（[message] 就是那条 Snackbar 的文案）——
 * 两种操作要恢复的东西本来**就是同一份快照**，没必要写两套。
 */
private data class PendingUndo(val message: String, val before: CalibrationData)

/**
 * 写入必备条件（用户 2026-09-13）：返回**缺项的资源 ID**，空 = 可以写入。
 * 点 ✓ 的弹窗与 `onWriteSignal` 的兜底校验共用这一份判据，避免两处判据漂移。
 */
private fun missingWriteConditions(
    hasBox: Boolean,
    state: UiState?,
    roles: Set<SignalRole>,
    requiresPurpose: Boolean = false,
    hasPurpose: Boolean = false,
): List<Int> = buildList {
    if (!hasBox) add(R.string.calibration_missing_box)
    if (state == null) add(R.string.calibration_missing_state)
    if (roles.isEmpty()) add(R.string.calibration_missing_roles)
    // T4-3a：这个界面上有不止一个可点控件时，锚点**必须**指定用途 ——
    // 不指定就只能退回自动序号名，而跑号代码按用途名找它、永远找不到（错在这里发现，比跑号时才发现好）
    if (requiresPurpose && !hasPurpose) add(R.string.calibration_missing_purpose)
}

/**
 * 这次写入是否**要**选锚点用途（T4-3a）：勾了锚点，且该界面确实有可点控件（用途表非空）。
 *
 * 没有用途表的界面（活动弹窗只有一个关闭控件）→ 沿用 `popup_close_anchor` 这类旧命名，不受影响。
 */
internal fun requiresAnchorPurpose(state: UiState?, roles: Set<SignalRole>): Boolean =
    state != null &&
        SignalRole.ANCHOR in roles &&
        PatrolAnchors.purposesFor(state).isNotEmpty()

/**
 * 这次写入实际采用的锚点用途（T4-3a）；null = 不走用途（没勾锚点 / 该界面没有用途表）。
 *
 * **只有一个用途的界面不用用户点** —— 没有第二种选择，点一下纯属多余；
 * 用 [effectiveAnchorPurpose] 算出来的值同时用于"禁用判据"与"实际写入"，两者不会漂移。
 */
internal fun effectiveAnchorPurpose(
    state: UiState?,
    roles: Set<SignalRole>,
    chosen: String?,
): String? {
    if (state == null || SignalRole.ANCHOR !in roles) return null
    val purposes = PatrolAnchors.purposesFor(state)
    return when {
        purposes.isEmpty() -> null
        purposes.size == 1 -> purposes.single().name
        else -> chosen
    }
}



/**
 * 标定页（T1-5b 起；T1-5l 按真机反馈重构；T2-2 起支持同状态多条记录）：**产物即唯一数据源**——
 * 全屏工作台里挑帧 / 框选 / 选归属状态，选中状态即写入产物（记录名由程序按图片 MD5 算，
 * 无草稿、无「保存产物」两步）；页面本体只保留采集控制、产物清单（可查看 / 可改备注 / 可删除）与
 * 产物级参数（合法即写入）。
 *
 * 产物在采集会话建立时加载进识别循环（CaptureService），本页不直接驱动识别。
 */
@Composable
fun CalibrationRoute(
    resumeTick: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var captureActive by remember { mutableStateOf(CaptureSessionSignal.isActive) }
    var frames by remember { mutableStateOf(CalibrationFramePool.listFrames(context)) }
    var recording by remember { mutableStateOf(CalibrationFramePool.isRecording) }
    var selectedFrame by remember { mutableStateOf<File?>(null) }
    var selection by remember(selectedFrame) { mutableStateOf<RatioRect?>(null) }
    var selectedState by remember(selectedFrame) { mutableStateOf<UiState?>(null) }
    // T2-3d：本次框选写入的角色（标志 = 判状态 / 锚点 = 点击位置）
    // T2-3g：改为**可多选**——同一个元素既要判状态又要点击时勾两个，一次写入两条记录（§2.1）；
    // 初始**一个都不勾**（2026-09-13 用户口径）：避免"默认标志"被顺手写进去，逼一次明确选择
    var selectedRoles by remember(selectedFrame) { mutableStateOf(emptySet<SignalRole>()) }
    // T4-3a：锚点用途。同一个界面常有不止一个可点控件（大厅 = 设置入口 + 农场入口），
    // 记录名（图片 MD5）分不出谁是谁 → 由用户选用途（`hall_farm`），**跑号代码按用途找锚点**。
    // 归属状态一换就清空（用途属于某个状态），取消勾锚点则保留（再勾回来不用重选）。
    var selectedPurpose by remember(selectedFrame) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    /** 可撤销的那一次删除：**删除前的整份产物 + 给人看的名字**（撤销 = 原样写回）。 */
    var pendingUndo by remember { mutableStateOf<PendingUndo?>(null) }
    /**
     * 破坏性操作的**二次确认**（2026-09-20 用户口径）：清空帧池 / 删除整个产物都先问一次。
     *
     * 它们与"删一行 / 删一条清单项"不是一回事：帧池是**在游戏里跑一遍才录得到**的素材，
     * 产物是**11 条锚点 + 10 个界面标志**的框选结果 —— 误触一次要重做半小时。
     */
    var confirmClearFrames by remember { mutableStateOf(false) }
    var confirmDeleteArtifact by remember { mutableStateOf(false) }
    /** 刚清空的帧数（非空 = 弹一条带「撤销」的 Snackbar）。 */
    var clearedFrames by remember { mutableStateOf<Int?>(null) }
    /**
     * **单帧删除的二次确认**（2026-09-23 用户要求：帧列表里有很多不需要的无效帧，要能就地剔除）。
     * 触发方式 = 缩略图上滑 / 下滑（长按同一件事，见 `FrameThumb`）。
     *
     * 为什么删一帧也要问一次：帧是**在游戏里跑一遍才录得到**的素材（与"清空帧池"同一个理由），
     * 而"滑动"比"点按钮"更容易误触 —— 问一次的成本，远低于回游戏里重录一帧。
     */
    var confirmDeleteFrame by remember { mutableStateOf<File?>(null) }
    /** 刚删掉的那一帧 + 它删除前的序号（1 起，只给文案用）——非空 = 弹一条带「撤销」的 Snackbar。 */
    var deletedFrame by remember { mutableStateOf<Pair<File, Int>?>(null) }
    var workbenchOpen by remember { mutableStateOf(false) }
    // T1-5m：工作台是否处于「框选模式」（浏览 = 滑页挑帧；框选 = 禁滑页 + 状态条 + ✕/✓）
    var selectMode by remember { mutableStateOf(false) }
    var viewingSignal by remember { mutableStateOf<String?>(null) }

    /**
     * 详情弹窗要看**哪一条记录**（2026-10-01：清单行已按角色拆成两行 ⇒ 点哪行看哪条）。
     * null = 沿用旧行为（按标志优先挑一条）。
     */
    var viewingRole by remember { mutableStateOf<SignalRole?>(null) }
    var artifactTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val artifact = remember(resumeTick, artifactTick) { loadArtifact(context) }

    /**
     * **旧产物备注补全**（2026-10-01 用户口径："将旧产物的备注按新规则改一下"）。
     *
     * 备注是清单行的主文本（也是悬浮窗提示里中文说法的来源），而规则上线前写下的记录全是空的 ⇒
     * 只能显示"模板 77×79"这类开发参数。这里在打开标定页时**一次性**补全并落盘：
     * 幂等（只填空备注，用户手写的永不被覆盖），归属界面认不出的记录跳过。
     * 落盘会顺带触发 `CalibrationReloadSignal` ⇒ 采集侧就地重载，不必重开会话。
     */
    LaunchedEffect(artifactTick) {
        val filled = withContext(Dispatchers.IO) {
            runCatching {
                val current = CalibrationStore.load(context) ?: return@runCatching 0
                val (updated, count) = CalibrationSignals.withDefaultNotes(
                    current = current,
                    // 新规则：**按记录的角色**各给一句（锚点用用途标签；标志写「界面」的界面标志）
                    defaultFor = { state, role, purpose ->
                        SignalNames.defaultNoteFor(state, role, purpose)
                    },
                    // 认"这句是程序写的"（默认规则当天改过两版：标志/锚点串味版、以及"锚点"兜底版）
                    // ⇒ 命中就换成新文本；用户手写的（不在集合里）永不被动
                    autoNoteHistory = { state, roles, purpose ->
                        SignalNames.autoNoteHistory(state, roles, purpose)
                    },
                )
                if (count > 0) CalibrationStore.save(context, updated)
                count
            }.getOrDefault(0)
        }
        if (filled > 0) {
            message = context.getString(R.string.calibration_notes_filled, filled)
            // 刷新列表（否则这一屏还显示着补全前的空备注）；下一次跑进来 count 为 0 ⇒ 不再自增，不会打转
            artifactTick++
        }
    }
    /**
     * 回收站里可恢复的帧数（帧池卡片上的「恢复」入口据此出现）。
     * 随 [frames] 变化重算：清空后变非空、恢复/重录后回到 0 —— 帧池变了它就一定变。
     */
    val restorableFrames = remember(frames) { CalibrationFramePool.restorableCount(context) }
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

    // ⚠ "工作台打开 ⇒ 暂停采集取帧"这一版已回滚（2026-09-29）：真机量到 Java 堆 15 秒涨 110MB
    // （留住了的增长）⇒ 先回到以前的写法做二分，等定位到那处再一并改。

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
        onClearFrames = { confirmClearFrames = true },
        restorableCount = restorableFrames,
        // 卡片上的「恢复」入口：与 Snackbar 的撤销走同一条路（`CalibrationFramePool.restore`）
        onRestoreFrames = {
            scope.launch {
                val restored = withContext(Dispatchers.IO) { CalibrationFramePool.restore(context) }
                frames = CalibrationFramePool.listFrames(context)
                selectedFrame = null
                message = context.getString(R.string.calibration_frames_restored, restored)
            }
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
        onViewSignal = { id, role ->
            viewingSignal = id
            viewingRole = role
        },
        onRemoveSignal = { id, role ->
            // 读盘上现产物再删（T1-5l ②）：不依赖组合期捕获的快照，避免与刚写入的信号错位。
            // **删除粒度 = 一条记录**（2026-10-01：清单行已按角色拆分 ⇒ 删哪行删哪条）。
            // ⚠ 只删标志会让那一屏**认不出来**（真机踩过：大厅只剩锚点 ⇒ 起点判定失效）——
            // 撤销窗口还在（把删除前的整份产物原样写回，顺序、位置都保住），所以先做再给撤回机会。
            // **删除即生效 + 撤销窗口**（与服务器 / 好友清单同一口径）：左滑 + 点按钮已是两步明确意图。
            scope.launch {
                val removed = runCatching {
                    withContext(Dispatchers.IO) {
                        val current = CalibrationStore.load(context) ?: return@withContext null
                        val state = CalibrationSignals.stateOf(current, id)
                        val text = CalibrationArtifactGroups.of(current)
                            .flatMap { it.elements }
                            .firstOrNull { it.id == id }
                            ?.let { CalibrationArtifactGroups.primaryText(it, state ?: UiState.UNKNOWN) }
                            ?: id
                        // 文案里带上角色：清单里同一个元素现在是两行，只说主文本会分不清删的是哪一行
                        val label = "$text · ${role.label}"
                        val next = CalibrationSignals.remove(current, id, role)
                        if (next == null) CalibrationStore.delete(context) else CalibrationStore.save(context, next)
                        // **删除也留痕**（2026-10-01 补）：以前删除只弹一条 Snackbar、日志里一个字都没有 ⇒
                        // 排查"产物里那条记录怎么没了"只能靠"信号数从 29 变 28"反推（真机真吃过这个亏：
                        // 大厅的标志就是这么丢的，当时误判成"旧产物问题"）。字段与「写入已落盘」同一套，一眼对得上。
                        val left = if (next == null) {
                            "删空了 ⇒ 产物文件已删除"
                        } else {
                            "剩余 ${next.signals.size} 条记录（锚点 " +
                                "${next.signals.count { it.role == SignalRole.ANCHOR }} 个）"
                        }
                        MmLog.i(
                            TAG,
                            "删除记录已落盘 $id｜${role.name}｜${state?.label ?: "（认不出归属界面）"}｜$left",
                        )
                        // 删除前的整份产物留作撤销快照：原样写回即恢复（顺序、位置都保住）
                        PendingUndo(
                            message = context.getString(R.string.calibration_signal_deleted, label),
                            before = current,
                        )
                    }
                }
                removed
                    .onSuccess { deleted ->
                        if (deleted != null) {
                            if (viewingSignal == id) viewingSignal = null
                            artifactTick++
                            pendingUndo = deleted
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
        // 只负责"问一次"：真正删除在确认弹窗的回调里（2026-09-20 用户口径："删除整个产物"要先确认）
        // 那个按钮只在有产物时才画得出来（见 ArtifactCard 的 `data == null` 分支），所以这里不判空
        onDeleteArtifact = { confirmDeleteArtifact = true },
        snackbarHostState = snackbarHostState,
    )

    // 撤销窗口（与服务器 / 好友清单同一口径）：Snackbar 上点「撤销」才恢复，超时就让它过去。
    // 时长取 Long（约 10s）：删除不可逆、这里又是**唯一的挽回入口** —— 用户口径"撤销按钮驻留太短"（2026-09-22）
    LaunchedEffect(pendingUndo) {
        val deleted = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deleted.message,
            actionLabel = context.getString(R.string.calibration_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val restored = runCatching {
                withContext(Dispatchers.IO) {
                    CalibrationStore.save(context, deleted.before)
                    true
                }
            }.getOrDefault(false)
            if (restored) {
                artifactTick++
                message = context.getString(R.string.calibration_restored)
                // 撤销也要留痕（2026-10-01）：否则日志里只留下"删除记录已落盘"，而记录其实还在 —— 反而更误导
                MmLog.i(
                    TAG,
                    "删除已撤销：产物原样写回 ${deleted.before.signals.size} 条记录" +
                        "（含锚点 ${deleted.before.signals.count { it.role == SignalRole.ANCHOR }} 个）",
                )
            }
        }
        pendingUndo = null
    }

    // 清空帧池的撤销窗口：真删是不可能的（帧要重录），所以"清空"其实是移进回收站（`CalibrationFramePool.clear`）。
    // 除了这条 Snackbar，帧池卡片上还有一个常驻的「恢复」入口（回收站非空时才出现）——
    // Snackbar 只活十几秒，而"清错了 49 帧"这件事可能过一会儿才被发现。
    LaunchedEffect(clearedFrames) {
        val count = clearedFrames ?: return@LaunchedEffect
        if (count <= 0) {
            clearedFrames = null
            return@LaunchedEffect
        }
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.calibration_frames_cleared, count),
            actionLabel = context.getString(R.string.calibration_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val restored = withContext(Dispatchers.IO) { CalibrationFramePool.restore(context) }
            frames = CalibrationFramePool.listFrames(context)
            selectedFrame = null
            message = context.getString(R.string.calibration_frames_restored, restored)
        }
        // **必须放在最后**：这个值就是本 LaunchedEffect 的 key，提前清空 = 改 key = 本协程被取消，
        // 于是 `showSnackbar` 还没显示就被撤掉 —— 用户看到的现象是"点了清空，底部什么也没出现"
        // （2026-09-20 用户报的就是这个；`pendingUndo` 那条本来就写在最后，所以它是好的）
        clearedFrames = null
    }

    // 删单帧的撤销窗口（与"清空帧池"同一口径）：帧要回游戏里重录，删错一帧也得找得回来。
    // 暂存有上限（`CalibrationFramePool.UNDO_TRASH_LIMIT`）⇒ 恢复可能失败，那就如实报，不假装成功。
    LaunchedEffect(deletedFrame) {
        val deleted = deletedFrame ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.calibration_frame_deleted, deleted.second),
            actionLabel = context.getString(R.string.calibration_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            val restored = withContext(Dispatchers.IO) {
                CalibrationFramePool.restoreFrame(context, deleted.first)
            }
            if (restored) {
                frames = CalibrationFramePool.listFrames(context)
                // 恢复的正是刚才那一帧 → 顺手跳回去（"撤销"要看起来像什么都没发生）
                selectedFrame = deleted.first
                message = context.getString(R.string.calibration_restored)
            } else {
                message = context.getString(R.string.calibration_frame_restore_failed)
            }
        }
        // **同 clearedFrames 那条教训**：这里改的就是本 LaunchedEffect 的 key ⇒ 必须放最后，
        // 提前清空会让协程被取消、Snackbar 还没露脸就消失
        deletedFrame = null
    }

    // 破坏性操作的二次确认（2026-09-20 用户口径）
    if (confirmClearFrames) {
        ConfirmDialog(
            title = stringResource(R.string.calibration_clear_frames_title),
            body = stringResource(R.string.calibration_clear_frames_body, frames.size),
            confirmLabel = stringResource(R.string.calibration_clear_frames_confirm),
            onConfirm = {
                confirmClearFrames = false
                clearedFrames = CalibrationFramePool.clear(context)
                frames = CalibrationFramePool.listFrames(context)
                selectedFrame = null
                message = null
            },
            onDismiss = { confirmClearFrames = false },
        )
    }
    // 单帧删除的二次确认（与"清空帧池"共用同一个对话框组件 —— 语义上确实是同一类操作）
    val doomedFrame = confirmDeleteFrame
    val doomedIndex = doomedFrame?.let { frames.indexOf(it) } ?: -1
    if (doomedFrame != null && doomedIndex >= 0) {
        ConfirmDialog(
            title = stringResource(R.string.calibration_delete_frame_title),
            body = stringResource(
                R.string.calibration_delete_frame_body,
                doomedIndex + 1,
                frames.size,
            ),
            confirmLabel = stringResource(R.string.calibration_delete_frame_confirm),
            onConfirm = {
                confirmDeleteFrame = null
                val position = doomedIndex + 1
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        CalibrationFramePool.deleteFrame(context, doomedFrame)
                    }
                    if (!ok) {
                        message = context.getString(R.string.calibration_delete_frame_failed, position)
                        return@launch
                    }
                    val rest = CalibrationFramePool.listFrames(context)
                    frames = rest
                    // 选中的帧被删掉了 → 停在**同一个位置**（后一帧顶上来）；删到空则清空选择。
                    // 滑过来的下一帧往往也是要剔的，停在原地比跳回第一帧顺手得多。
                    if (selectedFrame == doomedFrame) {
                        selectedFrame = rest.getOrNull((position - 1).coerceAtMost(rest.size - 1))
                    }
                    deletedFrame = doomedFrame to position
                }
            },
            onDismiss = { confirmDeleteFrame = null },
        )
    }
    if (confirmDeleteArtifact) {
        val doomed = artifact.data
        ConfirmDialog(
            title = stringResource(R.string.calibration_delete_title),
            body = stringResource(
                R.string.calibration_delete_body,
                doomed?.signals?.size ?: 0,
                doomed?.frameWidth ?: 0,
                doomed?.frameHeight ?: 0,
            ),
            confirmLabel = stringResource(R.string.calibration_delete_confirm),
            onConfirm = {
                confirmDeleteArtifact = false
                val before = doomed
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { CalibrationStore.delete(context) } }
                    // 「删除整个产物」也留痕（2026-10-01，与"删一条记录"同一口径）：这是清单里最重的破坏性操作，
                    // 事后只有这一行能说明"产物是被主动删掉的，不是读坏了"
                    MmLog.i(TAG, "产物已删除（整个文件）｜原有 ${before?.signals?.size ?: 0} 条记录")
                    viewingSignal = null
                    artifactTick++
                    // 撤销 = 把删除前那一份原样写回（与"删一行"共用同一套快照）
                    if (before != null) {
                        pendingUndo = PendingUndo(context.getString(R.string.calibration_deleted), before)
                    } else {
                        message = context.getString(R.string.calibration_deleted)
                    }
                }
            },
            onDismiss = { confirmDeleteArtifact = false },
        )
    }

    if (workbenchOpen) {
        // T1-5m：框选模式由工作台回调驱动（进入清旧选框；✓ 写入成功后回浏览模式）
        CalibrationWorkbench(
            frames = frames,
            selectedFrame = selectedFrame,
            selection = selection,
            selectedState = selectedState,
            selectedRoles = selectedRoles,
            selectedPurpose = selectedPurpose,
            message = message,
            selectMode = selectMode,
            snackbarHostState = snackbarHostState,
            onSelectFrame = { selectedFrame = it },
            // 工作台只**发起**删除：确认与真删都在页面层（对话框在标定页里，SnackbarHost 也在那儿）
            onDeleteFrame = { confirmDeleteFrame = it },
            onSelectionChange = { selection = it },
            onStateSelect = {
                selectedState = it
                // 用途属于某个状态：换了状态就必须重填（否则会把"农场入口"写到启动页上）
                selectedPurpose = null
            },
            onRoleToggle = { role ->
                selectedRoles = if (role in selectedRoles) selectedRoles - role else selectedRoles + role
            },
            onPurposeSelect = { selectedPurpose = it },
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
                val frame = selectedFrame
                val sel = selection
                val state = selectedState
                val roles = selectedRoles
                val purpose = effectiveAnchorPurpose(state, roles, selectedPurpose)
                // 写入条件不常驻显示：缺什么由点 ✓ 时的弹窗报全（见工作台的 missingWrite）。
                // 这里只做兜底 —— 工作台已按同一判据拦下，重复校验成本为零，但能防住绕过 UI 的调用。
                val missing = missingWriteConditions(
                    hasBox = frame != null && sel != null,
                    state = state,
                    roles = roles,
                    requiresPurpose = requiresAnchorPurpose(state, roles),
                    hasPurpose = purpose != null,
                )
                if (missing.isNotEmpty()) {
                    message = context.getString(
                        R.string.calibration_write_missing,
                        missing.joinToString(context.getString(R.string.calibration_missing_sep)) {
                            context.getString(it)
                        },
                    )
                } else if (frame != null && sel != null && state != null) {
                    scope.launch {
                        val result = writeSignalFromSelection(
                            context = context,
                            frame = frame,
                            ratio = sel,
                            state = state,
                            roles = roles,
                            anchorPurpose = purpose,
                            // 2026-09-23：界面上不再有"归属好友名"这个输入（`friend_avatar` 用途已撤），
                            // 数据层那条 `friend` 字段留着只为读旧产物。
                            anchorFriend = null,
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
        // 详情看的是**元素**（同一 ID 的标志 + 锚点是一行的两面）：默认按**标志**那条显示
        // （同一次框选两面共用窗口与模板；分两次标才可能不一样，那时会有提示）
        val element = data?.let { d ->
            d.signals.filter { it.id == viewingSignal }.takeIf { it.isNotEmpty() }
        }
        val marker = element?.firstOrNull { it.role == SignalRole.MARKER }
        val anchor = element?.firstOrNull { it.role == SignalRole.ANCHOR }
        // 点的是哪一行就显示哪条记录（2026-10-01 分两行显示后，两行的模板/窗口可能不一样）；
        // `viewingRole` 为 null（老路径）时沿用"标志优先"
        val shown = element?.firstOrNull { it.role == viewingRole } ?: marker ?: element?.first()
        val differ = marker != null && anchor != null && (
            marker.window != anchor.window ||
                marker.templates.first().width != anchor.templates.first().width ||
                marker.templates.first().height != anchor.templates.first().height ||
                !marker.templates.first().pixels.contentEquals(anchor.templates.first().pixels)
            )
        if (data != null && shown != null) {
            TemplateDetailDialog(
                id = shown.id,
                roles = element.orEmpty().map { it.role },
                state = CalibrationSignals.stateOf(data, shown.id),
                purpose = data.purposeOf(shown.id),
                friend = data.friendOf(shown.id),
                // 看的是**这一条记录**的备注（标志与锚点各说各的；该角色没有则退回同 ID 的第一条）
                note = data.noteOf(shown.id, shown.role),
                template = shown.templates.first(),
                templateCount = shown.templates.size,
                window = shown.window,
                frameWidth = data.frameWidth,
                frameHeight = data.frameHeight,
                sidesDiffer = differ,
                onSaveNote = { note ->
                    scope.launch {
                        val ok = runCatching {
                            withContext(Dispatchers.IO) {
                                val current = CalibrationStore.load(context)
                                    ?: return@withContext false
                                // 只改**这一条记录**的备注（点的是哪一行就改哪一行，2026-10-01）
                                CalibrationStore.save(
                                    context,
                                    CalibrationSignals.withNote(current, shown.id, shown.role, note),
                                )
                                true
                            }
                        }.getOrDefault(false)
                        viewingSignal = null
                        if (ok) {
                            artifactTick++
                            message = context.getString(R.string.calibration_note_saved)
                        } else {
                            message = context.getString(R.string.calibration_note_save_failed)
                        }
                    }
                },
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
    restorableCount: Int,
    onRestoreFrames: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onThresholdChange: (String) -> Unit,
    onMarginChange: (String) -> Unit,
    onMinDistanceChange: (String) -> Unit,
    onViewSignal: (String, SignalRole) -> Unit,
    onRemoveSignal: (String, SignalRole) -> Unit,
    onDeleteArtifact: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
private fun ConfirmDialog(
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
private fun ArtifactCard(
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
                            Animatable(from).animateTo(target, tween(SwipeSettleMillis, easing = FastOutSlowInEasing)) {
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
private fun RoleBadge(text: String) {
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
private fun ParamRow(label: String, value: String) {
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
private fun TemplateDetailDialog(
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
 *   右 ✓（确认写入：需已框选 + 已选归属状态 + 至少一个写入角色）；工具栏上方是两行各自的选项
 *   （T2-3f：「归属状态」chip 行 +「角色」行），再上一行为选区坐标（单行全宽居中）。
 *   缺条件时点 ✓ 不写入，改为弹窗列出缺项（用户 2026-09-13）。
 *
 * 写入时机（T1-5m 修订 T1-5l 口径）：点 ✓ 按选中状态写入产物，成功后回浏览模式。
 * 角色可多选（T2-3g）：勾两个 = 同一个元素同时写「标志 + 锚点」两条记录（§2.1）。
 */
@Composable
private fun CalibrationWorkbench(
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
@Composable
private fun InteractiveFrameCanvas(
    info: CalibrationFrames.Preview,
    selection: RatioRect?,
    roles: Set<SignalRole>,
    onSelectionChange: (RatioRect?) -> Unit,
    /**
     * 屏幕下沿被底部悬浮条占掉的高度（px）。
     *
     * 只用来把**浮层入口**抬到条之上；**不参与视口尺寸**（视口恒为整屏）。
     * 2026-09-23 真机反馈修正：早先让它参与布局（给 Column 加 `padding(bottom=…)`），
     * 视口因此变矮 ⇒ 画面在"整屏减去底栏"的区域里居中 ⇒ **1 倍时整体偏高了**（底栏越高偏得越多）。
     */
    bottomInsetPx: Int = 0,
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
    // 每轴可见下限的像素兜底（设计评审口径：48dp 是触控目标下限，也是"还看得见"的下限）
    val minVisiblePx = with(LocalDensity.current) { MIN_VISIBLE_PX.toPx() }
    val latestMinVisible = rememberUpdatedState(minVisiblePx)
    val density = LocalDensity.current
    // 视口尺寸由布局回报：画面放在哪儿（适配矩形）必须由它算，不能靠猜。
    // **不把 info 放进 key**：视口尺寸是布局属性、与帧内容无关；带上 info 会在"换帧但视口不变"时
    // 把尺寸重置为 Zero，而 onSizeChanged 不会再回调 ⇒ 白丢一帧画面。
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(viewportSize, info) {
        FrameEditMath.fitRect(
            viewportSize.width.toFloat(),
            viewportSize.height.toFloat(),
            info.frameWidth.toFloat() / info.frameHeight,
        )
    }
    val latestFit = rememberUpdatedState(fit)
    // 帧尺寸也走"最新值"通道：手势协程的 key 改成 Unit 后，这里不能再直接读 `info`
    //（否则协程拿到的是启动那一刻的 info，换帧后仍按旧帧算"最小可拉框"）
    val latestFrameSize = rememberUpdatedState(info.frameWidth to info.frameHeight)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                // 视口 = **整屏**（不给自己留任何边距）：手势与裁剪都在这一层。
                // 下方那个入口按钮是**浮层**、不占布局 —— 一旦让它占布局，画面就会在"整屏减去底栏"的区域里
                // 居中，1 倍时整体偏高（2026-09-23 真机反馈）。
                .fillMaxSize()
                .background(Color(0xFF101010))
                .clipToBounds()
                .onSizeChanged { viewportSize = it }
                // ⚠ **key 不能用 `info`**（2026-09-29 真机"第三次打开框选就崩"的修复点之一）：
                // `pointerInput(key)` 的 key 一变，Compose 会把这段手势协程**整个重启**；而 `info` 是
                // 每次解码出来的预览对象 —— 只要它换了一次实例（缓存未命中 / 重新解码），手势就被重启一次。
                // 这里需要的值早就都走了 `rememberUpdatedState`（`latestFit` / `latestSelection` / `latestTransform`），
                // 再加一个 `latestFrameSize` 就够 ⇒ key 用 `Unit`，协程**永不因数据变化而重启**。
                // ⚠ **不用 Compose 的 `awaitEachGesture`，自己控制循环**（2026-09-29 关键修复）。
                //
                // 真机实录：它的 `while (isActive)` 会**每秒重新进入下面的块约 180 万次**
                // （探针日志：`框选画布手势进入 3619920 次（最近 2 秒）`）⇒ 主线程被烧穿 ⇒ ANR + 黑屏。
                // 机理：它把"这一轮没有任何变化"当成"所有指针都抬起了" ⇒ 一旦指针状态错乱，
                // 就变成"等按下 → 跑块 → 等抬起"的原地空转（我们块里的限次 `break` 也救不了：外层马上再进来）。
                //
                // 自己写之后，每次迭代都**只挂在"真实事件"上**（没有事件就挂起），并且：
                // ① 等一个真实的按下 → ② 处理整段手势 → ③ 自己等指针抬起（**带上限**，超限就**退出整个处理器**）。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val viewWidth0 = size.width.toFloat()
                            val viewHeight0 = size.height.toFloat()
                        // ⚠ **自己兜一层**（2026-09-29 真机日志：`适配 0.0×0.0｜视口 1440.0×3016.0`）：
                        // `fit` 是由 `viewportSize`（`onSizeChanged` 回报）算的；只要那一刻它还是 Zero，
                        // `fit` 就是 0×0 ⇒ 下面的守卫会把**这一屏的框选整条跳过**（用户表现：画布点了没反应）。
                        // 视口尺寸指针节点自己就有（`size`）⇒ 用它的现算一个，框选立刻可用；
                        // 等 `onSizeChanged` 回来之后，两者一致，行为不变。
                        val rawFit = latestFit.value
                        val frameSizeNow = latestFrameSize.value
                        val fitNow = if (rawFit.width > 0f && rawFit.height > 0f) {
                            rawFit
                        } else {
                            FrameEditMath.fitRect(
                                size.width.toFloat(),
                                size.height.toFloat(),
                                frameSizeNow.first.toFloat() / frameSizeNow.second.toFloat(),
                            )
                        }
                        val width = fitNow.width
                        val height = fitNow.height
                        val viewWidth = viewWidth0
                        val viewHeight = viewHeight0
                        if (width <= 0f || height <= 0f ||
                            viewWidth <= 0f || viewHeight <= 0f
                        ) {
                            // ⚠ **必须"先等一个真实事件"再继续**（2026-09-29 血案）：这一支若直接 `continue`，
                            // 就是**不挂起的纯空转** —— 真机实录每秒 500 万次重新进入本循环（`MM-Watch` +
                            // 手势进入计数同时指认）⇒ 主线程烧穿 ⇒ ANR。`awaitPointerEvent` 没有新事件时
                            // 会**挂起**，空转就此终止。（这一支实际很少走到：`fit` 由 [latestFit] 兜底现算，
                            // 只有连指针节点自己的尺寸都还没量出来时才会进来。）
                            awaitPointerEvent()
                            continue
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startTransform = latestTransform.value
                        val startSelection = latestSelection.value
                        val startRatio = FrameEditMath.toRatio(
                            down.position.x, down.position.y, startTransform, fitNow,
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
                        val frameSize = latestFrameSize.value
                        val minRatioX = MIN_TEMPLATE_PX / frameSize.first.toFloat()
                        val minRatioY = MIN_TEMPLATE_PX / frameSize.second.toFloat()
                        val touchSlop = viewConfiguration.touchSlop

                        var transforming = false
                        var createStarted = false
                        var lastCentroid = Offset.Zero
                        var lastDistance = 0f
                        var iterations = 0

                        while (true) {
                            // ⚠ **硬上限**（2026-09-29，看门狗栈实录：主线程卡在这段里 3 秒以上、分配 0MB/s ⇒ ANR）。
                            // `awaitPointerEvent` 只在"有下一个事件"时挂起；事件流异常密集时它几乎不挂起 ⇒
                            // 这个循环会把主线程整个占住。`awaitEachGesture` 的块是**受限挂起作用域**
                            // （只允许它自己的挂起函数，`yield()` 编译不过）⇒ 直接**限次**：
                            // 正常一次手势只有几十~几百次迭代（长按拖拽最坏也就上千），超过上限说明事件流异常，
                            // 主动结束本次手势 —— 用户最坏是"这一下拖动不跟手了"，而不会再看到黑屏崩溃。
                            if (++iterations > GESTURE_MAX_ITERATIONS) {
                                MmLog.w(
                                    TAG,
                                    "框选手势循环超过 $GESTURE_MAX_ITERATIONS 次迭代仍未结束（事件流异常密集）" +
                                        "⇒ **退出整个手势处理器**（不是只跳出内层循环：那样外层会立刻再进来，"
                                        + "真机实测过每秒 180 万次的空转）",
                                )
                                return@awaitPointerEventScope
                            }
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
                                        fitNow,
                                        viewWidth,
                                        viewHeight,
                                        latestMinVisible.value,
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
                                change.position.x, change.position.y, startTransform, fitNow,
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
                        // **自己等所有指针抬起**（不再依赖 Compose 的 `awaitAllPointersUp`：它把"这一轮没有变化"
                        // 当成"都抬起了"，正是上面那个每秒 180 万次空转的来源）。
                        // 只在**真实事件**上推进；超过上限就退出整个处理器（宁可放弃这次手势，绝不空转）。
                        var upWait = 0
                        while (true) {
                            if (++upWait > GESTURE_MAX_ITERATIONS) {
                                MmLog.w(
                                    TAG,
                                    "等待指针抬起超过 $GESTURE_MAX_ITERATIONS 次仍没有结果（指针状态异常）" +
                                        "⇒ 退出整个手势处理器",
                                )
                                return@awaitPointerEventScope
                            }
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    // 内容层 = 画面适配矩形（1 倍时就是"帧居中 + 上下黑边"的那一块）；
                    // 变换的平移量以它为原点，所以这里必须显式摆到适配位置，不能 fillMaxSize。
                    .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                    .size(
                        width = with(density) { fit.width.toDp() },
                        height = with(density) { fit.height.toDp() },
                    )
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
                            roles = roles,
                            scale = transform.scale,
                            strokePx = strokePx,
                            closeRadiusPx = closeRadiusPx,
                            closeMarginPx = closeMarginPx,
                        )
                    }
                }
            }
        }
        // 倍数 / 「适应画面」（2026-09-23 设计评审口径）：常驻的**复位入口**，同时也是"已放大"的指示。
        // 一点按就把画面拉回 1 倍居中 —— **不动选框**（清框是选框左上角那个 ✕ 的职责，别把两件事混成一个词）。
        //
        // 它是**浮层**（`align` + `padding`，不占布局）：① 底栏压不住它（抬到条之上）；
        // ② 不挤压视口 ⇒ 画面仍按整屏居中，1 倍位置与改造前一致。
        // 声明在视口之后 ⇒ 它在最上层、先拿到触摸，点它不会顺带在画布上起一个框。
        val zoomed = transform.scale > FrameEditMath.FIT_EPS
        TextButton(
            onClick = { transform = ViewTransform() },
            enabled = zoomed,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { bottomInsetPx.toDp() } + ZOOM_CHIP_GAP)
                .background(Color.Black.copy(alpha = 0.65f)),
        ) {
            Text(
                text = if (zoomed) {
                    stringResource(
                        R.string.calibration_zoom_fit,
                        "%.1f".format(transform.scale),
                    )
                } else {
                    stringResource(
                        R.string.calibration_zoom_value,
                        "%.1f".format(transform.scale),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (zoomed) ON_DARK_SECONDARY else ON_DARK_SECONDARY.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * 角色集合 → 界面显示名（T2-3g）：`标志` / `锚点` / `标志 + 锚点`，顺序固定（标志 → 锚点，
 * 见 [CalibrationSignals.orderedRoles]）。✓ 按钮与写入提示共用，保证「按下去写什么」处处一致。
 */
@Composable
private fun roleSummaryLabel(roles: Set<SignalRole>): String {
    val ordered = CalibrationSignals.orderedRoles(roles)
    return when (ordered.size) {
        0 -> ""
        1 -> ordered.first().label
        else -> stringResource(
            R.string.calibration_role_pair,
            ordered.first().label,
            ordered.last().label,
        )
    }
}

/** 同上，供非 Composable 的写入路径（消息文本）使用。 */
private fun roleSummaryText(context: Context, roles: Collection<SignalRole>): String {
    val ordered = CalibrationSignals.orderedRoles(roles)
    return when (ordered.size) {
        0 -> ""
        1 -> ordered.first().label
        else -> context.getString(
            R.string.calibration_role_pair,
            ordered.first().label,
            ordered.last().label,
        )
    }
}

/**
 * 「角色」的多选行（T2-3g）：标志 / 锚点各是一枚**可独立勾选**的分段按钮。
 *
 * 用的是 material3 的**多选**版分段控件 —— 它的行容器 `MultiChoiceSegmentedButtonRow` 从 1.4.0 起被标为
 * `@Deprecated(HIDDEN)`（只留二进制兼容），但配套的多选 `SegmentedButton` 重载仍是公开 API，
 * 只要求调用点位于 [MultiChoiceSegmentedButtonRowScope]（一个只继承 [RowScope] 的空标记接口）之内，
 * 所以这里用一个 [Row] + 桥接作用域即可，外观与单选分段控件完全一致。
 */
@Composable
private fun RoleToggleRow(
    selectedRoles: Set<SignalRole>,
    onRoleToggle: (SignalRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        val multiChoiceScope = MultiChoiceRowScopeBridge(row = this)
        with(multiChoiceScope) {
            val roles = SignalRole.entries
            roles.forEachIndexed { index, role ->
                SegmentedButton(
                    checked = role in selectedRoles,
                    onCheckedChange = { onRoleToggle(role) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = roles.size),
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
    }
}

/** [RoleToggleRow] 的作用域桥：把 [Row] 的 [RowScope] 适配成多选分段控件要求的作用域。 */
private class MultiChoiceRowScopeBridge(private val row: RowScope) :
    RowScope by row,
    MultiChoiceSegmentedButtonRowScope

/** 带前缀标签的一行（T2-3f）：标签固定不滚动，内容占满剩余宽度（用于「归属状态 / 角色」两行）。 */
@Composable
private fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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

/**
 * 帧条里的一枚缩略图。三种操作：
 *
 * | 操作 | 结果 |
 * | --- | --- |
 * | **点** | 切到这一帧 |
 * | **上滑 / 下滑** | 删除这一帧（先弹确认）—— 2026-09-23 用户要求：帧列表里攒了很多无效帧，要能就地剔 |
 * | **长按** | 同一件事，给**读屏**留的路 |
 *
 * 长按那条不是冗余：滑动在 TalkBack 下要么没有、要么发现不了，而 [combinedClickable] 会把长按
 * 发布成**语义动作**（`onLongClickLabel`），读屏用户能直接选到「删除本帧」
 * （与清单行那句"语义动作必须另有路径"同一口径）。
 *
 * **横向位移一定要让给帧条自己滚**：所以用 `draggable(orientation = Vertical)` —— 它只在纵向占优时
 * 才开始拖，横向占优时整个手势原样交还父级。（自己写 `detectDragGestures` 就会和 LazyRow 抢事件，
 * 结果是帧条滑不动。）
 *
 * 判据（划多远 / 甩多快算数）是纯算术，在 [FrameThumbSwipe] 里，有 JVM 单测。
 */
@Composable
private fun FrameThumb(
    file: File,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val preview by produceState<CalibrationFrames.Preview?>(null, file) {
        value = withContext(Dispatchers.IO) { CalibrationFrames.decodePreview(file, 144) }
    }
    val info = preview
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    /** 跟手位移（px，夹在 ±[FrameThumbSwipe.MAX_PULL_DP]）：松手回弹。 */
    var pull by remember { mutableFloatStateOf(0f) }
    /** 未播完的回弹动画；新的一次拖动要先把它掐掉，否则两个动效互相打架。 */
    val settleJob = remember { mutableStateOf<Job?>(null) }
    val limitPx = with(density) { FrameThumbSwipe.MAX_PULL_DP.dp.toPx() }
    // 过了门槛就变红：**手指还没抬起来**用户就知道"这一下会弹删除确认"
    val armed = abs(pull) >= with(density) { FrameThumbSwipe.DELETE_DISTANCE_DP.dp.toPx() }
    val drag = rememberDraggableState { delta ->
        settleJob.value?.cancel()
        pull = (pull + delta).coerceIn(-limitPx, limitPx)
    }
    // 缩略图固定小方块：列表条矮化与画布同屏；裁切显示画面中部
    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer { translationY = pull }
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (selected || armed) 2.dp else 1.dp,
                // 缩略图条压在近黑浮层上：选中用纯白（主题 primary 是深紫、黑底上显不出选中），
                // 未选中用弱白；划过门槛换成错误色（"这一下会删掉它"）
                color = when {
                    armed -> MaterialTheme.colorScheme.error
                    selected -> Color.White
                    else -> Color.White.copy(alpha = 0.25f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.calibration_delete_frame_action),
                onLongClick = onDelete,
            )
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (FrameThumbSwipe.isDeleteSwipe(pull, velocity, density.density)) {
                        // 抬手这一下给个触感（与长按同一种反馈）：确认框弹出来的同时手指也"知道了"
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    }
                    // 无论删不删都要归位：确认框只是"问一句"，取消时缩略图不能停在半路
                    settleJob.value = scope.launch {
                        Animatable(pull).animateTo(
                            targetValue = 0f,
                            animationSpec = tween(SwipeSettleMillis, easing = FastOutSlowInEasing),
                        ) {
                            pull = value
                        }
                    }
                },
            ),
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

/**
 * 提取准备结果（IO 线程产出 → 主线程写入产物）。
 *
 * [rgba] 保留**原始帧像素**（2026-09-29 加）：写入那一步要用它在"当前这张帧"上跑一次**试读**
 * （见 [CalibrationSignals.tryoutText]），而提取时读出来的那份像素在那个 `withContext` 块里
 * 就出了作用域。只是一份引用（不复制），本函数返回后即释放。
 */
private class PreparedSignal(
    val template: Template,
    val window: SearchWindow,
    val frameWidth: Int,
    val frameHeight: Int,
    val rgba: ByteArray,
    /**
     * 选区在帧内的像素矩形 `[x0, y0, x1, y1]`（2026-09-29 加）。
     *
     * 为什么要把选区带出来：余量改成**固定像素**之后，**标志与锚点的窗口不再相同**
     * （48 vs 64 px/边，见 `SelectionWindow` 的对象注释）⇒ 一次框选写给两个角色时，窗口要在写入那一步
     * **各算一次**，而算它需要选区本身。这里只搬引用，不复制像素。
     */
    val selection: IntArray,
)

/**
 * 框选 → 写入产物（T1-5l ①）：从选区提取模板与搜索窗口，落到**哪个元素**由
 * [CalibrationSignals.targetFor] 决定（2026-09-19 用户口径）：**有用途的锚点 → 覆盖同用途**，
 * 其余（标志 / 活动弹窗的关闭控件）→ **新增一条** `<界面>_e[n]`。
 * 判据是**语义**（用途 / 界面），不是像素 —— 人不可能每次都框出一样的像素。
 *
 * 几何校验（T1-5l ⑥）：产物已记录的标定帧几何与本次标定帧不一致时拒绝写入——窗口按整幅比例
 * 记录、模板按像素记录，混几何会让同一产物内的信号互相矛盾。解码失败 / 选区过小 / 几何不一致
 * 均以文本返回（本页以文字呈现，不中断流程）。
 *
 * 多角色（T2-3g）：[roles] 可含两个角色 —— 同一个元素既要判状态又要点击时，一次框选写**两条记录**
 * （一条标志、一条锚点，§2.1），两条共用本次提取的模板与搜索窗口（只解码 / 提取一次）。
 */
private suspend fun writeSignalFromSelection(
    context: Context,
    frame: File,
    ratio: RatioRect,
    state: UiState,
    roles: Set<SignalRole>,
    anchorPurpose: String?,
    /** 归属好友名（2026-09-21）：好友头像这类"每位好友一条"的用途才有值。 */
    anchorFriend: String? = null,
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
            // **只圈区域**的锚点（列表区域 / 好友名称列 / 列竖带）**不参与模板匹配** ——
            // 它们只是"把这一片送去读文字"的范围。所以这类模板**只存一张缩略图**：
            // 整块存对识别毫无用处，却会把产物撑到几 MB（真机 5.4MB），重载解码时 OOM、
            // 把帧线程读崩过（progress 第 258 条）。匹配用的信号一律**原样存**。
            val regionPurpose = anchorPurpose != null && anchorPurpose in PatrolAnchors.nonLocatableAnchors
            val extracted = TemplateExtractor.extract(
                rgba.pixels, rgba.width, rgba.height, rgba.width * 4, x0, y0, x1, y1,
            )
            // 这一份 `window` 只用于**日志与提示**（拿本次要写的角色里**更小**的那个余量算）：
            // 真正写进产物的是下面 `planned.forEach` 里**按角色各算**的那一份（标志 48 / 锚点 64 px/边）。
            val primaryRole = if (SignalRole.MARKER in roles) SignalRole.MARKER else SignalRole.ANCHOR
            PreparedSignal(
                template = if (regionPurpose) TemplateExtractor.preview(extracted) else extracted,
                // 原始帧像素带给写入那一步做**试读**（见 PreparedSignal 的说明）
                rgba = rgba.pixels,
                // 窗口策略按**用途 + 角色**挑：区域型用途（「列表区域」/「好友名称列」）**不外扩** ——
                // 它的窗口是文字识别的输入区域，外扩会把"名字那一列"变成更宽 ⇒ 等于没裁
                //（真机实录：框 510×57 的一行名字，产物里写成 1530×172）；
                // 其余记录各方向外扩**固定像素**（2026-09-29 起，旧口径是"外扩 1 倍选区"，
                // 见 `SelectionWindow` 的对象注释：容差该按像素计、成本不该随选区平方涨）。
                window = SelectionWindow.forPurpose(
                    purpose = anchorPurpose,
                    regionPurposes = PatrolAnchors.nonLocatableAnchors,
                    marginPx = SelectionWindow.marginPxFor(primaryRole),
                    frameWidth = rgba.width,
                    frameHeight = rgba.height,
                    x0 = x0,
                    y0 = y0,
                    x1 = x1,
                    y1 = y1,
                ),
                frameWidth = rgba.width,
                frameHeight = rgba.height,
                selection = intArrayOf(x0, y0, x1, y1),
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

        // 写入计划（2026-09-19 用户口径）：落到**哪个元素**只由用途决定 ——
        // 有用途的锚点 → 该用途元素（覆盖旧记录）；其余（标志 / 活动弹窗的关闭控件）→ 新增一条。
        // 两个角色落在同一元素 = 清单里的同一行。
        val planned = CalibrationSignals.rolesToWrite(state, roles)
        val id = CalibrationSignals.targetFor(
            current = current,
            state = state,
            roles = planned,
            purpose = anchorPurpose,
            friend = anchorFriend,
        )
        // 该元素已存在 = 这条是**重新标定**（覆盖）→ 提示要说"已更新"而不是"已新增"
        val replacing = current?.signals.orEmpty().any { it.id == id }
        // 写入自检（2026-09-21 真机排障）：把"这条记录到底写成了什么样"当场打出来 ——
        // 模板尺寸、窗口尺寸与位置、以及两者的比。
        // **比 ≈1.2~1.4 = 四周各外扩固定像素**（2026-09-29 起：标志 48 / 锚点 64 px/边）。
        // ⚠️ 旧口径下这里的比是"≈3 = 外扩 1 倍选区"，**看到 ≈1.2 不要当成写坏了**；
        // 区域型锚点（「列表区域」/「好友名称列」）**故意是 ≈1**（2026-09-23 修）—— 它们不做模板匹配，
        // 窗口是文字识别的输入区域，外扩反而让它失去"只读一列 / 只读一片"的作用。
        // 帧池里被测元素与产物对不上时，这一行是唯一能立刻分辨"框错了"还是"写坏了"的证据。
        run {
            val b = prepared.window.pixelBounds(prepared.frameWidth, prepared.frameHeight)
            val w = b.x1 - b.x0
            val h = b.y1 - b.y0
            val regionAnchor = anchorPurpose != null && anchorPurpose in PatrolAnchors.nonLocatableAnchors
            MmLog.i(
                TAG,
                "写入 $id｜${planned.joinToString("+") { it.name }}｜" +
                    "模板 ${prepared.template.width}×${prepared.template.height}｜" +
                    "窗口 ${w}×${h} @(${b.x0},${b.y0})｜" +
                    "余量 ${(w - prepared.template.width) / 2}×${(h - prepared.template.height) / 2}px/边｜" +
                    "窗口/模板 %.2f×%.2f｜".format(
                        w.toDouble() / prepared.template.width,
                        h.toDouble() / prepared.template.height,
                    ) +
                    (if (regionAnchor) {
                        "区域型锚点（窗口 = 选区，作识别输入区域；" +
                            "模板只存缩略图 ≤${TemplateExtractor.PREVIEW_MAX_PX}px）｜"
                    } else {
                        ""
                    }) +
                    (if (replacing) "覆盖" else "新增"),
            )
        }
        val effectiveParams = params ?: current?.params ?: CalibrationSignals.defaultParams()
        // ⚠ **先落盘，后试读**（2026-09-29 定序）：
        // 落盘是"产物即唯一数据源"的那一锤 —— 必须**不可取消**，否则用户点完 ✓ 就返回列表 ⇒
        // 协程被取消 ⇒ 记录静默丢失（真机：只有『写入』一行、没有『写入试读』、产物里也没有）。
        // 而试读只是诊断、且很贵（见下），放在**落盘之后** ⇒ 它即便被取消也不影响记录。
        val written = withContext(Dispatchers.IO + NonCancellable) {
            var data = current
            planned.forEach { role ->
                data = CalibrationSignals.upsert(
                    current = data,
                    id = id,
                    state = state,
                    // **按角色各算一次窗口**（2026-09-29）：余量是固定像素、且锚点比标志大
                    //（认不出 = 整步停摆）⇒ 一次框选同时写标志 + 锚点时，两条记录的窗口**刻意不一样**。
                    window = SelectionWindow.forPurpose(
                        purpose = if (role == SignalRole.ANCHOR) anchorPurpose else null,
                        regionPurposes = PatrolAnchors.nonLocatableAnchors,
                        marginPx = SelectionWindow.marginPxFor(role),
                        frameWidth = prepared.frameWidth,
                        frameHeight = prepared.frameHeight,
                        x0 = prepared.selection[0],
                        y0 = prepared.selection[1],
                        x1 = prepared.selection[2],
                        y1 = prepared.selection[3],
                    ),
                    template = prepared.template,
                    params = effectiveParams,
                    frameWidth = prepared.frameWidth,
                    frameHeight = prepared.frameHeight,
                    role = role,
                    // 用途只给锚点（标志没有用途）；没有用途表的界面（活动弹窗）保持 null
                    purpose = if (role == SignalRole.ANCHOR) anchorPurpose else null,
                    friend = if (role == SignalRole.ANCHOR) anchorFriend else null,
                    // **备注默认填"这条记录干嘛用"的中文描述**（2026-10-01 用户口径）：备注是标定清单行的
                    // 主文本（`ArtifactRow` 的"备注 > 用途 > 尺寸"），空着就只能显示"模板 77×79"这种参数；
                    // 用户手写的备注仍然优先，见 `CalibrationSignals.upsert` 的取值顺序。
                    // ⚠ 必须**按这一条记录的角色**算（2026-10-01 订正）：一次框选同时写标志 + 锚点时，
                    // 若按"整元素"算，两条会得到同一句话 —— 锚点就顶上了「界面标志」的文案（真机踩过）。
                    fallbackNote = SignalNames.defaultNoteFor(
                        state = state,
                        role = role,
                        purpose = if (role == SignalRole.ANCHOR) anchorPurpose else null,
                    ),
                )
            }
            val saved = requireNotNull(data) { "写入计划非空时产物必非空" }
            CalibrationStore.save(context, saved)
            // 落盘后再记一行（2026-09-29）：有了它，任何时候都能一眼分清"没写"与"写了但界面没刷新"。
            MmLog.i(TAG, "写入已落盘 $id｜${planned.joinToString("+") { it.name }}｜${state.label}")
            saved
        }

        // ⚠ **"写入试读"已整条移除**（2026-09-29 用户拍板："直接把试读这个功能给取消掉"）。
        //
        // 它原本是"写完后当场拿新模板在这张帧上再匹配一次、把结论摆在成功提示里"。代价是**一次完整
        // 模板匹配**（大模板 × 3 倍窗口要跑好几秒）：设成不可取消 ⇒ 用户返回列表后它仍在后台跑、
        // 连点几次就叠起来把 CPU 吃满 ⇒ **主线程 5 秒无响应 ⇒ ANR + 黑屏 + 闪退**（真机三次）。
        // 用户确认"有些图正常了、有些还是黑屏闪退" ⇒ 这个功能带来的信息**不值这个代价** ⇒ 整条删掉。
        // 写入路径现在只剩"准备 → 落盘"，不再有任何重活。
        // 同角色计数：标志数 / 锚点数各自累计（不要混着数，否则「该状态现有 N 条」会误导标定）
        val counts = CalibrationSignals.orderedRoles(planned)
            .joinToString("、") { role ->
                context.getString(
                    if (role == SignalRole.ANCHOR) {
                        R.string.calibration_count_anchor
                    } else {
                        R.string.calibration_count_marker
                    },
                    CalibrationSignals.countOf(written, state, role),
                )
            }
        // 提示里**不出现 ID**（md5 对人没有意义）：写的是"哪个角色、什么用途"
        val writtenRoles = roleSummaryText(context, planned) +
            if (anchorPurpose != null && SignalRole.ANCHOR in planned) {
                " · " + CalibrationArtifactGroups.purposeLabel(state, anchorPurpose)
            } else {
                ""
            }
        WriteResult(
            message = context.getString(
                if (replacing) R.string.calibration_signal_updated else R.string.calibration_signal_added,
                writtenRoles,
                state.label,
                prepared.template.width,
                prepared.template.height,
                counts,
            ), // 试读已于 2026-09-29 移除（见上：那条重活会把主线程饿死 ⇒ ANR + 黑屏）
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
 * 两个角色都勾选时（T2-3g）：外圈红 + 内圈青（双框线），一眼看出这一框会同时写成标志与锚点。
 *
 * [closeMarginPx] 为关闭钮中心相对选框左上角的外扩屏幕像素（与命中测试同一口径，
 * 见 [FrameEditMath.closeButtonCenter]）；它同时是**外框拉伸带**的宽度来源（T2-3f）。
 */
private fun DrawScope.drawSelectionOverlay(
    rect: RatioRect?,
    roles: Set<SignalRole>,
    scale: Float,
    strokePx: Float,
    closeRadiusPx: Float,
    closeMarginPx: Float,
) {
    if (rect == null || size.width <= 0f || size.height <= 0f) return
    val marker = SignalRole.MARKER in roles
    val anchor = SignalRole.ANCHOR in roles
    // 主色（T2-3g 定稿）：一个都没勾 = 琥珀黄（还没决定写什么）；只勾锚点 = 青；
    // 勾了标志（含两个都勾）以标志红为主，锚点青退到内圈
    val accent = when {
        roles.isEmpty() -> PENDING_ACCENT
        anchor && !marker -> ANCHOR_ACCENT
        else -> MARKER_ACCENT
    }
    val topLeft = Offset(rect.left * size.width, rect.top * size.height)
    val boxSize = Size(
        (rect.right - rect.left) * size.width,
        (rect.bottom - rect.top) * size.height,
    )
    val stroke = strokePx / scale
    drawRect(color = accent.copy(alpha = 0.2f), topLeft = topLeft, size = boxSize)
    drawRect(
        color = accent,
        topLeft = topLeft,
        size = boxSize,
        style = Stroke(width = stroke),
    )
    if (marker && anchor) {
        // 双角色：内圈再描一根锚点青线（选框太小放不下内圈时自动跳过，不画畸形线）
        val inset = stroke
        val innerSize = Size(boxSize.width - inset * 2f, boxSize.height - inset * 2f)
        if (innerSize.width > 0f && innerSize.height > 0f) {
            drawRect(
                color = ANCHOR_ACCENT,
                topLeft = Offset(topLeft.x + inset, topLeft.y + inset),
                size = innerSize,
                style = Stroke(width = stroke),
            )
        }
    }
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
