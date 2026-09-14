# 로컬 사진 의미 검색 (LanceDB + SigLIP 2)

Home Photo의 Spring Boot가 인증·MCP·사진 접근을 담당하고, 같은 PC의 Python 서비스가
CPU 임베딩 생성과 LanceDB 검색을 담당한다. GB10이나 외부 AI API는 사용하지 않는다.
검색 중에도 Python 서비스가 켜져 있어야 한다. 기존 얼굴 인식 워커와 별도 가상환경을 사용한다.

얼굴별 벡터 인덱스와 인물 후보 조회도 같은 검색 프로세스에서 제공한다.
별도 모델로 얼굴을 다시 분석하지 않으며, 자세한 내용은 [얼굴 벡터 검색](FACE-VECTOR-SEARCH.md)을 참고한다.

## 구성

- 모델: `google/siglip2-base-patch16-224`, CPU, 기본 PyTorch 연산 스레드 2개.
- 이미지: 기존 400px 썸네일을 모델의 공식 전처리로 변환한다. 원본을 분석 서비스로 보내지 않는다.
- 텍스트/이미지: 같은 모델 리비전의 인코더로 변환한 정규화 벡터를 cosine 거리로 검색한다.
- LanceDB: `ml-worker/search-data`, 모델 리비전별 테이블. 모델 변경 시 새 테이블을 구축한다.
- 모델: `ml-worker/search-model`에 명시적으로 다운로드한 뒤 정상 서비스에서는 오프라인으로 로드한다.
- 통신: Spring → `127.0.0.1:18082`, 별도 검색 토큰. Python → Spring은 기존 API 키.
- 기본값: `homephoto.search.enabled=false`. 비활성일 때 의미 검색 MCP 도구는 노출하지 않는다.

SQLite가 사진 정보의 기준이다. 벡터 저장소에는 ID·해시·촬영일·벡터만 저장한다.
검색 결과를 반환하기 전에 SQLite에서 현재 삭제 여부·사진 종류·촬영일을 재검사한다.

## 준비와 실행 (개발/운영 PC에서 각각 수행)

운영 PC는 [최초 설치 스크립트 안내](SEARCH-INSTALL.md)를 사용하면 파일 다운로드와 연결 설정을 한 번에 처리할 수 있다.

Python 3.12 또는 3.14의 일반 x64 배포판을 사용한다. 버전에 맞는 의존성은 requirements의 marker로 선택한다.
아래 수동 준비 명령은 운영 설정/DB/기존 얼굴 워커를 자동으로 수정하지 않는다.

```powershell
# 별도 가상환경과 모델 준비. 이 단계만 인터넷이 필요하다.
.\ml-worker\start-search.ps1 -Prepare
```

모델 다운로드 완료 후 `search-model/homephoto-model.json`에 정확한 리비전이 기록된다.
다른 PC로 배포할 때 모델 폴더 전체와 해당 파일을 함께 복사한다. 임의로 다른 리비전의
파일을 섞지 않는다. 모델 저장 공간과 실제 CPU 처리 속도는 준비 단계에서 확인한다.

기존 서버 설정에 다음 항목을 추가한다. 검색 토큰은 32자 이상의 임의 문자열로 만들어
서버와 Python에 동일하게 설정하며, 일반 API 키와 구분한다. 실제 키는 저장소에 커밋하지 않는다.

```properties
homephoto.search.enabled=true
homephoto.search.base-url=http://127.0.0.1:18082
homephoto.search.token=<별도 검색 토큰>
```

```powershell
# 값은 현재 PC의 비밀 설정에서 읽어서 넣는다. 실제 값을 대화나 로그에 출력하지 않는다.
$env:HOMEPHOTO_API_KEY = '<기존 Home Photo API 키>'
$env:HOMEPHOTO_SEARCH_TOKEN = '<별도 검색 토큰>'
$env:HOMEPHOTO_SERVER = 'http://127.0.0.1:8080'
.\ml-worker\start-search.ps1

# 종료
.\ml-worker\start-search.ps1 -Stop
```

`start-search.ps1`은 숨김 프로세스로 시작하며 PID와 stdout/stderr를 `search-data`에 남긴다.
프로세스 시작과 모델 준비 완료는 다르다. `GET http://127.0.0.1:18082/health`에
`Authorization: Bearer <검색 토큰>`을 보내 `ready`인지 확인한다.
기존 `start-server.bat`은 이 서비스를 자동 시작하지 않으므로 서버와 함께 별도로 시작한다.
최신 JAR에서는 웹 설정의 **사진·얼굴 벡터 검색** 카드에서 시작·중지와 인덱스 수 확인도 가능하다.
별도 DB 서버나 컨테이너는 필요 없고, 외부 포트 포워딩도 추가하지 않는다.

선택 환경변수: `HOMEPHOTO_SEARCH_MODEL`, `HOMEPHOTO_SEARCH_DATA`,
`HOMEPHOTO_SEARCH_THREADS`(기본 2), `HOMEPHOTO_SEARCH_PORT`(기본 18082).
현재 SQLite 라이브러리를 바꾸면 해당 라이브러리 전용 검색 데이터 디렉터리도 새로 지정한다.

## 동기화와 검색

1. 인증된 `/api/v1/internal/search/catalog?afterId=0`에서 100개씩 목록을 읽는다.
2. 새 사진/해시가 바뀐 사진만 썸네일로 임베딩한다. 기존 사진은 벡터를 재사용한다.
3. 촬영일 변경은 메타데이터를 갱신하고, 휴지통·영구 삭제·키즈노트·동영상은 인덱스에서 제거한다.
4. 전체 순회 후 5분 대기한다. 아직 썸네일이 없는 사진이나 실패한 사진은 다음 순회에서 재시도한다.
5. 512장 이상이면 LanceDB IVF_FLAT 인덱스를 생성한다. 그 이하는 정확한 벡터 비교를 사용한다.

초기 분석은 ID 순으로 진행하며 완료된 벡터는 재시작해도 재사용한다. 대규모 초기 분석 중에는
아직 처리하지 않은 사진이 검색되지 않는다. 날짜 변경·삭제 반영은 순회 시간만큼 지연될 수 있다.
Spring의 최종 검사로 삭제 사진 노출은 막지만, 변경된 날짜의 검색 누락까지 즉시 해소하지는 못한다.
인덱스는 재생성 가능한 데이터이므로 SQLite/원본 백업을 대체하지 않는다.

MCP 도구: `search_photos_by_description`

```json
{"query":"음식이 담긴 사진","start_date":"2026-09-01","end_date":"2026-09-30","limit":12}
```

`query`는 필수이고 날짜는 선택이다. 날짜를 생략하면 전체 분석 사진에서 검색한다.
하루는 `date`로 지정한다. 날짜 규칙은 기존 검색과 같고 최대 24장을 관련도 순으로 표시한다.
이번 구현은 상위 결과만 제공하며 의미 검색의 추가 페이지는 제공하지 않는다.

서버 API: `GET /api/v1/photos/search?query=음식&start_date=2026-09-01&end_date=2026-09-30`
(실제 요청에서는 query를 URL 인코딩한다.) 기존 API 키/웹 로그인 인증을 사용한다.

`indexed_photos`는 검색 서비스의 전체 인덱스 행 수이며 조건에 맞는 정확한 사진 수나
현재 라이브러리 분석 완료율이 아니다. 결과에는 관련 없는 사진이 섞이거나 누락될 수 있다.
특정 가족의 신원 검색이나 ‘음식 사진 총 몇 장’의 정확한 집계에 사용하지 않는다.
서비스 중단 시 의미 검색 API는 503, MCP는 도구 오류를 반환한다. 날짜 검색과 집계는 유지된다.

## 검증과 롤백

```powershell
cd ml-worker
.venv-search\Scripts\python.exe -m pip install httpx==0.28.1
.venv-search\Scripts\python.exe -m unittest test_search_service -v
# bootJar를 먼저 빌드한 뒤 실제 모델/JAR/MCP를 임시 데이터로 검사한다.
.venv-search\Scripts\python.exe smoke_search.py --jar ..\server\build\libs\homephoto-server-0.1.6-rc2.jar --java <Java21의 java.exe>
```

생성 이미지 기반 검사는 통신·모델 실행·벡터 저장·미리보기 경로를 검증한다.
한국어 가족 사진 검색 품질이나 전체 라이브러리의 응답 시간을 보장하는 성능 측정은 아니다.
운영 사진의 음식·풍경·활동 예제로 품질 확인 후 사용 범위를 넓힌다.

개발 검증: 서버 회귀·검색 경계 테스트, 실제 LanceDB의 저장/재열기/삭제/날짜 필터 및
IVF_FLAT 인덱스 테스트를 수행했다. 생성 이미지 3장을 실제 서버에 업로드하고
SigLIP 2의 768차원 벡터로 인덱싱한 뒤 MCP 검색·단기 미리보기와 API의 200 응답을 확인했다.
모델 리비전은 `75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2`였다.
숨김 실행 스크립트의 모델 준비 완료 상태 및 종료도 합성 키로 검증했다.

롤백은 검색 서비스를 종료하고 `homephoto.search.enabled=false`로 바꿔 서버를 재시작한다.
기존 사진·SQLite·OAuth 키를 변경하거나 삭제할 필요는 없다. 배포본은 아직 별도로 게시하지 않는다.

공식 근거: [SigLIP 2](https://huggingface.co/google/siglip2-base-patch16-224),
[LanceDB 벡터 검색](https://docs.lancedb.com/search/vector-search).
