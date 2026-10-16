package com.hrstts.jiandu.domain.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 纯文本编码探测：BOM → UTF-8 严格校验 → GB18030 严格校验 → GBK 字节结构启发式。
 *
 * 中文文本文件常见 UTF-8 与 GBK/GB18030 两种编码，且用户往往无法自行判断，
 * 因此导入时必须自动识别。最后一级启发式用于「文件里混了少量损坏字节」的情况：
 * 此时严格校验会全部失败，若不处理就会按 UTF-8 强行解码，整篇变成乱码（每个坏字节一个 ）。
 * 只要绝大多数非 ASCII 字节组成合法的 GBK 双字节序列，就按 GB18030 解码，
 * 乱码范围被限制在真正损坏的那几个字节上。
 */
object EncodingDetector {

    val UTF8: Charset = Charsets.UTF_8
    val GB18030: Charset = Charset.forName("GB18030")
    val UTF16_LE: Charset = Charsets.UTF_16LE
    val UTF16_BE: Charset = Charsets.UTF_16BE

    /** GBK 双字节结构的最低合法配对比例 */
    private const val GBK_PAIR_RATIO_THRESHOLD = 0.8

    /** Unicode 替换字符：解码后出现它就说明这次解码是错的 */
    private const val REPLACEMENT = '\uFFFD'

    data class Result(val charset: Charset, val bomLength: Int)

    fun detect(bytes: ByteArray, length: Int = bytes.size): Result = when {
        length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Result(UTF8, 3)

        length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Result(UTF16_LE, 2)

        length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Result(UTF16_BE, 2)

        isValid(bytes, length, UTF8) -> Result(UTF8, 0)

        isValid(bytes, length, GB18030) -> Result(GB18030, 0)

        looksLikeUtf8Truncated(bytes, length) -> Result(UTF8, 0)

        looksLikeGbk(bytes, length) -> Result(GB18030, 0)

        else -> Result(UTF8, 0)
    }

    /** 用严格模式解码，出现非法字节序列即判定为「不是该编码」 */
    fun isValid(bytes: ByteArray, length: Int, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * 「UTF-8 文本被截断」判定。
     *
     * 触发场景：导入超过 [com.hrstts.jiandu.data.SafSource.MAX_BYTES] 的文件时按字节截断，
     * 最后一个汉字的 3 字节序列被切掉 1~2 个字节 → 严格 UTF-8 校验失败。
     * 若此时退回 GBK 启发式，整篇 UTF-8 中文会被按 GB18030 解码成乱码
     * （实测 20 MB 文件全篇乱码，段落结构也随之丢失）。
     *
     * 判据必须**只认「确实是被切断的 UTF-8 尾字符」**，否则会把真正的 GBK 文件误判成 UTF-8：
     *  1. 截断点前的主体必须能通过严格 UTF-8 解码；
     *  2. 末尾最多 3 个字节必须构成一个「合法的 UTF-8 多字节序列前缀」（首字节 + 正确的续接字节数），
     *     而不是随便几个高位字节。GBK 双字节正好是 `0xE3 0x80` 这类形状，只有第 2 条才能把它们区分开
     *     （实测 `0xB6 0xBC` 之外的组合会骗过只看比例/只看替换字符的判据）。
     */
    fun looksLikeUtf8Truncated(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length <= 0) return false

        val head = indexOfTruncatedUtf8Lead(bytes, length) ?: return false
        if (head == 0) return false

        val decoded = try {
            UTF8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, head))
        } catch (_: CharacterCodingException) {
            return false
        }
        return !decoded.toString().contains(REPLACEMENT)
    }

    /**
     * 找出「被截断的 UTF-8 字符」的起始下标；不是这种形状就返回 null。
     *
     * 末尾 1~3 个字节必须是：一个合法的多字节首字节 + 数量正确的续接字节（但整体不足该字符所需长度）。
     */
    private fun indexOfTruncatedUtf8Lead(bytes: ByteArray, length: Int): Int? {
        for (lead in (length - 3).coerceAtLeast(0)..(length - 1)) {
            val b0 = bytes[lead].toInt() and 0xFF
            val expected = when {
                b0 in 0xC2..0xDF -> 2
                b0 in 0xE0..0xEF -> 3
                b0 in 0xF0..0xF4 -> 4
                else -> continue
            }
            val present = length - lead
            if (present >= expected) continue      // 字符完整，不是截断
            var wellFormed = true
            for (i in (lead + 1) until length) {
                if ((bytes[i].toInt() and 0xC0) != 0x80) {
                    wellFormed = false
                    break
                }
            }
            if (wellFormed) return lead
        }
        return null
    }

    /**
     * GBK 双字节结构启发式：
     *  - 非 ASCII 字节里应有相当比例落在 GBK 首字节区（0x81~0xFE）与尾字节区（0x40~0xFE，不含 0x7F）；
     *  - 统计「首字节 + 合法尾字节」的配对数与首字节总数的比例，超过阈值即认为整体是 GBK。
     * 纯 UTF-8 中文文本会先在 [isValid] 一步被识别，不会走到这里。
     */
    fun looksLikeGbk(bytes: ByteArray, length: Int = bytes.size): Boolean {
        var leadBytes = 0
        var validPairs = 0
        var index = 0
        while (index < length) {
            val value = bytes[index].toInt() and 0xFF
            if (value < 0x80) {
                index++
                continue
            }
            if (value in 0x81..0xFE) {
                leadBytes++
                val trail = if (index + 1 < length) bytes[index + 1].toInt() and 0xFF else -1
                if (trail in 0x40..0xFE && trail != 0x7F) {
                    validPairs++
                    index += 2
                    continue
                }
            }
            index++
        }
        if (leadBytes == 0) return false
        return validPairs.toDouble() / leadBytes >= GBK_PAIR_RATIO_THRESHOLD
    }
}
