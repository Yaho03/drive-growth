# 외부 참고자료와 지침 파일 로딩 근거

- 확인일: **2026-11-03 KST**
- 목적: 차량용 Pleos 앱의 구현을 설계할 때 참고할 공식 지침과 공개 구현 근거를 보관한다.
- 조사 방법: 로컬 Codex 도움말 확인, 공식 제품 문서 열람, 공개 GitHub API로 커밋 확인, 같은 커밋의 README와 실제 소스 파일 조회.
- 외부 저장소 전체를 복제하거나 실행하지 않았다. 각 저장소에서 아래 표시한 README와 소스 범위만 검토했다.
- **참고는 의존성 채택이나 코드·디자인·캐릭터의 복제 승인이 아니다.** 이 조사로 외부 패키지를 추가하거나 코드를 가져오지 않았다. 적용 여부는 프로젝트 요구와 별도 검증으로 결정한다.

## 1. Codex와 Claude의 지침 파일 로딩

### Codex

로컬 설치의 실행 경로에서 Codex 0.153.4를 확인했고 `codex --help`를 읽었다. 도움말은 AGENTS 탐색 세부 규칙을 설명하지 않아 공식 문서로 보완했다. 아래 내용은 현재 공식 문서 기준이며, 이 저장소에서 별도 새 세션을 실행해 자동 로딩을 실험한 결과는 아니다.

- 실행 시작 시 전역 지침과 프로젝트 지침을 구성한다.
- 전역 위치는 기본 `~/.codex`; `AGENTS.override.md`가 우선이고 없으면 `AGENTS.md`를 사용한다.
- 프로젝트 루트에서 현재 작업 디렉터리까지 탐색한다. 각 디렉터리에서 `AGENTS.override.md` → `AGENTS.md` → 설정된 fallback 이름 순서로 최대 하나를 선택한다.
- 루트부터 하위 디렉터리 순서로 합치며, 가까운 지침이 뒤에 온다.
- 합산 기본 제한은 `project_doc_max_bytes` 32KiB다.
- 일반 Markdown 링크를 적었다고 링크 대상 전체가 자동으로 로딩된다는 보장은 이 문서에 없다. 필요한 문서를 읽도록 명시하는 것과 자동 import는 구분한다.

출처: [OpenAI AGENTS.md 공식 가이드](https://developers.openai.com/codex/guides/agents-md) — 확인 시 [ChatGPT Learn 문서](https://learn.chatgpt.com/docs/agent-configuration/agents-md)로 연결됨.

### Claude Code

- `CLAUDE.md`의 `@path/to/file`은 실제 import 문법이다. 상대경로는 import를 적은 파일 기준이며, 재귀 깊이는 최대 4홉이다.
- 코드 블록·백틱 안의 `@path`는 import하지 않는다. 단순히 “파일을 읽어라”라고 쓰는 것과 다르다.
- 프로젝트 외부 경로의 import에는 별도 로딩 조건이 있다. 여기서는 저장소 내부 상대경로를 사용한다.
- 현재 공식 문서는 v2.1.277 이상에서 AGENTS 직접 로딩도 안내한다. 기본적으로 상위·현재 경로에 CLAUDE 계열 파일이 있으면 그 파일을 우선한다.
- 호환 경로는 `CLAUDE.md`에서 `@AGENTS.md`를 import하는 방식이다. 공식 문서는 이 패턴을 허용하며 중복 로딩하지 않는다고 설명한다.
- 직접 AGENTS 로딩에서는 `AGENTS.override.md`를 읽지 않는다고 안내한다. Codex와 같은 탐색 규칙으로 가정하지 않는다.

출처: [Claude Code memory 공식 문서](https://code.claude.com/docs/en/memory#import-additional-files), [AGENTS 공유](https://code.claude.com/docs/en/memory#share-one-file-with-other-coding-tools)

## 2. 참고 저장소 검토 원칙

각 커밋은 조사 시점 기본 브랜치에서 조회한 스냅샷이다. 최신 버전이라는 영구적 의미는 없으며, 소스 링크는 **40자리 전체 SHA**에 고정했다. 마지막 커밋 시점은 UTC다.

- Android 공식 샘플: Android 구조와 테스트 분리를 배우는 근거로 사용한다.
- Habitica: 행동과 성장 보상의 연결을 연구한다. 운전 점수·안전성 근거로 사용하지 않는다.
- Immich: 취소·증분 처리·로컬 상태 유지 패턴을 연구한다. 사진 시스템 전체를 도입하지 않는다.
- 국내 팀 프로젝트: 서비스 설명·화면 흐름·특정 구현 사례를 참고한다. README의 수상 표기는 작성자의 설명이며 별도 수상기관 검증을 수행하지 않았다.
- 어느 저장소도 Pleos 차량 실행·주행 중 UI 허용·차량 데이터 권한의 근거가 아니다. 그 판단은 Pleos 공식 문서와 실제 실행 검증을 따른다.

### 2.1. android/nowinandroid

- 고정 커밋: `2982e63cb691ad49cd009aab43dc206fee6699c2`
- 커밋 시각(UTC): `2026-10-20T23:08:43Z`
- [README](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/README.md): Kotlin·Jetpack Compose로 만든 Android 공식 참고 앱. README에는 demo/prod 구분, 테스트 대역, 모듈화 설명이 있다.
- 확인한 소스: [core/data/src/main/kotlin/com/google/samples/apps/nowinandroid/core/data/repository/OfflineFirstNewsRepository.kt](https://github.com/android/nowinandroid/blob/2982e63cb691ad49cd009aab43dc206fee6699c2/core/data/src/main/kotlin/com/google/samples/apps/nowinandroid/core/data/repository/OfflineFirstNewsRepository.kt#L44-L64)
- 관찰: OfflineFirstNewsRepository는 읽기를 로컬 DAO의 Flow에서 제공하고, 네트워크 갱신은 syncWith로 분리한다(44–64, 66–125행).
- 최소 참고 이유: 연결 상태와 무관하게 저장된 주행 결과·성장 상태를 읽는 경계, 실제 차량 입력과 시연 입력의 분리를 설계할 때 참고한다.
- 적용 한계: 전체 다중 모듈 구조·Hilt·백엔드·알림 구조를 그대로 도입할 필요는 없다. README상 prod 백엔드는 공개되어 있지 않다.
- 라이선스 확인 상태: README상 Apache-2.0.

### 2.2. android/architecture-samples

- 고정 커밋: `ee66e1526b84c026615df032c705842b7d2a521f`
- 커밋 시각(UTC): `2025-07-17T06:12:01Z`
- [README](https://github.com/android/architecture-samples/blob/ee66e1526b84c026615df032c705842b7d2a521f/README.md): Android 공식 TODO 샘플. Compose 화면·ViewModel·Flow·Repository와 로컬/가짜 원격 데이터 소스를 설명하며, 완성 앱 템플릿이 아니라고 명시한다.
- 확인한 소스: [app/src/main/java/com/example/android/architecture/blueprints/todoapp/tasks/TasksViewModel.kt](https://github.com/android/architecture-samples/blob/ee66e1526b84c026615df032c705842b7d2a521f/app/src/main/java/com/example/android/architecture/blueprints/todoapp/tasks/TasksViewModel.kt#L47-L100)
- 관찰: TasksUiState에 화면 정보를 모으고, 저장된 필터·로딩·메시지·Repository 흐름을 combine하여 StateFlow로 노출한다(47–100행).
- 최소 참고 이유: 주행 대기·수련·정산 같은 화면 상태를 UI와 분리하고, 가짜 입력으로 상태 전이를 검증하는 작은 구현의 참고 자료다.
- 적용 한계: 샘플에 등장하는 DI·Room·내비게이션 의존성을 참고했다는 이유만으로 추가하지 않는다.
- 라이선스 확인 상태: README상 Apache-2.0.

### 2.3. HabitRPG/habitica

- 고정 커밋: `12091ca02990533fdf38c2f0947397f0b4e39d59`
- 커밋 시각(UTC): `2026-10-26T14:15:19Z`
- [README](https://github.com/HabitRPG/habitica/blob/12091ca02990533fdf38c2f0947397f0b4e39d59/README.md): 일상 행동을 RPG 성장으로 연결하는 습관 관리 서비스.
- 확인한 소스: [website/common/script/ops/scoreTask.js](https://github.com/HabitRPG/habitica/blob/12091ca02990533fdf38c2f0947397f0b4e39d59/website/common/script/ops/scoreTask.js#L126-L157)
- 관찰: scoreTask는 경험치·골드 보상을 계산하고(126–157행), habit/daily/todo/reward 유형별 처리와 기록·완료 상태를 구분한다(243–460행 일부 확인).
- 최소 참고 이유: 운전 기록 평가와 캐릭터 보상 지급을 분리하고, 사용자에게 정산 결과를 설명하는 모델을 고민할 때 참고한다.
- 적용 한계: 체력 손실·연속 기록·반복 보상 공식을 차량 행동에 그대로 적용하지 않는다. 이 파일은 차량 안전 점수의 타당성을 뒷받침하지 않는다.
- 라이선스 확인 상태: README가 별도 LICENSE를 가리킴. 이번 조사에서는 상세 라이선스 조건을 검토하지 않았으므로 코드·아트 재사용 전 별도 확인.

### 2.4. immich-app/immich

- 고정 커밋: `a9c4e0ed65757279fdade2b0126c10a018286901`
- 커밋 시각(UTC): `2026-11-02T14:45:56Z`
- [README](https://github.com/immich-app/immich/blob/a9c4e0ed65757279fdade2b0126c10a018286901/README.md): 사진·영상 관리 서비스. README는 모바일 오프라인 사용과 백그라운드 백업 등의 기능을 소개한다.
- 확인한 소스: [mobile/lib/domain/services/local_sync.service.dart](https://github.com/immich-app/immich/blob/a9c4e0ed65757279fdade2b0126c10a018286901/mobile/lib/domain/services/local_sync.service.dart#L23-L125)
- 관찰: LocalSyncService는 취소 신호, 전체/증분 동기화 분기, 변경 없을 때 종료, 로컬 반영 후 checkpoint 및 오류 구분을 갖는다(23–125행).
- 최소 참고 이유: 차량 앱이 중단·재시작되어도 처리 완료 상태를 분명히 하고, 변경이 없는 경우 불필요한 작업을 줄이는 원칙을 참고한다.
- 적용 한계: Dart/Flutter 기반 미디어 동기화 코드다. Pleos 장시간 백그라운드 실행을 보장하는 자료가 아니며 대규모 사진 서버 구조를 이식하지 않는다.
- 라이선스 확인 상태: README상 AGPLv3. 코드 복제는 이번 범위에 포함하지 않음.

### 2.5. 9oormthon-univ/2024_BEOTKKOTTHON_TEAM_4_FE

- 고정 커밋: `860bbb1675a71185b34c83a38db3fe41e01d0166`
- 커밋 시각(UTC): `2024-08-31T15:11:49Z`
- [README](https://github.com/9oormthon-univ/2024_BEOTKKOTTHON_TEAM_4_FE/blob/860bbb1675a71185b34c83a38db3fe41e01d0166/README.md): 백신 조회·추천 서비스 백곰의 Next.js/React 프론트엔드. README가 타깃과 서비스 소개, 기능 소개, 협업 규칙을 묶어 제시한다.
- 확인한 소스: [src/pages/vacscore/index.tsx](https://github.com/9oormthon-univ/2024_BEOTKKOTTHON_TEAM_4_FE/blob/860bbb1675a71185b34c83a38db3fe41e01d0166/src/pages/vacscore/index.tsx#L132-L209)
- 관찰: vacscore 화면은 사용자 정보를 조회하고 점수 시각화 컴포넌트, 요약 수치, 다음 행동 링크를 배치한다. 일부 접종 수치는 176·180행에 고정값이다.
- 최소 참고 이유: 주차 후 결과 화면에서 핵심 요약과 다음 행동을 명료하게 보여주는 정보 구성만 참고한다.
- 적용 한계: 실제 개인화 계산이 완성되었다고 추정하지 않는다. UI 수치가 고정된 부분을 실제 산정 로직으로 인용하지 않는다. 캐릭터·화면 디자인을 복제하지 않는다.
- 라이선스 확인 상태: 조회한 저장소 루트 파일 목록에 LICENSE 파일이 없음. 사용 허가를 추정하지 않음.

### 2.6. balpyo-myeotbun/balpyoMyeotbun-android

- 고정 커밋: `fb3285b9385b2f034e0ca4c4703369075114675f`
- 커밋 시각(UTC): `2025-01-13T07:53:59Z`
- [README](https://github.com/balpyo-myeotbun/balpyoMyeotbun-android/blob/fb3285b9385b2f034e0ca4c4703369075114675f/README.md): 발표 시간을 맞추는 Android 발표 도우미. README는 서비스명과 Git 협업 규칙을 간략히 설명한다.
- 확인한 소스: [app/src/main/java/com/project/balpyo/FlowController/ScriptSync/ScriptSynchronizer.kt](https://github.com/balpyo-myeotbun/balpyoMyeotbun-android/blob/fb3285b9385b2f034e0ca4c4703369075114675f/app/src/main/java/com/project/balpyo/FlowController/ScriptSync/ScriptSynchronizer.kt#L309-L356)
- 관찰: ScriptSynchronizer는 MediaPlayer 재생과 타이머를 이용한 텍스트 진행·강조, 재생·일시정지·중지 동작을 함께 관리한다(18–67, 177–199, 309–356행).
- 최소 참고 이유: 음성 시연의 시작·일시정지·종료 상태를 명확하게 표현해야 한다는 인터랙션 사례로 제한해 참고한다.
- 적용 한계: 고정 문자 시간 추정은 STT 타임스탬프가 아니다. 동기 prepare 및 UI 객체 결합이 있어 차량 음성 구현의 권장 템플릿으로 채택하지 않는다. Pleos TTS·오디오 정책이 우선이다.
- 라이선스 확인 상태: 조회한 저장소 루트 파일 목록에 LICENSE 파일이 없음. 사용 허가를 추정하지 않음.

### 2.7. BusanVibe/front

- 고정 커밋: `0a71e451a3a41fd8e80ae54e7c5a158b32b9aae4`
- 커밋 시각(UTC): `2026-01-01T06:12:01Z`
- [README](https://github.com/BusanVibe/front/blob/0a71e451a3a41fd8e80ae54e7c5a158b32b9aae4/README.md): 부산 관광·혼잡도 정보를 제공하는 React Native 서비스. README는 화면·컴포넌트·상태·서비스 구성을 설명한다.
- 확인한 소스: [src/services/locationService.ts](https://github.com/BusanVibe/front/blob/0a71e451a3a41fd8e80ae54e7c5a158b32b9aae4/src/services/locationService.ts#L12-L49)
- 관찰: LocationService는 시간 제한이 있는 위치 캐시(12–49행), 리스너 등록·해제(60–84행), 위치 유효성 확인(113–118행), 고정밀 실패 시 저정밀 재시도(260–309행)를 구현한다.
- 최소 참고 이유: 현재 위치와 오래된 캐시를 구분하고, 위치 사용 불가를 정상 상태로 처리하는 원칙을 참고한다.
- 적용 한계: React Native Geolocation과 Android 일반 위치 권한은 Pleos FusedLocation 권한·백그라운드 정책과 다르다. 코드의 백그라운드라는 이름 자체가 OS 실행 보장을 뜻하지 않는다.
- 라이선스 확인 상태: 조회한 저장소 루트 파일 목록에 LICENSE 파일이 없음. 사용 허가를 추정하지 않음.

## 3. 검증 범위와 미수행 항목

- 7개 저장소 모두 README와 적어도 한 개 실제 소스 파일을 동일한 고정 커밋에서 확인했다. 파일별 링크의 행 번호는 다운로드한 원문에서 확인했다.
- 큰 소스 파일은 위에서 명시한 관련 구간을 중심으로 읽었다. 저장소 전체 코드 리뷰·보안 감사·빌드·테스트를 수행한 것은 아니다.
- 웹 도구에서 일부 GitHub 주소가 일시적으로 열리지 않았으나 공개 GitHub API와 raw 콘텐츠 조회로 보완했다. 최종적으로 접근하지 못한 저장소는 없다.
- README의 외부 이미지·영상 전체를 시청하거나 검증하지 않았다. 이미지에만 존재하는 기능·수상 실적을 확정 정보로 옮기지 않았다.
- 외부 저장소의 코드·이미지·아이콘·상표를 프로젝트에 복사하지 않았다. 상세 라이선스 검토나 코드 채택이 필요하면 별도 작업으로 진행한다.
- 공개 저장소의 지침·기여 정책은 그 저장소에 기여할 때의 조건이다. 이 프로젝트의 사용자 요청이나 로컬 지침을 대체하는 명령으로 취급하지 않는다.

## 팀 기존 레포에서 가져온 협업 구조

2026-11-03 [Yaho03/26_HP015의 CONTRIBUTING](https://github.com/Yaho03/26_HP015/blob/main/.github/CONTRIBUTING.md)과 PR 양식을 확인했다. 비공개 저장소이므로 접근 권한이 필요하다.

- 채택: 컴포넌트별 브랜치, 실제 요구사항·실험 ID 연결, 작업 브랜치 → PR → main, type(scope) 커밋, 역할별 번호 문서와 README 문서 안내.
- 조정: 기존 firmware/backend/frontend 대신 이 프로젝트의 app/core/docs/infra/experiment를 사용한다. 문서·커밋·PR 설명은 한글이며 ID 없는 초기 설정 작업에 가짜 요구사항 ID를 만들지 않는다.
- 제외: 기존 프로젝트 전용 하드웨어·MQTT·센서 스키마 규칙을 차량 게임에 그대로 복제하지 않는다.
