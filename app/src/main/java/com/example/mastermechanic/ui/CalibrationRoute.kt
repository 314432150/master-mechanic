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
internal const val MIN_TEMPLATE_PX = 8

/**
 * 框选手势循环的**迭代硬上限**（2026-09-29，见 `InteractiveFrameCanvas` 里那段循环的注释）。
 *
 * 正常一次手势：按下 → 移动几帧 → 抬起 ≈ 几十次；长时间的拖拽/缩放最坏也就上千次。
 * 取 5000：既不会误伤任何正常手势，又能在"事件流异常密集（`awaitPointerEvent` 几乎不挂起）"时
 * 让循环**主动结束**，不至于把主线程占住 5 秒 ⇒ ANR ⇒ 用户看到"黑屏崩溃"。
 */
internal const val GESTURE_MAX_ITERATIONS = 5_000



/** 日志标签（写入自检用，与采集侧同前缀便于一起抓）。 */
internal const val TAG = "MM-Calibration"

/** 画布预览解码的最大宽度（只影响显示清晰度，不影响模板像素——模板取自原分辨率帧）。 */
internal const val PREVIEW_MAX_WIDTH = 1080

/** 关闭钮中心相对选框左上角的外置距离（屏幕 dp；T1-5l 保留 T1-5k 外置口径，避免压住选框角）。 */
internal val CLOSE_BUTTON_MARGIN = 16.dp

/** 关闭钮热区半径（屏幕 dp）。 */
internal val CLOSE_BUTTON_TOUCH = 20.dp

/**
 * 画面被拖到视口外时，**每根轴至少保留可见的像素下限**（屏幕 dp，2026-09-23 设计评审口径）。
 *
 * 放开平移是为了让放大后的画面能拖出中央横带，但不能让它被拖到完全看不见
 * （用户会以为"把图拖丢了"）。48dp 是触控目标下限，也是"还看得见、还能把双指按上去抓回来"的下限；
 * 纯逻辑层不知道 dp，由这里换算后传给 [FrameEditMath.zoomBy]。
 */
internal val MIN_VISIBLE_PX = 48.dp

/** 「适应画面」入口与底部悬浮条之间的空隙（屏幕 dp）：两者视觉上分开，免得被读成同一个东西。 */
internal val ZOOM_CHIP_GAP = 8.dp

/**
 * 工作台衬底是近黑（`0xFF101010` + 半透明黑浮层）：其上的文字**不能沿用主题色**——
 * 应用是亮色主题，`primary` / `onSurfaceVariant` 在黑底上都是深色，真机上「看不清」（2026-09-13 用户反馈）。
 * 深色衬底上的文字统一走这两个常量。
 */
internal val ON_DARK_SECONDARY = Color.White.copy(alpha = 0.8f)

/** 深色衬底的错误提示色（亮色主题的 `error` 为深红，黑底不可读）。 */
internal val ON_DARK_ERROR = Color(0xFFFF8A80)

/** 选框主色 —— 标志（判状态）：沿用 T1-5k 的红色。 */
internal val MARKER_ACCENT = Color(0xFFFF5252)

/** 选框主色 —— 锚点（点击位置）：青色（与红色色相相距最远，黑底上同样醒目）。 */
internal val ANCHOR_ACCENT = Color(0xFF4DD0E1)

/**
 * 选框主色 —— 「角色」一个都不勾（T2-3g）：琥珀黄（用户 2026-09-13 定稿）。
 * 含义是"还没决定写什么"，与标志红（色相 0°，差 45°）、锚点青（187°，差 142°）都拉得开。
 * **不要用白色**：外层命中带细线是 `#CCFFFFFF`、左上关闭钮是白底圆 —— 白框会跟它们糊在一起，
 * 真机上分不清框在哪（2026-09-13 真机反馈）。
 */
internal val PENDING_ACCENT = Color(0xFFFFC107)

/**
 * 产物清单的行高（左滑行要固定高度，由 `SwipeActionRow` 统一约束）：
 * 缩略图 36dp + 上下各 10dp = 56dp，仍 ≥48dp 的触摸目标。
 */
internal val ArtifactRowHeight = 56.dp

/**
 * 左滑松手后的吸附时长（与服务器清单同一个手感）。
 *
 * ⚠ 名字带 `Calibration` 前缀**不是为了好看**：U5 拆文件后它被
 * `CalibrationEntryScreen` / `CalibrationCanvas` 跨文件引用 ⇒ 必须放宽到 `internal`，
 * 而 `ServerListScreen` 里有一个同名的 `private const val SwipeSettleMillis = 180` ⇒
 * 同名放宽会**撞声明**（真机编译报 `Conflicting declarations`）⇒ 这里改名避开。
 */
internal const val CalibrationSwipeSettleMillis = 180

/**
 * 详情弹窗键值行的 label 列宽：全表共用，值才能对齐成一条竖线。
 *
 * 取 **56dp**（≈ 最长的「搜索窗口」4 个字 + 余量），**不再按最长的标签撑满** ——
 * 标签越长的行越挤，值越容易换行；代价大的那一行（曾经的「用途（锚点）」）改成把角色放进**值**里。
 */
internal val ParamLabelWidth = 56.dp

/**
 * 一次**可撤销的破坏性操作**：Snackbar 上那句话 + **操作前的整份产物**。
 *
 * 撤销 = 整份写回，而不是"按位置插回"：标定产物就一份、体积很小，
 * 整份快照既不会算错顺序，也不用为"插在哪一行"再写一套逻辑。
 *
 * 2026-09-20 起「删除整个产物」也走同一套（[message] 就是那条 Snackbar 的文案）——
 * 两种操作要恢复的东西本来**就是同一份快照**，没必要写两套。
 */
internal data class PendingUndo(val message: String, val before: CalibrationData)

/**
 * 写入必备条件（用户 2026-09-13）：返回**缺项的资源 ID**，空 = 可以写入。
 * 点 ✓ 的弹窗与 `onWriteSignal` 的兜底校验共用这一份判据，避免两处判据漂移。
 */
internal fun missingWriteConditions(
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
    onFullScreenChange: (Boolean) -> Unit,
    onRequestReauth: () -> Unit,
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

    // M5-U5：**全屏工作台开着时，壳要把标题栏与底栏都收起来**（框选要占满整屏）。
    // 页面只**报告**状态 —— "现在有哪些栏"只有壳知道，怎么处理由壳决定（页面不越权改别人的 Scaffold）。
    LaunchedEffect(workbenchOpen) { onFullScreenChange(workbenchOpen) }
    // ⚠ 离开这一页（切目的地 / 退出）必须**复位**：否则壳会以为还全屏，把两个栏一直藏着 ✗。
    DisposableEffect(Unit) { onDispose { onFullScreenChange(false) } }
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

    // ⚠ 这里原有 `thresholdText` / `marginText` / `minDistanceText` 三个编辑态与 `applyParams()`：
    // **2026-10-02 整体搬到抽屉「诊断」页**（用户："识别参数目前还是放在了最底部，放进抽屉里吧"）——
    // 判断依据是"什么时候会用到它"：只在**认不出画面 / 老判不可信**时才调，而那正是去诊断页查问题的时刻。
    // 参数仍属于标定产物、读写同一个文件（`CalibrationStore`），本页不再持有任何参数输入。

    CalibrationScreen(
        captureActive = captureActive,
        frames = frames,
        recording = recording,
        artifact = artifact,
        message = message,
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
        // 没有采集会话时那颗主按钮：一键建立（App 内直接拉起系统采集授权，不必自己去授权页）
        onRequestReauth = onRequestReauth,
        onOpenWorkbench = {
            selectedFrame = selectedFrame ?: frames.firstOrNull()
            selection = null
            message = null
            workbenchOpen = true
        },
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
                            // 参数**不再是本页的输入框**（2026-10-02 搬到抽屉「诊断」页）⇒
                            // 写入新信号时**沿用产物里现有的那份**（与搬走前等价：那时输入框的初值
                            // 就是产物里的值，用户不改就等于沿用）。null = 产物还没有参数 ⇒ 由写入侧取默认。
                            params = artifact.data?.params,
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
