# 功能规格（最终版）· 简读 / JianDu

> 本文件是 **P5 收敛后的功能规格**：描述「当前代码实际实现的行为」，而非计划。
> 相关文档：[PLAN.md](PLAN.md)（计划）、[PROGRESS.md](PROGRESS.md)（实施进度与证据）、
> [DEV.md](DEV.md)（环境与构建）、[P3-STATUS.md](P3-STATUS.md)（端侧 TTS 专项）、[TEST.md](TEST.md)（测试用例与实测结果）。
>
> 基线版本：`versionCode 3` / `versionName 0.3.0`（`app/build.gradle.kts`）
> 目标平台：`minSdk 26` / `targetSdk 35` / `compileSdk 35`，单 Activity + Compose + MVVM

---

## 1. 产品定位

### 1.1 一句话

**把手机里任意一份纯文本，变成可以自动分章、长按即听的有声读物。**

### 1.2 一句话流程

```
导入（SAF 选文件）→ 阅读（自动分章分段、长按选中）→ 聆听（逐句连续朗读、高亮跟随）
```

### 1.3 目标用户与典型场景

| 用户 | 场景 | 本应用的关键价值 |
|------|------|-----------------|
| 中文网文读者 | 手上是 `.txt` 全本（几 MB~几十 MB），第三方阅读器要么收费要么插广告 | 本地导入、自动识别「第 N 章」、长按即听，全程离线 |
| 通勤/家务中的「听书」用户 | 眼睛不方便持续看屏幕 | 长按一段 → 「阅读」→ 逐句连读，锁屏之外无需操作 |
| 无障碍用户（低视力） | 依赖 TalkBack | 章节标题有 `heading()` 语义、段落有朗读提示、控制条按钮全部带描述 |
| 只想快速看一眼的人 | 别人发来一个编码不明的 `.txt` | 自动识别 UTF-8 / GB18030 / UTF-16，不乱码 |

### 1.4 三条产品红线（由实现决定）

1. **离线可用**：导入、解析、阅读、**系统语音朗读**、历史记录全程不联网（只有可选的端侧模型下载需要网络）。
2. **权限最小化**：导入走 SAF，**不授权任何存储权限也能完整使用**。
3. **不阻塞界面**：解析在 IO 线程，正文用 `LazyColumn` 懒加载；导入期间只显示进度指示器。

---

## 2. 功能规格

### 2.1 文本导入

**入口**：右上角 `导入文本` 图标（`ui/AppScaffold.kt`）。

| 项目 | 规格 | 代码位置 |
|------|------|---------|
| 选择器 | `Intent.ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE`，`type = "*/*"`，`EXTRA_MIME_TYPES = ["text/plain", "text/*", "application/octet-stream"]` | `data/SafSource.openDocumentIntent()` |
| 权限 | 不使用存储权限；`takePersistableUriPermission(READ)` 持久化授权，失败静默忽略（第三方 provider 可能不支持） | `data/SafSource.persistPermission()` |
| 文件名 | `OpenableColumns.DISPLAY_NAME`；查询失败回退到 URI 末段，再回退到 `import_unnamed`（「未命名文本」） | `data/SafSource.queryDisplayName()` |
| 读取 | `BufferedInputStream` + 64 KB 缓冲，读取上限 **`MAX_BYTES = 20 * 1024 * 1024`（20 MB）** | `data/SafSource.readBytes()` |
| 截断 | 超出上限时**截断**而非拒绝：`truncated = true`，并弹 Snackbar「文件超过 20MB，仅显示前部分内容」 | `viewmodel/ReaderViewModel.load()` |
| 截断点对齐 | `trimToCompleteLine()`：先退回完整 UTF-8 字符（最多 3 个续接字节），再在 64 KB 窗口内退到最后一个 `\n` 之后 | `data/SafSource.trimToCompleteLine()` |
| 解析线程 | `Dispatchers.IO` | `data/SafSource.readBook()` |
| 导入中 | `ReaderUiState.isLoading = true` → 正文区显示 `CircularProgressIndicator` | `ui/AppScaffold.kt` |

> **注意**：`type = "*/*"` + `EXTRA_MIME_TYPES` 的组合使得「无扩展名 / 被识别为 `application/octet-stream` 的文件」也能选中；
> 代价是文件选择器不会硬性过滤，用户可以选中任意文件（此时由编码探测与解析兜底）。

#### 2.1.1 编码探测策略（严格顺序，先命中先返回）

`domain/parser/EncodingDetector.detect(bytes, length)`：

| 优先级 | 判据 | 结果 | `bomLength` |
|-------|------|------|------------|
| 1 | 前 3 字节 `EF BB BF` | UTF-8 | 3 |
| 2 | 前 2 字节 `FF FE` | UTF-16LE | 2 |
| 3 | 前 2 字节 `FE FF` | UTF-16BE | 2 |
| 4 | **严格** UTF-8 解码全部成功（`REPORT` malformed/unmappable） | UTF-8 | 0 |
| 5 | **严格** GB18030 解码全部成功 | GB18030 | 0 |
| 6 | `looksLikeUtf8Truncated()`：末尾 1~3 字节是「**合法 UTF-8 多字节序列前缀**」（首字节 `C2–DF`/`E0–EF`/`F0–F4` + 数量正确但不足的续接字节 `80–BF`），且该前缀之前的主体能通过严格 UTF-8 解码 | UTF-8 | 0 |
| 7 | `looksLikeGbk()`：扫描非 ASCII 字节，「首字节 `81–FE` + 合法尾字节 `40–FE`（不含 `7F`）」的配对比例 **≥ 0.8** 且首字节数 > 0 | GB18030 | 0 |
| 8 | 全部未命中 | UTF-8（兜底） | 0 |

要点：

- 第 6 条只认「**被切断的 UTF-8 尾字符**」这一种形状，因此不会抢走真正的 GBK 文件
  （GBK 双字节如 `0xB6 0xBC` 的第 2 字节不是 UTF-8 续接字节，形状不匹配）。
- 第 7 条用于「文件里混了少量损坏字节、严格校验全部失败」的场景：
  此时若不兜底就会按 UTF-8 强行解码，整篇每个坏字节产生一个 `\uFFFD`；启用后乱码范围被限制在真正损坏的字节上。
- `bomLength` 由 `SafSource.readBook()` 用于 `String(bytes, bomLength, size - bomLength, charset)` 跳过 BOM。

#### 2.1.2 无扩展名文件

不依赖扩展名：文件名只用于显示与历史去重，编码与结构完全由**内容**判定。
实测 `noext`（无扩展名）文件正常分章分段，见 `docs/shots/p5-noext-reader.png`。

#### 2.1.3 空文件（含纯空白、只有 BOM）

| 情况 | 行为 |
|------|------|
| 0 字节文件 | 解析出 `charCount == 0` → `ReaderUiState.isEmptyBook == true` |
| 只有 BOM（`\uFEFF`） | `TextParser.normalize()` 先 `removePrefix("\uFEFF")` → `charCount == 0` |
| 纯空白/纯换行 | 无任何段落，`paragraphCount == 0` |

界面表现（`ui/reader/ReaderScreen.kt`）：

- `book.paragraphCount == 0` → 渲染 **空文件占位** `ReaderEmptyFileState`：
  图标 + `reader_empty_file_title`（「文件是空的」）+ `reader_empty_file_body`（「所选文件里没有可显示的文本…」）；
- 同时 Snackbar 提示 `import_empty_file`（「这个文件没有任何文字内容」）；
- **不写入历史、不记住恢复点**（`load()` 中 `if (!empty)` 才 `recordHistory` / `rememberBook`）；
- 不崩溃（`TextParserTest.空文件解析结果为空书且不崩溃` 覆盖）。

### 2.2 章节与段落解析

解析器为纯 Kotlin（无 Android 依赖），类 `domain/parser/TextParser.kt`，可在 JVM 单测中直接验证。

#### 2.2.1 归一化 `normalize()`

单次线性扫描（**刻意不用 `split('\n')` + `joinToString`**，见 §4.2）：

1. 去掉开头的 `\uFEFF`（BOM）；
2. 把换行统一为 `LF`（源代码里的字面量分别是 CR、LF）：`CRLF` 与单独的 `CR` 都归一为一次换行；
3. 每一行的**行尾空白**（半角空格 / 制表符 / 全角空格等 `isWhitespace()`）全部去掉。

#### 2.2.2 章节标题正则

```
^(?:第\s*[0-9零一二三四五六七八九十百千万两〇]+\s*[章节回卷集部篇幕]
 |Chapter\s+[0-9IVXLCivxlc]+
 |CHAPTER\s+[0-9IVXLCivxlc]+
 |楔子|序章|序言|序幕|前言|引子|后记|尾声|终章|番外)
(?:$|\s+.{0,20}$|[：:、，,.\-—－].{0,20}$)
```

**判定条件**（`isChapterTitle()`）：整行 `trim()` 后非空、长度 **≤ `maxChapterTitleLength`（默认 40）**、且用 `regex.matches()` **整行匹配**。

- 好处：`第一章的教室里没有人。`、`他翻开了第二章，发现字迹模糊不清，于是放下书走了出去。` 都不会被误判为标题（要求分隔符存在且标题部分 ≤ 20 字）。
- 匹配示例：`第一章 少年`、`第 12 回`、`第一百二十三章`、`Chapter 3: The Beginning`、`CHAPTER IV`、`楔子`、`番外 一`。

#### 2.2.3 拆分结构

```
headings = 所有标题行的行号
若 headings 为空:
    全部行 → 段落 → fallbackSplit()              # 软分章
否则:
    首个标题之前的非空内容 → Chapter(title = null)  # 「前言」章节
    每个标题 → Chapter(title = 该行 trim 文本, paragraphs = 标题行之后到下一个标题之前的段落)
    最后 reindex() 重排章节与段落下标
```

#### 2.2.4 软分章（无标题文本）

`fallbackSplit(paragraphs, titlePrefix = "第 %d 节")`：

- 段落字数总计 ≤ `fallbackChapterChars`（**默认 8000 字**）→ **单章**，`title = null`（界面上不显示章节标题，直接显示正文）；
- 超过则按段落累积切块，**每块字数 ≥ 8000 就收一刀**（块边界落在段落之间，不切开段落）；
- 每块标题为 **`第 N 节`**（`第 %d 节`，N 从 1 开始，`i + 1`）；
- 段落按块内顺序重新编号（`reindexParagraphs`）。

> 注意：软分章标题是**中文**「第 N 节」，英文界面下也不变（见 §7 已知限制）。

#### 2.2.5 段落模式 `ParagraphMode`

| 模式 | 行为 |
|------|------|
| `PER_LINE` | 每个非空行 = 一个段落 |
| `BLANK_LINE` | 以空行分隔；段内换行**保留**（成员行用换行符拼接后整段作为一个段落文本） |
| `AUTO`（默认） | 自动判定，见下 |

`AUTO` 判定规则（`resolveParagraphMode()`）：

```
非空行数 == 0                      → PER_LINE
空行数 / 非空行数 >= blankLineRatioThreshold (默认 0.1) → BLANK_LINE
否则                                → PER_LINE
```

> 直觉：中文网文常见「一段一行、无空行」，此时空行占比低 → `PER_LINE`；
> 出版文本常见「段落之间空一行」→ 空行占比高 → `BLANK_LINE`。

#### 2.2.6 超长段落二次切分

`splitLongText(text, startOffset)`，单段上限 `maxParagraphChars`（**默认 500 字**）：

- 逐字符扫描，记录**最后一个句末标点之后**的位置 `lastBreak`；
- 当 `i + 1 - segmentStart >= maxParagraphChars` 时收一刀：
  - 若 `lastBreak > segmentStart` → 在 `lastBreak` 处切（保证句子完整）；
  - 否则 → 在 `i + 1` 处**硬切**（无标点的超长串，如 base64、英文无句号长段）；
- 扫描结束后若还有残余 → 作为最后一段；
- **不丢字**：`切分后各段拼接 == 原文`（`TextParserTest.超长段落按句末标点二次切分` / `超长无标点文本按硬切分且不丢字` 各有一条完整性断言）。

句末标点集合（`TextParser.sentenceEndChars`）：

```
。 ！ ？ … ” 』 」 . ! ? ; ；
```

> 注意与朗读切句（`ReadingSegments.sentenceEndChars` = `。！？；…!?;` + 尾随引号 `”』」）)"'`）**不是同一套**：
> 解析期切段允许 `.`/`!`/`?`，朗读期不按 `.` 切，避免英文缩写把句子切碎。

#### 2.2.7 可调参数 `ParserConfig`

```kotlin
data class ParserConfig(
    val paragraphMode: ParagraphMode = ParagraphMode.AUTO,
    val maxParagraphChars: Int = 500,          // 超长段二次切分阈值
    val fallbackChapterChars: Int = 8_000,     // 无标题时按字数软分章
    val blankLineRatioThreshold: Double = 0.1, // AUTO 判定「空行分段」的占比阈值
    val maxChapterTitleLength: Int = 40,       // 超过该长度的行不视为标题
)
```

当前**全应用统一使用默认值**（`ReaderViewModel` 中 `TextParser()`），尚未暴露到设置界面。

#### 2.2.8 解析产物的小约定

- `Chapter.index`：在 `chapters` 列表中的下标（重排后保证 `0..n-1` 连续）。
- `Paragraph.index`：**章内**下标（`0..m-1`），不是全书下标。
- `Paragraph.startOffset`：段落首字符在**归一化后全文**中的字符下标（用于定位，不参与渲染）。
- 章节标题行**不会**作为段落出现在正文里；标题只由 `ReaderItem.ChapterHeader` 渲染。
- 空章节（标题之后无任何段落）会保留为 `Chapter(title = ..., paragraphs = [])`，界面只显示标题。

### 2.3 阅读界面

**结构**：`Scaffold` → `TopAppBar`（左：`我的`☰；右：`导入文本`）+ 正文 `ReaderScreen`。

| 元素 | 规格 |
|------|------|
| 正文列表 | `LazyColumn` + `ReaderItems.build(book)` 扁平化（章节标题 + 段落），`key` 为 `ch-<i>` / `p-<i>-<j>` |
| 排版 | 左右 20 dp、顶部 12 dp、底部 160 dp（给控制条留位置）；行间距 10 dp；正文样式 `ReadingBodyStyle` + **首行缩进 = 字号 × 2** |
| 章节标题 | `ChapterTitleStyle`，`primary` 色，标题上方 `HorizontalDivider`；首个章节顶部 8 dp，其余 24 dp |
| 导入后位置 | 换书（`book.uri` 变化）且无恢复点时 `scrollToItem(0)` → **从文件头显示** |
| 恢复位置 | 有 `pendingAnchor` 时 `scrollToItem(indexOfParagraph(...))` |
| 进度上报 | `snapshotFlow { listState.firstVisibleItemIndex }` → 首个可见段落/章节 → `onVisibleAnchorChanged()` |
| 朗读跟随 | 当前朗读段落不在可视区时 `animateScrollToItem()` |

#### 2.3.1 长按选中句子 → 选中框上方工具栏

| 步骤 | 行为 |
|------|------|
| 长按段落 | `detectTapGestures(onLongPress)` → `layout.getOffsetForPosition(pos)` → `sentenceRangeAt()` 取**包含该字符的整句范围** |
| 选中反馈 | 段内选中区间加 `SpanStyle(background = #33FFB74D)`（半透明橙） |
| 工具栏 | `SelectionToolbar` 渲染在**该段落文本的上方**（`Column` 内 `Text` 之前），三项： |
| ↳ `阅读` | `readSelection()`：从选中句开始 → `ReadingSegments.fromBook(startChapter, startParagraph, startSentence)` → 连续朗读到**文末** |
| ↳ `整段` | `expandSelectionToParagraph()`：把选中区间扩展为 `0 until text.length` |
| ↳ `复制` | `Clipboard`（`ClipData.newPlainText("jiandu", text)`），复制**当前选中文本** |
| 点击空白 | `onTap` → `clearSelection()` 清除选中 |

**选择粒度**：仅「句 / 整段」，**不支持任意区间拖拽手柄**（见 §7 已知限制）。
同一时刻只有一个段落处于选中态（`ReaderUiState.selection` 是单值）。

#### 2.3.2 朗读与高亮

朗读单位是**句子**（`domain/tts/ReadingSegments.kt`）：

- 切句：遇 `。！？；…!?;` 结束，并**吞掉紧随其后的收尾引号** `”』」）)"'`；
- **过短片段合并**：任一侧长度 < `MIN_SENTENCE_CHARS = 4` 时与前一段合并，避免逐字碎读；
- 片段 key 为 `"章节-段落-句子"`（如 `0-2-1`），段落前缀 `"0-2-"` 用于判断「这段是否正在朗读」。

高亮表现：

| 状态 | 样式 |
|------|------|
| 当前朗读段落 | 段落背景变 `surfaceContainerLow` |
| 当前朗读区间 | `SpanStyle(background = #556750A4, fontWeight = Medium)` |
| 选中区间 | `SpanStyle(background = #33FFB74D)` |

- 两种引擎在 `speak()` 的瞬间都先把 `activeRange` 置为**整句**（`speakCurrent()`），随后由引擎回调收紧；
- 系统 TTS 路径由 `onRangeStart(utteranceId, start, end, frame)` 驱动 → 收紧到**字级**；
- 端侧 Qwen3-TTS 路径按「已写出采样数 / 预估总采样数」粗略推进（`reportApproximateProgress`）→ 实际表现为**句级**（见 §7）；
- 越界范围一律 `coerceIn` 钳制到段内，防止引擎做数字/符号归一化后高亮漂移出界。

#### 2.3.3 朗读控制条 `TtsControlBar`

- **可见性**：`status ∈ {Speaking, Paused, Initializing}` 时从底部淡入（`AnimatedVisibility`）；
- 内容：引擎名 `AssistChip`（如「系统语音」/「Qwen3-TTS（端侧）」）、当前句文本（最多 2 行、省略号）、
  语速选择器、`上一句` / `播放·暂停` / `下一句` / `停止朗读`；
- 语速选项：`0.75x / 1.0x / 1.25x / 1.5x / 2.0x`（默认 `1.0`）；
- 朗读中改语速 → 引擎 `stop()` 后**从当前句重新开始**（`TtsController.setSpeechRate`）；
- 暂停/继续以**句**为单位（`pause()` 调 `engine.stop()`；`resume()` 从当前句重新 `speak`）。

#### 2.3.4 朗读片段来源

| 触发 | 片段起点 | 终点 |
|------|---------|------|
| 长按选中 → `阅读` | 选中句 `(chapter, paragraph, sentenceIndex)`（由 `sentenceIndexAt(text, selection.start)` 求得） | 文末 |
| 控制条 `播放`（无选中） | 文首 `(0, 0, 0)` | 文末 |
| 控制条 `播放`（有选中） | 同「阅读」 | 文末 |

### 2.4 「我的」历史抽屉

| 项目 | 规格 |
|------|------|
| 入口 | 左上角 ☰（`action_menu` = 「打开我的」）→ `ModalNavigationDrawer` 从左侧滑出 |
| 数据 | Room `AppDatabase` → `history` 表，`HistoryDao.observeAll()` 按 `last_opened_at DESC` |
| 列表项 | 文件名（1 行省略）+ `yyyy-MM-dd HH:mm` 本地时间；当前书 `selected = (entry.uri == book.uri)` |
| 空态 | `drawer_empty_title`（「还没有打开过文本」）+ `drawer_empty_body`（引导点右上角导入） |
| 移除 | 每项的删除图标（`drawer_remove` = 「移除记录」）→ `HistoryDao.deleteByUri()`，**只删记录，不动源文件** |
| 点击 | 关抽屉 → `openHistory(entry)`：先 `SafSource.canRead()` 校验 |
| URI 失效 | 弹 Snackbar `import_uri_unavailable`（「《%1$s》已无法访问，请重新导入该文件」），**不崩溃、不清除记录** |
| URI 有效 | 以 `ReadingAnchor(entry.chapterIndex, entry.paragraphIndex)` 重新解析并跳转 |
| 抽屉底部 | `VoiceEngineCard`（语音引擎与模型）→ `权限与联网` 按钮 → `版本 x.y.z（build N）· 构建时间` 按钮（打开「检查更新 / 关于」） |

#### 2.4.1 去重规则

`recordHistory(book)`：

```
existing = dao.findByNameAndCharCount(book.name, book.charCount)
if (existing != null && existing.uri != book.uri) dao.deleteByUri(existing.uri)   // 合并旧记录
dao.upsert(HistoryEntry(uri, name, now, 0, 0, charCount))
```

- 主键是 `uri`，因此同一 URI 重复导入是 `REPLACE` 更新；
- 「同一文件被系统授予不同 document URI」→ 以 **`文件名 + 字数`** 认定重复，删旧留新；
- 新导入/切换历史时**重置进度为 (0, 0)**；进度由 `updateProgress()` 单独写回。

#### 2.4.2 阅读进度与恢复

| 机制 | 说明 |
|------|------|
| 上报 | 阅读区把首个可见段落/章节回传 `onVisibleAnchorChanged(chapter, paragraph)` |
| 落库节奏 | 仅当「章节下标变化 ≥ 1 或段落下标变化 ≥ 10」才写 Room，避免滚动时高频写库 |
| 强制落库 | `Lifecycle.ON_STOP` → `persistProgress()`（同时 `stopReading()`） |
| 恢复入口 | ① 点历史项 → 用该条记录的 `chapterIndex/paragraphIndex`；② 冷启动自动恢复（§2.4.3） |
| SharedPreferences | `jiandu_reader`：`last_uri` / `last_chapter` / `last_paragraph` |

#### 2.4.3 进程被杀后自动恢复

`ReaderViewModel.init` → `restoreLastBook()`：

```
uri = prefs.last_uri ?: return            # 没有记录 → 什么都不做
if (!SafSource.canRead(uri)) { 删除 last_uri; return }   # 授权已失效 → 静默放弃
load(uri, ReadingAnchor(last_chapter, last_paragraph), remember = false)
```

- 触发时机是 **ViewModel 创建**（即冷启动/进程重建后进入界面）即自动恢复；
- 代码里**没有**「当前是否已有内容」的前置判断，因此配置变更（如旋转）触发 ViewModel 重建时也会走一遍恢复逻辑；
  恢复本身是幂等的（重解析同一 URI 并跳回同一 anchor），实际表现为「无害但会多做一次解析」；
- `remember = false`：恢复过程**不会**刷新历史时间戳；
- 失败**静默处理**（不弹错误），用户重新导入即可；
- 恢复成功后访问的是 **SAF 持久授权**的 URI，因此不需要用户再选一次文件。

### 2.5 语音引擎与回退链

`domain/tts/TtsEngine.kt` 定义统一接口，`TtsController` 驱动逐句连续播放，两种引擎完全解耦：

```
interface TtsEngine {
  val id: String; val displayName: String; val isReady: Boolean
  fun setListener(l: TtsListener); fun initialize()
  fun speak(text: String, utteranceId: String)
  fun stop(); fun setSpeechRate(rate: Float); fun shutdown()
}
interface TtsListener { onReady / onError / onStart / onDone / onRange }
```

| 引擎 | 实现 | 特点 |
|------|------|------|
| `SystemTtsEngine`（兜底） | Android `TextToSpeech` | 零下载、即开即用、支持 `onRangeStart` **字级**高亮；`engine_system_voice` |
| `Qwen3TtsEngine`（首选） | LiteRT + `AudioTrack` 流式 PCM | 端侧离线、音色更自然；`engine_qwen3`；RTF ≈ 4~5（见 P3-STATUS.md） |

**启动选择**（`ReaderViewModel.init`）：

```
modelManager.isInstalled()  →  Qwen3TtsEngine  else  SystemTtsEngine
```

**回退链**（自动、单向）：

| 触发 | 行为 | 提示 |
|------|------|------|
| 端侧引擎上报 `TtsStatus.Error`（ABI 不支持 / 模型损坏 / 内存不足） | 切到 `SystemTtsEngine`，`fellBackToSystem = true`（**只回退一次**，不会反复横跳） | `engine_fallback_to_system` |
| 端侧模型随后下载完成（界面观察到 `Qwen3ModelState.Ready`） | `onModelAvailable()` 热切换到端侧引擎 | `engine_switched_to_qwen3` |
| 已回退过系统语音 | 即使模型就绪也**不再**切回（避免抖动） | — |

**系统语音的中文兜底**（`SystemTtsEngine`）：

1. 优先 `setLanguage(Locale.CHINA)`，并挑选「中文 + 不需要网络连接」的离线音色；
2. 合成失败 → 切成 `Locale.getDefault()` **重试一次**（`usedFallbackLocale` 保证只重试一次）；
3. 仍失败 → 给出中文语音包引导文案（`system_tts_no_voice_data` / `system_tts_failed`）。

**引擎无关的连续播放**（`TtsController`）：

- 一次只把**一句**交给引擎；`onDone(utteranceId == token)` 时 `advance()` 到下一句；
- `token` 递增，旧回调被丢弃（防止停止/跳句后迟到的 `onDone` 把播放推错位置）；
- 播到最后一句 → `finish()`：状态回 `Ready`、清空高亮；
- `next()` / `previous()`：`engine.stop()` 后移动游标重播，**在当前片段序列内**移动（不会越界）。

### 2.6 端侧模型管理

`data/tts/Qwen3ModelManager.kt`（清单与实测体积见 [P3-STATUS.md](P3-STATUS.md) §3）：

| 项目 | 规格 |
|------|------|
| 存放目录 | `filesDir/qwen3-tts`（应用私有，无需存储权限） |
| 镜像回退 | `hf-mirror.com` → `huggingface.co` 顺序重试 |
| 断点续传 | 先写 `<file>.part`，按 `Range: bytes=<existing>-` 续传；服务器返回 `200`（而非 `206`）则丢弃已下载部分重来 |
| 完整性校验 | 按「文件存在 + `length >= minBytes`」逐文件校验（`isInstalled()`） |
| 空间检查 | `REQUIRED_FREE_BYTES = 1750 MB`；目录不存在时回退到 `filesDir` 取可用空间（首次下载前目录不存在，否则会误报「可用 0 MB」） |
| 内存检查 | `RECOMMENDED_RAM_BYTES = 6 GB`，仅作**风险提示**（主按钮始终可点，不禁用） |
| 错误翻译 | `friendlyError()`：`UnknownHost` → 无法解析地址；`SocketTimeout` → 超时（已下载部分保留）；`SecurityException` → 缺网络权限；`SSLException` → 安全连接失败；HTTP 4xx/5xx → 服务器返回状态码 |
| 热切换 | 下载完成 → 界面观察 `Ready` → `ReaderViewModel.onModelAvailable()` 即时切引擎，**无需重启** |
| 删除 | `deleteAll()` → 回落到系统语音，提示 `engine_deleted` |
| 镜像检测 | `probeMirrors()`：对第一个必需文件发请求，报告 `HTTP 状态码 + Content-Length` |
| 体积档位 | 快速图组（int8 MTP + 拆分编解码器，必需）/ 精度图组（fp32，可选）；卡片显示 `engine_graph_*` |

### 2.7 权限与联网引导

`data/permission/AppPermissionCenter.kt` + `viewmodel/PermissionViewModel.kt` + `ui/permission/PermissionDialog.kt`。

**弹出策略（P5 已调整）**：

```
lastSeenVersion != BuildConfig.VERSION_CODE   →  弹出 + 重置「不再提示」   # 升级后一定出现一次
else if (!dontAsk && shownForVersion != VERSION_CODE) → 弹出             # 同版本内仅首次运行
else                                          →  不自动弹出
```

之后只能通过抽屉里的 **`权限与联网`**（`perm_reopen`）手动打开。
记录存于 SharedPreferences `jiandu_permissions`：`last_version_code` / `shown_version_code` / `dont_ask_permissions` / `asked_permissions`。

**三项的机制与动作**：

| 项 | 真实机制 | 弹窗里的动作 | 满足判定 |
|----|---------|-------------|---------|
| 存储访问 | Android ≤ 12：`READ/WRITE_EXTERNAL_STORAGE` 是运行时权限；Android 11+：`MANAGE_EXTERNAL_STORAGE` 是**特殊权限**，只能跳设置页 | ≤ 12 弹系统权限框（`REQUEST_STORAGE`）；≥ 11 跳「所有文件访问」（`OPEN_ALL_FILES`，单应用页不存在时回退到总列表页） | `Environment.isExternalStorageManager()`（≥ 11）或 `READ_EXTERNAL_STORAGE` 已授权 |
| 网络 | `INTERNET` 安装时自动授予，**无法也不需要**弹窗 | 只检测当前是否联网；未联网时给「打开网络设置」 | `NET_CAPABILITY_INTERNET`（**刻意不用 `VALIDATED`**：模拟器/内网下它恒为 false，会误判为离线） |
| 安装未知应用 | Android 8+ 特殊权限，必须手动开启 | 跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` + 本应用包名 | `PackageManager.canRequestPackageInstalls()` |

其他行为：

- 从设置页返回（`Lifecycle.ON_RESUME`）自动 `refresh()`；**三项全部满足时自动关闭弹窗**；
- 「完成」/「不再提示」都记录「本版本已引导过」，避免每次冷启动挡住正文；
- 「不再提示」在**版本号变化**时被重置，保证新版本的引导一定出现一次；
- 全部跳过**不影响**「导入文本 + 系统语音朗读」（这两项本就不依赖上述权限）；
- 打开设置页失败（`ActivityNotFoundException` 等）→ Snackbar `error_open_settings_failed`。

> 合规提醒：`MANAGE_EXTERNAL_STORAGE` 与 `REQUEST_INSTALL_PACKAGES` 属敏感权限，上架 Google Play 需说明用途或改用更窄 API。

### 2.8 应用内自更新

`data/update/UpdateManager.kt` + `viewmodel/UpdateViewModel.kt` + `ui/update/UpdateDialog.kt`。
入口：抽屉底部「版本 0.3.0（build 3）· 构建时间」行。

**清单 JSON**（URL 由用户填写，本仓库不含服务器）：

```json
{
  "versionCode": 3,
  "versionName": "0.3.0",
  "apkUrl": "https://example.com/jiandu-0.3.0.apk",
  "sha256": "可选，填了就校验",
  "notes": "本次更新内容"
}
```

| 能力 | 规格 |
|------|------|
| 检查 | `fetchManifest()`：15 s 连接/读取超时、跟随重定向、`User-Agent: JianDu/<versionName> (Android)`；非 2xx 抛错 |
| 版本判定 | 仅当 **清单 `versionCode` > 当前 BuildConfig.VERSION_CODE** 才算新版本（同版本号覆盖安装不会带来新功能语义） |
| 下载 | `downloadApk()`：写 `cacheDir/update/app-update.apk.part`，支持 `Range` 断点续传；服务器忽略 Range 时自动重下；完成后 `renameTo` 就位 |
| SHA-256 | 清单给了 `sha256` 才校验，不匹配 → `update_sha_mismatch` |
| 安装前校验 `verifyApk()` | ① 能读到包信息（否则「不是有效的 APK」）② 包名 == `context.packageName` ③ `archiveVersion >= currentVersionCode` ④ 若清单给了 `versionCode`，必须相等 ⑤ **签名证书集合一致**（读不到就不拦，交给系统判断） |
| 本地 APK 路径 | `OpenDocument` 选 APK → `importLocalApk(uri)` 拷到 `cacheDir/update/app-local.apk` → 走同一套校验（**不依赖任何服务器**） |
| 交给安装器 | `FileProvider`（authority `${applicationId}.fileprovider` → `cacheDir/update/`）→ `ACTION_VIEW` + `application/vnd.android.package-archive`；需用户已开启「安装未知应用」，否则提示 `update_need_permission_first` |

> 每次发布功能都必须 **`versionCode + 1`**，否则「检查更新」永远认为已是最新。

---

## 3. 数据模型

`domain/model/ReaderModels.kt`：

```kotlin
@Immutable data class Book(
    val uri: String,                        // SAF URI 字符串
    val name: String,                       // 显示名（DISPLAY_NAME）
    val charsetName: String = "UTF-8",      // 探测到的编码名（仅展示/诊断）
    val chapters: List<Chapter> = emptyList(),
    val openedAt: Long = 0L,
) {
    val paragraphCount: Int                 // 所有章节段落数之和
    val charCount: Int                      // 所有段落文本长度之和（判定「空文件」的依据）
}

@Immutable data class Chapter(
    val index: Int,                         // 章内顺序 = chapters 列表下标
    val title: String?,                     // null = 无标题（前言 / 未软分章的单章）
    val paragraphs: List<Paragraph> = emptyList(),
)

@Immutable data class Paragraph(
    val index: Int,                         // 章内下标
    val text: String,                       // 已 trim、已按需二次切分
    val startOffset: Int = 0,               // 归一化全文中的字符下标
)

@Immutable data class ReadingAnchor(        // 阅读进度定位
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
)

@Immutable data class ParagraphSelection(   // 长按选中（句 / 整段）
    val chapterIndex: Int, val paragraphIndex: Int,
    val start: Int, val end: Int,           // 段内偏移，end 不含
    val text: String,
) { val range: IntRange get() = start until end }
```

持久化实体 `data/db/HistoryEntry.kt`（Room，表名 `history`，**主键 = uri**）：

| 列 | 类型 | 含义 |
|----|------|------|
| `uri` | String (PK) | SAF document URI |
| `name` | String | 显示名（去重键之一） |
| `last_opened_at` | Long | 最近打开时间（排序键） |
| `chapter_index` | Int | 阅读进度：章节下标 |
| `paragraph_index` | Int | 阅读进度：段内下标 |
| `char_count` | Int | 字数（去重键之一） |

> 计划文档里提到的 `Bookmark` 表**未实现**；书签不在本版范围。

`HistoryDao` 提供的操作：`observeAll()`（倒序 Flow）、`upsert()`（`REPLACE`）、
`updateProgress()`、`deleteByUri()`、`findByUri()`、`findByNameAndCharCount()`。

---

## 4. 交互状态机与关键时序

### 4.1 朗读状态机 `TtsStatus`

```
                    play(segments)
        ┌────────┐  engine.isReady?  ┌──────────────┐
        │  Idle  │──────────────────▶│    Ready     │◀── stop() / 播完最后一句 finish()
        └────────┘                   └──────────────┘
             ▲                         │        ▲
             │ attach(engine)          │ speak  │ onDone → advance()（还有下一句）
             │ (初始化完成)             ▼        │
             │                   ┌──────────────┐
             └───────────────────│   Speaking   │──── pause() ──▶ ┌──────────┐
                                 └──────────────┘                  │  Paused  │
                                        │                          └──────────┘
                   引擎未就绪 → initialize()      resume() → speakCurrent() ──┘
                                 │
                                 ▼
                          ┌──────────────┐   onError(message)
                          │ Initializing │──────────────┐
                          └──────────────┘              ▼
                                 │                ┌───────────┐
                                 │ onReady()      │   Error   │ ← 清空高亮，回退链在此触发
                                 └──▶ Speaking    └───────────┘
```

| 状态 | 界面表现 | 说明 |
|------|---------|------|
| `Idle` | 控制条隐藏 | 初始状态，未 attach 引擎 |
| `Initializing` | 控制条显示，正文提示「正在准备语音引擎…」 | 引擎未就绪时先挂起播放意图（`pendingPlay`） |
| `Ready` | 控制条隐藏 | 可播放；`stop()` 与「播完」都回到这里 |
| `Speaking` | 控制条显示，当前句高亮 + 自动滚动 | 正常朗读 |
| `Paused` | 控制条显示，播放按钮变 ▶ | 以「句」为单位，`resume()` 重播当前句 |
| `Error` | Snackbar 提示错误 | 清空 `activeSegmentKey`/`activeRange`，端侧引擎错误会触发回退链 |

`TtsUiState` 还带：`engineName`、`activeSegmentKey`、`activeRange`、`currentText`、`speechRate`、`message`。

### 4.2 导入 → 阅读 时序

```
用户点「导入文本」
   └─▶ importLauncher.launch(SafSource.openDocumentIntent())
         └─▶ 系统文件选择器（用户选中文件）
               └─▶ ReaderViewModel.import(uri)
                     ├─ isLoading = true（正文区转圈）
                     ├─ SafSource.persistPermission(uri)          # 持久授权（失败忽略）
                     └─ SafSource.readBook()  [Dispatchers.IO]
                          ├─ queryDisplayName()
                          ├─ readBytes(limit = 20MB)               # 超限 → truncated
                          │     └─ trimToCompleteLine()            # 退到完整字符 / 换行
                          ├─ EncodingDetector.detect()             # 8 级策略
                          ├─ String(bytes, bomLength, ..., charset)
                          └─ TextParser.parse()                    # normalize → 分章 → 分段
                     ├─ charCount == 0 ?  → 空文件占位 + 消息，不写历史
                     ├─ truncated ?       → Snackbar「文件超过 20MB…」
                     ├─ recordHistory(book)                        # 按 名称+字数 去重
                     ├─ rememberBook(book, anchor)                 # SharedPreferences
                     └─ ReaderScreen: scrollToItem(0)              # 从文件头显示
```

### 4.3 长按 → 朗读 时序

```
长按段落文本
  └─▶ getOffsetForPosition(pos) → sentenceRangeAt(text, offset)
        └─▶ select(ParagraphSelection(章, 段, start, end, text))
              └─▶ 段落上方出现「阅读 / 整段 / 复制」
                    └─▶ 点「阅读」
                          ├─ sentenceIndexAt(text, selection.start)
                          ├─ ReadingSegments.fromBook(start = 选中句)
                          ├─ clearSelection()
                          └─ TtsController.play(segments)
                                ├─ 引擎未就绪 → Initializing → initialize() → pendingPlay
                                └─ speakCurrent()
                                      ├─ Speaking + activeSegmentKey + activeRange（整句）
                                      ├─ engine.speak(text, token)
                                      ├─ onRange 回调 → activeRange 收缩到当前字（系统 TTS）
                                      └─ onDone → advance() → 下一句 ... → finish()（Ready）
```

### 4.4 「我的」切换时序

```
点历史条目
  └─▶ 关抽屉 → openHistory(entry)
        ├─ SafSource.canRead(uri)?
        │     false → Snackbar「《名》已无法访问，请重新导入该文件」（记录保留）
        └─ true  → import(uri, ReadingAnchor(entry.chapterIndex, entry.paragraphIndex))
                     └─ ▶ 4.2 的导入流程（anchor 非空 → 解析后 scrollToItem(恢复点)）
```

---

## 5. i18n 与无障碍规格

### 5.1 i18n 契约

| 规则 | 说明 |
|------|------|
| 单一入口 | 所有用户可见文案都通过 `i18n/UiStrings.get(resId, args)` 获取；`Viewmodel`/`data` 层**不得**硬编码中文 |
| 实现 | `AndroidStrings(context)`（跟随系统语言，走 `values/`、`values-en/`）；`StaticStrings`（单测用，无需 Android 环境） |
| 状态里只放 ID | `UiText(id, args)` 只携带资源 ID 与参数 → **切换系统语言后，已产生的消息也按新语言渲染** |
| Compose 还原 | `ui/text/UiTextResolve.kt` 的 `resolve()`（`@Composable`）把 `UiText` 渲染成字符串 |
| 集中引用 | 常用提示在 `i18n/UiText.kt` 的 `object TextIds` 中集中声明，避免各处重复写 `R.string.xxx` |
| 参数格式 | 资源里**禁止**用 `%d` 做纯数字格式化（阿拉伯语等区域会输出本地数字形态），**一律用位置参数** `%1$d`、`%2$s` 并按位置传参 |
| 语言覆盖 | 中文（`values/strings.xml`，默认）+ 英文（`values-en/strings.xml`）；两份文件**键集合必须完全一致**（当前各 **136** 条，已核对一致） |

例外（少量文案仍在 Compose 层用 `stringResource()` 直接取，因为它们本就属于 Compose 组件）：
`SelectionToolbar` 的「阅读/整段/复制」、`TtsControlBar` 的按钮描述与语速、`ReaderScreen` 的空文件占位与段落提示、
`HistoryDrawer` 的标题/空态、`VoiceEngineCard`、`PermissionDialog`、`UpdateDialog`、`TopAppBar`。

### 5.2 资源命名规约

| 前缀 | 归属 |
|------|------|
| `action_*` | 顶部栏与选中工具栏动作（`action_mine` / `action_import` / `action_read` / `action_select_paragraph` / `action_copy`） |
| `drawer_*` | 「我的」抽屉（标题、副标题、空态、移除） |
| `reader_*` | 阅读区（空态 `reader_empty_*`、空文件 `reader_empty_file_*`、段落提示 `reader_paragraph_hint`） |
| `tts_*` | 朗读控制条（引擎未知、准备中、播放/暂停/停止/上下句、语速） |
| `engine_*` | 端侧语音引擎与模型（标题、引擎名、状态、下载、错误翻译、图组、探测） |
| `system_tts_*` / `qwen3_*` | 两类引擎各自的失败提示 |
| `perm_*` | 权限引导弹窗 |
| `update_*` | 应用内自更新 |
| `import_*` | 导入流程提示（截断、失败、URI 失效、空文件、未命名、打开失败） |
| `error_*` | 通用错误（设置页/选择器打开失败） |

### 5.3 TalkBack 语义清单

| 位置 | 语义 | 资源 |
|------|------|------|
| 章节标题 `ChapterHeader` | `semantics { heading() }` → TalkBack 可「按标题导航」快速跳章 | 标题文本本身 |
| 段落文本 `ParagraphRow` | `contentDescription = "第 %1$d 段。长按可选中文字并朗读"` | `reader_paragraph_hint` |
| 朗读控制条 · 上一句 | 图标 `contentDescription` | `tts_previous` |
| 朗读控制条 · 播放/暂停 | `contentDescription` 随状态切换 | `tts_play` / `tts_pause` |
| 朗读控制条 · 下一句 | `contentDescription` | `tts_next` |
| 朗读控制条 · 停止 | `contentDescription` | `tts_stop` |
| 语速选择器 | `semantics { contentDescription = "语速 1.0x" }`（只念「1.0x」读不出含义） | `tts_rate` |
| 顶部栏 · ☰ | `contentDescription = "打开我的"` | `action_menu` |
| 顶部栏 · 导入 | `contentDescription = "导入文本"` | `action_import` |
| 历史项 · 删除 | 图标 `contentDescription = "移除记录"` | `drawer_remove` |
| 装饰性图标（空态、空文件占位图标、工具栏内图标） | `contentDescription = null`（与相邻文字重复，避免啰嗦） | — |

其他可访问性相关：`supportsRtl="true"`；正文「首行缩进 = 字号 × 2」用 `TextIndent` 实现（不是插入空格字符，因此屏幕阅读器不会念出空白）。

---

## 6. 边界与异常行为表

| # | 场景 | 行为 | 证据 |
|---|------|------|------|
| 1 | 0 字节文件 | 显示「文件是空的」占位 + Snackbar；不写历史；不崩溃 | `p5-empty-file-state.png`、`p5-en-empty-file.png` |
| 2 | 只有 BOM 的文件 | `normalize()` 去 BOM → `charCount == 0` → 同上 | 单测 `只有 BOM 的文件内容为空` |
| 3 | 纯空白 / 纯换行 | `paragraphCount == 0`，`chapters.size <= 1`（保留章节占位） | 单测 `纯空行文件不产生段落但保留章节占位` |
| 4 | 无扩展名文件（`noext`） | 完全正常：按内容探测编码 → 分章分段 | `p5-noext-reader.png` |
| 5 | 超过 20 MB | **截断**到 20 MB（先退到完整 UTF-8 字符，再退到最后一个换行之后）；Snackbar 提示；正文中文正常 | `p5-huge-truncated.png`（56.71 MB → 20 MB） |
| 6 | GB18030/GBK 中文 | 严格 GB18030 校验通过 → 按 GB18030 解码，无乱码 | `p5-gbk-reader-en.png`、`p5-empty-import.png`（对照） |
| 7 | UTF-16 LE/BE（有 BOM） | 按 BOM 判定 UTF-16LE/BE，跳过 2 字节 BOM | 单测 `UTF16 小端文本被识别` / `UTF16 大端文本被识别` |
| 8 | 少量损坏字节混在 GBK 文本里 | 严格校验失败 → GBK 双字节启发式（配对比例 ≥ 0.8）→ 按 GB18030 解码，乱码被限制在损坏字节处 | 单测 `GBK 文本夹带损坏字节时仍按 GB18030 解码` |
| 9 | 二进制/随机字节（如 `C3 28 41 42`） | 配对比例 0 → 不误判为 GBK，回退 UTF-8 | 单测 `孤立高位字节的二进制内容不会被误判为 GBK` |
| 10 | **20 MB 处截断 UTF-8**（历史缺陷） | `trimToCompleteLine` + `looksLikeUtf8Truncated` 双保险 → 判为 UTF-8，仅末尾可能 1 个 `\uFFFD` | `p5-huge-truncated.png`；单测 5 条（见 TEST.md §4） |
| 11 | URI 失效（文件被删除/移动） | `canRead()` 为 false → Snackbar「已无法访问，请重新导入」；记录保留、不崩溃 | 用例 R-08（手工） |
| 12 | SAF 持久授权失败 | `persistPermission` 静默忽略（部分 provider 不支持）；下一次冷启动 `canRead()` 为 false → 静默放弃自动恢复 | `restoreLastBook()` |
| 13 | 进程被杀后冷启动 | 有 `last_uri` 且可读 → 自动恢复该文本 + 上次进度（SharedPreferences 与 Room 取更靠后者）；不可读 → 静默清除 `last_uri` | `p5-restore-after-kill.png`、`p5-restore-scrolled.png` |
| 14 | 权限弹窗重复打扰 | 同版本内只自动弹一次；升级后重置「不再提示」再弹一次；之后只能从抽屉手动打开 | `p5-first-launch.png`、`p5-restore-after-kill.png` |
| 15 | 系统语言为英文 | 全部界面文案切到英文（含权限弹窗、空态） | `p5-en-permission-dialog.png`、`p5-en-empty-file.png` |
| 16 | 横竖屏切换 | 阅读位置按**段落锚点**保留：列表尺寸变化（`layoutInfo.viewportSize`）触发重新落位；进度每次滚动写入 SharedPreferences/SavedStateHandle（Room 每 10 段节流）。旋转后仍停在同一段落，不崩溃 | 用例 **R-11**（实测 竖 71 → 横 71 → 横内滚动 77 → 竖 77）、`p5-rotate-landscape.png` |
| 17 | 切后台 / 锁屏 | `Lifecycle.ON_STOP` → 保存进度 + 停止朗读 | 用例 R-10 |
| 18 | 端侧引擎不可用（ABI/内存/模型损坏） | 自动回退系统语音并提示，只回退一次 | 用例 R-11 |
| 19 | 模拟器缺中文语音数据 | 切默认语言重试一次 → 仍失败给中文语音包引导 | 用例 R-12 |
| 20 | 打开设置页/文件选择器失败 | Snackbar `error_open_settings_failed` / `error_open_picker_failed` | 用例 R-13 |
| 21 | 超长无标点文本（如 base64） | 硬切分，段长 ≤ 500，**不丢字** | 单测 `超长无标点文本按硬切分且不丢字` |

---

## 7. 非功能需求

| 类别 | 目标 | 实现手段 | 实测 |
|------|------|---------|------|
| **内存** | 20 MB 文本导入峰值 **PSS < 512 MB** | ① 单文件上限 20 MB ② 单次线性扫描归一化（`normalize` 不产生百万级临时 String） ③ `LazyColumn` 懒加载（只组合可视项） ④ 解析在 IO 线程、导入结束即释放原始 `ByteArray` | 5.37 MB 文本导入 PSS ≈ **138 MB**；56.71 MB（截断到 20 MB）导入 PSS ≈ **212 MB**（`p5-bigbook-reader.png`、`p5-huge-truncated.png`） |
| **无 ANR** | 导入/解析/滚动全程不阻塞主线程 | 读文件与解析都在 `Dispatchers.IO`；数据库写入在 `viewModelScope` 协程；正文懒加载 | 实测均无 ANR 弹窗（20 MB 真实样本的 ANR 观察仍未闭环，见 TEST.md §7） |
| **启动与响应** | 冷启动 ≤ 2 s | 单 Activity + Compose；冷启动不做网络/磁盘重活（模型安装检查只做文件存在性 + 体积比较） | 见 TEST.md 用例 R-01 |
| **离线可用** | 不下载端侧模型时**零网络请求** | 导入（SAF）、解析、阅读、系统语音、历史全部本地；`INTERNET` 仅用于可选的模型下载与自更新 | 用例 R-14 |
| **权限最小化** | 不申请任何存储权限即可完整使用 | 导入走 SAF + 持久 URI 授权；模型与 APK 放应用私有目录/缓存；存储权限仅为「可选增强」 | 清单见 DEV.md §6 |
| **可维护性** | 解析器可在 JVM 单测中直接验证 | `TextParser` / `EncodingDetector` 为纯 Kotlin（无 Android 依赖）；`UiStrings` 让 data/viewmodel 层不依赖 Compose | 32 个单测全绿（TEST.md §2） |
| **包体** | Debug APK 含 LiteRT 三 ABI 原生库 | Release 开 `isMinifyEnabled` + `isShrinkResources`，规则见 `app/proguard-rules.pro` | `assembleRelease` 可产出 |

---

## 8. 本版不做 / 已知限制

1. **句级高亮（端侧 Qwen3-TTS）**：端侧流式 PCM 与文本 token 无显式对齐，当前按「已写出采样数 / 预估总采样数」
   粗略推进，实际表现为**句级**高亮；字级跟随需建立帧 → token 时间轴（见 [P3-STATUS.md](P3-STATUS.md) §6）。
   系统 TTS 路径仍是**字级**（`onRangeStart`）。
2. **非任意区间选择**：长按只能选中「整句」，工具栏可扩到「整段」；**没有拖拽手柄**，不能选任意区间。
   计划 V1.1 用原生 `SelectionContainer` 支持任意区间。
3. **软分章标题固定为中文「第 N 节」**：`fallbackSplit(titlePrefix = "第 %d 节")` 未走资源，英文界面下同样输出中文。
4. **Release 暂不签名**：`app/build.gradle.kts` 的 release 未配置 `signingConfigs`，AGP 默认回落到 debug 签名
   （因此 `assembleRelease` 仍产出可安装、可自更新的 APK）；正式分发前必须补签名配置。
5. **`lintVital` 被禁用**：AGP 8.7.3 自带的 lint（UAST）解析不了 Kotlin 2.2 编译的字节码，
   `lintVitalAnalyzeRelease` 必然崩溃（`Found class ...KaCallableMemberCall, but interface was expected`），
   因此 `checkReleaseBuilds = false` 且 `tasks.matching { it.name.startsWith("lintVital") }` 全部禁用。
   日常静态检查改用 `gradlew :app:lintDebug`。**这与 R8 压缩无关，压缩本身正常。**
6. **超大文件是「截断」而不是「流式分页」**：20 MB 之后的正文不可见（Snackbar 已提示）。
   计划文档中的「>2MB 流式/分块解析」未按原方案实现，改为「上限截断 + 线性扫描解析 + 懒渲染」。
7. **端侧 TTS 不满足实时**：实测 RTF ≈ 4~5（官方 Pixel 8a 参考值 2.06），播放期间仍会周期性 underrun；
   「首音 1 秒内」在本模型上不可达（流式分块后首音 ≈ 6.8 s）。
   内存常驻约 1.2~1.6 GB，4 GB 设备会被系统回收；已按「内存 < 6 GB 建议用系统语音」处理。
8. **端侧仅 CPU 后端**：`CompiledModel(Accelerator.CPU)`，NPU/GPU 需按机型适配。
9. **音色固定**：使用仓库自带 `demo_speaker.npy`，无音色管理界面。
10. **无目录/书签跳转**：章节只能靠 TalkBack 的标题导航或手动滚动定位。
11. **历史去重靠「文件名 + 字数」**：极少数情况下（同名同字数的两个不同文件）可能被误合并；
    历史遗留的脏数据不会自动清理。
12. **`MANAGE_EXTERNAL_STORAGE` / `REQUEST_INSTALL_PACKAGES` 敏感**：上架 Google Play 需说明用途或改用更窄 API。
13. **进度落盘有节流**：滚动过程中 Room 只在「跨章」或「每 10 段」写一次；
    `SharedPreferences` / `SavedStateHandle` 每次滚动都写（很便宜），
    恢复时取两者中更靠后的位置，因此最多只会退回到最近一个 10 段检查点以内
    （实测滚到第 71 段后杀进程，冷启动停在第 76 段的反向误差 ≤ 1 段，见 [TEST.md](TEST.md) R-11）。
14. **模型完整性判定是「存在 + 体积下限」**：不看哈希、不看文件头。因此
    ① 半截文件若已超过该文件的最小体积（如 `talker_int4.tflite` 的 200 MB）会被当成完整，
    实际要到引擎加载阶段才报错；② 手工补文件/脚本推送时请保证文件完整。
    下载路径本身是安全的：`.part` 校验通过后才重命名。

---

## 9. 代码地图（按本规格的章节索引）

| 规格章节 | 关键文件 |
|---------|---------|
| §2.1 导入 / 编码 | `app/src/main/java/com/hrstts/jiandu/data/SafSource.kt`、`domain/parser/EncodingDetector.kt` |
| §2.2 解析 | `app/src/main/java/com/hrstts/jiandu/domain/parser/TextParser.kt` |
| §2.3 阅读界面 | `ui/reader/ReaderScreen.kt`、`ui/reader/ReaderItems.kt`、`ui/reader/SelectionToolbar.kt`、`ui/reader/TtsControlBar.kt`、`ui/reader/ReaderEmptyState.kt` |
| §2.4 历史抽屉 | `ui/history/HistoryDrawer.kt`、`data/db/{HistoryEntry,HistoryDao,AppDatabase}.kt`、`viewmodel/{ReaderViewModel,HistoryViewModel}.kt` |
| §2.5 语音引擎 | `domain/tts/{TtsEngine,TtsController,ReadingSegments}.kt`、`data/tts/{SystemTtsEngine,Qwen3TtsEngine}.kt` |
| §2.6 模型管理 | `data/tts/Qwen3ModelManager.kt`、`ui/settings/VoiceEngineCard.kt`、`viewmodel/VoiceEngineViewModel.kt` |
| §2.7 权限引导 | `data/permission/AppPermissionCenter.kt`、`viewmodel/PermissionViewModel.kt`、`ui/permission/PermissionDialog.kt` |
| §2.8 自更新 | `data/update/UpdateManager.kt`、`viewmodel/UpdateViewModel.kt`、`ui/update/UpdateDialog.kt` |
| §5 i18n | `i18n/{UiStrings,UiText,AndroidStrings}.kt`、`ui/text/UiTextResolve.kt`、`res/values{,-en}/strings.xml` |
| 骨架 | `MainActivity.kt`、`ui/AppScaffold.kt`、`ui/theme/*` |
| 端侧推理 | `qwen3tts/`（`Qwen3TtsEngine.kt`、`Qwen3TtsSynthesizer.kt`、`QwenBpeTokenizer.kt`、`Npy.kt`） |
