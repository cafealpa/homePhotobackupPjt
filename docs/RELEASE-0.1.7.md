# HomePhoto Server 0.1.7

## 주요 변경

- 가족 문서 보관 화면에서 문서 판별, Gemini OCR, 처리 현황, 재분석 및 검색을 제공합니다. 기존 사진은 사용자가 선택해 등록하며 문서 OCR은 처음에는 일시정지 상태입니다.
- 추출한 문서 내용을 청크로 나누어 기존 E5 모델과 Lucene 인덱스로 검색합니다. 장면 설명 검색도 유지합니다.
- 사진 썸네일의 HTTP 캐시 유효기간을 30일에서 180일로 늘렸습니다. 기존 앱 캐시는 기존 만료기간을 유지하며 업데이트한 서버에서 새로 내려받는 응답부터 적용됩니다.

## 설치와 업데이트

- Windows x64, Java 21 기준입니다. ZIP을 풀고 `homephoto-server.jar`와 해시 이름의 `homephoto-face-runtime-*.jar`를 같은 폴더에 두세요. 런타임 파일명은 변경하지 마세요.
- 설정, DB, 사진, 비밀 키, 모델, 검색 인덱스 및 ffmpeg는 배포 파일에 포함하지 않습니다. 기존 파일을 유지하세요.
- 문서 OCR은 장면 분석에 설정된 Gemini 모델과 키를 사용하며 호출 비용이 발생할 수 있습니다. 문서 보관 화면에서 시작한 뒤에 처리합니다.
- 기존 DB에 문서 분석·검색 관련 테이블과 트리거를 추가합니다. 업데이트 전에 서버를 정상 종료하고 DB·설정·기존 JAR를 함께 백업하세요. 완전한 롤백은 일관된 백업을 복원해야 합니다.
- rc4 이전 버전에서 업데이트한다면 [rc4 얼굴 엔진 마이그레이션 안내](https://github.com/cafealpa/homePhotobackupPjt/releases/tag/v0.1.6-rc4)를 확인하세요.
- 자세한 사용법은 [문서 보관·검색](https://github.com/cafealpa/homePhotobackupPjt/blob/v0.1.7/docs/DOCUMENT-SEARCH.md)을 참고하세요.

릴리즈 게시는 운영 서버의 JAR 교체나 재시작을 자동 수행하지 않습니다.
