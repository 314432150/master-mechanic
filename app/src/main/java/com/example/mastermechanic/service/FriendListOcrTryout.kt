package com.example.mastermechanic.service

import android.content.Context
import android.os.SystemClock
import com.example.mastermechanic.R
import com.example.mastermechanic.capture.RgbaToGray
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.decision.UiState
import com.example.mastermechanic.patrol.FriendListOcrProbe
import com.example.mastermechanic.recognition.GrayImage
import com.example.mastermechanic.recognition.NameCandidate
import com.example.mastermechanic.recognition.PixelBounds
import com.example.mastermechanic.servers.ServerCaptureDraft
import com.example.mastermechanic.servers.ServerCaptureOutcome
import com.example.mastermechanic.servers.ServerListCapture

/**
 * **「导入服务器」的安卓侧接线**（2026-09-23 起；纯逻辑在 [ServerListCapture]，TAG 借 [FriendListOcrProbe]）。
 *
 * 由 [CaptureService] 在**帧线程上**调用：悬浮窗点「更多 → 导入服务器」→ [FriendListOcrSignal]
 * （见 `patrol/FriendListOcrSignal.kt`）→ 帧线程下一轮消费，读的是**当前实机画面的那一帧**。
 *
 * 报告同时走 [MmLog]（TAG 见 [FriendListOcrProbe.TAG]，logcat + `files/logs/`）⇒ 结果**事后可取证**：
 * 真机上展开的面板看漏了，日志里还在。
 *
 * ## 一处"看起来能用、其实是坑"的地方
 *
 * **必须用整帧灰度**：帧线程手上那份是**窗口化**灰度（识别窗口之外被置零，
 * 见 [RgbaToGray.toGrayRegions]），而服务器列表区域**不在**识别窗口集合里 ⇒
 * 直接拿它去读只会读到一整块黑，报出一个**假的**"一行都没读到"。
 * 所以本方法收原始帧数据、自己转一次完整灰度。
 *
 * （原「试读这一屏」的"读一次出一份报告"那条路 —— 好友名对账 / 服务器两列竖带 —— 已于
 * 2026-09-30 按用户口径整体移除；本文件现在只剩「导入服务器」这一条。）
 */
object FriendListOcrTryout {

    /**
     * **添加这一屏的服务器**（2026-09-24 加，2026-09-25 入口搬到**根菜单**）：
     * 读**整个屏幕** → 按屏幕结构解析 → 写进服务器清单。
     *
     * ## 为什么是"整屏 + 分块"，而不是整帧一次送
     *
     * 用户口径：**识别范围为整个屏幕**（不再依赖两条列竖带的框选 —— 那些框是为"每一步只读一小条"设计的，
     * 框窄了还会把末字切掉，真机踩过）。但整帧 3168×1440 直接放大 ×3 会得到 9504×4320 的位图（≈154MB），
     * 必然 OOM ⇒ 这里按 [CAPTURE_BLOCK_COLUMNS]×[CAPTURE_BLOCK_ROWS] 分块读，**块间留重叠**：
     * 一条区服行被切在块边界上时，它在相邻块里是完整的（解析按区服名去重，重复读到不会变成两条）。
     *
     * ## 口径
     *
     * - **前置判据：画面必须是「服务器列表」（2026-09-25 加）** —— 这个入口搬到根菜单之后，
     *   用户可能在**任何画面**点它（以前它藏在「换号 → 选服务器」里，点到时人多半已经站在选服页）。
     *   读到别的画面没有意义，更糟的是"读出来的噪声被当成区服写进清单"（第 196 条那次事故的同类）
     *   ⇒ 不在 [UiState.SERVER_SELECT] 就**连读都不读**，直接回一句"现在识别到「X」"，
     *   **一个字节都不写**（显式失败，比静默写脏数据安全）；
     * - 放大 [CAPTURE_SCALE] 倍（真机名字字高 ≈38px ⇒ 放大后 ≈76px，读得稳；分块后单块位图 ≈18MB）；
     * - **只写清单、只做加法**（见 [ServerListCapture.merge]）：已有条目**只在空字段上补全**，用户填过的不动；
     * - 清单**读不了就一个字节都不写**并如实报错（ADR-006：不静默降级、不拿坏文件当空清单）；
     * - 报告一律回一句（成功 / 读不到 / 异常），面板靠它结束"进行中"。
     */
    fun captureServers(
        context: Context,
        state: UiState,
        frameAgeMs: Long,
        area: PixelBounds?,
        bytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): ServerCaptureOutcome {
        if (state != UiState.SERVER_SELECT) {
            MmLog.i(
                FriendListOcrProbe.TAG,
                "添加这一屏的服务器：当前画面是「${state.label}」而不是服务器列表 ⇒ 不读不写",
            )
            return ServerCaptureOutcome.Nothing(
                context.getString(R.string.capture_servers_wrong_screen, state.label),
            )
        }
        if (area == null) {
            MmLog.w(FriendListOcrProbe.TAG, "添加这一屏的服务器：产物里没有「服务器列表区域」锚点 ⇒ 不读不写")
            return ServerCaptureOutcome.Nothing(context.getString(R.string.capture_servers_no_area))
        }
        return captureServerPage(context, area, frameAgeMs, bytes, width, height, rowStride)
    }

    /**
     * 真读那一步：**只在框选的「服务器列表区域」里**分块 → 解析 → 产出复核草稿。
     *
     * ## 为什么只读框选区域（2026-09-25 用户口径）
     *
     * 老做法读**整屏**，于是把屏幕别处的文字一起读进来了 —— 真机事故就是**表头右上那颗"当前区服"药丸**
     * （`◆ 微信56区 白色死神`）：它也是一条名字行，还把列表里的同一台挤到了清单第一位
     * （用户："改为读取产物配置中框选的服务器列表区域，绝对不会重复。"）。
     * 只读列表区域之后：药丸 / 侧边分区标签 / 顶部说明这些都不在输入里，
     * 解析层那条"同名多行留字段更全的"就退化成一道**兜底**（区域框得宽一点时仍能救）。
     *
     * ## 等级 / 角色名 / 区号怎么来
     *
     * 同 [ServerListCapture]：名字行 + 它的"详情带"；这里只管"读到哪些行"。
     *
     * ## 产物是**草稿**，不是落盘
     *
     * 写盘由用户在复核页点「写入清单」触发（先看后写，2026-09-25 用户口径）——
     * 所以这里**一个字节都不动**，也就没有"读的是旧画面却写进去了"这种事。
     */
    private fun captureServerPage(
        context: Context,
        area: PixelBounds,
        frameAgeMs: Long,
        bytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): ServerCaptureOutcome = try {
        val gray = RgbaToGray.toGray(bytes, width, height, rowStride)
        val blocks = blocksInArea(area, gray.width, gray.height)
        val reader = MlKitNameReader(scale = CAPTURE_SCALE, timeoutMs = CAPTURE_TIMEOUT_MS)
        val started = SystemClock.elapsedRealtime()
        val firstPass = blocks.flatMap { reader.read(gray, it) }
        // **逐行复核**（2026-09-25 真机修）：分块 ×2 读出来后，对每个"名字行"再单独放大 ×3 读一次，
        // 把每一次读数都留着 —— 解析时同一行**取名字最长的那次**（OCR 的错法以"少读"为主）。
        // 真机证据：`微信395区 莲动渔舟` 在 ×2 里读成 `莲动渔`，而同一行在 ×3 窄条里读全过。
        val recheck = recheckNameLines(gray, firstPass)
        val entries = ServerListCapture.parse(firstPass + recheck.candidates)
        val elapsedMs = SystemClock.elapsedRealtime() - started
        MmLog.i(
            FriendListOcrProbe.TAG,
            "添加这一屏的服务器：区域 x=[${area.x0},${area.x1}) y=[${area.y0},${area.y1})｜分 ${blocks.size} 块" +
                "（放大 ×$CAPTURE_SCALE）｜读到 ${firstPass.size} 行 → 解析出 ${entries.size} 台" +
                "｜逐行复核 ${recheck.checkedRows} 行（${recheck.longerRows} 行读得更全）" +
                "｜画面龄 ${frameAgeMs}ms｜合计 ${elapsedMs}ms",
        )
        if (entries.isEmpty()) {
            ServerCaptureOutcome.Nothing(context.getString(R.string.capture_servers_none))
        } else {
            // ⚠ **这张画面可能是旧的**（2026-09-25 真机事故后加）：那一轮用的不是新帧，而是静止兜底重放的
            // 缓存副本（日志实录：连续 3 分钟全是「帧：兜底重放（N ms 前拍到）」）。这里**不拦**
            // （静止画面重读一次是合法用法），但必须让用户看见，否则"又是那几条"会被当成自己没滚到位。
            val notice = if (frameAgeMs > STALE_FRAME_WARN_MS) {
                val seconds = frameAgeMs / 1000
                MmLog.w(
                    FriendListOcrProbe.TAG,
                    "添加这一屏的服务器：用的是一张 ${seconds}s 前的旧画面（兜底重放）——" +
                        "屏幕上如果刚滚过，读到的很可能还是滚之前那一屏",
                )
                context.getString(R.string.capture_servers_stale_frame, seconds)
            } else {
                null
            }
            ServerCaptureOutcome.Draft(ServerCaptureDraft(entries), notice)
        }
    } catch (t: Throwable) {
        MmLog.w(FriendListOcrProbe.TAG, "添加这一屏的服务器失败：${t.javaClass.simpleName} ${t.message}", t)
        ServerCaptureOutcome.Nothing(
            context.getString(R.string.ocr_probe_failed, t.message ?: t.javaClass.simpleName),
        )
    }

    // 注：读清单 / 合并 / 落盘**都不在这里**了 —— 2026-09-25 起采集只产出复核草稿，
    // 写盘由用户在复核页点「写入清单」触发（先看后写），落点见 `FloatingWindow.writeCaptureDraft`。

    /** 逐行复核的结果：额外读到的候选 + "复核了几行 / 其中几行读得更长"。 */
    private class Recheck(
        val candidates: List<NameCandidate>,
        val checkedRows: Int,
        val longerRows: Int,
    )

    /**
     * **逐行复核**（2026-09-25 真机事故后加）：把第一遍读出来的每个"名字行"再单独放大读一次。
     *
     * 为什么有用：整屏分块是"一眼看全"，而窄条是"贴着这一行细看" —— 真机上同一行在两者下的读数并不一样
     * （`莲动渔舟` 在整屏 ×2 里丢末字、在 ×3 窄条里读全过）。两次读数都交给 [ServerListCapture.parse]，
     * 由它**同一行取名字最长的那次** —— 这不是猜，而是"取更完整的读数"。
     *
     * 复核区域要**比行框更宽一点**（右侧外扩 [RECHECK_PAD_RATIO]）：第一遍少读一个字时，
     * 行框本身也短了一截，不外扩就正好把那个字排除在复核区域之外。
     * 纵向也带一点余量，顺带把下一行的等级 / 角色名一起读进来（[ServerListCapture.parse] 本来就要配对它们）。
     */
    private fun recheckNameLines(gray: GrayImage, firstPass: List<NameCandidate>): Recheck {
        val rows = firstPass
            .filter { ServerListCapture.looksLikeServerNameLine(it.text) }
            .take(RECHECK_MAX_ROWS)
        if (rows.isEmpty()) return Recheck(emptyList(), 0, 0)
        val reader = MlKitNameReader(scale = RECHECK_SCALE, timeoutMs = RECHECK_TIMEOUT_MS)
        val extra = ArrayList<NameCandidate>(rows.size * 2)
        var longer = 0
        rows.forEach { row ->
            val read = reader.read(gray, recheckRegion(row, gray.width, gray.height))
            extra += read
            if (read.any { it.text.length > row.text.length }) longer++
        }
        return Recheck(extra, rows.size, longer)
    }

    /** 复核区域：行框上下左右各留余量；**右边按行宽的 [RECHECK_PAD_RATIO] 外扩**（少读的字在那儿）。 */
    private fun recheckRegion(row: NameCandidate, frameWidth: Int, frameHeight: Int): PixelBounds {
        val padY = maxOf(row.height / 2, RECHECK_PAD_MIN_PX)
        val padRight = maxOf((row.width * RECHECK_PAD_RATIO).toInt(), RECHECK_PAD_MIN_PX)
        val x0 = (row.x - RECHECK_PAD_MIN_PX).coerceAtLeast(0)
        val x1 = (row.x + row.width + padRight).coerceAtMost(frameWidth)
        val y0 = (row.y - padY).coerceAtLeast(0)
        val y1 = (row.y + row.height + padY * 2).coerceAtMost(frameHeight)
        return PixelBounds(x0, y0, x1, y1)
    }

    /**
     * **框选区域**内分块（列 × 行，**块间留重叠**，见 [captureServerPage]）。
     *
     * 与"整屏分块"的差别只有一处：**一切都在 [area] 里切**（2026-09-25 用户口径：只读产物里框选的
     * 「服务器列表区域」⇒ 表头那颗"当前区服药丸"、侧边分区标签这些根本不在输入里，也就不会重复读到）。
     *
     * 重叠量固定 [CAPTURE_BLOCK_OVERLAP_PX]：区服行高 ≈40px，留 80px 保证"被块边界切到的那一行"
     * 在相邻块里是完整的；重复读到同一行由 [ServerListCapture.parse] 按位置归并。
     */
    private fun blocksInArea(area: PixelBounds, frameWidth: Int, frameHeight: Int): List<PixelBounds> {
        val x0 = area.x0.coerceIn(0, frameWidth)
        val x1 = area.x1.coerceIn(0, frameWidth)
        val y0 = area.y0.coerceIn(0, frameHeight)
        val y1 = area.y1.coerceIn(0, frameHeight)
        val width = x1 - x0
        val height = y1 - y0
        if (width <= 0 || height <= 0) return emptyList()
        val blocks = mutableListOf<PixelBounds>()
        for (row in 0 until CAPTURE_BLOCK_ROWS) {
            val blockY0 = (y0 + row * height / CAPTURE_BLOCK_ROWS -
                if (row > 0) CAPTURE_BLOCK_OVERLAP_PX else 0).coerceAtLeast(y0)
            val blockY1 = (y0 + (row + 1) * height / CAPTURE_BLOCK_ROWS +
                if (row < CAPTURE_BLOCK_ROWS - 1) CAPTURE_BLOCK_OVERLAP_PX else 0).coerceAtMost(y1)
            for (column in 0 until CAPTURE_BLOCK_COLUMNS) {
                val blockX0 = (x0 + column * width / CAPTURE_BLOCK_COLUMNS -
                    if (column > 0) CAPTURE_BLOCK_OVERLAP_PX else 0).coerceAtLeast(x0)
                val blockX1 = (x0 + (column + 1) * width / CAPTURE_BLOCK_COLUMNS +
                    if (column < CAPTURE_BLOCK_COLUMNS - 1) CAPTURE_BLOCK_OVERLAP_PX else 0)
                    .coerceAtMost(x1)
                if (blockX1 > blockX0 && blockY1 > blockY0) {
                    blocks += PixelBounds(blockX0, blockY0, blockX1, blockY1)
                }
            }
        }
        return blocks
    }

    /** 「导入服务器」的参数：读的是框选的「服务器列表区域」，分块放大 ×2（见 [blocksInArea]）。 */
    private const val CAPTURE_SCALE = 2

    /** 整屏分块：2 列 × 2 行（块间重叠，见 [captureServers]）。 */
    private const val CAPTURE_BLOCK_COLUMNS = 2
    private const val CAPTURE_BLOCK_ROWS = 2

    /** 块间重叠像素（区服行高 ≈40px，留 80px 保证被边界切到的行在邻块里是完整的）。 */
    private const val CAPTURE_BLOCK_OVERLAP_PX = 80

    /**
     * 单块识别的等待上限。一次性动作（用户点一次），放宽到 2 秒不影响流程；
     * 四块合计最坏 ≈8 秒，报告里会把实际耗时写出来（真机再决定要不要收窄）。
     */
    private const val CAPTURE_TIMEOUT_MS = 2_000L

    /**
     * 这一轮的画面**超过它就提醒"是旧画面"**（2026-09-25 真机事故后加）。
     *
     * 3 秒的来源：正常"新帧"的帧龄是**几十毫秒**（帧率 60fps、识别节流 160~1000ms）；
     * 帧龄涨到秒级只有一个原因 —— 这一轮吃的是**静止兜底重放的缓存副本**（屏幕上没有新帧）。
     * 而用户"滚一屏再点一次"时，滚动本身就会产生新帧 ⇒ 真滚了就不会触发这条提醒。
     */
    private const val STALE_FRAME_WARN_MS = 3_000L

    /**
     * 逐行复核的放大倍数：**3 倍**（与两列竖带那条"已验证读得准"的路同档），
     * 而整屏分块只用 2 倍（整屏 ×3 的位图会到 150MB+）。
     */
    private const val RECHECK_SCALE = 3

    /** 单条复核的等待上限（窄条很小，1 秒足够；超时按"这一行没复核到"处理，不影响第一遍的读数）。 */
    private const val RECHECK_TIMEOUT_MS = 1_000L

    /** 一次最多复核几行（防极端画面把时间拖长：24 行 ≈ 0.5~1s）。 */
    private const val RECHECK_MAX_ROWS = 24

    /** 复核区域向右外扩的比例（行宽的 35%）——第一遍少读一个字时，行框也短了一截。 */
    private const val RECHECK_PAD_RATIO = 0.35

    /** 复核区域的**最小**余量（像素）：行高很小的模板也要有地方装那个可能被少读的字。 */
    private const val RECHECK_PAD_MIN_PX = 12
}
