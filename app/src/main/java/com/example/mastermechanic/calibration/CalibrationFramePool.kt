package com.example.mastermechanic.calibration

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import com.example.mastermechanic.log.MmLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * 标定帧样本池（T1-5b）：从采集会话的帧管线录制目标画面样本，PNG 落盘到应用私有目录。
 *
 * - 录制开关由标定界面驱动（[setRecording]）；帧线程经 [maybeCapture] 每秒最多保存 1 帧；
 * - 只保存「目标应用位于前台」的帧：单应用共享下非前台本无帧到达，整屏共享路径下由前台
 *   信号兜底——样本池只含游戏画面（若混入其他画面，标定者会在缩略图中看到并剔除）；
 * - PNG 无损保存（模板提取以原始分辨率为准，JPEG 伪影会污染模板）；文件名按保存时刻
 *   毫秒补零（字典序 = 时间序）；数量达上限自动停止录制（防占满存储）。
 */
object CalibrationFramePool {

    private const val TAG = "MM-Calibration"
    private const val SAVE_INTERVAL_MS = 1000L
    private const val MAX_FRAMES = 120

    /**
     * 单帧删除的暂存上限（[deleteFrame]）：只保留最近这么多帧。
     *
     * 帧是全分辨率 PNG（几 MB 一张），而暂存区存在的意义只是"给刚删错的那一下留个后路" ——
     * 攒 20 帧足够覆盖"删了一串才发现删错"，又不至于白占上百 MB。
     */
    private const val UNDO_TRASH_LIMIT = 20

    /**
     * 回到前台后**先等这么久**才允许存第一帧（2026-09-24）：滤掉"信号已说前台、画面还是上一屏"的缝隙，
     * 免得帧池里混进一张竖屏 App 的脏帧（见 [maybeCapture]）。
     */
    private const val WARMUP_AFTER_FOREGROUND_MS = 2_000L

    /** 录制开关：UI 线程写、帧线程读。仅进程内有效（进程被杀即复位）。 */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** 上次保存时刻（elapsedRealtime；仅帧线程访问）。 */
    private var lastSavedAt = 0L

    /**
     * 「目标前台」是从哪一刻开始连续的（elapsedRealtime；0 = 此刻不在前台）。仅帧线程访问。
     *
     * 用途见 [maybeCapture] 的"开存前先等 [WARMUP_AFTER_FOREGROUND_MS]"。
     */
    private var foregroundSinceMs = 0L

    fun setRecording(context: Context, enabled: Boolean) {
        if (isRecording == enabled) return
        isRecording = enabled
        lastSavedAt = 0L
        // 每次都从零起算：保证"开录后也要先等满 [WARMUP_AFTER_FOREGROUND_MS]"这条规则不会被上一次的值绕过
        foregroundSinceMs = 0L
        MmLog.i(
            TAG,
            if (enabled) {
                "标定帧录制开始：每秒 1 帧，仅保存目标前台帧，且**起步先等 ${WARMUP_AFTER_FOREGROUND_MS / 1000} 秒**" +
                    "（已存 ${listFrames(context).size} 帧）"
            } else {
                "标定帧录制停止（已存 ${listFrames(context).size} 帧）"
            },
        )
    }

    /**
     * 帧到达时的录制点（帧线程调用；调用方保证 [image] 尚未关闭）。
     * 仅在录制开启、目标前台且距上次保存满间隔时保存；失败只记录不抛出（不阻塞帧管线）。
     */
    fun maybeCapture(context: Context, image: Image, now: Long, targetForeground: Boolean) {
        if (!isRecording) return
        // **回到前台后先等 [WARMUP_AFTER_FOREGROUND_MS] 再开存**（2026-09-24 真机 15:19 实录，
        // 见 progress 第 176 条）：录制判据用的是"目标前台"这个**信号**，而信号**比画面内容早翻转半拍** ——
        // 从本 App 切回游戏的一瞬，"信号说前台了、缓冲里还是上一帧的竖屏 App 画面"（我们截的缓冲是横屏，
        // 那一帧就是"中间一条竖图 + 左右黑边"），于是**存下来的第一张是脏帧**。
        // 为什么按**时间**而不是按"连续 N 帧"：帧率随画面是否变化浮动（静止时可能 1 秒才一帧、
        // 动起来时 60fps）⇒ "2 帧"有时只有 60ms，滤不掉这个缝隙。等满 2 秒是确定性的，
        // 也正好等于"人眼看上去画面已经稳在游戏里"。
        if (!targetForeground) {
            foregroundSinceMs = 0L
            return
        }
        if (foregroundSinceMs == 0L) foregroundSinceMs = now
        if (now - foregroundSinceMs < WARMUP_AFTER_FOREGROUND_MS) return
        if (lastSavedAt != 0L && now - lastSavedAt < SAVE_INTERVAL_MS) return
        lastSavedAt = now
        try {
            if (listFrames(context).size >= MAX_FRAMES) {
                setRecording(context, false)
                MmLog.w(TAG, "标定帧池已达上限 $MAX_FRAMES 帧，自动停止录制")
                return
            }
            saveFrame(context, image, now)
        } catch (t: RuntimeException) {
            MmLog.w(TAG, "标定帧保存失败（跳过本帧）：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 帧池目录（可能不存在）。 */
    fun framesDir(context: Context): File = File(File(context.filesDir, "calibration"), "frames")

    /**
     * **回收站**目录（"清空"其实是把帧移进来，不是删掉）。
     *
     * 为什么要有它（2026-09-20 用户口径：清空帧池要能恢复）：帧池是**在游戏里跑一遍各界面才录得到**的素材，
     * 清错了重录的成本很高（要重新走一遍流程 + 重新框选）。所以清空做成**可撤销**的软删除。
     */
    fun trashDir(context: Context): File = File(File(context.filesDir, "calibration"), "frames-trash")

    /** 现有帧文件（按名称排序：名称即保存时刻的补零毫秒数）。**不含回收站**。 */
    fun listFrames(context: Context): List<File> =
        framesDir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 可恢复的帧数（回收站里的）；0 = 没有可恢复的清空。 */
    fun restorableCount(context: Context): Int = trashFrames(context).size

    private fun trashFrames(context: Context): List<File> =
        trashDir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * **清空**帧池（可撤销）：把帧移进 [trashDir] 而不是删掉，返回移走的帧数。
     *
     * **回收站只保留一代**：本次清空前先把上一代的文件真正删掉 —— 磁盘占用有上界（最多两代），
     * 而"刚清错的那一次"一定救得回来。要更长的历史就得引入体积与清理策略，不值得。
     *
     * 同目录内 `renameTo` 即可（同一文件系统），失败才退化成真删。
     */
    fun clear(context: Context): Int {
        val frames = listFrames(context)
        val trash = trashDir(context)
        val previous = trashFrames(context)
        previous.forEach { it.delete() }
        trash.mkdirs()
        var moved = 0
        frames.forEach { frame ->
            if (frame.renameTo(File(trash, frame.name))) moved++ else frame.delete()
        }
        MmLog.i(TAG, "标定帧池已清空（$moved 帧移入回收站，可撤销；上一代 ${previous.size} 帧已真删）")
        return moved
    }

    /** 恢复上次清空的帧（从回收站移回帧池）；返回恢复的帧数，没有可恢复的返回 0。 */
    fun restore(context: Context): Int {
        val trash = trashFrames(context)
        if (trash.isEmpty()) return 0
        val dir = framesDir(context)
        dir.mkdirs()
        var moved = 0
        trash.forEach { frame ->
            if (frame.renameTo(File(dir, frame.name))) moved++
        }
        MmLog.i(TAG, "标定帧池已恢复 $moved 帧（来自回收站）")
        return moved
    }

    /**
     * **单帧删除**的暂存目录：挂在回收站[trashDir]下面**单开一层**。
     *
     * 为什么不直接丢进回收站（2026-09-23 加单帧删除时定的）：回收站是**整代**语义 ——
     * [clear] 会先把上一代真删、[restore] 会把里面**全部**帧移回来。单帧删除若混进去，两件事就互相污染：
     * "清空一次"把之前单独删掉的几帧也一起抹掉、"恢复上次清空"又把它们一起捞回来。
     * 单开一层之后，[trashFrames] 那条筛选（只列 `*.png` **文件**、不递归）天然看不到它，两边各管各的。
     */
    private fun singleTrashDir(context: Context): File = File(trashDir(context), "single")

    /**
     * **删除单帧**（可撤销）：移进 [singleTrashDir] 而不是真删，返回是否成功。
     *
     * 同目录内 `renameTo` 即可（同一文件系统），失败才退化成真删。
     * 超过 [UNDO_TRASH_LIMIT] 时先删掉最旧的那几帧：磁盘占用有上界，而"刚删错的那一帧"一定救得回来。
     */
    fun deleteFrame(context: Context, frame: File): Boolean {
        val dir = singleTrashDir(context)
        // 建不出暂存目录（极少见）→ 退化成真删，行为与"删除"这个语义一致，只是丢了撤销
        if (!dir.exists() && !dir.mkdirs()) return frame.delete()
        val stashed = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()
        // 名称 = 保存时刻（补零毫秒），字典序即时间序 ⇒ 多出来的就是最旧的那几帧
        stashed.take((stashed.size + 1 - UNDO_TRASH_LIMIT).coerceAtLeast(0)).forEach { it.delete() }
        val moved = frame.renameTo(File(dir, frame.name))
        val ok = moved || frame.delete()
        MmLog.i(TAG, "标定帧已删除：${frame.name}（移入暂存=$moved，可撤销）")
        return ok
    }

    /**
     * **撤销单帧删除**：把帧从暂存移回帧池；返回是否成功。
     *
     * false = 暂存里已经没有它（被上限挤掉、或帧池刚被清空过）—— 调用方据此如实报"恢复不了"，
     * 不要假装恢复成功。
     */
    fun restoreFrame(context: Context, frame: File): Boolean {
        val stashed = File(singleTrashDir(context), frame.name)
        if (!stashed.isFile) return false
        val dir = framesDir(context).apply { mkdirs() }
        val moved = stashed.renameTo(File(dir, frame.name))
        MmLog.i(TAG, "标定帧已恢复：${frame.name}（移回帧池=$moved）")
        return moved
    }

    private fun saveFrame(context: Context, image: Image, now: Long) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val packed = ByteArray(width * height * 4)
        if (rowStride == width * 4) {
            buffer.get(packed)
        } else {
            // 行对齐填充：逐行取有效像素，压紧后交给 Bitmap
            val row = ByteArray(width * 4)
            for (y in 0 until height) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, width * 4)
                System.arraycopy(row, 0, packed, y * width * 4, width * 4)
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
            val dir = framesDir(context).apply { mkdirs() }
            val file = File(dir, String.format(Locale.ROOT, "frame-%013d.png", now))
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            MmLog.i(TAG, "标定帧已保存：${file.name}（${width}x$height）")
        } finally {
            bitmap.recycle()
        }
    }

}
