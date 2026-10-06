# 가족 문서 보관·검색

사진 메뉴의 **문서 보관**(`/documents.html`)에서 문서 판별·OCR·검색 반영 진행 상황을 확인한다. 장면 분석 화면에서도 이동할 수 있다. 기존 Kotlin/Spring 서버와 SQLite, E5-small INT8 384차원, Lucene HNSW를 사용하며 Node.js 서비스·새 모델·새 벡터 DB·FTS5는 추가하지 않는다.

## 사용 순서

1. 기존 방식으로 서버에 로그인한다. 장면 분석 화면에서 Gemini 모델과 키 파일을 설정한다. 문서 OCR은 장면분석 제공자 선택과 별개로 이 Gemini 모델·키를 사용한다.
2. **문서 보관 → 문서 분석 시작**을 누른다. 최초 설치 시 문서 OCR은 일시정지 상태다. 시작·정지 설정은 SQLite에 저장돼 재시작 후 유지된다.
3. 새 Gemini 장면분석 결과의 문서/불확실 후보는 자동으로 DOCUMENT 대기열에 들어간다. 기존 사진은 **기존 미분석 사진 등록**에서 1~500장씩, 최근 사진부터 추가한다. 기존 캡션을 지우거나 전체 라이브러리에 자동 유료 재분석을 실행하지 않는다. 일반 사진도 판별 호출이 발생한다.
4. 미분석·대기·분석 중·실패·확인 필요·검색 반영 대기/완료 건수를 확인한다. 화면은 3초마다 갱신한다. 문서 유형과 문서 날짜(촬영 날짜가 아님)로 범위를 좁힐 수 있다.
5. **내용 보기 / 분석**에서 사진과 OCR을 비교하고 **원본 보기**로 원본 파일을 연다. 잘못 판별된 사진은 일반 사진/미분석 목록에서도 다시 분석할 수 있다. 흐린 글씨·잘린 영역은 `확인 필요`로 표시된다.

일시정지는 진행 중인 한 사진의 저장 후 적용된다. 실패 목록의 마지막 오류는 DB에 남으며, 실패 재시도 버튼은 최대 100건을 등록한다. DOCUMENT의 FAILED는 서버 재시작만으로 재등록하지 않는다. 중단된 RUNNING 작업은 기존 복구 로직으로 재개한다. 재분석 실패 시 이전 성공 결과는 유지한다.

## 처리 구조

`CAPTION → 문서 판별 → DOCUMENT 작업 → 이미지 준비 → Gemini 구조화 OCR → SQLite → E5 청크 임베딩 → 기존 Lucene`

- 장면분석은 기존 768px JPEG/MEDIUM을 유지한다. 문서 OCR은 긴 변 최대 2,048px JPEG/HIGH, 최대 출력 16,384토큰으로 요청한다. 원본은 변경하지 않는다.
- ImageIO 디코딩은 축소 샘플링 후 크기 조정하며 EXIF 방향을 반영한다. HEIC 등은 기존 ffmpeg 실행기를 이용해 임시 JPEG로 변환하고 처리 후 지운다. ffmpeg 또는 코덱이 없으면 이미지 준비 단계 실패로 표시한다.
- Gemini HTTP 호출과 할당량 대기는 장면분석과 문서 OCR이 공유한다. 동시에 하나만 요청한다. 모델/키/할당량 오류는 일정 시간 대기하며 재시도 횟수를 소모하지 않는다. 잘못된 JSON·잘린 응답·문서의 빈 OCR은 성공으로 저장하지 않는다.
- 판별은 DOCUMENT / NOT_DOCUMENT / UNCERTAIN이다. 아직 판별하지 않은 사진은 UNKNOWN이다. 문서에 있는 글은 분석 대상이며 지시로 실행하지 않는다.
- `document_analysis`는 구조화 JSON, OCR, 제목·발행기관·날짜·유형·모델·분석 시각·검토 사유·revision을 저장한다. `search_text`는 추출 결과를 서버에서 결정적으로 조합한다.
- `document_search_chunks`는 성공적으로 검색 반영한 청크와 원본 revision을 저장한다. 원본 전체 OCR은 따로 보존한다. 청크는 제목 맥락과 `passage:`를 포함한 실제 토큰 수가 480 이하가 되도록 분할하고, 약 40토큰 이하의 중첩을 둔다. 512토큰 제한으로 뒷부분을 버리지 않는다.
- 기존 `CaptionTextEncoder` 세션을 공유한다. 별도 ONNX 세션을 추가하지 않는다. 한 인덱싱 작업에서 문서 변경은 최대 2건씩 처리한다.
- 기존 Lucene 디렉터리에 `document_vector` 필드를 추가한다. 기존 장면용 `vector`와 구분되므로 장면 인덱스 재구축 없이 확장한다. 문서의 모든 이전 청크는 함께 교체한다.
- `document_search_changes`는 판별/분석 변경·삭제·복구를 추적한다. Lucene commit 후 큐 버전이 같을 때만 청크와 indexed_revision을 갱신하고 큐를 지운다. 영구 삭제 시 OCR·JSON·청크를 지운다. 조회 시 최신 자산 상태와 문서 revision을 다시 확인한다.

## 검색

- LIKE: `search_text` 및 파일명에서 모든 검색 단어가 일치하는 후보 최대 200장. 제목에 검색 구문이 있는 문서를 우선하고 사진 ID로 동점을 정렬한다. `%`, `_`, 역슬래시는 일반 문자로 검색한다. 사용자 문자열은 SQL 매개변수로 전달한다.
- Vector: 기존 E5로 검색어를 인코딩하고 문서 벡터 상위 200개 청크를 검색한다. 문서별 최고 청크 순위로 중복 제거한다. 유형·날짜 필터는 Lucene 후보 추출 전에 적용한다.
- 기본 문서 코사인 하한은 `homephoto.caption-search.document-min-similarity=0.80`이다. 초기 기준이며 실제 가족 문서 평가로 조정해야 한다. 확률이나 OCR 신뢰도가 아니다.
- RRF: 두 문서 순위의 `1 / (60 + rank)`를 동일 가중치로 더한다. rank는 1부터 시작한다. 최종 40장씩 표시한다.
- 검색 응답의 `candidateLimited=true`와 `total`은 제한된 후보 집합의 수다. 전체 라이브러리의 일치 건수로 해석하지 않는다. 검색어 없는 목록의 total은 해당 필터의 전체 건수다.
- 모델이 없거나 인덱스 준비가 안 됐으면 LIKE 검색은 유지된다. FTS5가 없으므로 `%단어%` 검색은 문서 수와 본문 크기에 따라 느려질 수 있다.

## API와 코드

기존 `X-Api-Key` 또는 로그인 쿠키 인증이 필요하다. 공개 사진 API/MCP에 OCR을 추가하지 않는다.

| API | 용도 |
|---|---|
| `GET /api/v1/admin/documents/status` | 작업자·건수·검색 인덱스 상태 |
| `POST /api/v1/admin/documents/control` | `{"enabled":true}` 시작·정지 |
| `POST /api/v1/admin/documents/enqueue` | `{"mode":"missing","limit":100}` 또는 failed/reanalyze |
| `GET /api/v1/admin/documents` | q, filter, page, type, from, to |
| `GET /api/v1/admin/documents/{assetId}` | OCR·구조화 결과·모델·상태 |

`reanalyze`는 assetId가 필요하며 대기/진행 중인 작업은 덮어쓰지 않는다. 날짜는 YYYY-MM-DD다. 검색어는 최대 200자다.

- `document/DocumentWorker.kt`: 작업 실행, 원본 접근, OCR, 재시도.
- `document/DocumentAnalysis.kt`: JSON 계약·검증·search_text 생성.
- `document/DocumentRepository.kt`: SQLite 읽기·저장·변경 큐·단어 검색.
- `document/DocumentChunks.kt`: 토큰 예산에 맞춘 청크 분할.
- `document/DocumentController.kt`: 인증 범위 내 API와 RRF.
- `search/CaptionTextSearch.kt`, `CaptionVectorIndex.kt`: 공용 임베딩·인덱스.

## 검증과 제한

`server/gradlew.bat test`는 임시 SQLite/Lucene, 합성 이미지, 로컬 HTTP 모의 Gemini로 스키마·원자적 변경 확인·삭제 가시성·인증·필터·RRF·OCR 응답/이미지 크기를 검증한다.

`server/gradlew.bat captionSearchSmoke -PcaptionModelDir=<기존 모델 폴더>`는 실제 E5로 가상 장면 24개와 긴 가상 문서의 청크 인덱싱/검색을 검증한다. 개인 사진이나 Gemini API는 사용하지 않는다.

사진 한 장을 문서 한 항목으로 취급한다. PDF 업로드·여러 사진 묶기·OCR 수동 편집·문서 자동 회전 보정(기울기/원근)은 포함하지 않는다. EXIF 방향은 반영한다. 긴 영수증을 나누어 여러 번 OCR하는 처리는 아직 없으므로 작은 글씨/긴 문서는 실제 샘플 판독 확인이 필요하다. 실제 Gemini OCR 정확도, 가족 문서 검색 품질, Windows Server 8GB의 전체 부하 및 HEIC 변환은 운영 환경에서 별도 검증해야 한다.

## 배포·복구

기존 서버 배포 절차를 사용한다. 이번 소스 변경 자체는 운영 배포나 JAR 교체를 수행하지 않는다. 새 서버 시작 시 마이그레이션 7이 문서 테이블·트리거를 추가한다. 배포 전 기존 SQLite 백업 절차를 따른다. 모델 준비는 [설명 검색 안내](caption-text-search.md)를 참고한다.

문제 발생 시 먼저 문서 분석을 일시정지한다. 문서 기능을 중단해도 기존 사진/장면 결과는 유지된다. 인덱스 복구가 필요하면 서버를 중지한 상태에서 기존 caption-search 인덱스 디렉터리를 별도 위치에 보존한 뒤 재시작한다. 빈 인덱스는 SQLite 텍스트로 다시 채워지며 Gemini를 재호출하지 않는다. 이때 장면과 문서 의미 검색은 재구축 완료 전까지 제한되고 단어 검색은 유지된다. 문서 테이블과 마이그레이션 이력은 임의로 삭제하지 않는다.
