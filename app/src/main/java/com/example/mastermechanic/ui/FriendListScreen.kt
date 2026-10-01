package com.example.mastermechanic.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mastermechanic.R
import com.example.mastermechanic.friends.FriendEntry
import com.example.mastermechanic.friends.FriendReferences
import com.example.mastermechanic.friends.FriendRenameSync
import com.example.mastermechanic.ui.list.DeleteActionButton
import com.example.mastermechanic.ui.list.SwipeActionRow
import com.example.mastermechanic.friends.FriendList
import com.example.mastermechanic.friends.FriendListStore
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友清单页（M3-T3-8 / FR-07「拜访」的三级列表数据源；2026-09-15 用户口径「新建独立好友清单」）。
 *
 * 数据口径与服务器清单页（[ServerListRoute]）、巡查配置页（[PatrolConfigRoute]）完全一致（ADR-006）：
 * - **盘上文件是唯一数据源**——每次编辑都是「读盘 → 改 → 落盘 → 重新读盘」；
 *   写入失败**不**更新界面并明确提示（不制造"看起来改了、其实没保存"的假象）；
 * - 解析失败 → 显示原因 + 「清空重建」入口，**不静默变成空列表**。
 *
 * **交互按安卓移动端惯例，不照搬桌面端**（2026-09-15 用户指出"列表尾部带删除和编辑 label 是 PC 端做法"）：
 * - **点按整行 = 改名**（安卓设置类列表的肌肉记忆：点行进详情 / 编辑）；
 * - **长按一行 = 操作菜单**（编辑 / 删除）；
 * - 删除**先做、再给撤销**（Snackbar，约 4s）——中文名字删了要重打一遍，比区服名更心疼，
 *   但"再问一遍"的确认弹窗在十几条的清单里很烦，Material 的口径是 **undo 而不是 confirm**。
 * - 行内**没有任何常驻按钮**：每行拆成多个 ≥48dp 的按钮会把名字挤没，而且视觉上是表格行（桌面端习惯）。
 *
 * **未做（下一步）**：左滑露出「删除」按钮（与服务器清单同一套手势）——那段手势状态机目前内联在
 * 服务器清单页里，直接复制一份会养出两份难维护的代码，等它被抽成可复用组件后再接上；
 * 在那之前删除走长按菜单，撤销兜底。
 */
private sealed interface FriendListState {

    data class Loaded(val list: FriendList) : FriendListState

    data class Failed(val reason: String) : FriendListState
}

/** 好友清单行高：一行名字（56dp 也满足最小触摸目标）。 */
private val FriendRowHeight = 56.dp

/** 行与行之间的间距：**8dp**（2026-09-16 用户口径「行距太短，参照跑号清单的间距」）。 */
private val FriendRowSpacing = 8.dp

private fun loadFriendListState(context: Context): FriendListState = try {
    FriendListState.Loaded(FriendListStore.load(context) ?: FriendList.EMPTY)
} catch (e: IllegalArgumentException) {
    FriendListState.Failed(e.message ?: e.javaClass.simpleName)
}

/**
 * 改名结果 → 提示文案（2026-09-22 用户口径：改名要**说出来**下游跟着改了没有）。
 *
 * 分成四句而不是"已改名"一句：用户真正需要知道的是"还有没有别的地方需要我去改"。
 * 没引用 / 只改了一处 / 两处都改 / 同步失败，四种后续动作完全不同（无事 / 无事 / 无事 / 去排查）。
 * 纯函数，有单测（`FriendRenameNoticeTest`）。
 */
internal fun renameNoticeOf(report: FriendRenameSync.Report): Int = when {
    report.failure != null -> R.string.friend_rename_sync_failed
    report.presetRenamed && report.anchorsRenamed > 0 -> R.string.friend_rename_synced_both
    report.presetRenamed -> R.string.friend_rename_synced_preset
    report.anchorsRenamed > 0 -> R.string.friend_rename_synced_anchors
    else -> R.string.friend_rename_synced_none
}

/**
 * 删除时**引用验证**的结果 → 提示文案（2026-09-22 用户口径）。
 *
 * 与改名是同一件事的两面：改名能把引用**改挂过去**，删除没有可改的地方 —— 引用只剩**悬空**。
 * 所以删除时唯一能做的就是**先说清楚**"这个名字还在被谁用着"，用户才知道删完还得去哪一页收拾。
 * 不说的话，症状要等跑号才出现（第 9 步按名字在列表里找不到行 → 中止，红线 3），
 * 那时已经离"删除"这个动作很远了，没人能联想到。
 *
 * 四句话：没引用 / 拜访规则在用 / N 条产物在用 / 两处都在用；查不成另走一句带原因
 * （**不把"查不到"说成"没问题"**）。纯函数，有单测（`FriendDeleteNoticeTest`）。
 */
internal fun friendDeleteNoticeOf(hits: FriendReferences.Hits): Int = when {
    hits.failure != null -> R.string.friend_deleted_ref_check_failed
    hits.presetNames.isNotEmpty() && hits.anchorCount > 0 -> R.string.friend_deleted_referenced_both
    hits.presetNames.isNotEmpty() -> R.string.friend_deleted_referenced_preset
    hits.anchorCount > 0 -> R.string.friend_deleted_referenced_anchors
    else -> R.string.friend_deleted_with_name // 干净的那一次：原样那一句，不加噪音
}

/**
 * 删除前的**确认框正文**：只有"有引用"或"没能确认"时才需要问一句，其余情况返回 null（直接删）。
 *
 * 用户口径（2026-09-22）：**有引用时先问一句再删**。上面 [friendDeleteNoticeOf] 那一句是**事后**说的，
 * 那时已经删掉了，只能靠撤销挽回；而"删了之后会怎样"本来就是动手前最该知道的事。
 * 查不成也问：结论不可信时按"有风险"处理，绝不当成"没问题"放过去。
 *
 * 纯函数，有单测（`FriendDeleteNoticeTest`）。
 */
internal fun friendDeleteConfirmBodyOf(hits: FriendReferences.Hits): Int? = when {
    hits.failure != null -> R.string.friend_delete_body_ref_unknown
    hits.presetNames.isNotEmpty() && hits.anchorCount > 0 -> R.string.friend_delete_body_both
    hits.presetNames.isNotEmpty() -> R.string.friend_delete_body_preset
    hits.anchorCount > 0 -> R.string.friend_delete_body_anchors
    else -> null // 干净的那一次：不打断，照旧"先做再给撤销"
}

/**
 * 清空确认框旁边那次引用查询的三态（清空**本来就有**确认框，所以引用信息放在**动手前**说最有用）。
 *
 * 查的过程中先按普通正文显示（查得很快，不能让对话框干等），查不成**如实加一句** ——
 * 但**不拦住清空**：清空已经问过一遍了，这里只是把风险讲清楚。
 */
internal sealed interface FriendClearRef {

    /** 正在查（正文先用普通那一句）。 */
    data object Checking : FriendClearRef

    /** 查到了：这批里有 [referenced] 条还被引用。 */
    data class Done(val referenced: Int) : FriendClearRef

    /** 没查成（读盘失败 / 文件坏了）：原因照样说出来。 */
    data class Failed(val reason: String) : FriendClearRef
}

/** 清空确认框正文：按三态分档（查不成要如实说，而不是当成没查到）。纯函数，有单测。 */
internal fun friendClearBodyOf(ref: FriendClearRef?): Int = when (ref) {
    is FriendClearRef.Done ->
        if (ref.referenced > 0) R.string.friend_clear_body_referenced else R.string.friend_clear_body

    is FriendClearRef.Failed -> R.string.friend_clear_body_ref_unknown
    else -> R.string.friend_clear_body // 还在查：先用普通那一句
}

/** 可撤销的那一次删除：**位置 + 内容**（撤销要插回原位）+ 已经算好的提示语。 */
private data class FriendPendingDelete(val index: Int, val entry: FriendEntry, val message: String)

/**
 * **先问一句**的那一次删除：位置 + 内容 + 已经查好的引用结论。
 *
 * 结论要跟着一起存：用户点「仍要删除」时才真删，删完的撤销窗口里那一句还要用它。
 */
private data class FriendDeleteAsk(
    val index: Int,
    val entry: FriendEntry,
    val hits: FriendReferences.Hits,
)

@Composable
fun FriendListRoute(resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val density = LocalDensity.current.density
    val densityRef = LocalDensity.current

    var reloadTick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    /**
     * 可撤销的那一次删除：**位置 + 内容**（撤销要插回原位，不能追加到末尾）+ **已经算好的提示语**。
     *
     * 提示语要在这里一起存：删除前先做了一次引用验证（[friendDeleteNoticeOf]），
     * 结论得跟着这一行走，撤销窗口里才说得出来。
     */
    var pendingUndo by remember { mutableStateOf<FriendPendingDelete?>(null) }

    /** **先问一句**的那一次删除（有引用 / 没能确认引用时才不是 null）：点「仍要删除」才真删。 */
    var ask by remember { mutableStateOf<FriendDeleteAsk?>(null) }

    /** 清空确认框旁边那次的引用查询（三态）：对话框一打开就查，正文据此分档。 */
    var clearRef by remember { mutableStateOf<FriendClearRef?>(null) }

    /**
     * 可撤销的那一次**整体清空**：留着清空前的**整份清单**（撤销 = 整份写回）。
     *
     * 与"删一行"两个粒度：一行要插回原位，清空则整份还回来 —— 与服务器清单同一口径
     * （2026-09-20 用户口径：整体清空也要给底部撤销，原来只有二次确认）。
     */
    var pendingClearUndo by remember { mutableStateOf<FriendList?>(null) }
    var clearing by remember { mutableStateOf(false) }
    /**
     * 最近一次改名的**结果提示**（null = 没有要说的）。
     *
     * 走到删除 / 清空同一套底部提示：改名的重点不是"改成功了"（那一眼就能看见），而是
     * **下游引用有没有跟着改** —— 没同步就等于预设指着不存在的人，必须说出来。
     */
    var renameNotice by remember { mutableStateOf<String?>(null) }
    /** 左滑露出的那一行（**同一时刻只允许一行**，按好友名索引 —— 名字唯一）。 */
    var revealedKey by remember { mutableStateOf<String?>(null) }
    /** 正在跟手滑动的那一行**：跟手期间比例由 [swipingFraction] 直接给，松手后才交给吸附动画。 */
    var swipingKey by remember { mutableStateOf<String?>(null) }
    var swipingFraction by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    /** 拖动排序：正在拖的那一行的名字 / 起点 / 当前预览下标 / 累计位移。 */
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    /** 拖动中的**预览顺序**（松手才落盘，被打断就丢弃）。 */
    var dragPreview by remember { mutableStateOf<List<FriendEntry>?>(null) }

    // 列表一滚就收起露出的按钮（用户已经"离开"那一行了，留着只会让人看不懂）
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) revealedKey = null
    }

    // 每次进入页面 / 每次落盘后重新读盘（盘上文件 = 唯一数据源）
    val state = remember(resumeTick, reloadTick) { loadFriendListState(context) }
    val loadedList = (state as? FriendListState.Loaded)?.list

    /**
     * 落盘 + 重新读盘（新增 / 删除 / 清空 / 拖动排序 / 改名都走这里）。
     *
     * [andThen] 只在**保存成功之后**执行：写失败时界面保持读盘结果，绝不基于一份没写进去的数据往下走
     * （改名要接着同步下游引用，正是靠这条保证"清单没改成 → 不同步、也不提示"）。
     */
    fun persist(next: FriendList, andThen: suspend () -> Unit = {}) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FriendListStore.save(context, next) }
            }
                .onSuccess {
                    message = null
                    reloadTick++
                    andThen()
                }
                .onFailure {
                    // 写失败 → **不**假装成功：界面保持读盘结果，提示里带原始原因
                    message = context.getString(
                        R.string.friend_save_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
        }
    }

    fun applyEdit(transform: (FriendList) -> FriendList) {
        persist(transform(loadedList ?: FriendList.EMPTY))
    }

    /**
     * 改名（点按一行 → 填新名字）：清单落盘后**接着同步下游引用**（2026-09-22 用户口径）。
     *
     * 名字是**跨文件的引用键**：拜访规则（`preset/visit.txt`）与标定产物里好友头像的**归属好友名**
     * 都存着同一个字符串，好友清单只是它的主副本。只改清单 = 那两处指向一个**不存在的人**，
     * 运行时表现为"第 9 步找不到目标好友 / 认不出这是谁的农场"，而两份文件单看都合法。
     *
     * 顺序上**先落盘清单、成功了再同步**；反过来同步失败**不撤销**改名（改名这个动作本身成功了，
     * 撤销只会让人以为没改上、再改一次还是同样的失败 —— 失败原因照实提示，见 [renameNotice]）。
     */
    fun renameAt(index: Int, entry: FriendEntry) {
        val oldName = loadedList?.entries?.getOrNull(index)?.name ?: return
        persist((loadedList ?: FriendList.EMPTY).update(index, entry)) {
            if (oldName == entry.name) return@persist // 名字没变 → 没有要同步的东西
            val report = withContext(Dispatchers.IO) {
                FriendRenameSync.apply(context, oldName, entry.name)
            }
            renameNotice = context.getString(
                renameNoticeOf(report),
                entry.name,
                // 第二格按用到的那条文案给：条数（%2$d）或失败原因（%2$s）
                report.failure ?: report.anchorsRenamed,
            )
        }
    }

    // 真的删掉（入口：判断为"没引用"时的直接删 / 用户在确认框里点了「仍要删除」）。
    // 局部函数必须先声明后使用，所以它排在 [deleteAt] 前面（这就是删除动作的公共尾巴）。
    fun commitDelete(index: Int, entry: FriendEntry, hits: FriendReferences.Hits) {
        applyEdit { it.remove(index) }
        pendingUndo = FriendPendingDelete(
            index = index,
            entry = entry,
            message = context.getString(friendDeleteNoticeOf(hits), entry.name, hits.arg),
        )
    }

    /**
     * 删除一行：**先查引用**，再决定"直接删"还是"先问一句"（2026-09-22 用户口径）。
     *
     * 名字是**跨文件的引用键**，这一行删掉不代表别处的引用也没了（引用会变成**悬空引用**）。
     * 没引用 → 照旧"先做再给撤销"；有引用（或**没能确认**）→ 先把后果说清楚，
     * 用户点「仍要删除」才真删（[friendDeleteConfirmBodyOf]）。
     *
     * 验证**只读**、不改任何数据；撤销窗口里那一句仍带着同样的结论 —— 删完可能反悔，
     * 那时还需要知道"还得去哪一页收拾"（[friendDeleteNoticeOf]）。
     *
     * 点删除到弹框之间会去读一次文件（很小），期间用户连点第二行是做不到的：按钮要先左滑露出来。
     */
    fun deleteAt(index: Int) {
        val entry = loadedList?.entries?.getOrNull(index) ?: return
        revealedKey = null // 删掉了就别再留着"已露出"的那一格
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                FriendReferences.of(context, listOf(entry.name))
            }
            if (friendDeleteConfirmBodyOf(hits) == null) {
                commitDelete(index, entry, hits)
            } else {
                ask = FriendDeleteAsk(index, entry, hits)
            }
        }
    }

    fun undoDelete() {
        val pending = pendingUndo ?: return
        // 撤销的窗口期间可能又删了别人 → 位置要夹回当前清单范围内（insert 越界会抛错）
        applyEdit { it.insert(pending.index.coerceIn(0, it.size), pending.entry) }
        pendingUndo = null
    }

    // 撤销窗口：Snackbar 上点「撤销」才恢复，超时才关掉这个窗口
    // （消息里已含删除时的引用验证结论：这个人还在被谁用着）
    // 时长取 Long（约 10s）：删除不可逆、这里又是**唯一的挽回入口** —— 用户口径"撤销按钮驻留太短"（2026-09-22）
    LaunchedEffect(pendingUndo) {
        val pending = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = pending.message,
            actionLabel = context.getString(R.string.friend_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) undoDelete() else pendingUndo = null
    }

    // 整体清空的撤销窗口（"其中几条还被引用"已经在**动手前的确认框**里说过，这里只说清了多少位）
    LaunchedEffect(pendingClearUndo) {
        val before = pendingClearUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.friend_cleared, before.size),
            actionLabel = context.getString(R.string.friend_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            applyEdit { before } // 整份写回：顺序、内容都原样还回来
        }
        // **必须最后**：它是本 LaunchedEffect 的 key，提前清空会把本协程连同 showSnackbar 一起取消
        pendingClearUndo = null
    }

    // 清空确认框一打开就把"其中几条还被引用"查出来（清空的破坏面最大，信息要在动手前给）
    LaunchedEffect(clearing) {
        if (!clearing) {
            clearRef = null
            return@LaunchedEffect
        }
        clearRef = FriendClearRef.Checking
        val names = loadedList?.entries.orEmpty().map { it.name }
        val hits = withContext(Dispatchers.IO) { FriendReferences.of(context, names) }
        clearRef = if (hits.failure != null) {
            FriendClearRef.Failed(hits.failure)
        } else {
            FriendClearRef.Done(hits.referenced.size)
        }
    }

    // 改名的结果（尤其"下游引用有没有跟着改"）：与删除 / 清空同一套底部提示，不占用页头的错误位
    LaunchedEffect(renameNotice) {
        val notice = renameNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = notice, duration = SnackbarDuration.Short)
        // **必须最后**：它是本 LaunchedEffect 的 key（与上面两个撤销窗口同一个坑）
        renameNotice = null
    }

    FriendListScreen(
        state = state,
        entries = dragPreview ?: loadedList?.entries.orEmpty(),
        message = message,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onAdd = {
            editingIndex = null
            editorOpen = true
        },
        onEdit = { index ->
            editingIndex = index
            editorOpen = true
        },
        onDelete = { index -> deleteAt(index) },
        preview = dragPreview,
        draggingKey = draggingKey,
        dragOffsetY = dragAccumPx,
        onDragStart = { index ->
            val current = loadedList?.entries.orEmpty()
            draggingKey = current.getOrNull(index)?.name
            dragStartIndex = index
            dragCurrentIndex = index
            dragAccumPx = 0f
            dragPreview = current
        },
        onDragBy = { dy ->
            dragAccumPx += dy
            // 落位单位 = 行高 + 行距（dp 常量，不写死像素）
            val pitch = with(densityRef) { (FriendRowHeight + FriendRowSpacing).toPx() }
            while (abs(dragAccumPx) >= pitch) {
                val step = if (dragAccumPx > 0) 1 else -1
                val size = dragPreview?.size ?: 0
                val target = (dragCurrentIndex + step).coerceIn(0, (size - 1).coerceAtLeast(0))
                if (target != dragCurrentIndex) {
                    dragPreview = dragPreview?.toMutableList()?.also {
                        val moved = it.removeAt(dragCurrentIndex)
                        it.add(target, moved)
                    }
                    dragCurrentIndex = target
                }
                dragAccumPx -= step * pitch
            }
        },
        onDragEnd = {
            val from = dragStartIndex
            val to = dragCurrentIndex
            // 松手**才落盘一次**（拖动途中写盘会把手指还按着的那一行顶掉，ADR-006）
            if (from >= 0 && to >= 0 && from != to) {
                applyEdit { list ->
                    var result = list
                    var cursor = from
                    while (cursor < to) {
                        result = result.move(cursor, +1)
                        cursor++
                    }
                    while (cursor > to) {
                        result = result.move(cursor, -1)
                        cursor--
                    }
                    result
                }
            }
            draggingKey = null
            dragStartIndex = -1
            dragCurrentIndex = -1
            dragAccumPx = 0f
            dragPreview = null
        },
        revealedKey = revealedKey,
        swipingKey = swipingKey,
        swipingFraction = swipingFraction,
        listState = listState,
        onSwipeStart = { key ->
            // 开始滑别处 → 先把原来露出的收起来（同一时刻只允许一行露出）
            revealedKey = null
            swipingKey = key
        },
        onSwipe = { _, fraction -> swipingFraction = fraction },
        onSwipeEnd = { key, fraction, velocityX ->
            // 吸附判据走统一的纯函数（与服务器清单同一套，不另写一遍）
            val reveal = ServerListGestures.swipeRevealsDeleteButton(
                revealFraction = fraction,
                velocityXPx = velocityX,
                density = density,
            )
            swipingKey = null
            revealedKey = if (reveal) key else null
        },
        onCollapseRevealed = { revealedKey = null },
        onClear = { clearing = true },
        onRebuild = { applyEdit { it.clear() } },
    )

    if (editorOpen) {
        val index = editingIndex
        val editingFriend = index?.let { loadedList?.entries?.getOrNull(it) }
        FriendNameEditor(
            isNew = index == null,
            initial = editingFriend?.name.orEmpty(),
            // 正在编辑的这条自己不算重名，否则连名字里打错一个字都改不了
            takenNames = loadedList?.entries.orEmpty()
                .filterIndexed { i, _ -> i != index }
                .map { it.name },
            onDismiss = { editorOpen = false },
            onConfirm = { name ->
                val entry = FriendEntry.of(name)
                if (index == null) {
                    applyEdit { it.add(entry) }
                } else {
                    // 改名**不是**普通编辑：名字被下游（拜访规则 / 标定产物）引用着，见 [renameAt]
                    renameAt(index, entry)
                }
                editorOpen = false
            },
        )
    }

    if (clearing) {
        val count = loadedList?.size ?: 0
        // 正文按引用查询的三态分档（还在查 → 先用普通那一句；查到 → 说有几条；没查成 → 如实加一句）
        val body = when (val ref = clearRef) {
            is FriendClearRef.Done -> context.getString(friendClearBodyOf(ref), count, ref.referenced)
            is FriendClearRef.Failed -> context.getString(friendClearBodyOf(ref), count, ref.reason)
            else -> context.getString(R.string.friend_clear_body, count)
        }
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.friend_clear_title)) },
            text = { Text(body) },
            confirmButton = {
                Button(
                    onClick = {
                        val before = loadedList
                        clearing = false
                        // 清空前那一份留作撤销快照（null = 本来就空，那种情况按钮也点不到）
                        if (before != null) {
                            applyEdit { it.clear() }
                            pendingClearUndo = before
                        }
                    },
                ) {
                    Text(stringResource(R.string.friend_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) {
                    Text(stringResource(R.string.friend_cancel))
                }
            },
        )
    }

    // **有引用（或没能确认）时先问一句**（2026-09-22 用户口径）：删除不可逆、且引用会变成悬空，
    // 把"删了之后会怎样"讲在动手前，比删完再靠撤销挽回强。没引用则压根不会走到这里（见 [deleteAt]）。
    val asking = ask
    val askingBody = asking?.let { friendDeleteConfirmBodyOf(it.hits) }
    if (asking != null && askingBody != null) {
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.friend_delete_title, asking.entry.name)) },
            // 第二格由分档决定：失败原因是 String（%1$s）、条数是 Int（%1$d）。
            // 两个分支互斥，所以「分档函数 + hits.arg」这一对是配得上的（有单测钉住）。
            text = { Text(stringResource(askingBody, asking.hits.arg)) },
            confirmButton = {
                Button(
                    onClick = {
                        ask = null
                        commitDelete(asking.index, asking.entry, asking.hits)
                    },
                ) {
                    Text(stringResource(R.string.friend_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { ask = null }) {
                    Text(stringResource(R.string.friend_cancel))
                }
            },
        )
    }
}

@Composable
private fun FriendListScreen(
    state: FriendListState,
    entries: List<FriendEntry>,
    message: String?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    /** 拖动中的预览顺序（null = 用盘上顺序）。 */
    preview: List<FriendEntry>?,
    draggingKey: String?,
    dragOffsetY: Float,
    onDragStart: (Int) -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    revealedKey: String?,
    swipingKey: String?,
    swipingFraction: Float,
    listState: LazyListState,
    onSwipeStart: (String) -> Unit,
    onSwipe: (String, Float) -> Unit,
    onSwipeEnd: (String, Float, Float) -> Unit,
    onCollapseRevealed: () -> Unit,
    onClear: () -> Unit,
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.friend_list_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.friend_back_to_auth))
                }
            }
            Text(
                text = stringResource(R.string.friend_list_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.friend_list_count, entries.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onAdd, modifier = Modifier.widthIn(min = 88.dp)) {
                    Text(stringResource(R.string.friend_add))
                }
            }

            when (state) {
                is FriendListState.Failed -> {
                    Text(
                        text = stringResource(R.string.friend_load_failed, state.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.friend_load_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRebuild) {
                        Text(stringResource(R.string.friend_rebuild))
                    }
                }
                is FriendListState.Loaded -> {
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (entries.isEmpty()) {
                        // 空态：居中说明 + 一个明确的主按钮（发现性靠它，不是靠"猜还有隐藏手势"）
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.friend_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(onClick = onAdd) {
                                    Text(stringResource(R.string.friend_add))
                                }
                            }
                        }
                    } else {
                        // 手势的**发现性**全靠这一行常驻提示：没有可见按钮，不告诉用户就没人知道长按能删
                        Text(
                            text = stringResource(R.string.friend_list_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            state = listState,
                            // 行与行之间用**间隙**分隔（服务器清单同一套）：不再画分隔线，
                            // 卡片本身已经有边界，再描一条线是多余的
                            verticalArrangement = Arrangement.spacedBy(FriendRowSpacing),
                        ) {
                            itemsIndexed(entries, key = { _, item -> item.name }) { index, entry ->
                                FriendRow(
                                    name = entry.name,
                                    index = index,
                                    total = entries.size,
                                    revealed = revealedKey == entry.name,
                                    anyRevealed = revealedKey != null,
                                    liveFraction = if (swipingKey == entry.name) swipingFraction else null,
                                    onEdit = { onEdit(index) },
                                    onDelete = { onDelete(index) },
                                    dragging = draggingKey == entry.name,
                                    dragOffsetY = if (draggingKey == entry.name) dragOffsetY else 0f,
                                    onDragStart = { onDragStart(index) },
                                    onDragBy = onDragBy,
                                    onDragEnd = onDragEnd,
                                    onSwipeStart = { onSwipeStart(entry.name) },
                                    onSwipe = { onSwipe(entry.name, it) },
                                    onSwipeEnd = { fraction, velocityX ->
                                        onSwipeEnd(entry.name, fraction, velocityX)
                                    },
                                    onCollapse = onCollapseRevealed,
                                )
                            }
                        }
                        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.friend_clear))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一行好友：交互全部交给 [SwipeActionRow]（2026-09-15 抽出来的统一契约）。
 *
 * - **点按 = 改名**（安卓设置类列表的肌肉记忆）；
 * - **左滑 → 点红色「删除」**（与服务器清单同一套，行自己不动、按钮盖上来）；
 * - **长按 = 菜单**（编辑 / 删除），作为手不稳时的冗余通道；
 * - 行内**没有任何常驻按钮**（用户否掉过：尾部常驻「编辑 / 删除」是 PC 端做法）。
 *
 * 读屏既没有"左滑"也没有"长按" → `customActions` 是他们**唯一**的编辑 / 删除路径，不是加分项。
 */
@Composable
private fun FriendRow(
    name: String,
    index: Int,
    total: Int,
    revealed: Boolean,
    anyRevealed: Boolean,
    liveFraction: Float?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** 长按 = **拖动排序**（与服务器清单 / 跑号清单同一套，不再弹编辑·删除菜单）。 */
    dragging: Boolean,
    dragOffsetY: Float,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSwipeStart: () -> Unit,
    onSwipe: (Float) -> Unit,
    onSwipeEnd: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
) {
    val density = LocalDensity.current.density
    val revealWidthPx = remember(density) { ServerListGestures.REVEAL_WIDTH_DP * density }
    // 跟手时用**即时值**（snap），松手后才用 180ms 吸附 —— 跟手带动画会有橡皮筋一样的滞后感
    val target = liveFraction ?: if (revealed) 1f else 0f
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (liveFraction != null) snap() else tween(180, easing = FastOutSlowInEasing),
        label = "friendRowReveal",
    )

    // 资源必须在 @Composable 作用域里取：semantics 的 lambda 不是 Composable 作用域
    val description = stringResource(R.string.friend_row_desc, index + 1, total, name)
    val editLabel = stringResource(R.string.friend_row_edit)
    val deleteLabel = stringResource(R.string.friend_row_delete)

    SwipeActionRow(
        modifier = modifier,
        key = name,
        revealed = revealed,
        anyRevealed = anyRevealed,
        revealWidthPx = revealWidthPx,
        revealFraction = fraction,
        rowHeight = 56.dp,
        onDelete = onDelete,
        onTap = onEdit,
        dragEnabled = true,
        dragging = dragging,
        // 跟手：剩余位移交给这一行（顺序换了但位移没给，看起来就是"跳"）
        dragOffsetY = dragOffsetY,
        onDragStart = onDragStart,
        onDragBy = { _, dy -> onDragBy(dy) },
        // SwipeActionRow 会带一个"是否被系统打断"的参数，这里不需要（被打断与正常松手都走同一个收尾）
        onDragEnd = { onDragEnd() },
        onCollapse = onCollapse,
        onSwipeStart = onSwipeStart,
        onSwipe = onSwipe,
        onSwipeEnd = onSwipeEnd,
        action = {
            // 删除按钮：盖在行尾，**通用组件**（颜色与服务器清单同源，不再各自设色）
            DeleteActionButton(label = deleteLabel, onDelete = onDelete)
        },
        content = {
            // **卡片式**，与服务器清单 / 跑号清单同一形态（之前这里是裸的一行文字，三页不一致）
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = if (dragging) {
                    // 拖动中换底色：明确"这一条正被拎着"
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            customActions = listOf(
                                CustomAccessibilityAction(editLabel) { onEdit(); true },
                                CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                            )
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/**
 * 单字段编辑对话框。
 *
 * **保存按钮始终可点** —— 置灰的按钮收不到点击事件，"为什么不能点"只能靠猜（沿用标定页口径：
 * 点了才一次性把缺什么说出来）。
 */
@Composable
private fun FriendNameEditor(
    isNew: Boolean,
    initial: String,
    takenNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    // 同样的道理：文案在 Composable 体内先取好，[confirm] 这个局部函数才能不带 @Composable
    val requiredText = stringResource(R.string.friend_name_required)
    val duplicateText = stringResource(R.string.friend_name_duplicate)
    val invalidText = stringResource(R.string.friend_name_invalid)

    fun confirm() {
        val name = text.trim()
        val reason = when {
            name.isEmpty() -> requiredText
            takenNames.any { it == name } -> duplicateText
            !FriendList.isValidName(name) -> invalidText
            else -> null
        }
        if (reason != null) {
            error = reason
        } else {
            onConfirm(name)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) stringResource(R.string.friend_editor_title_new)
                else stringResource(R.string.friend_editor_title_edit),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.friend_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.friend_editor_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { confirm() }) { Text(stringResource(R.string.friend_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_cancel)) }
        },
    )
}
