package com.example.mastermechanic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * **行高**（**40dp**；两轮真机反馈一路收下来：`ListItem` 默认 ≈72dp → 48 → 40）。
 *
 * ⚠ 口径冲突如实记：Material 的触控目标建议 **≥48dp**（验收 V6 引用那条），用户明确要更紧凑 ⇒ 40dp
 * （≈8mm）。**V6 走查时一起确认**，真机难点就回 44 / 48。
 */
private val WHEEL_ROW_HEIGHT: Dp = 40.dp

/**
 * 滚轮里**同时看到的行数**（取 **5**）。
 *
 * 必须是**奇数** —— 中间那一行才是"当前选中"（用户 2026-10-01 的口径：
 * "**列表拖动时，中间位置代表选中该行**"）。5 行 = 上下各两行陪衬，中间一行高亮。
 */
private const val WHEEL_VISIBLE_ROWS = 5

/**
 * 上下各留半个滚轮高度的**空白**，好让**第一行 / 最后一行也能滚到中间**。
 *
 * 没有它的话首尾两项永远到不了中线 ⇒ 选中项是首尾时，用户一打开就看到"选中项不在中间"。
 */
private val WHEEL_EDGE_PADDING: Dp = WHEEL_ROW_HEIGHT * (WHEEL_VISIBLE_ROWS / 2)

/** 标题与滚轮之间的留白（越小弹层越矮）。 */
private val WHEEL_TITLE_PADDING: Dp = 8.dp

/** 标题的**上边距**：拖手已去掉（`dragHandle = null`），不留会贴着弹层顶边像被裁掉。 */
private val WHEEL_TITLE_TOP_PADDING: Dp = 16.dp

/** 行的左右内边距。 */
private val WHEEL_ROW_PADDING: Dp = 16.dp

/**
 * **从列表里选一个** —— 底部向上弹出的**滚轮选择器**（2026-10-01 用户口径）。
 *
 * ## 长什么样、怎么用
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 区服                            │   ← 标题
 * ├──────────────────────────────┤
 * │        （上两行，陪衬）           │
 * │ ▓▓▓▓▓ 中间这一行 = 当前选中 ▓▓▓▓▓ │   ← 中线高亮
 * │        （下两行，陪衬）           │
 * ├──────────────────────────────┤
 * │ 将选：莲动渔舟      [取消] [确定]  │
 * └──────────────────────────────┘
 * ```
 *
 * - **打开时直接定位到当前选中的那一行**（用户 2026-10-01 要求）：初始滚动位置按 `selected` 算好，
 *   并把中线吸附到它上面 ⇒ 不用手动去找"现在选的是哪个"；
 * - **中间那一行 = 选中行**（用户原话："**列表拖动时，中间位置代表选中该行**"）⇒
 *   滚动带**中线吸附**（`SnapPosition.Center`，停下时总是整行对齐中线）；
 * - **点某一行 = 把它滚到中线**（不是立刻提交 —— 滚轮的心智是"停下即选中"，提交统一走「确定」）；
 * - **「将选：X」** 把当前中线那一行**用文字再报一遍**（弹层里的字可能被截断/被手指挡着，
 *   而这一句永远看得清）；**「确定」才真正生效**，「取消」/ 点外部 / 返回键都不改值。
 *
 * ## 为什么不用 `DropdownMenu`（用户第一次反馈）
 *
 * `DropdownMenu` 贴着锚点弹出、宽度按最长项撑开 ⇒ 长区服名会横着压住别的元素；贴锚点还会被挤到屏幕上缘。
 * 底部弹层**整宽** ⇒ 长名字只在自己那一行里截断，压不到任何东西。
 *
 * ## 为什么关掉弹层自己的拖拽 + 去掉拖手（用户第三 / 第四次反馈）
 *
 * `ModalBottomSheet` 的手势是**复合的**（列表滚到头继续拖 = 拖弹层，M3 用嵌套滚动接在一起）⇒
 * 手指在列表里上下滑会把弹层带着回缩/展开 ✗。所以 `sheetGesturesEnabled = false`；
 * 而**拖手是"可以拖"的视觉暗示**，拖不动还留着就是骗人去拖 ⇒ 一并 `dragHandle = null`。
 *
 * ## 高度为什么"算"而不是"量"（用户第二次反馈）
 *
 * 行高与滚轮高度都是常量（[WHEEL_ROW_HEIGHT] × [WHEEL_VISIBLE_ROWS]）⇒ 与滚动位置无关，滑不抖；
 * 也不用 `ListItem`（它默认内边距 ≈72dp 正是"行距太高"的来源）。
 *
 * @param title 弹层标题（调用方那一条的字段名，例如「区服」/「拜访好友」）
 * @param options 候选项（来自清单，**不是手打**）
 * @param selected 当前值（**打开时定位到它**；不在列表里就从第一行开始）
 * @param onPick 点「确定」时给出**中线那一行**（调用方负责关弹层）
 * @param onDismiss 取消 / 点弹层外部 / 系统返回（**都不改值**）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListPickerSheet(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedIndex = options.indexOf(selected).takeIf { it >= 0 } ?: 0
    // **打开就定位到选中行**：初始位置把它放在中线（上方留两行的量，由 contentPadding 提供）
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - WHEEL_VISIBLE_ROWS / 2).coerceAtLeast(0),
    )
    val snap = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)
    val scope = rememberCoroutineScope()

    // **中线那一行**：取"离视口中心最近"的那一项（比 `firstVisibleItemIndex ± 常数` 稳：
    // 首尾附近、或列表比滚轮还短时，那个常数就不成立了）
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
                ?.index
                ?: selectedIndex
        }
    }
    val centerValue = options.getOrNull(centerIndex) ?: selected

    // ⚠ **不要在这里再补一句 `scrollToItem(selectedIndex)`**（写错过一次，编译能过但行为错）：
    // `scrollToItem(index)` 是"**把这一项置顶**"的语义 ⇒ 选中行会跑到**视口顶部**而不是中线，
    // 中线高亮带于是指着**上一行**（用户一打开就看到"选中的不是我"）。
    // 定位只由 `initialFirstVisibleItemIndex = selected - (可见行数/2)` 负责：
    // 让 `selected` 落在第 (可见行数/2 + 1) = **第 3 格**，也就是滚轮的中线。
    // （首尾两项走不到中线的问题由 `contentPadding` = 各半屏解决，见 [WHEEL_EDGE_PADDING]。）

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = WHEEL_ROW_PADDING,
                end = WHEEL_ROW_PADDING,
                top = WHEEL_TITLE_TOP_PADDING,
                bottom = WHEEL_TITLE_PADDING,
            ),
        )
        HorizontalDivider()

        if (options.isEmpty()) {
            // 理论上不会走到（调用方在清单为空时把触发按钮禁用了）；真到了也不给一个空白弹层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT * WHEEL_VISIBLE_ROWS),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW_HEIGHT * WHEEL_VISIBLE_ROWS),
            ) {
                // 中线高亮带（在列表**下面**一层：文字盖在它上面才读得清）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WHEEL_ROW_HEIGHT)
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                )
                LazyColumn(
                    state = listState,
                    flingBehavior = snap,
                    contentPadding = PaddingValues(vertical = WHEEL_EDGE_PADDING),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items = options, key = { _, option -> option }) { index, option ->
                        WheelRow(
                            option = option,
                            atCenter = index == centerIndex,
                            originallySelected = index == selectedIndex,
                            onClick = {
                                // 点一行 = 把它滚到中线（滚轮的心智是"停下即选中"，提交统一走「确定」）
                                scope.launch { listState.animateScrollToItem(index) }
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WHEEL_ROW_PADDING, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.picker_to_pick, centerValue),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.picker_cancel)) }
            TextButton(
                onClick = { onPick(centerValue) },
                enabled = options.isNotEmpty(),
            ) {
                Text(stringResource(R.string.picker_confirm))
            }
        }
    }
}

/**
 * 滚轮里的一行。
 *
 * 两种"标记"是**两件事**，别混：`atCenter` = 现在停在中线上（即将选它）；`originallySelected` = 打开弹层时
 * 存着的那个值（给一个 ✓，好让用户看清"我从哪来的"）。
 */
@Composable
private fun WheelRow(
    option: String,
    atCenter: Boolean,
    originallySelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WHEEL_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = WHEEL_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (atCenter) FontWeight.SemiBold else FontWeight.Normal,
            color = if (atCenter) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (originallySelected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
