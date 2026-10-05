# ml-worker — 사진·얼굴 벡터 검색 서비스

얼굴 검출과 특징 추출은 JVM 서버 내부 FaceWorker로 이전했다. Python 얼굴 워커, 빌드 스크립트, 실행 파일 패키징은 제거했다.

이 폴더의 `search_service.py`, `face_search.py`와 `.venv-search`는 사진 의미 검색 및 이미 생성된 얼굴 벡터 검색을 담당한다. 얼굴 검출 모델을 실행하는 워커가 아니므로 그대로 사용한다.

- [JVM 얼굴 인식 전환 안내](../docs/JVM-FACE-RECOGNITION.md)
- [사진 의미 검색 안내](../docs/MCP-SEMANTIC-SEARCH.md)

기존 `.venv`, `dist/homephoto-ml-worker.exe`는 새 서버가 사용하지 않는다. 실행 중인 구형 워커는 종료하고 제거해도 된다. 모델 파일 `det_10g.onnx`, `w600k_r50.onnx`는 JVM에서도 필요하므로 유지한다.
