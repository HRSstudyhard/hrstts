package com.hrstts.jiandu.data.tts

import android.content.Context
import android.content.SharedPreferences
import com.hrstts.jiandu.domain.tts.RemoteTtsProtocol

/**
 * 远程 TTS 的本地设置（SharedPreferences）。
 *
 * 为什么不用 Room：这是一份「用户 preferences」，只有 4 个字段，读写极频繁（每次朗读都要算
 * 缓存 key），SharedPreferences 更合适，也不需要迁移。
 */
class RemoteTtsSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否选择用电脑合成（关掉则回到端侧 Qwen3 / 系统语音） */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * 用户在设置里填的服务器地址（原样保存，允许只写 `10.62.61.202`）。
     * 真正请求时用 [baseUrl]（已标准化）。
     */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    /** 标准化后的 baseUrl，例如 `http://10.62.61.202:8080`；未配置返回空串 */
    val baseUrl: String
        get() = RemoteTtsProtocol.normalizeBaseUrl(serverUrl)

    /** 选中的音色 id；空串表示「用服务端默认音色」 */
    var voice: String
        get() = prefs.getString(KEY_VOICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_VOICE, value.trim()).apply()

    /**
     * 电脑连不上时是否自动回退到端侧/系统引擎。
     *
     * 默认开：手机带着出门、电脑关机的情况下，朗读不应该直接失效。
     */
    var autoFallback: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_FALLBACK, value).apply()

    /** 缓存服务端 `/v1/health` 返回的 modelTag，供 UI 展示与诊断 */
    var cachedModelTag: String
        get() = prefs.getString(KEY_MODEL_TAG, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL_TAG, value).apply()

    /** 是否真的该启用远程引擎（开关打开 + 地址已填） */
    val isConfigured: Boolean
        get() = enabled && baseUrl.isNotEmpty()

    fun snapshot(): Snapshot = Snapshot(
        enabled = enabled,
        serverUrl = serverUrl,
        baseUrl = baseUrl,
        voice = voice,
        autoFallback = autoFallback,
        cachedModelTag = cachedModelTag,
    )

    data class Snapshot(
        val enabled: Boolean,
        val serverUrl: String,
        val baseUrl: String,
        val voice: String,
        val autoFallback: Boolean,
        val cachedModelTag: String,
    )

    private companion object {
        const val PREFS = "jiandu_remote_tts"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER = "server_url"
        const val KEY_VOICE = "voice"
        const val KEY_FALLBACK = "auto_fallback"
        const val KEY_MODEL_TAG = "model_tag"
    }
}
