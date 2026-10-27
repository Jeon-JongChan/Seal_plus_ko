<div align="center">

# Seal Plus

**다운로드부터 정리와 AI 보정까지 확장한 Android 미디어 도구**<br>
**An Android media toolkit extending downloads with organization and AI enhancement**

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

[주요 개선점 · Improvements](#improvements) · [시작하기 · Getting started](#getting-started) · [빌드 · Build](#build) · [기여 · Contributing](#contributing)

</div>

Seal Plus는 [Seal](https://github.com/JunkFood02/Seal)을 기반으로 소셜 미디어 다운로드, 라이브러리 관리, 이미지·영상 AI 처리 기능을 확장한 비공식 파생 프로젝트입니다.<br>
Seal Plus is an unofficial fork of [Seal](https://github.com/JunkFood02/Seal), adding social media downloads, library management, and AI processing for images and videos.

> **기존 Seal의 기능 소개·사용법·스크린샷은 [원본 README](https://github.com/JunkFood02/Seal#readme)를 참고하세요. 이 문서는 현재 프로젝트에서 추가하거나 개선한 내용을 중심으로 설명합니다.**<br>
> **For the original Seal features, usage, and screenshots, see the [upstream README](https://github.com/JunkFood02/Seal#readme). This document focuses on additions and improvements in this project.**

<a id="improvements"></a>
## Seal 대비 주요 개선점
*Improvements over Seal*

### 소셜 미디어와 다운로드 관리
*Social media and download management*

- **게시물 단위 다운로드:** Instagram·TikTok의 이미지, 영상, 혼합 게시물에서 필요한 항목을 선택하고 계정·게시물 목록을 처리합니다.<br>
  **Post-based downloads:** Select images and videos from Instagram and TikTok posts, including mixed posts, and process account and post lists.
- **확장 대기열:** 게시물별 묶음, 상세 정보, 정렬, 다중 선택, 재시도와 5개씩 추가 표시를 지원합니다.<br>
  **Extended queue:** Group items by post, view details, sort, select multiple items, retry downloads, and load five more posts at a time.
- **빠른 입력과 공유:** 통합 입력, 메뉴 즐겨찾기, 빠른 쿠키 선택과 Android 공유 메뉴의 다운로드 동작을 제공합니다.<br>
  **Faster input and sharing:** Use unified input, menu favorites, quick cookie selection, and download actions from the Android share menu.
- **계정과 게시물 정보:** 자주 방문하는 계정, 받은 게시글 수 정렬, 프로필·설명·해시태그를 확인합니다.<br>
  **Accounts and post details:** Access frequent accounts, sort by downloaded post count, and view profiles, captions, and hashtags.

### 라이브러리와 저장 관리
*Library and storage management*

- **폴더와 화질별 분류:** 이미지·영상을 해상도별로 정리하고, 기존 저장폴더를 요청마다 선택하거나 퀵 폴더로 지정합니다.<br>
  **Folder and resolution sorting:** Organize images and videos by resolution, choose existing folders per request, or assign a quick folder.
- **파일 재사용과 경로 복구:** 기존 파일을 재사용하고 이동한 미디어를 이력과 다시 연결합니다. AV1 변환과 컨테이너 선택도 지원합니다.<br>
  **File reuse and path recovery:** Reuse existing files and reconnect moved media with download history. AV1 conversion and container selection are also available.
- **통합 미디어 뷰어:** 전체 화면, 이미지 확대, 영상 탐색과 음량 조절을 지원합니다.<br>
  **Integrated media viewer:** View media full screen, zoom images, seek through videos, and adjust volume.
- **임시 숨기기:** 내부 저장소에서 다운로드·대기열·이력을 분리해 사용한 뒤 통합하거나 폐기합니다.<br>
  **Temporary hidden workspace:** Keep downloads, queue items, and history separate in internal storage, then merge or discard them.

### 이미지·영상 AI 보정과 중복 정리
*AI enhancement and duplicate management*

- **이미지 보정:** 복원·업스케일, 추가 모델 가져오기, 원본·결과 비교와 교체·복제 저장을 지원합니다.<br>
  **Image enhancement:** Restore and upscale images, import additional models, compare results with originals, and save by replacing or creating a copy.
- **영상 보정:** QuickSRNet 기반 해상도 개선과 RIFE 기반 Vulkan GPU 프레임 보간을 제공하며 이미지와 모델 관리 기능을 공유합니다.<br>
  **Video enhancement:** Improve resolution with QuickSRNet and interpolate frames with RIFE on Vulkan GPUs, using shared model management with image enhancement.
- **실행 장치 선택:** 보정 엔진과 모델에 따라 NPU·GPU·CPU 실행 옵션을 제공합니다. 실제 가속 지원과 처리 속도는 기기·드라이버·모델에 따라 달라집니다.<br>
  **Execution options:** Choose NPU, GPU, or CPU options depending on the enhancement engine and model. Acceleration support and speed vary by device, driver, and model.
- **중복 후보 검토:** 이미지·영상의 유사 후보를 찾고 보존 우선순위와 제외 기록을 적용하며, 삭제는 사용자 승인 후 진행합니다.<br>
  **Duplicate review:** Find similar images and videos, apply retention priorities and exclusions, and delete only after user approval.

### 동일인물 폴더 분류
*Person-based folder sorting*

- **참조 사진과 주의대상:** 대상 사진을 최대 5장 등록하고, 닮은 다른 인물을 주의대상으로 추가해 혼동을 줄입니다.<br>
  **Reference and caution photos:** Register up to five reference photos and add lookalike people as caution references to reduce confusion.
- **고정 참조와 캐시:** 실행 중 참조 특징을 고정해 재사용하고, 이미지·영상 분석 캐시로 반복 처리를 줄입니다.<br>
  **Fixed references and caching:** Reuse fixed reference embeddings during each run and reduce repeated analysis with image and video caches.
- **영상의 여러 시점 확인:** 여러 시점의 얼굴 특징으로 판정하며, 새 판정에 필요한 정보가 없는 구형 영상 캐시는 해당 영상 분류 시 한 번 재분석합니다.<br>
  **Multiple video timestamps:** Evaluate faces across multiple timestamps. Older video caches missing the required features are reanalyzed once when that video is classified.
- **분류 범위 설정:** 하위폴더 포함 여부와 다른 인물명으로 분류된 파일 건너뛰기를 선택하고, 일치 파일을 지정한 인물 폴더로 이동합니다.<br>
  **Scope controls:** Choose whether to include subfolders or skip files classified under other person names, then move matching files into the selected person's folder.
- **애매한 결과 보류:** 근거가 부족하거나 주의대상과 가까운 결과는 보류합니다. 얼굴이 없는 이미지를 `NOFACE`로 분류하는 기능도 별도로 제공합니다.<br>
  **Uncertain results are held:** Hold results with insufficient evidence or close matches to caution references. A separate feature sorts images without faces into `NOFACE` folders.

동일인물 판정은 오탐·누락이 발생할 수 있으며, 닮은 얼굴을 완전히 구분한다고 보장하지 않습니다.<br>
Person matching can produce false matches and misses; it does not guarantee perfect separation of lookalike faces.

### 많은 파일을 다루는 UI와 처리 흐름
*UI and processing for larger libraries*

- **불필요한 전체 조회 감소:** 앱 시작과 홈 화면의 반복 전체 파일 조회를 줄이고, 필요한 경로와 게시물 중심으로 이력·대기열을 갱신합니다.<br>
  **Fewer full-library queries:** Reduce repeated full-file lookups at startup and on the home screen, and update history and queue data around relevant paths and posts.
- **간결한 안내:** 긴 설명은 한 줄 요약으로 표시하고, 자세한 원리·옵션은 도움말 아이콘이나 펼치기로 확인합니다.<br>
  **Compact guidance:** Show a one-line summary and reveal detailed explanations and options through help icons or expandable sections.
- **백그라운드 처리:** AI 분류의 진행 상황·로그·취소를 제공해 작업 상태를 확인할 수 있습니다.<br>
  **Background processing:** Monitor AI sorting through progress, logs, and cancellation controls.

<a id="getting-started"></a>
## 시작하기
*Getting started*

1. Android 7.0 이상 기기에서 이 저장소의 [릴리스](https://github.com/Jeon-JongChan/Seal_plus_ko/releases)에 APK가 제공되는지 확인하거나 아래 안내로 직접 빌드합니다.<br>
   On an Android 7.0 or later device, check this repository's [releases](https://github.com/Jeon-JongChan/Seal_plus_ko/releases) for an APK, or build one using the instructions below.
2. 앱의 플러그인 관리 화면에서 필요한 선택 기능을 켭니다. 선택 플러그인은 처음에는 꺼져 있으며 필수 호환 기반은 항상 활성화됩니다.<br>
   Enable the optional features you need in the plugin management screen. Optional plugins start disabled; the required compatibility layer remains active.
3. 다운로드 폴더와 필요한 접근 권한을 설정한 뒤 링크를 입력하거나 Android 공유 메뉴로 전달합니다.<br>
   Set the download folder and required access permissions, then enter a link or send it through the Android share menu.
4. AI 기능은 모델과 실행 장치를 설정한 뒤 사용합니다. 일부 모델·선택 런타임은 처음 사용할 때 추가 다운로드가 필요합니다.<br>
   Configure models and execution devices before using AI features. Some models and optional runtimes require an additional download on first use.

선택 기능은 같은 APK에 포함된 내장 플러그인이며 별도 설치·업데이트하는 외부 플러그인이 아닙니다.<br>
Optional features are built-in plugins within the same APK, rather than externally installed or independently updated plugins.

<a id="build"></a>
## 소스에서 빌드하기
*Building from source*

Windows PowerShell에서 프로젝트 루트를 기준으로 실행합니다. 최초 구성에는 네트워크 연결과 Android SDK 라이선스 동의가 필요합니다.<br>
Run the following from the project root in Windows PowerShell. Initial setup requires network access and acceptance of the Android SDK licenses.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Setup-Portable.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1
```

기본 명령은 Generic Debug APK를 생성합니다. 릴리스 APK가 필요하면 다음 작업을 지정합니다.<br>
The default command builds a Generic Debug APK. Specify the following task to build a release APK.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 :app:assembleGenericRelease
```

서명 설정, 네이티브 RIFE 준비, 오프라인 빌드와 출력 경로는 [빌드 도구 안내](.build/README.md)와 [빌드 및 배포 안내서](.docs/빌드%20및%20배포%20안내서.md)를 참고하세요.<br>
For signing configuration, native RIFE setup, offline builds, and output paths, see the [build tooling guide](.build/README.md) and [build and release guide](.docs/빌드%20및%20배포%20안내서.md).

<a id="contributing"></a>
## 개발과 기여
*Development and contributing*

버그 제보와 개선 제안은 [이 저장소의 이슈](https://github.com/Jeon-JongChan/Seal_plus_ko/issues)에 앱 버전, 기기·Android 버전, 재현 순서와 관련 로그를 함께 남겨 주세요. 로그에 포함된 쿠키·토큰 등 개인정보는 제거해 주세요.<br>
Report bugs and suggest improvements in [this repository's issues](https://github.com/Jeon-JongChan/Seal_plus_ko/issues), including the app version, device and Android version, reproduction steps, and relevant logs. Remove cookies, tokens, and other private information from logs.

확장 구현은 `app/src/main/java/com/plugin/`을 중심으로 관리합니다. `app/src/**/com/junkfood/` 아래 원본 소스는 보존하며, 변경 전 [프로젝트 개발 규칙](AGENTS.md)과 [플러그인 구조 안내](app/src/main/java/com/plugin/README.md)를 확인하세요.<br>
Extensions are maintained primarily under `app/src/main/java/com/plugin/`. Original sources under `app/src/**/com/junkfood/` are preserved. Read the [project development rules](AGENTS.md) and [plugin architecture guide](app/src/main/java/com/plugin/README.md) before making changes.

일부 개발 문서는 한국어로 작성되어 있습니다. 이 README는 각 한국어 설명 바로 아래에 영어 번역을 제공합니다.<br>
Some development documents are written in Korean. This README provides an English translation directly below each Korean explanation.

## 원본 프로젝트와 라이선스
*Upstream project and license*

이 프로젝트는 [JunkFood02/Seal](https://github.com/JunkFood02/Seal)과 기여자들의 작업을 기반으로 하며, 다운로드 기반에는 [yt-dlp](https://github.com/yt-dlp/yt-dlp)와 [youtubedl-android](https://github.com/yausername/youtubedl-android)를 사용합니다.<br>
This project builds on the work of [JunkFood02/Seal](https://github.com/JunkFood02/Seal) and its contributors, using [yt-dlp](https://github.com/yt-dlp/yt-dlp) and [youtubedl-android](https://github.com/yausername/youtubedl-android) for downloads.

소스 코드 라이선스는 [GNU GPL v3](LICENSE)입니다. 원본의 전체 크레딧과 명칭 관련 안내는 [Seal README](https://github.com/JunkFood02/Seal#readme)를, 포함된 모델·런타임은 각 배포물의 라이선스를 참고하세요.<br>
The source code is licensed under [GNU GPL v3](LICENSE). See the [Seal README](https://github.com/JunkFood02/Seal#readme) for full upstream credits and naming guidance, and each distributed model or runtime for its own license.
