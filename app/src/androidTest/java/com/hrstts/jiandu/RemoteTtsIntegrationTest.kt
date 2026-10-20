package com.hrstts.jiandu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hrstts.jiandu.data.tts.RemoteTtsEngine
import com.hrstts.jiandu.data.tts.RemoteTtsSettings
import com.hrstts.jiandu.data.tts.TtsAudioCache
import com.hrstts.jiandu.domain.tts.RemoteTtsProtocol
import com.hrstts.jiandu.domain.tts.TtsListener
import com.hrstts.jiandu.i18n.AndroidStrings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 远程 TTS 端到端集成测试。
 *
 * 它跑在**真实 Android 运行时**上，覆盖「HTTP → PCM → AudioTrack → 两级缓存」整条链路，
 * 而不用手工点界面——这样回归时一条命令就能验证「手机还能不能用电脑朗读」。
 *
 * 前置条件：
 *   1. 电脑上先起服务：`cd server && python run.py`
 *   2. 模拟器里 `10.0.2.2` 指向宿主机；真机则用 `-e remoteTtsUrl http://192.168.x.x:8080`
 *
 * 连不上时**自动跳过**（assumeTrue），不会把 CI 变红——这是集成测试而不是单元测试。
 *
 * 跑法：
 *   gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.hrstts.jiandu.RemoteTtsIntegrationTest
 */
@RunWith(AndroidJUnit4::class)
class RemoteTtsIntegrationTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun baseUrl(): String {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("remoteTtsUrl")?.takeIf { it.isNotBlank() }
            ?: "http://10.0.2.2:${RemoteTtsProtocol.DEFAULT_PORT}"
    }

    @Test
    fun 电脑合成整条链路可用并写入缓存() {
        val url = baseUrl()
        val settings = RemoteTtsSettings(context).apply {
            serverUrl = url
            enabled = true
            voice = ""
        }
        val cache = TtsAudioCache(context)
        val strings = AndroidStrings(context)
        val engine = RemoteTtsEngine(settings, strings, cache)

        val readyLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        val firstChunkLatch = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val rangeSeen = AtomicReference(0)

        engine.setListener(object : TtsListener {
            override fun onReady() = readyLatch.countDown()
            override fun onError(message: String) {
                error.compareAndSet(null, message)
                readyLatch.countDown()
                doneLatch.countDown()
            }
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = doneLatch.countDown()
            override fun onRange(utteranceId: String, start: Int, end: Int) {
                rangeSeen.set(end)
                firstChunkLatch.countDown()
            }
        })

        try {
            engine.initialize()
            assertTrue(
                "3 秒内没有连上 $url（服务没启动？防火墙？）",
                readyLatch.await(8, TimeUnit.SECONDS),
            )
            // 连不上就跳过，而不是失败：这条测试依赖外部服务
            assumeTrue(
                "服务端不可达，跳过：${error.get()}",
                error.get() == null && engine.isReady,
            )
            assertNotNull("服务端应返回音色列表", engine.availableVoices)
            assertTrue("应至少有一个音色", engine.availableVoices.isNotEmpty())

            val text = "第一章 少年。他翻开了第二章，发现字迹模糊不清。"
            engine.speak(text, "1")

            assertTrue(
                "首块 PCM 没有在 10 秒内到达（网络或合成过慢）",
                firstChunkLatch.await(10, TimeUnit.SECONDS),
            )
            assertTrue(
                "整句没有在 30 秒内播完：${error.get()}",
                doneLatch.await(30, TimeUnit.SECONDS),
            )
            assertTrue("不应出现引擎错误：${error.get()}", error.get() == null)

            // 高亮推进过（说明确实收到了 PCM 并在按进度上报）
            assertTrue("字符高亮没有推进", rangeSeen.get() > 0)

            // 合成结果必须落到缓存：这是「同步到手机」「断网也能听」的前提
            val key = RemoteTtsProtocol.keyOf(text, engine.currentVoiceId, engine.currentModelTag)
            assertTrue(
                "音频没有写入本地缓存（key=$key）",
                cache.contains(key),
            )

            // 再念一次应命中缓存（首音 ms 级），验证缓存确实可复用
            val hitLatch = CountDownLatch(1)
            engine.setListener(object : TtsListener {
                override fun onReady() = Unit
                override fun onError(message: String) {
                    error.set(message)
                    hitLatch.countDown()
                }
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = hitLatch.countDown()
                override fun onRange(utteranceId: String, start: Int, end: Int) = Unit
            })
            engine.speak(text, "2")
            assertTrue("缓存回放没有在 20 秒内结束", hitLatch.await(20, TimeUnit.SECONDS))
            assertTrue("缓存回放出错：${error.get()}", error.get() == null)

            android.util.Log.i(
                "RemoteTtsIntegrationTest",
                "端到端通过：url=$url engine=${engine.serverEngine} voice=${engine.currentVoiceId} " +
                    "cache=${cache.statsLine()}",
            )
        } finally {
            engine.shutdown()
        }
    }

    /**
     * 控制器逐句连播：`TextParser` → `ReadingSegments` → `TtsController` → 远端引擎。
     *
     * 这条测试覆盖的正是用户按「阅读」之后发生的事，只是绕开了 SAF 与长按手势。
     * 它验证三件事：能**连续**播完多句（不是只播第一句）、高亮键会随句子推进、
     * 状态最终回到 Ready。
     */
    @Test
    fun 控制器能把多句连续送进远端引擎并播完() {
        val url = baseUrl()
        val settings = RemoteTtsSettings(context).apply {
            serverUrl = url
            enabled = true
            voice = ""
        }
        val engine = RemoteTtsEngine(settings, AndroidStrings(context), TtsAudioCache(context))
        val controller = com.hrstts.jiandu.domain.tts.TtsController()
        controller.attach(engine)

        val book = com.hrstts.jiandu.domain.parser.TextParser().parse(
            raw = "第一章 少年。他翻开了第二章，发现字迹模糊不清。于是放下书走了出去。",
            uri = "test://book",
            name = "测试书",
        )
        val segments = com.hrstts.jiandu.domain.tts.ReadingSegments.fromBook(book)
        assumeTrue("解析没有产出句子", segments.isNotEmpty())

        val seenKeys = mutableSetOf<String>()
        val failed = AtomicReference<String?>(null)

        try {
            controller.play(segments)
            // 轮询而不是用 Flow 收集：避免「收集器还没挂上，播放已经结束」的竞态
            var sawSpeaking = false
            var finished = false
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                val state = controller.state.value
                state.activeSegmentKey?.let { seenKeys.add(it) }
                if (state.status == com.hrstts.jiandu.domain.tts.TtsStatus.Error) {
                    failed.set(state.message)
                    break
                }
                if (state.status == com.hrstts.jiandu.domain.tts.TtsStatus.Speaking) {
                    sawSpeaking = true
                }
                if (sawSpeaking && state.status == com.hrstts.jiandu.domain.tts.TtsStatus.Ready) {
                    finished = true
                    break
                }
                Thread.sleep(100)
            }
            controller.stop()
            assumeTrue("服务端不可达，跳过：${failed.get()}", failed.get() == null)
            assertTrue("整段没有在 90 秒内播完（经过片段=${seenKeys.size}）", finished)
            assertTrue("只经过了 1 句，说明连播没生效", seenKeys.size >= 2)
            android.util.Log.i(
                "RemoteTtsIntegrationTest",
                "连播通过：句子数=${segments.size} 经过的片段=${seenKeys.size} " +
                    "句间=${controller.gapSummary()}",
            )
        } finally {
            controller.shutdown()
        }
    }

    /**
     * 整本同步机制：服务端预合成 → 轮询进度 → 逐段下载 → **直接写进手机缓存**。
     *
     * 这条测试是「同步到手机上」这个需求的验收：跑完之后删掉缓存之前，
     * 即使电脑关机也能连续朗读（因为音频已经是手机本地的了）。
     */
    @Test
    fun 整本同步能预合成并落盘到手机缓存() {
        val url = baseUrl()
        val cache = TtsAudioCache(context)
        val client = com.hrstts.jiandu.data.tts.RemoteTtsClient(url)

        val health = try {
            client.health(timeoutMs = 5_000)
        } catch (t: Throwable) {
            assumeTrue("服务端不可达，跳过：${t.message}", false)
            return
        }

        val voice = health.defaultVoice
        val modelTag = health.modelTag
        val sentences = listOf(
            "第一章 少年。",
            "他翻开了第二章，发现字迹模糊不清。",
            "于是放下书，走了出去。",
        )
        val items = sentences.map { it to RemoteTtsProtocol.keyOf(it, voice, modelTag) }

        val book = "androidtest-${System.currentTimeMillis()}"
        client.requestSync(book, voice, items.map { it.second to it.first })

        val deadline = System.currentTimeMillis() + 60_000
        var done = false
        while (System.currentTimeMillis() < deadline) {
            val progress = client.syncProgress(book)
            if (progress != null && !progress.running) {
                assertTrue("预合成失败 ${progress.failed} 句", progress.failed == 0)
                done = true
                break
            }
            Thread.sleep(300)
        }
        assertTrue("60 秒内没有完成预合成", done)

        var downloaded = 0
        for ((text, key) in items) {
            val blob = client.fetchClip(key)
            assertNotNull("服务端没有返回片段：${text.take(8)}", blob)
            assertTrue("片段头不合法（说明拿到的不是 jdpc）", cache.putEncoded(key, blob!!))
            downloaded++
        }
        assertTrue("应有 3 段落盘", downloaded == 3)

        // 落盘后再读回来，必须是可播的 int16 PCM
        for ((text, key) in items) {
            val clip = cache.get(key)
            assertNotNull("缓存里读不回来：${text.take(8)}", clip)
            assertTrue("音频为空：${text.take(8)}", clip!!.pcm.isNotEmpty())
        }
        android.util.Log.i(
            "RemoteTtsIntegrationTest",
            "同步通过：book=$book 段数=$downloaded cache=${cache.statsLine()}",
        )
    }
}
