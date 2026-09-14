# 운영 PC 검색 서비스 최초 설치

최신 소스와 JAR를 GitHub main에 푸시하고 운영 JAR를 갱신한다.
루트 `install-search.ps1`을 운영 PC에 한 번 복사한다.
Python 3.12 64비트가 필요하며 없으면 `winget install -e --id Python.Python.3.12`로 설치한 뒤
PowerShell을 다시 연다. 기존 얼굴 인식 워커.exe는 교체하지 않는다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-search.ps1
# 설치 위치가 다른 경우
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-search.ps1 -InstallDir D:\HomePhoto
```

설치 프로그램은 실제 설치 폴더와 JAR 존재 여부를 확인한다. GitHub main을 한 커밋으로
고정하여 검색 코드와 의존성 목록을 받고, 별도 `.venv-search` 및 모델을 준비한다.
Python 경로는 `-PythonExe`, 서버 내부 주소는 `-ServerUrl http://127.0.0.1:8080`으로 지정할 수 있다.
일반 API 키는 숨김 입력으로 한 번 받으며 검색 토큰은 자동 생성한다.
API 키와 검색 토큰은 `ml-worker/search-private/credentials.xml`에 Windows DPAPI로 저장한다.
같은 PC의 같은 Windows 계정으로 실행해야 한다. 서버용 검색 토큰은
`config/application.properties`에도 저장되므로 운영 설정 파일과 같은 비밀 파일로 취급한다.
대화나 GitHub에 이 파일들을 올리지 않는다.

기존 YAML/OAuth 설정은 수정하지 않는다. properties의 다른 설정을 보존하고 관리 블록만
교체한다. 기존 properties와 검색 코드는 `ml-worker/install-backup-*`에 백업된다.
환경변수나 실행 인수에서 `homephoto.search.*`를 별도 지정했다면 그 값이 우선하므로 맞춰야 한다.
모델 다운로드가 실패하면 서버 설정은 바꾸지 않는다. 이미 받은 모델과 가상환경은 재실행 시 재사용한다.

설치 후 기존 방식으로 HomePhoto 서버를 재시작하고 다음을 실행한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File E:\homePhotoServer\start-installed-search.ps1
# 종료
powershell -NoProfile -ExecutionPolicy Bypass -File E:\homePhotoServer\start-installed-search.ps1 -Stop
```

실행기는 저장된 인증 정보를 읽고 서버의 얼굴 카탈로그 API 인증·검색 활성화를 확인한 뒤
검색 서비스를 숨김 실행한다. 오류 401은 API 키, 404는 JAR 버전, 503은 검색 설정과 서버 재시작을 확인한다.
키 변경 시 설치 스크립트에 `-ResetApiKey`를 붙여 다시 입력한다.
시작 메시지는 모델 로딩이나 전체 인덱싱 완료를 의미하지 않는다.
`ml-worker/search-data/service.err.log`에서 모델 로딩과 인덱싱 진행을 확인한다.

예약 작업은 만들지 않는다. PC 재부팅 후 같은 실행 스크립트를 다시 실행한다.
검색 서비스 갱신 시 먼저 `-Stop`으로 종료한 뒤 설치 스크립트를 재실행한다.
재설치는 의존성도 갱신하며 완전한 자동 롤백을 제공하지 않는다.
설정을 되돌리려면 검색 서비스를 종료하고 properties 관리 블록의 enabled를 false로 바꾸고 서버를 재시작한다.
사진·SQLite·기존 얼굴 워커는 설치/갱신 대상이 아니다.

개발 검증: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-search-installer.ps1`.
Python 실행과 GitHub 통신을 대체한 임시 폴더 검사이므로 실제 운영 설치를 검증한 것은 아니다.
