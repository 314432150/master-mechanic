package com.example.mastermechanic.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.example.mastermechanic.R
import com.example.mastermechanic.servers.ServerEntry
import com.example.mastermechanic.ui.list.DeleteActionButton
import com.example.mastermechanic.servers.ServerList
import com.example.mastermechanic.servers.ServerListStore
import com.example.mastermechanic.servers.ServerPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

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
    // （用 rememberSaveable：转屏 / 进程回收恢复时，打开中的编辑页与拖动预览不该凭空消失）
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    /**
     * 拖动排序的**预览顺序**：只有拖动中才非空，其余时候界面直接用盘上顺序。
     * 松手落盘成功 / 失败 / 拖动被打断都清空它——清空即回到盘上顺序，这就是拖动排序唯一的"反悔"路径。
     */
    var dragPreview by remember { mutableStateOf<List<ServerEntry>?>(null) }

    // 每次进入页面 / 每次落盘后重新读盘（盘上文件 = 唯一数据源）
    val state = remember(resumeTick, reloadTick) { loadServerListState(context) }
    val loadedList = (state as? ServerListState.Loaded)?.list
    val entries = dragPreview ?: loadedList?.entries.orEmpty()

    // 参数顺序：`transform` 放**最后**——这样 `applyEdit { it.clear() }` 的尾随 lambda 才会落到它身上
    // （Kotlin 的尾随 lambda 永远绑给最后一个参数，放前面会被绑到 onFinished 上）
    fun applyEdit(onFinished: () -> Unit = {}, transform: (ServerList) -> ServerList) {
        val current = loadedList ?: ServerList.EMPTY
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
            onFinished()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 可撤销的那一次删除：**位置 + 内容**（撤销要插回**原位**，不能追加到末尾）。
     *
     * 一条服务器要重打 5 个字段（平台 / 区号 / 区服名 / 角色名 / 等级），误删代价高，
     * 所以 2026-09-15 用户拍板：与好友清单拉平 → 删除后给一个**撤销窗口**，
     * 而不是二次确认弹窗（左滑 + 点按钮已经是两步明确意图，再问一遍在十几条的清单里很烦）。
     */
    var pendingUndo by remember { mutableStateOf<Pair<Int, ServerEntry>?>(null) }

    fun deleteAt(index: Int) {
        val entry = loadedList?.entries?.getOrNull(index) ?: return
        applyEdit { it.remove(index) }
        pendingUndo = index to entry
    }

    fun undoDelete() {
        val (index, entry) = pendingUndo ?: return
        // 撤销窗口期间可能又删了别人 → 位置夹回当前清单范围内（insert 越界会抛错）
        applyEdit { it.insert(index.coerceIn(0, it.size), entry) }
        pendingUndo = null
    }

    // 撤销窗口：Snackbar 上点「撤销」才恢复，超时（约 4s）就地关掉这个窗口
    LaunchedEffect(pendingUndo) {
        val pending = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.server_deleted_with_name, pending.second.serverName),
            actionLabel = context.getString(R.string.server_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) undoDelete() else pendingUndo = null
    }

    ServerListScreen(
        state = state,
        entries = entries,
        message = message,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAbout = { aboutOpen = true },
        onAdd = {
            editingIndex = null
            editorOpen = true
        },
        onEdit = { index ->
            editingIndex = index
            editorOpen = true
        },
        // 点红色「删除」按钮 = 立即删除 + **撤销窗口**（2026-09-15 用户拍板，与好友清单拉平）。
        // 仍**不弹确认框**：左滑 + 点按钮已经是两步明确意图；改为"先做、再给撤销"。
        // （读屏的「删除」自定义动作走同一个出口，同样可撤销。）
        onRequestDelete = { index -> deleteAt(index) },
        onDragPreview = { dragPreview = it },
        onReorder = { from, to ->
            // 松手**才写一次盘**（拖动途中只改界面上的预览顺序）；成功或失败都清预览——
            // 失败时盘上没变，界面自动回到拖动前的顺序
            applyEdit(onFinished = { dragPreview = null }) { it.moveTo(from, to) }
        },
        onClear = { clearing = true },
        onRebuild = { applyEdit { it.clear() } },
    )

    if (editorOpen) {
        val initial = editingIndex?.let { loadedList?.entries?.getOrNull(it) }
        // 一个区服只有一个小号：编辑页据此在保存前就能给出「这个区服已在清单里」的提示
        // （正在编辑的这条自己不算重名，否则连角色名都改不了）
        val takenServerNames = loadedList?.entries.orEmpty()
            .filterIndexed { index, _ -> index != editingIndex }
            .map { it.serverName }
        ServerEntryEditor(
            isNew = editingIndex == null,
            index = editingIndex,
            initial = initial,
            takenServerNames = takenServerNames,
            onDismiss = { editorOpen = false },
            onConfirm = { entry ->
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
        val count = loadedList?.size ?: 0
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

    // 「说明」：原来常驻的 4 行长副标题挪进这里（读一次就够的信息不该一直占着列表的高度）
    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text(stringResource(R.string.server_about)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.server_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.server_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { aboutOpen = false }) {
                    Text(stringResource(R.string.server_about_close))
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

/**
 * 清单页的**布局骨架**：固定区 + 列表吃满剩余高度。
 *
 * 为什么改（2026-09-14 用户口径「加大列表的高度」）：原来整页是一个 `verticalScroll`——
 * 标题 + 4 行副标题 + 列表 + 按钮依次往下排，列表能占多高完全取决于上面那些文字有多长；
 * 横屏（可用高 360dp）时「新增服务器」更是被压到屏幕外。现在固定五段：
 * 顶栏（标题 / 说明 / 返回）→ 操作行（条数 + 新增）→ 手势提示行 → **列表 `weight(1f)`** → 底栏（整体清空）。
 * 副标题与"唯一性"说明挪进「说明」弹窗（读一次就够的信息，不该一直占着列表的高度）。
 */
@Composable
fun ServerListScreen(
    state: ServerListState,
    entries: List<ServerEntry>,
    message: String?,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
    onDragPreview: (List<ServerEntry>?) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onClear: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onRebuild: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.server_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAbout) {
                    Text(stringResource(R.string.server_about))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.server_back))
                }
            }

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
                    Text(
                        text = stringResource(R.string.server_load_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(
                        onClick = onRebuild,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.server_rebuild))
                    }
                }
                is ServerListState.Loaded -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.server_list_title, entries.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onAdd,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.server_add))
                        }
                    }
                    // 手势提示：按钮全删掉之后，这三个手势唯一的"发现入口"（空态另有教学文案）
                    if (entries.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.server_gesture_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        if (entries.isEmpty()) {
                            Text(
                                text = stringResource(R.string.server_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        } else {
                            ServerEntryList(
                                entries = entries,
                                onEdit = onEdit,
                                onRequestDelete = onRequestDelete,
                                onDragPreview = onDragPreview,
                                onReorder = onReorder,
                            )
                        }
                    }
                    if (entries.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.server_clear))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一张卡片的高（FR-10 列表）：**三行文字**（2026-09-14 第九轮用户口径「改成两列，卡片式布局」）。
 *
 * 48 → 56（拆两行）→ 72 → **76dp**（拆三行 + 第十二轮用户口径「三行之间太紧凑」）：
 * 三行是 11sp + 15sp + 11sp（约 15 + 21 + 15dp）再加两处 8dp 的行距 ≈ 67dp，上下各余约 4dp 的呼吸。
 * 第十三轮把行距提到 8dp 时**没有再加高**：小字从 12 收到 11sp 正好让出了这点高度。
 * 它同时是拖动排序的**行落位单位**（不用 px 常量）。
 */
private val ServerRowHeight = 76.dp

/**
 * 卡片之间的**纵向**间距：4 → **8dp**（2026-09-16 用户口径「行距太短，参照跑号清单的间距」）。
 * 它与 [ServerRowHeight] 一起构成拖动排序的**行落位单位**（`rowPitchPx`），改一处要连着另一处想。
 */
private val ServerRowSpacing = 8.dp

/**
 * 卡片之间的**横向**间距：8 → **12dp**（与纵向一起放大，用户口径「横向间距也要相应改动」）。
 * 横向比纵向再宽一档，一排里才看得出是"两张卡"而不是"一张宽卡"；列数与 `colPitchPx` 都由它算出来。
 */
private val ServerGridGap = 12.dp

/**
 * 一张卡的**最小宽**：列数由它算出来 —— `列数 = (可用宽 + 卡间距) / (最小宽 + 卡间距)`。
 *
 * **不判断"是横屏还是竖屏"**：竖屏可用宽约 328dp → 2 列（每列 160dp）、横屏约 760dp → 4 列（每列 184dp）。
 * 换设备、换方向、分屏都由同一条公式接住（红线 4：不出现设备型号 / 分辨率 / 密度 / 坐标）。
 * 150dp 是"再窄就会大面积省略"的下限：160dp 的卡里，14sp 的区服名能放 9 个中文字。
 */
private val ServerCardMinWidth = 150.dp

/** 卡片内左右留白：14 → **12dp**（窄卡片省空间：两列时这 4dp 就是 3% 的可用宽）。 */
private val ServerRowPadding = 12.dp

/**
 * 卡内三行之间的行距：**2 → 5 → 8dp**。
 *
 * 第十二轮用户口径「三行数据之间的间距太紧凑」：这三行是**三个不同性质的字段**
 * （哪个平台哪个区 / 哪个区服 / 哪个号），2dp 会让它们糊成一块，给到 5dp。
 * 第十三轮用户口径「继续增加三行数据的垂直间距」→ 5 → 8dp：这次**不再加高卡片**
 * （[ServerRowHeight] 仍是 76dp），腾出的高度靠小字收小（12 → 11sp，见 [RowSubTextStyle]）来抵。
 */
private val ServerTitleSpacing = 8.dp

/** 行内相邻元素之间的固定间隙：**4dp**（用户口径「文字间距太宽」——8dp 的空隙是主要来源）。 */
private val ServerRowGap = 4.dp

/**
 * 第 3 行里等级的宽度上限：它后面的角色名才是伸缩项，等级太长会把角色名挤掉（超长就截断）。
 *
 * 72 → **60dp**：卡片窄了（160dp），上限必须跟着收，否则"上限"就成了摆设。
 */
private val ServerLevelMaxWidth = 60.dp

/**
 * 行内文字样式：区服名 **15sp**、卡内小字（平台区号 / 等级 / 角色名）**11sp**。
 *
 * 区服名从 14sp 提到 15sp 是第十一轮的 UX 结论：序号拿掉后卡片靠"字号一档差 + 颜色一档差"分主次。
 * 第十三轮把小字从 12sp 收到 11sp（用户口径「缩小第一行和第三行文字的尺寸」）→ 两者差到 4sp，
 * "最该看清的是区服名"更直白。窄卡宽 136dp 下 15sp 仍能放 9 个中文字。
 *
 * `letterSpacing` 一律归零：M3 的 `bodyMedium` / `labelMedium` 自带 0.25 ~ 0.5sp 的字距，
 * 那是给拉丁文调的，中文看着就是"字与字之间空得慌"（用户口径「文字间距太宽」）。
 * 用裸 [TextStyle]（不带主题字体族）——M3 默认字体族就是平台默认字体，效果一致，
 * 而且顶层常量不会每次重组都新建对象。
 */
private val RowServerNameStyle = TextStyle(
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
)

/**
 * 卡内小字：**12 → 11sp**（2026-09-14 第十三轮用户口径「缩小第一行和第三行文字的尺寸」）。
 *
 * 用到它的正是那两行 —— 第 1 行（平台 + 区号）与第 3 行（等级 + 角色名）；第 2 行的区服名 15sp 不动。
 * 与区服名一对比，主次自然就出来了，颜色再补一档。
 */
private val RowSubTextStyle = TextStyle(fontSize = 11.sp, letterSpacing = 0.sp)

/** 左滑露出的删除按钮宽（与 [ServerListGestures.REVEAL_WIDTH_DP] 同一个口径，只在这里换算成 dp）。 */
private val RevealButtonWidth = ServerListGestures.REVEAL_WIDTH_DP.dp

/** 露出按钮的形状：**只圆右边两角**（贴住卡片的轮廓），左边两角是直角——它左邻着卡片，圆角会露出底色缝。 */
private val RevealButtonShape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)

/** 松手后的吸附动画时长：露出与收起共用一条曲线，不搞两套手感。 */
private const val SwipeSettleMillis = 180

/**
 * 清单列表：**行内没有任何常驻按钮**，三个手势各管一件事——双击编辑 / 长按上下拖排序 /
 * 左滑把红色「删除」按钮压出来（**点按钮就直接删**）。
 *
 * 拖动排序的口径（为什么这么做，见 `docs/plans/m3-inspection-config.md` 的手势表）：
 * - 拖动**途中只改界面上的预览顺序**（[onDragPreview]），**松手才落盘一次**（[onReorder]）——
 *   每跨一行就写盘的话，"写盘 → 重新读盘 → 重建列表"会把手指还按着的那一行顶掉（ADR-006）；
 * - 换位判据与"目标第几位"的换算在 [ServerListGestures]（纯函数、有单测）；
 * - 拖动被打断（来电、切后台、转屏）→ 预览顺序丢弃、盘上不变。
 *
 * 左滑露出的口径（2026-09-14 用户口径：**行不动，按钮直接盖在行尾上**）：
 * - 状态是"**按钮盖住行尾的比例**"（0 ~ 1，见 [ServerListGestures.revealFraction]），
 *   不再是"行的位移"——被盖住的那一行**一动不动**，看起来就是红按钮从行尾压进来，
 *   盖住角色名 / 等级（序号与区服名一直看得见，所以你还认得出删的是哪一条）；
 * - 同一时刻**只允许一行露出**，收起的唯一真相是 `revealedKey`——状态提升到这一层，
 *   行内不各自存（LazyColumn 复用条目时行内状态会串到别的行上）；
 * - 松手按 [ServerListGestures.swipeRevealsDeleteButton] 吸附到 0 或 1（[SwipeSettleMillis]）；
 * - 已露出的行可以往回拖（右滑）收起，也可以点一下它的正文区收起（多半是"算了不删"）；
 *   点其它行同理只收起、不进编辑——"另一件事下次再说"；
 * - 列表一滚就收起（用户已经离开那一行）。
 */
@Composable
private fun ServerEntryList(
    entries: List<ServerEntry>,
    onEdit: (Int) -> Unit,
    onRequestDelete: (Int) -> Unit,
    onDragPreview: (List<ServerEntry>?) -> Unit,
    onReorder: (Int, Int) -> Unit,
) {
    val listState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    // 露出区宽 / 落位单位都由 dp 常量乘密度得来（不测量、不读设备参数：红线 4）
    val revealWidthPx = RevealButtonWidth.value * density
    val rowPitchPx = (ServerRowHeight + ServerRowSpacing).value * density

    // 每行左滑露出的**比例**（0 = 收起，1 = 按钮完全盖住行尾），按"区服名"索引
    // ——区服名在清单里唯一（FR-10 唯一性），正好当行的稳定标识
    val revealFractions = remember { mutableStateMapOf<String, Float>() }
    // 同一时刻只允许一行露出（null = 没有行露出）
    var revealedKey by remember { mutableStateOf<String?>(null) }
    // 吸附动画的任务，按区服名索引：手指再按下去时要能立刻打断它
    val settleJobs = remember { mutableMapOf<String, Job>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    // 拖动中的位移**横竖各记一份**（第九轮：网格里横向换列、纵向换行，两个方向都要能落位）
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    fun indexOf(key: String): Int = entries.indexOfFirst { it.serverName == key }

    /** 松手后的吸附：比例从当前位置滑到 0（收起）或 1（全盖住）；上一次没滑完的直接作废。 */
    fun settle(key: String, from: Float, target: Float) {
        settleJobs[key]?.cancel()
        settleJobs[key] = scope.launch {
            Animatable(from).animateTo(target, tween(SwipeSettleMillis, easing = FastOutSlowInEasing)) {
                revealFractions[key] = value
            }
        }
    }

    /** 收起某一行；已经收起就不动它（免得白跑一遍动画）。 */
    fun collapse(key: String) {
        val from = revealFractions[key] ?: 0f
        if (from != 0f) settle(key, from, 0f)
        if (revealedKey == key) revealedKey = null
    }

    fun collapseRevealed() {
        revealedKey?.let { collapse(it) }
    }

    fun startDrag(key: String) {
        draggingKey = key
        dragStartIndex = indexOf(key)
        dragOffsetX = 0f
        dragOffsetY = 0f
    }

    fun dragBy(key: String, dx: Float, dy: Float, cols: Int, colPitchPx: Float) {
        if (draggingKey != key) return
        dragOffsetX += dx
        dragOffsetY += dy
        val from = indexOf(key)
        if (from < 0) return
        // 落位单位：横向 = 卡片宽 + 列距、纵向 = 行高 + 行距（都是 dp 常量换算出来的，不写死像素）
        val target = ServerListGestures.dragTargetIndexInGrid(
            from = from,
            dragOffsetXPx = dragOffsetX,
            dragOffsetYPx = dragOffsetY,
            colPitchPx = colPitchPx,
            rowPitchPx = rowPitchPx,
            cols = cols,
            size = entries.size,
        )
        if (target == from) return
        val moved = entries.toMutableList().also { it.add(target, it.removeAt(from)) }
        // 换位后把"已经跨过的行与列"分别从位移里减掉，手指下这张卡才不会跳一下
        dragOffsetX -= (target % cols - from % cols) * colPitchPx
        dragOffsetY -= (target / cols - from / cols) * rowPitchPx
        onDragPreview(moved)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun endDrag(key: String, cancelled: Boolean) {
        val from = dragStartIndex
        val to = indexOf(key)
        draggingKey = null
        dragStartIndex = -1
        dragOffsetX = 0f
        dragOffsetY = 0f
        if (cancelled || from < 0 || to < 0 || from == to) {
            onDragPreview(null) // 回滚：盘上没变，界面回到拖动前的顺序
        } else {
            onReorder(from, to)
        }
    }

    // 列表一滚就收起：用户已经"离开"那一行了，露出状态跟着列表滚到别处只会让人看不懂
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) collapseRevealed()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 列数 = 「能用几列装下最小卡片宽」（列间距一起算进去），至少 1 列。
        // 公式只看**可用宽**：竖屏约 328dp → 2 列、横屏约 760dp → 4 列
        // （不看方向、不看设备型号 —— 红线 4）。
        val cols = maxOf(1, ((maxWidth + ServerGridGap) / (ServerCardMinWidth + ServerGridGap)).toInt())
        // 列落位单位 = 实际卡片宽 + 列距（由可用宽和列数算出来，仍是 dp 常量换算，不写死像素）
        val colPitchPx = ((maxWidth - ServerGridGap * (cols - 1)) / cols + ServerGridGap).value * density

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(ServerGridGap),
            verticalArrangement = Arrangement.spacedBy(ServerRowSpacing),
        ) {
            itemsIndexed(items = entries, key = { _, entry -> entry.serverName }) { index, entry ->
                val key = entry.serverName
                ServerEntryRow(
                    index = index,
                    lastIndex = entries.lastIndex,
                    entry = entry,
                    revealed = revealedKey == key,
                    anyRevealed = revealedKey != null,
                    revealWidthPx = revealWidthPx,
                    revealFraction = revealFractions[key] ?: 0f,
                    onSwipeStart = { settleJobs[key]?.cancel() },
                    onSwipe = { revealFractions[key] = it },
                    onSwipeEnd = { fraction, velocityX ->
                        val open = ServerListGestures.swipeRevealsDeleteButton(
                            revealFraction = fraction,
                            velocityXPx = velocityX,
                            density = density,
                        )
                        if (open) {
                            // 只允许一行露出：把上一行收起来（它自己不算"刚露出"，不用再震一下）
                            if (revealedKey != key) {
                                revealedKey?.let { collapse(it) }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                revealedKey = key
                            }
                            settle(key, fraction, 1f)
                        } else {
                            if (revealedKey == key) revealedKey = null
                            settle(key, fraction, 0f)
                        }
                    },
                    onCollapse = { collapseRevealed() },
                    dragging = draggingKey == key,
                    dragOffsetX = dragOffsetX,
                    dragOffsetY = dragOffsetY,
                    onEdit = { onEdit(index) },
                    onDragStart = ::startDrag,
                    // 列数与列落位单位在这里才拿得到（它们由 BoxWithConstraints 的可用宽算出）
                    onDragBy = { dragKey, dx, dy -> dragBy(dragKey, dx, dy, cols, colPitchPx) },
                    onDragEnd = ::endDrag,
                    onRequestDelete = {
                        // 删掉之后这一行就不存在了：露出状态与比例一起清掉，别把状态留给下一行
                        revealedKey = null
                        revealFractions.remove(key)
                        onRequestDelete(index)
                    },
                    // TalkBack 的"前移 / 后移"走的是**立即落盘**（不用预览），和拖动排序同一个出口
                    onMoveUp = { if (index > 0) onReorder(index, index - 1) },
                    onMoveDown = { if (index < entries.lastIndex) onReorder(index, index + 1) },
                )
            }
        }
    }
}

/**
 * 清单里的一条（FR-10）：**两行文字**——主行「平台区号 + 区服名」、副行「等级 + 角色名」，
 * 只列填了的字段（空字段不占位、不写「未填」），行高 [ServerRowHeight]。
 *
 * 手势状态机（一个 `pointerInput` 里自绘，**不叠加三个现成检测器**——它们会互相抢事件、
 * 谁先消费掉按下事件就没人说得清）：
 * - `mode = 0` 未定向：纵向先超过 `touchSlop` → **判给列表滚动**（不消费事件，本行退出）；
 *   横向先超过且大于纵向 → 锁定左滑；按住不动满 `longPressTimeoutMillis` → 锁定拖动；
 * - `mode = 1` 左滑：**行本身不动**，只把"按钮盖住行尾的比例"报给列表层
 *   （[ServerListGestures.revealFraction]），按钮自己按这个比例从行尾压进来；
 *   松手按 [ServerListGestures.swipeRevealsDeleteButton] 吸附成"全盖住"或"回弹收起"；
 * - `mode = 2` 拖动：只取纵向位移，横向丢掉（纵向已被拖动占用，滚动不再响应）；
 * - **双击**：两次点击各自位移都在 `touchSlop` 内、间隔在 `doubleTapTimeoutMillis` 内 → 进编辑。
 *   单击不做事（用户口径是"双击进编辑"），提示行会写清楚；**但已露出删除按钮时单击 = 收起**；
 * - 手指落在**已露出的删除按钮**上 → 本状态机整块退出（[ServerListGestures.isOnRevealedDeleteButton]），
 *   事件让给按钮自己处理——否则会变成"点删除没反应，只把按钮收起来了"。
 */
@Composable
private fun ServerEntryRow(
    index: Int,
    lastIndex: Int,
    entry: ServerEntry,
    revealed: Boolean,
    anyRevealed: Boolean,
    revealWidthPx: Float,
    revealFraction: Float,
    onSwipeStart: () -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeEnd: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
    dragging: Boolean,
    dragOffsetX: Float,
    dragOffsetY: Float,
    onEdit: () -> Unit,
    onDragStart: (String) -> Unit,
    onDragBy: (String, Float, Float) -> Unit,
    onDragEnd: (String, Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val key = entry.serverName
    val haptics = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val density = LocalDensity.current.density
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val editLabel = stringResource(R.string.server_edit)
    val moveUpLabel = stringResource(R.string.server_move_up)
    val moveDownLabel = stringResource(R.string.server_move_down)
    val deleteLabel = stringResource(R.string.server_delete)
    val deleteDesc = stringResource(R.string.server_delete_action_desc, index + 1)
    // 行首的「平台 + 区号」（2026-09-14 用户口径：行内顺序 = 序号 ｜ 平台+区号 ｜ 区服名 ｜ · 角色名 ｜ 等级）：
    // 两个都没填 → 整段不占位（与空角色名 / 空等级同一口径，看不见空洞）
    val platformLabel = when (entry.platform) {
        ServerPlatform.WECHAT -> stringResource(R.string.server_platform_wechat)
        ServerPlatform.QQ -> stringResource(R.string.server_platform_qq)
        null -> ""
    }
    // 两个字段的展示口径（**用户口径，已由 [ServerListLabels] 的 JVM 单测钉住**）：
    // 区号填 `256` → 显示「256区」（第十一轮）；等级填 `30` → 显示「Lv.30」（第十三轮）；
    // 用户自己已经写了后缀 / 前缀 → 不再补第二个（实测数据里 `256区` / `Lv.50` 这种写法确实存在）。
    // 只在**展示层**生效：编辑页输入框与盘上文件里始终是用户填的原文。
    val noSuffix = stringResource(R.string.server_no_suffix)
    val levelPrefix = stringResource(R.string.server_level_prefix)
    val platformNoText = platformLabel + ServerListLabels.regionCode(entry.serverNo, noSuffix)
    val levelText = ServerListLabels.level(entry.level, levelPrefix)

    // 读屏用一句话把这一条念清楚（视觉上的分隔符" · "对读屏是噪音，这里改成中文逗号）
    val description = buildString {
        append(
            stringResource(
                R.string.server_row_desc,
                index + 1,
                if (platformNoText.isEmpty()) entry.serverName else "$platformNoText $entry.serverName",
            ),
        )
        if (entry.hasCharacterName) {
            append(stringResource(R.string.server_row_desc_role, entry.characterName))
        }
        if (entry.hasLevel) {
            // 念的和看的一致（都用补好前缀的 levelText）
            append(stringResource(R.string.server_row_desc_level, levelText))
        }
    }

    // `pointerInput(key)` 的块**只在 key 变化时重启**：块里直接读普通参数会读到"重启那一刻"的旧值
    // （拖动换位后 index 会变、露出状态也会变）；而捕获的引用一变，Compose 还会把整块手势处理重置掉
    // ——拖动途中被重置就是拖动断掉。所以块里用到的每个值与回调都经 `rememberUpdatedState` 取。
    val currentEdit by rememberUpdatedState(onEdit)
    val currentDelete by rememberUpdatedState(onRequestDelete)
    val currentCollapse by rememberUpdatedState(onCollapse)
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ServerRowHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                // 网格里两个方向都要跟手：横向是换列、纵向是换行
                translationX = if (dragging) dragOffsetX else 0f
                translationY = if (dragging) dragOffsetY else 0f
                val scale = if (dragging) ServerListGestures.DRAG_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            // 红按钮从行尾压进来时，压出行外的那部分要被裁掉（不裁就会盖到隔壁行上去）
            .clipToBounds()
            .pointerInput(key) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                var lastTapUptime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 已露出的删除按钮是**独立可点节点**：手指落在那儿就整块退出，让按钮自己处理
                    // （父级 pointerInput 先于子级拿到事件，抢着消费的话按钮永远点不到）
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
                    // 比例的基准 = 起手时按钮已经盖了多少：已露出的行往回拖（右滑）就是收起（不用另立一套状态）
                    var baseFraction = 0f
                    var liveFraction = 0f
                    var lastUptime = down.uptimeMillis
                    var completed = false
                    try {
                        while (true) {
                            // 长按是"不动也得到"的判定，所以要带超时等地等：超时无人叫醒 = 长按到位
                            val remaining = longPressMs - (lastUptime - down.uptimeMillis)
                            val event = if (mode == 0) {
                                withTimeoutOrNull(remaining.coerceAtLeast(0L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                // 已露出这一行：长按先收起，本次手势不再接着拖（"一次按住只做一件事"）
                                if (currentRevealed) {
                                    currentCollapse()
                                    break
                                }
                                if (currentAnyRevealed) currentCollapse()
                                mode = 2
                                currentDragStart(key)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastUptime = change.uptimeMillis
                            velocity.addPosition(change.uptimeMillis, change.position)
                            if (!change.pressed) {
                                completed = true
                                when (mode) {
                                    1 -> currentSwipeEnd(liveFraction, velocity.calculateVelocity().x)
                                    2 -> currentDragEnd(key, false)
                                    else -> {
                                        val tapped = abs(dx) < slop && abs(dy) < slop
                                        if (tapped && currentAnyRevealed) {
                                            // 已经露出按钮时点一下**只收起**（多半是"算了不删"），也不进编辑
                                            currentCollapse()
                                            lastTapUptime = 0L
                                        } else if (tapped) {
                                            // **单击即编辑**（2026-09-15 用户拍板，与好友清单统一）：
                                            // 原先是"双击才编辑、单击不做事" —— 双击在安卓不常见、新用户发现不了，
                                            // 且 TalkBack 的"双击"就是激活单击，自定义双击语义在读屏下根本传不到。
                                            // 双击的**第一下**这里就进编辑了，第二下不会再造成别的影响 —— 老习惯天然兼容。
                                            lastTapUptime = 0L
                                            currentEdit()
                                        }
                                    }
                                }
                                break
                            }
                            // 位移直接"当前位置 - 上一次位置"：`positionChange()` 在部分 Compose
                            // 版本里是「已被消费」的布尔成员（不是函数），换个写法省得踩版本差异
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
                                    currentDragBy(key, delta.x, delta.y)
                                }
                            }
                        }
                    } finally {
                        // 手势被系统打断（来电 / 切后台 / 转屏）→ 按当前位置吸附，不留半拉子状态
                        if (!completed) {
                            if (mode == 2) currentDragEnd(key, true)
                            if (mode == 1) currentSwipeEnd(liveFraction, 0f)
                        }
                    }
                }
            },
    ) {
        // 清单卡片：**位置全程不变**（2026-09-14 用户口径：行不该跟着手指滑走）。
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(ServerRowHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    customActions = buildList {
                        add(CustomAccessibilityAction(editLabel) { currentEdit(); true })
                        if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                        if (index < lastIndex) {
                            add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                        }
                        add(CustomAccessibilityAction(deleteLabel) { currentDelete(); true })
                    }
                },
            colors = if (dragging) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            // 卡内三行（2026-09-14 第九轮口径「改成两列，卡片式布局」；竖屏 2 列 / 横屏 4 列；
            // 第十一轮去掉序号、第十二轮把行距从 2dp 拉到 5dp）：
            // 第 1 行 = "哪个平台哪个区"（小字，最弱）、第 2 行 = 区服名（大字，主角）、
            // 第 3 行 = "这个区里是哪个号"（小字）。窄卡片里把主信息**竖过来**：
            // 160dp 的卡里 15sp 的区服名能放 9 个中文字，横着排只能放 4 个。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ServerRowPadding),
                verticalArrangement = Arrangement.spacedBy(
                    ServerTitleSpacing,
                    Alignment.CenterVertically,
                ),
            ) {
                // 第 1 行：平台 + 区号（小字、次级色）。**不显示序号**（2026-09-14 第十一轮用户口径）；
                // 序号消失后这一行只剩左侧一段内容，于是连"两端分列"的 Row 与 `weight(1f)` 一起撤掉
                // ——直接一段左对齐文字，顺带消掉一处 `weight` 预分空间的坑。
                // 右侧**刻意留白**：行尾是左滑删除按钮的压入区（88dp），往里塞任何东西都会被当成可点控件。
                if (platformNoText.isNotEmpty()) {
                    Text(
                        text = platformNoText,
                        style = RowSubTextStyle,
                        color = secondaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 第 2 行（主角）：区服名——它是**唯一用于定位的字段**（红线 3），字号给足
                Text(
                    text = entry.serverName,
                    style = RowServerNameStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 第 3 行：等级（primary，稍微跳出来一点）+ 角色名（次级色）；两个都没填 → 整行不占位
                if (entry.hasLevel || entry.hasCharacterName) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ServerRowGap),
                    ) {
                        if (entry.hasLevel) {
                            Text(
                                text = levelText,
                                style = RowSubTextStyle,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = ServerLevelMaxWidth),
                            )
                        }
                        // 一行里**只让最后一段伸缩**：它按内容宽取用（短就只占内容宽），超长才从尾部截断。
                        // 不要给两段都加 weight —— **权重是按比例"预分"空间的，用不掉的那一份不会回流给兄弟**
                        // （第七轮真机踩过：2 : 1 分权重让角色名被截断而右边还空着 70 多 dp）。
                        if (entry.hasCharacterName) {
                            Text(
                                text = entry.characterName,
                                style = RowSubTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
        // 左滑露出的**红色删除按钮**：画在卡片**之后** = 盖在行上（用户口径「直接覆盖在清单行上」）。
        // `translationX` 由露出比例驱动：比例 1 → 0（完全盖住行尾）；比例 0 → 一个按钮宽
        // （整块退回行外，被根节点上的 `clipToBounds` 裁掉）。所以拖动时看到的是
        // "红按钮从行尾压进来"，被删的那一行自己一动不动。
        // 只在压出来时进组合——语义节点不能常驻，否则读屏会念出一个眼睛看不见的按钮；
        // `enabled = revealed` 让它只有停稳之后才可点（拖动途中不算）。
        if (revealFraction > 0f || revealed) {
            DeleteActionButton(
                label = deleteLabel,
                description = deleteDesc,
                enabled = revealed,
                onDelete = currentDelete,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(RevealButtonWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = RevealButtonWidth.toPx() * (1f - revealFraction)
                    }
                    .clip(RevealButtonShape),
            )
        }
    }
}

/** 编辑页每个输入字段的高度：矮到三行字段 + 保存同屏可见（见 [ServerEntryEditor] 的说明）。 */
private val EditorFieldHeight = 40.dp

/** 字段内标签的固定宽度：三个字段的输入区左边界对齐，看起来才整齐。 */
private val EditorFieldLabelWidth = 64.dp

/** 横屏时表单占屏宽的比例：软键盘占**右半边**，表单留在左半边就永远不被挡（见 [ServerEntryEditor]）。 */
private const val EditorLandscapeWidthFraction = 0.5f

/**
 * 新增 / 编辑「服务器」：**全屏编辑页**（不是对话框）。
 *
 * 为什么不用 AlertDialog：本机横屏的可用高度只有 360dp，三个输入框 + 标题 + 按钮放不进对话框——
 * Material3 会把溢出的内容塞进内部滚动区并加一条分割线，用户看到的像是"只有两个输入框"
 * （2026-09-14 真机实测）。全屏编辑页无论横竖屏都完整可见（与标定工作台同一做法）。
 *
 * **字段各自独占一行 + 与保存按钮同屏可见（2026-09-14 用户口径）**，三条一起保证：
 * ① 字段**各占一行**（用户明确否掉了"角色名与等级并排"那种两列写法）；
 *   **例外：「平台」与「区号」共占一行**（2026-09-14 追加口径）——它们在列表行里本来就是连写在一起显示的
 *   （「微信392」），放同一行既省高度、视觉位置也和列表对得上；拆成两行会让表单多出 48dp，
 *   横屏（可用高约 360dp）下「保存」会被顶到屏幕外；
 * ② 字段高 40dp（标签放在框内左侧，省掉 Material 浮动标签占的那一行）→ 整个表单高约 184dp；
 * ③ **横屏时整块表单退到左半边**（[EditorLandscapeWidthFraction]）：横屏软键盘占屏幕**右半边**
 *   （真机实测键盘左边缘约在屏宽 56% 处），全宽表单右下角的「保存」会被压在键盘底下；
 *   竖屏（360dp 宽）键盘在下方，表单照旧占满宽度。
 *
 * 校验口径与巡查配置页一致：**保存按钮始终可点**，点了才报「缺什么」——
 * 置灰按钮收不到点击，"为什么不能点"只能靠猜。除格式校验外，多一条**区服唯一**：
 * 一个区服只有一个小号，重名时明确提示而不是静默写进去（也不会悄悄覆盖已有条目）。
 */
@Composable
private fun ServerEntryEditor(
    isNew: Boolean,
    index: Int?,
    initial: ServerEntry?,
    takenServerNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (ServerEntry) -> Unit,
) {
    // 用 rememberSaveable：转屏 / 进程回收不该把填了一半的表单丢掉（与 remember 的差别只在配置变化时体现）
    var serverName by rememberSaveable { mutableStateOf(initial?.serverName.orEmpty()) }
    // 平台存**编码**而不是枚举实例：字符串是 rememberSaveable 一定存得住的东西
    // （省得为"可空枚举能不能存进 Bundle"多留一个只有转屏时才炸的坑）；空串 = 没填
    var platformCode by rememberSaveable { mutableStateOf(initial?.platform?.code.orEmpty()) }
    var serverNo by rememberSaveable { mutableStateOf(initial?.serverNo.orEmpty()) }
    var characterName by rememberSaveable { mutableStateOf(initial?.characterName.orEmpty()) }
    var level by rememberSaveable { mutableStateOf(initial?.level.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val platform = ServerPlatform.fromCode(platformCode)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val requiredText = stringResource(R.string.server_field_required)
    val duplicateText = stringResource(R.string.server_field_duplicate)
    val separatorText = stringResource(R.string.server_field_separator)
    val optionalHint = stringResource(R.string.server_field_optional_hint)

    fun save() {
        val server = serverName.trim()
        when {
            server.isEmpty() -> error = requiredText
            // 一个区服只有一个小号：与清单里其它条目重名 → 明确提示，不写入、不覆盖
            takenServerNames.any { it == server } -> error = duplicateText
            !ServerList.isValidServerName(server) ||
                !ServerList.isValidOptionalField(serverNo) ||
                !ServerList.isValidOptionalField(characterName) ||
                !ServerList.isValidOptionalField(level) -> error = separatorText
            else -> onConfirm(ServerEntry.of(platform, serverNo, serverName, characterName, level))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                // 不用 Surface 做底：它会给子级传"最小宽度 = 屏宽"（propagateMinConstraints = true），
                // `fillMaxWidth(…)` 会被它顶回全宽（真机实测：限宽没生效，保存仍被键盘盖住）。
                modifier = Modifier
                    .fillMaxWidth(if (landscape) EditorLandscapeWidthFraction else 1f)
                    .fillMaxHeight()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (isNew) {
                        stringResource(R.string.server_dialog_add)
                    } else {
                        stringResource(R.string.server_dialog_edit_indexed, (index ?: 0) + 1)
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                CompactPlatformNoField(
                    platform = platform,
                    onPlatformChange = {
                        platformCode = it?.code.orEmpty()
                        error = null
                    },
                    serverNo = serverNo,
                    onServerNoChange = {
                        serverNo = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactField(
                    value = serverName,
                    onValueChange = {
                        serverName = it
                        error = null
                    },
                    label = stringResource(R.string.server_field_server),
                    hint = stringResource(R.string.server_field_server_hint),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactField(
                    value = characterName,
                    onValueChange = {
                        characterName = it
                        error = null
                    },
                    label = stringResource(R.string.server_field_role),
                    hint = optionalHint,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactField(
                    value = level,
                    onValueChange = {
                        level = it
                        error = null
                    },
                    label = stringResource(R.string.server_field_level),
                    hint = optionalHint,
                    imeAction = ImeAction.Done,
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

/**
 * 矮输入框：固定 [EditorFieldHeight] 高、标签放在框内左侧。
 *
 * 不用 `OutlinedTextField` 的原因：它的最小高度由 Material 组件内部固定（默认 56dp，
 * 浮动标签还要占一行），压到 40dp 会把标签压变形；这里用 [BasicTextField] + 自绘边框，
 * 高度完全由我们说了算（键盘弹出后横屏只剩不到 200dp，这三行字段必须矮下来）。
 */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(EditorFieldHeight)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = EditorFieldLabelWidth),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

/** 平台选项块的高度：矮一点才塞得进 40dp 的字段行（点它的区域就是这一块本身）。 */
private val PlatformChoiceHeight = 28.dp

/** 平台选项块的圆角。 */
private val PlatformChoiceShape = RoundedCornerShape(8.dp)

/**
 * 「平台 + 区号」字段（2026-09-14 用户口径新增）：左边两个可点选项（微信 / QQ）、右边一个区号输入框，
 * **共占一行**（理由见 [ServerEntryEditor] 的说明：列表行里这两个本来就是连写的「微信392」）。
 *
 * - **点已选中的那一个 = 取消选择**（回到"没填"）——比再加一个「不填」选项省地方，
 *   也和"平台与区号都可以留空"的口径一致；两侧都不选就是留空；
 * - 平台用**选项**而不是输入框：只有两个值，让人自己打字就会冒出「wx」「WeChat」「微信 」三种写法，
 *   而它是要一眼扫过去的辨识信息，写法必须先统一（模型层就是枚举，见 [ServerPlatform]）。
 */
@Composable
private fun CompactPlatformNoField(
    platform: ServerPlatform?,
    onPlatformChange: (ServerPlatform?) -> Unit,
    serverNo: String,
    onServerNoChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(EditorFieldHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.server_field_platform),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.widthIn(min = EditorFieldLabelWidth),
        )
        PlatformChoice(
            label = stringResource(R.string.server_platform_wechat),
            selected = platform == ServerPlatform.WECHAT,
            onClick = {
                onPlatformChange(
                    if (platform == ServerPlatform.WECHAT) null else ServerPlatform.WECHAT,
                )
            },
        )
        PlatformChoice(
            label = stringResource(R.string.server_platform_qq),
            selected = platform == ServerPlatform.QQ,
            onClick = {
                onPlatformChange(if (platform == ServerPlatform.QQ) null else ServerPlatform.QQ)
            },
        )
        // 区号紧挨着平台（列表行里这两个也是连写的「微信392」，位置对得上）
        CompactInlineField(
            value = serverNo,
            onValueChange = onServerNoChange,
            hint = stringResource(R.string.server_field_server_no_hint),
            imeAction = ImeAction.Next,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 平台的一个选项块：选中 = 实底色 + 描边，未选中 = 只描边（**两个都不选是正常状态** = 留空）。 */
@Composable
private fun PlatformChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(PlatformChoiceHeight)
            .clip(PlatformChoiceShape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = PlatformChoiceShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, letterSpacing = 0.sp),
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * 不带外框的单行输入（[CompactPlatformNoField] 里那一格「区号」用）：外框与标签由外层那一行负责，
 * 这里只管"有提示文字、能输入"。与 [CompactField] 的差别仅是**不带自己的边框**（画了两层框会很难看）。
 */
@Composable
private fun CompactInlineField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}
