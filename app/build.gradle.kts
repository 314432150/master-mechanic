plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mastermechanic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mastermechanic"
        // 34（Android 14）是 `MediaProjection.Callback.onCapturedContentResize` 的起点 ——
        // 转屏跟随（VirtualDisplay.resize）依赖它，低于 34 会退化成"几何不一致 ⇒ 结束会话"。
        // 2026-09-23 用户口径：本工具的目标设备就是本机（Android 16），不值得为老设备背两套几何代码。
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /**
         * 只打 **arm64-v8a**（2026-09-21，T4-3）：ML Kit 中文 OCR 的原生库
         * `libmlkit_google_ocr_pipeline.so` 每个 ABI 约 **11MB**，四个 ABI 合计 41MB ——
         * 不裁剪会让安装包从 22.36MB 涨到 45.09MB（NFR-03 要求 ≤25MB）。
         * 目标设备 vivo V2463A 是 64 位 ARM；**代价**：32 位 ARM（armeabi-v7a）设备装不上。
         * 将来要覆盖 32 位设备，就得回到"不装 OCR"或换更轻的方案。
         */
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            /**
             * 2026-09-21 起**开启压缩**：T4-3 要引入 ML Kit 中文模型（捆绑式，约 +5~8MB），
             * 而 NFR-03 要求安装包 ≤25MB —— 未压缩的 release 包已是 22.36MB，不压缩会直接超线。
             * 开启后由 R8 收缩代码与资源，把模型那部分体积省回来（实测见 progress.md）。
             */
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    /**
     * JVM 单测里 `android.*` 的桩方法**返回默认值而不抛异常**：纯逻辑会顺手写一行日志
     * （[com.example.mastermechanic.log.MmLog] 内部走 `android.util.Log`），那行日志不该把
     * 一个纯逻辑单测打挂（2026-09-16 加：PatrolRequestSignalTest 就是这么挂的）。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * 快跑开关（`-PfastTests`）：跳过依赖真机帧池 / 大批量样本的离线回放与探针类。
 *
 * 依据（2026-09-13 实测 261 例）：全量 3m37s 里 `SpeedupProbeTest` 151.7s + `ReplayBatchRunTest` 57.1s
 * 占 209s，其余 32 个类合计约 7s。这两类是**取证 / 研究口径**，只在需要复演时才跑。
 *
 * 默认**不排除任何测试**：既有复现命令（如 `--tests "*CalibrationReviewProbeTest*"`）依赖默认可跑，
 * 离线回放也是验收证据的一部分。
 */
if (project.hasProperty("fastTests")) {
    tasks.withType<Test>().configureEach {
        filter {
            excludeTestsMatching("*ProbeTest")
            excludeTestsMatching("*BatchRunTest")
        }
    }
}

/**
 * 单测 JVM 的堆上限（2026-10-01 加）。
 *
 * 依据：里程碑口径的真全量 `:app:test` 里 `NameLocateProbeTest`（真机帧池回放）
 * 以 `java.lang.OutOfMemoryError: Java heap space` 挂掉 —— 一帧 3168×1440 RGBA ≈ **18MB**，
 * 默认堆（Gradle 默认 512MB）放不下几十帧。抬到 4GB。
 *
 * ⚠ 只影响**测试 JVM**，不进 APK、不影响包体（NFR-03 那 25MB 与此无关）。
 */
tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // M5-U1：导航 / 抽屉的图标（只引 core 那一档，见 libs.versions.toml 里的说明）
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // M5-U1：一级导航 / 抽屉 / 页面切换与返回栈（用户 2026-10-01 拍板引入）
    implementation(libs.androidx.navigation.compose)
    // T4-3 名称定位（第 5 / 9 步）：区服名 / 好友名的文字识别，**捆绑模型**（无需 Google Play 服务）
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}