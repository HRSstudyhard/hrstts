"""服务端音频缓存。

存在的意义：手机的同步通道靠它。电脑可以把**整本书**预合成到这个目录
（RTF 0.03~0.18 意味着 3 小时有声书约 6~20 分钟合完），手机再按 key 把缺的片段拉走；
之后断网也能连续朗读，因为手机侧的 `TtsAudioCache` 磁盘档里已经是同样的字节。

目录布局与手机一致：`<cache_dir>/<key>`，每个文件 16 字节头 + int16 LE PCM。
"""

from __future__ import annotations

import os
import shutil
import threading
from pathlib import Path

from . import protocol


class ClipCache:
    def __init__(self, root: Path, limit_bytes: int = 2 * 1024 * 1024 * 1024) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.limit_bytes = limit_bytes
        self._lock = threading.Lock()
        self.hits = 0
        self.misses = 0

    def path_of(self, key: str) -> Path:
        return self.root / key

    def _ensure_root(self) -> None:
        """缓存目录可能在运行期间被删掉（用户清理、脚本、磁盘清理工具）。

        这里每次都确保存在：否则 `/v1/health` 里的 `stats()` 会抛
        FileNotFoundError，让手机端「引擎初始化失败」—— 明明服务是好的。
        """
        try:
            self.root.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass

    def has(self, key: str) -> bool:
        return self.path_of(key).is_file()

    def get(self, key: str) -> bytes | None:
        """返回**缓存文件原始字节**（含 16 字节头），方便直接回给手机落盘。"""
        path = self.path_of(key)
        try:
            if not path.is_file():
                with self._lock:
                    self.misses += 1
                return None
            blob = path.read_bytes()
            protocol.unpack_frames(blob)  # 校验头，坏文件当作未命中
            with self._lock:
                self.hits += 1
            os.utime(path)  # 触达时间 → LRU 依据
            return blob
        except Exception:
            # 坏文件直接删掉，下次会重新合成
            try:
                path.unlink()
            except OSError:
                pass
            with self._lock:
                self.misses += 1
            return None

    def put(self, key: str, pcm: bytes) -> bytes:
        """写入一段 PCM，返回写好的缓存文件字节。"""
        self._ensure_root()
        blob = protocol.pack_clip(pcm)
        target = self.path_of(key)
        tmp = target.with_suffix(target.suffix + ".tmp")
        try:
            tmp.write_bytes(blob)
            tmp.replace(target)
        except OSError:
            tmp.unlink(missing_ok=True)
            raise
        self.trim()
        return blob

    def stats(self) -> dict:
        try:
            self._ensure_root()
            files = [p for p in self.root.iterdir() if p.is_file() and not p.name.endswith(".tmp")]
            total = sum(p.stat().st_size for p in files)
        except OSError:
            return {
                "entries": 0,
                "bytes": 0,
                "limitBytes": self.limit_bytes,
                "hits": self.hits,
                "misses": self.misses,
            }
        return {
            "entries": len(files),
            "bytes": total,
            "limitBytes": self.limit_bytes,
            "hits": self.hits,
            "misses": self.misses,
        }

    def trim(self) -> None:
        """超出上限时按「最久未访问」删除。"""
        try:
            self._ensure_root()
            files = [
                p for p in self.root.iterdir() if p.is_file() and not p.name.endswith(".tmp")
            ]
            total = sum(p.stat().st_size for p in files)
        except OSError:
            return
        if total <= self.limit_bytes:
            return
        files.sort(key=lambda p: p.stat().st_mtime)
        for path in files:
            if total <= self.limit_bytes:
                break
            try:
                size = path.stat().st_size
                path.unlink()
                total -= size
            except OSError:
                pass

    def clear(self) -> int:
        self._ensure_root()
        files = [p for p in self.root.iterdir() if p.is_file()]
        freed = sum(p.stat().st_size for p in files)
        for path in files:
            try:
                path.unlink()
            except OSError:
                pass
        return freed

    def prune_to(self, keep_bytes: int) -> int:
        """把缓存压到指定体积以下（例如手机端缓存上限），返回释放字节数。"""
        before = self.stats()["bytes"]
        old = self.limit_bytes
        self.limit_bytes = keep_bytes
        self.trim()
        self.limit_bytes = old
        return max(0, before - self.stats()["bytes"])
