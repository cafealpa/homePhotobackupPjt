# Google Photos 파생 이미지 게시

실제 UNC 공유와 Google Photos 계정 검증은 사용자 요청(2026-09-30)으로 보류했다.
9단계까지 코드를 이어가되 외부 게시의 기본값은 비활성이다.
실제 계정의 날짜/GPS/지도/파일명/품질 인식은 [5단계 PoC](GOOGLE-PHOTOS-METADATA-POC.md)에 기록할 미확인 사항이다.
이 문서의 코드/로컬 테스트 완료를 실제 Google Photos 동작 확인과 구분한다.

## 6단계 Export Rendition

[GooglePhotosExport](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosExport.kt)는
기존 1600 JPEG 썸네일을 읽고 로컬 `storage-root/tmp/google-photos/`에 별도 파일을 만든다.
원본과 기존 썸네일에는 쓰지 않는다. 성공한 출력의 SHA-256과 메타데이터 snapshot을 반환한다.
생성 도중 실패하면 이번 임시 파일과 원자적 쓰기 파일을 정리한다.
Apache Commons Imaging `1.0.0-alpha6`를 JVM 의존성으로 사용해 JPEG EXIF 영역만 교체한다.
resize/픽셀 재인코딩을 추가하지 않는다. 작성기는 PoC와 동일하다.

[PublicationMetadataProvider](../server/src/main/kotlin/com/homephoto/server/publication/PublicationMetadataProvider.kt)의 규칙:

- 원본명/촬영 시각/위경도는 기존 DB 값을 우선한다. 기존 날짜 선택 정책은 바꾸지 않는다.
- DB에 날짜 또는 좌표가 없으면 원본 EXIF에서 보충한다.
- DB가 보존하지 않는 offset/고도를 위해 원본을 한 번 읽는다. 이를 위한 대규모 DB migration은 하지 않는다.
- 원본 시각이 선택한 시각과 같을 때만 원본 offset을 기록한다. 모르는 시간대를 서버 기본값으로 추정하지 않는다.
- 좌표가 원본 좌표와 같을 때만 원본 고도를 사용한다. 알려지지 않은 GPS 시각/ModifyDate는 만들지 않는다.
- 원본 접근/추출 실패 시 알려진 DB 값으로 준비하고 보충 실패 경고를 snapshot에 남긴다.
- Orientation은 보정된 썸네일 기준 1이다. 기존 썸네일 자체의 방향 문제는 실제 사진/ffmpeg 검증으로 별도 확인한다.

EXIF 최소 세트는 아직 실제 계정 인식 검증 전의 임시 규칙이다.
원본 전체 EXIF/기기별 MakerNote를 복사하지 않는다.
썸네일/메타데이터 version은 `jpeg1600-exif-v1`로 구분한다.

2026-09-30 16:43 KST 서버 테스트 **94건 통과**, 실패·오류·건너뜀 0건.
추가한 Export 테스트 3건은 DB 우선값/원본 보충, 원본 장애 시 알려진 값 사용,
로컬 임시 출력·입력 보존·깨진 JPEG 실패 정리를 검증한다.
기존 PoC의 별도 리더 재추출과 JPEG 압축 바이트 비교도 같은 작성기에 대해 통과했다.
실제 Google 업로드는 실행하지 않았다.
