"""얼굴 인식 워커.

서버의 internal API에서 FACE 작업을 클레임해 InsightFace(buffalo_l)로
얼굴 감지·임베딩을 수행하고 결과를 제출한다. 큐가 비면 클러스터링을
갱신한 뒤 대기한다. DB에 직접 접근하지 않으므로 어느 머신에서든 실행 가능.

사용법:
    python worker.py                  (개발: .venv)
    homephoto-ml-worker.exe           (배포: build-worker.bat 으로 만든 단일 실행 파일, 모델 동봉)
환경변수:
    HOMEPHOTO_SERVER   (기본 http://localhost:8080)
    HOMEPHOTO_API_KEY  (기본 dev-key-change-me)
    HOMEPHOTO_CLUSTER_EPS  DBSCAN cosine distance 임계값 (기본 0.45)
"""

import base64
import logging
import os
import sys
import time

import cv2
import numpy as np
import requests

SERVER = os.environ.get("HOMEPHOTO_SERVER", "http://localhost:8080").rstrip("/")
API_KEY = os.environ.get("HOMEPHOTO_API_KEY", "dev-key-change-me")
CLUSTER_EPS = float(os.environ.get("HOMEPHOTO_CLUSTER_EPS", "0.45"))
HEADERS = {"X-Api-Key": API_KEY}
IDLE_SLEEP_SEC = 10

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("face-worker")


def model_root():
    """InsightFace 모델 루트. 단일 실행 파일(PyInstaller)이면 동봉한 models/ 를 쓰고,
    아니면 기본값(~/.insightface, 없으면 자동 다운로드)."""
    if getattr(sys, "frozen", False):
        return sys._MEIPASS  # noqa: SLF001 — PyInstaller 가 풀어놓은 임시 폴더
    return "~/.insightface"


def create_face_app():
    from insightface.app import FaceAnalysis

    # detection(det_10g) + recognition(w600k_r50)만 쓴다 — 나머지(3D 랜드마크·성별나이)는 로드 생략
    app = FaceAnalysis(
        name="buffalo_l",
        root=model_root(),
        allowed_modules=["detection", "recognition"],
        providers=["CPUExecutionProvider"],
    )
    app.prepare(ctx_id=-1, det_size=(640, 640))
    return app


def claim_job():
    r = requests.post(
        f"{SERVER}/api/v1/internal/jobs/claim",
        json={"jobType": "FACE"},
        headers=HEADERS,
        timeout=30,
    )
    if r.status_code == 204:
        return None
    r.raise_for_status()
    return r.json()


def process_job(face_app, job):
    asset_id = job["assetId"]
    r = requests.get(
        f"{SERVER}/api/v1/assets/{asset_id}/file", headers=HEADERS, timeout=300
    )
    r.raise_for_status()

    img = cv2.imdecode(np.frombuffer(r.content, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("image decode failed (unsupported format?)")

    height, width = img.shape[:2]
    faces = []
    for f in face_app.get(img):
        x1, y1, x2, y2 = (float(v) for v in f.bbox)
        emb = f.normed_embedding.astype(np.float32)
        faces.append(
            {
                "x": max(0.0, x1 / width),
                "y": max(0.0, y1 / height),
                "w": min(1.0, (x2 - x1) / width),
                "h": min(1.0, (y2 - y1) / height),
                "embedding": base64.b64encode(emb.tobytes()).decode(),
            }
        )

    requests.post(
        f"{SERVER}/api/v1/internal/jobs/{job['jobId']}/complete",
        json={"faces": faces},
        headers=HEADERS,
        timeout=60,
    ).raise_for_status()
    return len(faces)


def report_fail(job, error):
    try:
        requests.post(
            f"{SERVER}/api/v1/internal/jobs/{job['jobId']}/fail",
            json={"error": str(error)[:500]},
            headers=HEADERS,
            timeout=30,
        )
    except Exception:
        log.exception("failed to report job failure")


def dbscan_cosine(matrix, eps, min_samples=2, block=1024):
    """DBSCAN(metric=cosine) — sklearn 과 같은 결과를 내는 numpy 구현.
    scikit-learn/scipy(~160MB)를 실행 파일에 동봉하지 않기 위해 직접 구현했다.
    거리 행렬을 한 번에 만들지 않고 block 행씩 계산해 얼굴 수만 개에서도 메모리를 아낀다.
    반환: 각 행의 클러스터 번호 (노이즈는 -1)."""
    n = len(matrix)
    normed = matrix / np.maximum(np.linalg.norm(matrix, axis=1, keepdims=True), 1e-12)
    neighbors = []
    for start in range(0, n, block):
        dist = 1.0 - normed[start : start + block] @ normed.T
        for row in dist:
            neighbors.append(np.flatnonzero(row <= eps))  # 자기 자신 포함 (sklearn 과 동일)
    core = np.fromiter((len(nb) >= min_samples for nb in neighbors), bool, n)
    labels = np.full(n, -1, dtype=np.int64)
    cluster = 0
    for i in range(n):
        if labels[i] != -1 or not core[i]:
            continue
        labels[i] = cluster
        stack = [i]
        while stack:
            j = stack.pop()
            for k in neighbors[j]:
                if labels[k] == -1:
                    labels[k] = cluster
                    if core[k]:
                        stack.append(k)
        cluster += 1
    return labels


def recluster():
    """전체 임베딩을 받아 DBSCAN으로 인물 클러스터를 다시 계산한다."""
    r = requests.get(f"{SERVER}/api/v1/internal/faces", headers=HEADERS, timeout=300)
    r.raise_for_status()
    rows = r.json()
    if len(rows) < 2:
        return 0

    matrix = np.stack(
        [np.frombuffer(base64.b64decode(row["embedding"]), np.float32) for row in rows]
    )
    labels = dbscan_cosine(matrix, eps=CLUSTER_EPS, min_samples=2)
    assignments = {
        str(row["id"]): int(label)
        for row, label in zip(rows, labels)
        if label >= 0  # -1 = 노이즈(단독 얼굴)는 클러스터 없음
    }
    requests.post(
        f"{SERVER}/api/v1/internal/faces/clusters",
        json={"assignments": assignments},
        headers=HEADERS,
        timeout=120,
    ).raise_for_status()
    clusters = len(set(assignments.values()))
    log.info("reclustered: %d faces -> %d clusters", len(rows), clusters)
    return clusters


def main():
    log.info("loading InsightFace (buffalo_l, CPU)... 최초 실행 시 모델 다운로드로 수 분 걸릴 수 있음")
    face_app = create_face_app()
    log.info("model ready. server=%s", SERVER)

    processed_since_cluster = 0
    while True:
        try:
            job = claim_job()
        except Exception as e:
            log.warning("server unreachable: %s — %ds 후 재시도", e, IDLE_SLEEP_SEC)
            time.sleep(IDLE_SLEEP_SEC)
            continue

        if job is None:
            if processed_since_cluster > 0:
                try:
                    recluster()
                except Exception:
                    log.exception("recluster failed")
                processed_since_cluster = 0
            time.sleep(IDLE_SLEEP_SEC)
            continue

        try:
            count = process_job(face_app, job)
            processed_since_cluster += 1
            log.info("job %s (asset %s): %d face(s)", job["jobId"], job["assetId"], count)
        except Exception as e:
            log.warning("job %s failed: %s", job["jobId"], e)
            report_fail(job, e)


if __name__ == "__main__":
    main()
