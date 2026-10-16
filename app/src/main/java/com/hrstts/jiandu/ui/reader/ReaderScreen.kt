package com.hrstts.jiandu.ui.reader

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.model.Book
import com.hrstts.jiandu.domain.model.Paragraph
import com.hrstts.jiandu.domain.model.ParagraphSelection
import com.hrstts.jiandu.domain.model.ReadingAnchor
import com.hrstts.jiandu.domain.tts.ReadingSegments
import com.hrstts.jiandu.domain.tts.TtsUiState
import com.hrstts.jiandu.ui.theme.ChapterTitleStyle
import com.hrstts.jiandu.ui.theme.ReadingBodyStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 阅读区对外动作集合 */
data class ReaderActions(
    val onSelect: (ParagraphSelection) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onReadSelection: () -> Unit = {},
    val onExpandToParagraph: () -> Unit = {},
    val onTogglePlayback: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRateChange: (Float) -> Unit = {},
    val onVisibleAnchorChanged: (chapterIndex: Int, paragraphIndex: Int) -> Unit = { _, _ -> },
    /** 恢复落位完成（见 ReaderViewModel.suppressVisibleAnchorReports） */
    val onInitialPositionSettled: () -> Unit = {},
)

/** 落位后多久才允许上报「当前可见段落」（跳过瞬态的第 0 项读数） */
private const val SETTLE_DELAY_MS = 400L

/**
 * 阅读区正文。
 *
 * 阶段 P1：章节 + 段落渲染，导入后从文件头开始显示。
 * 阶段 P2：段落长按选中 → 选中框上方出现「阅读」工具栏；朗读时逐句高亮并自动滚动。
 * 阶段 P4：支持从「我的」切换文本后恢复上次阅读位置，并回传当前可见段落用于保存进度。
 */
@Composable
fun ReaderScreen(
    book: Book,
    selection: ParagraphSelection?,
    ttsState: TtsUiState,
    actions: ReaderActions,
    pendingAnchor: ReadingAnchor? = null,
    modifier: Modifier = Modifier,
) {
    // 空文件（或只有空白）：没有段落可读，给出明确占位而不是一片空白
    if (book.paragraphCount == 0) {
        ReaderEmptyFileState(modifier = modifier)
        return
    }

    val listState = rememberLazyListState()
    val items = remember(book) { ReaderItems.build(book) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    /**
     * 当前**希望停在**的阅读位置。
     *
     * - `pendingAnchor`（来自 ViewModel）：换书/恢复上次阅读位置时给出；
     * - 用户滚动后由下面的上报 effect 更新。
     *
     * 之所以要自己记一份，是因为配置变更（旋转/分屏）后列表项的宽度与行高都会变，
     * `scrollToItem(index)` 在旧布局下算出的位置会被随后的重新测量顶掉——必须按
     * **段落锚点**在每次布局稳定后重新落位（见下面的 `layoutEpoch`）。
     */
    var desiredAnchor by remember(book.uri) { mutableStateOf(pendingAnchor) }
    var userScrolled by remember(book.uri) { mutableStateOf(false) }
    // ViewModel 后续给出新的恢复位置（换书 / 从历史切换）时，只要用户还没有滚动过就采纳
    LaunchedEffect(pendingAnchor) {
        if (pendingAnchor != null && !userScrolled) desiredAnchor = pendingAnchor
    }

    // 列表尺寸变化（旋转/分屏/窗口调整）会让已算好的滚动位置失效，用 epoch 触发重新落位
    var layoutEpoch by remember(book.uri) { mutableStateOf(0) }
    val listSize = listState.layoutInfo.viewportSize
    LaunchedEffect(listSize.width, listSize.height) {
        if (listSize.width > 0 && listSize.height > 0) layoutEpoch++
    }

    // 本次 composition 里是否已经处理过「换书后回文首」；用于区分「新书」与「同一本书重排/重建」
    var initialScrollDone by remember(book.uri) { mutableStateOf(false) }

    // 等首次布局完成后再落位；每次布局 epoch 变化都重新落位一次
    LaunchedEffect(book.uri, layoutEpoch, items) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.filter { it > 0 }.first()
        val anchor = desiredAnchor
        val target = if (anchor == null) {
            -1
        } else {
            ReaderItems.indexOfParagraph(items, anchor.chapterIndex, anchor.paragraphIndex)
        }
        when {
            // 有明确锚点：滚到该段落（横竖屏行高不同，每次重排后都要重做）
            anchor != null && target >= 0 -> listState.scrollToItem(target)
            // 没有锚点且是本次新书的第一次落位：回到文首
            anchor == null && !initialScrollDone -> {
                initialScrollDone = true
                listState.scrollToItem(0)
            }
            // 其余情况（旋转/分屏重排、且 ViewModel 还没给出恢复锚点）保持原位置不动
            else -> Unit
        }
    }

    /**
     * 回传当前可见段落（用于保存阅读进度）。
     *
     * 落位瞬间列表还会报一次旧的 0-0（新列表初始位置），若直接上报就会把刚恢复的
     * 阅读位置覆盖掉；但也不能死等「下一次变化」——用户落位后不再滚动时 snapshotFlow
     * 不会再发射，抑制就永远解除不了（实测：进度完全不落盘，冷启动只能回到文首）。
     * 因此用「[SETTLE_DELAY_MS] 内位置不再变化」作为解除条件：
     *  - 落位后马上又变（说明恢复/布局还在进行）→ 重置计时，继续抑制；
     *  - 位置稳定超过 [SETTLE_DELAY_MS] → 解除抑制并上报当前位置。
     */
    LaunchedEffect(listState, items) {
        var settled = false
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { index ->
            if (!settled) {
                // 若在等待期间位置又变了，collectLatest 会取消本次等待并从头计时
                kotlinx.coroutines.delay(SETTLE_DELAY_MS)
                settled = true
                actions.onInitialPositionSettled()
            }
            when (val item = items.getOrNull(index)) {
                is ReaderItem.ParagraphItem -> {
                    userScrolled = true
                    desiredAnchor = ReadingAnchor(item.chapterIndex, item.paragraph.index)
                    actions.onVisibleAnchorChanged(item.chapterIndex, item.paragraph.index)
                }

                is ReaderItem.ChapterHeader -> {
                    userScrolled = true
                    desiredAnchor = ReadingAnchor(item.chapterIndex, 0)
                    actions.onVisibleAnchorChanged(item.chapterIndex, 0)
                }

                null -> Unit
            }
        }
    }

    // 朗读时自动滚动到当前段落（若不在可见范围内）
    val activeParagraph = remember(ttsState.activeSegmentKey) {
        ttsState.activeSegmentKey?.split('-')?.takeIf { it.size >= 2 }?.let { parts ->
            parts[0].toIntOrNull() to parts[1].toIntOrNull()
        }
    }
    LaunchedEffect(activeParagraph) {
        val (chapterIndex, paragraphIndex) = activeParagraph ?: return@LaunchedEffect
        if (chapterIndex == null || paragraphIndex == null) return@LaunchedEffect
        val target = ReaderItems.indexOfParagraph(items, chapterIndex, paragraphIndex)
        if (target < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val isVisible = visible.any { it.index == target }
        if (!isVisible) {
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(items = items, key = { _, item -> item.key }) { _, item ->
                when (item) {
                    is ReaderItem.ChapterHeader -> ChapterHeader(
                        index = item.chapterIndex,
                        title = item.title,
                    )

                    is ReaderItem.ParagraphItem -> ParagraphRow(
                        chapterIndex = item.chapterIndex,
                        paragraph = item.paragraph,
                        selection = selection?.takeIf {
                            it.chapterIndex == item.chapterIndex && it.paragraphIndex == item.paragraph.index
                        },
                        ttsState = ttsState,
                        onSelect = actions.onSelect,
                        onClearSelection = actions.onClearSelection,
                        onReadSelection = actions.onReadSelection,
                        onExpandToParagraph = actions.onExpandToParagraph,
                        onCopy = { text ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("jiandu", text)))
                            }
                        },
                    )
                }
            }
        }

        TtsControlBar(
            state = ttsState,
            onTogglePlayback = actions.onTogglePlayback,
            onPrevious = actions.onPrevious,
            onNext = actions.onNext,
            onStop = actions.onStop,
            onRateChange = actions.onRateChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun ChapterHeader(index: Int, title: String) {
    Column(
        modifier = Modifier
            .padding(top = if (index == 0) 8.dp else 24.dp)
            // 让 TalkBack 把章节标题当作「标题」朗读，可快速跳转章节
            .semantics { heading() },
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = title,
            style = ChapterTitleStyle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 空文件占位（导入成功但没有任何文字） */
@Composable
private fun ReaderEmptyFileState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Article,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.reader_empty_file_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.reader_empty_file_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 单个段落：长按选中句子 → 上方出现「阅读」工具栏；朗读中的句子高亮。
 */
@Composable
private fun ParagraphRow(
    chapterIndex: Int,
    paragraph: Paragraph,
    selection: ParagraphSelection?,
    ttsState: TtsUiState,
    onSelect: (ParagraphSelection) -> Unit,
    onClearSelection: () -> Unit,
    onReadSelection: () -> Unit,
    onExpandToParagraph: () -> Unit,
    onCopy: (String) -> Unit,
) {
    var layoutResult by remember(paragraph.text) { mutableStateOf<TextLayoutResult?>(null) }
    val paragraphPrefix = ReadingSegments.paragraphPrefix(chapterIndex, paragraph.index)
    val isSpeakingParagraph = ttsState.activeSegmentKey?.startsWith(paragraphPrefix) == true
    val paragraphHint = stringResource(R.string.reader_paragraph_hint, paragraph.index + 1)

    val style = ReadingBodyStyle.copy(
        textIndent = TextIndent(firstLine = ReadingBodyStyle.fontSize * 2),
    )

    val annotated = remember(paragraph.text, selection?.range, ttsState.activeRange, isSpeakingParagraph) {
        buildAnnotatedString {
            append(paragraph.text)
            // 选中高亮（选中框）
            selection?.range?.let { range ->
                val start = range.first.coerceIn(0, paragraph.text.length)
                val end = (range.last + 1).coerceIn(start, paragraph.text.length)
                if (end > start) {
                    addStyle(
                        SpanStyle(background = androidx.compose.ui.graphics.Color(0x33FFB74D)),
                        start,
                        end,
                    )
                }
            }
            // 朗读中高亮
            if (isSpeakingParagraph) {
                ttsState.activeRange?.let { range ->
                    val start = range.first.coerceIn(0, paragraph.text.length)
                    val end = (range.last + 1).coerceIn(start, paragraph.text.length)
                    if (end > start) {
                        addStyle(
                            SpanStyle(
                                background = androidx.compose.ui.graphics.Color(0x556750A4),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            ),
                            start,
                            end,
                        )
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (selection != null) {
            // 选中框上方出现「阅读」等操作
            SelectionToolbar(
                onRead = onReadSelection,
                onExpandToParagraph = onExpandToParagraph,
                onCopy = { onCopy(selection.text) },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Text(
            text = annotated,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                // 无障碍：说明「长按可选中并朗读」，否则 TalkBack 只会念正文
                .semantics {
                    contentDescription = paragraphHint
                }
                .background(
                    if (isSpeakingParagraph) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                )
                .pointerInput(paragraph.text) {
                    detectTapGestures(
                        onLongPress = { position ->
                            val layout = layoutResult
                            if (layout != null) {
                                val offset = layout.getOffsetForPosition(position)
                                    .coerceIn(0, paragraph.text.length)
                                val range = sentenceRangeAt(paragraph.text, offset)
                                onSelect(
                                    ParagraphSelection(
                                        chapterIndex = chapterIndex,
                                        paragraphIndex = paragraph.index,
                                        start = range.first,
                                        end = range.last + 1,
                                        text = paragraph.text.substring(range.first, range.last + 1),
                                    ),
                                )
                            }
                        },
                        onTap = { onClearSelection() },
                    )
                },
        )
    }
}

/** 找出包含指定字符偏移的句子范围（段内下标），用于长按选中整句 */
private fun sentenceRangeAt(text: String, offset: Int): IntRange {
    val sentences = ReadingSegments.sentences(text)
    if (sentences.isEmpty()) return 0 until text.length
    val safeOffset = offset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    sentences.forEach { (start, sentenceText) ->
        val end = start + sentenceText.length
        if (safeOffset in start until end) return start until end
    }
    val (start, sentenceText) = sentences.last()
    return start until (start + sentenceText.length)
}
