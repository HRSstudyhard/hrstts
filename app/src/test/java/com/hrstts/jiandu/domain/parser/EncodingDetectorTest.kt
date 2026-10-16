package com.hrstts.jiandu.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingDetectorTest {

    @Test
    fun `UTF8 无 BOM 文本被识别为 UTF-8`() {
        val bytes = "第一章 少年".toByteArray(Charsets.UTF_8)

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.UTF8, result.charset)
        assertEquals(0, result.bomLength)
    }

    @Test
    fun `UTF8 BOM 文本识别并给出 BOM 长度`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "正文".toByteArray(Charsets.UTF_8)

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.UTF8, result.charset)
        assertEquals(3, result.bomLength)
    }

    @Test
    fun `GBK 文本被识别为 GB18030`() {
        val bytes = "第一章 少年".toByteArray(EncodingDetector.GB18030)

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.GB18030, result.charset)
        assertEquals("第一章 少年", String(bytes, result.charset))
    }

    @Test
    fun `UTF16 小端文本被识别`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + "正文".toByteArray(Charsets.UTF_16LE)

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.UTF16_LE, result.charset)
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `UTF16 大端文本被识别`() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val bytes = bom + "正文".toByteArray(Charsets.UTF_16BE)

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.UTF16_BE, result.charset)
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `严格校验能区分合法与非法 UTF8 字节序列`() {
        val validUtf8 = "中文测试内容".toByteArray(Charsets.UTF_8)

        assertEquals(true, EncodingDetector.isValid(validUtf8, validUtf8.size, EncodingDetector.UTF8))
        assertEquals(false, EncodingDetector.isValid(byteArrayOf(0xC3.toByte(), 0x28), 2, EncodingDetector.UTF8))
        assertEquals(false, EncodingDetector.isValid(byteArrayOf(0xC3.toByte(), 0x28), 2, EncodingDetector.GB18030))
    }

    // ---- P5 边界场景：损坏/混合字节的文本 ----

    @Test
    fun `GBK 文本夹带损坏字节时仍按 GB18030 解码`() {
        // 合法 GBK 中文 + 一个孤立的高位字节：严格 UTF-8 与严格 GB18030 都会失败，
        // 若不启用启发式就会按 UTF-8 强行解码，整篇变乱码。
        val bytes = "中文测试内容".toByteArray(EncodingDetector.GB18030) + byteArrayOf(0xFF.toByte())

        val result = EncodingDetector.detect(bytes)

        assertEquals(EncodingDetector.GB18030, result.charset)
        val text = String(bytes, result.charset)
        assertTrue("损坏字节之外的内容应保持可读", text.startsWith("中文测试内容"))
    }

    @Test
    fun `孤立高位字节的二进制内容不会被误判为 GBK`() {
        // 0xC3 0x28 的第二个字节不是合法 GBK 尾字节，配对比例 0 → 仍回退 UTF-8
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0x41, 0x42)

        assertFalse(EncodingDetector.looksLikeGbk(bytes))
    }

    @Test
    fun `纯 ASCII 文本判定为不是 GBK`() {
        assertFalse(EncodingDetector.looksLikeGbk("Chapter 1 hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `合法 GBK 双字节序列被启发式认可`() {
        val bytes = "野草与少年".toByteArray(EncodingDetector.GB18030)

        assertTrue(EncodingDetector.looksLikeGbk(bytes))
    }

    @Test
    fun `UTF8 中文文本优先识别为 UTF-8 而不会走到 GBK 启发式`() {
        val bytes = "荒野的呼唤，第一章".toByteArray(Charsets.UTF_8)

        assertEquals(EncodingDetector.UTF8, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `detect 支持限定读取长度`() {
        val bytes = "正文".toByteArray(Charsets.UTF_8) + "乱码".toByteArray(EncodingDetector.GB18030)

        // 只探测前一段（合法 UTF-8）时应判定 UTF-8，说明 length 参数生效
        val head = "正文".toByteArray(Charsets.UTF_8).size
        assertEquals(EncodingDetector.UTF8, EncodingDetector.detect(bytes, head).charset)
    }

    // ---- P5 实测缺陷回归：超大文件按字节截断后不能变成乱码 ----

    @Test
    fun `UTF8 文本被截断半个汉字时仍判定为 UTF-8`() {
        val full = "夜色渐深，他把书合上又打开。".toByteArray(Charsets.UTF_8)
        // 砍掉最后一个汉字的 1、2 个字节，模拟 20 MB 上限处的截断
        for (cut in 1..2) {
            val truncated = full.copyOfRange(0, full.size - cut)

            val utf8Ok = EncodingDetector.isValid(truncated, truncated.size, EncodingDetector.UTF8)
            val gbkOk = EncodingDetector.isValid(truncated, truncated.size, EncodingDetector.GB18030)
            val truncatedLook = EncodingDetector.looksLikeUtf8Truncated(truncated)
            val gbkLook = EncodingDetector.looksLikeGbk(truncated)
            val detected = EncodingDetector.detect(truncated).charset
            val info = "cut=$cut utf8Valid=$utf8Ok gb18030Valid=$gbkOk utf8Truncated=$truncatedLook gbkHeuristic=$gbkLook detected=${detected.name()}"

            assertEquals(info, EncodingDetector.UTF8, detected)
        }
    }

    @Test
    fun `截断后的 UTF8 文本按 UTF-8 解码只有末尾一个替换字符`() {
        val full = "第一章 少年出发了，他走了很远很远的路。".toByteArray(Charsets.UTF_8)
        val truncated = full.copyOfRange(0, full.size - 2)

        val charset = EncodingDetector.detect(truncated).charset
        val text = String(truncated, charset)

        assertEquals(EncodingDetector.UTF8, charset)
        assertEquals(1, text.count { it == '\uFFFD' })
        assertTrue(text.startsWith("第一章 少年出发了"))
    }

    @Test
    fun `真正的 GBK 文本不会被误判为截断的 UTF8`() {
        val gbk = "夜色渐深，他把书合上又打开。".toByteArray(EncodingDetector.GB18030)

        assertFalse(EncodingDetector.looksLikeUtf8Truncated(gbk))
        assertEquals(EncodingDetector.GB18030, EncodingDetector.detect(gbk).charset)
    }

    @Test
    fun `带损坏字节的 GBK 文本仍走 GBK 启发式（不被 UTF8 截断规则抢走）`() {
        val bytes = "中文测试内容".toByteArray(EncodingDetector.GB18030) + byteArrayOf(0xFF.toByte())

        assertFalse(EncodingDetector.looksLikeUtf8Truncated(bytes))
        assertEquals(EncodingDetector.GB18030, EncodingDetector.detect(bytes).charset)
    }

    @Test
    fun `截断到只剩续接字节时不崩溃`() {
        // 极端情况：字节流全部是续接字节（0x80~0xBF）
        val odd = byteArrayOf(0x80.toByte(), 0xBF.toByte(), 0x80.toByte())

        assertFalse(EncodingDetector.looksLikeUtf8Truncated(odd))
        assertFalse(EncodingDetector.looksLikeUtf8Truncated(ByteArray(0)))
    }
}
