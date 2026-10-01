package com.example.mastermechanic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R

/**
 * **行高**（2026-10-01 定死，**40dp**）。
 *
 * ⚠ **两轮真机反馈一路收下来**：`ListItem` 默认 ≈72dp → 48dp → **40dp**
 * （用户第二次："列表还是**不够紧凑**"）。
 *
 * ⚠ 口径冲突如实记在这里：**Material 的触控目标建议是 ≥48dp**（验收 V6 引用那条），
 * 而用户明确要更紧凑 ⇒ 取 40dp（约 8mm，实际点起来仍够用）。**V6 走查时把这一条一起确认**：
 * 若真机上发现难点，就回到 44 或 48。
 *
 * ⚠ **定死**（而不是让内容撑）是修"滑动时高度抖动"的关键：高度由 [ListPickerSheet] 按
 * `行数 × 本值` **算出来**，与滚动位置无关 ⇒ 滑到哪高度都一样。
 */
private val PICKER_ROW_HEIGHT: Dp = 40.dp

/**
 * 列表区的**最大高度**（取 **200dp** = 正好 5 行）。
 *
 * 演变：360 → 240 → **200**（随行高 48 → 40 一起收；用户两次都说"太高 / 不够紧凑"）。
 * 超过 5 行的清单**在里面滑**。
 */
private val PICKER_LIST_MAX_HEIGHT: Dp = 200.dp

/** 标题与列表之间的留白（越小弹层越矮）。 */
private val PICKER_TITLE_PADDING: Dp = 8.dp

/** 行的左右内边距（20 → 16：40dp 的行高配 20 的左右会显得"空"）。 */
private val PICKER_ROW_PADDING: Dp = 16.dp

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
 * ## 为什么把弹层自己的拖拽手势**关掉**（用户第三次反馈：列表滑动会带动弹层）
 *
 * 用户："**列表滑动会触发弹层的滑动，触发弹窗回缩和展开导致出现抖动。**"
 *
 * 这是 `ModalBottomSheet` 的默认行为：**手势是"复合"的** —— 往上拖先滚列表、到底/到顶后**继续拖就变成拖弹层**
 * （M3 用嵌套滚动把两者接在一起）。弹层里只有"一屏列表"时，这个衔接非常容易被误触发：
 * 手指在列表里上下滑，弹层就跟着**回缩 / 再展开** ⇒ 看起来就是抖动 ✗。
 *
 * ⇒ [sheetGesturesEnabled] = **false**：**弹层不再响应拖拽**，列表怎么滑都只影响列表。
 * 关闭方式改为**「取消」按钮 / 点弹层外部 / 系统返回**（前两条本来就成立，所以补一个显式的取消按钮，
 * 免得用户以为"关不掉"）。
 *
 * @param title 弹层标题（用调用方那一条的字段名，例如「区服」/「拜访好友」）
 * @param options 候选项（来自清单，**不是手打**）
 * @param selected 当前值（高亮用；不在列表里也没关系）
 * @param onPick 选中某一项（调用方负责关弹层）
 * @param onDismiss 点弹层外部 / 系统返回 / 「取消」关闭（**下滑关闭已按上面的理由禁用**）
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // **关掉弹层自身的拖拽**（见上方说明）：列表滑动不再带动弹层回缩/展开
        sheetGesturesEnabled = false,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                start = PICKER_ROW_PADDING,
                end = PICKER_ROW_PADDING,
                bottom = PICKER_TITLE_PADDING,
            ),
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
        // 下滑关闭禁用后，给一个**显式的**关闭入口（免得用户以为关不掉）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PICKER_ROW_PADDING, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.picker_cancel))
            }
        }
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
            .padding(horizontal = PICKER_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option,
            // 40dp 的行高配 bodyMedium 才不挤（bodyLarge 在小屏上会显得"字撑满行"）
            style = MaterialTheme.typography.bodyMedium,
            // 口径 1：单行 + 省略号（固定行高装不下第二行；整宽弹层里这条也够宽了）
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
