@echo off
rem 한글 메시지 표시용 - 이 파일은 CP949로 저장되어 있다
chcp 949 >nul
setlocal
cd /d "%~dp0"

echo ================================================
echo   얼굴 인식 워커 - 단일 실행 파일 빌드
echo ================================================
echo   결과물: dist\homephoto-ml-worker.exe
echo   Python 런타임 + 모든 패키지 + InsightFace 모델을 한 파일에 담습니다.
echo.

rem === 1. 가상환경과 의존성 ===
if not exist ".venv\Scripts\python.exe" (
    echo .venv 가 없어 새로 만듭니다. Python 3.12 가 PATH 에 있어야 합니다...
    python -m venv .venv
    if errorlevel 1 (
        echo Python 을 찾을 수 없습니다. https://www.python.org 에서 3.12 를 설치하세요.
        pause
        exit /b 1
    )
)
echo [1/4] 의존성 설치 확인 (requirements.txt + pyinstaller)...
".venv\Scripts\python.exe" -m pip install -q -r requirements.txt pyinstaller
if errorlevel 1 (
    echo 의존성 설치에 실패했습니다.
    pause
    exit /b 1
)

rem === 2. 모델 준비 (빌드 PC 의 ~\.insightface\models\buffalo_l 에서 가져다 동봉) ===
echo [2/4] InsightFace 모델 확인 (없으면 내려받음, 약 300MB)...
".venv\Scripts\python.exe" -c "from insightface.utils import ensure_available; ensure_available('models', 'buffalo_l')"
if errorlevel 1 (
    echo 모델 준비에 실패했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
)

rem === 3. 빌드 ===
echo [3/4] PyInstaller 빌드 중 (2~3분)...
if exist "dist\homephoto-ml-worker.exe" del /q "dist\homephoto-ml-worker.exe"
".venv\Scripts\pyinstaller.exe" --noconfirm --clean --distpath dist --workpath build homephoto-ml-worker.spec
if errorlevel 1 (
    echo 빌드에 실패했습니다.
    pause
    exit /b 1
)

rem === 4. 확인 ===
echo [4/4] 결과 확인...
if not exist "dist\homephoto-ml-worker.exe" (
    echo dist\homephoto-ml-worker.exe 가 만들어지지 않았습니다.
    pause
    exit /b 1
)
for %%f in ("dist\homephoto-ml-worker.exe") do set "SIZE=%%~zf"
set /a SIZEMB=%SIZE:~0,-6%
echo.
echo ================================================
echo   완료: dist\homephoto-ml-worker.exe  (약 %SIZEMB% MB)
echo ================================================
echo 이 파일 하나만 있으면 Python 없이 어느 Windows PC 에서든 실행됩니다.
echo server\package-release.bat 이 배포 zip 에 자동으로 담고,
echo start-server.bat 이 서버와 함께 띄웁니다.
echo.
pause
