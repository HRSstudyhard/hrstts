# 测试用例清单（含本轮实测结果）· 简读 / JianDu

> 规格见 [SPEC.md](SPEC.md)，环境与构建见 [DEV.md](DEV.md)，端侧 TTS 专项数据见 [P3-STATUS.md](P3-STATUS.md)，
> 阶段进度与历史证据见 [PROGRESS.md](PROGRESS.md)。
>
> 被测版本：`versionCode 3` / `versionName 0.3.0`
> 截图目录：`docs/shots/`
> 本轮实测日期：2026-10-13（单测时间戳取自 `app/build/test-results/testDebugUnitTest/*.xml`）

---

## 1. 测试分层

| 层级 | 覆盖对象 | 运行方式 | 本文件章节 |
|------|---------|---------|-----------|
| **① JVM 单元测试** | 编码探测（8 级策略）、章节/段落解析（正则、模式、二次切分、软分章）、归一化 | `gradlew :app:testDebugUnitTest`（无需设备） | §2 |
| **② 手工上机回归** | 导入链路、i18n、空文件、无扩展名、GBK、进程被杀恢复、权限弹窗策略 | Android 模拟器 AVD `jiandu_api37` + `tools/ui.ps1` + 截图 | §3 |
| **③ 性能与内存** | 大文件首章可见耗时、进程 PSS | `dumpsys meminfo` + 时间戳打点 | §5 |
| **④ 缺陷回归** | 已修复的 4 个缺陷（超大文件乱码、解析慢、权限弹窗骚扰、空文件白屏） | 单测用例 + 定点上机复现 | §4 |
| **⑤ 构建验收** | Debug / Release 构建、单测任务、产物可用性 | Gradle | §6 |

**设备环境（模拟器）**

```
AVD              jiandu_api37（手工创建，配置见 C:\Users\H\.android\avd\jiandu_api37.avd\config.ini）
镜像             Android API 37 · x86_64 · 16 KB 页
启动参数         -gpu off -feature -Vulkan -memory 8192      # 无窗口模式
必需注意         不要加 -no-audio（会导致系统 TTS 合成必然失败）
                 -gpu off -feature -Vulkan 必需（swiftshader Vulkan 初始化会崩溃）
                 -memory 8192 必需（端侧模型常驻约 1.2~1.6 GB，4 GB 会被 low-memory kill）
```

**辅助脚本 `tools/ui.ps1`**（本轮回归的实际操作入口，坐标来自 `uiautomator dump` 的真实 bounds，避免手写坐标猜错）：

| 命令 | 作用 |
|------|------|
| `.\tools\ui.ps1 dump` | 输出当前界面的可点击/有文本节点（`x,y clickable=… text='…' desc='…'`） |
| `.\tools\ui.ps1 tap <x> <y>` | 按坐标点击（`adb shell input tap`） |
| `.\tools\ui.ps1 tapText "文本"` | 先精确匹配 `text`/`content-desc`，再模糊包含匹配，点击该节点中心；找不到则报错退出（code 2） |
| `.\tools\ui.ps1 shot <文件名>` | `screencap` → `adb pull` 到 `docs/shots/<文件名>` |
| `.\tools\ui.ps1 back` | 发送返回键（keyevent 4） |

脚本内固定 `adb = C:\Users\H\AppData\Local\Android\Sdk\platform-tools\adb.exe`，仓库根通过 `$PSScriptRoot` 推导。

---

## 2. JVM 单元测试清单（共 32 个用例）

**本轮实跑结果：32 / 32 通过，0 失败，0 错误，0 跳过。**

| 套件 | 用例数 | 失败 | 错误 | 跳过 | 耗时 |
|------|-------|------|------|------|------|
| `com.hrstts.jiandu.domain.parser.TextParserTest` | 15 | 0 | 0 | 0 | 0.042 s |
| `com.hrstts.jiandu.domain.parser.EncodingDetectorTest` | 17 | 0 | 0 | 0 | 0.052 s |
| **合计** | **32** | **0** | **0** | **0** | **0.094 s** |

命令：`.\gradlew.bat :app:testDebugUnitTest` → `BUILD SUCCESSFUL in 43s`
报告：`app/build/test-results/testDebugUnitTest/TEST-com.hrstts.jiandu.domain.parser.TextParserTest.xml`、
`…EncodingDetectorTest.xml`

### 2.1 `TextParserTest`（15）

源文件：`app/src/test/java/com/hrstts/jiandu/domain/parser/TextParserTest.kt`

| # | 用例名 | 断言要点 |
|---|-------|---------|
| 1 | `按空行分段并识别章节标题` | `chapters.size == 2`；标题分别为「第一章 少年」「第二章 归途」；每章 `paragraphs.size == 1`；段落文本 == 「少年出发了。」/「他回来了。」 |
| 2 | `无空行时每行视为一个段落` | `chapters.size == 2`；第 0 章 `paragraphs.size == 2`；`paragraphs[1].text == "他走了很远。"`（AUTO 在空行占比为 0 时回落 `PER_LINE`） |
| 3 | `首个标题之前的内容归入前言章节` | `chapters.size == 2`；`chapters[0].title == null`（前言）；前言 `paragraphs.size == 2`；`chapters[1].title == "第一章 少年"`；`chapters[1].paragraphs[0].index == 0`（章内重新编号） |
| 4 | `超长段落按句末标点二次切分` | 配置 `maxParagraphChars = 50`、`PER_LINE`；110 字长行（每句 11 字）→ `paragraphs.size == 3`；每段 `length <= 50`；每段以「。」结尾；`joinToString("") == 原文`（不丢字） |
| 5 | `没有章节标题时按字数软分章` | 配置 `fallbackChapterChars = 50`、`PER_LINE`；20 行 → `chapters.size > 1`；所有标题 `startsWith("第 ")`（即「第 N 节」）；段落总数仍为 20 |
| 6 | `章节标题判定不误伤正文` | 9 条表驱动断言：`第一章 少年`✔ `第 12 回`✔ `第一百二十三章`✔ `Chapter 3: The Beginning`✔ `楔子`✔ `番外 一`✔；`第一章的教室里没有人。`✘ `他翻开了第二章，发现字迹模糊不清，于是放下书走了出去。`✘ `正文中间的一句话。`✘ |
| 7 | `归一化统一换行符并去除BOM` | `normalize("\uFEFF第一章\r\n正文\r结束")`：不以 `\uFEFF` 开头、不含 `\r`、结果 == `"第一章\n正文\n结束"` |
| 8 | `空白文本不会导致崩溃` | `parse("   \n\n  ")`：`chapters.size == 1`；`paragraphCount == 0`；`charCount == 0` |
| 9 | `空文件解析结果为空书且不崩溃` | 空串 → `paragraphCount == 0`、`charCount == 0`；`chapters.isEmpty() \|\| 所有章节段落为空`（界面据此显示空文件占位） |
| 10 | `只有 BOM 的文件内容为空` | `parse("\uFEFF")` → `charCount == 0` |
| 11 | `CRLF 与 LF 混排按同一段落规则切分` | 混排文本 + `PER_LINE`：`chapters.size == 1`；`paragraphs.size == 3`；`paragraphs[2].text == "他停了下来。"`（CRLF 只算一次换行） |
| 12 | `行尾空白与制表符被清理` | 行尾追加空格与两个制表符 → 段落文本 == `"正文一句话。"` |
| 13 | `英文 Chapter 标题同样可以分章` | `Chapter 1: The Beginning` / `Chapter 2` → `chapters.size == 2`；`chapters[0].title == "Chapter 1: The Beginning"`；`chapters[1].paragraphs[0].text == "He came back."` |
| 14 | `超长无标点文本按硬切分且不丢字` | 配置 `maxParagraphChars = 30`、`PER_LINE`；120 字符无标点 → `paragraphs.size > 1`；每段 `length <= 30`；`joinToString("") == 原文`（走硬切分支） |
| 15 | `纯空行文件不产生段落但保留章节占位` | `"\n\n\n\n"` → `paragraphCount == 0`；`chapters.size <= 1` |

### 2.2 `EncodingDetectorTest`（17）

源文件：`app/src/test/java/com/hrstts/jiandu/domain/parser/EncodingDetectorTest.kt`

| # | 用例名 | 断言要点 |
|---|-------|---------|
| 1 | `UTF8 无 BOM 文本被识别为 UTF-8` | `charset == UTF8`；`bomLength == 0` |
| 2 | `UTF8 BOM 文本识别并给出 BOM 长度` | `EF BB BF` 前缀 → `charset == UTF8`；`bomLength == 3` |
| 3 | `GBK 文本被识别为 GB18030` | GB18030 编码的「第一章 少年」→ `charset == GB18030`；且按该编码解回原文 |
| 4 | `UTF16 小端文本被识别` | `FF FE` + UTF-16LE → `charset == UTF16_LE`；`bomLength == 2` |
| 5 | `UTF16 大端文本被识别` | `FE FF` + UTF-16BE → `charset == UTF16_BE`；`bomLength == 2` |
| 6 | `严格校验能区分合法与非法 UTF8 字节序列` | `isValid(合法中文, UTF8) == true`；`isValid(C3 28, UTF8) == false`；`isValid(C3 28, GB18030) == false`（严格模式 `REPORT` 生效） |
| 7 | `GBK 文本夹带损坏字节时仍按 GB18030 解码` | GB18030 中文 + 孤立 `0xFF`（严格 UTF-8/GB18030 均失败）→ `charset == GB18030`；解码结果 `startsWith("中文测试内容")`（损坏范围被限制） |
| 8 | `孤立高位字节的二进制内容不会被误判为 GBK` | `C3 28 41 42` → `looksLikeGbk == false`（配对比例 0，`0x28` 不是合法 GBK 尾字节） |
| 9 | `纯 ASCII 文本判定为不是 GBK` | ASCII 文本 → `looksLikeGbk == false`（首字节数 0 直接返回 false） |
| 10 | `合法 GBK 双字节序列被启发式认可` | GB18030「野草与少年」→ `looksLikeGbk == true` |
| 11 | `UTF8 中文文本优先识别为 UTF-8 而不会走到 GBK 启发式` | 纯 UTF-8 中文 → `detect().charset == UTF8`（在第 4 级就被拦下） |
| 12 | `detect 支持限定读取长度` | `detect(bytes, head)` 只探测前段合法 UTF-8 → `charset == UTF8`（`length` 参数生效） |
| 13 | `UTF8 文本被截断半个汉字时仍判定为 UTF-8` | 末尾砍 1、2 字节各一轮；失败信息里打印 `cut= utf8Valid= gb18030Valid= utf8Truncated= gbkHeuristic= detected=`；断言两轮 `detected == UTF8` |
| 14 | `截断后的 UTF8 文本按 UTF-8 解码只有末尾一个替换字符` | 砍 2 字节 → `charset == UTF8`；`text.count { it == '\uFFFD' } == 1`；`startsWith("第一章 少年出发了")` |
| 15 | `真正的 GBK 文本不会被误判为截断的 UTF8` | GB18030 文本 → `looksLikeUtf8Truncated == false`；`detect().charset == GB18030` |
| 16 | `带损坏字节的 GBK 文本仍走 GBK 启发式（不被 UTF8 截断规则抢走）` | GB18030 中文 + `0xFF` → `looksLikeUtf8Truncated == false`；`detect().charset == GB18030` |
| 17 | `截断到只剩续接字节时不崩溃` | 全续接字节 `80 BF 80` → `looksLikeUtf8Truncated == false`；空数组 → `false` |

> 覆盖情况：BOM 三分支（UTF-8/UTF-16LE/UTF-16BE）各 1 条，严格校验 1 条，
> GBK 启发式的正/负例 4 条，**P5 截断回归 5 条（#13–#17）**，长度限定 1 条。

---

## 3. 上机回归用例表

设备：AVD `jiandu_api37`，API 37 x86_64 16 KB 页，`-gpu off -feature -Vulkan -memory 8192`，无窗口。

| 编号 | 场景 | 步骤 | 预期 | 实测结果 | 证据截图 |
|------|------|------|------|---------|---------|
| R-01 | 冷启动首次运行 → 权限引导 | 全新安装 → `am start` 启动 → `tools/ui.ps1 dump` 读取弹窗节点 | 弹出「权限与联网」；网络项显示「已联网」✓；可从弹窗跳「所有文件访问」「安装未知应用」设置页 | **通过**：弹窗如期出现，网络项 ✓（不使用 `VALIDATED` 判定后不再误报离线） | `p5-first-launch.png` |
| R-02 | 系统语言为英文时的 i18n | 模拟器切英文 → 启动 → 打开权限弹窗 → 导入空文件 | 权限弹窗、空态等**全部**界面文案切到英文 | **通过**：`Permissions & network` 弹窗与 `The file is empty` 空态均为英文 | `p5-en-permission-dialog.png`、`p5-en-empty-file.png` |
| R-03 | 空文件导入 | 导入 0 字节 `empty.txt` | 显示空文件占位（标题 + 说明），不崩溃；不写入历史 | **通过**：显示「The file is empty / 文件是空的」占位，无崩溃 | `p5-empty-file-state.png`（英文界面另见 `p5-en-empty-file.png`） |
| R-04 | GBK 编码文本导入 | 导入 GBK 编码的 `gbk-sample.txt` | 自动识别为 GB18030；章节标题与正文无乱码 | **通过**：章节「第一章 编码测试」与正文均无乱码 | `p5-gbk-reader-en.png` |
| R-05 | 无扩展名文件导入 | 导入无扩展名文件 `noext` | 正常分章分段（不依赖扩展名） | **通过**：章节/段落结构与正常 `.txt` 一致 | `p5-noext-reader.png` |
| R-06 | 进程被杀后自动恢复 | 导入 `gbk-sample.txt` → 滚动若干章 → `adb shell am force-stop com.hrstts.jiandu` → 重新冷启动 | 自动恢复上次文本与进度；权限弹窗**不再重复弹出** | **通过**：恢复上次文本（`gbk-sample.txt`），权限弹窗未再弹出 | `p5-restore-after-kill.png` |
| R-07 | 大文件导入（解析耗时与内存） | 导入 5.37 MB 文本 | 无 ANR；首章可见耗时可接受；PSS 远低于 512 MB | **通过**：首章可见 ≈ **3.2 s**，PSS ≈ **138 MB** | `p5-bigbook-reader.png` |
| R-08 | 超大文件截断（回归缺陷①） | 导入 56.71 MB 文件 `ahuge.txt` | 截断到 20 MB；Snackbar 提示；正文中文**不乱码** | **通过**：截断生效，首章可见 ≈ **6.0 s**，PSS ≈ **212 MB**，正文中文正常（修复前整篇乱码） | `p5-huge-truncated.png` |
| R-09 | 截断提示条（辅助证据） | 同 R-08，截取提示 UI | 出现「文件超过 20MB，仅显示前部分内容」 | 通过（辅助证据，非本轮主判据） | `p5-huge-snackbar.png` |
| R-10 | 编码修复前对照 | 修复前的超大文件导入 | 整篇乱码（缺陷现象留证） | 已复现（修复前） | `p5-empty-import.png`（早期一轮对照图，标注为修复前/辅助证据） |
| R-11 | 阅读位置保留矩阵（回归缺陷⑤⑥） | 5.37 MB 文本 → 滚动到第 71 段 → 依次执行：旋转横屏 → 横屏内滚动 → 转回竖屏 → 切后台回前台 → `force-stop` 冷启动 → 从「我的」打开 | 每一步都停在同一段落（允许 1 段误差） | **通过**：71 → 横屏 71 → 横屏滚动 77 → 回竖屏 77 → 回前台 77 → 冷启动 76 → 历史打开 76 | `p5-rotate-landscape.png`、`p5-rotate-portrait-back.png`、`p5-restore-scrolled.png` |
| R-11b | 小文件（约 2 屏）位置保留 | 导入 `docs/samples/halfscreen.txt`（第一章 + 24 段）→ 滚动到第 15 段 → 横屏 → 竖屏 → `force-stop` 冷启动 | 同上 | **通过**：15 → 横屏 15 → 竖屏 15 → 冷启动 14 | 无独立截图（与 R-11 同一套操作） |
| R-11c | R8 release 包的位置保留 | 安装 `app-release-debug-signed.apk` → 5.37 MB 文本滚动到第 26 段 → 横竖屏往返 | 压缩后行为与 debug 一致，无混淆导致的异常 | **通过**：26 → 横屏 26 → 竖屏 26；无 `ClassNotFound`/`NoSuchMethod` | `p5-release-rotate-landscape.png` |
| R-12 | 换书后回到文首 | 阅读 `bigbook.txt`（第 76 段）→ 从文件选择器导入另一本书 `noext` | 新书从第一章第一段开始，不沿用上一本的位置 | **通过**：显示「第一章 无名文件 / 没有扩展名也能导入并解析。」 | `p5-noext-reader.png` |
| R-13 | 长按选中工具栏（P5 复跑） | 长按段落 → 检查工具栏 | 选中框上方出现「阅读 / 整段 / 复制」 | **通过**：三个按钮就位（英文界面显示 `Read / Paragraph / Copy`） | `p5-select-toolbar.png` |
| **R-14** | **应用内正式下载端侧模型（本轮新增）** | 全新安装（无模型）→ 抽屉「语音引擎」→ 先「检测网络」→ 点「Download model」→ 全程 `ls -l` 采样 + UI 采样 | 镜像可达；11 个文件依次下载；进度百分比推进；完成后状态变「已安装」、引擎**热切换**到 Qwen3-TTS；无 `.part` 残留、无崩溃/ANR | **通过**：镜像探测 `hf-mirror.com → Reachable (HTTP 200, 244 MB)`（`huggingface.co` 超时，自动回退生效）；**11 个文件 / 1,592.4 MB**，第一次全量 **≈185 s**（平均 ≈8.6 MB/s，峰值 ≈26.6 MB/s），第二次全量 ≈135 s；结束后 `.part = 0`；UI 变 `On-device model installed: Fast graphs (int8 MTP + split codec)`；期间 PSS ≈ 112 MB，无 ANR | `p5-model-downloading.png` |
| R-14b | 引擎热切换（补 1 个缺失文件） | 删掉 `text_projection_fp32.npz`（25 MB）→ 冷启动（应为系统语音）→ 点下载补齐 → 观察引擎名与日志 | 补齐后**无需重启**切到端侧引擎并真正加载 | **通过**：下载前 `Current engine: System voice`；补齐后 `Current engine: Qwen3-TTS (on-device)`，日志 `端侧模型已加载：talker_int4 + mtp_folded_int8 + codec_partA + codec_partB（采样率 24000）` | 日志 |
| R-15 | 下载后立即合成（模型可用性） | 导入 `tiny.txt` → 长按段落 → 点「Read」 | 端侧引擎真正加载并出声（不是「已安装但加载失败」） | **通过**：`libLiteRt.so` 加载 + XNNPACK 413/413 节点替换；日志 `流式合成完成：40 帧（3.20s 音频），首音延迟=12513ms，总耗时=24009ms，RTF=7.50`；`AudioTrack delivered 76800 frames`（=3.2 s×24000）；全程无异常栈 | 日志（模拟器 RTF 偏高，真机见 P3-STATUS.md） |
| R-16 | 断点续传（模型下载） | 造一个 3.07 MB 的 `text_projection_fp32.npz.part` → 删掉同名完整文件 → 点「Download model」→ 每 0.7 s 采样 `.part` 大小 | 从已有字节继续（`.part` 从 3 MB 起增长），完成后重命名为完整文件 | **通过**：`.part` 3.07 → **4.70 → 13.16 → 22.01 MB** → 重命名为 25,179,078 字节的 `text_projection_fp32.npz`（与 P3-STATUS 记录一致）。若服务器忽略 Range 返回 200，代码会删掉 `.part` 从 0 重下，实测未发生 | 采样日志 |
| R-17 | 缺文件检测与补齐 | 删掉 `vocab.json` + `text_projection_fp32.npz` → 重启 | 启动后状态回到「未安装」、引擎回落系统语音；点下载只补缺失文件 | **通过**：重启后 `Current engine: System voice` + 未安装提示；下载补齐 2 个文件耗时 **99 s**，无 `.part` 残留 | 采样日志 |
| R-18 | 删除模型释放空间 | 抽屉「Delete on-device model」 | 目录清空、空间释放、引擎回落系统语音 | **通过**：`files/` 只剩 `profileInstalled`；/data 已用 **2.5 GB → 980 MB**（释放 ≈1.6 GB）；引擎回落 `System voice` | 采样日志 |

> 说明：R-09 / R-10 是同一轮的辅助证据，不单独作为验收项；
> 主判据是 R-07、R-08 的耗时/PSS 与 R-08 的「无乱码」结论。
> R-11 ~ R-13 是第二轮（修缺陷⑤⑥）补测的；R-14 ~ R-18 是第三轮（应用内正式下载端侧模型）补测的，
> 命令与判定方式见 §5.7。

### 3.1 尚未在本轮闭环的上机用例（手工，需补测）

| 编号 | 场景 | 步骤 | 预期 | 状态 |
|------|------|------|------|------|
| M-01 | 长按选中 → 阅读 | 长按段落 → 选中框上方出现「阅读/整段/复制」→ 点「阅读」 | 逐句连续朗读，高亮跟随，控制条出现 | 已在 P2 阶段验证（`p2-select.png`、`p2-final.png`），P5 未复跑 |
| M-02 | 暂停/继续/停止/语速 | 朗读中依次操作四个控件与语速档位 | 状态切换正确，无崩溃；改语速从当前句重播 | 已在 P2 阶段验证（`p2-pause.png`），P5 未复跑 |
| M-03 | URI 失效 | 导入文件 → 用文件管理器删除源文件 → 点历史条目 | Snackbar「《名》已无法访问，请重新导入该文件」；记录保留；不崩溃 | 待测 |
| M-04 | 横竖屏切换 | 阅读中旋转设备 | 列表重建后仍定位在当前可见段落；朗读状态不乱；不崩溃 | **已通过**（见 R-11：竖 71 → 横 71 → 横屏滚动 77 → 竖 77） |
| M-05 | 切后台/锁屏 | 阅读中按 Home / 锁屏 | 保存进度并停止朗读；回到前台可继续 | **部分通过**（见 R-11：切后台回前台仍停在第 77 段）；锁屏路径未单独复跑 |
| M-06 | 端侧引擎回退 | 端侧模型就绪时制造引擎错误（如 ABI 不支持/模型损坏） | 自动切系统语音并提示；只回退一次 | 待测 |
| M-07 | 系统语音缺中文数据 | 模拟器（Google TTS 无中文）点朗读 | 切默认语言重试一次 → 仍失败给中文语音包引导 | 已在 P2 阶段验证（见 PROGRESS.md §4.2），P5 未复跑 |
| M-08 | 设置页跳转 | 弹窗内点「打开设置」（所有文件访问 / 安装未知应用） | 焦点转到对应设置页；返回后状态自动刷新；失败给 Snackbar | 已在 P3 阶段验证（`perm-settings-jump.png`），P5 未复跑 |
| M-09 | 深色模式与动态取色 | 切换系统深色模式 / 更换壁纸色（Android 12+） | MD3 配色跟随，正文可读 | 待测 |
| M-10 | TalkBack 走查 | 开启 TalkBack 遍历界面 | 章节标题可「按标题导航」跳转；段落读出「第 N 段。长按可选中文字并朗读」；控制条按钮念出中文描述；语速念出「语速 1.0x」 | 待测 |
| M-11 | 自更新两条路径 | ① 填清单 URL 检查/下载/校验 ② 选本地 APK | 校验通过后拉起系统安装器；签名/包名/版本不符时给出明确原因 | 已在 P3 阶段验证（`update-dialog.png`、`update-drawer.png`、`update-perm-after-upgrade.png`），P5 未复跑 |
| M-12 | 端侧 TTS 连续朗读 | 长按 → 阅读（端侧模型已安装） | 流式播放首音 ≈ 6.8 s；高亮自动推进到下一句 | 已在 P3 阶段验证（`p3-playing.png`、`stream-playing.png`），P5 未复跑 |

---

## 4. 已修复缺陷回归表

| # | 现象 | 根因 | 修复 | 回归证据 |
|---|------|------|------|---------|
| ① | **超大文件截断后整篇乱码**：56.71 MB 文件导入后正文全是乱码，段落结构也随之丢失 | 20 MB 上限按**字节**截断，截断点正好落在一个汉字的 3 字节 UTF-8 序列中间 → 严格 UTF-8 校验失败 → 编码探测退到 GBK 启发式 → 整篇 UTF-8 中文被按 GB18030 解码 | 双保险：<br>**第一道** `SafSource.trimToCompleteLine()`：先退回完整 UTF-8 字符（最多 3 个续接字节），再在 64 KB 窗口内退到最后一个 `\n` 之后；<br>**第二道** `EncodingDetector.looksLikeUtf8Truncated()`：只认「合法多字节首字节（`C2–DF`/`E0–EF`/`F0–F4`）+ 数量正确但不足的续接字节（`80–BF`）」这一种形状，且前缀主体必须能通过严格 UTF-8 解码 | `EncodingDetectorTest` **5 条**新用例（`UTF8 文本被截断半个汉字时仍判定为 UTF-8`、`截断后的 UTF8 文本按 UTF-8 解码只有末尾一个替换字符`、`真正的 GBK 文本不会被误判为截断的 UTF8`、`带损坏字节的 GBK 文本仍走 GBK 启发式…`、`截断到只剩续接字节时不崩溃`）；上机 `p5-huge-truncated.png`（20 MB 正文中文正常） |
| ② | **超大文件解析慢**：20 MB 文本导入期间界面长时间等待 | `TextParser.normalize` 用 `split('\n')` + `joinToString`，20 MB 中文约 700 万行 → 产生约 **700 万个临时 String** | 改为**单次线性扫描**：一个 `StringBuilder` + 一趟遍历，同时完成 CRLF/CR 归一、去 BOM、去行尾空白 | 上机：5.37 MB → 首章可见 ≈ **3.2 s**、PSS ≈ 138 MB；20 MB（截断后）→ ≈ **6.0 s**、PSS ≈ 212 MB（`p5-bigbook-reader.png`、`p5-huge-truncated.png`）；`TextParserTest.归一化统一换行符并去除BOM` 保证行为不变 |
| ③ | **权限引导弹窗每次冷启动都弹出，挡住正文** | `PermissionViewModel` 只判断「是否勾选不再提示」，没有记录「本版本已经引导过」，因此每次冷启动都会自动弹出 | 引入 `shown_version_code` / `last_version_code`：**首次运行**或**版本号变化后的第一次运行**才自动弹出；「完成/不再提示」都会写下 `shown_version_code`；升级时重置 `dont_ask`（保证新版本的引导必出现一次）；之后只能从抽屉「权限与联网」手动打开 | 上机 R-06：`force-stop` 后冷启动**不再重复弹出**（`p5-restore-after-kill.png`）；首启仍弹出（`p5-first-launch.png`） |
| ④ | **空文件导入后阅读区一片空白** | `ReaderScreen` 只处理「有章节 → 渲染列表」，`paragraphCount == 0` 时渲染空列表，用户看到白屏且不知道原因 | 新增空文件占位：`reader_empty_file_title`（「文件是空的」）+ `reader_empty_file_body`（说明文案），由 `ReaderScreen` 在 `book.paragraphCount == 0` 时渲染；同时 Snackbar 提示 `import_empty_file` | 上机 R-03（`p5-empty-file-state.png`、`p5-en-empty-file.png`）；单测 `空文件解析结果为空书且不崩溃`、`只有 BOM 的文件内容为空`、`空白文本不会导致崩溃`、`纯空行文件不产生段落但保留章节占位` |
| ⑤ | **阅读进度从不落盘 + 横竖屏切换跳回文首**：滚到第 71 段后旋转屏幕/杀进程，都会回到第 1 段 | 两个独立原因：<br>**(a) 节流比较对象错了**——`onVisibleAnchorChanged` 拿「当前状态」与「本次新位置」比，滚动时差值恒为 1，`≥10 段` 的落盘条件永不成立（实测滚到第 71 段时 `last_paragraph` 仍是 0）；<br>**(b) 落位被瞬态读数覆盖**——新列表先停在文首并上报 `0-0`，把刚恢复的位置写回 0；<br>**(c) 「抑制上报」永不解除**——用「等下一次位置变化」作解除条件，而用户停手后 `snapshotFlow` 不再发射，于是进度永远不保存 | **(a)** 新增 `lastPersistedAnchor`（上次**真正落盘**的位置）与之比较；位置同时写 `SavedStateHandle` + SharedPreferences（每次滚动），Room 仍按 10 段/跨章节流；<br>**(b)** ViewModel 增加 `suppressVisibleAnchorReports`，界面在落位稳定后调用 `onInitialPositionSettled()` 解除；<br>**(c)** 改用 `collectLatest` + `delay(400ms)`：等待期间位置若再变则重新计时，稳定 400 ms 即解除抑制并上报；<br>**(d)** 列表尺寸变化（`layoutInfo.viewportSize`）触发 `layoutEpoch`，每次重排后按**段落锚点**重新落位（`scrollToItem(index)` 在横竖屏行高变化后会被重新测量顶掉） | 上机 R-11 全矩阵：竖 71 → 横屏 **71** → 横屏滚动 77 → 回竖屏 **77** → 切后台回前台 **77** → `force-stop` 冷启动 **76** → 从「我的」打开 **76**（`p5-rotate-landscape.png`、`p5-rotate-portrait-back.png`、`p5-restore-scrolled.png`） |
| ⑥ | **从「我的」重复打开同一文件会退回文首**：读到第 46 段，从历史抽屉再点开同一文件，回到第 1 段 | `load()` 成功后调用 `recordHistory()`，而它写入的 `chapterIndex/paragraphIndex` 恒为 `0/0`，把历史行里的进度覆盖掉了；此外 Room 的位置是节流写入的（每 10 段），恢复时会退回到旧检查点 | ① `recordHistory(book, anchor)` 改为保留**更靠后**的位置：`max(本次 anchor, 历史行位置, SharedPreferences 记录)`；② 新增 `lastRememberedAnchor(uri)`（只在 URI 相同时返回，避免拿上一本书的位置）；③ `openHistory` 同样取 Room 与 SharedPreferences 中更靠后的那个；④ `onVisibleAnchorChanged` 每次滚动都写 SharedPreferences，让「上次读到哪里」始终最新 | 上机：读到第 46 段 → 滚动到 26 → 从「我的」打开停在 **26**；完整矩阵见 R-11（冷启动 76 → 历史打开 **76**） |
| ⑦ | **下载中断后残留孤儿 `.part` 文件** | 下载被中断/进程被杀会留下 `.part`；下一次下载若该文件已完整（用户手工补过或用脚本推过）就会跳过它，`.part` 便永远留在磁盘上（实测一次中断留下 **40 MB** 孤儿文件，且 `full install` 判定不受影响、用户完全无感知） | `Qwen3ModelManager.cleanupStaleParts()`：只删除「同名文件已完整（存在且 ≥ minBytes）」的 `.part`；在 `refresh()`（应用启动）与每次下载结束后各调用一次。注意**不会**删掉真正需要续传的 `.part` | 上机：造 20 MB 的 `talker_int4.tflite.part`（对应文件已完整）→ 重启 → 启动清理后目录内无 `.part`；R-14/R-17 结束时也均为 `.part = 0` |

---

## 5. 性能与内存测法

### 5.1 内存（PSS）

```powershell
# 单次采样
adb shell dumpsys meminfo com.hrstts.jiandu | Select-String "TOTAL PSS"

# 只看一行（TOTAL 行的 PSS 列）
adb shell dumpsys meminfo com.hrstts.jiandu | Select-String "TOTAL"
```

采样时机：导入完成后静置数秒（等 `LazyColumn` 完成首屏组合）再读，避免读到解析中峰值以外的瞬时值。

### 5.2 解析耗时（首章可见）

以「点选文件」到「首章标题出现在屏幕上」为口径，用截图/`uiautomator dump` 的出现时刻与操作时刻相减。
本轮实测：

| 样本 | 原始体积 | 实际读入 | 首章可见 | 导入后 PSS |
|------|---------|---------|---------|-----------|
| 大文件样本 | 5.37 MB | 5.37 MB（未截断） | ≈ **3.2 s** | ≈ **138 MB** |
| 超大样本 `ahuge.txt` | 56.71 MB | 20 MB（截断） | ≈ **6.0 s** | ≈ **212 MB** |

### 5.3 界面操作与取证

```powershell
# 读取当前界面节点（带真实坐标）
.\tools\ui.ps1 dump

# 按文本/描述点击（等价于 adb shell input tap）
.\tools\ui.ps1 tapText "导入文本"
.\tools\ui.ps1 tap <x> <y>

# 截图到 docs/shots/
.\tools\ui.ps1 shot p5-xxx.png

# 返回键
.\tools\ui.ps1 back
```

### 5.4 进程与崩溃观察

```powershell
# 制造「进程被杀」场景（R-06 / R-11）
adb shell am force-stop com.hrstts.jiandu
adb shell am start -n com.hrstts.jiandu/.MainActivity

# 观察崩溃 / ANR / low-memory kill
adb logcat -d | Select-String "FATAL|ANR in com.hrstts.jiandu|lowmemorykiller|ActivityManager: Killing"
```

### 5.5 阅读位置保留矩阵（R-11）

```powershell
# 旋转（模拟器上用系统设置切方向，避免依赖重力感应）
adb shell settings put system user_rotation 1   # 横屏
adb shell settings put system user_rotation 0   # 竖屏

# 判定口径：dump 出「首个可见段落」的段号并逐步比对（允许 1 段误差）
#   第 1 章第 N 段  ← 正文段落
#   第 N 章         ← 章节标题（说明停在章首）
.\tools\ui.ps1 dump | Select-Object -First 1

# 阅读进度落盘位置（SharedPreferences，每次滚动都写）
adb shell run-as com.hrstts.jiandu cat /data/data/com.hrstts.jiandu/shared_prefs/jiandu_reader.xml

# 历史行里的位置（Room，节流写入；供「我的」恢复时与上面取更靠后者）
#   没有 sqlite3 时可用：run-as ... cat databases/jiandu.db 后按需导出
```

### 5.6 端侧 TTS 性能（详见 P3-STATUS.md）

```powershell
# 合成日志（RTF、首音延迟、各阶段耗时）
adb logcat -d | Select-String "RTF|首音延迟|prefill|talker|mtp|codec|AudioTrack"
```

已记录基线：整句合成 `119 帧 / 9.52s，RTF=4.29`；流式分块（`CHUNK_FRAMES = 16`）首音延迟 41 s → **6.8 s**。

### 5.7 应用内模型下载（R-14 ~ R-18）

```powershell
# 前置：模拟器 /data 至少留 2 GB（模型 1.59 GB + 余量）
adb shell df -h /data

# 1) 镜像探测（在抽屉「语音引擎」里点「Test network」，或直接看日志/UI dump）
.\tools\ui.ps1 tapText "Test network"
.\tools\ui.ps1 dump | Select-String "Reachable|Timed out"

# 2) 触发下载（等价于点抽屉里的「Download model」）
.\tools\ui.ps1 tapText "Download model"

# 3) 边下边看：进度来自 UI，落地字节来自应用私有目录（debug 包可 run-as）
$dir = "/data/data/com.hrstts.jiandu/files/qwen3-tts"
adb shell run-as com.hrstts.jiandu ls -l $dir
.\tools\ui.ps1 dump | Select-String "Downloading|installed|Current engine"

# 4) 断点续传：造一个 .part（对应完整文件先删掉），点下载后看它是否从已有字节继续
adb shell "run-as com.hrstts.jiandu dd if=/dev/zero of=$dir/text_projection_fp32.npz.part bs=1024 count=3072"
adb shell run-as com.hrstts.jiandu rm -f $dir/text_projection_fp32.npz
#   → 若支持续传：.part 从 3 MB 起增长（本机实测 3.07 → 4.70 → 13.16 → 22.01 MB 后转正）
#   → 若服务器忽略 Range（返回 200）：代码会删掉 .part 并从 0 重下，表现为 `.part` 先变小

# 5) 孤儿 .part 清理：造一个「同名文件已完整」的 .part，重启后应被清掉
adb shell "run-as com.hrstts.jiandu dd if=/dev/zero of=$dir/talker_int4.tflite.part bs=1024 count=20480"
adb shell am force-stop com.hrstts.jiandu; adb shell am start -n com.hrstts.jiandu/.MainActivity
adb shell run-as com.hrstts.jiandu ls -l $dir | Select-String "part"   # 期望：无输出

# 6) 删除模型并确认空间回收
.\tools\ui.ps1 tapText "Delete on-device model"
adb shell df -h /data
```

**本轮实测口径**：清单 11 个文件、合计 **1,592.4 MB**；第一次全量下载 **≈185 s**（平均 **≈8.6 MB/s**，峰值 **≈26.6 MB/s**），
第二次全量 ≈135 s；结束后 `.part = 0`；下载期间进程 PSS ≈ 112 MB，`logcat` 无 `ANR in com.hrstts.jiandu`。
第二镜像 `huggingface.co` 在本机网络下超时，`hf-mirror.com` 正常，回退链按预期工作。

> 观察技巧：`Downloading …（n/11）` 文案里带文件序号，配合 `run-as … ls -l` 的落地字节即可交叉确认
> 「UI 进度」与「磁盘实况」一致；只看磁盘总字节容易把「上一个文件已落地、下一个还没开始」误判为停滞。

---

## 6. 构建与验收命令

> **前置**：本机系统 JDK 为 25，Gradle 8.9 不支持，必须让构建使用项目自带的 JDK 21。
> `gradle.properties` 里已写 `org.gradle.java.home`，但显式设 `JAVA_HOME` 更稳妥：

```powershell
$env:JAVA_HOME = "F:\Projects\hrstts\.toolchain\jdk-21"
```

### 6.1 验收命令

```powershell
# ① Debug 构建
.\gradlew.bat :app:assembleDebug
#   产物：app\build\outputs\apk\debug\app-debug.apk

# ② JVM 单元测试（32 个用例，无需设备）
.\gradlew.bat :app:testDebugUnitTest
#   报告：app\build\test-results\testDebugUnitTest\*.xml
#        app\build\reports\tests\testDebugUnitTest\index.html

# ③ Release 构建（R8 压缩 + 资源压缩）
.\gradlew.bat :app:assembleRelease
#   产物：app\build\outputs\apk\release\app-release.apk
#   注意：当前未配置 signingConfigs，release 产物回落到 debug 签名（仍可安装做自更新验证）

# ④ 一次性跑完（本轮实际执行）
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
#   → BUILD SUCCESSFUL in 43s
```

### 6.2 安装与启动

```powershell
$adb = "C:\Users\H\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.hrstts.jiandu/.MainActivity
```

### 6.3 验收判据

| 判据 | 通过标准 | 本轮 |
|------|---------|------|
| 单元测试 | 32 / 32 通过 | ✅ 通过（0 失败） |
| Debug 构建 | `BUILD SUCCESSFUL`，产出 APK | ✅ 通过 |
| Release 构建 | `BUILD SUCCESSFUL`（`lintVital` 已禁用） | ✅ 可产出（未签名，见 §7） |
| 编译告警 | 0（仅个别弃用提示） | ✅ |
| 上机回归 | R-01 ~ R-13 全部通过 | ✅ 通过 |
| 内存 | 20 MB 导入峰值 PSS < 512 MB | ✅ 实测 ≈ 212 MB |
| ANR | 导入 20 MB 无 ANR | ⏳ 未闭环（见 §7） |
| 阅读位置 | 旋转/切后台/杀进程/从历史打开都停在同一段落 | ✅ 通过（R-11 矩阵，误差 ≤ 1 段） |

---

## 7. 未闭环项 / 待办

| # | 事项 | 现状 | 下一步 |
|---|------|------|-------|
| 1 | **20 MB 真实样本的 ANR 观察** | 本轮只在 5.37 MB 全量与 56.71 MB→20 MB 截断样本上确认「无 ANR 弹窗」，未做长时间/多次连续导入的压力观察，也没有 `ANR in com.hrstts.jiandu` 的日志对照 | 用真正的 20 MB 未截断文本连续导入若干轮，配合 `logcat` 过滤 `ANR in` 与 `dumpsys` 主线程阻塞；建议加 `Choreographer` 掉帧统计 |
| 2 | **端侧 Qwen3-TTS 在真机的 RTF** | 已有模拟器数据（RTF ≈ 4~5，首音 ≈ 6.8 s），真机数据缺失；官方 Pixel 8a 参考值 2.06 | 在中端真机（骁龙 7 系 / 8 GB）上跑同一脚本，记录 RTF、首音延迟、underrun 次数 |
| 3 | **Release 签名** | `app/build.gradle.kts` 未配置 `signingConfigs`，release 产物回落 debug 签名 | 生成正式 keystore，配置 `signingConfigs.release`（密钥不入库），再验证覆盖安装与自更新 |
| 4 | **恢复 `lintVital`** | AGP 8.7.3 的 lint（UAST）解析不了 Kotlin 2.2 字节码，`lintVitalAnalyzeRelease` 必崩，故整体禁用 | 升级 AGP 到能识别 Kotlin 2.2 的版本后，移除 `checkReleaseBuilds = false` 与 `tasks.matching { it.name.startsWith("lintVital") }` 的禁用 |
| 5 | 手工用例 M-03、M-06、M-09、M-10 | 未测（M-04/M-05 已在 R-11 中覆盖，见 §3.1） | 补测：URI 失效、端侧引擎回退、深色模式、TalkBack 走查 |
| 6 | 端侧字级高亮 | 端侧路径为句级高亮 | 建立帧 → token 时间轴（见 [P3-STATUS.md](P3-STATUS.md) §6） |
| 7 | 任意区间选择 | 只能选「句 / 整段」 | V1.1 用原生 `SelectionContainer` + 拖拽手柄 |
| 8 | 自动化 UI 测试 | `androidTest` 依赖已引入（Compose UI Test / Espresso），但**没有编写任何仪器化测试**；上机回归全靠 `tools/ui.ps1` + 截图 | 把 R-01 ~ R-08 固化为 Compose UI Test（可离线跑，不依赖人眼） |
