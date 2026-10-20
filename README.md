# 简读 / JianDu

把手机里任意一份纯文本，变成可以自动分章、长按即听的有声读物。

> 当前版本：**0.4.0**（`versionCode 4`）· Android `minSdk 26` / `targetSdk 35`
> 支持中文 + 英文界面，Material 3，单 Activity + Compose + MVVM。

---

## 它解决什么问题

中文网文通常是几 MB~几十 MB 的 `.txt`，第三方阅读器要么收费要么插广告；
而「听书」这件事，端侧跑一个 1.6 GB 的扩散式 TTS 在手机上并不现实
（实测 RTF 4~5，首音要 6.8 秒，常驻内存 1.2~1.6 GB）。

所以做了两条朗读路径：

| 引擎 | 位置 | 首音 | 手机占用 |
|------|------|------|---------|
| **电脑合成**（推荐） | 局域网里的电脑 | **385 ms** | **0** |
| Qwen3-TTS 端侧 | 手机上（LiteRT） | 6834 ms | 1.59 GB 存储 / 1.2~1.6 GB 内存 |
| 系统语音（兜底） | 手机上 | 极快 | 0 |

电脑合成的前提是你在同一局域网有一台电脑跑本仓库的 `server/`——
手机只发文本、收 24 kHz PCM，不再需要下载模型。

---

## 功能

- **导入**：SAF 选文件，不需要任何存储权限；编码自动识别（UTF-8 / GB18030 / UTF-16 BOM）
- **解析**：自动识别「第 N 章 / Chapter N / 楔子 / 番外」，按空行或逐行分段，超长段落按句末标点二次切分
- **阅读**：长按段落选中整句 → 上方出现「阅读 / 整段 / 复制」→ 逐句连续朗读 + 高亮跟随
- **我的**：历史记录、点击替换当前文本、阅读进度恢复（旋转 / 切后台 / 杀进程都不丢）
- **电脑合成**：设置服务器地址 → 在线流式合成；也可把整本预下载到手机，之后断网/关机照听
- **无障碍**：章节标题 `heading()` 语义、段落朗读提示、控制条按钮全部带描述
- **自更新**：应用内检查更新 / 从本地选 APK 安装（校验包名、版本、签名）

---

## 目录结构

```
app/            Android 应用（Compose + MVVM + Room）
qwen3tts/       端侧 Qwen3-TTS 推理（LiteRT Compiled Model API）
server/         电脑端 TTS 网关（纯标准库，零依赖）
docs/           设计 / 规格 / 测试 / 进度文档
tools/          构建与部署脚本
```

## 电脑端服务（三步）

```powershell
cd server

# 1) 放行防火墙（一次即可，脚本会弹 UAC）
powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1

# 2) 起服务
python run.py       # 或双击 start.bat

# 3) 手机连同一 WiFi，浏览器打开屏幕上打印的地址验证
```

详见 [server/README.md](server/README.md)。当前后端是 Windows SAPI（零安装、离线），
换 GPT-SoVITS / edge-tts 只需在 `server/jiandu_gateway/engines/` 加一个类，手机端不用改。

## 构建

```powershell
# 项目内自带 JDK 21（.toolchain/jdk-21），由 gradle.properties 指定
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest          # 40 项单测
.\gradlew.bat :app:assembleRelease            # R8 压缩
.\tools\sign-release.ps1                      # 用调试密钥签一份可安装的 release
```

## 文档

| 文档 | 内容 |
|------|------|
| [docs/SPEC.md](docs/SPEC.md) | 功能规格最终版（边界行为、状态机、i18n 契约） |
| [docs/REMOTE-TTS.md](docs/REMOTE-TTS.md) | 电脑合成：架构、HTTP 契约、实测数据、排错 |
| [docs/P3-STATUS.md](docs/P3-STATUS.md) | 端侧 Qwen3-TTS 集成与性能专项 |
| [docs/TEST.md](docs/TEST.md) | 测试用例与实测结果 |
| [docs/PROGRESS.md](docs/PROGRESS.md) | 阶段进度与验证证据 |
| [docs/DEV.md](docs/DEV.md) | 环境、构建、签名、模拟器回归 |

## 状态

所有功能在 Android 模拟器（API 37）上逐项截图验证；
**未做正式发布签名**——仓库里的 release APK 使用 Android 调试密钥签名，
可自测安装，不适合分发。正式发布前需配置自己的 keystore（见 DEV.md §7）。

## 许可

本仓库为个人项目，尚未附加开源许可证。如需使用请先联系作者。
