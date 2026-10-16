# 「简读」R8 / ProGuard 规则
#
# 目标：Release 构建可以开启 minify + resource shrinking，且不破坏以下三类东西
#   1) LiteRT 原生推理（JNI 按类名/方法名查找，混淆后会 UnsatisfiedLinkError 或找不到方法）
#   2) Room 生成的实现（通过类名反射加载）
#   3) 读 APK 元信息时用到的反射字段（UpdateManager 需要读签名与 versionCode）
#
# 说明：文件里的 keep 规则偏保守（`{ *; }` 保留整包），原因是端侧推理一旦被裁掉
# 只会在真机运行到那一步才暴露，收益（几十 KB）远小于风险。

# ---------------------------------------------------------------- LiteRT / TFLite
# LiteRT 原生库通过 JNI 按完整类名与方法签名回调 Java 层，必须整体保留。
-keep class com.google.ai.edge.litert.** { *; }
-keep interface com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn org.tensorflow.lite.**

# 端侧推理模块自身的公开门面（宿主循环 + 分词器 + npy 解析）：
# 其中 Npy/Tokenizer 的方法会被 Kotlin 内联以外的路径调用，保留公开成员以策安全。
-keep class com.hrstts.qwen3tts.** { *; }

# JNI 方法名（native 声明所在类不能被改名或裁剪）
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------- Room
# Room 生成的实现类由 Room 运行时按类名实例化（_Impl 后缀），保留构造与成员。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class **.*_Impl { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- Compose / Kotlin
# Compose 的 @Composable 由编译器插件处理，运行时不需要反射，但保留 Compose 运行时注解
# 有助于诊断崩溃栈。
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# Kotlin 元数据：保留后崩溃栈与反射工具（如 kotlin-reflect）仍可用
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---------------------------------------------------------------- 应用自身
# 说明：读已安装/待安装 APK 的元信息（signatures / versionCode）用的是编译期直接引用，
# 不需要额外 keep；此处只屏蔽与之相关的第三方告警。
-dontwarn android.content.pm.**

# ViewModel 由 androidx 工厂按构造反射创建（AndroidViewModel(Application) 构造函数）
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ---------------------------------------------------------------- 其它
# 序列化/反序列化与协程内部类
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# 保留行号，便于线上崩溃栈定位（配合 -keepattributes SourceFile,LineNumberTable）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
