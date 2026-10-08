# 사진·얼굴 검색 설치

검색은 JVM 서버에 통합되었다. 기존 Python 설치 스크립트와 별도 검색 서비스는 제거했다.
[모델 준비·기존 설치 전환·설정 방법](JVM-VECTOR-SEARCH.md)을 따른다.

서버 폴더에서 `prepare-search-model.ps1 -ModelDir ./models/siglip2`를 실행하고
`homephoto.search.enabled=true`로 설정한 뒤 새 서버를 시작한다.
운영 설정과 이전 LanceDB는 자동 변경하거나 삭제하지 않는다.
