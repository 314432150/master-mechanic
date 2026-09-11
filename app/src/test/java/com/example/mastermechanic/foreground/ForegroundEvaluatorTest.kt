package com.example.mastermechanic.foreground

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前台判定纯规则测试（ADR-005）：
 * 活动窗口包名 == 目标包名 → 前台；其余（含查询失败）→ 非前台。
 */
class ForegroundEvaluatorTest {

    private val target = "com.example.target"

    @Test
    fun `活动窗口等于目标包名时判定为前台`() {
        assertEquals(ForegroundStatus.FOREGROUND, ForegroundEvaluator.evaluate(target, target))
    }

    @Test
    fun `活动窗口是其他应用时判定为非前台`() {
        assertEquals(ForegroundStatus.NOT_FOREGROUND, ForegroundEvaluator.evaluate("com.android.settings", target))
    }

    @Test
    fun `查询失败（null）时判定为非前台`() {
        assertEquals(ForegroundStatus.NOT_FOREGROUND, ForegroundEvaluator.evaluate(null, target))
    }

    @Test
    fun `空字符串包名判定为非前台`() {
        assertEquals(ForegroundStatus.NOT_FOREGROUND, ForegroundEvaluator.evaluate("", target))
    }

    @Test
    fun `省略目标参数时使用默认目标包名`() {
        assertEquals(ForegroundStatus.FOREGROUND, ForegroundEvaluator.evaluate(TargetApp.PACKAGE_NAME))
    }
}
