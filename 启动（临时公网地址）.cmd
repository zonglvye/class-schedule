@echo off
chcp 65001 >nul
title 我的课表 - 临时公网 HTTPS 地址
cd /d "%~dp0"

if not exist "_dev\bin\cloudflared.exe" (
  echo   [!] 找不到 _dev\bin\cloudflared.exe
  echo       请先下载：https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe
  echo       保存为 _dev\bin\cloudflared.exe 后再运行本脚本。
  pause
  exit /b 1
)

echo.
echo   本脚本会：① 起本地服务器 ② 开一条 Cloudflare 临时隧道
echo   然后给你一个 https://xxxx.trycloudflare.com 地址，手机用这个地址打开即可。
echo.
echo   注意：这是 Cloudflare 的免账号临时隧道（无可用性保证），
echo         关掉本窗口地址就失效，下次重开会换一个新地址。
echo         想长期固定地址，请部署到 GitHub Pages，见 README。
echo.

start "课表本地服务器" /min cmd /c "node _dev\serve.mjs 4174"
timeout /t 2 >nul

echo   正在建立隧道，请稍等 10 秒左右，下面出现
echo   "Your quick Tunnel has been created! Visit it at ..." 那一行就是你的网址：
echo.
"_dev\bin\cloudflared.exe" tunnel --url http://127.0.0.1:4174 --no-autoupdate
pause
