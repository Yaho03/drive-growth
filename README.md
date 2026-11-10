# 드라이브 키우기

최종 UI 자산은 디자인팀 제작 중이며 추후 교체한다. 현재 전경/Views는 기능 검증용 임시 표현이고, 최신 적용 기준은 [09 화면 명세](docs/09_UI_UX_SPEC.md#1-ui-구현-기준과-산출물)를 따른다.

가챠·강화·수리/처분·초기화/첫 선물의 **#47~#50 core·저장 이슈는 완료**됐다. 판정 범위와 후속 화면/통합의 구분은 [완료 기록](docs/06_VERIFICATION.md#core저장-4이슈-완료와-후속-연결--2026-11-10)을 따른다.

## 팀 작업 현황 (임시)

현재 구현·수정은 환석이 책임지고 기존 세션에 위임해 진행한다. 아래 표의 GitHub 할당·기존 영역 구분은 보존하며 실제 실행 방식과 우선순위는 [진행 계획의 현재 위임 기준](docs/08_PROJECT_PLAN.md#1차-제출-앱-구현의-현재-실행-방식)을 따른다.

| 담당 | 지금 할 일 | 필요한 공통 연결·해제 조건 |
|---|---|---|
| 정원 | #131 도착·기본 주행과 #135 변경·포기 계산 main 인계. #128·#134·#137 저장 Ledger main 인계 | #136 공용 엄격 금액 decoder는 main 제공. 공용 통합 #139는 두 결함 해결·최신 head 검토/CI 후 main 반영. #143 휴식 저장의 이력 완전성·head 쓰기 원자성은 두 수정과 독립19개로 확인한 부분 공급. 내비·화면 진입과 관측 전달은 #181로 연결했으며 실제 도착 상관·전체 SDK 수명/정책 검증은 별도 |
| 환석 | #121 재료·수리·초기화·첫 영업 선물 메모리 참조 공급. #52 저장 차고 읽기·부분 가챠 호스트/정확 비율·#55 중립2D 가게 전경 모듈 공급 | 실제 SQLite/read·분리 View 표적 및 독립 검토 범위. MainActivity/탐색·차량 gate·제품 권위/진행·durable 명령/프로세스 복원, 수리 SQLite·계좌 무갱신 guard는 부분 공급. 초기화/선물 SQLite·실제 onCreate NEW 근거·4종 typed 명령 checkpoint는 부분 공급. 같은 factory의 실제 초기화·가챠·관리 소비는 #173 제공. 남은 화면·새 기능 복원은 #51/#52/#56에서 추적 |
| 호열 | 기존 #111/#138/#140/#141/#145 공급 유지. #148 재료 prepare·메모리 소비 main 인계, #150 수리 prepare·#155 수리 메모리 참조·#157 실제 소비 시험 main 인계, #151 원래 초기화 명령 재생·선물 호출자 source/profile 경계 수정 공급 | #116 이력·사고 조회 재사용. 재료 #156·수리 #162 SQLite는 부분 공급. 초기화/선물 SQLite·같은 factory 소비는 #169/#173 제공. #47~#50 core·저장 완료와 #52/#56 화면 후속을 구분 |
| 경민 | #116 이력·발견·완료 영업/사고 조회, #136 가챠 SQLite/공용 금액 수정·#156 재료 두 개체·#162 수리 SQLite 공급 | [#122](https://github.com/Yaho03/drive-growth/issues/122)의 #139 결제 조회·지원 v4 재생 보완은 main 공급. #143 휴식 저장은 두 P2 해결 후 main 공급. 재료 #156·수리 #162의 같은 DB 활동/최초 결과·재개방은 부분 공급, 초기화/선물 SQLite·typed checkpoint는 #169, 같은 factory 소비는 #173 제공. Quest·Delivery 저장은 #178, 관측 주행 schema13은 #179, 제품 페이지 소비는 #181 제공. 시설·배치 등 나머지 저장은 후속. 기능별 decoder 중복 구현 금지 |

#53 탁송·#55 시설/꾸미기·#57 진행/휴식 표시 UI는 PR #124·#125·#129로 main에 인계됐고, #55 첫 복원 수정도 PR #130에 반영됐다. #52 가챠는 두 P2 수정·독립 단위/장치 검증·CI를 통과해 [PR #132](https://github.com/Yaho03/drive-growth/pull/132)로 main에 반영됐다. 확장 preview·명령·원래 결과 복구는 아래 중앙 정의 연결 범위로 제공했다. 탁송·Quest 진행 UI의 같은 factory 실제 조회/명령·저널 소비는 #181로 연결했다. 실제 도착 상관·휴식/업적·시설 구매/배치·꾸미기 등 나머지 연결은 남아 있다. 가챠/초기화·차량 관리의 같은 factory 소비는 #173으로 제공했다. 실제 명령/저장 미지원은 화면에 구분하고 UI 작업 전체를 공통 구현 완료 대기로 바꾸지 않는다. 구체 입출력과 제공/대기 구분은 [기술 계약](docs/02_SYSTEM_ARCHITECTURE.md#전체-기능의-공통-계약과-저장-선행-작업), 순서·책임은 [진행 계획](docs/08_PROJECT_PLAN.md#남은-공통-선행-작업과-병렬-연결-순서), 실제 차단은 각 이슈를 따른다.

선행 결과가 검토·머지되거나 이슈를 완료로 닫으면 이 표도 갱신한다. 세부 정책·실제 연결 조건은 각 이슈를 따른다. 팀 구현이 끝나면 이 섹션을 삭제한다.

평소 이동을 탁송·차량 영입으로 연결하고, 주차 후 차량을 모으고 렌터카 가게를 확장하는 Pleos Connect 차량용 게임을 기획하고 있습니다.

**HOME ShopView·가챠 조회·차고·도감·정산·첫 실행 안내를 실제 탐색에 부분 연결했습니다. 정산은 기존 SQLite 열람 저장·재조회·복원을 사용하며, 첫 실행은 같은 프로필 factory의 NEW 근거·SQLite 원자 지급과 원래 결과 조회에 연결합니다. legacy/불명은 미지원으로 유지합니다.** 실제 #50 지급·관리 경제 명령·앱 전체 흐름 완료와 구분합니다. Debug APK는 로컬 시험 산출물이며 제출 Release APK·실제 게임 시연 영상은 준비 전입니다.

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
| 공통 거래 원장 | #104에서 개체 희귀도·진행량·취득 정보와 단일 개체 저장 확장. SQLite 시험 26개 통과. 지원 v1–v4 정합 fixture 이전·영속 이력/발견/도감 읽기와 mapper는 #116/#139/#140/#149 제공. 재료 두 개체 SQLite·지원 v1–v5 구조 이전은 #156 제공. 필수 누락 legacy 복구·다른 다중 개체 명령·제품 앱 재실행은 남음 |
| 확장 완료 고객 조회 | RentalLedger.completedRentalCount가 실제 대여·공용 정산의 고유 연결을 검증하고 expand의 같은 거래에서 재검사. 원542 실제13PASS와 최신 Runtime parkedSince 정상통합을 구분. 현재 중앙 시험 정의와 같은 factory 기반 확장 preview·명령·원래 결과 복구를 제공. native 성공 구매는 완료 고객0/3으로 미실행이며 시설 구매·배치·자동 영업·실차는 후속. [연결 계약](docs/02_SYSTEM_ARCHITECTURE.md#중앙-시험-정의와-실제-확장-호스트-연결--2026-11-10)·[검증 범위](docs/06_VERIFICATION.md#중앙-시험-정의실제-확장-main-통합--2026-11-10) 참조 |
| 전경 차량 입력·Back | #105의 SDK 단일 worker·늦은 응답/수신 시각 처리. Pleos API34에서 D/P 제한·권한·Back·재생성, 제어된 worker 정지 중 제한·복귀 확인. 공식 폼 팩터·실차·장기 안정성은 남음 |
| 개발 도구·APK | 공통 조합 빌드·Lint, Debug 설치·Activity 기본 실행 확인 |
| 차고·도감 탐색 | #109에서 typed 조회·SQLite→차고·Back·선택 복원과 SDK 제한 연결. 독립 장치 회귀 5개 및 D/P·권한·CCP 확인. 실제 활동·사고·영속 도감의 저장 읽기/mapper는 #149 제공. 같은 factory 관리 명령은 #173에서 연결했다. 실제 발견 표시 쓰기와 개별 제품 수명/전원·실차 검증은 후속 |
| HOME·가챠 조회 부분 호스트 | 실제 시험 출처 SQLite→ShopView/화면선택·Back과 가챠 terminal 조회 연결. 원649 장치12PASS와 새360 동일회귀1GREEN/native Missing·Back/gate를 구분. 이후 실제 draw·첫 지급·관리의 같은 factory 소비와 확정 후 재실행은 #173에서 연결했다. 시설 거래·모든 in-flight 복원/전원·실차는 후속 |
| 정산·첫 실행 연결 | 기존 #108/#110 View를 실제 호스트·gate·Back에 연결. 원본 Settlement→실제 markViewed 응답·재조회, host 회귀9개·격리 장치3개·제품 SDK D/P·권한·CCP와 원장 불변 재시작 확인. 초기화는 NEW/기존 원래 결과 조회와 실제 첫 차 지급을 연결했고 불명/legacy는 Unavailable이다. 전체 history/세부 수익·공식 폼 팩터/실차는 남음. [실행 근거](docs/06_VERIFICATION.md#정산첫-실행-실제-호스트-연결-검증--2026-11-07) |
| 퀘스트·탁송·내 기록 페이지 연결 | 같은 factory와 worker에 실제 조회·typed 명령·저장 복구 진입점을 연결. 실제 첫 플레이의 정산 열람→Quest 청구와 cold 랭킹·Back/복원 근거를 확보했다. SDK 도착 상관·전체 주행/자동 영업·시설과 새 앱 이탈 정책의 실제 연결은 남음. 최종 아트는 디자인팀 작업 중이며 현재는 임시 Views다 |
| 관측 주행 schema13 저장 공급 | D의 관측 reducer/동일 세션 포트와 E의 전체 상태·원출발/정산 저장을 연결. 검증 원본0681의 실제 격리SQLite23개 통과. Main/HostSession의 전경 관측 전달은 #181에서 연결했다. 실제 SDK 관측 전체·정책·전원 검증은 별도이며 [계약](docs/02_SYSTEM_ARCHITECTURE.md#관측-주행-schema13-저장-공급--2026-11-10)·[검증 범위](docs/06_VERIFICATION.md#관측-주행-schema13-통합-후보--2026-11-10)를 따른다 |
| Quest·Delivery schema12 저장 공급 | 실제 원인 기반 Quest 진행/단일 수령·원명령 저널과 Delivery 전체 명령·Hold 쓰기 검증을 기존 저장소에 연결. 원본 b897의 격리 SQLite Quest54·Delivery17 통과. 이후 Driving13은 #179, 같은 factory의 제품 Main/Progress·Delivery 소비는 #181에서 연결했다. typed 실패·foreign 원명령 보존을 유지하며 전체 기능/정책 검증은 별도다. [계약](docs/02_SYSTEM_ARCHITECTURE.md#questdelivery-schema12-저장-공급--2026-11-10)·[검증 범위](docs/06_VERIFICATION.md#questdelivery-schema12-통합-후보--2026-11-10) 참조 |
| 경제 시험값 동의 회수 | [기존 동의된 대여·확장 기준](docs/01_PRD.md#기존-동의된-대여확장-시험값)만 회수. 대여 기준/확장 조건·비용/용량은 중앙 TrialDefinition으로 공급하되 시험값 동의와 검증 범위를 구분. b-economy 전체표를 채택하지 않음 |
| 랭킹 1차 범위 | [PRD 사용자 결정](docs/01_PRD.md#1차-랭킹-범위)으로 실제 내 기록 6개 지표와 분리된 시연용 랭킹을 포함. 누적 수익·주간 보상 반영 거리·과거 보유 도감 칸의 집계 기준도 확정. 같은 원장 조회·독립 RankingView/합성시연 모듈과 Android JSON 분리 수정은 실제 원본13+추가2로 검증. MainActivity 탐색·명시 Demo·Back/복원은 #181로 연결했다. 같은 factory의 실제 Quest 청구 원인·원출발 zone hook을 readRanking에 연결한 후속 후보를 준비했다. 정확 e9007ea의 공개 SIMULATION 저장 경로9개가 통과했고 누락/손상 및 조회 DB 불변을 구분했다. [현재 연결·검증 범위](docs/06_VERIFICATION.md#랭킹-공개-원인-조회-연결-검증--2026-11-10)를 따르며 정확 게시 head/CI·main 반영과 #58 종료 판단은 연결 PR에서 확인한다. 온라인은 향후 범위이며 1차 완료 게이트에서 제외한다 |
| Rental·시설 schema15 저장 공급 | 중앙 시험 정의 v3, 고정 방문·요금·사고·커서와 종료 영업 원점을 같은 DB에 저장한다. 시설 구매는 원래 정산, 영업 쉬기는 비금융 원결과를 재생한다. 원 f99 Rental32·시설14와 요청 ID 반례 RED1→수정 d8 시설15 GREEN을 구분한다. 실제 Main 경제 이탈 driver·시설 화면·제품 DB 이전·Rest·SDK/전원 검증은 남아 있다. [검증 범위](docs/06_VERIFICATION.md#rental시설-schema15-정상-통합-후보--2026-11-10) 참조 |
| 렌터카 runtime 부분 공급 | 기존 RentalLedger의 고정 방문·확인 구간·원래 주차 기준/최초 완료 조회 연결. 독립 코드app23/core13·SIMULATION 동일SQLite10개 통과. 실제 NPC/제품 정의·clock/coverage·durable 방문/factory/Activity·프로세스/실차는 후속 |
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
| [CONTRIBUTING.md](CONTRIBUTING.md) | 팀 기여·협업 가이드 | 문서 언어, 브랜치·커밋·PR, [댓글 요청 추적](CONTRIBUTING.md#댓글-요청과-조율), 검사, 사람 리뷰·머지·게시 절차. 모든 작업 전에 확인 |
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
| [11_DESIGN_IMPLEMENTATION_STATUS.md](docs/11_DESIGN_IMPLEMENTATION_STATUS.md) | 디자인 적용 현황 | 계획한 시안·현재 Android 화면·없는 자산·남은 제작 및 연결 작업. 디자인 기준은 09에서 관리 |
| [공모전 자료 안내](docs/contest/README.md) | 공식 공모전 자료 목차 | 일정·심사·접수, Pleos 개발자료, 차량 API와 공식 자료·다운로드 보관 기록 |
| [OT 전체 요약](docs/contest/05_ORIENTATION_NOTES.md) | 온라인 설명회·질의응답 | 전체 35개 구간의 핵심 내용, 시각별 영상 링크, 기존 안내와의 차이. 기획·제출·SDK 조건 확인 시 참고 |
| [내비 콜백 진단 도구](navi-diagnostic/README.md) | #80 지원용 별도 APK | 다운로드·설치·내장 모의주행 재현·시험 증거·남은 제한. 제품 앱·전체 오류 해결과 구분 |
| [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) | AI 작업 진입점 | 같은 협업·제품·기술·검증 문서를 읽게 하는 지침. 공통 규칙을 중복 작성하지 않음 |

차량 목록의 기준 데이터는 [vehicles.csv](docs/data/vehicles.csv)입니다. PDF 기획서 원본은 로컬에만 보관하며 GitHub에 올리지 않습니다. 조사 문서의 제안은 팀이 결정하기 전까지 확정 기획이나 구현 완료를 의미하지 않습니다.

외부 이미지·캐릭터·로고의 사용 조건은 별도 확인 대상입니다. 현재 화면의 차량 도형은 임시 표현이며 실제 차종으로 주장하지 않습니다. 원격 저장소는 [Yaho03/drive-growth](https://github.com/Yaho03/drive-growth)입니다. 보호 규칙·CI·게시 상태는 [검증 기록](docs/06_VERIFICATION.md)에서 구분해 관리합니다.

## B 담당 검토 제안

[경제·해금·인계 검토 자료](docs/proposals/b-economy/README.md)는 미확정 시험안과 비교 실험이다. 확정 제품 규칙이나 기능 완료 상태는 기존 PRD·검증 문서를 따른다.
