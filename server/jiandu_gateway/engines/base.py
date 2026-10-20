"""引擎抽象。

这一层是为了「引擎可替换」：手机的 HTTP 契约是稳定的，电脑上用哪个 TTS 后端
（Windows SAPI / edge-tts / GPT-SoVITS / Qwen3-TTS）只是插件的差异。
换引擎不需要动手机端一行代码，也不会让手机缓存失效——只要 `voice_id` 变了就会自动换 key。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Voice:
    """一个可选音色。`id` 会进入缓存 key，必须稳定。"""

    id: str
    label: str
    engine: str
    locale: str = ""

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "label": self.label,
            "engine": self.engine,
            "locale": self.locale,
        }


class TtsEngine:
    """所有后端的基类。`synthesize` 必须返回 24 kHz/单声道/int16 小端裸 PCM。"""

    name = "base"

    def voices(self) -> list[Voice]:
        raise NotImplementedError

    def synthesize(self, text: str, voice_id: str) -> bytes:
        """合成一段文本，返回裸 PCM（无 WAV 头）。"""
        raise NotImplementedError

    def close(self) -> None:
        pass

    def describe(self) -> dict:
        return {"engine": self.name, "voices": [v.to_json() for v in self.voices()]}
