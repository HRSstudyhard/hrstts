package com.hrstts.jiandu.domain.parser

import com.hrstts.jiandu.domain.model.Book
import com.hrstts.jiandu.domain.model.Chapter
import com.hrstts.jiandu.domain.model.Paragraph

/** 段落切分模式 */
enum class ParagraphMode {
    /** 自动判断：文本中空行较多时按空行分段，否则每行视为一段（中文网文常见排版） */
    AUTO,

    /** 每个非空行视为一个段落 */
    PER_LINE,

    /** 以空行作为段落分隔（段内换行保留） */
    BLANK_LINE,
}

data class ParserConfig(
    val paragraphMode: ParagraphMode = ParagraphMode.AUTO,
    /** 单段最大字符数，超过则按句末标点二次切分 */
    val maxParagraphChars: Int = 500,
    /** 未识别到章节标题时，按该字数软分章 */
    val fallbackChapterChars: Int = 8_000,
    /** AUTO 模式下判定「空行分段」的空行占比阈值 */
    val blankLineRatioThreshold: Double = 0.1,
    /** 章节标题行的最大长度（超过则不视为标题） */
    val maxChapterTitleLength: Int = 40,
)

/**
 * 文本解析器：把任意纯文本切分为「章节 → 段落」。
 *
 * 设计要点：
 * 1. 纯 Kotlin 实现（无 Android 依赖），可在 JVM 单元测试中直接验证；
 * 2. 章节识别基于标题行正则，识别不到时按字数软分章，保证长文永远可用；
 * 3. 段落切分对「空行分段」与「每行一段」两种主流排版自适应；
 * 4. 超长段落按句末标点二次切分，避免单段朗读/渲染过大。
 */
class TextParser(private val config: ParserConfig = ParserConfig()) {

    fun parse(
        raw: String,
        uri: String,
        name: String,
        charsetName: String = "UTF-8",
        openedAt: Long = System.currentTimeMillis(),
    ): Book {
        val text = normalize(raw)
        val lines = text.split('\n')
        val lineOffsets = buildLineOffsets(lines)
        val paragraphsPerMode = resolveParagraphMode(lines)

        val headings = lines.indices.filter { isChapterTitle(lines[it]) }

        val chapters = if (headings.isEmpty()) {
            val paragraphs = buildParagraphs(lines, lineOffsets, 0, lines.size, paragraphsPerMode)
            fallbackSplit(paragraphs, titlePrefix = "第 %d 节")
        } else {
            buildList {
                val first = headings.first()
                if (first > 0) {
                    val head = buildParagraphs(lines, lineOffsets, 0, first, paragraphsPerMode)
                    if (head.isNotEmpty()) add(Chapter(index = 0, title = null, paragraphs = head))
                }
                headings.forEachIndexed { i, start ->
                    val end = if (i + 1 < headings.size) headings[i + 1] else lines.size
                    val title = lines[start].trim()
                    val body = buildParagraphs(lines, lineOffsets, start + 1, end, paragraphsPerMode)
                    add(Chapter(index = size, title = title, paragraphs = body))
                }
            }.let { raw -> reindex(raw) }
        }

        return Book(
            uri = uri,
            name = name,
            charsetName = charsetName,
            chapters = chapters,
            openedAt = openedAt,
        )
    }

    /**
     * 归一化：统一换行符（CRLF/CR → LF）、去除 BOM、去除每行行尾空白。
     *
     * 实现上刻意用**单次线性扫描**而不是 `split('\n')` + `joinToString`：
     * 20 MB 中文文本约有 700 万行（每段一行），split 会产生 700 万个 String 对象，
     * 实测是超大文件导入耗时的主要来源。
     */
    internal fun normalize(raw: String): String {
        val text = raw.removePrefix("\uFEFF")
        val out = StringBuilder(text.length)
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            val ch = if (i < text.length) text[i] else '\n'
            if (ch == '\n' || ch == '\r') {
                // 行尾空白（空格 / 制表符 / 全角空格）不保留
                var end = i
                while (end > lineStart && text[end - 1].isWhitespace()) end--
                out.append(text, lineStart, end)
                if (i < text.length) out.append('\n')
                // CRLF 视为一个换行
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                lineStart = i + 1
            }
            i++
        }
        return out.toString()
    }

    /**
     * 章节标题判定：形如「第一章」「第 12 回」「Chapter 3」「楔子」「番外 一」等。
     * 为避免误判正文，要求整行较短且匹配后剩余内容极少。
     */
    internal fun isChapterTitle(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > config.maxChapterTitleLength) return false
        return chapterTitleRegex.matches(trimmed)
    }

    private fun resolveParagraphMode(lines: List<String>): ParagraphMode = when (config.paragraphMode) {
        ParagraphMode.AUTO -> {
            val nonEmpty = lines.count { it.isNotBlank() }
            if (nonEmpty == 0) ParagraphMode.PER_LINE else {
                val blanks = lines.size - nonEmpty
                if (blanks.toDouble() / nonEmpty >= config.blankLineRatioThreshold) {
                    ParagraphMode.BLANK_LINE
                } else {
                    ParagraphMode.PER_LINE
                }
            }
        }

        else -> config.paragraphMode
    }

    private fun buildLineOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            offsets[i] = acc
            acc += lines[i].length + 1 // + '\n'
        }
        return offsets
    }

    private fun buildParagraphs(
        lines: List<String>,
        lineOffsets: IntArray,
        fromIndex: Int,
        toIndex: Int,
        mode: ParagraphMode,
    ): List<Paragraph> {
        val result = mutableListOf<Paragraph>()
        var index = 0

        fun emit(text: String, startOffset: Int) {
            splitLongText(text, startOffset).forEach { (piece, offset) ->
                val trimmed = piece.trim()
                if (trimmed.isNotEmpty()) {
                    result.add(Paragraph(index = index++, text = trimmed, startOffset = offset))
                }
            }
        }

        when (mode) {
            ParagraphMode.PER_LINE -> {
                for (i in fromIndex until toIndex) {
                    val line = lines[i]
                    if (line.isBlank()) continue
                    emit(line, lineOffsets[i])
                }
            }

            ParagraphMode.BLANK_LINE, ParagraphMode.AUTO -> {
                var buffer = StringBuilder()
                var bufferStart = -1
                for (i in fromIndex until toIndex) {
                    val line = lines[i]
                    if (line.isBlank()) {
                        if (buffer.isNotEmpty()) {
                            emit(buffer.toString(), if (bufferStart >= 0) bufferStart else lineOffsets[i])
                            buffer = StringBuilder()
                            bufferStart = -1
                        }
                        continue
                    }
                    if (buffer.isEmpty()) bufferStart = lineOffsets[i]
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line)
                }
                if (buffer.isNotEmpty()) emit(buffer.toString(), if (bufferStart >= 0) bufferStart else 0)
            }
        }
        return result
    }

    /** 超长段落按句末标点二次切分，返回（片段, 起始偏移） */
    private fun splitLongText(text: String, startOffset: Int): List<Pair<String, Int>> {
        if (text.length <= config.maxParagraphChars) return listOf(text to startOffset)

        val result = mutableListOf<Pair<String, Int>>()
        var segmentStart = 0
        var lastBreak = -1

        fun flush(endExclusive: Int) {
            val piece = text.substring(segmentStart, endExclusive)
            if (piece.isNotBlank()) result.add(piece to startOffset + segmentStart)
            segmentStart = endExclusive
        }

        for (i in text.indices) {
            val ch = text[i]
            if (ch in sentenceEndChars) lastBreak = i + 1
            val length = i + 1 - segmentStart
            if (length >= config.maxParagraphChars) {
                if (lastBreak > segmentStart) flush(lastBreak) else flush(i + 1)
                lastBreak = -1
            }
        }
        if (segmentStart < text.length) {
            val piece = text.substring(segmentStart)
            if (piece.isNotBlank()) result.add(piece to startOffset + segmentStart)
        }
        return result
    }

    /** 标题缺失时按字数软分章 */
    private fun fallbackSplit(paragraphs: List<Paragraph>, titlePrefix: String): List<Chapter> {
        if (paragraphs.isEmpty()) return listOf(Chapter(index = 0, title = null, paragraphs = emptyList()))

        val total = paragraphs.sumOf { it.text.length }
        if (total <= config.fallbackChapterChars) {
            return listOf(Chapter(index = 0, title = null, paragraphs = paragraphs))
        }

        val chunks = mutableListOf<List<Paragraph>>()
        var current = mutableListOf<Paragraph>()
        var currentChars = 0
        for (paragraph in paragraphs) {
            current.add(paragraph)
            currentChars += paragraph.text.length
            if (currentChars >= config.fallbackChapterChars) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
        }
        if (current.isNotEmpty()) chunks.add(current)

        return chunks.mapIndexed { i, chunk ->
            Chapter(
                index = i,
                title = titlePrefix.format(i + 1),
                paragraphs = reindexParagraphs(chunk),
            )
        }
    }

    private fun reindex(chapters: List<Chapter>): List<Chapter> =
        chapters.mapIndexed { i, chapter ->
            chapter.copy(index = i, paragraphs = reindexParagraphs(chapter.paragraphs))
        }

    private fun reindexParagraphs(paragraphs: List<Paragraph>): List<Paragraph> =
        paragraphs.mapIndexed { i, paragraph -> paragraph.copy(index = i) }

    private companion object {
        val sentenceEndChars = charArrayOf('。', '！', '？', '…', '”', '』', '」', '.', '!', '?', ';', '；')

        /**
         * 章节标题：关键词 + 「行尾」或「分隔符（空白/冒号/顿号等）+ 最多 20 字标题」。
         * 要求分隔符存在，可避免把「第一章的教室里……」这类正文误判为标题。
         */
        val chapterTitleRegex = Regex(
            "^(?:第\\s*[0-9零一二三四五六七八九十百千万两〇]+\\s*[章节回卷集部篇幕]" +
                "|Chapter\\s+[0-9IVXLCivxlc]+|CHAPTER\\s+[0-9IVXLCivxlc]+" +
                "|楔子|序章|序言|序幕|前言|引子|后记|尾声|终章|番外)" +
                "(?:$|\\s+.{0,20}$|[：:、，,.\\-—－].{0,20}$)",
        )
    }
}
