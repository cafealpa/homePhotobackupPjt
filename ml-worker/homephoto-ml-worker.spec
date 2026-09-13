# -*- mode: python ; coding: utf-8 -*-
# PyInstaller 스펙 — 얼굴 인식 워커를 단일 실행 파일(homephoto-ml-worker.exe)로 묶는다.
# 실행: build-worker.bat  (또는 .venv\Scripts\pyinstaller homephoto-ml-worker.spec)
#
# 동봉 내용: Python 런타임 + 모든 site-packages 의존성 + InsightFace buffalo_l 모델 중
# 워커가 실제 쓰는 두 파일(det_10g.onnx 감지, w600k_r50.onnx 임베딩).
# 실행 PC에는 Python 도, pip 도, 인터넷도 필요 없다.
import os
import glob

from PyInstaller.utils.hooks import collect_all

MODEL_DIR = os.path.join(os.path.expanduser("~"), ".insightface", "models", "buffalo_l")
MODEL_FILES = ["det_10g.onnx", "w600k_r50.onnx"]
for f in MODEL_FILES:
    if not os.path.exists(os.path.join(MODEL_DIR, f)):
        raise SystemExit(
            f"모델 파일이 없습니다: {os.path.join(MODEL_DIR, f)}\n"
            "build-worker.bat 을 쓰거나, 먼저 worker.py 를 한 번 실행해 모델을 내려받으세요."
        )
# 실행 시 worker.model_root() 가 sys._MEIPASS 를 루트로 주므로 models/buffalo_l/ 아래에 둔다.
model_datas = [(os.path.join(MODEL_DIR, f), os.path.join("models", "buffalo_l")) for f in MODEL_FILES]

datas, binaries, hiddenimports = list(model_datas), [], []
# insightface 는 패키지 데이터(data/images, objects)와 cython .pyd 를 동적으로 찾으므로 통째로 수집
for pkg in ["insightface", "onnxruntime", "albumentations"]:
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

a = Analysis(
    ["worker.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    # 워커가 안 쓰는 무거운 패키지 — 의존성 그래프에 딸려와도 제외 (matplotlib 은 insightface 가 import 시점에 요구해 포함)
    excludes=["sklearn", "tkinter", "torch", "torchvision", "IPython", "pytest", "PIL.ImageQt", "PyQt5", "PySide2"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="homephoto-ml-worker",
    debug=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)
