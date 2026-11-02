# 다운로드와 공식 문서 목록

자료 조사 기준: 2026-11-02 KST. 정리 완료일: 2026-11-03 KST.

이 목록은 실제 확인한 공식 페이지 및 해당 페이지에서 제공하는 자료를 정리한 것입니다. **다운로드 완료**, **공식 다운로드 경로 확인**, **링크만 확인**을 구분합니다. 개발 도구를 설치하거나 SDK 사용 신청을 완료한 목록이 아닙니다.

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
| 온라인 설명회 다시보기 | 설명회 확인 | [공지](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000005) | 제공 예정 문구 확인, 재생 링크 미확보 |

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

## 6. 시험·샘플·릴리스

- [폼 팩터 테스트](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)
- [Virtual Console 사용](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)
- [Vehicle Sample App 안내](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)
- [에뮬레이터 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)
- [SDK 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

폼 팩터 시험은 별도 가상 디스플레이로 화면 크기·방향 변화를 확인합니다. 안내상 Go 1.22 이상과 8095·8096 포트가 필요하며 에뮬레이터를 별도 창으로 실행해야 합니다.

Virtual Console에서는 기어, 브레이크 페달, 거리, 조향, 크루즈, 조명, 탑승 상태, 에너지·공조 등 상태를 바꾸며 앱의 반응을 시험할 수 있습니다. 콘솔 입력값과 실제 앱이 받는 API 값·단위·갱신 주기가 일치하는지는 시험해야 합니다.

2026-10-19 에뮬레이터 릴리스는 LLM SDK·CRN·Bluetooth 동글 연동 제공 종료를 명시합니다. 과거 안내를 그대로 개발 전제로 사용하지 않습니다.

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

## 8. 자료 추가·갱신 규칙

1. 새로운 링크를 받으면 첫 페이지뿐 아니라 관련 하위 가이드·첨부자료를 확인합니다.
2. 공식 사실, 팀 판단, 미확인 항목을 구분합니다.
3. 원문 URL과 확인 날짜를 함께 기록합니다.
4. 변경된 규정은 이전 내용과 무엇이 달라졌는지 기록합니다.
5. 다운로드 파일은 출처·다운로드일·해시를 남기고, 작성본과 원본을 구분합니다.
6. 문서 존재만으로 SDK 사용 승인·실차 호환·백그라운드 지속 실행을 확정하지 않습니다.
