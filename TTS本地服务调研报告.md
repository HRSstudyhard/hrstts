# Windows 本地 TTS 服务 → 局域网 Android 中文小说朗读：可行性调研

> 【文档】= 官方文档/README 明确写出；【推断】= 我基于数据的推理。
> 机器：RTX 4060 Laptop 8GB / R7 7840H / 15.2GB RAM / Py3.13.7 / 驱动 537.53。

## 1. Qwen3-TTS（QwenLM/Qwen3-TTS，PyPI `qwen-tts`）

已开源（2026-01-22，Apache-2.0，arXiv 2601.15621）。PyPI 最新 `qwen-tts 0.1.1`。

**模型与体积**（HF API 实测文件字节数）：

| 模型 | 参数量 | 下载体积 | LLM 权重(bf16) | 语音 tokenizer |
|---|---|---|---|---|
| 12Hz-1.7B-CustomVoice / VoiceDesign / Base | 1.7B | **4.21 GB** | 3655.8 MB | 650.7 MB |
| 12Hz-0.6B-CustomVoice / Base | 0.6B | **2.33 GB** | 1727.7 MB | 650.7 MB |
| 12Hz-25Hz 系列 | 0.6B/1.7B | — | 文档提及，未列入发布表 | — |

**显存**【文档】权重：1.7B fp32 ~6.8GB / **bf16 ~3.4GB**；0.6B fp32 ~2.4GB / **bf16 ~1.2GB**；另加 **2–4GB 激活**。FAQ：最低要求 "8GB+ VRAM / 16GB+ RAM"；"1.7B 推荐 16GB+ VRAM"；**"0.6B：8GB 显存足够"**。vLLM 章：0.6B 最低 8GB，**1.7B 最低 12GB**。
→ **【推断】8GB 上 1.7B 是"勉强"（4.3GB 权重 + 激活极易 OOM），0.6B 才稳。**

**Consumer GPUs 表**【文档】只列 RTX 3090/4090 (24GB) 与 **RTX 3080 10GB → "Development only，仅 0.6B"**。**8GB 卡根本没被列出**，最接近的 10GB 都只推荐 0.6B。

**速度**【文档】RTF：1.7B A100 ~0.1 / V100 ~0.15；0.6B A100 ~0.06 / V100 ~0.09；0.6B 比 1.7B 快 ~1.7×。全部是数据中心卡，**无消费级数据**。【推断】4060 Laptop(AD107, 8GB, ~110W) 弱于 V100，1.7B 实际 RTF 约 0.3–0.5。

**流式：宣传与实际不符（最关键）。**
- 【文档】宣称 Dual-Track 架构、单字符输入即出首包、**端到端 97ms**。表：1.7B 97ms / 0.6B 95ms。**注脚写明"A100 + FlashAttention-2 实测"**——不是你这张卡。
- 【文档】**Python 包的"流式"是假的**：`non_streaming_mode=False` 只是"模拟流式文本输入，不启用真正流式生成"；FAQ 原文 **"Python package streaming support is coming soon."**；返回值是 `Tuple[List[np.ndarray], int]` 整体波形，**没有 yield chunk**。真流式只在 DashScope 云端 API。
- 【文档】**流式会导致长文语速漂移**：Issue #239「>100 字符的长文本，语速向句尾逐渐加快」，环境 RTX 5060；官方 PR #362 直接把 `generate_voice_clone` 默认值改成 `non_streaming_mode=True` 来修。三个页面默认值互相矛盾（quickstart 说 False，API 页签名叫 True）。

**服务/接口**【文档】**没有** OpenAI 兼容 `/v1/audio/speech`。只有 Gradio：`qwen-tts-demo <model> --ip 0.0.0.0 --port 8000 [--share --concurrency 16 --ssl-certfile]`。要 HTTP API 得自己包 FastAPI。vLLM-Omni 有 day-0 支持但**仅离线推理，online serving 未发布**。

**Python / Windows**【文档】classifiers 含 3.9–**3.13**，但推荐 **3.12**；依赖 `transformers==4.57.3, accelerate==1.12.0, gradio, librosa, torchaudio, soundfile, **sox**, onnxruntime, einops`——**无 torch 版本约束**，`sox` 在 Windows 需另装 SoX 二进制。flash-attn **可选**（不装只是慢），但 **Windows 无预编译 wheel**（flash-attn #1885，需自行编译，且文档写"编译需 96GB+ RAM"，你只有 15.2GB）。其他已知 bug：#260 4-bit 量化加载报错、#369 25Hz tokenizer 注意力掩码 bug、#343 微调模型短句开头 1–2s 音色不稳（greedy 下约 15% 短句受影响）。【推断】驱动 537.53 属 R535 分支，较旧（约 CUDA 12.2 上限），装 cu126/cu128 版 torch 前建议先升级驱动。

## 2. 备选方案对比（8GB / Windows / 中文长文逐句）

| 方案 | 体积 | 显存 | RTF / 首音延迟 | 流式 | Windows | HTTP API | 中文口碑 |
|---|---|---|---|---|---|---|---|
| **GPT-SoVITS v2Pro/v4** | ~2–3GB | v2Pro 略高于 v2【文档】 | **RTF 0.028 @4060Ti**、0.014 @4090【文档】 | ✅ 内置 | ✅ **官方一键整合包(.7z)，双击 go-webui.bat** | ✅ `api_v2.py`，FastAPI，默认 `127.0.0.1:9880` | 很好，小说朗读社区主流 |
| IndexTTS-2.5 | **5.11GB** | 【推断】bf16 ~4GB 可跑 | RTF 0.207(bf16)/0.206(fp32) @4090【文档】；7字 0.287 | ❌ README 无 | ⚠️ 需 `uv`，无 Windows 专章 | ❌ 仅 webui:7860 + vLLM | 好，0.8B，工业级 |
| CosyVoice2/3 | 0.5B ~1–2GB | 低 | **双流式延迟 150ms**【文档】 | ✅ 文本+音频双流 | ⚠️ 官方只给 conda/docker | ✅ `runtime/python/fastapi/server.py --port 50000` | 好；CER 0.71(CV3) |
| Fish-Speech S2-Pro | **10.26GB** | 4B，8GB 紧张 | RTF 0.195、**首音 ~100ms @H200** | ✅ SGLang 引擎 | ⚠️ SGLang/Triton 难 | ✅ 有 api server | 好，但**非商用授权** |
| F5-TTS | 0.3B | 低 | 0.1467 @L20(PyTorch)；253ms/0.0394(TRT) | ⚠️ 需 Triton+TRT | ⚠️ 栈重 | ⚠️ 需自建 | 中上，CER 1.52 |
| Kokoro-82M | **312MB** | 极低/可CPU | 极快【文档】82M | — | ✅ pip 即可 | ❌ 需自建(kokoro-fastapi) | **偏弱**，misaki[zh] 口音明显 |
| ChatTTS | ~1GB | **≥4GB**【文档】 | RTF ~0.3 @4090 | ❌ | ✅ | ⚠️ 需自建 | 对话向；**官方承认稳定性差** |

## 3. 中文长文本的已知坑

1. **自回归模型的"跑飞"是通病**：ChatTTS FAQ 原文承认 "Model stability is not good enough... typically occurs with autoregressive models... generally difficult to avoid"；CosyVoice 为此专门加了 RAS（重复感知采样）。
2. **实测劣化**：GPT-SoVITS Issue #579——API 调用**中文开头几个字被吞**，且出现整句**重复两遍**。Qwen3-TTS Issue #239——>100 字**语速递增**。
3. **逐句合成 vs 整段**【推断】：逐句最稳（可控长度、失败可重试），代价是丢失跨句语调、句间需拼接。建议单句 ≤50–80 字（Qwen3-TTS 文档明确 "Avoid extremely long sentences"），句尾补 **200–300ms 静音**或轻微交叉淡入淡出，并**固定 seed + 复用同一个 `voice_clone_prompt`**（Qwen3-TTS 文档：缓存 prompt 后 <1s/次）。
4. **文本正则化**【文档】：CosyVoice3 无传统前端即支持数字/特殊符号；GPT-SoVITS 走 ttsfrd，缺省回落 WeTextProcessing（**pynini 在 Windows 上难装**）。Qwen3-TTS 未提独立 TN 模块，只称"对噪声文本鲁棒"。**【推断】小说里的年份/数字/英文缩写应在服务端用规则先转成中文汉字**，多音字（行/重/长/了/差）交给模型，实测有问题再建替换表。

## 4. 局域网暴露要点

- **绑定**：`127.0.0.1` 只回环，必须 `0.0.0.0`。【文档】GPT-SoVITS `api_v2.py` **默认就是 127.0.0.1**，必须加 `-a 0.0.0.0`。
- **防火墙**（Microsoft Learn 原文示例语法）：
  ```
  netsh advfirewall firewall add rule name="TTS 8000" dir=in action=allow protocol=TCP localport=8000
  # 等价 PS:
  New-NetFirewallRule -DisplayName "TTS 8000" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
  ```
  首次绑定 `0.0.0.0` 时 Windows 会弹"允许访问"对话框。【推断】选"专用网络"；WiFi 被判为"公用网络"时入站默认被拦截。
- **Android 明文 HTTP**【文档】：**"Starting with Android 9 (API level 28), cleartext support is disabled by default."** 解决：
  - 粗放：`<application android:usesCleartextTraffic="true">`（该属性 **API 38+ 将被忽略**，官方让改用 Network Security Config）
  - 推荐：`res/xml/network_security_config.xml` 里 `<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="true">192.168.1.50</domain></domain-config>`，再在 manifest 上挂 `android:networkSecurityConfig="@xml/network_security_config"`
  - 只对**你自己的 App（含其 WebView）**生效；**手机 Chrome 直接打开 http://IP:8000 不受影响**，是最快的验证通路。
- **地址**：【推断】做 **DHCP 保留/静态 IP** 最省事（`ipconfig` 查 IPv4）。mDNS `.local` 是否被 Android 原生解析我**未能取得可靠来源，不做结论**。**USB 兜底**：`adb reverse tcp:9880 tcp:9880`，手机侧访问 `127.0.0.1:9880`，完全绕开防火墙与网段问题。
- **不要让笔记本睡眠**：接入电源并设"从不睡眠"，否则服务直接断。

## 5. 推荐

**首选 GPT-SoVITS（v2Pro/v4）+ `api_v2.py`**：唯一有**同档次显卡实测数字**的方案（4060 Ti 上 RTF 0.028，即 4 分钟音频约 6.7 秒生成），官方 Windows 一键整合包绕开全部编译地狱，`api_v2.py` 自带 FastAPI + 4 档 `streaming_mode` + `text_split_method`，中文小说朗读的社区验证最充分——8GB 显存绰绰有余且长跑稳定。
（若追求更高音质且能接受折腾：IndexTTS-2.5，5.11GB、RTF 0.207，但有中文社区贴称"8G显存可用"，我未独立验证。）
**Qwen3-TTS 在 8GB 上不推荐**：1.7B 被官方标注最低 12GB，8GB 卡根本没进官方 Consumer GPU 表；且 Python 包**没有真流式**（FAQ：coming soon），97ms 是 A100 上的数字，其流式模式还正是长文语速漂移的成因（#239 → PR #362）。

**最低安装成本验证通路（最快备选）**：**Kokoro-82M**（312MB，`pip install kokoro`，CPU 就能跑）或直接拿 **GPT-SoVITS 整合包**——两者都能在 10 分钟内打通"手机浏览器 → HTTP → 听到中文"，先验证链路再谈音质。
