# P3 专项：Qwen3-TTS 端侧集成（已实现并实测）

> 目标：把 **Qwen3-TTS** 作为首选朗读引擎，在设备本地合成语音；模型不可用时自动回退系统 TTS。
> 状态：**已实现、已在设备上跑通端到端合成与播放**；性能不满足实时（与原模型一致），需要预缓冲。

---

## 1. 实现结构

```
app/                                  ← 只依赖门面，classpath 上不出现 LiteRT（关键约束）
├─ data/tts/Qwen3TtsEngine.kt         我写的适配器：实现 TtsEngine，合成→AudioTrack 播放，可中断
├─ data/tts/Qwen3ModelManager.kt      模型清单/下载（断点续传）/完整性校验/内存与存储检查
├─ data/tts/SystemTtsEngine.kt        系统 TTS（兜底）
├─ ui/settings/VoiceEngineCard.kt     「我的」底部：当前引擎、模型状态、下载/删除
└─ viewmodel/VoiceEngineViewModel.kt  模型管理状态
qwen3tts/                             ← 独立 Android library 模块（不启用 KSP / Compose / Room）
├─ Qwen3TtsEngine.kt                  官方参考实现原样移植（talker + MTP + codec 宿主循环）
├─ Qwen3TtsSynthesizer.kt             稳定门面：synthesize(text, language, …) → Speech(audio, 24kHz)
├─ QwenBpeTokenizer.kt                字节级 BPE（官方实现移植）
└─ Npy.kt                             .npy/.npz 解析（宿主侧嵌入表）
```

**为什么必须拆模块**：LiteRT AAR 的字节码会让 `app` 的 KSP 崩溃。
实测两种失败：① Kotlin 元数据版本不兼容（需 Kotlin ≥ 2.2）；② Room 的 KSP 处理器遇到
`unexpected jvm signature V`（Room 2.6.1 的缺陷，升级 Room 2.7.2 解决）。
用 `implementation(project(":qwen3tts"))` 后，LiteRT 只出现在运行期 classpath（已用
`gradlew :app:dependencies` 验证），KSP 不再看到它。

**引擎选择与回退**：`ReaderViewModel` 启动时若模型完整安装则装载 Qwen3-TTS，否则用系统语音；
若端侧引擎随后报错（ABI 不支持、模型损坏、内存不足），自动切换系统语音并提示用户；
**模型下载完成后界面观察到 `Ready` 会即时热切换引擎**，无需重启。

**模型下载**：`Qwen3ModelManager` 按清单下载（`hf-mirror.com` → `huggingface.co` 顺序回退）、
`Range` 断点续传（`.part` 临时文件）、按「文件存在 + 最小体积」校验完整性、下载前后检查存储与内存；
失败原因翻译为可行动的中文提示。⚠️ 需要 `INTERNET` 权限（见 DEV.md §6）。

## 2. 参考来源（Apache-2.0）

- `google-ai-edge/litert-samples` → `samples/litert/text_to_speech_lm/kotlin_cpu/android`
  （Kotlin 宿主循环；已存档于 `docs/reference/litert-samples/`）。
- 模型：`litert-community/Qwen3-TTS-12Hz-0.6B-Base`，经 `hf-mirror.com` 下载（HuggingFace 直连在本机超时）。
- 依赖：`com.google.ai.edge.litert:litert:2.1.5`（AAR 含 `arm64-v8a` / `armeabi-v7a` / `x86_64`）。

## 3. 模型清单（快速档，实测 1592.4 MB）

| 文件 | 实测大小 | 作用 |
|------|---------|------|
| `talker_int4.tflite` | 244.1 MB | Talker LM（int4，prefill_32/prefill_128/decode） |
| `mtp_folded_int8.tflite` | 219.0 MB | 折叠 int8 MTP（每帧 1 次调用） |
| `codec_partA.tflite` | 155.4 MB | 编解码器 transformer 段 |
| `codec_partB.tflite` | 280.1 MB | 编解码器卷积上采样段 |
| `tables/text_embedding_fp16.npy` | 593.5 MB | 文本嵌入表（宿主侧） |
| `tables/mtp_embeddings_fp16.npy` | 60.0 MB | 15 个 MTP 嵌入表 |
| `tables/text_projection_fp32.npz` | 24.0 MB | 文本投影 MLP |
| `tables/codec_embedding_fp32.npy` | 12.0 MB | codec 嵌入表 |
| `vocab.json` / `merges.txt` | 2.6 / 1.6 MB | Kotlin 侧 BPE 词表 |
| `voices/demo_speaker.npy` | 4 KB | 演示音色（x-vector） |

可选精度档：`talker_fp32` / `mtp_fp32` / `codec_decoder_fp32`（更大更慢，逐 token 与 PyTorch 一致）。
设备端存放位置：`files/qwen3-tts/`（应用私有目录）；开发期可用
`scripts/push-qwen3-model.ps1` 直接推送，避免重复下载。

## 4. 实测结果（本机 Android 模拟器，x86_64，8 GB，无 GPU 加速）

### 4.1 整句合成（优化前基线）

| 项目 | 结果 |
|------|------|
| 引擎装载 | `libLiteRt.so`(x86_64) 加载成功，CPU 加速器注册，三图 + 宿主表加载完成 |
| 端到端合成 | `合成完成：119 帧 / 9.52s，RTF=4.29，prefill=4285ms talker=9189ms mtp=11195ms codec=16140ms` |
| 播放 | `AudioTrack: stop(15): called with 228480 frames delivered`（228480/24000 = 9.52 s） |
| 连续朗读 | 第一句播完自动进入下一句（界面高亮与提示句同步推进） |
| 引擎选择 | 「我的」底部显示「当前朗读引擎：Qwen3-TTS（端侧）」「端侧模型已安装：快速图组」 |

### 4.2 流式分块播放（优化后，`CHUNK_FRAMES = 16`）

PCM 边生成边解码边播放（编解码器分块解码与一次性解码逐样本等价，见 §5 注），自动连续朗读 4 句：

| 句子 | 帧数 | 音频时长 | **首音延迟** | 整句总耗时 | 其中 codec |
|------|------|---------|------------|-----------|-----------|
| 1 | 131 | 10.48 s | **6.83 s** | 53.4 s | 25.4 s |
| 2 | 25 | 2.00 s | **9.35 s** | 14.4 s | 5.5 s |
| 3 | 112 | 8.96 s | **9.77 s** | 48.9 s | 19.7 s |
| 4 | 31 | 2.48 s | 18.92 s | 25.9 s | 6.8 s |

**首音延迟：约 41 s（整句合成完才出声）→ 6.8 s（首句），提升约 6 倍。**
首块耗时构成（第 1 句实测对照）：prefill 1.07 s + 16 帧生成 ≈ 2.4 s + 首次编解码 ≈ 2.8 s ≈ 6.3 s。

代价与现象：
- 编解码器每次调用有较大固定开销（本例 16 帧一块 ≈ 2.8 s），分块越多总耗时越长
  （codec 25.4 s / 总量 53.4 s）；`CHUNK_FRAMES` 是「首音延迟 ↔ 总耗时」的调节旋钮。
- RTF 仍 ≈ 4~5（模拟器，无加速），播放过程中 `AudioTrack` 会周期性 underrun（消费快于生产），
  即在慢设备上仍会有停顿；官方 Pixel 8a 同图组为 RTF ≈ 2.06，停顿会明显减少但仍存在。

### 4.3 对照

| 指标 | 整句合成 | 流式分块（本次） | 官方 Pixel 8a 参考 |
|------|---------|----------------|------------------|
| 首音延迟（10 s 音频） | ≈ 41 s | **≈ 6.8 s** | ≈ 20 s（推算：RTF 2.06 整句合成完才出声） |
| RTF | 4.29 | 4~5 | 2.06 |

> 结论：**「首音 1 秒内」在本模型上不可达**——首块固定成本（编解码器首次调用 + 16 帧生成）
> 就有数秒。要再降只能减小 `chunkFrames`（首批音频更短）或换更快后端（NPU/GPU，按机型）。

## 5. 已知限制（重要）

1. **不是实时合流**：官方模型卡实测 Pixel 8a 也是 RTF≈2.06（fp32 图组 6.7），即合成比播放慢一倍。
   已实现**流式分块播放**（首音 ~6.8 s，见 §4.2），但 RTF > 1 意味着播放期间仍会周期性 underrun
   （慢设备上更明显）；真正无缝需要「生成快于播放」或并行流水线（见 §6）。
2. **句级高亮**：端侧合成为流式给出 PCM，但帧位置与文本 token 无显式对齐，当前按「已写出采样数
   / 预估总采样数」粗略推进字符高亮；精确字级高亮需要建立帧→token 时间轴。
3. **内存**：模型常驻约 1.2~1.6 GB（实测进程 RSS ≈ 1.2 GB）。4 GB 设备会被系统杀掉
   （本机 4 GB 模拟器实测：整机 low-memory kill 风暴，应用被回收）；已按「内存 < 6 GB 默认推荐
   系统语音」处理，并在卡片里给出提示。
4. **音色**：当前固定使用仓库自带 `demo_speaker.npy`；自定义音色需在 PC 上用官方
   `extract_speaker_embedding.py` 生成 x-vector 再导入。
5. **仅 CPU 后端**：`CompiledModel(Accelerator.CPU)`；NPU/GPU 路线见模型卡，需按机型适配与实测。
6. **下一句预取暂未实现**：控制器仍是「播完一句再合成下一句」。实测分析表明在本模型 RTF≈2~4 的
   前提下，只预取 1 句仍无法消除句间停顿（合成一句的时间是播放一句的 2~4 倍），因此性价比低于 §6
   的并行流水线；若后续要加，接口与设计已确定（`TtsEngine` 增加 `supportsPrefetch` + `enqueue`，
   控制器在播放当前句时预提交下一句并维护 id→片段映射）。

> 注：分块解码的正确性依据是「编解码器为因果结构、左侧上下文只需 25 帧」——参考实现自身就按
> 64 帧窗口 + 25 帧左上下文分块解码，并在尾块用零填充，本实现的任意切分与之同理、逐样本等价。

## 6. 后续可选优化（非阻塞）

| 优先级 | 事项 | 收益（预估） |
|-------|------|------------|
| 高 | **编解码器与 talker 并行流水线**：talker 线程产帧入队，codec 线程按块解码并直写 `AudioTrack`（两者是不同 LiteRT 图，可并行） | 总耗时从「生成 + 解码」变为 `max(生成, 解码)`；本机实测 codec 占 25.4s / 53.4s，预计整句耗时减半、句间停顿明显变短 |
| 中 | 预取下一句（接口设计见 §5.6） | 与上面叠加才有明显效果；单独做收益有限 |
| 中 | `chunkFrames` 自适应（按设备 RTF 调 8/16/32） | 快设备更小首音延迟、慢设备更少总开销 |
| 中 | 帧级进度 → 文本 token 时间轴 | 字级高亮跟随 |
| 低 | 音色管理界面（导入 x-vector） | 个性化 |
| 低 | NPU/GPU 后端按机型选择 | 真机提速（Pixel 8a 上 GPU 反而更慢，需逐机型实测） |
