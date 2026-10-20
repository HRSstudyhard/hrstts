@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 简读 · 电脑 TTS 服务 一键启动
rem
rem 双击即可。启动后把屏幕上打印的 http://192.168.x.x:8080 地址
rem 用手机浏览器打开，能出页面就说明链路通了。

set PYTHONIOENCODING=utf-8
set PYTHONUNBUFFERED=1

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 没有找到 python，请先安装 Python 3.9+ 并加入 PATH
    pause
    exit /b 1
)

python run.py %*

echo.
echo 服务已停止。
pause
