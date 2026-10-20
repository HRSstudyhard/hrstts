"""Windows SAPI 后端：零安装、离线、已经装好的中文音色直接用。

为什么把它做成第一个后端：
  * 依赖只有 pywin32（本机已装），不联网、不下载模型、不需要 GPU；
  * 实测 RTF 0.035~0.18（2.3 秒的句子约 80 ms 合完），整本 3 小时有声书约 6~20 分钟；
  * 引擎可替换：等 GPT-SoVITS / edge-tts 装好后，改 `--engine` 即可，
    手机端的 HTTP 契约与缓存机制完全不变。

已知取舍：音色是「老式合成音」（Microsoft Huihui），可懂但不自然。
它存在的价值是**先把链路和工程问题全部打通**，音质是后面换引擎的事。
"""

from __future__ import annotations

import re
import threading
from concurrent.futures import ThreadPoolExecutor

from .base import TtsEngine, Voice

#: SAPI 的 SpeechAudioFormatType 枚举：SAFT24kHz16BitMono
#: 选 24 kHz 是为了和端侧 Qwen3-TTS 完全一致，手机侧 AudioTrack / 缓存格式都不用改。
SAFT_24KHZ_16BIT_MONO = 24

_SLUG_RE = re.compile(r"[^a-z0-9]+")

#: SAPI 会把某些符号念出名字（括号、星号、井号…），朗读用文本先清一遍。
_STRIP_RE = re.compile(r"[#*_`~^<>\[\]{}|\\]")
_SPACE_RE = re.compile(r"[ \t\u3000]+")


def _slug(text: str) -> str:
    return _SLUG_RE.sub("-", text.strip().lower()).strip("-")


def clean_for_speech(text: str) -> str:
    """把正文变成适合朗读的文本（不影响缓存 key：key 用的是调用方传入的原文）。"""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _STRIP_RE.sub(" ", text)
    text = _SPACE_RE.sub(" ", text)
    return text.strip()


class SapiEngine(TtsEngine):
    name = "sapi"

    def __init__(self) -> None:
        if not _com_available():
            raise RuntimeError(
                "SAPI 后端需要 pywin32 且只能在 Windows 上运行"
                "（pip install pywin32）",
            )
        # COM 对象不是线程安全的，而且 SAPI 的 SpVoice 内部有状态（当前语音、
        # 输出流），因此把全部调用串行到一个固定工作线程上，避免互相踩。
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="sapi")
        self._lock = threading.Lock()
        self._initialized = False
        self._voice = None
        self._format_type = None
        self._voices: list[Voice] = []
        self._tokens: dict[str, object] = {}
        self._current_voice_id: str | None = None
        # 启动时把音色枚举出来：既暴露 /v1/voices，也让中文音色的默认值有据可依
        self._executor.submit(self._ensure_ready).result(timeout=30)

    # ---- 对外 ----

    def voices(self) -> list[Voice]:
        return list(self._voices)

    def synthesize(self, text: str, voice_id: str) -> bytes:
        speech = clean_for_speech(text)
        if not speech:
            return b""
        return self._executor.submit(self._synth_blocking, speech, voice_id).result()

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

    # ---- 工作线程内部 ----

    def _ensure_ready(self) -> None:
        if self._initialized:
            return
        import pythoncom
        import win32com.client

        pythoncom.CoInitialize()
        voice = win32com.client.Dispatch("SAPI.SpVoice")
        collection = voice.GetVoices()
        voices: list[Voice] = []
        tokens: dict[str, object] = {}
        for index in range(collection.Count):
            token = collection.Item(index)
            description = str(token.GetDescription())
            locale = ""
            try:
                locale = str(token.GetAttribute("Language"))
            except Exception:
                pass
            voice_id = "sapi:" + _slug(description.split(" Desktop")[0])
            if voice_id in tokens:  # 极端情况下同机有重复描述
                voice_id = f"{voice_id}-{index}"
            tokens[voice_id] = token
            voices.append(
                Voice(
                    id=voice_id,
                    label=description,
                    engine=self.name,
                    locale=_locale_from_description(description),
                ),
            )
        self._voice = voice
        self._voices = voices
        self._tokens = tokens
        self._initialized = True
        # 默认优先中文音色（本机是 Microsoft Huihui）
        for candidate in voices:
            if candidate.locale.startswith("zh"):
                self._current_voice_id = candidate.id
                voice.Voice = tokens[candidate.id]
                break

    def _synth_blocking(self, text: str, voice_id: str) -> bytes:
        import win32com.client

        self._ensure_ready()
        token = self._tokens.get(voice_id)
        if token is None:
            raise ValueError(f"未知音色：{voice_id}")
        if voice_id != self._current_voice_id:
            self._voice.Voice = token
            self._current_voice_id = voice_id
        stream = win32com.client.Dispatch("SAPI.SpMemoryStream")
        audio_format = win32com.client.Dispatch("SAPI.SpAudioFormat")
        audio_format.Type = SAFT_24KHZ_16BIT_MONO
        stream.Format = audio_format
        self._voice.AudioOutputStream = stream
        self._voice.Speak(text)
        raw = stream.GetData()
        # pywin32 会把二进制数据当 BSTR 返回；latin-1 是逐字节的可逆映射。
        if isinstance(raw, str):
            raw = raw.encode("latin-1")
        raw = bytes(raw)
        # 去掉可能出现的 WAV 头（不同 SAPI 实现行为不一致，防御性处理）
        if raw[:4] == b"RIFF" and raw[8:12] == b"WAVE":
            raw = _strip_wav(raw)
        if len(raw) % 2:
            raw = raw[:-1]
        return raw


def _strip_wav(data: bytes) -> bytes:
    """剥掉 RIFF 头，只留 data 段。"""
    offset = 12
    while offset + 8 <= len(data):
        chunk_id = data[offset : offset + 4]
        size = int.from_bytes(data[offset + 4 : offset + 8], "little")
        if chunk_id == b"data":
            return data[offset + 8 : offset + 8 + size]
        offset += 8 + size + (size & 1)
    return data


def _locale_from_description(description: str) -> str:
    if "Chinese" in description:
        return "zh-CN"
    if "English" in description:
        return "en-US"
    return ""


def _com_available() -> bool:
    try:
        import pythoncom  # noqa: F401
        import win32com.client  # noqa: F401
    except Exception:
        return False
    return True
