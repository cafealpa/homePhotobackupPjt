# JVM 사진·얼굴 벡터 검색

2026-10-08부터 사진 의미 검색과 유사 얼굴 검색은 서버 JVM 안에서 실행한다.
Python, PyTorch, LanceDB, 별도 HTTP 서비스/검색 토큰/18082 포트가 필요하지 않다.
기존 사진 검색 API와 MCP 도구, 얼굴별 후보 API는 그대로 유지한다.

## 준비와 전환

1. 서버와 같은 폴더의 `models/siglip2`에 모델을 준비한다.

   ```powershell
   .\prepare-search-model.ps1 -ModelDir 'E:\homePhotoServer\models\siglip2'
   ```

   저장소 루트에서 인자 없이 실행하면 `deploy/models/siglip2`에 내려받는다.
   개발용 `server/gradlew bootRun`은 `-ModelDir ./server/models/siglip2`로 준비하거나
   아래 `model-dir`에 전체 경로를 지정한다.

2. 외부 `config/application.yml`의 기존 `homephoto` 아래에 설정을 합친다.
   기존 `application.properties`에 같은 키가 있으면 그 값이 우선하므로 함께 확인한다.

   ```yaml
   homephoto:
     search:
       enabled: true
       model-dir: E:/homePhotoServer/models/siglip2
       index-dir: ''
   ```

3. 새 서버 JAR와 해당 JAR의 Manifest가 지정하는 런타임 JAR를 배포하고 재시작한다.
   모델은 JAR에 포함되지 않는다. 서버 시작 후 약 15초부터 인덱싱하며 5분마다 동기화한다.
4. 설정의 **사진·얼굴 벡터 검색**에서 준비 상태, 저장된 사진/얼굴 수, 이번 순회 확인 수,
   실패 수와 마지막 오류를 확인한다. 모델 누락 시 사진 검색만 준비 대기하고 얼굴은 계속 동기화한다.
5. 구형 Python 검색 프로세스는 이전 실행 환경에서 종료한다. 새 서버는 구형 프로세스를 종료하거나 실행하지 않는다.
   새 검색을 확인한 뒤 기존 `ml-worker` 가상환경/모델/LanceDB를 정리할 수 있다.

기존 `homephoto.search.enabled`는 그대로 사용한다. 구형 `base-url`, `token`, `worker-dir`은
더 이상 사용하지 않으므로 설정에서 제거할 수 있다. 기존 폴더의 데이터는 자동 삭제하지 않는다.
**얼굴 검출용 `det_10g.onnx`, `w600k_r50.onnx`는 별도로 계속 필요하다.**

## 모델과 호환성

- 모델: `google/siglip2-base-patch16-224`, 사진/텍스트 768차원.
- ONNX: `onnx-community/siglip2-base-patch16-224-ONNX`, 리비전
  `ba1f3b0843f24bc5417d38e19c37b287d719b2f4`, FP32.
- 설치 스크립트와 JVM 로더 모두 모델 2개와 tokenizer의 고정 SHA-256을 검증한다.
  다운로드는 약 1.5GB이며 운영 추론은 오프라인이다. CPU 연산 스레드는 2개로 제한한다.
- 기존 400px 썸네일을 읽고 Pillow RGB bilinear와 같은 방식으로 224×224로 조정한다.
  채널별 `(pixel / 255 - 0.5) / 0.5`, 토큰 64개, L2 정규화를 적용한다.
- E5 설명·문서 검색과 SigLIP 사진 검색은 서로 다른 모델/인덱스를 유지한다.
- 얼굴은 SQLite의 기존 512차원 little-endian float 벡터를 사용한다. 얼굴 재검출이나 DB 초기화는 하지 않는다.

## 인덱스와 수명주기

기본 인덱스 위치는 `<storage-root>/vector-search/<모델 식별자>`다.
`index-dir`을 지정하면 그 아래에 모델별 하위 폴더를 만든다. 기존 LanceDB와 섞지 않는다.
첫 실행은 얼굴 벡터를 복사하고 사진 썸네일의 임베딩을 다시 계산한다.

- SQLite를 100개씩 읽고 진행 중에도 Lucene을 커밋한다. 재시작 시 사진 해시가 같으면 벡터를 재사용한다.
- 촬영일만 바뀌면 벡터 추론 없이 날짜 필터를 갱신한다. 날짜 미상 사진은 날짜 제한 검색에서 제외한다.
- 삭제·숨김 항목과 DB에서 사라진 얼굴은 제거한다. 전체 순회가 성공한 경우에만 누락 ID 정리를 수행한다.
- 한 사진의 썸네일 읽기/추론 실패는 다른 사진을 막지 않고 다음 순회에서 재시도한다.
- 조회 시 현재 DB의 삭제·숨김·원본 해시·얼굴 fingerprint와 소속 사진을 다시 확인한다.
- 얼굴 후보는 같은 사진을 검색 전에 제외한다. Lucene 점수를 코사인 유사도로 환산한 후 기존 임계값을 적용한다.
- **인덱싱 중지**는 현재 항목 처리 뒤 반영된다. 준비된 인덱스는 계속 검색할 수 있다.
  서버 재시작 시 `enabled: true`이면 자동으로 재개한다.
- 안전 종료의 활성 작업에 집계하며 모델 로딩/추론/검색 도중 리소스를 닫지 않는다.

## 개발 검증

```powershell
cd server
.\gradlew.bat test
```

실제 모델 비교는 기존 Python 환경에서 **합성 데이터만** 만든 뒤 JVM에서 비교한다.
Python은 이 비교 도구에서만 선택적으로 필요하며 설치/실행 의존성이 아니다.

```powershell
python scripts/search-reference.py OLD_MODEL_DIR server/build/search-reference.json
cd server
.\gradlew.bat siglipSmoke -PsiglipModelDir=../deploy/models/siglip2 -PsiglipReference=build/search-reference.json
```

검증 범위는 픽셀 리사이즈 일치, 한국어·영어·공백·긴 문장 토큰 일치,
이미지/텍스트 특징 벡터 오차, 실제 Lucene 순위 및 재열기다. 운영 사진 품질/처리량 검증과는 구분한다.

2026-10-08 검증 결과:

- 서버 전체 225개 테스트 통과(실패/오류/건너뜀 0).
- 합성 이미지 4종의 Pillow/JVM 리사이즈 픽셀 SHA-256 일치, 문장 4종의 토큰 일치.
- 기존 PyTorch와 JVM ONNX 특징 벡터 코사인 유사도 0.9999999 이상, 최대 절대 오차 약 `3.3e-7`.
- 최종 bootJar를 별도 포트·임시 SQLite·합성 썸네일로 실행하여 사진/얼굴 검색 API,
  중지 후 검색, DB 삭제 결과 제외, 안전 종료(exit 0) 확인.
- 운영 서버와 실제 사진 라이브러리는 변경하거나 검증하지 않았다. `deploy` 배포 JAR는 갱신하지 않았다.

패키지 검증 재현(검증 로그와 임시 DB는 시스템 임시 폴더에 보존):

```powershell
python scripts/smoke-jvm-search.py JAVA_EXE server/build/libs/homephoto-server-0.1.7.jar deploy/models/siglip2
```

참고: [원본 모델](https://huggingface.co/google/siglip2-base-patch16-224),
[고정 ONNX 리비전](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/tree/ba1f3b0843f24bc5417d38e19c37b287d719b2f4).
