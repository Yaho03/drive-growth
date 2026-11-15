# 르르르 렌터카

**평소 이동으로 탁송 의뢰를 수행하고, 주차한 뒤 차량을 모아 가게를 키우는 Pleos Connect 경영 게임입니다.**

현대자동차그룹 차량용 앱 개발 공모전 프로젝트입니다.

[APK 다운로드와 실행](#실행할-앱과-apk) · [테스트 환경](#테스트-환경과-설정)

## 실제 앱 화면

### 시작 화면

![르르르 렌터카 타이틀과 화면을 눌러 시작 버튼](https://github.com/Yaho03/drive-growth/releases/download/integrated-20261115-52285de/firstcarplay-start-52285de.png)

### 메인 게임 화면(앞선 검증 버전)

![가게 지도에 보유 차량과 시설, 하단 게임 메뉴가 표시된 메인 화면](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/recheck-current-home.png)

두 화면은 Pleos 에뮬레이터에서 촬영했습니다. 타이틀은 최신 체험 APK, 가게 화면은 앞선 모의 시험에서 촬영했습니다.

## 플레이 흐름

1. **첫 차량을 고릅니다.** 캐스퍼나 아반떼 한 대로 렌터카 영업을 시작합니다.
2. **주차한 상태에서 탁송을 맡습니다.** 목적지의 예상 거리를 확인하고, 도착 후 게임머니를 받는 수익 탁송이나 차량을 얻는 구매 탁송을 고릅니다. 주행 중에는 게임 화면과 조작이 제한됩니다.
3. **다시 주차하면 수익을 확인합니다.** 탁송 보상과 자동 대여 수익을 확인하고, 뽑기로 새로운 차량을 모아 도감을 채웁니다. 차고의 차량을 강화하거나 주차 공간과 시설을 늘려 가게를 키울 수 있습니다.

## 실행할 앱과 APK

| 목적 | 다운로드 | 설치 조건 |
| --- | --- | --- |
| **게임 체험** | [첫 차량 체험 APK](https://github.com/Yaho03/drive-growth/releases/download/integrated-20261115-52285de/rrr-rentcar-firstcarplay-52285de.apk) | 모의 차량·내비 입력을 쓰는 Debug 앱입니다. 같은 서명과 Android 사용자에서 기존 체험 앱을 업데이트할 수 있습니다. |
| **Pleos 차량 SDK 시험** | [차량용 APK](https://github.com/Yaho03/drive-growth/releases/download/integrated-20261115-52285de/rrr-rentcar-vehicle-fresh-install-52285de.apk) | 개발 서명 Release입니다. 기존 차량 앱과 서명이 달라, `io.drivegrowth.app`이 없는 별도 Pleos 환경에 신규 설치합니다. 기기 설치·실행은 아직 확인하지 않았습니다. |

[통합 GitHub 릴리스와 체크섬](https://github.com/Yaho03/drive-growth/releases/tag/integrated-20261115-52285de) · [설치 명령과 기존 저장 보존](docs/05_DEVELOPMENT_SETUP.md#심사위원-실행-안내)

1. **첫 차량 체험 APK**를 Pleos 에뮬레이터에 설치하고 앱 목록에서 **르르르 렌터카 · 첫 차량 테스트**를 엽니다. [APK 설치·런처 실행 명령](docs/05_DEVELOPMENT_SETUP.md#첫-차량-선택과-모의-입력)
2. **화면을 눌러 시작 → 시작하기**를 누릅니다. 새 프로필에서 차량 카드를 고르고 **캐스퍼로 할게요** 또는 **아반떼로 할게요**를 누릅니다. 이어서 **확인 → 가게로 가기**를 선택합니다.
3. **탁송 → 이 목적지의 탁송 후보 보기 → 수익 탁송 → 이 의뢰 선택 → 탁송 수락** 순서로 진행합니다.
4. 출발 연출에서는 **확인**을 누릅니다. 저장 결과 확인이 표시되면 **처리 결과 확인하고 새로 조회**를 누릅니다. 가게로 돌아와 **설정 → 시연 → 모의 입력 모드 → 모의주행 시작**을 선택합니다. 모의주행 중 화면 제한과 주차 후 정산을 볼 수 있습니다. [탁송 상세 조작 순서](docs/05_DEVELOPMENT_SETUP.md#첫-차량-선택과-모의-입력)

## 테스트 환경과 설정

- **에뮬레이터** — Pleos Connect Emulator **2.1.0**, Android API 34 ARM64.
- **화면 규격** — 기본 디스플레이 **1080×600px·120dpi**, 캡처한 앱 창 **702×438px**.
- **입력 설정** — 체험 앱의 **설정 → 시연**에서 모의 입력을 선택합니다. 차량용 APK에는 Pleos 차량 데이터 권한과 시스템 조작 허용이 필요하며, 기어 P와 정차가 확인된 뒤 게임을 조작할 수 있습니다. [차량용 설치와 권한 확인](docs/05_DEVELOPMENT_SETUP.md#차량용-apk-실행)

## 현재 지원 범위

체험 앱에서 첫 차량 선택, 모의 탁송·정산, 차량 수집과 가게 성장을 확인할 수 있습니다. 랭킹은 시연용입니다. 실제 내비의 도착과 보상 지급이 이어지는 과정 및 실차 동작은 검증이 남아 있습니다. [실행 검증과 확인 범위](docs/06_VERIFICATION.md#현재-검증-요약--2026-11-15)

## 개발 문서

- [설치·로컬 빌드](docs/05_DEVELOPMENT_SETUP.md) · [시연·검증 기록](docs/06_VERIFICATION.md)
- [제품 요구사항](docs/01_PRD.md) · [화면 명세](docs/09_UI_UX_SPEC.md) · [기술 구조와 저장 계약](docs/02_SYSTEM_ARCHITECTURE.md)
- [문서 안내](docs/README.md) · [공식 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission)
