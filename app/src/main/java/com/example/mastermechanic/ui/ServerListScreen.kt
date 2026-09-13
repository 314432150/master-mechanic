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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mastermechanic.R
import com.example.mastermechanic.servers.ServerEntry
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器清单页（M3-T3-9 / FR-10）：有序的「区服名称 + 角色名 + 等级」列表，
 * 支持增 / 删 / 改 / 调整顺序 / 整体清空。
 *
 * 口径与巡查配置页（[PatrolConfigRoute]）保持一致（ADR-006）：
 * - **盘上文件是唯一数据源**——每次编辑都是「读盘 → 改 → 落盘 → 重新读盘」；
 *   写入失败则**不**更新界面并明确提示（不制造"看起来改了、其实没保存"的假象）；
 * - 解析失败（文件被改坏 / 别的文件）→ 显示原因 + 「清空重建」入口，**不静默变成空列表**。
 *
 * 只做**格式**校验：区服名称必填（它是换号时唯一用于定位的字段），角色名与等级可留空。
 */
@Composable
fun ServerListRoute(resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var reloadTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    // 正在编辑的条目：null = 新增；否则 = 该条序号
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    // 每次进入页面 / 每次落盘后重新读盘（盘上文件 = 唯一数据源）
    val state = remember(resumeTick, reloadTick) { loadServerListState(context) }

    fun applyEdit(transform: (ServerList) -> ServerList) {
        val current = (state as? ServerListState.Loaded)?.list ?: ServerList.EMPTY
        val next = transform(current)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ServerListStore.save(context, next) }
            }
                .onSuccess {
                    message = null
                    reloadTick++
                }
                .onFailure {
                    message = context.getString(
                        R.string.server_save_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    ServerListScreen(
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
        onRebuild = { applyEdit { it.clear() } },
    )

    if (editorOpen) {
        val loaded = (state as? ServerListState.Loaded)?.list
        val initial = editingIndex?.let { loaded?.entries?.getOrNull(it) }
        ServerEntryEditor(
            isNew = editingIndex == null,
            initial = initial,
            onDismiss = { editorOpen = false },
            onConfirm = { serverName, characterName, level ->
                val entry = ServerEntry.of(serverName, characterName, level)
                val index = editingIndex
                if (index == null) {
                    applyEdit { it.add(entry) }
                } else {
                    applyEdit { it.update(index, entry) }
                }
                editorOpen = false
            },
        )
    }

    if (clearing) {
        val count = (state as? ServerListState.Loaded)?.list?.size ?: 0
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.server_clear_title)) },
            text = { Text(stringResource(R.string.server_clear_body, count)) },
            confirmButton = {
                Button(
                    onClick = {
                        applyEdit { it.clear() }
                        clearing = false
                    },
                ) {
                    Text(stringResource(R.string.server_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) {
                    Text(stringResource(R.string.server_cancel))
                }
            },
        )
    }
}

/** 服务器清单页的读取结果：加载成功（含空清单）或解析失败（必须显式提示，ADR-006）。 */
sealed interface ServerListState {

    data class Loaded(val list: ServerList) : ServerListState

    data class Broken(val reason: String) : ServerListState
}

private fun loadServerListState(context: android.content.Context): ServerListState = try {
    ServerListState.Loaded(ServerListStore.load(context) ?: ServerList.EMPTY)
} catch (e: IllegalArgumentException) {
    ServerListState.Broken(e.message ?: e.javaClass.simpleName)
}

@Composable
fun ServerListScreen(
    state: ServerListState,
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
                text = stringResource(R.string.server_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.server_subtitle),
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
                is ServerListState.Broken -> {
                    Text(
                        text = stringResource(R.string.server_load_failed, state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.server_rebuild))
                    }
                }
                is ServerListState.Loaded -> {
                    Text(
                        text = stringResource(R.string.server_list_title, state.list.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val entries = state.list.entries
                    if (entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.server_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        entries.forEachIndexed { index, entry ->
                            ServerEntryCard(
                                index = index,
                                entry = entry,
                                lastIndex = entries.lastIndex,
                                onEdit = { onEdit(index) },
                                onMoveUp = { onMove(index, -1) },
                                onMoveDown = { onMove(index, +1) },
                                onDelete = { onDelete(index) },
                            )
                        }
                        Text(
                            text = stringResource(R.string.server_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.server_add))
                    }
                    if (entries.isNotEmpty()) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.server_clear))
                        }
                    }
                }
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.server_back))
            }
        }
    }
}

@Composable
private fun ServerEntryCard(
    index: Int,
    entry: ServerEntry,
    lastIndex: Int,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val unset = stringResource(R.string.server_field_unset)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${stringResource(R.string.server_index, index + 1)}　${entry.serverName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.server_item_role,
                    if (entry.hasCharacterName) entry.characterName else unset,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.server_item_level,
                    if (entry.hasLevel) entry.level else unset,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.server_edit)) }
                TextButton(onClick = onMoveUp, enabled = index > 0) {
                    Text(stringResource(R.string.server_move_up))
                }
                TextButton(onClick = onMoveDown, enabled = index < lastIndex) {
                    Text(stringResource(R.string.server_move_down))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.server_delete)) }
            }
        }
    }
}

/**
 * 新增 / 编辑「服务器」：**全屏编辑页**（不是对话框）。
 *
 * 为什么不用 AlertDialog：本机横屏的可用高度只有 360dp（3168×1440 @ 640dpi），
 * 三个输入框 + 标题 + 按钮放不进对话框——Material3 会把溢出的内容塞进内部滚动区并加一条分割线，
 * 用户看到的是"只有两个输入框"，第三个（等级）像是不存在（2026-09-14 真机实测）。
 * 全屏编辑页无论横竖屏都完整可见（与标定工作台同一做法）。
 *
 * 校验口径与巡查配置页一致：**保存按钮始终可点**，点了才报「缺什么」——
 * 置灰按钮收不到点击，"为什么不能点"只能靠猜。
 */
@Composable
private fun ServerEntryEditor(
    isNew: Boolean,
    initial: ServerEntry?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var serverName by remember { mutableStateOf(initial?.serverName.orEmpty()) }
    var characterName by remember { mutableStateOf(initial?.characterName.orEmpty()) }
    var level by remember { mutableStateOf(initial?.level.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val requiredText = stringResource(R.string.server_field_required)
    val separatorText = stringResource(R.string.server_field_separator)

    fun save() {
        val server = serverName.trim()
        when {
            server.isEmpty() -> error = requiredText
            !ServerList.isValidServerName(server) ||
                !ServerList.isValidOptionalField(characterName) ||
                !ServerList.isValidOptionalField(level) -> error = separatorText
            else -> onConfirm(serverName, characterName, level)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isNew) R.string.server_dialog_add else R.string.server_dialog_edit,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = serverName,
                    onValueChange = {
                        serverName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.server_field_server)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = characterName,
                    onValueChange = {
                        characterName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.server_field_role)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = level,
                    onValueChange = {
                        level = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.server_field_level)) },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.server_cancel))
                    }
                    Button(onClick = { save() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.server_confirm))
                    }
                }
            }
        }
    }
}
