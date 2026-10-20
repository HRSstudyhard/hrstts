"""服务端自检：不起 HTTP，直接验证协议、引擎与缓存。

运行：python test_smoke.py
（这是纯脚本，不依赖 pytest，方便在 Windows 上直接跑完看结果。）
"""

from __future__ import annotations

import hashlib
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jiandu_gateway import protocol  # noqa: E402
from jiandu_gateway.cache import ClipCache  # noqa: E402
from jiandu_gateway.engines import create_engine  # noqa: E402

PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    (PASSED if condition else FAILED).append(name)
    mark = "✅" if condition else "❌"
    print(f"  {mark} {name}" + (f"  — {detail}" if detail else ""))


def section(title: str) -> None:
    print(f"\n{title}")
    print("-" * 68)


def test_key_algorithm() -> None:
    """缓存 key 必须与 Android 端 `TtsAudioCache.keyOf` 逐字节一致。

    这个期望值是用**独立实现的** sha1 手算出来的（不复用 protocol.key_of），
    这样如果哪天有人改了拼接顺序或分隔符，这里会立刻炸。
    """
    section("1) 缓存 key 算法（与 Android 端必须一致）")
    text = "第一章 少年"
    voice = "sapi:microsoft-huihui"
    model = "jiandu-remote-v1"
    independent = hashlib.sha1(f"{text}|{voice}|{model}".encode("utf-8")).hexdigest()
    actual = protocol.key_of(text, voice, model)
    check("key = sha1(text|voice|modelTag)", actual == independent, actual)
    check("key 是 40 位小写十六进制", len(actual) == 40 and actual == actual.lower())
    check(
        "分隔符是 0x7C",
        protocol.key_of("a", "b", "c")
        == hashlib.sha1(b"a" + b"|" + b"b" + b"|" + b"c").hexdigest(),
    )
    check(
        "换音色 / 换 modelTag 必然换 key",
        len({protocol.key_of(text, "v1", model), protocol.key_of(text, "v2", model),
             protocol.key_of(text, "v1", "jiandu-remote-v2")}) == 3,
    )
    print(f"\n  用于 Kotlin 单测的固定向量：")
    print(f'    text  = "{text}"')
    print(f'    voice = "{voice}"')
    print(f'    model = "{model}"')
    print(f'    key   = "{actual}"')
    # 留给 Kotlin RemoteTtsProtocolTest 用的常量
    Path(__file__).with_name("KEY_VECTOR.txt").write_text(
        f"{text}\n{voice}\n{model}\n{actual}\n", encoding="utf-8",
    )


def test_clip_format() -> None:
    section("2) 缓存文件格式（= Kotlin TtsAudioCache 磁盘格式）")
    pcm = bytes(range(256)) * 8  # 2048 字节 = 1024 采样
    blob = protocol.pack_clip(pcm)
    check("头部 16 字节", len(blob) == 16 + len(pcm))
    check("magic = 'jdpc'", blob[0:4] == b"jdpc")
    check("版本 = 1", blob[4] == 1)
    frames_from_header = int.from_bytes(blob[8:12], "big")
    check("frames 在 8..11 且为大端",
          frames_from_header == protocol.frames_of(len(pcm) // 2),
          f"frames={frames_from_header}")
    check("12..15 保留为 0", blob[12:16] == b"\x00\x00\x00\x00")
    frames, back = protocol.unpack_frames(blob)
    check("解包可逆", back == pcm and frames == frames_from_header)
    check("1920 采样 = 1 帧（12.5 帧/秒）", protocol.frames_of(1920) == 1)
    check("bad magic 抛错", _raises(lambda: protocol.unpack_frames(b"xxxx" + blob[4:])))


def test_wav() -> None:
    section("3) WAV 包装（浏览器自测页用）")
    wav = protocol.wrap_wav(b"\x00\x01" * 100)
    check("RIFF/WAVE 头", wav[0:4] == b"RIFF" and wav[8:12] == b"WAVE")
    check("采样率 24000", int.from_bytes(wav[24:28], "little") == 24000)
    check("单声道 + 16 位", int.from_bytes(wav[22:24], "little") == 1
          and int.from_bytes(wav[34:36], "little") == 16)


def test_cache() -> None:
    section("4) 服务端缓存")
    with tempfile.TemporaryDirectory() as tmp:
        cache = ClipCache(Path(tmp), limit_bytes=1024 * 1024)
        pcm = b"\x00\x10" * 24000  # 1 秒
        key = protocol.key_of("测试", "sapi:test")
        check("未写入前未命中", cache.get(key) is None)
        blob = cache.put(key, pcm)
        check("put 返回的就是落盘字节", cache.path_of(key).read_bytes() == blob)
        hit = cache.get(key)
        check("写入后命中", hit == blob)
        check("命中内容可解包", protocol.unpack_frames(hit)[1] == pcm)
        check("has() 一致", cache.has(key))
        bad = cache.path_of("deadbeef" * 5)
        bad.write_bytes(b"not-a-clip")
        check("坏文件当作未命中并清理", cache.get(bad.name) is None and not bad.exists())
        stats = cache.stats()
        check("统计里有条目数", stats["entries"] == 1, f"entries={stats['entries']}")

        # 回归：缓存目录在运行期间被删掉（用户清理 / 磁盘清理工具）时，
        # 既不能把 /v1/health 打成 500，也不能让下一次写入失败。
        import shutil
        shutil.rmtree(Path(tmp), ignore_errors=True)
        try:
            empty = cache.stats()
            check("缓存目录被删后 stats 不抛异常", empty["entries"] == 0, f"entries={empty['entries']}")
        except Exception as exc:
            check("缓存目录被删后 stats 不抛异常", False, f"{type(exc).__name__}: {exc}")
        try:
            cache.put(key, pcm)
            check("缓存目录被删后仍能重新写入", cache.get(key) is not None)
        except Exception as exc:
            check("缓存目录被删后仍能重新写入", False, f"{type(exc).__name__}: {exc}")


def test_engine() -> None:
    section("5) SAPI 引擎实测（真实合成）")
    engine = create_engine("sapi")
    voices = engine.voices()
    check("至少有一个音色", len(voices) >= 1, f"{len(voices)} 个")
    zh = [v for v in voices if v.locale.startswith("zh")]
    check("有中文音色", len(zh) >= 1, zh[0].label if zh else "无")

    samples = [
        ("你好，世界。", "短句"),
        ("第一章 少年。他翻开了第二章，发现字迹模糊不清，于是放下书走了出去。", "长句"),
        ("他说：“我们走吧！”然后转身离开。", "含引号与破折号"),
    ]
    import time
    for text, label in samples:
        voice = zh[0].id if zh else voices[0].id
        started = time.perf_counter()
        pcm = engine.synthesize(text, voice)
        elapsed = (time.perf_counter() - started) * 1000
        okay = True
        if len(pcm) < 2000:
            check(f"{label}：有音频输出", False, f"只有 {len(pcm)} 字节")
            continue
        seconds = len(pcm) / 2 / protocol.SAMPLE_RATE
        rtf = (elapsed / 1000) / seconds if seconds else 0
        check(
            f"{label}：{seconds:.2f}s 音频 / {elapsed:.0f}ms / RTF={rtf:.3f}",
            len(pcm) % 2 == 0 and seconds > 0.1,
        )
        check(f"{label}：不是 WAV（是裸 PCM）", pcm[0:4] != b"RIFF")
        peak = max(abs(int.from_bytes(pcm[i:i + 2], "little", signed=True))
                   for i in range(0, min(len(pcm), 24000 * 2), 2))
        check(f"{label}：有实际波形（peak={peak}）", peak > 500)

    check("未知音色抛错", _raises(lambda: engine.synthesize("测试", "sapi:不存在")))
    check("空白文本返回空", engine.synthesize("   ", voices[0].id) == b"")
    engine.close()


def _raises(fn) -> bool:
    try:
        fn()
    except Exception:
        return True
    return False


def main() -> int:
    import io
    sys.stdout.reconfigure(encoding="utf-8", errors="replace") if hasattr(sys.stdout, "reconfigure") else None
    print("=" * 68)
    print(" 简读远程 TTS · 服务端自检")
    print("=" * 68)
    test_key_algorithm()
    test_clip_format()
    test_wav()
    test_cache()
    try:
        test_engine()
    except Exception as exc:
        check("SAPI 引擎可用", False, f"{type(exc).__name__}: {exc}")
    print()
    print("=" * 68)
    print(f" 通过 {len(PASSED)} 项，失败 {len(FAILED)} 项")
    if FAILED:
        for name in FAILED:
            print(f"   失败：{name}")
        return 1
    print(" 全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
