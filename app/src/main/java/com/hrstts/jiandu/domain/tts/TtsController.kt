package com.hrstts.jiandu.domain.tts

import com.hrstts.jiandu.data.tts.Qwen3TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 朗读控制器：把「片段序列」逐句送入 [TtsEngine]，并维护播放状态与高亮范围。
 *
 * 连续播放由控制器驱动（引擎一次只合成一段），因此：
 *  - 系统 TTS 与 Qwen3-TTS 可以复用同一套播放逻辑；
 *  - 暂停/继续可按「句」为单位恢复；
 *  - 高亮位置来自引擎的 onRange 回调，粒度到字。
 */
class TtsController {

    private val _state = MutableStateFlow(TtsUiState())
    val state: StateFlow<TtsUiState> = _state.asStateFlow()

    private var engine: TtsEngine? = null
    private var segments: List<ReadingSegment> = emptyList()
    private var cursor: Int = 0
    private var token: Int = 0
    private var baseOffset: Int = 0
    private var lastSpokenOffset: Int = 0
    private var pendingPlay: Boolean = false

    /** 连续性统计：上一句播放结束的时刻（用于算句间空档） */
    private var lastDoneAtMs: Long = 0L

    /** 连续性统计：句间空档（ms）+ 已统计句数 */
    @Volatile
    var maxGapMs: Long = 0L
        private set

    @Volatile
    var totalGapMs: Long = 0L
        private set

    @Volatile
    var gapSamples: Int = 0
        private set

    private val listener = object : TtsListener {

        override fun onReady() {
            _state.update { it.copy(status = TtsStatus.Ready, engineName = engine?.displayName ?: "") }
            if (pendingPlay) {
                pendingPlay = false
                speakCurrent()
            }
        }

        override fun onError(message: String) {
            pendingPlay = false
            // 出错时清除高亮，避免正文停留在「正在朗读」的视觉状态
            _state.update {
                it.copy(
                    status = TtsStatus.Error,
                    activeSegmentKey = null,
                    activeRange = null,
                    currentText = null,
                    message = message,
                )
            }
        }

        override fun onStart(utteranceId: String) {
            // 结算句间空档：上一句播完 → 本句开始出声
            if (lastDoneAtMs > 0 && utteranceId == token.toString()) {
                val gap = System.currentTimeMillis() - lastDoneAtMs
                totalGapMs += gap
                gapSamples++
                if (gap > maxGapMs) maxGapMs = gap
                android.util.Log.i(
                    "TtsController",
                    "句间空档=${gap}ms 平均=${totalGapMs / gapSamples}ms 最大=${maxGapMs}ms 样本=$gapSamples",
                )
                lastDoneAtMs = 0
            }
        }

        override fun onDone(utteranceId: String) {
            if (utteranceId != token.toString()) return
            baseOffset = 0
            lastSpokenOffset = 0
            // 记录句间空档的起点（引擎下一次 onStart 时结算）
            lastDoneAtMs = System.currentTimeMillis()
            // 播完一句 → 先把**本段剩余句子**排进预取队列（段内衔接的关键）
            prefetchParagraphAhead()
            advance()
        }

        override fun onRange(utteranceId: String, start: Int, end: Int) {
            if (utteranceId != token.toString()) return
            val segment = segments.getOrNull(cursor) ?: return
            // 引擎可能对文本做归一化（数字/符号展开），越界时退化为整句高亮
            val segmentEnd = segment.charStart + segment.text.length
            val absoluteStart = (segment.charStart + baseOffset + start).coerceIn(segment.charStart, segmentEnd)
            val absoluteEnd = (segment.charStart + baseOffset + end).coerceIn(absoluteStart, segmentEnd)
            lastSpokenOffset = absoluteStart
            _state.update { it.copy(activeRange = absoluteStart until absoluteEnd) }
        }
    }

    fun attach(engine: TtsEngine) {
        if (this.engine === engine) return
        this.engine?.shutdown()
        this.engine = engine
        engine.setListener(listener)
        _state.update { it.copy(engineName = engine.displayName, status = TtsStatus.Ready) }
    }

    /** 引擎已就绪时的展示名（用于界面提示当前朗读引擎） */
    val engineName: String get() = engine?.displayName.orEmpty()

    /** 底层引擎是否真的完成初始化（`attach` 后仍需要加载模型宿主表，约 2 s） */
    val isEngineReady: Boolean get() = engine?.isReady == true

    /** 从指定位置开始连续朗读 */
    fun play(segments: List<ReadingSegment>, fromIndex: Int = 0) {
        if (segments.isEmpty()) return
        this.segments = segments
        this.cursor = fromIndex.coerceIn(0, segments.lastIndex)
        this.baseOffset = 0
        this.lastSpokenOffset = 0
        resetGapStats()
        // 开播即把本段剩余句子全部排入预取队列：段内衔接靠的就是「后面几句已经备好」
        prefetchParagraphAhead()
        startOrInitialize()
    }

    /** 重置连续性统计（每次开始朗读时调用） */
    fun resetGapStats() {
        lastDoneAtMs = 0L
        maxGapMs = 0L
        totalGapMs = 0L
        gapSamples = 0
    }

    /** 连续性统计摘要，例如 `n=8 avg=1015ms max=1025ms` */
    fun gapSummary(): String =
        if (gapSamples == 0) "n=0" else "n=$gapSamples avg=${totalGapMs / gapSamples}ms max=${maxGapMs}ms"

    fun pause() {
        if (_state.value.status != TtsStatus.Speaking) return
        engine?.stop()
        _state.update { it.copy(status = TtsStatus.Paused) }
    }

    fun resume() {
        if (_state.value.status != TtsStatus.Paused) return
        _state.update { it.copy(status = TtsStatus.Speaking) }
        speakCurrent()
    }

    fun stop() {
        engine?.stop()
        segments = emptyList()
        cursor = 0
        baseOffset = 0
        lastSpokenOffset = 0
        pendingPlay = false
        _state.update {
            it.copy(
                status = TtsStatus.Ready,
                activeSegmentKey = null,
                activeRange = null,
                currentText = null,
            )
        }
    }

    fun next() {
        if (segments.isEmpty()) return
        engine?.stop()
        cursor = (cursor + 1).coerceAtMost(segments.lastIndex)
        baseOffset = 0
        lastSpokenOffset = 0
        speakCurrent()
    }

    fun previous() {
        if (segments.isEmpty()) return
        engine?.stop()
        cursor = (cursor - 1).coerceAtLeast(0)
        baseOffset = 0
        lastSpokenOffset = 0
        speakCurrent()
    }

    fun setSpeechRate(rate: Float) {
        engine?.setSpeechRate(rate)
        _state.update { it.copy(speechRate = rate) }
        if (_state.value.status == TtsStatus.Speaking) {
            engine?.stop()
            speakCurrent()
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 预热：把从 [startIndex] 开始的若干句提前合成进引擎缓存（不播放）。
     *
     * 调用时机：导入文本后（预热首段）、或朗读开始前。端侧引擎会忽略「正在朗读」期间的调用。
     */
    fun warmUp(segments: List<ReadingSegment>, startIndex: Int = 0, count: Int = PREFETCH_AHEAD) {
        val engine = engine ?: return
        if (!engine.isReady || _state.value.isActive) return
        var index = startIndex.coerceAtLeast(0)
        var done = 0
        while (index < segments.size && done < count) {
            val text = segments[index].text
            if (text.isNotBlank()) {
                engine.prefetch(text)
                done++
            }
            index++
        }
    }

    /** 开关预取（省电模式/低内存机型可关） */
    fun setPrefetchEnabled(enabled: Boolean) {
        (engine as? Qwen3TtsEngine)?.prefetchEnabled = enabled
        (engine as? com.hrstts.jiandu.data.tts.RemoteTtsEngine)?.prefetchEnabled = enabled
    }

    /** 退后台：清空引擎音频缓存的内存档（磁盘档保留） */
    fun releaseAudioCacheMemory(): Long =
        (engine as? Qwen3TtsEngine)?.trimCacheForBackground()
            ?: (engine as? com.hrstts.jiandu.data.tts.RemoteTtsEngine)?.trimCacheForBackground()
            ?: 0L

    /**
     * 段内预取：把**当前段落剩余的所有句子**排进引擎的预取队列。
     *
     * 这是「段内衔接」的关键：段内句与句之间不该有合成等待，否则每句都要等几十秒，
     * 听感就是断续。只预取本段（不跨段），避免一次排入过多、把队列和内存顶满。
     */
    private fun prefetchParagraphAhead() {
        val engine = engine ?: return
        if (!engine.isReady) return
        val current = segments.getOrNull(cursor) ?: return
        var index = cursor + 1
        var queued = 0
        while (index < segments.size && queued < MAX_PARAGRAPH_PREFETCH) {
            val segment = segments[index]
            // 只排同一段落的句子
            if (segment.chapterIndex != current.chapterIndex ||
                segment.paragraphIndex != current.paragraphIndex
            ) {
                break
            }
            if (segment.text.isNotBlank()) {
                engine.prefetch(segment.text)
                queued++
            }
            index++
        }
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
    }

    // ---- 内部实现 ----

    private fun startOrInitialize() {
        val engine = engine ?: return
        if (engine.isReady) {
            speakCurrent()
        } else {
            pendingPlay = true
            _state.update { it.copy(status = TtsStatus.Initializing) }
            engine.initialize()
        }
    }

    private fun speakCurrent() {
        val engine = engine ?: return
        val segment = segments.getOrNull(cursor) ?: return finish()

        val from = baseOffset.coerceIn(0, segment.text.length)
        val text = segment.text.substring(from)
        if (text.isBlank()) return advance()

        token++
        val segmentEnd = segment.charStart + segment.text.length
        _state.update {
            it.copy(
                status = TtsStatus.Speaking,
                activeSegmentKey = segment.key,
                activeRange = (segment.charStart + from) until segmentEnd,
                currentText = segment.text,
            )
        }
        engine.speak(text, token.toString())
    }

    private fun advance() {
        if (segments.isEmpty()) return finish()
        if (cursor >= segments.lastIndex) return finish()
        cursor++
        baseOffset = 0
        lastSpokenOffset = 0
        speakCurrent()
    }

    private fun finish() {
        segments = emptyList()
        cursor = 0
        baseOffset = 0
        lastSpokenOffset = 0
        _state.update {
            it.copy(
                status = TtsStatus.Ready,
                activeSegmentKey = null,
                activeRange = null,
                currentText = null,
            )
        }
    }

    private companion object {
        /** 句间预取的句数（端侧合成慢，多备一句能明显减少停顿） */
        const val PREFETCH_AHEAD = 2

        /** 段内预取上限：一段最多预取多少句（防止超长段落一次排入几百句） */
        const val MAX_PARAGRAPH_PREFETCH = 16
    }
}
