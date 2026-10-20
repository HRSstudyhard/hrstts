"""简读远程 TTS —— 客户端与服务端共享的协议契约。

这个文件是**唯一真源**：Android 侧的 `RemoteTtsProtocol.kt` 必须与它逐字节一致，
否则手机本地缓存与服务端缓存会算出不同的 key，缓存永远不命中、同步也白做。

契约内容：
  1. 音频格式：24 kHz / 单声道 / int16 小端裸 PCM（与端侧 Qwen3-TTS 输出的采样率一致，
     因此手机侧的 AudioTrack 与 TtsAudioCache 一行都不用改）。
  2. 缓存 key：sha1(text_utf8 | voice_id_utf8 | model_tag_utf8)，十六进制小写。
     分隔符是单个字节 0x7C（'|'），与 Kotlin 的 `digest.update('|'.code.toByte())` 相同。
  3. 缓存文件：16 字节头 + int16 LE PCM，直接复用 Kotlin `TtsAudioCache` 的磁盘格式。
"""

from __future__ import annotations

import hashlib
import struct

# ---- 音频格式 ----

SAMPLE_RATE = 24_000
BYTES_PER_SAMPLE = 2

# Qwen3-TTS 是 12.5 帧/秒的 codec 帧；手机侧的 `frames` 只用于日志与「近似音频时长」，
# 这里按同样的定义换算，保证两端打印出来的秒数一致。
FRAMES_PER_SECOND = 12.5
SAMPLES_PER_FRAME = int(SAMPLE_RATE / FRAMES_PER_SECOND)  # 1920

# ---- 缓存 key ----

#: 换掉音频前端（重采样策略、文本正则化规则、模型权重）时必须 +1，
#: 否则手机里拿着旧音频的缓存会被新结果污染。
MODEL_TAG = "jiandu-remote-v1"

KEY_SEPARATOR = b"|"


def key_of(text: str, voice_id: str, model_tag: str = MODEL_TAG) -> str:
    """缓存 key。与 Kotlin `TtsAudioCache.keyOf` 必须完全一致。"""
    digest = hashlib.sha1()
    digest.update(text.encode("utf-8"))
    digest.update(KEY_SEPARATOR)
    digest.update(voice_id.encode("utf-8"))
    digest.update(KEY_SEPARATOR)
    digest.update(model_tag.encode("utf-8"))
    return digest.hexdigest()


# ---- 缓存文件格式（= Kotlin TtsAudioCache 磁盘格式） ----

MAGIC = b"jdpc"
FORMAT_VERSION = 1
HEADER_BYTES = 16


def frames_of(samples: int) -> int:
    """采样点数 → 帧数（四舍五入，至少 1 帧）。"""
    return max(1, round(samples / SAMPLES_PER_FRAME))


def pack_clip(pcm: bytes) -> bytes:
    """int16 LE PCM → 缓存文件字节（16 字节头 + PCM）。

    头部布局（与 Kotlin 逐字节一致）：
      0..3   'j' 'd' 'p' 'c'
      4      FORMAT_VERSION
      5..7   保留 0
      8..11  frames（**大端** int32）
      12..15 保留 0
    """
    frames = frames_of(len(pcm) // BYTES_PER_SAMPLE)
    header = bytearray(HEADER_BYTES)
    header[0:4] = MAGIC
    header[4] = FORMAT_VERSION
    header[8:12] = struct.pack(">i", frames)
    return bytes(header) + pcm


def unpack_frames(blob: bytes) -> tuple[int, bytes]:
    """缓存文件字节 → (frames, PCM)；头不合法时抛 ValueError。"""
    if len(blob) < HEADER_BYTES or blob[0:4] != MAGIC:
        raise ValueError("不是合法的 jdpc 片段")
    if blob[4] != FORMAT_VERSION:
        raise ValueError(f"不支持的片段版本 {blob[4]}")
    (frames,) = struct.unpack(">i", blob[8:12])
    return frames, blob[HEADER_BYTES:]


# ---- WAV 包装（给手机浏览器里的自测页面用；App 走裸 PCM，不需要） ----


def wrap_wav(pcm: bytes) -> bytes:
    byte_rate = SAMPLE_RATE * BYTES_PER_SAMPLE
    header = (
        b"RIFF"
        + struct.pack("<I", 36 + len(pcm))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, SAMPLE_RATE, byte_rate, BYTES_PER_SAMPLE, 16)
        + b"data"
        + struct.pack("<I", len(pcm))
    )
    return header + pcm
