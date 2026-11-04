# 드라이브 키우기

평소 이동을 탁송·차량 영입으로 연결하고, 주차 후 차량을 모으고 렌터카 가게를 확장하는 Pleos Connect 차량용 게임을 기획하고 있습니다.

**현재는 렌터카 게임의 설계·공통 개발 기반 준비 단계입니다.** 기존 기술 검증용 소스로 빌드·저장 시험을 재현할 수 있지만 새 탁송·렌터카 게임은 아직 구현하지 않았습니다. Debug APK는 로컬 시험 산출물이며 제출 Release APK·실제 게임 시연 영상은 준비 전입니다.

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
| 공통 거래 원장 | SQLite v1 어댑터·디스크 테스트 12개 통과. 게임 기능·화면 연결과 교차 검증은 미완료 |
| 개발 도구·APK | 공통 조합 빌드·Lint, Debug 설치·Activity 기본 실행 확인 |
| 새 화면 | 가게·차고·탁송·정산 [와이어프레임](docs/design-assets/wireframes.svg), #24 반영 [UI 시안 v3](docs/design-assets/ui-v3/)(세로 20·가로 10)과 2D Views 설계. 앱 적용 전 |
| 렌터카 게임·추가 기능 | 상세 구현 이슈로 진행. 정책·수치·일부 동작은 결정 대기 |
| 최종 APK·시연·실차 | 제출 산출물 미준비, 실차 미검증 |

## 개발환경과 실행

공통 적용값은 Kotlin Gradle Plugin **2.2.21**, AGP **8.10.1**, Gradle **8.11.1**, JDK **17**입니다. SDK 설치·환경 변수·Pleos 실행·팀원별 재현 확인은 [개발환경 기준](docs/05_DEVELOPMENT_SETUP.md)에 있습니다. 로컬 SDK·캐시·개인 `local.properties`는 게시하지 않습니다.

JDK 17과 문서에 명시된 Android SDK를 준비하고 `ANDROID_HOME` 또는 개인 `local.properties`의 `sdk.dir`를 지정한 뒤 실행합니다.

```sh
python3 scripts/check_repository.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

Android 저장 시험은 Pleos 시험 장치를 연결한 뒤 실행합니다.

```sh
./gradlew :app:connectedDebugAndroidTest
```

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
| [09_UI_UX_SPEC.md](docs/09_UI_UX_SPEC.md) | 화면 흐름·디자인 명세 | 주행 시 숨김·주차 후 복귀·실패 상태·디자인 제약 정리. 4화면 와이어프레임·초기 스타일 작성, 앱 적용·기기 검증은 미완료 |
| [10_SUBMISSION_DRAFT.md](docs/10_SUBMISSION_DRAFT.md) | 공식 양식용 제출 초안 | 심사용 1–8번 항목에 맞춘 문안, 미확정·실제 자료 상태. 최종 제출본 아님 |
| [공모전 자료 안내](docs/contest/README.md) | 공식 공모전 자료 목차 | 일정·심사·접수, Pleos 개발자료, 차량 API와 공식 자료·다운로드 보관 기록 |
| [OT 전체 요약](docs/contest/05_ORIENTATION_NOTES.md) | 온라인 설명회·질의응답 | 전체 35개 구간의 핵심 내용, 시각별 영상 링크, 기존 안내와의 차이. 기획·제출·SDK 조건 확인 시 참고 |
| [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) | AI 작업 진입점 | 같은 협업·제품·기술·검증 문서를 읽게 하는 지침. 공통 규칙을 중복 작성하지 않음 |

차량 목록의 기준 데이터는 [vehicles.csv](docs/data/vehicles.csv)입니다. PDF 기획서 원본은 로컬에만 보관하며 GitHub에 올리지 않습니다. 조사 문서의 제안은 팀이 결정하기 전까지 확정 기획이나 구현 완료를 의미하지 않습니다.

외부 이미지·캐릭터·로고의 사용 조건은 별도 확인 대상입니다. 현재 화면의 차량 도형은 임시 표현이며 실제 차종으로 주장하지 않습니다. 원격 저장소는 [Yaho03/drive-growth](https://github.com/Yaho03/drive-growth)입니다. 보호 규칙·CI·게시 상태는 [검증 기록](docs/06_VERIFICATION.md)에서 구분해 관리합니다.
