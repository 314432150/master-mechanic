package com.example.mastermechanic.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.mastermechanic.MainActivity
import com.example.mastermechanic.R
import com.example.mastermechanic.capture.CaptureSessionSignal
import com.example.mastermechanic.capture.CaptureSessionStatus
import com.example.mastermechanic.capture.FrameThrottle

/**
 * 采集会话服务（T1-2，ADR-001）：以 mediaProjection 类型前台服务承载一次采集会话。
 *
 * - 授权凭证经 Intent 一次性传入，不落盘、不复用（ADR-001 第 1 条）；
 * - Android 14+ 顺序要求：先 startForeground（mediaProjection 类型）再创建会话；
 * - 会话终止两条路径（系统侧回收 / 应用主动停止）均汇聚到 [CaptureSessionSignal]，
 *   输出带来源的终态日志（验收 A1 证据本体）；「用户切到别的应用」不触发终止（FR-09）；
 * - 帧管线：ImageReader 取帧 → [FrameThrottle] 节流 → 处理点（识别循环于 T1-4 接入），
 *   每 10s 输出一条存活统计；全程零点击（红线 1/2）。
 *
 * 分辨率 / 密度运行时从系统读取（ADR-001 第 4 条：零设备常量）。
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameThrottle = FrameThrottle(FRAME_THROTTLE_MS)

    /** 会话终止来源；null 表示会话仍存活。仅主线程访问。 */
    private var stopSource: String? = null

    // 帧管线统计（仅帧线程访问）
    private var framesReceived = 0L
    private var framesProcessed = 0L
    private var lastStatsAt = 0L
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 系统侧终止（用户在系统 UI 停止 / 会话被回收）：本回调是唯一信号（ADR-001 第 3 条）
            if (stopSource == null) stopSource = SOURCE_SYSTEM_STOP
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            // FR-08：不静默失败——凭证缺失时明确记录并退出，不留下无会话的空服务
            Log.w(TAG, "采集会话启动失败：缺少授权凭证，服务退出（需在前台界面重新授权）")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        startSession(resultCode, resultData)
        // 凭证一次性：进程被杀后重启无凭证可用，不自动复活（不同于守护服务）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (stopSource == null) stopSource = SOURCE_APP_STOP
        releaseSession()
        CaptureSessionSignal.update(CaptureSessionStatus.INACTIVE, stopSource ?: SOURCE_APP_STOP)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(resultCode: Int, resultData: Intent) {
        releaseSession()
        stopSource = null

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Exception) {
            Log.w(TAG, "创建采集会话失败：${t.javaClass.simpleName} ${t.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            Log.w(TAG, "创建采集会话失败：系统未返回会话实例")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader

        val thread = HandlerThread("MM-CaptureFrames")
        thread.start()
        frameThread = thread
        val frameHandler = Handler(thread.looper)
        reader.setOnImageAvailableListener({ handleFrameAvailable() }, frameHandler)

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            frameHandler,
        )

        frameThrottle = FrameThrottle(FRAME_THROTTLE_MS)
        framesReceived = 0
        framesProcessed = 0
        lastStatsAt = SystemClock.elapsedRealtime()
        CaptureSessionSignal.update(CaptureSessionStatus.ACTIVE, SOURCE_USER_CREATED)
    }

    private fun releaseSession() {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        // 先注销回调再 stop：避免主动释放路径误报为「系统侧回收」
        mediaProjection?.unregisterCallback(projectionCallback)
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
            // 会话已被系统侧终止时 stop 可能抛异常，终止流程不受影响
        }
        mediaProjection = null
        frameThread?.quitSafely()
        frameThread = null
    }

    /**
     * 帧到达回调（帧线程）：取最新帧并释放旧帧，节流后交给处理点。
     *
     * 处理点当前仅记录帧元数据（管线存活证据）；T1-4 识别循环在此接入，帧数据不落盘。
     */
    private fun handleFrameAvailable() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            return // 会话释放竞态：reader 已关闭
        } ?: return

        framesReceived++
        val now = SystemClock.elapsedRealtime()
        if (frameThrottle.shouldProcess(now)) {
            framesProcessed++
            lastFrameWidth = image.width
            lastFrameHeight = image.height
        }
        image.close()
        maybeLogStats(now)
    }

    private fun maybeLogStats(now: Long) {
        if (now - lastStatsAt < STATS_WINDOW_MS) return
        Log.i(
            TAG,
            "帧管线统计: 窗口 ${now - lastStatsAt}ms 接收 $framesReceived 帧 / 处理 $framesProcessed 帧；" +
                "最近帧 ${lastFrameWidth}x$lastFrameHeight",
        )
        lastStatsAt = now
        framesReceived = 0
        framesProcessed = 0
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {

        private const val TAG = CaptureSessionSignal.TAG
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 2
        private const val VIRTUAL_DISPLAY_NAME = "MM-Capture"

        /** T1-2 固定节流间隔：仅维持帧管线；NFR-02 自适应间隔随识别循环（T1-4）接入。 */
        private const val FRAME_THROTTLE_MS = 1000L
        private const val STATS_WINDOW_MS = 10_000L
        private const val MAX_IMAGES = 2

        private const val EXTRA_RESULT_CODE = "capture_result_code"
        private const val EXTRA_RESULT_DATA = "capture_result_data"

        private const val SOURCE_USER_CREATED = "用户授权会话建立"
        private const val SOURCE_SYSTEM_STOP = "系统侧回收"
        private const val SOURCE_APP_STOP = "应用主动停止"

        /** 由前台界面在用户完成系统采集授权后调用；凭证只经内存传递。 */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
