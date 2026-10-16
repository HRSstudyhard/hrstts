import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Qwen3-TTS 端侧推理（LiteRT Compiled Model API）的隔离模块。
//
// 为什么单独成模块：
//  - LiteRT 的 classpath 字节码会让 app 模块的 KSP 崩溃（unexpected jvm signature V /
//    Kotlin 元数据版本不兼容），因此 LiteRT 只出现在本模块的 classpath 上；
//  - app 用 implementation(project(":qwen3tts")) 引入本模块，LiteRT 不会进入 app 的
//    编译 classpath（只会在运行时打包进 APK），KSP 也就看不到它。
//
// 本模块不得启用 KSP / Compose / Room，也不得限制 ABI（保留 AAR 里全部 ABI）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hrstts.qwen3tts"
    compileSdk = 35
    // 与 app 模块保持一致（本机 SDK 只装了 34.0.0 / 36.1.0 / 37.0.0）
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 26
        // 不设置 ndk.abiFilters：保留 LiteRT AAR 自带的全部 ABI
        // （arm64-v8a / armeabi-v7a / x86_64），x86_64 模拟器也能加载。
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // Release 构建的 lintVital 会崩溃：AGP 8.7.3 自带的 lint（UAST）解析不了 Kotlin 2.2 编译出的
    // 字节码，报 `Found class org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall,
    // but interface was expected` / `Unexpected failure during lint analysis of Npy.kt`
    // （lint ≠ R8，代码压缩本身没问题）。
    // 取舍：在 AGP 升级到能识别 Kotlin 2.2 的版本前，先让本模块的 release lint 不参与构建，
    // 否则 :app:assembleRelease 永远失败。日常静态检查仍可用 `gradlew :app:lintDebug`。
    lint {
        checkReleaseBuilds = false
    }
}

// 上面的 checkReleaseBuilds 只影响 lint 的「致命问题」开关，AGP 仍会把
// lintVitalAnalyzeRelease 挂进 assembleRelease 的任务图，这里显式禁用它。
tasks.matching { it.name.startsWith("lintVital") }.configureEach {
    enabled = false
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.litert)
}
