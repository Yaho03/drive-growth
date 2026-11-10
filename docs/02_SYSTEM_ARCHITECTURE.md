# 구조·데이터·상태 계약

제품 범위는 [01_PRD.md](01_PRD.md), 검증 증거는 [06_VERIFICATION.md](06_VERIFICATION.md)를 따른다. 개발 기반 결정과 과거 로컬 구현 기록을 구분하며, 과거 코드의 수치를 새 게임 기획의 최종 규칙으로 사용하지 않는다.

기존 `app/src`·`core/src`는 공통 빌드·시험 재현을 위한 선행 기술 검증 코드다. 렌터카 기획의 구현으로 사용하지 않는다. 실제 게시 버전과 실행 결과는 검증 문서를 따른다.

## 개발 기반 결정

2026-11-03: 게임 세부 규칙에 앞서 정할 수 있는 기반을 문서 기준으로 채택했다. 구체 버전은 [개발환경 기준](05_DEVELOPMENT_SETUP.md#개발-기반과-버전-기준) 한 곳에서 현재 적용값과 권장 조합으로 구분한다. 권장 조합은 KGP·AGP·Gradle·D8/R8·IDE의 공식 조건을 함께 대조했으며 기존 버전을 그대로 확정하지 않았다. 2026-11-04 공통 도구 설정 적용·실행 검증은 개발환경/검증 문서에 별도로 기록한다.

| 항목 | 지금 채택할 기준 | 이유·적용 범위 |
|---|---|---|
| 실행 환경·언어 | Pleos Connect의 AAOS 차량용 Android 앱, Kotlin을 주 언어로 사용 | 공식 시작 가이드의 Kotlin 경로와 기존 프로젝트 기반 활용. 최종 게임 그래픽 엔진까지 선택한 것은 아님 |
| 구조 | 기존 `app`·`core` 경계 유지. `app` → `core` 단방향 의존 | 차량·Android 연결과 게임 계산의 검증을 분리. 기능마다 빌드 모듈을 늘리지 않음 |
| 핵심 로직 | `core`는 Android·Pleos SDK·화면에 의존하지 않음 | 차량 SDK 없이 규칙을 검증하고, 기획 변경이 차량 연결 코드에 번지지 않도록 함 |
| 차량 입력 | 실제 SDK·모의 입력을 정규화한 뒤 같은 핵심 처리 경로에 전달. 출처·단위·시각·누락 상태 보존 | 게임 규칙이 바뀌어도 입력의 의미와 검증 근거를 유지 |
| 성장·평가·화면 | 운전 이벤트 해석, 게임 보상 계산, 화면 연출의 책임을 분리 | 차량 레벨·차종이 운전 평가를 유리하게 하지 않는 제품 원칙 반영 |
| 저장 책임 | 진행 기록·확정 정산·보상 이력의 일관성과 재실행 복원 요구를 유지 | 구체 DB나 파일 형식은 선택하지 않아도 중복 지급 방지 조건은 정의 가능 |
| 검증 방식 | 핵심 로직 단위 테스트, Android 빌드·Lint, 에뮬레이터 시험, 실차 시험을 구분 | 기존 검사 체계를 활용하며 문서상 지원을 실행 성공으로 처리하지 않음 |

공식 언어·환경 근거: [Pleos 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started). 위 구조 선택은 팀 프로젝트의 설계 결정이며 공식 의무 구조라는 뜻은 아니다.

### 결정 대기 항목

| 항목 | 결정에 필요한 선행조건 | 결정 전 다루는 범위 |
|---|---|---|
| UI·그래픽 기술과 버전 | [화면 명세](09_UI_UX_SPEC.md)의 2D Android Views 기준안 | 새 엔진 없이 시작하는 설계 선택. 실제 폼팩터·성능 시험은 구현 후 확인 |
| 저장 기술·스키마 | 정산 경계, 보유 개체·영업·탁송·가챠 데이터, 손상·이전 버전 복구 조건 | 첫 거래 원장은 Android SQLite v1. 적용 범위와 이후 이전 기준은 [거래 원장 영속 저장](#거래-원장-영속-저장) 참조 |
| 백그라운드 서비스·계산 시점 | 게임 기록 정책 답변과 실제 수명주기 검증, 게임 진행 규칙 | Foreground Service·WorkManager 등의 방식과 주행 중 계산 여부 미정 |
| 음성·휴게소·Gleo 연결 | 유형별 주행 허용, 장소 데이터 출처, 인증·스키마 승인·실제 SDK 가용성 | 기능을 제외하지 않고 조건 확인. 사용할 SDK와 호출 흐름은 기능별 확정 |
| 랭킹 후속 온라인 | [PRD의 1차 범위](01_PRD.md#1차-랭킹-범위)에 따라 실제 사용자 간 순위는 향후 연결 | 이번 1차는 실제 내 기록 조회와 분리된 시연용 provider/화면. 새 서버·인증·외부 계정은 구성하지 않음. 후속 온라인의 기술/식별/동의·통신 계약은 별도 |
| 게임 데이터·산식 | 팀의 영업·탁송·강화·보상 수치 합의 | 과거 시험용 계산을 최종 계약으로 승격하지 않음 |

결정 대기는 기능 제외를 뜻하지 않는다. 해당 기능의 입력·동작·결과·실패 처리와 검증 조건이 정해지면 기술을 선택하고 구현 이슈로 나눈다. 전체 게임 규칙의 일괄 확정까지 모든 기반 조사를 기다릴 필요는 없다.

## 렌터카 기획의 데이터·처리 계약 — 설계안, 미구현

2026-11-03 제품 변경 반영. 경제 규칙과 확정 수치는 [PRD](01_PRD.md)의 기준을 참조한다. 아래는 구현 전에 지켜야 할 데이터 경계이며 DB 제품·클래스명·저장 스키마를 확정한 것은 아니다.

| 책임 | 구분해 보존할 정보·처리 조건 |
|---|---|
| 차량 사실·소유 개체 | 차종 `vehicle_id`와 개별 보유 차량 ID를 분리. 동일 차종 여러 대의 레벨·사고 이력·대여·수리·소비 상태를 따로 관리 |
| 탁송 | 의뢰 ID, 수익/구매 유형, 선택 후보·가격·규칙 버전, 목적지, 변경 사용 여부, 결제·완료·포기 상태. 도착 확인과 주차 UI 허용은 별개 |
| 거리 | 실제 관측 거리(m)·예상 경로 거리(m), 각각의 출처·취득 시각·누락 여부. 목록 재생성용 합산 거리와 주행 수익 계산용 실제 거리를 분리 |
| 수익 원장 | 기본 주행·탁송 완료·렌트·시설 수익을 구분. 거래 ID·사유·대상·규칙 버전·금액 저장. 정수 최소 재화 단위와 반올림 규칙은 산식 확정 때 결정 |
| 결제·소비 | 차감과 차량 지급/의뢰 상태/환급을 일관되게 확정. 강화·판매·폐차는 같은 개체의 중복 소비 차단. 구매 변경 차액 정책 확정 전 구현하지 않음 |
| 자동 영업 | 고객 요청·배정 차량·대여 구간·결과·가상 사고 이력. 고객 출현·자동 배정·궁합의 결정된 시험 기준은 PRD를 참조한다. 실제 영업 쉬기·활동·동시 점유와 저장은 #41에서 연결한다 |
| 시간 정산 | 마지막 정산 시점, 이미 반영한 구간, 정상·감액·상한 적용 구간 보존. 같은 구간을 재접속마다 다시 지급하지 않음 |
| 복원·표시 | 확정 결과와 사용자 확인 여부 분리. 미확인 결과는 재실행 때 조회하며 확인 버튼으로 잔액을 다시 증가시키지 않음 |

계획 상태 흐름은 `목적지·후보 선택 → 의뢰 활성 → 도착 확인 대기 → 정산 확정 → 결과 확인`이다. 활성 의뢰에서 변경·포기로 분기한다. 데이터 부족은 도착 성공이나 자동 취소로 치환하지 않고 별도 확인 필요 상태로 표현하는 안을 검토한다. 상태 식별자와 전이 조건은 도착 API·실패 정책 합의 후 확정한다.

앱 화면 숨김, 프로세스 종료, IGN OFF/Suspend, 주차 휴식, 실제 주행을 같은 ‘오프라인’ 신호로 취급하지 않는다. 화면이 숨겨졌다는 이유만으로 감액 수익을 적용해서는 안 된다. 재접속 시 시간 차이로 영업을 계산하는 방식은 후보이며, 지속 백그라운드 실행을 보장하지 않는다. 시간 출처·시계 역행·날짜 경계·다중 기기 충돌 처리는 미정이다.

저장 경계에서는 금액·보유 개체·의뢰 상태·정산 이력을 함께 확정하고 실패 시 재시도 가능해야 한다. 무작위 목록과 가챠·사고 결과는 재실행으로 임의 재추첨되거나 중복 지급되지 않도록 결과와 적용 여부를 저장한다. 재추첨 규칙·원격 검증 여부는 별도 결정한다. 모의 입력과 실제 차량 입력은 같은 처리 경로를 사용하되 기록·보상 출처를 섞지 않는다.

## 과거 로컬 구현 기록

다음 구조·입력·상태·계산은 이전에 작성된 코드의 설명이다. 개발 기반 선택의 출발 자료일 뿐, 미정인 게임 규칙이나 구현 기술을 확정하는 근거가 아니다.

### 구조와 의존 방향

- `core`: Android SDK에 의존하지 않는 `DriveEngine`, 입력·주행·정산 상태, JUnit 테스트.
- `app`: Android 앱과 화면. `core`에 의존한다. 당시에는 `DashboardView` 중심으로 확인했다. 현재 로컬 재확인 결과는 아래 절을 따른다.
- `docs`: 주제별 기준 문서와 공식 자료. 공모전 규칙은 `contest/`에서 관리한다.
- `scripts`: 외부 의존성 없는 저장소 링크·필수 파일 검사.
- `.github`: PR·이슈 양식과 자동 검사. 원격에서 실행된 사실과는 구분한다.
- `.local-tools`: 이 컴퓨터의 SDK·에뮬레이터·캐시, 버전 관리 제외.

계획한 입력 흐름은 차량 어댑터/모의 입력 → `Sample` → `DriveEngine` → 상태·결과의 영속 저장 → 화면이다. 실제·모의 입력이 같은 계산 경로를 사용하되 출처별 저장과 표시를 분리해야 한다.

### 입력 계약

| 필드 | 의미·단위 | 누락·검증 |
|---|---|---|
| `source` | `VEHICLE` 또는 `SIMULATION` | 진행 중 주행에 다른 출처를 혼합하지 않음 |
| `elapsedMs` | 단조 증가 시각, ms | 이전 값 이하이면 입력 무시, 재시작 때 이전 기준 초기화 |
| `epochMs` | Unix 시각, ms | 기록 표시·Asia/Seoul 일일 보상 경계에 사용 |
| `speedMps` | 속도, m/s | null·비유한 값·절댓값 100 초과는 사용 불가 |
| `gear` | P/D/R/N/UNKNOWN | UNKNOWN은 사용 불가 |
| `uxRestricted` | 시스템 조작 제한 여부 | null은 사용 불가, false를 주차 확인 조건에 사용 |
| `valid` | 입력 제공자가 표시한 유효성 | false이면 사용 불가 |

속도의 취득 API 시각과 앱 수신 시각을 연결하는 Android 어댑터는 아직 없다. 데이터가 오래됐을 때의 유효성 판단은 어댑터 구현·검증이 필요하다. 현재 엔진은 유효한 인접 입력의 간격이 5초를 넘으면 그 구간을 적분하지 않는다.

### 상태와 정산

| 상태 | 현재 구현의 의미 |
|---|---|
| `READY` | 활성 주행 없음. 이 상태만으로 UI 조작을 허용해서는 안 됨 |
| `RECORDING` | D/R에서 절댓값 0.5m/s 이상을 관측해 시작한 주행 |
| `WAITING_FOR_PARK` | P·절댓값 0.1m/s 이하·UX 제한 없음의 연속 관측 중 |
| `DATA_UNAVAILABLE` | 필요한 입력이 없거나 유효하지 않음 |
| `SETTLED` | 주차 조건을 3초 확인하고 정산 결과 생성 |

주행 ID는 출처·시작 시각으로 구성한다. 결과 목록에 같은 ID가 있으면 경험치를 다시 지급하지 않는다. `recoverInterrupted()`는 중단 주행을 0XP 기록으로 만들고 단조 시각 기준을 지운다. 당시에는 디스크 저장 연결을 확인하지 않았다. 현재 저장 코드 존재와 실행 검증 상태는 아래 재확인 결과를 따른다.

### 현재 계산과 한계

- 거리: 유효한 연속 샘플의 속도 절댓값을 사다리꼴 적분해 m로 저장.
- 이벤트: 속도 절댓값 변화율이 ±3m/s² 이상인 구간 진입을 계수. 같은 방향 이벤트가 이어지면 중복 계수하지 않음.
- 평가: 유효 시간 60초 이상일 때 `100 - 이벤트 수 × 5`, 0~100으로 제한. 짧은 기록은 평가 없음.
- 경험치: 유효 시간 60초 이상이면 20XP, KST 하루 합계 최대 20XP. 점수와 독립.
- 레벨: `1 + totalXp / 20`, 첫 배지는 레벨 2에서 표시.

이 수치들은 초기 실행 검증용 가정이다. 필요한 긴급 제동을 구분하지 못하므로 안전 평가로 홍보하지 않는다. 현재 렌터카 경제 규칙으로 확정하지 않는다.

### 저장·UI에서 남은 계약

입력 출처, 프로필, 전체 경험치, 정산된 주행 ID와 결과, 진행 중 주행을 보존해야 한다. 저장 실패·손상·앱 종료 상황에서 중복 보상이 발생하지 않아야 한다. 당시 가챠·시즌 후보에 대해 잔액 차감과 결과·보상 ID를 함께 저장하는 조건을 검토했다. 시즌은 현재 기획에서 제외됐으며 현재 계약은 위 렌터카 설계안을 따른다. 아직 저장 형식이나 구현이 완료된 것으로 표시하지 않는다.

실제 주차 확인과 시스템 UX 허용 여부를 UI 입력 제한에 함께 반영한다. 모의 입력 선택으로 실제 주행 중 조작 제한을 우회해서는 안 된다. 화면 단독 컴파일과 이 제한의 실행 검증을 구분한다.

## Pleos 조사 반영 설계 조건 — 미구현·미검증

2026-11-03 보완. 위 엔진 상태·수치는 과거 구현 기록으로 보존하며, 아래는 [PRD의 핵심 흐름](01_PRD.md#핵심-흐름과-성장-전후-변화)에 맞춰 구현 전에 해결할 계약이다. 공식 정책 원문과 해석은 [차량 설계 조건](contest/03_VEHICLE_API.md) 5.1절을 따른다.

**화면 표시, 데이터 수신, 게임 계산, 정산 저장을 별도로 판단한다.** `RECORDING`은 게임 UI 표시 허가가 아니고, UI가 숨겨졌다는 사실은 차량 기록이 계속된다는 증거가 아니다. 구체적인 주행 기록 서비스와 새 상태 식별자는 아직 선택하지 않는다.

심화 조사에서는 조건부 백그라운드 실행 모델을 확인했으나 게임 자동 기록의 예외 허용은 확인하지 못했다. 서비스 채택·구독 유지·중단 복원은 [백그라운드 근거와 문의안](contest/03_VEHICLE_API.md) 5.3·6.1절을 선행 근거로 삼는다. 음성은 [유형별 정책](contest/02_PLEOS_DEVELOPMENT.md) 9.1절을 따르며, 게임 피드백·휴식 권유·STT·Gleo를 하나의 허용 상태로 합치지 않는다. IGN OFF/Suspend와 Activity 숨김은 다른 수명주기 경계로 검증한다.

| 경계 | 설계·검증할 조건 | 미결정·미확인 |
|---|---|---|
| UI → 숨김·일시 중지 | 주행 전환 시 화면·게임 입력을 중단하고 재진입도 제한. 기록 엔진 상태와 별도로 플랫폼 UX 상태를 확인 | 정확한 플랫폼 전환 처리·재진입 동작 |
| 숨김 → 차량 기록 | 허용된 수명주기에서 실제 조회·콜백을 받는지 확인. 권한 거부·연결 해제·누락 구간을 구분 | 게임 카테고리의 백그라운드 기록 허용, OS 종료·재개 동작 |
| 기록 → 게임 계산 | 유효 기록·누락·입력 출처를 전달하고 운전 평가와 성장 계산을 분리 | 주행 중 계산 또는 주차 후 기록 기반 계산. 팀 결정과 플랫폼 근거 필요 |
| 종료 → 복원 | 진행 중 기록의 저장 시점·유실 가능한 범위를 정하고 실제 저장된 부분만 복원 | 저장 방식·주기·중단 기록의 보상 기준. 기존 `recoverInterrupted()`는 최종 제품 정책 아님 |
| 주차 → 정산 확정 | 같은 주행 결과·보상·처리 이력을 일관되게 저장. 저장 성공 전 확정 지급으로 표시하지 않음 | 실패 후 재시도·앱 종료 경계의 원자성 검증 |
| 복귀 → 화면 표시 | 주차·UX 허용 여부를 다시 확인하고 저장된 상태를 조회. 복귀 이벤트만으로 보상 재지급하지 않음 | 주차 후 자동 전면 실행은 보장하지 않음 |

입력 어댑터는 Virtual Console 카드명·단위를 SDK 데이터와 그대로 동일시하지 않는다. 크루즈 목표 속도와 실제 속도, 페달 백분율과 Boolean 작동 상태, 스티어링 휠 각도와 앞바퀴 조향각을 구분한다. 근거와 콘솔 입력 범위는 [개발 자료](contest/02_PLEOS_DEVELOPMENT.md) 5절을 따른다. 실제 SDK·에뮬레이터 콜백의 출처 표시 방식은 구현 시 확인하며 기존 `source` 값만으로 실차 검증을 주장하지 않는다.

## 로컬 코드 재확인 — 2026-11-03, 읽기 검사

`MainActivity`·`DriveApplication`·`DriveRuntime`·`RecordingService`·`VehicleInput`·`SnapshotStore`와 Manifest가 존재한다. Activity→서비스→Runtime→차량 조회/엔진/저장/화면의 연결 코드를 확인했다. `SnapshotStore`는 `AtomicFile`을 사용하며 Runtime은 실제·모의 출처별 저장과 중단 복원을 호출한다. 소스 존재·정적 연결 확인은 빌드·디스크 복원·실제 수신의 실행 성공을 뜻하지 않는다.

Manifest의 `distractionOptimized=true`와 `dataSync` Foreground Service 선언, 주행 중 화면 표시·알림 처리 방식은 현재 게임 정책에 적합하다고 승인한 구성이 아니다. #13에서 공식 조건과 실제 제한 동작을 대조해야 한다. #20은 저장 실패 시 메모리 상태와 디스크 일관성, 재실행·손상·중복 정산을 현재 코드로 확인해야 한다. 렌터카 영업·탁송·구매 원장·개별 보유 차량은 이 선행 코드가 구현한 기능으로 표시하지 않는다. 이번 공통 기반 공유에 이 소스를 포함하되 렌터카 기능 구현으로 표시하지 않는다.

## 첫 병렬 구현의 공통 계약 초안

#27 작업 산출물이다. 아래는 기능 담당자가 검토할 문서 계약이며 Kotlin 모델·DB 구현 완료가 아니다. 미정 숫자는 [PRD 시연용 시험값 운영](01_PRD.md#1차-시연용-시험값-운영)에 따라 버전 있는 단일 정의/provider로 공급하고, 수치 밖의 미정 제품 의미·정책은 #24에서 정한다. 첫 구현에서 필요한 경계를 먼저 정하고 온라인·전체 콘텐츠까지 한 번에 확정하지 않는다.

### 타입·식별자·입력 출처

| 공통 값 | 계약 초안 |
|---|---|
| 개체·거래 ID | 불투명 문자열. 차종 `vehicleId`와 소유 개체 `ownedVehicleId`는 구분. 화면에서 이름으로 개체를 식별하지 않음 |
| 거리 | `distanceMeters`: 유한한 0 이상의 수. 실제 관측·예상 경로를 다른 필드에 보관하며 예상 거리를 주행 수익에 더하지 않음 |
| 시각·기간 | `occurredAtEpochMs`는 UTC Unix ms, `durationMs`는 0 이상 기간. 센서의 단조 시각과 혼합하지 않음. 보상 일자·시계 역행 정책은 결정 대기 |
| 재화 | `amount`: 게임머니 최소 단위의 0 이상 정수. 차감/지급 방향을 별도 구분. 소수 산식의 반올림·최대 잔액은 규칙 결정 전 지급에 사용하지 않음 |
| 출처 | `VEHICLE` / `SIMULATION`. 실제 차량 식별값이나 개인 위치가 ID에 포함되지 않음. 예상 경로는 이 출처와 별도로 `distanceKind=ESTIMATED` 표시 |
| 버전 | 저장 `schemaVersion`, 계산 `ruleVersion`, 콘텐츠 `contentVersion`을 구분. 실행 시 사용한 버전을 결과에 보존 |

### 최소 데이터 관계

필드 표는 논리 스키마이며 특정 DB 테이블 구현을 강제하지 않는다. 선택 필드는 값이 없는 이유를 상태·오류와 함께 표시한다.

| 레코드 | 필수 정보 | 관계·제약 |
|---|---|---|
| VehicleDefinition | vehicleId, displayName, contentVersion | 사실 CSV 참조. 게임 가격·희귀도·효과는 검증된 별도 콘텐츠로 관리 |
| OwnedVehicle | ownedVehicleId, vehicleId, rarity, level, levelProgressPercent, acquisitionCost, acquisitionSource, revision, lifecycle | 동일 차종의 희귀도·성장·취득 정보를 개체별로 보존. 처분 개체는 재대여·재소비 불가 |
| VehicleCondition | ownedVehicleId, 사고 이력 참조, 현재 수리 상태 | 사고 이력과 수리 후 회복 상태를 분리. 손상/대여/수리의 동시 허용 정책은 #24 |
| Delivery | deliveryId, type, status, revision, ruleVersion, 출처 | INCOME/PURCHASE 구분. 목적지 참조·선택 제안·결제 거래·변경 사용 여부 보존. 원시 목적지 위치 저장은 별도 데이터 정책 필요 |
| DriveRecord | driveId, source, 실제 관측 구간, 누락 구간, 거리·시간 | 센서 입력의 측정/수신 시각 구분. 미관측 구간 보간으로 지급하지 않음 |
| Rental | rentalId, ownedVehicleId, status, ruleVersion | 활성 대여가 동일 개체를 중복 점유하지 않음. 고객 요청·배정·시간·사고 결과 보존 |
| Transaction | transactionId, requestId, 종류, amount, ruleVersion, 관련 의뢰/대여/개체 ID | 하나의 요청에 한 확정 결과. 주행·탁송·렌트·시설 수익을 구분 |
| Settlement | settlementId, 관련 원인 ID, 거래/개체 변화, confirmedAtEpochMs | 확정 결과 불변. 사용자 열람 상태는 별도 저장 |
| TimeAccrual | streamId, accountedThroughEpochMs, 정산 구간·적용 규칙 | 한 시간 구간 재정산 금지. 종료/주행/화면 숨김을 동일시하지 않음 |

OwnedVehicle의 `lifecycle` 초안은 OWNED/SOLD/SCRAPPED/CONSUMED다. 대여·수리 상태는 별도 관계로 표시한다. Delivery의 기술 상태 초안은 SELECTED/ACTIVE/AWAITING_EVIDENCE/SETTLED/CANCELLED이며 도착 증거 부족을 자동 취소·성공으로 전환하지 않는다. 경제적 처리와 각 전이 허용 조건은 #24·#13 확인 후 확정한다.

### 담당별 호출 경계

아래 이름은 구현 시 사용할 인터페이스 제안이다. 함수 구현·최종 시그니처가 이미 있다는 뜻이 아니다.

| 호출 | 호출자 → 책임 | 입력 → 결과 |
|---|---|---|
| listOwnedVehicles | A/B/D → C 차량 조회 | 조회 조건 → 개체별 현재 revision·대여 가능 판단 근거. 실제 예약은 별도 거래. #46 첫 구현은 `io.drivegrowth.core.garage.Garage`(`owned`·`find`·`availableForRental`·차종×희귀도 `collection`)이며 저장소 목록을 받아 조회만 한다. 콘텐츠에 없는 차종·중복 개체 ID는 `INVALID_INPUT`, 없거나 처분된 개체는 `DATA_UNAVAILABLE` |
| proposeDelivery / changeDelivery | D 화면 → A 탁송 | 목적지/거리 근거·선택 요청 → 후보/활성 의뢰 또는 결정·데이터 부족 오류 |
| calculateRentalResult | B 영업 | 확정 규칙·차량 스냅샷·대여 입력 → 계산 결과안. 잔액을 직접 저장하지 않음 |
| prepareVehicleChange | C 차량 | 강화/수리/처분 요청·개체 revision → 변화 결과안. 다른 기능의 원장을 직접 수정하지 않음 |
| commitGameChange | A/B/C → 공통 저장 경로 | requestId·예상 revision·금액/개체/상태 변화 → 저장된 확정 결과 또는 오류 |
| listPendingResults / markResultViewed | D → 공통 조회/표시 | settlementId → 미확인 결과 조회/열람 처리. 열람으로 재지급하지 않음 |

A가 도착을 확인해 차량 지급을 요청하더라도 소유 개체 생성과 금액·의뢰 상태의 확정은 공통 거래 경로를 사용한다. B가 차량을 배정할 때 조회 결과만 믿고 대여를 시작하지 않고 revision을 검사하는 동일 경로에서 점유를 확정한다. D는 저장 파일·잔액·게임 DB를 직접 변경하지 않는다. 공통 저장 코드의 작성 담당은 #25에서 별도 확정하며 #27의 설계 담당과 자동으로 같다고 보지 않는다.

#### #46 Garage 조회의 부분 인계 범위

2026-11-07 PR #100 검토에서 확인한 카탈로그 원본 변경과 활동 조회 실패 경계를 보완한다. `Garage`는 생성 당시 차종 ID 목록을 복사해 개체 검증과 도감 계산에 같은 기준을 사용한다. `availableForRental(vehicles, activityOf)`의 공급자는 개체 ID를 받아 `ContractResult<VehicleActivity>`를 반환하며 조회 중 거래·점유 등 상태 변경을 수행하지 않는다. 활동 정보 누락은 `DATA_UNAVAILABLE`, 저장 조회 실패는 `STORAGE_FAILURE` 등 실제 실패로 전달한다. Garage는 실패 이유를 그대로 반환하고 후보 일부를 성공으로 반환하지 않으며, 누락을 `IDLE`로 채우거나 예상하지 못한 예외를 일괄 변환하지 않는다.

- `availableForRental`은 현재 `OWNED`·`IDLE` 후보를 거르는 조회이며 영업 쉬기는 보지 않는다. 영업 쉬기는 #41의 `planRental(…, resting, …)`이 이 결과에서 `RentalLedger.restingVehicles()` 개체를 빼서 적용하고, 켜기/끄기는 `RentalLedger.setResting`이 저장한다(대여 중에 켜면 반납 후 배정에서 빠짐). 최종 자동 배정 가능 여부·점유·최신 revision과 동시 상태 변경 검사는 #41의 공통 거래 연결에서 별도로 확인한다.
- `owned`는 사용 중·수리 필요 개체도 포함하므로 강화 가능 목록으로 그대로 사용하지 않는다. 대여 후보 목록도 수리·판매·폐차 가능 목록으로 재사용하지 않는다. 수리 필요 차량의 수리·판매·폐차는 PRD에서 허용하므로 각 명령의 활동 제한을 별도로 검사한다.
- `collection`의 `OWNED`는 한 번이라도 소유한 기록이다. 호출자는 처분 뒤에도 유지되는 `(vehicleId, rarity)` 획득 이력을 `everOwned`로, 탁송 목록·가챠 결과에서 본 기록을 `discovered`로 제공해야 한다. 도감 키를 직접 주입한 계산 시험은 실제 저장·재실행 시험과 다르다.

현재 `GameChangeStore.listOwnedVehicles()`의 개체 타입은 아래 v1 필드를 포함한다. 메모리 조회·변경 예제와 SQLite의 필드 보존용 최소 codec 대응은 반영했다. 시험용 DB의 SQLite 실행 범위는 [검증 문서](06_VERIFICATION.md)의 PR #104 숫자 decoder 재검증 기록을 따르며 실제 제품 DB 이전·앱 재실행 검증과 구분한다. 처분 포함 이력·발견 기록, 실제 활동·영업 쉬기의 영속 공급은 별도다. 필드·명령은 아래 공통 계약을 사용하고 실제 조회 확장·SQLite 호환·재개방 검증은 #34 담당과 아래 연결점으로 조율한다. #46은 이 조회·계산 부분의 인계이며 저장·재실행을 포함한 전체 완료가 아니다. 기능별 본인 검증과 #28 통합 검증은 유지한다.

`Garage.collection(history: VehicleHistoryStore)`는 #116의 `listVehicleHistory()`(처분 포함 전체 개체)에서 `(vehicleId, rarity)` 보유 키를, `discoveredVehicles()`에서 발견 키를 읽어 위 `collection(everOwned, discovered)`를 계산한다. 이력·발견 조회 실패는 빈 도감으로 바꾸지 않고 그대로 전달하며, 이력의 콘텐츠 밖 차종·중복 개체 ID는 `INVALID_INPUT`이다. 공급 목록은 불변을 약정하지 않으므로 `Garage`의 모든 조회는 받은 목록을 먼저 한 번 복사하고 그 사본으로 검사·선택·키 변환을 한다(복사 자체가 외부 동시 변경에 원자적이라는 뜻은 아님). 가챠 결과·탁송 목록에서 본 키는 호출자(화면·호스트)가 `recordDiscoveries(catalog, keys)`로 먼저 기록한다.

#### #50 첫 실행 지급

`io.drivegrowth.core.garage.FirstGrant.grant(store, chosen, source)`는 PRD 「최초 차량」·「초기 상태」 시험값대로 초기 재화 1,000 CREDIT과 고른 첫 차(캐스퍼·아반떼, COMMON·1레벨·0%·취득액0·`INITIAL_GRANT`, 개체 ID `initial-grant`)를 `GameChange` 한 건으로 커밋한다. SQLite 계정 잔액은 0에서 시작하므로 이 거래가 초기 재화의 유일한 지급 경로다. 초기 차고 칸 수는 `FirstGrant.INITIAL_GARAGE_CAPACITY`(3)로 제공한다.

프로필당 고정 `requestId`(`initial-grant`)를 쓰므로 같은 차로 반복하면 처음 정산을 재생하고, 다른 차는 `STATE_CONFLICT`(선택 취소 불가), 후보 밖은 `INVALID_INPUT`, 콘텐츠에 없는 후보는 `DATA_UNAVAILABLE`이다. 첫 차를 처분해 보유가 0대여도 재지급하지 않는다. 단, 이 방지는 `initial-grant` 요청이 기록된 저장소에만 해당한다. 이 요청 기록이 없는 기존 데이터(다른 경로로 재화·첫 차를 받은 DB)에 호출하면 다시 지급되므로, 호스트는 **신규 프로필로 확인된 경우에만** 호출하고 확인할 수 없으면 Unavailable/복구 필요를 유지한다. 빈 보유 목록이나 미확인 정산 없음은 신규 프로필 근거가 아니다. 후보 목록 `CANDIDATES`는 읽기 전용이다. 규칙 버전이 바뀐 뒤 재시도도 명령이 달라 `STATE_CONFLICT`이며 재지급하지 않는다. 화면이 지급 전에 "이미 받은 프로필"인지 알려면 처분 포함 이력 조회(아래 「#34 저장 확장의 연결점」)가 필요하다. 개업 선물 뽑기권은 아래 `prepareOpeningGift`가 명령을 만든다.

`FirstGrant.alreadyGranted(history)`는 처분 포함 이력에 `INITIAL_GRANT` 개체가 있으면 true(이미 받은 프로필)다. 첫 차를 판매·소비해도 true다. false는 "이력에 첫 지급 기록 없음"이며 신규 프로필 근거가 아니다. 마커 없는 기존 DB 판별(NEW/EXISTING/UNKNOWN)은 #121 후속이다. 조회 실패는 그대로 전달한다.

#121 계약(PR #147)을 소비하는 제품 경로는 아래 세 함수다. 모두 저장하지 않는 준비이거나 계약 어댑터로 한 번에 확정하며, 조회 실패는 받은 오류 그대로다.

| 함수 | 동작 | 거절 |
|---|---|---|
| `choices(store: InitializationChangeStore, profileId)` | 저장 측이 NEW 근거를 준 프로필만 `CANDIDATES` 중 현재 콘텐츠·저장 측 후보에 있는 차를 돌려준다. 이미 받은 프로필(EXISTING)은 빈 목록이다(다시 고르지 않음) | UNKNOWN·후보 없음 `DATA_UNAVAILABLE`(무지급) |
| `choose(store, profileId, chosen)` | NEW만 근거와 `INITIAL_GARAGE_CAPACITY`로 `commitFirstGrantInitialization`을 호출해 초기 재화·첫 차·초기 공간·마커를 한 번에 확정한다. 이미 확정된 프로필(EXISTING)은 현재 콘텐츠로 다시 만들지 않고 **보관된 원래 전체 명령을 그대로** `commitInitialization`에 제출한다. 그래서 콘텐츠·규칙 버전이 바뀌거나 원래 차종이 빠져도 같은 선택은 처음 결과를 재생한다. 응답을 잃으면 같은 선택으로 다시 부른다 | 다른 차 `STATE_CONFLICT`, 다른 프로필 `INVALID_INPUT`, 원래 명령에 첫 차 없음 `STORAGE_FAILURE`, 원래 결과 없는 기존 프로필·UNKNOWN `DATA_UNAVAILABLE`(commit 호출 안 함) |
| `prepareOpeningGift(store: FirstRentalGiftStore, profileId, source)` | 같은 프로필 원장의 최초 완료 영업(rentalId·정산 ID)과 현재 가챠 revision으로 고정 요청 ID `opening-gift`의 `FirstRentalGiftCommand`(무료 뽑기권 +1, 돈·차량 불변)를 만든다. 이미 지급된 프로필은 원래 명령을 돌려주므로 다시 제출하면 처음 결과가 재생된다(프로필당 1회). 호출자가 `commitFirstRentalGift`로 확정한다 | 다른 프로필·출처 `INVALID_INPUT`, 완료 영업 없음 `DATA_UNAVAILABLE`, 버전·revision 불일치 `STATE_CONFLICT` |

`commitInitialization`은 아래 #121의 명시 메모리 참조로 제공하며 실제 SQLite/NEW 근거 발급은 후속이다. `commitFirstRentalGift`도 아래 같은 메모리 원장의 참조로 제공하며 실제 SQLite/live 확정은 후속이다. #151 당시에는 계약의 `prepareInitialization`/`prepareFirstRentalGift` 응답을 공급하는 시험 저장소로 후보·선택·재요청·선물 명령을 검증했다. 현재 같은 메모리 원장의 실제 참조 확정은 아래 #121 공급과 구분한다. NEW 근거 발급과 legacy 복구 정책은 저장 측 책임이다.

### 원자성·오류·재시도

- 성공 응답은 금액·개체·의뢰/대여·정산·요청 처리 이력을 함께 저장한 이후에만 반환한다. 중간 상태를 화면에 지급 완료로 표시하지 않는다.
- `requestId`와 요청 내용 식별값을 저장한다. 같은 ID·같은 내용은 기존 결과를 반환하고, 같은 ID·다른 내용은 충돌로 거절한다. 재시작 후에도 같은 규칙을 적용한다.
- 상태 변경은 예상 `revision`을 검사한다. 오래된 조회로 판매·대여·강화가 충돌하면 최신 상태를 조회하게 한다. 재화 부족·사용 불가 개체를 자동 재시도로 우회하지 않는다.
- 오류 종류 초안: INVALID_INPUT, POLICY_UNRESOLVED, DATA_UNAVAILABLE, UX_RESTRICTED, INSUFFICIENT_FUNDS, CAPACITY_EXCEEDED, STATE_CONFLICT, STORAGE_FAILURE. UI는 오류 코드와 복구 행동을 받되 예외 원문·민감 로그를 그대로 노출하지 않는다.
- 난수 후보·가챠·사고 결과를 확정한 후 저장하고 재실행에서 임의 재추첨하지 않는다. 확률·생성 시점·후보 갱신은 제품 규칙 대기다.
- 영속 저장 구현과 이전 기준은 [거래 원장 영속 저장](#거래-원장-영속-저장)을 따른다.

### 기능별 독립 개발용 공통 예제

아래 수치는 테스트 입력 예시일 뿐 최종 경제 규칙이 아니다. 가짜 구현도 동일 인터페이스·오류를 사용하며 실제 차량 연동으로 표시하지 않는다.

| 입력 | 기대 결과 |
|---|---|
| 같은 vehicleId의 개체 v1/v2 조회 | 독립 개체 두 개. v1의 변경으로 v2의 상태가 바뀌지 않음 |
| request r1로 100 지급, 같은 내용 두 번 전달 | 거래/정산 한 개, 잔액 총 증가 100. 앱 재실행 후 같은 요청도 동일 |
| request r1을 200 지급 내용으로 재사용 | 충돌, 추가 지급 없음 |
| v1 revision=3으로 판매와 대여 동시 요청 | 먼저 확정한 한 요청만 성공, 나머지는 충돌. 중복 처분/점유 없음 |
| 실제 이동 30,000m + 새 예상 경로 20,000m | 목록 계산 근거 50,000m, 관측 거리 30,000m 유지 |
| 31분 종료 구간, 정상 30분 규칙(PRD 2026-11-04) | 30분 정상·1분은 정상의 30%로 분리. 최대 8시간 초과분은 지급하지 않음 |
| 저장 확정 직전 실패 → 동일 요청 재시도 | 전체 이전 상태 또는 전체 확정 상태 중 하나. 부분 지급·중복 개체 없음 |
| 결과 조회·열람 처리 반복 | 결과·잔액 불변, 열람 여부만 변경 |

위 표 중 공통 계약 첫 인계에서 실행할 수 있는 범위는 아래 구현 경계에 기록한다. 재실행·실제 저장·도착 판정은 해당 구현 이슈의 검증으로 이어간다.

### 데이터 취급·변경과 첫 합의 범위

첫 로컬 흐름은 차량 개체·게임 원장·정산 중심으로 설계한다. 실제 위치·주행의 저장 필요성, 보관/삭제·로그 범위·권한 거부 처리는 #13과 #24의 근거를 받아 정한다. 온라인·휴대폰 연동을 채택하면 인증·접근권·통신 오류·동기화 충돌·중복 지급의 별도 계약이 필요하다. 현재 계약으로 서버나 개인정보 전송을 승인하지 않는다.

우선 합의할 것은 ID·단위·출처, 개별 차량, 금액/상태 변경 경로, 결과 조회, 공통 오류와 위 예제다. 활성 대여/처분 규칙, 도착 근거·환급 차액·시간 정책은 관련 구현의 선행조건으로 남는다. #27 전체 종료 전에 합의된 구간을 기준 버전으로 지정할 수 있으나, 변경 시 영향받는 기능 담당자에게 알리고 공통 예제와 호출자를 함께 검토한다.

### 첫 계약의 구현 인계 조건

첫 논리 계약 기준은 `rental-contract-v0`였으며, 2026-11-07 필수 개체 정보 확장부터 구현 식별값은 `rental-contract-v1`이다. DB 스키마 버전·규칙 버전·콘텐츠 버전과 구분하며 팀 전원 승인 기록으로 해석하지 않는다. 공통 모델 이슈에서 다음 항목을 구체 타입으로 고정하고 소비자가 같은 타입을 import하도록 만든다.

- ID는 비어 있지 않은 String 기반이며 차종/개체/요청 ID를 혼용하지 않는다. 거리·시각·금액은 Long, revision·schemaVersion은 정수로 시작하는 안을 검증한다. 범위 초과는 오류이며 오버플로를 보상으로 저장하지 않는다.
- nullable 값은 `Missing(reason)` 또는 동등한 명시적 누락 표현과 함께 정의한다. 센서 시각 없음, 권한 거부, 미지원, 오래된 입력을 값 0으로 바꾸지 않는다.
- 명령에 requestId·기준 revision·출처·규칙 버전, 결과에 확정 정산 ID·변경 후 revision·실패 이유를 포함한다. 최종 필수/선택과 함수 서명은 공통 모델 PR에서 호출자와 함께 고정한다.
- 버전 불일치·손상은 새 게임으로 자동 초기화해 덮어쓰지 않는다. 기존 데이터 보존·복구 불가 상태를 반환하고 사용자 복구 절차를 제시한다. 실제 이전 버전 마이그레이션은 존재하는 버전 대상으로 시험한다.
- 시계 역행은 음수 수익·같은 구간 재지급으로 처리하지 않는다. 같은 부팅 내 경과 시간과 영속 시각을 구분하고 재부팅·시간 변경 시 확정할 수 없는 구간의 처리는 #24 결정에 연결한다.
- 로그에는 원시 목적지 좌표·차량 식별자·프로필 개인정보를 남기지 않는 기본안을 사용한다. 첫 계약 자체에는 외부 전송이 없다. 실제 위치 보관 필요·기간·삭제와 온라인 계약은 채택 기능별로 추가한다.

#### 공통 타입과 fixture의 첫 구현 경계

패키지는 `io.drivegrowth.core.contract`이고 계약 식별값은 `RENTAL_CONTRACT_VERSION`이다. #33의 첫 인계는 #34 거래 저장과 #35 콘텐츠 로딩이 함께 사용할 아래 경계를 제공한다. #33의 첫 계약과 #34·#35의 소비 경계는 본인 검증 및 PR 검토에서 확인한다. 완료된 #62의 과거 연결 시험은 참고 근거로 보존하고, 새 변경의 기능 간 검증은 #28에서 확인한다.

| 소비 작업 | 첫 시그니처와 필드 |
|---|---|
| #34 거래 | `GameChangeStore.commitGameChange(change: GameChange): ContractResult<Settlement>`. 요청 ID, 종류, 출처, 규칙 버전은 필수다. 재화 변화와 개체 쓰기는 각각 생략할 수 있으며 최소 하나는 필요하다. 신규 개체는 `VehicleWrite.Add`, 기존 개체는 기대 revision을 받는 `VehicleWrite.Update`로 표현한다. 결과는 정산 ID·거래·잔액·시각과, 개체 변경이 있을 때 갱신된 개체를 포함한다. |
| 보유 조회 | `GameChangeStore.listOwnedVehicles(): ContractResult<List<OwnedVehicle>>`. 차종 ID와 보유 개체 ID를 구분한다. 개체는 희귀도·레벨·진행량·실제 취득 기준액·취득 출처·revision·생명주기를 가진다. |
| #35 콘텐츠 | `VehicleCatalog.load(): ContractResult<VehicleCatalogSnapshot>`. 스냅샷이 하나의 `ContentVersion`을 가지고, 각 차종에는 ID와 표시 이름을 둔다. |
| 차량·화면 입력 | `DeliveryDistanceInput`에서 `ObservedDistance`와 `EstimatedRouteDistance`를 별도 타입과 출처로 받는다. `Observation.Missing`에는 누락 사유가 필수다. |
| 공통 실패 | `ContractResult.Failure`에 `ContractError`를 제공한다. 권한 거부와 데이터 없음, 정책 미정, 충돌, 저장 실패를 구분한다. |

거리 m와 게임머니 최소 단위는 0 이상 `Long`, UTC Unix ms는 `Long`, revision은 0 이상 `Int`다. 재화 방향은 별도 필드로 전달한다. 공통 계약은 정수 결과를 받고, 소수 거리 환산과 경제 산식의 반올림은 값을 계산하는 호출 측 정책에서 정한다. 저장 구현은 범위 초과를 저장 전에 실패 처리한다.

`core/src/testFixtures`의 `RentalContractFixtures`에서 시험 차량 v1/v2, 관측 30,000m·예상 20,000m, 고정 시각·규칙 버전·seed 33, 성공·실패 카탈로그와 메모리 저장소를 얻는다. `:core:test --tests '*RentalContractConsumerTest'`로 소비 예제를 실행한다. 다른 모듈의 테스트에서는 필요할 때 `testFixtures(project(":core"))`를 테스트 의존성으로 사용한다.

#35의 첫 로더는 `io.drivegrowth.core.content.GameContentLoader.load(사실 CSV, 게임 콘텐츠)`다. 사실 CSV에서는 `vehicle_id`·`model_name`만 읽는다. 이미지·로고·3D 열은 읽지 않는다. 게임 콘텐츠는 별도 파일이다. 머리 메타에는 콘텐츠·규칙 버전, 시험값 여부, 가격 단위 `price_unit`, 가챠 천분율 가중치(합 1000)·천장·보장 희귀도가 들어간다. 본문(`vehicle_id,price_ratio_percent,unlock_shop_level`)에는 차종별 가격 비율(1.0 = 100)과 해금 가게 레벨이 들어간다. 차종 가격은 `price_unit × 비율 / 100`(마지막에 한 번 내림)이다. 중간 곱이 Long을 넘어도 최종값이 Long 안이면 계산하고, 최종값이 Long을 넘거나 1 미만이면 거절한다(차종 기준가는 양수. 첫 지급·무료 취득액 0과는 다른 값). 금액 단위는 #44 합의 시험값(기준 차종 1,000)이며, 바꿀 때는 `price_unit` 한 값과 콘텐츠 버전만 바꾼다. 다음 경우에는 `ContractError.INVALID_INPUT`으로 거절하고 기본값을 넣지 않는다.

- 사실 CSV에 없는 ID 또는 중복 ID
- 0 이하·비정수 가격 비율 또는 가격 단위, 1 미만 해금 레벨
- 확률 합 오류
- 보장 희귀도가 RARE 미만이거나 보장 대상 희귀도의 가중치 합이 0

**2026-11-05 PRD에서 희귀도가 개별 차량 단위로 바뀌었다.** 2026-11-07 #35에서 콘텐츠 본문의 희귀도 열과 첫 레벨 보장 대상 검사를 제거했다. 모든 차종이 모든 희귀도로 나오므로 보장 후보는 해금 차종이 1종 이상이면 되고, 콘텐츠에 차량이 하나라도 있으면 첫 해금 레벨부터 성립한다. 개별 차량의 희귀도·레벨 진행량·취득 정보는 PR #104의 `OwnedVehicle` v1 계약과 단일 개체 저장에 반영됐다. 각 기능은 아래 v1 계약의 읽기/쓰기·갱신·요청 동일성·당시 정산 재생을 소비하고, #35·#48·#49의 실제 명령 연결과 구형 데이터 보존·이전·다중 개체 거래 등 남은 저장 확장은 #34 담당과 조율한다. #34의 과거 완료 근거를 새 필드의 저장 검증으로 간주하지 않으며 담당은 #34의 경민과 조율한다. 차종별 희귀도 검사를 대체해도 확률 합·보장 등급·양수 가중치 등 추첨 가능한 보장 설정의 검증은 #35·#47에 유지한다. 성공 시 읽기 전용 목록·확률을 가진 `GameContentSnapshot`을 돌려주고, `asVehicleCatalog()`로 위 `VehicleCatalog` 계약을 제공한다. 테스트 입력은 testFixtures의 `GameContentFixtures`(5종, 테스트용 해금 레벨)다. 앱에 넣는 콘텐츠 `app/src/main/assets/content/`는 [PRD의 차량 표](01_PRD.md#1차-차종가격-비율해금-배치)의 20종·가격 비율·해금 배치를 그대로 옮긴 `trial-content-2026-11-09`다. `price_unit=1000`은 [#24의 #44 수치 제안](https://github.com/Yaho03/drive-growth/issues/24#issuecomment-6704210916)에 팀 4명이 동의한 시험값이다(캐스퍼 1,000 ~ G90 3,000). 이전 `trial-content-2026-11-07`의 `price_unit=8000`은 임시 입력이었으며, 가격이 바뀌므로 콘텐츠 버전을 올렸다. `GameContentLoaderTest`가 이 파일을 직접 읽어 해금 수(8·14·18·20)와 가격 폭(3배)을 확인한다. 테스트 fixture(`GameContentFixtures`)의 `price_unit=8000`은 시험 입력이라 바꾸지 않았다. 남은 결정은 가게 레벨별 해금 조건·배치 조정(#44)이다. 뽑기권 가격의 콘텐츠 메타 이전은 PR #111 머지 후 진행한다.

2026-11-07 PRD 결정에 따라 취득 기준액은 실제 지불액을 보존하고 무료 뽑기권·첫 지급 차량은 0, 할인 뽑기권은 실제 할인가를 사용한다. 차종 가격, 판매·폐차의 취득 기준액, 현재 보유 차량으로 계산하는 최고가 랭킹 평가액은 서로 구분한다. 최고가 산식과 첫 지급 희귀도는 [PRD의 시작 경험·주요 기능](01_PRD.md#시작-경험과-주요-기능)을 따른다. 이 결정만으로 `OwnedVehicle` 필드·SQLite 저장·요청 동일성·정산 재생이 구현된 것은 아니며 위 공통 계약·저장 조율에서 함께 검증한다. B 경제 제안의 금액을 확정값으로 결합하지 않는다.

이 첫 버전은 한 요청에서 재화 변화와 보유 개체 한 대의 변경을 표현한다. 다중 개체 소비, 의뢰·대여·시간 구간·열람 상태의 거래 모델은 후속 계약 범위다. 메모리 fixture는 그 정책이나 실제 저장을 대체하지 않는다.

구체 구현 인계: [공통 모델·fixture #33](https://github.com/Yaho03/drive-growth/issues/33), [원자적 저장 #34](https://github.com/Yaho03/drive-growth/issues/34), [콘텐츠 로딩 #35](https://github.com/Yaho03/drive-growth/issues/35). #27은 논리 계약과 담당 간 경계 확인을 추적하며 이 세 이슈의 코드 완료를 대신하지 않는다.

### 차량 표본·화면 잠금·도착 증거 계약

| 소비 작업 | 입력과 반환 기준 |
|---|---|
| #36 차량 입력 | `VehicleObservation`은 입력 출처, 동일 부팅 내 단조 수신 시각(ms), UTC Unix 수신 시각(ms), 속도(m/s), 기어와 UX 제한을 전달한다. AAOS 조회 완료의 수신 시각은 센서 측정 시각과 구분한다. |
| #51 화면 조립 | `Observation<VehicleInteraction>`을 받는다. `ALLOWED`일 때만 게임 조작을 열고, `RESTRICTED` 또는 `Missing`이면 제한을 유지한다. |
| #37·#38·#39·#53 탁송 | 관측 이동 거리와 내비 예상 경로 거리에는 기존의 별도 거리 타입을 사용한다. 도착은 활성 경로의 앱 내부 `RouteId`, 입력 출처, UTC 수신 시각을 가진 `DeliveryArrivalEvidence`로 전달한다. |

속도·기어·UX는 각각 `Observation`으로 전달한다. 미수신, 권한 거부, 미지원, 오래된 값은 각각 `NO_DATA`, `PERMISSION_DENIED`, `UNSUPPORTED`, `STALE`이다. 누락을 속도 0이나 제한 해제로 치환하지 않는다. 주차 후보는 차량 출처, 유한한 정차 범위 속도, P, UX 허용을 모두 요구한다. #36에서 시각 유효성·연속 관측을 확인한 후 화면 상태를 결정한다. 허용 속도 오차와 시간 기준은 소비자가 정책을 명시해 전달한다.

2026-11-07 #51의 최상위 Back은 전경 관측·조작 판정을 초기화한 뒤 Task를 뒤로 보내고 게임 창도 숨긴다. Pleos 다중 창에서는 `moveTaskToBack(true)` 후에도 Activity가 `RESUMED`·표시 상태에 남는 것을 재현해 공개 `Activity.setVisible(false)`를 함께 사용했다. 일반 `onResume` 또는 API 29 이상 top-resumed 복귀에서 창과 관측을 다시 시작하며 중복 시작은 막는다. 이전 허용값을 복원하지 않고 기존 연속 주차 조건을 다시 적용한다. 이 창 표시 보완은 Pleos 공식 예제 자체와 구분하며, 적용 환경·복귀 검증·남은 범위는 [#51 실행 근거](06_VERIFICATION.md#51-back창입력-후속-본인-검증--2026-11-07)를 따른다. 기록 서비스·백그라운드 수집을 새로 활성화하는 변경이 아니다.

경로 재선택·재탐색마다 새 `RouteId`를 발급한다. 같은 활성 경로와 예상 출처에 연결할 수 있는 도착 콜백만 정산에 전달한다. 정차·P·거리 0·조회 성공만으로 도착을 만들지 않는다. SDK 거리 단위나 경로 연결 근거가 없으면 예상 거리를 `Missing`으로 유지하고, 도착을 확인할 수 없는 의뢰는 보상을 확정하지 않는다. 최종 정산의 중복 방지는 기존 거래 요청 ID와 단일 원장 경계를 따른다. [#13 실행 근거](https://github.com/Yaho03/drive-growth/issues/13)

#34·#35의 기존 거래·콘텐츠 타입, 저장 형식과 식별값은 유지한다. 실제 어댑터와 화면 연결은 #36·#51에서 검증하고, #62의 완료된 기록은 기존 거래·콘텐츠 소비 경계의 과거 검증 근거다. 새 변경은 본인 검증·PR 검토와 #28 통합 검증에서 추적한다. 플랫폼 지원과 정책 확인은 #13·#24에서 추적한다.

#### #36 차량 입력 구현 경계

`app/vehicle/VehicleInput`은 속도·기어·UX를 신호별로 읽어 `VehicleObservation`을 전달한다. 한 신호의 조회 실패가 다른 신호를 지우지 않는다. `SecurityException`은 `PERMISSION_DENIED`, `IllegalArgumentException`은 `UNSUPPORTED`, 그 밖의 실행 오류와 서비스 미연결은 `NO_DATA`다.

`core/driving/VehicleInteractionGate`가 `Observation<VehicleInteraction>`을 정한다. 표본이 없거나 멈추면 `Missing`, 신호 하나라도 누락되면 원인을 담은 `Missing`이다. 차량 출처가 아닌 표본은 `Missing(NO_DATA)`이며 모의 입력은 게임 조작을 열지 않는다. 그 외에는 연속 주차 조건을 채울 때만 `ALLOWED`, 나머지는 `RESTRICTED`다. 역순·중복 표본은 버린다. 판정 기준은 `InteractionPolicy`로 호출자가 전달하며, 앱의 `TRIAL_INTERACTION_POLICY`(정차 0.1 m/s, 연속 주차 3초, 신호 유효 2.5초, 표본 간격 2.5초)는 기존 어댑터 값을 옮긴 시험값이다.

2026-11-07 #105는 검증 C에서 확인한 기존 main의 UI-thread 동기 IPC 취약점을 수정한다. `VehicleInputQueue`는 프로세스 공통 `HandlerThread` 하나에서 앱이 호출하는 Car 생성·연결·manager 획득·속성/UX 조회·해제를 직렬화한다. main은 1초 예약, 연결 상태/세대 변경, 결과 전달과 기존 화면 watchdog을 담당한다. 연결 콜백을 worker에 매번 적재하지 않고 source의 최신 상태만 보관한다. 앱 Context의 display Context를 사용해 Activity 수명과 UXR의 display 연결을 분리한다.

진행 중 작업은 하나이며 busy tick과 반복 start/stop은 추가 작업을 적재하지 않는다. stop/교체는 수신 callback을 즉시 해제하고 세대를 무효화한다. 실행 전과 이전 source 해제 뒤 open 직전에 폐기 세션을 검사한다. idle stop은 cleanup 하나를 예약하며, 이미 막힌 호출이 반환되면 이전 자원을 정리한 뒤 최신 세션만 시작한다. close가 실패하면 이전 reader를 보존해 새 연결을 막고 소유자가 없어도 operation·예외 종류를 기록한다. Binder를 interrupt/timeout으로 실제 종료했다고 가정하거나 대체 thread를 계속 만들지 않는다. worker는 프로세스 수명에 하나 유지하며, IPC가 영구 정체되면 해제·재연결도 기다리지만 화면 조작과 제한 판정은 기다리지 않는다.

표본은 SDK 조회 완료의 elapsed/UTC 수신 시각을 그대로 보존하고, 요청 시작 elapsed는 main 전달까지 기존 2.5초 이내인지 판단하는 deadline에만 사용한다. 요청 시작 시각으로 수신 시각을 덮으면 첫 조회가 느리고 다음 조회가 빠를 때 연속 주차를 실제 수신보다 일찍 누적하므로 덮지 않는다. gate는 수신 이후의 기존 유효기간·연속 주차 기준을 그대로 적용한다. 연결 revision 변경·늦은 완료·stop 뒤·이전 세션 결과는 전달하지 않는다. SDK 미응답 중 busy는 새 정상값이나 NO_DATA로 반복 치환하지 않고 기존 gate/watchdog이 만료를 제한한다. 알려진 연결 변경·오류·권한 거부·미지원은 기존 Missing 규칙을 유지한다. 주차3초·속도0.1m/s·UX·연속 표본 조건은 바꾸지 않는다. 이는 앱 요청/응답의 나이 제한이며 SDK가 오래된 센서 AVAILABLE 값을 반복 반환하는 문제나 센서 시각의 신뢰성을 해결한 것이 아니다.

공식 [getProperty 참조](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager#getProperty(java.lang.Class%3CE%3E,%20int,%20int))와 [Android14 Car 연결/해제 소스](https://android.googlesource.com/platform/packages/services/Car/+/f9a252f4a3b621daabd330531fce555300a1d4c0/car-lib/src/android/car/Car.java)는 동기 호출·main 연결 callback을 구분한다. SDK 내부 main 콜백의 `Car.mLock` 경합까지 제거하거나 OEM 구현을 바꾼다는 보장은 아니다. 기존 관측 공백 로그는 main 예약 공백을 측정하며, worker 응답 정체의 안전 판정은 gate의 표본 만료를 따른다. 실제 SDK와 제어된 지연 시험, 복구·남은 범위는 [#105 실행 근거](06_VERIFICATION.md#105-차량-동기-조회-멈춤-수정--2026-11-07)를 따른다.

`VehicleObservation.toSample()`은 기존 `DriveEngine` 입력으로 변환한다. 누락이 있으면 무효 표본이다. `io.drivegrowth.core.InputSource`는 공통 계약 `InputSource`의 별칭으로 바꿔 저장값(`VEHICLE`, `SIMULATION`)을 유지했다.

`ObservationCoverage`는 관측 공백을 앱 숨김, 기기 절전(`elapsedRealtime`와 `uptimeMillis`의 차이), 수신 중단으로 나눠 `DriveGrowthVehicle` 로그에 남긴다. 수신 중단에는 메인 스레드 지연도 포함된다. 프로세스 재시작은 `firstInProcess=true`로 구분하며 이전 구간의 원인은 추정하지 않는다. 로그에 속도 값은 남기지 않는다.

이 구현은 앱이 전면에 있는 동안의 관측까지다. 주행 중 백그라운드 기록은 #81의 근거 없이 활성화하지 않는다. `RecordingService`는 시작하지 않으며 게임 수익 계산은 #39에서 다룬다.

#### #46 개체 정보 v1과 공통 소비 예제

2026-11-07, #46 공통 계약 조율 댓글과 최신 PRD의 개별 희귀도·재료 진행량·실제 취득액 결정을 코드 계약에 반영한다. 제품 규칙 추가가 아니라 기존 결정의 타입화다. `Rarity`는 `core.progression`에서 `core.contract`로 이동하며 enum은 하나만 둔다. 기존 소비자는 공통 위치를 import한다. 호환 alias는 두지 않는다. 콘텐츠의 차종 희귀도 열과 `VehicleGameData.rarity`는 PR #115에서 제거했다. 개체 희귀도는 가챠 추첨·첫 지급 등 취득 시점에 정해진다.

| 필드·명령 | 단위와 유효 범위·보존 조건 |
|---|---|
| `OwnedVehicle.rarity` | 개별 차량의 `COMMON/ADVANCED/RARE/LEGEND`. 동일 차종에 서로 다른 값 가능. 차종 정의·랭킹 평가액과 별개 |
| `level`, `levelProgressPercent` | 레벨1..20, 진행량은 정수 퍼센트포인트0..99이며 100은 한 레벨. 레벨20은 진행량0만 허용. #48 계산이 이월·상한 초과분 버림을 끝낸 값을 전달하며 공통 저장소가 자동 정규화하지 않음 |
| `acquisitionCost` | 실제 취득 기준액인 `GameMoney` 최소 단위 Long 0..Long.MAX_VALUE. 무료·첫 지급은 실제0, 할인 티켓은 실제 할인 지출액. 차종 기준가·강화/수리비·랭킹 평가액이 아님 |
| `acquisitionSource` | `INITIAL_GRANT`, `GACHA`, `PURCHASE_DELIVERY`만 정의. 첫 지급은 COMMON·취득액0. GACHA는 유료·할인·무료 티켓을 모두 포함하며 실제 금액으로 구분 |
| `VehicleWrite.Add(vehicle)` | 새 필드는 전부 필수이며 기본값 없음. revision0·OWNED만 새 개체로 수락. 선불 티켓일 수 있어 취득액을 이번 `MoneyChange.amount`로 자동 계산하지 않음 |
| `VehicleWrite.Update` | 기대revision·새레벨·새진행량·lifecycle를 명시. 희귀도·취득액·취득출처는 명령에 없어 이전 값을 보존. 처분(SOLD/SCRAPPED/CONSUMED)에서는 기존 레벨·진행량도 보존하며 함께 바꾸면 INVALID_INPUT |
| 실패·동일 요청 | 잘못된 개체/명령 생성값은 기존 Kotlin `require`의 IllegalArgumentException으로 거절. 저장소의 처분/추가 조건 위반은 INVALID_INPUT, 오래된 revision·다른 명령의 requestId 재사용은 STATE_CONFLICT |
| 확정 결과 | 같은 요청은 현재 차량이 아니라 당시 `Settlement.changedVehicle`을 반환. 새 개체 필드 전부와 Update 진행량도 요청 동일성에 포함 |

취득 정보 누락을 무료 획득의0으로 바꾸지 않는다. Kotlin 생성자에 누락 기본값이 없고, SQLite JSON도 새 필드를 필수 읽기한다. enum·레벨·진행량·금액의 잘못된 저장값은 기존 `STORAGE_FAILURE`/내부 `INVALID_RECORD` 경로다. 차량 JSON의 level·revision·levelProgressPercent·acquisitionCost는 writer가 쓰는 JSON 정수(Int/Long)만 허용하며, 범위 검사 후 Int로 좁힌다. 문자열 숫자·실수(1.0 포함)·지수 표기·Long 범위 초과를 절삭/반올림/기본값으로 복구하지 않는다. 같은 decoder를 쓰는 정산의 차량 snapshot에도 적용한다. `GameChange`는 여전히 재화와 차량 한 대 변경이며 활동·점유 검사나 다중 차량 강화를 구현한 것이 아니다.

C 담당은 `RentalContractFixtures.vehicleExamples()`에서 revision0인 같은 차종의 첫 지급(COMMON/1레벨/0%), 할인 가챠(RARE/4레벨/50%/250), 유료 가챠(ADVANCED/2레벨/25%/500), 무료 가챠(LEGEND/0), 구매 탁송(800) 입력을 얻는다. 금액은 시험 입력이며 최종 밸런스가 아니다. 반환 목록은 외부 변경이 금지되고 각 개체도 불변 값이다.

```kotlin
val vehicle = RentalContractFixtures.paidGachaVehicle // revision0, 실제 취득액500
val store: GameChangeStore = InMemoryGameChangeStore(emptyList())
val add = GameChange(RequestId("example-add"), TransactionKind.GARAGE,
    InputSource.SIMULATION, RentalContractFixtures.ruleVersion,
    vehicle = VehicleWrite.Add(vehicle))
val issued = store.commitGameChange(add)
val growth = add.copy(requestId = RequestId("example-growth"), vehicle =
    VehicleWrite.Update(vehicle.ownedVehicleId, Revision(0), 3, VehicleLifecycle.OWNED, 75))
val grown = store.commitGameChange(growth) // 취득액500과 희귀도·출처 보존
val original = store.commitGameChange(add) // 성장 전 issued와 같은 정산
val vehicles = store.listOwnedVehicles() // Garage.owned/find의 입력
```

`OwnedVehicleContractTest`는 위 호출을 실제 실행하며 생성→조회→성장→처분→원래 정산 재생, 필드 하나만 다른 같은 요청의 충돌과 무료0·잘못된 값 거절을 확인한다. 실행은 `./gradlew :core:test --tests '*OwnedVehicleContractTest'`다. 도감 입력은 검증된 획득 이력에서 `CollectionKey(it.vehicleId, it.rarity)`를 만들어 공급한다. 현재 `listOwnedVehicles()`만으로 처분 후 영구 보유 이력을 만들지 않는다.

#### #34 저장 확장의 연결점과 기존 데이터 조건

**PR #104 당시 범위**다. 현재 main은 아래 B 영업 원장의 schema2까지 제공한다. PR #116의 schema3·이력 조회는 검토 대기이며, 새 필드가 없는 옛 JSON의 복구 정책과는 다르다.

이번 SQLite 대응은 새 필드를 손실시키지 않기 위한 `LedgerJson.vehicle` 양방향, Update 명령 JSON·copy, 처분 성장값 보존 조건에 한정했다. 테이블·DB 이름·스키마 버전1·원자성·revision 책임은 바꾸지 않았다. 코드 반영·컴파일과 실제 저장/재실행 검증을 구분한다.

| 기존 코드 위치 | 필요한 입력·출력과 후속 검증 |
|---|---|
| `LedgerJson.vehicle` / `vehicles.payload` | rarity, levelProgressPercent, acquisitionCost, acquisitionSource 필수. 비기본 값 Add→재개방→보유 조회가 같아야 함 |
| `LedgerJson.command` / `settlements.command` | Add의 전체 개체·Update 진행량 포함. 각 필드 하나만 다른 같은 requestId는 STATE_CONFLICT, 원장 추가 변경 없음 |
| `LedgerJson.settlement` / `settlements.result` | 당시 차량 snapshot 보존. 성장·처분 후 DB 재개방 및 과거 Add/Update 재시도도 당시 snapshot과 정산ID·잔액 반환 |
| `listOwnedVehicles` / `findVehicle` | 현재 보유와 처분 포함 이력은 다른 조회. #34는 모든 lifecycle의 개체 또는 영구 `(vehicleId, rarity)` 획득 키를 반환하는 조회를 연결해야 함. 처분 후에도 도감의 과거 보유 칸 유지 검증 |
| 발견 기록: main 미제공, PR #116 작성·수정 대기 | 입력 `(vehicleId, rarity)`를 중복 없이 저장하고 재개방 뒤 같은 발견 키 집합 반환. 콘텐츠 참조 검증·출처별 원장 경계와 실패를 보존하며 `Garage.collection`의 discovered로 공급. 새 테이블/서명은 저장 담당과 후속 확정 |
| 기존 schema1 JSON | 새 필드 누락 시 기본값/자동 삭제 없이 실패. 처분 개체 한 건의 누락도 현재 조회가 모든 payload를 먼저 읽으므로 전체 보유 조회를 실패시킬 수 있음. 미확인 정산의 차량 snapshot 누락도 전체 정산 조회 실패 |
| 과거 명령 재시도 | 옛 차량 Add/Update 명령은 새 명령과 JSON이 달라 result 읽기 전 STATE_CONFLICT. 차량 없는 과거 금액 명령은 동일 표현의 재생 경로 유지. 이 차이를 기기에서 검증해야 함 |

이미 운영한 schema1 데이터가 새 필드를 가진 것으로 간주하지 않는다. 같은 DB 버전 안에서 payload 의미가 확장되므로 저장 담당이 실제 구버전 자료와 호환 전략을 검증해야 한다. 근거 있는 취득 기록 없이 COMMON·0·현재 차종가로 복구하거나, 새 DB/기존 데이터 삭제로 통과시키지 않는다. 확정 결과의 과거 값도 현재 개체로 재구성하지 않는다. 자동 이전은 이번에 제공하지 않는다.

#48 후속 거래는 대상ID/revision·재료ID/revision·활동·차종/희귀도·규칙 버전에서 계산한 새 레벨/진행량과 필요한 재화 변경을 입력으로 받아야 한다. 한 정산에서 대상 성장 snapshot과 재료 CONSUMED snapshot을 함께 확정하고 취득 정보는 각각 보존해야 한다. 같은 개체 중복·활동 제한·revision 충돌·금액 부족이면 전부 미반영이어야 한다. 현재 단일 `GameChange` 두 번 호출로 이를 흉내 내지 않는다. 다중 개체 명령·결과 구조 자체는 #48·#34의 후속 연결이며 이번에는 구현하지 않는다. 가챠 티켓/보장 이력·초기 지급 이력·사고/활동·도감 영속 입력도 각 기존 담당의 범위다. 기존 `VehicleCare.levelAfterMaterial`의 +3레벨 시험 로직은 아래 #48 절에서 희귀도별 진행량 계산으로 교체했다.

#### #48 레벨업과 재료 강화

`io.drivegrowth.core.garage.VehicleUpgrade`가 `Garage.find`로 대상·재료를 찾고 `VehicleRef`(개체 ID·화면에서 본 revision)와 호출자가 넘긴 활동 상태(`(OwnedVehicleId) -> ContractResult<VehicleActivity>`, `Garage.availableForRental`과 같은 형태)로 검사한다. 계산은 `VehicleCare`의 PRD 시험값이다: 레벨업 비용 = 차종 가격 × 2% × 현재 레벨 × 희귀도 비용 배율(1.0·1.05·1.1·1.15, 중간 곱은 BigInteger로 계산하고 마지막에 한 번 내림), 재료 진행량 = 재료 희귀도별 100·150·250·400%(이월, 20레벨 초과분 버림).

| 동작 | 저장 | 실패 |
|---|---|---|
| `planLevelUp(owned, requestId, source, VehicleRef, activityOf)` | 비용 DEBIT + `VehicleWrite.Update`(레벨+1, 진행량 유지, 20이면 0)를 담은 불변 `GameChange` 계획을 반환하고, 호출자가 `commitGameChange`로 저장. **응답을 잃으면 보관한 같은 계획을 다시 제출**해 처음 정산을 재생한다(같은 requestId·다른 내용은 `STATE_CONFLICT`). 성공 뒤 계획을 새로 만들면 revision이 달라 새 요청으로 거절 | 없음·처분 `DATA_UNAVAILABLE`, revision 불일치·대여/반납/수리 필요 `STATE_CONFLICT`, 20레벨·최종 비용 Long 초과 `INVALID_INPUT`, 활동 조회 실패는 받은 오류 그대로, 잔액 부족·저장 실패는 저장소가 전체 거절 |
| `planMaterial(owned, target, material, activityOf)` | **계산·검사만.** 대상 성장 Update와 재료 CONSUMED Update(성장값 보존) 두 개를 `MaterialPlan`으로 반환하고 저장하지 않음 | 같은 개체·다른 차종·20레벨 대상 `INVALID_INPUT`, 처분된 재료 `DATA_UNAVAILABLE`, revision 불일치·사용 중 `STATE_CONFLICT`, 대상·재료 활동 조회 실패는 받은 오류 그대로. 재료는 20레벨이어도 사용 가능 |
| `prepareMaterial(store, requestId, source, target, material)` | 저장 측 `readMaterialState`의 두 개체·활동·버전으로 `planMaterial`을 실행해 비용0(PRD에 재료 강화 비용 없음) `MaterialCommand`를 만든다. 저장하지 않는다. 호출자가 `store.commitMaterial(command)`로 대상 성장·재료 CONSUMED를 한 정산에 확정하고, **응답을 잃으면 보관한 같은 command를 다시 commitMaterial**해 처음 정산을 재생한다 | 같은 개체 `INVALID_INPUT`, 저장 측 콘텐츠·규칙 버전이 앱과 다르면 `STATE_CONFLICT`, 상태·활동 조회 실패·활동 누락은 받은 오류 그대로(IDLE로 보정 안 함), 나머지는 `planMaterial`과 같다. 저장 실패는 저장소가 두 개체·잔액 모두 미반영 |

재료 강화는 #121 `MaterialChangeStore`(PR #147)로 두 개체를 한 정산에 확정한다. 메모리 참조 저장소에서 확정·재제출 재생·저장 실패 무변경을 시험했다. 실제 SQLite 저장·live 활동·DB 재개방·제품 화면 연결은 #122·#52의 후속이다. 희귀도 수익 배율(1.0~1.3)과 대여료 반영은 영업 계산(B) 범위라 이번에 바꾸지 않았다.

#### #47 가챠 1회 지급

`io.drivegrowth.core.garage.GachaPull.prepare(store, requestId, ticket)`는 `readGachaState()`의 권위 상태와 이 콘텐츠를 대조한 뒤(콘텐츠·규칙 버전, 천장·보장 희귀도, 양수 가중치 희귀도, 가게 레벨의 해금 차종 집합이 모두 같아야 함) 희귀도 추첨(30회 보장) → 해금 차종 균등 선택으로 추첨을 고정한 `GachaCommand`를 만든다. 가게 레벨·칸 수·카운터·가챠 revision·뽑기권 가격은 저장 측 `GachaAuthority`/`GachaProgress` 값을 쓰고 호출자 입력이나 콘텐츠 `price_unit`을 쓰지 않는다. 새 개체는 `gacha-<requestId>`, GACHA·1레벨·0%·실제 지불액(유료 `purchasePrice`, 무료 0)이다. 추첨 seed는 requestId에서 정한다.

저장은 호출자가 `store.commitGacha(command)`로 한다(#126 `GachaChangeStore`). 차감·새 개체·다음 카운터·무료권 −1이 한 번에 확정되고, 결과는 저장소가 돌려준 `GachaSettlement`(최초 명령·당시 정산·진행 상태)만 사용한다. **응답을 잃으면 보관한 같은 command를 다시 commitGacha**한다. 저장소가 현재 공간·조회 상태보다 먼저 최초 결과를 재생한다.

| 경우 | 처리 |
|---|---|
| 권위 상태 없음 / 해금 차종 없음 | `DATA_UNAVAILABLE` (계획 단계) |
| 저장 측 규칙·해금이 이 콘텐츠와 다름 | `STATE_CONFLICT` (계획 단계) |
| 차고 칸 부족 / 무료권 0 / 손상 개체 | `CAPACITY_EXCEEDED` / `INSUFFICIENT_FUNDS` / `INVALID_INPUT` (계획 단계, 저장소도 확정 때 다시 검사) |
| 같은 requestId·다른 입력(카운터·티켓·버전·결과) | 저장소 `STATE_CONFLICT`, 무변경 |
| 같은 상태로 만든 두 요청의 마지막 칸 경쟁 | 저장소가 한 건만 확정(다른 건은 가챠 revision 충돌) |
| 잔액 부족·저장 실패 | 저장소가 전체 미반영 |

메모리 참조 구현(`InMemoryGameChangeStore`)으로 검증했다. 실제 SQLite 원자 확정은 경민 PR #136(#122), 무료권 지급·제품 초기 권위 상태·앱 재실행은 후속이다.

#### #49 수리·판매·폐차 금액 계산

`io.drivegrowth.core.progression.VehicleCare`의 금액 계산은 PRD 「수리」·「판매」·「폐차」 시험값을 따른다. 모두 BigInteger로 계산해 마지막에 한 번 내리고(레벨 가산·사고 감액 비율도 BigInteger), 최종값이 Long을 넘으면 ArithmeticException이다. 내림은 기존 `VehicleCare`의 잠정 정책을 유지한 것이며 최종 반올림 합의가 아니다.

| 함수 | 산식 | 기준값 |
|---|---|---|
| `repairCost(modelPrice, rarity)` | 차종 가격 × 5% × 희귀도 비용 배율(1.0·1.05·1.1·1.15) | 콘텐츠 차종 가격 |
| `salePrice(vehicle, accidents)` | 실제 취득액 × (max(60% − 사고당 5%p, 30%) + 레벨 1 초과분 레벨당 1%p). 사고 3회 이상은 거절 | `OwnedVehicle.acquisitionCost`(무료권·첫 지급 0). 차종 가격·희귀도는 쓰지 않음 |
| `scrapValue(vehicle)` | 실제 취득액 × 20% | 위와 같음 |

예: 구입 뽑기권 500 Lv1 판매 300, 같은 차 Lv20 사고 없음 395(79%), 무료권·첫 지급 0. 사고 횟수는 호출자가 넘기는 입력이며, 차량별 정산 완료 사고 횟수는 #116의 `CompletedRentalSource.accidentCount(ownedVehicleId)`로 읽어 넘긴다(아래 `VehicleDisposal`이 연결). 수리 명령은 아래 `VehicleRepair`가 만든다.

`io.drivegrowth.core.garage.VehicleDisposal`의 `planSale(owned, requestId, source, VehicleRef, accidents, activityOf)`·`planScrap(...)`은 처분 금액 CREDIT(0이면 금액 변경 없음)과 `VehicleWrite.Update`(SOLD/SCRAPPED, 레벨·진행량·취득 정보 보존)를 담은 불변 `GameChange` 계획을 만든다. 호출자가 `commitGameChange`로 저장하고, 응답을 잃으면 같은 계획을 재제출해 처음 정산을 재생한다. 판매의 사고 횟수는 #116 `CompletedRentalSource.accidentCount(ownedVehicleId)`에서 읽으며 조회 실패·음수는 그대로/`INVALID_INPUT`, 사고 3회 이상 판매는 `INVALID_INPUT`(폐차만 가능)이다. 대여·반납 중과 revision 불일치는 `STATE_CONFLICT`, 수리 필요는 판매·폐차 가능, 처분된 개체는 `DATA_UNAVAILABLE`이다. 동시 대여와의 최종 점유 경쟁은 저장소(#107 SQLite)가 확정 때 검사한다.

`io.drivegrowth.core.garage.VehicleRepair.prepare(store, requestId, source, VehicleRef)`는 #121 `RepairChangeStore.readRepairState`(PR #147)의 개체·활동·확정 사고 누계·잔액·견적을 읽는다. 저장 측 콘텐츠·규칙 버전과 견적이 이 앱의 `VehicleCare.repairCost(차종 가격, 개체 희귀도)`와 같을 때만 `RepairCommand`를 만든다. 다르면 `STATE_CONFLICT`, 콘텐츠에 없는 차종은 `DATA_UNAVAILABLE`, 조회 실패는 받은 오류 그대로다. 화면에서 본 revision·수리 필요 상태·잔액은 공통 `prepareRepairChange`로 미리 검사한다(수리 필요가 아니거나 옛 revision이면 `STATE_CONFLICT`, 잔액 부족은 `INSUFFICIENT_FUNDS`). 저장하지 않는다. 호출자가 `store.commitRepair(command)`로 비용 차감·NEEDS_REPAIR→IDLE·revision+1을 한 정산에 확정한다. 응답을 잃으면 보관한 같은 command를 다시 제출한다. 사고 누계·성장·취득 정보·영업 쉬기는 바뀌지 않는다. `VehicleRepairTest`는 #155의 메모리 참조 `InMemoryGameChangeStore.commitRepair`로 이 명령의 실제 확정(차감 440·IDLE 회복·revision+1·사고 누계 보존), 같은 명령 재제출의 최초 정산 재생, 저장 실패 시 잔액·수리 필요·revision 무변경을 확인한다. 거절 경로는 읽기 전용 시험 저장소로 검증한다. **SQLite `commitRepair`·같은 DB 활동/확정 사고·DB 재개방은 #162로 부분 공급됐다.** 실제 제품 콘텐츠/명령·UI/프로세스 연결은 후속이며 아래 「#122 수리 SQLite 확정 경계」와 실행 근거를 따른다.

### 거래 원장 영속 저장

#34의 첫 영속 어댑터는 앱 내부의 SQLite 데이터베이스 하나를 `SQLiteOpenHelper`로 관리한다. 초기 스키마는 v1이다. Android 기본 API로 잔액·보유 차량·거래 결과를 한 트랜잭션에서 확정하고, 요청 ID의 유일성을 DB에서 강제한다. 기존 `SnapshotStore`는 출처별 주행 기록과 경험치를 한 파일로 저장한다. `AtomicFile`의 파일 교체는 해당 용도에 적합하며, 거래 원장에 그대로 적용하면 중복 키·동시 갱신 규칙을 직접 구현해야 한다. 기존 파일은 유지하고 과거 경험치를 게임머니로 변환하지 않는다. [SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase), [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper), [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)

- **저장 경계**. 잔액, 개체 ID별 보유 차량 상태와 revision, 요청 ID별 정규화된 명령과 확정 `Settlement`를 같은 DB에 둔다. SQLite 스키마 버전은 저장 구조를, `RuleVersion`은 거래 계산 규칙을 식별한다. 모의 실행은 별도 시험 저장소를 사용한다.
- **쓰기 순서**. UI와 백그라운드 호출은 같은 저장소 진입점을 사용한다. DB 작업은 메인 스레드 밖에서 실행하며 한 트랜잭션 안에서 기존 요청 확인 → 현재 상태와 기대 revision 검증 → 잔액·개체·확정 결과 저장 순으로 처리한다. 트랜잭션 종료까지 성공한 뒤 결과를 반환한다. 같은 요청 ID에 같은 명령이면 당시 확정 결과를 돌려준다. 같은 ID에 다른 종류·출처·규칙 버전·금액·개체 변경을 보내거나 새 요청의 revision이 오래됐으면 충돌로 처리한다. 범위 초과와 잔액 부족은 저장 전에 거부한다.
- **실패와 복원**. I/O 실패·손상·미지원 스키마 버전은 `ContractError.STORAGE_FAILURE`로 반환하고 내부 진단에서 원인을 구분한다. 손상 시 삭제하는 기본 복구 동작을 사용하지 않도록 `DatabaseErrorHandler`를 지정한다. 열린 DB를 자동 초기화하거나 낮은 버전으로 덮어쓰지 않는다. 저장 성공 여부를 받기 전에 프로세스가 끝났으면 재실행 후 같은 요청 ID로 조회·재시도한다. 확정 전 상태 또는 이미 확정된 정산 중 하나가 관측되어야 한다. 정산을 열어 보는 행동과 열람 상태 기록은 재화 변경과 분리한다. 앞으로 지원하는 스키마 이전은 실제 구버전 데이터로 트랜잭션 안에서 검증한다. [DefaultDatabaseErrorHandler](https://developer.android.com/reference/android/database/DefaultDatabaseErrorHandler)
- **#34 완료 근거**. 같은 요청을 반복하고 DB를 닫았다 다시 열어도 잔액과 정산 ID가 유지되는지, 같은 ID에 다른 내용과 동일 revision의 동시 갱신이 충돌하는지, 커밋 전 실패와 응답 유실 후 재시도에서 중복 지급이 없는지, 미지원 버전과 손상에서 원본이 보존되는지 확인한다. 메모리·디스크의 기능별 결과는 #34 본인 검증과 연결 PR에 남기고, 콘텐츠·화면을 통한 기능 간 연결은 #28에서 확인한다. 완료된 #62의 과거 결과는 당시 버전의 근거로 보존한다.


#### #34 SQLite v1 어댑터 인계

`app/data/SqliteGameChangeStore`는 `GameChangeStore`를 구현한다. 생성 시 `InputSource`를 지정하고 기본 DB 이름과 계정의 출처 필드로 실제·모의 원장을 분리한다. 같은 DB를 다른 출처로 열거나 다른 출처 요청을 쓰는 것은 거절한다. 앱 조립에서는 단일 인스턴스를 공유하고 메인 스레드 밖에서 호출한다. 기존 `SnapshotStore`·경험치 파일과 게임 원장은 연결하거나 변환하지 않는다.

계정 잔액, 개체 JSON, 요청의 전체 명령 JSON과 당시 확정 결과 JSON을 SQLite 트랜잭션 하나에 저장한다. `request_id`와 `settlement_id`는 DB의 유일 키다. 여러 연결의 쓰기도 SQLite 트랜잭션 안에서 요청/revision을 다시 확인한다. 읽기 전용 결과 조회 `listUnviewedSettlements()`와 반복 가능한 `markViewed(settlementId)`는 이 어댑터가 제공하며 지급을 수행하지 않는다. PR #118에서 D의 실제 정산 화면에 연결했다. 전체 영업 history·세부 수익 조회는 그 인계에 포함되지 않는다.

v1은 최초 스키마로 실제 이전 대상 버전이 없다. 버전 0의 알 수 없는 테이블, 이후 버전, 파일 손상은 원본을 삭제·초기화하지 않고 `STORAGE_FAILURE`로 거절한다. 자동 백업·자동 복구·자동 버전 이전은 제공하지 않는다. 복구는 모든 연결을 닫은 뒤 원본과 남은 SQLite 부속 파일을 별도로 보존하고 지원 버전과 무결성을 조사하는 수동 절차로 남긴다. 새 게임으로 덮어쓰거나 과거 규칙 버전을 현재 규칙으로 바꾸지 않는다. `ContentVersion`은 콘텐츠 카탈로그의 버전이며 v0 거래 명령에는 포함되지 않는다.

최초 구현은 v0 계약의 재화·차량 한 대 변경·확정 정산·열람 상태까지다. 개체 v1 필드의 후속 최소 대응과 기존 payload 조건은 위 「#34 저장 확장의 연결점과 기존 데이터 조건」을 따른다. 다중 차량 소비, 의뢰/대여/시간 구간, 가챠의 재추첨 방지 결과 모델은 해당 후속 계약과 구현이 필요하다. #34를 전체 게임 저장 완료로 해석하지 않는다.

### B 영업 원장 소비 계약 (#41–#44)

기존 `SqliteGameChangeStore`의 `rental`에서 같은 출처·DB의 영업 상태를 읽고 쓴다. SQLite 스키마 2는 v1에 방문·대여·영업 쉬기·가게·시설 표를 추가한다. 기존 원장·개체 JSON은 유지하며 누락된 개체 필드를 복구하지 않는다. 실제 금액 단위, 반올림, 시설 가격과 고객 분포는 호출 측의 버전 있는 규칙 입력이다.

- 배정은 `assign`에 고정 방문 ID·희망 차종·기간·카탈로그·차종별 유형/시간당 요금·가상 고객/사고 추첨값을 넘긴다. 같은 SQLite 쓰기 안에서 최신 개체와 활동을 조회하고 후보 revision을 올려 점유한다. 희망 차종, 같은 유형 순으로 고르고 같은 궁합에서는 높은 레벨, 개체 ID 순서다. 결과가 비어도 방문을 기록한다. 같은 ID·입력의 재전송은 당시 결과를 돌려주며 재방문은 새 방문 ID를 사용한다.
- `pending`은 재개할 대여 ID, 상태, 마지막 반영 시각, 대여/반납 종료 시각을 돌려준다. `account`에는 검증된 연속 구간과 원래 주차 시작 시각을 전달한다. 주행 구간은 주차 시작 인자에 null을 사용한다. 관측 누락을 주행으로 보내지 않는다. 반영 커서와 가중 시간을 함께 저장하고 복귀 시 앞선 구간은 다시 더하지 않는다. 차량 입력 담당은 주차 중 프로세스 재시작에도 원래 주차 시각을 공급해야 한다.
- 대여 종료까지 반영된 뒤 `settle`로 금액, 사고, 리뷰 상승 근거와 입력받은 단골 여부를 조회한다. 미정인 단골 판정은 null이다. 정수 정책 없이 소수 지급이 필요하면 `POLICY_UNRESOLVED`로 보류하고 확정된 방법을 전달해 이어간다. 원장 입금과 반납 이동 전환은 함께 확정된다. 같은 ID의 재정산은 당시 잔액·결과를 재생한다. `finishReturn`은 반납 종료 시각이 지난 경우에 유휴 또는 수리 필요 상태로 전환한다. #49의 수리 완료·사고 이력 소비 연결은 별도다.
- `shop`, `buy`, `expand`는 가게 상태와 시설 구입·공간 확장의 거래 입구다. 시설과 확장 제안은 규칙 버전·시험값 표시·가격을 받는다. 누적 고객은 중복을 제거한 방문 ID 수로 계산한다. 일반 고객 수익과 대여당 주유소 보너스를 영수증에서 구분하며, 보험은 사고 손실의 절반을 보전한다. 실제 콘텐츠·제품 화면·차량 신호를 연결하는 단계에서는 #35·#51·#55·#28에서 같은 규칙 버전과 입력 출처를 대조한다.

### C 파트에 제공하는 영속 조회 (#34·#46·#49·#50)

- `SqliteGameChangeStore`가 구현하는 `VehicleHistoryStore.listVehicleHistory()`는 모든 개체의 마지막 상태·취득 출처·revision을 반환한다. 첫 차 지급 확인은 `acquisitionSource == INITIAL_GRANT`인 개체가 있는지 검사한다. 처음 받은 차를 처분해도 찾을 수 있다. 현재 보유 목록은 기존 `listOwnedVehicles()`로 읽는다.
- 탁송/가챠 화면에서 실제로 본 키 집합을 `recordDiscoveries(catalog, keys)`에 넘긴다. SQLite v3의 `discoveries` 표는 차종 ID와 희귀도의 조합을 한 번만 기록한다. 목록에 없는 차종은 배치 전체를 거절한다. 도감은 이력에서 얻은 `CollectionKey(vehicleId, rarity)` 집합과 `discoveredVehicles()` 결과를 `Garage.collection`에 넘긴다. 조회 실패를 빈 도감으로 대체하지 않는다. v1과 v2는 기존 행을 보존해 이전하며 과거에 보았는지 알 수 없는 키를 만들지 않는다.
- `SqliteGameChangeStore.rental`은 코어의 `CompletedRentalSource`를 구현한다. #49는 `completedRental(rentalId)`에서 개체 ID, 확정 정산의 매출(시설 보너스 포함), 시설 보너스 및 사고 여부를 읽는다. #50은 `firstCompletedRental()`에서 처음 **정산이 커밋된** 대여의 같은 결과를 읽는다. 아직 결과가 없으면 Success(null), DB 오류는 Failure다. 열람·반납·재접속 후에도 최초 정산 ID를 재사용한다. 무료권 지급은 #33이 정할 명령에서 최초 정산 ID와 프로필당 1회 지급을 함께 검사해야 한다. 조회 자체는 보상을 지급하지 않는다.
- #49의 판매 감액·폐차 조건은 `CompletedRentalSource.accidentCount(ownedVehicleId)`로 조회한다. 같은 차종도 보유 개체 ID별로 분리하며, 사고가 발생하고 정산까지 커밋된 대여 1건을 1회로 센다. 수리·열람·처분 후에도 누적값을 유지한다. 알려진 무사고 차량은 0, 없는 개체는 DATA_UNAVAILABLE다. 처분을 확정할 때는 조회 후 상태가 바뀔 수 있으므로 해당 명령의 revision/활동 검사도 수행한다.

## 전체 기능의 공통 계약과 저장 선행 작업

2026-11-07 사용자 요청으로 main `b6631a4`, PR #111 `4f710e4`·#116 `d841f84`, #35~#58 및 #24·#25·#28·#80~#82를 정적으로 대조했다. 아래는 기존 구현을 유지하면서 필요한 **후속 구현 명세**다. 제공 상태 표는 2026-11-10 main 공급·#47~#50 core/저장 완료를 반영하며 과거 조사 시점과 구분한다. 함수명이 아닌 논리 입출력으로 기술한 항목은 아직 호출 가능한 API가 아니다. 제품 규칙은 PRD, 제안의 확정은 #24, 실행 결과는 06에서 관리한다. 닫힌 #27·#33·#34의 당시 인계와 #104·#107·#112~#115·#117~#119의 반영을 취소하지 않는다.

### 제공 상태와 작업 책임

| 구간 | 현재 제공 | 남은 공급과 책임 |
|---|---|---|
| 공통 타입·단일 거래 | `core.contract`의 Rarity·OwnedVehicle 필수 필드, GameChange/Settlement·공통 오류, 메모리 fixture와 SQLite 단일 개체 명령 | [계약 후속 121](https://github.com/Yaho03/drive-growth/issues/121): 환석이 기능 입력/결과·다중 개체·프로필·진행 상태의 공통 타입과 소비 fixture를 확장. 기존 생성자 누락을 기본값으로 숨기지 않음 |
| 공용 저장 | schema10의 공용 store·기존 Rental/Gacha/Delivery/Driving/Material/Repair 원장, Initialization/FirstGift·typed checkpoint. #169 초기화/선물·#173 같은 factory 소비와 #175 완료 고객 집계 공급 | [저장 후속 122](https://github.com/Yaho03/drive-growth/issues/122): 경민이 합의된 명령과 기능 Ledger를 같은 DB에 연결하고 최종 조건 검사·요청 이력·이전·조회 어댑터를 담당. A/C의 계산 구현과 중복하지 않음 |
| PR #111 | main `094ee59` 반영. #126의 전체 GachaCommand를 보존·재제출하고 저장소의 최초 전체 결과를 소비. 동일 ID·다른 입력과 마지막 칸 경쟁의 이전 두 P1 해결 | 실제 SQLite #136·공용 금액 #139와 콘텐츠→SQLite 시험 #145는 main 제공. #169 무료권/최초 선물 저장과 #173 제품 가챠 소비/확정 뒤 재실행까지 공급해 #47 core·저장은 완료. 남은 화면 조건은 #52 |
| PR #116 | main `0799eb5` 반영. VehicleHistoryStore와 CompletedRentalSource의 이력/발견·완료 영업/사고 조회 제공. 입력 snapshot·정산 연결·원시 값의 세 지적 해결 | 이력 부재만으로 신규 프로필을 판정하지 않음. 실제 표시 발견 이벤트는 #37/#46/#53 후속. 무료권·다중 강화·초기화 core/저장은 #156/#169/#173으로 공급 |
| PR #128·#134·#137 | 목록/선택·도착/보관·기본 주행·변경/포기 Ledger main 제공. 최신 head는 #136의 엄격 공용 금액 decoder 포함 | #139 공용 연결/이전의 active 결제 검증·지원 v4 재생 두 P2는 해결되어 main 공급. 실제 SDK/제품 호스트·화면/프로세스는 후속. 세부 실행·head는 [06 최신 재검토](06_VERIFICATION.md#pr-수정본-재검토와-부분-인계--2026-11-08) 참조 |
| PR #138·#141·#140 | #138 가격 계산·#141 사고 조회 기반 판매/폐차 GameChange 계획은 main 제공. #140 도감/첫 지급 이력의 core 소비는 snapshot P2 해결 후 main 공급 | #140의 동일 사본 검사/변환은 JVM28개 통과. #49 수리/처분은 #162/#166·#173, #50 실제 NEW/초기화/첫 선물은 #169/#173으로 공급해 core·저장은 완료. 해당 UI는 #52/#56에서 추적. FirstGrant false는 NEW 판정이 아님 |

### 공통 명령·결과·실패의 확장 조건

- 명령에는 requestId, 작업 종류, 저장 출처, 필요한 프로필/대상 ID·예상 revision, 규칙/콘텐츠 버전과 **해당 기능의 전체 의미 입력**을 고정한다. 호출자가 바꿀 수 있는 목록·Set·Map도 입구에서 검증에 쓰는 snapshot으로 고정한다. 개체 Add의 revision0과 저장 이후 revision을 구분한다.
- 같은 DB의 기능 요청·공용 거래 사이 ID 재사용을 양방향 검사한다. 구매 선택과 연결된 결제는 같은 요청의 연결 기록으로 대조한다. 기능 테이블은 허용하되 별도 지갑/원장으로 우회하지 않는다. 금전 변경 없는 명령을 기록하려고 0원 거래를 만들거나 현재 GameChange 필수 조건을 풀지 않는다. 필요한 비금전 명령/결과는 계약 후속에서 명시한다.
- 저장 순서는 **기존 요청의 전체 입력 확인과 당시 결과 재생 → 새 요청의 현재 상태/권한·출처·revision·활동·잔액·공간 조건 확인 → 전체 확정**이다. 같은 ID·다른 입력은 STATE_CONFLICT·무변경, 같은 입력은 성장·처분·후보 변경·완료 후에도 최초의 전체 결과를 반환한다. 현재 잔액/개체나 새 난수로 과거 결과를 만들지 않는다.
- 결과에는 확정 ID·당시 잔액·변경한 모든 개체 snapshot·기능 상태/카운터/티켓·당시 선택/추첨 결과를 연결한다. 정산 조회는 거래 요청/정산 ID·종류·출처의 연결도 검사하며 누락·손상은 정상 null/0/false로 보정하지 않는다. 빈 정상 목록, 미지원/미확인, 읽기 실패는 구분한다.
- INVALID_INPUT(타입·범위), STATE_CONFLICT(동일성·revision·점유), INSUFFICIENT_FUNDS(재화), CAPACITY_EXCEEDED(공간), DATA_UNAVAILABLE(없는 대상/증거), POLICY_UNRESOLVED(미정 정책), UX_RESTRICTED/PERMISSION_DENIED(조작/권한), STORAGE_FAILURE(손상·저장 실패)를 기존 의미로 사용한다. 무료권 부족의 구체 오류는 계약 후속에서 정하고 fixture에 고정한다. 실패/커밋 직전 종료는 모든 변경 미반영, 응답 유실 뒤 재시도는 당시 확정 결과여야 한다.

### 기능별 최소 입출력과 확정 단위

| 소비 기능 | 후속 입력 → 출력·동일 확정 단위 | 공급 전 가능한 작업·완료 게이트 |
|---|---|---|
| #47 가챠 | 티켓 종류/실제 지불액·가게 단계/공간·보장 카운터·콘텐츠/규칙·고정 추첨 결과 → 최초 전체 결과. 재화/무료권 소비 + 카운터 갱신 + 새 개체 + 획득 이력을 한 거래로 확정. 현재 DB 공간을 다시 검사 | #111의 보관 GachaCommand 소비와 #136 SQLite 최종 조건 검사는 main 제공. 실제 권위 상태·무료권 지급·호스트/UI·제품 복원은 후속 |
| #48 강화 | 대상/재료 ID와 revision·현재 활동·규칙·비용·새 성장값 → 대상 성장 + 재료 CONSUMED snapshot 한 정산. 같은 개체·사용 중·잔액 부족·한쪽 revision 충돌은 전부 거절 | VehicleUpgrade.planLevelUp/planMaterial 유지. 단일 레벨업 재제출과 두 개체 원자 거래를 구분. 두 번 commit으로 재료 소비를 흉내 내지 않음 |
| #50 초기화·첫 영업 선물 | 출처/프로필·신규 판별 근거·고정 선택·초기 규칙 → NEW/EXISTING/UNKNOWN과 원래 지급 정산. 초기 재화/차량·공간·초기화 마커를 일관되게 확정. 첫 완료 영업 정산 ID에 연결해 선물 티켓을 1회 지급 | FirstGrant 유지, 검증된 NEW 호출 전제 공급. 빈 차고·이력 부재=NEW 금지. #116의 INITIAL_GRANT 존재는 기존 지급의 양성 근거일 뿐 부재는 신규 근거가 아님. legacy/복구 불명은 UNKNOWN·무지급, 구체 복구 정책은 #24 |
| #37 후보·선택 | 일자/거리 구간/출처·목적지/경로 세대·관측/예상 거리·콘텐츠/규칙·고정 후보/다음 번호·선택 → 저장 목록·활성 한 건·전체 선택 요청 이력/당시 결과. 구매 결제와 선택을 같은 확정으로 처리 | #112 계산 유지. 정원이 DeliveryLedger·직렬화·시험과 후보별 발견 키를 공급, D가 실제 표시된 목록 ID/버전·희귀도 있는 구매 후보 키를 전달하고 경민의 검토된 발견 저장 API로 기록한다. 미선택 후보도 발견/미획득으로 유지하며 생성만 한 미표시 후보·희귀도 없는 수익 후보에 키를 만들지 않는다. 경민이 공용 연결 책임. [#37 조율](https://github.com/Yaho03/drive-growth/issues/37#issuecomment-6705230075)의 분담·원자성·출처/요청 ID 조건 유지 |
| #38 변경·취소 | 활성 의뢰 ID/revision·원래 결제/사용한 변경 기회·실제+새 예상 거리·새 선택/차액·규칙 → 변경/포기·차액 결제/환급·기회 소비·이력. 한 요청으로 확정 | #112 선택/오류 재사용, 독립 계산 가능. 잔액 부족이면 원래 의뢰/돈/기회 유지. 최저 희귀도·취소 최소액 등 #24 제안은 확정 전 |
| #39 도착·정산·임시 보관 | 활성 의뢰/revision·현재 경로와 연결 가능한 도착 증거·출처/시각·확인된 주행 구간·기한·보관 상태 → 수익/차량 지급 또는 확인 대기. 완료 슬롯 교체도 같은 거래 | 증거 부족은 대기·미지급. P·거리0·앱 보관 routeId만으로 도착 추정 금지(#80). 구매 만차의 24시간 보관/수령과 기한 뒤5% 공제, 미도착24시간 전액 환급은 서로 다른 상태·중복 방지 필요 |
| #41~#44 영업·시설 | 방문/대여 ID·개체/revision·유형/요금·고객/사고 고정값·검증 구간/원래 주차시각·시설/확장 규칙 → 배정·정산·반납·영업 쉬기·가게/공간 snapshot | RentalLedger의 assign/account/pending/settle/finishReturn/activity/setResting/shop/buy/expand 유지. 신규 원장 대신 콘텐츠/차량 입력 어댑터 보완. 방문 건수와 완료 고객 기준을 동일시하지 않음. pending은 #167에서 원래 parkedSince의 엄격 조회를 공급하며 실제 검증clock/coverage 복원 입력은 연결부에서 보완. setResting은 현재 ID/불리언만 받으므로 UI 요청 동일성/최신 상태 대조를 연결부에서 구체화. [기존 동의된 시험값](01_PRD.md#기존-동의된-대여확장-시험값)은 버전 있는 정의로 공급한다. 일반Lv1 대여 기준을 모든 차종 요금으로 채우지 않고 확장은 확정 완료 고객 근거를 조회한다. 20종별 시간당료·고객 분포·미제공 시설 비용/효과·단골은 별도 정의/공급 대기 |
| #49 수리·처분 | 개체/revision·확정 사고 누계/활동·차종가/취득액·최신 규칙 → 수리비/회복 또는 판매/폐차금·lifecycle·당시 정산. 사고 이력 보존·영업 점유와 같은 DB에서 검사 | 기존 단일 Update를 재사용. salePrice의 레벨 가산·실제 취득액 기준과 repairCost의 희귀도 비용 배율은 위 「#49 수리·판매·폐차 금액 계산」에 반영. 수리비 차감+NEEDS_REPAIR 종료는 원자 명령 확장. #116 반영된 사고 조회를 소비하며 처분 계획과 실제 저장/수리 연결을 구분 |
| #46 도감·활동 | 처분 포함 획득/검증된 발견 키·출처/버전·활동/영업 쉬기 → Garage.collection/availableForRental의 입력 | Garage·현재 보유 조회·RentalLedger 활동은 유지. #116 API는 main 제공이며 #140의 core 소비는 snapshot 수정·재검토 후 main 공급. 제품 mapper의 DATA_UNAVAILABLE 경계는 후속 연결. 획득은 실제 지급에, 발견은 실제 표시한 탁송 후보/가챠 결과에 연결. 미선택 구매 후보도 발견으로 보존하며 발견 저장 실패를 기록 성공으로 표시하지 않음 |
| #45 퀘스트·업적·채택 이벤트 | 버전 있는 정의(ID·기간/시간대·목표·보상·조건) + 중복 제거 가능한 원인 이벤트 ID/출처/확정 ID → 진행/완료/수령/만료 snapshot. 보상·수령 마커를 한 거래로 확정 | 기본 주행/탁송/영업/강화/수집 이벤트를 재생해도 진행/지급 한 번. 정의는 #24·#35, 계산은 경민, 공통 수령 저장은 후속. 제외된 거리 목표·주말/시즌을 생성하지 않음 |
| #36·#39·#40 주행·휴식 | 같은 부팅의 관측 순서/단조 시각·UTC·출처·누락·확인된 구간/일자 + 휴식 장소/출발 증거 → 원인 구간·보상 반영 거리/상한·버프 상태/시작 이벤트/소모 커서 | 기존 차량 gate/DriveEngine 기록과 RestBonus 계산 유지. 경험치 SnapshotStore를 게임머니 원장으로 간주하지 않음. 구간 중복 제거·주행 수익/하루500km·버프 소모를 원자 저장·복원. 주행 서비스·음성 활성화는 #81 조건 충족 후 |
| #55 꾸미기 | 채택 아이템/해금/가격/배치 영역·현재 배치 revision·선택/미리보기 → 확정 배치/구매 결과 | 미리보기는 무저장, 확정 시 소유/해금·가격·배치를 공통 거래로 검사. 아이템 정의/비용/배치 규칙은 #24 결정 대기. 시설 buy를 꾸미기 지급으로 임의 대체하지 않음 |
| #58 랭킹 | 가게 레벨·도감 수집 수·보유 차량 수·누적 게임머니·주행거리·최고가의 확정 집계 원인·범위/기간·출처·콘텐츠·집계 시각 → 값/지원 상태. 1차 실제 내 기록과 시연용 예시 provider를 분리 | 최고가=현재 보유 차종가×희귀도 수익 배율, 주행거리 하루500km/주간 초기화는 기존 기준. [1차 범위](01_PRD.md#1차-랭킹-범위)는 확정이며 실제 내 기록의 집계/기간/출처·누락을 엄격히 공급한다. 세 집계 기준은 [PRD](01_PRD.md#1차-랭킹-범위)로 확정됐다. 같은 DB의 전체 확정 원장/보유 이력·보상 반영 거리에서 읽고 미열람 목록·현재 잔액으로 대체하지 않는다. 확정 수익은 원래 정산/명령의 원인으로 분류해 포함·제외/중복을 검증한다. 기간/출처·이력의 완전성이 없으면 명시 Missing이며 정상0을 만들지 않는다. 온라인 식별/API/인증/개인정보는 향후 연결 계약으로 분리한다. [댓글6705552358](https://github.com/Yaho03/drive-growth/issues/24#issuecomment-6705552358)은 제안. 로컬 fixture를 실제 순위로 표시하지 않음 |
| #51~#58 공통 UI | 프로필/출처·현재 gate·콘텐츠/규칙 버전·대상 ID/revision·준비/확정 requestId·시도 세대 → Loading/내용(빈값)/Pending/실패/미지원/확정 snapshot | HostSession·QueryController·ResultsHostController·각 View 재사용. 현재 StoredHostSource의 기본 원장은 SIMULATION이며 차량 gate 허용을 실제 보상 출처로 혼동하지 않음. HostSnapshot은 개별 순차 조회이며 DB 일괄 원자 snapshot이 아님. 서로 의존하는 필드의 일관 조회/기준 revision은 저장 후속의 해당 조회에서 보완. 제한/페이지 이동/재생성 뒤 늦은 응답을 새 요청 결과로 쓰지 않음. Bundle의 선택/Pending만으로 지급 성공을 추정하지 않고 원래 요청을 조회·복구. #53/#55 표시 UI는 #124/#125 main 인계(실제 호스트/거래 연결은 별도), #57 표시 UI는 fixture로 진행 |

### 시간·초기화·이전과 검증 게이트

1. 기간 delta와 절대 시각을 분리한다. 같은 부팅의 elapsed는 순서·관측 간격용, UTC는 영속 사건/기한/일자 근거다. 재부팅·시계 역행·출처 혼합·관측 공백을 확인된 주행으로 채우지 않는다. #43의 기존 주차30분/30%/8시간 커서를 재사용하고 #40의 state.copy 시험을 SQLite 복원으로 바꾸지 않는다. 짧은 P 정차 합산·일반 주차 중 소모 정책은 #24의 미확정 항목이다.
2. 첫 지급·최초 영업 선물·가챠/강화/수리·시설·탁송·보상 수령은 동일 DB의 요청 이력과 점유/용량을 대조한다. 기존 SQLite Update가 이미 검사하는 RENTED/RETURNING/NEEDS_REPAIR 제한은 유지하고 다중 개체·수리 회복·최종 공간 조건만 필요한 구간에 확장한다.
3. 현재 공용 저장소는 #139 schema5·#156 schema6·#162 schema7을 보존하고 아래 초기화/선물/checkpoint의 통합 schema10을 제공한다. 후속 이전은 경민·정원이 실제 머지 순서로 조율한다. **미래 버전 번호를 여기서 확정하지 않는다.** #116 반영본 위에서 탁송 공용 연결을 갱신하는 기본 순서와 독립 Ledger 작업의 병렬 진행은 #37 댓글을 따른다. 같은 버전에 서로 다른 스키마를 만들지 않는다.
4. 테이블 이전과 개체 payload 복구는 별개다. 기존 schema1의 새 필드 없는 개체/정산·처분 이력은 자동 COMMON/0이나 빈 NEW로 바꾸지 않는다. 지원 구버전의 원본/부속 파일 보존·이전 실패 롤백, 알 수 없는/미래 버전 거절, 모든 조회의 엄격한 숫자/불리언 검증을 저장 후속에서 시험한다. 복구·사용자 초기화·백업 채택 정책은 #24 결정 전 임의 구현하지 않는다.
5. 계약 후속은 소비 파트의 정상/누락/충돌/정책 미정 fixture와 전체 요청 재생을 검증하고, 저장 후속은 실제 SQLite에서 두 요청 경쟁·다중 개체 한쪽 실패·커밋 전/후 장애·현재 상태 변화 뒤 재생·오연결/손상·DB 재개방·지원 버전 이전을 검증한다. Activity 재생성·DB 재개방·프로세스 재시작은 각각 별도 근거다. 기능 PR의 실제 소비 뒤 #28에서 연결·UI 제한·버전/출처·중복 지급을 확인한다. 이 문서 작성은 제품 시험 통과 근거가 아니다.

### #121 가챠 명령 capability의 첫 부분 공급

아래는 최초 공급 당시 기록이며 현재 제공 상태는 위 「제공 상태와 작업 책임」 표를 따른다.

이 변경은 `core.contract.GachaChangeStore : GameChangeStore`와 실행 가능한 같은 메모리 원장의 참조 구현을 제공한다. SQLite·호열의 PR #111 소비 연결은 아직 없으며 #122와 #47에서 이어간다. 기존 GameChange/Settlement·공통 개체와 `rental-contract-v1` 식별값을 유지하고 추가 capability로 지원 범위를 구분한다. 다중 개체 강화·초기화 NEW/EXISTING/UNKNOWN·원래 지급 조회는 #121의 다음 묶음으로 남는다.

| 타입/호출 | 실제 입출력·조건 |
|---|---|
| `GachaAuthority` | 저장 측이 소유한 source·contentVersion·ruleVersion·shopLevel·garageCapacity·purchasePrice·pity·guaranteedFrom·현재 해금 차종/양수 가중치 희귀도 집합. 시간/경제 기본값 없음. 두 Set은 생성 시 복사하고 변경 불가로 공개 |
| `GachaProgress` / `GachaState` | 가챠 상태 revision·카운터·무료권 수와 권위 설정. revision은 전체 원장 버전이 아니다. 설정/상태는 저장 담당이 검증·공급하며 UI 요청의 capacity/count를 현재 진실로 저장하지 않음 |
| `readGachaState()` | `ContractResult<GachaState>`. 미지원/미공급은 DATA_UNAVAILABLE. fixture의 명시적인 상태 주입은 제품 초기화·무료권 지급이 아님 |
| `GachaCommand` | requestId·출처·콘텐츠/규칙·예상 가챠 revision·기대 가게 단계/공간·이전 카운터·티켓 종류와 불변 `GachaAward`(새 개체·다음 카운터·보장 여부). 규칙 계산은 기존 Gacha/GachaPull이 담당하고 저장은 재추첨하지 않음 |
| `commitGacha(command)` | `GachaSettlement(command, settlement, progress)`. 최초 명령에 포함된 추첨·당시 개체/잔액·확정 카운터/무료권을 함께 반환. 호출자가 현재 계획의 draw를 다시 붙이지 않음 |

기존 요청의 종류/전체 입력 대조와 최초 결과 재생을 현재 상태 검사보다 먼저 한다. 같은 ID·다른 카운터/티켓/출처/버전/결과는 STATE_CONFLICT다. 새 요청은 현재 권위 설정·revision·카운터·가게 단계/공간, 실제 OWNED 수·잔액·무료권을 검사한다. 새 개체는 GACHA 취득·1레벨/0%·revision0·OWNED, 취득액은 유료의 권위 가격 또는 무료0이어야 한다. 비활성 희귀도/잠긴 차종·잘못된 보장/다음 카운터·revision 증가 범위 초과는 INVALID_INPUT, 후보 없음은 DATA_UNAVAILABLE, 무료권/잔액 부족은 INSUFFICIENT_FUNDS다. 유료는 금액 차감+Add, 무료는 금액 변경 없이 Add이며 가짜0원 거래를 만들지 않는다.

`InMemoryGameChangeStore`는 기존 잔액·차량·단일 정산을 재사용하고 가챠 전체 결과를 같은 요청 ID에 연결한다. 동일 모니터 안에서 전체 변경을 확정하므로 마지막 칸·마지막 무료권·같은 요청 경쟁은 한 번만 반영된다. legacy와 가챠의 요청 ID 재사용을 양방향 거절한다. **가챠 권위 상태를 명시한 fixture에 한해** 기존 거래에도 source 일치, 기존 Add에도 같은 현재 용량을 적용해 혼합 진입점의 초과를 막는다. 기존 호출자가 가챠 상태를 생략한 경우 옛 단일 거래 동작은 유지되고 새 가챠 호출은 DATA_UNAVAILABLE다. 이 메모리 잠금은 SQLite의 다중 연결 원자성 증거가 아니다.

#111 소비자는 먼저 저장 상태와 실제 사용하는 콘텐츠/규칙의 버전을 대조하고 같은 snapshot의 revision/카운터/가게 조건으로 기존 추첨·계획을 만든다. `GachaTicket`을 공통 `GachaTicketUse`에 대응시키고 plan.vehicle·plan.draw의 다음 카운터/보장 여부를 GachaAward에 넣는다. **보관한 같은 GachaCommand**를 재제출하고, 반환한 command/settlement/progress만 결과로 사용한다. 같은 ID로 현재 상태를 다시 읽어 새 명령/추첨을 만들면 충돌이므로 새 계획으로 재시도를 대체하지 않는다. 실제 소비 코드는 이 변경에서 수정하지 않았다.

#122 인계 조건: 같은 출처 DB의 실제 가게/공간·카운터/무료권·규칙/후보를 거래 안에서 읽고, 설정 변경 시 가게 단계·후보·버전의 일관성을 보장한다. 전체 명령을 안정된 표현으로 저장하고 기존 공용 정산의 ID와 충돌 검사하며, 재화/개체/카운터/무료권/최초 전체 결과를 한 트랜잭션으로 확정한다. 저장한 결과는 성장·처분·설정 변경 후에도 당시 값으로 재생한다. 기존 Add를 포함한 생성 경로의 실제 공간 검사·source 검사도 같은 DB에서 적용한다. 메모리의 구성값을 제품 DB 기본값으로 복제하지 않는다. DB 재개방·두 연결 경쟁·커밋 전 실패·응답 유실·손상 원본 보존을 실제 SQLite에서 검증하기 전 저장 capability가 제공됐다고 표시하지 않는다.

### #121 재료 강화의 독립 타입·순수 검증 준비

PR #127은 가챠 검토용 커밋 `08eb3c4`를 기반으로 `core.contract.MaterialContract`의 타입과 순수 검증을 먼저 제공했다. 당시에는 저장 참조 구현을 추가하지 않고 중단했다. 2026-11-09 사용자 재개 지시로 아래 「재료 강화 메모리 참조와 추가 계약」까지 확장했으며, 기존 성장 산식·SQLite·UI는 변경하지 않았다.

- `MaterialCommand`는 requestId/source/contentVersion/ruleVersion과 이름 있는 `target`·`material`의 기존 `VehicleWrite.Update`, 기본값 없는 `cost: GameMoney`를 보존한다. 역할·ID·revision·성장/lifecycle·비용·버전 모두 전체 입력 동일성에 포함된다. 비용0은 명시적 무과금이며 MoneyChange는 만들지 않는다. 현재 MaterialPlan 소비는 비용0을 명시하고, 양수 금액 시험값을 새 제품 강화 비용으로 채택하지 않는다.
- `MaterialState`는 저장 측 출처/두 버전·실제 잔액과 역할별 현재 OwnedVehicle/VehicleActivity다. 활동 조회 실패는 state 생성 전에 ContractResult.Failure로 전달하며 IDLE·잔액0으로 보정하지 않는다. `readMaterialState(targetId, materialId)`는 이 상태의 조회 계약이며 아래 메모리 참조에서 실행한다. 제품 SQLite 조회 구현은 아래 「#122 재료 강화 SQLite 확정 경계」에 제공하며 실제 제품 UI 소비는 후속이다.
- `prepareMaterialChange(command, current)`는 같은 개체 중복·역할 lifecycle·출처/버전·ID/현재 OWNED·양쪽 revision/증가 범위·같은 차종·IDLE·최대 레벨 대상·대상 성장의 단조 증가·재료 성장값 보존·잔액을 검사한다. 예정 두 개체는 권위 개체의 copy로 만들어 희귀도·차종·취득 출처/기준액을 각각 보존하고 revision을 각각1 올린다. 20레벨 재료는 허용한다. 결과 `MaterialPreparedChange`는 예정 snapshot·잔액·양수 비용일 때만 DEBIT를 담으며, 성공은 **저장·예약·경쟁 해결을 뜻하지 않는다.**
- 이 함수는 100/150/250/400% 성장 산식이나 비용 정책을 재계산/확정하지 않는다. 기존 `VehicleUpgrade.planMaterial → MaterialCommand → prepareMaterialChange` 소비 예제로 연결하며 정확한 성장량은 VehicleCare의 기존 계산 책임이다. 저장 시에는 검증된 규칙/계산 결과와 현재 상태를 같은 확정 경계에서 대조해야 한다.
- `MaterialSettlement`는 원래 command와 공통 SettlementId·Transaction·잔액·확정 시각, nullable이 아닌 **target/material 두 snapshot**을 보존한다. 후속 decoder는 거래의 requestId/종류/출처/규칙/금액 및 두 역할 ID/revision·결과 연결을 검증한다. 기존 nullable changedVehicle 하나에 두 역할을 숨기지 않는다.
- `commitMaterial(command)`는 동일 요청/전체 내용의 최초 결과 재생을 현재 검증보다 먼저 하고, 다른 내용/legacy/가챠 ID 재사용은 STATE_CONFLICT여야 한다. 새 명령은 같은 잠금/DB에서 최신 상태를 다시 읽고 두 개체·비용·요청/정산을 전부 확정하거나 전부 거절한다. 아래 메모리 참조는 이 순서·원자 확정·실패 주입·재생을 실행하며, 실제 SQLite 확정·재개방은 아래 #122 재료 부분에서 검증하고 제품 프로세스 경계는 후속이다. 두 번의 GameChange 커밋으로 우회하지 않는다.

현재 순수 검증 오류는 잘못된 역할/동일 개체/차종/성장·보존/증가 범위·출처가 INVALID_INPUT, 버전/revision/활동 충돌은 STATE_CONFLICT, 대상 누락·처분은 DATA_UNAVAILABLE, 비용 부족은 INSUFFICIENT_FUNDS다. 실패 주입과 현재 상태 변화 후 과거 두 snapshot 재생은 메모리 참조에서 검증하며, 실제 STORAGE_FAILURE·SQLite 재개방은 저장 구현 후속이다. 이 부분 인계로 #121/#122/#48을 완료 처리하지 않는다.

### #121 재료 강화 메모리 참조와 추가 계약

2026-11-09 사용자 재개 지시에 따른 부분 공급이다. 아래 계약은 기존 `rental-contract-v1`과 `GameChangeStore`를 유지한다. 새 SQLite 원장·기본 경제 수치·프로필 복구 정책을 만들지 않는다.

| 묶음 | 이번에 실행·제공하는 범위 | 실제 저장·제품 연결에서 남은 범위 |
|---|---|---|
| 재료 강화 | `InMemoryGameChangeStore : MaterialChangeStore`, `MaterialFixtureConfiguration`의 명시 출처/버전/활동과 기존 지갑·차량·모니터를 공유. 같은 전체 명령은 당시 두 개체/잔액/거래를 먼저 재생하고 legacy/gacha/material의 요청 ID 충돌을 양방향 거절 | #122 재료 SQLite의 두 개체·요청·최초 결과 원자 저장은 PR #156으로 제공. #48 제품 UI·명령 영속 복원은 후속 |
| 수리 | 기존 `InMemoryGameChangeStore : RepairChangeStore`의 `readRepairState/commitRepair/repairSettlement`, 같은 원장의 잠금·지갑·개체·요청 ID와 재료 활동 공유. 명시 `RepairFixtureConfiguration`으로 활동·확정 사고·견적을 공급 | #122 수리 SQLite commit과 RentalLedger 활동/확정사고·VehicleCare 견적은 아래 경계로 제공. 실제 제품 repairContent 공급·#49 UI/명령 영속 복원은 후속 |
| 초기화 | 기존 `InMemoryGameChangeStore : InitializationChangeStore`의 readInitialization/commitInitialization, 명시 프로필 상태·마커 fixture, 같은 원장 재화·첫 차·공간·마커·원래 결과 원자 확정/재생. 기존 FirstGrant 전용 어댑터 소비 | 실제 NEW 근거 발급·legacy 복구·SQLite 원자 저장, #151 소비 래퍼는 main 제공이며 #50/#56 제품 연결은 후속 |
| 첫 영업 선물 | 기존 `InMemoryGameChangeStore : FirstRentalGiftStore`, 명시 구성과 같은 가챠 progress·모니터에서 무료권/마커/최초 전체 결과 원자 확정·재생, 요청 ID 양방향 충돌·가챠 무료권 소비와 경쟁 | 실제 RentalLedger/live 완료 근거·같은 프로필 권위·SQLite commit, #50 제품 지급/프로세스 |

재료 참조는 같은 잠금에서 최신 두 개체·잔액·활동을 읽고 기존 prepare를 실행한 뒤 비용·두 revision·재료 소비·최초 결과를 함께 확정한다. 비용0은 `money=null`이며 새 요금 규칙이 아니다. 설정/활동 누락은 DATA_UNAVAILABLE, 명시 실패는 그대로 전달하고 IDLE/0으로 보정하지 않는다. 활동 Map은 구성 시 복사·변경 불가 처리한다. 실패 주입과 동일 재료·다른 명령 종류의 경쟁도 같은 원장에서 검증한다. fixture 활동은 고정 시험 입력이며 제품 RentalLedger의 live 활동이 아니다.

2026-11-09 후속: 수리 메모리 참조는 `RepairFixtureConfiguration`의 source/콘텐츠·규칙 버전/활동/확정 사고 누계/비용 견적을 명시 입력받는다. Map은 복사·변경 불가 노출하며 누락은 DATA_UNAVAILABLE, 명시 조회 실패는 그대로, 음수 사고 누계는 STORAGE_FAILURE다. 같은 개체의 재료·수리 활동이 모순되거나 원장 source가 다르면 구성을 거절한다. 수리 뒤 IDLE은 재료 조회와 공유하며 generic Update도 수리 구성의 현재 점유 제한을 따른다. 제품의 실시간 활동/견적 공급 구현은 아니다.

수리는 저장 측이 공급한 현재 NEEDS_REPAIR·개체 revision·확정 사고 누계·VehicleCare 비용 견적을 대조한다. 순수 결과는 비용 DEBIT(0이면 없음), NEEDS_REPAIR→IDLE, revision+1이며 차종·희귀도·성장·취득 정보·사고 누계와 영업 쉬기는 보존한다. 이전 전체 명령은 현재 조회보다 먼저 원래 수리 결과를 재생해야 한다. 참조 `commitRepair`는 같은 모니터에서 활동·개체·금액·최초 전체 결과를 함께 확정하고 실패 주입은 전부 무변경이다. legacy/gacha/material과 ID 충돌은 양방향 거절한다. 실제 SQLite도 같은 DB 거래에서 이 경계를 구현해야 하며 일반 Update와 활동 변경을 별도 commit으로 나누지 않는다.

초기화의 `NewProfileEvidence`는 신뢰한 저장/프로필 생성 경계가 검증해 공급하는 양성 근거의 DTO다. DTO 생성 자체가 신규 판별 구현은 아니다. 빈 차고·INITIAL_GRANT 이력 부재를 NEW로 만들지 않고, UNKNOWN/마커 없는 legacy의 미복구 결과는 DATA_UNAVAILABLE·무지급이다. 마커가 있는데 결과가 손상·누락됐다면 STORAGE_FAILURE다. `InitializationState`의 가용 후보는 생성·copy 경계에서 복사하고 변경 불가 Set으로 노출한다. 새 선택은 기존 `FirstGrant`의 전체 GameChange를 `commitFirstGrantInitialization`으로 전달하며, 응답 유실은 보관한 전체 InitializationCommand로 재시도한다. 초기 재화/차량/공간/마커는 실제 저장 후속에서 원자 확정한다. 초기화 메모리 참조의 새 Ready 확정에서도 일반 Add와 같은 개체 ID 유일성을 검사한다. SOLD/SCRAPPED/CONSUMED를 포함해 기존 ID가 있으면 변경 전 STATE_CONFLICT로 거절하며 원래 전체 명령 Replay는 이 검사보다 먼저 처리한다.

2026-11-09 초기화 후속: `InitializationFixtureConfiguration`은 명시 상태/양성 근거 및 저장된 마커를 시험 입력으로 공급한다. 빈 차고/이력 부재에서 NEW를 추론하지 않는다. 같은 모니터에서 현재 지갑·소유 개체 수를 읽고 기존 prepareInitialization으로 검사해 초기 재화·첫 차·공간·마커·전체 결과를 함께 확정한다. 비용/무료권을 임의로 만들지 않고, 명시 가챠 상태가 있으면 공간만 공유하며 진행/무료권은 보존한다. 일반 Add도 확정된 초기 공간을 검사한다.

기존 결과는 당시 전체 명령/차량/재화/거래/source/프로필/마커/공간 연결을 확인한 뒤 readInitialization의 EXISTING(original)로 공급한다. 마커-결과 누락/손상은 STORAGE_FAILURE, UNKNOWN/마커 없는 legacy는 무지급이다. 현재 콘텐츠·후보·잔액·성장/판매가 달라도 보관한 전체 명령은 최초 결과를 재생하고, 다른 선택·명령과 legacy/gacha/material/repair의 같은 ID는 충돌이다. 검증에 원래 근거를 사용하는 것은 NEW 발급 구현이 아니다. 초기화 참조 작성 당시와 달리 현재 #151의 원래 명령 재생·선물 호출자 source/profile 수정도 main 제공이며, 이 참조의 기존 개체 ID 충돌 수정과 별도 근거로 구분한다.

2026-11-10 선물 메모리 참조 공급: `FirstRentalGiftFixtureConfiguration`의 명시 프로필/출처·콘텐츠/규칙·최초 완료 영업을 시험 입력으로 받는다. 잔액/차량 존재로 완료를 추론하지 않으며 수익0 완료도 허용한다. 같은 모니터와 기존 `gachaState.progress`에서 무료권+1/revision+1·프로필 마커·최초 전체 결과를 함께 확정한다. 재화·차량·활동·보장 카운터는 보존한다. 당시 상태는 기록 검증용이며 별도 현재 권위가 아니다. command/거래/마커/결과의 연결 손상·누락은 STORAGE_FAILURE, 같은 전체 명령은 현재 권위/완료 조회보다 먼저 원래 결과를 재생한다. 기존 legacy/gacha/material/repair/initialization과 요청 ID를 양방향 거절하고, 가챠 무료권 소비와 같은 progress revision으로 경쟁한다. 초기화의 기존 개체 ID guard를 유지한 통합88bb의 독립84개로 검토했다. 실제 SQLite/live 완료·NEW issuer/legacy 복구·제품 UI/프로세스는 별도다.

첫 선물은 같은 프로필 원장의 최초 완료 영업 rentalId/SettlementId를 근거로 무료권+1·GachaProgress revision+1·프로필당 마커를 함께 계획한다. 잔액은 완료 증거가 아니며 정상 수익0도 허용한다. 가챠의 무료권 소비와 같은 progress revision을 사용하고 보장 카운터·재화는 유지한다. `Ready`는 예정일 뿐 실제 지급이 아니다. 동일 전체 명령은 최초 전체 결과 재생, 다른 명령/기능의 같은 요청 ID는 충돌이어야 한다.

`MaterialStoreTest`, `RepairContractTest/RepairContractFixtures`, `ProfileContractTest/ProfileContractFixtures`가 실행 가능한 소비·실패 예제다. 수리 메모리 참조의 실제 확정 시험은 디스크 저장이 아니며, 초기화 메모리 참조의 확정도 영속 저장이 아니며 선물 fixture 응답 예제는 실제 확정 성공이 아니다. 어느 부분도 #121/#122/#48/#49/#50 전체 완료나 에뮬레이터·실차 검증을 의미하지 않는다.

### #122 가챠 SQLite 확정 경계

`SqliteGameChangeStore`는 `GachaChangeStore`를 구현한다. `readGachaState/commitGacha`는 공통 계약의 전체 명령·전체 결과를 사용한다. 스키마4에 `gacha_progress`와 `gacha_requests`를 추가한다. v1/v2/v3의 기존 표·행은 보존한다. #139의 탁송·주행 통합은 다음 schema5로 main 반영됐다.

권위 콘텐츠는 저장소 구성 시 `gachaAuthority` 공급자로 전달하며 현재 DB의 가게 레벨·용량과 일치해야 한다. 후보·가격·보장·버전은 공급자의 불변 GachaAuthority를 따른다. `initialGachaProgress`는 신뢰한 프로필 초기화 입력을 새 DB 생성 시 명시한 경우에만 저장한다. 기존 DB 이전에는 값을 주입하지 않는다. 값이나 공급자가 없으면 DATA_UNAVAILABLE이며, 과거 이력 부재로 무료권·카운터를 0으로 초기화하지 않는다. #50의 NEW/EXISTING/UNKNOWN 및 복구 정책·실제 앱 공급 연결은 후속이다.

확정은 기존 요청 재생/다른 종류 ID 충돌 → 권위 상태·revision·공간·추첨 결과·티켓·재화 검사 → 공용 원장·차량·카운터·티켓·최초 결과 저장 순서다. 무료권은 실제 취득액0과 money=null을 사용한다. 현재 상태가 바뀌어도 같은 전체 명령 재시도는 당시 결과를 반환한다. 같은 DB의 일반 Add도 설정된 공간을 검사하고, 기존 원장·대여 방문·가챠의 요청 ID 재사용을 거절한다. 두 연결의 쓰기와 실패 롤백은 같은 SQLite 거래로 직렬화한다. 읽은 가챠 정수와 과거 결과 연결 오류는 STORAGE_FAILURE다.

### #122 탁송·기본 주행 저장 통합

정원의 #128/#131/#134/#135/#137 Ledger·계산 코드를 기존 DB에 연결한다. `store.delivery`, `store.driving`가 제품 호출 입구이며 새 DB와 지원 v1~v4 이전에서 탁송/주행 표를 생성한다. 이 탁송/주행 통합에서 도입한 스키마는5이며 현재 재료 스키마6·이전은 아래 재료 SQLite 절을 따른다. 기존 렌털·발견·가챠 표는 해당 버전에 이미 있으면 재생성하지 않는다.

공용 `record`는 직접 호출에도 출처를 검사하고, 탁송·주행 내부 확정은 각각 DELIVERY/DRIVING 종류를 요구한다. `delivery/`와 `drive/` 요청 ID는 해당 내부 Ledger용으로 예약해 일반 거래·렌털·가챠·탁송 선택이 선점하지 못하게 한다. 수익 선택의 원래 요청 ID는 `delivery_requests`로 점유하므로 금액이 없어도 다른 기능에서 재사용할 수 없다. 내부 거래가 기존 정산을 만나면 기능 이력 없이 성공으로 재생하지 않고 충돌로 반환한다. 과거 요청 재생은 각 Ledger가 보존한 명령/결과 이력을 먼저 검사한다.

도착 시 빈 칸은 같은 거래의 shop.capacity와 OWNED 개체 수를 사용한다. 기존 가챠 공간과 단일 Add 경계도 공유한다. 실제 표시된 구매 후보의 발견 기록 호출은 #53 표시 이벤트 연결에서 수행한다. 이 통합만으로 목록 생성 시 발견 기록을 추가하지 않는다. 실제 내비·차량 SDK·제품 화면 연결은 각 담당의 후속이다.


#139의 예약 prefix 규칙은 새 요청에 적용한다. v4가 허용한 정합적인 GENERAL 정산은 source와 실제 기능 소유 이력을 검사한 뒤, 동일 전체 명령·원래 정산 연결을 검증해 먼저 재생한다. 기능 소유 행이 있는 ID·다른 명령은 재생 예외가 아니며 충돌이다. 활성 구매는 공급본 b12f268의 readRequest 단계에서 실제 결제 Settlement를 검증해 active/inProgress/terms 경로에서도 누락·오연결을 저장 오류로 반환한다.


### 휴식 저장의 현재 검토 경계 — 2026-11-09

[#143](https://github.com/Yaho03/drive-growth/pull/143)의 `RestLedger`는 출발/기간 소모·전체 명령 재생을 위한 부분 제안이며 main 공급 전이다. [이력 완전성 수정 요청](https://github.com/Yaho03/drive-growth/pull/143#pullrequestreview-5820998093)이 남아 있다. 누락 이력을 정상 상태로 복원하지 않는 수정과 기존 재생/rollback 회귀를 확인한 뒤 #122가 공용 연결·테이블/이전과 기본 주행 수익의 원자 소모를 연결한다. 실제 구간/장소·정차 번호 발급·음성·제품 화면은 기존 담당 경계를 유지한다. 새 제품 규칙을 확정하거나 팀원 코드를 대신 구현한 변경이 아니다.


### #52 가챠 호스트 명령 보존과 정확한 비율 표시

`app.host.gacha.StoredGachaHostSource`는 실제 assets와 기존 SqliteGameChangeStore를 GachaStoreHostSource/GachaPull로 연결한다. 권위/진행이 없으면 DATA_UNAVAILABLE이며 초기 재화·무료권·권위값을 주입하지 않는다. 명시 factory는 같은 프로필·출처의 기존 구성 저장소를 연다. 여러 조회를 원자 일괄 조회라고 약정하지 않으며 최종 경쟁은 commitGacha 책임이다.

GachaHostController는 확인 입력을 입구에서 한 번 고정하고 같은 사본을 검사·saved·worker prepare·응답 상관에 사용한다. 전체 GachaCommand의 checkpoint 성공 뒤에만 commit하며 반환한 원래 GachaSettlement로 새 Result를 공급한다. 반복/복원 재시도는 같은 command만 제출하고 현재 콘텐츠/권위 조회를 과거 재생보다 먼저 강제하지 않는다. command 없는 준비 실패를 retry에서 새 prepare로 바꾸지 않는다. clearUnprepared의 반환 키가 있을 때만 미제출 UI 예약을 해소한다. command가 있거나 결과가 불명이면 유지하며 제한/해제/옛 표시 세대·늦은 응답을 검사한다.

GachaOffers는 기존 appliedPercent와 선택적 GachaRatio를 받는다. GachaRatio는 모든 Rarity의 원시 Long 가중치·양수 Long 분모를 불변 Map으로 보존하고 음수/키/합을 검사한다(BigInteger 합으로 overflow 방지). 둘 중 하나는 필요하며 함께 주면 percent×분모=가중치×100을 정확히 비교한다. 일반 회차는 기존74.7/20/5/0.3%를 유지하고 보장 회차는0/53·0/53·50/53·3/53을 직접 표시한다. 임의 반올림·합100 잔여 배분·core 확률 변경 없이 정상 prepare/commit/retry로 이어진다. 순환소수는 정책 미정 오류가 아니다. 실제 권위/보장 후보 누락은 기존 오류를 유지한다.

보존 입출력은 GachaHostState의 generation·pending GachaRequest(전체 확인 입력/attemptId)·GachaCommand 전체다. 같은 프로필의 최신 GachaUiState도 함께 복원하고 이전 owner는 release한다. durable checkpoint/codec와 Draw를 포함한 GachaHostController의 제품 조립은 미제공이다. 아래 MainActivity의 GachaBrowseHost는 조회만 연결하며 새 DB/SharedPreferences를 만들지 않았다. 누락 command를 명시적 미준비 null로 보정하지 않는다. freeTickets가 기존 UI Int 범위를 넘으면 0으로 채우지 않고 DATA_UNAVAILABLE다.

### #122 재료 강화 SQLite 확정 경계

SqliteGameChangeStore는 MaterialChangeStore.readMaterialState/commitMaterial을 제공한다. 구성 시 materialVersions 공급자가 콘텐츠/규칙 버전을 명시하며, 없거나 실패하면 DATA_UNAVAILABLE 또는 원래 오류를 반환한다. 명령이 주장하는 버전에서 저장 권위를 만들지 않는다. 조회/새 확정은 같은 SQLite 거래 안에서 현재 잔액·역할별 두 개체와 RentalLedger의 live 활동을 읽고 기존 prepareMaterialChange를 사용한다. 영업 쉬기와 IDLE 활동은 구분하며 대여/반납/수리 상태는 두 역할 모두 거절한다. 성장 산식은 기존 #148 VehicleUpgrade.planMaterial/prepareMaterial 책임이고 제품 비용은0을 유지한다.

확정 순서는 기존 재료 요청/최초 결과의 무결성 검증·동일 명령 재생 → 공용 요청 ID 충돌 검사 → 최신 버전/revision/활동/잔액 검증 → 두 차량·비용·공용 정산·최초 전체 결과 저장이다. 서로 다른 요청이 같은 재료를 소비하거나 배정/처분과 경쟁하면 같은 DB 거래의 현재 조건으로 판정한다. 단일 GameChange 두 건으로 나누지 않는다. 공용 settlements에는 동일 거래의 메타데이터를 연결하고, material_requests에는 전체 명령·검증 당시 상태·역할별 두 snapshot을 포함한 최초 MaterialSettlement를 저장한다. 두 역할을 현재 차량이나 nullable 단일 changedVehicle에서 재구성하지 않는다. 비용0은 money=null이며 가짜0원 결제를 만들지 않는다. 기존 가챠/대여 방문/무금액 탁송 선택과 요청 ID 공간을 공유한다.

스키마6은 material_requests만 추가하며 지원 v1~v5의 기존 표/행/정산·렌털·발견·가챠·탁송/주행 이력을 보존한다. 이전 중 실패는 SQLiteOpenHelper 거래로 롤백하고 원본을 보존한다. 스키마5 공급이 main에 반영된 뒤 준비한 버전이며 향후 PR 머지 순서가 달라지면 재확인한다. 최초 결과 재생은 현재 버전 공급/활동/성장/처분과 독립적이며 원래 상태+명령의 순수 검증 결과, 공용 정산의 ID/출처/종류/규칙/금액/잔액/시각 및 두 역할을 대조한다. JSON 정수·금액/차량은 공용 LedgerJson decoder를 재사용해 소수/문자열/지수/범위 초과를 보정하지 않고 STORAGE_FAILURE로 반환한다. 수리 SQLite commit은 아래 #162로 공급됐고 초기화/선물 SQLite는 아래 schema10으로 부분 공급하며 제품 UI/권위 공급·프로세스/실차 연결과 #122 전체 완료는 별도 후속이다.

재료 SQLite의 자기 material-v1 공용 정산이 남고 material_requests 행만 누락된 경우는 정상 타 기능 ID 충돌과 구분해 STORAGE_FAILURE로 거절한다. 누락 snapshot을 현재 개체에서 재구성하지 않으며 원본을 보존한다. 정상 같은 명령의 최초 전체 결과 재생·다른 입력/다른 기능 ID의 STATE_CONFLICT는 유지한다.

휴식 원장의 head 쓰기도 같은 거래의 필수 확정이다. rest_head의 singleton 행1 삽입 결과를 검사하고 무삽입/실패는 STORAGE_FAILURE로 전달해 로그·head·allocator를 함께 rollback한다. 정상 seq gap을 손상으로 거절하지 않으며 연결/머리 정보의 누락 검증·같은 요청 재생·실패 재시도는 유지한다.


### #122 수리 SQLite 확정 경계

2026-11-10, 별도 로컬 head `272b62dc2777c6a391e8939cf9a321e5f847e025`에서 기존 SqliteGameChangeStore가 RepairChangeStore의 readRepairState/commitRepair/repairSettlement를 구현한다. 선행 PR156 dc9687의 schema6와 MaterialLedger 이력 누락 수정068ce4a를 정상 통합한 기준이며 수정/호환 최종3c473dd의 독립 코드34개 및 실제 SQLite30개를 확인했다. 정확 게시 head/CI/main 공급은 연결 PR을 따른다.

명시 repairContent 공급자는 검증된 GameContentSnapshot을 반환한다. 조회 중 상태를 변경하지 않으며 같은 콘텐츠/규칙 버전과 차종 가격을 공급한다. 없으면 DATA_UNAVAILABLE, 명시 실패는 그대로 전달한다. 같은 SQLite 거래에서 현재 개체·잔액·RentalLedger 활동/확정 사고 누계와 실제 VehicleCare 견적을 대조한다. NEEDS_REPAIR 활동도 확정 사고 영업에 연결돼야 하며 누락을 IDLE/0으로 보정하지 않는다.

전체 RepairCommand와 지급 전 RepairState 및 당시 전체 RepairSettlement를 repair_requests에 보존한다. 공용 settlements/지갑/차량/대여 테이블을 재사용하며 비용 차감·revision+1·NEEDS_REPAIR→IDLE·두 종류의 정산 기록을 한 거래로 확정한다. 사고 이력·영업 쉬기·희귀도·레벨/진행량·취득 정보는 보존한다. 비용0은 money=null이다. 원래 전체 결과를 현재 콘텐츠/활동보다 먼저 검증·재생하며 다른 전체 입력/도메인 ID는 충돌, 한쪽 이력 누락·손상은 STORAGE_FAILURE다. 원래 결과를 현재 값으로 재구성하지 않는다.

스키마7은 repair_requests만 추가하고 지원 v1~v6의 기존 표/행을 보존한다. 실제 원격 머지 순서에서 번호를 다시 확인한다. 기존 구조 fixture의 schema7 준비 보완은 최종3c473dd의 시험 호환 커밋으로 실제 반영했으며 독립 실제 장치에서 수리23개·호환 표적7개로 이전/실패 rollback·다중 연결/재개방을 확인했다. 정합 구조 fixture이며 실제 옛 APK 제품 DB 이전·프로세스/실차 성공은 아니다. 초기화/첫 선물 SQLite는 아래 schema10으로 부분 공급하며 제품 관리 UI/프로세스 연결·전체 #122/#49는 후속이다.


수리 account 무갱신 추가 경계: RepairLedger의 잔액 UPDATE는 영향행이 정확히1인지 검사한다. 예외 없는0행 갱신도 저장실패로 전달해 같은 atomic 경계의 차량/활동·공용/수리 기록을 전부 rollback한다. 원래 전체 명령의 재생 우선순위는 유지하고 현재 상태 검사로 과거 결과를 바꾸지 않는다. 원래3c의 RED와 수정51e의 같은 회귀 GREEN은 [06](06_VERIFICATION.md)의 후속 근거를 따른다.


### 렌터카 runtime의 고정 입력·확인 구간 연결 — 2026-11-10

`app.runtime.rental.SqliteRentalRuntimeSource`는 명시 store factory가 연 같은 출처의 기존 RentalLedger에 고정 RentalVisit을 assign하고, 확인된 RentalTick에서 account→settle→finishReturn을 호출한다. 별도 영업 원장/고객 난수/수익 산식은 없다. Pending은 기존 parked_since를 엄격 정수로 읽어 원래 연속 주차 기준을 반환한다. source/clock/부팅 불일치·미래/역행·관측 공백은 실패/대여별 보류이며 미수신 구간을 Driving으로 바꾸지 않는다. epoch/elapsed는 공급자가 같은 부팅에서 보정한 쌍이어야 하고, 관측만료/실제 P·연속주차 판정·bootId 발급은 입력 공급 책임이다. runtime는 정상수익/감액을 직접 계산하지 않는다.

RentalRuntime는 UI 진입과 worker 저장 실행을 분리하고 한 작업만 수락한다. stop은 대기 작업·늦은 응답을 폐기하지만 이미 시작한 원장 확정을 취소하지 않는다. 재개 시 read/refresh와 원래 고정 방문 명령으로 조회/재생한다. 각 대여의 account·정산은 기존 원장의 개별 확정 경계이며 여러 대여를 하나의 원자 거래로 취급하지 않는다. firstCompleted는 실제 CompletedRental과 rentalId/settlementId를 반환하며 선물 지급을 수행하지 않는다. 방문 수와 완료 고객 수를 혼용하지 않는다.

이 공급은 자동 NPC 방문 정의·버전 있는 제품 요금/시설 정책·실제 차량 관측/시계·프로필 store factory·Activity/UI와 연결된 상태가 아니다. 정의 없음은 DATA_UNAVAILABLE/명시 실패로 전달하고 시험용 SIMULATION 입력과 제품 공급을 구분한다. 원래 주차 시각만으로 앱 종료 중 공백을 연속 P로 추정하지 않는다. 고정 SIMULATION 입력의 독립 실제SQLite10개는 원본eb096에서 통과했다. 최신 게시head 재실행·프로세스/전원·제품 영업 성공은 아니며 실제 NPC/정의·coverage/clock·factory/Activity 연결은 후속이다.


### #51 실제 가게 HOME·탐색과 가챠 조회 연결

2026-11-10 최초 HOME 연결은 `ShopHostMapper`의 순차 `HostSnapshot` 표시였다. 같은 날 아래 중앙 시험 정의·실제 확장 호스트 연결에서 HOME 조회·확장 소비 경로를 교체했다. appearance의 간판·색은 고정 표시 스타일이며 저장 꾸미기 상태가 아니다. Shop revision은 표시 세대이고 DB/거래 revision이 아니며 실제 저장 revision 검증을 대신하지 않는다.

HostSession의 같은 worker에서 `StoredGachaHostSource`→기존 `GachaStoreHostSource.read`를 연결한다. 현재 권위 부재는 DATA_UNAVAILABLE, 다른 조회 실패는 원래 오류다. 전체 요청/명령의 durable checkpoint/load가 미제공이라 실제 제안이 있어도 UNSUPPORTED로 표시해 Draw를 활성화하지 않는다. 메모리 checkpoint 성공으로 영속 보존을 가장하지 않으며 prepare/commit은 이 조회 호스트가 호출하지 않는다. 아래 제품 세션 연결은 실제 NEW/SQLite 공급을 소비하며 legacy/불명은 Onboarding Unavailable을 유지한다. 필요한 입출력은 기존 #121/#122의 저장 연결 범위다.

가챠 조회 호스트는 workGeneration(비동기 작업 무효화)과 frameGeneration(표시 세대)을 분리한다. Loading과 terminal Missing/Failure/Offers마다 표시 세대를 전진시켜 UI의 같은 세대 다른 내용 거절 규칙을 유지한다. 예전649의 Loading 잔류는 수정360에서 같은 고정 회귀와 실제 native terminal로 해결했으며 오래된 작업/gate 거절을 완화하지 않았다.


### 초기화·첫 영업 선물·명령 checkpoint의 공통 SQLite 공급 — 2026-11-10

원81bbaf30의 `SqliteGameChangeStore`는 같은 DB의 InitializationChangeStore/FirstRentalGiftStore와 `localProfileId()`/`hostCommands`를 제공한다. schema8은 초기화 프로필·전체 요청, schema9는 첫 영업 선물의 마커·전체 요청, 통합 schema10은 미확정 호스트 명령 표를 추가한다. 실제 helper의 onCreate에서만 빈 DB 검증 뒤 NEW 근거를 발급하며 기존 v1~v9 이전은 원본 표/행을 보존하고 기존 프로필에 신규 지급 근거를 만들지 않는다. 이전 실패·미래 버전은 원본을 보존한다. 빈 차고·이력 부재에서 신규로 추정하지 않는다.

초기화는 같은 원장의 재화/첫 개체/공간/프로필 마커·전체 결과를, 선물은 실제 첫 완료 영업 rentalId/settlementId/source와 연결한 무료권1/진행 revision·마커/공용 정산·전체 결과를 함께 확정한다. 같은 전체 명령의 최초 결과는 현재 권위/후보/성장/처분·진행보다 먼저 재생한다. 손상/한쪽 누락은 STORAGE_FAILURE이며 0/정상 미지급으로 보정하지 않는다. 초기화 고아 정산 검사는 공백 JSON에도 적용하고, 선물 NEW 확정 끝의 전체 결과 재조회가 실패하면 같은 거래를 rollback한다. 초기화 이후 일반 Add도 공용 공간을 검사한다. 원래 수리 계좌 guard와 RentalRuntime은 유지한다.

worker 전용 `hostCommands`는 Gacha/단일 Garage GameChange/Material/Repair 및 아래 Expand 명령의 원래 typed 내용과 프로필·출처를 저장한다. save→execute→finish, 시작 load·원래 STARTED 복구, 명시 cancelUnstarted(SAVED)를 구분한다. checkpoint 자체는 완료 거래의 requestId 점유 목록에 넣지 않아 자기 실행을 막지 않으며 기존 완료 이력 namespace는 유지한다. 기존 decoder·원자 거래와 엄격 숫자 검증을 재사용한다.

이는 전체 GachaRequest/UI/attemptId/Bundle의 영속 codec이나 제품 프로세스 복원 구현이 아니다. 실제 동일profile/source factory·비동기 호스트 소비·첫 선물 runtime·NPC/clock/coverage·제품 Activity/프로세스·전원/실차는 후속이다. #44의 신규 Expand 후보는 이 네 명령 공급에 포함하지 않는다. 정확 게시 head/CI는 연결 PR, 실제69 메서드실행과 checkpoint23 재사용 범위는 [검증 문서](06_VERIFICATION.md#초기화첫-선물checkpoint-sqlite-통합81-독립-검증--2026-11-10)를 따른다.


### 제품 세션·첫 실행·가챠·차량 관리의 공통 저장 소비 — 2026-11-10

원cab567은 C274의 GameSessionFactory/온보딩7파일과 Garage 공급을 포함한다. MainActivity/HostSession/StoredHostSource·가챠·차량 관리가 동일 factory/profile/source/store를 소비한다. 기존 DB 모드를 보존하고 두 모드 파일 공존/잘못된 source·손상·사라진 기존 DB를 새 프로필로 바꾸지 않는다. NEW는 실제 helper 근거를 소비하고 초기화/선물 상태를 누락 보정하지 않는다. UI Bundle은 선택/요청 식별만 저장하며 NEW 근거·완료를 만들지 않는다.

GachaLiveHost→CheckpointGachaSource는 기존 GachaPull/공용 hostCommands의 worker save→execute→finish를 사용한다. UI 저널은 메모리 경계이며 durable typed 명령·당시 확정 결과를 복구한다. 결과의 차고 이동/명시 재조회에서 finish하고 SAVED만 명시 취소한다. STARTED/불명·finish 실패·알고 있는 원명령 누락을 정상 새 요청으로 바꾸지 않고 과거 명령을 현재 가격/새 난수로 재prepare하지 않는다. Garage도 같은 factory의 실제 원장을 사용하며 재료/레벨업/수리/판매/폐차 준비와 저장 응답의 비용/개체/잔액을 연결한다. 기존 명령/decoder와 공통 checkpoint를 재사용하고 별도 지갑/DB를 만들지 않는다.

실제 첫 실행→1,000/첫 차→유료500 추첨→차고·확정 뒤 Activity/프로세스 재실행은 격리 패키지와 실제 SDK gate에서 확인했다. 무료권/수리/재료 등의 원장 경계 fixture 시험과 제품 native 실행은 구분한다. 첫 영업 선물 runtime·미완성 Expand/Quest/Delivery·전원/실차·모든 in-flight process 중단 검증은 포함하지 않는다. 세부 근거는 [검증 문서](06_VERIFICATION.md#제품-첫-실행가챠공통-factory-소비-독립-검증--2026-11-10)를 따른다.


### 내 기록·시연 랭킹의 읽기 모듈 — 2026-11-10

원58407d의 RankingReadSource는 기존 같은 profile/source SQLite를 한 snapshot으로 읽고6지표를 집계한다. 지급/지출·schema/helper를 바꾸지 않는다. 누적수익은 전체 확정원장/원인을 검증하고 판매·폐차를 포함·초기지급/환급 제외, 도감은ever-owned고유칸, 최고가는현재OWNED가격×희귀도수익배율이다. 기준은 [PRD](01_PRD.md#1차-랭킹-범위) 한곳을 따른다. RankingController/RankingView와 DemoRankingProvider는 실제내기록과합성참여자/예시순위를분리하고통신/계정/서버를추가하지않는다.

실제0/Empty/Missing/Failure를구분한다. Android checked JSONException은지표경계의STORAGE_FAILURE로변환하고숫자를절삭하거나DB를수리하지않는다. 누적/주간이같은주행원장을검증하면그손상에서두지표가실패할수있으며모든손상에서다른5정상이라고보장하지않는다.

departureZone hook은실제출발당시지역근거를요구한다. 기존LocalDate만있는기록을현재zone/정산시각/고정KST로바꾸지않고불명/다른지역은주간Missing이다. questCauses hook은같은DB의검증된원래Quest지급요청ID만공급한다. 미분류입금이있는데hook이없으면누적Missing이며잔액/미열람목록으로대체하지않는다. 실제생산자/영속근거는 #39/#45/#58/#122에서연결하고 MainActivity hook은별도다.


### 확장 조건의 확정 완료 고객 조회 — 2026-11-10

원542649a의 RentalLedger.completedRentalCount()는 같은 DB의 rentals/settlements를 기존 decodeCompleted로 검증하고 rentalId/settlementId 고유 건수를 반환한다. 누락·손상·공유 정산/정확 소문자 rental/settle/ 고아는 STORAGE_FAILURE이며 정상0/미완료로보정하지않는다. 타 도메인 대문자 prefix는 자기 고아로 취급하지 않는다.

expand는 기존 purchase atomic 안에서만 완료수를 preview의 servedCustomers에 공급하고 단계/공간/차감을 함께확정한다. 원래 동일 전체요청 replay를 현재조건보다먼저 유지한다. shop().servedCustomers의 방문수·보험/buy 의미는바꾸지않으며 제품미리보기는별도completedRentalCount를소비해야한다. 현재원래정산이검증되면무료/사고영업도완료이며현개체/잔액으로과거완료를추정하지않는다.

최신main Runtime Pending의 parkedSince/엄격정수조회를정상통합해보존했다. helper/schema/새원장은변경하지않았다. 이부분은버전있는실제확장정의·시설비용/효과·제품source/UI·영속Expand명령모두를제공하지않으며동의된시험값은 [PRD](01_PRD.md#기존-동의된-대여확장-시험값)를따른다.


### 중앙 시험 정의와 실제 확장 호스트 연결 — 2026-11-10

E 원본 `26c1e7b`의 `trial-definitions.csv`와 `TrialDefinition`은 기존 PRD의 동의된 대여·확장 시험값을 버전 있는 한 정의로 공급한다. 확장 비용·용량·확정 완료 고객 조건, 차종별 대여 요금·시설·연료·Quest 숫자 입력을 구분하며 신규 Quest 사건/진행/수령·실제 자동 영업 생산자를 구현한 것은 아니다. 정의 누락·버전/등록 ID 불일치는 실패로 유지하고 정상 기본값을 만들지 않는다.

B 원본 `aef5a11`의 Main HOME은 같은 `HostSession.games` factory·단일 worker를 쓰는 `SessionShopExpansionSource`와 `ShopExpansionHostController`를 연결한다. 미리보기는 실제 완료 고객 수를 읽고, 확인 시 최신 저장 조건과 정의를 재검증한다. typed Expand 원명령은 기존 같은 DB/profile checkpoint의 SAVED→STARTED→원래 결과 확인·finish로 처리한다. 실행 전 명시 취소와 결과 불명 복구를 구분하고 현재 입력으로 명령을 다시 만들지 않는다. 기존 가챠/차량 관리 명령과 보관 슬롯을 공유하며 다른 종류의 명령은 해당 화면으로 안내한다. Garage adapter도 Expand를 자기 명령으로 해석하지 않는다.

허용 상태의 실제 전이만 확장 호스트에 전달하며 동일 상태의 250ms 반복 전달은 재렌더하지 않는다. 제한·화면 이탈·Activity 해제 뒤 오래된 응답은 적용하지 않고, 재진입 시 같은 저장 원명령을 복구한다. 시설 구매·배치·꾸미기, 자동 영업 driver, 신규 Quest/Driving 생산자, 랭킹 Main 진입은 이 통합에 포함하지 않는다. 기존 #173·#174·#175 소스와 저장 공급은 유지한다. 실행 및 미검증 범위는 [06](06_VERIFICATION.md#중앙-시험-정의실제-확장-main-통합--2026-11-10)을 따른다.


### Quest·Delivery schema12 저장 공급 — 2026-11-10

E b8972b56의 기능 커밋만 main177 위에 통합한 후보는 schema12다. Quest 정의/기간·실제 원인 이벤트·최초 수령/원래 요청 조회와 typed Quest 명령을 기존 같은 DB/profile/source 원장에 연결한다. 확정 결과 열람·실제 차량 성장·대여 완료의 원자 이벤트 hook을 공급하며 현재 값을 과거 진행으로 합성하지 않는다. Delivery 선택/변경/포기/도착/Hold 전체 원명령과 당시 중앙 trial 규칙을 저장·재생한다. STARTED/정산의 실제 쓰기와 Hold 생성/해소의 후조건을 확인하고 실패 시 같은 거래를 롤백한다. 삭제/INSERT 무시 회귀와 손상 zone 검증을 포함한다.

E 변경31파일 중30개는 b897과 동일하며 RentalLedger는 최신 main의 Pending.parkedSince·엄격 정수 조회만 추가 보존했다. 중앙 trial·helper/data에 새로운 제품 논리를 추가하지 않았다. 현 앱 소비 호환은 Garage adapter의 Quest/Delivery 명령·결과를 STATE_CONFLICT로 거절하고, 보관 경로 조회도 두 명령을 명시 실패로 반환해 UI의 재확인 상태로 전달한다. null은 실제 빈 checkpoint에만 사용하며 명령을 지우거나 다른 페이지 명령으로 실행/취소하지 않는다. Driving schema13·제품 Progress/Delivery Main·랭킹 진입은 포함하지 않는다. 제품 연결과 공식 SDK/기기 범위는 별도다.


### 관측 주행 schema13 저장 공급 — 2026-11-10

D의 고정 관측 recorder7파일과 E `0681fbfe`의 저장 공급을 main178 위에 통합했다. `ObservedDrivingReducer`는 명시 정책·receiver token·clock/calendar와 출처 있는 VehicleObservation을 받아 확인된 구간·공백·출발·봉인 outbox를 구분한다. `SessionObservedDrivingSource`의 journal 포트는 같은 GameSessionFactory/profile/source의 기존 store.atomic을 사용한다. Main/HostSession에서 실제 VehicleInput을 연결하는 B52e 변경은 포함하지 않는다.

schema13은 marker·전체 상태 revision CAS·원출발 provenance를 같은 DB에 보존한다. 기능은 worker의 `activateObservedDriving(profileId)`로 명시 활성화하며 조회가 새 프로필/재화·빈 상태를 만들지 않는다. raw 비유한 관측은 gap의 double bits로 보존하고 canonical 원문으로 전체 동일성을 비교한다. 같은 거래에서 save→기존 DrivingLedger 정산→최초 결과 검증→provenance 저장/재조회→outbox ack를 처리한다. 실패·손상·출처 혼합·필수 쓰기 무갱신은 실패로 전달하고 원래 결과 재생은 현재 상태보다 우선한다. marker 없이 이력이 남은 고아 상태를 미활성으로 숨기지 않는다.

출발 당시 UTC/zone/rules와 원정산을 보존하고 현재 zone/날짜로 legacy 기록을 재해석하지 않는다. 금액은 기존 중앙 TrialDefinition의 drivingIncomeRules와 DrivingLedger를 재사용하며 일500km·출발한 날 기준을 바꾸지 않는다. 휴식 효과·미관측 구간을 합성하지 않는다. main178의 foreign 명령 STATE_CONFLICT·빈 checkpoint만 null인 조회와 RentalLedger Pending.parkedSince/엄격 조회를 보존했다. 실제 신호 수신·제품 수명 연결·플랫폼/정책 허용은 이 저장 공급의 완료와 별도다.
