package com.hrstts.jiandu.domain.tts

import java.security.MessageDigest

/**
 * 远程 TTS 的协议契约（纯 Kotlin，无 Android 依赖，可在 JVM 单测里验证）。
 *
 * **必须与服务端 `server/jiandu_gateway/protocol.py` 逐字节一致**。
 * 一旦两端算出的缓存 key 不同，手机本地缓存永远不命中、整本同步也白做，
 * 而且这种 bug 不会报错，只会表现为「怎么听都在等合成」——所以这里用固定向量做单测。
 *
 * 三个约束：
 *  1. 音频是 24 kHz / 单声道 / **int16 小端裸 PCM**，与端侧 Qwen3-TTS 的采样率一致，
 *     因此 [com.hrstts.jiandu.data.tts.TtsAudioCache] 与 AudioTrack 代码一行都不用改。
 *  2. 缓存 key = `sha1(text | voiceId | modelTag)`，分隔符是单字节 `0x7C`。
 *  3. 片段文件 = 16 字节头（`jdpc` + 版本 + 大端 frames）+ int16 LE PCM，
 *     与 `TtsAudioCache` 的磁盘格式完全相同，所以服务端下发的片段可以**直接落盘复用**。
 */
object RemoteTtsProtocol {

    /** 采样率：固定 24 kHz（端侧 Qwen3-TTS 也是 24 kHz） */
    const val SAMPLE_RATE: Int = 24_000

    /** 每个采样点的字节数（int16） */
    const val BYTES_PER_SAMPLE: Int = 2

    /** Qwen3-TTS 的 codec 帧率：12.5 帧/秒 */
    const val FRAMES_PER_SECOND: Double = 12.5

    /** 每帧采样点数（1920） */
    const val SAMPLES_PER_FRAME: Int = (SAMPLE_RATE / FRAMES_PER_SECOND).toInt()

    /**
     * 默认模型标记。
     *
     * 运行时以服务端 `/v1/health` 返回的 `modelTag` 为准（见 [RemoteTtsClient.health]），
     * 这样服务端换引擎/改音频前端时只要把 tag 加一，手机侧缓存就会自动失效。
     * 这里的常量只在「还没连上服务端」和单元测试时使用。
     */
    const val DEFAULT_MODEL_TAG: String = "jiandu-remote-v1"

    private const val HEADER_BYTES = 16
    private val MAGIC = byteArrayOf('j'.code.toByte(), 'd'.code.toByte(), 'p'.code.toByte(), 'c'.code.toByte())
    private const val FORMAT_VERSION = 1

    /**
     * 缓存 key：`sha1(text | voiceId | modelTag)` 的十六进制小写。
     *
     * 与 `TtsAudioCache.keyOf` 的算法相同（同为 sha1 + `|` 分隔），
     * 但这里刻意独立实现一份：远程音频与端侧音频**必须**落在不同的 key 上，
     * 否则换了引擎会播到上一个引擎的缓存。
     */
    fun keyOf(text: String, voiceId: String, modelTag: String = DEFAULT_MODEL_TAG): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(voiceId.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(modelTag.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val SEPARATOR = byteArrayOf(0x7C) // '|'

    /** 采样点数 → 帧数（与 Python 端 `frames_of` 相同，四舍五入且至少 1） */
    fun framesOf(samples: Int): Int = maxOf(1, Math.round(samples.toDouble() / SAMPLES_PER_FRAME).toInt())

    /** 一段 PCM 的音频时长（秒），用于日志与高亮推进 */
    fun secondsOf(samples: Int): Double = samples.toDouble() / SAMPLE_RATE

    /**
     * 解析服务端下发的片段头，返回帧数；头不合法返回 null。
     *
     * 手机下载片段后要先校验再落盘，避免把一段 HTML 错误页写进缓存。
     */
    fun parseFrames(blob: ByteArray): Int? {
        if (blob.size < HEADER_BYTES) return null
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return null
        }
        if (blob[4].toInt() and 0xFF != FORMAT_VERSION) return null
        return ((blob[8].toInt() and 0xFF) shl 24) or
            ((blob[9].toInt() and 0xFF) shl 16) or
            ((blob[10].toInt() and 0xFF) shl 8) or
            (blob[11].toInt() and 0xFF)
    }

    /** 从片段里取出纯 PCM（已校验头）。 */
    fun pcmOf(blob: ByteArray): ByteArray? {
        if (parseFrames(blob) == null) return null
        return blob.copyOfRange(HEADER_BYTES, blob.size)
    }

    /** 打包成片段字节（与 `TtsAudioCache.writeDisk` 完全一致的布局）。 */
    fun packClip(pcm: ByteArray, frames: Int = framesOf(pcm.size / BYTES_PER_SAMPLE)): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(header)
        header[4] = FORMAT_VERSION.toByte()
        header[8] = (frames ushr 24).toByte()
        header[9] = ((frames ushr 16) and 0xFF).toByte()
        header[10] = ((frames ushr 8) and 0xFF).toByte()
        header[11] = (frames and 0xFF).toByte()
        return header + pcm
    }

    /**
     * 把任意地址标准化成可用的 baseUrl。
     *
     * 用户会在手机上输入 `10.62.61.202`、`10.62.61.202:8080`、`http://10.62.61.202:8080/`
     * 这几种形式，这里统一补全成 `http://host:port`（默认端口 8080）。
     */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        value = value.trimEnd('/')
        // 只给了协议+主机（没有显式端口）时补默认端口
        val afterScheme = value.substringAfter("://")
        val hostPart = afterScheme.substringBefore('/')
        if (!hostPart.contains(':')) {
            value = "$value:$DEFAULT_PORT"
        }
        return value
    }

    /** 默认端口：与服务端 `run.py` 的默认值一致 */
    const val DEFAULT_PORT: Int = 8080
}
