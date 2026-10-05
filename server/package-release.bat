@echo off
setlocal
set "OPTIONS="
if /i "%~1"=="withffmpeg" set "OPTIONS=-WithFfmpeg"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-release.ps1" %OPTIONS%
exit /b %errorlevel%
