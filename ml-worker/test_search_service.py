import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from fastapi.testclient import TestClient
from PIL import Image
from io import BytesIO

from search_service import Store, SearchRequest, Sync, create_app


class SearchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name), "test-model-v1", 3)
        self.encoder = Mock(identity="test-model-v1")
        self.encoder.encode.return_value = [1., 0., 0.]

    def tearDown(self):
        # Windows 파일 매핑을 해제한 뒤 임시 디렉터리를 정리한다.
        self.store = None
        import gc
        gc.collect()
        self.temp.cleanup()

    def test_persistent_search_prefilters_dates_and_deletes(self):
        from datetime import date
        self.store.put(1, "a", date(2026, 9, 1).toordinal(), [0.9, 0.1, 0.])
        self.store.put(2, "b", date(2026, 10, 1).toordinal(), [1., 0., 0.])
        self.store.put(3, "c", date(2026, 9, 30).toordinal(), [0., 1., 0.])
        request = SearchRequest(query="음식", start_date="2026-09-01", end_date="2026-09-30", limit=1)
        self.assertEqual([1], self.store.search([1., 0., 0.], request)["ids"])
        reopened = Store(Path(self.temp.name), "test-model-v1", 3)
        self.assertEqual([1], reopened.search([1., 0., 0.], request)["ids"])
        del reopened
        self.store.delete(1)
        self.assertEqual([3], self.store.search([1., 0., 0.], request)["ids"])

    def test_ann_index_keeps_date_prefilter(self):
        import math
        from datetime import date
        day = date(2026, 9, 1).toordinal()
        rows = [{"id": i, "hash": str(i), "day": day + i % 2,
                 "vector": [math.cos(i / 100), math.sin(i / 100), 0.1]} for i in range(1, 601)]
        self.store.table.add(rows)
        self.store.optimize()
        self.assertEqual(600, self.store.last_indexed)
        result = self.store.search([1., 0., 0.], SearchRequest(query="음식", start_date="2026-09-01", end_date="2026-09-01"))
        self.assertEqual(12, len(result["ids"]))
        self.assertTrue(all(i % 2 == 0 for i in result["ids"]))

    def test_sync_reuses_vectors_updates_dates_and_removes_tombstones(self):
        sync = Sync("http://127.0.0.1:8080", "test-api", self.encoder, self.store)
        output = BytesIO()
        Image.new("RGB", (16, 16), "red").save(output, "JPEG")
        sync.session = Mock()
        sync.session.get.return_value = Mock(status_code=200, content=output.getvalue())
        item = {"id": 1, "hash": "hash", "taken_at": "2026-09-01T00:00:00", "active": True}
        sync.sync_item(item)
        sync.sync_item(item)
        self.encoder.encode.assert_called_once()
        sync.sync_item(item | {"taken_at": "2026-10-01T00:00:00"})
        self.encoder.encode.assert_called_once()
        sync.sync_item(item | {"active": False})
        self.assertIsNone(self.store.lookup(1))

    def test_authentication_validation_empty_index_and_model_separation(self):
        token = "test-local-search-token-0123456789"
        with TestClient(create_app(self.encoder, self.store, token)) as client:
            self.assertEqual(401, client.post("/search", json={"query": "음식"}).status_code)
            headers = {"Authorization": "Bearer " + token}
            self.assertEqual(403, client.post("/search", headers=headers | {"Origin": "https://evil.test"}, json={"query": "음식"}).status_code)
            for payload in ({"query": " "}, {"query": "음식", "limit": 201},
                            {"query": "음식", "start_date": "2026-09-01"},
                            {"query": "음식", "start_date": "2026-02-29", "end_date": "2026-03-01"},
                            {"query": "음식", "sql": "true"}):
                self.assertEqual(422, client.post("/search", headers=headers, json=payload).status_code)
            response = client.post("/search", headers=headers, json={"query": "음식"})
            self.assertEqual(200, response.status_code)
            self.assertEqual([], response.json()["ids"])
        self.store.put(1, "a", 1, [1., 0., 0.])
        other = Store(Path(self.temp.name), "test-model-v2", 3)
        self.assertIsNone(other.lookup(1))
        del other


if __name__ == "__main__":
    unittest.main()
