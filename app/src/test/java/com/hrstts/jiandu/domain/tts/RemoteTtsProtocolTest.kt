package com.hrstts.jiandu.domain.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程 TTS 协议测试。
 *
 * 最重要的一条是 [缓存 key 与服务端 Python 实现完全一致]：那个期望值是用 Python 端
 * `server/test_smoke.py` 独立算出来的**跨语言固定向量**。只要有人改了拼接顺序、
 * 分隔符或编码，这里立刻会红——而不是等到手机上「缓存永远不命中」才发现。
 */
class RemoteTtsProtocolTest {

    @Test
    fun `缓存 key 与服务端 Python 实现完全一致`() {
        // 由 server/test_smoke.py 生成（sha1("第一章 少年|sapi:microsoft-huihui|jiandu-remote-v1")）
        assertEquals(
            "c96b120ffad2943c9f181dd2c8e72fea2d6daaf5",
            RemoteTtsProtocol.keyOf(
                text = "第一章 少年",
                voiceId = "sapi:microsoft-huihui",
                modelTag = "jiandu-remote-v1",
            ),
        )
    }

    @Test
    fun `key 是 40 位小写十六进制`() {
        val key = RemoteTtsProtocol.keyOf("任意文本", "sapi:x")
        assertEquals(40, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun `换音色或换 modelTag 必然换 key`() {
        val text = "第一章 少年"
        val base = RemoteTtsProtocol.keyOf(text, "sapi:a", "jiandu-remote-v1")
        val otherVoice = RemoteTtsProtocol.keyOf(text, "sapi:b", "jiandu-remote-v1")
        val otherModel = RemoteTtsProtocol.keyOf(text, "sapi:a", "jiandu-remote-v2")
        assertEquals(3, setOf(base, otherVoice, otherModel).size)
    }

    @Test
    fun `片段打包与解析可逆`() {
        val pcm = ByteArray(4096) { (it % 251).toByte() }
        val blob = RemoteTtsProtocol.packClip(pcm)

        val frames = RemoteTtsProtocol.parseFrames(blob)
        assertNotNull("合法片段应能解析出帧数", frames)
        assertEquals(RemoteTtsProtocol.framesOf(pcm.size / 2), frames)
        assertContentEquals(pcm, RemoteTtsProtocol.pcmOf(blob)!!)
    }

    @Test
    fun `片段头布局与服务端一致`() {
        val blob = RemoteTtsProtocol.packClip(ByteArray(3840)) // 1920 采样 = 1 帧
        assertEquals(16, blob.size - 3840)
        assertEquals('j'.code.toByte(), blob[0])
        assertEquals('d'.code.toByte(), blob[1])
        assertEquals('p'.code.toByte(), blob[2])
        assertEquals('c'.code.toByte(), blob[3])
        assertEquals(1, blob[4].toInt())
        // 大端 frames
        assertEquals(0, blob[7].toInt())
        assertEquals(1, blob[11].toInt())
        assertEquals(1, RemoteTtsProtocol.parseFrames(blob))
    }

    @Test
    fun `非法片段头被拒绝`() {
        assertNull(RemoteTtsProtocol.parseFrames(ByteArray(0)))
        assertNull(RemoteTtsProtocol.parseFrames(ByteArray(16))) // 全 0
        assertNull(RemoteTtsProtocol.parseFrames("<html>404</html>".toByteArray()))
        val badVersion = RemoteTtsProtocol.packClip(ByteArray(64)).also { it[4] = 99 }
        assertNull(RemoteTtsProtocol.parseFrames(badVersion))
    }

    @Test
    fun `帧数与采样数的换算`() {
        assertEquals(1, RemoteTtsProtocol.framesOf(1920))
        assertEquals(1, RemoteTtsProtocol.framesOf(1))      // 至少 1 帧
        assertEquals(13, RemoteTtsProtocol.framesOf(24000)) // 1 秒 ≈ 12.5 帧
        assertEquals(1.0, RemoteTtsProtocol.secondsOf(24000), 0.0001)
    }

    @Test
    fun `服务器地址标准化`() {
        assertEquals("http://10.62.61.202:8080", RemoteTtsProtocol.normalizeBaseUrl("10.62.61.202"))
        assertEquals("http://10.62.61.202:8080", RemoteTtsProtocol.normalizeBaseUrl("  10.62.61.202  "))
        assertEquals("http://10.62.61.202:9000", RemoteTtsProtocol.normalizeBaseUrl("10.62.61.202:9000"))
        assertEquals("http://10.62.61.202:8080", RemoteTtsProtocol.normalizeBaseUrl("http://10.62.61.202:8080/"))
        assertEquals("https://tts.example.com:443", RemoteTtsProtocol.normalizeBaseUrl("https://tts.example.com:443"))
        assertEquals("http://192.168.43.1:8080", RemoteTtsProtocol.normalizeBaseUrl("192.168.43.1:8080/"))
        assertEquals("", RemoteTtsProtocol.normalizeBaseUrl("   "))
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("字节 $i 不一致", expected[i], actual[i])
        }
    }
}
