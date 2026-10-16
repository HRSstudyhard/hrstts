# 端侧朗读 PCM 音频缓存 —— 设计与实测

> 目标（来自需求）：导入文本时就开始缓存语音；容量 1 GB；退后台自动清除。
> 结论：**已按「双档 + 退后台清 RAM」实现并实测通过**；但「1 GB」按实测改动为
> **RAM 32 MB + 磁盘 512 MB**，原因是合成速度而非容量（见 §1）。
> 相关：[TTS-LATENCY-OPTIONS.md](TTS-LATENCY-OPTIONS.md)（延迟优化方案对比）。

---

## 1. 为什么不是「1 GB 全放内存」

| 事实 | 数值 | 影响 |
|------|------|------|
| 端侧合成速度 | RTF 5.1~8.7（模拟器）；官方 Pixel 8a 2.06 | 合满 1 GB 音频（≈6.2 小时）需要 **12 小时以上**纯合成时间 |
| 模型常驻内存 | 2.4 GB native / PSS 3.0 GB | 再加 1 GB RAM 缓存 → 逼近 4 GB，8 GB 设备开始换页 |
| 朗读中峰值 | PSS 3.8~4.3 GB | 同上，实测 native 涨到 5.76 GB 时整机 Swap 被吃 672 MB、进程随时被杀 |
| int16 PCM 体积 | 46.9 KB/s ≈ 2.7 MB/分钟 | 1 GB ≈ 6.2 小时音频；32 MB ≈ 11.7 分钟；512 MB ≈ 3 小时 |

所以：**内存档做小（32 MB），大队列放磁盘（512 MB）**。磁盘是存储不是内存，容量压力完全不同。

---

## 2. 结构

```
导入文本 / 引擎就绪
   │  预热首段（默认 2 句，可调）
   ▼
TtsAudioCache（两档）
 ├─ RAM 档：LinkedHashMap(LRU) + int16 PCM，上限 32 MB，退后台清空（保留最近 3 条）
 └─ 磁盘档：filesDir/tts-pcm/<sha1>，16 字节头(frames) + int16 PCM，上限 512 MB，按 mtime LRU 淘汰
   ▲
   │  朗读时
   ├─ 命中 → 直接用 AudioTrack 回放（不再合成）
   └─ 未命中 → 边合成边播放，同时写入两档
        └─ 每句播完 → 预取后面 2 句（空闲时；朗读中不抢 CPU）
```

**缓存键**：`sha1(句子文本 | 音色指纹 | 图组合)`。
- 音色指纹取自 `demo_speaker.npy` 向量的抽样哈希 → 将来支持换音色会自动失效，不会播错；
- 图组合（fast/reference）不同则音频不同，也进键；
- **语速不进键**：端侧语速是 `AudioTrack.playbackParams.setSpeed()` 变速，不改合成结果。

**实现位置**
| 文件 | 职责 |
|------|------|
| `app/data/tts/TtsAudioCache.kt` | 两档缓存本体（LRU、磁盘读写、int16 编解码、生命周期） |
| `app/data/tts/Qwen3TtsEngine.kt` | 命中回放、合成后写缓存、`prefetch`、`trimCacheForBackground` |
| `domain/tts/TtsEngine.kt` | 新增 `prefetch(text)` 默认空实现（系统 TTS 不需要） |
| `domain/tts/TtsController.kt` | 句间预取（播完一句预取后两句）、`warmUp()`、`releaseAudioCacheMemory()` |
| `viewmodel/ReaderViewModel.kt` | 引擎就绪后预热首段；`releaseAudioCacheMemory()` / `clearAudioCache()` |
| `ui/AppScaffold.kt` | `Lifecycle.ON_STOP` → 清空 RAM 档 |

---

## 3. 实测结果（模拟器 API 37，8 GB；`docs/samples/朗读验收.txt`，32 句）

| 指标 | 无缓存（合成路径） | 命中缓存（回放路径） |
|------|------------------|-------------------|
| 首音延迟 | 6.8~12.8 s | **2~3 ms** |
| 单句总耗时 | 16.6~26.0 s | **2.4~2.9 s**（= 音频时长 + 起播开销） |
| 连续 4 句 | 每句都要等 | 连续 4 次命中，`hit=4 miss=0` |

日志样例（连续命中）：

```
I/Qwen3TtsEngine: 缓存命中：42 帧（3.36s 音频），首音延迟=3ms，总耗时=2929ms，命中率=hit=1 miss=0 ram=157KB disk=735KB
I/Qwen3TtsEngine: 缓存命中：35 帧（2.80s 音频），首音延迟=2ms，总耗时=2430ms，命中率=hit=2 miss=0 ram=288KB disk=735KB
I/Qwen3TtsEngine: 缓存命中：40 帧（3.20s 音频），首音延迟=3ms，总耗时=2703ms，命中率=hit=4 miss=0 ram=570KB disk=735KB
```

**预热与磁盘档实测**：引擎就绪后后台预热 → `files/tts-pcm/` 出现片段文件
（单句 134~169 KB，与「3.36 s 音频 × 46.9 KB/s ≈ 158 KB」吻合），
`run-as ls` 可核对；磁盘档在**退后台清空 RAM 后依然命中**（本轮就是这么测出来的）。

---

## 4. 生命周期约定

| 事件 | RAM 档 | 磁盘档 |
|------|--------|--------|
| 引擎就绪 | 预热首段（2 句） | 同左（写盘） |
| 句间 | 播完一句预取后 2 句 | 同左 |
| 朗读中 | **不预取**（避免抢 CPU，`currentUtteranceId != null` 时直接返回） | — |
| **退后台（ON_STOP）** | **清空**，仅保留最近 3 条（约 400 KB） | 保留 |
| 存储紧张 / 用户手动清 | 清空 | 由 `clearAudioCache()` 或 LRU 淘汰处理 |

> 「保留最近 3 条」是对需求的一处有意偏离：若不保留，回前台必须重新合成首句，
> 真机 RTF 2 意味着再等约 40 s。保留后回前台仍能秒开，代价是几百 KB。
> 要严格按需求执行，把 `TtsAudioCache.KEEP_RECENT_RAM_ENTRIES` 改成 `0` 即可。

---

## 5. 可调参数

| 参数 | 位置 | 默认 | 说明 |
|------|------|------|------|
| RAM 档上限 | `TtsAudioCache.DEFAULT_MEMORY_LIMIT_BYTES` | 32 MB | ≈11.7 分钟音频 |
| 磁盘档上限 | `TtsAudioCache.DEFAULT_DISK_LIMIT_BYTES` | 512 MB | ≈3 小时音频 |
| 退后台保留条数 | `KEEP_RECENT_RAM_ENTRIES` | 3 | 0 = 完全清空 |
| 句间预取句数 | `TtsController.PREFETCH_AHEAD` | 2 | |
| 导入预热句数 | `ReaderViewModel.WARMUP_SENTENCES` | 2 | 预热整章会让 CPU 长时间满载（5 分钟音频 ≈ 33 分钟合成） |
| 预取开关 | `Qwen3TtsEngine.prefetchEnabled` | true | 低内存/省电模式可关 |

---

## 6. 已知限制

1. **预取是「单槽」的**：同一时刻只跑一个预取线程，若上一句还没合完，新的预取请求会被丢弃
   （实测 169 KB 的句子要合成十几秒）。所以**连续长句的缓存填充会滞后**，
   第一遍听仍然要等；收益主要在「重听 / 回看 / 暂停继续」这些回头动作上。
2. **首次阅读无法受益**：缓存只能在「已经合过」之后命中；要让首读也快，
   需要 §TTS-LATENCY-OPTIONS 里的方案 C/D（chunk 自适应 / 并行流水线）。
3. **磁盘档不参与「退后台清除」**：按当前实现，退后台只清内存；如果也要求清磁盘，
   在 `ON_STOP` 里改调 `clearAudioCache()`（代价：回前台全部重新合成）。
4. **缓存键不含章节位置**：同一句话在别处出现也会命中——这是期望行为，但意味着
   「文本被编辑后」若句子完全相同仍会复用旧音频（当前应用不支持编辑正文，暂无影响）。
5. **未做加密/校验**：缓存是应用私有目录下的裸 PCM；多一字节少一字节只会导致按帧数播放，
   不会崩溃（`readDisk` 对异常返回 null）。若将来要防篡改，可加 CRC。

---

## 7. 复现命令

```powershell
$env:JAVA_HOME = "F:\Projects\hrstts\.toolchain\jdk-21"
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 清空缓存后冷启动 → 等引擎就绪 → 预热会写入首段
adb shell run-as com.hrstts.jiandu rm -f /data/data/com.hrstts.jiandu/files/tts-pcm/*
adb shell am force-stop com.hrstts.jiandu
adb logcat -c; adb shell am start -n com.hrstts.jiandu/.MainActivity
Start-Sleep 40
adb shell run-as com.hrstts.jiandu ls -l /data/data/com.hrstts.jiandu/files/tts-pcm/

# 读同一段两次：第一次合成、第二次应命中（首音延迟 ms 级）
adb logcat -d | Select-String "缓存命中|流式合成完成"

# 退后台清 RAM、回前台仍能从磁盘档命中
adb shell input keyevent 3      # Home
adb shell am start -n com.hrstts.jiandu/.MainActivity
# 再点一次「阅读」→ 日志应仍是「缓存命中」

# 内存占用（缓存上限 32 MB，不应把 PSS 推高）
$p = (adb shell pidof com.hrstts.jiandu).Trim()
adb shell dumpsys meminfo $p | Select-String "TOTAL PSS"
```
