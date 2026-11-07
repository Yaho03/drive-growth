# 드라이브 키우기

## 팀 작업 현황 (임시)

| 담당 | 지금 할 일 | 필요한 공통 연결·해제 조건 |
|---|---|---|
| 정원 | #37 Ledger/직렬화·#38/#39 독립 계산, #40 실제 증거 연결. #112·#119·#36 재사용 | 탁송 최종 공용 연결은 경민과 수정 범위·#116 이전 순서 조율. 실제 도착은 #80·기록/음성은 #81 해당 근거만 기다림 |
| 환석 | [공통 계약 후속](https://github.com/Yaho03/drive-growth/issues/121) 묶음별 인계, #52/#54/#56 실제 공급 연결, #58 정의 조율 | 기존 #104·호스트/열람 코드 유지. 초기화 NEW 근거·가챠/재료/수리·진행 명령을 구체화하고 경민 저장에 전달 |
| 호열 | #111 두 P1 소비 수정, #48 재료 거래·#49 최신 계산/수리·처분·#50 초기화 연결, #35 유형/정의 | #113/#114/#115 유지. 공통 전체 요청/결과·다중 개체/티켓·최종 공간 저장과 #116 수정·머지된 이력/사고 조회 사용 |
| 경민 | [저장 후속](https://github.com/Yaho03/drive-growth/issues/122), #116 세 지적 수정, #41~#45 실제 입력·진행/수령 연결 | 기존 #107 RentalLedger 유지. 합의된 계약 묶음부터 저장·이전·최종 점유 검증. 금액/퀘스트 정의는 #24/#35와 조율 |

#53 탁송·#55 시설/꾸미기·#57 진행/휴식 **표시 UI는 fixture로 병렬 진행**한다. 실제 명령/저장 미지원은 화면에 구분하고 UI 작업 전체를 공통 구현 완료 대기로 바꾸지 않는다. 구체 입출력과 제공/대기 구분은 [기술 계약](docs/02_SYSTEM_ARCHITECTURE.md#전체-기능의-공통-계약과-저장-선행-작업), 순서·책임은 [진행 계획](docs/08_PROJECT_PLAN.md#남은-공통-선행-작업과-병렬-연결-순서), 실제 차단은 각 이슈를 따른다.

선행 결과가 검토·머지되거나 이슈를 완료로 닫으면 이 표도 갱신한다. 세부 정책·실제 연결 조건은 각 이슈를 따른다. 팀 구현이 끝나면 이 섹션을 삭제한다.

평소 이동을 탁송·차량 영입으로 연결하고, 주차 후 차량을 모으고 렌터카 가게를 확장하는 Pleos Connect 차량용 게임을 기획하고 있습니다.

**차고·도감·정산·첫 실행 View를 실제 탐색에 연결했습니다. 정산은 기존 SQLite 열람 저장·재조회·복원을 사용하며, 첫 실행은 초기 지급 조회 미지원 상태를 표시합니다.** 실제 #50 지급·관리 경제 명령·앱 전체 흐름 완료와 구분합니다. Debug APK는 로컬 시험 산출물이며 제출 Release APK·실제 게임 시연 영상은 준비 전입니다.

처음 참여한다면 [4인 구현 시작 안내와 책임표](docs/08_PROJECT_PLAN.md)를 확인하세요. 기능 이슈 → 해당 PRD 항목 → 공통 데이터 계약 → 화면 명세 → 검증 명령 순서로 진행합니다. 공통 타입·거래 저장·fixture 구현은 기능 연결의 선행 작업입니다.

## 대상 사용자와 해결할 문제

일상적인 주행을 돌아보고 게임의 성장·수집 경험으로 연결하려는 운전자가 대상입니다. 추가 주행이나 주행 중 조작을 유도하지 않고, 확인·꾸미기·보상 행동을 주차 후로 모으는 것을 목표로 합니다.

## 차량용 앱이어야 하는 이유

차량의 속도·기어·시스템 주행 제한 상태를 함께 사용해 기록과 화면 제한을 연결하려 합니다. 속도 0만으로 주차를 판단하지 않습니다. 차량별 데이터 지원 여부와 실제 앱 수신 검증은 남아 있습니다.

## 공통 소스와 실제 구현 범위

`app/src`와 `core/src`는 선행 기술 검증 코드를 팀이 같은 버전으로 빌드·시험하기 위한 기반입니다. 기존 `DriveEngine`의 경험치·점수와 `DashboardView` 화면은 현재 렌터카 경제·화면 구현이 아닙니다. 차량 서비스·DO 선언의 정책 적합성은 #13에서 별도 확인합니다.

| 항목 | 확인한 범위 |
|---|---|
| 기존 순수 Kotlin 엔진 | 테스트 13개 통과. 새 탁송·영업 규칙은 미구현 |
| 기존 Android 저장 | Pleos 에뮬레이터에서 주행 스냅샷 저장 테스트 8개 통과 |
| 공통 거래 원장 | #104에서 개체 희귀도·진행량·취득 정보와 단일 개체 저장 확장. SQLite 시험 26개 통과. 구형 데이터 migration·영속 도감·다중 개체 거래·제품 앱 재실행은 남음 |
| 전경 차량 입력·Back | #105의 SDK 단일 worker·늦은 응답/수신 시각 처리. Pleos API34에서 D/P 제한·권한·Back·재생성, 제어된 worker 정지 중 제한·복귀 확인. 공식 폼 팩터·실차·장기 안정성은 남음 |
| 개발 도구·APK | 공통 조합 빌드·Lint, Debug 설치·Activity 기본 실행 확인 |
| 차고·도감 탐색 | #109에서 typed 조회·SQLite→차고·Back·선택 복원과 SDK 제한 연결. 독립 장치 회귀 5개 및 D/P·권한·CCP 확인. 실제 활동·도감 이력·관리 명령은 남음 |
| 정산·첫 실행 연결 | 기존 #108/#110 View를 실제 호스트·gate·Back에 연결. 원본 Settlement→실제 markViewed 응답·재조회, host 회귀9개·격리 장치3개·제품 SDK D/P·권한·CCP와 원장 불변 재시작 확인. 초기화는 Unavailable이며 실제 #50 지급·전체 history/세부 수익·공식 폼 팩터/실차는 남음. [실행 근거](docs/06_VERIFICATION.md#정산첫-실행-실제-호스트-연결-검증--2026-11-07) |
| 렌터카 게임·추가 기능 | 상세 구현 이슈로 진행. 정책·수치·일부 동작은 결정 대기 |
| 최종 APK·시연·실차 | 제출 산출물 미준비, 실차 미검증 |

## 개발환경과 실행

공통 적용값은 Kotlin Gradle Plugin **2.2.21**, AGP **8.10.1**, Gradle **8.11.1**, JDK **17**입니다. SDK 설치·환경 변수·Pleos 실행·팀원별 재현 확인은 [개발환경 기준](docs/05_DEVELOPMENT_SETUP.md)에 있습니다. 로컬 SDK·캐시·개인 `local.properties`는 게시하지 않습니다.

JDK 17과 문서에 명시된 Android SDK를 준비하고 `ANDROID_HOME` 또는 개인 `local.properties`의 `sdk.dir`를 지정한 뒤 실행합니다.

```sh
python3 scripts/check_repository.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

기존 SQLite 저장 시험은 Pleos 시험 장치를 연결한 뒤 대상 클래스를 지정합니다.

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.drivegrowth.app.data.SqliteGameChangeStoreTest
```

실제 호스트의 기본 원장에 시험 정산을 만드는 새 `ResultsHostStorageTest`·`ResultsHostActivityTest`는 제품 데이터 보호를 위해 **격리 패키지에서만 실행**하며 제품 패키지에서는 쓰기 전에 실패합니다. 이 시험까지 포함한 전체 계측 시험을 제품 앱에 무조건 실행하지 않습니다. 격리 빌드·설치·권한·표적 실행 명령은 [실제 연결 검증의 재현 절차](docs/06_VERIFICATION.md#격리-표적-시험-재현)를 따릅니다. 장치 점유를 먼저 조율하고 실제 P·정차·UX 허용 조건을 준비합니다.

Debug 산출물은 `app/build/outputs/apk/debug/app-debug.apk`입니다. 설치·로그 수집은 개발환경 문서를 따릅니다. CI는 소스 포함 시 `repository-checks`와 `android-checks`를 실행합니다. 실제 로컬·CI 결과와 경고는 [검증 기록](docs/06_VERIFICATION.md)을 확인합니다.

## 검증과 한계

핵심 로직 테스트는 주차 확인, 중복·역순·누락 입력, 중복 보상, 중단 복원, 재부팅 시각 처리를 확인합니다. 메모리 상태 복원 테스트는 디스크 저장 검증과 다릅니다. Pleos 부팅 성공은 앱 연동이나 실차 검증을 의미하지 않습니다. 상세 근거는 [검증·제출 상태](docs/06_VERIFICATION.md)에 기록합니다.

## 문서 안내와 읽는 순서

문서 파일명은 영어, 내용은 한글로 작성합니다. 처음 참여한 팀원은 **README → CONTRIBUTING → PRD → SYSTEM_ARCHITECTURE → VERIFICATION** 순서로 읽고, 조사·개발환경 문서는 해당 작업 때 참고합니다.

| 문서 | 이름의 뜻 | 담는 내용·읽는 시점 |
|---|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | 팀 기여·협업 가이드 | 문서 언어, 브랜치·커밋·PR, 검사, 사람 리뷰·머지·게시 절차. 모든 작업 전에 확인 |
| [01_PRD.md](docs/01_PRD.md) | Product Requirements Document · 제품 요구사항 | 무엇을 만들지, 확정·미정 범위, 렌터카 영업·탁송·차량 수집·가게 성장 규칙. 기존 제품·차량 게임 기획 통합 |
| [02_SYSTEM_ARCHITECTURE.md](docs/02_SYSTEM_ARCHITECTURE.md) | 시스템 구조·기술 계약 | 모듈 역할, 입력 데이터, 상태 전환, 계산·저장·UI 제한. 구현 전에 확인 |
| [03_GAME_BENCHMARK.md](docs/03_GAME_BENCHMARK.md) | 참고 게임 비교·분석 | 유명 방치형 게임 7개의 근거와 우리 게임 적용 제안. 외부 사실과 제안을 구분하며 확정 요구사항은 PRD에 기록 |
| [04_VEHICLE_RESEARCH.md](docs/04_VEHICLE_RESEARCH.md) | 차량 조사 | 조사 범위, 차종 분류, 출처·확인 한계·자산 권리. 차량 사실은 연결된 CSV에서 관리 |
| [05_DEVELOPMENT_SETUP.md](docs/05_DEVELOPMENT_SETUP.md) | 개발 기반·버전 | 현재 설정과 채택·추가 검증·결정 대기 구분, 공식 호환성 근거, 과거 Android·Pleos 환경 설치·실행 기록 |
| [06_VERIFICATION.md](docs/06_VERIFICATION.md) | 검증·완료 상태 | 실행한 검사, 실패·미검증 사항, 시연 조건·제출 준비 상태. PR·머지·제출 전 확인 |
| [07_REFERENCES.md](docs/07_REFERENCES.md) | 외부 참고 근거 | 유명·수상 레포에서 채택한 특징과 AI 지침 로딩 근거. 협업·구조를 바꿀 때 참고 |
| [08_PROJECT_PLAN.md](docs/08_PROJECT_PLAN.md) | 프로젝트 진행 계획 | 지금 할 일·선행조건·통합 회의와 M0–M3 단계 안내. 담당·목표일은 회의 후 확정 |
| [09_UI_UX_SPEC.md](docs/09_UI_UX_SPEC.md) | 화면 흐름·디자인 명세 | 화면·상태·입력과 실제 코드 연결점. 차고·상세·도감 표시 구현, 실제 탐색·명령·저장 연결은 미완료 |
| [10_SUBMISSION_DRAFT.md](docs/10_SUBMISSION_DRAFT.md) | 공식 양식용 제출 초안 | 심사용 1–8번 항목에 맞춘 문안, 미확정·실제 자료 상태. 최종 제출본 아님 |
| [공모전 자료 안내](docs/contest/README.md) | 공식 공모전 자료 목차 | 일정·심사·접수, Pleos 개발자료, 차량 API와 공식 자료·다운로드 보관 기록 |
| [OT 전체 요약](docs/contest/05_ORIENTATION_NOTES.md) | 온라인 설명회·질의응답 | 전체 35개 구간의 핵심 내용, 시각별 영상 링크, 기존 안내와의 차이. 기획·제출·SDK 조건 확인 시 참고 |
| [내비 콜백 진단 도구](navi-diagnostic/README.md) | #80 지원용 별도 APK | 다운로드·설치·내장 모의주행 재현·시험 증거·남은 제한. 제품 앱·전체 오류 해결과 구분 |
| [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) | AI 작업 진입점 | 같은 협업·제품·기술·검증 문서를 읽게 하는 지침. 공통 규칙을 중복 작성하지 않음 |

차량 목록의 기준 데이터는 [vehicles.csv](docs/data/vehicles.csv)입니다. PDF 기획서 원본은 로컬에만 보관하며 GitHub에 올리지 않습니다. 조사 문서의 제안은 팀이 결정하기 전까지 확정 기획이나 구현 완료를 의미하지 않습니다.

외부 이미지·캐릭터·로고의 사용 조건은 별도 확인 대상입니다. 현재 화면의 차량 도형은 임시 표현이며 실제 차종으로 주장하지 않습니다. 원격 저장소는 [Yaho03/drive-growth](https://github.com/Yaho03/drive-growth)입니다. 보호 규칙·CI·게시 상태는 [검증 기록](docs/06_VERIFICATION.md)에서 구분해 관리합니다.

## B 담당 검토 제안

[경제·해금·인계 검토 자료](docs/proposals/b-economy/README.md)는 미확정 시험안과 비교 실험이다. 확정 제품 규칙이나 기능 완료 상태는 기존 PRD·검증 문서를 따른다.
