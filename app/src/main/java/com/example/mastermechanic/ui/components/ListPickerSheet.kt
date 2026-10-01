package com.example.mastermechanic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

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
 * 上下各留 [WHEEL_VISIBLE_ROWS] / 2 行的**空白**（= 两行高）。
 *
 * ## 这个留白同时决定了两件事（2026-10-01 在这里踩过一次，写清楚）
 *
 * 1. **首 / 末项也能滚到中线**：没有它的话，第一项永远到不了中线
 *    （用户一打开就是"选中的不在中间"）；
 * 2. **"第一个可见项"就等于"中线那一格"**：留白占满上面两格，
 *    所以第一个可见项正好落在第 3 格 —— 于是**初始定位直接把"选中下标"当作首项下标**即可
 *    （见 [ListPickerSheet] 里 `initialFirstVisibleItemIndex` 的注释，别再加 `- 2`）。
 */
private val WHEEL_EDGE_PADDING: Dp = WHEEL_ROW_HEIGHT * (WHEEL_VISIBLE_ROWS / 2)

/** 标题与滚轮之间的留白（越小弹层越矮）。 */
private val WHEEL_TITLE_PADDING: Dp = 8.dp

/** 标题的**上边距**：拖手已去掉（`dragHandle = null`），不留会贴着弹层顶边像被裁掉。 */
private val WHEEL_TITLE_TOP_PADDING: Dp = 16.dp

/** 行的左右内边距。 */
private val WHEEL_ROW_PADDING: Dp = 16.dp

/**
 * **从列表里选一个** —— 底部向上弹出的**滚轮选择器**（2026-10-01 用户口径，经五轮真机反馈定型）。
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 区服                            │   ← 标题（拖手已去掉，见下）
 * ├──────────────────────────────┤
 * │          克克花儿(阿娜雅)         │   ← 陪衬（次级色）
 * │ ▓▓ 莲动渔舟 ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │   ← 中线：加粗 + 主色 + 高亮带 = 当前选中
 * │          星月晚.(星月晚)          │   ← 陪衬
 * ├──────────────────────────────┤
 * │ （没有取消 / 确定 / 「将选」了）    │
 * └──────────────────────────────┘
 * ```
 *
 * ## 怎么提交（用户 2026-10-01："移除取消/确定/将选，因为外层还有一个保存按钮"）
 *
 * ⇒ **弹层里没有提交动作**，选中的那一刻就**实时回调** [onPick]（写进外层的草稿），
 * **落盘由外层那个「保存」按钮负责**。于是：
 * - **滚动**（停下后中线变了）⇒ 实时更新草稿，**弹层不关**（可以继续来回调）；
 * - **点某一行** ⇒ 立刻把该值回调出去并**关闭弹层**（"就是它了"的快捷路径）；
 * - **点弹层外部 / 系统返回** ⇒ 关掉，**草稿保持**（外层不保存就不生效 ⇒ 天然的"取消"）。
 *
 * ## 其余关键点（每条都对应一轮真机反馈）
 *
 * - **打开时中线就是当前值**：`initialFirstVisibleItemIndex = 选中下标`（配合 [WHEEL_EDGE_PADDING]
 *   的几何：第一个可见项就是中线那一格）；不再额外调 `scrollToItem`（它是"置顶"语义，会把
 *   选中行顶到视口顶部、高亮带指上一行 ✗，踩过一次）；
 * - **中线 = 选中**：`SnapPosition.Center` 吸附（停下时整行对齐）+ 中央高亮带 + 中线行加粗；
 * - **滑不抖**：滚轮高度与行高都是常量（[WHEEL_ROW_HEIGHT] × [WHEEL_VISIBLE_ROWS]），与滚动位置无关；
 * - **不用 `ListItem`**：它的默认内边距 ≈72dp 正是"行距太高"的来源；
 * - **关掉弹层自身的拖拽 + 去掉拖手**：`ModalBottomSheet` 的手势是**复合的**（列表滚到头继续拖 = 拖弹层
 *   ⇒ 手指在列表里滑会把弹层带着回缩/展开 ✗）；而拖手是"可以拖"的视觉暗示，拖不动还留着就是骗人去拖。
 *
 * @param title 弹层标题（调用方那一条的字段名，例如「区服」/「拜访好友」）
 * @param options 候选项（来自清单，**不是手打**）
 * @param selected 当前值（**打开时中线定位到它**）；不在列表里就从中线那一格的第一项开始
 * @param onPick 中线变化时**实时**给值（写进外层草稿）；点某一行也会调它（随后自动关闭）
 * @param onDismiss 点弹层外部 / 系统返回（草稿保持，是否生效由外层的保存决定）
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
    // **打开就定位到选中行**：`WHEEL_EDGE_PADDING` 把上面两格留成空白 ⇒ 第一个可见项**就是中线那一格**
    // ⇒ 首项下标 = 选中下标（⚠ 别再写 `- 2`：那会把中线指到选中值**上面两行**，真机报过这个 bug）。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val snap = rememberSnapFlingBehavior(lazyListState = listState, snapPosition = SnapPosition.Center)

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

    // **实时生效**：中线变了就把值交出去（写进外层草稿）。首次组合时值相同 ⇒ 空跑一次，无副作用。
    LaunchedEffect(centerValue) { onPick(centerValue) }

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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WHEEL_ROW_HEIGHT * WHEEL_VISIBLE_ROWS),
        ) {
            if (options.isEmpty()) {
                // 理论上不会走到（调用方在清单为空时把触发按钮禁用了）；真到了也不给一个空白弹层
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Box
            }
            // 中线高亮带（在列表**下面**一层：文字盖在它上面才读得清）。
            // ⚠ 必须作为 **Box 的直接子项 + `Alignment.Center`** 才是在垂直中线上 ——
            // 放进 `Column` 里会被排到顶部（写错过一次）。
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
                        onClick = {
                            // 点一行 = "就是它了" ⇒ 立刻回调并关闭（快捷路径；滚动那条路径不关弹层）
                            onPick(option)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** 滚轮里的一行：只有"在中线上"一种标记（**选中值末尾那个 ✓ 已按用户口径去掉**）。 */
@Composable
private fun WheelRow(option: String, atCenter: Boolean, onClick: () -> Unit) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
