# 简读 · 电脑 TTS 服务

把本机变成手机的语音合成后端。手机只发文本、收音频，
**不再需要 1.6 GB 端侧模型，也不吃手机内存**。

设计说明与实测数据见 [`../docs/REMOTE-TTS.md`](../docs/REMOTE-TTS.md)。

---

## 依赖

| 项 | 要求 | 说明 |
|----|------|------|
| Python | 3.9+ | 已在 3.13 上实测 |
| pywin32 | 已装（`pip install pywin32`） | 仅 `sapi` 后端需要；走 Windows COM |
| 其它 | **无** | HTTP 层是纯标准库（`http.server`），不需要 FastAPI/venv |

---

## 三步跑起来

```powershell
cd F:\Projects\hrstts\server

# 1) 放行防火墙（只需一次，脚本会弹 UAC）
powershell -ExecutionPolicy Bypass -File .\open-firewall.ps1

# 2) 起服务
python run.py
```

3. 手机连同一个 WiFi，浏览器打开屏幕上打印的地址（如 `http://10.62.61.202:8080`），
   输入文本点「朗读」——能出声就说明链路全通。

也可以直接双击 `start.bat`。

---

## 常用参数

```powershell
python run.py --port 9000              # 换端口
python run.py --engine sapi            # 指定后端（目前只有 sapi）
python run.py --cache-limit-mb 4096    # 服务端音频缓存上限（默认 2048 MB）
python run.py --cache-dir D:\tts-cache # 缓存目录
```

---

## 自检

```powershell
python test_smoke.py
```

覆盖：协议 key 算法（含给 Kotlin 单测用的跨语言固定向量）、片段文件格式、
WAV 包装、服务端缓存、SAPI 真实合成（含 RTF 打印）。

---

## 接口速查

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/` | 手机浏览器自测页 |
| GET | `/v1/health` | 引擎、音色、`modelTag`（客户端据此算缓存 key） |
| POST | `/v1/tts` | 合成一句 → 24 kHz int16 裸 PCM |
| POST | `/v1/tts/wav` | 同上，包成 WAV（浏览器用） |
| GET | `/v1/clips/{key}` | 取缓存片段（`jdpc` 格式，手机可直接落盘） |
| POST | `/v1/sync` | 登记整本预合成（后台跑） |
| GET | `/v1/sync/{book}` | 预合成进度 |
| GET | `/v1/stats` | 请求数 / 合成耗时 / 缓存命中 |

完整契约见 [`../docs/REMOTE-TTS.md`](../docs/REMOTE-TTS.md) §4。

---

## 目录

```
server/
├─ run.py                     启动器（打印局域网地址、防火墙提示、音色列表）
├─ start.bat                  Windows 一键启动
├─ open-firewall.ps1          放行入站端口（自动提权）
├─ test_smoke.py              服务端自检 35 项
├─ cache/                     音频缓存（自动创建，已 gitignore）
└─ jiandu_gateway/
   ├─ protocol.py             协议唯一真源：key、片段格式、采样率、modelTag
   ├─ cache.py                磁盘缓存（LRU）
   ├─ server.py               HTTP 路由、自测页、预合成任务
   └─ engines/
      ├─ base.py              引擎抽象（返回 24 kHz int16 裸 PCM）
      └─ sapi.py              Windows SAPI 后端
```

---

## 加自己的引擎

```python
# jiandu_gateway/engines/my_engine.py
from .base import TtsEngine, Voice

class MyEngine(TtsEngine):
    name = "my-engine"

    def voices(self):
        return [Voice(id="my:voice1", label="音色 1", engine=self.name, locale="zh-CN")]

    def synthesize(self, text: str, voice_id: str) -> bytes:
        # 必须返回 24000 Hz / 单声道 / int16 小端 裸 PCM
        return b""
```

在 `engines/__init__.py` 的 `_REGISTRY` 里登记 `{"my-engine": MyEngine}`，
然后 `python run.py --engine my-engine`。**手机端不用改。**

> 换引擎后如果听起来还是旧音色，是缓存 key 里的 `modelTag` 没变：
> 把 `protocol.py` 的 `MODEL_TAG` 加一，重启服务即可（手机端会自动跟随）。

---

## 常见问题

| 现象 | 处理 |
|------|------|
| 手机打不开页面 | 防火墙没放行（跑 `open-firewall.ps1`）；或不在同一网络 |
| `端口绑定失败` | 端口被占用，换 `--port` |
| 音色很机械 | 用的是 Windows SAPI，见 `../docs/REMOTE-TTS.md` §8 |
| 电脑睡眠后断连 | 电源设置里改为「接通电源时从不睡眠」 |
