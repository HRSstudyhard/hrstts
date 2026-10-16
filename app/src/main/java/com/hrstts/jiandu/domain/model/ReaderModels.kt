package com.hrstts.jiandu.domain.model

import androidx.compose.runtime.Immutable

/** 一次导入的文本 */
@Immutable
data class Book(
    val uri: String,
    val name: String,
    val charsetName: String = "UTF-8",
    val chapters: List<Chapter> = emptyList(),
    val openedAt: Long = 0L,
) {
    val paragraphCount: Int get() = chapters.sumOf { it.paragraphs.size }
    val charCount: Int get() = chapters.sumOf { chapter -> chapter.paragraphs.sumOf { it.text.length } }
}

/** 章节 */
@Immutable
data class Chapter(
    val index: Int,
    val title: String?,
    val paragraphs: List<Paragraph> = emptyList(),
)

/** 段落 */
@Immutable
data class Paragraph(
    val index: Int,
    val text: String,
    val startOffset: Int = 0,
)

/** 阅读进度定位 */
@Immutable
data class ReadingAnchor(
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
)

/** 段落选中信息：长按选中的句子或整段（需求 3、4） */
@Immutable
data class ParagraphSelection(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    /** 段内起始偏移 */
    val start: Int,
    /** 段内结束偏移（不含） */
    val end: Int,
    /** 选中的文本 */
    val text: String,
) {
    val range: IntRange get() = start until end
}
