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

@Composable
internal fun InteractiveFrameCanvas(
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
internal fun roleSummaryLabel(roles: Set<SignalRole>): String {
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
internal fun roleSummaryText(context: Context, roles: Collection<SignalRole>): String {
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
internal fun RoleToggleRow(
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
internal class MultiChoiceRowScopeBridge(private val row: RowScope) :
    RowScope by row,
    MultiChoiceSegmentedButtonRowScope

/** 带前缀标签的一行（T2-3f）：标签固定不滚动，内容占满剩余宽度（用于「归属状态 / 角色」两行）。 */
@Composable
internal fun LabeledRow(label: String, content: @Composable RowScope.() -> Unit) {
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
internal fun FrameThumb(
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
                            animationSpec = tween(CalibrationSwipeSettleMillis, easing = FastOutSlowInEasing),
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
internal class ArtifactState(val data: CalibrationData?, val broken: Boolean) {

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

internal fun loadArtifact(context: Context): ArtifactState = try {
    ArtifactState(CalibrationStore.load(context), false)
} catch (_: IllegalArgumentException) {
    ArtifactState(null, true)
}

/** 写入结果：[saved] = 是否已落盘（成功才刷新产物列表与清空选框）。 */
internal class WriteResult(val message: String, val saved: Boolean)

/**
 * 提取准备结果（IO 线程产出 → 主线程写入产物）。
 *
 * [rgba] 保留**原始帧像素**（2026-09-29 加）：写入那一步要用它在"当前这张帧"上跑一次**试读**
 * （见 [CalibrationSignals.tryoutText]），而提取时读出来的那份像素在那个 `withContext` 块里
 * 就出了作用域。只是一份引用（不复制），本函数返回后即释放。
 */
internal class PreparedSignal(
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
internal suspend fun writeSignalFromSelection(
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
internal fun DrawScope.drawSelectionOverlay(
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
internal fun DrawScope.drawCloseButton(center: Offset, radius: Float, strokeWidth: Float) {
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
