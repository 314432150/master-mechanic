package com.example.mastermechanic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.friends.FriendListStore
import com.example.mastermechanic.preset.ServerChoice
import com.example.mastermechanic.preset.VisitPreset
import com.example.mastermechanic.preset.VisitPresetStore
import com.example.mastermechanic.servers.ServerListStore
import kotlinx.coroutines.launch

/**
 * **「拜访设置」页**（M5-U3，2026-10-01）：区服策略 + 拜访好友 ⇒ **保存**。
 *
 * ## 只保存，不发起（用户 2026-10-01 拍板）
 *
 * 用户原话："**U3 只负责保存，要跑就回游戏用悬浮窗点**"。页面底部那句提示把这件事说清楚
 * （[R.string.visit_settings_run_hint]）—— 否则用户改完设置会找"开始"按钮，找不到就以为坏了。
 * 口径同"要游戏在前台才能做的动作，入口留在游戏里"（见 ADR-009 修订）。
 *
 * ## 与悬浮窗共用同一份数据
 *
 * 读 / 写都是 `preset/visit.txt`（[VisitPresetStore]）；可执行性判据也只有 [VisitPreset.isReady] 一处
 * （本页只把结果翻成"该说哪一句"，见 [VisitSettingsLogic.blockedReason]）。
 * 所以"App 改完悬浮窗读到的是新值、反之亦然"是**结构上成立**的，不是靠两边同步。
 *
 * ## 选项来自两份清单（不是手打）
 *
 * 好友与区服都从「账号与好友」页的清单里**选**（`FriendListStore` / `ServerListStore`）——
 * 名字是跨文件的引用键（红线 3：运行时按名字全等定位），手打错一个字就会在跑号时"永远找不到"。
 * 清单为空时不是静默变灰，而是**说清去哪儿加**。
 */
@Composable
fun VisitSettingsRoute(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 草稿（用户正在编的）与"当前已生效的"分开存：保存失败时不能把生效值也改掉
    var draftChoice by remember { mutableStateOf(ServerChoice.NEXT) }
    var draftServer by remember { mutableStateOf("") }
    var draftFriend by remember { mutableStateOf("") }
    var effective by remember { mutableStateOf<VisitPreset?>(null) }
    var serverOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var friendOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 保存成功一次就自增 ⇒ 下面的 `LaunchedEffect` 重新读盘（草稿对齐刚落盘的值）。 */
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(resumeTick, reloadTick) {
        val preset = runCatching { VisitPresetStore.load(context) ?: VisitPreset.EMPTY }
            .getOrElse {
                // 损坏不静默降级（ADR-006）：说清读不出来，并让用户知道草稿是空白的
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.visit_settings_load_failed,
                            it.message ?: it.javaClass.simpleName,
                        ),
                    )
                }
                VisitPreset.EMPTY
            }
        serverOptions = runCatching {
            ServerListStore.load(context)?.entries.orEmpty().map { it.serverName }
        }.getOrDefault(emptyList())
        friendOptions = runCatching {
            FriendListStore.load(context)?.entries.orEmpty().map { it.name }
        }.getOrDefault(emptyList())
        draftChoice = preset.serverChoice
        draftServer = preset.serverName
        draftFriend = preset.friendName
        effective = preset
    }

    val pickHint = stringResource(R.string.visit_settings_pick)
    val draft = VisitSettingsLogic.draft(draftChoice, draftServer, draftFriend)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 当前生效（一眼看出"现在跑的是哪一套"）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentText(effective),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.visit_settings_run_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ① 区服策略
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.visit_settings_strategy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    StrategyOption(
                        label = stringResource(R.string.visit_settings_strategy_next),
                        selected = draftChoice == ServerChoice.NEXT,
                        onSelect = {
                            draftChoice = ServerChoice.NEXT
                        },
                    )
                    StrategyOption(
                        label = stringResource(R.string.visit_settings_strategy_fixed),
                        selected = draftChoice == ServerChoice.FIXED,
                        onSelect = {
                            draftChoice = ServerChoice.FIXED
                        },
                    )
                    if (draftChoice == ServerChoice.FIXED) {
                        PickerRow(
                            label = stringResource(R.string.visit_settings_server),
                            value = draftServer,
                            pickHint = pickHint,
                            options = serverOptions,
                            emptyHint = stringResource(R.string.visit_settings_no_servers),
                            onPick = { draftServer = it },
                        )
                    }
                }
            }

            // ② 拜访好友
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PickerRow(
                        label = stringResource(R.string.visit_settings_friend),
                        value = draftFriend,
                        pickHint = pickHint,
                        options = friendOptions,
                        emptyHint = stringResource(R.string.visit_settings_no_friends),
                        onPick = { draftFriend = it },
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when (VisitSettingsLogic.blockedReason(draft)) {
                        VisitSettingsLogic.Blocked.FRIEND_MISSING -> scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.visit_settings_blocked_friend),
                            )
                        }

                        VisitSettingsLogic.Blocked.SERVER_MISSING -> scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.visit_settings_blocked_server),
                            )
                        }

                        VisitSettingsLogic.Blocked.NONE -> {
                            val failure = runCatching { VisitPresetStore.save(context, draft) }
                                .exceptionOrNull()
                            if (failure != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(
                                            R.string.visit_settings_save_failed,
                                            failure.message ?: failure.javaClass.simpleName,
                                        ),
                                    )
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.visit_settings_saved),
                                    )
                                }
                                reloadTick++
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.visit_settings_save))
            }
        }
    }
}

/** 「当前生效」那一句（没配过就直说，别显示成一串空白）。 */
@Composable
private fun currentText(effective: VisitPreset?): String = when {
    effective == null || !effective.isReady -> stringResource(R.string.visit_settings_current_none)
    effective.serverChoice == ServerChoice.FIXED -> stringResource(
        R.string.visit_settings_current_fixed,
        effective.serverName,
        effective.friendName,
    )

    else -> stringResource(R.string.visit_settings_current_next, effective.friendName)
}

/** 单选一行（策略两项）。整行可点：只点小圆点对拇指太苛刻。 */
@Composable
private fun StrategyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 从清单里**选**一个值（不是手打）。
 *
 * 清单为空时**不是静默变灰**：按钮禁用 + 下面一行说清"去哪儿加"（用户看到灰按钮最怕的就是
 * "为什么不能点"，而这里的原因只有一个：清单还是空的）。
 */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    pickHint: String,
    options: List<String>,
    emptyHint: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty()) {
                Text(text = value.ifEmpty { pickHint })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
        if (options.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
