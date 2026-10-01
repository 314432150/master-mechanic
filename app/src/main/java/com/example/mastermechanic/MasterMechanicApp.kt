package com.example.mastermechanic

import android.app.Application
import com.example.mastermechanic.log.MainThreadWatchdog
import com.example.mastermechanic.log.MmLog

/**
 * 应用入口（T2-7）：只做最轻量的初始化 —— 把业务日志的**落盘通道**装上。
 *
 * 曾经还在这里挂底部 Toast 通知（`FloatingNotifier.install`）；2026-09-29 用户口径
 * （"既然顶部增加了悬浮窗，那么底部的 toast 是不是可以去除了"）**整体移除**：
 * 它承载的三类内容都有了去处 ——
 * ① 菜单动作的"已发出"确认：菜单收起 + 顶部标签立刻显示正在做的事；
 * ② 跑号中止 / 暂停 / 完成：顶部标签**常驻**显示（比 3 秒的 toast 更好）；
 * ③ 读屏播报：改由标签在结局变化时 `announceForAccessibility` 一次（见 `FloatingWindow`）。
 *
 * 也曾在这里装过 `ActivityLifecycleCallbacks` 维护"我们自己的界面是否在前台"（`OwnAppFront`），
 * 供无障碍服务短路 `windows…root` 的建树代价 —— **2026-09-29 当天就整体撤掉了**：
 * 那条判据一旦卡住就会让前台判定永远返回"不在前台"，游戏里悬浮窗被每 ~1.4 秒摘掉一次
 * （闪烁），反复 `addView/removeView` 又把主线程拖到 ANR（详见 `MasterMechanicAccessibilityService`）。
 * 结论：**前台判定只看系统事实（活动窗口 / 事件包名），不要镜像我们自己的生命周期状态。**
 *
 * 刻意不在这里做任何与识别 / 点击相关的初始化：红线 1 / 2 的约束不因本类而改变
 * （点击的唯一出口仍是 `ClickDispatch`，且只有真实点击一种行为）。
 */
class MasterMechanicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MmLog.install(this)
        // **主线程卡死看门狗**（2026-09-29）：ANR 栈在真机上拿不到（`/data/anr` 要 root），
        // 于是自己在进程内取 —— 主线程卡住 >3 秒就把**卡在哪一行**写进日志。
        // 起因：有一族"黑屏崩溃"在探针上**毫无先兆**（分配 0~1.7MB/s、查询 1~3ms），
        // 没有栈就只能猜。见 [MainThreadWatchdog]。
        MainThreadWatchdog.start()
        MmLog.i("MM-Log", "日志落盘已启用：${MmLog.currentFile()?.absolutePath ?: "（路径未就绪）"}")
    }
}
