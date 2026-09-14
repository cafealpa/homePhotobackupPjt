"""CPU SigLIP 2 + 로컬 LanceDB. python search_service.py --download-model 후 실행.

모델 다운로드는 명시적인 준비 단계에서만 수행한다. 정상 서비스는 오프라인 모델을 사용한다.
사진·검색어를 외부 AI API에 전송하지 않는다. Spring과 loopback HTTP로만 통신한다.
"""
import argparse
from contextlib import asynccontextmanager
from datetime import date
from hashlib import sha256
from io import BytesIO
import json
import logging
import math
import os
from pathlib import Path
import secrets
import threading
import time
from urllib.parse import urlparse

import lancedb
from lancedb.index import IvfFlat
import numpy as np
import pyarrow as pa
import requests
from fastapi import FastAPI, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator
from PIL import Image
from face_search import FaceStore, FaceSync, FaceRequest

LOG = logging.getLogger("photo-search")
MODEL = "google/siglip2-base-patch16-224"
ROOT = Path(__file__).resolve().parent


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    query: str = Field(min_length=1, max_length=500)
    start_date: str | None = None
    end_date: str | None = None
    limit: int = Field(default=12, ge=1, le=200)

    @model_validator(mode="after")
    def validate_range(self):
        if not self.query.strip():
            raise ValueError("query cannot be blank")
        if (self.start_date is None) != (self.end_date is None):
            raise ValueError("both dates are required")
        if self.start_date is not None:
            start, end = date.fromisoformat(self.start_date), date.fromisoformat(self.end_date)
            if start.isoformat() != self.start_date or end.isoformat() != self.end_date or start > end:
                raise ValueError("invalid date range")
        return self


class Encoder:
    def __init__(self, path):
        import torch
        from transformers import AutoModel, AutoProcessor
        manifest = json.loads((path / "homephoto-model.json").read_text("utf-8"))
        if manifest["model"] != MODEL:
            raise ValueError("unsupported model")
        self.identity = MODEL + "@" + manifest["revision"] + ":preprocess-v1"
        torch.set_num_threads(max(1, int(os.getenv("HOMEPHOTO_SEARCH_THREADS", "2"))))
        self.torch = torch
        self.model = AutoModel.from_pretrained(path, local_files_only=True).to("cpu").eval()
        self.processor = AutoProcessor.from_pretrained(path, local_files_only=True, use_fast=False)
        self.dimension = self.model.config.vision_config.hidden_size
        self.lock = threading.Lock()

    def encode(self, *, image=None, text=None):
        with self.lock, self.torch.inference_mode():
            if image is not None:
                inputs = self.processor(images=image, return_tensors="pt")
                value = self.model.get_image_features(**inputs)
            else:
                inputs = self.processor(text=[text], padding="max_length", truncation=True,
                                        max_length=self.model.config.text_config.max_position_embeddings,
                                        return_tensors="pt")
                value = self.model.get_text_features(**inputs)
            vector = value[0].float().cpu().numpy()
            return (vector / np.linalg.norm(vector)).tolist()


class Store:
    def __init__(self, path, identity, dimension):
        self.identity = identity
        self.lock = threading.RLock()
        db = lancedb.connect(str(path))
        name = "photos_" + sha256(identity.encode()).hexdigest()[:20]
        schema = pa.schema([("id", pa.int64()), ("hash", pa.string()), ("day", pa.int32()),
                            ("vector", pa.list_(pa.float32(), dimension))])
        self.table = db.create_table(name, schema=schema, exist_ok=True)
        if self.table.schema != schema:
            raise ValueError("model/index schema mismatch")
        self.last_indexed = 0

    def lookup(self, photo_id):
        with self.lock:
            rows = self.table.search().where(f"id = {int(photo_id)}").limit(1).to_list()
            return rows[0] if rows else None

    def put(self, photo_id, digest, day, vector):
        row = {"id": int(photo_id), "hash": digest, "day": day, "vector": vector}
        with self.lock:
            self.table.merge_insert("id").when_matched_update_all().when_not_matched_insert_all().execute([row])

    def delete(self, photo_id):
        with self.lock:
            self.table.delete(f"id = {int(photo_id)}")

    def optimize(self):
        with self.lock:
            size = self.table.count_rows()
            if size >= 512 and abs(size - self.last_indexed) >= max(512, self.last_indexed // 5):
                self.table.create_index("vector", config=IvfFlat(distance_type="cosine",
                                        num_partitions=min(64, int(math.sqrt(size)))), replace=True)
                self.last_indexed = size
            self.table.optimize()

    def search(self, vector, request):
        with self.lock:
            count = self.table.count_rows()
            if not count:
                return {"ids": [], "indexed_photos": 0, "model": self.identity}
            query = self.table.search(vector).distance_type("cosine")
            if request.start_date:
                start, end = date.fromisoformat(request.start_date).toordinal(), date.fromisoformat(request.end_date).toordinal()
                query = query.where(f"day >= {start} AND day <= {end}", prefilter=True)
            rows = query.limit(request.limit).select(["id", "_distance"]).to_list()
            return {"ids": [row["id"] for row in rows], "indexed_photos": count, "model": self.identity}


class Sync:
    def __init__(self, server, api_key, encoder, store):
        uri = urlparse(server)
        if uri.scheme != "http" or uri.hostname != "127.0.0.1" or uri.path or uri.query or uri.fragment or uri.username:
            raise ValueError("HOMEPHOTO_SERVER must be a 127.0.0.1 HTTP origin")
        if not api_key:
            raise ValueError("HOMEPHOTO_API_KEY is required")
        self.server, self.encoder, self.store = server, encoder, store
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers["X-Api-Key"] = api_key
        self.stop = threading.Event()

    def sync_item(self, item):
        photo_id = int(item["id"])
        existing = self.store.lookup(photo_id)
        if not item["active"]:
            if existing:
                self.store.delete(photo_id)
            return
        # 미상 촬영일은 기간 검색에서 제외하되 전체 장면 검색에는 포함한다.
        day = date.fromisoformat(item["taken_at"][:10]).toordinal() if item["taken_at"] else 0
        if existing and existing["hash"] == item["hash"]:
            if existing["day"] != day:
                self.store.put(photo_id, item["hash"], day, existing["vector"])
            return
        response = self.session.get(f"{self.server}/api/v1/assets/{photo_id}/thumb?size=400", timeout=20, allow_redirects=False)
        if response.status_code != 200:
            raise RuntimeError(f"thumbnail HTTP {response.status_code}")
        with Image.open(BytesIO(response.content)) as image:
            vector = self.encoder.encode(image=image.convert("RGB"))
        self.store.put(photo_id, item["hash"], day, vector)
        LOG.info("사진 임베딩 저장: id=%s", photo_id)
        self.stop.wait(0.05)

    def run(self):
        while not self.stop.is_set():
            try:
                after = 0
                processed = failed = 0
                while not self.stop.is_set():
                    response = self.session.get(f"{self.server}/api/v1/internal/search/catalog", params={"afterId": after}, timeout=20, allow_redirects=False)
                    if response.status_code != 200:
                        raise RuntimeError(f"catalog HTTP {response.status_code}")
                    page = response.json()
                    for item in page["items"]:
                        if self.stop.is_set():
                            return
                        try:
                            self.sync_item(item)
                            processed += 1
                        except Exception:
                            failed += 1
                            LOG.warning("사진 분석 보류: id=%s (다음 순회에서 재시도)", item["id"], exc_info=True)
                    if page["next_id"] is None:
                        break
                    after = int(page["next_id"])
                if not self.stop.is_set():
                    self.store.optimize()
                    LOG.info("검색 동기화 완료: 확인=%s 보류=%s", processed, failed)
            except Exception:
                LOG.warning("검색 동기화 연결 대기", exc_info=True)
            self.stop.wait(300)


def create_app(encoder, store, token, sync=None, face_store=None, face_sync=None):
    if len(token) < 32 or any(c.isspace() for c in token):
        raise ValueError("HOMEPHOTO_SEARCH_TOKEN requires 32 non-whitespace characters")

    @asynccontextmanager
    async def lifespan(app):
        jobs = [job for job in (sync, face_sync) if job is not None]
        threads = [threading.Thread(target=job.run, daemon=True) for job in jobs]
        for thread in threads:
            thread.start()
        yield
        for job in jobs:
            job.stop.set()
        for thread in threads:
            thread.join(timeout=25)

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def boundary(request: Request, call_next):
        from starlette.responses import JSONResponse
        # 서비스는 loopback에만 바인딩하며 프록시를 통한 우회와 브라우저 요청도 받지 않는다.
        if request.headers.get("origin") or any(h in request.headers for h in ("forwarded", "x-forwarded-for", "x-forwarded-host")):
            return JSONResponse({"error": "forbidden"}, status_code=403)
        if not secrets.compare_digest(request.headers.get("authorization", "").encode(), ("Bearer " + token).encode()):
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        try:
            length = int(request.headers.get("content-length", "0"))
        except ValueError:
            return JSONResponse({"error": "invalid content length"}, status_code=400)
        if length > 8192:
            return JSONResponse({"error": "request too large"}, status_code=413)
        # chunked 요청에도 같은 한도를 적용한다.
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 8192:
                return JSONResponse({"error": "request too large"}, status_code=413)
        request._body = bytes(body)
        return await call_next(request)

    @app.get("/health")
    def health():
        return {"status": "ready", "model": encoder.identity}

    @app.post("/search")
    def search(request: SearchRequest):
        start = time.monotonic()
        result = store.search(encoder.encode(text=request.query), request)
        LOG.info("의미 검색 완료: 결과=%s 소요=%.3fs", len(result["ids"]), time.monotonic() - start)
        return result

    @app.post("/faces/search")
    def face_search(request: FaceRequest):
        if face_store is None:
            from fastapi import HTTPException
            raise HTTPException(status_code=503, detail="face index unavailable")
        return face_store.search(request)

    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download-model", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    path = Path(os.getenv("HOMEPHOTO_SEARCH_MODEL", str(ROOT / "search-model")))
    if args.download_model:
        from huggingface_hub import HfApi, snapshot_download
        revision = HfApi().model_info(MODEL).sha
        snapshot_download(MODEL, revision=revision, local_dir=path,
                          allow_patterns=["*.json", "*.safetensors", "*.model", "*.txt"])
        (path / "homephoto-model.json").write_text(json.dumps({"model": MODEL, "revision": revision}), "utf-8")
        LOG.info("모델 준비 완료: %s@%s", MODEL, revision)
        return
    token = os.environ["HOMEPHOTO_SEARCH_TOKEN"]
    encoder = Encoder(path)
    store = Store(Path(os.getenv("HOMEPHOTO_SEARCH_DATA", str(ROOT / "search-data"))), encoder.identity, encoder.dimension)
    sync = Sync(os.getenv("HOMEPHOTO_SERVER", "http://127.0.0.1:8080"), os.environ["HOMEPHOTO_API_KEY"], encoder, store)
    face_store = FaceStore(Path(os.getenv("HOMEPHOTO_SEARCH_DATA", str(ROOT / "search-data"))))
    face_sync = FaceSync(sync.server, os.environ["HOMEPHOTO_API_KEY"], face_store)
    import uvicorn
    uvicorn.run(create_app(encoder, store, token, sync, face_store, face_sync), host="127.0.0.1",
                port=int(os.getenv("HOMEPHOTO_SEARCH_PORT", "18082")), proxy_headers=False, access_log=False,
                limit_concurrency=8)


if __name__ == "__main__":
    main()
