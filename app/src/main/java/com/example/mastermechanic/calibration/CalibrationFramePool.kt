package com.example.mastermechanic.calibration

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
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

    /** 录制开关：UI 线程写、帧线程读。仅进程内有效（进程被杀即复位）。 */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** 上次保存时刻（elapsedRealtime；仅帧线程访问）。 */
    private var lastSavedAt = 0L

    fun setRecording(context: Context, enabled: Boolean) {
        if (isRecording == enabled) return
        isRecording = enabled
        lastSavedAt = 0L
        Log.i(
            TAG,
            if (enabled) {
                "标定帧录制开始：每秒 1 帧，仅保存目标前台帧（已存 ${listFrames(context).size} 帧）"
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
        if (!isRecording || !targetForeground) return
        if (lastSavedAt != 0L && now - lastSavedAt < SAVE_INTERVAL_MS) return
        lastSavedAt = now
        try {
            if (listFrames(context).size >= MAX_FRAMES) {
                setRecording(context, false)
                Log.w(TAG, "标定帧池已达上限 $MAX_FRAMES 帧，自动停止录制")
                return
            }
            saveFrame(context, image, now)
        } catch (t: RuntimeException) {
            Log.w(TAG, "标定帧保存失败（跳过本帧）：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 帧池目录（可能不存在）。 */
    fun framesDir(context: Context): File = File(File(context.filesDir, "calibration"), "frames")

    /** 现有帧文件（按名称排序：名称即保存时刻的补零毫秒数）。 */
    fun listFrames(context: Context): List<File> =
        framesDir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** 清空帧池；返回删除的帧数。 */
    fun clear(context: Context): Int {
        val frames = listFrames(context)
        frames.forEach { it.delete() }
        Log.i(TAG, "标定帧池已清空（删除 ${frames.size} 帧）")
        return frames.size
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
            Log.i(TAG, "标定帧已保存：${file.name}（${width}x$height）")
        } finally {
            bitmap.recycle()
        }
    }

}
