# Seal Plus — 변경 사항 / Changes

Seal 기반 Android 미디어 다운로더 확장판.
An extended Android media downloader based on Seal.

## Seal 원본 대비 / Compared with upstream Seal

| 기능 / Feature | 한국어 / Korean | English |
| --- | --- | --- |
| 플러그인 / Plugins | 13개 기능 개별 활성화. 기본 비활성. 설정·기록 보존. | 13 independently enabled features. Disabled by default. Settings and history retained. |
| 소셜 미디어 / Social media | Instagram·TikTok 이미지·영상 및 혼합 게시글 선택 다운로드. | Selective downloads of Instagram and TikTok images, videos, and mixed posts. |
| 목록 다운로드 / List downloads | 계정·게시글 목록 조회. 개별·전체 다운로드. | Account and post lists. Individual or batch downloads. |
| 미디어 처리 / Media processing | AV1 변환·컨테이너 선택·기존 파일 재사용. | AV1 conversion, container selection, and existing file reuse. |
| 라이브러리 / Library | 폴더·해상도별 분류. 이동한 파일과 게시글 연결. | Folder and resolution sorting. Post matching for moved files. |
| 대기열 / Queue | 게시글 묶음·정렬·선택 관리. 5개씩 추가 표시. | Grouped posts, sorting, and selection. Load five more at a time. |
| 입력·메뉴 / Input and menus | 통합 입력·메뉴 즐겨찾기·빠른 쿠키 선택. | Unified input, menu favorites, and quick cookie selection. |
| AI 이미지 / AI images | 이미지 복원·확대. 공유 모델·상세 조정·비교·교체 또는 복제. | Image restoration and upscaling. Shared models, controls, comparison, replace or save a copy. |
| AI 동영상 / AI videos | QuickSRNet Small 기본·간편 최대 Medium. 모델 공유·FPS 선택·RIFE Vulkan 보간. | QuickSRNet Small by default; Medium for the maximum preset. Shared models, FPS selection, and RIFE Vulkan interpolation. |
| 자주 방문하는 계정 / Frequent accounts | 기본 정렬은 받은 게시글 수. 프로필·게시글 바로 열기. | Default sorting by downloaded post count. Open profiles and posts directly. |
| 게시글 정보 / Post details | 프로필 사진·계정명·본문·태그 표시 선택. | Optional avatars, usernames, captions, and hashtags. |
| 저장폴더 / Save folders | 기존 폴더 등록·요청별 선택·퀵 폴더 전환. | Register existing folders, choose per request, and switch quick folders. |
| 얼굴 분류 / Face sorting | YuNet·MediaPipe 판별. NOFACE 게시글 연결 유지·변경 작업 제외. | YuNet and MediaPipe detection. NOFACE keeps post matching and is excluded from processing. |
| 공유 메뉴 / Share menu | 빠른 다운로드만 기본 표시. 한 기능 선택 시 바로 실행. | Quick download is the default share action. A single selected action opens directly. |
| 뷰어 / Viewer | 전체 화면·핀치 확대·반투명 배율. 재생바·음량 분리. | Full screen, pinch zoom, and translucent zoom indicator. Separate seek and volume controls. |
| 다운로드창 / Download dialog | 형식 선택 한 줄·링크 입력 두 줄. 재시도·쿠키·로그 보기 한 줄. | One format row, two link-input lines, and one row for retry, cookies, and logs. |
| 쿠키 / Cookies | 재시도 쿠키는 현재 요청에만 적용. | Retry cookies apply only to the current request. |
| 공통 구조 / Shared structure | 파사드·팩토리·전략/어댑터·템플릿. 공통 기능은 항시 활성. | Facades, factories, strategies/adapters, and templates. Shared services are always enabled. |

## 빌드·개발 / Build and development

- Android 7.0 이상 / Android 7.0 or later.
- Windows 포터블 환경 / Portable Windows toolchain: JDK·SDK·Gradle·AVD → `.build/`.
- 원본 소스 보존·확장 코드 분리 / Upstream sources preserved; extensions in `app/src/main/java/com/plugin/`.
- GPU·NPU: 호환 모델·기기 필요 / GPU/NPU: compatible models and devices required.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File Setup-Portable.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1
```

- [설치·빌드 안내 / Setup and build guide](.build/README.md)
- [플러그인·공통 API / Plugins and shared APIs](app/src/main/java/com/plugin/README.md)
- [의존성 버전 / Dependency versions](gradle/libs.versions.toml)

RIFE 네이티브 빌드: NDK·CMake 준비 후 `.build/Seal/native/rife/Build.ps1` 실행.
RIFE native build: install NDK and CMake, then run `.build/Seal/native/rife/Build.ps1`.

## 원본 Seal 안내 / Upstream Seal information

아래 배지·다운로드·스크린샷·연락처는 원본 Seal 기준.
The badges, downloads, screenshots, and contacts below refer to upstream Seal.

<div align="center">

<img width="" src="fastlane/metadata/android/en-US/images/icon.png"  width=160 height=160  align="center">

# Seal

### Video/Audio Downloader for Android


English
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-zh_Hans.md">简体中文</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-zh_Hant.md">繁體中文</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-ar.md">العربية</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-pt.md">Portuguese</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-ua.md">Українська</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-th.md">ภาษาไทย</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-fa.md">فارسی</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-it.md">Italiano</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-az.md">Azərbaycanca</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-ru.md">Русский</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-sr.md">Српски</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-ja.md">日本語</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-id.md">Indonesia</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-hi.md">हिंदी</a>
&nbsp;&nbsp;| &nbsp;&nbsp;
<a href="https://github.com/JunkFood02/Seal/blob/main/translations/README-bn.md">বাংলা</a>



[![F-Droid](https://img.shields.io/f-droid/v/com.junkfood.seal?color=b4eb12&label=F-Droid&logo=fdroid&logoColor=1f78d2)](https://f-droid.org/en/packages/com.junkfood.seal)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/JunkFood02/Seal?color=black&label=Stable&logo=github)](https://github.com/JunkFood02/Seal/releases/latest/)
[![GitHub release (latest by date including pre-releases)](https://img.shields.io/github/v/release/JunkFood02/Seal?include_prereleases&label=Preview&logo=Github)](https://github.com/JunkFood02/Seal/releases/)
[![Keep a Changelog](https://img.shields.io/badge/Changelog-lightgray?style=flat&color=gray&logo=keep-a-changelog)](https://github.com/JunkFood02/Seal/blob/main/CHANGELOG.md)
[![GitHub all releases](https://img.shields.io/github/downloads/JunkFood02/Seal/total?label=Downloads&logo=github)](https://github.com/JunkFood02/Seal/releases/)
[![GitHub Repo stars](https://img.shields.io/github/stars/JunkFood02/Seal?style=flat&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0idXRmLTgiPz4KPHN2ZyBoZWlnaHQ9IjI0IiB2aWV3Qm94PSIwIC05NjAgOTYwIDk2MCIgd2lkdGg9IjI0IiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPgogIDxwYXRoIGQ9Im0zNTQtMjQ3IDEyNi03NiAxMjYgNzctMzMtMTQ0IDExMS05Ni0xNDYtMTMtNTgtMTM2LTU4IDEzNS0xNDYgMTMgMTExIDk3LTMzIDE0M1pNMjMzLTgwbDY1LTI4MUw4MC01NTBsMjg4LTI1IDExMi0yNjUgMTEyIDI2NSAyODggMjUtMjE4IDE4OSA2NSAyODEtMjQ3LTE0OUwyMzMtODBabTI0Ny0zNTBaIiBzdHlsZT0iZmlsbDogcmdiKDI0NSwgMjI3LCA2Nik7Ii8%2BCjwvc3ZnPg%3D%3D&color=%23f8e444)](https://github.com/JunkFood02/Seal/stargazers)
[![Supported-Sites](https://img.shields.io/badge/Sites-9cf?style=flat&logo=data:image/svg+xml;base64,PD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0idXRmLTgiPz4KPHN2ZyBoZWlnaHQ9IjI0cHgiIHZpZXdCb3g9IjAgMCAyNCAyNCIgd2lkdGg9IjI0cHgiIGZpbGw9IiNGRkZGRkYiIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyI+CiAgPHBhdGggZD0iTTAgMGgyNHYyNEgwVjB6IiBmaWxsPSJub25lIi8+CiAgPHBhdGggZD0iTTExLjk5IDJDNi40NyAyIDIgNi40OCAyIDEyczQuNDcgMTAgOS45OSAxMEMxNy41MiAyMiAyMiAxNy41MiAyMiAxMlMxNy41MiAyIDExLjk5IDJ6bTYuOTMgNmgtMi45NWMtLjMyLTEuMjUtLjc4LTIuNDUtMS4zOC0zLjU2IDEuODQuNjMgMy4zNyAxLjkxIDQuMzMgMy41NnpNMTIgNC4wNGMuODMgMS4yIDEuNDggMi41MyAxLjkxIDMuOTZoLTMuODJjLjQzLTEuNDMgMS4wOC0yLjc2IDEuOTEtMy45NnpNNC4yNiAxNEM0LjEgMTMuMzYgNCAxMi42OSA0IDEycy4xLTEuMzYuMjYtMmgzLjM4Yy0uMDguNjYtLjE0IDEuMzItLjE0IDJzLjA2IDEuMzQuMTQgMkg0LjI2em0uODIgMmgyLjk1Yy4zMiAxLjI1Ljc4IDIuNDUgMS4zOCAzLjU2LTEuODQtLjYzLTMuMzctMS45LTQuMzMtMy41NnptMi45NS04SDUuMDhjLjk2LTEuNjYgMi40OS0yLjkzIDQuMzMtMy41NkM4LjgxIDUuNTUgOC4zNSA2Ljc1IDguMDMgOHpNMTIgMTkuOTZjLS44My0xLjItMS40OC0yLjUzLTEuOTEtMy45NmgzLjgyYy0uNDMgMS40My0xLjA4IDIuNzYtMS45MSAzLjk2ek0xNC4zNCAxNEg5LjY2Yy0uMDktLjY2LS4xNi0xLjMyLS4xNi0ycy4wNy0xLjM1LjE2LTJoNC42OGMuMDkuNjUuMTYgMS4zMi4xNiAycy0uMDcgMS4zNC0uMTYgMnptLjI1IDUuNTZjLjYtMS4xMSAxLjA2LTIuMzEgMS4zOC0zLjU2aDIuOTVjLS45NiAxLjY1LTIuNDkgMi45My00LjMzIDMuNTZ6TTE2LjM2IDE0Yy4wOC0uNjYuMTQtMS4zMi4xNC0ycy0uMDYtMS4zNC0uMTQtMmgzLjM4Yy4xNi42NC4yNiAxLjMxLjI2IDJzLS4xIDEuMzYtLjI2IDJoLTMuMzh6IiBzdHlsZT0iZmlsbDogcmdiKDE2MiwgMTk4LCAyMzQpOyIvPgo8L3N2Zz4=&label=Supported)](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md)
[![Telegram Channel](https://img.shields.io/badge/Telegram-Seal-blue?style=flat&logo=telegram)](https://t.me/seal_app)
[![Matrix](https://img.shields.io/matrix/seal-space%3Amatrix.org?server_fqdn=matrix.org&style=flat&logo=element&label=Matrix&color=%230DBD8B)
](https://matrix.to/#/#seal-space:matrix.org)


</div>


## 📱 Screenshots

<div align="center">
<div>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" width="30%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/9.jpg" width="30%" />
</div>
</div>

<br>

## 📖 Features

- Download videos and audio files from video platforms supported by [yt-dlp](https://github.com/yt-dlp/yt-dlp) (formerly youtube-dl).

- Embed metadata and video thumbnail into extracted audio files supported by [mutagen](https://github.com/quodlibet/mutagen).

- Download all videos in the playlist with one click.

- Use embedded [aria2c](https://github.com/aria2/aria2) as external downloader for all your downloads.

- Embed subtitles into the downloaded videos.

- Execute custom yt-dlp commands with templates.

- Manage in-app downloads and custom command templates.

- Easy to use and user-friendly.

- [Material Design 3](https://m3.material.io/) style UI, with dynamic color theme.

- MAD: UI and logic written with pure Kotlin. Single activity, no fragments, only composable destinations.



## ⬇️ Download

For most devices, it is recommended to install the **arm64-v8a** version of the apks

- Download the latest stable version from [GitHub releases](https://github.com/JunkFood02/Seal/releases/latest)
  - Install the [pre-release](https://github.com/JunkFood02/Seal/releases/) versions to help us test out new features & changes

- Stable releases are also available on [F-Droid](https://f-droid.org/packages/com.junkfood.seal/)

<!-- [<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="70">](https://f-droid.org/packages/com.junkfood.seal/) -->

## 💬 Contact

Join our [Telegram Channel](https://t.me/seal_app) or [Matrix Space](https://matrix.to/#/#seal-space:matrix.org) for discussion, announcements, and releases!

## 💖 Sponsors

<p><!-- sponsors --><a href="https://github.com/Opposum102"><img src="https:&#x2F;&#x2F;github.com&#x2F;Opposum102.png" width="60px" alt="User avatar: " /></a><!-- sponsors --></p>


Seal will be always free and open source for everyone. If you like it, please consider [sponsoring me](https://github.com/sponsors/JunkFood02)!

## 🤝 Contributing

Contributions are welcome!

You can help translate Seal on [Hosted Weblate](https://hosted.weblate.org/projects/seal/).
	
[![Translate status](https://hosted.weblate.org/widgets/seal/-/strings/multi-auto.svg)](https://hosted.weblate.org/engage/seal/)
	
>[!Note]
>
>For submitting bug reports, feature requests, questions, or any other ideas to improve, please read [CONTRIBUTING.md](https://github.com/JunkFood02/Seal/blob/main/CONTRIBUTING.md) for instructions and guidelines first.

## ⭐️ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=JunkFood02/Seal&type=Timeline)](https://star-history.com/#JunkFood02/Seal&Timeline)


## 🧱 Credits

Seal is a simple GUI of [yt-dlp](https://github.com/yt-dlp/yt-dlp), based on [youtubedl-android](https://github.com/yausername/youtubedl-android)

Some of the UI designs and codes are borrowed from [Read You](https://github.com/Ashinch/ReadYou) and [Music You](https://github.com/Kyant0/MusicYou)

[dvd](https://github.com/yausername/dvd)

[Material color utilities](https://github.com/material-foundation/material-color-utilities)

[Monet](https://github.com/Kyant0/Monet)

## 📃 License

[![GitHub](https://img.shields.io/github/license/JunkFood02/Seal?style=for-the-badge)](https://github.com/JunkFood02/Seal/blob/main/LICENSE)

>[!Warning]
>
>Except for the source code licensed under the GPLv3 license,
>all other parties are prohibited from using Seal's name as a downloader app,
>and the same is true for Seal's derivatives.
>Derivatives include but are not limited to forks and unofficial builds.

<div align="right">
<table><td>
<a href="#start-of-content">👆 Scroll to top</a>
</td></table>
</div>
