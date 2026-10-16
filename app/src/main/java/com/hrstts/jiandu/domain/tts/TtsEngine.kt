package com.hrstts.jiandu.domain.tts

/** 朗读状态机 */
enum class TtsStatus {
    Idle,
    Initializing,
    Ready,
    Speaking,
    Paused,
    Error,
}

/** 朗读界面状态 */
data class TtsUiState(
    val status: TtsStatus = TtsStatus.Idle,
    val engineName: String = "",
    /** 正在朗读的片段 key（chapter-paragraph-sentence），用于正文高亮 */
    val activeSegmentKey: String? = null,
    /** 正在朗读的字符范围（对应段落文本内的下标） */
    val activeRange: IntRange? = null,
    val currentText: String? = null,
    val speechRate: Float = 1.0f,
    val message: String? = null,
) {
    val isActive: Boolean get() = status == TtsStatus.Speaking || status == TtsStatus.Paused
    val isSpeaking: Boolean get() = status == TtsStatus.Speaking
}

/** 引擎回调（实现方需保证回调发生在主线程） */
interface TtsListener {
    fun onReady()
    fun onError(message: String)
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onRange(utteranceId: String, start: Int, end: Int)
}

/**
 * 语音合成引擎抽象。
 *
 * 阶段 P2 实现系统 TTS（[com.hrstts.jiandu.data.tts.SystemTtsEngine]）；
 * 阶段 P3 在相同接口下接入 Qwen3-TTS 端侧推理，控制器无需改动。
 */
interface TtsEngine {

    /** 引擎标识（system / qwen3-tts） */
    val id: String

    /** 展示名（用于界面提示当前使用的引擎） */
    val displayName: String

    /** 是否已就绪可朗读 */
    val isReady: Boolean

    fun setListener(listener: TtsListener)

    /** 初始化（幂等）；结果通过 listener 回调 */
    fun initialize()

    /** 朗读一段文本（单段，连续播放由控制器驱动） */
    fun speak(text: String, utteranceId: String)

    /** 停止当前朗读（保持引擎可继续使用） */
    fun stop()

    /** 语速（1.0 为正常） */
    fun setSpeechRate(rate: Float)

    /**
     * 预取：把文本提前合成进缓存，**不播放**。
     *
     * 默认空实现（系统 TTS 用不到：它本身很快）。端侧引擎实现为「合成 + 写缓存」，
     * 供控制器在句间预取下一句、或导入文本后预热首段。
     * 实现必须能被下一次 [speak] 抢占，不得阻塞朗读。
     */
    fun prefetch(text: String) = Unit

    fun shutdown()
}
