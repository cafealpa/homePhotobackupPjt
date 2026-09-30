# 원본 StorageAdapter — 2단계 구현

> 이 문서는 2단계 완료 시점 기록이다. 이후 접근 코드와 설정 전환은 [3단계 문서](STORAGE-ACCESS.md)를 참고한다.

## 구현 범위

기존 `AssetIngestService`의 원본 배치·MOVE 입력 정리·이동 복구를
[FileSystemAdapter](../server/src/main/kotlin/com/homephoto/server/storage/FileSystemAdapter.kt)로 옮겼다.
ingest와 삭제 자산 재업로드는 [StorageAdapter](../server/src/main/kotlin/com/homephoto/server/storage/StorageAdapter.kt)를 생성자 주입받는다.
기존 Spring 컴포넌트 검색으로 FileSystemAdapter 하나가 주입되며 새 설정은 필요하지 않다.

현재 루트는 기존 `homephoto.storage-root`이고 key는 기존 DB의 `assets.original_path`이다.
신규 파일 이름·날짜 정책·SHA-256 중복 판정·자산 ID·작업 등록과 DB 구조는 유지한다.
기존 상대경로를 새 형식으로 다시 계산하거나 원본 파일을 일괄 이동하지 않는다.

## 계약

| API | 책임 |
|---|---|
| `save(key, source, checksum, moveSource)` | SHA-256과 로컬 입력 파일을 받아 완성된 원본을 저장하고 `Write` 반환 |
| `Write.commit()` | DB 성공 뒤 MOVE의 복사 경로 입력 정리. 실패는 ingest에서 경고로 기록하고 이미 완료한 백업 유지 |
| `Write.rollback()` | DB 실패 뒤 실제로 이동한 입력 파일 복구. 복구 실패는 원래 오류에 suppressed exception으로 추가 |
| `stat(key)` | 파일 크기 반환. 확인된 파일 부재는 null이며 접근 권한/일반 I/O 오류는 전파 |
| `open(key, offset, length)` | 전체 또는 offset부터 최대 length 바이트의 InputStream 반환. 호출자가 close |
| `delete(key)` | 존재하는 원본 삭제. 이미 없는 파일은 성공으로 취급하고 삭제 오류는 전파 |
| `withReadableFile(key, reader)` | 파일을 요구하는 EXIF/Thumbnailator/ffmpeg 처리를 위한 callback. 물리 Path는 callback 범위에서 사용 |

원본 저장소 내부의 물리 목적지 Path는 ingest에 반환하지 않는다.
로컬 수신/임포트 입력의 Path, EXIF 추출, 입력 크기·해시 계산은 기존 ingest 계층에 유지한다.
저장 대상의 물리 경로 해석·배치·정리·복구는 FileSystemAdapter가 담당한다.

## 파일 배치와 실패 처리

- 같은 key의 변경은 기존 `AssetLocks`로 직렬화한다. Adapter 자체에 별도 잠금은 추가하지 않는다.
- 대상이 이미 존재하면 전체 SHA-256이 같을 때만 재사용하며, 다르면 대상과 입력 모두 보존한 채 실패한다.
- MOVE이고 입력/대상 부모가 같은 FileStore이면 기존 `ATOMIC_MOVE` 최적화를 유지한다.
- 다른 FileStore이거나 원자적 이동 미지원이면 기존 `AtomicFiles.write`로 임시 대상에 복사한 뒤 공개한다.
- DB 저장 성공 전 복사 입력을 삭제하지 않는다. commit은 입력과 대상이 같은 경로일 때 대상 파일을 삭제하지 않는다.
- 실제 이동한 입력은 DB 실패 시 rollback으로 되돌린다. 신규 저장과 삭제 자산 재업로드 모두 DB 결과에 따라 완료/복구 처리한다.
- 삭제 자산 재업로드는 기존처럼 복사 방식으로 원본을 복구하고 입력 파일을 보존한다.
- 복사 후 DB 실패 시 대상 원본이 남을 수 있는 기존 정책과 신규 복사 대상의 추가 해시 재검증 부재는 유지한다.
  [1단계 검증 기준](STORAGE-PUBLICATION-REGRESSION.md)에 기록한 후속 실패 처리 항목이다.
- 빈 key·절대경로·루트 밖으로 나가는 상대경로는 거부한다. 기존 저장소 내부 상대경로는 유지한다.

`open`은 FileChannel의 위치를 이동해 읽기 시작점을 지정하며 전체 파일을 메모리에 적재하지 않는다.
length가 있으면 제한된 스트림을 반환하고 파일 끝이 먼저 오면 EOF를 반환한다.
부분 읽기 계약 추가만으로 현재 원본 HTTP API가 전환된 것은 아니다.

## 후속 전환 범위

2단계에서는 원본 저장·재업로드 배치에만 Adapter를 연결했다.
다운로드·썸네일 생성·휴지통·용량 조회·시작 초기화는 아직 기존 파일시스템 호출을 사용한다.

3단계에서 해당 호출부와 원본 전용 설정을 전환하고 DB·썸네일·임시 파일의 로컬 경로를 분리한다.
그때 원본 HTTP Resource/Range 호환성과 설정 화면 저장 후 설정 보존을 검증한다.
현재 다운로드 응답 코드와 Python 워커의 원본 API 계약은 변경하지 않았다.

4단계에서 실제 UNC 공유와 서버 실행 계정으로 동작을 검증한다.
현재 stat은 `NoSuchFileException`만 부재로 처리하고 다른 I/O 예외는 전파한다.
네트워크 장애를 OS가 부재 오류로 반환하는 경우까지 구분하려면 공유 루트 확인 정책과 실환경 검증이 필요하다.
따라서 이번 로컬 테스트 통과를 UNC 장애 처리 완료로 보고하지 않는다.

Google Photos와 S3 구현은 이후 단계의 범위이다.

## 검증

[FileSystemAdapterTest](../server/src/test/kotlin/com/homephoto/server/storage/FileSystemAdapterTest.kt)에 다음 계약 검증 10건을 추가했다.

- COPY 입력 보존, 레거시 상대경로와 전체 읽기/파일 callback.
- 같은 볼륨 MOVE 뒤 바이트 보존과 rollback.
- 기존 동일 대상의 MOVE 입력 정리를 commit 이후에만 수행.
- 기존 대상 해시 불일치 시 양쪽 파일 보존.
- 입력이 이미 저장 대상인 경우 commit 후 원본 보존.
- 부분 읽기, read/skip 조합, 길이 0, EOF, 잘못된 범위, 스트림 종료 후 삭제.
- 없는 파일의 stat/open/delete 계약.
- 저장 루트와 외부 경로 접근 거부.
- 빈 COPY의 최종 파일 공개 차단과 임시 파일 정리.
- 기존 설정으로 Spring의 StorageAdapter/AssetIngestService 주입.

기존 [ServerRegressionTest](../server/src/test/kotlin/com/homephoto/server/ServerRegressionTest.kt)의
fixture만 FileSystemAdapter 주입으로 조정했다. 중복 ingest, DB 실패 시 이동 복구, 재업로드,
휴지통 경합, 작업 큐, migration, 썸네일 테스트의 기존 단언은 유지한다.

관련 테스트 실행 명령 (`server/`):

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain --tests com.homephoto.server.storage.FileSystemAdapterTest --tests com.homephoto.server.ServerRegressionTest --tests com.homephoto.server.FileSafetyTest
```

2026-09-30 실행 결과: 관련 테스트 **29건 통과**, 실패/오류/건너뜀 0.
같은 날 15:50 KST 서버 전체 테스트 **65건 통과**, 실패/오류/건너뜀 0, `BUILD SUCCESSFUL`.

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain
```

실제 운영 라이브러리·UNC 공유·ffmpeg 미디어 처리·웹/Android 원본 재생은 이번 테스트의 검증 범위에 포함되지 않는다.
배포 JAR와 운영 설정은 변경하지 않았다.
