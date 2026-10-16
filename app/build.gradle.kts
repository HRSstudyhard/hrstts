import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 构建时间戳：写进 BuildConfig，用于在应用内核对「手机上装的是不是最新构建」 */
val buildTimeStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hrstts.jiandu"
    compileSdk = 35
    // SDK 中已安装的 build-tools（低于 AGP 8.7 要求的 35.0.0 会被拒绝）
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.hrstts.jiandu"
        minSdk = 26
        targetSdk = 35
        // 自更新依赖 versionCode 比较：每次发布功能都必须 +1，否则「检查更新」不会认为有新版本
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 便于核对「手机上装的是不是最新构建」
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeStamp\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // P5：发布构建开启代码压缩/混淆与资源压缩（规则见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 说明：本项目暂不做正式发布签名，因此 release 产物会回落到 debug 签名（AGP 默认行为）。
            // 这样 `assembleRelease` 依然产出可安装、可自更新的 APK；正式分发前请补 signingConfigs。
            // 需要严格校验时可用 assembleRelease 后 aapt2 检查 apk 是否已对齐/签名。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Release 的 lintVital 在当前工具链上必然崩溃：AGP 8.7.3 自带的 lint（UAST）解析不了
    // Kotlin 2.2 编译出的字节码，报 `Found class ...KaCallableMemberCall, but interface was
    // expected` / `Unexpected failure during lint analysis of <file>.kt`（lint ≠ R8，
    // 压缩本身没问题）。在 AGP 升级到能识别 Kotlin 2.2 之前先关掉，否则 assembleRelease 不可用。
    // 日常静态检查：`gradlew :app:lintDebug`。
    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 上面的 lint { checkReleaseBuilds = false } 只改变「致命问题」策略，AGP 仍会把
// lintVitalAnalyzeRelease 挂进 assembleRelease 的任务图，这里显式禁用它。
tasks.matching { it.name.startsWith("lintVital") }.configureEach {
    enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Qwen3-TTS 端侧推理（LiteRT Compiled Model API）
    // LiteRT 只出现在 :qwen3tts 的 classpath 上：app 用 implementation(project(...)) 引入，
    // 运行时会被打包进 APK，但不会进入 app 的编译 classpath，KSP 因而看不到 LiteRT 字节码。
    implementation(project(":qwen3tts"))

    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
