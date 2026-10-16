package com.hrstts.jiandu.data.tts

import android.app.ActivityManager
import android.content.Context
import com.hrstts.jiandu.R
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** 模型包中的一个文件 */
data class ModelFile(
    /** 仓库内路径 */
    val remote: String,
    /** 设备上的扁平文件名（参考实现按此名加载） */
    val local: String,
    /** 完整性判定的最小字节数（防止半截文件被当成有效模型） */
    val minBytes: Long,
)

/** Qwen3-TTS 端侧模型清单（与官方 install_to_device.sh 的快速档一致） */
object Qwen3ModelSpec {

    /** 默认镜像：HuggingFace 在国内不可达，使用 hf-mirror 镜像（可换成任意同构镜像） */
    const val BASE_URL: String = "https://hf-mirror.com/litert-community/Qwen3-TTS-12Hz-0.6B-Base/resolve/main"

    /** 下载镜像列表：按顺序尝试，前一个失败自动换下一个 */
    val BASE_URLS: List<String> = listOf(
        BASE_URL,
        "https://huggingface.co/litert-community/Qwen3-TTS-12Hz-0.6B-Base/resolve/main",
    )

    private const val MB = 1024L * 1024L

    /** 所有配置共用的文件 */
    val common: List<ModelFile> = listOf(
        ModelFile("talker_int4.tflite", "talker_int4.tflite", 200 * MB),
        ModelFile("tables/codec_embedding_fp32.npy", "codec_embedding_fp32.npy", 1 * MB),
        ModelFile("tables/mtp_embeddings_fp16.npy", "mtp_embeddings_fp16.npy", 1 * MB),
        ModelFile("tables/text_embedding_fp16.npy", "text_embedding_fp16.npy", 500 * MB),
        ModelFile("tables/text_projection_fp32.npz", "text_projection_fp32.npz", 1 * 1024L),
        ModelFile("voices/demo_speaker.npy", "demo_speaker.npy", 1 * 1024L),
        ModelFile("vocab.json", "vocab.json", 1 * MB),
        ModelFile("merges.txt", "merges.txt", 1 * MB),
    )

    /** 快速图组（推荐）：折叠 int8 MTP + 拆分编解码器 */
    val fastGraphs: List<ModelFile> = listOf(
        ModelFile("mtp_folded_int8.tflite", "mtp_folded_int8.tflite", 150 * MB),
        ModelFile("codec_partA.tflite", "codec_partA.tflite", 100 * MB),
        ModelFile("codec_partB.tflite", "codec_partB.tflite", 200 * MB),
    )

    /** 精度图组（回退）：fp32 MTP + 整块解码器 */
    val referenceGraphs: List<ModelFile> = listOf(
        ModelFile("mtp_fp32.tflite", "mtp_fp32.tflite", 300 * MB),
        ModelFile("codec_decoder_fp32.tflite", "codec_decoder_fp32.tflite", 300 * MB),
    )

    /** 默认安装：共用文件 + 快速图组，约 1.5 GB */
    val required: List<ModelFile> = common + fastGraphs

    /** 建议剩余存储空间（安装所需 + 余量；模型实际 1.59 GB） */
    const val REQUIRED_FREE_BYTES: Long = 1_750L * 1024L * 1024L

    /** 建议最低内存（6 GB 以下设备默认推荐系统 TTS） */
    const val RECOMMENDED_RAM_BYTES: Long = 6L * 1024L * 1024L * 1024L
}

/** 模型包状态 */
sealed interface Qwen3ModelState {
    data object NotInstalled : Qwen3ModelState

    data class Downloading(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : Qwen3ModelState {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }

    data object Ready : Qwen3ModelState
    data class Failed(val message: String) : Qwen3ModelState
}

/**
 * Qwen3-TTS 端侧模型包的下载与状态管理。
 *
 * - 模型约 1.5 GB，下载到应用私有目录 `filesDir/qwen3-tts/`；
 * - 支持断点续传（HTTP Range + `.part` 临时文件）；
 * - 下载完成后按「文件存在 + 最小体积」判定完整性，避免半截模型被误用。
 * - 所有用户可见文案走 [strings]（i18n），不在此处硬编码语言。
 */
class Qwen3ModelManager(
    private val context: Context,
    private val strings: UiStrings = AndroidStrings(context),
) {

    private val _state = MutableStateFlow<Qwen3ModelState>(Qwen3ModelState.NotInstalled)
    val state: StateFlow<Qwen3ModelState> = _state.asStateFlow()

    val modelDir: File get() = File(context.filesDir, DIR_NAME)

    /** 设备内存是否达到建议值 */
    fun hasEnoughMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem >= Qwen3ModelSpec.RECOMMENDED_RAM_BYTES
    }

    /**
     * 可用存储空间。
     *
     * 注意：`File.usableSpace` 对**不存在的路径返回 0**，而模型目录在首次下载前并不存在，
     * 若直接取 `modelDir.usableSpace` 会把「首次下载」误判为存储不足。这里回退到必定存在的
     * `filesDir`（同一分区）。
     */
    fun freeSpaceBytes(): Long {
        val dir = if (modelDir.exists()) modelDir else context.filesDir
        return dir.usableSpace
    }

    /** 是否已完整安装（所有必需文件存在且不小于最小体积） */
    fun isInstalled(): Boolean = Qwen3ModelSpec.required.all { file ->
        val target = File(modelDir, file.local)
        target.isFile && target.length() >= file.minBytes
    }

    /** 按当前磁盘状态刷新（应用启动时调用）；顺带清掉孤儿 `.part` 文件 */
    fun refresh() {
        if (_state.value is Qwen3ModelState.Downloading) return
        cleanupStaleParts()
        _state.value = if (isInstalled()) Qwen3ModelState.Ready else Qwen3ModelState.NotInstalled
    }

    /**
     * 清理孤儿 `.part` 文件（对应文件已经完整时）。
     *
     * 场景：下载被中断/进程被杀后，`.part` 会留着；下一次下载若该文件已完整（比如用户
     * 手工补过或用脚本推过），下载循环就会跳过它，`.part` 便永远留在磁盘上。
     * 实测一次中断会留下 40 MB 的孤儿文件，因此启动与每次下载结束后都清一次。
     *
     * @return 删除的文件数（便于日志/测试断言）
     */
    fun cleanupStaleParts(): Int {
        if (!modelDir.isDirectory) return 0
        var removed = 0
        Qwen3ModelSpec.required.forEach { file ->
            val complete = File(modelDir, file.local)
            if (complete.isFile && complete.length() >= file.minBytes) {
                val part = File(modelDir, "${file.local}.part")
                if (part.isFile && part.delete()) removed++
            }
        }
        return removed
    }

    /** 已安装的图组（快速 / 精度），用于界面提示（已本地化） */
    fun installedGraphs(): String {
        val hasFast = Qwen3ModelSpec.fastGraphs.all { File(modelDir, it.local).isFile }
        val hasRef = Qwen3ModelSpec.referenceGraphs.all { File(modelDir, it.local).isFile }
        return when {
            hasFast && hasRef -> strings.get(R.string.engine_graph_fast_and_ref)
            hasFast -> strings.get(R.string.engine_graph_fast)
            hasRef -> strings.get(R.string.engine_graph_ref)
            else -> strings.get(R.string.engine_graph_none)
        }
    }

    /**
     * 下载缺失文件（可在下载中再次调用，重复调用会被忽略）。
     *
     * @param onFinished 主线程回调：true 表示全部就绪
     */
    suspend fun download(onFinished: (Boolean) -> Unit = {}) {
        if (_state.value is Qwen3ModelState.Downloading) return
        if (isInstalled()) {
            _state.value = Qwen3ModelState.Ready
            onFinished(true)
            return
        }

        val files = Qwen3ModelSpec.required
        _state.value = Qwen3ModelState.Downloading(
            fileName = files.first().local,
            fileIndex = 1,
            fileCount = files.size,
            downloadedBytes = 0L,
            totalBytes = files.sumOf { it.minBytes },
        )

        try {
            modelDir.mkdirs()
            var done = 0L
            files.forEachIndexed { index, file ->
                val local = File(modelDir, file.local)
                if (!(local.isFile && local.length() >= file.minBytes)) {
                    downloadOne(file) { fileBytes ->
                        _state.update { current ->
                            if (current is Qwen3ModelState.Downloading) {
                                current.copy(
                                    fileName = file.local,
                                    fileIndex = index + 1,
                                    downloadedBytes = done + fileBytes,
                                )
                            } else {
                                current
                            }
                        }
                    }
                }
                // 清理同名残留的 .part：文件已完整时不会被 downloadOne 覆盖，
                // 不清就会一直占着磁盘（实测一次中断会留下 40 MB 的孤儿 .part）
                File(modelDir, "${file.local}.part").takeIf { it.isFile }?.delete()
                done += max(local.length(), file.minBytes)
            }
            _state.value = if (isInstalled()) {
                Qwen3ModelState.Ready
            } else {
                Qwen3ModelState.Failed(strings.get(R.string.engine_err_verify))
            }
            cleanupStaleParts()
            onFinished(isInstalled())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.value = Qwen3ModelState.Failed(friendlyError(t))
            onFinished(false)
        }
    }

    /** 把底层异常翻译成用户能看懂、能行动的原因（已本地化） */
    private fun friendlyError(t: Throwable): String = when {
        t is java.net.UnknownHostException ->
            strings.get(R.string.engine_err_dns)

        t is java.net.SocketTimeoutException ->
            strings.get(R.string.engine_err_timeout)

        t is SecurityException || t.message?.contains("Permission denied", ignoreCase = true) == true ->
            strings.get(R.string.engine_err_permission)

        t is javax.net.ssl.SSLException ->
            strings.get(R.string.engine_err_ssl)

        t.message?.contains("HTTP 4") == true ->
            strings.get(R.string.engine_err_http, t.message.orEmpty())

        else -> t.message ?: strings.get(R.string.engine_err_generic)
    }

    /** 删除已下载模型，释放空间 */
    fun deleteAll() {
        modelDir.deleteRecursively()
        _state.value = Qwen3ModelState.NotInstalled
    }

    /**
     * 依次探测各镜像是否可达，返回可读诊断文本（已本地化）。
     *
     * 用途：下载失败时让用户/开发者直接看到「是 DNS、超时、HTTP 错误还是权限问题」，
     * 每个镜像单独一行，便于判断是否需要换镜像或换网络。
     */
    suspend fun probeMirrors(): String = withContext(Dispatchers.IO) {
        val probeFile = Qwen3ModelSpec.required.first()
        Qwen3ModelSpec.BASE_URLS.joinToString("\n") { base ->
            val host = runCatching { java.net.URI(base).host }.getOrDefault(base)
            val result = try {
                val connection = (URL("$base/${probeFile.remote}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "HEAD"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JianDu/1.0 (Android)")
                }
                val code = connection.responseCode
                val length = connection.contentLengthLong
                connection.disconnect()
                if (code in 200..299) {
                    if (length > 0) {
                        strings.get(R.string.engine_probe_reachable, code, length / 1024 / 1024)
                    } else {
                        strings.get(R.string.engine_probe_reachable_nolen, code)
                    }
                } else {
                    strings.get(R.string.engine_probe_http, code)
                }
            } catch (t: Throwable) {
                friendlyError(t)
            }
            "$host → $result"
        }
    }

    // ---- 内部 ----

    /** 依次尝试各镜像；某个镜像失败则换下一个，全部失败才向上报错 */
    private suspend fun downloadOne(file: ModelFile, onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (base in Qwen3ModelSpec.BASE_URLS) {
            try {
                downloadFrom(base, file, onBytes)
                return@withContext
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.w("Qwen3ModelManager", "镜像下载失败：$base/${file.remote}", t)
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException(
            strings.get(R.string.engine_err_download_file, file.local),
        )
    }

    private fun downloadFrom(base: String, file: ModelFile, onBytes: (Long) -> Unit) {
        val target = File(modelDir, file.local)
        val part = File(modelDir, "${file.local}.part")
        var existing = if (part.isFile) part.length() else 0L

        val connection = (URL("$base/${file.remote}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JianDu/1.0 (Android)")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    strings.get(R.string.engine_err_download_http, file.local, code),
                )
            }
            // 服务器不支持断点续传时从头下载
            if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                existing = 0L
                part.delete()
            }

            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.seek(existing)
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (part.length() < file.minBytes) {
            throw IllegalStateException(
                strings.get(R.string.engine_err_incomplete, file.local, part.length()),
            )
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        onBytes(target.length())
    }

    private companion object {
        const val DIR_NAME = "qwen3-tts"
    }
}
