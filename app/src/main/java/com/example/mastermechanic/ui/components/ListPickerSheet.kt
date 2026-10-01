package com.example.mastermechanic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **行高**（2026-10-01 定死；取 **48dp**）。
 *
 * 为什么是 48 而不是更好看的 44：**48dp 是 Material 的最小触控目标**（验收 V6 要求 ≥48dp）
 * —— 再矮点更好看，但会牺牲可点性。
 *
 * ⚠ **定死**（而不是让内容撑）是这次修"滑动时高度抖动"的关键：高度由 [ListPickerSheet] 按
 * `行数 × 本值` **算出来**，与滚动位置无关 ⇒ 滑到哪高度都一样。
 */
private val PICKER_ROW_HEIGHT: Dp = 48.dp

/**
 * 列表区的**最大高度**（取 **240dp** = 正好 5 行）。
 *
 * 用户 2026-10-01："弹层**高度**和列表**行距**太高了" ⇒ 上限从 360 收到 240（少占半屏），
 * 行距从 `ListItem` 默认（≈72dp 含内边距）收到 48dp。超过 5 行的清单**在里面滑**。
 */
private val PICKER_LIST_MAX_HEIGHT: Dp = 240.dp

/** 标题与列表之间的留白（弹层整体高度也因此矮下来）。 */
private val PICKER_TITLE_PADDING: Dp = 12.dp

/**
 * **从列表里选一个** —— 底部向上弹出的可滑动选择器（2026-10-01 用户口径）。
 *
 * ## 为什么不用 `DropdownMenu`（用户真机反馈）
 *
 * 用户："**区服列表和好友列表展示方式不够友好，区服名太长会覆盖其他元素**，
 * 改为友好的底部向上弹出、可滑动选择的方式。"
 *
 * `DropdownMenu` 的两个硬伤都是它自己的定位方式造成的：① **贴着锚点**弹出、宽度按最长那一项撑开
 * ⇒ 长区服名会横着压住别的元素；② 贴锚点时会被挤到屏幕上缘，可点区域跟着飘。
 * 底部弹层没有这两个问题：**整宽**（长名字在自己那一行里截断，压不到任何东西）、高度可预期、内容多可滑。
 *
 * ## 高度为什么"算"而不是"量"（用户第二次反馈：滑动时高度抖动 + 太高太松）
 *
 * 第一版把列表写成 `heightIn(max = 360.dp)` + 默认 `ListItem` 行 ⇒ 两个毛病：
 * - **抖动**：弹层高度跟着**内容测量结果**走，而 `LazyColumn` 在滚动中的测量与
 *   `ModalBottomSheet` 的手势折叠互相影响 ⇒ 一边滑、高度一边变 ✗；
 * - **太高太松**：`ListItem` 的默认内边距把每行撑到 ≈72dp，5 行就 360dp 顶满半屏 ✗。
 *
 * ⇒ 现在：**行高定死 48dp**、**列表高度 = min(行数 × 48dp, 240dp)**（[PICKER_ROW_HEIGHT] /
 * [PICKER_LIST_MAX_HEIGHT]），并且 `skipPartiallyExpanded = true`（不让弹层停在"半展开"那个
 * 会与内部滚动抢手势的中间态）。**高度与滚动位置无关** ⇒ 怎么滑都不抖；
 * **短清单也不会留一大片空白**（高度按行数算，1 项就 48dp）。
 *
 * ## 三条口径（免得被"顺手改回下拉 / 改回 ListItem"）
 *
 * 1. **每行单行 + 省略号**：[PICKER_ROW_HEIGHT] 是固定行高，长名字在整宽的弹层里按尾截断，
 *    **永远不会压到别的元素**；当前值在下面的触发按钮上还会完整显示（那里也是整宽）；
 * 2. **列表限高 + 内部滚动**：超长清单不把弹层撑满，用户始终看得见上下文；
 * 3. **当前值高亮 + 勾**：一眼看出"现在是哪一个"。
 *
 * @param title 弹层标题（用调用方那一条的字段名，例如「区服」/「拜访好友」）
 * @param options 候选项（来自清单，**不是手打**）
 * @param selected 当前值（高亮用；不在列表里也没关系）
 * @param onPick 选中某一项（调用方负责关弹层）
 * @param onDismiss 下滑 / 点外部 / 返回键关闭
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = PICKER_TITLE_PADDING),
        )
        HorizontalDivider()
        // **高度自己算，不靠测量**：行数 × 行高（封顶 5 行）⇒ 滑动时高度恒定
        val listHeight = PICKER_ROW_HEIGHT * options.size
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (listHeight > PICKER_LIST_MAX_HEIGHT) PICKER_LIST_MAX_HEIGHT else listHeight),
        ) {
            items(items = options, key = { it }) { option ->
                PickerRow(
                    option = option,
                    selected = option == selected,
                    onPick = { onPick(option) },
                )
            }
        }
        // 底部只留一点缝：手势条那一段由 `ModalBottomSheet` 自己处理 inset（原来多留了 28dp，白占高度）
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 一行（自绘，不用 `ListItem` —— 它的默认内边距正是"行距太高"的来源）。 */
@Composable
private fun PickerRow(option: String, selected: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PICKER_ROW_HEIGHT)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option,
            style = MaterialTheme.typography.bodyLarge,
            // 口径 1：单行 + 省略号（固定行高装不下第二行；整宽弹层里这条也够宽了）
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
