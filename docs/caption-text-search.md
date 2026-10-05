# JVM 설명 의미 검색

운영 적용 순서와 롤백은 [운영 배포 가이드](caption-text-search-deployment.md)를 참고한다.

장면 검색은 SQLite `captions.caption + tags`를 JVM 안에서 임베딩한다. Python, 사진 파일 읽기, Gemini 호출은 필요하지 않다. 기존 사진 전체의 이미지 의미 검색과 얼굴 유사검색 API는 아직 Python 경로를 유지하며, 이번 변경은 그 기능을 삭제하지 않는다.

## 모델과 라이선스

- 원 모델: https://huggingface.co/intfloat/multilingual-e5-small (MIT, 한국어 포함 다국어)
- ONNX 변환 배포: https://huggingface.co/Xenova/multilingual-e5-small
- 고정 리비전: `761b726dd34fb83930e26aab4e9ac3899aa1fa78`
- `model_quantized.onnx`: 118,308,185 bytes. SHA-256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`
- `tokenizer.json`: 17,082,730 bytes. SHA-256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`
- 합계 135,390,915 bytes (약 129.1 MiB). 모델을 JAR/Git에 포함하지 않는다.
- 384차원, 최대 512토큰, attention-mask mean pooling + L2 정규화. 검색어는 `query: `, 설명은 `passage: ` 접두사를 붙인다.
- DJL HuggingFace Tokenizers 0.34.0 / Apache 2.0, Lucene Core 9.12.3 / Apache 2.0, 기존 ONNX Runtime / MIT. Windows 토크나이저 네이티브 DLL은 Maven JAR에서 추출하며 실행 시 다운로드하지 않는다. DJL offline 및 telemetry opt-out 적용.

## 준비와 배포

1. 저장소에서 `powershell -NoProfile -File ./prepare-caption-model.ps1` 실행. 기본 출력은 `deploy/models/caption-e5`. 유효한 기존 파일은 다시 받지 않는다. 고정 URL 두 파일만 받고 체크섬을 검증한다. 키와 사용자 사진은 읽지 않는다.
2. `build-jar.ps1`은 테스트 후 배포 JAR와 체크섬을 준비한다. 모델 준비는 명시적인 별도 단계이며 서버 시작 시 자동 다운로드하지 않는다.
3. 서버 중지/교체/재시작은 운영자가 진행한다. 새 서버 JAR와 기존 face-runtime JAR 및 `models/caption-e5` 폴더를 서버 실행 폴더에 둔다. 다른 위치는 `homephoto.caption-search.model-dir` 절대경로로 지정한다.
4. 시작 후 약 15초 뒤 백그라운드 인덱싱 시작. 장면 분석 화면에 JVM 설명 검색의 준비 상태와 인덱스 건수가 표시된다. 모델 누락·체크섬 오류 시 단어 검색은 계속 동작한다. 기존 Python 검색 설정이나 시작 버튼은 필요하지 않다.

기본 설정: `homephoto.caption-search.enabled=true`, `model-dir=models/caption-e5`. 인덱스는 `storageRoot/caption-search/<model-id>`이며 `index-dir`로 별도 루트를 지정할 수 있다. 개인 설명이 담긴 인덱스를 Git에 넣지 않는다. 배포 모델 폴더는 .gitignore에 제외되어 있다. 운영 폴더가 저장소 밖이면 별도 Git 관리 대상이 아니다.

## 증분 처리와 복구

SQLite trigger가 설명 생성·수정·삭제, 사진 삭제/복구를 `caption_search_changes` 큐에 기록한다. 기존 데이터는 새 인덱스 최초 생성 시 한 번 등록한다. 한 작업자는 최대 32개를 처리하며 다음 tick과 겹치지 않는다. Lucene commit 후 큐 버전이 동일한 항목만 지운다. 중단된 작업은 재처리한다. 의미 후보 캐시를 갱신할 때 현재 SQLite의 삭제 상태와 설명 fingerprint를 다시 확인한다. 캐시는 최대 30초이고, 화면 조회에서는 삭제 상태를 항상 다시 확인한다. 검색 과정에서 읽는 최대 후보는 200개이고 기존 SQL 페이지 나누기와 단어 검색 결과 결합을 유지한다.

인덱스 경로의 모델 ID는 모델 리비전·양자화·pooling·토큰 길이 버전을 포함한다. 현재 허용된 모델/토크나이저 hash와 차원은 코드에서 고정한다. 임의 파일 교체는 거부한다. 모델 변경 구현 시 ID·hash·차원·전처리를 함께 갱신하고 새 디렉터리에 전체 설명을 재임베딩해야 한다. Gemini 재분석은 필요 없다. 기존 인덱스는 자동 삭제하지 않는다.

## 검색 품질과 규모

디스크 기반 Lucene HNSW를 사용하여 매 검색 시 20~30만 벡터 전체를 순회하거나 모두 JVM heap에 적재하지 않는다. 30만 x 384 x float32의 원시 벡터만 약 439 MiB이고 HNSW 그래프·문서·세그먼트·재빌드 여유 공간이 추가로 필요하다. Lucene writer buffer는 32MiB이며 실제 RSS에는 ONNX 가중치, JVM, mmap 및 OS 캐시가 포함된다. 30만 장 실측 성능을 보장하지 않는다.

기본 코사인 최소값 0.80 및 1위와의 최대 차이 0.06은 무관한 결과를 줄이는 초기 휴리스틱이다. 각각 `min-similarity`, `max-score-gap`으로 조정할 수 있으며 확률이 아니다. 실제 가족 사진 검색어로 추가 평가해야 한다. 설명에 없는 색·사물은 텍스트 임베딩으로 복구할 수 없다. 이름이나 특정 인물의 행동을 추론하지 않는다.

검증: `server/gradlew.bat captionSearchSmoke -PcaptionModelDir=<model-folder>`는 실제 모델로 가상 한국어 설명 24개만 검색한다. 사용자 사진·운영 DB·유료 API는 사용하지 않는다. 단위 테스트는 임시 SQLite/Lucene 디렉터리를 사용하고 Spring 테스트의 자동 인덱싱은 비활성이다.

## 이번 검증 결과

Windows Java 21에서 고정 ONNX와 로컬 DJL 토크나이저를 직접 실행했다. 가상 한국어 설명 24개/바꿔 쓴 검색문 24개에서 Recall@1 23/24, Recall@3 24/24였다. 이 작은 예제는 실제 개인 사진이나 30만 장 품질을 보장하지 않는다. 한 예제에서는 고양이 질의에 강아지가 먼저 나와 의미 검색의 오탐 가능성을 확인했다. 검색 중간값 약 11ms는 24개 문서의 소규모 smoke 측정이며 대규모 성능 수치가 아니다.
