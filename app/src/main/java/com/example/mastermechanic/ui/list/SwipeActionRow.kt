package com.example.mastermechanic.ui.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.mastermechanic.ui.ServerListGestures
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **统一列表行**（2026-09-15 UX 审核后抽出来，服务器清单 / 好友清单 / 巡查项共用）。
 *
 * 之前同一件事有三套触发方式（服务器 = 双击编辑 + 左滑直接删，好友 = 单击编辑 + 长按菜单，
 * 巡查项 = 行内四个文字按钮），现在收敛成一套契约：
 *
 * | 操作 | 触发 | 备注 |
 * | --- | --- | --- |
 * | 编辑 | **点按整行**（`onTap`）；`onDoubleTap` 只为兼容旧习惯保留 | 双击在安卓不常见，且读屏下会被吞成单击 |
 * | 删除 | **左滑 → 行尾压出按钮 → 点按钮**（`onDelete`） | 行**本身不动**，按钮盖在行上 |
 * | 排序 | **长按 + 拖**（`dragEnabled = true`） | 松手才落盘一次；无顺序语义的清单关掉它 |
 * | 菜单 | **长按**（`dragEnabled = false` 时的 `onLongPress`） | 长按在 TalkBack 下没有 → 语义动作必须另有路径 |
 *
 * 两组坑（都是从服务器清单那 370 行里带过来的教训，**别改坏**）：
 * 1. `pointerInput(key)` 的块**只在 key 变化时重启**，块里直接读参数会读到旧值 →
 *    所有回调一律经 `rememberUpdatedState` 取；
 * 2. 父级 `pointerInput` **先于子级**拿到事件 → 手指落在已露出的按钮上时，本行手势必须**整块退出**
 *    （`ServerListGestures.isOnRevealedDeleteButton`），否则按钮永远点不到。
 *
 * 纯逻辑（露出比例 / 吸附判据 / 命中区）仍在 `ServerListGestures`（复用，没另起一套）。
 */
@Composable
fun SwipeActionRow(
    key: Any,
    revealed: Boolean,
    anyRevealed: Boolean,
    revealWidthPx: Float,
    revealFraction: Float,
    rowHeight: Dp,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    dragOffsetX: Float = 0f,
    dragOffsetY: Float = 0f,
    dragEnabled: Boolean = false,
    onTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onCollapse: () -> Unit = {},
    onSwipeStart: () -> Unit = {},
    onSwipe: (Float) -> Unit = {},
    onSwipeEnd: (Float, Float) -> Unit = { _, _ -> },
    onDragStart: () -> Unit = {},
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: (interrupted: Boolean) -> Unit = {},
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current

    // 块里用到的每个值与回调都必须经 rememberUpdatedState（见 KDoc 第 1 条坑）
    val currentTap by rememberUpdatedState(onTap)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentCollapse by rememberUpdatedState(onCollapse)
    val currentDelete by rememberUpdatedState(onDelete)
    val currentSwipeStart by rememberUpdatedState(onSwipeStart)
    val currentSwipe by rememberUpdatedState(onSwipe)
    val currentSwipeEnd by rememberUpdatedState(onSwipeEnd)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragBy by rememberUpdatedState(onDragBy)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentFraction by rememberUpdatedState(revealFraction)
    val currentRevealed by rememberUpdatedState(revealed)
    val currentAnyRevealed by rememberUpdatedState(anyRevealed)
    val currentRevealWidth by rememberUpdatedState(revealWidthPx)
    val currentDragEnabled by rememberUpdatedState(dragEnabled)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                // 网格里两个方向都要跟手：横向是换列、纵向是换行（服务器清单用得上）
                translationX = if (dragging) dragOffsetX else 0f
                translationY = if (dragging) dragOffsetY else 0f
                val scale = if (dragging) ServerListGestures.DRAG_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            // 按钮从行尾压进来时，压出行外的那部分要被裁掉（不裁就会盖到隔壁行上去）
            .clipToBounds()
            .pointerInput(key) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                var lastTapUptime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 已露出的按钮是**独立可点节点**：落在那儿就整块退出，把事件让给按钮自己
                    if (
                        ServerListGestures.isOnRevealedDeleteButton(
                            xPx = down.position.x,
                            rowWidthPx = size.width.toFloat(),
                            revealWidthPx = currentRevealWidth,
                            revealed = currentRevealed,
                        )
                    ) {
                        return@awaitEachGesture
                    }
                    val velocity = VelocityTracker()
                    var mode = 0
                    var dx = 0f
                    var dy = 0f
                    // 基准 = 起手时已盖了多少：已露出的行往回拖（右滑）就是收起，不用另立一套状态
                    var baseFraction = 0f
                    var liveFraction = 0f
                    var lastUptime = down.uptimeMillis
                    var completed = false
                    try {
                        while (true) {
                            // 长按是"不动也得到"的判定 → 带超时等地等：超时无人叫醒 = 长按到位
                            val remaining = longPressMs - (lastUptime - down.uptimeMillis)
                            val event = if (mode == 0) {
                                withTimeoutOrNull(remaining.coerceAtLeast(0L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // 已露出这一行：长按**先收起**，本次手势不再接着做别的（一次按住只做一件事）
                                if (currentRevealed) {
                                    currentCollapse()
                                    break
                                }
                                if (currentAnyRevealed) currentCollapse()
                                if (currentDragEnabled) {
                                    mode = 2
                                    currentDragStart()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    continue
                                }
                                // 不排序的清单：长按 = 打开菜单（好友清单的编辑 / 删除）
                                currentLongPress?.invoke()
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastUptime = change.uptimeMillis
                            velocity.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                completed = true
                                when (mode) {
                                    1 -> currentSwipeEnd(liveFraction, velocity.calculateVelocity().x)
                                    2 -> currentDragEnd(false)
                                    else -> {
                                        val tapped = abs(dx) < slop && abs(dy) < slop
                                        if (tapped && currentAnyRevealed) {
                                            // 已露出时点一下**只收起**（多半是"算了不删"），也不进编辑
                                            currentCollapse()
                                            lastTapUptime = 0L
                                        } else if (tapped && change.uptimeMillis - lastTapUptime <= doubleTapMs) {
                                            lastTapUptime = 0L
                                            // 双击只是兼容：没给 onDoubleTap 就退回单击（单击已是主口径）
                                            (currentDoubleTap ?: currentTap)?.invoke()
                                        } else if (tapped) {
                                            lastTapUptime = change.uptimeMillis
                                            // 单击立刻进编辑（不再等双击窗口）：
                                            // 双击窗口内若真来了第二下，上面那支会再执行一次（幂等：编辑页只会开一次）
                                            currentTap?.invoke()
                                        }
                                    }
                                }
                                break
                            }
                            // 位移直接"当前位置 - 上一次位置"：`positionChange()` 在部分 Compose 版本里
                            // 是「已被消费」的布尔成员（不是函数），换个写法省得踩版本差异
                            val delta = change.position - change.previousPosition
                            when (mode) {
                                0 -> {
                                    dx += delta.x
                                    dy += delta.y
                                    if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                        mode = 1
                                        baseFraction = currentFraction
                                        currentSwipeStart()
                                        change.consume()
                                    } else if (abs(dy) > slop) {
                                        break // 判给列表滚动：不消费事件，本行手势就此退出
                                    }
                                }
                                1 -> {
                                    dx += delta.x
                                    change.consume()
                                    liveFraction = ServerListGestures.revealFraction(
                                        dxPx = dx,
                                        baseFraction = baseFraction,
                                        revealWidthPx = currentRevealWidth,
                                    )
                                    currentSwipe(liveFraction)
                                }
                                else -> {
                                    change.consume()
                                    currentDragBy(delta.x, delta.y)
                                }
                            }
                        }
                    } finally {
                        // 手势被系统打断（来电 / 切后台 / 转屏）→ 按当前位置收尾，不留半拉子状态
                        if (!completed) {
                            if (mode == 2) currentDragEnd(true)
                            if (mode == 1) currentSwipeEnd(liveFraction, 0f)
                        }
                    }
                }
            },
    ) {
        content()
        // 删除按钮画在内容**之后** = 盖在行上；位移由露出比例驱动（行本身一动不动）。
        // **只在压出来时才进组合**：语义节点不能常驻，否则读屏会念出一个眼睛看不见的按钮
        // （这一点是服务器清单先踩出来的，抽组件时一并继承）。
        if (revealFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { revealWidthPx.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = revealWidthPx * (1f - revealFraction)
                    },
                contentAlignment = Alignment.Center,
            ) {
                action()
            }
        }
    }
}
