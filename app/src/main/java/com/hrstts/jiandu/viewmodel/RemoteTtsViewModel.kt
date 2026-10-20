package com.hrstts.jiandu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrstts.jiandu.R
import com.hrstts.jiandu.data.tts.RemoteTtsClient
import com.hrstts.jiandu.data.tts.RemoteTtsSettings
import com.hrstts.jiandu.i18n.AndroidStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「电脑合成」设置。
 *
 * 与阅读区的 [com.hrstts.jiandu.data.tts.RemoteTtsEngine] 共享同一份 SharedPreferences，
 * 所以这里改完地址后通知 ReaderViewModel 重新评估引擎即可，不需要传对象。
 */
class RemoteTtsViewModel(application: Application) : AndroidViewModel(application) {

    /** 连接测试的状态机 */
    sealed interface Probe {
        data object Idle : Probe
        data object Testing : Probe
        data class Ok(val engine: String, val voiceCount: Int) : Probe
        data class Failed(val reason: String) : Probe
    }

    data class UiState(
        val enabled: Boolean = false,
        val serverUrl: String = "",
        val voice: String = "",
        val voices: List<RemoteTtsClient.Voice> = emptyList(),
        val defaultVoice: String = "",
        val probe: Probe = Probe.Idle,
    ) {
        val normalizedUrl: String
            get() = com.hrstts.jiandu.domain.tts.RemoteTtsProtocol.normalizeBaseUrl(serverUrl)
    }

    private val strings = AndroidStrings(application)
    private val settings = RemoteTtsSettings(application)

    private val _state = MutableStateFlow(
        UiState(
            enabled = settings.enabled,
            serverUrl = settings.serverUrl,
            voice = settings.voice,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 用户改了需要重建引擎的设置后，由界面转给 ReaderViewModel */
    var onSettingsChanged: (() -> Unit)? = null

    init {
        // 已经配过地址就主动探一次：否则抽屉里会一直显示「未测试」，
        // 而实际引擎可能已经连上了（两个状态不一致会让人以为坏了）。
        if (settings.isConfigured) testConnection()
    }

    fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        _state.update { it.copy(enabled = enabled) }
        onSettingsChanged?.invoke()
    }

    /**
     * 地址输入。**不做实时保存**——用户逐字输入时中间态（如 `10.6`）没有意义，
     * 由界面在失焦/回车时调用 [commitServerUrl]。
     */
    fun editServerUrl(text: String) {
        _state.update { it.copy(serverUrl = text) }
    }

    fun commitServerUrl() {
        val raw = _state.value.serverUrl
        settings.serverUrl = raw
        _state.update { it.copy(serverUrl = settings.serverUrl) }
        if (raw.isNotBlank()) {
            settings.enabled = true
            _state.update { it.copy(enabled = true) }
        }
        onSettingsChanged?.invoke()
    }

    fun setVoice(voiceId: String) {
        settings.voice = voiceId
        _state.update { it.copy(voice = voiceId) }
        onSettingsChanged?.invoke()
    }

    /** 探测服务端：能拿到 health 就说明地址、防火墙、明文 HTTP 三件事都对。 */
    fun testConnection() {
        val baseUrl = _state.value.normalizedUrl
        if (baseUrl.isEmpty()) {
            _state.update { it.copy(probe = Probe.Failed(strings.get(R.string.remote_no_server))) }
            return
        }
        _state.update { it.copy(probe = Probe.Testing) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val health = RemoteTtsClient(baseUrl).health(timeoutMs = 5_000)
                    Probe.Ok(health.engine, health.voices.size) to health
                } catch (t: Throwable) {
                    Probe.Failed(friendly(t)) to null
                }
            }
            val (probe, health) = result
            _state.update {
                it.copy(
                    probe = probe,
                    voices = health?.voices ?: it.voices,
                    defaultVoice = health?.defaultVoice ?: it.defaultVoice,
                )
            }
            if (health != null) {
                // 记下服务端地址（标准化后的），下次冷启动直接用
                settings.serverUrl = baseUrl
                settings.enabled = true
                _state.update { it.copy(serverUrl = baseUrl, enabled = true) }
                onSettingsChanged?.invoke()
            }
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> strings.get(R.string.remote_error_unknown_host)
        is java.net.ConnectException -> strings.get(R.string.remote_error_refused)
        is java.net.SocketTimeoutException -> strings.get(R.string.remote_error_timeout)
        is java.net.NoRouteToHostException -> strings.get(R.string.remote_error_no_route)
        is java.io.IOException -> t.message ?: t.javaClass.simpleName
        else -> t.message ?: t.javaClass.simpleName
    }
}
