package com.hrstts.jiandu.domain.tts

import com.hrstts.jiandu.domain.model.Book

/**
 * 朗读片段：以「句子」为最小朗读单位。
 *
 * 相比整段朗读，句子级片段可以做到：
 *  - 正文高亮跟随朗读进度；
 *  - 暂停后从当前句（而非整段）继续；
 *  - 与 Qwen3-TTS 的流式合成粒度对齐（阶段 P3）。
 */
data class ReadingSegment(
    val key: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val text: String,
    /** 该句在所属段落内的起始字符下标 */
    val charStart: Int,
) {
    /** 该句在段落文本中的范围 */
    val range: IntRange get() = charStart until (charStart + text.length)
}

object ReadingSegments {

    private val sentenceEndChars = charArrayOf('。', '！', '？', '；', '…', '!', '?', ';')
    private val trailingChars = charArrayOf('”', '』', '」', '）', ')', '"', '\'')

    /** 片段 key 生成规则：章节-段落-句子 */
    fun key(chapterIndex: Int, paragraphIndex: Int, sentenceIndex: Int): String =
        "$chapterIndex-$paragraphIndex-$sentenceIndex"

    /** 段落 key 前缀，用于判断「某段落是否正在朗读」 */
    fun paragraphPrefix(chapterIndex: Int, paragraphIndex: Int): String = "$chapterIndex-$paragraphIndex-"

    /**
     * 把段落文本切分为句子，返回 (段内起始偏移, 句子文本) 列表。
     * 过短的碎片会与其前一句合并，避免逐字碎读。
     */
    fun sentences(text: String): List<Pair<Int, String>> {
        if (text.isBlank()) return emptyList()

        val raw = mutableListOf<Pair<Int, String>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in sentenceEndChars) {
                var end = i + 1
                while (end < text.length && text[end] in trailingChars) end++
                val piece = text.substring(start, end)
                if (piece.isNotBlank()) raw.add(start to piece)
                start = end
                i = end
                continue
            }
            i++
        }
        if (start < text.length) {
            val piece = text.substring(start)
            if (piece.isNotBlank()) raw.add(start to piece)
        }

        // 合并过短片段
        val merged = mutableListOf<Pair<Int, String>>()
        for (item in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && (previous.second.length < MIN_SENTENCE_CHARS || item.second.length < MIN_SENTENCE_CHARS)) {
                merged[merged.lastIndex] = previous.first to (previous.second + item.second)
            } else {
                merged.add(item)
            }
        }
        return merged
    }

    /**
     * 由书籍构建朗读片段序列。
     *
     * @param startChapter 起始章节下标（含）
     * @param startParagraph 起始段落下标（含，仅对起始章节生效）
     * @param startSentence 起始句子下标（含，仅对起始段落生效）
     */
    fun fromBook(
        book: Book,
        startChapter: Int = 0,
        startParagraph: Int = 0,
        startSentence: Int = 0,
    ): List<ReadingSegment> {
        val result = mutableListOf<ReadingSegment>()
        book.chapters.forEach { chapter ->
            if (chapter.index < startChapter) return@forEach
            val isStartChapter = chapter.index == startChapter
            chapter.paragraphs.forEach { paragraph ->
                if (isStartChapter && paragraph.index < startParagraph) return@forEach
                val isStartParagraph = isStartChapter && paragraph.index == startParagraph
                sentences(paragraph.text).forEachIndexed { sentenceIndex, (offset, sentenceText) ->
                    if (isStartParagraph && sentenceIndex < startSentence) return@forEachIndexed
                    result.add(
                        ReadingSegment(
                            key = key(chapter.index, paragraph.index, sentenceIndex),
                            chapterIndex = chapter.index,
                            paragraphIndex = paragraph.index,
                            sentenceIndex = sentenceIndex,
                            text = sentenceText,
                            charStart = offset,
                        ),
                    )
                }
            }
        }
        return result
    }

    /** 单段文本的朗读片段（长按朗读「本段」时使用） */
    fun fromParagraph(
        chapterIndex: Int,
        paragraphIndex: Int,
        text: String,
    ): List<ReadingSegment> = sentences(text).mapIndexed { sentenceIndex, (offset, sentenceText) ->
        ReadingSegment(
            key = key(chapterIndex, paragraphIndex, sentenceIndex),
            chapterIndex = chapterIndex,
            paragraphIndex = paragraphIndex,
            sentenceIndex = sentenceIndex,
            text = sentenceText,
            charStart = offset,
        )
    }

    private const val MIN_SENTENCE_CHARS = 4
}
