## JunkFood 원본 코드 보존 (필수)

- `app/src/**/com/junkfood/` 아래 모든 소스(특히 `app/src/main/java/com/junkfood/`)는 읽기·비교만 허용하며 수정·추가·삭제·이동·주석 추가·자동 포맷을 금지한다.
- 새 기능, 버그 수정, 설정, 실행 연결은 `app/src/main/java/com/plugin/` 또는 원본 폴더 밖에서 구현한다. 기존 연결점을 사용한다.
- 원본 수정이 꼭 필요하면 대안과 대상 파일·이유를 설명하고 사용자의 명시적 허용을 받은 뒤 해당 범위만 수정한다. 일반적인 기능 구현 요청은 원본 수정 허용으로 해석하지 않는다.
- 빌드·생성·정리 스크립트도 이 경로의 소스를 덮어쓰지 않도록 한다.

## graphify

이 프로젝트의 지식 그래프와 모든 Graphify 캐시는 `.graphify/Seal_plus_ko/graphify-out/`에 둡니다.

- 코드베이스 질문은 이 위치에 `graph.json`이 있으면 Graphify로 먼저 조회합니다.
- PowerShell에서 Graphify 명령을 실행할 때마다 먼저 `$env:GRAPHIFY_OUT = ".graphify/Seal_plus_ko/graphify-out"`를 설정합니다. 이 설정은 현재 셸에만 적용됩니다.
- 소스 변경 후 그래프를 갱신할 때도 같은 설정을 한 뒤 `graphify update .`를 실행합니다.
- 출력, 캐시, 조회에 저장소 루트의 `graphify-out/`이나 전역 그래프 경로를 사용하지 않습니다.
