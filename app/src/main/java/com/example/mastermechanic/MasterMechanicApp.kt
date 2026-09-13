package com.example.mastermechanic

import android.app.Application
import com.example.mastermechanic.log.MmLog

/**
 * 应用入口（T2-7）：只做一件事——把业务日志的**落盘通道**装上。
 *
 * 刻意不在这里做任何与识别 / 点击相关的初始化：红线 1 / 2 的约束不因本类而改变
 * （点击的唯一出口仍是 `ClickDispatch`，模式默认演练）。
 */
class MasterMechanicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MmLog.install(this)
        MmLog.i("MM-Log", "日志落盘已启用：${MmLog.currentFile()?.absolutePath ?: "（路径未就绪）"}")
    }
}
