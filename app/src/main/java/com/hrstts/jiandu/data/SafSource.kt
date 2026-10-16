package com.hrstts.jiandu.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.database.Cursor
import android.provider.OpenableColumns
import com.hrstts.jiandu.R
import com.hrstts.jiandu.domain.model.Book
import com.hrstts.jiandu.domain.parser.EncodingDetector
import com.hrstts.jiandu.domain.parser.TextParser
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream

/**
 * 通过 SAF（Storage Access Framework）读取本地文本文件。
 *
 * 不使用任何存储权限：文件由用户在选择器中显式授权，授权通过
 * `takePersistableUriPermission` 持久化，重启后仍可访问「我的」中的历史文件。
 */
object SafSource {

    /** 单文件上限：超过则截断（避免一次性占用过多内存） */
    const val MAX_BYTES: Int = 20 * 1024 * 1024

    /** 文件选择器允许的类型：纯文本为主，附带 application/octet-stream 以兼容部分文件管理器 */
    val MIME_TYPES = arrayOf("text/plain", "text/*", "application/octet-stream")

    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /** 持久化读授权，失败不致命（例如某些第三方 provider 不支持） */
    fun persistPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 忽略：下次冷启动时该 uri 可能失效，UI 侧会给出提示
        } catch (_: IllegalArgumentException) {
            // 忽略：部分 provider（如媒体库）不支持持久授权
        }
    }

    fun canRead(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    data class Imported(
        val book: Book,
        val truncated: Boolean,
    )

    suspend fun readBook(
        context: Context,
        uri: Uri,
        parser: TextParser = TextParser(),
        strings: UiStrings = AndroidStrings(context),
    ): Imported = withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri, strings)
        val raw = readBytes(context, uri, MAX_BYTES, strings)
        val detection = EncodingDetector.detect(raw.bytes)
        val text = String(
            raw.bytes,
            detection.bomLength,
            raw.bytes.size - detection.bomLength,
            detection.charset,
        )
        val book = parser.parse(
            raw = text,
            uri = uri.toString(),
            name = name,
            charsetName = detection.charset.name(),
        )
        Imported(book = book, truncated = raw.truncated)
    }

    private class RawBytes(val bytes: ByteArray, val truncated: Boolean)

    private fun readBytes(context: Context, uri: Uri, limit: Int, strings: UiStrings): RawBytes {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(strings.get(R.string.import_open_failed))
        BufferedInputStream(input).use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var truncated = false
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                if (output.size() + read > limit) {
                    output.write(buffer, 0, limit - output.size())
                    truncated = true
                    break
                }
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            // 先按完整行结尾退到换行处，避免截断在段落中间（也让编码探测不会遇到半截序列）
            val safe = if (truncated) trimToCompleteLine(bytes) else bytes
            return RawBytes(safe, truncated)
        }
    }

    /**
     * 把按字节截断的缓冲区退到「最后一个完整 UTF-8 字符」。
     *
     * 不这样做时，上限处正好切在一个汉字的 3 字节序列中间：严格 UTF-8 校验失败 →
     * 编码探测退到 GBK 分支 → 整篇 UTF-8 中文被按 GB18030 解码成乱码。
     * 编码探测侧也做了兜底（[EncodingDetector.looksLikeUtf8Truncated]），这里是第一道防线。
     */
    private fun trimToCompleteLine(bytes: ByteArray): ByteArray {
        // 最多回退 3 个续接字节（UTF-8 单字符最多 4 字节）
        var end = bytes.size
        var guard = 0
        while (end > 0 && guard < 3 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            guard++
        }
        if (end <= 0) return bytes

        // 若能找到换行，就在换行之后截断：段落不会缺一半
        val newline = lastNewlineBefore(bytes, end)
        if (newline >= 0 && newline + 1 < end) end = newline + 1

        return if (end == bytes.size) bytes else bytes.copyOfRange(0, end)
    }

    /** 在 end 之前查找最后一个 '\n'（只回看有限窗口；找不到返回 -1） */
    private fun lastNewlineBefore(bytes: ByteArray, end: Int): Int {
        val window = 64 * 1024
        val from = (end - 1).coerceAtMost(bytes.size - 1)
        val stop = (end - window).coerceAtLeast(0)
        for (i in from downTo stop) {
            if (bytes[i] == '\n'.code.toByte()) return i
        }
        return -1
    }

    private fun queryDisplayName(context: Context, uri: Uri, strings: UiStrings): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')
            ?: strings.get(R.string.import_unnamed)
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) ?: fallback else fallback
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        } finally {
            cursor?.close()
        }
    }
}
