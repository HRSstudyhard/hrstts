package com.hrstts.jiandu.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.tts.TtsEngine
import com.hrstts.jiandu.domain.tts.TtsListener
import com.hrstts.jiandu.i18n.UiStrings
import com.hrstts.qwen3tts.Qwen3TtsSynthesizer
import com.hrstts.qwen3tts.Tuning
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Qwen3-TTS 端侧引擎适配器。
 *
 * 参考实现（litert-samples）一次合成返回整段 PCM，因此本适配器：
 *  - 在后台线程完成「合成 → AudioTrack 播放」，播放过程中可被 [stop] 中断；
 *  - 与 [TtsController] 的协作是「一段一句」：控制器送一句、播完回调 onDone 再送下一句；
 *  - 合成比实时慢（Pixel 8a 快速图组 RTF≈2），所以按下「阅读」后会有可见的准备时间，
 *    界面上的当前句高亮由控制器负责，无需引擎上报字符范围。
 */
class Qwen3TtsEngine(
    private val modelDir: File,
    private val strings: UiStrings,
    /** PCM 音频缓存（两档：RAM 环形 + 磁盘 LRU）；null 表示不使用缓存 */
    private val cache: TtsAudioCache? = null,
) : TtsEngine {

    override val id: String = ID
    override val displayName: String = strings.get(R.string.engine_qwen3)

    override var isReady: Boolean = false
        private set

    private var listener: TtsListener? = null
    private var synthesizer: Qwen3TtsSynthesizer? = null
    private var worker: Thread? = null
    private var prefetchWorker: Thread? = null

    /** 待预取文本队列（FIFO；朗读开始时清空，避免过期的预取继续占 CPU） */
    private val prefetchQueue = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /**
     * 合成互斥锁。
     *
     * **必须**：`Qwen3TtsSynthesizer` 内部复用同一套 LiteRT 输入/输出张量与 KV 缓存
     * （`TalkerState`、`mtpBuffers`、codec buffers），不是线程安全的。
     * 预取线程与朗读线程若同时进入 `synthesize`，会互相踩张量缓冲 ——
     * 实测直接 native 崩溃：`Scudo ERROR: invalid chunk state when deallocating`（SIGABRT）。
     * 因此所有合成入口都必须先拿这把锁。
     */
    private val synthesisLock = java.util.concurrent.locks.ReentrantLock()

    /** 预取开关：可在设置里关掉（省电/降内存） */
    @Volatile
    var prefetchEnabled: Boolean = true

    /**
     * 预取总开关（全局）。
     *
     * ⚠️ 目前置为 `false`：预取线程与朗读线程会**共用同一套 LiteRT 张量/KV 缓存**，
     * 历史上实测到 native 崩溃（`Scudo ERROR: invalid chunk state`）。
     * 现已加 [synthesisLock] 互斥，但风险与收益需要重新权衡：
     *  - 收益：段内后续句子提前备好（首音 ms 级）；
     *  - 风险：并发/锁竞争引发的不稳定，以及模型常驻 + 双份 KV 带来的 5 GB 级内存。
     * 待真机复测后再逐个开关打开。
     */
    private val prefetchGloballyEnabled: Boolean = false

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var currentUtteranceId: String? = null

    private var speechRate: Float = 1.0f

    override fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    override fun initialize() {
        if (isReady) return
        Thread({
            try {
                val startedAt = System.nanoTime()
                Qwen3TtsSynthesizer.requireModelFiles(modelDir)
                val checkedAt = System.nanoTime()
                val engine = Qwen3TtsSynthesizer(modelDir)
                val loadedAt = System.nanoTime()
                synthesizer = engine
                isReady = true
                // 把本次生效的调参（线程数 / FP16 / 分块）写进日志，
                // 便于在真机 logcat 里核对「这一轮跑的是哪一档」。
                Tuning.logTo(LOG_TAG)
                // 拆开计时：文件校验 / 宿主表 + 图加载，便于定位「加载过慢」到底慢在哪一步
                android.util.Log.i(
                    LOG_TAG,
                    "端侧模型已加载：${engine.graphsDescription}（采样率 ${engine.sampleRate}）" +
                        " 校验=${(checkedAt - startedAt) / 1_000_000}ms" +
                        " 加载=${(loadedAt - checkedAt) / 1_000_000}ms" +
                        " 合计=${(loadedAt - startedAt) / 1_000_000}ms",
                )
                listener?.onReady()
                // 空闲预加载：把最贵的三个图提前建好，换取「首次朗读不用再等 5 s」。
                // 代价是启动后约 3 s 起常驻 2 GB；PREFETCH_GRAPHS 关掉即回到纯延迟加载。
                if (PREFETCH_GRAPHS) {
                    Thread.sleep(PREFETCH_DELAY_MS)
                    val t = System.nanoTime()
                    engine.preloadGraphs()
                    android.util.Log.i(
                        LOG_TAG,
                        "空闲预加载推理图完成：${(System.nanoTime() - t) / 1_000_000}ms",
                    )
                }
            } catch (t: Throwable) {
                if (t is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                // 设备 ABI 不支持、模型缺文件、内存不足等都会走这里
                listener?.onError(
                    strings.get(
                        R.string.qwen3_load_failed,
                        t.message ?: t.javaClass.simpleName,
                    ),
                )
            }
        }, "qwen3-tts-init").apply { isDaemon = true }.start()
    }

    override fun speak(text: String, utteranceId: String) {
        val engine = synthesizer
        if (engine == null || !isReady) {
            listener?.onError(strings.get(R.string.qwen3_not_ready))
            return
        }

        // 注意：**不能在这里 join**。speak 由控制器在主线程调用，join 会把 UI 卡住整段时间
        // （实测最坏 1.5~3 s 的主线程阻塞）。改为把「等旧线程退出」放到新 worker 里做。
        stopRequested = true
        val previous = worker
        currentUtteranceId = utteranceId
        // 本句已经要开始合成，把队列里同名任务去掉，避免白合一遍（预取队列继续为后面的句子服务）
        prefetchQueue.remove(text)

        val cacheKey = cache?.let { TtsAudioCache.keyOf(text, speakerTag(engine), engine.graphsDescription) }

        worker = Thread({
            // 1) 只有确实有上一个线程时才需要等待。
            //    注意：`speak` 开头刚把 stopRequested 置为 true（用于通知旧线程退出），
            //    所以**必须先把标志清掉**再检查，否则新线程会把自己当成「被中断」而立刻放弃
            //    —— 实测表现就是「第一句能播（previous==null 不走这段），第二句起全哑」。
            stopRequested = false
            if (previous != null) {
                previous.join(WORKER_JOIN_TIMEOUT_MS)
                if (previous.isAlive) {
                    android.util.Log.w(
                        LOG_TAG,
                        "上一个朗读线程未在 ${WORKER_JOIN_TIMEOUT_MS}ms 内退出（可能出现短暂重叠）",
                    )
                }
                // 等待期间可能已经被切到下一句/被停止
                if (currentUtteranceId != utteranceId) return@Thread
                stopRequested = false
            }

            // 2) 取音频：缓存两档都查（内存纳秒级；磁盘读 169 KB 约毫秒级）
            val cached = cacheKey?.let { cache?.get(it) }

            val track = try {
                createTrack()
            } catch (t: Throwable) {
                listener?.onError(
                    strings.get(
                        R.string.qwen3_audio_output_failed,
                        t.message ?: t.javaClass.simpleName,
                    ),
                )
                return@Thread
            }

            var firstChunkMs = -1L
            var writtenSamples = 0L
            val startedAt = System.nanoTime()

            try {
                listener?.onStart(utteranceId)
                // 立刻开始播放：AudioTrack 在数据到达前保持静默，首块 PCM 一到就出声
                track.play()

                if (cached != null) {
                    // 命中缓存：直接回放，跳过合成（这是「重读/回看/暂停继续」秒开的关键路径）
                    firstChunkMs = (System.nanoTime() - startedAt) / 1_000_000
                    writtenSamples = playCached(utteranceId, text, cached, track)
                    val totalMs = (System.nanoTime() - startedAt) / 1_000_000
                    android.util.Log.i(
                        LOG_TAG,
                        "缓存命中：${cached.frames} 帧（${"%.2f".format(cached.audioSeconds)}s 音频），" +
                            "首音延迟=${firstChunkMs}ms，总耗时=${totalMs}ms，" +
                            "命中率=${runCatching { cache?.statsLine() }.getOrNull()}",
                    )
                    if (!stopRequested && currentUtteranceId == utteranceId) {
                        runCatching { track.stop() }
                        listener?.onDone(utteranceId)
                    }
                    return@Thread
                }

                val builder = cacheKey?.let { TtsAudioCache.ClipBuilder() }
                // 合成期间持有互斥锁：预取线程不得同时进入（否则踩共享张量 → native 崩溃）
                val speech = synthesisLock.withLock {
                    engine.synthesizeStreaming(
                        text = text,
                        language = languageOf(text),
                        chunkFrames = CHUNK_FRAMES,
                        shouldStop = { stopRequested || currentUtteranceId != utteranceId },
                        onPcm = { pcm ->
                            if (firstChunkMs < 0) {
                                firstChunkMs = (System.nanoTime() - startedAt) / 1_000_000
                            }
                            builder?.append(pcm)
                            val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            if (written > 0) writtenSamples += written
                            // 用「已生成帧比例」粗略推进正文字符高亮
                            reportApproximateProgress(utteranceId, text, writtenSamples)
                        },
                    )
                }
                // 合成成功 → 写入两档缓存（把本次结果留给「段内下一句/重听」直接复用）
                if (cacheKey != null && builder != null) {
                    cache?.put(cacheKey, builder.toFloatArray(), speech.frames)
                }

                val audioSeconds = speech.frames * 0.08          // 12.5 帧/秒
                val totalMs = (System.nanoTime() - startedAt) / 1_000_000
                val rtf = if (audioSeconds > 0) totalMs / 1000.0 / audioSeconds else 0.0
                android.util.Log.i(
                    LOG_TAG,
                    "流式合成完成：${speech.frames} 帧（${"%.2f".format(audioSeconds)}s 音频），" +
                        "首音延迟=${firstChunkMs}ms，总耗时=${totalMs}ms，" +
                        "RTF=${"%.2f".format(rtf)}，" +
                        "prefill=${speech.prefillMs}ms talker=${speech.talkerMs}ms " +
                        "mtp=${speech.mtpMs}ms codec=${speech.codecMs}ms",
                )

                if (!stopRequested && currentUtteranceId == utteranceId) {
                    // 等缓冲区播完再结束，避免尾音被截断
                    runCatching { track.stop() }
                    listener?.onDone(utteranceId)
                }
            } catch (t: Throwable) {
                if (!stopRequested) {
                    android.util.Log.e(LOG_TAG, "端侧合成失败", t)
                    listener?.onError(
                        strings.get(
                            R.string.qwen3_synthesis_failed,
                            t.message ?: t.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                runCatching { track.release() }
            }
        }, "qwen3-tts-speak")
        worker?.apply { isDaemon = true }?.start()
    }

    /**
     * 端侧合成一次性只在最后才知道总帧数，这里按「已写出的采样数 / 预估总采样数」推进高亮。
     * 预估按中文约 3 帧/字（≈0.24 秒/字），仅用于让高亮随朗读前进，不追求精确。
     */
    private fun reportApproximateProgress(utteranceId: String, text: String, writtenSamples: Long) {
        val estimatedFrames = (text.length * 3.0).coerceAtLeast(4.0)
        val estimatedSamples = estimatedFrames * SAMPLES_PER_FRAME
        val fraction = (writtenSamples / estimatedSamples).coerceIn(0.0, 1.0)
        val end = (text.length * fraction).toInt().coerceIn(0, text.length)
        listener?.onRange(utteranceId, 0, end)
    }

    /**
     * 从缓存回放（命中路径）。
     *
     * 逐块把 int16 转 float32 写进 [AudioTrack]，并沿用与合成路径相同的
     * 「按已写出采样数推进字符高亮」逻辑，保证界面表现一致。
     *
     * @return 写出的采样点数
     */
    private fun playCached(
        utteranceId: String,
        text: String,
        clip: TtsAudioCache.Clip,
        track: AudioTrack,
    ): Long {
        val chunk = 8192
        val buffer = FloatArray(chunk)
        var written = 0L
        var index = 0
        while (index < clip.pcm.size) {
            if (stopRequested || currentUtteranceId != utteranceId) break
            val count = minOf(chunk, clip.pcm.size - index)
            for (i in 0 until count) {
                buffer[i] = clip.pcm[index + i] / 32768f
            }
            val wrote = track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
            if (wrote <= 0) break
            written += wrote
            index += count
            reportApproximateProgress(utteranceId, text, written)
        }
        return written
    }

    /**
     * 预取：只合成并写入缓存，不播放。
     *
     * 供「导入后预热首段」与「朗读时预取下一句」使用；调用方需要保证不与正在进行的
     * 朗读抢 CPU（由 `TtsController` 在空闲时驱动）。
     *
     * @return 是否成功写入缓存（缓存未启用或失败返回 false）
     */
    fun prefetchToCache(text: String): Boolean {
        val engine = synthesizer ?: return false
        val audioCache = cache ?: return false
        if (!isReady || text.isBlank()) return false
        val key = TtsAudioCache.keyOf(text, speakerTag(engine), engine.graphsDescription)
        if (audioCache.contains(key)) {
            return true
        }
        return try {
            val builder = TtsAudioCache.ClipBuilder()
            // 与朗读互斥（同一把锁）；朗读进行中直接放弃本次预取，避免等待与抢资源
            if (synthesisLock.isLocked || currentUtteranceId != null) return false
            val speech = synthesisLock.withLock {
                engine.synthesizeStreaming(
                    text = text,
                    language = languageOf(text),
                    chunkFrames = CHUNK_FRAMES,
                    shouldStop = { currentUtteranceId != null },
                    onPcm = { pcm -> builder.append(pcm) },
                )
            }
            if (currentUtteranceId != null) return false // 期间开始朗读了，丢弃本次预取
            audioCache.put(key, builder.toFloatArray(), speech.frames)
            android.util.Log.i(
                LOG_TAG,
                "预取入缓存：${speech.frames} 帧（${"%.2f".format(speech.frames * 0.08)}s）" +
                    " 「${text.take(10)}…」 ${audioCache.statsLine()}",
            )
            true
        } catch (t: Throwable) {
            android.util.Log.w(LOG_TAG, "预取失败（不影响朗读）：${t.message}")
            false
        }
    }

    /** 退后台：清空内存档缓存（保留最近几条，回前台仍能秒开） */
    fun trimCacheForBackground(): Long {
        prefetchQueue.clear()
        return cache?.clearMemory() ?: 0L
    }

    override fun prefetch(text: String) {
        if (!prefetchEnabled || !prefetchGloballyEnabled || text.isBlank()) return
        if (!isReady) return
        // 排队而不是「忙就丢弃」：段内一次会排入多句，丢弃会让后面的句子全部没准备好。
        // 正在朗读时也允许排队，由预取线程在朗读间隙消费。
        prefetchQueue.add(text)
        startPrefetchWorkerIfIdle()
    }

    private fun startPrefetchWorkerIfIdle() {
        prefetchWorker?.let { if (it.isAlive) return }
        val worker = Thread({
            while (true) {
                val text = prefetchQueue.poll() ?: break
                if (!prefetchEnabled) break
                // 与朗读互斥：
                //  - 正在朗读 → 让路（不抢 CPU，也不碰共享张量缓冲）
                //  - 合成锁被朗读占用 → 稍后重试
                if (currentUtteranceId != null || synthesisLock.isLocked) {
                    prefetchQueue.addFirst(text)
                    Thread.sleep(PREFETCH_PAUSE_MS)
                    continue
                }
                prefetchToCache(text)
            }
        }, "qwen3-tts-prefetch")
        worker.isDaemon = true
        prefetchWorker = worker
        worker.start()
    }

    /** 预取队列长度（诊断用） */
    val prefetchQueueSize: Int get() = prefetchQueue.size

    override fun stop() {
        stopRequested = true
        currentUtteranceId = null
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
    }

    override fun shutdown() {
        stopRequested = true
        currentUtteranceId = null
        runCatching { synthesizer?.close() }
        synthesizer = null
        isReady = false
    }

    // ---- 内部 ----

    /** 创建流式 PCM 播放通道（24 kHz 单声道 float） */
    private fun createTrack(): AudioTrack {
        val sampleRate = synthesizer?.sampleRate ?: 24_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate / 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * Float.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (speechRate != 1.0f) {
            runCatching { track.playbackParams = track.playbackParams.setSpeed(speechRate) }
        }
        return track
    }

    /** 参考实现的 LANGUAGE_IDS 使用英文语言名 */
    private fun languageOf(text: String): String =
        if (text.any { it.code in 0x4E00..0x9FFF }) "chinese" else "english"

    /**
     * 音色指纹：缓存 key 的一部分。
     *
     * 音色变了（将来支持导入 x-vector）就必须换 key，否则会播到旧音色的缓存。
     * 现在固定用 `demo_speaker.npy`，取向量前若干维的哈希即可稳定区分。
     */
    private fun speakerTag(engine: Qwen3TtsSynthesizer): String {
        val speaker = engine.speaker
        var acc = 17
        for (i in speaker.indices step 32) {
            acc = acc * 31 + speaker[i].toRawBits()
        }
        return "spk$acc"
    }

    companion object {
        const val ID = "qwen3-tts"
        private const val LOG_TAG = "Qwen3TtsEngine"

        /**
         * 空闲预加载推理图（talker/MTP/codec）。
         *
         * 三个图的创建约 5 s / 2 GB native 堆，本来推迟到首次合成；开启后改为
         * 「引擎就绪 + [PREFETCH_DELAY_MS]」时后台建好，代价是这段时间内存会上到 2 GB+。
         * 关掉即回到「启动快、首次朗读多等约 5 s」。
         */
        private const val PREFETCH_GRAPHS = true

        /** 预加载延迟：留一点时间给界面首帧与正文渲染，避免抢内存带宽 */
        private const val PREFETCH_DELAY_MS = 3_000L

        /** 朗读进行中时，预取线程的轮询间隔（让出 CPU 给朗读） */
        private const val PREFETCH_PAUSE_MS = 500L

        /**
         * 切换朗读时等待上一个 worker 退出的上限。
         *
         * 太短 → 旧线程还在合成，新旧两次朗读**重叠**：既会双声，又会让两份 KV 缓存同时驻留
         * （实测 PSS 3.0 GB → 5.1 GB）。太长 → 句间出现人为空档。
         * 取 1_000 ms：与 CHUNK_FRAMES=16（约 1.28 s 音频/块）匹配，切句干净。
         */
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L

        /**
         * 流式分块大小（新帧数）。
         *
         * 实际值由 `:qwen3tts` 的 `Tuning` 决定（默认 16 帧 ≈ 1.28 秒音频），
         * 可用 `adb shell setprop debug.jiandu.chunkFrames <n>` 覆盖后重启进程来扫描。
         *
         * 这是「首音延迟 / 整句总耗时 / 可中断粒度」三者的旋钮（见 docs/P3-STATUS.md §4）：
         *  - 调大（24~48）：编解码器调用少 → 总耗时与 RTF 更低（实测 48 时 RTF 5~7 → 3.5~3.9）；
         *    但**可中断粒度变粗**（一个块要合成数秒），切句时旧线程迟迟不退出 →
         *    与下一句重叠、KV 缓存翻倍（实测 PSS 冲到 5 GB）。
         *  - 调小（16）：切句细、内存稳，但总耗时高、句间空档更长。
         */
        private val CHUNK_FRAMES: Int get() = Tuning.chunkFrames

        /** 每帧采样点数：12.5 帧/秒 × 24 kHz */
        private const val SAMPLES_PER_FRAME = 1920
    }
}
