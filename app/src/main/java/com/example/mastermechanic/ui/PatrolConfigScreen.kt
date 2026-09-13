package com.example.mastermechanic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.patrol.PatrolConfig
import com.example.mastermechanic.patrol.PatrolConfigStore
import com.example.mastermechanic.patrol.PatrolItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 巡查配置页（M3-T3-3 / FR-03）：有序的「区服名称 + 目标好友名称」列表，支持
 * 增 / 删 / 改 / 调整顺序 / 整体清空。
 *
 * 口径（ADR-006）：
 * - **盘上文件是唯一数据源**——每次编辑都是「读盘 → 改 → 落盘 → 重新读盘」，界面显示的就是落盘的结果；
 *   写入失败则**不**更新界面并明确提示（不制造"看起来改了、其实没保存"的假象）；
 * - 配置解析失败（文件被改坏 / 别的文件）→ 显示原因 + 「清空重建」入口，**不静默变成空列表**。
 *
 * 名称只做"不得为空"的**格式**校验；能否唯一匹配到界面元素属 M4 的名称定位（红线 3）。
 */
@Composable
fun PatrolConfigRoute(resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reloadTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    // 正在编辑的项：null = 新增；否则 = 该项序号
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    // 每次进入页面 / 每次落盘后重新读盘（盘上文件 = 唯一数据源）
    val state = remember(resumeTick, reloadTick) { loadPatrolState(context) }

    fun applyEdit(transform: (PatrolConfig) -> PatrolConfig) {
        val current = (state as? PatrolState.Loaded)?.config ?: PatrolConfig.EMPTY
        val next = transform(current)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { PatrolConfigStore.save(context, next) }
            }
                .onSuccess {
                    message = null
                    reloadTick++
                }
                .onFailure {
                    message = context.getString(
                        R.string.patrol_save_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    fun rebuildEmpty() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { PatrolConfigStore.save(context, PatrolConfig.EMPTY) }
            }
                .onSuccess {
                    message = null
                    reloadTick++
                }
                .onFailure {
                    message = context.getString(
                        R.string.patrol_save_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    PatrolConfigScreen(
        state = state,
        message = message,
        onBack = onBack,
        onAdd = {
            editingIndex = null
            editorOpen = true
        },
        onEdit = { index ->
            editingIndex = index
            editorOpen = true
        },
        onMove = { index, delta -> applyEdit { it.move(index, delta) } },
        onDelete = { index -> applyEdit { it.remove(index) } },
        onClear = { clearing = true },
        onRebuild = { rebuildEmpty() },
    )

    if (editorOpen) {
        val loaded = (state as? PatrolState.Loaded)?.config
        val initial = editingIndex?.let { loaded?.items?.getOrNull(it) }
        PatrolItemDialog(
            isNew = editingIndex == null,
            initial = initial,
            onDismiss = { editorOpen = false },
            onConfirm = { serverName, friendName ->
                val item = PatrolItem.of(serverName, friendName)
                val index = editingIndex
                if (index == null) {
                    applyEdit { it.add(item) }
                } else {
                    applyEdit { it.update(index, item) }
                }
                editorOpen = false
            },
        )
    }

    if (clearing) {
        val count = (state as? PatrolState.Loaded)?.config?.size ?: 0
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.patrol_clear_title)) },
            text = { Text(stringResource(R.string.patrol_clear_body, count)) },
            confirmButton = {
                Button(
                    onClick = {
                        applyEdit { it.clear() }
                        clearing = false
                    },
                ) {
                    Text(stringResource(R.string.patrol_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) {
                    Text(stringResource(R.string.patrol_cancel))
                }
            },
        )
    }
}

/** 巡查配置页的读取结果：加载成功（含空列表）或解析失败（必须显式提示，ADR-006）。 */
sealed interface PatrolState {

    data class Loaded(val config: PatrolConfig) : PatrolState

    data class Broken(val reason: String) : PatrolState
}

private fun loadPatrolState(context: android.content.Context): PatrolState = try {
    PatrolState.Loaded(PatrolConfigStore.load(context) ?: PatrolConfig.EMPTY)
} catch (e: IllegalArgumentException) {
    PatrolState.Broken(e.message ?: e.javaClass.simpleName)
}

@Composable
fun PatrolConfigScreen(
    state: PatrolState,
    message: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onClear: () -> Unit,
    onRebuild: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.patrol_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.patrol_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (state) {
                is PatrolState.Broken -> {
                    Text(
                        text = stringResource(R.string.patrol_load_failed, state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.patrol_rebuild))
                    }
                }
                is PatrolState.Loaded -> {
                    Text(
                        text = stringResource(R.string.patrol_list_title, state.config.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val items = state.config.items
                    if (items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.patrol_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        items.forEachIndexed { index, item ->
                            PatrolItemCard(
                                index = index,
                                item = item,
                                lastIndex = items.lastIndex,
                                onEdit = { onEdit(index) },
                                onMoveUp = { onMove(index, -1) },
                                onMoveDown = { onMove(index, +1) },
                                onDelete = { onDelete(index) },
                            )
                        }
                        Text(
                            text = stringResource(R.string.patrol_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.patrol_add))
                    }
                    if (items.isNotEmpty()) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.patrol_clear))
                        }
                    }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.patrol_back))
            }
        }
    }
}

@Composable
private fun PatrolItemCard(
    index: Int,
    item: PatrolItem,
    lastIndex: Int,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${stringResource(R.string.patrol_index, index + 1)}　${item.serverName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.patrol_item_friend, item.friendName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.patrol_edit)) }
                TextButton(onClick = onMoveUp, enabled = index > 0) {
                    Text(stringResource(R.string.patrol_move_up))
                }
                TextButton(onClick = onMoveDown, enabled = index < lastIndex) {
                    Text(stringResource(R.string.patrol_move_down))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.patrol_delete)) }
            }
        }
    }
}

/**
 * 新增 / 编辑对话框：两个名称框 + 「保存」。
 *
 * 校验口径与标定页一致（用户 2026-09-13）：**保存按钮始终可点**，点了才报「缺什么」——
 * 置灰按钮收不到点击，"为什么不能点"只能靠猜；这里又不是"点了会写坏东西"的危险动作。
 */
@Composable
private fun PatrolItemDialog(
    isNew: Boolean,
    initial: PatrolItem?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var serverName by remember { mutableStateOf(initial?.serverName.orEmpty()) }
    var friendName by remember { mutableStateOf(initial?.friendName.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val requiredText = stringResource(R.string.patrol_field_required)
    val separatorText = stringResource(R.string.patrol_field_separator)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.patrol_dialog_add else R.string.patrol_dialog_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = serverName,
                    onValueChange = {
                        serverName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.patrol_field_server)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = friendName,
                    onValueChange = {
                        friendName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.patrol_field_friend)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val server = serverName.trim()
                    val friend = friendName.trim()
                    when {
                        server.isEmpty() || friend.isEmpty() -> error = requiredText
                        !PatrolConfig.isValidName(server) || !PatrolConfig.isValidName(friend) ->
                            error = separatorText
                        else -> onConfirm(serverName, friendName)
                    }
                },
            ) {
                Text(stringResource(R.string.patrol_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.patrol_cancel)) }
        },
    )
}
