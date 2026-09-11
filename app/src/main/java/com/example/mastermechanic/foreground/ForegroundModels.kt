package com.example.mastermechanic.foreground

/**
 * 前台状态仅两态：任何无法判定的情形一律归入 [NOT_FOREGROUND]（ADR-005），
 * 不引入"未知"态，避免下游出现多余分支。
 */
enum class ForegroundStatus { FOREGROUND, NOT_FOREGROUND }

/**
 * 目标应用包名。M0 以常量给定（《王者荣耀》国服）；
 * M3 巡查项配置化后由配置提供，此处退化为默认值。
 */
object TargetApp {
    const val PACKAGE_NAME = "com.tencent.tmgp.sgame"
}

/**
 * 前台判定纯规则：活动（焦点）窗口包名 == 目标包名 → 前台；其余（含 null / 查询失败）→ 非前台。
 * 不引用任何画面数据，与识别层严格分离（FR-09 独立性）。
 */
object ForegroundEvaluator {
    fun evaluate(
        activePackage: String?,
        targetPackage: String = TargetApp.PACKAGE_NAME,
    ): ForegroundStatus =
        if (activePackage != null && activePackage == targetPackage) {
            ForegroundStatus.FOREGROUND
        } else {
            ForegroundStatus.NOT_FOREGROUND
        }
}
