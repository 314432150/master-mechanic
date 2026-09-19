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
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}