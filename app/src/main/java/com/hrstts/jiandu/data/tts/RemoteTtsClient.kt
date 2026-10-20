package com.hrstts.jiandu.data.tts

import com.hrstts.jiandu.domain.tts.RemoteTtsProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 电脑端 TTS 服务的 HTTP 客户端（只用 `HttpURLConnection`，不引入 OkHttp/Retrofit）。
 *
 * 两个刻意的设计：
 *  1. **[synthesize] 是流式的**：一边读 socket 一边把 PCM 交给回调，所以引擎可以「收到第一块就开播」，
 *     而不是等整句传完。当前 SAPI 后端整句只要几十毫秒，但换成会流式输出的模型后这条路径不用改。
 *  2. **[cancel] 真的能打断读阻塞**：`stop()` 时调用它 `disconnect()` 掉当前连接，
 *     否则切到下一句要等上一句的 socket 读完。
 */
class RemoteTtsClient(baseUrl: String) {

    @Volatile
    var baseUrl: String = baseUrl.trimEnd('/')
        set(value) {
            field = value.trimEnd('/')
        }

    /** 正在进行的连接（用于 [cancel]） */
    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var cancelled: Boolean = false

    // ---- 数据模型 ----

    data class Voice(val id: String, val label: String, val locale: String)

    data class Health(
        val engine: String,
        val modelTag: String,
        val sampleRate: Int,
        val defaultVoice: String,
        val voices: List<Voice>,
    )

    data class SyncProgress(val total: Int, val done: Int, val failed: Int, val running: Boolean)

    // ---- 接口 ----

    /** 探测服务端；连不上或返回非 2xx 时抛 [IOException]。 */
    fun health(timeoutMs: Int = CONNECT_TIMEOUT_MS): Health {
        val text = getText("/v1/health", timeoutMs)
        val json = JSONObject(text)
        val voices = mutableListOf<Voice>()
        json.optJSONArray("voices")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                voices += Voice(
                    id = item.optString("id"),
                    label = item.optString("label"),
                    locale = item.optString("locale"),
                )
            }
        }
        return Health(
            engine = json.optString("engine"),
            modelTag = json.optString("modelTag", RemoteTtsProtocol.DEFAULT_MODEL_TAG),
            sampleRate = json.optInt("sampleRate", RemoteTtsProtocol.SAMPLE_RATE),
            defaultVoice = json.optString("defaultVoice"),
            voices = voices,
        )
    }

    /**
     * 合成一句，边收边把 PCM 交给 [onChunk]。
     *
     * @param onChunk 参数是 (缓冲区, 有效长度)；**不要**在回调里持有这个数组（会被复用）。
     * @param isCancelled 每读一块调用一次，返回 true 则立即中止（用于停止/切句）。
     * @return 收到的帧数（来自响应头 `X-Frames`；没有则返回 -1）
     */
    fun synthesize(
        text: String,
        voice: String,
        isCancelled: () -> Boolean = { false },
        onChunk: (ByteArray, Int) -> Unit,
    ): Int {
        cancelled = false
        val body = JSONObject().apply {
            put("text", text)
            if (voice.isNotBlank()) put("voice", voice)
        }.toString()

        val connection = open("/v1/tts", "POST")
        active = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/L16")
            val payload = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("服务器返回 $code：${readError(connection)}")
            }
            val frames = connection.getHeaderField("X-Frames")?.toIntOrNull() ?: -1
            val buffer = ByteArray(READ_BUFFER_BYTES)
            connection.inputStream.use { input ->
                while (true) {
                    if (cancelled || isCancelled()) return frames
                    val count = input.read(buffer)
                    if (count <= 0) break
                    onChunk(buffer, count)
                }
            }
            return frames
        } catch (e: IOException) {
            if (cancelled || isCancelled()) return -1
            throw e
        } finally {
            active = null
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 取服务端缓存片段（`jdpc` 格式，含 16 字节头）。
     * 返回 null 表示服务端没有这一段（404）。
     */
    fun fetchClip(key: String, timeoutMs: Int = 20_000): ByteArray? {
        val connection = open("/v1/clips/$key", "GET")
        active = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = timeoutMs
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) {
                throw IOException("服务器返回 $code：${readError(connection)}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            active = null
            runCatching { connection.disconnect() }
        }
    }

    /** 登记一本书的预合成任务；返回服务端接受了多少句。 */
    fun requestSync(book: String, voice: String, items: List<Pair<String, String>>): Int {
        val array = JSONArray()
        items.forEach { (key, text) ->
            array.put(JSONObject().apply {
                put("key", key)
                put("text", text)
            })
        }
        val body = JSONObject().apply {
            put("book", book)
            if (voice.isNotBlank()) put("voice", voice)
            put("items", array)
        }.toString()
        val text = postText("/v1/sync", body)
        return JSONObject(text).optInt("total", items.size)
    }

    /** 查询预合成进度；没有该任务返回 null。 */
    fun syncProgress(book: String): SyncProgress? {
        val text = try {
            getText("/v1/sync/$book", 10_000)
        } catch (e: IOException) {
            if (e.message?.contains("404") == true) return null
            throw e
        }
        val json = JSONObject(text)
        return SyncProgress(
            total = json.optInt("total"),
            done = json.optInt("done"),
            failed = json.optInt("failed"),
            running = json.optBoolean("running"),
        )
    }

    /** 打断当前请求（停止朗读 / 切句时调用）。 */
    fun cancel() {
        cancelled = true
        active?.let { runCatching { it.disconnect() } }
        active = null
    }

    // ---- 内部 ----

    private fun open(path: String, method: String): HttpURLConnection {
        if (baseUrl.isBlank()) throw IOException("没有配置服务器地址")
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "JianDu-Android")
        return connection
    }

    private fun getText(path: String, timeoutMs: Int): String {
        val connection = open(path, "GET")
        active = connection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("服务器返回 $code：${readError(connection)}")
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            active = null
            runCatching { connection.disconnect() }
        }
    }

    private fun postText(path: String, body: String): String {
        val connection = open(path, "POST")
        active = connection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("服务器返回 $code：${readError(connection)}")
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            active = null
            runCatching { connection.disconnect() }
        }
    }

    /** 从错误响应里挖出服务端给的中文原因，拼进异常消息。 */
    private fun readError(connection: HttpURLConnection): String {
        val raw = try {
            connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return connection.responseMessage.orEmpty()
        return runCatching { JSONObject(raw).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: raw.take(160)
    }

    private companion object {
        /** 局域网连接超时：要短，连不上就赶紧回退，不能让用户干等 */
        const val CONNECT_TIMEOUT_MS = 4_000

        /** 读超时：整句合成 + 传输，给足余量（换成慢模型也够） */
        const val READ_TIMEOUT_MS = 60_000

        const val READ_BUFFER_BYTES = 8 * 1024
    }
}
