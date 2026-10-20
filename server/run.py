"""简读远程 TTS 网关启动器。

用法（在 F:\\Projects\\hrstts\\server 目录下）：

    python run.py                     # 默认 0.0.0.0:8080，自动选中文音色的后端
    python run.py --port 9000         # 换端口
    python run.py --engine sapi       # 指定后端（目前只有 sapi）

启动后会把「手机应该访问的地址」打印出来，直接用手机浏览器打开验证。
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
from pathlib import Path

# 让 `python run.py` 能直接 import 包（不需要 pip install -e .）
sys.path.insert(0, str(Path(__file__).resolve().parent))

from jiandu_gateway import protocol  # noqa: E402
from jiandu_gateway.engines import available, create_engine  # noqa: E402
from jiandu_gateway.server import serve  # noqa: E402


def _enable_utf8_console() -> None:
    """控制台默认是 GBK，直接 print 中文会在某些终端裂开。

    同时打开行缓冲：被重定向到文件/管道时 Python 默认块缓冲，启动横幅会一直卡在
    缓冲区里，用户以为服务没起来（实测：`python run.py 2>&1 | Tee-Object` 看不到输出）。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)  # type: ignore[union-attr]
        except Exception:
            pass


def lan_addresses() -> list[tuple[str, str]]:
    """枚举本机可被手机访问的 IPv4 地址：[(地址, 网卡名)]。"""
    found: list[tuple[str, str]] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in {f[0] for f in found}:
                found.append((ip, "主机名解析"))
    except Exception:
        pass

    # UDP connect 不会真的发包，但会让系统选出「默认出口网卡」的地址
    for probe in ("10.255.255.255", "192.168.255.255", "8.8.8.8"):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(0.2)
            sock.connect((probe, 9))
            ip = sock.getsockname()[0]
            sock.close()
            if ip not in {f[0] for f in found}:
                found.append((ip, "默认出口网卡"))
        except Exception:
            pass

    # 排除回环与虚拟网卡（VMware / VirtualBox 的地址手机永远访问不到）
    usable = [
        (ip, name)
        for ip, name in found
        if not ip.startswith("127.") and not _is_virtual(ip)
    ]
    return usable


def _is_virtual(ip: str) -> bool:
    """VMware / VirtualBox / Hyper-V 常用的网段，手机连不到，直接过滤掉噪音。"""
    prefixes = ("192.168.174.", "192.168.112.", "192.168.56.", "172.17.", "172.18.", "172.19.")
    return ip.startswith(prefixes)


def _firewall_hint(port: int) -> str:
    """给出可直接复制的放行命令。

    注意 `-Profile` 必须是 **Any**：本机 WLAN 常被 Windows 识别成 Public 配置
    （本机实测就是 Public），只放行 Private 会「命令执行成功但手机仍连不上」，
    这是最难查的一类问题。
    """
    return (
        f'  New-NetFirewallRule -DisplayName "简读TTS {port}" -Direction Inbound '
        f"-Protocol TCP -LocalPort {port} -Action Allow -Profile Any\n"
        f"  （或直接跑：powershell -ExecutionPolicy Bypass -File open-firewall.ps1）"
    )


def main(argv: list[str] | None = None) -> int:
    _enable_utf8_console()

    parser = argparse.ArgumentParser(description="简读远程 TTS 网关")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0 = 局域网可访问）")
    parser.add_argument("--port", type=int, default=8080, help="监听端口（默认 8080）")
    parser.add_argument("--engine", default="sapi", help=f"TTS 后端，可选：{', '.join(available())}")
    parser.add_argument(
        "--cache-dir",
        default=str(Path(__file__).resolve().parent / "cache"),
        help="服务端音频缓存目录（默认 server/cache）",
    )
    parser.add_argument(
        "--cache-limit-mb",
        type=int,
        default=2048,
        help="缓存上限 MB，超出按最久未访问淘汰（默认 2048）",
    )
    args = parser.parse_args(argv)

    cache_dir = Path(args.cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 68)
    print(" 简读 · 电脑 TTS 服务")
    print("=" * 68)
    print(f"  后端引擎 : {args.engine}")
    print(f"  音频格式 : {protocol.SAMPLE_RATE} Hz / 单声道 / int16（与手机端完全一致）")
    print(f"  模型标记 : {protocol.MODEL_TAG}  ← 换引擎或改音频前端时需 +1")
    print(f"  缓存目录 : {cache_dir}")
    print(f"  缓存上限 : {args.cache_limit_mb} MB")

    try:
        engine = create_engine(args.engine)
    except Exception as exc:
        print(f"\n❌ 后端初始化失败：{exc}")
        return 2

    voices = engine.voices()
    print(f"  可选音色 : {len(voices)} 个")
    for voice in voices:
        print(f"             - {voice.id}  ({voice.label})")

    try:
        httpd = serve(
            engine,
            args.host,
            args.port,
            cache_dir,
            cache_limit=args.cache_limit_mb * 1024 * 1024,
        )
    except OSError as exc:
        print(f"\n❌ 端口 {args.port} 绑定失败：{exc}")
        print("   端口可能已被占用，换一个：python run.py --port 9000")
        return 3

    print()
    print("-" * 68)
    print(" 在手机上用浏览器打开下面任意一个地址，能打开就说明链路通了：")
    addresses = lan_addresses()
    if not addresses:
        print("   ⚠️ 没找到可用的局域网地址（电脑可能没连网络）")
    for ip, name in addresses:
        print(f"    http://{ip}:{args.port}      [{name}]")
    print()
    print(" App 里填「服务器地址」时用上面第一个地址。")
    print()
    print(" 如果手机打不开，90% 是 Windows 防火墙挡住了。用管理员 PowerShell 执行：")
    print(_firewall_hint(args.port))
    print("-" * 68)
    print(" 按 Ctrl+C 停止服务")
    print()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n正在停止…")
    finally:
        httpd.shutdown()
        httpd.server_close()
        engine.close()
    print("已停止。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
