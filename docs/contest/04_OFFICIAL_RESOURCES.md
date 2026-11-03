# 다운로드와 공식 문서 목록

최초 자료 조사: 2026-11-02 KST. OT 다시보기·전체 스크립트 확인: 2026-11-03 KST.

이 목록은 실제 확인한 공식 페이지 및 해당 페이지에서 제공하는 자료를 정리한 것입니다. **다운로드 완료**, **공식 다운로드 경로 확인**, **링크만 확인**을 구분합니다. 개발 도구를 설치하거나 SDK 사용 신청을 완료한 목록이 아닙니다.

## 0. OT에서 소개한 웹사이트와 실제 메뉴

2026-11-03에 **Pleos Playground 실제 웹 화면에서 Discover의 두 페이지를 열고 Developer Journey의 접힌 6단계를 모두 펼쳤다.** Publish의 제출 문서·심사 하위 5개 문서, Workspace 진입 경로도 확인했다. 아래는 메뉴 탐색 지도이며 기능별 상세 기준은 연결된 02·03 문서에서 관리한다.

| 웹 메뉴·도구 | 실제 위치 | 확인한 역할·범위 |
|---|---|---|
| Discover → Resources & Emulator | [리소스 페이지](https://developers.pleos.ai/playground/discover/resources) | 5개 Connect SDK, 신규 SDK 계획, Fleet·Vehicle Data API, Emulator, Devbox 소개 본문 확인 |
| Discover → Developer Journey | [개발 단계](https://developers.pleos.ai/playground/discover/developer-journey) | 준비·환경·기획설계·개발연동·시험·등록의 6단계와 펼친 하위 항목 확인 |
| Docs → Pleos Connect | [빠른 시작](https://document.pleos.ai/docs/pleos-only/getting-started) | Android 앱·SDK·차량 UI·개발·테스트 자료. 웹에서 앱을 자동 제작하는 서비스로 소개된 것은 아님 |
| Docs → Pleos Fleet | [Fleet 개요](https://document.pleos.ai/api-reference/fleet-api/intro) | 별도 차량·단말 관리 REST API/Webhook. 사용 계약·승인 조건은 [시작 안내](https://document.pleos.ai/api-reference/fleet-api/getting-started)에서 확인 |
| Publish → App Submission | [제출 안내](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide) | 앱 마켓 등록·유통 심사 자료. 공모전 이메일 접수와 구분 |
| Publish → App Review Guidelines | [심사 목차](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide) | 콘텐츠·성능·주의분산·등록정보·개인정보의 5개 하위 본문 확인 |
| Workspace → My Project | [프로젝트](https://developers.pleos.ai/playground/console/projects) · [공개 설명](https://document.pleos.ai/docs/pleos-only/create-my-project) | 팀 멤버·프로젝트 ID·SDK 상태 관리. 실제 경로는 계정 인증으로 연결되어 내부 미열람 |
| Workspace → Pleos App Market Console | [배포 콘솔](https://appmarket.pleos.ai/console/) | 별도 로그인·가입 안내 화면 확인. 실제 앱 등록·심사 화면은 미열람 |
| Pleos Connect Virtual Console | `http://127.0.0.1:8088/` | PC의 실행 중인 Emulator에 연결하는 로컬 웹 조작 화면. 공개 Playground 계정 콘솔과 다름. [설명·연결 조건](02_PLEOS_DEVELOPMENT.md) 5절 참조 |

### Developer Journey의 펼친 하위 항목

| 단계 | 확인한 항목 | 이 저장소의 정리 위치 |
|---|---|---|
| 01 준비 | 앱 개발 시작, 프로젝트 생성·SDK 신청 | [개발 자료](02_PLEOS_DEVELOPMENT.md) 2~3절 |
| 02 환경 | Emulator 설치·설정, Virtual Console 셋업 | [개발 자료](02_PLEOS_DEVELOPMENT.md) 4~5절 |
| 03 기획·설계 | 안전·지역 규제·카테고리·화면/실행 모델·하드웨어·SDK 정책·API 호환·개인정보·성능, UI 시작·Foundations·Components | 아래 3~4절 및 [차량 설계 조건](03_VEHICLE_API.md) 5절 |
| 04 개발·연동 | 오디오·주행 제한·CCP·내비·미디어·선행 묵음·IME·FAQ, 5개 SDK, Gleo 스키마·콜백·예제·전달 | 아래 5절 및 [개발 자료](02_PLEOS_DEVELOPMENT.md) 6~9절 |
| 05 시험 | 폼 팩터, Virtual Console Emulation Controls | 아래 6절 |
| 06 등록 | 태그·업데이트·보안·심사 제출 | 아래 7절 |

**확인 경계:** 공개 메뉴와 관련 본문을 따라 확인했으며 SDK의 모든 클래스·메서드, 모든 디자인 컴포넌트, 계정 내부·실제 API 동작까지 확인한 것은 아니다. My Project의 계정 화면은 자동 승인 검토가 인증 정보 접근 위험을 이유로 열람을 차단하여 공개 설명으로 대체했다. 계정 생성·신청·제출·외부 설정 변경은 하지 않았다.

### 같은 포털에 있어도 구분할 서비스

- **Vehicle SDK:** 차량 내부 Pleos Connect 앱에서 사용하는 SDK. 상세 계약은 [차량 API 자료](03_VEHICLE_API.md)를 따른다.
- **Fleet API:** 계약 이후 담당자가 API 호스트를 제공하며, 프로젝트 자격 증명과 사용 승인이 필요하다. OT의 Connect SDK 개발 사전 승인 면제 안내를 Fleet에 그대로 적용하지 않는다. [Fleet 시작 안내](https://document.pleos.ai/api-reference/fleet-api/getting-started)
- **Vehicle Data API:** 커넥티드 카 데이터의 REST API다. 공식 소개에는 **2026년 8월 기준 유럽에서만 제공, 타 지역 준비 중**이라고 명시되어 있다. 2026-11-03 열람 시에도 이 안내가 남아 있다. 한국 공모전 환경에서 Vehicle SDK를 대체할 수 있다고 가정하지 않는다. [공식 소개](https://document.pleos.ai/api-reference/vehicle-data-api/intro)
- **Devbox:** 전용 테스트 하드웨어 소개는 확인했지만 공모전 참가팀 대여·배송·무상 제공 신청 경로는 이 페이지에 없다. 장비 제공 확정으로 기록하지 않는다. [리소스 소개](https://developers.pleos.ai/playground/discover/resources)
- **향후 SDK:** Resources의 클라우드 프로필·지도/장소/교통·모바일 연계 설명은 지원 확대 계획이다. 현재 사용할 수 있는 API 목록으로 옮기지 않는다.

## 1. 받을 수 있는 자료

| 자료 | 용도 | 제공 경로 | 보관/확인 상태 |
|---|---|---|---|
| 공모전 접수양식·개인정보 동의서 DOCX | 실제 접수·기획서 작성·전원 서명 | [공식 원본](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx) | **다운로드 및 본문 확인 완료**, 아래 보관본 |
| Pleos 개발 리소스 | 에뮬레이터 등 공식 개발 자료 탐색 | [Resources & Emulator](https://developers.pleos.ai/playground/discover/resources) | 포털 링크 확인 |
| Pleos Connect 에뮬레이터 | 차량 앱 개발·시연 | [설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) | 조사 당시 설치 경로 확인; 이후 설치 결과는 개발환경 구성 기록 참조 |
| Vehicle SDK 공식 샘플 | 차량 기능 지원 확인·조회·변경·구독 예제 | [GitHub](https://github.com/Pleos-Playground/pleos-sample-vehicle-app) | 저장소·README 확인, 소스 미다운로드 |
| NaviHelper 공식 샘플 | 내비 연동 예제 | [GitHub](https://github.com/Pleos-Playground/pleos-sample-navi-app) | 공식 문서의 연결 및 저장소 확인 |
| FusedLocation 공식 샘플 | 위치 연동 예제 | [GitHub](https://github.com/Pleos-Playground/pleos-sample-fusedlocation-app) | 공식 문서의 연결 및 저장소 확인 |
| Host Display Bridge ZIP | 폼 팩터 테스트용 호스트 도구 | [공식 ZIP](https://nexus-playground.pleos.ai/repository/raw-releases/host-display-bridge/1.0.0/host-display-bridge.zip) | 다운로드 경로 확인, 보관·실행하지 않음 |
| Pleos Connect display guide | 화면 구성용 Figma 자료 | [공식 `.fig`](https://document.pleos.ai/img/Pleos_Connect_display_guide_215.fig) | UI 가이드의 다운로드 링크와 바이너리 응답 확인. 미보관·Figma 내부 미열람 |
| Pleos Connect design assets | UI 디자인 자산 | [공식 `.fig`](https://document.pleos.ai/img/Pleos_Connect_design_assets_215.fig) | 위와 동일. 파일명의 `215`를 SDK 버전으로 해석하지 않음 |
| Pleos Connect CCP UX Guideline | 물리 컨트롤러 UX | [공식 `.fig`](https://document.pleos.ai/img/policy/Pleos-Connect-CCP-UX-guideline-20260220.fig) | CCP 가이드의 다운로드 링크 확인. PDF가 아닌 Figma 파일, 내부·사용 조건 미확인 |
| 온라인 설명회 다시보기 | 공모전 운영·개발 안내·Q&A | [영상](https://www.youtube.com/watch?v=na6tSJ44pWc) · [공지](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000005) | 전체 제공 스크립트·설명란 확인. 영상·자막 파일 미보관. [요약·확인 범위](05_ORIENTATION_NOTES.md) |

보관본: [공모전 접수양식·동의서](downloads/공모전_접수양식_개인정보동의서_2026-11-02.docx)

### 공식 접수양식 보관 기록

| 항목 | 기록 |
|---|---|
| 자료 | 공모전 접수양식 및 개인정보 수집·이용 동의서 |
| 공식 출처 | [원본 다운로드](https://appdevchallenge.auto-hmg.io/downloads/contest-application-consent.docx) |
| 다운로드 기준일 | 2026-11-02 KST |
| 프로젝트 정리·보관일 | 2026-11-03 KST |
| 보관 위치 | 위 보관본 링크 참조: `downloads/공모전_접수양식_개인정보동의서_2026-11-02.docx` |
| 확인 상태 | DOCX 구조·본문 확인. 미작성·미서명 원본이며 편집하지 않음 |
| 양식에 표시된 버전 | `1020 ver.` |
| 파일 크기 | 55,208 bytes |
| SHA-256 | `07be893d58b0e96ab7788f0d189d6829f0e59a2cee4bc18cb90ec37eeda2f5fd` |

제출 전에 공식 URL의 최신 원본과 비교합니다. `downloads/`에는 실제 받은 파일만 두고, 출처·다운로드일·버전·해시는 이 문서에서 관리합니다. 개발 도구의 실제 설치·실행 결과는 [개발환경 구성 기록](../05_DEVELOPMENT_SETUP.md)을 참조합니다.

### 에뮬레이터 다운로드 방식

공식 안내는 Android Studio → SDK Manager → SDK Update Sites에서 아래 경로를 등록하는 방식입니다. 일반 웹 앱용 실행환경이나 휴대전화 기본 이미지와 구분합니다.

| 환경 | 공식 시스템 이미지 피드 |
|---|---|
| Mac | [ARM64 이미지 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml) |
| Windows/Linux | [시스템 이미지 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml) |

문서상 에뮬레이터 버전은 2.1.0, 시스템 이미지는 API 34입니다. 앱 최소 지원 API 28과 에뮬레이터 API 34를 혼동하지 않습니다. Cold boot 및 RAM 4 GB 설정 안내를 따릅니다.

출처: [설치 및 실행 상세](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator).

### SDK 라이브러리와 샘플 사용

- SDK는 각 SDK의 공식 가져오기 가이드와 Maven 저장소를 통해 프로젝트에 연결합니다.
- 공식 Vehicle 샘플에서 확인한 Maven 경로: `https://nexus-playground.pleos.ai/repository/maven-releases/`.
- 샘플에 다양한 API가 나열되어 있어도 모든 차량에서 모든 기능을 지원한다는 뜻은 아닙니다.
- Vehicle 샘플의 현재 기준 예제는 Door Position 연동입니다. 초안에 적힌 ‘안전운전 점수 계산기 튜토리얼’과 같다고 단정하지 않습니다.
- 샘플 앱 자체 minSdk 23과 제품 앱 요구사항 minSdk 28 이상은 다릅니다. 공식 안내도 구분합니다.
- 다운로드·재사용 시 해당 저장소의 라이선스와 사용 자산 권리를 확인합니다.

출처: [Vehicle 샘플 가이드](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [공식 저장소](https://github.com/Pleos-Playground/pleos-sample-vehicle-app).

## 2. 공모전 공식 사이트 전체 안내 메뉴

| 문서 | 링크 |
|---|---|
| 홈 | [공모전 홈페이지](https://appdevchallenge.auto-hmg.io/) |
| 가이드 목차 | [참가 가이드](https://appdevchallenge.auto-hmg.io/guide) |
| 01 개요 | [공모전 개요](https://appdevchallenge.auto-hmg.io/guide/overview) |
| 02 일정 | [일정·설명회](https://appdevchallenge.auto-hmg.io/guide/schedule) |
| 03 숏폼 | [숏폼 챌린지](https://appdevchallenge.auto-hmg.io/guide/shortform) |
| 04 제출 | [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission) |
| 05 개발 | [개발 환경](https://appdevchallenge.auto-hmg.io/guide/development) |
| 06 심사 | [심사·시상](https://appdevchallenge.auto-hmg.io/guide/judging-awards) |
| 07 규정 | [참가 규정](https://appdevchallenge.auto-hmg.io/guide/rules) |
| 08 문의 | [도움말·문의](https://appdevchallenge.auto-hmg.io/guide/help) |
| FAQ | [전체 FAQ](https://appdevchallenge.auto-hmg.io/faq) |
| 공지 목록 | [공지사항](https://appdevchallenge.auto-hmg.io/notice) |
| 참가·출품 유의사항 | [공지 본문](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000006) |
| 모집 공고 | [공지 본문](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000001) |
| 설명회 | [공지 본문](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000005) |
| 이용약관 | [약관](https://appdevchallenge.auto-hmg.io/terms) |
| 개인정보 | [처리방침](https://appdevchallenge.auto-hmg.io/privacy) |

## 3. 차량 앱 설계 문서

아래는 본문을 확인한 설계 문서입니다. SDK 관련 더 깊은 링크는 02·03 문서에도 수록합니다.

- [설계 가이드 목차](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/intro)
- [주행 안전 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
- [지역별 주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)
- [앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)
- [앱 구조·멀티윈도·백그라운드](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)
- [하드웨어 접근](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture)
- [SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api)
- [차량별 API 호환성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)
- [보안·개인정보 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy)
- [성능·리소스](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt)

## 4. 차량 화면과 UI

- [UI 디자인 원칙](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)
- [Display Layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout)
- [Window Policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window)
- [Colors](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors)
- [Animation](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/animation)
- [Basic button](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button)

확인한 범위의 요점: Driving view와 앱 표시 영역을 구분하고, 앱 영역은 화면 형태에 따라 1개 또는 2개 앱을 표시합니다. 화면 분할·크기 변경·가로세로 전환을 고려해야 합니다. Display Layout은 가로 전용 게임 등 예외도 설명합니다. 단순히 ‘모든 앱은 무조건 세로·가로 모두 지원’이라고 일반화하지 않습니다.

색상·버튼 크기 등의 일부 정보는 그림으로 제공됩니다. 이번 정리는 이미지에 포함된 모든 수치·디자인 토큰을 전사한 UI 명세가 아닙니다. UI를 구현할 때 해당 원본 그림을 다시 확인합니다. 나머지 모든 UI 부품 페이지의 본문을 확인한 것은 아닙니다.

추가 확인한 디자인 다운로드 경로는 위 1절에서 관리한다. 공식 배포 자료라는 사실만으로 로고·아이콘·차량 이미지의 외부 재배포 권한까지 확보됐다고 판단하지 않는다. Figma 내부 자료와 재사용 조건은 실제 디자인 적용 전에 확인한다.

## 5. 개발·오디오 동작 문서

- [앱 개발 가이드 목차](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/intro)
- [주행 규제 구현](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [오디오 포커스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)
- [시동 종료 후 Suspend·Resume 오디오 처리](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)
- [음성 안내 선행 묵음](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide)
- [실행 환경·차량 속도·플랫폼 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq)

음성 구현에서 확인한 사항:

- 재생 전 포커스를 요청하고, 상실·일시 상실·볼륨 감쇄에 대응합니다. 캐릭터 음성이 차량 경고나 통화와 충돌하지 않도록 실제 정책을 따릅니다.
- Suspend 진입 시 일반 오디오를 중단하고 포커스를 반납합니다. Resume 시 이전 포커스가 자동 복구된다고 가정하지 않습니다.
- 음성 안내 시작에 150~200ms 선행 묵음을 권장합니다. 단순 지연과 실제 오디오 데이터의 묵음 추가는 구분합니다.

위 조건은 각 문서의 정책 요약입니다. 공모전의 별도 배점 항목으로 확인된 것은 아닙니다.

### 하위 메뉴에서 추가 확인한 개발 조건

| 자료 | 본문에서 확인한 핵심 조건 |
|---|---|
| [CCP 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development) | CCP는 물리 버튼·다이얼 입력. 사용자 설치 앱은 지원 선택 가능하나 터치만으로 필수 조작 가능해야 함. 미지원 선언 키는 `com.hmg.rotary.ROTARY_SUPPORTED`. 최상위 CCP Back은 앱 숨김 정책과 상태 저장·복원을 함께 검토 |
| [IME 창 크기 처리](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide) | 입력 필드 유무에 따라 키보드 표시 시 창 조정을 구분. 입력 없는 Dialog의 IME inset 제외 예제를 Activity 전체에 일괄 적용하지 않음 |
| [ADB 디버깅 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/adb-command-reference-guide) | 연결·화면 캡처·녹화·앱 설치·실행·주행 규제 시험 명령 제공. CCS 연결 명령은 Devbox·Emulator에서 미지원이라고 명시. 이번 조사에서 명령을 실행한 것은 아님 |
| [Maps/Navigation 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide) | 카테고리·실제 기능 일치, 단일 활성 경로, 명시적 경로 전환 등 요구. Navigation 앱의 게임 요소는 반려 항목이므로 휴게소 연계가 있다는 이유로 게임을 내비 카테고리로 선언하지 않음 |
| [Music/Audio 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide) | 주 기능이 오디오인 앱의 미디어 서비스·세션·차량 제어 연동 기준. 게임이 TTS/BGM을 쓴다는 이유만으로 Music/Audio 카테고리나 그 예외가 적용되는 것은 아님 |

예제 코드는 적용 대상·버전·실제 명령 도움말을 확인한 뒤 사용한다. 문서의 설치·삭제·규제 조작 예제를 조사 과정에서 실행하지 않는다.

## 6. 시험·샘플·릴리스

- [폼 팩터 테스트](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)
- [Virtual Console 사용](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)
- [Vehicle Sample App 안내](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [에뮬레이터 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)
- [SDK 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

폼 팩터 시험은 별도 가상 디스플레이로 화면 크기·방향 변화를 확인합니다. 안내상 Go 1.22 이상과 8095·8096 포트가 필요하며 에뮬레이터를 별도 창으로 실행해야 합니다.

Virtual Console에서는 기어, 브레이크 페달, 거리, 조향, 크루즈, 조명, 탑승 상태, 에너지·공조 등 상태를 바꾸며 앱의 반응을 시험할 수 있습니다. 콘솔 입력값과 실제 앱이 받는 API 값·단위·갱신 주기가 일치하는지는 시험해야 합니다.

2026-10-19 에뮬레이터 릴리스는 LLM SDK·CRN·Bluetooth 동글 연동 제공 종료를 명시합니다. 과거 안내를 그대로 개발 전제로 사용하지 않습니다.

### 샘플·폼 팩터에서 추가 확인한 검증 한계

[Vehicle 샘플 사용 가이드](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)는 `SDK initialized`를 서비스 연결 완료로, `Complete`를 실제 차량 값 반영 완료로 해석하지 않도록 설명한다. 지원 결과·현재값·콜백 수신을 별도로 확인하며 `setDoorPosition` 반환 요청값은 Refresh 또는 변경 콜백으로 대조한다. `Unsupported`에는 Capability 요청 실패도 포함될 수 있다.

[폼 팩터 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)의 추가 조건:

- FormFactorConsole + Host Display Bridge는 차량 상태 웹 콘솔과 다른 화면 시험 도구다. Android Studio 내장 Running Devices 창·Headless 모드는 이 가상 디스플레이 시험에 사용할 수 없다.
- Bridge를 유지한 상태에서 preset·방향·dp/DPI/px를 확인한다. `UNAVAILABLE`과 디스플레이 생성 전 `DISCONNECTED`를 구분한다.
- 가상 디스플레이 제거는 해당 화면의 앱을 강제 종료한다. 제거 → Bridge 종료 → 포워딩 정리 순서를 따르며 샘플의 AVD 이름을 실제 환경 이름으로 확인한다.

## 7. 앱 마켓 등록·배포 문서

- [태그 표기](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-tagging-guide)
- [업데이트](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-update-guide)
- [버전 관리](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-version-management-policy)
- [앱 보안](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide)
- [기능·성능 안정성 심사](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability)

**공모전 접수와 실제 앱 마켓 배포는 다른 단계입니다.** 위 문서의 배포 기준을 모두 1차 접수 필수자료로 해석하지 않습니다.

확인한 요점:

- 현재 업데이트 가이드는 자동 업데이트 미지원으로 안내하며, 앱 개발사가 버전 확인·안내·마켓 이동을 구현하도록 설명합니다.
- 변경 후 배포·심사 요청 시 버전도 변경해야 합니다.
- 정식 배포는 동작 완성도, 필요한 권한, 보안, 개인정보, 서명 및 디버깅 설정 등을 확인합니다.
- 게임 데이터와 별개로 인증정보·위치·차량 민감 데이터가 있다면 저장·전송·로그 보호를 설계합니다.

### Publish의 심사 하위 문서

아래 5개 본문은 2026-11-03 확인했다. 공모전 1차 접수 서류에 새 의무를 추가하는 표가 아니라 **App Market 등록·유통 단계** 기준이다.

| 세부 문서 | 우리 앱에 관련된 내용 |
|---|---|
| [콘텐츠 안전성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-content-safety) | 위험 운전 조장 금지, 외부 이미지·상표·오디오 이용 권리 증빙. 유료 무작위 항목 확률 안내와 무료 보상을 구분. AI 생성·사용자 콘텐츠 필터/신고 조항은 고정 제작 자산에 대한 적용 범위가 불명확하여 별도 확인 |
| [기능·성능 안정성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability) | 실제 동작·안정성 및 승인된 Vehicle 권한 범위. 공모전 개발 사전 승인 조건은 [개발 자료](02_PLEOS_DEVELOPMENT.md)와 구분 |
| [운전자 주의 분산](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements) | 게임을 포함한 미디어 앱의 주행 UI 숨김·일시 중지와 알림 제한. 해석 기준은 [차량 설계 조건](03_VEHICLE_API.md) 5절 |
| [등록정보](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-app-registration-info) | 실제 앱과 일치하는 설명·스크린샷, 권리·연령 등급 증빙, 로그인 기능의 심사 접근 정보 |
| [개인정보·보안](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security) | 최소 수집·권한, 목적·기간·거부권과 동의, 철회 시 처리 중단·저장 데이터 삭제. 위치 기능의 국가별 추가 요건 확인 |

[앱 심사 제출 안내](https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide)는 APK·메타데이터·아이콘·스크린샷·개인정보처리방침과 위치 사용 시 조건부 약관 등을 구분한다. 연령 등급은 IARC 및 국가별 조건을 함께 확인해야 하며, 현재 기획의 실제 심의 경로가 확정됐다는 뜻은 아니다. 스크린샷 규격은 등록정보의 `840 × 1212`와 제출 안내의 `840 × 121`이 달라 실제 배포 시 재확인한다.

연결된 [App Review Submission Materials Request](https://docs.google.com/forms/d/e/1FAIpQLSfb-OdILShhzgQZBrh2TpZ5gB7GRKoi1sRdrd2gSQZA0udhRQ/viewform)는 첫 페이지에서 앱 이름·기능 설명서·파일 추가 항목을 확인했다. **앱 마켓 심사 자료 요청 폼이며 OT의 사용 경험 설문과 다르다.** 필수 정보 입력 후 진행하는 다음 페이지는 열지 않았고 입력·업로드·제출하지 않았다. OT가 언급한 Playground 피드백 설문의 실제 URL은 이번에 확인한 페이지에서 확보하지 못했다.

## 8. 자료 추가·갱신 규칙

1. 새로운 링크를 받으면 첫 페이지뿐 아니라 관련 하위 가이드·첨부자료를 확인합니다.
2. 공식 사실, 팀 판단, 미확인 항목을 구분합니다.
3. 원문 URL과 확인 날짜를 함께 기록합니다.
4. 변경된 규정은 이전 내용과 무엇이 달라졌는지 기록합니다.
5. 다운로드 파일은 출처·다운로드일·해시를 남기고, 작성본과 원본을 구분합니다.
6. 문서 존재만으로 SDK 사용 승인·실차 호환·백그라운드 지속 실행을 확정하지 않습니다.
