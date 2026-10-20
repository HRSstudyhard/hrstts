package com.hrstts.jiandu.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.tts.RemoteTtsProtocol
import com.hrstts.jiandu.domain.tts.TtsEngine
import com.hrstts.jiandu.domain.tts.TtsListener
import com.hrstts.jiandu.i18n.UiStrings
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedDeque
import javax.net.ssl.SSLException

/**
 * 远程（电脑）TTS 引擎。
 *
 * 与 [Qwen3TtsEngine] 的关系：**同一套播放与缓存骨架，只把「LiteRT 合成」换成「HTTP 拉 PCM」**。
 * 因此这里能白拿到端侧引擎已经打磨过的全部行为：
 *  - 句级连播（由 [com.hrstts.jiandu.domain.tts.TtsController] 驱动，引擎一次只管一句）；
 *  - 两级音频缓存（命中就秒开，不再走网络）；
 *  - 可被打断的流式播放（[stop] 会 `disconnect` 掉正在读的 socket）；
 *  - 语速走 `AudioTrack` 变速（不改合成结果，所以不进缓存 key）。
 *
 * 换来的好处：手机上不再需要 1.6 GB 模型、不再有 RTF 4~5 的等待、也不再吃 1.5~3 GB 内存。
 */
class RemoteTtsEngine(
    private val settings: RemoteTtsSettings,
    private val strings: UiStrings,
    /** PCM 音频缓存（两档：RAM + 磁盘 LRU）；null 表示不使用缓存 */
    private val cache: TtsAudioCache? = null,
) : TtsEngine {

    override val id: String = ID

    override val displayName: String
        get() {
            val base = strings.get(R.string.engine_remote)
            val host = settings.baseUrl.removePrefix("http://").removePrefix("https://")
            return if (isReady && host.isNotEmpty()) "$base · $host" else base
        }

    override var isReady: Boolean = false
        private set

    /** 服务端 `/v1/health` 返回的引擎名（sapi / gpt-sovits / …），供诊断显示 */
    @Volatile
    var serverEngine: String = ""
        private set

    /** 服务端返回的音色列表（UI 用来做选择器） */
    @Volatile
    var availableVoices: List<RemoteTtsClient.Voice> = emptyList()
        private set

    /** 实际生效的音色 id（用户没选时就是服务端默认音色）。同步要用它算缓存 key。 */
    val currentVoiceId: String get() = voiceId

    /** 实际生效的 modelTag（来自服务端 health）。同步要用它算缓存 key。 */
    val currentModelTag: String get() = modelTag

    private var listener: TtsListener? = null
    private var client: RemoteTtsClient? = null
    private var worker: Thread? = null
    private var prefetchWorker: Thread? = null
    private val prefetchQueue = ConcurrentLinkedDeque<String>()

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var currentUtteranceId: String? = null

    /** 缓存 key 的第三段；以服务端下发的 modelTag 为准（服务端换引擎时自动失效缓存） */
    @Volatile
    private var modelTag: String = RemoteTtsProtocol.DEFAULT_MODEL_TAG

    /** 当前音色 id；空串表示用服务端默认音色 */
    @Volatile
    private var voiceId: String = ""

    @Volatile
    private var sampleRate: Int = RemoteTtsProtocol.SAMPLE_RATE

    @Volatile
    var prefetchEnabled: Boolean = true

    private var speechRate: Float = 1.0f

    override fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    // ---- 生命周期 ----

    override fun initialize() {
        if (isReady) return
        val baseUrl = settings.baseUrl
        if (baseUrl.isEmpty()) {
            listener?.onError(strings.get(R.string.remote_no_server))
            return
        }
        val connection = RemoteTtsClient(baseUrl)
        client = connection
        Thread({
            try {
                val started = System.nanoTime()
                val health = connection.health()
                modelTag = health.modelTag.ifBlank { RemoteTtsProtocol.DEFAULT_MODEL_TAG }
                sampleRate = health.sampleRate.takeIf { it > 0 } ?: RemoteTtsProtocol.SAMPLE_RATE
                serverEngine = health.engine
                availableVoices = health.voices
                // 用户没选音色 → 用服务端默认；选了但服务端没有 → 也回落到默认
                val wanted = settings.voice
                voiceId = wanted.takeIf { id -> health.voices.any { it.id == id } }
                    ?: health.defaultVoice
                settings.cachedModelTag = modelTag
                isReady = true
                android.util.Log.i(
                    LOG_TAG,
                    "远程引擎已就绪：$baseUrl engine=${health.engine} voice=$voiceId " +
                        "modelTag=$modelTag 音色数=${health.voices.size} " +
                        "探测耗时=${(System.nanoTime() - started) / 1_000_000}ms",
                )
                listener?.onReady()
            } catch (t: Throwable) {
                if (t is InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                val message = friendlyError(t)
                android.util.Log.w(LOG_TAG, "远程引擎初始化失败：$baseUrl", t)
                listener?.onError(strings.get(R.string.remote_connect_failed, message))
            }
        }, "remote-tts-init").apply { isDaemon = true }.start()
    }

    // ---- 朗读 ----

    override fun speak(text: String, utteranceId: String) {
        val connection = client
        if (connection == null || !isReady) {
            listener?.onError(strings.get(R.string.remote_not_ready))
            return
        }

        // 与端侧引擎同样的约定：不在主线程 join 上一个 worker（会把 UI 卡住）
        stopRequested = true
        val previous = worker
        currentUtteranceId = utteranceId
        prefetchQueue.remove(text)

        val cacheKey = cache?.let { RemoteTtsProtocol.keyOf(text, voiceId, modelTag) }

        worker = Thread({
            stopRequested = false
            if (previous != null) {
                previous.join(WORKER_JOIN_TIMEOUT_MS)
                if (currentUtteranceId != utteranceId) return@Thread
                stopRequested = false
            }

            val cached = cacheKey?.let { cache?.get(it) }

            val track = try {
                createTrack()
            } catch (t: Throwable) {
                listener?.onError(
                    strings.get(R.string.remote_audio_output_failed, t.message ?: t.javaClass.simpleName),
                )
                return@Thread
            }

            var firstChunkMs = -1L
            var writtenSamples = 0L
            val startedAt = System.nanoTime()

            try {
                listener?.onStart(utteranceId)
                track.play()

                if (cached != null) {
                    // 命中缓存：完全不碰网络（这是「重听 / 回看 / 断网」的关键路径）
                    firstChunkMs = (System.nanoTime() - startedAt) / 1_000_000
                    writtenSamples = playCached(utteranceId, text, cached, track)
                    android.util.Log.i(
                        LOG_TAG,
                        "缓存命中：${cached.frames} 帧（${"%.2f".format(cached.audioSeconds)}s 音频），" +
                            "首音延迟=${firstChunkMs}ms，命中率=${runCatching { cache?.statsLine() }.getOrNull()}",
                    )
                    if (!stopRequested && currentUtteranceId == utteranceId) {
                        runCatching { track.stop() }
                        listener?.onDone(utteranceId)
                    }
                    return@Thread
                }

                val accumulator = ShortAccumulator()
                val assembler = PcmAssembler()
                val shorts = ShortArray(READ_SAMPLES + 1)
                var framesFromHeader = -1

                framesFromHeader = connection.synthesize(
                    text = text,
                    voice = voiceId,
                    isCancelled = { stopRequested || currentUtteranceId != utteranceId },
                ) { buffer, count ->
                    if (firstChunkMs < 0) {
                        firstChunkMs = (System.nanoTime() - startedAt) / 1_000_000
                    }
                    val produced = assembler.toShorts(buffer, count, shorts)
                    if (produced > 0) {
                        accumulator.append(shorts, produced)
                        val wrote = track.write(shorts, 0, produced, AudioTrack.WRITE_BLOCKING)
                        if (wrote > 0) writtenSamples += wrote
                        reportApproximateProgress(utteranceId, text, writtenSamples)
                    }
                }

                if (stopRequested || currentUtteranceId != utteranceId) return@Thread

                // 整句合成完成 → 写入两档缓存（下次重听、以及「同步到手机」都靠它）
                val all = accumulator.toArray()
                val frames = if (framesFromHeader > 0) {
                    framesFromHeader
                } else {
                    RemoteTtsProtocol.framesOf(all.size)
                }
                if (cacheKey != null && all.isNotEmpty()) {
                    cache?.putShorts(cacheKey, all, frames)
                }

                val totalMs = (System.nanoTime() - startedAt) / 1_000_000
                val audioSeconds = RemoteTtsProtocol.secondsOf(all.size)
                android.util.Log.i(
                    LOG_TAG,
                    "远程合成完成：${frames} 帧（${"%.2f".format(audioSeconds)}s 音频），" +
                        "首音延迟=${firstChunkMs}ms，总耗时=${totalMs}ms，" +
                        "RTF=${"%.3f".format(if (audioSeconds > 0) totalMs / 1000.0 / audioSeconds else 0.0)}，" +
                        "server=$serverEngine",
                )

                if (!stopRequested && currentUtteranceId == utteranceId) {
                    runCatching { track.stop() }
                    listener?.onDone(utteranceId)
                }
            } catch (t: Throwable) {
                if (!stopRequested && currentUtteranceId == utteranceId) {
                    android.util.Log.e(LOG_TAG, "远程合成失败", t)
                    listener?.onError(strings.get(R.string.remote_synthesis_failed, friendlyError(t)))
                }
            } finally {
                runCatching { track.release() }
            }
        }, "remote-tts-speak")
        worker?.apply { isDaemon = true }?.start()
    }

    override fun stop() {
        stopRequested = true
        currentUtteranceId = null
        // 真正打断正在阻塞的 socket 读，否则切句要等上一句读完
        client?.cancel()
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
    }

    override fun shutdown() {
        stopRequested = true
        currentUtteranceId = null
        prefetchQueue.clear()
        runCatching { client?.cancel() }
        client = null
        isReady = false
    }

    // ---- 预取 ----

    override fun prefetch(text: String) {
        if (!prefetchEnabled || !isReady || text.isBlank()) return
        prefetchQueue.add(text)
        startPrefetchWorkerIfIdle()
    }

    /** 退后台：清空内存档缓存（磁盘档保留，回来仍能秒开） */
    fun trimCacheForBackground(): Long {
        prefetchQueue.clear()
        return cache?.clearMemory() ?: 0L
    }

    private fun startPrefetchWorkerIfIdle() {
        prefetchWorker?.let { if (it.isAlive) return }
        val worker = Thread({
            while (true) {
                val text = prefetchQueue.poll() ?: break
                if (!prefetchEnabled || !isReady) break
                if (stopRequested || currentUtteranceId != null) {
                    prefetchQueue.addFirst(text)
                    Thread.sleep(PREFETCH_PAUSE_MS)
                    continue
                }
                prefetchToCache(text)
            }
        }, "remote-tts-prefetch")
        worker.isDaemon = true
        prefetchWorker = worker
        worker.start()
    }

    /** 只合成进缓存、不播放；成功返回 true。 */
    fun prefetchToCache(text: String): Boolean {
        val connection = client ?: return false
        val audioCache = cache ?: return false
        if (!isReady || text.isBlank()) return false
        val key = RemoteTtsProtocol.keyOf(text, voiceId, modelTag)
        if (audioCache.contains(key)) return true
        if (currentUtteranceId != null) return false
        return try {
            val accumulator = ShortAccumulator()
            val assembler = PcmAssembler()
            val shorts = ShortArray(READ_SAMPLES + 1)
            var frames = -1
            frames = connection.synthesize(text, voiceId, isCancelled = { currentUtteranceId != null }) { buffer, count ->
                val produced = assembler.toShorts(buffer, count, shorts)
                if (produced > 0) accumulator.append(shorts, produced)
            }
            if (currentUtteranceId != null) return false
            val all = accumulator.toArray()
            if (all.isEmpty()) return false
            audioCache.putShorts(key, all, if (frames > 0) frames else RemoteTtsProtocol.framesOf(all.size))
            true
        } catch (t: Throwable) {
            android.util.Log.w(LOG_TAG, "预取失败（不影响朗读）：${t.message}")
            false
        }
    }

    // ---- 内部 ----

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
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
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (speechRate != 1.0f) {
            runCatching { track.playbackParams = track.playbackParams.setSpeed(speechRate) }
        }
        return track
    }

    /** 从缓存回放（命中路径），沿用与在线路径相同的高亮推进。 */
    private fun playCached(
        utteranceId: String,
        text: String,
        clip: TtsAudioCache.Clip,
        track: AudioTrack,
    ): Long {
        var written = 0L
        var index = 0
        val chunk = 8192
        while (index < clip.pcm.size) {
            if (stopRequested || currentUtteranceId != utteranceId) break
            val count = minOf(chunk, clip.pcm.size - index)
            val wrote = track.write(clip.pcm, index, count, AudioTrack.WRITE_BLOCKING)
            if (wrote <= 0) break
            written += wrote
            index += wrote
            reportApproximateProgress(utteranceId, text, written)
        }
        return written
    }

    /**
     * 远程合成按「已写出的采样数 / 预估总采样数」推进正文字符高亮。
     * 预估按中文约 0.24 秒/字（≈3 帧/字），仅用于让高亮跟着朗读走，不追求精确。
     */
    private fun reportApproximateProgress(utteranceId: String, text: String, writtenSamples: Long) {
        val estimatedSamples = (text.length * 0.24 * sampleRate).coerceAtLeast(sampleRate * 0.2)
        val fraction = (writtenSamples / estimatedSamples).coerceIn(0.0, 1.0)
        val end = (text.length * fraction).toInt().coerceIn(0, text.length)
        listener?.onRange(utteranceId, 0, end)
    }

    /** 把网络异常翻译成用户能行动的中文提示。 */
    private fun friendlyError(t: Throwable): String = when (t) {
        is UnknownHostException -> strings.get(R.string.remote_error_unknown_host)
        is ConnectException -> strings.get(R.string.remote_error_refused)
        is SocketTimeoutException -> strings.get(R.string.remote_error_timeout)
        is NoRouteToHostException -> strings.get(R.string.remote_error_no_route)
        is SSLException -> strings.get(R.string.remote_error_ssl)
        is IOException -> t.message ?: t.javaClass.simpleName
        else -> t.message ?: t.javaClass.simpleName
    }

    /**
     * 把任意切分的字节流拼成 int16 小端采样。
     *
     * 必须处理「一个采样被切成两半」的情况：`HttpURLConnection` 的 read 边界与采样边界无关，
     * 直接按 `count/2` 转换会让后续所有采样错位一个字节 → 整句变成噪音。
     */
    private class PcmAssembler {
        private var carry: Byte = 0
        private var hasCarry = false

        fun toShorts(bytes: ByteArray, count: Int, out: ShortArray): Int {
            var index = 0
            var produced = 0
            if (hasCarry && count > 0) {
                out[produced++] = ((carry.toInt() and 0xFF) or (bytes[0].toInt().shl(8))).toShort()
                index = 1
                hasCarry = false
            }
            while (index + 1 < count) {
                out[produced++] = ((bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt().shl(8))).toShort()
                index += 2
            }
            if (index < count) {
                carry = bytes[index]
                hasCarry = true
            }
            return produced
        }
    }

    /** 累积整句 PCM（int16），最后一次性写缓存。 */
    private class ShortAccumulator {
        private var buffer = ShortArray(INITIAL_SAMPLES)
        private var size = 0

        fun append(source: ShortArray, count: Int) {
            if (size + count > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
            }
            System.arraycopy(source, 0, buffer, size, count)
            size += count
        }

        fun toArray(): ShortArray = buffer.copyOf(size)
    }

    companion object {
        const val ID = "remote"
        private const val LOG_TAG = "RemoteTtsEngine"

        /** 一次 read 最多能装多少个采样（8 KB 字节 ≈ 4096 个采样，多留 1 个给 carry） */
        private const val READ_SAMPLES = 4096

        private const val INITIAL_SAMPLES = 24000 * 4

        /** 切换朗读时等待上一个 worker 退出的上限（与端侧引擎一致） */
        private const val WORKER_JOIN_TIMEOUT_MS = 1_000L

        /** 预取线程在朗读进行中的轮询间隔 */
        private const val PREFETCH_PAUSE_MS = 300L
    }
}
