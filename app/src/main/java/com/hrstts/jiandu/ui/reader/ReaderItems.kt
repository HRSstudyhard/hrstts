package com.hrstts.jiandu.ui.reader

import com.hrstts.jiandu.domain.model.Book
import com.hrstts.jiandu.domain.model.Paragraph

/** 阅读区扁平列表项（章节标题 / 段落） */
sealed interface ReaderItem {
    val key: String

    data class ChapterHeader(val chapterIndex: Int, val title: String) : ReaderItem {
        override val key: String get() = "ch-$chapterIndex"
    }

    data class ParagraphItem(val chapterIndex: Int, val paragraph: Paragraph) : ReaderItem {
        override val key: String get() = "p-$chapterIndex-${paragraph.index}"
    }
}

object ReaderItems {

    fun build(book: Book): List<ReaderItem> = buildList {
        book.chapters.forEach { chapter ->
            chapter.title?.let { add(ReaderItem.ChapterHeader(chapter.index, it)) }
            chapter.paragraphs.forEach { add(ReaderItem.ParagraphItem(chapter.index, it)) }
        }
    }

    /** 找到某个段落对应的列表下标（用于朗读时自动滚动） */
    fun indexOfParagraph(items: List<ReaderItem>, chapterIndex: Int, paragraphIndex: Int): Int =
        items.indexOfFirst { item ->
            item is ReaderItem.ParagraphItem &&
                item.chapterIndex == chapterIndex &&
                item.paragraph.index == paragraphIndex
        }
}
