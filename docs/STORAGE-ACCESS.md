# 원본 접근과 설정 분리 — 3단계

## 2026-10-05: 기기 업로드 수신 큐

새 안드로이드 앱은 `POST /api/v1/assets`에 `X-Upload-Queue: true`를 보낸다.
서버는 해시와 형식을 검증하고 `<homephoto.storage-root>/incoming`에 완성된 파일을 보관한 뒤
SQLite `incoming_uploads`에 접수 기록을 커밋한다. 이 두 작업이 성공해야 `202 {hash, status: "QUEUED"}`를 반환한다.
원본 저장소에 접근하지 못해도 기기 전송은 종료되며, 앱에는 **서버 수신 완료 · 원본 저장 대기**로 표시된다.
기기 원본 자동 삭제 기능은 추가하지 않았다. `storage-root`와 DB는 원본 공유와 독립된 로컬 디스크에 둔다.

- 원본 저장 작업자 1개가 5초 주기로 처리한다. I/O 실패는 30초부터 최대 30분까지 간격을 늘려 재시도하며,
  기존 분석 큐의 3회 실패 제한을 적용하지 않는다. 저장소 권한 오류도 사본을 유지하며 재시도한다.
- 해시 재검증, 원본 저장, 사진 DB 및 후속 작업 등록이 끝난 뒤에만 로컬 사본을 정리한다.
  저장 후 큐 완료 기록 전 종료된 경우에는 기존 자산을 확인하여 중복 생성 없이 마무리한다.
- 재시작하면 `RUNNING`을 다시 대기로 돌린다. `incoming`은 기존 `tmp` 정리에서 제외된다.
- `/assets/check`의 `queued`는 서버에 접수된 해시다. `missing`/`deleted`와 겹치지 않는다.
  앱은 `SERVER_QUEUED` 상태로 재전송을 막고, 다음 백업의 서버 대조에서 저장 완료를 확인한다.
- 기기에서 기존 `FAILED` 항목을 다시 백업하면 새 큐로 접수된다. 과거 오류로 삭제된 서버 임시 파일은 복구되지 않는다.
- 구형 앱/기타 API 호출은 헤더를 생략하면 기존 동기 업로드의 `201/409` 응답을 유지한다.
  새 앱도 구형 서버의 `201/409` 및 `queued`가 없는 대조 응답을 처리한다.

운영 확인은 **대시보드 → 저장소·작업 → 원본 저장 대기**에서 한다. 건수·용량·가장 오래된 접수 시각과
최대 100건의 파일 상태/최근 오류를 표시한다. 새로고침으로 상태를 갱신하고, 연결/권한 복구 후
**지금 재시도**로 대기 간격을 건너뛸 수 있다.

- `PENDING`: 연결 대기, 자동 재시도. `RUNNING`: 원본 저장 중.
- `BLOCKED`: DB 또는 예상하지 못한 오류. 로컬 사본은 보존하며 원인 해결 후 지금 재시도를 누른다.
- `LOST`: 로컬 사본 유실/해시 불일치. 서버 대조에서 다시 필요한 파일로 보고하므로 기기에서 백업을 재실행한다.
  삭제된 자산은 기존 스킵/명시적 복원 규칙을 우선한다.
- 접수 뒤 사용자가 휴지통 이동 또는 영구 삭제한 자산은 재시도로 되살리지 않는다.

로컬 디스크 부족, 해시 불일치, 지원하지 않는 형식, 큐 DB 기록 실패는 접수 실패로 응답한다.
파일 확정과 DB 기록은 서로 다른 자원이므로 그 사이 종료되면 DB에 없는 고아 사본이 남을 수 있다.
이 파일은 자동 삭제하지 않는다. 서버를 중지하고 DB의 `incoming_uploads.local_name`과 비교하여
별도 보관/복구 여부를 결정한다. 임의로 `incoming`을 비우지 않으며, 운영 백업에는 DB와 이 폴더를 함께 포함한다.
원본 루트 변경이나 구버전 서버로 되돌리기 전에는 큐를 비우거나 DB/대기 파일을 함께 보존한다.

검증: 임시 SQLite와 분리된 로컬/원본 디렉터리에서 접근 실패·복구, 3회 초과 재시도, 재시작,
동시 중복 접수, 저장 후 중단, 삭제/복원 경쟁, 사본 유실/변조, DB 실패를 테스트한다.
HTTP 통합 테스트는 큐 접수→중복 대조→저장 완료와 인증을 검증한다.
실제 NAS/UNC 단절·복구, 실제 휴대폰 설치 및 화면 동작은 별도 운영 검증 대상이다.
2026-10-05 로컬 검증 결과: 서버 테스트 153건, 안드로이드 단위 테스트 14건 통과.
서버 `test bootJar`, 앱 `testDebugUnitTest assembleDebug`, 대시보드 JavaScript 구문 검사를 통과했다.

## 적용 범위

원본 저장에 이어 다운로드·썸네일 입력·휴지통 복원/영구 삭제·원본 저장소 용량 조회·임포트 제외 경로·시작 준비를
[StorageAdapter](../server/src/main/kotlin/com/homephoto/server/storage/StorageAdapter.kt)에 연결했다.
DB의 `original_path`와 기존 파일 이름, 촬영일 선택, 중복 판정, 자산 ID/작업 등록 정책은 유지한다.
이 단계에는 DB 스키마 변경과 기존 원본의 일괄 이동이 없다.

기존 [2단계 문서](STORAGE-ADAPTER.md)는 도입 시점 기록이며, 현재 접근/설정 계약은 이 문서를 따른다.

## 원본 전용 설정

`homephoto.storage-root`는 홈서버의 로컬 데이터 루트이다.
`homephoto.original-storage.root`를 생략하면 원본도 기존 루트를 사용한다.
원본 전용 루트를 지정해도 DB·썸네일·수신 임시 파일의 경로는 기존 로컬 설정을 유지한다.

```yaml
homephoto:
  storage-root: 'C:/homePhotoData'
  original-storage:
    root: '\\STORAGE-PC\PhotoArchive'
```

위 UNC는 설정 형식 예시이며 실제 검증한 공유 경로가 아니다.
원본 위치는 `<original-storage.root>/<기존 original_path>`이다.
예를 들어 `originals/2024/01/20240102_030405_abcd1234.jpg`는 공유 폴더의 `originals/2024/01/`에 있어야 한다.
루트 변경만으로 원본이 이동하지 않는다. 운영 루트를 전환하려면 기존 key와 동일한 상대경로의 파일을 먼저 준비해야 한다.

설정 화면에 원본 경로와 재시작 안내를 추가했다. 빈 값은 기존 로컬 루트를 사용하는 의미다.
저장된 원본 경로는 재시작 전에도 폼에 유지되지만 실제 Adapter 루트는 재시작 후 바뀐다.
이전 클라이언트가 필드를 생략하면 저장된 원본 경로를 보존한다.
명시적으로 빈 값을 보내면 원본 전용 설정을 제거한다.
DB·썸네일·임시 폴더와 원본 폴더가 겹치는 설정은 거부한다.

[SettingsService](../server/src/main/kotlin/com/homephoto/server/service/SettingsService.kt)는 기존 YAML을 안전한 loader로 읽고
웹 폼의 항목만 갱신한 뒤 임시 파일에서 완성된 설정을 공개한다.
MCP 등 기타 YAML 값과 재시작을 기다리는 원본 경로를 보존하며, 동시 저장은 직렬 처리한다.
YAML 주석·서식은 재작성될 수 있다. 파싱/쓰기 실패 시 기존 파일을 덮어쓰거나 런타임 설정을 먼저 적용하지 않는다.
설정 파일의 비밀 값은 계속 Git 제외 대상이다.

## 원본 응답과 장애 처리

[StorageResource](../server/src/main/kotlin/com/homephoto/server/storage/StorageResource.kt)는 기존 Resource 응답과 MIME/Range 계약을 유지한다.
Range 처리에서 첫 읽기 전에 건너뛴 위치를 `StorageAdapter.open(key, offset)`에 전달한다.
전체 파일이나 앞부분을 메모리에 적재하지 않는다. 여러 Range는 Spring의 multipart 응답을 사용한다.

[FileSystemAdapter](../server/src/main/kotlin/com/homephoto/server/storage/FileSystemAdapter.kt)의 stat/delete는
원본이 없을 때 루트가 접근 가능한 디렉터리인지 확인한다.
루트 자체가 없거나 파일이면 저장소 오류로 전달하며, 확인된 원본 부재와 구분한다.
이 오류로 휴지통 복원/영구 삭제의 DB 상태를 확정하지 않는다.
접근 권한 및 일반 I/O 오류도 전파한다.

시작 시 원본 준비에서 I/O 오류가 나도 로컬 DB·썸네일 초기화는 유지한다.
원본 저장·읽기는 해당 오류를 전달한다. 네트워크 대기 시간과 실제 UNC 오류 코드는 4단계 실환경 검증 대상이다.
용량 조회는 Adapter를 사용한다. 임포트는 로컬 데이터 루트와 별도 원본 루트를 모두 제외하고 원본 저장소의 여유 공간을 확인한다.

## 검증 기록

2026-09-30에 다음 테스트를 추가/확장했다.

- [AssetStorageHttpTest](../server/src/test/kotlin/com/homephoto/server/storage/AssetStorageHttpTest.kt), 11건:
  임시 SQLite와 서로 다른 로컬/원본 폴더로 실제 임의 포트 서버를 시작하고 multipart/중복/해시 오류/미지원 형식,
  전체 다운로드, 일반·suffix·끝 생략·잘못된·다중 Range, 휴지통·재업로드·없는 파일·저장소 중단,
  썸네일·용량·SCAN/COPY/MOVE·인증·설정 API를 검증한다.
- [StorageSettingsTest](../server/src/test/kotlin/com/homephoto/server/storage/StorageSettingsTest.kt), 7건:
  설정 저장 후 Spring 바인딩, 로컬 경로 유지, 이전 클라이언트/동시 저장/기타 YAML 값 보존,
  UNC 문자열과 기본 루트 복귀, 잘못된 경로/YAML 보존, 원본/파생/임시 폴더 겹침 거부.
- [StorageResourceTest](../server/src/test/kotlin/com/homephoto/server/storage/StorageResourceTest.kt), 1건:
  prefix skip 전에 저장소를 열지 않고 실제 offset으로 읽기 시작하며 각 스트림을 독립적으로 종료.
- [FileSystemAdapterTest](../server/src/test/kotlin/com/homephoto/server/storage/FileSystemAdapterTest.kt), 13건:
  기존 10건에 루트 분리·용량/임포트 경계와 루트 부재/파일 오류 검증을 추가.
- 기존 서버 55건의 회귀 단언은 유지한다.

```powershell
# server/에서 실행
.\gradlew.bat test --offline --no-daemon --console=plain
```

2026-09-30 16:19 KST 최종 실행은 서버 테스트 87건 모두 통과했다. 실패·오류·건너뜀은 0건이며 `BUILD SUCCESSFUL`을 확인했다.
`node --check server/src/main/resources/static/app.js`도 통과했다.
테스트 서버는 운영 설정을 제외하고 임시 DB/설정 파일만 사용한다.
Range용 동영상 바이트는 합성 fixture로 실제 영상 디코딩을 검증하지 않는다.
실제 UNC 공유·ffmpeg·웹/Android 영상 재생·브라우저 설정 화면·Google Photos 계정 검증은 아직 수행하지 않았다.
운영 설정·배포 JAR는 변경하지 않았다.

## 다음 단계

4단계의 실제 UNC 검증은 사용자 요청(2026-09-30)에 따라 뒤로 미뤘다. 완료로 간주하지 않는다.
추후 실제 공유 경로와 홈서버 실행 계정으로 저장·읽기·썸네일·삭제·Range 및 연결 장애를 검증한다.
그 경로와 계정을 확인하기 전에는 운영 원본 루트를 변경하거나 기존 원본을 이동하지 않는다.
5단계 도구 준비와 6~9단계 코드는 사용자의 계속 진행 요청에 따라 기본 비활성 상태로 구현했다.
Google Photos 실제 계정 검증도 보류했으며, 활성화 전 [메타데이터 PoC](GOOGLE-PHOTOS-METADATA-POC.md)를 확인한다.
관리 화면과 로컬 검증 범위는 [게시 기능 기록](GOOGLE-PHOTOS-PUBLICATION.md)에 이어서 정리한다.
