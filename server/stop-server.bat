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
