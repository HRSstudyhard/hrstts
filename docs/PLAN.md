# 「简读」—— Android 文本阅读器 · 项目开发计划

> 项目代号：**简读（JianDu）**　|　文档版本：v1.0　|　最后更新：2026-02-19
> SDK 路径：`C:\Users\H\AppData\Local\Android\Sdk`（写入 `local.properties` 的 `sdk.dir`）

> **实施状态（最新）**：P0 / P1 / P2 / P3 / P4 已完成并上机验证（M0、M1、M2、M4 达成，
> M3 功能达成、性能指标未达「实时」——实测 RTF 4.29，官方 Pixel 8a 参考值 2.06，见
> [P3-STATUS.md](P3-STATUS.md)）；**P5（打磨、测试与发布）工程项已完成**：边界加固、i18n 与无障碍、
> R8 混淆规则与 Release 构建、`SPEC.md` / `TEST.md` 文档；**发布签名按用户要求暂缓**，
> 因此 M5 只达成「可构建、可自测」，尚未产出正式签名包。
> 进度与证据见 [PROGRESS.md](PROGRESS.md)，规格见 [SPEC.md](SPEC.md)，测试见 [TEST.md](TEST.md)，
> 环境与构建方式见 [DEV.md](DEV.md)。

---

## 1. 项目概述

### 1.1 目标

开发一款 **Android 原生文本阅读器**，核心能力为「导入 → 阅读 → 聆听」闭环：

1. **文本导入**：从本地存储选择文本文件，自动分章节、分段落展示；
2. **阅读**：长按段落选中文本，在选中框上方弹出「阅读」选项，一键朗读（TTS）；
3. **我的**：左侧抽屉记录所有打开过的文本，点击即可切换当前阅读内容；
4. **界面**：完整遵循 **Material Design 3（MD3）** 设计语言；
5. **语音引擎**：集成 **Qwen3-TTS**（https://github.com/QwenLM/Qwen3-TTS）作为首选端侧语音合成引擎，Android 系统 TTS 作为兜底。

### 1.2 需求对照表

| # | 原始需求 | 实现方案（概要） | 里程碑 |
|---|---------|----------------|-------|
| 1 | 文本阅读器 | Compose + MD3 单 Activity 阅读应用 | M1 |
| 2 | 三大功能：文本导入 / 阅读 / 我的 | TopAppBar 右上导入 + 正文长按阅读 + 左上抽屉历史 | M2 / M4 |
| 3 | 右上角导入 → SAF 选文件 → 从文件头显示 → 自动分章节、分段落 → 段落长按可选中 | SAF(`ACTION_OPEN_DOCUMENT`) + 章节/段落解析器 + 段落级选择 | M1–M2 |
| 4 | 长按文本 → 选中框上方出现「阅读」→ 点击朗读 | 自定义文本选择工具栏（TextToolbar）注入「阅读」项 + TTS 引擎 | M2–M3 |
| 5 | 左上角「我的」→ 左侧弹出历史 → 点击替换当前文本 | ModalNavigationDrawer + Room 历史库 | M4 |
| 6 | MD3 风格 | Material 3 主题、动态取色（Android 12+）、Material You 组件 | M1 |
| 7 | Qwen3-TTS 模型 | LiteRT 端侧 0.6B 模型（主）+ 系统 TTS（兜底）+ 引擎抽象层（可扩展 GGUF/服务端） | M3 |

---

## 2. 技术选型

| 类别 | 选型 | 依据 |
|------|------|------|
| 语言 | Kotlin 2.x | 官方首选，协程支持 TTS/IO |
| UI | Jetpack Compose + Material 3 | MD3 硬性要求，声明式 UI 便于实现选择与抽屉 |
| 架构 | 单 Activity + MVVM（ViewModel + StateFlow） | 阅读器状态（进度/选择/朗读）集中管理 |
| 文件访问 | Storage Access Framework（SAF） | 免存储权限，持久化 URI 授权（`takePersistableUriPermission`） |
| 持久化 | Room（History + Bookmark 表） | 历史记录、阅读进度 |
| 文本解析 | 自研 Parser（正则 + 状态机） | 章节/段落规则可控可调 |
| TTS（主） | Qwen3-TTS-12Hz-0.6B-Base（LiteRT / TFLite） | 官方社区提供 Android 端侧模型与示例（见 §8） |
| TTS（兜底） | Android `TextToSpeech`（系统引擎） | 零下载、即开即用、离线 |
| 音频播放 | `AudioTrack`（PCM 流式） | Qwen3-TTS 输出 12Hz 离散 token，需自建流式播放 |
| 构建 | Gradle KTS + AGP 8.x | 与最新 Compose/AGP 兼容 |

**环境基线**：JDK 17 · `minSdk 26`（覆盖 95%+ 设备）· `targetSdk 35` · `compileSdk 35`。项目根目录生成 `local.properties`：
```properties
sdk.dir=C\:\\Users\\H\\AppData\\Local\\Android\\Sdk
```

---

## 3. 总体架构

```
┌────────────────────────── MainActivity (单 Activity) ──────────────────────────┐
│  AppScaffold                                                                   │
│  ├─ ModalNavigationDrawer ──「我的」历史列表（Room → HistoryUiState）            │
│  └─ Scaffold                                                                    │
│      ├─ TopAppBar：左 = 菜单(☰「我的」) ｜ 右 = actions(「导入」⬆)                │
│      └─ ReaderScreen（阅读区）                                                   │
│          ├─ ChapterList / LazyColumn（段落懒加载，防大文件 OOM）                  │
│          ├─ SelectionLayer：长按段落 → 选中框 + 上方工具栏(复制/阅读)             │
│          └─ TtsProgressBar：朗读进度、当前句高亮                                │
└────────────────────────────────────────────────────────────────────────────────┘
         │                      │                      │
   DocumentRepository      SelectionManager        TtsEngine(接口)
   ├─ SafSource           ├─ 段落级选择状态          ├─ Qwen3TtsEngine (LiteRT)
   ├─ ChapterParser       └─ TextToolbar 扩展        ├─ SystemTtsEngine (兜底)
   ├─ ParagraphParser                             └─ EngineFactory（能力探测/回退链）
   └─ HistoryDao (Room)                                 │
                                        ┌───────────────┴──────────────┐
                                        │ AudioTrack 流式播放（12Hz 码本解码）│
                                        └──────────────────────────────┘
```

**核心数据模型**：

```kotlin
data class Book(           // 一次导入的文本
    val uri: Uri, val name: String, val encoding: Charset,
    val chapters: List<Chapter>, val openedAt: Long
)
data class Chapter(        // 章节
    val index: Int, val title: String?,
    val paragraphs: List<Paragraph>, val startOffset: Long
)
data class Paragraph(      // 段落
    val index: Int, val text: String, val startOffset: Long
)
```

---

## 4. 项目目录结构（规划）

```
hrstts/
├── local.properties                 # sdk.dir=C:\Users\H\AppData\Local\Android\Sdk
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml        # 版本目录：Compose BOM / Room / LiteRT
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # 无需存储权限（SAF）
│       │   ├── java/com/hrstts/jiandu/
│       │   │   ├── MainActivity.kt
│       │   │   ├── ui/
│       │   │   │   ├── theme/            # MD3：Color.kt / Theme.kt / Type.kt
│       │   │   │   ├── AppScaffold.kt    # 抽屉 + TopAppBar
│       │   │   │   ├── reader/           # ReaderScreen / ParagraphItem / SelectionLayer
│       │   │   │   ├── history/          # HistoryDrawer（「我的」）
│       │   │   │   └── components/       # 自定义 TextToolbar（含「阅读」）
│       │   │   ├── domain/
│       │   │   │   ├── parser/           # ChapterParser / ParagraphParser / EncodingDetector
│       │   │   │   ├── tts/              # TtsEngine / TtsCallback / TtsPlaybackState
│       │   │   │   └── model/            # Book / Chapter / Paragraph / HistoryEntry
│       │   │   ├── data/
│       │   │   │   ├── SafSource.kt      # SAF 读取 + URI 持久授权
│       │   │   │   ├── db/               # Room：HistoryDao / AppDatabase
│       │   │   │   └── tts/
│       │   │   │       ├── Qwen3TtsEngine.kt   # LiteRT 推理 + AudioTrack 流播
│       │   │   │       ├── SystemTtsEngine.kt  # Android TextToSpeech
│       │   │   │       └── ModelManager.kt     # 模型下载（HF 镜像/断点续传）
│       │   │   └── viewmodel/            # ReaderViewModel / HistoryViewModel / TtsViewModel
│       │   └── res/                      # strings（中英）/ themes / drawable
│       └── androidTest/  &  test/        # UI 测试 / Parser 单测
└── docs/
    ├── PLAN.md                          # 本计划
    ├── SPEC.md                          # 详细功能规格（阶段进行中维护）
    └── TEST.md                          # 测试用例清单（M2 起维护）
```

---

## 5. 开发阶段划分

> 总工期估算：**约 16 人日（3~4 周）**。每个阶段结束均需通过其「验收标准」方可进入下一阶段。

### Phase 0 — 工程搭建与 MD3 基座（1 天）

**目标**：跑通可运行的空壳 App，具备 MD3 主题与页面骨架。

任务清单：
- [ ] 初始化 Gradle 项目（AGP 8.x、Kotlin 2.x、Compose BOM），配置 `local.properties` 指向 `C:\Users\H\AppData\Local\Android\Sdk`；
- [ ] 引入 Material 3 依赖，实现动态取色（Dynamic Color，Android 12+）与静态回退配色；
- [ ] 搭建 `AppScaffold`：`ModalNavigationDrawer`（占位）+ `TopAppBar`（左菜单图标、右「导入」图标按钮）；
- [ ] 建立 MVVM 骨架：`ReaderViewModel` / `HistoryViewModel` 空实现 + StateFlow 数据流；
- [ ] 添加 Room 依赖与 `HistoryEntry` 实体骨架（不落业务逻辑）。

**交付物**：可安装运行的 APK；MD3 主题生效；TopAppBar 左右图标就位（点击暂无行为或显示占位提示）。
**验收标准**：在模拟器/真机上启动无崩溃；`AndroidManifest` 无任何存储权限申请。

---

### Phase 1 — 文本导入与解析引擎（2 天）

**目标**：完成「文本导入」全链路：选文件 → 读内容 → 自动分章节、分段落 → 从文件头渲染。

任务清单：
- [ ] **SafSource**：`ACTION_OPEN_DOCUMENT`（`text/*` 过滤），`takePersistableUriPermission` 持久授权，记录 `displayName`；
- [ ] **EncodingDetector**：BOM 探测 → UTF-8 校验 → GBK/GB18030 回退；
- [ ] **ChapterParser**：正则识别章节标题（`第[0-9零一二三四五六七八九十百千万]+[章节回卷集部篇]`、`Chapter N`、`楔子/序章/前言/后记/尾声/番外` 等），无标题文本按固定字数（如 8000 字）软分章；
- [ ] **ParagraphParser**：空行分段 → 超长段（>500 字）按句末标点（`。！？…”』`）二次切分（阈值可配置）；
- [ ] 大文件策略：>20MB 弹提示；>2MB 走**流式/分块解析**，`LazyColumn` 懒加载渲染，避免 OOM；
- [ ] `ReaderViewModel` 接入：导入 → 解析 → 更新 UI 状态 → 默认滚动至文件头（第一章第一段）。

**交付物**：能导入 `.txt` 文件并正确显示章节标题与段落；GBK/UTF-8 中文文本均不乱码。
**验收标准**：
- 导入 100KB / 2MB / 20MB 三类测试文本，解析与滚动流畅（无 ANR）；
- 章节标题识别准确率 ≥ 95%（用 10 个异构样本文本回归）；
- 空行分段、超长段二次切分符合预期。

---

### Phase 2 — 阅读界面、段落选中与「阅读」入口（3 天）

**目标**：完成核心交互——长按段落出现选中框，选中框上方弹出「阅读」按钮；接入系统 TTS 打通首条朗读链路。

任务清单：
- [ ] **段落级选择**：`SelectionContainer` + 自定义 `TextToolbar`，在默认工具栏（复制/全选）之上注入「阅读 🔊」项（图标 + 文字），位置位于选中框上方；
- [ ] 选中态样式：MD3 高亮（`primaryContainer`）与手柄，长按段落整段进入选中态；
- [ ] 「阅读」触发后：① 仅选中片段 → 朗读该片段；② 未选中（长按后直接点阅读）→ 从该段落朗读至文末；
- [ ] **TtsEngine 抽象层**：`speak(segment, onRangeStart)` / `pause` / `resume` / `stop` / `setRate` / `setVoice`；朗读状态机（Idle/Preparing/Playing/Paused/Finished）；
- [ ] **SystemTtsEngine（兜底引擎）**：基于 `TextToSpeech`，中文语音包检测与引导下载；`onRangeStart` 回调驱动当前朗读句高亮；
- [ ] 朗读控制浮层：播放/暂停/停止、语速调节、当前句高亮滚动跟随。

**交付物**：长按段落 → 选中框上方出现「阅读」→ 点击后系统 TTS 朗读，当前句高亮。
**验收标准**：
- 长按任一段落均可选中；选中框上方工具栏同时含「复制」与「阅读」；
- 朗读中可按段落连续播放，暂停/恢复/停止行为正确；
- 朗读进度与高亮同步误差 ≤ 1 句。

---

### Phase 3 — Qwen3-TTS 端侧集成（5 天）★ 核心攻坚阶段

**目标**：把 Qwen3-TTS 变成 App 的首选朗读引擎，实现端侧流式合成与播放。

任务清单：
- [ ] **模型获取**：集成 `litert-community/Qwen3-TTS-12Hz-0.6B-Base`（LiteRT 格式，含量化档位），首次启动引导下载（支持 `hf-mirror.com` 镜像、断点续传、MD5 校验），参考 [litert-samples 官方 Qwen3-TTS Android 示例](https://github.com/google-ai-edge/litert-samples)；
- [ ] **LiteRT 推理管线**：加载 `.tflite` 模型 → 文本 tokenize → 逐帧（12Hz）推理离散语音 token → 解码为 PCM；
- [ ] **AudioTrack 流式播放**：环形缓冲 + 背压控制，保证 12Hz 帧持续输出不卡顿、不爆音；
- [ ] **Qwen3TtsEngine**：实现同一 `TtsEngine` 接口，接入进度回调（token 时间轴 → 句级高亮）；
- [ ] **引擎回退链**：启动时能力探测（模型已下载？设备内存/CPU 达标？）→ 优先 Qwen3-TTS，失败自动回退系统 TTS，设置页可手动切换；
- [ ] 性能调优：LiteRT XNNPACK 多线程（`numThreads`），可选 GPU/NPU delegate；中低端机自动降档（更小量化档/更低采样策略）。

**交付物**：端侧 Qwen3-TTS 朗读可用；模型未下载或设备不达标时无缝回退系统 TTS。
**验收标准**：
- 中端设备（骁龙 7 系/8GB RAM）上 RTF（合成耗时/音频时长）≤ 1.0，即接近实时；
- 连续朗读 10 分钟无内存泄漏、无音频卡顿（卡顿次数 ≤ 3 次/分钟）；
- 断网状态下朗读功能完全可用（模型本地化）。

---

### Phase 4 — 「我的」历史抽屉（2 天）

**目标**：完成左上角「我的」：左侧抽屉展示历史，点击替换当前文本。

任务清单：
- [ ] **HistoryEntry 落库**：导入成功即写入 Room（名称、URI、打开时间、阅读进度、最后朗读位置）；
- [ ] **HistoryDrawer（MD3 NavigationDrawer）**：按打开时间倒序展示，每项含文件名/时间；空态占位（首次使用引导）；
- [ ] 点击历史项 → 校验 URI 可访问性（SAF 授权是否仍有效）→ 失效弹提示并置灰；有效则替换当前文本并滚动至上次进度；
- [ ] 返回键/手势关闭抽屉；长按历史项弹出「移除记录」菜单（仅删记录，不动源文件）；
- [ ] 阅读进度保存：退出/切后台时记录章节与段落索引。

**交付物**：左上角按钮呼出左侧抽屉，历史可点击切换文本。
**验收标准**：
- 历史列表按最近打开排序，条目信息正确；
- 点击历史项后正文正确替换，并恢复到上次阅读位置；
- 删除源文件后再次点击历史项，App 不崩溃且有友好提示。

---

### Phase 5 — 打磨、测试与发布（3 天）

**目标**：质量收敛，产出可分发 APK。

任务清单：
- [x] 全功能回归：需求对照表（§1.2）逐条验收（结果见 [PROGRESS.md](PROGRESS.md) §4、[TEST.md](TEST.md) §3）；
- [x] 边界场景：超大文件（5.37 MB / 56.71 MB）、空文件、无扩展名文件、损坏/截断编码、进程被杀恢复、
      横竖屏切换位置保留（第二轮修复并实测，矩阵见 [TEST.md](TEST.md) R-11：竖 71 → 横 71 → 横内滚动 77 → 竖 77）；
- [x] 无障碍与国际化：TalkBack 语义（章节 heading、段落提示、按钮 `contentDescription`）、
      中文/英文 `strings.xml`（`values/` + `values-en/`）、深色模式（MD3 暗色主题，P0 起已就绪）；
- [x] 性能与内存：导入 5.37 MB ≈ 3.2 s / PSS 138 MB，56.71 MB（截断到 20 MB）≈ 6.0 s / PSS 212 MB，
      均无 ANR，峰值 PSS 远低于 512 MB 目标；
- [x] 签名构建：Release APK 已可构建（R8 压缩 + 资源压缩，规则见 `app/proguard-rules.pro`）；
      ⚠️ **发布签名按用户要求暂缓**，`assembleRelease` 产出未签名包，自测用 `tools/sign-release.ps1` 签调试密钥；
- [x] 编写 `docs/SPEC.md`、`docs/TEST.md` 最终版（用户使用说明并入 [SPEC.md](SPEC.md) §1）。

**交付物**：签名的 Release APK + 完整文档 → 当前交付 **R8 压缩的 Release APK（未签名）+ 完整文档**。
**验收标准**：§11 验收总表全部通过；冷启动 ≤ 2s；导入 20MB 文本无 ANR → 除「正式签名」与「20 MB 真实样本的 ANR 压力观察」外均通过。

---

## 6. 里程碑（Milestones）

| 里程碑 | 内容 | 预计日期（自开工起） | 退出准则（必须全部满足） |
|--------|------|--------------------|------------------------|
| **M0** | 工程基座 + MD3 主题 | D+1 | APK 可运行；无存储权限；MD3 主题与页面骨架就位 |
| **M1** | 导入 + 自动分章节/段落 | D+3 | 三类规模文件解析正确、渲染流畅；编码兼容 GBK/UTF-8 |
| **M2** | 长按选中 +「阅读」入口 + 系统 TTS 兜底链路 | D+6 | 长按选中框上方出现「阅读」；系统 TTS 全链路朗读可用 |
| **M3** | ★ Qwen3-TTS 端侧集成 | D+11 | 端侧 Qwen3-TTS 实时流式朗读；回退链可靠；离线可用 |
| **M4** | 「我的」历史抽屉 | D+13 | 历史记录/切换/进度恢复完整可用 |
| **M5** | 打磨 + Release APK | D+16 | §11 验收总表 100% 通过，签名 APK 产出 → **部分达成**：R8 压缩的 Release APK 已产出并通过上机冒烟；未签名（用户要求暂缓）。横竖屏位置保留已修复并实测（[TEST.md](TEST.md) R-11） |

> 里程碑节奏：每周三与周日做里程碑评审（Review），未过退出准则的阶段不得开启下一阶段。

---

## 7. Qwen3-TTS 集成专项方案（重点）

### 7.1 为什么选 LiteRT 端侧方案

| 方案 | 优劣 | 结论 |
|------|------|------|
| **LiteRT / TFLite 端侧**（`litert-community/Qwen3-TTS-12Hz-0.6B-Base`） | 官方社区模型，已有 [litert-samples 安卓示例](https://github.com/google-ai-edge/litert-samples)；完全离线、免服务器；0.6B 参数在 8GB 手机上实时 | **首选（M3）** |
| llama.cpp GGUF（`Arm/qwen3-tts-0-6b-*-mix-precision`） | 生态成熟、量化灵活 | 备选，通过 `TtsEngine` 抽象后续接入 |
| 自建服务端推理（官方 PyTorch 推理） | 质量最高、零端侧资源占用 | V1.1 可选，走同一引擎接口 |

### 7.2 数据流

```
段落文本 ──tokenize──▶ text tokens ──LiteRT 前向(12Hz 帧)──▶ 语音 token 流
        ──码本解码──▶ PCM 16k ──环形缓冲──▶ AudioTrack 播放 ──▶ 进度回调(句级)
```

### 7.3 关键工程点

1. **模型体积与下载**：默认分发最小可用量化档（体积以 HuggingFace 模型卡实测为准，预计数百 MB 级）；首启向导说明下载原因；支持镜像源与断点续传。
2. **实时性**：以「一帧推理 ≤ 83ms（12Hz 周期）」为目标；不达标自动降档量化或降采样。
3. **内存**：0.6B 模型 + 推理缓冲常驻内存目标 ≤ 1.5GB；低于 6GB RAM 设备默认推荐系统 TTS 并允许手动强制。
4. **进度同步**：利用 token 时间轴将「合成进度」映射为「当前朗读句」，驱动正文高亮与滚动。
5. **参考实现**：Qwen3-TTS 官方仓库（https://github.com/QwenLM/Qwen3-TTS）与 [google-ai-edge/litert-samples](https://github.com/google-ai-edge/litert-samples) 中的 Android 示例；模型卡中的 Pixel 8a 实测数据作为性能基线参照。

---

## 8. 风险与对策

| # | 风险 | 概率 | 影响 | 对策 |
|---|------|------|------|------|
| R1 | 0.6B 模型在中低端机无法实时（RTF > 1） | 中 | 高 | 量化分档 + 自动降档；回退系统 TTS；仅下载档位可选 |
| R2 | 模型体积大，国内下载慢/失败 | 高 | 中 | hf-mirror 镜像 + 断点续传 + 后台下载 + 系统 TTS 兜底 |
| R3 | 大文件解析 OOM / ANR | 中 | 高 | 流式分块解析 + LazyColumn 懒渲染 + 20MB 预警 |
| R4 | SAF 持久授权失效（文件被移动/删除） | 中 | 低 | 点击历史时校验 URI，失效给出引导重新导入 |
| R5 | 长按选择手势与列表滚动冲突 | 中 | 中 | 段落级独立 SelectionContainer；长按延迟调优（400ms） |
| R6 | GBK/编码误判乱码 | 中 | 中 | BOM→UTF-8 校验→GB18030 三级探测 + 预览确认 |
| R7 | Compose 自定义 TextToolbar 兼容性（版本差异） | 低 | 中 | 封装 SelectionLayer 隔离 API；必要时用自定义浮动工具栏兜底 |

---

## 9. 测试计划

| 层级 | 内容 | 阶段 |
|------|------|------|
| 单元测试 | ChapterParser / ParagraphParser / EncodingDetector（含 10 个异构样本回归集） | P1 起 |
| 单元测试 | TtsEngine 状态机、回退链决策逻辑（Mock） | P2–P3 |
| UI 测试 | 导入流程、长按选中、「阅读」弹出、抽屉切换（Compose Test） | P2–P4 |
| 手工用例 | 真机朗读听感、音频卡顿、横竖屏、进程被杀、深色模式 | P3–P5 |
| 性能测试 | 20MB 导入内存/耗时；Qwen3-TTS RTF 实测（Pixel 8a 基线 + 中端机） | P3–P5 |
| 回归 | §11 验收总表逐条执行 | P5 |

---

## 10. 验收标准总表（对应 §1.2 需求）

| # | 验收项 | 通过标准 |
|---|--------|---------|
| A1 | 文本导入 | 右上角入口 → 系统文件选择器 → 正文从文件头显示，无乱码 |
| A2 | 自动分章节 | 章节标题正确切分并展示；无标题文本按字数软分章 |
| A3 | 自动分段落 | 空行分段正确；超长段按句末标点二次切分 |
| A4 | 长按选中 | 长按段落出现选中框与手柄，可调整选择范围 |
| A5 | 「阅读」入口 | 选中框上方工具栏出现「阅读」，点击即开始朗读 |
| A6 | 朗读功能 | Qwen3-TTS 端侧朗读流式播放；模型不可用时自动回退系统 TTS；支持暂停/继续/停止/语速 |
| A7 | 「我的」 | 左上角按钮弹出左侧抽屉，按时间倒序列出历史，点击替换当前文本并恢复进度 |
| A8 | MD3 界面 | Material 3 组件、动态取色、深色模式、Material You 视觉规范 |
| A9 | 权限最小化 | 不申请任何存储权限（SAF 免权限） |
| A10 | 稳定性 | 全回归无崩溃；20MB 导入无 ANR；10 分钟朗读无泄漏 |

---

## 11. 后续迭代（V1.1+ 规划，暂不实施）

- 书签 / 章节目录跳转（Chapter Navigator）；
- 服务端 Qwen3-TTS 推理模式（音质最高档）与音色克隆；
- EPUB/PDF 导入、阅读页字体/字号/行距/背景主题定制；
- 阅读统计（「我的」页扩展）。

---

## 附：参考资料

- Qwen3-TTS 官方仓库：https://github.com/QwenLM/Qwen3-TTS
- LiteRT 社区端侧模型：https://huggingface.co/litert-community/Qwen3-TTS-12Hz-0.6B-Base
- Google AI Edge litert-samples（Qwen3-TTS Android 示例）：https://github.com/google-ai-edge/litert-samples
- ARM 量化 GGUF 备选：https://huggingface.co/Arm/qwen3-tts-0-6b-base-mix-precision
- Material Design 3 官方指南：https://m3.material.io
