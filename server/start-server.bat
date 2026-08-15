@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem ── Java 확인 ───────────────────────────────────────────────
java -version >nul 2>&1
if errorlevel 1 (
    echo Java를 찾을 수 없습니다. Java 21 이상을 설치한 뒤 다시 실행하세요.
    echo 다운로드: https://adoptium.net
    pause
    exit /b 1
)

rem ── 중복 실행 방지 ─────────────────────────────────────────
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do (
    echo 이미 8080 포트에서 서버가 실행 중입니다. ^(PID %%p^)
    echo 중지하려면 stop-server.bat 을 실행하세요.
    pause
    exit /b 1
)

rem ── 실행할 jar 찾기 (배포본 = 이 폴더, 개발 = build\libs) ──
call :findjar
if not defined JAR if exist gradlew.bat (
    echo 실행할 jar가 없어 새로 빌드합니다. 처음이면 몇 분 걸립니다...
    call gradlew.bat bootJar
    if errorlevel 1 (
        echo 빌드에 실패했습니다.
        pause
        exit /b 1
    )
    call :findjar
)
if not defined JAR (
    echo 실행할 homephoto-server.jar 를 찾을 수 없습니다.
    pause
    exit /b 1
)

rem ── 시작 ────────────────────────────────────────────────────
echo 서버를 시작합니다: %JAR%
start "HomePhoto Server" java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%"
echo.
echo 새 창에서 서버가 실행됩니다. 잠시 후 http://localhost:8080 으로 접속하세요.
echo 중지하려면 stop-server.bat 을 실행하거나 새로 뜬 창을 닫으면 됩니다.
exit /b 0

rem ── jar 탐색: 배포본은 옆에, 개발 환경은 build\libs 에 있다 ──
rem     -plain.jar 는 라이브러리만 든 jar라 실행할 수 없으므로 걸러낸다
:findjar
set "JAR="
if exist "homephoto-server.jar" (
    set "JAR=homephoto-server.jar"
    goto :eof
)
for %%f in (build\libs\homephoto-server-*.jar) do (
    echo %%~nf | findstr /e /c:"-plain" >nul
    if errorlevel 1 set "JAR=%%f"
)
goto :eof
