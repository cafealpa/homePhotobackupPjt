"""Boot a built JAR against an isolated temporary DB; synthetic thumbnails only.

Usage: python scripts/smoke-jvm-search.py JAVA_EXE SERVER_JAR SIGLIP_MODEL_DIR
Only Python stdlib is needed for this development check, never for server operation.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import struct
import subprocess
import sys
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.parse import urlencode

java, jar, model = [str(Path(x).resolve()) for x in sys.argv[1:4]]
root = Path(tempfile.mkdtemp(prefix="homephoto-jvm-search-"))
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
base = f"http://127.0.0.1:{port}"
key = "synthetic-search-smoke"


def request(path, action=None):
    headers = {"X-Api-Key": key}
    if action:
        headers["X-HomePhoto-Action"] = action
    req = Request(base + path, headers=headers, method="POST" if action else "GET")
    with urlopen(req, timeout=30) as response:
        return json.load(response)


def wait_ready():
    deadline = time.monotonic() + 90
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Server exited; see {root / 'server.log'}")
        try:
            last = request("/api/v1/admin/search-service")
            if last["state"] == "ready" and last["indexedPhotos"] == 3 and last["indexedFaces"] == 3:
                return last
            if last["state"] in ("failed", "partial", "model_missing"):
                raise AssertionError(last)
        except (OSError, ValueError):
            pass
        time.sleep(.2)
    raise AssertionError(f"Search timeout: {last}")


log = (root / "server.log").open("w", encoding="utf-8")
process = subprocess.Popen([java, "-Xmx2g", "-jar", jar,
    "--spring.config.location=classpath:/application.yml", f"--server.port={port}", "--server.address=127.0.0.1",
    f"--homephoto.storage-root={root / 'data'}", f"--spring.datasource.url=jdbc:sqlite:{root / 'photos.db'}",
    f"--homephoto.api-key={key}", "--homephoto.face.enabled=false", "--homephoto.caption.enabled=false",
    "--homephoto.caption-search.enabled=false", "--homephoto.search.enabled=true", f"--homephoto.search.model-dir={model}",
    "--homephoto.mcp.enabled=false", "--homephoto.google-photos.enabled=false", "--logging.file.name="],
    cwd=root, stdout=log, stderr=subprocess.STDOUT,
    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
print(f"Isolated JVM smoke: {root}, port={port}", flush=True)
try:
    deadline = time.monotonic() + 60
    while True:
        try:
            request("/api/v1/health")
            break
        except OSError:
            if process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError(f"Startup failed; see {root / 'server.log'}")
            time.sleep(.2)
    request("/api/v1/admin/search-service/stop", "search-service")
    while request("/api/v1/admin/search-service")["state"] == "stopping":
        time.sleep(.2)
    # Tiny BMPs are read by ImageIO by magic bytes, even at the normal .jpg thumbnail path.
    with sqlite3.connect(root / "photos.db") as db:
        for n in range(1, 4):
            digest = hashlib.sha256(f"synthetic-{n}".encode()).hexdigest()
            path = root / "data" / "thumbs" / digest[:2] / digest[2:4] / f"{digest}_400.jpg"
            path.parent.mkdir(parents=True, exist_ok=True)
            w, h = 64, 48
            pixels = bytes(c for y in range(h) for x in range(w) for c in ((x*n*4)%256, (y*5)%256, n*60))
            path.write_bytes(b"BM" + struct.pack("<IHHI", 54+len(pixels), 0, 0, 54) +
                struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, len(pixels), 0, 0, 0, 0) + pixels)
            db.execute("""INSERT INTO assets(id,hash,media_type,original_path,original_filename,file_size,
                taken_at,taken_at_source,year_month,created_at) VALUES(?,?, 'PHOTO','unused','synthetic.bmp',1,
                '2026-10-08T12:00:00','EXIF','2026-10','2026-10-08T12:00:00')""", (n, digest))
            db.execute("""INSERT INTO faces(id,asset_id,bbox_x,bbox_y,bbox_w,bbox_h,embedding)
                VALUES(?,?,.1,.1,.2,.2,?)""", (n, n, struct.pack("<512f", 1, *([0]*511))))
    request("/api/v1/admin/search-service/start", "search-service")
    status = wait_ready()
    result = request("/api/v1/photos/search?" + urlencode({"query": "붉은 사진", "limit": 3}))
    assert len(result["items"]) == 3 and result["indexed_photos"] == 3, result
    faces = request("/api/v1/faces/1/similar")
    assert {hit["face"]["faceId"] for hit in faces["matches"]} == {2, 3}, faces
    assert faces["indexedFaces"] == 3, faces
    request("/api/v1/admin/search-service/stop", "search-service")
    assert len(request("/api/v1/photos/search?query=photo")["items"]) == 3
    with sqlite3.connect(root / "photos.db") as db:
        db.execute("UPDATE assets SET deleted_at='2026-10-08T13:00:00' WHERE id=2")
    assert {item["id"] for item in request("/api/v1/photos/search?query=photo")["items"]} == {1, 3}
    assert {hit["face"]["faceId"] for hit in request("/api/v1/faces/1/similar")["matches"]} == {3}
    request("/api/v1/admin/maintenance/shutdown", "maintenance")
    assert process.wait(timeout=45) == 0
    print("PASS: packaged JVM model load, photo/face HTTP search, paused search, deletion recheck, safe shutdown", flush=True)
finally:
    if process.poll() is None:
        try:
            request("/api/v1/admin/maintenance/shutdown", "maintenance")
            process.wait(timeout=45)
        except Exception:
            process.terminate()
            process.wait(timeout=15)
    log.close()
    print(f"Smoke evidence retained: {root}", flush=True)
