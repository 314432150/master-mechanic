package com.example.mastermechanic

import android.app.Application
import com.example.mastermechanic.log.MmLog
import com.example.mastermechanic.notify.FloatingNotifier

/**
 * 应用入口（T2-7）：只做最轻量的初始化——
 * ① 把业务日志的**落盘通道**装上；
 * ② 把 **Toast 通知**挂上（2026-09-17 用户口径"取消状态标签，采用 toast 提醒"）。
 *
 * 刻意不在这里做任何与识别 / 点击相关的初始化：红线 1 / 2 的约束不因本类而改变
 * （点击的唯一出口仍是 `ClickDispatch`，模式默认实点）。
 */
class MasterMechanicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MmLog.install(this)
        MmLog.i("MM-Log", "日志落盘已启用：${MmLog.currentFile()?.absolutePath ?: "（路径未就绪）"}")
        FloatingNotifier.install(this)
    }
}
