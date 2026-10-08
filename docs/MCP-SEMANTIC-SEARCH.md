# 로컬 사진 의미 검색 (JVM SigLIP 2 + Lucene)

사진과 검색어를 외부 AI에 보내지 않고 서버 JVM에서 임베딩과 검색을 수행한다.
[모델·인덱스·설치·검증 안내](JVM-VECTOR-SEARCH.md)를 참고한다.

## API와 MCP

- `GET /api/v1/photos/search?query=바닷가&limit=12`
- 선택 조건: `date=YYYY-MM-DD` 또는 `start_date`와 `end_date` 쌍. 동시에 사용하지 않는다.
- `limit`: 1~24. 응답: `items`, `indexed_photos`, `model`, `approximate=true`.
- MCP `search_photos_by_description`도 같은 JVM 검색을 호출한다.
- 기존 API 키/세션, MCP OAuth 인증 경계를 유지한다. 별도 검색 토큰과 loopback 서비스는 없다.
- 내부 catalog API는 호환성을 위해 남아 있으나 JVM 인덱싱은 DB와 썸네일을 직접 읽는다.
- 준비되지 않은 검색은 503으로 응답한다. 저장된 사진 수는 전체 인덱싱 완료율이 아니다.
- 검색 결과의 현재 삭제·영구 삭제·제외 출처·사진 종류·날짜·파일 해시를 DB에서 재검사한다.

E5 설명 검색과 SigLIP 이미지 검색은 모델 공간이 달라 인덱스를 공유하지 않는다.
