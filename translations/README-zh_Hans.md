<div align="center">

<img width="" src="fastlane/metadata/android/en-US/images/icon.png"  width=160 height=160  align="center">

# Seal



### 一个简单的 Android 视频/音频下载器，使用 Jetpack Compose 进行开发

<p align="center">
<a href="https://github.com/JunkFood02/Seal/blob/main/README.md">English</a>
&nbsp;&nbsp;|&nbsp;&nbsp;
简体中文
</p>

[![F-Droid](https://img.shields.io/f-droid/v/com.junkfood.seal?color=b4eb12&label=F-Droid&logo=fdroid&logoColor=1f78d2)](https://f-droid.org/en/packages/com.junkfood.seal)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/JunkFood02/Seal?color=black&label=Stable&logo=github)](https://github.com/JunkFood02/Seal/releases/latest/)
[![GitHub release (latest by date including pre-releases)](https://img.shields.io/github/v/release/JunkFood02/Seal?include_prereleases&label=Preview&logo=Github)](https://github.com/JunkFood02/Seal/releases/)
[![Keep a Changelog](https://img.shields.io/badge/Changelog-lightgray?style=flat&color=gray&logo=keep-a-changelog)](https://github.com/JunkFood02/Seal/blob/main/CHANGELOG.md)
[![GitHub all releases](https://img.shields.io/github/downloads/JunkFood02/Seal/total?label=Downloads&logo=github)](https://github.com/JunkFood02/Seal/releases/)
[![GitHub Repo stars](https://img.shields.io/github/stars/JunkFood02/Seal?color=informational&label=Stars)](https://github.com/JunkFood02/Seal/stargazers)
[![Supported Sites](https://img.shields.io/badge/Supported-Sites-9cf.svg?style=flat)](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md)
[![Telegram Channel](https://img.shields.io/badge/Telegram-Seal-blue?style=flat&logo=telegram)](https://t.me/seal_app)
[![Matrix Space](https://img.shields.io/badge/Matrix-Seal-Black?style=flat&color=black&logo=matrix)](https://matrix.to/#/#seal-space:matrix.org)

</div>
## 屏幕截图

<div>
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" width="30%" />
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/9.jpg" width="30%" />
</div>


## 功能特色

- 从 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 所支持的数千个视频平台下载视频与音频

- 提取媒体元数据与专辑封面，调用 [mutagen](https://github.com/quodlibet/mutagen) 嵌入到提取的音频文件中

- 播放列表下载支持

- 使用 [aria2c](https://github.com/aria2/aria2) 进行下载

- 内嵌字幕于视频文件中

- 执行自定义的 yt-dlp 命令模板

- 管理应用内下载与自定义命令模板

- 使用简单、用户友好

- 遵循 [Material Design 3](https://m3.material.io/) 设计规范，实现了 [动态色彩](https://m3.material.io/foundations/customization) 主题的应用界面
  
- MAD：完全使用 Kotlin 构造界面与编写逻辑，单 Activity + Compose Navigation 应用结构

## 下载

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
alt="Get it on F-Droid"
height="80">](https://f-droid.org/packages/com.junkfood.seal/)

你也可以从 [releases](https://github.com/JunkFood02/Seal/releases) 获取最新的 apk

## 贡献

欢迎贡献！

你可以在 [Hosted Weblate](https://hosted.weblate.org/projects/seal/)  参与 Seal 的翻译

[![Translate status](https://hosted.weblate.org/widgets/seal/-/multi-auto.svg)](https://hosted.weblate.org/engage/seal/)

对于错误报告、功能请求或其他改进的想法，请先在 Issue 和 Discussion 中进行搜索（包括已关闭的 Issue）。如果没有出现重复，欢迎 [发起讨论](https://github.com/JunkFood02/Seal/discussions) 或 [提交问题](https://github.com/JunkFood02/Seal/issues/new)。

Seal 被设计为 yt-dlp 的一个简单的 GUI 封装，所以我们不会接受 yt-dlp 不支持的功能请求。

## 致谢

[youtubedl-android](https://github.com/yausername/youtubedl-android)

[yt-dlp](https://github.com/yt-dlp/yt-dlp)

[Read You](https://github.com/Ashinch/ReadYou)

[Music You](https://github.com/Kyant0/MusicYou)

[dvd](https://github.com/yausername/dvd)

[Material color utilities](https://github.com/material-foundation/material-color-utilities)

## 许可证

[GNU GPL v3.0](https://github.com/JunkFood02/Seal/blob/main/LICENSE)

## Seal Plus 현재 변경 요약 (2026-10-09)

아래는 이 수정본의 한국어 변경 안내입니다. 위의 원본 설명·배포 링크와 별도로 적용되며, 원본 배포 APK에 이 기능들이 포함되어 있다고 보장하지 않습니다.

- 빠른 다운로드에서 쿠키 사용 여부·프로필 선택을 추가했습니다. 사용자가 지정한 경우에만 로그인 쿠키를 사용합니다.
- 미디어 파일 관리와 비트레이트 폴더 분류를 추가했습니다. 기본 경계는 4Mbps이고 1–100Mbps로 조정합니다. 체감 화질·AI 평가는 아닙니다.
- 다운로드 기록과 실제 파일·하위 폴더·선택한 SAF 트리를 확인하여 이동한 파일의 중복 다운로드를 줄입니다. 내용 해시나 영상 유사도 검사 기능은 아닙니다.
- Instagram 이미지 저장, 계정 이름 저장·feed/클립 페이지 조회, 혼합 캐러셀의 이미지·영상 개별 선택과 해상도 후보 선택을 추가했습니다. 접근 권한·API 변경·호출 제한의 영향을 받습니다.
- 선택 시 FFmpeg libsvtav1으로 AV1 변환 후 검증한 출력의 이름을 확정하고 원본을 대체합니다. 기본 CRF는 32이며 변환·폴더 분류는 기본 비활성화입니다.
- 여러 HTTP(S) 링크 입력 처리를 개선하고 AGP 8.7.3·Room 2.7.2/KSP2·OkHttp 정식판 4.12.0·Coil 2.7.0·다운로드 관련 래퍼 0.18.1 등으로 조정했습니다. Kotlin 2.0.20·Gradle 8.10.2는 유지합니다.
- Windows x64용 `.build/` 포터블 JDK·SDK·캐시·에뮬레이터 환경을 추가했습니다. 현재 Git에서는 `.build/` 전체를 제외하므로 clone만으로 스크립트·도구·캐시가 전달되지 않습니다. 별도로 `.build/`를 복사하거나 JDK·SDK를 직접 준비해야 합니다.

단위 테스트 6개와 API 35 x86_64 Android 테스트 2개, 로컬 HTTP 다운로드·실제 AV1 변환·공백 경로 오프라인 빌드를 검증했습니다. YouTube·Instagram 실사이트·로그인 쿠키·대량 목록·SAF 공급자·ARM 실기기·16KB 환경은 미검증입니다. AI 유사 영상 판별·화질 향상은 구현하지 않았습니다.

상세 기능·버전·검증 범위는 [프로젝트 README](../README.md), 설치·실행·이동 명령은 [포터블 빌드 README](../.build/README.md)를 확인하세요. 로컬 작업 폴더에는 [다크모드 HTML 보고서](../.docs/20261009-0419-portable-build-and-code-report.html)도 있습니다. `.docs/`와 `.build/`는 Git 제외 경로이므로 로컬 보고서·포터블 README 링크는 해당 폴더를 함께 전달받아야 열립니다.
