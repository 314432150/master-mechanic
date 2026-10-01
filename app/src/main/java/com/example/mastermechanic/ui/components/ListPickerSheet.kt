package com.example.mastermechanic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 弹层里列表的最大高度：超过就**在里面滑**（不把弹层撑到满屏，上半屏始终留着上下文）。 */
private val SHEET_LIST_MAX_HEIGHT: Dp = 360.dp

/** 列表底部留白：「手势条 / 导航栏」会盖住最后一行，留出这一段就永远点得到。 */
private val SHEET_BOTTOM_SPACER: Dp = 28.dp

/**
 * **从列表里选一个** —— 底部向上弹出的可滑动选择器（2026-10-01 用户口径）。
 *
 * ## 为什么不用 `DropdownMenu`（用户真机反馈）
 *
 * 用户："**区服列表和好友列表展示方式不够友好，区服名太长会覆盖其他元素**，
 * 改为友好的底部向上弹出、可滑动选择的方式。"
 *
 * `DropdownMenu` 的两个硬伤都是它自己的定位方式造成的：
 * ① 它是**贴着锚点**弹出的浮层，宽度按最长那一项撑开 ⇒ 遇到很长的区服名就会**横着压住**别的元素；
 * ② 它贴着按钮，屏幕下半部分高时会被**挤到屏幕上边缘**，可点区域跟着飘。
 *
 * 底部弹层没有这两个问题：**整宽**（长名字在里面换行 / 截断，压不到任何东西）、
 * 从底边起、高度可预期，而且**内容多时可以滑动**（这正是用户说的"可滑动选择"）。
 *
 * ## 三条口径（写进实现，免得被"顺手改回下拉"）
 *
 * 1. **每行最多两行 + 省略号**：名字再长也只占自己的行 —— 这是"不覆盖其他元素"的直接保证；
 * 2. **列表区域限高 + 内部滚动**：超长清单不会把弹层撑满，用户始终看得见"我在选什么"；
 * 3. **当前值高亮**（[selected]）：一眼看出"现在是哪一个"，省得来回确认。
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SHEET_LIST_MAX_HEIGHT),
        ) {
            items(items = options, key = { it }) { option ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = option,
                            // 口径 1：**最多两行 + 省略号** ⇒ 名字再长也压不到别的元素
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = if (option == selected) {
                        // 口径 3：当前值高亮
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onPick(option) },
                )
            }
            item { Spacer(modifier = Modifier.height(SHEET_BOTTOM_SPACER)) }
        }
    }
}
