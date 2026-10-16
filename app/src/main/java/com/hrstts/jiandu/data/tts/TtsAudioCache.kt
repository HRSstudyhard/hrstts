package com.hrstts.jiandu.data.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 端侧朗读的 PCM 音频缓存（两档：RAM 环形 + 磁盘 LRU）。
 *
 * 设计依据（实测见 docs/TTS-LATENCY-OPTIONS.md、docs/TTS-AUDIO-CACHE.md）：
 *  - 端侧合成很慢（模拟器 RTF 6.7~8.7，官方 Pixel 8a 2.06），**1 GB 音频要合成 12 小时以上**，
 *    所以缓存的意义不是「预先把整本书合完」，而是「把最近/最近的下一句准备好」，让重读、回看、
 *    暂停后继续这些高频动作做到**秒开**；
 *  - 模型常驻已占约 3 GB、朗读峰值 3.8~4.3 GB，因此 RAM 档刻意做得**很小**（默认 32 MB ≈ 11.7 分钟
 *    音频），大队列放在**磁盘**（默认 512 MB ≈ 3 小时音频，占存储不占内存）；
 *  - RAM 档在**退后台**时清空（`Lifecycle.ON_STOP` → [clearMemory]），符合「退后台自动清除」；
 *    磁盘档保留，并额外保留最近 [KEEP_RECENT_RAM_ENTRIES] 条到内存里，保证回前台能立刻出声。
 *
 * 格式：内存与磁盘都存 **int16 单声道 PCM**（24 kHz 下 46.9 KB/s，是 float32 的一半）。
 */
class TtsAudioCache(
    context: Context,
    private val memoryLimitBytes: Int = DEFAULT_MEMORY_LIMIT_BYTES,
    private val diskLimitBytes: Long = DEFAULT_DISK_LIMIT_BYTES,
) {

    /** 一段缓存音频：PCM（int16）+ 帧数（用于高亮推进与日志） */
    class Clip(val pcm: ShortArray, val frames: Int) {
        val samples: Int get() = pcm.size
        val audioSeconds: Double get() = frames * 0.08
    }

    private val diskDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 内存档：最近使用的少量片段，LRU 淘汰 */
    private val memory = object : LinkedHashMap<String, Clip>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Clip>): Boolean =
            memoryBytes > memoryLimitBytes
    }

    private var memoryBytes = 0

    /** 最近用过的片段（退后台时保留，保证回前台立刻出声） */
    private val recentlyUsed = ArrayDeque<String>()

    @Volatile
    var hits: Int = 0
        private set

    @Volatile
    var misses: Int = 0
        private set

    init {
        trimDisk()
    }

    // ---- 查询 ----

    /** 内存命中的片段（不读磁盘） */
    fun getFromMemory(key: String): Clip? {
        val clip = memory[key]
        if (clip != null) hits++ else misses++
        return clip
    }

    /** 取片段：先内存，再磁盘（磁盘命中会回填内存） */
    fun get(key: String): Clip? {
        memory[key]?.let {
            hits++
            return it
        }
        val fromDisk = readDisk(key)
        if (fromDisk == null) {
            misses++
            return null
        }
        hits++
        putMemory(key, fromDisk)
        return fromDisk
    }

    fun contains(key: String): Boolean = memory.containsKey(key) || diskFile(key).isFile

    // ---- 写入 ----

    /** 合成结束：把整段写入两档缓存 */
    fun put(key: String, pcm: FloatArray, frames: Int) {
        val clip = Clip(toShorts(pcm), frames)
        putMemory(key, clip)
        writeDisk(key, clip)
        trimDisk()
    }

    /** 直接放入内存档（例如磁盘读回） */
    private fun putMemory(key: String, clip: Clip) {
        val previous = memory.put(key, clip)
        if (previous != null) memoryBytes -= previous.pcm.size * 2
        memoryBytes += clip.pcm.size * 2
        recentlyUsed.remove(key)
        recentlyUsed.addLast(key)
        while (recentlyUsed.size > KEEP_RECENT_RAM_ENTRIES) recentlyUsed.removeFirst()
        // 大小上限由 LinkedHashMap.removeEldestEntry 处理（memoryBytes > memoryLimitBytes）
    }

    // ---- 生命周期 ----

    /**
     * 退后台：清空内存档，但保留最近 [KEEP_RECENT_RAM_ENTRIES] 条
     * （否则回前台要重新合成首句，真机 RTF 2 意味着再等约 40 s）。
     *
     * @return 释放的字节数
     */
    fun clearMemory(): Long {
        var freed = 0L
        val keep = recentlyUsed.takeLast(KEEP_RECENT_RAM_ENTRIES).toSet()
        val iterator = memory.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keep) {
                freed += entry.value.pcm.size * 2L
                iterator.remove()
            }
        }
        memoryBytes -= freed.toInt()
        if (memoryBytes < 0) memoryBytes = 0
        return freed
    }

    /** 磁盘占用（字节） */
    fun diskBytes(): Long = diskDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 缓存条目数（磁盘） */
    fun diskEntries(): Int = diskDir.listFiles()?.size ?: 0

    /** 清空磁盘档（用户在设置里手动清、或存储紧张时） */
    fun clearDisk(): Long {
        val before = diskBytes()
        diskDir.listFiles()?.forEach { it.delete() }
        return before
    }

    fun statsLine(): String = "hit=$hits miss=$misses ram=${memoryBytes / 1024}KB disk=${diskBytes() / 1024}KB"

    // ---- 内部 ----

    private fun diskFile(key: String): File = File(diskDir, key)

    private fun readDisk(key: String): Clip? = try {
        val file = diskFile(key)
        if (!file.isFile) {
            null
        } else {
            file.inputStream().buffered(64 * 1024).use { input ->
                val header = ByteArray(HEADER_BYTES)
                if (input.read(header) != HEADER_BYTES) return null
                val frames = ((header[8].toInt() and 0xFF) shl 24) or
                    ((header[9].toInt() and 0xFF) shl 16) or
                    ((header[10].toInt() and 0xFF) shl 8) or
                    (header[11].toInt() and 0xFF)
                val raw = input.readBytes()
                val shorts = ShortArray(raw.size / 2)
                var i = 0
                while (i < shorts.size) {
                    shorts[i] = (((raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)).toShort())
                    i++
                }
                // 触达时间：用于磁盘 LRU
                file.setLastModified(System.currentTimeMillis())
                Clip(shorts, frames)
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeDisk(key: String, clip: Clip) {
        try {
            val target = diskFile(key)
            val tmp = File(diskDir, "$key.tmp")
            tmp.outputStream().buffered(64 * 1024).use { output ->
                val header = ByteArray(HEADER_BYTES)
                header[0] = 'j'.code.toByte()
                header[1] = 'd'.code.toByte()
                header[2] = 'p'.code.toByte()
                header[3] = 'c'.code.toByte()
                header[4] = FORMAT_VERSION.toByte()
                header[8] = (clip.frames ushr 24).toByte()
                header[9] = ((clip.frames ushr 16) and 0xFF).toByte()
                header[10] = ((clip.frames ushr 8) and 0xFF).toByte()
                header[11] = (clip.frames and 0xFF).toByte()
                output.write(header)
                val buffer = ByteArray(64 * 1024)
                var index = 0
                var offset = 0
                while (index < clip.pcm.size) {
                    val value = clip.pcm[index].toInt()
                    buffer[offset++] = (value and 0xFF).toByte()
                    buffer[offset++] = ((value shr 8) and 0xFF).toByte()
                    index++
                    if (offset == buffer.size) {
                        output.write(buffer, 0, offset)
                        offset = 0
                    }
                }
                if (offset > 0) output.write(buffer, 0, offset)
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (_: Throwable) {
            // 缓存写失败不影响朗读
        }
    }

    /** 超出磁盘上限时按「最久未访问」删除 */
    private fun trimDisk() {
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        val stale = files.filter { it.name.endsWith(".tmp") }
        stale.forEach { it.delete() }
        var total = files.filter { !it.name.endsWith(".tmp") }.sumOf { it.length() }
        if (total <= diskLimitBytes) return
        val sorted = files.filter { !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= diskLimitBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    /** 合成过程中的累积器（float32），合成结束后一次转 int16 存入两档 */
    class ClipBuilder {
        private var buffer = FloatArray(INITIAL_SAMPLES)
        private var size = 0

        fun append(pcm: FloatArray) {
            if (size + pcm.size > buffer.size) {
                buffer = buffer.copyOf(maxOf(buffer.size * 2, size + pcm.size))
            }
            System.arraycopy(pcm, 0, buffer, size, pcm.size)
            size += pcm.size
        }

        val samples: Int get() = size

        fun toFloatArray(): FloatArray = buffer.copyOf(size)
    }

    private fun toShorts(pcm: FloatArray): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }

    companion object {
        private const val DIR_NAME = "tts-pcm"
        private const val HEADER_BYTES = 16
        private const val FORMAT_VERSION = 1
        private const val INITIAL_SAMPLES = 24000 * 4 // 4 秒

        /** RAM 档上限：32 MB ≈ 11.7 分钟音频（int16 @24 kHz） */
        const val DEFAULT_MEMORY_LIMIT_BYTES: Int = 32 * 1024 * 1024

        /** 磁盘档上限：512 MB ≈ 3 小时音频 */
        const val DEFAULT_DISK_LIMIT_BYTES: Long = 512L * 1024 * 1024

        /** 退后台时保留在内存里的「最近片段」条数（保证回前台立刻出声） */
        const val KEEP_RECENT_RAM_ENTRIES: Int = 3

        /** 缓存 key：文本 + 音色 + 图组合（换音色/换图即失效） */
        fun keyOf(text: String, speakerTag: String, modelTag: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(speakerTag.toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(modelTag.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
