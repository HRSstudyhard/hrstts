# 电脑当 TTS 服务器，手机只负责听 —— 设计与实测

> 目标变更：**不再在手机上部署 TTS**，改用本机（RTX 4060 / Ryzen 7 7840H）做合成，
> 手机拿文本过去、把音频拿回来；同时把整本音频**同步到手机**，之后断网也能听。
>
> 相关：[SPEC.md](SPEC.md)（原功能规格）、[PROGRESS.md](PROGRESS.md)（阶段进度）、
> [TTS-AUDIO-CACHE.md](TTS-AUDIO-CACHE.md)（端侧缓存设计，本方案复用了它）
> 服务端代码：`server/`；手机端新增：`RemoteTtsEngine` / `RemoteTtsClient` / `RemoteTtsProtocol`

---

## 1. 为什么这样改能成立

端侧 Qwen3-TTS 的三个硬伤（实测见 [PROGRESS.md](PROGRESS.md) §5.2、[TTS-AUDIO-CACHE.md](TTS-AUDIO-CACHE.md) §1）：

| 问题 | 端侧实测 | 换成电脑后 |
|------|---------|-----------|
| 合成比播放慢 | RTF 4.29~8.7；首音 **6834 ms** | 服务端 RTF **0.011~0.082**；端到端首音 **385 ms** |
| 模型占内存 | 常驻 1.2~1.6 GB，峰值 PSS 3.8~4.3 GB | 手机 **0 字节**模型 |
| 模型占存储 | 1.59 GB | 手机 0；电脑侧缓存按需淘汰 |

**关键结论：这套改动的重点不是"换个更快的模型"，而是"把合成这一步搬到有 GPU 的机器上"。**
手机端只保留它真正擅长的事：解析文本、驱动逐句播放、高亮跟随、缓存回放。

而且**播放骨架一行都不用改**：`TtsEngine` 接口、`TtsController` 的逐句连播、
`TtsAudioCache` 的 int16 PCM 两级缓存、`AudioTrack` 的流式写入全部复用。
新引擎 `RemoteTtsEngine` 是把 `Qwen3TtsEngine` 里的「LiteRT 合成」换成「HTTP 拉流」。

---

## 2. 架构

```
┌──────────────── 手机（简读 App） ────────────────┐
│                                                  │
│  ReaderViewModel                                 │
│    └─ 选引擎：远程 → 端侧 → 系统（单向回退链）      │
│  TtsController（逐句连播，引擎无关）               │
│    └─ RemoteTtsEngine                            │
│         ├─ 命中本地缓存 → 直接 AudioTrack 回放     │
│         └─ 未命中 → POST /v1/tts → 边收边播        │
│  TtsAudioCache（RAM 32 MB + 磁盘 512 MB，int16）   │
└───────────────────────┬──────────────────────────┘
                        │ 局域网 HTTP（明文，24 kHz int16 PCM）
┌───────────────────────▼──────────────────────────┐
│  电脑：简读 TTS 网关（server/，纯标准库）           │
│    ├─ /v1/health   引擎/音色/modelTag             │
│    ├─ /v1/tts      合成一句 → 裸 PCM              │
│    ├─ /v1/clips/*  取服务端缓存片段（jdpc）        │
│    └─ /v1/sync     整本后台预合成                  │
│  ClipCache（磁盘 LRU 2 GB，格式与手机完全相同）     │
│    └─ 引擎插件（engines/）                        │
│         ├─ sapi       Windows SAPI，零安装、离线   │
│         └─ （待加）edge-tts / GPT-SoVITS           │
└──────────────────────────────────────────────────┘
```

**两个刻意的设计决定：**

1. **手机不认识具体引擎**。它只会「发文本、收 PCM」。所以电脑上从 SAPI 换成
   GPT-SoVITS / edge-tts，手机端**不用重新发版**，只改 `--engine`。
2. **服务端和手机的缓存文件格式完全相同**（16 字节 `jdpc` 头 + int16 LE PCM，
   缓存 key 算法也一致）。因此「同步」不是转码再导入，而是**下载字节直接落盘**，
   听完立刻可用，零额外开销。这个一致性由 `RemoteTtsProtocolTest` 里的一条
   **跨语言固定向量**守着（见 §6）。

---

## 3. 快速开始（4 步）

### 第 1 步：电脑上起服务

```powershell
cd F:\Projects\hrstts\server
python run.py
```

启动后会打印类似：

```
 后端引擎 : sapi
 音频格式 : 24000 Hz / 单声道 / int16（与手机端完全一致）
 可选音色 : 2 个
             - sapi:microsoft-huihui  (Microsoft Huihui Desktop - Chinese (Simplified))
             - sapi:microsoft-zira    (Microsoft Zira Desktop - English (United States))

 在手机上用浏览器打开下面任意一个地址，能打开就说明链路通了：
    http://10.62.61.202:8080      [默认出口网卡]
```

也可以直接双击 `server/start.bat`。

### 第 2 步：放行防火墙（**必做，否则手机一定连不上**）

本机 WLAN 被识别成 **Public** 配置，默认拦截入站。
用管理员 PowerShell 跑一次（脚本会自己弹 UAC）：

```powershell
cd F:\Projects\hrstts\server
powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1
```

### 第 3 步：用手机浏览器验证（10 秒）

手机连**同一个 WiFi**，浏览器打开第 1 步打印的地址（如 `http://10.62.61.202:8080`）。

页面上输入一段文本点「朗读」，能出声就说明：
**地址对 ✓ 防火墙通 ✓ 明文 HTTP 允许 ✓ 合成正常 ✓**。

> 这一步故意不依赖 App：手机浏览器不受 Android 明文 HTTP 限制，
> 所以它是排查「是网络问题还是 App 问题」的最快分界线。

### 第 4 步：App 里填地址

简读 → 左上角「我的」→ **Desktop synthesis (remote TTS)** 卡片：

1. 地址填 `10.62.61.202:8080`（不用写 `http://`，会自动补全并标准化）
2. 点 **Test connection** → 应显示 `Connected · sapi`
3. 选音色（下拉）
4. 关闭抽屉，长按正文 → 「阅读」

之后每次启动都会自动连；连不上会自动回退到端侧/系统语音（可在卡片里关掉回退）。

### 可选：把整本预下载到手机

同一个卡片点 **Pre-download the whole book**。

电脑会按句批量合成（RTF 0.01~0.08，一本 3 小时有声书约 6~20 分钟），
手机把缺的片段逐段下载并**直接写进现有缓存**。之后电脑关机、
手机开飞行模式也能连续朗读。

---

## 4. HTTP 契约（换引擎/自己写客户端时看这里）

所有响应体都是 **24 kHz / 单声道 / int16 小端裸 PCM**（无 WAV 头）。

| 方法 | 路径 | 请求 | 响应 |
|------|------|------|------|
| GET | `/` | — | HTML 自测页 |
| GET | `/v1/health` | — | `{ok, engine, defaultVoice, voices[], sampleRate, modelTag, cache}` |
| GET | `/v1/voices` | — | `{voices:[{id,label,engine,locale}]}` |
| POST | `/v1/tts` | `{text, voice?}` | 裸 PCM；头：`X-Key` `X-Frames` `X-Sample-Rate` `X-Cache: hit\|miss` |
| POST | `/v1/tts/wav` | `{text, voice?}` | 同上，但包成 WAV（给浏览器） |
| GET | `/v1/clips/{key}` | — | `jdpc` 片段字节；404 = 没有 |
| POST | `/v1/sync` | `{book, voice?, items:[{key,text}]}` | `202 {book,total,done,failed,running}` |
| GET | `/v1/sync/{book}` | — | 任务进度 |
| GET | `/v1/stats` | — | 请求数 / 合成耗时 / 缓存命中 |

### 4.1 缓存 key（两端必须一致）

```
key = sha1( text_utf8 | voice_id_utf8 | model_tag_utf8 )    // '|' = 0x7C
```

- `voice_id` 形如 `sapi:microsoft-huihui`、`edge:zh-CN-YunxiNeural`，**必须稳定**；
- `model_tag` 由服务端 `/v1/health` 下发（当前 `jiandu-remote-v1`）。
  **换了音频前端（重采样/正则化/模型）就要 +1**，否则手机会播到旧音频；
- 手机侧运行时以服务端返回的 `modelTag` 为准，所以改 tag 不会出现两端不一致。

### 4.2 片段文件格式（= `TtsAudioCache` 磁盘格式）

| 偏移 | 内容 |
|------|------|
| 0..3 | `jdpc` |
| 4 | 版本 = 1 |
| 5..7 | 保留 0 |
| 8..11 | frames，**大端** int32 |
| 12..15 | 保留 0 |
| 16.. | int16 LE PCM |

`frames` 只用于日志与「近似音频时长」（12.5 帧/秒，与 Qwen3-TTS 的 codec 帧率一致）。

### 4.3 加一个新引擎（三步）

```python
# server/jiandu_gateway/engines/my_engine.py
from .base import TtsEngine, Voice

class MyEngine(TtsEngine):
    name = "my-engine"
    def voices(self) -> list[Voice]:
        return [Voice(id="my:voice1", label="音色 1", engine=self.name, locale="zh-CN")]
    def synthesize(self, text: str, voice_id: str) -> bytes:
        ...   # 必须返回 24 kHz 单声道 int16 LE 裸 PCM
```

然后在 `engines/__init__.py` 的 `_REGISTER` 里登记一行，用
`python run.py --engine my-engine` 启动即可。**手机端不需要任何改动。**

---

## 5. 实测结果

环境：模拟器 `jiandu_api37`（API 37 x86_64）+ 本机 RTX 4060 Laptop。
服务端：`sapi` 后端（Microsoft Huihui），24 kHz。

| 指标 | 端侧 Qwen3-TTS | 电脑合成 | 提升 |
|------|---------------|---------|------|
| 首音延迟（无缓存） | 6834 ms | **385 ms** | **17.7×** |
| 首音延迟（缓存命中） | 2~3 ms | **3 ms** | 持平 |
| 句间空档（连续 3 句） | 首次播放每句要等合成（1.5 s + 合成时间）；已缓存时 14 ms | **平均 17 ms / 最大 18 ms** | 与端侧「已缓存」持平，但**不需要预热** |
| 服务端合成 RTF | 4.29~8.7 | **0.011~0.082** | **约 100×** |
| 手机模型存储 | 1.59 GB | **0** | — |
| 手机模型内存 | 1.2~1.6 GB | **0** | — |
| `/v1/health` 探测 | — | **15~39 ms** | — |
| 整本同步（3 段验收） | — | **435 ms**（含预合成+下载+落盘） | — |

### 验证证据

```
单元测试        40 项全绿（原 32 + 新增 RemoteTtsProtocolTest 8）
服务端自检      server/test_smoke.py  37 项全绿
集成测试        RemoteTtsIntegrationTest  3 项，在模拟器上真机运行时通过（0 failed / 0 skipped）
APK             app-debug.apk  40.3 MB（versionCode 4 / 0.4.0）
```

三条集成测试分别覆盖：

| 测试 | 覆盖的链路 |
|------|-----------|
| `电脑合成整条链路可用并写入缓存` | `initialize → speak → 收 PCM → AudioTrack → 写缓存 → 二次播命中缓存` |
| `控制器能把多句连续送进远端引擎并播完` | `TextParser → ReadingSegments → TtsController 逐句连播 → 状态机回到 Ready` |
| `整本同步能预合成并落盘到手机缓存` | `requestSync → 轮询进度 → fetchClip → putEncoded → 读回可播` |

关键日志（模拟器 logcat，真实一次朗读 + 一次缓存回放）：

```
I RemoteTtsEngine: 远程引擎已就绪：http://10.0.2.2:8080 engine=sapi voice=sapi:microsoft-huihui
                   modelTag=jiandu-remote-v1 音色数=2 探测耗时=39ms
I RemoteTtsEngine: 远程合成完成：42 帧（3.36s 音频），首音延迟=385ms，总耗时=3367ms，RTF=1.001，server=sapi
I RemoteTtsEngine: 缓存命中：42 帧（3.36s 音频），首音延迟=3ms，命中率=hit=1 miss=1 ram=157KB disk=6424KB
```

连播与同步（集成测试实测）：

```
I RemoteTtsIntegrationTest: 连播通过：句子数=3 经过的片段=3 句间=n=2 avg=17ms max=18ms
I RemoteTtsIntegrationTest: 同步通过：book=androidtest-... 段数=3 cache=hit=3 miss=0 ram=235KB disk=235KB
I RemoteTtsEngine: 缓存命中：29 帧（2.32s 音频），首音延迟=4ms，命中率=hit=2 miss=0
```

> `RTF=1.001` 是**包含了播放时间**的端到端比值（`track.write` 是阻塞的），
> 真正衡量合成速度的是服务端的 `RTF=0.011~0.082`。

界面证据：`docs/shots/p6-remote-drawer.png`（卡片显示 `Connected · sapi`、
当前引擎 `Desktop (remote) · 10.0.2.2:8080`）。

---

## 6. 为什么缓存 key 那条单测最重要

跨语言最容易出、也最难查的 bug 就是「两端算的 key 不一样」：不报错、不崩溃，
只表现为**缓存永远不命中、整本同步白做**。

所以用了一条**固定向量**互相钉死：

| 项 | 值 |
|----|-----|
| text | `第一章 少年` |
| voice | `sapi:microsoft-huihui` |
| modelTag | `jiandu-remote-v1` |
| key | `c96b120ffad2943c9f181dd2c8e72fea2d6daaf5` |

这个值由 Python 端 `server/test_smoke.py` 独立算出并打印，
Kotlin 端 `RemoteTtsProtocolTest.缓存 key 与服务端 Python 实现完全一致` 硬编码断言。
任何一端改了拼接顺序/分隔符/编码，都会立刻红。

---

## 7. 排错

| 现象 | 原因 | 处理 |
|------|------|------|
| 手机浏览器也打不开地址 | 不在同一网络 / 防火墙 | 先确认同一 WiFi；跑 `open-firewall.ps1`；关掉 360/火绒 |
| 浏览器能开，App 连不上 | 地址填错 / 明文 HTTP 被拦 | 卡片点 Test connection 看具体错误；地址不用写 `http://` |
| `Connection refused` | 服务没启动 / 端口不对 | 电脑上确认 `python run.py` 还活着；端口与卡片一致 |
| `Timed out` | 手机在流量、电脑在别的网 | 检查两边网段；或电脑连手机热点后用热点的 `192.168.43.x` |
| 能连上但没声音 | 手机静音 / 音量 | 端侧路径同样受音量影响，先确认媒体音量 |
| 首次朗读要等 1~2 秒 | COM 冷启动 | 正常（首句 391 ms，之后 ~80 ms）；再听一遍会走缓存 |
| 音色很机器人 | 用的是 Windows SAPI | 见 §8，换引擎 |
| 换了引擎后听着像旧音色 | `model_tag` 没变 | 把 `protocol.py` 的 `MODEL_TAG` +1，重启服务 |
| 同步卡在某进度 | 服务端在合长书 | `GET /v1/sync/<book>` 看 `running`；上限 30 分钟 |

---

## 8. 关于音质：怎么换掉 Windows SAPI

当前后端是 **Windows 内建 SAPI**（Microsoft Huihui）：零安装、离线、RTF 0.01~0.08，
但音色是"老式合成音"，**适合先打通链路，不适合长期听书**。

调研结论（完整报告见根目录 `TTS本地服务调研报告.md`）：

| 方案 | 8 GB 显存 | 流式 | Windows | HTTP API | 说明 |
|------|----------|------|---------|----------|------|
| **GPT-SoVITS v2Pro/v4** | ✅ 富余 | ✅ | ✅ 一键整合包 | ✅ `api_v2.py` | **推荐**：README 实测 4060Ti RTF 0.028 |
| CosyVoice2/3 | ✅ | ✅ 150 ms | ⚠️ conda/docker | ✅ `runtime/.../server.py` | 中文质量好 |
| IndexTTS-2.5 | ⚠️ 5.11 GB 权重 | ❌ | ⚠️ | 仅 webui | 质量高但重 |
| edge-tts | 不需要 GPU | — | ✅ pip | 需自建 | **最省事的高质量音色**（走微软云，需联网） |
| Qwen3-TTS 官方版 | ❌ 不推荐 | ⚠️ 假流式 | ⚠️ sox/依赖 pin | ❌ 仅 gradio | 1.7B 官方要 12 GB；Python 包 FAQ 自认流式未支持 |

> 为什么没沿用同一套 Qwen3-TTS：官方的 8 GB 兼容表里连 RTX 3080 都只标
> "Development only（仅 0.6B）"，1.7B 最低 12 GB；且 Python 包的流式
> "coming soon"，其流式模式正是长文语速漂移的成因（Issue #239）。
> 另外本机 GitHub raw 不可达，而 `qwen-tts` 虽在 PyPI 上，仍要过 sox 等 Windows 坑。

**推荐路线**：装 GPT-SoVITS 一键包 → 用当前 SAPI/edge-tts 生成的几秒音频做
**参考音色**（这样音色接近现在听到的）→ 在 `engines/` 里加一个适配 `api_v2.py` 的后端。
手机端不用改。

**想马上要好音色又不想装模型**：加 `edge_tts` 后端（`pip install edge-tts`），
中文音色如 `zh-CN-YunxiNeural`、`zh-CN-XiaoxiaoNeural`，质量远好于 SAPI。

---

## 9. 已知限制

1. **音色**：默认 SAPI 偏机械。见 §8。
2. **必须在同一网络**：手机走移动数据时连不到家里电脑。要么连同一 WiFi，
   要么电脑连手机热点，要么上内网穿透（Tailscale 等，本方案未做）。
3. **电脑要醒着**：Windows 睡眠后服务断开。建议"接通电源时从不睡眠"。
   注意：**已经同步到手机的整本音频不受影响**，断网/关机照样能听。
4. **明文 HTTP**：局域网内无加密（`network_security_config.xml` 已整体放开明文）。
   同 WiFi 下的其他人理论上可以访问这个服务；家庭网络可接受，公共 WiFi 不建议。
5. **单机单用户**：服务端没做鉴权、限流、多用户隔离。
6. **`/v1/tts` 不做句内流式**：当前后端整句几十毫秒就合完，够用；
   换成慢模型且需要"边生成边播"时应加 chunked 响应（客户端已按流式读，不用改）。
7. **整本同步的上限**：手机磁盘缓存档 512 MB（约 3 小时音频），
   超出按最久未访问淘汰；很长的书需要分段听。
8. **语速仍在手机侧变速**：`AudioTrack.playbackParams.setSpeed`，
   所以语速不影响缓存 key，但极端语速（>1.5×）会有轻微音质损失。

---

## 10. 代码地图

| 位置 | 职责 |
|------|------|
| `server/run.py` | 启动器：打印局域网地址、防火墙提示、音色列表 |
| `server/jiandu_gateway/protocol.py` | **协议唯一真源**：key 算法、片段格式、采样率、modelTag |
| `server/jiandu_gateway/cache.py` | 服务端磁盘缓存（LRU 2 GB） |
| `server/jiandu_gateway/server.py` | HTTP 路由 + 自测页 + 预合成任务 |
| `server/jiandu_gateway/engines/` | 引擎插件：`base.py` 抽象、`sapi.py` 实现 |
| `server/test_smoke.py` | 服务端自检 35 项（含生成跨语言固定向量） |
| `app/.../domain/tts/RemoteTtsProtocol.kt` | 协议的手机端镜像（纯 Kotlin，可 JVM 单测） |
| `app/.../data/tts/RemoteTtsClient.kt` | HTTP 客户端（流式读、可打断） |
| `app/.../data/tts/RemoteTtsEngine.kt` | `TtsEngine` 实现：HTTP PCM → AudioTrack + 缓存 |
| `app/.../data/tts/RemoteTtsSettings.kt` | 地址/音色/开关的持久化 |
| `app/.../viewmodel/ReaderViewModel.kt` | 引擎选择与回退链、整本同步编排 |
| `app/.../viewmodel/RemoteTtsViewModel.kt` | 设置卡的状态与连接测试 |
| `app/.../ui/settings/RemoteTtsCard.kt` | 抽屉里的卡片 UI |
| `app/.../res/xml/network_security_config.xml` | 放开明文 HTTP（Android 9+ 必需） |
| `app/src/test/.../RemoteTtsProtocolTest.kt` | 跨语言一致性单测（8 项） |
| `app/src/androidTest/.../RemoteTtsIntegrationTest.kt` | 端到端 / 连播 / 同步集成测试（3 项） |

### 复跑命令

```powershell
# 服务端自检
cd server; python test_smoke.py

# 起服务
cd server; python run.py

# 手机端单测（含跨语言 key 一致性）
$env:JAVA_HOME = "F:\Projects\hrstts\.toolchain\jdk-21"
.\gradlew.bat :app:testDebugUnitTest

# 端到端集成测试（需要先起服务，且模拟器/真机已连接）
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.hrstts.jiandu.RemoteTtsIntegrationTest"
```
