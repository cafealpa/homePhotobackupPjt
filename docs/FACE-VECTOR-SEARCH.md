# JVM 얼굴 벡터 검색과 인물 후보

SQLite의 기존 `faces.embedding`을 JVM에서 읽어 Lucene HNSW 인덱스로 동기화한다.
[모델·인덱스 수명주기 및 전환 안내](JVM-VECTOR-SEARCH.md)를 참고한다.

- 512차원 little-endian float32 벡터를 L2 정규화한다. 빈 값·NaN·차원 오류는 제외하고 보고한다.
- `face_id` 단위로 저장하여 한 사진의 여러 얼굴을 보존한다.
- `GET /api/v1/assets/{assetId}/faces`: 얼굴별 위치와 현재 인물 연결. 벡터는 노출하지 않는다.
- `GET /api/v1/faces/{faceId}/similar?limit=20&minSimilarity=0.55`: 얼굴/인물 후보.
- 같은 사진을 사전 필터로 제외하고 코사인 유사도 임계값을 적용한다.
- 조회 시 DB의 숨김·삭제·출처·소속 사진·벡터 fingerprint를 재검사한다.
- 후보를 조회해도 이름/묶음을 자동 변경하지 않는다. 유사도는 동일 인물 확률이 아니다.
- `GET /api/v1/internal/search/faces`는 인증된 기존 호환 API로 유지한다.
- 사진 SigLIP 모델이 없어도 기존 얼굴 벡터 인덱싱과 검색은 가능하다.

검증: `FaceVectorSearchTest`, `LocalVectorIndexTest`, `PhotoSearchProcessTest`에서
실제 Lucene과 임시 SQLite를 이용해 여러 얼굴·숨김·삭제·오래된 벡터·인증·재시도를 확인한다.
