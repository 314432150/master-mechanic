package com.example.mastermechanic.capture

import com.example.mastermechanic.log.MmLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 采集会话信号：平台适配层对外的「采集会话是否进行中」独立信号（ADR-001 / FR-09）。
 *
 * - 初始与任何不可用情形一律 [CaptureSessionStatus.INACTIVE]；
 * - 「会话终止」的唯一来源：系统侧 MediaProjection 回调或应用主动停止，两条路径均汇聚到 [update]；
 *   「用户切到别的应用」不改变本信号（FR-09 的会话区分）；
 * - 状态每次**变化**输出一条日志，即 T1-2 验收证据本体。
 */
object CaptureSessionSignal {

    const val TAG = "MM-Capture"

    private val listeners = CopyOnWriteArraySet<(CaptureSessionStatus) -> Unit>()

    @Volatile
    private var current: CaptureSessionStatus = CaptureSessionStatus.INACTIVE

    val status: CaptureSessionStatus get() = current

    val isActive: Boolean get() = current == CaptureSessionStatus.ACTIVE

    /**
     * **上一个会话是怎么结束的**（null = 本进程内还没有会话结束过）。
     *
     * 为什么要把它单独记下来：会话结束之后界面只剩"未激活"，而**"从未授权"与"授权过又断了"
     * 对用户是两件完全不同的事** —— 前者要去授权页授予，后者要去**重新**授予（凭证一次性），
     * 提示词也必须不一样（2026-09-28 用户报障：明明刚授过权，菜单却让他"先授予采集权限"）。
     *
     * 值为结束来源（与日志同一份文案，见 `CaptureService` 里的 `SOURCE_*`），例如
     * "系统侧回收" / "镜像失效：画面不再更新（请重新建立采集）"；进程重启后自然回 null。
     */
    @Volatile
    var lastEndSource: String? = null
        private set

    fun addListener(listener: (CaptureSessionStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (CaptureSessionStatus) -> Unit) {
        listeners.remove(listener)
    }

    /** 更新状态；状态未变化时不产生任何输出（日志与时序证据只记录"变化"）。 */
    fun update(next: CaptureSessionStatus, source: String) {
        if (next == current) return
        val previous = current
        current = next
        // 结束来源单独留一份：界面要靠它把"从未授权"与"授权过又断了"说成两句不同的话
        if (next == CaptureSessionStatus.INACTIVE) lastEndSource = source
        MmLog.i(TAG, "采集会话状态变化: $previous -> $next（来源: $source）")
        listeners.forEach { it(next) }
    }
}
