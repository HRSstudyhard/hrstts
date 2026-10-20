"""简读远程 TTS 网关（HTTP 层）。

设计哲学：**手机不该知道电脑上用哪个 TTS 引擎**。
所有引擎差异都被压缩成「传文本、收 24 kHz int16 PCM」这一个动作，
这样换引擎（SAPI → edge-tts → GPT-SoVITS）时手机端不用重新发版。

接口一览（详细说明见 docs/REMOTE-TTS.md）：

  GET  /                    手机浏览器打开的自测页（10 秒验证链路）
  GET  /v1/health           引擎、音色、采样率、modelTag（客户端要用它算缓存 key）
  GET  /v1/voices           音色列表
  POST /v1/tts              合成一句 → 裸 PCM（App 用）
  POST /v1/tts/wav          合成一句 → WAV（浏览器预览用）
  GET  /v1/clips/<key>      取服务端缓存片段（jdpc 格式，手机可直接落盘）
  POST /v1/sync             登记整本预合成任务（后台跑）
  GET  /v1/sync/<book>      查询预合成进度
  GET  /v1/stats            缓存统计
"""

from __future__ import annotations

import json
import sys
import threading
import time
import traceback
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from . import protocol
from .cache import ClipCache
from .engines import TtsEngine, available, create_engine

SERVER_VERSION = "jiandu-tts-gateway/1.0"


class SyncJob:
    """一本书的预合成任务。"""

    __slots__ = ("book", "total", "done", "failed", "running", "keys", "error")

    def __init__(self, book: str, total: int) -> None:
        self.book = book
        self.total = total
        self.done = 0
        self.failed = 0
        self.running = True
        self.keys: list[str] = []
        self.error: str | None = None

    def to_json(self) -> dict:
        return {
            "book": self.book,
            "total": self.total,
            "done": self.done,
            "failed": self.failed,
            "running": self.running,
            "error": self.error,
        }


class Gateway:
    """把引擎 + 缓存 + 任务粘起来。HTTP 层只负责搬运。"""

    def __init__(self, engine: TtsEngine, cache_dir: Path, cache_limit: int = 2 << 30) -> None:
        self.engine = engine
        self.cache = ClipCache(cache_dir, limit_bytes=cache_limit)
        self.jobs: dict[str, SyncJob] = {}
        self._jobs_lock = threading.Lock()
        self.started_at = time.time()
        self.stats = {"requests": 0, "synth": 0, "synthMs": 0, "cacheHit": 0}
        self._default_voice = self._pick_default_voice()

    # ---- 音色 ----

    def _pick_default_voice(self) -> str:
        voices = self.engine.voices()
        for voice in voices:
            if voice.locale.startswith("zh"):
                return voice.id
        return voices[0].id if voices else ""

    @property
    def default_voice(self) -> str:
        return self._default_voice

    def resolve_voice(self, voice_id: str | None) -> str:
        return voice_id or self._default_voice

    # ---- 合成 ----

    def synthesize(self, text: str, voice_id: str) -> tuple[bytes, str, bool]:
        """返回 (PCM, key, 是否命中缓存)。"""
        key = protocol.key_of(text, voice_id)
        blob = self.cache.get(key)
        if blob is not None:
            self.stats["cacheHit"] += 1
            _frames, pcm = protocol.unpack_frames(blob)
            return pcm, key, True
        started = time.perf_counter()
        pcm = self.engine.synthesize(text, voice_id)
        self.stats["synth"] += 1
        self.stats["synthMs"] += int((time.perf_counter() - started) * 1000)
        if not pcm:
            return b"", key, False
        self.cache.put(key, pcm)
        return pcm, key, False

    # ---- 预合成任务 ----

    def start_sync(self, book: str, voice_id: str, items: list[dict]) -> SyncJob:
        """登记一本书的预合成，立即返回；后台线程逐句合成。"""
        with self._jobs_lock:
            current = self.jobs.get(book)
            if current is not None and current.running:
                return current
            job = SyncJob(book, len(items))
            self.jobs[book] = job
        thread = threading.Thread(
            target=self._run_sync,
            args=(job, voice_id, items),
            name=f"sync-{book[:8]}",
            daemon=True,
        )
        thread.start()
        return job

    def _run_sync(self, job: SyncJob, voice_id: str, items: list[dict]) -> None:
        try:
            for item in items:
                text = (item.get("text") or "").strip()
                if not text:
                    job.done += 1
                    continue
                expected = item.get("key")
                key = protocol.key_of(text, voice_id)
                if expected and expected != key:
                    # key 不一致说明两端协议漂移了，尽早报出来而不是默默存错位置
                    job.failed += 1
                    job.done += 1
                    continue
                try:
                    self.synthesize(text, voice_id)
                    job.keys.append(key)
                except Exception:
                    job.failed += 1
                    traceback.print_exc()
                job.done += 1
        except Exception as exc:  # 整个任务失败（例如引擎崩了）
            job.error = str(exc)
            traceback.print_exc()
        finally:
            job.running = False

    def job_of(self, book: str) -> SyncJob | None:
        with self._jobs_lock:
            return self.jobs.get(book)

    # ---- 状态 ----

    def health(self) -> dict:
        return {
            "ok": True,
            "server": SERVER_VERSION,
            "engine": self.engine.name,
            "defaultVoice": self._default_voice,
            "voices": [v.to_json() for v in self.engine.voices()],
            "sampleRate": protocol.SAMPLE_RATE,
            "bytesPerSample": protocol.BYTES_PER_SAMPLE,
            "framesPerSecond": protocol.FRAMES_PER_SECOND,
            "modelTag": protocol.MODEL_TAG,
            "uptimeSeconds": int(time.time() - self.started_at),
            "cache": self.cache.stats(),
            "enginesAvailable": available(),
        }


def make_handler(gateway: Gateway):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = SERVER_VERSION
        #: 关掉默认的逐请求日志噪音，只打印摘要
        quiet_paths = ("/v1/health",)

        # ---- 工具 ----

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003
            if self.path.split("?")[0] in self.quiet_paths:
                return
            print(f"  [{self.address_string()}] {fmt % args}")

        def _json(self, payload: dict, status: int = HTTPStatus.OK) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _bytes(self, body: bytes, content_type: str, extra: dict | None = None,
                   status: int = HTTPStatus.OK) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for name, value in (extra or {}).items():
                self.send_header(name, str(value))
            self.end_headers()
            self.wfile.write(body)

        def _html(self, body: str) -> None:
            self._bytes(body.encode("utf-8"), "text/html; charset=utf-8")

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0:
                return {}
            raw = self.rfile.read(length)
            try:
                return json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError as exc:
                raise ValueError(f"请求体不是合法 JSON：{exc}") from exc

        # ---- 路由 ----

        def do_GET(self) -> None:  # noqa: N802
            gateway.stats["requests"] += 1
            parsed = urlparse(self.path)
            path = unquote(parsed.path)
            try:
                if path in ("/", "/index.html"):
                    return self._html(INDEX_HTML.replace("__VOICES__", _voice_options(gateway)))
                if path == "/v1/health":
                    return self._json(gateway.health())
                if path == "/v1/voices":
                    return self._json({"voices": [v.to_json() for v in gateway.engine.voices()]})
                if path == "/v1/stats":
                    return self._json({**gateway.stats, "cache": gateway.cache.stats()})
                if path == "/v1/preview.wav":
                    query = parse_qs(parsed.query)
                    text = (query.get("text") or [""])[0]
                    voice = gateway.resolve_voice((query.get("voice") or [None])[0])
                    if not text.strip():
                        return self._json({"error": "text 不能为空"}, HTTPStatus.BAD_REQUEST)
                    pcm, key, hit = gateway.synthesize(text, voice)
                    return self._bytes(
                        protocol.wrap_wav(pcm),
                        "audio/wav",
                        {"X-Key": key, "X-Cache": "hit" if hit else "miss"},
                    )
                if path.startswith("/v1/clips/"):
                    key = path[len("/v1/clips/"):]
                    if not _looks_like_key(key):
                        return self._json({"error": "key 格式不对"}, HTTPStatus.BAD_REQUEST)
                    blob = gateway.cache.get(key)
                    if blob is None:
                        return self._json({"error": "片段不存在", "key": key}, HTTPStatus.NOT_FOUND)
                    return self._bytes(blob, "application/octet-stream", {"X-Key": key})
                if path.startswith("/v1/sync/"):
                    book = path[len("/v1/sync/"):]
                    job = gateway.job_of(book)
                    if job is None:
                        return self._json({"error": "没有这个任务"}, HTTPStatus.NOT_FOUND)
                    return self._json(job.to_json())
                return self._json({"error": f"没有这个接口：{path}"}, HTTPStatus.NOT_FOUND)
            except Exception as exc:  # noqa: BLE001
                traceback.print_exc()
                return self._json({"error": f"{type(exc).__name__}: {exc}"},
                                  HTTPStatus.INTERNAL_SERVER_ERROR)

        def do_POST(self) -> None:  # noqa: N802
            gateway.stats["requests"] += 1
            path = urlparse(self.path).path
            try:
                payload = self._read_json()
                if path in ("/v1/tts", "/v1/tts/wav"):
                    text = (payload.get("text") or "").strip()
                    if not text:
                        return self._json({"error": "text 不能为空"}, HTTPStatus.BAD_REQUEST)
                    voice = gateway.resolve_voice(payload.get("voice"))
                    pcm, key, hit = gateway.synthesize(text, voice)
                    frames = protocol.frames_of(len(pcm) // protocol.BYTES_PER_SAMPLE)
                    if path == "/v1/tts/wav":
                        return self._bytes(
                            protocol.wrap_wav(pcm),
                            "audio/wav",
                            {"X-Key": key, "X-Cache": "hit" if hit else "miss", "X-Frames": frames},
                        )
                    return self._bytes(
                        pcm,
                        f"audio/L16;rate={protocol.SAMPLE_RATE};channels=1",
                        {
                            "X-Key": key,
                            "X-Frames": frames,
                            "X-Sample-Rate": protocol.SAMPLE_RATE,
                            "X-Voice": voice,
                            "X-Cache": "hit" if hit else "miss",
                            "Cache-Control": "no-store",
                        },
                    )
                if path == "/v1/sync":
                    book = (payload.get("book") or "").strip()
                    items = payload.get("items") or []
                    if not book:
                        return self._json({"error": "book 不能为空"}, HTTPStatus.BAD_REQUEST)
                    if not isinstance(items, list):
                        return self._json({"error": "items 必须是数组"}, HTTPStatus.BAD_REQUEST)
                    voice = gateway.resolve_voice(payload.get("voice"))
                    job = gateway.start_sync(book, voice, items)
                    return self._json(job.to_json(), HTTPStatus.ACCEPTED)
                return self._json({"error": f"没有这个接口：{path}"}, HTTPStatus.NOT_FOUND)
            except ValueError as exc:
                return self._json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
            except Exception as exc:  # noqa: BLE001
                traceback.print_exc()
                return self._json({"error": f"{type(exc).__name__}: {exc}"},
                                  HTTPStatus.INTERNAL_SERVER_ERROR)

    return Handler


def _looks_like_key(key: str) -> bool:
    return len(key) == 40 and all(c in "0123456789abcdef" for c in key)


def _voice_options(gateway: Gateway) -> str:
    rows = []
    for voice in gateway.engine.voices():
        selected = " selected" if voice.id == gateway.default_voice else ""
        rows.append(f'<option value="{voice.id}"{selected}>{voice.label}</option>')
    return "\n".join(rows)


class GatewayServer(ThreadingHTTPServer):
    """HTTP 服务器。

    唯一改动是 [handle_error]：手机「停止朗读 / 切到下一句」时会主动
    `disconnect()` 掉正在读的 socket，`http.server` 默认会把每次
    ConnectionResetError 打成一整段 traceback。那是**正常行为**，不是故障，
    打出来只会掩盖真正的问题（也让人以为服务在报错）。
    """

    daemon_threads = True

    #: 客户端主动断开 → 正常，静默
    _BENIGN = (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)

    def handle_error(self, request, client_address) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, self._BENIGN):
            return
        host = client_address[0] if client_address else "?"
        print(f"  [{host}] 处理请求出错：{exc!r}")
        traceback.print_exc()


def serve(engine: TtsEngine, host: str, port: int, cache_dir: Path,
          cache_limit: int = 2 << 30) -> GatewayServer:
    gateway = Gateway(engine, cache_dir, cache_limit)
    handler = make_handler(gateway)
    httpd = GatewayServer((host, port), handler)
    httpd.gateway = gateway  # type: ignore[attr-defined]
    return httpd


INDEX_HTML = """<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>简读 · 电脑 TTS 服务</title>
<style>
 body{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;margin:0;padding:18px;
      background:#12100f;color:#ece5e0;max-width:720px;margin:0 auto}
 h1{font-size:19px;margin:0 0 4px} .sub{color:#a89b93;font-size:13px;margin-bottom:16px}
 textarea,select,button{width:100%;box-sizing:border-box;font-size:15px;border-radius:10px;
      border:1px solid #3a3330;background:#1c1917;color:#ece5e0;padding:10px}
 textarea{min-height:110px;resize:vertical;font-family:inherit}
 label{display:block;margin:14px 0 6px;font-size:13px;color:#a89b93}
 button{background:#6e56cf;border:0;font-weight:600;padding:12px;margin-top:14px;cursor:pointer}
 button:active{background:#5b45b0} button:disabled{background:#3a3330;color:#7a6f68}
 #out{margin-top:14px;font-size:13px;color:#a89b93;white-space:pre-wrap;line-height:1.6}
 .ok{color:#7ec699}.err{color:#e08a8a}
</style></head><body>
<h1>简读 · 电脑 TTS 服务</h1>
<div class="sub">这个页面能打开，说明手机已经连上电脑了。输入文本点「朗读」即可验证整条链路。</div>
<label>音色</label>
<select id="voice">__VOICES__</select>
<label>文本</label>
<textarea id="text">第一章 少年。他翻开了第二章，发现字迹模糊不清，于是放下书走了出去。</textarea>
<button id="go">朗读</button>
<div id="out"></div>
<script>
const go = document.getElementById('go');
const out = document.getElementById('out');
let audio = null;
go.onclick = async () => {
  const text = document.getElementById('text').value.trim();
  if (!text) return;
  go.disabled = true; out.className = ''; out.textContent = '合成中…';
  const t0 = performance.now();
  try {
    const resp = await fetch('/v1/tts/wav', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({text, voice: document.getElementById('voice').value})
    });
    if (!resp.ok) throw new Error(resp.status + ' ' + (await resp.text()));
    const blob = await resp.blob();
    const t1 = performance.now();
    if (audio) { audio.pause(); URL.revokeObjectURL(audio.src); }
    audio = new Audio(URL.createObjectURL(blob));
    await audio.play();
    const ms = (t1 - t0).toFixed(0);
    const frames = resp.headers.get('X-Frames');
    out.className = 'ok';
    out.textContent = `✅ 首音 ${ms} ms · ${(blob.size / 1024).toFixed(0)} KB · ${frames} 帧 · 缓存=${resp.headers.get('X-Cache')}`;
  } catch (e) {
    out.className = 'err';
    out.textContent = '❌ ' + e.message;
  } finally { go.disabled = false; }
};
</script></body></html>
"""
