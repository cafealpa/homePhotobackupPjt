import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

import numpy as np
from fastapi.testclient import TestClient
from face_search import FaceStore, FaceSync, FaceRequest, MODEL, decode_vector
from search_service import create_app


def vector(axis):
    value = np.zeros(512, dtype="<f4")
    value[axis] = 1
    return base64.b64encode(value.tobytes()).decode()


def face(face_id, asset_id, axis=0, active=True):
    return {"face_id": face_id, "asset_id": asset_id, "active": active, "embedding": vector(axis)}


class FaceSearchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = FaceStore(Path(self.temp.name))

    def tearDown(self):
        self.store = None
        import gc
        gc.collect()
        self.temp.cleanup()

    def query(self, axis=0):
        return self.store.search(FaceRequest(model=MODEL, embedding=vector(axis), source_asset_id=1))

    def test_multiple_faces_per_photo_stay_separate_and_companions_are_excluded(self):
        self.store.sync_page([face(11, 1, 0), face(12, 1, 1), face(13, 1, 2),
                              face(21, 2, 0), face(22, 2, 1), face(23, 2, 2), face(31, 3, 0)])
        self.assertEqual(7, self.query()["indexed_faces"])
        self.assertEqual({21, 31}, {m["face_id"] for m in self.query(0)["matches"]})
        self.assertEqual([22], [m["face_id"] for m in self.query(1)["matches"]])
        self.assertEqual([23], [m["face_id"] for m in self.query(2)["matches"]])

    def test_hidden_removed_and_reused_face_ids_update_without_duplicate_rows(self):
        self.store.sync_page([face(21, 2), face(22, 2, 1), face(31, 3)])
        self.store.sync_page([face(21, 2, active=False)])
        self.assertEqual([31], [m["face_id"] for m in self.query()["matches"]])
        self.store.sync_page([face(31, 4, 1)])
        self.assertEqual([], self.query()["matches"])
        self.store.complete_scan({31})
        self.assertEqual([31], [m["face_id"] for m in self.query(1)["matches"]])
        self.assertEqual(1, self.query()["indexed_faces"])

    def test_failed_scan_does_not_prune_old_index_and_successful_scan_does(self):
        self.store.sync_page([face(21, 2), face(31, 3)])
        sync = FaceSync("http://127.0.0.1:8080", "test-api-key", self.store)
        sync.session = Mock()
        sync.session.get.side_effect = [Mock(status_code=200, json=lambda: {"model": MODEL, "items": [face(21, 2)], "next_id": 21}),
                                        Mock(status_code=503)]
        with self.assertRaises(RuntimeError):
            sync.scan()
        self.assertEqual(2, self.query()["indexed_faces"])
        sync.session.get.side_effect = None
        sync.session.get.return_value = Mock(status_code=200, json=lambda: {"model": MODEL, "items": [face(21, 2)], "next_id": None})
        sync.scan()
        self.assertEqual(1, self.query()["indexed_faces"])

    def test_model_validation_auth_and_saved_index(self):
        self.store.sync_page([face(21, 2)])
        other = FaceStore(Path(self.temp.name))
        self.assertEqual(1, other.table.count_rows())
        del other
        token = "face-search-test-token-0123456789"
        with TestClient(create_app(Mock(identity="scene"), Mock(), token, face_store=self.store)) as client:
            payload = {"model": MODEL, "embedding": vector(0), "source_asset_id": 1}
            self.assertEqual(401, client.post("/faces/search", json=payload).status_code)
            headers = {"Authorization": "Bearer " + token}
            self.assertEqual(200, client.post("/faces/search", headers=headers, json=payload).status_code)
            self.assertEqual(422, client.post("/faces/search", headers=headers, json=payload | {"model": "siglip"}).status_code)
            self.assertEqual(422, client.post("/faces/search", headers=headers, json=payload | {"embedding": "bad"}).status_code)
            self.assertEqual(422, client.post("/faces/search", headers=headers, json=payload | {"min_similarity": -1.0}).status_code)
        with self.assertRaises(ValueError):
            decode_vector(base64.b64encode(np.zeros(512, dtype="<f4").tobytes()).decode())


if __name__ == "__main__":
    unittest.main()
