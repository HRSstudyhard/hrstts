# 开发环境与构建说明（简读 / JianDu）

## 1. 本机环境实况（已探测）

| 项目 | 实际值 | 说明 |
|------|--------|------|
| Android SDK | `C:\Users\H\AppData\Local\Android\Sdk` | 已安装 `platforms/android-35`、`android-36.1`；`build-tools/34.0.0`、`36.1.0`、`37.0.0` |
| SDK Licenses | `licenses/android-sdk-license` 存在 | 已接受，AGP 可自动补齐组件 |
| 系统 JDK | `F:\jdk&develop\develop`（**JDK 25**） | 过新：Gradle 8.9 最高支持 Java 22，**不能**用于本项目构建 |
| 项目 JDK | `.toolchain/jdk-21`（OpenJDK 21.0.2，华为云镜像下载） | 由 `gradle.properties` 的 `org.gradle.java.home` 指定 |
| Gradle | 8.9（本机已缓存于 `~/.gradle/wrapper/dists`） | 通过 `gradlew.bat` 使用，无需重新下载 |
| 网络 | `dl.google.com` / `repo1.maven.org` / `services.gradle.org` 可达；**`github.com` 不可达** | 依赖走 Google Maven + Maven Central；模型/工具下载需避开 GitHub |

> ⚠️ GitHub 在本机网络不可达，因此 P3 阶段获取 Qwen3-TTS 模型时必须使用国内镜像
> （如 `hf-mirror.com`），相关下载地址在 P3 实施时固化到 `ModelManager`。

## 2. 工具链准备（一次性）

```powershell
# 1) 下载 OpenJDK 21（GitHub 不可达，改用华为云镜像）
New-Item -ItemType Directory -Force -Path .toolchain | Out-Null
curl.exe -L -o .toolchain\jdk21.zip `
  "https://mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_windows-x64_bin.zip"

# 2) 解压并改名为 .toolchain\jdk-21（gradle.properties 中引用的路径）
Expand-Archive .toolchain\jdk21.zip -DestinationPath .toolchain
Move-Item .toolchain\jdk-21.0.2 .toolchain\jdk-21
```

## 3. 构建与安装

```powershell
# 构建 Debug APK
.\gradlew.bat :app:assembleDebug

# 产物位置
app\build\outputs\apk\debug\app-debug.apk

# 安装到已连接设备/模拟器
& "C:\Users\H\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk

# 单元测试（解析器等）
.\gradlew.bat :app:testDebugUnitTest
```

## 4. 关键配置速查

- `local.properties` → `sdk.dir=C\:\\Users\\H\\AppData\\Local\\Android\\Sdk`
- `gradle.properties` → `org.gradle.java.home=F:/Projects/hrstts/.toolchain/jdk-21`
- `app/build.gradle.kts` → `compileSdk = 35`、`buildToolsVersion = "36.1.0"`（AGP 8.7 要求 ≥ 35.0.0，本机 35.x 未安装，故显式指定 36.1.0）、`minSdk = 26`、`targetSdk = 35`
- 版本目录：`gradle/libs.versions.toml`（AGP 8.7.3 / **Kotlin 2.2.10** / **KSP 2.2.10-2.0.2** / Compose BOM 2024.10.01 / **Room 2.7.2** / **LiteRT 2.1.5**）
  - 版本升级原因：LiteRT 2.1.5 的 Kotlin 元数据要求 Kotlin ≥ 2.2；Room 2.6.1 的 KSP 处理器在遇到
    LiteRT 相关字节码时会抛 `unexpected jvm signature V`（Room 2.7.2 修复）。
- 模块划分：`:app`（UI/业务，启用 KSP+Room）与 `:qwen3tts`（LiteRT 端侧推理，**不启用 KSP**）。
  `app` 以 `implementation(project(":qwen3tts"))` 引入，LiteRT 只进入运行期 classpath，
  避免 KSP 处理 LiteRT 字节码崩溃。

## 5. 端侧语音模型（Qwen3-TTS）

两条获取路径，**推荐第一条**（不依赖本机网络与脚本）：

### 5.1 应用内下载（已实测通过，见 PROGRESS.md §4.4）

```powershell
# 1) 抽屉「我的」→ 底部「语音引擎」→ 先点「Test network」确认镜像可达
#    期望：hf-mirror.com → Reachable (HTTP 200, 244 MB)
# 2) 点「Download model」→ 进度条按文件推进（11 个文件，合计约 1.6 GB）
# 3) 完成后卡片自动变「端侧模型已安装：快速图组」，朗读引擎热切换到 Qwen3-TTS（无需重启）
# 4) 想释放空间：点「Delete on-device model」

# 命令行侧观察（debug 包可 run-as）
$dir = "/data/data/com.hrstts.jiandu/files/qwen3-tts"
adb shell run-as com.hrstts.jiandu ls -l $dir
adb shell df -h /data          # 需要可用空间 ≥ 2 GB
```

- 下载中断/进程被杀后再次点击会**从 `.part` 断点续传**（HTTP `Range`）。
- 状态判定是「文件存在且 ≥ 最小体积」；因此**不要手工往模型目录丢半截文件**，
  否则会被当成已装好（引擎加载阶段才会报缺文件）。
- 若对应文件已完整而磁盘上还留着 `.part`，应用启动或下次下载结束时会自动清理
  （`Qwen3ModelManager.cleanupStaleParts()`）。

### 5.2 脚本推送（无网络/想省流量时）

```powershell
# 1) 本机下载（约 1.6 GB，走 hf-mirror 镜像；HuggingFace 直连在本机超时）
#    文件清单与地址见 docs/P3-STATUS.md §3
# 2) 推送到设备应用私有目录（避免在设备上重复下载）
& .\scripts\push-qwen3-model.ps1
# 3) 应用内「我的」底部 → 语音引擎卡片，会显示「已安装：快速图组」并自动改用端侧引擎
```

- 模拟器需 **≥ 8 GB 内存**（`emulator ... -memory 8192`），否则模型常驻内存会触发系统 low-memory kill。
- 完整下载实测基线（应用内、模拟器、hf-mirror）：**11 个文件 / 1,592.4 MB / ≈185 s / 平均 ≈8.6 MB/s**。

## 6. 权限与合规

清单中的权限（`app/src/main/AndroidManifest.xml`）：

| 权限 | 用途 | 能否弹窗申请 |
|------|------|-------------|
| `INTERNET` | 仅用于可选的 Qwen3-TTS 端侧模型下载（约 1.5 GB） | 安装时自动授予，无需/无法弹窗 |
| `ACCESS_NETWORK_STATE` | 检测当前是否联网（权限引导弹窗的「网络」项） | 同上 |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) / `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | Android 12 及以下「直接读写文本文件」用的运行时权限 | ✅ 可弹窗 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 的「所有文件访问」，对应存储项 | ❌ 只能跳系统设置页 |
| `REQUEST_INSTALL_PACKAGES` | 应用内安装自身新版本 APK | ❌ 只能跳「安装未知应用」设置页 |

- **存储**：文本导入走 SAF（`ACTION_OPEN_DOCUMENT`）+ 持久化 URI 授权，模型写在应用私有目录
  （`filesDir/qwen3-tts`），因此**不授权任何存储权限也能完整使用**；存储权限是可选增强。
- **网络**：不下载模型时不发起任何网络请求（导入、朗读、历史全为本地能力）。
  ⚠️ 该权限是后补的：最初版本全离线，加模型下载后漏声明，导致应用内下载必然失败（`Permission denied`）。
- ⚠️ `MANAGE_EXTERNAL_STORAGE` 与 `REQUEST_INSTALL_PACKAGES` 是敏感权限，上架 Google Play 需说明用途；
  若只做自用分发可保留，若要上架建议移除或改为按需启用。
- 启动时弹出「权限与联网」引导（`ui/permission/PermissionDialog.kt`，逻辑在
  `data/permission/AppPermissionCenter.kt`）：能在应用内弹窗的直接申请，特殊权限跳设置页并在
  `ON_RESUME` 后刷新；「不再提示」记在 `jiandu_permissions` SharedPreferences。
- **P5 调整**：引导弹窗只在**首次运行**或**版本号变化后的第一次运行**自动弹出一次，
  之后从抽屉底部的「权限与联网」手动打开（不再每次冷启动都挡住正文）。

## 7. 发布构建（Release）与签名

```powershell
# 0) 每个 Gradle 命令都要先指到项目内 JDK（本机 JAVA_HOME 指向 JDK 25，Gradle 8.9 不支持）
$env:JAVA_HOME = "F:\Projects\hrstts\.toolchain\jdk-21"

# 1) R8 压缩 + 资源压缩
.\gradlew.bat :app:assembleRelease
#   → app\build\outputs\apk\release\app-release-unsigned.apk（约 23.9 MB，未签名）

# 2) 该未签名包系统会拒绝安装（INSTALL_PARSE_FAILED_NO_CERTIFICATES）；
#    自测时用调试密钥签一份（脚本会自动挑最新的 build-tools 并校验签名）
.\tools\sign-release.ps1
#   → app\build\outputs\apk\release\app-release-debug-signed.apk（可 adb install）

# 3) 传统校验（16 KB 页对齐 + 权限清单）
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\zipalign.exe" -c -P 16 -v 4 `
    app\build\outputs\apk\release\app-release-unsigned.apk
```

**正式发布前必须做的两件事**（当前按用户要求暂缓）：

1. **换成正式 keystore**：在 `app/build.gradle.kts` 增加 `signingConfigs.release`
   （`keystore.properties` 放仓库外并加入 `.gitignore`），并把 `release.signingConfig` 指过去；
   同一应用后续版本必须用同一把钥匙，否则用户升级会被系统拒绝（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
2. **恢复 lintVital**：AGP 8.7.3 自带的 lint 解析不了 Kotlin 2.2 的字节码，
   `lintVitalAnalyzeRelease` 会崩（`Found class …KaCallableMemberCall, but interface was expected`），
   因此 `:app` 与 `:qwen3tts` 都关掉了它（`lint { checkReleaseBuilds = false }` +
   `tasks.matching { name.startsWith("lintVital") }.configureEach { enabled = false }`）。
   升级 AGP 到支持 Kotlin 2.2 的版本后应删掉这两处开关。
   日常静态检查仍可用 `.\gradlew.bat :app:lintDebug`。

R8 规则见 `app/proguard-rules.pro`，关键 keep：`com.google.ai.edge.litert.**`（JNI 按类名/方法名
查找）、`org.tensorflow.lite.**`、`com.hrstts.qwen3tts.**`、native 方法持有类、Room 的
`@Entity`/`@Dao`/`**_Impl`、`androidx.lifecycle.ViewModel` 的构造函数。

## 8. 模拟器上机回归（P5 方法）

```powershell
# 启动模拟器（必须 8 GB；不要加 -no-audio，否则系统 TTS 合成必失败）
& "C:\Users\H\AppData\Local\Android\Sdk\emulator\emulator.exe" -avd jiandu_api37 `
    -no-window -gpu off -feature -Vulkan -memory 8192 -no-snapshot-load -no-boot-anim

# 仓库内辅助脚本：坐标一律来自 uiautomator dump 的真实 bounds，避免手写坐标猜错
.\tools\ui.ps1 dump              # 列出可点击/带文本节点
.\tools\ui.ps1 tapText "bigbook.txt"
.\tools\ui.ps1 shot p5-xxx.png   # 截图存到 docs\shots\
.\tools\ui.ps1 back

# 内存与崩溃观测
adb shell dumpsys meminfo com.hrstts.jiandu | Select-String "TOTAL PSS"
adb logcat -d -v brief | Select-String "FATAL|ANR in|ClassNotFound"

# 边界样本（docs\samples\ 已收录，可直接 push 到 /sdcard/Download/）
#   empty.txt（0 字节）、blank.txt（只有空白）、noext（无扩展名）、
#   titleonly.txt（只有标题）、gbk-sample.txt（GB18030）
```

> 大文件样本请自行生成（不要把几十 MB 的文本放进仓库）：
> 5 MB 级用于滚动/渲染，>20 MB 级用于验证截断与编码兜底。

## 9. 已知工具链问题

| 问题 | 现象 | 处理 |
|------|------|------|
| 系统 JAVA_HOME 指向 JDK 25 | `gradlew.bat` 直接报 `JAVA_HOME is set to an invalid directory: F:\jdk`（路径里的 `&` 也会截断命令） | 每次先 `$env:JAVA_HOME = "F:\Projects\hrstts\.toolchain\jdk-21"` |
| AGP 8.7.3 的 lint 不认 Kotlin 2.2 字节码 | `:app:lintVitalAnalyzeRelease` 崩溃，`assembleRelease` 失败 | 已在两个模块关闭 lintVital（见 §7），升级 AGP 后恢复 |
| R8 报 `An error occurred when parsing kotlin metadata` | 大量 WARNING（Kotlin 2.2.10 比 AGP 自带 R8 新） | 仅告警，不影响压缩结果；升级 AGP 后消失 |
