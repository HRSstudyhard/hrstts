package com.hrstts.jiandu.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextParserTest {

    private val parser = TextParser()

    @Test
    fun `按空行分段并识别章节标题`() {
        val raw = buildString {
            appendLine("第一章 少年")
            appendLine()
            appendLine("少年出发了。")
            appendLine()
            appendLine("第二章 归途")
            appendLine()
            appendLine("他回来了。")
        }

        val book = parser.parse(raw, "content://test/1", "测试.txt")

        assertEquals(2, book.chapters.size)
        assertEquals("第一章 少年", book.chapters[0].title)
        assertEquals("第二章 归途", book.chapters[1].title)
        assertEquals(1, book.chapters[0].paragraphs.size)
        assertEquals("少年出发了。", book.chapters[0].paragraphs[0].text)
        assertEquals("他回来了。", book.chapters[1].paragraphs[0].text)
    }

    @Test
    fun `无空行时每行视为一个段落`() {
        val raw = "第一章 少年\n少年出发了。\n他走了很远。\n第二章 归途\n他回来了。"

        val book = parser.parse(raw, "content://test/2", "测试.txt")

        assertEquals(2, book.chapters.size)
        assertEquals(2, book.chapters[0].paragraphs.size)
        assertEquals("他走了很远。", book.chapters[0].paragraphs[1].text)
    }

    @Test
    fun `首个标题之前的内容归入前言章节`() {
        val raw = "书名：测试小说\n作者：某人\n第一章 少年\n正文开始。"

        val book = parser.parse(raw, "content://test/3", "测试.txt")

        // 前言（无标题章节）+ 第一章 = 2 个章节
        assertEquals(2, book.chapters.size)
        assertEquals(null, book.chapters[0].title)
        assertEquals(2, book.chapters[0].paragraphs.size)
        assertEquals("第一章 少年", book.chapters[1].title)
        assertEquals(0, book.chapters[1].paragraphs[0].index)
    }

    @Test
    fun `超长段落按句末标点二次切分`() {
        val config = ParserConfig(maxParagraphChars = 50, paragraphMode = ParagraphMode.PER_LINE)
        val longLine = "一二三四五六七八九十。".repeat(10) // 110 字，每句 11 字
        val raw = "第一章 少年\n$longLine"

        val book = TextParser(config).parse(raw, "content://test/4", "测试.txt")
        val paragraphs = book.chapters[0].paragraphs

        assertEquals(3, paragraphs.size)
        assertTrue(paragraphs.all { it.text.length <= 50 })
        assertTrue(paragraphs.all { it.text.endsWith("。") })
        assertEquals(longLine, paragraphs.joinToString("") { it.text })
    }

    @Test
    fun `没有章节标题时按字数软分章`() {
        val config = ParserConfig(fallbackChapterChars = 50, paragraphMode = ParagraphMode.PER_LINE)
        val raw = (1..20).joinToString("\n") { "这是第${it}行的内容。" }

        val book = TextParser(config).parse(raw, "content://test/5", "测试.txt")

        assertTrue("章节数应大于 1，实际=${book.chapters.size}", book.chapters.size > 1)
        assertTrue(book.chapters.all { it.title?.startsWith("第 ") == true })
        assertEquals(20, book.chapters.sumOf { it.paragraphs.size })
    }

    @Test
    fun `章节标题判定不误伤正文`() {
        val cases = mapOf(
            "第一章 少年" to true,
            "第 12 回" to true,
            "第一百二十三章" to true,
            "Chapter 3: The Beginning" to true,
            "楔子" to true,
            "番外 一" to true,
            "第一章的教室里没有人。" to false,
            "他翻开了第二章，发现字迹模糊不清，于是放下书走了出去。" to false,
            "正文中间的一句话。" to false,
        )

        cases.forEach { (line, expected) ->
            assertEquals("「$line」判定错误", expected, parser.isChapterTitle(line))
        }
    }

    @Test
    fun `归一化统一换行符并去除BOM`() {
        val normalized = parser.normalize("\uFEFF第一章\r\n正文\r结束")

        assertFalse(normalized.startsWith("\uFEFF"))
        assertFalse(normalized.contains('\r'))
        assertEquals("第一章\n正文\n结束", normalized)
    }

    @Test
    fun `空白文本不会导致崩溃`() {
        val book = parser.parse("   \n\n  ", "content://test/6", "空.txt")

        assertEquals(1, book.chapters.size)
        assertEquals(0, book.paragraphCount)
        assertEquals(0, book.charCount)
    }

    // ---- P5 边界场景 ----

    @Test
    fun `空文件解析结果为空书且不崩溃`() {
        val book = parser.parse("", "content://test/empty", "空.txt")

        assertEquals(0, book.paragraphCount)
        assertEquals(0, book.charCount)
        // 界面据此显示「文件是空的」占位
        assertTrue(book.chapters.isEmpty() || book.chapters.all { it.paragraphs.isEmpty() })
    }

    @Test
    fun `只有 BOM 的文件内容为空`() {
        val book = parser.parse("\uFEFF", "content://test/bom", "bom.txt")

        assertEquals(0, book.charCount)
    }

    @Test
    fun `CRLF 与 LF 混排按同一段落规则切分`() {
        val raw = "第一章 少年\r\n少年出发了。\r\n他走了很远。\n他停了下来。"
        val config = ParserConfig(paragraphMode = ParagraphMode.PER_LINE)

        val book = TextParser(config).parse(raw, "content://test/crlf", "混排.txt")

        assertEquals(1, book.chapters.size)
        assertEquals(3, book.chapters[0].paragraphs.size)
        assertEquals("他停了下来。", book.chapters[0].paragraphs[2].text)
    }

    @Test
    fun `行尾空白与制表符被清理`() {
        val raw = "第一章 少年   \n正文一句话。\t\t"

        val book = parser.parse(raw, "content://test/trim", "trim.txt")

        assertEquals("正文一句话。", book.chapters[0].paragraphs[0].text)
    }

    @Test
    fun `英文 Chapter 标题同样可以分章`() {
        val raw = "Chapter 1: The Beginning\nHe left home.\nChapter 2\nHe came back."

        val book = parser.parse(raw, "content://test/en", "english.txt")

        assertEquals(2, book.chapters.size)
        assertEquals("Chapter 1: The Beginning", book.chapters[0].title)
        assertEquals("He came back.", book.chapters[1].paragraphs[0].text)
    }

    @Test
    fun `超长无标点文本按硬切分且不丢字`() {
        val config = ParserConfig(maxParagraphChars = 30, paragraphMode = ParagraphMode.PER_LINE)
        val raw = "abcdefghij".repeat(12) // 120 字符，无任何句末标点
        val book = TextParser(config).parse(raw, "content://test/hard", "hard.txt")

        val paragraphs = book.chapters[0].paragraphs
        assertTrue(paragraphs.size > 1)
        assertTrue(paragraphs.all { it.text.length <= 30 })
        assertEquals(raw, paragraphs.joinToString("") { it.text })
    }

    @Test
    fun `纯空行文件不产生段落但保留章节占位`() {
        val raw = "\n\n\n\n"

        val book = parser.parse(raw, "content://test/blank", "blank.txt")

        assertEquals(0, book.paragraphCount)
        assertTrue(book.chapters.size <= 1)
    }
}
