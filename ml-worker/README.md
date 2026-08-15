# ml-worker — 얼굴 인식 워커

InsightFace(buffalo_l)로 얼굴 감지·임베딩을 수행하는 Python 워커.
서버의 internal API로만 통신하므로 서버와 같은 머신이 아니어도 된다 (GB10에서 실행 가능).

## 설치 (최초 1회)

```
cd ml-worker
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

## 실행

서버가 떠 있는 상태에서:

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
3. 큐가 비면 전체 임베딩으로 DBSCAN 클러스터링을 갱신하고 10초 대기
4. 실패는 서버에 보고 (3회 누적 시 FAILED, 서버 재시작하면 리셋)

확인: `GET /api/v1/faces/clusters` — 클러스터별 얼굴 수 요약
