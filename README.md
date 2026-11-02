# 드라이브 키우기

주행을 자동 기록하고 주차 후 게임의 성장과 수집 결과를 확인하는 Pleos Connect 차량용 앱을 개발하고 있습니다.

**현재는 개발 중입니다. 설치 가능한 APK와 실제 앱 대표 화면·시연 영상은 아직 없습니다.** 선형 차종 성장은 사용자 확정이며, 르르르·전투의 세부 규칙과 자산 사용 조건은 검토 중입니다.

## 대상 사용자와 해결할 문제

일상적인 주행을 돌아보고 게임의 성장·수집 경험으로 연결하려는 운전자가 대상입니다. 추가 주행이나 주행 중 조작을 유도하지 않고, 확인·꾸미기·보상 행동을 주차 후로 모으는 것을 목표로 합니다.

## 차량용 앱이어야 하는 이유

차량의 속도·기어·시스템 주행 제한 상태를 함께 사용해 기록과 화면 제한을 연결하려 합니다. 속도 0만으로 주차를 판단하지 않습니다. 차량별 데이터 지원 여부와 실제 앱 수신 검증은 남아 있습니다.

## 저장소 게시 범위

이번 초기 게시에는 문서, 차량 조사 데이터, 빈 공식 양식, 협업 양식, Gradle 기본 설정과 문서 검사 도구를 포함합니다. `app/src`와 `core/src`의 앱 구현·테스트 코드는 아직 게시하지 않습니다. 아래 구현 기록은 별도 로컬 개발 작업의 기록이며 이 저장소를 내려받아 같은 앱을 실행할 수 있다는 의미가 아닙니다.

## 로컬 구현 기록과 계획

| 항목 | 상태 |
|---|---|
| 주행 상태·거리·이벤트 계산·정산 중복 방지 | 순수 Kotlin 엔진 구현, 자동 테스트 13개 통과 |
| 홈·주행 제한·정산·차고 등 화면 표현 | 화면 클래스 작성·독립 컴파일 확인, 앱 연결 미완료 |
| Android SDK·Pleos 에뮬레이터 | 설치·부팅·차량 서비스 존재 확인 |
| 앱 저장·재실행·실제 차량 입력 연결 | 미구현 |
| 새 기획의 전투·가챠·시즌·퀘스트·음성·수집 | 검토·개발 대상, 구현 완료 아님 |
| APK·설치·시연·실차 | 미검증 또는 미준비 |

핵심 엔진의 현재 경험치 수치는 기술 검증용이며 새 기획의 성장표와 다릅니다. 운전 이벤트 점수는 검증된 안전성 지표가 아닙니다.

## 개발환경과 실행

JDK 17, Android SDK Platform 35·Build Tools 35.0.0이 필요합니다. Gradle Wrapper가 포함되어 있습니다. 일반 개발환경에서는 `ANDROID_HOME` 또는 개인 `local.properties`의 `sdk.dir`로 SDK를 지정합니다. 로컬 SDK·빌드 캐시는 커밋하지 않습니다.

문서 검사는 `python3 scripts/check_repository.py`로 실행합니다. 아래는 앱 소스와 로컬 도구가 있는 개발 작업 공간의 예시이며 `.local-tools/env.sh`는 저장소에 포함하지 않습니다.

```sh
source .local-tools/env.sh
export GRADLE_USER_HOME="$PWD/.local-tools/gradle"
python3 scripts/check_repository.py
./gradlew :core:test
```

아래 명령은 앱·핵심 소스를 추가한 뒤 실행합니다. 초기 문서·기본 설정 게시에서는 소스가 없어 CI의 Android 검사를 생략하며, 소스가 추가되면 자동 실행합니다. 이 생략은 앱 빌드 성공을 뜻하지 않습니다.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

APK 설치 명령과 다운로드 링크는 실제 산출물을 생성·검증한 뒤 추가합니다. Pleos 에뮬레이터 사용 방법은 [개발환경 검증 기록](docs/05_DEVELOPMENT_SETUP.md)을 따릅니다.

## 검증과 한계

핵심 로직 테스트는 주차 확인, 중복·역순·누락 입력, 중복 보상, 중단 복원, 재부팅 시각 처리를 확인합니다. 메모리 상태 복원 테스트는 디스크 저장 검증과 다릅니다. Pleos 부팅 성공은 앱 연동이나 실차 검증을 의미하지 않습니다. 상세 근거는 [검증·제출 상태](docs/06_VERIFICATION.md)에 기록합니다.

## 문서 안내와 읽는 순서

문서 파일명은 영어, 내용은 한글로 작성합니다. 처음 참여한 팀원은 **README → CONTRIBUTING → PRD → SYSTEM_ARCHITECTURE → VERIFICATION** 순서로 읽고, 조사·개발환경 문서는 해당 작업 때 참고합니다.

| 문서 | 이름의 뜻 | 담는 내용·읽는 시점 |
|---|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | 팀 기여·협업 가이드 | 문서 언어, 브랜치·커밋·PR, 검사, 사람 리뷰·머지·게시 절차. 모든 작업 전에 확인 |
| [01_PRD.md](docs/01_PRD.md) | Product Requirements Document · 제품 요구사항 | 무엇을 만들지, 확정·미정 범위, 차량 성장 단계·화면·보상 기획. 기존 제품·차량 게임 기획 통합 |
| [02_SYSTEM_ARCHITECTURE.md](docs/02_SYSTEM_ARCHITECTURE.md) | 시스템 구조·기술 계약 | 모듈 역할, 입력 데이터, 상태 전환, 계산·저장·UI 제한. 구현 전에 확인 |
| [03_GAME_BENCHMARK.md](docs/03_GAME_BENCHMARK.md) | 참고 게임 비교·분석 | 유명 방치형 게임 7개의 근거와 우리 게임 적용 제안. 외부 사실과 제안을 구분하며 확정 요구사항은 PRD에 기록 |
| [04_VEHICLE_RESEARCH.md](docs/04_VEHICLE_RESEARCH.md) | 차량 조사 | 조사 범위, 차종 분류, 출처·확인 한계·자산 권리. 차량 사실은 연결된 CSV에서 관리 |
| [05_DEVELOPMENT_SETUP.md](docs/05_DEVELOPMENT_SETUP.md) | 개발환경 구성 | Android·Pleos SDK와 에뮬레이터 설치·실행 방법 및 당시 검증 기록. 개발환경 준비 시 참고 |
| [06_VERIFICATION.md](docs/06_VERIFICATION.md) | 검증·완료 상태 | 실행한 검사, 실패·미검증 사항, 시연 조건·제출 준비 상태. PR·머지·제출 전 확인 |
| [07_REFERENCES.md](docs/07_REFERENCES.md) | 외부 참고 근거 | 유명·수상 레포에서 채택한 특징과 AI 지침 로딩 근거. 협업·구조를 바꿀 때 참고 |
| [공모전 자료 안내](docs/공모전/README.md) | 공식 공모전 자료 목차 | 일정·심사·접수, Pleos 개발자료, 차량 API와 공식 다운로드 안내 |
| [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) | AI 작업 진입점 | 같은 협업·제품·기술·검증 문서를 읽게 하는 지침. 공통 규칙을 중복 작성하지 않음 |

차량 목록의 기준 데이터는 [vehicles.csv](docs/data/vehicles.csv)입니다. PDF 기획서 원본은 로컬에만 보관하며 GitHub에 올리지 않습니다. 조사 문서의 제안은 팀이 결정하기 전까지 확정 기획이나 구현 완료를 의미하지 않습니다.

외부 이미지·캐릭터·로고의 사용 조건은 별도 확인 대상입니다. 현재 화면의 차량 도형은 임시 표현이며 실제 차종으로 주장하지 않습니다. 원격 저장소는 [Yaho03/drive-growth](https://github.com/Yaho03/drive-growth)입니다. 보호 규칙·CI·게시 상태는 [검증 기록](docs/06_VERIFICATION.md)에서 구분해 관리합니다.
