@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ================================================
echo   홈 포토 백업 서버 - 릴리즈 패키징
echo ================================================
echo.
echo 사용법: package-release.bat [withffmpeg]
echo   withffmpeg 를 붙이면 tools\ffmpeg.exe 도 함께 담습니다 ^(용량 +212MB^).
echo.

rem ── 1. 빌드 ────────────────────────────────────────────────
echo [1/4] jar 빌드 중...
call gradlew.bat bootJar
if errorlevel 1 (
    echo.
    echo 빌드에 실패했습니다.
    pause
    exit /b 1
)

rem ── 2. 방금 만든 jar 찾기 ──────────────────────────────────
rem     -plain.jar 는 실행할 수 없는 라이브러리 jar라 걸러낸다
set "JAR="
set "NAME="
for %%f in (build\libs\homephoto-server-*.jar) do (
    echo %%~nf | findstr /e /c:"-plain" >nul
    if errorlevel 1 (
        set "JAR=%%f"
        set "NAME=%%~nf"
    )
)
if not defined JAR (
    echo build\libs 에서 실행 가능한 jar를 찾지 못했습니다.
    pause
    exit /b 1
)

set "STAGE=release\%NAME%"

rem ── 3. 배포 폴더 구성 ──────────────────────────────────────
echo [2/4] 배포 폴더 구성: %STAGE%
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%\tools"

copy /y "%JAR%"               "%STAGE%\homephoto-server.jar" >nul
copy /y "start-server.bat"    "%STAGE%\start-server.bat"     >nul
copy /y "stop-server.bat"     "%STAGE%\stop-server.bat"      >nul
copy /y "dist\README.txt"     "%STAGE%\README.txt"           >nul
copy /y "dist\tools-README.txt" "%STAGE%\tools\README.txt"   >nul

rem ── 4. ffmpeg 선택 포함 ────────────────────────────────────
echo [3/4] ffmpeg 처리...
if /i "%~1"=="withffmpeg" (
    if exist "tools\ffmpeg.exe" (
        echo     ffmpeg.exe 를 포함합니다. 용량이 커서 잠시 걸립니다...
        copy /y "tools\ffmpeg.exe" "%STAGE%\tools\ffmpeg.exe" >nul
    ) else (
        echo     경고: tools\ffmpeg.exe 가 없어 포함하지 못했습니다.
    )
) else (
    echo     생략. 사용자가 직접 tools 폴더에 넣습니다.
)

rem ── 5. 압축 ────────────────────────────────────────────────
echo [4/4] 압축 중: release\%NAME%.zip
if exist "release\%NAME%.zip" del /q "release\%NAME%.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path 'release\%NAME%' -DestinationPath 'release\%NAME%.zip' -Force"
if errorlevel 1 (
    echo 압축에 실패했습니다.
    pause
    exit /b 1
)

echo.
echo ================================================
echo   완료
echo ================================================
for %%z in ("release\%NAME%.zip") do echo   release\%NAME%.zip  ^(%%~zz 바이트^)
echo.
echo 이 zip 파일을 GitHub Releases 에 올리면 됩니다.
echo 받는 사람은 압축을 풀고 start-server.bat 을 더블클릭하면 끝입니다.
echo.
pause
