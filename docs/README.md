# 드라이브 키우기 문서 안내

앱 이름은 **르르르 렌터카**입니다. [프로젝트 README](../README.md)에서 실제 앱 화면과 APK를 확인할 수 있습니다.

## 심사 자료 읽는 순서

1. [프로젝트 소개와 APK](../README.md)에서 플레이 흐름을 보고, [05 실행 안내](05_DEVELOPMENT_SETUP.md#심사위원-실행-안내)로 시험 앱을 설치합니다.
2. [01 제품 요구사항](01_PRD.md)과 [09 화면 명세](09_UI_UX_SPEC.md)에서 차량용 조작 제한, 탁송, 차량 수집과 가게 성장의 설계를 확인합니다.
3. [06 검증 요약](06_VERIFICATION.md#현재-검증-요약--2026-11-15)에서 사용한 APK·입력 출처·검증 범위를 확인합니다. 이전 첫 차량 지급·재실행의 상세 근거는 [회귀 시험 기록](evidence/first-vehicle-isolation-20261115/README.md)에 있습니다.
4. [10 제출 소개 초안](10_SUBMISSION_DRAFT.md)과 [공식 제출 자료](contest/README.md)에서 제출용 설명과 공식 양식을 찾을 수 있습니다.

## 개발 자료 읽는 순서

[CONTRIBUTING.md](../CONTRIBUTING.md)와 [AGENTS.md](../AGENTS.md)에서 협업 절차와 작업 시작 범위를 확인합니다. [05 개발환경](05_DEVELOPMENT_SETUP.md)에서 빌드하고, [01 제품 요구사항](01_PRD.md) → [02 기술 계약](02_SYSTEM_ARCHITECTURE.md) → [09 화면 명세](09_UI_UX_SPEC.md) → [06 검증 기록](06_VERIFICATION.md) 순서로 담당 기능을 확인합니다. [08 계획·분담](08_PROJECT_PLAN.md)에서 현재 인계와 선행 작업을 확인합니다.

## 문서별 역할

| 문서 | 담당하는 내용 | 읽을 때 확인할 기준 |
|---|---|---|
| [01 제품 요구사항](01_PRD.md) | 게임 규칙, 기능 범위, 안전 조건, 향후 계획 | 제품 결정을 확인하는 기준. 구현·실행 여부는 06 참조 |
| [02 구조·데이터·상태 계약](02_SYSTEM_ARCHITECTURE.md) | 입력 출처, 원장과 저장, 정산·요청·복원, 앱과 코어 연결 | 현재 기술 계약과 버전별 공급 이력. 초기 스키마와 시험값에는 당시 기준 적용 |
| [03 방치형 게임 조사](03_GAME_BENCHMARK.md) | 비교 게임과 수집·성장·경제 제안 | 조사와 과거 제안. 채택된 제품 규칙은 01 참조 |
| [04 차량·자산 조사](04_VEHICLE_RESEARCH.md) | 차량 자료, 외형 참고, 실제 채택 파일의 출처와 사용 조건 | 확인된 출처 및 사용 조건과 남은 권리 확인을 함께 기록 |
| [05 개발환경과 실행](05_DEVELOPMENT_SETUP.md) | 도구 버전, APK별 빌드·설치·재실행, Pleos 환경 | 현재 실행 절차와 날짜가 붙은 설치·기동 이력 |
| [06 검증·시연·제출 상태](06_VERIFICATION.md) | 실행 결과, 커밋·APK 해시, 시험 제한 | 기록별 코드·빌드·장치와 입력 출처가 근거의 범위를 정함 |
| [07 외부 참고자료](07_REFERENCES.md) | 참고 프로젝트, 기술 조사, 협업 도구의 문서 | 비교·채택 판단의 출처 |
| [08 프로젝트 계획과 분담](08_PROJECT_PLAN.md) | 담당, 인계, 선행조건과 일정 | 현재 상태 안내와 과거 계획의 기록 시점을 확인 |
| [09 화면 흐름·디자인 명세](09_UI_UX_SPEC.md) | 탐색, 첫 실행, 가게·차고·탁송과 제한 상태 | 현재 화면 기준과 당시 시안·구현 이력이 함께 있음 |
| [10 제출 앱 소개 초안](10_SUBMISSION_DRAFT.md) | 공식 양식의 심사 자료에 옮겨 쓸 소개 | 제출 초안. 제출 조건과 실제 발송 상태는 공식 자료 및 06 참조 |
| [11 디자인 적용 이력](11_DESIGN_IMPLEMENTATION_STATUS.md) | 디자인 결정과 시안 적용 경과 | 날짜별 기록. 현재 화면은 09와 06 참조 |

## 자료 폴더

| 자료 | 내용 |
|---|---|
| [contest](contest/README.md) | 공모전 규정·공식 양식, Pleos 개발·차량 API 및 권한 조사 |
| [design-assets](design-assets/) | 화면 시안과 자산 작업 기록. [마을 브라우저 시안](design-assets/town-preview/README.md)은 별도 시안이며 실제 APK 캡처는 아래 증거 폴더에서 확인 |
| [evidence](evidence/) | 실행 APK의 캡처·로그·설치 해시. 이전 첫 차량 회귀는 [격리 시험 기록](evidence/first-vehicle-isolation-20261115/README.md), 통합 이후의 시험은 [06 검증 요약](06_VERIFICATION.md#현재-검증-요약--2026-11-15) 참조 |
| [proposals](proposals/) | 검토 중이거나 과거에 참고한 제안. [영업 경제 제안](proposals/b-economy/README.md)과 채택 규칙은 01에서 대조 |
| [data](data/) | 차량 조사 CSV와 데이터 자료. 실제 빌드가 소비하는 정의는 기술 계약과 앱 자산 참조 |
| [내비 진단 자료](../navi-diagnostic/README.md) | 내비 SDK 콜백 실험 도구와 기록. 제품 플레이 APK는 프로젝트 README에서 제공 |
