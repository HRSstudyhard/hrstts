package com.hrstts.jiandu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hrstts.qwen3tts.Qwen3TtsSynthesizer
import com.hrstts.qwen3tts.Tuning
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 端侧 TTS 合成基准测试（方案 C / D / E 的量化工具）。
 *
 * 为什么用仪器化测试而不是「adb 点界面 + 抓日志」：
 *  - 可复现：同一段文本、同一组参数、固定轮次，测完自动打印结构化结果；
 *  - 可参数化：线程数/分块/FP16 通过 `setprop` 覆盖（见 [Tuning]），**不需要重新构建 APK**；
 *  - 分离变量：这里直接调 [Qwen3TtsSynthesizer]，不经过 AudioTrack 与缓存，
 *    因此测到的是**纯合成**耗时（界面路径会叠加播放与缓存回放，无法用于调参）。
 *
 * 运行方式（默认关闭，避免误跑；1.6 GB 模型的加载与合成很慢）：
 *
 * ⚠️ **绝对不要用 `connectedDebugAndroidTest`**：它在跑测试前会**卸载并重装**应用，
 * 这会连带删除应用私有目录里的 **1.6 GB 模型**（以及历史库）。请用 `adb shell am instrument`
 * 手动触发 —— 它直接在已安装的包上跑，不碰应用数据：
 *
 * ```powershell
 * # 1) 模型必须已在设备上（files/qwen3-tts，11 个文件）
 * # 2) 覆盖参数（可选）。注意：属性在进程首次读取时定格，所以要先 force-stop：
 * adb shell am force-stop com.hrstts.jiandu
 * adb shell setprop debug.jiandu.threads 6
 * adb shell setprop debug.jiandu.chunkFrames 32
 * # 3) 构建并安装带 androidTest 的 APK（-r 保留数据）
 * .\gradlew.bat :app:assembleDebugAndroidTest
 * adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
 * # 4) 直接跑，不需要点界面
 * adb shell am instrument -w -e class com.hrstts.jiandu.TtsBenchmarkTest `
 *     com.hrstts.jiandu.test/androidx.test.runner.AndroidJUnitRunner
 * # 5) 抓结果
 * adb logcat -d | Select-String "TTSBENCH"
 * ```
 *
 * 为了让 `am instrument` 的相对路径干净，本测试在应用私有目录查找模型（见 [resolveModelDir]）。
 */
@RunWith(AndroidJUnit4::class)
class TtsBenchmarkTest {

    companion object {
        private const val TAG = "TTSBENCH"

        /** 合成轮次：每一轮都是完整一次合成，取中位数与最小/最大值。 */
        private const val ROUNDS = 3

        /**
         * 基准文本：刻意取 3 种长度，覆盖「短句（codec 固定开销占主导）」
         * 与「长句（talker 生成占主导）」两种形状 —— 它们的优化收益方向不同。
         */
        private val SAMPLES = listOf(
            "short" to "第一章 控制测试。",
            "medium" to "第一句，测试连续朗读。第二句，测试暂停继续。第三句，测试下一句。",
            "long" to "他翻开了那本泛黄的书，发现里面的字迹已经模糊不清，" +
                "于是放下书走了出去。窗外的雨还在下，街上的行人早已散尽，" +
                "只有远处偶尔传来一两声犬吠，提醒着这座城市还没有完全睡去。",
        )
    }

    @Test
    fun benchmarkSynthesis() {
        // 守卫：这是「重型且耗时」的基准测试（加载 ~2 s 宿主表 + 建图 ~5 s + 每轮合成十几秒），
        // 不该在常规 androidTest 套件里被顺带跑掉。必须显式开启：
        //   adb shell am instrument -w -e bench true -e class com.hrstts.jiandu.TtsBenchmarkTest \
        //       com.hrstts.jiandu.test/androidx.test.runner.AndroidJUnitRunner
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("bench") != "true") {
            log("SKIP: pass -e bench true to run the benchmark")
            return
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = resolveModelDir(ctx.filesDir) ?: run {
            log("SKIP: model dir not found; install the model first")
            return
        }

        // 先把本次生效的参数打出来：这是结果表里每一行的「自变量」
        log("configured: ${Tuning.describe()}")
        log("graphs: ${Tuning.graphsIn(modelDir)}")

        // 建引擎 = 加载宿主表（≈2 s）；图在首次合成时才建，因此第一轮会偏慢，
        // 这里显式预热一次，把「建图成本」从测量里剔除（否则测的是加载不是合成）。
        val t0 = System.nanoTime()
        val synth = Qwen3TtsSynthesizer(modelDir)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        log("hostTablesLoadMs=$loadMs sampleRate=${synth.sampleRate} chunk=${synth.chunkFrames}")

        try {
            log("preload graphs ...")
            val preloadMs = synth.preloadGraphs()
            log("graphPreloadMs=$preloadMs")

            for ((label, text) in SAMPLES) {
                // 预热：让 XNNPACK 完成首次分配与内核选择，避免把一次性开销计入
                runCatching { synth.synthesize(text = text, language = "chinese") }
                    .onFailure { log("warmup FAILED ($label): ${it.message}"); return@onFailure }

                val results = ArrayList<Run>(ROUNDS)
                repeat(ROUNDS) { i ->
                    val started = System.nanoTime()
                    var firstPcmMs = -1L
                    var chunks = 0
                    val speech = synth.synthesizeStreaming(
                        text = text,
                        language = "chinese",
                        chunkFrames = 0, // 0 = 用 Tuning.chunkFrames
                        onPcm = { pcm ->
                            if (firstPcmMs < 0) {
                                firstPcmMs = (System.nanoTime() - started) / 1_000_000
                            }
                            chunks++
                        },
                    )
                    val totalMs = (System.nanoTime() - started) / 1_000_000
                    val audioSeconds = speech.frames * 0.08
                    results += Run(
                        round = i + 1,
                        frames = speech.frames,
                        audioSeconds = audioSeconds,
                        firstPcmMs = firstPcmMs,
                        totalMs = totalMs,
                        rtf = if (audioSeconds > 0) totalMs / 1000.0 / audioSeconds else 0.0,
                        prefillMs = speech.prefillMs,
                        talkerMs = speech.talkerMs,
                        mtpMs = speech.mtpMs,
                        codecMs = speech.codecMs,
                        chunks = chunks,
                    )
                }

                // 一行一个样本，字段用空格分隔，便于脚本解析成表
                for (r in results) {
                    log(
                        "sample=$label round=${r.round} frames=${r.frames} " +
                            "audioS=${"%.2f".format(r.audioSeconds)} " +
                            "firstPcmMs=${r.firstPcmMs} totalMs=${r.totalMs} " +
                            "rtf=${"%.2f".format(r.rtf)} chunks=${r.chunks} " +
                            "prefill=${r.prefillMs} talker=${r.talkerMs} " +
                            "mtp=${r.mtpMs} codec=${r.codecMs}",
                    )
                }
                val best = results.minByOrNull { it.rtf }!!
                log(
                    "summary sample=$label bestRtf=${"%.2f".format(best.rtf)} " +
                        "bestTotalMs=${best.totalMs} bestFirstPcmMs=${best.firstPcmMs} " +
                        "medianRtf=${"%.2f".format(results.map { it.rtf }.sorted()[results.size / 2])}",
                )
            }
        } finally {
            runCatching { synth.close() }
            log("closed")
        }
    }

    private data class Run(
        val round: Int,
        val frames: Int,
        val audioSeconds: Double,
        val firstPcmMs: Long,
        val totalMs: Long,
        val rtf: Double,
        val prefillMs: Long,
        val talkerMs: Long,
        val mtpMs: Long,
        val codecMs: Long,
        val chunks: Int,
    )

    /**
     * 模型目录：只查应用私有目录。
     *
     * 模型由应用内下载写到 `filesDir/qwen3-tts`，测试进程与被测应用同 UID，
     * 因此可直接读。**不查外部存储**：那里通常没有模型，白白多一次 1.6 GB 的目录探测。
     */
    private fun resolveModelDir(filesDir: File): File? {
        val dir = File(filesDir, "qwen3-tts")
        return dir.takeIf {
            it.isDirectory && runCatching { Qwen3TtsSynthesizer.requireModelFiles(it) }.isSuccess
        }
    }

    private fun log(msg: String) {
        // 同时写 logcat 与 instrumentation 输出：前者便于 adb logcat -d 抓，
        // 后者在 gradle 控制台直接可见。
        android.util.Log.i(TAG, msg)
        println("[$TAG] $msg")
    }
}
