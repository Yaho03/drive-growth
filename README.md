# 르르르 렌터카

**평소 이동을 탁송과 수익으로 연결하고, 주차한 뒤 차량을 모아 렌터카 가게를 키우는 Pleos Connect 경영 게임입니다.** 프로젝트 이름은 **드라이브 키우기**입니다. 현대자동차그룹 차량용 앱 개발 공모전을 위해 만들었습니다.

[APK 다운로드와 체험 순서](#실행할-앱과-apk) · [심사 자료 안내](docs/README.md#심사-자료-읽는-순서)

## 실제 화면과 시연

![Pleos 에뮬레이터에서 실행한 르르르 렌터카 가게. 지도에 보유 차량, 시설과 하단 메뉴가 표시된다.](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/recheck-current-home.png)

아래에 링크한 이전 테스트 릴리스의 **격리 모의 앱 가게 화면**입니다. Pleos 전체 화면은 1080×600px이며, 오른쪽의 게임 창은 702×438px입니다. [사용자용 테스트 APK의 첫 차량 선택 화면](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/player-ready.png) · [새 프로세스에서 복원한 첫 지급 화면](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/recheck-original-result-rendered.png)

### 게임 흐름

아래 흐름은 모의 입력 앱에서 체험할 수 있습니다. 이전 별도 차량용 SDK 계측에서 최초 견적과 의뢰 복원을 확인했습니다. 실제 SDK에 연결된 활성 의뢰의 도착·지급 검증은 남아 있습니다. [SDK 계측 범위](docs/06_VERIFICATION.md#3739-실제-sdk-견적의-구매-복원변경-거절과-모의-종료-결과--2026-11-13)

1. **주차 중 의뢰 선택** — 렌터카를 시작할 첫 차량을 고릅니다. 목적지의 예상 경로를 확인하고, 게임머니를 받는 수익 탁송이나 도착 후 차량을 얻는 구매 탁송을 선택합니다.
2. **평소대로 이동** — 운전 중에는 게임 화면과 조작을 제한합니다. 앱은 전경에서 수신한 주행 데이터를 기록하고 도착 근거를 확인합니다.
3. **다시 주차하고 가게 관리** — 탁송과 주행·대여 정산을 확인합니다. 차량을 차고와 도감에 모으고, 모은 재화로 차량·차고·시설을 관리합니다.

## 실행할 앱과 APK

아래 다운로드와 화면은 **이전 검증 릴리스**에 해당합니다. 각 APK는 표의 커밋에서 빌드했습니다. 최신 통합 소스를 실행하려면 [로컬 빌드와 실행 절차](docs/05_DEVELOPMENT_SETUP.md#팀-공통-실행-절차)를 사용하세요. 최신 소스의 실행 기록과 확인 범위는 [검증 요약](docs/06_VERIFICATION.md#현재-검증-요약--2026-11-15)에서 확인할 수 있습니다.

| 사용 목적 | 다운로드와 입력 |
|---|---|
| **새 저장으로 게임 체험** | [첫 차량 테스트 APK](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/rrr-rentcar-first-vehicle-test-20261115.apk) · `io.drivegrowth.verify.prototype.firstcarplay` · 모의 차량·내비 입력. 코드 `6070445`, 릴리스 문서 `2c75715` |
| **Pleos 차량 SDK 입력 확인** | [차량용 APK](https://github.com/Yaho03/drive-growth/releases/download/first-vehicle-test-20261115/rrr-rentcar-vehicle-dev-709e08d.apk) · `io.drivegrowth.app` · `709e08d` 기반 개발 서명 Release |

두 앱은 저장 공간이 분리됩니다. 설치한 앱을 업데이트할 때는 패키지·서명·Android 사용자를 확인하세요. 비공개 저장소에는 다운로드 권한이 필요합니다. [릴리스와 SHA-256](https://github.com/Yaho03/drive-growth/releases/tag/first-vehicle-test-20261115) · [기존 저장을 보존하는 설치 방법](docs/05_DEVELOPMENT_SETUP.md#설치-대상과-저장-보존)

**위 테스트 APK의 빠른 체험** — 앱을 열어 **화면을 눌러 시작 → 첫 차량 고르기 → 캐스퍼 또는 아반떼 선택 → 선택 내용 확인 → 이 차량으로 시작 → 가게로**를 누릅니다. 종료 후 같은 앱을 다시 열어 차고와 잔액을 확인할 수 있습니다. 탁송은 **탁송 → 이 목적지의 탁송 후보 보기 → 수익 탁송 → 이 의뢰 선택 → 탁송 수락**에서 시작합니다. 저장된 선택 결과를 확인한 뒤 **설정 → 시연 → 모의 입력 모드**에서 모의주행을 시작합니다. [심사위원 설치·실행 안내](docs/05_DEVELOPMENT_SETUP.md#심사위원-실행-안내) · [모의주행의 자세한 순서](docs/05_DEVELOPMENT_SETUP.md#현재-소스의-로컬-debug-화면-경로)

## 핵심 기능

- **탁송·정산** — 수익 의뢰와 구매 의뢰를 고르고, 조건을 충족하면 완료 보상이나 차량을 받습니다. 기본 주행 수익과 자동 대여 수익은 정산에서 각각 확인할 수 있습니다.
- **차량 수집·관리** — 첫 차량 선택, 뽑기, 차종×희귀도 도감, 개별 차량의 강화·수리·판매·폐차를 제공합니다.
- **가게 성장·기록** — 주차 공간을 늘리고 시설과 간판을 꾸밉니다. 퀘스트·업적, 내 기록과 시연용 랭킹을 확인할 수 있습니다. [제품 규칙](docs/01_PRD.md) · [화면 흐름](docs/09_UI_UX_SPEC.md)

## 차량용 앱이어야 하는 이유

차량용 앱은 Pleos SDK의 속도·기어·UX 제한을 확인해 주차 후에만 게임 조작을 엽니다. 목적지는 예상 경로를 확인하는 데 쓰고, 도착 보상은 현재 의뢰의 도착 근거를 확인한 뒤 처리합니다. 실제 SDK의 활성 의뢰 도착·지급은 미검증이며, 모의 앱은 고정 시연 목적지와 논리 시각으로 이 게임 흐름을 체험할 수 있습니다. [차량 입력과 안전 기준](docs/01_PRD.md#안전기술-확인과-남은-결정)

## 공모전과 개발 조건

- **시험 환경** — Pleos Connect Emulator 2.1.0, Android API 34 ARM64, Android 사용자 10. 위 시연의 기본 디스플레이는 1080×600px·120dpi이며, 실제 게임 창은 702×438px입니다. [에뮬레이터와 실행 설정](docs/05_DEVELOPMENT_SETUP.md) · [창 크기별 시험](docs/06_VERIFICATION.md#공식-세로가로-창과-표준-debug-apk-직접-설치--2026-11-13)
- **개발 환경** — Kotlin과 Android Views, JDK 17, KGP 2.2.21, AGP 8.10.1, Gradle 8.11.1. `app`은 Pleos 입력·화면·SQLite 저장을 연결하고, `core`는 거래·수익·성장 규칙과 공통 계약을 담습니다. [빌드 명령](docs/05_DEVELOPMENT_SETUP.md#팀-공통-실행-절차) · [구조와 데이터 계약](docs/02_SYSTEM_ARCHITECTURE.md)
- **제출 자료** — [공모전 규정과 제출 방법](docs/contest/01_CONTEST_GUIDE.md#4-제출물과-github), [심사용 소개 문안](docs/10_SUBMISSION_DRAFT.md), [자산별 출처와 사용 조건](docs/04_VEHICLE_RESEARCH.md#android-채택-픽셀-자산과-사용-조건-확인--2026-11-13)을 확인할 수 있습니다.

## 검증과 한계

- **격리 모의 실행** — 첫 지급 전 전체 정산 0건, 지급 후 최초 지급 1건, 새 프로세스에서 같은 캐스퍼·잔액·차고 공간·원정산의 복원을 계측했습니다. 탁송·정산·확장·진행 보상은 별도 모의 실행 기록에 있습니다. [첫 차량 보강 회귀](docs/evidence/first-vehicle-isolation-20261115/README.md) · [같은 모의 원장의 연속 실행](docs/06_VERIFICATION.md#최종-동일-모의-원장의-연속-실행--2026-11-13)
- **Pleos SDK 실행** — 별도 차량용 시험에서 실제 SDK의 PARK·UX 허용, 권한 거부 및 복귀를 확인했습니다. [차량용 첫 실행과 SDK 시험](docs/06_VERIFICATION.md#차량용-첫-실행과-마을-주민-검증--2026-11-13)
- **남은 검증과 계획** — 실제 SDK 목적지 변경·도착 지급, 전원·실차, 최종 제출 APK와 App Market 검증은 후속입니다. 온라인 사용자 간 순위는 향후 계획입니다. [전체 검증과 제출 준비](docs/06_VERIFICATION.md) · [1차 랭킹 범위](docs/01_PRD.md#1차-랭킹-범위)

## 문서 안내와 읽는 순서

심사 흐름, 개발자 진입점, 문서별 현재 기준과 과거 기록을 [문서 안내](docs/README.md)에 모았습니다. 개발 중 참고할 표준 Debug와 과거 배포 APK는 [개발환경 문서](docs/05_DEVELOPMENT_SETUP.md#별도-모의-시연-debug-apk)에서 찾을 수 있습니다.
