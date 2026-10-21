# 중복 분석 모델

Small·Large 모델은 화면의 다운로드 버튼으로 명시적으로 설치하고, 고정 SHA-256과 크기 검증을 통과한 앱 전용 `files/duplicate-models/` 파일만 LiteRT에 전달한다. 사용하지 않는 저장소 내 모델 사본은 제거했으며 APK에도 포함하지 않는다. 자동 다운로드·다른 모델로의 임의 변경은 하지 않는다. 다운로드·취소·원자적 저장은 화질 개선의 공통 다운로더를 재사용하며 모델 목록과 저장 위치는 분리한다.

LiteRT 2.3.0 CompiledModel로 실행한다. 기본 옵션은 NPU·GPU 자동이며 준비된 NPU → GPU → CPU 순서로 초기화·추론을 시도한다. GPU 우선과 CPU도 선택할 수 있다. NPU는 기존 플러그인 설정에서 런타임 설치가 필요하며 분석 중 다운로드하지 않는다. NPU 요청 경로가 완료되어도 공개 API로 실제 연산 분배는 확인할 수 없어 화면에 이를 명시한다.

입력은 모델 메타데이터와 같은 224×224 NHWC RGB FP32, mean=0·std=255를 사용한다. Small은 1024차원, Large는 1280차원이다. GPU 세션의 생성·추론·해제를 분석·제외 재분석·삭제 전 재검증 모두 같은 전용 스레드에서 실행한다. 전처리 변경 전 특징 캐시는 버전으로 구분한다.

| 모델 | 고정 배포본 | SHA-256 |
| --- | --- | --- |
| Small | [Google 공식 float32/1](https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite) | `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40` |
| Large | [Google 공식 float32/1](https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite) | `11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d` |

[모델 안내](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder), [Android API 안내](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder/android).

임베딩 유사도만으로 삭제를 확정하지 않는다. SHA-256 완전 일치 또는 dHash·축소 RGB 구조 유사도(SSIM)·시간순 대표 프레임·AI 유사도의 동시 통과를 검토 후보로 제시한다. 프리셋 임계값은 기기별 정확도 보장이 아니며 보존본과 삭제 대상을 승인 전에 확인한다.
