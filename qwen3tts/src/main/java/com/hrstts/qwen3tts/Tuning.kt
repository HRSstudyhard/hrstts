package com.hrstts.qwen3tts

import java.io.File

/**
 * 端侧推理的**运行期可调参数**（方案 C / E 的实验旋钮）。
 *
 * 为什么需要这个类：`talker`/`MTP`/`codec` 的 XNNPACK 线程数、流式分块大小、
 * `FORCE_FP16` 等原本是写死的常量，每次做参数扫描都要改代码、重新构建、重装 APK ——
 * 一轮对照要十几分钟，无法在真机上快速迭代。现在可以在**不重新构建**的情况下覆盖：
 *
 * ```powershell
 * adb shell setprop debug.jiandu.threads 8          # talker/mtp/codecA/codecB 统一线程数
 * adb shell setprop debug.jiandu.talkerThreads 6    # 单独覆盖 talker
 * adb shell setprop debug.jiandu.chunkFrames 32     # 流式分块（帧）
 * adb shell setprop debug.jiandu.codecFp16 0        # 关闭 codec_partB 的 FORCE_FP16
 * adb shell setprop debug.jiandu.tuning 0           # 整体关闭覆盖，回到内置默认值
 * ```
 *
 * 读取方式说明（重要）：Android 上 `System.getProperty("debug.xxx")` **读不到** `setprop`
 * 设置的属性 —— `setprop` 写的是 Android 属性服务（`ro.*`/`debug.*`），而
 * `System.getProperty` 读的是 JVM 的 `Properties` 表，两者是不同的命名空间。
 * 正确入口是隐藏类 `android.os.SystemProperties`。本类用反射调用它，这样
 * ① `:qwen3tts` 不需要编译期依赖；② 在 JVM 单测里也能安全降级为默认值，不抛异常。
 *
 * ⚠️ 仅 `debug.*` 前缀可被普通应用写入（Android 对属性命名空间有 SELinux 限制）。
 * 生产环境不应设置这些属性：所有内置默认值即当前已验证的稳妥配置。
 *
 * **求值时机**：所有参数在**本进程首次读取时一次性定格**（`by lazy`）。
 * 这是有意为之 —— XNNPACK 线程数只在建图那一刻生效，中途改变毫无意义；
 * 而且这样 `describe()` 不会每次读属性。因此 `setprop` 后必须**重启进程**才生效。
 */
object Tuning {

    private const val TAG = "Qwen3TtsTuning"

    /** 覆盖总开关：为 false 时全部参数回到内置默认值。 */
    val enabled: Boolean by lazy { rawProp(KEY_ENABLED, "1") != "0" }

    /** talker 图线程数（默认 4）。8 核真机上可试 6/8。 */
    val talkerThreads: Int by lazy { specificOrGlobal(KEY_TALKER_THREADS, 4) }

    /** 折叠 MTP 图线程数（默认 4）。 */
    val mtpFoldedThreads: Int by lazy { specificOrGlobal(KEY_MTP_THREADS, 4) }

    /** 参考 MTP（`mtp_fp32`）图线程数（默认 2，沿用官方参考实现取值）。 */
    val mtpReferenceThreads: Int by lazy { specificOrGlobal(KEY_MTP_THREADS, 2) }

    /** `codec_partA` 线程数（默认 4）。 */
    val codecAThreads: Int by lazy { specificOrGlobal(KEY_CODEC_THREADS, 4) }

    /** `codec_partB` / `codec_decoder_fp32` 线程数（默认 4）。 */
    val codecThreads: Int by lazy { specificOrGlobal(KEY_CODEC_THREADS, 4) }

    /** `codec_partB` 是否使用 XNNPACK 的 FORCE_FP16 标志（默认 true）。 */
    val codecForceFp16: Boolean by lazy { prop(KEY_CODEC_FP16, "1") != "0" }

    /** 流式分块大小（新帧数，默认 16 ≈ 1.28 s 音频）。 */
    val chunkFrames: Int by lazy { prop(KEY_CHUNK_FRAMES, "16").trim().toIntOrNull()?.coerceIn(1, 128) ?: 16 }

    /** 全局线程覆盖；未设置时为 null。 */
    private val globalThreads: Int? by lazy {
        rawProp(KEY_THREADS, "").trim().toIntOrNull()?.coerceIn(1, 16)
    }

    /** 当前生效参数的可读描述，便于在真机 logcat 里核对「这次跑的是哪一档」。 */
    fun describe(): String =
        "enabled=$enabled talker=$talkerThreads mtpFolded=$mtpFoldedThreads " +
            "mtpRef=$mtpReferenceThreads codecA=$codecAThreads codec=$codecThreads " +
            "codecFp16=${if (codecForceFp16) 1 else 0} chunk=$chunkFrames"

    /**
     * 把当前生效的调参写进日志，便于在真机 logcat 里核对。
     * 参数不可用时静默跳过（不引入新的崩溃点）。
     */
    fun logTo(tag: String = TAG) {
        runCatching { android.util.Log.i(tag, "调参生效：${describe()}") }
    }

    /** 调试便利：模型目录里实际存在的图组合（用于确认跑的是快速档还是精度档）。 */
    fun graphsIn(modelDir: File): String = Qwen3TtsEngine.describeGraphs(modelDir)

    // ---- 内部：属性读取（反射 android.os.SystemProperties，失败即降级）----

    private val systemPropertiesClass: Class<*>? by lazy {
        runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
    }

    /** `get(String, String)`：属性不存在时返回给定默认值。 */
    private val getWithDefault: java.lang.reflect.Method? by lazy {
        systemPropertiesClass
            ?.let { runCatching { it.getMethod("get", String::class.java, String::class.java) }.getOrNull() }
    }

    /** `get(String)`：属性不存在时返回空串。 */
    private val getWithoutDefault: java.lang.reflect.Method? by lazy {
        systemPropertiesClass
            ?.let { runCatching { it.getMethod("get", String::class.java) }.getOrNull() }
    }

    /**
     * 不做 enabled 判定的原始读取。
     * ⚠️ 必须与 [prop] 分开：`enabled` 本身就依赖本方法，若走 [prop] 会无限递归。
     */
    private fun rawProp(key: String, default: String): String {
        // 1) System.getProperty：桌面 JVM 单测可用（Android 上通常为空）
        runCatching { System.getProperty(key) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        // 2) 反射 android.os.SystemProperties（Android 真机/模拟器的正路）
        getWithDefault?.let { m ->
            runCatching { m.invoke(null, key, default) as? String }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        getWithoutDefault?.let { m ->
            runCatching { m.invoke(null, key) as? String }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return default
    }

    /** 受 [enabled] 总开关约束的读取。 */
    private fun prop(key: String, default: String): String =
        if (!enabled) default else rawProp(key, default)

    /** 先读专用属性，未设置则退到全局线程属性，最后用内置默认值。 */
    private fun specificOrGlobal(specificKey: String, default: Int): Int {
        if (!enabled) return default
        rawProp(specificKey, "").trim().toIntOrNull()?.let { return it.coerceIn(1, 16) }
        return globalThreads ?: default
    }

    // 属性名常量（集中声明，便于文档与脚本引用）
    const val KEY_ENABLED = "debug.jiandu.tuning"
    const val KEY_THREADS = "debug.jiandu.threads"
    const val KEY_TALKER_THREADS = "debug.jiandu.talkerThreads"
    const val KEY_MTP_THREADS = "debug.jiandu.mtpThreads"
    const val KEY_CODEC_THREADS = "debug.jiandu.codecThreads"
    const val KEY_CODEC_FP16 = "debug.jiandu.codecFp16"
    const val KEY_CHUNK_FRAMES = "debug.jiandu.chunkFrames"

    @Suppress("unused")
    private const val LOG_TAG = TAG
}
