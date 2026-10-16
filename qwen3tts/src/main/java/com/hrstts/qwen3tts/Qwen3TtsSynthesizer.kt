/*
 * Copyright 2026 The Google AI Edge Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Facade written for the hrstts/JianDu app on top of the reference engine
 * ported from google-ai-edge/litert-samples (Apache-2.0),
 * samples/litert/text_to_speech_lm/kotlin_cpu/android.
 */

package com.hrstts.qwen3tts

import java.io.File
import java.io.FileNotFoundException

/**
 * 稳定的门面类：app 模块只依赖这个类，不直接接触 LiteRT 的 API。
 *
 * 门面本身不做任何算法工作，只负责：
 *  - 在构造时直接加载 [Qwen3TtsEngine]（缺失文件会立刻抛异常，不会静默返回空音频）；
 *  - 把官方实现的 [Qwen3TtsEngine.synthesize] 暴露成对 app 友好的签名
 *    （语言字符串 + 逐帧进度回调 + 可变参数的采样控制）；
 *  - 提供 [Speech] 结果（audio / frames / 各阶段耗时）与 [sampleRate]。
 *
 * 线程与生命周期：
 *  - 构造和 [synthesize] 都会长时间占用调用线程（加载约 1 GB 图 / 合成时每帧数十毫秒），
 *    必须在工作线程调用；本类不做任何线程切换。
 *  - [close] 释放全部 CompiledModel 与 TensorBuffer；释放后不能再用。
 */
class Qwen3TtsSynthesizer(private val modelDir: File) : AutoCloseable {

    /**
     * 官方引擎实例。构造 [Qwen3TtsEngine] 时会一次性加载全部图与宿主表，
     * 任一必需文件缺失即抛异常（见 [requireModelFiles]）。
     */
    private val engine = Qwen3TtsEngine(modelDir)

    /**
     * 实际使用的图组合，例如 `talker_int4 + mtp_folded_int8 + codec_partA + codec_partB`。
     *
     * 注意：**不会**触发图加载（只看文件是否存在）。图的加载已推迟到首次合成，
     * 因此这里不能用会实例化图的 `engine.graphNames`。
     */
    val graphsDescription: String = Qwen3TtsEngine.describeGraphs(modelDir)

    /** 输出采样率，参考实现固定 24 kHz。 */
    val sampleRate: Int = Qwen3TtsEngine.SAMPLE_RATE

    /** 当前引擎用的默认说话人向量（1024 维 x-vector，来自 `demo_speaker.npy`）。 */
    val speaker: FloatArray get() = engine.speaker

    /** 是否为折叠 int8 MTP 图（否则是 `mtp_fp32.tflite` 参考图）。 */
    val mtpFolded: Boolean get() = engine.mtpFolded

    /** 是否为拆分编解码器（`codec_partA` + `codec_partB`，否则是 `codec_decoder_fp32`）。 */
    val codecSplit: Boolean get() = engine.codecSplit

    /**
     * 预加载三个推理图（talker / MTP / codec，合计约 5 s、2 GB native 堆）。
     *
     * 图的创建本来推迟到首次合成；如果调用方希望在**空闲时**提前付掉这笔开销
     * （例如模型刚装好、用户还没点朗读），调用本方法即可。多线程安全由引擎内部的
     * `CompiledModel` 创建顺序保证（同一线程串行调用即可）。
     *
     * @return 本次实际耗时（毫秒），用于日志/埋点
     */
    fun preloadGraphs(): Long {
        val t0 = System.nanoTime()
        engine.ensureGraphsLoaded()
        return (System.nanoTime() - t0) / 1_000_000
    }

    /**
     * 合成一段语音。
     *
     * @param text 纯文本（无特殊 token），内部走 Qwen2 字节级 BPE。
     * @param language [Qwen3TtsEngine.LANGUAGE_IDS] 的键，例如 `"chinese"` / `"english"`；
     *   另有 `"auto"`，表示不指定语言（走 NOTHINK 控制序列）。大小写不敏感，
     *   未知值抛 [IllegalArgumentException]。
     * @param speaker 说话人 x-vector，默认 [Qwen3TtsSynthesizer.speaker]（`demo_speaker.npy`）。
     * @param greedy `true` 走贪心解码（与 Python/PyTorch 参考实现逐 token 一致），
     *   `false` 走 top-50 / temperature 0.9 采样。折叠 MTP 图下残差码本永远是图内 argmax。
     * @param seed 采样随机种子；`null` 表示每次随机。
     * @param onFrame 每生成一帧音频回调一次，参数是**已生成的帧数**（从 1 开始，
     *   最终等于结果里的 [Speech.frames]）。在调用线程（即 [synthesize] 所在线程）同步回调。
     * @return [Speech]：单声道 PCM float，已归一化到 [-1, 1]，24 kHz（见 [sampleRate]）。
     */
    fun synthesize(
        text: String,
        language: String = DEFAULT_LANGUAGE,
        speaker: FloatArray = this.speaker,
        greedy: Boolean = false,
        seed: Long? = null,
        onFrame: (Int) -> Unit = {},
    ): Speech {
        val result = engine.synthesize(
            text = text,
            language = language,
            spk = speaker,
            greedy = greedy,
            seed = seed,
            progress = if (onFrame === NO_FRAME_CALLBACK) {
                null
            } else {
                object : Qwen3TtsEngine.Progress {
                    override fun onFrame(frame: Int) = onFrame(frame)
                }
            },
        )
        return Speech(
            audio = result.audio,
            sampleRate = sampleRate,
            frames = result.frames,
            prefillMs = result.prefillMs,
            talkerMs = result.talkerMs,
            mtpMs = result.mtpMs,
            codecMs = result.codecMs,
        )
    }

    /**
     * 与 [synthesize] 完全相同的调用，只返回 PCM 数据，方便直接接 AudioTrack。
     */
    fun synthesizeToPcm(
        text: String,
        language: String = DEFAULT_LANGUAGE,
        speaker: FloatArray = this.speaker,
        greedy: Boolean = false,
        seed: Long? = null,
        onFrame: (Int) -> Unit = {},
    ): FloatArray = synthesize(text, language, speaker, greedy, seed, onFrame).audio

    /**
     * **流式合成**：边生成边解码，每凑够 [chunkFrames] 个新音频帧就通过 [onPcm] 回吐一段 PCM
     * （单声道 float、24 kHz，已归一化），调用方可以立刻写入 `AudioTrack` 播放，
     * 从而把「首音延迟」从「整句合成完」缩短到「首块生成 + 首块解码」。
     *
     * 语义与 [synthesize] 的差异：
     *  - 返回值里的 [Speech.audio] 为空（PCM 已经通过 [onPcm] 分块给出），[Speech.frames]
     *    与各阶段耗时仍然有效；
     *  - 分块解码与一次性解码**逐样本等价**：编解码器是因果结构、左侧上下文只需 25 帧，
     *    因此任意切分不影响输出（与官方参考实现的尾部零填充行为一致）；
     *  - [shouldStop] 每生成一帧检查一次，返回 true 立即停止（用于「停止朗读」，不必等整句合成完）。
     *
     * @param chunkFrames 每多少新帧解码一次（12.5 帧/秒 → 16 帧 ≈ 1.28 秒音频）
     * @param onPcm 在调用线程同步回调；该回调可以阻塞（AudioTrack 写入形成背压）
     */
    fun synthesizeStreaming(
        text: String,
        language: String = DEFAULT_LANGUAGE,
        speaker: FloatArray = this.speaker,
        greedy: Boolean = false,
        seed: Long? = null,
        chunkFrames: Int = DEFAULT_CHUNK_FRAMES,
        shouldStop: () -> Boolean = { false },
        onFrame: (Int) -> Unit = {},
        onPcm: (FloatArray) -> Unit,
    ): Speech {
        val result = engine.synthesize(
            text = text,
            language = language,
            spk = speaker,
            greedy = greedy,
            seed = seed,
            progress = if (onFrame === NO_FRAME_CALLBACK) {
                null
            } else {
                object : Qwen3TtsEngine.Progress {
                    override fun onFrame(frame: Int) = onFrame(frame)
                }
            },
            streamChunkFrames = chunkFrames.coerceAtLeast(1),
            onPcm = onPcm,
            shouldStop = shouldStop,
        )
        return Speech(
            audio = FloatArray(0),
            sampleRate = sampleRate,
            frames = result.frames,
            prefillMs = result.prefillMs,
            talkerMs = result.talkerMs,
            mtpMs = result.mtpMs,
            codecMs = result.codecMs,
        )
    }

    /** 纯文本分词结果，便于自检（对应官方 MainActivity 里的 tokenizer self-test）。 */
    fun encodeText(text: String): IntArray = engine.encodeText(text)

    /** 一次合成的结果。 */
    data class Speech(
        /** 单声道 PCM float，24 kHz，已归一化到 [-1, 1]。 */
        val audio: FloatArray,
        /** 采样率，固定 24000。 */
        val sampleRate: Int,
        /** 生成的音频帧数；每帧 1920 个采样点（0.08 s）。 */
        val frames: Int,
        /** talker 预填充耗时（ms）。 */
        val prefillMs: Long,
        /** talker 自回归解码总耗时（ms）。 */
        val talkerMs: Long,
        /** MTP 残差码本预测总耗时（ms）。 */
        val mtpMs: Long,
        /** 编解码器（codec）解码总耗时（ms）。 */
        val codecMs: Long,
    ) {
        /** 音频时长（秒）。 */
        val durationSeconds: Double get() = audio.size.toDouble() / sampleRate

        /** 端到端耗时（ms）：prefill + talker + mtp + codec。 */
        val totalMs: Long get() = prefillMs + talkerMs + mtpMs + codecMs

        /** 实时率 RTF = 端到端耗时 / 音频时长。 */
        val realTimeFactor: Double
            get() = if (audio.isEmpty()) 0.0 else totalMs / 1000.0 / durationSeconds
    }

    override fun close() = engine.close()

    companion object {
        /** 默认语言（门面默认值；官方 [Qwen3TtsEngine.synthesize] 默认是 `"english"`）。 */
        const val DEFAULT_LANGUAGE = "chinese"

        /** 每帧音频的采样点数（12.5 帧/秒 × 24 kHz）。 */
        const val SAMPLES_PER_FRAME = 1920

        /** 流式合成默认分块：16 帧 ≈ 1.28 秒音频 */
        const val DEFAULT_CHUNK_FRAMES = 16

        /** 单次合成的最大帧数（官方引擎上限）。 */
        const val MAX_FRAMES = Qwen3TtsEngine.MAX_FRAMES

        /** 支持的语言键（含 `"auto"` 由 [synthesize] 单独处理）。 */
        val LANGUAGES: Set<String> = Qwen3TtsEngine.LANGUAGE_IDS.keys

        private val NO_FRAME_CALLBACK: (Int) -> Unit = {}

        /**
         * 校验 `modelDir` 里必需的文件是否齐备，不齐时抛
         * [FileNotFoundException] 并列出缺失文件名（不会静默返回空音频）。
         *
         * 必需：`vocab.json`、`merges.txt`、`talker_int4.tflite`、
         * `codec_embedding_fp32.npy`、`mtp_embeddings_fp16.npy`、`text_embedding_fp16.npy`、
         * `text_projection_fp32.npz`、`demo_speaker.npy`；
         * 二选一图组：`mtp_folded_int8.tflite` + `codec_partA.tflite` + `codec_partB.tflite`
         * 或 `mtp_fp32.tflite` + `codec_decoder_fp32.tflite`。
         *
         * 注意：这只是把失败提前并给出明确文件名；即使跳过本方法，
         * 构造 [Qwen3TtsSynthesizer] 也会在加载阶段抛出等价的异常。
         */
        @JvmStatic
        fun requireModelFiles(modelDir: File) {
            val missing = ArrayList<String>()
            for (name in ALWAYS_REQUIRED) {
                if (!File(modelDir, name).isFile) missing.add(name)
            }
            val fast = FAST_GRAPHS.all { File(modelDir, it).isFile }
            val reference = REFERENCE_GRAPHS.all { File(modelDir, it).isFile }
            // 拆分编解码器只装了一半属于装坏，不算回退（官方引擎同样会直接报错）
            val partA = File(modelDir, "codec_partA.tflite").isFile
            val partB = File(modelDir, "codec_partB.tflite").isFile
            if (partA != partB) {
                missing.add(if (partA) "codec_partB.tflite" else "codec_partA.tflite")
            }
            if (!fast && !reference) {
                missing.add("mtp_folded_int8.tflite + codec_partA.tflite + codec_partB.tflite" +
                    " 或 mtp_fp32.tflite + codec_decoder_fp32.tflite")
            }
            if (missing.isNotEmpty()) {
                throw FileNotFoundException(
                    "Qwen3-TTS 模型目录不完整: ${modelDir.absolutePath}; 缺失: " +
                        missing.joinToString(", "))
            }
        }

        private val ALWAYS_REQUIRED = listOf(
            "vocab.json",
            "merges.txt",
            "talker_int4.tflite",
            "codec_embedding_fp32.npy",
            "mtp_embeddings_fp16.npy",
            "text_embedding_fp16.npy",
            "text_projection_fp32.npz",
            "demo_speaker.npy",
        )

        private val FAST_GRAPHS = listOf(
            "mtp_folded_int8.tflite", "codec_partA.tflite", "codec_partB.tflite")

        private val REFERENCE_GRAPHS = listOf(
            "mtp_fp32.tflite", "codec_decoder_fp32.tflite")
    }
}
