# JAR 직접 배포

개발 PC에서 저장소 루트의 `build-jar.ps1`을 실행하면 서버 테스트와 빌드 후
이 폴더에 `homephoto-server.jar`와 `homephoto-server.jar.sha256`이 만들어진다.
두 파일을 소스와 함께 커밋하고 GitHub에 푸시한다. Git LFS는 사용하지 않는다.
"jar 만들어줘" 요청 시에도 이 명령을 사용한다. 자동 푸시는 하지 않는다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-jar.ps1
```

운영 PC에는 루트의 `update-server-jar.ps1`을 한 번 복사해 둔다.
기존 방식으로 서버를 중지한 뒤 아래 명령으로 JAR만 교체하고 서버를 다시 시작한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\update-server-jar.ps1
# 다른 설치 위치 또는 브랜치
.\update-server-jar.ps1 -InstallDir 'D:\HomePhoto' -Ref main
```

기본 설치 위치는 `E:\homePhotoServer`, 저장소는 `cafealpa/homePhotobackupPjt`,
브랜치는 `main`이다. 공개 저장소를 대상으로 하며 운영 PC에 Git/gh 설치는 필요 없다.
브랜치를 커밋 SHA로 고정한 후 JAR와 SHA256만 다운로드한다.
체크섬과 JAR 구조 확인 후 원자적으로 교체하며 이전 JAR는 같은 폴더에 `.bak`으로 보존한다.
같은 JAR면 교체하지 않는다. 오류 시 기존 JAR를 유지한다.
롤백은 서버를 중지하고 해당 `.bak`을 `homephoto-server.jar`로 복사한 뒤 재시작한다.

설정·사진·DB·OAuth 키와 Python 워커는 이 스크립트의 갱신 대상이 아니다.
LanceDB 검색 서비스를 처음 설치하거나 Python 코드/의존성이 변경된 경우에는 별도 갱신이 필요하다.
이 흐름은 GitHub Releases 게시가 아니라 Git 저장소의 JAR 파일 직접 배포다.
