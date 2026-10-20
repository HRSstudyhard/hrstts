# 实施进度（简读 / JianDu）

> 计划见 [PLAN.md](PLAN.md)，环境见 [DEV.md](DEV.md)，P3 专项见 [P3-STATUS.md](P3-STATUS.md)，
> 功能规格见 [SPEC.md](SPEC.md)，测试用例见 [TEST.md](TEST.md)，电脑合成见 [REMOTE-TTS.md](REMOTE-TTS.md)
> 最近更新：P6（改为电脑合成）实施后

## 1. 阶段与里程碑状态

| 阶段 | 内容 | 状态 | 里程碑 |
|------|------|------|--------|
| P0 | 工程搭建 + MD3 基座 | ✅ 完成 | **M0 达成** |
| P1 | 文本导入 + 章节/段落解析 | ✅ 完成 | **M1 达成** |
| P2 | 长按选中 +「阅读」+ 系统 TTS | ✅ 完成 | **M2 达成** |
| P3 | Qwen3-TTS 端侧集成 | ✅ 完成（端到端实测通过；性能不满足实时，与原模型一致） | **M3 功能达成，性能项未达标** |
| P4 | 「我的」历史抽屉 | ✅ 完成（含进度恢复） | **M4 达成** |
| P5 | 打磨、测试与发布 | ✅ 工程项完成（加固 / i18n / 无障碍 / R8 / Release 构建 / 文档）；**发布签名按用户要求暂缓** | **M5 部分达成**（无正式签名） |
| P6 | **改为「电脑合成」远程 TTS**（不在手机上部署 TTS） | ✅ 完成（服务端 + 手机端 + 同步通道；37 项服务端自检 / 8 项新单测 / 3 项端到端集成测试全绿） | **M6 达成** |

## 1.1 P6：电脑当 TTS 服务器（功能方向变更）

需求变更：**不再在手机上部署 TTS**，改用本机做合成，手机发文本、收音频，
并把整本音频**同步到手机**以便离线听。

完整设计、HTTP 契约、实测数据与排错见 **[REMOTE-TTS.md](REMOTE-TTS.md)**。要点：

| 项 | 端侧 Qwen3-TTS（P3） | 电脑合成（P6） |
|----|---------------------|---------------|
| 首音延迟 | 6834 ms | **385 ms**（缓存命中 3 ms） |
| 合成 RTF | 4.29~8.7 | **0.011~0.082**（服务端） |
| 手机存储 / 内存 | 1.59 GB / 1.2~1.6 GB | **0 / 0** |

- 新增 `server/`（纯标准库 HTTP 网关 + 引擎插件 + 服务端缓存 + 自测页），
  当前后端是 **Windows SAPI**（零安装、离线、RTF 0.01~0.08）；
- 手机端新增 `RemoteTtsEngine` / `RemoteTtsClient` / `RemoteTtsProtocol` / 设置卡片，
  复用现有的 `TtsEngine`、`TtsController` 连播、`TtsAudioCache` 两级缓存与 AudioTrack 流式写入；
- **缓存 key 与片段文件格式两端完全一致**，所以「同步」是下载字节直接落盘，不转码；
  用一条跨语言固定向量单测钉住（Python 算出、Kotlin 断言）；
- 引擎回退链扩为「远程 → 端侧 → 系统语音」，连不上电脑自动回退；
- `versionCode 3 → 4`、`versionName 0.3.0 → 0.4.0`。

> 音质说明：当前 SAPI 音色偏机械，目的是先把链路与工程问题全部打通。
> 换 GPT-SoVITS / edge-tts 只需在 `server/jiandu_gateway/engines/` 加一个后端，
> **手机端不用改**（见 REMOTE-TTS.md §8）。

## 2. 每个阶段的验证证据

所有验证均在 **Android 模拟器（AVD `jiandu_api37`，API 37 x86_64 16KB 页，无窗口模式）** 上通过截图确认，截图存于 `docs/shots/`。

| 阶段 | 验证内容 | 证据截图 | 结论 |
|------|---------|---------|------|
| M0 | 应用可启动、MD3 顶部栏、无存储权限 | `m0-1.png`（启动图）、`m0-2.png`（空状态） | 左上「我的」☰、右上「导入」图标就位 |
| P1 | SAF 选文件 → 从文件头显示 → 自动分章节/分段 | `p1-picker.png` → `p1-downloads.png` → `p1-reader.png` | 前言 + 三章标题正确，段落按行切分，首行缩进生效 |
| P1 | 历史写入 Room 并在抽屉显示 | `p1-mydrawer.png` | 文件名 + 时间正确，选中态高亮 |
| P2 | 长按段落 → 选中框 + 上方「阅读/整段/复制」 | `p2-select.png` | 长按选中整句，工具栏位于**选中框上方** |
| P2 | 点「阅读」→ 逐句朗读 + 高亮跟随 + 控制条 | `p2-final.png` | 高亮推进到第 2 句 = 连续播放生效 |
| P2 | 暂停/继续/停止、语速选择 | `p2-pause.png` | 状态切换正常，无崩溃 |
| P4 | 点击历史条目替换当前文本 | `p4-switched.png` | `changye.txt` → `shanwu.txt` 正文整体替换 |
| P4 | 阅读进度保存与恢复 | `p4-scrolled.png` → `p4-restore.png` | 退出后重进历史，定位回第 6~7 章（非文件头） |
| P3 | 端侧模型被识别并作为首选引擎 | `p3-drawer.png` | 「当前朗读引擎：Qwen3-TTS（端侧）」「已安装：快速图组」 |
| P3 | 长按 → 阅读 → 端侧合成 + 播放 + 连续朗读 | `p3-playing.png` | 日志：`119 帧 / 9.52s，RTF=4.29`、`AudioTrack delivered 228480 frames`；高亮自动推进到下一句 |
| P3 优化 | 流式分块解码 + 边生成边播（首音延迟优化） | 日志 | **首音延迟 41s → 6.8s**（`首音延迟=6834ms`），自动连读 4 句；`CHUNK_FRAMES=16` |

### 构建与测试结果

```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest   → BUILD SUCCESSFUL
单元测试：32 个全部通过（TextParser 15 个 + EncodingDetector 17 个）
产物：app\build\outputs\apk\debug\app-debug.apk（含 LiteRT 三 ABI 原生库）
      app\build\outputs\apk\release\app-release-unsigned.apk（R8 压缩，未签名）
      app\build\outputs\apk\release\app-release-debug-signed.apk（用调试密钥签好，可安装自测）
编译告警：0
```

> 工具链本轮升级：Kotlin 2.0.21 → **2.2.10**、KSP → **2.2.10-2.0.2**、Room 2.6.1 → **2.7.2**
> （原因：LiteRT 元数据版本要求 Kotlin ≥ 2.2；Room 2.6.1 的 KSP 处理器遇到 LiteRT 相关类会抛
> `unexpected jvm signature V`）。新增 `:qwen3tts` 模块隔离 LiteRT，避免 `app` 的 KSP 崩溃。

## 3. 当前实现范围（对照原始需求）

### 3.1 启动权限引导（本轮新增需求）

进入应用后弹出「权限与联网」弹窗，三项各自显示实时状态：

| 项目 | 各版本真实机制 | 弹窗里的行为 |
|------|---------------|-------------|
| 存储访问 | Android ≤ 12：`READ/WRITE_EXTERNAL_STORAGE` 是运行时权限；Android 11+（含 13/14/15）：没有通用存储运行时权限，「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`) 是**特殊权限**，只能在系统设置页开启 | ≤ 12 弹系统权限框；≥ 11 跳「所有文件访问」设置页；返回应用自动刷新状态 |
| 网络 | `INTERNET` 是**安装时自动授予**的普通权限，任何应用都无法（也不需要）弹窗申请；`ACCESS_NETWORK_STATE` 同 | 不申请，只检测「当前是否联网」并给出 ✓/！状态；未联网时提供「打开网络设置」 |
| 安装未知应用 | Android 8+ 起是特殊权限 (`REQUEST_INSTALL_PACKAGES`)，只能由用户在「安装未知应用」页手动开启 | 跳该设置页（`ACTION_MANAGE_UNKNOWN_APP_SOURCES` + 本应用包名），返回后刷新 |

其他行为：勾选「不再提示」后不再自动弹出；抽屉底部有「权限与联网」可随时重新打开；全部跳过不影响
「导入文本 + 系统语音朗读」（这两项本来就不依赖上述权限）。

**上机验证（模拟器 API 37）**：进应用即弹出弹窗（`perm-dialog.png`）；
网络项检测为已联网并显示 ✓（修复了用 `NET_CAPABILITY_VALIDATED` 导致的误判）；
点「安装未知应用 → 打开设置」焦点成功转到 `com.android.settings`（无 ActivityNotFound / 异常）。

> ⚠️ 合规提醒：`MANAGE_EXTERNAL_STORAGE`（所有文件访问）与 `REQUEST_INSTALL_PACKAGES`（安装未知应用）
> 属于敏感权限，上架 Google Play 需要说明用途、通常会被要求提供核心功能理由或改用更窄的 API。
> 本应用的核心流程（SAF 导入 + 朗读）不需要它们，未授权时功能完整可用。

### 3.2 应用内自更新（本轮新增需求）

**为什么先做「版本可核对」**：用户反馈「装在手机上功能没更新」。这类现象的三个常见原因，
表现完全一样（都像「功能没更新」）：

1. **同版本号覆盖安装**：功能变了但 `versionCode` 仍是 1 → 「检查更新」永远认为已是最新，也无从判断装的是哪次构建；
2. **签名不一致**（debug/release 混装、换了打包机器）→ 系统直接拒绝并提示「应用未安装」，旧版本原样留着；
3. **旧数据里的开关吃掉了新功能**：例如「不再提示」是持久化的，新版本加了权限引导弹窗也会被它挡住。

因此本轮同时解决这三点：

| 措施 | 说明 |
|------|------|
| 版本号推进 | `versionCode 1 → 2`、`versionName 0.1.0 → 0.2.0`，并写入 `BuildConfig.BUILD_TIME` |
| 应用内可见版本 | 抽屉底部显示「版本 0.2.0（build 2）· 构建时间」，点击打开「检查更新 / 关于」弹窗 |
| 升级重置开关 | `PermissionViewModel` 记录上次运行的 versionCode，检测到变化即重置「不再提示」，保证新版本的引导弹窗一定出现一次 |
| 安装前校验 | `UpdateManager.verifyApk()` 校验 **包名 + versionCode ≥ 当前 + 签名证书一致**；签名不一致会提前给出明确原因，而不是等系统报「应用未安装」 |
| 自更新两条路径 | ① 远程：更新清单 JSON → 下载（断点续传）→ SHA-256 校验 → 交系统安装器；② 本地：选一个 APK → 同样校验 → 交系统安装器（无需服务器） |

APK 经 `FileProvider`（`${applicationId}.fileprovider` → `cacheDir/update/`）交给系统安装器；
需用户已开启「安装未知应用」（弹窗内提供跳转）。清单格式：

```json
{
  "versionCode": 3,
  "versionName": "0.3.0",
  "apkUrl": "https://example.com/jiandu-0.3.0.apk",
  "sha256": "可选，填了就校验",
  "notes": "本次更新内容"
}
```

**上机验证（模拟器）**

1. **覆盖安装确实换包**：安装前 `versionCode=1 / versionName=0.1.0` → `adb install -r` 安装新 APK 后
   `versionCode=2 / versionName=0.2.0`，`lastUpdateTime` 更新为新时间（`dumpsys package` 实测）。
2. **应用内版本可核对**：抽屉底部显示「版本 0.2.0（build 2）· 2026-10-13 00:19」（`update-drawer.png`）。
3. **升级重置开关生效**：升级后首次进入，权限引导弹窗重新出现一次（`update-perm-after-upgrade.png`）。
4. **更新弹窗可用**：点版本行打开「检查更新 / 关于」，显示当前版本与构建时间、清单地址输入框、
   「检查更新」「从本地选 APK」以及确认是否更新的说明（`update-dialog.png`）。

> 自更新的「远程」路径需要你自己提供一个 JSON 清单地址（本仓库不含服务器）；
> 「从本地选 APK」路径不依赖任何服务器，可立即用于手动分发。

### 3.3 需求对照

| 需求 | 实现情况 |
|------|---------|
| 1. 文本阅读器 | ✅ Compose + MD3 单 Activity 应用 |
| 2. 导入 / 阅读 / 我的 | ✅ 三者均已打通并上机验证 |
| 3. 右上角导入、从本地选文本、从文件头显示、自动分章节分段落、段落长按可选中 | ✅ 全部实现；编码自动识别（UTF-8/GB18030/UTF-16 BOM） |
| 4. 长按文本 → 选中框上方出现「阅读」→ 点击朗读 | ✅ 句级/段级选中 + 工具栏朗读，逐句连续播放、逐字高亮 |
| 5. 左上角「我的」→ 左侧弹出历史 → 点击替换当前文本 | ✅ 含 URI 失效校验、进度恢复、记录去重 |
| 6. MD3 风格 | ✅ Material 3 + 动态取色（Android 12+）+ 深色模式 |
| 7. Qwen3-TTS | ✅ 端侧集成完成并实测：模型管理（1.5 GB/断点续传）→ LiteRT 三图宿主循环 → AudioTrack 播放；模型不可用时自动回退系统语音 |

## 4. P5：打磨、测试与发布（本轮）

> 功能规格细节见 [SPEC.md](SPEC.md)，测试用例与实测数据见 [TEST.md](TEST.md)。

### 4.1 代码与边界场景加固

| 项 | 问题（修复前） | 修复 |
|----|---------------|------|
| **超大文件截断后整篇乱码**（实测 56.7 MB 文件） | 20 MB 上限按字节截断，正好切在 UTF-8 汉字中间 → 严格 UTF-8 校验失败 → 编码探测退到 GB18030 → **全篇中文变乱码、章节结构丢失**（首章显示成「第 1 节」） | ① `SafSource.trimToCompleteLine()`：截断后退到最后一个完整字符，并尽量退到换行处；② `EncodingDetector.looksLikeUtf8Truncated()`：只认「合法多字节首字节 + 正确数量续接字节但整体不足」的形状，避免把真 GBK 误判成 UTF-8 |
| **超大文件解析慢** | `TextParser.normalize()` 用 `split('\n')` + `joinToString`，20 MB 文本约 700 万行，产生 700 万个临时 String | 改为单次线性扫描（`StringBuilder` + 顺序 append） |
| **阅读进度从不落盘 + 旋转/杀进程后跳回文首** | 三个原因叠加：① `needsPersist` 拿「当前状态」与「本次新位置」比较，滚动时差值恒为 1，`≥10 段` 的落盘条件永不成立（实测滚到第 71 段时 `last_paragraph` 仍是 0）；② 新列表先停在文首并上报 `0-0`，把刚恢复的位置写回 0；③ 抑制上报的解除条件写成「等下一次位置变化」，用户停手后 `snapshotFlow` 不再发射 → 抑制永远解除不了，进度根本不保存 | ① 新增 `lastPersistedAnchor`（上次**真正落盘**的位置）与之比较；位置同时写 `SavedStateHandle` + SharedPreferences（每次滚动），Room 保持 10 段/跨章节流；② `ReaderViewModel` 增加 `suppressVisibleAnchorReports`，界面落位稳定后调 `onInitialPositionSettled()` 解除；③ 改用 `collectLatest` + `delay(400ms)`：等待期间位置再变就重新计时，稳定 400 ms 即解除抑制并上报；④ 列表尺寸变化（`layoutInfo.viewportSize`）触发 `layoutEpoch`，每次重排后按**段落锚点**重新落位（横竖屏行高不同，`scrollToItem(index)` 会被随后的重新测量顶掉） |
| **从「我的」重复打开同一文件退回文首** | `load()` 成功后调用 `recordHistory()`，而它写入的 `chapterIndex/paragraphIndex` 恒为 `0/0`，把历史行里的进度覆盖了；另外 Room 的位置是节流写入（每 10 段），恢复时会退回到旧检查点 | `recordHistory(book, anchor)` 改为保留**更靠后**的位置（本次 anchor / 历史行 / SharedPreferences 三者取 max）；`openHistory` 同样取 Room 与 SharedPreferences 中更靠后者；`rememberAnchor` 每次滚动都写（SharedPreferences 很便宜），保证「上次读到哪里」始终最新 |
| **权限引导弹窗每次冷启动都弹出** | 只要用户没点「不再提示」就每次都弹，挡住正文（恢复上次文本时尤其碍事） | 改为**首次运行 / 版本号变化后的第一次运行**自动弹出一次；之后用抽屉底部「权限与联网」手动打开 |
| **空文件导入后阅读区一片空白** | 0 字节或只有空白的文件解析出 0 段，界面无任何提示 | 新增空文件占位（`reader_empty_file_title` / `reader_empty_file_body`）；`ReaderScreen` 检测 `paragraphCount == 0` 时显示 |
| **下载中断后残留孤儿 `.part`**（第三轮新增） | 中断/被杀会留下 `.part`；下次下载若该文件已完整就会跳过，`.part` 永久残留（实测 40 MB，用户无感知） | 新增 `Qwen3ModelManager.cleanupStaleParts()`：只删「同名文件已完整（≥ minBytes）」的 `.part`，在启动 `refresh()` 与每次下载结束后各调一次；真正需要续传的 `.part` 不动 |

**上机验证（模拟器 API 37，截图见 `docs/shots/`）**

| 场景 | 证据 | 实测 |
|------|------|------|
| 导入 0 字节文件 | `p5-empty-file-state.png`（英文界面 `p5-en-empty-file.png`） | 显示「文件是空的 / The file is empty」占位，无崩溃 |
| 导入 GBK 编码文件 | `p5-gbk-reader-en.png` | 「第一章 编码测试」+ 正文正确，无乱码 |
| 导入无扩展名文件 | `p5-noext-reader.png` | 正常分两章、正文完整；长按弹出「阅读 / 整段 / 复制」（`p5-select-toolbar.png`） |
| 导入 5.37 MB 文本 | `p5-bigbook-reader.png` | 点击到首章可见 ≈ 3.2 s；进程 PSS ≈ 138 MB |
| 导入 56.71 MB 文本（截断到 20 MB） | `p5-huge-truncated.png` | 首章可见 ≈ 6.0 s；PSS ≈ 212 MB；**正文中文正常**（修复前全篇乱码） |
| **阅读位置保留矩阵** | `p5-rotate-landscape.png`、`p5-rotate-portrait-back.png`、`p5-restore-scrolled.png`、`p5-release-rotate-landscape.png` | 大文件（5.37 MB）：竖屏第 **71** 段 → 横屏 **71** → 横屏内滚动 **77** → 回竖屏 **77** → 切后台回前台 **77** → `force-stop` 冷启动 **76** → 从「我的」打开 **76**；小文件（`halfscreen.txt`，24 段）：**15 → 15 → 15 → 冷启动 14**；R8 release 包：**26 → 26 → 26**。全程无崩溃，误差 ≤ 1 段 |
| 换书后回到文首 | `p5-noext-reader.png` | 阅读大文件到第 76 段后导入另一本书 → 新书从「第一章 无名文件」第一段开始 |
| 进程被杀/切后台后恢复 | `p5-restore-after-kill.png`、`p5-restore-scrolled.png` | `am force-stop` 后冷启动回到上次文本与段落；权限弹窗不再重复弹出 |
| 英文界面 | `p5-en-permission-dialog.png` | 权限弹窗等全部文案切换为英文（系统语言为英文时） |
| Release（R8）安装运行 | `p5-release-launch.png`、`p5-release-drawer.png` | 压缩后的包可安装、可启动，语音引擎卡片与版本行正常，无 `ClassNotFound` |
| 16 KB 页对齐 | `zipalign -c -P 16` | release APK `Verification successful` |

### 4.2 国际化（i18n）与无障碍

| 项 | 说明 |
|----|------|
| 文案全部资源化 | 新增 `i18n/UiStrings.kt`（`UiStrings` / `AndroidStrings` / `StaticStrings`）与 `i18n/UiText.kt`（资源 ID + 参数）。**ViewModel 状态里只放 `UiText`**，Compose 层用 `ui/text/UiTextResolve.kt` 的 `resolve()` 渲染；`data` 层（TTS 引擎、模型管理、自更新）通过注入 `UiStrings` 取文案 |
| 清理硬编码中文 | 业务代码里原有的 60 余处中文字面量（提示、错误、引擎名、图组名等）全部迁入 `res/values/strings.xml` |
| 英文资源 | 新增 `res/values-en/strings.xml`，与中文资源**逐条对应**（含权限弹窗、空态、更新弹窗、TTS 失败提示） |
| TalkBack 语义 | 章节标题 `semantics { heading() }`（可快速跳章）；段落 `contentDescription = 「第 N 段。长按可选中文字并朗读」`；朗读控制条所有按钮有 `contentDescription`；语速选择器补「语速 x1.0」描述 |
| 剪贴板 API | 从已弃用的 `LocalClipboardManager` 迁到 `LocalClipboard` + `ClipEntry`（编译告警清零） |

### 4.3 R8 / Release 构建

| 项 | 说明 |
|----|------|
| 版本号 | `versionCode 2 → 3`、`versionName 0.2.0 → 0.3.0` |
| 压缩开关 | release 打开 `isMinifyEnabled = true` + `isShrinkResources = true`；APK 42.1 MB(debug) → 23.9 MB(release) |
| 混淆规则 | `app/proguard-rules.pro` 重写：整体保留 `com.google.ai.edge.litert.**`（JNI 按类名/方法名查找）、`org.tensorflow.lite.**`、`com.hrstts.qwen3tts.**`、native 方法持有类、Room 的 `@Entity/@Dao/**._Impl`、`ViewModel` 构造；保留 `SourceFile,LineNumberTable` 便于定位崩溃 |
| 已知工具链缺陷 | AGP 8.7.3 自带的 lint（UAST）解析不了 Kotlin 2.2 编译的字节码，`lintVitalAnalyzeRelease` **必然崩溃**（`Found class ...KaCallableMemberCall, but interface was expected`）。已用 `lint { checkReleaseBuilds = false }` + `tasks.matching { name.startsWith("lintVital") }.configureEach { enabled = false }` 在 `:app` 与 `:qwen3tts` 两处关闭；日常检查用 `gradlew :app:lintDebug`。**升级 AGP 到支持 Kotlin 2.2 的版本后应恢复** |
| 签名 | 按用户要求**暂不做发布签名**。`assembleRelease` 产出未签名 APK（系统拒绝安装）；仓库提供 `tools/sign-release.ps1` 用调试密钥签一份可安装的自测包，正式分发前需替换为正式 keystore（见 DEV.md §7） |

### 4.4 应用内正式下载端侧模型（第三轮验证）

P3 阶段只验证过「应用内下载能补回缺的文件」与「脚本推送」，**没有做过一次完整的全新下载**。
本轮在全新安装（无模型）状态下完整跑通，并据此修掉一个残留文件缺陷。

| 项 | 结论 |
|----|------|
| 镜像探测 | 抽屉「Test network」→ `hf-mirror.com → Reachable (HTTP 200, 244 MB)`；`huggingface.co → Timed out…`（本机网络到 HuggingFace 直连不通，回退链按预期工作） |
| 完整下载 | **11 个文件 / 1,592.4 MB**；第一次全量 **≈185 s**（平均 **≈8.6 MB/s**，峰值 **≈26.6 MB/s**），第二次全量 ≈135 s；与 P3-STATUS 记录的 1,592.4 MB 完全一致 |
| 断点续传 | 造 3.07 MB 的 `.part` 后触发下载：`.part` 从 **3.07 → 4.70 → 13.16 → 22.01 MB** 继续增长，最后重命名为 25,179,078 字节的完整文件（若服务器忽略 `Range` 返回 200，代码会删掉 `.part` 从 0 重下，实测未发生） |
| 完整性判定 | 下载结束 `.part = 0`；`isInstalled()`（存在 + ≥ minBytes）通过，状态转 `Ready` |
| 引擎热切换 | 缺文件时启动 → `System voice`；补齐后**无需重启**自动变为 `Current engine: Qwen3-TTS (on-device)`，日志 `端侧模型已加载：talker_int4 + mtp_folded_int8 + codec_partA + codec_partB（采样率 24000）`（冷启动路径同样加载成功） |
| 真实合成 | 导入短句 → 长按 → 阅读：`libLiteRt.so` 加载 + XNNPACK 413/413 节点替换；`流式合成完成：40 帧（3.20s 音频），首音延迟=12513ms，总耗时=24009ms，RTF=7.50`；`AudioTrack delivered 76800 frames`（=3.2 s×24000），无异常栈 |
| 缺文件补齐 | 删掉 2 个文件（`vocab.json`、`text_projection_fp32.npz`）→ 重启后正确回到「未安装 + 系统语音」→ 点下载只补缺失的，**99 s** 完成，无 `.part` 残留 |
| 删除与空间 | 「Delete on-device model」→ 目录清空、引擎回落系统语音；`/data` 已用 **2.5 GB → 980 MB**（释放 ≈1.6 GB） |
| 稳定性 | 下载期间进程 PSS ≈ 112 MB；`logcat` 无 `FATAL` / `ANR in com.hrstts.jiandu` / `OutOfMemory` |
| **本轮修复** | **孤儿 `.part` 未被清理**：下载中断会留下 `.part`，若对应文件已完整（用户手工补过或用脚本推过）就会被跳过而永久残留（实测留下 40 MB）。新增 `Qwen3ModelManager.cleanupStaleParts()`，只删「同名文件已完整」的 `.part`，在启动 `refresh()` 与每次下载结束后各调一次 |

> 一次性验证开销提示：模型 **1.59 GB**，模拟器 `/data` 默认 6 GB 分区；全新下载前后请用
> `adb shell df -h /data` 确认可用空间 ≥ 2 GB（应用自身要求 ≥ 1,750 MB）。
> 复跑命令见 [TEST.md](TEST.md) §5.7，用例编号 R-14 ~ R-18。

### 4.5 文档

| 文件 | 内容 |
|------|------|
| [SPEC.md](SPEC.md) | 功能规格最终版：编码探测 8 级策略、章节/段落规则与可调参数、朗读状态机、i18n 契约、边界行为表、已知限制 |
| [TEST.md](TEST.md) | 测试用例清单：32 个单测逐条列出、上机回归表 R-01 ~ R-18（含实测数字与截图）、7 条缺陷回归表、性能/内存/模型下载测法、构建验收命令 |
| [DEV.md](DEV.md) | 新增 §7 发布构建与签名、§8 模拟器回归方法（含应用内模型下载） |
| 本文档 | P5 加固项与验证证据 |

## 5. 已知问题与技术债

### 5.1 本轮修复的缺陷（模型下载）

| 缺陷 | 根因 | 修复 |
|------|------|------|
| 应用内「下载模型」总是失败 | `AndroidManifest.xml` **没有 `INTERNET` 权限**：应用最初就是全离线的（SAF 导入 + 系统 TTS），加端侧模型下载功能时漏了联网权限，系统直接拒绝 socket | 补 `<uses-permission android:name="android.permission.INTERNET" />`；已用 `aapt2 dump permissions` 确认 APK 内含该权限；不装模型时应用仍可完全离线运行 |
| 首次下载被误判「存储空间不足（可用 0 MB）」 | `modelDir.usableSpace` 对**不存在的路径返回 0**，而模型目录首次下载前并不存在 → `freeSpaceBytes()` 返回 0，直接拦住下载 | 目录不存在时回退到必定存在的 `filesDir`（同一分区）取可用空间 |
| 低内存设备上「下载模型」按钮是灰的，看起来像不能下载 | 主按钮被 `memorySufficient` 禁用（< 6 GB 内存），而次要按钮却可点，语义混乱 | 主按钮改为始终可点，低内存只作为风险提示显示 |
| 失败时只显示底层异常文本（如 `Permission denied`），用户不知道怎么办 | 未做异常翻译 | `Qwen3ModelManager.friendlyError()` 把 `UnknownHost` / `SocketTimeout` / `SecurityException` / `SSLException` / HTTP 4xx 翻译成可行动的中文提示 |
| 单一镜像不可用即整体失败 | 只写死了一个 `hf-mirror.com` | 改为镜像列表顺序重试：`hf-mirror.com` → `huggingface.co` |
| 模型下载完成后必须重启应用才会使用端侧引擎 | 引擎只在 `ReaderViewModel.init` 时选定 | 新增 `ReaderViewModel.onModelAvailable()`，界面观察到模型 `Ready` 即热切换引擎并提示 |

**上机验证（模拟器）**

1. 删单个文件（`vocab.json`）→ 卡片显示「未安装 / 当前朗读引擎：系统语音」→ 点「下载模型」→ 文件按 2 776 833 字节补回 → 状态「已安装」→ 引擎**即时**切到 Qwen3-TTS（端侧）。
2. **全新设备路径**：`rm -rf files/qwen3-tts` 清空后重进应用 → 「下载模型」→ 25 s 拉了 228 MB（`talker_int4.tflite.part`）→ 约 3.5 分钟完成 11 个文件（1.59 GB，无 `.part` 残留）→ 卡片显示「已安装：快速图组」且引擎自动切到 Qwen3-TTS（端侧），全程无需重启。

### 5.2 待处理问题

| # | 问题 | 影响 | 处理 |
|---|------|------|------|
| 1 | 模拟器 Google TTS 缺中文语音数据，合成报错 | 仅测试环境 | 已实现「中文失败 → 自动切默认语言重试 → 仍失败给出中文语音包引导」；真机装有中文语音包时正常 |
| 2 | 用英文音色朗读中文时，引擎上报的字符范围会漂移 | 仅影响测试环境高亮精度 | 真机中文音色下 1:1；已对越界范围做钳制 |
| 3 | 同一文件经不同 SAF URI 导入会产生重复历史 | 列表重复 | 已按「文件名 + 字数」合并记录（历史遗留脏数据不会自动清理） |
| 4 | ~~大文件（>2MB）流式解析尚未实测~~ | — | **P5 已闭环**：5.37 MB 与 56.71 MB（截断到 20 MB）样本实测首章可见 ≈ 3.2 s / 6.0 s，PSS 138 MB / 212 MB，无 ANR（见 §4.1） |
| 5 | 选中粒度是「句 / 整段」，非任意区间 | 与部分阅读 App 习惯不同 | 计划 V1.1 用原生 `SelectionContainer` 支持任意区间 + 手柄 |
| 6 | ~~P5 未开始~~ | — | **P5 工程项已完成**：加固、i18n/无障碍、R8 与 release 构建、文档（见 §4）；**发布签名按用户要求暂缓** |
| 7 | Qwen3-TTS 端侧合成比播放慢（RTF 实测 4~5；官方 Pixel 8a 亦为 2.06） | 播放期间仍有 underrun 停顿；「首音 1 秒内」不可达 | 已做**流式分块播放**（首音 41s → 6.8s）；进一步优化见 P3-STATUS.md §6（并行流水线） |
| 8 | 端侧模型常驻内存约 1.2~1.6 GB | 4 GB 设备会被系统回收 | 已按「内存 < 6 GB 默认推荐系统语音」处理；4 GB 模拟器实测被 low-memory kill |
| 9 | 端侧朗读的高亮粒度是「句」 | 无字级跟随 | 系统 TTS 路径仍有字级 `onRangeStart`；端侧字级需帧→token 时间轴（见 P3-STATUS.md §6） |
| 10 | ~~横竖屏切换后回到文首~~ | — | **已修复**（本轮第二轮）：根因是节流比较对象错误 + 抑制上报永不解除 + 重排后未按段落锚点重落位，三者叠加。修复与验证见 §4.1「阅读位置保留矩阵」（竖 71 → 横 71 → 横内滚动 77 → 竖 77 → 冷启动 76） |
| 11 | 软分章的章节标题硬编码中文「第 N 节」 | 英文界面下软分章标题仍是中文 | 解析器为纯 Kotlin（不依赖 Android 资源），需要把 `titlePrefix` 作为参数从 `ReaderViewModel` 传入；当前按已知限制记录 |
| 12 | ~~小文件（不足一屏）无法证明位置保留~~ | — | **已补测**：新增 `docs/samples/halfscreen.txt`（第一章 + 24 段，约 3 KB，竖屏约 2 屏）→ 竖 15 → 横 15 → 竖 15 → 冷启动 14，全部保留 |

## 6. 环境要点（踩坑记录）

- 本机只有 **JDK 25**，Gradle 8.9 不支持 → 项目内自带 `OpenJDK 21` 于 `.toolchain/jdk-21`，由 `gradle.properties` 的 `org.gradle.java.home` 指定。
- **github.com 不可达**：JDK 走华为云镜像、Gradle 走 services.gradle.org、依赖走阿里云 Maven 镜像、模型走 hf-mirror.com 均可用。
- 模拟器必须加 `-gpu off -feature -Vulkan`（swiftshader Vulkan 初始化会崩溃）；**不要加 `-no-audio`**，否则系统 TTS 合成必失败。
- AVD 为手工创建（无 cmdline-tools），配置见 `C:\Users\H\.android\avd\jiandu_api37.avd\config.ini`。
- 端侧模型需要 **≥ 8 GB 内存**：4 GB 模拟器实测出现整机 low-memory kill 风暴，应用被回收；
  启动模拟器时用 `-memory 8192`。
- 模型可先用 `scripts/push-qwen3-model.ps1` 从本机推送到设备（避免在设备上重复下载 1.5 GB）。
