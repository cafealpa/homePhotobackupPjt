@echo off
rem 한글 메시지 표시용 - 이 파일은 CP949로 저장되어 있다
chcp 949 >nul
setlocal enabledelayedexpansion
set "KILLED="

rem 8080 포트를 점유한 프로세스를 종료한다.
rem SQLite는 WAL 모드라 강제 종료해도 데이터가 깨지지 않는다.
rem netstat 은 IPv4/IPv6 를 따로 출력하므로 같은 PID 를 두 번 처리하지 않도록 걸러낸다.
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do (
    echo !KILLED! | findstr /c:"[%%p]" >nul
    if errorlevel 1 (
        echo 서버를 중지합니다. PID %%p
        taskkill /pid %%p /f >nul 2>&1
        set "KILLED=!KILLED![%%p]"
    )
)

if not defined KILLED echo 실행 중인 서버가 없습니다. 8080 포트가 비어 있습니다.

rem === 얼굴 인식 워커(ml-worker) 종료 ===
rem start-server.bat 이 같이 띄운 python worker.py 를 명령줄로 찾아 종료한다.
rem (콘솔 창 제목은 Windows Terminal 환경에서 믿을 수 없어 명령줄로 찾는다)
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'python*' -and $_.CommandLine -like '*worker.py*' }; if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (
    echo 실행 중인 얼굴 인식 워커가 없습니다.
) else (
    echo 얼굴 인식 워커를 중지했습니다.
)
