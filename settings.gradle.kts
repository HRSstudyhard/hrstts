// 依赖仓库：优先国内镜像（下载快），镜像缺失时自动回退到官方源
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "JianDu"
include(":app")
// Qwen3-TTS 端侧推理（LiteRT）隔离模块：不启用 KSP，避免 LiteRT 字节码让 app 的 KSP 崩溃
include(":qwen3tts")
