"""引擎注册表。新增后端只要在这里登记一行。"""

from __future__ import annotations

from .base import TtsEngine, Voice

_REGISTRY: dict[str, object] = {}


def _register() -> None:
    global _REGISTRY
    if _REGISTRY:
        return
    from .sapi import SapiEngine

    _REGISTRY = {"sapi": SapiEngine}


def available() -> list[str]:
    _register()
    return sorted(_REGISTRY)


def create_engine(name: str) -> TtsEngine:
    _register()
    key = name.strip().lower()
    factory = _REGISTRY.get(key)
    if factory is None:
        raise ValueError(f"未知引擎 {name!r}，可用：{', '.join(available())}")
    return factory()


__all__ = ["TtsEngine", "Voice", "available", "create_engine"]
