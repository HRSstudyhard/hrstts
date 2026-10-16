package com.hrstts.jiandu.data.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.tts.TtsEngine
import com.hrstts.jiandu.domain.tts.TtsListener
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.UiStrings
import java.util.Locale

/**
 * 系统 TTS 引擎（兜底方案）。
 *
 * 优点：零下载、随系统更新、几乎全部设备可用；缺点：音色固定、机械感强。
 * 当 Qwen3-TTS 端侧模型未下载或设备不满足要求时，控制器会自动使用本引擎。
 */
class SystemTtsEngine(
    private val context: Context,
    private val strings: UiStrings = AndroidStrings(context),
) : TtsEngine {

    override val id: String = ID
    override val displayName: String = strings.get(R.string.engine_system_voice)

    override var isReady: Boolean = false
        private set

    private var listener: TtsListener? = null
    private var tts: TextToSpeech? = null
    private var initializing = false
    private var speechRate: Float = 1.0f
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次朗读请求，用于合成失败时按备用语言重试 */
    private var lastText: String? = null
    private var lastUtteranceId: String? = null
    private var usedFallbackLocale = false

    override fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    override fun initialize() {
        if (isReady || initializing) return
        initializing = true

        tts = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                initializing = false
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    listener?.onError(strings.get(R.string.system_tts_init_failed))
                    return@post
                }

                val chinese = runCatching { engine.setLanguage(Locale.CHINA) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                val usable = chinese != TextToSpeech.LANG_MISSING_DATA &&
                    chinese != TextToSpeech.LANG_NOT_SUPPORTED
                if (usable) {
                    // 优先挑一个中文音色（不同引擎音色质量差异较大）
                    runCatching {
                        engine.voices
                            ?.firstOrNull { it.locale.language == Locale.CHINA.language && !it.isNetworkConnectionRequired }
                            ?.let { engine.voice = it }
                    }
                } else {
                    val fallback = runCatching { engine.setLanguage(Locale.getDefault()) }
                        .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    if (fallback == TextToSpeech.LANG_MISSING_DATA ||
                        fallback == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        listener?.onError(strings.get(R.string.system_tts_no_voice_data))
                        return@post
                    }
                    usedFallbackLocale = true
                }

                engine.setSpeechRate(speechRate)
                engine.setOnUtteranceProgressListener(progressListener)
                isReady = true
                listener?.onReady()
            }
        }
    }

    override fun speak(text: String, utteranceId: String) {
        lastText = text
        lastUtteranceId = utteranceId
        speakInternal(text, utteranceId)
    }

    private fun speakInternal(text: String, utteranceId: String) {
        val engine = tts
        if (engine == null || !isReady) {
            listener?.onError(strings.get(R.string.system_tts_not_ready))
            return
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            listener?.onError(strings.get(R.string.system_tts_rejected))
        }
    }

    /**
     * 合成失败处理：先尝试切换到系统默认语言重试一次（部分设备的引擎在中文音色上
     * 缺少数据或合成失败），仍失败才向界面报错。
     */
    private fun handleSynthesisError() {
        val text = lastText
        val id = lastUtteranceId
        val engine = tts
        if (!usedFallbackLocale && text != null && id != null && engine != null) {
            usedFallbackLocale = true
            val result = runCatching { engine.setLanguage(Locale.getDefault()) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                speakInternal(text, id)
                return
            }
        }
        listener?.onError(strings.get(R.string.system_tts_failed))
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(MIN_RATE, MAX_RATE)
        runCatching { tts?.setSpeechRate(speechRate) }
    }

    override fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isReady = false
    }

    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            mainHandler.post { utteranceId?.let { listener?.onStart(it) } }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post { utteranceId?.let { listener?.onDone(it) } }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post { handleSynthesisError() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { handleSynthesisError() }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            mainHandler.post { utteranceId?.let { listener?.onRange(it, start, end) } }
        }
    }

    companion object {
        const val ID = "system"
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2.0f
    }
}
