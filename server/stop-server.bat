@echo off
chcp 65001 >nul
setlocal
set FOUND=0

rem 8080 포트를 점유한 프로세스를 찾아 종료 (SQLite는 WAL 모드라 강제 종료에도 안전)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do (
    echo 서버를 중지합니다. PID %%p
    taskkill /pid %%p /f >nul 2>&1
    set FOUND=1
)

if "%FOUND%"=="0" echo 실행 중인 서버가 없습니다. 8080 포트가 비어 있습니다.
