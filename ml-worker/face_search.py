"""SQLite의 기존 얼굴 벡터를 복제한 검색 인덱스. 사진 하나에 여러 face_id를 보존한다."""
import base64
from hashlib import sha256
import logging
import math
import threading

import lancedb
from lancedb.index import IvfFlat
import numpy as np
import pyarrow as pa
from pydantic import BaseModel, ConfigDict, Field, model_validator
import requests

MODEL = "legacy-insightface-buffalo_l-512-v1"
LOG = logging.getLogger("face-search")


def decode_vector(encoded):
    raw = base64.b64decode(encoded, validate=True)
    vector = np.frombuffer(raw, dtype="<f4")
    if len(vector) != 512 or not np.isfinite(vector).all():
        raise ValueError("face vector must contain 512 finite float32 values")
    norm = np.linalg.norm(vector.astype(np.float64))
    if norm <= 1e-12:
        raise ValueError("face vector cannot be zero")
    return (vector / norm).astype(np.float32).tolist(), sha256(raw).hexdigest()


class FaceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    model: str
    embedding: str = Field(max_length=2800)
    source_asset_id: int = Field(gt=0)
    limit: int = Field(default=100, ge=1, le=200)
    min_similarity: float = Field(default=0.55, ge=0, le=1, allow_inf_nan=False)

    @model_validator(mode="after")
    def validate_vector(self):
        if self.model != MODEL:
            raise ValueError("face model mismatch")
        decode_vector(self.embedding)
        return self


class FaceStore:
    def __init__(self, path):
        self.lock = threading.RLock()
        schema = pa.schema([("face_id", pa.int64()), ("asset_id", pa.int64()),
                            ("fingerprint", pa.string()), ("vector", pa.list_(pa.float32(), 512))])
        self.table = lancedb.connect(str(path)).create_table("faces_" + sha256(MODEL.encode()).hexdigest()[:20],
                                                           schema=schema, exist_ok=True)
        if self.table.schema != schema:
            raise ValueError("face index schema mismatch")
        self.last_indexed = 0

    def sync_page(self, items):
        ids = [int(item["face_id"]) for item in items]
        if not ids:
            return
        with self.lock:
            old = {row["face_id"]: row for row in self.table.search()
                   .where("face_id IN (" + ",".join(map(str, ids)) + ")")
                   .select(["face_id", "asset_id", "fingerprint"]).limit(len(ids)).to_list()}
            updates = []
            for item in items:
                face_id, asset_id = int(item["face_id"]), int(item["asset_id"])
                if not item["active"]:
                    if face_id in old:
                        self.table.delete(f"face_id = {face_id}")
                    continue
                try:
                    vector, digest = decode_vector(item["embedding"])
                except (ValueError, TypeError):
                    # 잘못된 기존 벡터 하나가 전체 얼굴 동기화를 막지 않도록 한다.
                    LOG.warning("유효하지 않은 얼굴 벡터 제외: face_id=%s", face_id)
                    if face_id in old:
                        self.table.delete(f"face_id = {face_id}")
                    continue
                if face_id in old and old[face_id]["fingerprint"] == digest and old[face_id]["asset_id"] == asset_id:
                    continue
                updates.append({"face_id": face_id, "asset_id": asset_id, "fingerprint": digest, "vector": vector})
            if updates:
                self.table.merge_insert("face_id").when_matched_update_all().when_not_matched_insert_all().execute(updates)

    def complete_scan(self, seen):
        """완료된 목록에서 사라진 얼굴만 제거. 중단/실패한 순회에서는 호출하지 않는다."""
        with self.lock:
            missing = [row["face_id"] for row in self.table.search().select(["face_id"]).limit(None).to_list()
                       if row["face_id"] not in seen]
            for start in range(0, len(missing), 100):
                self.table.delete("face_id IN (" + ",".join(map(str, missing[start:start + 100])) + ")")
            count = self.table.count_rows()
            if count >= 512 and abs(count - self.last_indexed) >= max(512, self.last_indexed // 5):
                self.table.create_index("vector", config=IvfFlat(distance_type="cosine",
                    num_partitions=min(64, int(math.sqrt(count)))), replace=True)
                self.last_indexed = count
            self.table.optimize()

    def search(self, request):
        vector, _ = decode_vector(request.embedding)
        with self.lock:
            count = self.table.count_rows()
            if count == 0:
                return {"model": MODEL, "matches": [], "indexed_faces": 0}
            # 같은 사진의 동반자를 동일 인물 후보로 제안하지 않는다.
            rows = self.table.search(vector).distance_type("cosine").where(
                f"asset_id != {request.source_asset_id}", prefilter=True).limit(request.limit)
            matches = []
            for row in rows.select(["face_id", "asset_id", "fingerprint", "_distance"]).to_list():
                similarity = max(-1., min(1., 1. - float(row["_distance"])))
                if similarity >= request.min_similarity:
                    matches.append({"face_id": row["face_id"], "asset_id": row["asset_id"],
                                    "fingerprint": row["fingerprint"], "similarity": similarity})
            return {"model": MODEL, "matches": matches, "indexed_faces": count}


class FaceSync:
    def __init__(self, server, api_key, store):
        self.server, self.store = server, store
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers["X-Api-Key"] = api_key
        self.stop = threading.Event()

    def scan(self):
        after, seen = 0, set()
        while not self.stop.is_set():
            response = self.session.get(self.server + "/api/v1/internal/search/faces", params={"afterId": after},
                                        timeout=20, allow_redirects=False)
            if response.status_code != 200:
                raise RuntimeError(f"face catalog HTTP {response.status_code}")
            page = response.json()
            if page["model"] != MODEL:
                raise ValueError("face model mismatch")
            self.store.sync_page(page["items"])
            seen.update(int(item["face_id"]) for item in page["items"])
            if page["next_id"] is None:
                if not self.stop.is_set():
                    self.store.complete_scan(seen)
                    LOG.info("얼굴 인덱스 동기화 완료: 확인=%s", len(seen))
                return
            next_id = int(page["next_id"])
            if next_id <= after:
                raise ValueError("face catalog cursor did not advance")
            after = next_id

    def run(self):
        while not self.stop.is_set():
            try:
                self.scan()
            except Exception:
                LOG.warning("얼굴 인덱스 동기화 보류 (기존 인덱스 유지)", exc_info=True)
            self.stop.wait(300)
