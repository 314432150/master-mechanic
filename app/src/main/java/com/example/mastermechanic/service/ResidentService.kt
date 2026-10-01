package com.example.mastermechanic.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mastermechanic.MainActivity
import com.example.mastermechanic.R
import com.example.mastermechanic.action.ClickDispatch
import com.example.mastermechanic.patrol.PatrolRequestConsumer
import com.example.mastermechanic.patrol.PatrolSession

/**
 * 常驻守护服务（FR-08）：由用户在前台界面主动启动，以系统通知常驻展示运行状态。
 *
 * - Android 14+ 要求前台服务声明类型；「守护」无法归入官方分类，取 specialUse 兜底类型；
 * - 被系统回收后由 START_STICKY 重新拉起（用户经界面停止则不复活）；
 * - M1 起在此承载识别与巡查的常驻循环；本阶段仅维持常驻与通知（A3 证据本体）。
 */
class ResidentService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // M4-T4-4c：菜单请求（开始 / 继续 / 停止）的消费方挂在这里 ——
        // 常驻服务与进程同寿，比采集服务（会话级、会重建）更适合持有这个订阅。
        // 带上上下文：顺序轮换要在启动前读服务器清单 + 游标（2026-09-24）
        PatrolRequestConsumer.install(this)
        // 2026-09-20 用户口径：**守护是运行自动化的必备项** —— 没它不许点击（门禁会如实拒绝并留痕）。
        // 置位放在这里而不是"装了消费者之后"：两者本来就是同一件事的两面（有守护才有消费方）。
        ClickDispatch.setGuardRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        PatrolRequestConsumer.uninstall()
        // 守护停止 = 自动化整体停（门禁随即拒绝所有点击，不只是"没消费方"）
        ClickDispatch.setGuardRunning(false)
        // 常驻服务都没了 → 没人再消费菜单请求，进行中的跑号也一并收掉
        PatrolSession.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        // minSdk 34 起始终带类型
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.resident_notification_title))
            .setContentText(getString(R.string.resident_notification_text))
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
            getString(R.string.resident_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.resident_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "resident"
        private const val NOTIFICATION_ID = 1

        /** 查询常驻服务是否在运行（仅查询本应用自身服务，无需额外权限）。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ResidentService::class.java.name }
        }
    }
}
