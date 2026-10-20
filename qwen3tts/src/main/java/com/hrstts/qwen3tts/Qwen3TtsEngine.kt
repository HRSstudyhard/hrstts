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
 * Ported from google-ai-edge/litert-samples (Apache-2.0),
 * samples/litert/text_to_speech_lm/kotlin_cpu/android.
 */

package com.hrstts.qwen3tts

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.exp
import kotlin.math.min

/**
 * Qwen3-TTS on LiteRT: the host-orchestrated Compiled Model decode loop.
 *
 * Runs the talker LM (prefill_32/decode signatures, KV 1024), the MTP code
 * predictor and the codec decoder (64-frame chunks -> 24 kHz PCM), plus
 * host-side BPE tokenization, embedding-table lookups, prompt assembly, and
 * sampling. It is a Kotlin port of the Python reference pipeline in the sibling
 * `python/` directory. With the reference graphs and greedy decoding it
 * reproduces the PyTorch implementation token-for-token.
 *
 * The MTP and codec graphs come in two forms, and the engine takes the one it
 * finds in [dir]. Reference graphs: `mtp_fp32.tflite`, one decode step per
 * invoke (17-slot KV, invoked 16x per audio frame), and
 * `codec_decoder_fp32.tflite`. Folded and split graphs, which the model
 * repository also publishes: `mtp_folded_int8.tflite`, the MTP inner loop in
 * one invoke per frame with int8 weights and the 15 residual codes chosen by
 * argmax inside the graph, and the codec decoder in two parts,
 * `codec_partA.tflite` (fp32) and `codec_partB.tflite` (run with XNNPACK's
 * FORCE_FP16 flag). `install_to_device.sh` installs the folded and split
 * graphs by default and the reference graphs with `FAST=0`.
 */
class Qwen3TtsEngine(private val dir: File) {

    companion object {
        private const val TAG = "Qwen3Tts"
        private const val HIDDEN = 1024
        private const val CODEC_VOCAB = 3072
        private const val CACHE = 1024
        private const val NEG = -1e9f
        private const val EOS = 2150
        private const val PAD_ID = 2148
        private const val BOS_ID = 2149
        private const val THINK = 2154
        private const val THINK_BOS = 2156
        private const val THINK_EOS = 2157
        private const val NOTHINK = 2155
        private const val TTS_BOS = 151672
        private const val TTS_EOS = 151673
        private const val TTS_PAD = 151671
        private const val MTP_LAYERS = 5
        private const val MTP_CACHE = 17
        private const val MTP_KV_FLOATS = MTP_LAYERS * 8 * MTP_CACHE * 128
        private const val MTP_VOCAB = 2048
        private const val CODEC_CHUNK = 64
        private const val CODEC_CTX = 25
        private const val UPSAMPLE = 1920
        // TFLITE_XNNPACK_DELEGATE_FLAG_FORCE_FP16: ask XNNPACK to run an fp32
        // graph in fp16 (CompiledModel.CpuOptions.xnnPackFlags).
        private const val XNNPACK_FORCE_FP16 = 4
        const val SAMPLE_RATE = 24000
        const val MAX_FRAMES = 512

        // <|im_start|>assistant\n   and   <|im_end|>\n<|im_start|>assistant\n
        private val PROMPT_PREFIX = intArrayOf(151644, 77091, 198)
        private val PROMPT_SUFFIX = intArrayOf(151645, 198, 151644, 77091, 198)

        val LANGUAGE_IDS = mapOf(
            "chinese" to 2055, "english" to 2050, "german" to 2053,
            "italian" to 2070, "portuguese" to 2071, "spanish" to 2054,
            "japanese" to 2058, "korean" to 2064, "french" to 2061,
            "russian" to 2069,
        )

        /**
         * 只根据**文件是否存在**推断图组合，不实例化任何图。
         *
         * 这样状态行/日志可以在图尚未加载时正确显示，不会因为「读一个描述」而触发
         * 数秒的图加载（实测曾因这一行把延迟加载的收益全部吃掉）。
         */
        fun describeGraphs(dir: File): String {
            val folded = File(dir, "mtp_folded_int8.tflite").exists()
            val split = File(dir, "codec_partA.tflite").exists() &&
                File(dir, "codec_partB.tflite").exists()
            return "talker_int4 + " + (if (folded) "mtp_folded_int8" else "mtp_fp32") +
                " + " + (if (split) "codec_partA + codec_partB" else "codec_decoder_fp32")
        }
    }

    private val tokenizer =
        QwenBpeTokenizer(File(dir, "vocab.json"), File(dir, "merges.txt"))

    /** Exposes plain-text tokenization for the startup self-test. */
    fun encodeText(text: String): IntArray = tokenizer.encode(text)

    // Host tables. The two big ones stay memory-mapped fp16.
    private val codecEmb = Npy.loadFloats(File(dir, "codec_embedding_fp32.npy"))
    private val mtpEmb: ShortBuffer = Npy.mmapHalf(File(dir, "mtp_embeddings_fp16.npy"))
    private val textEmb: ShortBuffer = Npy.mmapHalf(File(dir, "text_embedding_fp16.npy"))
    private val proj = Npy.loadNpz(
        File(dir, "text_projection_fp32.npz"), listOf("w1", "b1", "w2", "b2"))
    val speaker: FloatArray = Npy.loadFloats(File(dir, "demo_speaker.npy"))

    private fun load(name: String, threads: Int, xnnPackFlags: Int? = null): CompiledModel {
        val f = File(dir, name)
        check(f.exists()) { "Model not found: $name — run install_to_device.sh" }
        val options = CompiledModel.Options(Accelerator.CPU)
        options.cpuOptions = CompiledModel.CpuOptions(threads, xnnPackFlags, null)
        return CompiledModel.create(f.absolutePath, options, null)
    }

    /** True when the folded MTP graph is in [dir], where it replaces the reference graph. */
    val mtpFolded = File(dir, "mtp_folded_int8.tflite").exists()

    /** True when both parts of the split codec are in [dir], where they replace the decoder. */
    val codecSplit = checkSplitCodec()

    // One part of the split codec is an incomplete install, not a reason to fall back;
    // checked before the first graph loads.
    private fun checkSplitCodec(): Boolean {
        val a = File(dir, "codec_partA.tflite").exists()
        val b = File(dir, "codec_partB.tflite").exists()
        check(a == b) {
            "Split codec needs codec_partA.tflite and codec_partB.tflite; found only " +
                (if (a) "Part A" else "Part B") + " — run install_to_device.sh"
        }
        return a
    }

    /**
     * 三个大图改成**首次合成时**才创建。
     *
     * 实测加载耗时：`talker_int4` 2157 ms + `mtp_folded_int8` 2140 ms + `codec_partA` 98 ms +
     * `codec_partB` 762 ms ≈ **5.2 s**（外加宿主表与 tokenizer 约 2 s，合计约 7 s）。
     * 而用户点「阅读」之前根本不需要这些图，因此把最贵的 talker/MTP 推迟到真正要合成时。
     * 日志与错误路径保持不变（加载失败照样走 `onError`）。
     */
    private var talkerModel: CompiledModel? = null
    private var mtpModel: CompiledModel? = null
    private var codecAModel: CompiledModel? = null
    private var codecModel: CompiledModel? = null

    // 线程数与 XNNPACK 标志走 Tuning（可用 setprop 覆盖，见 Tuning 的类注释）。
    // 内置默认值与原写死的常量完全一致（talker 4 / mtp 折叠 4 / mtp 参考 2 / codec 4 + FP16），
    // 因此不设置任何属性时行为与加入 Tuning 之前逐位一致。
    private val talker: CompiledModel
        get() = talkerModel ?: load("talker_int4.tflite", Tuning.talkerThreads).also { talkerModel = it }

    // The reference MTP graph keeps the pipeline's 2 threads; the folded graph, one
    // larger invoke per frame, gets the 4 the other graphs use.
    private val mtp: CompiledModel
        get() = mtpModel ?: (
            if (mtpFolded) {
                load("mtp_folded_int8.tflite", Tuning.mtpFoldedThreads)
            } else {
                load("mtp_fp32.tflite", Tuning.mtpReferenceThreads)
            }
            ).also { mtpModel = it }

    private val codecA: CompiledModel?
        get() {
            if (!codecSplit) return null
            return codecAModel ?: load("codec_partA.tflite", Tuning.codecAThreads).also { codecAModel = it }
        }

    private val codec: CompiledModel
        get() = codecModel ?: (if (codecSplit) {
            load(
                "codec_partB.tflite",
                Tuning.codecThreads,
                if (Tuning.codecForceFp16) XNNPACK_FORCE_FP16 else null,
            )
        } else {
            load("codec_decoder_fp32.tflite", Tuning.codecThreads)
        }).also { codecModel = it }

    /** File names of the graphs in use, for the status line and the log. */
    val graphNames = describeGraphs(dir)

    /**
     * 提前把三个推理图创建出来（空闲预加载用）。
     *
     * 与首次合成走的是同一套 `get()`，因此调用时机只影响延迟分布，不影响正确性：
     * 提前调用 = 「启动慢、朗读快」，不调用 = 「启动快、首次朗读慢」。
     */
    fun ensureGraphsLoaded() {
        talker
        mtp
        codecA
        codec
    }

    private val kvNames = (0 until 28).flatMap {
        listOf("kv_cache_k_$it", "kv_cache_v_$it")
    }

    data class Result(
        val audio: FloatArray, val frames: Int,
        val prefillMs: Long, val talkerMs: Long, val mtpMs: Long,
        val codecMs: Long,
    )

    interface Progress { fun onFrame(frame: Int) }

    /**
     * Synthesizes [text] in the voice of [spk] (1024-d x-vector).
     *
     * With [greedy] the loop matches the Python reference (and hence the
     * PyTorch implementation) token-for-token; otherwise top-k/temperature
     * sampling with the model's default parameters. Both apply to the first
     * codebook and, with the reference MTP graph, to the 15 residual codebooks.
     * With the folded MTP graph the residual codes are the graph's argmax
     * whatever [greedy] says, so the two graph sets produce different audio.
     */
    fun synthesize(
        text: String, language: String = "english",
        spk: FloatArray = speaker, greedy: Boolean = false,
        seed: Long? = null, progress: Progress? = null,
        /**
         * 流式模式（> 0 时启用）：每生成这么多「新帧」就解码一次并通过 [onPcm] 回吐 PCM。
         * 默认 0 = 官方行为（全部生成完再一次性解码）。
         */
        streamChunkFrames: Int = 0,
        /** 流式 PCM 回调，在调用线程同步执行（可以阻塞，用于背压） */
        onPcm: ((FloatArray) -> Unit)? = null,
        /** 每帧检查一次：返回 true 立即终止生成（用于「停止朗读」） */
        shouldStop: (() -> Boolean)? = null,
    ): Result {
        val rnd = if (seed != null) Random(seed) else Random()

        // ---- prompt assembly (host-side, plain lookups + the tiny MLP) ----
        val textIds = tokenizer.encode(text)
        val ttsBos = embedText(intArrayOf(TTS_BOS))[0]
        val ttsEos = embedText(intArrayOf(TTS_EOS))[0]
        val ttsPad = embedText(intArrayOf(TTS_PAD))[0]

        val control = if (language == "auto") {
            intArrayOf(NOTHINK, THINK_BOS, THINK_EOS)
        } else {
            val lang = LANGUAGE_IDS[language.lowercase()]
                ?: throw IllegalArgumentException("language: $language")
            intArrayOf(THINK, THINK_BOS, lang, THINK_EOS)
        }
        // control embeds | speaker | pad,bos  -> [C+3, 1024]
        val codecPre = ArrayList<FloatArray>()
        for (id in control) {
            codecPre.add(codecRow(id))
        }
        codecPre.add(spk.copyOf())
        codecPre.add(codecRow(PAD_ID))
        codecPre.add(codecRow(BOS_ID))

        val role = embedText(PROMPT_PREFIX)                      // [3,1024]
        val body = Array(codecPre.size - 1) { i ->              // pads+bos + codecPre[:-1]
            val cond = if (i < codecPre.size - 2) ttsPad else ttsBos
            add(cond, codecPre[i])
        }
        val firstText = add(embedText(intArrayOf(textIds[0]))[0], codecPre.last())
        val prefill = role + body + arrayOf(firstText)           // [P,1024]
        val trailing = ArrayList<FloatArray>()                   // streamed text cond
        for (i in 1 until textIds.size) {
            trailing.add(embedText(intArrayOf(textIds[i]))[0])
        }
        trailing.add(ttsEos)

        // ---- talker prefill + first decode ----
        var t0 = System.nanoTime()
        val decodeKv = TalkerState()
        decodeKv.prefill(prefill)
        var pos = prefill.size - 1
        var step = decodeKv.decode(prefill.last(), pos)
        val prefillMs = (System.nanoTime() - t0) / 1_000_000

        // ---- frame loop ----
        val suppress = FloatArray(CODEC_VOCAB)
        for (i in 2048 until CODEC_VOCAB) {
            suppress[i] = NEG
        }
        suppress[EOS] = 0f

        val frames = ArrayList<IntArray>()
        val history = HashSet<Int>()
        var talkerNs = 0L
        var mtpNs = 0L
        var codecNs = 0L

        // ---- 流式：跨块复用编解码器缓冲，边生成边解码 ----
        val streaming = streamChunkFrames > 0 && onPcm != null
        val streamBuffers = if (streaming) openCodecBuffers() else null
        var decodedFrames = 0

        fun flushFrames(force: Boolean) {
            val buffers = streamBuffers ?: return
            val pending = frames.size - decodedFrames
            if (pending <= 0) return
            if (!force && pending < streamChunkFrames) return
            val t = System.nanoTime()
            val pcm = decodeRange(frames, decodedFrames, frames.size, buffers)
            codecNs += System.nanoTime() - t
            decodedFrames = frames.size
            if (pcm.isNotEmpty()) onPcm!!.invoke(pcm)
        }

        try {
            while (frames.size < MAX_FRAMES) {
            val scores = FloatArray(CODEC_VOCAB) { step.logits[it] + suppress[it] }
            if (frames.size < 2) {
                scores[EOS] = NEG // min_new_tokens = 2
            }
            for (t in history) {
                scores[t] = if (scores[t] > 0) scores[t] / 1.05f else scores[t] * 1.05f
            }
            val cb0 = pick(scores, greedy, rnd)
            history.add(cb0)
            if (cb0 == EOS) break

            t0 = System.nanoTime()
            val residual = mtpFrame(step.hidden, cb0, greedy, rnd)
            mtpNs += System.nanoTime() - t0

            val frame = IntArray(16)
            frame[0] = cb0
            for (i in 0 until 15) {
                frame[i + 1] = residual[i]
            }
            frames.add(frame)
            progress?.onFrame(frames.size)
            if (streaming) {
                flushFrames(force = false)
                if (shouldStop?.invoke() == true) break
            }

            // next input embed = sum of 16 codebook embeds + text conditioning
            val embed = codecRow(cb0)
            for (i in 0 until 15) {
                addMtpRow(embed, i, residual[i])
            }
            val stepIdx = frames.size - 1
            val cond = if (stepIdx < trailing.size) trailing[stepIdx] else ttsPad
            for (i in 0 until HIDDEN) {
                embed[i] += cond[i]
            }

            pos += 1
            t0 = System.nanoTime()
            step = decodeKv.decode(embed, pos)
            talkerNs += System.nanoTime() - t0
        }

        // ---- codec decode (chunks with left context) ----
        if (streaming) {
            // 尾部：把不足一块的剩余帧也解码出来（与官方尾部零填充行为一致）
            flushFrames(force = true)
        }
        val audio = if (streaming) FloatArray(0) else {
            val t = System.nanoTime()
            val decoded = decodeCodes(frames)
            codecNs += System.nanoTime() - t
            decoded
        }
        val result = Result(audio, frames.size, prefillMs,
            talkerNs / 1_000_000, mtpNs / 1_000_000, codecNs / 1_000_000)
        return result
        } finally {
            // 正常与异常路径都在此释放（流式缓冲跨块复用，必须显式关闭）
            runCatching { streamBuffers?.close() }
            // KV 缓存也要释放，否则每句泄漏约 470 MB native 内存（实测会拖垮进程）
            runCatching { decodeKv.close() }
        }
    }

    // ------------------------------------------------------------------
    // Talker: prefill_32 + decode signatures with ping-pong KV buffers.
    // ------------------------------------------------------------------
    private inner class TalkerState {
        // Two full KV sets; run() alternates them as inputs/outputs to avoid
        // copying ~235 MB of cache per step.
        val setA = kvNames.associateWith { talker.createOutputBuffer(it, "decode") }
        val setB = kvNames.associateWith { talker.createOutputBuffer(it, "decode") }
        var current = setA // holds the cache AFTER the latest step

        val embIn = talker.createInputBuffer("embeddings", "decode")
        val posIn = talker.createInputBuffer("input_pos", "decode")
        val maskIn = talker.createInputBuffer("mask", "decode")
        val logitsOut = talker.createOutputBuffer("logits", "decode")
        val mask = FloatArray(CACHE) { NEG }

        fun prefill(embeds: Array<FloatArray>) {
            val p = embeds.size
            check(p <= 32) { "prompt too long for prefill_32: $p" }
            val flat = FloatArray(32 * HIDDEN)
            for (t in embeds.indices) {
                System.arraycopy(embeds[t], 0, flat, t * HIDDEN, HIDDEN)
            }
            val maskFlat = FloatArray(32 * CACHE) { NEG }
            for (row in 0 until 32) {
                val allowed = min(row, p - 1) + 1
                for (c in 0 until allowed) {
                    maskFlat[row * CACHE + c] = 0f
                }
            }
            val inputs = HashMap<String, TensorBuffer>()
            inputs["embeddings"] = talker.createInputBuffer("embeddings", "prefill_32")
                .also { it.writeFloat(flat) }
            inputs["input_pos"] = talker.createInputBuffer("input_pos", "prefill_32")
                .also { it.writeInt(IntArray(32) { i -> i }) }
            inputs["mask"] = talker.createInputBuffer("mask", "prefill_32")
                .also { it.writeFloat(maskFlat) }
            val zero = FloatArray(8 * CACHE * 128)
            for (name in kvNames) {
                inputs[name] = talker.createInputBuffer(name, "prefill_32")
                    .also { it.writeFloat(zero) }
            }
            talker.run(inputs, setA.mapValues { it.value }, "prefill_32")
            current = setA
            for (buffer in inputs.values) {
                buffer.close()
            }
        }

        fun decode(embed: FloatArray, pos: Int): Step {            embIn.writeFloat(embed)
            posIn.writeInt(intArrayOf(pos))
            mask.fill(NEG)
            for (c in 0..pos) {
                mask[c] = 0f
            }
            maskIn.writeFloat(mask)
            val next = if (current === setA) setB else setA
            val inputs = HashMap<String, TensorBuffer>(64)
            inputs["embeddings"] = embIn
            inputs["input_pos"] = posIn
            inputs["mask"] = maskIn
            for (name in kvNames) {
                inputs[name] = current.getValue(name)
            }
            val outputs = HashMap<String, TensorBuffer>(64)
            outputs["logits"] = logitsOut
            for (name in kvNames) {
                outputs[name] = next.getValue(name)
            }
            talker.run(inputs, outputs, "decode")
            current = next
            val logits = logitsOut.readFloat() // [4096] = codec logits | hidden
            return Step(
                logits.copyOfRange(0, CODEC_VOCAB),
                logits.copyOfRange(CODEC_VOCAB, CODEC_VOCAB + HIDDEN))
        }

        /**
         * 释放本次朗读占用的 KV 缓存张量。
         *
         * 这两组 ping-pong KV 缓存是 native 内存的大头（28 层 × 2 组 × 2048 token）。
         * 之前从未释放：每合成一句就新 new 一个 [TalkerState]，实测 native 堆
         * 2.45 GB → 5.76 GB（约 +470 MB/句），Swap 被吃到 672 MB，进程随时可能被系统回收。
         */
        fun close() {
            for (buffer in setA.values) buffer.close()
            for (buffer in setB.values) buffer.close()
            embIn.close()
            posIn.close()
            maskIn.close()
            logitsOut.close()
        }
    }

    class Step(val logits: FloatArray, val hidden: FloatArray)

    // ------------------------------------------------------------------
    // MTP inner loop. Reference graph: one decode step invoked 16x per frame.
    //   Inputs (positional): embed, pos, mask, k_all, v_all.
    //   Outputs (positional): logits_all [15,2048], k_all, v_all.
    // Folded graph: the whole loop in one invoke per frame.
    //   Inputs (positional): past_hidden [1,1,1024], cb0_embed [1,1,1024],
    //   noise [15,2048]. Outputs (positional): codes [15] int32, logits [15,2048].
    // ------------------------------------------------------------------
    private val mtpBuffersDelegate = lazy { openMtpBuffers() }
    private val mtpBuffers by mtpBuffersDelegate

    private fun openMtpBuffers(): MtpBuffers {
        val ins = mtp.createInputBuffers()
        if (mtpFolded) {
            // The graph adds a noise row to each codebook's logits before its argmax;
            // zeros make the choice greedy.
            ins[2].writeFloat(FloatArray(15 * MTP_VOCAB))
        }
        return MtpBuffers(
            inputs = ins,
            outPing = mtp.createOutputBuffers(),
            outPong = if (mtpFolded) emptyList() else mtp.createOutputBuffers(),
        )
    }

    /**
     * MTP 的输入/输出张量。
     *
     * 必须**延迟创建**：构造 `createInputBuffers()` 会顺带把 MTP 图加载起来，
     * 若写在属性初始化里就等于没有延迟加载（实测过这个坑）。
     */
    private class MtpBuffers(
        val inputs: List<TensorBuffer>,
        val outPing: List<TensorBuffer>,
        val outPong: List<TensorBuffer>,
    )

    private fun mtpFrame(
        hidden: FloatArray, cb0: Int, greedy: Boolean, rnd: Random,
    ): IntArray {
        if (mtpFolded) return mtpFrameFolded(hidden, cb0)
        return mtpFrameStep(hidden, cb0, greedy, rnd)
    }

    private fun mtpFrameFolded(hidden: FloatArray, cb0: Int): IntArray {
        val b = mtpBuffers
        b.inputs[0].writeFloat(hidden)
        b.inputs[1].writeFloat(codecRow(cb0))
        mtp.run(b.inputs, b.outPing)
        return b.outPing[0].readInt() // codes [15]
    }

    private fun mtpFrameStep(
        hidden: FloatArray, cb0: Int, greedy: Boolean, rnd: Random,
    ): IntArray {
        val b = mtpBuffers
        val mtpIn = b.inputs
        val mtpOutPing = b.outPing
        val mtpOutPong = b.outPong
        val zero = FloatArray(MTP_KV_FLOATS)
        mtpIn[3].writeFloat(zero)
        mtpIn[4].writeFloat(zero)
        // KV ping-pong: read from kIn, write into ping/pong alternately;
        // the freshly written pair becomes the next step's input. The
        // input-created pair (mtpIn[3/4]) only carries the initial zeros.
        var kIn = Pair(mtpIn[3], mtpIn[4])
        val ping = Pair(mtpOutPing[1], mtpOutPing[2])
        val pong = Pair(mtpOutPong[1], mtpOutPong[2])
        var usePing = true
        val logitsOut = mtpOutPing[0]
        val codes = IntArray(15)
        val mask = FloatArray(MTP_CACHE) { NEG }
        for (t in 0 until 16) {
            val embed = when {
                t == 0 -> hidden
                t == 1 -> codecRow(cb0)
                else -> mtpRow(t - 2, codes[t - 2])
            }
            mtpIn[0].writeFloat(embed)
            mtpIn[1].writeInt(intArrayOf(t))
            mask[t] = 0f
            mtpIn[2].writeFloat(mask)
            val write = if (usePing) ping else pong
            mtp.run(
                listOf(mtpIn[0], mtpIn[1], mtpIn[2], kIn.first, kIn.second),
                listOf(logitsOut, write.first, write.second))
            if (t >= 1) {
                val all = logitsOut.readFloat() // [15 * 2048]
                val head = t - 1
                val logits = FloatArray(MTP_VOCAB)
                System.arraycopy(all, head * MTP_VOCAB, logits, 0, MTP_VOCAB)
                codes[head] = pick(logits, greedy, rnd)
            }
            kIn = write
            usePing = !usePing
        }
        return codes
    }

    // ------------------------------------------------------------------
    // Codec decode: fixed 64-frame chunks, 25 frames of left context.
    //
    // 与官方参考实现的唯一差异：把「一次性解码全部帧」拆成「解码 [from,to) 区间的新帧」，
    // 由调用方决定何时解码（流式播放用）。因为编解码器是因果结构、左侧上下文只需
    // CODEC_CTX 帧，所以任意切分得到的 PCM 与一次性解码逐样本一致。
    // ------------------------------------------------------------------

    /** 编解码器输入/输出缓冲（流式解码时跨块复用，避免每块重新分配） */
    private class CodecBuffers(
        val codesIn: List<TensorBuffer>,
        val partAOut: List<TensorBuffer>,
        val wavOut: List<TensorBuffer>,
    ) {
        fun close() {
            for (b in codesIn + partAOut + wavOut) b.close()
        }
    }

    private fun openCodecBuffers(): CodecBuffers {
        // Reference graph: codes [1,16,64] -> wav. Split codec: Part A codes -> hidden
        // [1,1024,64], Part B hidden -> wav; Part A's output buffers are Part B's inputs
        // (a TensorBuffer is not tied to the model that created it).
        val codesIn = (codecA ?: codec).createInputBuffers()
        val partAOut = codecA?.createOutputBuffers() ?: emptyList()
        val wavOut = codec.createOutputBuffers()
        return CodecBuffers(codesIn, partAOut, wavOut)
    }

    /** 解码 frames[from, to) 中「新出现」的帧，返回其对应 PCM（不含左侧上下文帧） */
    private fun decodeRange(
        frames: List<IntArray>, from: Int, to: Int, buffers: CodecBuffers,
    ): FloatArray {
        if (to <= from) return FloatArray(0)
        val pieces = ArrayList<FloatArray>()
        var i = from
        while (i < to) {
            val ctx = min(CODEC_CTX, i)
            // Window = left context + new frames must fit the fixed-T graph, so
            // advance by at most CODEC_CHUNK - ctx new frames per chunk.
            val j = min(i + CODEC_CHUNK - ctx, to)
            val n = j - (i - ctx) // = new frames + ctx, always <= CODEC_CHUNK
            val buf = IntArray(16 * CODEC_CHUNK)
            for (t in 0 until n) {
                val frame = frames[i - ctx + t]
                for (q in 0 until 16) {
                    buf[q * CODEC_CHUNK + t] = frame[q]
                }
            }
            buffers.codesIn[0].writeInt(buf)
            val partA = codecA
            if (partA != null) {
                partA.run(buffers.codesIn, buffers.partAOut)
                codec.run(buffers.partAOut, buffers.wavOut)
            } else {
                codec.run(buffers.codesIn, buffers.wavOut)
            }
            val wav = buffers.wavOut[0].readFloat()
            pieces.add(wav.copyOfRange(ctx * UPSAMPLE, n * UPSAMPLE))
            i = j
        }
        var total = 0
        for (p in pieces) {
            total += p.size
        }
        val out = FloatArray(total)
        var off = 0
        for (p in pieces) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun decodeCodes(frames: List<IntArray>): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val buffers = openCodecBuffers()
        try {
            return decodeRange(frames, 0, frames.size, buffers)
        } finally {
            buffers.close()
        }
    }

    // ------------------------------------------------------------------
    // Host math helpers.
    // ------------------------------------------------------------------
    private fun codecRow(id: Int): FloatArray {
        val out = FloatArray(HIDDEN)
        System.arraycopy(codecEmb, id * HIDDEN, out, 0, HIDDEN)
        return out
    }

    private fun mtpRow(table: Int, id: Int): FloatArray {
        val out = FloatArray(HIDDEN)
        val base = (table * MTP_VOCAB + id) * HIDDEN
        for (i in 0 until HIDDEN) {
            out[i] = Npy.halfToFloat(mtpEmb.get(base + i))
        }
        return out
    }

    private fun addMtpRow(acc: FloatArray, table: Int, id: Int) {
        val base = (table * MTP_VOCAB + id) * HIDDEN
        for (i in 0 until HIDDEN) {
            acc[i] += Npy.halfToFloat(mtpEmb.get(base + i))
        }
    }

    private fun add(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(HIDDEN) { a[it] + b[it] }

    /** text_embedding lookup + the 2048->1024 SiLU projection MLP. */
    private fun embedText(ids: IntArray): Array<FloatArray> {
        val w1 = proj.getValue("w1")
        val b1 = proj.getValue("b1")
        val w2 = proj.getValue("w2")
        val b2 = proj.getValue("b2")
        return Array(ids.size) { n ->
            val x = FloatArray(2048)
            val base = ids[n] * 2048
            for (i in 0 until 2048) {
                x[i] = Npy.halfToFloat(textEmb.get(base + i))
            }
            val h = FloatArray(2048)
            for (r in 0 until 2048) {
                var acc = b1[r]
                val wBase = r * 2048
                for (c in 0 until 2048) {
                    acc += w1[wBase + c] * x[c]
                }
                h[r] = acc / (1f + exp(-acc)) // SiLU
            }
            val y = FloatArray(HIDDEN)
            for (r in 0 until HIDDEN) {
                var acc = b2[r]
                val wBase = r * 2048
                for (c in 0 until 2048) {
                    acc += w2[wBase + c] * h[c]
                }
                y[r] = acc
            }
            y
        }
    }

    /** Greedy argmax or top-50/temperature-0.9 sampling. */
    private fun pick(logits: FloatArray, greedy: Boolean, rnd: Random): Int {
        if (greedy) {
            var best = 0
            for (i in logits.indices) {
                if (logits[i] > logits[best]) {
                    best = i
                }
            }
            return best
        }
        val k = 50
        val idx = logits.indices.sortedByDescending { logits[it] }.take(k)
        val probs = DoubleArray(k)
        val maxLogit = logits[idx[0]] / 0.9
        var sum = 0.0
        for (i in 0 until k) {
            probs[i] = exp(logits[idx[i]] / 0.9 - maxLogit)
            sum += probs[i]
        }
        var r = rnd.nextDouble() * sum
        for (i in 0 until k) {
            r -= probs[i]
            if (r <= 0) return idx[i]
        }
        return idx[k - 1]
    }

    fun close() {
        closeMtpBuffers()
        talkerModel?.close()
        mtpModel?.close()
        codecAModel?.close()
        codecModel?.close()
        talkerModel = null
        mtpModel = null
        codecAModel = null
        codecModel = null
    }

    /** 关闭 MTP 张量缓冲（1.1 MB 级 · 15×2048 float），只在已创建时处理 */
    private fun closeMtpBuffers() {
        if (!mtpBuffersInitialized()) return
        for (b in mtpBuffers.inputs + mtpBuffers.outPing + mtpBuffers.outPong) {
            runCatching { b.close() }
        }
    }

    /** `by lazy` 是否已经求值（用于 close 时不触发无谓的图加载） */
    private fun mtpBuffersInitialized(): Boolean = mtpBuffersDelegate.isInitialized()
}
