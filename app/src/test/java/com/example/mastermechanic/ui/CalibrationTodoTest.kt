package com.example.mastermechanic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标定页首屏那句"下一步做什么"的四态判据（M5-U5 收尾，2026-10-02 UX 评审 P3）。
 *
 * 为什么值得钉：这四句是"用户该点哪儿"的**唯一提示** —— 判错就把人指向错的按钮
 * （例如已经录了帧却还让他去授权、或者明明没有结果却让他"去看清单"）。
 * 判据是纯逻辑 [calibrationTodoOf]，与 `RunPageLogic` / `VisitSettingsLogic` 一样单独测。
 */
class CalibrationTodoTest {

    @Test
    fun theMostBlockingStepWins() {
        // 没有采集会话 ⇒ 什么都做不了，先授权（**哪怕**已经录了帧、已有标定结果）
        assertEquals(
            CalibrationTodo.AUTH,
            calibrationTodoOf(captureActive = false, frameCount = 0, artifactCount = 0),
        )
        assertEquals(
            CalibrationTodo.AUTH,
            calibrationTodoOf(captureActive = false, frameCount = 42, artifactCount = 21),
        )
        // 有会话但**一帧都没有** ⇒ 先录（此时结果里就算有旧数据也不该让他去框选）
        assertEquals(
            CalibrationTodo.RECORD,
            calibrationTodoOf(captureActive = true, frameCount = 0, artifactCount = 21),
        )
        // 有帧、**没有结果** ⇒ 去全屏工作台框选
        assertEquals(
            CalibrationTodo.WORKBENCH,
            calibrationTodoOf(captureActive = true, frameCount = 42, artifactCount = 0),
        )
        // 帧和结果都有 ⇒ 去查看 / 删除单条
        assertEquals(
            CalibrationTodo.REVIEW,
            calibrationTodoOf(captureActive = true, frameCount = 42, artifactCount = 21),
        )
    }
}
