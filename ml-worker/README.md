# ml-worker — 얼굴 인식 워커

InsightFace(buffalo_l)로 얼굴 감지·임베딩을 수행하는 Python 워커.
서버의 internal API로만 통신하므로 서버와 같은 머신이 아니어도 된다 (GB10에서 실행 가능).

## 배포: 단일 실행 파일 (권장)

```
build-worker.bat
```

`dist\homephoto-ml-worker.exe` 하나(~320MB)가 나온다. Python 런타임, 모든 패키지,
InsightFace 모델(det_10g + w600k_r50)이 다 들어 있어 **실행 PC에 Python도 인터넷도 필요 없다.**
더블클릭하면 바로 돈다 (첫 기동 시 임시 폴더에 풀리느라 몇 초 걸림).

- 빌드 PC에는 `.venv`(아래 개발 설치)가 필요하며 없으면 스크립트가 만든다
- 모델은 빌드 PC의 `~\.insightface\models\buffalo_l`에서 가져다 동봉한다 (없으면 내려받음)
- 묶는 내용은 `homephoto-ml-worker.spec`에 정의. `server\package-release.bat`이 이 exe를 배포 zip에 자동으로 담는다
- `server\start-server.bat`이 서버와 함께 이 exe를 새 창으로 띄우고 `stop-server.bat`이 같이 종료한다

## 개발 설치

```
cd ml-worker
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

## 실행

`server/start-server.bat`이 서버와 함께 워커도 새 창으로 띄운다 (exe가 없으면 `.venv`를 씀).
따로 띄우려면:

```
.venv\Scripts\python worker.py
```

- 최초 실행 시 buffalo_l 모델(~300MB)을 자동 다운로드한다 (`~/.insightface/models/`)
- 환경변수: `HOMEPHOTO_SERVER`(기본 http://localhost:8080), `HOMEPHOTO_API_KEY`,
  `HOMEPHOTO_CLUSTER_EPS`(DBSCAN 임계값, 기본 0.45 — 낮출수록 엄격하게 묶음)
- CPU로 장당 0.5~1초. GPU를 쓰려면 `onnxruntime` 대신 `onnxruntime-gpu`(CUDA 12.x) 설치

## 동작

1. `POST /internal/jobs/claim` (FACE) — 최근 사진 우선으로 작업 클레임
2. 원본 다운로드 → 얼굴 감지 → 정규화 bbox + 512차원 임베딩 제출
3. 큐가 비면 전체 임베딩으로 DBSCAN(cosine, numpy 직접 구현 — scikit-learn 미사용) 클러스터링을 갱신하고 10초 대기
4. 실패는 서버에 보고 (3회 누적 시 FAILED, 서버 재시작하면 리셋)

확인: `GET /api/v1/faces/clusters` — 클러스터별 얼굴 수 요약
