package com.hrstts.jiandu.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hrstts.jiandu.data.SafSource
import com.hrstts.jiandu.data.db.AppDatabase
import com.hrstts.jiandu.data.db.HistoryEntry
import com.hrstts.jiandu.data.tts.Qwen3ModelManager
import com.hrstts.jiandu.data.tts.Qwen3TtsEngine
import com.hrstts.jiandu.data.tts.SystemTtsEngine
import com.hrstts.jiandu.data.tts.TtsAudioCache
import com.hrstts.jiandu.domain.model.Book
import com.hrstts.jiandu.domain.model.ParagraphSelection
import com.hrstts.jiandu.domain.model.ReadingAnchor
import com.hrstts.jiandu.domain.parser.TextParser
import com.hrstts.jiandu.domain.tts.ReadingSegment
import com.hrstts.jiandu.domain.tts.ReadingSegments
import com.hrstts.jiandu.domain.tts.TtsController
import com.hrstts.jiandu.domain.tts.TtsStatus
import com.hrstts.jiandu.domain.tts.TtsUiState
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.TextIds
import com.hrstts.jiandu.i18n.UiText
import com.hrstts.jiandu.i18n.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读区界面状态 */
@Immutable
data class ReaderUiState(
    val book: Book? = null,
    val isLoading: Boolean = false,
    /** 长按选中的段落/句子 */
    val selection: ParagraphSelection? = null,
    /** 待跳转的阅读位置（从「我的」切换历史文本时恢复进度） */
    val pendingAnchor: ReadingAnchor? = null,
    /** 当前可见的首个段落（用于保存阅读进度） */
    val visibleAnchor: ReadingAnchor = ReadingAnchor(),
    /** 一次性提示消息（如：文件过大已截断、URI 失效）；只带资源 ID，由界面按当前语言渲染 */
    val message: UiText? = null,
) {
    val bookName: String? get() = book?.name

    /** 已导入但没有任何文字内容（空文件 / 只有空白的文件） */
    val isEmptyBook: Boolean get() = book != null && book.charCount == 0
}

/**
 * 阅读区状态持有者。
 *
 * 阶段 P1：SAF 导入 → 编码探测 → 章节/段落解析 → 写入历史。
 * 阶段 P2：段落长按选中 → 「阅读」朗读（系统 TTS 兜底引擎）。
 * 阶段 P3：把朗读引擎替换为 Qwen3-TTS（控制器与界面无需改动）。
 * 阶段 P5：空文件兜底、进程被杀后自动恢复上次文本、文案全部走资源（i18n）。
 */
class ReaderViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).historyDao()
    private val parser = TextParser()
    private val ttsController = TtsController()
    private val modelManager = Qwen3ModelManager(application)
    private val strings = AndroidStrings(application)
    private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    /**
     * 端侧朗读的 PCM 缓存：RAM 32 MB 环形 + 磁盘 512 MB LRU。
     *
     * 退后台时由界面调用 [releaseAudioCacheMemory] 清空 RAM 档（保留最近几条，
     * 保证回前台第一句仍然秒开）；磁盘档保留，供回看/重读复用。
     */
    private val audioCache = TtsAudioCache(application)

    /** 当前是否使用端侧 Qwen3-TTS 引擎 */
    private var usingQwen3 = false
    private var fellBackToSystem = false

    /**
     * 上一次**真正落盘**的阅读位置。
     *
     * 存在的理由：滚动时 [onVisibleAnchorChanged] 会被连续调用，每次只会前进 1 段。
     * 若拿「当前状态」和「本次新位置」比较，差值恒为 1，节流条件（≥10 段）永远不成立，
     * 结果就是**阅读过程中进度从不落盘**——进程被杀或旋转屏幕后只能回到文首
     * （P5 实测发现：滚动到第 71 段，SharedPreferences 里仍是 `last_paragraph = 0`）。
     * 因此必须和「上次落盘的位置」比较。
     */
    private var lastPersistedAnchor: ReadingAnchor = ReadingAnchor()

    /**
     * 恢复落位期间抑制「当前可见段落」上报。
     *
     * 配置变更后新列表会先停在文首并上报 0-0，把刚恢复的阅读位置覆盖掉
     * （实测：旋转后 SavedStateHandle 被写成 0-0，界面跳回第 1 段）。
     * 恢复动作（或新书的回文首）完成后由界面调用 [onInitialPositionSettled] 解除抑制。
     */
    private var suppressVisibleAnchorReports = true

    val ttsState: StateFlow<TtsUiState> = ttsController.state

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        // 端侧模型已完整安装 → 使用 Qwen3-TTS；否则使用系统语音
        if (modelManager.isInstalled()) {
            attachQwen3(switchedMessage = null)
        } else {
            ttsController.attach(SystemTtsEngine(application.applicationContext, strings))
        }

        // 端侧引擎不可用（设备 ABI 不支持 / 内存不足 / 模型损坏）→ 自动回退系统语音
        viewModelScope.launch {
            ttsState.collect { state ->
                if (usingQwen3 && !fellBackToSystem && state.status == TtsStatus.Error) {
                    fellBackToSystem = true
                    usingQwen3 = false
                    ttsController.attach(
                        SystemTtsEngine(getApplication(), strings),
                    )
                    _uiState.update {
                        it.copy(message = uiText(TextIds.engineFallbackToSystem))
                    }
                }
            }
        }

        // 进程被杀后重新进入：自动恢复上次阅读的文本（URI 授权是持久的，通常仍可读）
        restoreLastBook()
    }

    // ---- 导入 ----

    /** 导入并解析用户选中的文本文件 */
    fun import(uri: Uri, anchor: ReadingAnchor? = null) {
        viewModelScope.launch { load(uri, anchor, remember = true) }
    }

    /**
     * 从「我的」抽屉切换文本（需求 5）：校验 URI 是否仍可访问，
     * 可用则替换当前文本并恢复到上次阅读位置。
     */
    fun openHistory(entry: HistoryEntry) {
        val context = getApplication<Application>()
        if (!SafSource.canRead(context, Uri.parse(entry.uri))) {
            _uiState.update {
                it.copy(message = uiText(TextIds.importUriUnavailable, entry.name))
            }
            return
        }
        // Room 里的位置是节流写入的（每 10 段/跨章一次），SharedPreferences 每次滚动都写；
        // 两者取更靠后的，避免「我的」恢复时退回到旧检查点。
        val remembered = lastRememberedAnchor(entry.uri)
        val fromDb = ReadingAnchor(entry.chapterIndex, entry.paragraphIndex)
        import(uri = Uri.parse(entry.uri), anchor = laterOf(fromDb, remembered))
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun consumeTtsMessage() {
        ttsController.consumeMessage()
    }

    /**
     * 端侧模型刚下载完成时调用：无需重启应用即可把朗读引擎切到 Qwen3-TTS。
     * 若之前因端侧不可用而回退过系统语音，不重复切换。
     */
    fun onModelAvailable() {
        if (usingQwen3 || fellBackToSystem || !modelManager.isInstalled()) return
        attachQwen3(switchedMessage = TextIds.engineSwitchedToQwen3)
    }

    /**
     * 装载端侧引擎。
     *
     * 必须**串行**：模型表加载会占约 2.4 GB native 堆（实测），若在加载途中再次进来
     * （例如 `init` 与「模型下载完成」两条路径同时触发），两个引擎实例同时初始化会把
     * 进程推到被系统回收（模拟器 8 GB 下实测进程消失）。
     */
    private fun attachQwen3(switchedMessage: Int?) {
        if (usingQwen3 || fellBackToSystem) return
        usingQwen3 = true
        val engine = Qwen3TtsEngine(modelManager.modelDir, strings, audioCache)
        ttsController.attach(engine)
        engine.initialize()
        android.util.Log.i("ReaderViewModel", "attach on-device engine: ${engine.displayName}")
        // 引擎就绪后再预热音频缓存。
        // 注意：`attach` 会立刻把 ttsState 置为 Ready，而端侧引擎的宿主表还要加载约 2 s；
        // 所以「等一次 Ready」会马上拿到 Ready 但 engineReady 仍为 false
        // （实测预热被丢弃、缓存始终为空）。改成轮询重试，直到引擎真正就绪。
        viewModelScope.launch {
            repeat(WARMUP_RETRY_TIMES) {
                if (ttsController.isEngineReady) {
                    warmUpAudioCache(_uiState.value.book)
                    return@launch
                }
                kotlinx.coroutines.delay(WARMUP_RETRY_DELAY_MS)
            }
            android.util.Log.w("ReaderViewModel", "预热放弃：引擎在重试窗口内未就绪")
        }
        if (switchedMessage != null) {
            _uiState.update { it.copy(message = uiText(switchedMessage)) }
        }
    }

    /** 退后台：释放音频缓存的内存档（磁盘档保留，回前台首句仍可秒开） */
    fun releaseAudioCacheMemory(): Long = audioCache.clearMemory()

    /** 音频缓存概况（诊断用） */
    fun audioCacheStats(): String = audioCache.statsLine()

    /** 清空音频缓存（内存 + 磁盘） */
    fun clearAudioCache() {
        audioCache.clearMemory()
        audioCache.clearDisk()
    }

    // ---- 阅读进度 ----

    /** 阅读区报告当前可见的首个段落 */
    fun onVisibleAnchorChanged(chapterIndex: Int, paragraphIndex: Int) {
        if (suppressVisibleAnchorReports) return
        val current = _uiState.value.visibleAnchor
        if (current.chapterIndex == chapterIndex && current.paragraphIndex == paragraphIndex) return
        // 配置变更（旋转/分屏）时本 ViewModel 会被销毁重建，`ON_STOP` 里的落盘
        // 晚于新实例的构造，因此**必须**同步写进 SavedStateHandle，否则旋转后回到文首。
        savedState[KEY_SAVED_CHAPTER] = chapterIndex
        savedState[KEY_SAVED_PARAGRAPH] = paragraphIndex
        // SharedPreferences 很便宜：每次滚动都写，保证「上次读到哪里」始终是最新的
        // （Room 有节流，只作长期记录；「我的」恢复时与这里取更靠后的那个）
        rememberAnchor(ReadingAnchor(chapterIndex, paragraphIndex))
        // 与「上次落盘的位置」比较，而不是与上一次滚动事件比较（否则差值恒为 1，永不落盘）
        val needsPersist = kotlin.math.abs(lastPersistedAnchor.chapterIndex - chapterIndex) >= 1 ||
            kotlin.math.abs(lastPersistedAnchor.paragraphIndex - paragraphIndex) >= 10
        _uiState.update {
            it.copy(
                visibleAnchor = ReadingAnchor(chapterIndex, paragraphIndex),
                pendingAnchor = null,
            )
        }
        if (needsPersist) persistProgress()
    }

    /** 阅读区完成「恢复落位」后调用，解除上报抑制（见 [suppressVisibleAnchorReports]） */
    fun onInitialPositionSettled() {
        suppressVisibleAnchorReports = false
    }

    /** 保存阅读进度（切后台/退出时调用；滚动过程中每 10 段或跨章也会调用） */
    fun persistProgress() {
        val book = _uiState.value.book ?: return
        val anchor = _uiState.value.visibleAnchor
        lastPersistedAnchor = anchor
        rememberAnchor(anchor)
        viewModelScope.launch {
            dao.updateProgress(
                uri = book.uri,
                timestamp = System.currentTimeMillis(),
                chapter = anchor.chapterIndex,
                paragraph = anchor.paragraphIndex,
            )
        }
    }

    // ---- 段落选中 ----

    fun select(selection: ParagraphSelection) {
        _uiState.update { it.copy(selection = selection) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selection = null) }
    }

    /** 「整段」：把选中范围扩展到整段 */
    fun expandSelectionToParagraph() {
        val current = _uiState.value.selection ?: return
        val text = paragraphText(current.chapterIndex, current.paragraphIndex) ?: return
        _uiState.update {
            it.copy(
                selection = current.copy(start = 0, end = text.length, text = text),
            )
        }
    }

    // ---- 朗读 ----

    /** 「阅读」：从选中位置开始连续朗读到文末 */
    fun readSelection() {
        val book = _uiState.value.book ?: return
        val selection = _uiState.value.selection ?: return
        val text = paragraphText(selection.chapterIndex, selection.paragraphIndex) ?: return
        val sentenceIndex = sentenceIndexAt(text, selection.start)
        val segments = ReadingSegments.fromBook(
            book = book,
            startChapter = selection.chapterIndex,
            startParagraph = selection.paragraphIndex,
            startSentence = sentenceIndex,
        )
        if (segments.isEmpty()) return
        _uiState.update { it.copy(selection = null) }
        ttsController.play(segments)
    }

    /** 播放/暂停切换；空闲时从文首开始朗读 */
    fun togglePlayback() {
        when (ttsState.value.status) {
            TtsStatus.Speaking -> ttsController.pause()
            TtsStatus.Paused -> ttsController.resume()
            else -> {
                if (_uiState.value.selection != null) {
                    readSelection()
                } else {
                    readFromStart()
                }
            }
        }
    }

    fun readFromStart() {
        val book = _uiState.value.book ?: return
        val segments: List<ReadingSegment> = ReadingSegments.fromBook(book)
        if (segments.isEmpty()) return
        _uiState.update { it.copy(selection = null) }
        ttsController.play(segments)
    }

    fun stopReading() = ttsController.stop()

    fun nextSegment() = ttsController.next()

    fun previousSegment() = ttsController.previous()

    fun setSpeechRate(rate: Float) = ttsController.setSpeechRate(rate)

    override fun onCleared() {
        ttsController.shutdown()
        super.onCleared()
    }

    // ---- 内部 ----

    /**
     * 统一的载入流程。
     *
     * @param remember true = 用户主动导入（成功后记住该 URI，供进程被杀后恢复）
     */
    private suspend fun load(uri: Uri, anchor: ReadingAnchor?, remember: Boolean) {
        val context = getApplication<Application>()
        ttsController.stop()
        _uiState.update {
            it.copy(isLoading = true, message = null, selection = null, pendingAnchor = null)
        }
        try {
            SafSource.persistPermission(context, uri)
            val imported = SafSource.readBook(context, uri, parser, strings)
            val empty = imported.book.charCount == 0
            android.util.Log.d(
                "ReaderViewModel",
                "load done uri=$uri anchor=${anchor?.chapterIndex}-${anchor?.paragraphIndex} empty=$empty",
            )
            _uiState.update {
                it.copy(
                    book = imported.book,
                    isLoading = false,
                    pendingAnchor = anchor,
                    visibleAnchor = anchor ?: ReadingAnchor(),
                    message = when {
                        // 空文件：解析器给不出段落，但仍把书交给界面以显示「文件是空的」占位
                        empty -> uiText(TextIds.importEmptyFile)
                        imported.truncated -> uiText(
                            TextIds.importTruncated,
                            SafSource.MAX_BYTES / 1024 / 1024,
                        )

                        else -> null
                    },
                )
            }
            if (!empty) {
                recordHistory(imported.book, anchor)
                if (remember) {
                    rememberBook(imported.book, anchor)
                }
                // 导入/切换文本后预热首段语音：用户很可能马上点「阅读」，
                // 端侧合成慢（模拟器 RTF 6.7+），提前备好能显著缩短首音等待。
                warmUpAudioCache(imported.book)
            }
        } catch (t: Throwable) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    message = uiText(
                        TextIds.importFailed,
                        t.message ?: strings.get(TextIds.importUnknownError),
                    ),
                )
            }
        }
    }

    /**
     * 进程被杀 / 配置变更后恢复上次阅读的文本。
     *
     * 触发条件只有一个：存在上次导入记录（`last_uri`）且该 URI 仍可读。
     * 没有「当前是否已有内容」的判断，因此配置变更导致 ViewModel 重建时也会走一遍——
     * 行为幂等（重新解析同一文件并回到记录的段落），代价是可接受的一次重读。
     * 失败静默处理：用户重新导入即可，不打扰。
     */
    private fun restoreLastBook() {
        val uri = prefs.getString(KEY_LAST_URI, null) ?: return
        // 配置变更后 SavedStateHandle 里的位置比 SharedPreferences 新（后者只在跨章/每 10 段落盘）
        val anchor = ReadingAnchor(
            chapterIndex = savedState.get<Int>(KEY_SAVED_CHAPTER)
                ?: prefs.getInt(KEY_LAST_CHAPTER, 0),
            paragraphIndex = savedState.get<Int>(KEY_SAVED_PARAGRAPH)
                ?: prefs.getInt(KEY_LAST_PARAGRAPH, 0),
        )
        android.util.Log.d("ReaderViewModel", "restoreLastBook uri=$uri anchor=${anchor.chapterIndex}-${anchor.paragraphIndex}")
        if (!SafSource.canRead(getApplication(), Uri.parse(uri))) {
            prefs.edit().remove(KEY_LAST_URI).apply()
            return
        }
        viewModelScope.launch {
            load(uri = Uri.parse(uri), anchor = anchor, remember = false)
        }
    }

    private fun rememberBook(book: Book, anchor: ReadingAnchor?) {
        prefs.edit()
            .putString(KEY_LAST_URI, book.uri)
            .putInt(KEY_LAST_CHAPTER, anchor?.chapterIndex ?: 0)
            .putInt(KEY_LAST_PARAGRAPH, anchor?.paragraphIndex ?: 0)
            .apply()
    }

    private fun rememberAnchor(anchor: ReadingAnchor) {
        if (_uiState.value.book == null) return
        prefs.edit()
            .putInt(KEY_LAST_CHAPTER, anchor.chapterIndex)
            .putInt(KEY_LAST_PARAGRAPH, anchor.paragraphIndex)
            .apply()
    }

    /**
     * 读取 SharedPreferences 里记录的阅读位置，但只在它**属于同一个 URI** 时返回。
     * 否则会拿上一本书的位置去恢复另一本书（例如刚换过文本时）。
     */
    private fun lastRememberedAnchor(uri: String): ReadingAnchor? {
        if (prefs.getString(KEY_LAST_URI, null) != uri) return null
        return ReadingAnchor(
            chapterIndex = prefs.getInt(KEY_LAST_CHAPTER, 0),
            paragraphIndex = prefs.getInt(KEY_LAST_PARAGRAPH, 0),
        )
    }

    private fun paragraphText(chapterIndex: Int, paragraphIndex: Int): String? =
        _uiState.value.book
            ?.chapters?.getOrNull(chapterIndex)
            ?.paragraphs?.getOrNull(paragraphIndex)
            ?.text

    /**
     * 预热首段语音（导入后调用）。
     *
     * 只预热**开头 [WARMUP_SENTENCES] 句**：端侧合成慢，预热整章会让 CPU 长时间满载
     * （实测 RTF 6.7，5 分钟音频要合 33 分钟），既费电又抢朗读的算力。
     */
    private fun warmUpAudioCache(book: Book?) {
        if (book == null) return
        if (!usingQwen3) return
        val segments = ReadingSegments.fromBook(book)
        if (segments.isEmpty()) return
        ttsController.warmUp(segments, startIndex = 0, count = WARMUP_SENTENCES)
    }

    private fun sentenceIndexAt(text: String, offset: Int): Int {
        val sentences = ReadingSegments.sentences(text)
        sentences.forEachIndexed { index, (start, sentence) ->
            if (offset in start until (start + sentence.length)) return index
        }
        return 0
    }

    private suspend fun recordHistory(book: Book, anchor: ReadingAnchor?) {
        // 同一文件若已存在（可能是另一个 SAF URI），先合并旧记录，避免「我的」出现重复条目
        val existing = dao.findByNameAndCharCount(book.name, book.charCount)
        if (existing != null && existing.uri != book.uri) {
            dao.deleteByUri(existing.uri)
        }
        // 保留更靠后的阅读位置：
        //  - `anchor`：本次恢复/切换时指定的位置；
        //  - `existing`：历史记录里的位置；
        //  - SharedPreferences：每次滚动都会更新，是最新的「上次读到哪里」。
        // 三者取最靠后的，否则从「我的」重复打开同一文件会把进度推回到文首。
        val target = laterOf(
            anchor,
            existing?.let { ReadingAnchor(it.chapterIndex, it.paragraphIndex) },
            lastRememberedAnchor(book.uri),
        )
        dao.upsert(
            HistoryEntry(
                uri = book.uri,
                name = book.name,
                lastOpenedAt = System.currentTimeMillis(),
                chapterIndex = target?.chapterIndex ?: 0,
                paragraphIndex = target?.paragraphIndex ?: 0,
                charCount = book.charCount,
            ),
        )
    }

    private companion object {
        const val PREFS = "jiandu_reader"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_CHAPTER = "last_chapter"
        const val KEY_LAST_PARAGRAPH = "last_paragraph"

        /** 导入后预热首段的句数（端侧合成慢，只预热开头几句） */
        const val WARMUP_SENTENCES = 2

        /** 等端侧引擎真正就绪的重试次数与间隔（约 2 s 加载，留足余量） */
        const val WARMUP_RETRY_TIMES = 40
        const val WARMUP_RETRY_DELAY_MS = 500L

        /** SavedStateHandle 键：用于跨配置变更（旋转）保留阅读位置 */
        const val KEY_SAVED_CHAPTER = "saved_chapter"
        const val KEY_SAVED_PARAGRAPH = "saved_paragraph"
    }

    /** 取「更靠后」的阅读位置：先比章节，再比段落；都为空返回 null */
    private fun laterOf(vararg anchors: ReadingAnchor?): ReadingAnchor? =
        anchors.filterNotNull().maxWithOrNull(compareBy({ it.chapterIndex }, { it.paragraphIndex }))
}
