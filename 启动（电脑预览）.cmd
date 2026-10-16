@echo off
chcp 65001 >nul
title 我的课表 - 本地服务器
cd /d "%~dp0"
echo.
echo   正在启动「我的课表」本地服务器...
echo.
where node >nul 2>nul
if errorlevel 1 (
  echo   [!] 没有找到 Node.js。本机没装 Node 也可以直接双击 index.html 使用，
  echo       只是"添加到桌面"和"系统通知"功能会受限。
  echo.
  pause
  exit /b 1
)
echo   电脑浏览器请打开：  http://localhost:4173/
echo   手机（连同一个 WiFi）请打开：  http://本机IP:4173/   本机 IP：
ipconfig | findstr /i "IPv4"
echo.
echo   关掉这个黑窗口即停止服务器。按 Ctrl+C 也可以。
echo.
start "" http://localhost:4173/
node _dev\serve.mjs 4173
pause
