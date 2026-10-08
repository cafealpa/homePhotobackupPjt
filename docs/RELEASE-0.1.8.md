# HomePhoto Server 0.1.8

## 주요 변경

- 사진 의미 검색과 유사 얼굴 검색을 JVM 서버 안으로 통합했습니다. 별도 Python/PyTorch/LanceDB 서비스가 필요하지 않습니다.
- SigLIP 2 모델을 ONNX Runtime Java에서 실행하고 사진·얼굴 벡터를 Lucene에 저장합니다. 기존 검색 API와 MCP 도구는 유지합니다.
- 서버 시작 시 자동 인덱싱하며 설정 화면에서 확인 수, 실패 수, 마지막 오류와 중지·재시도를 확인할 수 있습니다.
- 중지 직전 처리한 결과를 저장하며 중지 중에도 준비된 인덱스를 검색할 수 있습니다. 삭제·숨김·재분석된 항목은 현재 DB와 대조합니다.

## 설치와 전환

- Windows x64, Java 21 기준입니다. ZIP의 `homephoto-server.jar`와 해시 이름의 `homephoto-face-runtime-*.jar`를 같은 폴더에 두세요. 런타임 파일명은 변경하지 마세요.
- 최초 JVM 사진 검색 전환 시 ZIP의 `prepare-search-model.ps1 -ModelDir ./models/siglip2`로 약 1.5GB 모델을 준비하세요. 모델은 JAR/ZIP에 포함하지 않습니다.
- 외부 설정의 `homephoto.search.enabled=true`, `homephoto.search.model-dir`을 확인하세요. 기본 모델 경로는 `models/siglip2`입니다. 구형 `base-url`, `token`, `worker-dir`은 더 이상 사용하지 않습니다.
- 새 사진 인덱스는 기본 `<storage-root>/vector-search`에 다시 구축합니다. 얼굴은 SQLite의 기존 벡터를 재사용하며 이번 버전은 DB 초기화를 추가하지 않습니다.
- 구형 Python 검색 프로세스를 종료하고 새 검색을 확인하세요. 기존 `ml-worker` 모델·인덱스·가상환경은 자동 삭제하지 않습니다. 얼굴 검출용 buffalo_l 모델은 계속 유지하세요.
- 0.1.7의 문서 OCR·설명 검색 기능도 포함합니다. 더 오래된 버전에서 업데이트한다면 해당 버전의 DB 마이그레이션 안내도 확인하세요.
- 자세한 [JVM 검색 전환 안내](https://github.com/cafealpa/homePhotobackupPjt/blob/v0.1.8/docs/JVM-VECTOR-SEARCH.md)를 참고하세요.

## 검증과 롤백

- 서버 전체 225개 테스트, Python/JVM 합성 이미지·문장 모델 비교, 별도 임시 서버에서 실제 사진/얼굴 검색과 안전 종료를 확인했습니다.
- 업데이트 전 정상 종료 후 DB·설정·기존 서버/런타임 JAR를 백업하세요. 모델 로딩 실패 또는 검색 회귀 시 이전 JAR 쌍과 설정을 복원하고, 필요 시 보존한 Python 검색 환경을 다시 실행할 수 있습니다.
- GitHub 릴리스 공개는 운영 서버의 파일 교체나 재시작을 자동 수행하지 않습니다.
