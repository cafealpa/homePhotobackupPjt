"""실제 JAR + SigLIP CPU + LanceDB + MCP 검증. 생성 이미지와 임시 DB만 사용한다.
usage: .venv-search/Scripts/python smoke_search.py --jar <bootJar> --java <java.exe>
"""
import argparse
import base64
from io import BytesIO
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

from PIL import Image, ImageDraw
import requests
import uvicorn
from search_service import Encoder, Store, Sync, create_app, ROOT
from face_search import FaceStore, FaceSync


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    token = "synthetic-search-smoke-token-0123456789"
    api_key = "synthetic-search-smoke-api-key"
    mcp_token = "synthetic-search-smoke-mcp-token-0123456789"
    with tempfile.TemporaryDirectory(prefix="homephoto-search-smoke-") as temp:
        root = Path(temp)
        base = f"http://127.0.0.1:{free_port()}"
        search_port = free_port()
        log = (root / "server.log").open("w", encoding="utf-8")
        command = [args.java, "-jar", str(Path(args.jar).resolve()), "--server.address=127.0.0.1",
                   "--server.port=" + base.rsplit(":", 1)[1], "--homephoto.storage-root=" + str(root / "photos"),
                   "--homephoto.api-key=" + api_key, "--homephoto.search.enabled=true",
                   "--homephoto.search.token=" + token, f"--homephoto.search.base-url=http://127.0.0.1:{search_port}",
                   "--homephoto.mcp.enabled=true", "--homephoto.mcp.token=" + mcp_token,
                   "--homephoto.mcp.base-url=" + base, "--homephoto.mcp.mode=local", "--logging.file.name=",
                   "--homephoto.caption.enabled=false"]
        process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        web = None
        web_thread = None
        session = requests.Session()
        session.trust_env = False
        try:
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError("server startup failed")
                try:
                    if session.get(base + "/api/v1/health", timeout=1).status_code == 200:
                        break
                except requests.RequestException:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("server startup timed out")
            headers = {"X-Api-Key": api_key}
            for i, color in enumerate(("red", "blue", "green")):
                image = Image.new("RGB", (400, 300), "white")
                ImageDraw.Draw(image).ellipse((60, 50, 340, 250), fill=color)
                data = BytesIO()
                image.save(data, "PNG")
                response = session.post(base + "/api/v1/assets", headers=headers,
                    files={"file": (f"2026090{i+1}_120000.png", data.getvalue(), "image/png")}, timeout=30)
                response.raise_for_status()
            encoder = Encoder(ROOT / "search-model")
            store = Store(root / "vectors", encoder.identity, encoder.dimension)
            sync = Sync(base, api_key, encoder, store)
            # 얼굴 검출 모델의 정확도가 아니라 저장된 다중 얼굴 벡터의 검색 연결을 검사한다.
            import numpy as np
            for _ in range(3):
                claimed = session.post(base + "/api/v1/internal/jobs/claim", headers=headers, json={"jobType": "FACE"})
                claimed.raise_for_status()
                job = claimed.json()
                faces = []
                for axis in (0, 1):
                    vector = np.zeros(512, dtype="<f4")
                    vector[axis] = 1
                    faces.append({"x": .1 + axis * .4, "y": .1, "w": .2, "h": .3,
                                  "embedding": base64.b64encode(vector.tobytes()).decode()})
                session.post(base + f"/api/v1/internal/jobs/{job['jobId']}/complete", headers=headers,
                             json={"faces": faces}).raise_for_status()
            face_store = FaceStore(root / "vectors")
            face_sync = FaceSync(base, api_key, face_store)
            face_sync.scan()
            # 썸네일 워커가 완료될 때까지 실제 동기화를 재시도한다.
            items = session.get(base + "/api/v1/internal/search/catalog", headers=headers).json()["items"]
            for item in items:
                for attempt in range(30):
                    try:
                        sync.sync_item(item)
                        break
                    except RuntimeError:
                        time.sleep(1)
                else:
                    raise RuntimeError("thumbnail/indexing failed")
            web = uvicorn.Server(uvicorn.Config(create_app(encoder, store, token, face_store=face_store), host="127.0.0.1", port=search_port,
                                                log_level="error", proxy_headers=False))
            web_thread = threading.Thread(target=web.run)
            web_thread.start()
            for _ in range(100):
                if web.started:
                    break
                time.sleep(.1)
            mcp_headers = {"Authorization": "Bearer " + mcp_token, "Accept": "application/json, text/event-stream",
                           "MCP-Protocol-Version": "2025-06-18"}
            def rpc(method, params):
                response = session.post(base + "/mcp", headers=mcp_headers,
                    json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=30)
                response.raise_for_status()
                return response.json()["result"]
            names = [tool["name"] for tool in rpc("tools/list", {})["tools"]]
            assert "search_photos_by_description" in names, names
            started = time.monotonic()
            result = rpc("tools/call", {"name": "search_photos_by_description", "arguments": {
                "query": "빨간 동그라미", "start_date": "2026-09-01", "end_date": "2026-09-30", "limit": 2}})
            assert not result.get("isError"), result
            assert len(result["structuredContent"]["items"]) == 2, result
            assert result["structuredContent"]["approximate"] is True
            first = result["structuredContent"]["items"][0]["id"]
            preview = result["_meta"]["previews"][str(first)]["thumbnailUrl"]
            assert session.get(preview).status_code == 200
            api = session.get(base + "/api/v1/photos/search", headers=headers, params={"query": "빨간 동그라미", "limit": 2})
            assert api.status_code == 200 and len(api.json()["items"]) == 2, api.text
            source_faces = session.get(base + f"/api/v1/assets/{items[0]['id']}/faces", headers=headers).json()
            assert len(source_faces) == 2, source_faces
            candidate_sets = []
            for source_face in source_faces:
                suggestions = session.get(base + f"/api/v1/faces/{source_face['faceId']}/similar", headers=headers)
                assert suggestions.status_code == 200, suggestions.text
                matches = suggestions.json()["matches"]
                assert len(matches) == 2, suggestions.text
                assert all(match["face"]["assetId"] != items[0]["id"] for match in matches)
                candidate_sets.append({match["face"]["faceId"] for match in matches})
            assert candidate_sets[0].isdisjoint(candidate_sets[1])
            print(json.dumps({"model": encoder.identity, "dimension": encoder.dimension, "indexed": len(items),
                "mcp_tools": names, "result_ids": [p["id"] for p in result["structuredContent"]["items"]],
                "mcp_seconds": round(time.monotonic() - started, 3), "preview": "200", "api": "200",
                "indexed_faces": 6, "distinct_face_candidates": True}, ensure_ascii=False))
        except Exception:
            log.flush()
            print((root / "server.log").read_text("utf-8", errors="replace")[-8000:])
            raise
        finally:
            if web:
                web.should_exit = True
            if web_thread:
                web_thread.join(30)
            process.terminate()
            process.wait(30)
            log.close()
            # LanceDB 파일 매핑이 임시 폴더 정리 전에 해제되도록 한다.
            web = web_thread = store = encoder = sync = face_store = face_sync = None
            import gc
            gc.collect()


if __name__ == "__main__":
    main()
