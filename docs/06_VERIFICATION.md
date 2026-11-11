## #53 탁송 독립 표시 UI 본인검증 — 2026-11-07

기준 main은 `b6631a44e80e56a6b86ea9bfc1790cefc4ea82c0`이며 전용 `app/53-delivery-ui` 브랜치에서 구현했다. `DeliveryView`·전용 표시 상태/입력 경계·문자열·debug 고정 입력과 시험만 추가했다. `MainActivity`, 탐색/gate, 차량 SDK, Manifest, Gradle, core/공통 계약·fixture·DB/경제 계산은 수정하지 않았다. 09의 #53 연결점 절에 입력·요청/응답·복원과 실제 통합 책임을 기록했다. 기존 기록은 보존했다.

환경은 macOS arm64, Temurin JDK17, 기존 Gradle Wrapper8.11.1·Android SDK35 및 기존 캐시다. 아래 자동 검사는 `JAVA_HOME`·`ANDROID_HOME`·`ANDROID_USER_HOME`을 기존 개발환경에 지정하고 `./gradlew --offline`로 실행했다. 서명키·로컬 비밀값은 새로 만들거나 기록하지 않았다.

| 구분 | 실행·결과 | 적용 범위 |
|---|---|---|
| 선행 실패 확인 | 입력 시험의 미구현 타입 참조 실패, 이어 View 계측 시험의 미구현 `DeliveryView` 참조 실패 확인 | 새 기능을 먼저 시험에 표현한 컴파일 실패이며 기존 제품 동작의 회귀 재현으로 확대하지 않음 |
| 원본 변경 결함 재현/수정 | 새 경계 13개 중 제출·복원 후 원본 후보 목록 변경 시험 1개 실패 → 요청 snapshot 수정 뒤 13개 모두 통과 | 보조 코드 검토에서 찾은 앱 입력 경계. 실제 SDK/DB 재현 아님 |
| 단위 시험 | `:core:test :app:testDebugUnitTest` 코어191개·앱84개, 실패/오류/생략0 | 앱 신규13개: 중복·명시적 응답·제한/해제·이전 응답·재생성 Pending·미지원·수익/구매 문맥·누락·활성/변경 기회·변경/포기 금액·확인 대기·외부 목록 변경 |
| APK·Lint | `:app:assembleDebug :app:lintDebug` 성공, Lint 오류0·경고17 | 기존 경고16개 및 프로그램 생성 전용 DeliveryView의 XML 도구 생성자 경고1개. XML inflate 지원을 새로 추가하지 않음 |
| 기기 시험 APK | `:app:assembleDebugAndroidTest` 성공 | DeliveryViewTest 6개 컴파일. 누락/제한·stale 버튼·늦은 응답·Pending 중복·구매 확인·검토 Back/금액 누락·고정 상태/320dp/긴 문구를 준비. **기기 실행 성공이 아님** |
| 저장소·diff | `python3 scripts/check_repository.py`, `git diff --check` 통과 | 관련 문서 로컬 링크와 허용 파일 범위 확인 |

검사한 제품/시험 소스 커밋은 `b9186bfd70a49153a6004217ca980515cd85f1ed`다. 이후 문서 커밋은 제품/시험 소스를 바꾸지 않는다. 빌드 APK SHA-256은 `ca5fb94cd7e3706e128f3654a102cdd84cd0485279ec24c6fbf00fa93c56ede7`, 기기 시험 APK는 `a265f2cd37d7be0f026e314f288b5eedbc021f3bf745797f4912a135ec043b04`다. **설치 APK 해시가 아니며 기기에 설치하지 않았다.**

공유 장치는 메인이 C 구현에 단독 배정했다. 이 작업은 **adb 조회·설치·재부팅·권한/차량/창 조작을 시작하지 않았으며** 제품 데이터·장치 설정을 변경하지 않았다. 장치 현재 건강 상태나 다른 채팅의 실행 결과를 이 작업이 직접 확인했다고 기록하지 않는다. 화면 캡처·실제 터치·공식 폼 팩터·CCP/실제 gate·SDK 목적지/거리·DB 선택/결제·프로세스 복원은 미수행이다.

기기 배정 후의 표적 실행 대상은 `io.drivegrowth.app.ui.delivery.DeliveryViewTest`다. 테스트는 View와 SIMULATION 고정 입력만 사용하며 DB를 읽거나 쓰지 않고 SDK/Activity를 시작하지 않는다. 실행 방식은 기존 [개발환경](05_DEVELOPMENT_SETUP.md)의 장치·서명 확인 및 계측 절차를 따른다. 그 실행만으로 실제 내비·저장 거래·주행 제한·호스트 화면 연결이 완료되지는 않는다. debug `DeliveryPreviewStates`로 정상/누락/실패/빈 후보/다수/긴 문구를 공급하고, `DeliveryRequest`의 전체 제안·UI 상관값과 실제 경제 requestId를 구분해 확인한다.

로컬 원본 로그는 `/private/tmp/delivery53-red.txt`, `delivery53-view-red.txt`, `delivery53-mutation-red.txt`, `delivery53-mutation-green.txt`, `delivery53-final-checks.txt`에 보존한다(두 번째 이후 파일도 같은 `/private/tmp/` 디렉터리). 독립 검토자는 09의 연결점과 새 소스/시험의 정확한 커밋을 기준으로 표적 검토한다. 본인검증·보조 코드 검토는 메인의 독립 검토 승인과 다르다. 최신 운영 지시에 따라 새 PR/원격 CI/머지는 독립검토 뒤 메인 조율을 기다리며, #53 전체 완료·실제 저장/도착 정산 성공으로 기록하지 않는다.


### #53 독립 검토·격리 장치 실행 근거 반영

위 본인검증·기기 미배정 기록은 당시 결과다. 이후 메인이 B 구현에 독립 검토와 단독 기기 슬롯을 배정해 원head `5b26c412acac8eef56414485685141e8b9e3d04b`를 검토·실행했다. A 구현은 B 채팅의 최종 인계와 아래 원본 근거를 직접 읽어 발췌했으며 **이번 반영 중 adb를 실행하지 않았다**. 별도 개인 교차검증 단계를 추가한 것이 아니라 게시 전 지정 검토다. B는 독립 표시 UI의 게시 차단 결함을 찾지 못했다고 보고했다. AI 검토이며 별도 사람 승인을 확보했다는 뜻은 아니다.

| 독립 확인·근거 | 실제 실행 범위 |
|---|---|
| 입력 경계 표적16개 | B가 원본13개와 추가3개(반환 frame/요청 목록 외부 변경·동일 버전 다른 내용/오래된 제한·중복 후보 ID)를 실행해 통과. A의 새 실행으로 합산하지 않음 |
| [원본 View6개+호스트1개](evidence/issue53-delivery-ui/device-tests.txt) | 격리 패키지에서7개 통과, 7.638초. 원본 `DeliveryViewTest`와 추가 네이티브 호스트 시험을 구분 |
| [보완 후 호스트1개](evidence/issue53-delivery-ui/host-final.txt) | 호스트 가용폭 제한·캡처 대기 보완 뒤9.231초 통과. 원본6개를 최종 호스트 APK에서 다시 실행했다고 표시하지 않음 |
| [320dp 구매 확인](evidence/issue53-delivery-ui/narrow-confirm.png), [936dp 구매 확인](evidence/issue53-delivery-ui/wide-confirm.png), [936dp 활성 의뢰](evidence/issue53-delivery-ui/wide-active.png) | 실제 네이티브 구매 확인 터치, 제한 중 늦은 응답에도 버튼 없음, 재허용 후 활성 내용. `1100`은 호스트 요청값이고 실제 CCP 가용 영역은702px/0.75=936dp. 좁은 영역은240px/0.75=320dp. 고정 SIMULATION 입력이며 SDK/거래 성공 아님 |
| [격리 호스트](evidence/issue53-delivery-ui/Host53Activity.kt)·[호스트 시험](evidence/issue53-delivery-ui/Host53DeviceTest.kt) | 같은 Activity 안의 **View 교체**에서 선택/Pending 보존과 요청1회 확인. Activity 자체 재생성·프로세스 복원 시험으로 확대하지 않음 |
| [소스/APK/환경·반환 metadata](evidence/issue53-delivery-ui/metadata.json) | 원본 app/core 추적 파일114개 대조에서 격리용 debug Manifest만 다름. 제품 main·core·원본 시험·debug 고정 입력은 동일하며 추가 호스트/시험은 격리용 |

기기는 Pleos2.1.0/API34/ARM64, ADB5038·emulator-5556/user10, 1080×600/120dpi다. 패키지는 `io.drivegrowth.verify53b`와 `.test`로 제품과 분리했다. 최종 호스트 앱 APK SHA-256 `a9367103785db78fd8ccf61fd821b2e3b9154a9730ad40659c2b783d2ddfde6a`, 시험 APK `7e824a998d0135f4d2e841ca95bd6489887ce11cf788cac515bb20cd0a95332a`는 빌드/설치 기록이 일치한다. 앞선7개 실행의 별도 APK 해시는 metadata의 `initialRun`에 보존했다. 최종 호스트/캡처 보완은 제품 소스 수정이 아니다.

B의 반환 체크는 제품 APK·제품 파일6개·권한·전역 설정·user10/boot23·빈 forward 전후 동일, 격리4패키지 제거, 내비 전면, 활성 instrumentation 없음이다. 반환 후 추가 adb를 하지 않았다고 보고했다. 이 종료 상태는 B의 당시 보존 근거이며 현재 장치 상태의 새 조회가 아니다. 게시 자료는 고정 화면/계측/호스트 코드와 최소 metadata만 담고 제품 DB·전체 제품 로그·파일 해시 목록·키/서명 경로·원시 위치는 제외했다. 주변 차량·계기 화면은 OEM 셸이며 제품 자산이 아니다.

게시 준비에서 main `085786a152ed8b4b534039ad3d0ccb3d5414f655`를 일반 merge했다. 06 충돌은 본인검증과 main 공통 선행조건 기록을 모두 보존해 해결했고 원head 대비 `app/src`·`core/src` 변경0을 확인했다. main 차이는 공통 선행조건 문서이므로 이 통합에서 전체 단위/장치 시험을 반복하지 않는다. 통합 후 저장소 검사(필수21·문서26·링크266)·diff 검사와 `:app:assembleDebug :app:assembleDebugAndroidTest` 소비 컴파일이 성공했다. 전체 단위·Lint·기기 시험은 위 원본 결과를 재사용하며 이번 통합의 신규 실행으로 합산하지 않는다. 원격 PR·정확한 최종 head CI는 해당 PR에서 관리한다.

체크 가능한 범위는 후보/상세/활성/변경 비교/포기 확인/누락·실패·제한·Pending·View 복원 **표시와 요청 경계**다. 최신 #53의 실제 표시 후보 발견 키·출처/버전 전달 및 #116 저장, #37–#39 명령/결제/변경/포기/도착·재실행, 실제 경제 requestId 영속 복원·MainActivity 탐색·실제 gate/CCP 조작·공식 전체 폼 팩터/실차는 남는다. 이슈 체크·종료·ready/Blocked by를 바꾸지 않으며 전체 경제 연결 완료로 기록하지 않는다.

## 전체 기능 공통 선행조건 정적 대조 — 2026-11-07

사용자 요청으로 main `b6631a4`와 열린 PR #111 `4f710e4`·#116 `d841f84`, #35~#58·#24·#25·#28·#80~#82의 본문/댓글·기준 문서·실제 공통 타입/fixture·SQLite/RentalLedger·앱 소비 코드를 대조했다. 이미 머지된 계산·단일 거래·호스트 연결과 PR 대기·미제공·정책/제품 결정 대기를 구분했다. 입출력/실패/저장 게이트는 [02](02_SYSTEM_ARCHITECTURE.md#전체-기능의-공통-계약과-저장-선행-작업), 책임/순서는 [08](08_PROJECT_PLAN.md#남은-공통-선행-작업과-병렬-연결-순서)에 반영하고 닫힌 기반의 완료·과거 실행 근거는 보존한다.

이번 작업은 **정적 대조·독립 누락/의존성 검토·저장소/링크/diff 검사**다. 제품 코드를 바꾸지 않았고 로컬 Gradle 시험·장치/adb·새 SDK 시험은 수행하지 않았다. 게시 후 자동 CI는 문서 PR에서 별도로 확인한다. 문서에 적은 후속 계약·검증 조건을 이미 구현되거나 실행된 결과로 간주하지 않는다. #111의 재제출 지적1 해결/두 P1 미해결과 #116의 세 수정 요청은 유지하며, 이슈 전체 완료나 실제 지급·복원·정책 승인으로 확대하지 않는다. 문서 PR의 정확한 head·검사와 이슈 반영 결과는 해당 PR에서 관리한다.

## PR #117·#118·#119 검토와 인계 — 2026-11-07

- #117 수정본 `84c53e1`은 도착 이벤트의 경로 상관 근거가 부족하면 확인 대기·미지급이라는 기존 계약을 명시하고, 공개 발췌/화면/manifest/진단 patch와 담당자 보고를 구분했다. 독립 코드 APPROVE·구조 CLEAR와 증거 대조를 확인해 main `6ed7c87`에 반영했다. 공개 증거 검증은 새 SDK 실행이 아니며 #80 전체는 미완료다. 기기 화면의23:26과 로그16:26을 동일 시계로 표현한 비차단 문구는 이 문서에서 바로잡았다.
- #119 `22c3281`은 시험 입력의 휴식 분류·출발 이벤트·남은 기간 계산만 제공한다. 독립 표적15개와 main `fe329b1` 조합 코어191개 통과, 코드 APPROVE·구조 WATCH(실제 연결 조건 유지)로 검토해 main `afe2822`에 반영했다. 실제 위치/운전·주차 증거, 상태·이벤트 원자 저장, 정차 번호/출처/규칙 복원, 기간 delta의 중복 소모 방지와 수익/음성/화면은 #40·#45·#57·#81에 남는다. `state.copy()` 시험을 DB 재개방으로 표시하지 않는다. 미확인 정차는 처리 번호가 전진하므로 같은 번호의 지연 근거 재평가는 지원하지 않는다.
- #118 원본 `1901bc0`은 실제 View/탐색/gate와 공통 정산 열람 저장·재조회, 초기화 미지원 표시를 독립 검토했다. 코드 APPROVE·구조 CLEAR, 표적JVM14개 통과, 정확 원본 격리 APK의 실제 장치3개 통과(29.642초)를 확인했다. main `fe329b1` 조합 JVM14개·기기 시험 Kotlin 컴파일은 별도 근거다. 작성자의 제품 D/P·권한·Back 근거는 아래 원본 로그/화면과 대조해 재사용했고 이번 장치 실행 수로 합산하지 않는다. 제품 APK793e1db…bd9·기존 파일6개 해시와 권한은 보존, 사용한 격리 패키지만 제거했다. 신규 판별·첫 지급/무료권·전체 영업 history·관리 명령·강제 프로세스 종료의 상세 선택 복원·공식 폼 팩터/실차는 완료가 아니다.

최종 main `afe2822`와 #118을 합친 전용 작업 폴더에서 코어191개·앱71개가 통과했다(실패·오류·생략0). APK·기기 시험 APK·Lint 성공(오류0·경고16), 저장소·diff 검사도 통과했다. 이 통합 단계는57초에 종료했으며, #118의 검증 대상 소스/시험11개 파일은 원본 `1901bc0`와 바이트 단위로 동일하다. README 충돌은 최신 작업표와 실제 연결 설명을 함께 보존해 해결했다. 최종 통합 APK를 장치에서 다시 실행한 것은 아니다.

이 기록은 AI 보조 검토와 사용자 지시에 따른 범위 인계이며 별도 사람 승인을 추가 확보했다는 뜻이 아니다. 기존 실행·실패·원복 근거를 보존하며 #40·#51·#54·#56·#80 및 #28 전체 종료로 확대하지 않는다.

## PR #111–#115 수정본 재검토와 main 반영 — 2026-11-07

#112 `1cb940a`·#113 `5145eec`·#114 `4f22ce5`·#115 `ffd191b`의 이전 수정 요청을 재검토했다. 각 PR의 독립 코드 검토와 구조 검토는 APPROVE/CLEAR이며 AI 보조 결과다. 별도 사람 승인을 추가 확보한 것으로 표시하지 않는다. 사용자 지시에 따른 머지와 잔여 범위는 각 PR의 재검토에 기록했다.

| PR | 이번 직접 표적 재검증 | main 반영과 남은 범위 |
|---|---|---|
| [#112](https://github.com/Yaho03/drive-growth/pull/112) | 탁송 입력/불변성/금액 경계 62개 통과 | `3a3ece9` 반영. 후보·선택 계산이며 활성 의뢰/결제 영속 거래·재실행은 남음 |
| [#113](https://github.com/Yaho03/drive-growth/pull/113) | 레벨업 명령 재생/금액/활동 실패 24개 통과 | `ec91023` 반영. 보관 명령 재제출 계약이며 두 개체 재료 소비·프로세스 복원·최종 점유 검사는 남음 |
| [#114](https://github.com/Yaho03/drive-growth/pull/114) | 후보 불변성/단일 지급 경계 14개 통과 | `7bddb87` 반영. 신규로 확인된 프로필의 명령이며 실제 신규 판별/legacy/무료권/제품 연결은 남음 |
| [#115](https://github.com/Yaho03/drive-growth/pull/115) | 콘텐츠 로더/이전 실패 재현 14개 통과 | `1f23020` 반영. 채택 20종·가격 비율·해금/버전을 공급. 유형·시설/퀘스트·최종 경제 값·실제 assets 읽기 시험은 남음 |

직접 통합 검사 환경은 macOS arm64, Temurin JDK17, 기존 Gradle Wrapper8.11.1 및 Android SDK35다. main `4e8ab1a`에 네 수정본을 합친 코드에서 `:core:test` 170개·`:app:testDebugUnitTest` 62개가 통과했다. 동시에 다른 작업에서 머지된 #107을 보존해 main `7bddb87`과 같은 코드 트리로 맞춘 뒤 코어176개·앱62개가 통과했다(각 실패·오류·생략0). `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`, 저장소·diff 검사도 성공했고 Lint는 오류0·경고16이다. 마지막 코드 회귀는 36초에 종료했다. 실제 장치·adb는 사용하지 않았으며 과거 장치 시험을 새 head 실행으로 표시하지 않는다. #114의 `List.of`는 현재 APK에서 D8 backport 변환을 확인한 근거이며 API28 기기 실행 성공이 아니다.

[#111](https://github.com/Yaho03/drive-growth/pull/111)은 `4f710e4`에서 동일한 보관 계획 재제출 경계는 해결됐다. 그러나 같은 요청 ID·다른 카운터에 최초 정산과 새 결과가 섞이는 문제, 마지막 한 칸에 서로 다른 요청이 모두 저장되는 문제가 다시 재현돼 [수정 요청](https://github.com/Yaho03/drive-growth/pull/111#pullrequestreview-5811300082)을 유지했다. CI 성공이나 남은 문제의 문서 공개를 해결로 처리하지 않으며 main에는 머지하지 않았다. 공통 명령/저장 확장과 카운터·무료권의 원자 처리는 #47에서 추적한다.

#35·#37·#48·#50에는 반영된 부분과 실제 저장/제품 연결의 잔여 범위를 구분했다. #38·#39에는 #112에서 제공한 typed 후보/선택 결과의 차단만 해제해 고정 시험 입력의 독립 계산을 시작할 수 있게 했다. 실제 SDK·영속 거래·미정 정책의 완료 조건은 유지하며 이 변경을 전체 이슈 또는 #28 통합 완료로 보지 않는다. 이전 실패·장치·머지 근거는 보존한다.

# 검증·시연·제출 상태

## 검증 운영 기준 변경 — 2026-11-06

사용자 결정으로 별도 묶음 교차 검증을 PR 검토에 통합했다. 구현자 본인 검증은 유지하며, 기능별 미완료 시험은 해당 구현 이슈, 기능 간 연결과 전체 회귀는 #28에서 추적한다. 이전 버전의 실행 결과·실패·미검증 기록은 보존한다. 아래 과거 기록의 #63–#68·#71 인계·배정은 당시 절차이며 새 대기 조건이 아니다. 현재 책임·순서는 [협업 규칙](../CONTRIBUTING.md#구현-이슈의-검증-책임)과 [진행 계획](08_PROJECT_PLAN.md#기능별-검토와-통합-검증)을 따른다. 이번 절차 변경으로 앱 실행이나 검증 통과를 추가하지 않았다.


기준일: 2026-11-03 KST. 이 문서는 실제 수행 결과와 남은 검증의 기준이다. 제품 결정은 [01_PRD.md](01_PRD.md), 계산 규칙은 [02_SYSTEM_ARCHITECTURE.md](02_SYSTEM_ARCHITECTURE.md)에서 관리한다.

## #46 Garage 조회 경계 수정 검증 — 2026-11-07

PR #100의 두 결함을 최신 main `c57cb32`와 통합한 전용 worktree에서 확인했다. 환경은 macOS arm64, Temurin JDK 17.0.6, 프로젝트 Gradle Wrapper 8.11.1이다. 카탈로그 원본 비우기·차종 추가와 활동 기록 누락의 회귀 테스트를 먼저 실행해 Garage 9개 중 기존 6개 통과·추가 3개 실패를 확인했다. 수정 후 `./gradlew :core:test --no-daemon`은 86개 통과, 실패·오류·생략 0이다. Garage는 기존 6개와 추가 9개, 총 15개다.

추가 검증은 카탈로그 생성 당시 기준 유지, 활동 누락·명시적 `DATA_UNAVAILABLE`·부분 후보 계산 뒤 `STORAGE_FAILURE`, 개체별 활동 분리, 반환 목록·도감 입력의 변경 격리, 빈 입력·처분 개체의 callback 미호출, 예상하지 못한 callback 오류를 숨기지 않는 경계를 확인했다. 활동 공급자는 `ContractResult<VehicleActivity>`로 예상 가능한 실패를 전달한다. 저장소 검사와 `git diff --check`도 통과했다. 원격 head·CI 결과와 검토 기록은 PR #100에 남긴다.

입력은 시험용 콘텐츠·메모리 저장소·직접 주입한 활동/도감 키다. 로컬 앱·에뮬레이터·SQLite·실차 시험은 수행하지 않았다. 영업 쉬기·실제 활동 저장·도감 영속 입력·새 공통 개체 필드는 연결하지 않았으며 #46 전체 완료 근거가 아니다. 기능 간 연결은 #28, 플랫폼 시험은 #80·#81·#82의 기존 범위를 따른다.

## #46 공통 개체 정보 계약 확장 — 2026-11-07

PR #100이 포함된 main `e7a1bcca218f89f870e9c342ecb56c39cafa48bf`에서 전용 worktree를 만들었다. #46의 공통 계약 조율 댓글과 PRD의 개별 희귀도·진행량·실제 취득액 결정을 대조했고, 타입·명령·fixture 및 필요한 소비자만 변경했다. 필드·단위·오류·호환과 저장 담당 연결점은 [기술 문서](02_SYSTEM_ARCHITECTURE.md#46-개체-정보-v1과-공통-소비-예제)에서 관리한다. Garage 전체·가챠/강화/영업 기능 완료가 아니다.

환경은 macOS arm64·Temurin JDK17·프로젝트 Gradle8.11.1·기존 Android SDK35/Build Tools35이며 SDK 경로는 환경변수로만 전달했다. 에뮬레이터·adb·기존 앱/DB 데이터는 사용하거나 변경하지 않았다.

| 검사·입력 | 실제 결과와 적용 범위 |
|---|---|
| 변경 전 `:core:test :app:testDebugUnitTest` | core86개·app4개 통과. 기존 Garage15개 포함 |
| 회귀 테스트 선실행 | 신규2개 모두 실패: fixture 반환 목록 외부 수정, 처분 중 레벨 변경 허용을 재현 |
| 필드·명령·메모리 fixture 및 소비 예제 | core97개 통과, 실패·오류·생략0. 기존86개 유지+`OwnedVehicleContractTest`11개. Garage15개 유지 |
| 경계값 | 레벨1..20·진행량0..99·최대레벨진행량0, 음수금액과 잘못된 첫 지급 거절. 무료0·할인250·유료500·구매탁송800 및 같은 차종의 서로 다른 희귀도/성장값 확인 |
| 생성·성장·처분·재시도 | Garage 조회에 새 필드 보존, 각 취득 필드/진행량만 다른 같은 request 충돌, 오래된 revision 거절, 성장/처분 뒤 과거 정산 snapshot 재생, 처분 중 성장값 변경은 금액·차량 모두 미반영 |
| 입력·반환 방어 | fixture 원본 목록 변경 격리와 반환 목록 수정 거절. 불변 차량·정산 값 보존 |
| app 단위 테스트 | 4개 통과, 실패·오류·생략0. SQLite 기기 실행 시험은 아님 |
| `:app:assembleDebug :app:lintDebug` | 성공. Lint 오류0·경고11(기존 대상API·백업/아이콘·View 생성자·draw 할당 관련) |
| `:app:assembleDebugAndroidTest` | SQLite 장치 시험8개 추가를 포함해 시험 APK 컴파일 성공. 재개방/동일성/당시 정산 재생/legacy 누락·명령 차이·원본 보존 사례는 **실행하지 않음** |

실제 최종 명령은 `./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`다. 근거는 `core/build/test-results/test/`, `app/build/test-results/testDebugUnitTest/`, `app/build/reports/lint-results-debug.txt`다. 저장소·공백 검사는 `python3 scripts/check_repository.py`, `git diff --check`로 확인한다.

SQLite에는 새 필드를 손실시키지 않는 최소 JSON/Update 대응만 반영했다. 기존 schema1 차량 JSON은 새 필드가 없으면 `STORAGE_FAILURE`/내부 `INVALID_RECORD`, 옛 차량 명령의 새 필드 포함 재시도는 `STATE_CONFLICT`가 될 수 있다. 없는 희귀도·취득액을 COMMON·0으로 보정하지 않으며 DB 버전 증가·자동 이전·자동 초기화·삭제를 제공하지 않는다. 처분 이력/발견 기록 조회·저장과 이전 전략은 저장 담당의 후속 구현·실제 재개방 검증이 필요하다.

**최초 인계 시점의 검증 구분:** 공통 계약·메모리 동작과 기존 소비자 컴파일은 확인했다. SQLite 장치 실행·앱 재실행·실제 지급/활동·다중 차량 강화·도감 영속 일치는 미검증이다. 기존 #33·#34와 PR #100의 과거 완료를 이 새 확장의 실제 저장 성공으로 바꾸지 않는다. 구현자 본인 검증 → PR 검토 → #28 기능 간 통합 검증의 현행 절차를 유지하며 #46 전체 종료·후속 라벨·차단 관계를 변경하지 않는다.

### PR #104 차량 숫자 decoder 수정 — 2026-11-07

검증 A가 기존 head `d157bb9`에서 원본 SQLite20개 통과, 보완5개 중2개 실패를 보고했다. 실제 실패 로그와 시험 코드를 직접 대조했다. `levelProgressPercent=4294967297`은 getInt에서1로 래핑되고 `acquisitionCost=350.75`는 getLong에서350으로 절삭되어 모델 검증을 통과했다. 이 보고는 위 최초 구현자의 컴파일 확인과 구분한다.

차량의 level·revision·진행량·취득액을 원시 JSON Int/Long에서 범위 검사한 뒤 변환하도록 고쳤다. 정산의 차량 snapshot도 같은 decoder다. 문자열·실수(1.0 포함)·지수 표기·범위 초과를 정상값으로 보정하지 않는다. 기존 무료0과 정상 정수는 유지한다. 정식 `SqliteGameChangeStoreTest`에 회귀6개를 추가했다. 원시 JSON 텍스트를 직접 주입하여 재직렬화의 정규화를 피하고 차량/정산의 조회·변경/재시도 실패, 원본 바이트 보존, 정상 경계·무료0·롤백·과거 정산 재생을 확인한다.

로컬 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`는 성공했다. core97개·app4개 실패/오류/생략0, Lint오류0·기존경고11이다. 저장소 검사와 `git diff --check`도 통과했다. JDK17·기존 SDK 사용, 임시 독립 사본의 장치 패키지만 격리했으며 제품 applicationId·서명 설정은 변경하지 않았다.

메인의 #105 장치 종료·순서 확인 후 ADB5038/emulator-5556/user10에서 실행했다. 제품 패키지는 유지하고 임시 독립 사본의 `io.drivegrowth.verify104numeric`만 설치했다. red는 기존 decoder+정식 신규 시험, green은 수정 decoder+동일 시험이며 소스 해시와 설치 APK SHA-256을 대조했다.

| 실제 장치 실행 | 결과 |
|---|---|
| 수정 전 두 재현 시험 | 2개 실행·실패2·오류0. 진행량4294967297→1, 취득액350.75→350으로 잘못 수용됨을 재현 |
| 수정 후 `SqliteGameChangeStoreTest` | 기존20+신규6, **26개 통과**, 3.61초. 실제 Android JSON의 Int/Long/Double 형식 확인 |
| 손상 숫자 경계 | 차량/정산 각각 42종 원시 숫자·형식 입력의 조회 및 변경/재생 거절, INVALID_RECORD와 원본 DB 바이트 보존 |
| 정상·기존 동작 | 무료0·Long.MAX_VALUE·진행99·최대레벨, 재개방 후 조회·이전 정산 재생·revision충돌·동시성·원자성·무료 개체 성장 실패 롤백 유지 |
| 종료·보존 | 새 격리 앱/시험 패키지 제거, 고유 시험DB 정리. 제품 설치 APK 해시·경로 유지, 제품 데이터 삭제 없음. user10·1080×600·120dpi·night no·forward빈목록 유지. active instrumentation 없음·AVD 켜둠 |

장치 앱 APK SHA-256은 red `d6ea954a4ed8a42c7ca4ff35287d9d7bbe75e22bca5039bb788cc04f2b31b0cb`, green `70902dfb3c7d8a3efa59f81a78c3a1685b272a7dab60b2a711d0b5b04a3f8881`이고 공통 시험 APK는 `f26a03df9d267e51c5befa8051cdab41a8e46edd2f8e607232e57ded15e06b0b`이다. 근거 로그는 `red-two.log`, `green-all-26.log`, 소스·APK 대조는 `build-metadata.json`으로 보존했다. 메인에 장치 사용 종료를 전달했다.

환경 한계도 분리한다. CarService 존재 조회는 성공했으나 종료 시 전체 dump 말미에 `DUMP TIMEOUT (10000ms)`가 관측됐다. SQLite26개는 정상 종료했으며 이를 CarService·차량 입력 환경 정상으로 해석하지 않는다. 차량 브리지 복구·기어/권한/창/포워딩 변경·재부팅은 하지 않았다. 이 시험은 고유 시험DB의 재개방 검증이며 **기존 제품 DB migration·제품 앱 재실행·도감 영속·다중 개체 거래는 여전히 미검증/미구현**이다. 위 최초 인계 기록과 후속 범위를 전체 완료로 바꾸지 않는다.

## 현재 공통 개발 기반 검증 — 2026-11-04

사용자가 준비 작업을 진행하도록 요청한 범위에서 KGP 2.2.21·AGP 8.10.1·Gradle Wrapper 8.11.1(공식 배포 SHA-256 포함)·JDK 17 조합을 실제 설정에 적용했다. 검증 대상은 기존 로컬 선행 소스이며 렌터카 제품 기능 완성본이 아니다. 아래 결과는 이전 실패·단독 컴파일 기록을 지우지 않고 현재 상태를 갱신한다.

| 확인 | 실제 결과·범위 |
|---|---|
| `./gradlew :core:test :app:assembleDebug :app:lintDebug --stacktrace` | BUILD SUCCESSFUL. 핵심 JUnit 테스트 13개, 실패/오류/생략 0. Debug APK 빌드와 Android Lint 완료 |
| Android Lint | 오류 0·경고 12. 대상 API, 도구 최신 버전 안내, 백업 속성, draw 중 할당, 아이콘·View 생성자 경고를 확인했다. 경고를 숨기거나 렌터카 품질 검증 완료로 바꾸지 않음 |
| `./gradlew :app:connectedDebugAndroidTest --stacktrace` | Pleos Emulator 2.1.0/API34에서 기존 SnapshotStoreTest 8개 통과, 실패/오류/생략 0. 필드 보존·null·중복 정산·손상/버전 오류·중단 복원·전체 교체·비유한 샘플 저장 검증 |
| APK 설치·기본 실행 | `adb -s emulator-5556 install -r ...` 성공. 활성 사용자 10의 CAR_SPEED 권한 허용 후 MainActivity COLD 실행 `Status: ok`, topResumedActivity가 앱인 것 확인. 전체 게임 흐름/화면 가독성 시험 아님 |
| 차량 권한 경계 | CAR_POWERTRAIN을 `pm grant`로 변경하려는 시험 명령은 변경 불가 권한 타입으로 거절됐다. 권한 우회 없음. 이 결과가 차량 신호 수신 실패 또는 성공을 확정하는 것은 아님 |
| 크래시 구분 | 기본 실행 확인 시 해당 앱의 FATAL EXCEPTION 기록은 발견하지 못했다. 에뮬레이터 DrivingView의 별도 native crash가 있어 장기 안정성·플랫폼 전체 정상으로 판정하지 않음 |
| 팀 재현 | 이 컴퓨터 arm64·Temurin 17.0.6+10·SDK35/Build Tools35·Pleos API34의 결과. 다른 팀원 3명의 환경·실행은 미확인 |

APK는 `app/build/outputs/apk/debug/app-debug.apk`, 크기 2,564,962 bytes, SHA-256 `ce46ae78dc4995d20062da4779d905da7ebf8e75e65a3d6ef25c71ed04a93845`다. Debug 개발 확인용이며 제출 release APK가 아니다. 자동 생성 파일과 테스트 로그는 개인 정보·로컬 경로가 포함될 수 있어 원본을 그대로 게시하지 않는다. 소스 커밋과 최종 APK의 대응은 게시 버전에서 별도 기록한다.

로컬 근거: `/tmp/drive-environment-check.log`, `/tmp/drive-environment-instrumentation.log`, `core/build/test-results/test/TEST-io.drivegrowth.core.DriveEngineTest.xml`, `app/build/outputs/androidTest-results/connected/debug/` 및 `app/build/reports/lint-results-debug.xml`. 이 경로는 이 컴퓨터의 결과이며 저장소를 내려받은 팀원이 이미 갖고 있는 파일이 아니다.

### 남은 검증과 게시 해석

- 기존 소스의 DO 선언·dataSync 서비스·주행 중 표시 정책은 #13에서 다시 검토한다. 컴파일·설치·테스트 성공은 게임의 백그라운드 기록·음성 허용을 입증하지 않는다.
- #20의 현재 코드 실행 근거로 핵심·저장 테스트를 사용할 수 있으나 실제 쓰기 실패 주입·주행 중 종료/재진입·차량 권한 거부·수신 타이밍·실차는 이번에 시험하지 않았다. SnapshotStore의 전체 파일 저장 시험과 Runtime의 메모리/디스크 실패 복원 통합 시험을 구분한다.
- 렌터카 거래·탁송·영업·보유 개체·가게의 새 규칙과 계약은 별도 구현·#28 통합 검증이 필요하다. 기존 시험용 XP/이벤트 계산이 현재 게임 규칙이라는 뜻은 아니다.
- 공통 소스가 게시되면 원격 CI에서 Android 소스 포함 조건을 만족하므로 core 테스트·APK 빌드·Lint가 실행되어야 한다. 현재 로컬 성공을 원격 CI 성공으로 미리 기록하지 않는다.

## OT·Pleos 조사와 개발 기반 문서 게시

2026-11-03 사용자가 이번 문서 보완을 **PR 없이 `main`에 직접 반영**하도록 명시적으로 요청했다. 범위는 OT 전체 요약, 공식 개발 자료·주행 정책 조사, 이에 따른 기획·설계·검증 계획, 호환성을 고려한 개발 기반·권장 버전 설명의 Markdown 13개다. 앱·테스트 소스, APK, 원본 PDF, 개발 도구·캐시와 빌드 설정 변경은 포함하지 않는다. 문서에 선택한 권장 버전은 실제 설정에 아직 적용하지 않았다.

이번 직접 반영은 사용자 지시에 따른 예외이며 일반 PR 절차를 바꾸지 않는다. 별도 사람 리뷰 승인은 없고 AI 검토는 사람 승인으로 기록하지 않는다. 게시 대상만 복사한 최신 원격 기반 작업 폴더에서 문서·링크·차이 검사를 수행한다. 해당 원격에는 `app/src`·`core/src`가 없어 Android CI는 기존 조건에 따라 생략하며, 이를 앱 빌드 성공으로 해석하지 않는다. 각 조사 행의 미게시·미실행 표기는 당시 기록이며, 이번 게시의 실제 반영·CI 상태는 커밋과 GitHub Actions 결과로 확인한다. 원격 이슈 수정·공식 문의 발송은 이번 범위에 포함하지 않는다.

## 최초 게시와 검증 범위

2026-11-03 문서 구조와 게시 범위를 설명한 뒤 사용자가 08·09번 문서의 작성 틀 추가와 PR 머지를 새로 명시적으로 요청했다. 이번 범위는 문서·기본 설정이며 PDF와 앱 구현·테스트 소스는 제외한다. 기존 PR #2에 폴더 정리·다운로드 기록 통합·작성 틀을 반영하고 원격 검사 통과 후 머지한다. 별도 팀원의 승인은 받지 않았으며 AI 검토를 사람 승인으로 기록하지 않는다. 이번 초기 게시의 머지는 사용자 지시에 따른 예외이고 일반 작업의 사람 리뷰 원칙은 유지한다. 실제 머지·원격 검사 상태는 PR #2와 GitHub Actions 기록에서 확인한다.

아래 앱 관련 결과는 이전 로컬 작업의 시점별 기록이다. 현재 작업 공간에서 개발이 계속되고 있으므로 최신 구현 상태나 이번 원격 게시의 재현 결과로 해석하지 않는다. 이번 게시 검증은 게시 대상 파일만 분리한 상태에서 문서·링크·설정 검사를 수행한다.

## 수행 결과

2026-11-03 로컬 도구 추가 준비: Gradle 8.11.1 배포 체크섬·Java 17 실행 확인, 독립 확인 폴더의 KGP 2.2.21·AGP 8.10.1 해석 및 `help` 성공, Android Studio Rabbit 2 설치·최종 macOS 서명 검사 통과. IDE 최초 실행은 하지 않았다. 제품 빌드 설정과 앱 소스는 그대로이며 도구 설치를 제품 앱 빌드 성공으로 해석하지 않는다. 설치 위치·기존 적용 버전과의 차이는 개발환경 문서의 로컬 설치 확인 절을 따른다.

| 대상 | 근거 | 결과·한계 |
|---|---|---|
| 핵심 엔진 | `./gradlew :core:test`, `core/build/test-results/test/TEST-io.drivegrowth.core.DriveEngineTest.xml` | 13개 통과. 파일 저장·앱 실행·실차 검증 아님 |
| 화면 클래스 | Kotlin 2.0.21 컴파일러와 API35 android.jar로 독립 컴파일 | 성공. 화면·입력 실제 실행 미검증 |
| Android/Pleos 도구 | [05_DEVELOPMENT_SETUP.md](05_DEVELOPMENT_SETUP.md) | SDK 설치·Pleos API34 부팅·차량 서비스 존재 확인 |
| 새 기획서 수치 | 단계별 필요 EXP 독립 합산 | 표의 누적 EXP 일치. 실제 성장 페이스·리텐션 검증 아님 |
| 저장소 검사 | `python3 scripts/check_repository.py` | 이번 게시 파일만 분리해 실행: 필수 파일 17개·문서 17개·로컬 링크 48개 통과. PDF는 제외하고 참조를 로컬 보관 설명으로 변경 |
| 로컬 문서 재정리 | `python3 scripts/check_repository.py` | contest 폴더 변경·다운로드 기록 통합 후 필수 파일 17개·문서 16개·로컬 링크 46개 통과. 기존 경로 참조 없음, PDF·DOCX 원본 해시 보존. 커밋·원격 게시하지 않음 |
| 최종 문서·작성 틀 검사 | `python3 scripts/check_repository.py` | 08·09 작성 틀·README 연결·contest 정리 후 필수 파일 19개·문서 18개·로컬 링크 53개 통과. 계획·디자인은 미작성 상태이며 Android 실행 검증과 구분 |
| OT 문서 보완 (2026-11-03) | `python3 scripts/check_repository.py` 및 챕터 시각 대조 | 필수 파일 19개·문서 19개·로컬 링크 74개 통과. OT 요약 35개 챕터의 표시 시각·URL 시각 일치와 시간순 확인. 앱 실행 검증·원격 이슈 갱신은 수행하지 않음 |
| Pleos 웹 하위 자료 보완 (2026-11-03) | 공개 웹 메뉴·하위 본문 대조, `python3 scripts/check_repository.py` | 필수 파일 19개·문서 19개·로컬 링크 86개 통과. 계정 내부·Figma 파일 내부·일부 시연 GIF는 미확인. 앱 구현·콘솔 실행 시험·원격 이슈 변경은 수행하지 않음 |
| Pleos 근거의 설계·검증 계획 반영 (2026-11-03) | `python3 scripts/check_repository.py` 및 관련 문서 대조 | 필수 파일 19개·문서 19개·로컬 링크 107개 통과. PRD·기술 계약·화면 전환·시험 계획·진행 계획·README 일치 확인. 추가 시나리오는 미수행이며 기존 앱 검증 결과를 대체하지 않음 |
| 백그라운드 기록·주행 중 음성 심화 조사 (2026-11-03) | 공식 수명주기·심사·카테고리·SDK·오디오 문서 대조, `python3 scripts/check_repository.py` | 필수 파일 19개·문서 19개·로컬 링크 118개 통과. 기록 모델 문의안·음성 유형별 판단을 PRD·기술 계약·검증·진행 계획에 연결. 문의 미발송, 원격 이슈 미반영, 추가 실행 시험 미수행 |
| 개발 기반·버전 문서 기준 (2026-11-03) | Pleos·AGP·KGP 공식 문서와 실제 Gradle 설정 대조, `python3 scripts/check_repository.py`, 변경 전후 해시 비교 | 필수 파일 19개·문서 19개·로컬 링크 128개 통과. 코드·빌드·CI·검사 설정 등 28개 파일 변경 없음. Kotlin 2.0.21·AGP 8.8.2는 KGP 완전 지원 표 범위 밖이며 최종 조합 검증 필요. 공모전 사용 금지 판정 아님. 새 빌드·설치·앱 실행·원격 이슈 반영·게시 미수행 |
| 호환성 기반 권장 조합 재작성 (2026-11-03) | KGP·AGP·IDE 공식 호환표와 AGP 대응 R8 고정 소스 대조, 문서 검사·변경 전후 해시 비교 | Kotlin 2.2.21·AGP 8.10.1·Gradle 8.11.1·JDK 17을 문서 기준으로 선택. R8 8.10.24가 Kotlin 2.2 요구 8.10.21 이상임을 확인. 필수 파일 19개·문서 19개·로컬 링크 128개 통과, 코드·설정 28개 변경 없음. 새 조합 미적용·Pleos 실행 미검증·원격 미반영. 이전 행의 ‘최종 조합 검증 필요’에서 문서상 권장안 선택까지 진전한 것이며 실행 검증 완료 아님 |
| 워크플로 문법 | Ruby YAML 파싱·job 이름 확인 | `repository-checks`, `android-checks` 확인. GitHub 원격 실행 검증 아님 |
| 앱 빌드·Lint | `./gradlew :core:test :app:assembleDebug :app:lintDebug` | `:app:processDebugMainManifest`에서 Manifest 누락으로 실패. Lint까지 도달하지 못함. 핵심 테스트는 이전 통과 결과 재사용 |
| GitHub CI·보호 규칙 | 비공개 원격 `Yaho03/drive-growth` 생성 확인 | 문서 통합·영어 파일명·작업 영역별 브랜치 규칙을 적용한 [PR #2](https://github.com/Yaho03/drive-growth/pull/2)의 실행 결과는 Actions·PR 기록에서 확인. 보호 규칙 적용은 이번 작업 범위 밖 |

`repository-checks`는 필수 파일과 로컬 Markdown 링크를 확인한다. 이 검사는 제품 동작이나 워크플로의 원격 성공을 증명하지 않는다. `android-checks`는 핵심 테스트·APK 빌드·Android Lint를 실행하도록 정의했다. 초기 문서 게시에는 앱·핵심 소스가 없어 Android 검사를 생략했다. 이번 공통 소스 공유부터 Android 검사를 실행한다. 소스가 어느 한 모듈에라도 추가되면 실행하며 실패를 숨기지 않는다.

## 과거 핵심 테스트 범위

주차 3초 확인과 1회 정산, D 상태의 정차와 주차 구분, 입력 누락·UX 불명·긴 간격, 중복·역순 입력, 출처 혼합, 짧은 기록의 평가·보상 제외, 일일 보상 상한, 같은 이벤트 연속 구간, 정산 상태 복원, 중단 기록 0XP, 끊긴 주차 관측, 재부팅 후 단조 시각 초기화를 검증한다.

## 실제 앱 시연의 완료 조건

아래는 예정 절차이며 시연 성공 기록이 아니다.

1. APK 설치·실행 → 주차·조작 허용 상태에서 데이터 출처와 기록 가능 여부가 표시된다.
2. 유효 주행 입력 → 게임 UI가 숨겨지고 사용이 일시 중지된다. 허용 여부가 확인된 기록 경로는 실제 조회·콜백 증거로 확인한다.
3. D 상태에서 속도 0 → 정산·조작 허용으로 잘못 전환되지 않는다.
4. 주차 확인 → 같은 주행에 대해 결과·보상을 한 번만 저장한다.
5. 앱 종료·재실행 → 결과와 성장 상태가 유지되고 중복 보상이 없다.
6. 누락·권한 거부·미지원 신호 → 실제 기록을 만들어내지 않는다. 주행 중 게임 팝업으로 알리지 않고 주차·조작 허용 후 해당 상태를 확인한다.
7. 확정된 추가 기능 → 성공·실패·재실행 결과를 각각 확인한다.

모의 시연은 영상과 앱에 모의 입력임을 표시한다. 에뮬레이터 차량 신호 수신과 실제 차량 실행은 따로 기록한다.

## Pleos 조사에 따른 추가 검증 계획

2026-11-03 추가한 **미수행 시나리오**다. 기존 엔진 테스트·부팅 성공으로 아래 조건을 통과 처리하지 않는다. 백그라운드 기록·계산·음성은 정책 적합성 근거와 실행 결과를 각각 확보해야 한다.

| 입력·절차 | 기대 결과·확인 증거 |
|---|---|
| 주차 중 게임 → 주행·UX 제한 활성화 | UI 숨김·사용 중지. 화면 녹화와 제한 상태 로그의 시점을 대조 |
| 제한 중 런처·최근 앱으로 재진입, 이어서 D·속도 0 | 게임 화면·조작·성장 연출이 재개되지 않음. 속도 0만으로 정산하지 않음 |
| 허용된 기록 방식에서 앱 숨김·다른 앱 전면 전환 | 조회·콜백이 실제 수신된 구간만 기록. 정책 근거와 데이터 시각·출처·누락 증거를 별도 기록 |
| 기록 중 프로세스 종료 → 재실행 | 저장된 범위만 복원하고 중단·누락을 구분. 보상 기대값은 미확정 중단 정책 합의 후 고정 |
| 주차·UX 허용 후 사용자 복귀 → 정산 반복 진입 | 이전 결과 조회, 같은 주행 보상 1회. 주차가 앱 자동 실행을 보장한다고 판단하지 않음 |
| 정산 저장 실패·저장 경계에서 종료 → 재시도 | 확정 결과·보상·처리 이력이 일치하고 부분 저장·중복 지급 없음 |
| 데이터 미지원·권한 거부·연결 해제 | 기록 상태를 정확히 구분하고 정상값·만점·가짜 기록으로 대체하지 않음 |
| 별도 창 Emulator에서 크기·분할·방향 변경, 폼 팩터 디스플레이 제거·복귀 | 주요 내용·행동 접근 가능, 저장 상태 유지. 디스플레이 제거에 따른 앱 종료도 복원 시험에 포함 |
| 하위 화면·팝업·최상위에서 Back/CCP Back | 각각 이전 화면·팝업 닫기·적용되는 최상위 숨김 정책 확인. 종료될 가능성에 대비한 저장·복원 검증 |
| 음성 유형별 정책 근거 확인 후 주차·주행 전환 시험 | 게임 피드백·휴식 권유·STT·Gleo별 허용 근거와 실행 결과를 분리. API 호출 성공이나 DO 선언을 주행 중 허용으로 판정하지 않음 |
| 허용된 음성 재생 중 포커스 거부·상실, 통화·Gleo·안내 개입 | 현재 오디오 정책에 맞게 재생·중단·음량 처리. 승인되지 않은 우선순위로 강제 출력하지 않음. Speech Service와 앱의 처리 책임·이벤트·실제 소리를 대조 |
| 기록·음성 사용 중 IGN OFF/Suspend → Resume | 앱 숨김과 구분해 기록 전달·중단 구간 확인. 일반 오디오 중단·포커스 반납, Suspend 중 새 재생 없음. Resume 후 현재 허용 상태를 확인하고 누락 구간을 정상 데이터로 채우지 않음 |

정책 문의는 [차량 자료 6.1절](contest/03_VEHICLE_API.md), 음성별 판단은 [개발 자료 9.1절](contest/02_PLEOS_DEVELOPMENT.md)을 따른다. 계획 이후 실행한 범위는 [#13 차량 입력 실행 검증](#13-차량-입력-실행-검증)에 기록한다. 공식 답변·정책 적합성과 에뮬레이터의 기술적 동작을 별도 증거로 남긴다.

시험 전 Virtual Console 입력과 앱 데이터의 대응을 확인한다. 크루즈 목표 속도를 실제 차량 속도로 사용하지 않으며, SDK 초기화 메시지만으로 연결·콜백 수신·값 반영을 통과시키지 않는다. 상세 해석은 [공식 시험 자료](contest/04_OFFICIAL_RESOURCES.md) 6절을 따른다.

## #13 차량 입력 실행 검증

2026-11-04, Pleos Connect Emulator 2.1.0 / API 34와 Virtual Console을 사용했다. 기준은 `main` 커밋 `ccea6e0`이다. Debug APK SHA-256은 `d66491ba119a105d953194c231fa30e49aae20cf8862ab3cf03edd42f22db997`이며, 설치된 APK와 일치했다. 앱의 수신 값을 보기 위한 진단 빌드는 `VehicleInput`에 로그만 추가해 별도로 실행했다. [#13 검증 기록](https://github.com/Yaho03/drive-growth/issues/13)

| 실행 조건 | 확인한 결과 |
|---|---|
| 기준 APK에서 D·0 km/h → P | D에서 `DO=true`, `UxR=80`, 게임 메뉴 숨김과 조작 잠금 안내를 확인했다. P 복귀 후 `DO=false`, `UxR=0`, 홈·차고·기록 메뉴가 다시 표시됐다. |
| 차량 신호 엔진 단위 시험 | `./gradlew :core:test --tests '*DriveEngineTest' :app:assembleDebug` 성공. 테스트 13개에서 D 정차·신호 누락 무정산, P와 조작 허용이 이어진 뒤 1회 정산 등을 확인했다. |
| 활성 사용자에서 속도 권한 철회 | 진단 빌드에서 권한 없음 경로를 6회 연속 확인했다. 이 경로는 속도·UX 누락, 기어 UNKNOWN, 무효 표본을 전달한다. 시스템 권한 대화상자 종료 후의 앱 화면은 미확인이다. 시험 뒤 속도 권한을 복원했다. |
| 다른 Pleos 화면이 전면인 동안 P → D·0 km/h | 같은 앱 프로세스에서 기어 변경을 읽고 D 표본 11개를 약 1초 간격으로 수신했다. |
| Virtual Console에서 D → 가속 → 제동 → P | 콘솔 속도 61 km/h, 앱 유효 속도 표본 3.33–17.50 m/s, 주차 후 차량 출처 기록 1건(101.17 m, 14.35초, 경험치 0)을 확인했다. 시험 도중 Pleos DrivingView와 앱 사이에서 전면 창이 바뀌었다. 전체 이동 구간의 백그라운드 수신은 별도로 확인해야 한다. |
| 기록 완료 후 기준 APK로 재실행 | 진단 APK를 기준 APK로 교체하고 앱을 강제 종료한 뒤 실행했다. 저장된 101.17 m 기록 1건과 경험치 0을 다시 읽었다. |
| 앱 응답 지연 | 진단 시작 중 ANR을 관측했다. AVD 재시작 후 위 신호 수신을 재현했으며 ANR 원인과 장시간 안정성은 미확인이다. |

차량 속도와 기어는 Android Automotive `CarPropertyManager`, 조작 제한은 `CarUxRestrictionsManager`에서 읽는다. 앱의 표본 시각은 수신 시각이다. 실제 차량 도착은 주차와 별개이며, NaviHelper의 목적지·경로 상태 API는 문서에서 확인했다. 현재 앱의 NaviHelper 연결과 예상 거리·도착 이벤트 수신은 미검증이다. [Pleos 속도 조회 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq#클러스터-속도-조회-방법), [NaviHelper API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)

게임 카테고리의 주행 중 기록·음성 허용, IGN OFF/Suspend, 실차 동작은 별도 근거와 시험이 필요하다. #36·#51·#63은 D 정차와 P 복귀의 실행 결과를 사용할 수 있다. #24·#27에서는 주차 판정과 탁송 도착 근거를 별도 상태로 처리한다.

### 추가 재현과 플랫폼 한계

| 시험 | 확인한 결과 |
|---|---|
| 기준 APK에서 속도 권한 철회 | 권한 없음과 조작 잠금 안내가 표시됐다. 활성 사용자 권한과 플래그를 시험 전 상태로 복원했다. |
| 다른 Pleos 화면을 앞에 둔 상태에서 이동 | 진단 APK에서 1.39–17.22 m/s, 차량 출처 95.59 m 신규 기록을 확인했다. 샘플링한 창 포커스에서 게임이 전면으로 관측되지 않았다. 모든 화면 전환 순간을 연속 캡처하지는 않았다. |
| 이동 중 기준 APK 강제 종료·재실행 | 저장된 6.02 m가 같은 ID의 중단 기록 1건으로 복원됐고 경험치 지급은 0이었다. |
| AVD의 차량 전원 Suspend 모사·Resume | `cmd car_service suspend --simulate` → `SUSPEND(4)`, `cmd car_service resume` → `ON(1)`을 확인했다. P에 있던 기준 APK의 기존 기록 3건과 경험치가 유지됐다. 실제 IGN OFF와 이동 중 신호 전달은 별도 시험이다. |
| 별도 NaviHelper 3.0.5 진단 APK | 서비스 연결 및 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 부여, 목적지·경로 상태 콜백을 확인했다. 공식 예시 목적지 경로 요청 뒤 `remainDistance` 원값은 0에서 50으로 바뀌었다. SDK 단위·거리 정확도는 미확인이다. 동일 목적지 GPS 주입을 시도했으나, 관측 구간에 `onDestinationArrived`가 없었다. |

NaviHelper의 SDK 값은 목적지·경로 이벤트와 연결해 검증한다. 0 단독으로 도착을 확정하지 않는다. 게임 카테고리의 주행 중 기록·음성 허용은 공개 안내에서 명시적으로 확인되지 않았고 실차 검증은 미수행이다. 기능별 권한·정책과 미지원 대안은 [차량 API 조사](contest/03_VEHICLE_API.md), 구현 경계는 [공통 계약](02_SYSTEM_ARCHITECTURE.md)을 따른다. [NaviHelper 이벤트 명세](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/event-listeners)

### #36 차량 입력 본인 검증

2026-11-05, 작업 브랜치 `core/36-vehicle-input`(기준 `ab0c2d6`)의 미커밋 변경을 Windows 11 x64, Pleos Connect Emulator 2.1.0 / API 34 x86_64, 창 없는 실행, RAM 설정 3GB에서 시험했다. Debug APK SHA-256은 `4dee94082c9d2f5d904084da8c5bc6c441b6b9a62c4f1ba3c561387ed9cace5a`다. 차량 입력은 Virtual Console의 로컬 API(`PUT /vehicle/gear`, `/vehicle/controls`)로 넣었다. 에뮬레이터 입력이며 실차 신호가 아니다. 사람의 교차 검증은 #63에 남아 있다.

| 검사·절차 | 확인 결과 |
|---|---|
| `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` | 모두 성공. 코어 71개(#36 추가 21개 포함), 앱 단위 4개 |
| `am instrument --user 10` | 21개 성공. 저장 복원 시험 포함 |
| P 시작 | `gear=PARK ux=false`, 게임 화면 표시 |
| D·0 km/h 10초 유지, D·36 km/h | `gear=DRIVE ux=true`, 제한 화면 유지 |
| 주행 중 앱 강제 종료 후 재진입 | 제한 화면 |
| D → P | `gear=PARK ux=true`가 먼저 수신되고 약 1초 뒤 `ux=false`로 바뀐 후 게임 화면 복귀 |
| 속도 권한 거부 | `speed=PERMISSION_DENIED gear=PARK ux=false`, 제한 화면. 나머지 신호는 유지. 시험 후 권한 복원 |
| 앱 숨김 후 복귀 | `observation_gap kind=APP_HIDDEN`, 게임 화면 복귀 |
| 차량 전원 Suspend 모사·Resume | 복귀 후 `speed=OK gear=PARK ux=false` 수신. 화면 덤프는 실패해 화면은 미확인. 모사에서 `DEVICE_SLEEP`은 발생하지 않음 |

3초 연속 주차 대기, 신호 만료, 역순·중복·긴 간격, 출처 혼합, `DEVICE_SLEEP` 분류는 단위 시험으로만 확인했다. 시험 PC는 외장 GPU가 없어 공식 최소 사양에 못 미치고 에뮬레이터 부하(load average)가 28–50까지 올랐다. 첫 부팅에서는 `car_service`가 반복 재시작되어 재부팅했다. 메인 스레드가 2.5초 넘게 지연되면 표본이 만료되어 게임 화면이 제한 화면으로 바뀌는 것을 1회 관측했다. 안전 쪽 동작이지만 정상 사양 PC에서의 재현 여부는 미확인이다. 같은 APK를 창 모드로 실행해 구현자(정원)가 Virtual Console 웹 화면에서 D를 누르면 제한 화면, P를 누르면 몇 초 뒤 게임 화면으로 돌아오는 것을 직접 확인했다. 이는 본인 검증이며 교차 검증이 아니다. 실제 IGN OFF, 실차, 주행 중 백그라운드 기록은 시험하지 않았다.

`inject-vhal-event`는 이 이미지에서 `requires non-user build`로 거절되어 신호가 바뀌지 않는다. D 시험에는 Virtual Console을 사용하고, 입력이 앱에 도달했는지 `DriveGrowthVehicle` 로그로 먼저 확인한다.

### #36 절전·복귀 검증 범위

2026-11-06 사용자 승인으로 #36은 전경 입력과 에뮬레이터에서 확보한 절전·복귀 결과의 근거 확인·문서 반영·연결 PR과 #28 인계를 담당한다. 게임 화면 허용까지 3초 대기는 정밀 실측하지 못했으므로 기존 단위 시험 근거만 유지한다. 인계 조건은 [#36](https://github.com/Yaho03/drive-growth/issues/36)에서 관리한다.

`main` `506a38c`의 Debug APK(SHA-256 `b62238f243ba9d60a97b25c943b69f96b8ab253783ba959d437025d9c31e3ca2`)를 Windows 11 x64, Pleos Connect Emulator 2.1.0 / API 34 x86_64, 창 없는 실행에서 시험한 구현자 본인 검증이다. 기대 결과는 시험 전에 정했다. 차량 입력은 Virtual Console의 모의 입력이고, 화면은 `uiautomator` 문구로 확인했다. 검토 기록은 [PR #96](https://github.com/Yaho03/drive-growth/pull/96)에서 관리하고, 기능 간 통합 검증은 [#28](https://github.com/Yaho03/drive-growth/issues/28)에 남아 있다.

| 시험 | 기대 | 실제 |
|---|---|---|
| 1. P, `suspend --simulate --skip-garagemode` 약 20초, `resume` | 복귀 후 신호 재수신, 게임 화면 | 절전 중 `SUSPEND(4)`, 앱 일시정지. 복귀 후 `observation_gap kind=APP_HIDDEN durationMs=20480`, `gear=PARK ux=false`, 게임 화면. 통과 |
| 2. P, `suspend --real --skip-garagemode` | `DEVICE_SLEEP`, 복귀 후 게임 화면 | 약 20초 뒤 입력 없이 복귀. `kind=DEVICE_SLEEP durationMs=28167`, [게임 화면](evidence/issue36-suspend/real-suspend-parked.png). 첫 절전 시도는 실패 후 재시도됐다. 통과 |
| 3. D·36 km/h, 모사 절전 중 P로 변경, `resume` | 복귀 직후 열리지 않고 P 지속 후 게임 화면 | 복귀 0.7초 후 제한 화면, `kind=APP_HIDDEN durationMs=23967`, 11.6초 후 확인에서 게임 화면. 통과 |
| 4. D·36 km/h, 모사 절전, D 유지로 `resume`, 이후 P | 제한 유지, P 후 게임 화면 | 복귀 후 `gear=DRIVE ux=true`, [제한 화면 유지](evidence/issue36-suspend/simulated-suspend-drive.png). `gear=PARK ux=false` 수신 약 3.3초 후 확인에서 게임 화면. 통과 |
| 5. P, `KEYCODE_SLEEP` 약 23초, `KEYCODE_WAKEUP` | 공백 기록, 복귀 후 재판정 | 꺼진 동안 `Asleep`. `kind=APP_HIDDEN durationMs=26504`, 게임 화면. 통과 |
| 6. D·36 km/h, `suspend --real` | 복귀 후 제한 유지 | **미검증.** D와 제한 화면을 확인한 뒤 절전에 들어갔으나 약 8분 동안 adb 응답이 없었고 전원 키 입력에도 복귀하지 않아 강제 종료했다. 시험 2 뒤의 `--real` 1회도 같은 이유로 결과를 얻지 못했다. |

- 2026-11-07 검토 범위: PR #96의 시험별 발췌 로그와 첨부 화면 2장을 대조했다. 전체 원본 로그는 보존되지 않았으므로 전체 시간 순서를 독립 재구성하지 못했고, 표의 0.7초·11.6초·3.3초는 구현자가 시험 당시 보고한 화면 확인 시점이다. 정밀 지연 측정이나 정확한 3초 대기 실측을 통과로 판정하지 않는다. 본인시험 결과와 이 한계를 #28에 인계하며, 기능 간 실행과 비활성 기록 경로의 검증은 별도 범위로 유지한다.
- 공백 종류는 시계 차이로 나뉜다. 커널 절전이 일어난 `--real`만 `DEVICE_SLEEP`이고, 모사 절전과 화면 끄기는 `APP_HIDDEN`이다. 이 값은 현재 로그에만 쓰이며 조작 허용 판정은 공백 종류와 무관하게 `onPause`에서 초기화된다.
- 실제 절전 3회 중 1회만 복귀했다. 복귀 후 `adb forward`가 사라졌다. 부팅 직후에는 내비 앱이 늦게 실행되어 앱을 가릴 수 있어, 입력 전에 전면 창과 `DriveGrowthVehicle` 로그를 먼저 확인한다.
- `DriveEngine`이 5초를 넘는 표본 간격을 거리에 더하지 않는 것은 단위 시험 근거다. 이번 시험은 거리와 저장 기록을 다루지 않았다.

D 상태 `suspend --real`에서 에뮬레이터가 복귀하지 않아 확인하지 못한 앱 동작과 실제 절전의 안정성은 [#82](https://github.com/Yaho03/drive-growth/issues/82)로 이관한다. 이는 미검증 범위의 이관이며 시험 통과나 앱 결함 확정이 아니다. 에뮬레이터의 실제 절전과 실차 IGN OFF 결과는 구분한다. #82 종료를 #36의 결과 인계나 기존 #51 화면 작업의 조건으로 요구하지 않는다. 현재 경로에서 시작되지 않는 `DriveRuntime`·`RecordingService`의 기록 시험은 수행하지 않았으며 기능 연결·활성화는 기존 담당 이슈와 #81을 따른다.

### 플랫폼 검증 인계

[#13](https://github.com/Yaho03/drive-growth/issues/13)은 위 에뮬레이터 실행 근거를 관리한다. [#80](https://github.com/Yaho03/drive-growth/issues/80)은 실제 내비 거리·도착 확인의 선행조건, [#81](https://github.com/Yaho03/drive-growth/issues/81)은 주행 중 기록·음성 등 해당 기능 활성화의 선행조건, [#82](https://github.com/Yaho03/drive-growth/issues/82)는 미완료 에뮬레이터 실제 절전·복귀와 실차·실제 전원 전환을 구분해 추적한다. 해당 실제 전원·실차 동작을 검증 완료로 표시할 때는 대응 시험 근거가 필요하다. 모의 입력과 뼈대 검증의 착수 조건은 진행 계획을 따른다.

## #80 내비 모의주행·권한·재탐색·렌더링 추가 검증 — 2026-11-06

이 절은 앞선 도착 미수신·GPS 실패 기록을 보존한 후속 결과다. 입력은 **내비 내장 모의주행**이며 실제 NaviHelper 콜백을 관측했다. 실차·Android GPS 연동 성공·제품 탁송 정산 성공으로 해석하지 않는다. #80은 담당자가 남은 검증을 마무리할 수 있도록 열린 상태를 유지한다. 이 변경은 진단 도구·재현 방법·실행 근거를 제공하는 지원 자료다.

### 환경과 원본 대응

- Pleos Connect 2.1.0 / API 34 ARM64, Emulator 37.2.13.0, 내비 `3.3.2-rc5-hotfix.3`, NaviService 3.0.4, NaviHelper 3.0.5. 사용자 10 / `emulator-5556` / ADB 서버 포트 5038 / RAM 4GB.
- 제품 패키지는 `io.drivegrowth.app`, 별도 진단 패키지는 `io.drivegrowth.navidiagnostic`이다. 제품 APK·데이터·게임 기능은 변경하지 않았다.
- 기존 검증 APK SHA-256: `32fe31a755321534ba4978d852f0b0200651a9561cc933b597b6d919d56bc585`. 진단 사본과 실제 시험본의 해시 일치를 직접 확인했다.
- 기존 원본 `diagnostic-final-check.jsonl`은 로컬에 보존하며 1,599행이다. 1,598건 보고 이후 `activityStopped` 1건이 추가됐다. SHA-256 `d2b510f94a9f51cee1702ff5f13ee7b8fe2f400ad1c47be335422ae8df73efdf`.
- 공개 이벤트 선별본·화면과 원본/공개본 해시는 [시험 증거](../navi-diagnostic/evidence/README.md)에 보관한다. 설치·재현·APK 다운로드는 [진단 도구 안내](../navi-diagnostic/README.md)를 따른다. SDK·OEM APK·서명키·전체 시스템 로그는 게시하지 않는다.

### 도착과 취소: 기존 원본 직접 대조

| 이벤트 | 확인한 증거 | 한계 |
|---|---|---|
| 첫 도착 | 18:19:03.235 KST, seq155, `DestinationArrivedInfo(duration=3, distance=50, name=42dot, address=수정구 시흥동)` | 도착 당시 마지막 위치·목적지 조회 표본은 약56초 전으로 오래됐다. 경로 연결 증거는 두 번째보다 약함 |
| 두 번째 도착 | 18:21:29.051 KST, seq746, duration3·distance49. 두 도착 모두 `source=SDK_CALLBACK`, `staleConnection=false` | `DestinationArrivedInfo` 자체에는 routeId가 없음 |
| 두 번째의 경로 대조 | 직전 SDK routeId `506c7c27-d9c3-11f1-86b4-72e3ec72fa29`, currentRouteIndex 0→1→2와 위치 이동, `remainDistance` 50→30→10, `SIMULATION_GUIDING`, 도착 뒤 `FREE_DRIVE` | SDK가 보증한 요청-응답 ID가 아닌 시각·활성 경로·목적지의 교차 근거 |
| 취소 | 18:22:29.577 KST, seq1097, `onRouteCancelled`; +17ms `FREE_DRIVE`, +42ms `SAFE_GUIDING`, 목적지 초기화 | 취소 뒤 마지막 SDK 수신까지 **103.449초**, SDK 콜백240건·위치43건·추가 도착0. 리스너 해제 뒤 구간을 무도착 관측시간에 더하지 않음 |

기존 요청의 좌표·주소는 강남 `37.48544722,127.03636666`였지만, 동일 요청의 poiId를 서버가 시흥 `37.41289678,127.09383464`의 42dot으로 채택했다. 이는 요청 목적지 일치 성공이 아니다. 후속 진단 입력은 실제 POI 좌표·주소에 맞췄고, 원래 잘못된 요청과 당시 APK·로그는 보존했다. `50`이라는 원값만으로 모의 응답이라고 단정하지 않는다.

일반 모의주행 재현 순서는 SDK 연결 → 실제 목적지·안내 상태 확인 → 안내 중 설정 톱니바퀴 길게 누르기 → 일반 주행 시뮬레이션 패널의 시작이다. 1080×600에서 톱니는 약(1006,440), 시작은 약(768,334)이었으며 매번 현재 화면으로 대조했다. 비밀번호 개발자 메뉴는 사용하지 않았다.

### 후속 실제 시험

| 시험 | 입력과 기대 | 직접 관측한 결과 |
|---|---|---|
| 거리 단위와 화면 | 공식 `DestinationInfo.remainDistance` 명세 m, 내비 표시값과 대조 | 실제 목적지 조회50과 내비 `50m` 표시 일치. 도착 payload distance·duration의 공식 단위는 m·s. 정밀 거리 정확도·다른 거리 구간까지 검증한 것은 아님 |
| Android GPS: 모의주행 성공 이후 | 경로 없는 상태에서 `geo fix 127.094400 37.412800 0 12 0`; 내장 주행 성공 뒤에도 GPS 전달 경계가 남는지 확인 | Android 위치 `37.412798,127.094398`으로 변경. 약8초 동안 SDK 위치4회는 `37.4127212,127.094681` 고정. 도착·내비 이동 성공 아님. 기존 GSA 주입을 의미 없이 반복하지 않음 |
| 권한 미선언 | 같은 진단 패키지의 별도 빌드에서 두 Navi 권한 선언 제거. 실제 병합 Manifest·설치 상태 확인 | 두 권한 검사 -1. `onConnected`는 수신했으나 목적지·경로·위치 조회 각각 `NAVI_ROUTE` 요구 `SecurityException`, 경로 요청은 `NAVI_ROUTE_SEARCH` 요구 `SecurityException`. 오류는 `TOOL_ERROR`이고 가짜 `onError`로 기록하지 않음 |
| 권한 복원 | 데이터 삭제 없이 같은 패키지의 정상 선언 APK로 교체 | 두 권한 검사0, 조회 콜백·`onRouteStarted`·`ROUTE_DRIVE` 및 내비50m 안내 확인. 두 권한은 normal이므로 사용자 런타임 거부 대화상자 시험과 구분 |
| 공개 수동 재탐색 | `ROUTE_GUIDING`과 routeId·목적지가 있는 상태에서 `requestReRoute()` 1회 | 요청·반환과 내비 `OnReRouteRequest`, `reRouteManualRequest called` 확인. 30.760초 관측창의 `onReRouteEnded`·`onRoutingCompleted`는0. 목적지는 유지되고 SDK routeId `9b4c1381-d9c6-11f1-87a8-72e3ec72fa29`도 유지. 실제 탐색 성공으로 기록하지 않음 |
| 좌표일치 입력의 추가 도착 | 정상 권한·실제 POI에 맞춘 입력, host GPU의 일반 내장 모의주행 | 18:59:16.820 KST, run `689e4e83-fa18-4bab-8a6b-45bb1d79ffcc`, seq244, `SDK_CALLBACK/onDestinationArrived`, staleConnection=false, duration3·distance50·42dot·수정구 시흥동; 이어 `FREE_DRIVE`. 해당 실행은 주기 조회가 켜지지 않아 이동 전체의 신선한 SDK 좌표 열은 확보하지 못함 |

후속 진단 수정은 기존 모듈의 시흥 POI 좌표·주소, 수동 재탐색 버튼, 권한 미선언 빌드에 한정했다. 정상 APK SHA-256은 `e67c2580dd75ba23780c0544fe12fadbd8ee5d0854bdf6d81b7fa0d60286ab1f`, 권한 미선언 APK는 `8c5faa0802c1b67be4622b9cd7e6e2d3167a47bf5f33b6ae4661da0a87554a95`다. `:navi-diagnostic:testDebugUnitTest :navi-diagnostic:assembleDebug :navi-diagnostic:assembleNoNaviPermission :navi-diagnostic:lintDebug` 성공, 단위시험1개 통과·Lint 오류0/경고19. SDK 실행 결과와 빌드 결과는 별도다.

### 설치 버전의 API 불일치와 지도 렌더링

- **isRouting:** 활성 `ROUTE_DRIVE`·`ROUTE_GUIDING`·routeId·잔여거리에도 false를 반복 관측했다. 같은 버전 이미지의 읽기 전용 추출본 `NavigationUseCase.routeStateInfoRequest`에서 false 상수를 생성자에 넣고 Provider까지 전달하는 경로를 확인했다. 해당 필드를 활성 안내 판정에 단독 사용하지 않는다. 모든 SDK 버전의 의미를 재정의하지 않는다.
- **수동 재탐색:** 추출본은 `ManualReroute`를 채널에 전달하지만 소비부 명령 분기에 해당 처리가 없고 `ReRouteRequest.None`으로 실제 탐색을 건너뛴다. 요청 전달 뒤 완료가 없는 실행 결과와 부합한다. 상태변수나 모의주행을 바꿔 해결된 것으로 만들지 않으며, 시스템 APK를 패치하지 않았다. 실제 설치 APK 직접 해시는 일반 adb shell 읽기 권한이 없어 대조하지 못했으므로, 정적 근거는 같은 버전 시스템 이미지 추출본에 한정한다.
- **지도 자료:** 실제 선택 URL `https://a.tile.static.maps-coral.umos.ai/styles/42dot/connect_empty`의 공개 JSON을 TLS 검증을 유지한 curl로 읽었다. coastline 소스1개와 background·coastline·slot 레이어만 있고 도로·건물 소스 및 imports는 없다. 배경색은 명시적 검정이 아니다. `metadata is nil`은 last_info의 맵매칭 저장 문맥이며 지도 타일 실패로 동일시하지 않는다.
- **지도 설정 UI:** coral에서 설정 짧은 클릭은 차량 설정의 내비게이션 카테고리로 이동한다. 일반·위성·지형 팝업의 공개 진입으로 확인되지 않았다. 길게 누르기는 모의주행이며 지도 스타일 변경으로 취급하지 않는다.
- **그래픽 통제:** 같은 AVD·이미지·RAM·앱·해상도를 유지하고 실행 인수 `-gpu swiftshader`만 공식 `-gpu host`로 변경해 cold boot했다. 기존은 swangle/SwiftShader·GLES3.1, host는 Apple M1 Pro OpenGL ES Translator·GLES3.0으로 실제 선택됐다. GLES3.2 제공으로 주장하지 않는다.
- **host 결과:** 검정이던 지도 영역에 실제 스타일의 밝은 바탕, 위치·목적지 마커·경로 오버레이가 나타났고 안내·모의주행 화면에서도 유지됐다. 바탕색·스타일·시스템 APK는 수정하지 않았다. 수집한 host 로그 구간에서는 DrivingView 반복 종료가 없었다. 도로 기본 지도가 제공된 결과는 아니며 장시간 안정성·다른 장비 재현은 미검증이다.

공식 GPU 근거: [Android Emulator 그래픽 가속](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics), [Pleos 설치·실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator). 기존 소프트웨어 조건에서는 DrivingView의 `no ES 3.2 support`·반복 종료와 화면 정체가 있었다. 데이터 삭제 없는 Android 재부팅은 UI를 복구했지만 검정 지도는 그대로였으며, host GPU 전환의 결과와 구분한다. 중간 진단 프로세스의 `REMOVE TASK` 종료 이후 무수신은 SDK 무응답 증거에서 제외한다.

### #80 완료 조건과 게임 연결 경계

| 완료 조건 | 현재 판정 |
|---|---|
| 버전·권한·거리 단위 | 지정 환경과 공식 m·s 단위 근거·화면50m 대조 확보. 다른 환경·거리 정확도는 범위 밖 |
| 활성 경로 도착·경로 식별·재탐색·취소·권한 거부 | 도착·취소·권한 미선언/복원 결과 확보. 수동 재탐색은 미완료/설치 버전 구현 제한으로 남음. 도착 payload에 routeId가 없는 한계 유지 |
| 증거 부족·P/거리0 경계 및 #39·#64 대조 | 계약 대조 완료, 게임 실행 검증 아님. 취소 직후에도 거리0이 있어 도착으로 만들면 안 됨. SDK 출처 표시를 실차/VEHICLE 입력 증거로 바꾸지 않음 |
| 환경·기대·실측·미수행 문서 | 이 절과 진단 도구 안내·공개 시험 증거에 반영. 지원 PR은 #80에 연결하며 전체 완료 판정은 담당자의 후속 검토로 남김 |

`DeliveryArrivalEvidence`는 앱 내부 경로 세대와 출처가 맞을 때만 생성하는 계약이다. 현재 HEAD의 제품 `app/core`에는 NaviHelper 도착 어댑터가 없고, 계약의 `matches`만으로 오래된 콜백·목적지 불일치·취소/재탐색 뒤 세대 변경을 처리한 것은 아니다. #39·#64의 정산·지급·저장·복원까지 구현하거나 완료 처리하지 않는다. 목적지·출처·유효시각·활성 세대 연결이 부족하면 확인 불가로 전달한다.

### 시험 종료와 복원

- 원래 실행 인수의 `-gpu swiftshader`·RAM4096·포트5556·headless·no-snapshot-save 조건으로 다시 cold boot했다. AVD 설정 파일·시스템 이미지·ADB 서버 설정은 수정하지 않았다. 복원 뒤 지도 검정이 다시 나타나 host 전환의 렌더링 개선과 구분된다. host는 검증된 대안 실행 조건이며 현재 실행 조건으로 남겨두지 않았다.
- 진단 앱을 원본 `0.1.0-issue80`으로 데이터 유지 설치했다. 복원 중 Activity 등록을 재확인할 필요가 있어 같은 원본을 다시 설치한 뒤 resolve-activity·COLD 실행을 확인했다. **설치된 base.apk SHA-256도 원본의 `32fe31a755321534ba4978d852f0b0200651a9561cc933b597b6d919d56bc585`와 일치**했다. 사용자10의 두 Navi 권한 granted=true, 기존 ceDataInode492447 및 로그 파일 보존을 확인했다.
- 후속 host 실행의 seq371 `removeListenerReturned`·seq372 `releaseReturned`를 확인했다. 복원 앱의 새 run은 시작·단위·출처 이벤트만 있으며 연결·주기 조회·경로 요청을 실행하지 않았다.
- Android GPS는 본 세션 시작 시 좌표 `39.237255,-123.150032`로 복원됐고 마지막 dumpsys location으로 확인했다. 내비는 경로·모의주행이 없는 기본 화면 전면, 사용자10·car_service:found다. 기존 제품 앱·데이터는 변경하지 않았다.
- 시험 담당 세션은 독립 사본에서 작업했으며 제품 코드와 다른 작업자의 파일을 변경하지 않았다. 이후 사용자 승인으로 진단 소스·APK·재현 방법·공개 증거를 지원 PR로 게시한다. 별도 외부 문의는 보내지 않았다.

남은 일은 지정 설치 버전의 수동 재탐색 지원, Android GPS 입력 연동, 도로 기본 지도 제공 조건 확인과 제품의 안전한 도착 어댑터 연결이다. 지도 렌더링은 host라는 재현 가능한 대안 조건을 확보했지만 원래 조건의 오류가 없어진 것으로 기록하지 않는다. 이번 지원 PR 게시는 사용자의 후속 명시적 요청에 따른다. 내비 오류 전체 해결을 주장하는 PR이나 #80 자동 종료로 해석하지 않는다.

### #80 담당자 시험: Windows x86_64 — 2026-11-06~07

위 ARM64 결과와 별도 장비에서 담당자가 실행한 결과다. 입력은 내비 내장 모의주행과 에뮬레이터 입력이며 실차·Android GPS 연동·제품 정산 성공이 아니다. 시각은 KST다.

- **환경:** Windows 11 x64(메모리 7.7GB), Pleos Connect 2.1.0 / API 34 x86_64, 사용자 10, `emulator-5556`, 내비 `3.3.2-rc5-hotfix.3`, NaviService 3.0.4. 실행 스크립트에 `-gpu` 지정은 없다.
- **부하 조정:** 기본 상태에서는 부하가 30–40까지 올라 차량 서비스와 시스템 UI가 반복 종료됐다. 시험 동안 시스템 앱 7개(`ambientai`, `gleoai.model.provider`, `mediaapp`, `mediabridge`, `welcomescreen`, `connecttokensampleapp`, `servicemodepro`)를 끄고 시험 후 다시 켰다.
- **지도:** 모의주행 화면에서 밝은 바탕과 위치·목적지 마커·경로 표시를 확인했고, 도로 기본 지도는 없었다. 위 ARM64 결과의 GPU 비교와 같은 조건의 시험은 아니다.

#### 실제 거리와 긴 경로 도착 — 2026-11-07

지금까지 약 50m만 관측한 이유를 확인했다. 시험 목적지 42dot은 내비의 기본 위치 `37.4127212,127.094681`에서 직선 약 80m다. `6adf357`의 진단 앱(`navi-diagnostic`, NaviHelper 3.0.5)에 목적지 좌표를 인텐트로 받는 로컬 수정을 적용해 `poiId`를 비운 좌표로 요청했다. 수정 내용은 [patch](../navi-diagnostic/evidence/x86-destination-input.patch)로 남기고 진단 앱 소스에는 반영하지 않았다. 이 빌드의 APK SHA-256은 `a67a5d6082bfb9c908704488a74b2b10a6baef600498a7d1c241e109ee0289c0`이다.

**공개 근거와 담당자 보고.** 이 절의 x86_64 실행 중 50m·3.1km·56km 실행은 [시험 증거](../navi-diagnostic/evidence/README.md)의 `x86-*` 발췌와 화면으로 확인할 수 있다. 발췌는 원본 행 중 요청·경로·도착 관련 행만 고른 것이며 원본 행 수·SHA-256과 선택 기준은 `manifest.json`에 있다. 아래에서 **담당자 보고**로 표시한 항목은 원본이나 명령 출력을 제출하지 않아 공개 근거로 대조할 수 없다. 관측은 모두 이 설치 환경의 결과이며 공식 동작으로 확정하지 않는다.

| 시험 | 요청 | 관측 |
|---|---|---|
| 평택역 (실행 `ffa9e333-00d0-40c5-9c66-b48033cff3b5`) | `36.9908,127.0857`, `poiId` 빈 값 | 내비 화면 59분·56km, 경부고속도로 경유 안내. `onRouteStarted(duration=3540, distance=56000)`, `remainDistance=56000`, `remainTime=3480`, 목적지 좌표는 요청과 같고 주소는 평택시 평택동. 모의주행으로 경로를 따라 이동해 48000까지 줄어든 것을 확인하고 시험을 끝냈다. **이 경로의 도착은 시험하지 않았다.** 근거: `x86-56km-route-events.jsonl`, `x86-56km-guidance.png`, `x86-56km-simulation.png` |
| 판교역 (실행 `ced1fe0f-d932-4480-b713-e6a1c5de4388`) | `37.3948,127.1112`, `poiId` 빈 값 | 내비 화면 11분·3.1km. 아래 표와 같이 `onDestinationArrived`를 수신했다. 근거: `x86-3km-arrival-events.jsonl`(seq 371·431·784·8704), `x86-3km-simulation-panel.png` |

| 시각 | 판교역 관측 |
|---|---|
| 16:48:28.441 | `requestRoute` 요청 |
| 16:48:38.611 | `onRouteStarted(duration=660, distance=3100)`, 65ms 뒤 `onRouteEnded`, 다시 2ms 뒤 `ROUTE_DRIVE` |
| 16:49:45.879 | `naviMode=SIMULATION_GUIDING` |
| 16:53:48.413 | `remainDistance=10` |
| 16:53:49.533 | `remainDistance=0`, `remainTime=0` |
| 16:53:49.546 | 위치 조회의 routeId가 빈 값 |
| 16:53:49.846 | `onDestinationArrived(duration=244, distance=3153, name=PangyoStation, address=분당구 백현동)`, `staleConnection=false`. 이어서 `FREE_DRIVE` |

- **거리 값의 간격:** 2초 조회에서 `remainDistance`는 56000 근처에서 1000 간격, 3100~1000에서 100 간격, 1000 미만에서 약 30씩 줄었다. 정확한 전환 거리는 확인하지 않았다.
- **도착 값:** 시작 때의 예상 거리 3100과 도착 payload의 `distance=3153`은 역할이 다른 값이다. `duration=244`는 `SIMULATION_GUIDING` 전환(16:49:45.879)부터 도착(16:53:49.846)까지의 약 244초와 맞는다. 다른 입력에서는 확인하지 않았다.
- **좌표 요청:** `poiId`를 비운 두 요청은 모두 요청 좌표로 경로를 만들었다. 앞서 `poiId`를 함께 보낸 강남 요청이 42dot으로 채택된 결과와 비교하면 `poiId`가 좌표보다 우선하는 것으로 보이지만, 두 번의 관측으로 본 추정이며 공식 우선순위로 확정하지 않는다.
- **재부팅 뒤 이전 경로:** 평택 안내 중 에뮬레이터를 끄고 다시 켜자 내비에 경로 안내 재시작 확인창이 떴다(`x86-resume-prompt.png`). 이때 보낸 `requestRoute`(seq 112)는 약 52초 동안 경로를 만들지 않았고 `onError`도 없었다. 창에서 안내 종료를 고른 뒤 다시 요청하자(seq 371) 약 10초 뒤 경로가 시작됐다. 52초는 이 환경의 관측이며 SDK의 시간 초과 값이 아니다.
- **앱이 뒤로 갔을 때:** 56km 발췌에서 seq 208(16:25:16.440)과 seq 209(16:29:37.167) 사이에 기록이 없고, `onRouteStarted`(seq 235)는 진단 앱을 앞으로 가져온 16:29:38에 수신됐다. 보존 화면에는 56km 안내와 기기 시계 23:26이 표시된다(`x86-56km-guidance.png`). 로그의 16:26과 화면 시각에는 약 7시간 차이가 있어 동일 시간대로 직접 비교하지 않는다. 그동안 진단 앱의 `cgroup.freeze`가 1이었다는 것은 **담당자 보고**(명령 출력 미보존)다. 이 환경의 관측이며 일반 백그라운드 정책으로 확정하지 않는다. 도착 콜백이 같은 상태에서 어떻게 오는지는 시험하지 않았다. 판교역 시험은 진단 앱을 앞에 둔 채 도착을 받았다.

#### 50m 목적지의 도착 재현 — 2026-11-06

Release의 정상 권한 APK(SHA-256 `e67c2580dd75ba23780c0544fe12fadbd8ee5d0854bdf6d81b7fa0d60286ab1f`)로 위 재현 순서를 따랐다. 실행 ID는 `2fd60c40-c80e-4443-833e-3e3870e1a679`다. 원본 기록(1,765행, SHA-256 `4216c1fc4f8d871174a28ec70e4cbbe0cae7ead7fbb268902ef23d2231c585ba`)은 로컬에 보존했고, 발췌는 `x86-50m-arrival-events.jsonl`, 화면은 `x86-50m-simulation.png`다.

| 시각(KST) | 관측 |
|---|---|
| 23:02:00.506 | 42dot 좌표·POI로 `requestRoute` 요청 |
| 23:02:09.370 | `onRouteStarted(duration=60, distance=50)`, 13ms 뒤 `onRouteEnded`, 다시 4ms 뒤 `ROUTE_DRIVE` |
| 23:03:27–32 | 모의주행 중 `currentRouteIndex` 1→2→3, `remainDistance` 40→20→10. routeId 유지 |
| 23:03:33.892 | `remainDistance=0`, `remainTime=0` |
| 23:03:33.935 | 위치 조회의 routeId가 빈 값 |
| 23:03:34.019 | `onDestinationArrived(duration=7, distance=49, name=42dot, address=수정구 시흥동)`, `staleConnection=false` |
| 23:03:34.022 | `FREE_DRIVE` |
| 23:05:13.324 | 연결 해제. 그때까지 도착 콜백은 1회 |

#### 공식 샘플과 입력 시험

공식 [NaviHelper 샘플](https://github.com/Pleos-Playground/pleos-sample-navi-app) 2.5.0(SDK 3.0.5)을 로컬에서 빌드해 시험했다. 샘플 코드는 저장소에 넣지 않았다. 권한 시험에서만 로컬 복사본의 Manifest를 수정했다. **이 표와 아래 문단은 담당자 보고다.** 샘플 실행 기록은 제출하지 않았으며, 권한 미선언 결과는 위 ARM64 공개 근거와 같은 범주다.

| 시험 | 관측 |
|---|---|
| 내비 앱 미실행 중 `requestRoute`, `getDestinationInfo` | 각각 약 15초, 8초 동안 결과 콜백과 `onError`가 없었다. 내비 앱을 실행하자 `onNavigationStatus: INITIALIZING`이 왔다. |
| 경로 시작 콜백 | 약 9초 뒤 `onRouteStarted`, `ROUTE_DRIVE`, `onDrivingInfo`를 받았다. `onRouteEnded`도 함께 왔다. 다시 요청한 경로에서는 15초 안에 이 콜백들이 기록되지 않았지만 조회로 경로 생성을 확인했다. |
| 거리 단위 | 내비 화면 50m·1분과 `remainDistance=50`, `remainTime=60`이 일치했다. |
| 요청 목적지 | 샘플의 강남구 목적지(`poiId` 포함)를 요청했지만 현재 위치 부근의 주소와 거리 50m가 돌아왔다. 위 ARM64의 POI 채택 결과와 같은 범주로 보인다. 에뮬레이터 거리의 한계가 아니라는 점은 위 2026-11-07 시험에서 확인했다. |
| `routeId` | 샘플에서 재탐색 요청과 새 경로 요청 뒤에 값이 바뀌었고, 재탐색을 요청하지 않은 D 주행 시험 뒤에도 바뀌었다. 원인은 미확인이다. 위 ARM64의 재탐색 시험에서는 유지되어 결과가 다르므로 routeId 유지·변경에 의존하지 않는다. |
| `requestReRoute` | 15초 안에 `onReRouteEnded`가 기록되지 않았다. 위 ARM64의 30.760초 관측과 같이 완료를 확인하지 못했다. |
| `cancelRoute` | `onRouteCancelled`, `FREE_DRIVE`를 받았다. 이후 조회에서 이름·주소가 빈 값, `remainDistance=0`, `remainTime=0`이었다. |
| 권한 선언 | `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `NAVI_CUSTOM_ROUTE`, `NAVI_CUSTOM_ETC`는 모두 `normal`이다. 선언한 원본은 5개 모두 허용, 선언을 뺀 복사본은 0개 허용이었다. |
| 권한 미선언 호출 | 즉시 `SecurityException`이 발생했다. `requestRoute`, `requestReRoute`, `cancelRoute`는 `NAVI_ROUTE_SEARCH`, 조회 3종은 `NAVI_ROUTE`를 요구했다. `onError`와 결과 콜백은 없었다. 일부 권한만 뺀 경우와 도착 콜백에 필요한 권한은 시험하지 않았다. |
| 내비 위치 이동 | Virtual Console D·40 km/h 약 60초, `geo fix` 10단계, Extended Controls Routes 재생 약 41분, 체크섬을 계산한 `geo nmea`와 `geo fix` 약 40초를 각각 시험했다. Android GPS 값이 바뀐 시험에서도 내비 위치와 50m는 그대로였고 도착 콜백은 없었다. 일반 입력에서 `NMEA checksum mismatch: expected=47`을 재현했고 체크섬을 계산한 문장은 거부 기록이 없었다. |

내비 시작 전부터 Android GPS를 내비 위치와 맞춘 뒤 경로를 따라 이동하는 시험은 끝내지 못했다. 네 번의 실행에서 `system_server` Watchdog 재시작, adb 무응답, 화면 정지, 입력 포커스 없음이 나타났고, 원인은 미확인이다. 이때 PC 여유 메모리는 약 150 MB였다.

#### 게임 연결에 인계할 내용

| 대상 | 내용 |
|---|---|
| #39 도착 정산 | 콜백에는 routeId나 SDK 요청·응답 식별자가 없다. 앱이 경로 시작 때 목적지·routeId·세대를 보관하는 것은 필요하지만, 그것만으로 현재 경로의 도착을 입증하지 못한다. 예를 들어 A 경로 취소 → B 요청 → 늦게 온 A 도착은 보관한 B 값으로 구분되지 않는다. `staleConnection=false`도 같은 helper 객체라는 뜻이지 경로 세대 일치가 아니다. |
| #39 도착 정산 | 목적지·출처·관측 신선도와 취소·재탐색·연결 해제 뒤 지연 이벤트를 구분할 근거가 부족하면, 02 문서의 계약대로 확인 대기로 두고 지급하지 않는다. 이번 시험은 변경 없는 한 경로의 정상 종료만 관측했고, 취소·재요청 뒤 지연 도착을 구분하는 교차 세대 시험은 하지 않았다. routeId가 콜백보다 먼저 빈 순서도 이번 두 관측의 결과이며 순서 보장으로 쓰지 않는다. |
| #39 도착 정산 | `remainDistance=0`, 빈 routeId, `FREE_DRIVE`는 취소에서도 나타나므로 도착이 아니다. `onRouteEnded`는 경로 시작 때도 오므로 도착이나 종료 근거로 쓰지 않는다. |
| #39 도착 정산 | 콜백이 없으면 PRD의 기록 단절 규칙대로 확인 대기로 둔다. |
| #38 목적지 변경 | 재탐색 완료를 확인할 수 없어, 새 목적지에 새 `requestRoute`와 앱 세대를 쓰는 방식을 검토할 수 있다. 이 문서는 그 방식을 제품 결정으로 확정하지 않는다. #38의 변경 1회·거리 합산·기존 결제와 의뢰 보존, 이전 경로의 지연 이벤트 차단을 별도로 만족해야 한다. |
| #39 도착 정산 | 이 환경에서는 앱이 뒤에 있는 동안 콜백을 늦게 받았다. 콜백 수신 시각을 실제 도착 시각으로 쓰지 않는다. 뒤에 있는 동안의 도착 콜백은 미시험이다. |
| #37 후보 거리 | `remainDistance`를 m 단위 예상 거리로 사용할 수 있다. 3.1km와 56km로 근거리·원거리를 실제 내비 값으로 확인했다. 먼 거리는 1000m 간격이라 구간 경계 근처에서 오차가 있을 수 있고, 중거리는 직접 시험하지 않았다. |
| #37 경로 요청 | 이 환경에서는 `poiId`를 비운 좌표 요청이 요청 좌표로 경로를 만들었다. 예상 경로 거리는 목록 생성에만 쓰고 기본 주행 수익 거리로 쓰지 않는다. |
| #37·#38 경로 호출 | 내비 앱 미실행이나 이전 경로의 재시작 확인창이 있을 때 오류 없이 응답이 없었다(8·15·52초 관측). SDK의 시간 초과 값이 아니므로 시간 초과는 구현에서 정한다. 권한 없음은 `onError`가 아니라 호출 시점의 `SecurityException`으로 처리한다. |
| #53 화면 | 시간 초과, 권한 없음, 확인 대기를 도착 실패나 몰수로 표시하지 않고 구분한다. |
| #28 통합 시험 | 실제 도착 시험은 내장 모의주행으로 재현하고, 입력 출처를 모의주행으로 기록한다. 실차나 Android GPS 성공으로 기록하지 않는다. |

#### 완료 조건 판정

| 완료 조건 | 담당자 판정 |
|---|---|
| 버전·권한·거리 단위 | 충족. ARM64·x86_64 두 환경의 버전과 화면 50m 대조, x86_64의 3.1km·56km 대조. 거리 정확도는 범위 밖 |
| 도착·경로 식별·재탐색·취소·권한 거부 | 충족하지 않음. 도착(50m·3.1km)·취소·권한 미선언은 재현했다. 재탐색은 두 환경 모두 완료 신호가 오지 않는 결과를 한계로 기록하지만, 재탐색 성공이나 현재 경로 식별 검증을 대신하지 않는다. 취소·재요청 뒤 지연 도착을 구분하는 교차 세대 식별은 검증하지 않았다 |
| 확인 불가 경계와 #39·#53·#28 대조 | 위 표로 문서 대조를 마쳤다. 게임 실행 검증은 아니다 |
| 환경·기대값·실측·미수행 기록 | 이 절에 기록했다 |

해결되지 않은 항목은 수동 재탐색 완료, Android GPS의 내비 반영, 도로 기본 지도 제공 조건, 앱이 뒤에 있을 때의 도착 콜백이다. 모두 해결된 것으로 기록하지 않는다. Android GPS와 도로 지도는 #80 완료 조건이 아니며, 시험과 시연에는 내장 모의주행을 사용할 수 있다.

## 제출 상태

공식 일정·양식은 [공모전 자료](contest/README.md)를 따른다. 1차 접수 마감은 2026-11-15 23:59 KST이며, 앱 개발 산출물은 공식 필수 기획서와 별도로 준비한다.

| 제출 항목 | 상태 |
|---|---|
| 공식 양식 원본 | 다운로드 보관 완료 |
| 팀 기획서 초안 | 원본 PDF 보존. 이후 사용자 결정에 따른 렌터카 방향을 로컬 Markdown에 반영. 제출용 수정 PDF·경제 밸런스·자산 권리·실제 앱과의 일치는 미완료. [제품 기준](01_PRD.md) 참조 |
| 게시 범위 | 문서·조사 데이터·빈 공식 양식·Gradle 및 협업 기본 설정. 원본 PDF는 로컬 보관하며 게시하지 않음. 기존 앱·핵심 테스트 소스는 기술 검증 기반으로 공유하며 새 렌터카 기능은 미구현 |
| APK·소스 대응표 | 상단 로컬 Debug 해시·검증 조건 기록. 최종 제출 Release 버전 대응은 #29 |
| 실제 앱 시연 영상·대표 화면 | 미생성 |
| 개인정보·동의·서명 | 임의 작성하지 않음 |
| 최종 기능 범위 일치 확인 | 앱·문서·영상 준비 후 필요 |

**제출 준비 완료 상태가 아니다.** 외부 게시·원격 푸시·이메일 발송은 별도 지시를 따른다.

## 제출용 Pleos 에뮬레이터 검증표

이 표는 [#28 통합 인수](https://github.com/Yaho03/drive-growth/issues/28)의 실행 순서와 인계 조건을 정의한다. 제출에 포함할 기능과 표현 수준은 [#25](https://github.com/Yaho03/drive-growth/issues/25)에서 정한다. [공모전 가이드](contest/01_CONTEST_GUIDE.md)는 에뮬레이터를 통한 검증을 안내한다. 별도 실차 시험 [#82](https://github.com/Yaho03/drive-growth/issues/82)는 실차 결과를 주장할 때 적용한다.

### 지금 재현할 수 있는 기준 시험

Pleos API 34 기준 APK와 시험 환경이 필요하다. 이전 실행 결과는 [#13 차량 입력 실행 검증](#13-차량-입력-실행-검증)에 있다. 아래는 기존 앱으로 재현할 수 있는 범위이며, 이 표 작성 시 재실행 결과를 추가하지 않았다. 제출 APK가 준비되면 동일 조건으로 다시 검증한다.

| 시험 | 입력·절차 | 기대 결과와 근거 |
|---|---|---|
| 기준 APK 설치·실행 | #12 환경과 앱 버전을 확인한 뒤 Pleos AVD에 기준 APK를 설치하고 P에서 실행 | 앱이 열리고 APK·소스 버전을 식별할 수 있다. 렌터카 첫 차량 지급은 아래 통합 시험에서 확인한다. |
| 주행 조작 잠금 | Virtual Console에서 D·0 km/h로 바꿔 앱 재진입을 시도하고, P·UX 허용으로 복귀 | D 정차에서 게임 메뉴가 잠기고, P 복귀 후 조작이 허용되면 메뉴가 돌아온다. 기어·UX와 화면을 함께 기록한다. |
| 권한 거부 | 전용 시험 AVD에서 속도 권한을 거부한 채 앱을 열고, 시험 후 원래 권한을 복구 | 권한 없음과 조작 잠금이 표시된다. 신호 누락을 정상값 0으로 간주하지 않는다. |
| 이동·앱 종료·전원 모사 | 콘솔로 이동 후 P로 돌아오거나 이동 중 앱을 종료해 재실행한다. 전원 모사는 P에서 별도 수행한다. | 확인된 이동·중단 기록이 복원되며 중단 구간 보상이 새로 생기지 않는다. 에뮬레이터 전원 모사와 실제 IGN OFF를 구분한다. |

### 구현 인계 후 실행할 통합 시험

해당 행의 구현과 PR 검토 결과가 재현 가능한 같은 버전으로 인계되면 그 행부터 실행한다. 기본 계획은 시험용 프로필·고정 콘텐츠·모의 도착 증거를 사용한다. 실제 SDK 콜백을 사용하는 시험은 별도 입력 출처와 조건을 기록한다. 구현별 예외와 경계값은 아래 상세 시나리오를 따른다.

| 실행 단계 | 입력·절차 | 통과 조건 | 실행 전 필요한 결과 |
|---|---|---|---|
| 설치·초기 지급 | 제출 후보 APK를 깨끗한 시험 프로필에서 열고 첫 차량을 선택한 뒤 재실행 | 초기 재화·차고·첫 차량·첫 보상이 프로필당 한 번 지급되고 선택이 유지된다. | #50·#56. 공통 저장·콘텐츠는 #34·#35, 기존 #62 결과 참조 |
| 탁송 선택·변경·취소 | 고정 거리 입력으로 수익/구매 후보 선택, 목적지 변경 1회, 두 번째 변경과 구매 취소를 각각 시도 | 관측 거리와 예상 거리 분리, 변경 차액과 취소 환급 구분, 중복 차감·지급 없음 | #37·#38·#53. 실제 내비 거리 연결은 #80 |
| 이동·조작 잠금 | 탁송 중 D 정차·이동·UX 누락·권한 거부를 시험하고 제한 중 재진입, 이후 P로 복귀 | 화면·조작·성장 연출 제한 유지. 확인 불가를 표시하며 P만으로 도착이나 보상을 확정하지 않는다. | #36·#51. 주행 중 기록 활성화는 #81의 해당 정책 근거 |
| 도착·주차 정산 | 활성 경로와 같은 출처의 도착 증거를 주입한 뒤 P에서 정산을 열고, 이전 경로·다른 출처·누락을 별도로 시험 | 유효 증거만 정산에 쓰며 수익/구매 결과와 기본 주행 수익을 구분한다. 잘못된 증거와 단순 정차는 지급 대기 또는 확인 불가로 남긴다. | #39·#53·#54. 실제 도착 콜백 사용은 #80 |
| 차량·가게 변화 | 정산 뒤 보유 차량·차고를 조회하고, 제출 범위의 영업·시설·가챠·차량 관리에서 성공과 실패를 재현 | 재화·개체·결과가 화면과 원장에서 일치한다. 용량 부족·사용 불가 상태에서 차감과 개체 변경이 발생하지 않는다. | #46~#50·#52·#54~#56 |
| 저장 실패·재실행 | 확정 전 실패와 응답 수신 전 종료를 각각 주입하고 같은 요청 ID로 재시도. 미확인 결과를 다시 열기 | 전체 이전 상태 또는 확정 상태만 복원된다. 같은 요청은 같은 정산 결과를 반환하고, 결과 열람은 재지급을 발생시키지 않는다. | #34·#39·#43·#54, 관련 PR의 실제 저장 근거 |
| 폼 팩터·추가 기능 | 주차 상태에서 별도 창과 지원 크기로 핵심 화면을 열고, #25에서 포함한 휴식·퀘스트·이벤트·랭킹의 정상/실패를 시험 | 상태·가독성·접근 가능한 조작이 유지되고 주행 중 화면·알림 제한을 지킨다. 채택 기능별 실패 이유를 구분한다. | #51·#57·#58. 음성·위치 기능의 허용은 #81 |

각 실행에 소스 커밋·APK SHA-256·Pleos 이미지/기기·입력 출처·재현 절차·기대/실제 결과·로그 또는 화면 증거를 남긴다. 결과 상태는 통과·실패·미실행으로 기록하고, 미실행에는 대기 이슈 또는 사유를 적는다. 제출 APK와 다른 버전에서 얻은 결과는 참고 증거로 연결한다.

### 렌터카 상세 검증 시나리오

2026-11-03: 기존 13개 엔진 테스트는 아래 새 게임 규칙의 성공 근거가 아니다. 새 경제 규칙·영속 저장·화면 연결의 구현과 재검증이 필요하다. 미정 값은 시험 전에 합의하고 고정한 기대값으로 검증한다.

| 입력·절차 | 기대 결과 |
|---|---|
| 수익/구매 탁송 각각 선택 → 이동 → 도착 → 주차 후 앱 복귀 | 유형별 보상·구매 차량 1회 지급, 기본 주행 수익과 구분. 도착·주차 기준은 기술 확인 후 고정 |
| 실제 30km 이동 후 새 목적지까지 예상 20km로 변경 | 목록 기준 50km, 기본 수익에 예상 20km 선지급 없음. 변경 희귀도 하락 적용, 취소 수수료와 혼동 없음 |
| 구매 취소·재시도·재실행 | 확정 수수료만 공제해 1회 환급. 차량 미지급. 변경 사용 횟수 유지 |
| 주차 후 29분·30분·31분 복귀(앱 꺼 둠·켜 둠 각각) | 각각 전 구간 정상, 30분 정상, 30분 정상+1분은 정상의 30%. 두 경우 같은 결과. 8시간 직전·정확히 8시간·초과도 검증(2026-11-04 #24 결정으로 1시간 기준에서 변경, 재검증 필요) |
| 같은 종료 구간 반복 복귀·시계 역행·앞당김 | 같은 구간 재지급 없음. 재접속으로 정상 30분이 다시 시작되지 않음. 역행 구간 0, 앞당김은 최대 8시간까지만 인정 |
| 일일 보상 거리 상한 직전·정확히 상한·초과, 자정 걸친 주행 | 500km 상한은 기본 주행 수익에만 적용하고 탁송·영업 수익은 영향 없음. 자정을 넘긴 주행은 출발일로 계산. 원본 기록 거리와 보상 반영 거리 분리 |
| 같은 차종 두 대 → 한 대 강화 재료/판매/폐차 → 재실행 | 개체별 보유·대여 상태 유지, 소비한 개체의 중복 사용·중복 대금 수령 없음 |
| 가상 사고 → 수리 → 재대여·판매 | 수리 후 회복 상태와 사고 이력 분리. 합의한 매각 감액 적용, 실제 운전 평가 영향 없음 |
| 구입 뽑기권(500)·무료 뽑기권·첫 지급으로 얻은 Lv1 차량을 각각 판매, Lv20 사고 없는 구입 뽑기권 차량 판매 | 판매가 300·0·0, Lv20은 395(500의 79%). 폐차 기준액도 같은 구매가 사용. 희귀도·차종 가격은 처분 기준액에 쓰지 않음 |
| 시설 조건 경계·가챠/영업 결과 저장 중 강제 종료 | 해금·지급·차감의 일관성 유지. 재시작으로 결과 재추첨·중복 지급하지 않음 |
| 휴식 후 재출발·반복 휴식 | 휴게소·졸음쉼터에서 5분 이상(직전 1시간 이상 운전) 쉬고 출발하면 기본 주행 수익 1.5배 20분 자동 시작. 휴식 중 소모 없음, 재휴식 시 중첩 없이 20분 갱신, 500km 상한 유지. 일반 주차·조건 미달은 버프 없음 |
| 데이터 단절·미도착·도착 후 미확인 종료 | 가짜 기록·도착을 생성하지 않음. 확인 대기 보관 후 재확인, 24시간 경과 시 구매 탁송 전액 환급·수익 탁송 소멸을 1회만 처리, 확정 결과 다음 실행에서 조회 |
| 구매 탁송 목적지 변경 → 다른 가격 차량 선택 / 두 번째 변경 시도 | 기존 결제금 보류 후 차액만 추가 결제·환급, 취소 수수료 없음. 두 번째 변경은 차단되고 포기만 가능 |
| 대여 중·반납 중·수리 필요 차량에 레벨업·강화·재료·판매·폐차·수리 요청 | 대여·반납 중은 전부 거절. 수리 필요는 수리·판매·폐차만 허용. 거절 시 재화·개체 변화 없음 |
| 레벨 19·20 차량에 중복 재료 사용, 레벨 1 진행량 0%에 고급 재료 | 19는 20에서 멈추고 남는 진행량 버림, 20은 거절. 고급(150%)은 레벨 2·진행량 50%. 희귀도가 달라도 같은 차종이면 재료 가능. 재료는 성공한 경우에만 한 번 소비 |
| 차고 3칸 가득 참 → 가챠 / 구매 탁송 도착 | 가챠는 공간 부족으로 거절되고 뽑기권·카운터 변화 없음. 탁송 차량은 임시 보관함 보관, 칸 확보 후 1회 수령, 24시간 경과 시 수수료 5% 공제 환급을 1회만 처리 |
| 가챠 29회 연속 희귀 미만 → 30회째, 희귀 이상 획득 후 다음 뽑기 | 해금된 차종 중 하나에 희귀 이상을 붙여 한 번 지급하고 카운터 0. 재실행해도 결과·카운터 재추첨 없음. 화면 확률은 실제 추첨 확률과 일치 |
| 해금된 차종이 없는 가챠 콘텐츠 / 보장 회차에 후보 누락 | #35는 해당 가챠 콘텐츠를 유효하지 않은 것으로 판정. 실행 중 #47은 차감 전에 실패 사유를 안내하고 지급을 거절. 뽑기권·게임머니·카운터 유지, 잠긴 차량이나 낮은 희귀도 대체 지급 없음 |
| 첫 차 선택·개업 선물 뽑기권 → 앱 재실행·반복 요청 | 초기 1,000·3칸·첫 차·뽑기권 1장 한 번만 지급, 선택 취소 불가 |

향후 대표 시연은 가게 확인 → 탁송 선택 → 주행 UI 제한 → 도착·주차 후 정산 → 차량 영입·영업/가게 변화 → 재실행 복원 순서다. 모의 입력·SDK 수신·실차 실행을 구분하고 시연에 포함한 추가 기능의 실패 조건도 확인한다. 현재 APK·시안·시연 영상은 이 문서 변경으로 생성되지 않았다.

2026-11-03 로컬 문서 반영 검사: `python3 scripts/check_repository.py` 통과(필수 파일 19개·문서 19개·로컬 링크 127개). 변경 전후 해시로 검사한 코드·빌드·CI·검사 설정에 변경 없음. 기존 Markdown 8개 수정, 신규 문서·앱 구현·앱 실행 검증·원격 게시·GitHub 이슈 수정은 수행하지 않음. 위 렌터카 검증 시나리오는 모두 미수행이다.

## 문서 게시·이슈 갱신을 위한 현황 확인

2026-11-03 원격 `main`의 출발 버전은 `7f8d554a28513a2ee55dc6d7a5c4ed6abb6467a4`다. 로컬은 커밋 없는 작업 공간에서 파일들이 untracked로 표시되어 원격과 동일한 코드 버전이라고 부를 수 없다. 원격 기반의 별도 작업 폴더에서 허가된 Markdown만 선별해 게시한다.

현재 읽기 검사에서 Activity·Runtime·차량 입력·저장·서비스와 Manifest를 확인했다. 이전 표의 Manifest 누락 실패와 화면 단독 컴파일은 당시 결과로 보존하며 최신 빌드 판정으로 사용하지 않는다. 현재 연결 코드는 [기술 문서](02_SYSTEM_ARCHITECTURE.md)의 재확인 절에 기록했다. 이번에는 앱 빌드·실행·저장 테스트를 다시 수행하지 않았다. 이슈 #11·#13·#20의 현황·검증 작업이 남아 있다.

사용자는 문서 게시와 기존 열린 이슈의 최신 기획 반영을 요청했다. 새 구현 이슈 생성·앱 구현·원격 보호 규칙 변경은 이번 범위 밖이다. 이슈에 사용자 결정을 기록하되 팀 회의 개최나 사람 리뷰 승인으로 표시하지 않는다. 게시 PR과 원격 이슈의 실제 갱신 결과는 완료 보고에 연결한다.

## PR #23 머지와 현황 조사 인계

2026-11-03 사용자 지시로 [PR #23](https://github.com/Yaho03/drive-growth/pull/23)을 main에 머지했다. 머지 커밋은 `3d91568e9d9025f390c95909cc26840a5ab51025`이며 원격 파일 목록에 app/src·core/src가 없음을 확인했다. 문서 CI 성공, Android 검사는 소스 미게시 조건으로 생략됐다. 사람 리뷰 승인은 없으며 명시적 사용자 머지 요청과 잔여 한계를 PR에 남겼다.

[#11](https://github.com/Yaho03/drive-growth/issues/11)에 로컬 소스 11개·설정 5개의 SHA-256과 기능별 정적 확인·미검증·후속 작업을 기록했다. 확인한 목록의 집계 SHA-256은 `3bdc56f4102589631eb6e716844689ed548566f9c4cf3b41584b989d3233b70f`이며 계산 입력은 이슈에 기록한 경로, 탭, 파일 SHA-256, 줄바꿈을 경로순으로 연결한 UTF-8 문자열이다. 최신 앱 실행은 수행하지 않았다. 기존 코드 검증은 #20, 환경은 #12, 정책·실제 신호는 #13에 인계한다.

#14의 미완료 안건은 #24·#25로 분리하고 기존 결정 기록을 보존한다. 팀 회의 개최·미정 규칙 완료·앱 구현 완료를 주장하지 않는다. 작업별 선행조건은 [진행 계획](08_PROJECT_PLAN.md)의 최신 연결을 따른다.

## 제출 초안 인계

[공식 심사용 항목 1–8 제출 초안](10_SUBMISSION_DRAFT.md)을 작성했다. 원본 양식·개인정보·서명은 수정하지 않았다. 현재 기획 문안이며 실제 화면·Release APK·영상·최종 접근권 및 제출 검사는 #29에서 수행한다.

## 공통 소스 최초 CI 확인

PR #32 최초 Android CI는 앱 컴파일 전 `setup-android@v3`의 기본 `tools` 패키지를 찾지 못해 실패했다. 액션의 공식 `packages` 입력으로 필요한 `platform-tools`만 요청하도록 수정했다. API35·Build Tools35 설치 및 실제 Gradle 검사는 그대로 유지하며 실패를 생략 처리하지 않는다. [액션 입력 근거](https://github.com/android-actions/setup-android/blob/v3/action.yml). 재실행 결과는 PR 검사에서 확인한다.


## #34 거래 원장 SQLite v1 본인 검증 (2026-11-04, 경민 작업 환경)

`SqliteGameChangeStore`를 공통 `GameChangeStore` 계약의 영속 어댑터로 추가했다. 앱 조립·화면·렌트 계산에 연결하기 전의 저장 기반 검증이며, #34 전체 종료나 #62 교차 검증 완료를 의미하지 않는다. 별도 메모리 원장을 제품에 추가하지 않고 #33 fixture와 같은 계약을 소비한다.

- 환경: macOS arm64, Temurin JDK 17.0.21, 프로젝트 Gradle 8.11.1·API 35·Build Tools 35.0.0, Pleos Connect 2.1.0 / Android 14 / `emulator-5554`.
- 기본 검사: `:core:test :app:assembleDebug :app:lintDebug`, 핵심 테스트 39개 실패 0, APK 빌드·Lint 성공.
- 기기 검사: `:app:connectedDebugAndroidTest`, 총 20개 실패 0(기존 SnapshotStore 8개 + 새 거래 원장 12개).
- 저장소 검사: `python3 scripts/check_repository.py`, 필수 파일 19개·문서 20개·로컬 링크 150개 통과. `git diff --check` 통과.

| 입력·시험 | 기대·실제 결과 |
|---|---|
| r1에 100 지급, 반복·DB 재개방, 같은 r1에 200 지급 | 원래 정산 ID·시각·잔액 100 반환, 결과 한 개, 다른 명령은 충돌 |
| 종류·규칙 버전·금액 생략·차량 변경으로 요청 ID 재사용 | 모든 변형 거절, 명령 전체 비교 |
| 두 DB 연결에서 같은 요청 동시 전달 | 두 응답의 정산 일치, 지급 100·결과 한 개 |
| 같은 차량 revision의 갱신·판매 동시 요청 | 하나만 성공, 다른 하나 충돌, 잔액·차량·정산 함께 확정 |
| 잔액·차량·결과 INSERT 후 COMMIT 이전 SQLite 예외 주입 | 재개방 시 잔액 0·차량/결과 없음, 같은 요청 재시도 성공 |
| COMMIT 결과를 사용하지 않고 닫은 뒤 재시도 | 원래 결과를 반환하고 중복 지급 없음(응답 유실 모사) |
| 재화 부족·Long 오버플로·없는 차량·중복 차량 ID | 실패, 부분 차감·부분 개체 생성 없음 |
| 차량만 추가, 이후 차감과 판매, 재개방 | 잔액 150·revision 1 유지, 보유 목록에서 판매 차량 제외 |
| 결과 반복 조회·열람 처리·재개방 | 잔액 불변, 열람 상태만 유지, 없는 정산은 DATA_UNAVAILABLE |
| 실제/모의 출처를 같은 DB 또는 명령으로 혼용 | 거절, 원래 원장 유지 |
| 미래 스키마 99·알 수 없는 버전 0·손상 파일 | STORAGE_FAILURE, 파일 바이트 보존, 자동 초기화·삭제 없음 |

한글 프로젝트 경로의 기본 빌드에서는 core 타입을 소비 모듈이 찾지 못했다. 같은 소스를 영문 산출물 경로로 설정하면 통과했다. 이번 검사는 로컬 전용 init script에서 모든 프로젝트의 `layout.buildDirectory`를 `/tmp/drive-growth-build/<project.name>`으로 지정하고 JDK 17로 실행했다. 재현 시 같은 내용의 init script를 만든 뒤 `./gradlew -I <init-script> :core:test :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest`를 사용한다. init script·SDK 경로·IDE가 추가한 `gradle.properties` 변경·임시 로그/산출물은 게시하지 않는다. 일반 영문 경로에서는 저장소 기본 명령을 사용하며, Android Studio의 한글 경로 일반 빌드 문제는 해결됐다고 표시하지 않는다.

미검증·인계: 위 실패 시험은 DB 연결 재개방·예외 주입이며 OS 강제 종료/전원 차단·실제 디스크 용량 부족·실차 시험이 아니다. 지원하는 이전 스키마가 없으므로 자동 이전·자동 백업은 미제공이며 수동 원본 보존 정책을 구조 문서에 기록했다. 다중 차량 소비·의뢰·대여·시간 구간·재추첨 방지 상태는 v0 계약 밖이다. D의 공통 앱 조립과 결과 화면 연결, #62에서 지정된 다른 팀원의 교차 검증이 남아 있다. #34 체크박스나 후속 차단 관계를 본인 시험만으로 일괄 완료 처리하지 않는다.

## #51 공통 화면과 콘텐츠 연결

- 기준 소스는 `main`의 `8f7e83a`다. Android Views 화면에서 가게 잔액, 보유 차량, 미확인 정산을 모의 SQLite 원장에서 조회한다. `app/src/main/assets/content/`의 차량 사실·게임 정의 파일은 #35의 5대 시험 입력을 패키징한 것이다. 콘텐츠 버전은 `fixture-content-v0`이며 화면에 모의 원장과 시험용 콘텐츠를 표시한다.
- 전경 차량 표본에서 P·정차·UX 허용을 연속 확인한 경우에만 화면을 연다. 오래된 표본, 알 수 없는 UX, 모의 표본, Activity 일시 정지에서 제한 상태를 유지한다. 제한이 걸린 뒤 반환된 조회 결과는 버린다. 콘텐츠 읽기나 원장 조회 실패는 다시 시도할 수 있는 오류로 표시한다. 조회 중 중복 요청을 막으며, 페이지 선택은 Activity 저장 상태로 복원한다.
- 로컬 `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 통과. 코어 50개·앱 단위 2개를 확인했다. Pleos ARM64 `emulator-5556`에서 앱/시험 APK를 설치해 기기 시험 21개를 통과했다. 새 기기 시험은 패키징 콘텐츠의 재조회와 버전 일치를 검증한다.
- 실제 앱 Activity의 실행은 확인했으나 전면 UI 덤프가 내비게이션 창을 반환해 화면 조작 증거를 확보하지 못했다. #36의 최종 조작 허용 관측 연동, D 정차/P 복귀 화면 확인, Back·CCP·좁은 창·Activity 재생성의 실제 화면 검증은 #51 본인 검증·PR 검토와 #28 통합 검증에 남긴다. 차량 주행 기록은 이 화면에서 시작하지 않으며 원장 변경 명령은 개별 기능 구현에서 연결한다.

### #51 Pleos 화면 추가 시험

2026-11-04, PR #89 APK를 설치한 Pleos ARM64 에뮬레이터에서 시험했다. 화면이 검고 차량 서비스가 응답하지 않던 환경은 에뮬레이터를 재부팅해 복구했다.

| 절차 | 확인 결과 |
|---|---|
| 주차 상태에서 가게 → 보유 차량 → 미확인 정산 → Back | 빈 차량·정산 목록을 표시했고 Back으로 가게 잔액 화면에 돌아왔다. |
| 미확인 정산에서 화면 크기를 800×480으로 변경 | `wm_relaunch_resume_activity` 로그에서 Activity 재생성을 확인했다. 선택한 화면과 콘텐츠 버전 `fixture-content-v0`이 유지됐다. 시험 후 크기 설정을 원복했다. |

이 시험 당시의 #36 신호 미연결과 #51·#63 인계는 과거 기록이다. 현재 main에는 PR #93의 차량 신호 소비 경로가 반영됐다. 같은 버전의 D 정차·제한 중 재진입·P 복귀, CCP와 실제 분할 창의 남은 실행 결과는 #51 본인 검증·PR 검토와 #28 통합 검증에서 확인한다. 이 과거 APK 시험만으로 최신 연결 빌드의 전체 검증을 완료 처리하지 않는다.

### #51 Back·창·입력 후속 본인 검증 — 2026-11-07

출발 소스는 `origin/main c57cb32304129124bad97353442f8a51e954b527`이다. 아래는 macOS ARM64에서 직접 실행한 결과이며, [팀원의 Windows/x86_64 보고](https://github.com/Yaho03/drive-growth/issues/51#issuecomment-6689704196)를 이 세션의 재실행으로 표시하지 않는다. #100 Garage·경제·내비·기록 서비스는 수정하지 않았다.

- 환경: Pleos Connect 2.1.0 / API 34 ARM64, Emulator 37.2.13.0, 사용자10, `emulator-5556`, ADB5038. 기존 데이터 유지, 별도 창·host GPU·RAM4096MB·스냅샷 로드/저장 없이 기동했다. 기존 실행 AVD·연결 장치가 없음을 먼저 확인했고 메인 세션과 사용 범위를 조율했다.
- 기본 표시: 1080×600px / 120dpi. 실제 앱 창은 `(360,57)–(1062,495)`, 702×438px / 936×584dp였다. 플랫폼 신호는 `InputSource.VEHICLE`, 원장·콘텐츠는 화면에 `모의 원장 · 시험용 콘텐츠 · fixture-content-v0`로 표시한다. 에뮬레이터 신호를 실차로 부르지 않는다.
- **기기 검증 APK** SHA-256: `1ba2f47f9dee687f53be3a851d614b6362d1cd76a09d73c7b06fc9863a01f284`. 출발 main에 이번 앱 코드를 적용한 이 APK에서 창 숨김·복귀·재생성·22개 기기 시험을 확인했다. 표 A의 화면·task와 재생성 화면은 이 버전의 근거다. 기존 개발 서명과 일치함을 확인한 뒤 데이터 유지 업데이트했다. 서명키·APK·전체 시스템 로그는 게시하지 않는다.
- **최신 main 통합 APK** SHA-256: `30b294f4107cf209657f42b49edd6b752aaeef3a46175877e4b1be8e17e1fb37`. main `e7a1bcca218f89f870e9c342ecb56c39cafa48bf`의 #100·#102 결과를 보존해 rebase 후 빌드했다. 앱 코드 변경은 동일하지만 core가 달라져 APK가 바뀌었다. 이 버전의 [기기 재검사는 시험 시작 전 Process crashed로 중단](evidence/issue51-input-lifecycle/rebased-instrumentation.txt)됐으며, 앞의22개 통과를 이 APK 성공으로 옮기지 않는다. 최신 통합 APK의 기기 재검증은 남아 있다.
- 중간 진단 빌드 SHA-256: `eeac0ed1d52791125ebcac3ab75e23e05143ef91f27751f55d08937bf2b05954`. 창 표시 보완 전의 DISPLAY2·중복 원인·전체 표시/창 크기 시험은 이 단계의 근거다. 원인 진단 자료로 유지하지만 최종 APK에서 다시 실행한 것으로 세지 않는다. 이 중간 APK 파일은 별도로 보존하지 않았고 당시 빌드의 해시와 로그·화면을 보존했다. 최초 수정 전 실패는 출발 main의 근거이며 해당 APK 해시는 별도로 남기지 못했다.

| 항목·입력 | 직접 재현·수정·결과 | 한계·남은 확인 |
|---|---|---|
| A. 최상위 Back | 수정 전 Activity1개에서도 일반 Back 후 게임이 남았다. `moveTaskToBack(true)`만으로는 포커스만 이동하고 `RESUMED`·창 표시가 유지됐다. 최상위에서 관측·gate를 정리하고 `setVisible(false)`와 Task 뒤 이동을 함께 수행하도록 보완했다. 일반 Back·CCP `inject-key 4` 후 [실제 게임 창 숨김](evidence/issue51-input-lifecycle/final-ccp-hidden.png), [같은 Activity 복귀](evidence/issue51-input-lifecycle/final-ccp-return.png)를 확인했다. task1003830·Activity `8ed4066` 유지, focus는 홈→앱으로 바뀌었다. | 공개 창 표시 API를 사용한 다중 창 보완이며 Pleos 공식 예제 자체와 구분한다. 다른 Android 버전·에뮬레이터 툴바의 세모 버튼 전달은 미검증이다. |
| B. 중복 Activity | 깨끗한 `MAIN/LAUNCHER`, flags `0x10200000` 재진입을 반복해 task1003826·Activity `2e4848e` 한 개 유지. `am start -n`만 반복하면 같은 task에3개가 생겼다. 따라서 launchMode는 바꾸지 않았다. | 런처형 Intent와 실제 런처 UI의 버튼 실행은 구분한다. 공개 `ACTION_APP_GRID`로 AppGridActivity 전면 진입은 확인했지만 아이콘 목록이 표시되지 않아 실제 앱 목록 버튼 재진입은 미검증이다. 가상 표시 시험의 `MULTIPLE_TASK`·component task가 후속 시험을 오염시킨 구간은 제외하고 task만 정리해 재시험했다. |
| C. DISPLAY2 UX | Android 개발자 overlay `800x480/120`에서 [제한 화면](evidence/issue51-input-lifecycle/display2-parked.png), Activity `displayId=2`, `speed=OK gear=PARK ux=true` 재현. 실제 창800×480px / 1067×640dp. 표시 속도0km/h, SDK 속도 숫자 원값은 별도 수집하지 않았다. 시스템 UXR 구성에는 display0만 있고 `DO=false UxR=0`이었다. 조회 대상을 표시하는 로그만 추가했다. | 공식 FormFactorConsole 실행과 구분한 진단이다. 공개 무인자 UXR getter는 연결 Context의 display를 사용한다. 미구성 display는 완전 제한으로 귀결되는 AOSP 경로와 일치하지만 OEM 동일 구현을 확정하지 않는다. display0 허용값으로 대체하거나 P만으로 열지 않았다. |
| D. 크기·복원 | 기본 앱 창에서 터치로 가게·보유 차량·미확인 정산·새로고침에 접근했다. [정산 선택과 콘텐츠 버전은 Activity 재생성 후 유지](evidence/issue51-input-lifecycle/final-recreated-results.png). 전체 `wm size 800x480`에서는 ARM64도 앱 bounds가1062px까지 남아 [잘림](evidence/issue51-input-lifecycle/whole-display-800x480.png)을 재현했고 [원복](evidence/issue51-input-lifecycle/whole-display-restored.png)했다. `am task resize` 요청도 실제 bounds가 바뀌지 않아 좁은 창 통과로 세지 않았다. | 전체 표시 변경·앱 창·가상 표시·팀원841px 분할 창은 다른 시험이다. Go·Host Display Bridge가 준비되지 않아 공식 FormFactorConsole 프리셋 시험은 미수행. DISPLAY2에서는 제한을 유지하므로 게임 콘텐츠 배치는 미검증이다. |
| E. CCP 다이얼 | 2026-11-07 사용자 결정대로 `<application>`에 `com.hmg.rotary.ROTARY_SUPPORTED=false` 선언. 필수 탐색은 터치로 시험하고 하위 CCP Back은 가게로, 최상위 CCP Back은 숨김으로 확인했다. | 다이얼 이동·선택 지원 완료가 아니다. 선택 지원 확대는 별도 결정이며 Back·복원·안전 제한은 면제하지 않는다. |

**회귀·자동 검사:** 최상위 Back의 최초 [수정 전 실패](evidence/issue51-input-lifecycle/back-before-fix.txt), 포커스 검사만 통과하던 중간 구현의 [창 가시성 실패](evidence/issue51-input-lifecycle/back-visibility-before.txt)를 보존했다. 최종 `NavigationTest`는 포커스·실제 창 숨김·미종료·숨김 시 Restricted·최근 Task 복귀를 확인한다. 3초 이상 복귀가 지연돼도 정상 P 허용을 실패로 보지 않도록 Restricted는 숨김 시점에 검사한다. gate 초기화 후 연속 주차 재판정은 기존 `AppShellTest`와 연결한다.

- `python3 scripts/check_repository.py`, `git diff --check` 실행 결과는 PR에서 최종 확인한다.
- `./gradlew --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 성공. 출발 main 기준 core71개·app4개, 최신 main 통합 후 **core86개·app4개**, 실패0. Lint 오류0·경고12개: 기존11개(`DrawAllocation`6·`ViewConstructor`2·`OldTargetApi`·`DataExtractionRules`·`MissingApplicationIcon`)와 새 API29 callback의 `@TargetApi`에 대한 `UseRequiresApi` 제안1개다. 새 의존성으로 경고를 지우거나 미사용 DashboardView·디자인을 정리하지 않았다.
- `am instrument --user 10 -w io.drivegrowth.app.test/android.test.InstrumentationTestRunner`: 기기 검증 APK에서 [22개 성공](evidence/issue51-input-lifecycle/final-instrumentation.txt). 저장 검사는 격리된 시험 DB를 사용한다. [task·display 발췌](evidence/issue51-input-lifecycle/device-evidence.txt)는 빌드 구분 및 UI와 함께 대조한다.
- 최신 통합 APK 재검사가 종료된 뒤 확인한 로그에는 OEM 오디오 SIGABRT·CarService `registerAudioPolicy failed -1`가 있었고 emulator 실행도 종료코드139로 끝났다. [선별 환경 기록](evidence/issue51-input-lifecycle/recovery-evidence.txt)을 보존했다. 앱 코드가 원인이라고 확정하지 않는다. 데이터 삭제 없는 재기동은 이전 crash 보고 동의창에서 멈췄고 `-no-metrics` 옵션도 확인했지만 장치에 연결되지 않았다. 외부 보고를 보내거나 crash 저장소·AVD 데이터를 삭제하지 않고 이번 실행만 중단했다. 장시간 emulator 안정성과 최신 APK 재실행 성공은 미검증이다.

**미충족·인계:** 현재 Virtual Console `/vehicle/gear?gear=Drive` 요청은 시간 초과했고 SDK·화면에서는 P가 유지됐다. 이를 D 시험 성공으로 세지 않는다. 이번 변경 빌드의 D 정차·주행·감속·제한 중 재진입·D 재생성·P 복귀와 권한 거부/복원의 전체 실행은 미검증이다. 팀원의 `506a38c` 보고는 기존 근거로 유지하되 새 수명주기 보완의 성공으로 확장하지 않는다. user 이미지에서 제한된 VHAL 주입을 root·정책 변경으로 우회하지 않았다. 로컬에서 관측한 긴 표본 간격에도 유효성·3초 연속 주차 기준을 완화하지 않았다.

#51은 **부분 인계**다. 공통 화면·조회·출처·선택 복원, Back 창 숨김·복귀와 다이얼 미지원 범위를 사용할 수 있으나, 새 빌드의 D/P 회귀·지원 폼 팩터·실제 런처 입력 확인이 남아 전체 종료로 판단하지 않는다. 저장 성공 전 완료 연출·중복 명령의 실제 거래 기능 연결은 각 기능과 #28에서 확인한다. 새 원장이나 별도 교차검증 대기는 만들지 않는다. #36·#63은 다시 열지 않는다.

재현 시 중복 시험 task를 구분하고 다음 런처형 Intent를 사용한다. 전용 시험 장치에서 `am force-stop --user 10 io.drivegrowth.app`으로 task를 정리할 수 있지만 앱 데이터 삭제는 하지 않는다.

```sh
adb -P 5038 -s emulator-5556 shell am start --user 10 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -f 0x10200000 -n io.drivegrowth.app/.MainActivity
adb -P 5038 -s emulator-5556 shell input keyevent KEYCODE_BACK
adb -P 5038 -s emulator-5556 shell cmd car_service inject-key 4
adb -P 5038 -s emulator-5556 shell am instrument --user 10 -w \
  io.drivegrowth.app.test/android.test.InstrumentationTestRunner
```

시험 종료 전 `wm size reset` 후1080×600·원래120dpi, overlay 설정 null, 야간 모드 no·원래 secure 설정 null, `car_service:found`, 사용자10 속도 권한 granted를 확인했다. 권한은 변경하지 않았다. D·속도 변경 성공 근거는 없고 마지막 화면은 P·0km/h였다. 실제 설치 APK 해시도 기기 검증 APK 값과 대조했다. 이후 최신 main APK 업데이트·재검사에서 위 환경 실패가 발생해 정상 기기 종료 상태를 새 성공으로 만들지 않는다. 처음 실행 AVD가 없어 이번에 기동했던 실행들은 모두 중단했고, 마지막 `adb -P 5038 devices -l`은 장치 없음·추가 forward 없음이었다. adb 서버는 종료하거나 포트를 바꾸지 않았다. 최종 APK·시험 로그는 로컬에 보존한다.

공식 근거: [Pleos CCP](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development), [폼 팩터](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide), [Android 창 표시 API](https://developer.android.com/reference/android/app/Activity#setVisible(boolean)), [API34 display 선택](https://android.googlesource.com/platform/packages/services/Car/+/f9a252f4a3b621daabd330531fce555300a1d4c0/car-lib/src/android/car/drivingstate/CarUxRestrictionsManager.java#422), [미구성 display의 제한](https://android.googlesource.com/platform/packages/services/Car/+/f9a252f4a3b621daabd330531fce555300a1d4c0/service/src/com/android/car/CarUxRestrictionsManagerService.java#549).


### #105 차량 동기 조회 멈춤 수정 — 2026-11-07

사용자 요청으로 기존 PR #105의 `92d343a239102eaa3169f849c0b21362c6620c4e`에서 차량 입력 동기 경계를 수정했다. **기존 main부터 있는 UI-thread IPC 취약점**이며 #105가 새로 만든 회귀로 바꾸지 않는다. 검증 C가 제공한 `/private/tmp/pr105-session3-evidence/`의 JDWP·CarService 스택·metadata·동일 APK 재부팅 후22개 결과를 직접 읽었다. 앱 main은 `VehicleInput.read → getProperty → BinderProxy.transactNative`, 시험 thread는 `startActivitySync`를 기다렸고 시스템 속성 lock 소유자는 VHAL unsubscribe를 기다렸다. [앱 스택](evidence/pr105-vehicle-input-stall/before-main-blocked.txt)·[시스템 스택](evidence/pr105-vehicle-input-stall/before-carservice-lock.txt)을 발췌 보존했다. 환경 정체와 앱의 동기 호출 책임을 구분한다.

최초 worker 구현은 `56986ba`, 최종 수신 시각 보완은 `9e7ebbc1fdcb00d4ebcfb9eea36bfd216ad4e21d`다. 단일 SDK worker·진행 중 작업 하나·세션/revision 폐기·수신 시각 보존/요청 deadline과 실패/해제 처리는 [02의 입력 경계](02_SYSTEM_ARCHITECTURE.md#36-차량-입력-구현-경계)를 따른다. 기존 gate 수치·경제·UI 구조·SDK 버전·의존성은 바꾸지 않았다. 09의 사용자 화면 기준도 바꾸지 않았다. 실제 SDK가 계속 반환하는 센서 AVAILABLE 값의 나이 문제와 SDK 미응답 문제는 별개다.

게시 전 최신 main `3b547e52bcc7cdb0b513e8c19236f7ea66306fee`와 README·06 충돌을 확인했다. 메인에서 이미 머지한 #103·#104·#106을 `c9cb569`의 일반 merge로 통합했고 최신 README 담당표와 양쪽 검증 이력을 보존했다. 그 PR의 코드·계약을 별도로 수정하거나 임의 cherry-pick하지 않았다. 다음 결과는 통합 전/후 APK를 구분한다.

| 검사·입력 | 실제 결과·근거 |
|---|---|
| 수정 전 근거 | 위 검증 C의 실제 SDK 정체를 참조. 제어 가능한 입력 경계에서 만료 응답과 해제 대기 중 폐기 세션 open 경합의 [수정 전 실패](evidence/pr105-vehicle-input-stall/regressions-before.txt)를 확인한 뒤 수정했다. 새 클래스의 최초 컴파일 미구현 실패와 행동 실패를 혼동하지 않는다. |
| 제어 가능한 JVM 지연·실패 | 최종 `VehicleInputQueueTest`15개 통과. 막힌 read/close 중 main 작업·gate 만료, 2500/2501ms deadline·수신 elapsed/UTC 보존·변동 지연의3초 주차, 오류/권한 이유, stop/revision/이전 세션 폐기, 1000회 start/stop의 작업 하나·최신 factory만 생성, open/close 오류 후 상태·자원 보존·cleanup 진단을 확인. latch/수동 scheduler/clock 사용, 임의 sleep 없음. |
| 통합 전 `56986ba` | core86개·app18개, 빌드/Lint 성공. APK `6f09e9daed5dae61f7c9cff5125745e2f65b11c1d410e2fd5ed1fc2d75c8d29a`, [기기23개 통과](evidence/pr105-vehicle-input-stall/instrumentation-before-main-merge-23.txt): 원본22개+지연 경계1개. |
| 수신 시각 보완 전 main 통합 `c9cb569` | core97개·app25개, 실패/오류/생략0. `./gradlew --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` 성공. Lint 오류0·경고14(통합 main13+기존 #105 API29 callback 제안1), 경고 숨김/새 의존성 없음. |
| 기기 시험 APK 식별(시각 보완 전) | 앱 SHA-256 `8774cfc32210502b91ac1aa8f838425c0e49f01c8627affc071076430a243193`, 시험 APK `09cc28008de8e89dc7533db34e041b6aabeb80166338e5651ee41f42a4fb65c2`. 기존 설치 인증서 일치 확인 후 `install -r` 사용. 실제 설치 해시와 최종 대조하며 키 경로/비밀값·APK 원본은 게시하지 않는다. |
| 시각 보완 전 기기 전체 | [47개 통과](evidence/pr105-vehicle-input-stall/instrumentation-final-47.txt), 18.574초. 원본22개와 통합 main SQLite/차고 시험·새 지연1개를 포함한다. 새 검증 세션은 만들지 않았다. |
| Android main 지연 회귀 | 제품 큐+실제 main Looper에서 worker의 가짜 reader를 latch로 막은 채 main의 제한 판정·1000회 poll·stop을 먼저 완료, 결과 폐기·worker 종료 확인. 실제 CarService 지연 보장이 아니라 제어된 입력 경계 시험이다. |
| 실제 제품 UI의 입력 부재 | 통합 전 APK에서 JDWP로 **SDK worker만** 일시 중지, main 비중지와 [실제 제한·숨김·같은 Activity 복귀](evidence/pr105-vehicle-input-stall/worker-pause.txt)를 확인. 재개·detach 후 P 재수신. 실제 Binder 내부 정체를 주입한 시험은 아니다. 첫 유효기간 전 캡처·다른 창 포커스/앱 목록 오버레이 입력은 성공 근거에서 제외했다. |

기기 환경은 Pleos2.1.0/API34 ARM64·Emulator37.2.13·사용자10·동일 AVD(console5556), 1080×600/120dpi다. 메인에 현재 점유를 확인해 우선 사용 배정을 받았고 active instrumentation/제품 프로세스 없음도 재확인했다. 첫 재시험은 NavigationTest 시작문구 뒤 장치와 qemu가 사라져 **실패/원인 미확정**으로 보존했다. 메인·검증 A/B는 종료 조작을 하지 않았다고 보고했다. 데이터 유지 재기동의 보고창은 자동화 앱 목록에서 접근할 수 없어 사용자가 자동 전송 체크를 해제하고 Don't send를 눌렀다. 외부 충돌 보고·AVD 초기화·앱 삭제·시스템 패치·UX 우회는 하지 않았다.

복구 명령의 첫 AVD 경로 오류는 게스트를 시작하지 않았다. 실제 경로로 기동한 한 번의 게스트 복구는 기본 ADB5037에 등록돼, 기존5038 서버에서 같은 게스트를 `localhost:5557`로 연결했다. 원래5038 서버는 종료하지 않았고 이번 복구가 만든5037 서버는 해당 게스트만 있음을 확인한 뒤 정리했다. 이 별칭과 복구를 새 장치/실차 시험으로 표시하지 않는다. 브리지 복구는 공개 콘솔의8088 포워딩·`/browser/session` heartbeat·`/vehicle/gear`와`/vehicle/controls`를 사용했으며 SDK/시스템 앱은 변경하지 않았다.

통합 전 수동 시험에서는 P 콘텐츠·선택/재생성, 일반/CCP Back·실제 창 숨김/같은 Activity 복귀, 속도 권한 거부/복원과 거부 중 재생성을 확인했다. 콘솔이 처음 `bridge_ready=false/emulator_active=false`였으나 재기동 후 브라우저 세션을 유지한 요청에서 true가 됐고 **앱 SDK `gear=DRIVE·ux=true`** 수신을 확인했다. D 정차, 콘솔30km/h 주행·10km/h/브레이크60% 감속, D 숨김·재진입·재생성에서 게임 콘텐츠가 나타나지 않았고 P 복귀 후 `PARK·ux=false`와 콘텐츠를 확인했다. 속도의 콘솔 설정/표시와 앱의 `speed=OK` 상태는 구분하며 앱 로그에 숫자 속도는 남기지 않았다. 통합 후 핵심 수동 결과와 종료 상태는 아래 최종 기록을 따른다.


**`c9cb569` 시각 보완 전 통합 APK 수동 결과:** 앱의 권한은 허용 상태였지만 SDK는 `NO_DATA`에 머물렀다. [제한 신호](evidence/pr105-vehicle-input-stall/final-no-data-signals.txt), [Back 후 실제 창 숨김](evidence/pr105-vehicle-input-stall/final-no-data-hidden.png), [복귀·재생성 뒤 제한 화면](evidence/pr105-vehicle-input-stall/final-no-data-recreated.png)을 확인했다. 콘솔 Drive/30/10/P 응답이 성공해도 이 버전의 SDK D/P 수신 성공으로 기록하지 않는다. 앱의 현재 PID 스택 확보는 JDWP handshake 종료·ADB shell 지연으로 실패했고, 이어진 재실행도15초 host timeout이었다. [DrivingView SIGSEGV·호스트 ColorBuffer 오류](evidence/pr105-vehicle-input-stall/environment-after-main-merge.txt)는 관측 근거이며 원인 확정이 아니다. 통합 후의 정상 P 콘텐츠·실제 D/P·권한 거부/복원 전체 수동 회귀는 미완료로 남긴다. 앞의47개 자동 기기 통과와 통합 전의 수동 성공을 이 빈칸에 옮기지 않는다.


**최종 시각 보완과 적용 범위:** 게시 후 self-review와 같은 기존 AI 코드 검토에서 요청 시작 시각 덮어쓰기가 연속 주차를 앞당길 수 있음을 확인했다. 요청0/첫 완료1500/다음 완료2000·3000ms에서 조기 허용되는 [추가 수정 전 실패](evidence/pr105-vehicle-input-stall/variable-latency-before.txt)를 먼저 재현했다. `9e7ebbc`는 SDK 조회 완료의 수신 elapsed/UTC를 보존하고 요청 시작은2500ms 결과 deadline에만 사용한다. 첫 수신1500 후4500ms의 새 P 표본에서만 허용되는 회귀가 통과했다. gate 임계값·추가 주차 guard·계약 필드를 바꾸지 않았다.

이 **최종 코드**는 core97개·app26개(Queue15+기존11), 실패/오류/생략0, 앱/시험 APK 빌드와 Lint 오류0·경고14가 통과했다. 최종 앱 SHA-256은 `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`, 시험 APK는 앞의 `09cc2800…`와 같다. 처음에는 장치 종료/불안정으로 미설치였으나, 메인에 현재 점유를 다시 확인하고 최종 APK용 기존 AVD 기동1회를 재배정받아 다음 시험을 수행했다. 이전8774의 미완료를 삭제하거나 성공으로 바꾼 것이 아니다.

**최종793e APK 실제 기기 재검증:** 메인에서 A/B/C 검증·다른 구현이 idle·미사용임을 확인한 뒤 devices/qemu/instrumentation 없음도 직접 확인했다. ADB5038·emulator-5556을 명시해 같은 AVD를 데이터 보존으로 한 번 기동했다(11.059초 boot, host GPU/headless·RAM4096·snapshot load/save 없음). 앞의 SwiftShader 복구와 GPU/전송 별칭 조건을 구분하며 공식 폼팩터 시험으로 표시하지 않는다. 기존 인증서 일치 뒤 `install -r`로 설치했고 **실제 설치 해시도793e와 일치**했다. 시험한 head는 `e165098c389913fffe48d5fc736b2a4ef10fc333`이며 수신 시각 코드 `9e7ebbc`와 같고 이후는 문서/증거만 갱신한다.

- [최종47개 기기 시험](evidence/pr105-vehicle-input-stall/final-793-instrumentation-47.txt) 통과,9.952초. 원본22개·통합 main SQLite/차고·제어된 Android 지연 회귀를 포함한다. 최종 수신 시각 코드의 실제 기기 실행 근거다.
- P 콘텐츠·미확인 정산 선택 후 [재생성/연속 주차 재판정 뒤 같은 선택](evidence/pr105-vehicle-input-stall/final-793-selection-recreated.png) 유지. 일반 Back/D 숨김·같은 Activity 복귀, 앱 창 포커스를 확인한 CCP 하위→가게·[최상위 실제 창 숨김](evidence/pr105-vehicle-input-stall/final-793-ccp-hidden.png)·[동일 Activity 복귀의 새 제한](evidence/pr105-vehicle-input-stall/final-793-ccp-return.png) 확인.
- 콘솔의 browser heartbeat/기어·controls 요청으로 [실제 SDK DRIVE·ux=true 및 PARK·ux=false](evidence/pr105-vehicle-input-stall/final-793-sdk-signals.txt) 수신 확인. D 정차·콘솔30km/h/가속50%·10km/h/브레이크60%의 [제한 화면](evidence/pr105-vehicle-input-stall/final-793-d-restricted.png), D 재생성·숨김/재진입에서 게임 콘텐츠가 나타나지 않았다. P 복귀 후 콘텐츠를 확인했다. 콘솔 속도와 앱의 speed=OK 상태를 구분하며 센서별 시각·정밀 속도/지연 측정은 하지 않았다.
- 사용자10 CAR_SPEED를 일시 거부하고 임시user-set/user-fixed를 적용해 `PERMISSION_DENIED`·[재생성 뒤 제한](evidence/pr105-vehicle-input-stall/final-793-permission-denied.png)을 확인했다. grant/임시flags 해제와 야간no 복구 뒤 SDK P 재수신·[게임 콘텐츠 복귀](evidence/pr105-vehicle-input-stall/final-793-permission-restored.png)를 확인했다.
- [최종 설정·데이터 원복](evidence/pr105-vehicle-input-stall/final-793-restoration.txt): boot1/user10·1080×600/120dpi·nightno/overlaynull/securenull·권한granted·ceDataInode491836 유지. 콘솔Park·속도/가속/브레이크0 요청, Car UX 서비스dump 응답을 확인했다. 제품 프로세스와 heartbeat/8088 forward를 정리한 뒤 이번 qemu 실행을 중단했고 최종 devices/forward·시험 프로세스는 없고 원래5038 서버만 유지했다. 반복 재부팅이나 데이터 삭제로 기존 장애를 덮지 않았다. 장시간/OEM 전체 안정성은 이 짧은 성공으로 확대하지 않는다.

**종료·원복:** 콘솔 기어Park·속도/가속/브레이크0 요청, 사용자10 속도 권한granted와 임시user-set/user-fixed 해제, `ceDataInode=491836` 유지, [야간no·1080×600/120dpi·overlay/secure null 원복](evidence/pr105-vehicle-input-stall/final-restoration.txt)을 확인했다. 이 문단은 첫 복구 실행의 종료 기록이다. 당시 실제 설치는 시각 보완 전 `8774…`였고, 재배정받은 최종793e 시험/종료는 위 최종 기록을 따른다. 마지막 Car UX 서비스 dump는 응답했으나 차량 입력·ADB가 간헐 지연돼 전체 건강 상태를 정상으로 판정하지 않는다. 이번 복구 qemu 실행을 중단하고 offline 별칭·JDWP·heartbeat·8088/8701 forward를 제거했다. 최종 devices/forward는 비었고 qemu/JDWP/heartbeat 프로세스는 없으며 원래5038 서버만 유지했다. 사용자가 해제한 자동 crash 보고 설정은 다시 켜지 않았다. 데이터 삭제 없이 APK 업데이트와 위 한 번의 실제 게스트 복구를 수행했다. [빌드·설치·시험 단계별 메타데이터](evidence/pr105-vehicle-input-stall/verification-metadata.json)를 참조한다.

남은 범위: SDK 내부 main 콜백의 Car.mLock/OEM 정체 전체 제거, 영구 Binder 정체의 강제 취소·해제 완료/자동 복구, 센서 시각 신뢰성, 실제 CarService 지연·단절 주입, 공식 폼팩터/미구성 DISPLAY2의 콘텐츠·API28·실차는 미검증이다. #51 전체 완료·main 머지·Draft 해제·이슈 종료·ready/Blocked by 변경은 하지 않는다. 최종 독립 검증은 결과를 받은 메인이 요청하며 새 검증 세션은 만들지 않았다.


### #51 차고·도감 호스트 연결 본인 검증

2026-11-07 A 구현의 새 `app/51-garage-host` 작업이다. #105의 실제 main 머지 `6adf357b6d568067f60d0e0982fbfc015856a35c`를 fetch·일반 머지한 뒤 연결하며 기존 입력 큐·관측/숨김 수명주기·정책 임계값은 바꾸지 않는다. #105의 과거 증거와 이 작업의 실행 결과를 구분한다.

구현한 범위는 원본 콘텐츠·`GameMoney`·`OwnedVehicle`·`Settlement`를 보존하는 `HostSnapshot`, 기존 저장 API의 읽기 전용 공급자, 차고/도감 매핑, 단일 실행·세대 구분 조회 controller와 `MainActivity` 탐색 연결이다. SQL·공통 계약·관리 경제 규칙을 수정하지 않는다. 개별 저장 조회들을 원자적인 일괄 snapshot 조회로 주장하지 않는다. 도감 이력의 기본 공급은 미지원 실패이며 현재 보유 목록에서 과거 보유를 만들지 않는다. 초기 지급 조회도 미연결이므로 빈 보유를 신규 프로필로 해석하지 않는다. 정산·온보딩 정식 View 연결은 각 담당 최종 코드 인계 후 검증할 항목이다.

로컬 자동 검증:

- 먼저 미구현 타입을 사용하는 mapper/controller/탐색 회귀를 실행해 컴파일 실패를 확인한 뒤 구현했다. 제어 실행기를 사용하며 임의 sleep을 쓰지 않는다. 로컬 red/green 로그는 `/private/tmp/garage-host-*.txt`에 보존하며 실제 SDK 입력 시험과 구분한다.
- 신규 host 회귀 11개: 같은 차종의 다른 개체/revision·공통 필드, 손상 참조/중복 ID/빈 보유, 명시 이력만 사용하는 도감과 미지원 오류, 원본 Settlement 유지, 제한 뒤 늦은 성공 폐기, release/재시작 세대, 반복 100회 요청의 작업 1개 유지, 실패/재시도, 준비 요청/선택 복원과 Back 순서를 확인했다. 추가 100회 detach/attach 회귀는 구성 변경 사이 기존 작업 하나·이전 callback 폐기·허용 상태 미복원을 확인한다. 실제 Activity 구성 변경 시험과 구분한다.
- 최초 Activity 연결 뒤 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 성공(core 97개·app 35개). 이후 원본 데이터 보존 회귀를 더한 `:app:testDebugUnitTest :app:assembleDebugAndroidTest` 성공. 저장소 검사(필수 21개·문서 26개·로컬 링크 222개)와 `git diff --check` 통과. 이후 변경의 최종 재검사는 게시 전 결과로 갱신한다.
- 별도 읽기 전용 AI 검토에서 gate/Back/복원/세대 제어 결함은 발견하지 못했고, 상위 조회 예외의 오분류·진단 소실 1건을 수정했다. 예상 저장 오류·권한 거부·기타 공급 실패를 구분하고 예외 종류/단계만 진단한다. 사람 리뷰나 실제 장치 실행으로 간주하지 않는다.
- 이후 구성 변경 사이 application Context 기반 `HostSession`의 worker/source/controller만 유지하고 화면 callback을 해제하도록 보완했다. 새 화면은 제한 상태에서 시작한다. 이 변경의 app 단위 37개·APK 빌드·lint가 통과했으며 별도 읽기 검토에서 추가 결함은 발견하지 못했다. 새 의존성 없이 플랫폼의 Activity non-configuration 보존 API를 사용한다.

현재 실제 기기 시험은 **미실행**이다. 메인이 배정한 B의 #54 격리 시험 순서를 지켜 이 작업에서는 adb 조회·설치·AVD 실행·신호/권한/창 설정을 조작하지 않았다. 준비 후 메인과 사용 시간을 조율해 실제 차고/도감 탐색, 터치 Back/일반·CCP Back, 숨김·복귀·Activity 복원, 조회 실패, 실제 SDK 제한과 창 크기를 확인해야 한다. 고정 시험 입력으로 확인한 상세/도감 이력과 실제 원장·실제 SDK 시험은 구분한다. 관리 명령은 현재 비활성/미연결이며 거래 성공·재실행 복구 또는 #51/#52 전체 완료를 주장하지 않는다.

## #52 차고·도감 표시 UI 부분 검증

2026-11-07, `codex/52-garage-display` 독립 worktree에서 `origin/main`의 `e7a1bcc`를 기준으로 구현했다. #52 최신 본문과 댓글(없음), 당시 열린 PR(없음)을 확인했다. 이 절은 표시 UI와 고정 입력의 자동 검사 기록이며 #51 연결이나 #52 전체 완료 보고가 아니다.

- 소스: `app/src/main/java/io/drivegrowth/app/ui/garage/`의 차고·상세·도감 View, 표시 모델·입력 제어, `garage_` 문자열·중립 vector. debug 전용 `GaragePreviewStates`는 PRD의 차종 ID/이름 목록에 고정 상태·수치를 직접 주입한다. DB/실차를 읽거나 실제 지급·차감을 실행하지 않는다.
- 환경: JDK 17, 기존 Android SDK Platform 35, Gradle 8.11.1. 영문 worktree `/private/tmp/drive-growth-garage-ui-20261107`에서 기존 SDK 경로를 환경 변수로 지정했다. Gradle·CI·Manifest·공통 계약·Activity 파일 변경은 없다.
- `python3 scripts/check_repository.py`, `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug :app:lintDebug`, `./gradlew :app:assembleDebugAndroidTest`, `git diff --check`를 실행했다. 모두 통과했다. 추가로 `:core:test` 86개, 앱 단위 시험 전체 11개(차고 신규 7개)의 실패·오류 0건을 확인했다. Lint는 오류 0건·경고 13건이며 기존 대상 API/백업/그리기/아이콘/커스텀 View 생성자 경고와 새 프로그램 생성 View 2개의 `ViewConstructor` 경고를 포함한다. callback을 받는 View는 XML 인플레이션용이 아니다. instrumentation의 기존 플랫폼 시험 API 사용 중단 경고도 남는다. 추가 기기 시험 APK 빌드는 테스트 컴파일 확인이며 기기 실행 성공이 아니다.
- 새 JVM 시험 7개는 동일 차종의 정확한 개체/revision 선택, 반복 요청 차단과 외부 결과 해제, 불가·제한·누락·개체 소멸 차단, 오류 재시도 중복 방지, 상세 닫기 뒤 미확정 요청 유지, 오래된 버튼의 대상/revision 불일치 차단, 외부 선택 복원을 확인한다. callback 전송으로 목록을 삭제하거나 완료로 바꾸지 않는 것도 확인한다.
- Android 기기 시험 5개는 개체 선택/중복 클릭, 선택 소멸/제한 콘텐츠 제거, 빈 목록/로딩/읽기 실패/재시도, 도감 3상태·해금 분리, 도감 스크롤 재표시 보존을 준비했다. **컴파일만 수행, 실행하지 않았다.** 공유 에뮬레이터·adb 사용 금지 요청을 지켰으며 AVD 실행·설치·크기·권한 변경이 없다.
- AI 코드 검토에서 지적한 스크롤·포커스 초기화와 호스트 선택 복원 진입점을 보완했다. 사람 리뷰나 실제 화면 검증으로 간주하지 않는다. 실제 Android 스크린샷은 확보하지 못했으며 시안 PNG·HTML을 앱 실행 증거로 사용하지 않는다.

#51/메인 세션 인계: 공유 장치 사용이 가능한 시간에 약 15~20분을 확보해 `GaragePreviewStates`를 허용된 공통 탐색에 연결하고, 준비한 기기 시험과 세로 840×1184dp·가로 1360×948dp·좁은 창·긴 텍스트·글꼴 확대·선택 복원·Back/CCP를 확인해야 한다. 해당 크기는 공식 가이드의 예시 테스트 화면이며 모든 장치의 합격 기준이 아니다. 동기 callback 재표시 후 스크롤·포커스와 처리 중 화면 재생성도 확인한다. 이 세션이 공유 장치 사용을 예약하거나 시작한 것은 아니다.

실제 데이터 공급자는 09 명세의 [표시 UI 연결점](09_UI_UX_SPEC.md#52-차고도감-표시-ui-연결점)에 개별 희귀도·진행량·취득액·활동·행동 허용/설명과 영속 도감 키를 매핑한다. #47~#49 실제 준비/확정 명령, 비용 확인, 요청 ID, 실패 복구·저장/재실행·주행 제한 통합은 남아 있다. #52 완료 체크·담당·Blocked by·ready는 변경하지 않는다. 원격 CI와 정확한 커밋은 게시 PR의 실제 결과로 확인한다.


### #52 도감 재조회 스크롤 수정·재검증

2026-11-07 검증 B가 별도 격리 호스트에서 원본 장치 시험 5/5, 보강 시험 7개 중 6개 통과·1개 실패를 보고했다. 전달받은 `extra-seven-device.txt`의 실패는 Content에서 `scrollY=700`으로 이동한 뒤 Loading의 실제 layout을 거쳐 Content로 돌아오면 0이 되는 조건이다. 이 기록은 구현 세션의 새 장치 실행 결과와 구분한다.

원인은 마지막 ScrollView가 어떤 상태를 표시했는지 구분하지 않고 위치를 저장하던 것이다. `CollectionView`는 이전 Content의 배치된 위치만 저장하도록 수정했다. Loading/Error의 0이 Content 위치를 덮지 않고, Content→Content 연속 재표시·제한 중 콘텐츠 숨김을 유지한다. 내용이 줄면 Android ScrollView가 새 내용의 유효 범위로 복원한다. 09 명세의 스크롤 보존 약속은 유지한다.

`CollectionScrollDeviceTest`에 실제 debug Activity 부착·중간 상태 layout을 거치는 회귀 5개(Loading, Error, 연속 Content, 제한 복귀, 내용 축소)를 추가했다. 최소 debug 호스트는 제품 탐색·차량 SDK·저장을 호출하지 않고 외부에 export하지 않는다. assertion/예외는 UI 스레드에서 잡아 시험 스레드로 전달한다. 기존 5개 View 시험에도 같은 예외 전달을 적용했다. 제품 main Manifest·MainActivity·Gradle·CI는 수정하지 않았다.

장치 준비 당시에는 검증 C(#105) 종료·복원 → B 구현(#104) 종료 → 메인의 사용 가능 전달 순서를 기다렸고, 준비 중 adb/설치/AVD/장치 설정을 조작하지 않았다. `python3 scripts/check_repository.py`(21/26/183), 앱 단위 11개, `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`, `git diff --check`를 통과한 `dad71ec214ef91a16ab9dc0bc37234f86784baa2`를 기존 PR에 일반 push했다. 같은 head의 [CI 42278501355](https://github.com/Yaho03/drive-growth/actions/runs/42278501355)는 두 검사 모두 성공했다.

이후 메인의 장치 사용 가능 전달을 받아 ADB 5038·`emulator-5556`·user 10에서 **격리 패키지 `io.drivegrowth.app.garage103`**만 설치해 같은 정식 시험 APK로 수정 전/후를 비교했다. Gradle applicationIdSuffix는 로컬 init script로만 지정했고 저장소 설정은 바꾸지 않았다.

| 실행 | 소스·실제 결과 |
|---|---|
| 수정 전 | `6ed613a` + 동일 정식 시험·debug 호스트. 새 회귀 5개 중 Loading/Error의 실제 layout 경유 2개가 `expected 700 / actual 0` assertion 실패, 나머지 3개 통과, Errors 0. 시험 스레드에서 실패를 보고했고 프로세스 종료가 아니다. |
| 수정 후 | `dad71ec`의 호스트만 교체. 기존 View 시험 5개와 실제 Activity 부착 회귀 5개, 총 **10개 모두 통과**. Loading/Error 뒤 복원, 연속 render, 제한 중 숨김/복귀, 내용 축소 시 유효 범위 보정을 확인했다. |

시험 APK SHA-256은 전후 동일한 `89e8f957d1f0f4308883aed60f18de70087dc49080356800b2187ee160fdc3cc`다. 수정 전 호스트는 `ccb40a12094e27e1e4d1ca6a0f59ca981ad317c575f85451368b2776d67e5c0d`, 수정 후 호스트는 `56107cf8918565b0dc41cd04c2bad7c90dd7191e2ccdd81a28ea5428e5e5a3c2`이며 설치된 APK 해시와 대조했다. 로그·APK·전후 상태·설치 해시는 로컬 `/private/tmp/pr103-scroll-fix-evidence/`의 `red-device.txt`, `green-device.txt`, `prepared-artifacts.json`, `device-before.json`, `device-after.json`에 보존한다. 로컬 증거 파일·APK는 커밋하지 않는다.

시험 후 이번에 만든 격리 앱/시험 패키지만 제거하고 목록에서 사라짐을 확인했다. 제품 APK 경로·SHA-256 `298d5cf55e846b8495682ae6c1d08e4aa5eb2d2da0f3cab67bd1d85e751e0832`는 전후 동일하다. 제품 데이터·권한·기어·창·서버는 변경하지 않았다. 부팅 완료 1·user 10·1080×600px·120dpi·night no·forward 빈 목록을 확인했고 Activity 프로세스 조회에서 active instrumentation 항목이 없었다. AVD는 켜 둔 채 **장치 사용 종료**를 메인에 전달했다.

기존 인계의 OEMDrivingView 충돌/포커스 경쟁·차량 Console 브리지 문제·CarService dump 지연은 해결했다고 주장하지 않는다. 불필요한 CarService 전체 dump, 차량 신호·브리지 복구, 제품 데이터 삭제·AVD 초기화·다른 프로세스 종료는 하지 않았다. 위 성공은 격리 고정 UI 호스트의 회귀 결과이며 제품의 실제 #51 탐색·주행 제한·Back·경제 명령·DB/저장 연결·실차 검증이 아니다. #52 전체 완료도 계속 미완료다.

위 장치 시험 인계 당시 #104는 main 미반영이어서 Rarity 위치·공통 개체 필수 필드·표시 일치·유효 fixture·09 설명을 맞춘 호환 변경을 별도 임시 통합 worktree/patch로 관리했다. 당시 원격 #103에는 포함하지 않았다. 이후 실제 통합 결과는 아래에 기록한다. 09의 스크롤 보존 약속은 낮추지 않았다.

### PR #103·#104 계약 호환 통합 — 2026-11-07

#104 최종 `9c32bb38c93b4f219a2cfcc4113c0cd1c8776db8`를 main `ba5ede1a4a184eb22001fec600e51742601e57c1`에 반영한 뒤, #103 최종 `e8bd7cd8c7c12c89393bf64cf81a82ebc6d4097b`의 전용 통합 worktree에 최신 main과 검증 B가 확인한 호환 패치를 적용했다. 패치 SHA-256은 `8506b995267ade06eca484e4f82293eb9568ab323d14be8262e319f72c3b7697`이다. 공통 Rarity·필수 개체 필드를 연결하고 희귀도·진행량·취득액을 공통 개체에서 직접 표시한다. 활동·사고·차종 가격의 누락 표현은 유지하며, 100% 예시는 Lv7·0%의 유효 입력으로 바꿨다. 패치의 코드·리소스·단위 시험 6개 파일이 검증 B의 통합본과 동일함을 대조했다.

메인 통합 worktree에서 JDK17·기존 SDK로 `./gradlew --offline --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`를 실행했다. core97개·app11개가 실패·오류·생략0으로 통과했고 앱·시험 APK 빌드 및 Lint(오류0·경고13)가 성공했다. 저장소 검사와 `git diff --check`도 통과했다. 기존 02·06 기록을 보존하고 09 연결 설명과 README의 구현 범위를 맞췄다. 실제 최종 커밋과 원격 CI 결과는 PR #103에서 확인한다.

이번 통합은 adb·에뮬레이터를 사용하지 않았다. 위 SQLite26개·격리 UI10개의 실제 장치 근거를 새 조합의 제품 앱·경제 명령·저장/재실행 시험으로 확대하지 않는다. #46의 활동·영속 도감·migration·다중 개체 거래, #52의 #51 탐색·주행 제한·실제 명령 연결과 #28 통합 흐름은 남아 있다. #105 차량 입력·Back 변경은 이 통합에 포함하지 않는다.


### PR #105 독립 최종 검토와 인계 — 2026-11-07

검증 C가 최종 코드 `055649180ef265789da914bd36d83f390108b2d5`를 검토했다. 작성자의 기기 시험 head `e165098` 이후 app/core diff가 없고, 보존된 앱 `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`·시험 APK `09cc28008de8e89dc7533db34e041b6aabeb80166338e5651ee41f42a4fb65c2`와 실제 설치본이 일치함을 시작·종료 시 대조했다. fresh 빌드는 DEX 분할 순서 때문에 전체 해시가 달랐으며 DEX 내용 집합·다른 항목의 동일성을 확인했다. 추가 장치 시험에는 정확한 보존 APK를 사용했고 서로 같은 해시라고 기록하지 않았다.

- 새 Queue 회귀 15개를 직접 실행하고 빌드·Lint(오류0·경고14)·저장소·diff 검사를 확인했다. 메인에서도 별도 최종 worktree에서 Queue15개 실패·오류·생략0과 저장소·diff를 확인했다.
- 작성자의 장치47개와 실제 D/P·권한·Back·선택 복원 근거는 최종 소스·APK·로그·화면을 대조해 활용했고 일괄 재실행하지 않았다.
- 정확한 793e 설치본에서 `VehicleInputDelayTest`·`NavigationTest`를 추가 실행해 [2개 통과, 1.147초](evidence/pr105-vehicle-input-stall/final-review-targeted-2.txt)를 확인했다.
- 실제 제품의 SDK worker 하나만 통제해 중지한 동안 새 표본 만료로 제한 전환, 일반 Back의 창 숨김, 같은 Activity 재진입의 제한 유지, worker 재개·디버거 분리 후 새 P 관측으로 콘텐츠 복귀를 확인했다. 실제 CarService/VHAL 장애 주입과 구분한다. 로컬 원본은 `/private/tmp/pr105-final-verification-evidence/worker-pause-jdwp.txt`와 해당 화면·metadata에 보존했다.
- 신호·권한·창·야간 설정은 이 추가 시험에서 변경하지 않았다. worker 재개·디버거 분리·포워딩 제거 후 제품 시험 프로세스와 이번 AVD만 종료했고 기존 ADB5038 서버를 유지했다. 데이터 삭제·초기화는 하지 않았다.

이번 입력·수명주기 변경에서 차단 결함은 발견되지 않았다. PR 검토·사용자 머지 지시와 최종 원격 커밋/CI는 PR #105에서 관리하며 AI 검토를 사람 승인으로 표시하지 않는다. 위 SDK 내부 잠금·실제 서비스 장애 주입·센서 시각·공식 폼 팩터·실차 한계는 유지한다. 실제 명령 중복·저장 성공 경계, #103 표시 UI의 제품 탐색 연결과 후속 기능 통합은 #51·#52·#28에 남긴다. README의 화면 병렬 작업표는 같은 인계를 반영한다.

### B 대여 배정·영업 원장 (#41–#44, 2026-11-07)

코어 시험 103개와 앱 단위 시험 26개, debug APK·기기 시험 APK 조립, Android lint 및 저장소 검사를 확인했다. Pleos Connect 2.1.0 / Android 14의 활성 사용자 10에서 최종 APK로 신규 `RentalLedgerTest` 6개가 통과했다.

- 별도 DB 연결 두 개에서 같은 차량에 대한 배정과 판매를 경쟁시키고, 두 방문이 경쟁할 때 한 건만 점유하는 것을 확인했다. 영업 쉬기 중의 미배정도 재요청 후 그대로 유지됐다.
- 시험 기본료 100, 정확 일치, 주차 1시간은 대여료 78이었다. 주유소 추가금 5를 포함한 입금 83, 정산 직전 쓰기 실패 시 잔액 90 보존, 재시도 후 잔액 173 및 중복 요청 시 같은 영수증을 확인했다.
- 주차 중 원래 기준점 유지, 반납 전 재배정 제한, 가상 사고 후 수리 필요 상태, 반올림 미정 시 지급 보류·정책 입력 후 재개, 시설·공간·구매 원장 DB 재개방 및 SQLite v1 → v2의 기존 잔액·개체·정산 보존을 검사했다.
- 전체 기기 실행에서 기존 `NavigationTest.testTopLevelBackHidesTask`가 시작 시 앱 창 포커스를 얻지 못했다. 시험 DB 재개방은 제품 프로세스 강제 종료·전원 차단·실차 검증과 별개다. 실제 고객 분포·단골 판정, 수리 명령, A/D 수명주기·화면 연결과 최종 경제 수치의 검증은 후속 인계 범위다.

재현은 프로젝트의 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 후 Pleos의 활성 사용자에 앱/시험 APK를 설치하고 `adb shell am instrument --user 10 -w -e class io.drivegrowth.app.data.RentalLedgerTest io.drivegrowth.app.test/android.test.InstrumentationTestRunner`로 실행한다.

### #34·#46·#49·#50 이력과 영업 조회 인계

Android API 37 독립 에뮬레이터에서 `RentalLedgerTest` 11개와 `SqliteGameChangeStoreTest` 26개를 실행해 37개가 통과했다. 판매·폐차·강화 재료 소비 뒤 재개방해 개체와 취득 출처를 읽었고, 발견 중복 및 실패 배치가 추가 기록·재화 변경을 일으키지 않음을 확인했다. 두 대여를 배정과 반대 순서로 정산하고 최초 영수증을 열람한 뒤 v2 → v3로 이전해도 동일한 정산 ID·개체·사고 결과를 읽었다. 기존 v1에서의 직접 이전도 통과했다. 실행 범위는 SQLite이며 실제 가챠 화면 노출·선물 지급·실차 연동은 검증하지 않았다.

#49 인계 보완 후 같은 API 37 환경에서 대여 시험 12개·기존 원장 시험 26개(총 38개)가 통과했다. 같은 차종 두 개체 중 한 대에 무사고 1건·사고 3건을 정산해 누적 0/1/2/3을 확인했고, 정산 실패·재시도·재개방·폐차 뒤에도 개체별 횟수가 보존됐다. 연속 대여 사이의 수리 완료 활동만 시험 입력으로 주입했다.

#116 세 수정 요청 보완 후 API37 독립 기기에서 대여14개·원장26개 총40개 통과. 발견 입력 반복 순회 변경, 정산 오연결·ID/종류/출처 불일치와 숫자·불리언 손상11종에서 최초/지정 조회 실패·DB 바이트와 잔액 보존을 확인했다. 사고 집계도 같은 완료 정산 검증을 사용한다.


## #56 첫 실행·차량 선택 독립 표시 UI 검증

2026-11-07, 새 `app/56-onboarding-ui`·`/private/tmp/drive-growth-onboarding-ui-20261107`에서 구현했다. 시작 main3b547e5와 #56 최신 본문/댓글(없음)을 대조했고 게시 전 #105가 머지된 main `6adf357b6d568067f60d0e0982fbfc015856a35c`를 정상 통합해 README/02/06/09·공통 입력 변경을 보존했다. 실제 #51 연결 전 독립 표시 구간을 진행하라는 사용자 배정이며 이슈 차단/담당/완료 체크를 변경하지 않는다.

코드 `f975c8051b456c3a94b0e983cb989311bd9a8d16`은 `ui/onboarding/`의 View·상태/입력 제어, onboarding 문자열, debug 고정 fixture, JVM/정식 View 시험을 추가한다. 실제 지급·프로필·원장·마커를 만들지 않는다. 최초 상태 확인 미지원은 Unavailable이며 보유 수 0으로 신규 프로필을 추정하지 않는다. 사용 시안/연결점은 [09의 해당 절](09_UI_UX_SPEC.md#56-첫-실행-독립-표시-ui-연결점)을 따른다. 첫 성장의 순서/비용은 정하지 않았다.

### 로컬 검사와 요청 회귀

JDK17·기존 SDK Platform35·Gradle에서 코어97개·앱 단위 전체39개(새 OnboardingInteractionTest 13개), `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`, 저장소 검사·diff 검사를 수행했다. 최종 게시 검사/CI는 PR에 같은 최종 head로 기록한다. Lint는 오류0·경고15이며 기존14개와 프로그램에서 생성하는 새 View의 ViewConstructor 경고1개를 포함한다. 공유 Gradle/CI·제품 Manifest는 바꾸지 않았다.

새 단위 시험은 정확한 VehicleId 선택/복원, 선택·검토가 지급 완료가 아님, 반복 확인과 처리 중 Back 차단, 다른 선택의 늦은 응답/완료 후 역행 거절, 실패 뒤 기존 요청 복구, 이미 지급/미확인 프로필의 무지급, 동기 callback, 단계 전환과 단순 표시 갱신의 구분을 확인한다. 코드 검토에서 동일 상태 재표시로 복구/탐색 잠금이 풀리는 경계를 찾아 회귀2개로 실패를 재현했고, 오류 문구/출처만 갱신하는 조건도 실패를 재현한 뒤 실제 단계 변화로만 해제하도록 보완했다. 최종 재검토에서 추가 blocker 없음 응답을 받았으며 사람 승인으로 표시하지 않는다.

### 격리 기기 실행·화면·장치 인계

메인의 #54 장치 사용 종료 후 사용 가능 전달을 받고 ADB5038·emulator-5556·user10에서 진행했다. 제품 Manifest에는 등록하지 않고 **별도 임시 사본** `/private/tmp/drive-growth-onboarding-device-20261107`에서만 비공개 debug Activity·부착 시험·로컬 applicationIdSuffix를 추가했다. `io.drivegrowth.app.onboarding56`의 제품/fixture/정식시험/문자열 6파일이 원본과 동일함을 SHA-256으로 대조했다. 앱은 고정 입력만 사용하고 #50·실제 프로필/지급/저장·차량 SDK를 호출하지 않는다.

- 정식 View 시험6개와 임시 실제 Activity 시험6개, 총 **12개 통과**: 안내→아반떼 선택→확인→명시적 고정 완료, 다른 차량 결과 거절, 이미 지급/미확인, 실패 복구·처리 중 Activity 재생성, 제한·복귀, 좁은 본문/큰 글꼴을 확인했다. assertion/예외는 시험 스레드에서 보고했다.
- 별도 추가 시험 **1개 통과**: 440dp 본문·글꼴1.5에서 버튼 전체 높이가 보이도록 native ScrollView로 이동하고 실제 포인터 입력으로 안내→차량 선택→확인→지급 대기까지 진행했다. confirm1회·확정 결과 전 완료 문구 없음도 확인했다. 위젯 performClick 시험과 실제 터치 시험을 구분한다.
- 최초 캡처 일부는 전환 직전 화면/OEM 내비 창을 담아 로컬 first-pass-captures로 분리하고 성공 화면으로 쓰지 않았다. 제품 코드 변경 없이 임시 캡처에 합성 프레임 대기·phase/focus/크기를 기록하고 12개를 재실행해 통과했다. 아래 실제 디스플레이 캡처는 안정된 재확인/터치 실행의 결과다.

실제 가로 기기 내 앱 영역은 702×419px, 좁은 본문은 330×419px(120dpi에서 폭440dp)였다. 전역 wm/폰트 설정을 바꾸지 않고 격리 호스트의 콘텐츠 폭·리소스 fontScale만 사용했다. 공식 세로/가로 폼 팩터 전체 시험이나 실제 #51 입력/CCP 검증으로 확대하지 않는다. 주변 차량/시스템 그림은 OEM 셸이며 앱 자산이 아니다.

| 실제 화면 | 조건 |
|---|---|
| [첫 안내](evidence/issue56-onboarding-ui/intro.png) | 고정 시험 출처·지급 예정 안내, 실제 잔액/지급과 구분 |
| [확정 결과](evidence/issue56-onboarding-ui/completed.png) | 호스트가 공급한 고정 Completed 뒤 아반떼·일반·취득액0 표시, 실제 #50 성공이 아님 |
| [좁은 본문의 확인 버튼](evidence/issue56-onboarding-ui/narrow-confirm-action.png) | 폭440dp·글꼴1.5, 스크롤 후 버튼 전체가 보이고 실제 터치로 처리 중 진입 |
| [조작 제한](evidence/issue56-onboarding-ui/restricted.png) | controlsEnabled=false 고정 입력, 차량 선택·게임 버튼 제거 |

[12개 실행 로그](evidence/issue56-onboarding-ui/device-tests.txt), [실제 터치 로그](evidence/issue56-onboarding-ui/native-touch-test.txt), [소스·APK·프레임 metadata](evidence/issue56-onboarding-ui/metadata.json)를 보존한다. 앱 실제 설치 SHA-256은 `8b22597d76abd691024a0e1db25b6e680b58e5dcf055c777b6a591da509bd8da`이며 정식12개 시험 APK도 설치본과 대조했다. 이후 추가 터치용 임시 시험 APK 변경은 metadata의 별도 해시로 구분한다. APK·로컬 SDK·임시 호스트 소스는 커밋하지 않으며 임시 시험 원본/원시 초기 로그는 `/private/tmp/onboarding56-evidence/`와 해당 사본에 남긴다.

종료 시 이번에 만든 격리 앱/.test만 제거하고 패키지 없음, boot1/user10/1080×600/120dpi/night no/forward빈목록/Activity프로세스 조회 instrumentation항목없음을 확인했다. 제품 APK 경로·SHA-256 `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`는 전후 동일하고 제품 데이터·권한·기어·전역창·서버를 변경하지 않았다. AVD는 켜 두고 **장치 사용 종료**를 메인에 전달했다. 재부팅/AVD 재생성/초기화·다른 프로세스 종료·차량 브리지 복구는 하지 않았다.

남은 범위: A의 실제 호스트/탐색 연결, 명시적 신규·이미 지급 상태 조회, #50의 프로필당1회 지급·requestId/마커·원장 저장/실패 복구·앱 재실행, 같은 차종 이전 응답의 호스트 정합성, 실제 주행 전환/CCP·공식 폼 팩터·실차와 첫 성장 규칙/화면. 이 부분 구현으로 #56 전체를 닫거나 #51 차단을 해제하지 않는다.


### PR #108·#109·#110 독립 검토와 통합 — 2026-11-07

사용자 요청으로 세 PR을 main에 반영한다. 검증 A/B/C는 실제 코드·요구사항·최종 CI와 각 독립 범위를 대조했으며 필수 수정 결함을 발견하지 못했다. AI 검토이며 별도 사람 승인으로 표시하지 않는다. 사용자 명시 머지 지시와 승인 예외·최종 head/CI/merge 해시는 각 PR에 남긴다.

| PR·검토 코드 | 독립 검증에서 직접 확인한 결과 | 남은 범위 |
|---|---|---|
| #108 `b3ee4b7` · 검증 B | core97·app38, 실제 장치10개. ID·출처·잔액/수익 구분, 열람 응답·중복/늦은 응답, 새 View Pending 복원과 스크롤 | 제품 호스트·실제 markViewed·전체 history·프로세스 재시작 |
| #109 `14d4b18` · 검증 A | host 단위11개, 실제 Activity/320dp/Back 장치5개. 시험 DB12대의 typed 조회·빈 원장·실패 표현, SDK D 정차/36km/h·재진입·P 복귀·권한·CCP | #108/#110 정식 View 연결, 영속 도감·관리 명령·전체 제품 DB 재실행 |
| #110 `381b3de` (앱 코드 `f975c80`) · 검증 C | 입력 단위15개, 실제 View/부착 장치12개. 단계·중복 잠금·늦은 응답·실패 복구·재생성·440dp/글꼴1.5 터치 | 실제 #50 프로필/지급 마커/1회 지급·제품 호스트·실제 P/D/CCP |

독립 장치 시험은 각각 격리 패키지이며 제품 APK와 동일한 바이너리라고 주장하지 않는다. #109의 SDK 시험도 Virtual Console 입력을 실제 AAOS SDK로 받은 에뮬레이터 결과다. #110의 Completed는 외부 고정 확정 입력의 표시이며 실제 지급이 아니다. 세 세션 모두 자기 시험 패키지를 정리했고 제품 APK `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`·제품 데이터·권한을 보존했다.

메인의 통합은 앱 코드를 추가 수정하지 않고 docs/06·09의 양쪽 기록을 보존한 문서 충돌 해결과 README 인계만 포함한다. 각 PR의 제품/시험 소스가 독립 검토 커밋과 같은지 비교했다. 통합 빌드의 자동 검사는 아래에 기록하며, 이를 세 화면의 실제 탐색 연결 완료로 바꾸지 않는다.

후속 A는 #51/#54의 typed Settlement→ResultsView와 기존 markViewed의 실제 응답·재조회·복원을 연결한다. #56은 OnboardingView를 실제 탐색·gate·Back에 넣되 초기화 조회가 없으면 Unavailable로 표시하고 보유0을 신규로 추정하지 않는다. 실제 지급은 #50의 명시적 계약을 기다린다. #51/#52/#54/#56 및 #28은 남은 완료 조건 때문에 열린 상태다.

메인 독립 worktree에서 JDK17·기존 SDK·Wrapper로 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --no-daemon`을 실행해 BUILD SUCCESSFUL(38초)을 확인했다. core97개·app62개 모두 실패/오류/생략0, Lint오류0·경고16이다. 저장소 검사·`git diff --check`도 통과했다. 세 PR의 변경된 앱/시험 소스 7/10/6파일은 각각 위 독립 검토 head와 바이트 단위로 일치했다. 이번 메인 통합 검사는 adb/에뮬레이터를 사용하지 않았고, 각 격리 장치 결과를 실제 세 화면 연결 시험으로 확대하지 않는다.

## 정산·첫 실행 실제 호스트 연결 검증 — 2026-11-07

기준 main `4e8ab1a56254cd30ac87256ead5ee4c900f12645`에서 새 `app/51-results-onboarding-integration` 브랜치로 진행했다. 제품 연결 코드는 `19eadcaba76554477d035a3ae9a392b29f877b9a`, 네이티브 터치/지연 회귀를 포함한 시험 소스는 `a1bc2cc4bd7dbe9b40a024c153c06221a2ac18de`다. 두 커밋 사이 제품 `app/src/main`·core 차이는 없다. 아래 증거는 이 코드에 대한 본인 검증이며 새 교차 검증 대기를 만들지 않는다. 최종 문서 커밋/head·원격 CI는 연결 PR에서 확인한다.

### 구현과 실제 계약 공백

- 기존 ResultsView/OnboardingView를 실제 MainActivity에 삽입했다. 정산 원본·당시 개체/잔액·출처를 보존하고 외부 표시 목록을 읽기 전용 snapshot으로 전달한다. 기존 단일 host worker에서 markViewed를 실행하고 실제 성공/실패만 UI에 전달하며 성공 뒤 미확인 목록을 재조회한다. 결과를 열거나 열람해도 지급·수익 계산을 하지 않는다.
- HostSession이 구성 변경 사이 진행 중 작업을 보존한다. 선택/페이지 이동과 ID별 응답을 분리하고 숨김/제한 중 응답이 게임 화면을 다시 열지 않게 한다. 프로세스가 새로 시작되면 저장 응답이 이어지지 않는 Pending을 확인 불가 실패로 바꾸며 자동 재전송·완료하지 않는다.
- 기준 main 및 #50 본문을 확인했으며 프로필 초기화 조회·1회 지급/마커 확정 API는 아직 없다. 첫 실행은 기존 View의 무행동 Unavailable이다. 보유0·선택 ID로 신규/후보/Completed·티켓/공간/잔액을 만들지 않는다. 미머지 #114/#115의 지급·콘텐츠 형식을 가정하지 않았고 해당 콘텐츠3파일·core·SQLite·VehicleInputQueue는 수정하지 않았다.
- 상세→목록→가게→앱 숨김 Back과 온보딩 handleBack/GoToShop 전달을 연결했다. 실제 제품의 Completed/GoToShop 도달은 #50 확정 계약이 없어 미검증이다. LoadError/GrantError Back 차이와 목록 snapshot 소유 규칙은 09의 기존 관련 절에서 명확히 했다.

### 자동 검사와 실제 실패 재현

JDK17·기존 SDK/Wrapper로 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline`을 통과했다. **core97개·app71개, 실패/오류/생략0**이며 신규 host 회귀9개를 포함한다. 실제 gate 입력/화면 시험은 아래 장치 근거와 구분한다. 저장소·diff 검사와 최종 원격 CI는 게시 시 같은 최종 head로 확인한다.

별도 읽기 전용 AI 코드 검토에서 클릭 직전 gate 만료의 미제출 Pending 잔류1건을 발견했다. View는 Pending을 먼저 만든 뒤 callback하므로 호스트가 현재 gate로 거절할 때 실제 요청 상태를 View에 복원하도록 수정했다. [수정 전 실제 assertion 실패1·오류0](evidence/issue51-results-onboarding-integration/gate-before-fix.txt)와 [수정 후3개 통과](evidence/issue51-results-onboarding-integration/gate-after-fix.txt)를 보존한다. 전후 초기 시험 APK 해시는 동일한 `1132daf17c9b12a04b45904f3eda8300117b745bf8b2bc0825c47a47ac59dc1d`다. 실제 MainActivity·SDK P에서 gate만 제어 초기화한 회귀이며 실제 D 입력으로 표시하지 않는다. [최소 수정 패치](evidence/issue51-results-onboarding-integration/pending-gate-fix.patch)를 재현 참고로 남긴다. 재검토에서 해당 결함 해소를 확인했으며 사람 승인으로 표시하지 않는다.

### 기기·설치·저장/수명주기 시험

메인의 단독 슬롯 인계 후 ADB5038·emulator-5556·user10·Pleos2.1.0/API34 ARM64의 실제 점유/건강을 재확인했다. 준비 중 B #114의 3~5분 시험에 빈 시간을 먼저 배정했고 종료 인계 전에는 adb를 사용하지 않았다. APK 서명 인증서는 기존 설치본과 일치하며 키 경로/비밀값을 노출하지 않았다.

| 설치·입력 | 실제 검증 범위 |
|---|---|
| 제품 `io.drivegrowth.app` | APK/설치 SHA-256 `581eb9a576805b2ba88cebce7c03338e6f96a63913490b814832c41071e9413e`. 기존 제품 DB는 잔액0/보유0/미확인0이며 수정·시드하지 않았다. 실제 결과 빈 화면·초기화 미지원 View·터치/일반/CCP Back·SDK 제한/복귀·권한 거부/복원을 확인했다. |
| 격리 `io.drivegrowth.app.integration51` | 동일 제품 소스, 로컬 init script의 applicationIdSuffix만 다르며 공유 Gradle/Manifest는 변경하지 않았다. 실제 SQLite 공통 GameChange로 시험 정산/개체만 만든다. APK/설치 SHA-256 `55e4097389fe2ac7526e5271b3a0cc93cb3179bde0306762ef6b44c68892ba90`. 실제 #50 프로필 지급·영업 수익으로 표시하지 않는다. |
| 정식 격리 시험 APK | APK/설치 SHA-256 `a0dbd949787658d86c50aa93d9e661a279c069984783a429292e3c93d68c98f6`. 현재 코드 소스로 다시 빌드해 동일함을 대조했고 [최종3개 통과·29.54초](evidence/issue51-results-onboarding-integration/final-host-tests.txt)를 확인했다. |

세 표적 시험은 실제 markViewed 3회·재개방·원래 요청 재생에서 정산/잔액/개체 불변, 없는 ID 실패, 실제 Android Bundle의 Pending→확인 불가·자동 쓰기0, 실제 MainActivity 재생성/동일 HostSession·새 gate·선택 ID 복원을 확인한다. 미제출 gate 거절 뒤 같은 ID 재시도와 성공 후 미확인 목록 제거도 포함한다.

추가한 최종 Activity 경계는 **기존 host worker의 실제 쓰기 앞에 latch 대기**를 두고 320dp root에서 네이티브 터치로 열람을 요청한다. Pending 동안 상세→목록→가게→앱 숨김이 실행되고, 대기를 푼 뒤 실제 SQLite 성공이 와도 게임 UI를 되살리지 않는다. 사용자 복귀는 새 P gate와 조회를 기다린다. 저장 응답을 성공으로 모의 대체하지 않았다. 네이티브 입력의 최초 시도는 root 좌표를 화면 좌표로 사용해 [타 앱 대상 입력 거절 오류1](evidence/issue51-results-onboarding-integration/native-input-first-error.txt)이 났다. 실제 화면 위치로 교정했고 권한 우회 없이 같은 연결 시험3개가 통과했다.

실제 격리 프로세스 PID9171에 HOME 키를 보낸 뒤 종료하고 PID12603으로 다시 실행했다. HOME 뒤에도 앱의 제한 문구가 남는 Pleos 부분 표시 상태였으므로 정상적인 백그라운드 회수로 확대하지 않는다. 재실행은 HOME의 새 조회였고 상세 선택은 유지되지 않았다. 결과 화면의 미확인2건은 SQLite와 일치했으며 전후 격리 DB **바이트 해시 전체가 동일**하다. Android 저장 Bundle 전달 여부는 계측하지 않았다. [선택 유지 기대와 실제 HOME의 차이](evidence/issue51-results-onboarding-integration/process-selection-not-restored.txt)를 보존하고, 저장 Bundle을 받은 재생성 선택 복원·Bundle Pending의 확인 불가 처리와 구분한다. [프로세스 이벤트](evidence/issue51-results-onboarding-integration/process-events.txt)·[metadata](evidence/issue51-results-onboarding-integration/metadata.json)를 따른다.

### 격리 표적 시험 재현

새 host 시험은 기본 원장에 고정 정산을 쓰므로 `io.drivegrowth.app.integration51` 외의 패키지에서는 쓰기 전에 실패하도록 제한했다. 기존 저장 시험의 고유 DB와 구분한다. 장치 점유/설정을 확인하고 실제 P·정차·UX 허용을 준비한 뒤, 기존 SDK 환경에서 다음 명령으로 같은 격리 표적 시험을 실행할 수 있다. 공유 Gradle 파일은 수정하지 않는다. 이 명령은 시험 재현 안내이며 장치 사용 종료 뒤 새로 실행한 기록이 아니다.

```sh
cat > /tmp/drive-growth-host-isolate.gradle <<'EOF'
gradle.beforeProject { p ->
    p.plugins.withId('com.android.application') {
        p.android.buildTypes.getByName('debug').applicationIdSuffix = '.integration51'
    }
}
EOF
./gradlew --init-script /tmp/drive-growth-host-isolate.gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb -P 5038 -s emulator-5556 install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5038 -s emulator-5556 install --user 10 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5038 -s emulator-5556 shell pm grant --user 10 io.drivegrowth.app.integration51 android.car.permission.CAR_SPEED
adb -P 5038 -s emulator-5556 shell am instrument --user 10 -w -e class io.drivegrowth.app.host.ResultsHostStorageTest,io.drivegrowth.app.host.ResultsHostActivityTest io.drivegrowth.app.integration51.test/android.test.InstrumentationTestRunner
```

시험 후에는 자기 시험에서 생성한 격리 앱/시험 패키지만 정리하고 변경한 설정을 원복한다. 위 전체 새 host 시험을 제품 패키지의 `connectedDebugAndroidTest` 기본 전체 실행으로 대체하지 않는다. 원본 실제 실행·APK 해시는 위 표와 metadata를 따른다.

### 실제 SDK·화면과 한계

기존 Virtual Console 포워딩을 이번 슬롯에서만 만들고 browser session heartbeat로 브리지를 활성화했다. 서비스/시스템 앱 패치·직접 VHAL 쓰기·UX 제한 우회·재부팅/초기화는 하지 않았다. [제품 SDK 로그](evidence/issue51-results-onboarding-integration/product-sdk.txt)와 [화면 단계 기록](evidence/issue51-results-onboarding-integration/product-sdk-ui-boundaries.txt)에서 D 정차, 콘솔36km/h 요청, D 숨김/재진입·야간 설정에 의한 실제 재생성, D 감속 제한과 P 복귀를 확인했다. SDK는 DRIVE·속도 AVAILABLE·UX true를 수신했다. 숫자36은 콘솔 입력이며 SDK 로그가 실제 속도 숫자를 기록한 것으로 확대하지 않는다.

비어 있지 않은 격리 정산 상세에서도 [실제 SDK D 제한→P 복귀](evidence/issue51-results-onboarding-integration/stored-detail-sdk-boundaries.txt) 뒤 선택 ID·표시 내용 전체가 같음을 확인했다. 제품 속도 권한은 임시 거부/재요청 차단 flag로 실제 PERMISSION_DENIED와 제한을 확인한 뒤 원래 granted·flag 해제로 복원했다. 고정 gate 초기화·지연 큐·Virtual Console/실제 SDK·실차를 각각 구분한다.

| 실제 화면 | 근거 |
|---|---|
| [제품 빈 정산](evidence/issue51-results-onboarding-integration/product-results-empty.png) | 저장된 미확인0건과 전체 history 미지원 구분 |
| [제품 P 복귀의 첫 실행](evidence/issue51-results-onboarding-integration/product-onboarding-park-restored.png) | 실제 OnboardingView, 무행동 Unavailable |
| [D 재생성 제한](evidence/issue51-results-onboarding-integration/product-drive-recreated.png) | 게임 버튼/내용 제거 |
| [권한 거부](evidence/issue51-results-onboarding-integration/product-permission-denied.png)·[복원](evidence/issue51-results-onboarding-integration/product-permission-restored.png) | PERMISSION_DENIED 후 새 P gate로 복귀 |
| [일반 Back 숨김](evidence/issue51-results-onboarding-integration/product-back-hidden.png)·[CCP 하위 Back](evidence/issue51-results-onboarding-integration/product-ccp-back.png) | OEM 셸만 표시/가게 복귀 |
| [320dp 열람 요청](evidence/issue51-results-onboarding-integration/host-narrow-mark.png)·[첫 실행](evidence/issue51-results-onboarding-integration/host-narrow-onboarding.png) | 실제 content root 폭·네이티브 포인터 입력, 전역 창/공식 폼 팩터 변경과 구분 |
| [저장 정산 상세 D](evidence/issue51-results-onboarding-integration/stored-detail-drive-restricted.png)·[P](evidence/issue51-results-onboarding-integration/stored-detail-park-restored.png) | 실제 SQLite 정산과 SDK 입력 연결, 선택/내용 보존 |

주변 차량/시스템 그림은 OEM 셸이며 앱 자산이 아니다. 실차, 공식 폼 팩터 전체, 장기 안정성·실제 CarService/VHAL 장애 주입, 전체 과거 history·리뷰·가상 사고/시간 수익의 추가 조회 API는 미검증/미지원이다. #50의 실제 신규/이미 지급·프로필/requestId·재시도 정합성과 지급 상태의 GoToShop·첫 성장은 남아 있다.

### 정리와 이슈 인계

이번 격리 앱/.test만 제거하고 원래 제품 APK `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`로 데이터 보존 복원했다. 제품 DB와 기존 파일6개의 해시는 전후 모두 동일하다. 속도 권한·임시 flag, P/속도0·페달0·조향0·night no를 원복했다. boot1/user10/1080×600/120dpi/fontScale설정null, forward·격리 패키지·active instrumentation 없음, 기존 AVD/ADB5038 유지 상태를 확인하고 메인에 장치 사용 종료를 한 번 전달했다. 이후 장치 명령은 실행하지 않는다.

#51에는 실제 Results/Onboarding View·gate/Back·비동기 저장/복원 연결, #54에는 현재 공통 정산의 실제 열람/재조회·반복/프로세스 재실행 불변·미확인 수 일치 근거를 인계할 수 있다. #56은 실제 미지원 상태·탐색/제한 연결까지이며 실제 #50 지급/튜토리얼 완료 체크는 유지한다. 이슈 본문 체크·담당·ready/Blocked by·종료는 변경하지 않고 메인이 증거 범위에 맞춰 판단하도록 전달한다. #28 전체 게임 흐름·후속 거래·공식 폼 팩터/실차를 완료로 표시하지 않는다. 원본 APK/DB·임시 script·전체 로그는 로컬 `/private/tmp/results-onboarding-evidence/`에 보존하고 커밋하지 않는다.


## #55 가게·시설·꾸미기 독립 표시 UI — 2026-11-07

기준 main `b6631a44e80e56a6b86ea9bfc1790cefc4ea82c0`에서 새 worktree/`app/55-shop-ui` 브랜치로 구현했다. 범위는 `app/ui/shop/**`·`shop_` 리소스·관련 시험·09/06의 이번 절이다. MainActivity·공통 탐색/gate·차량 입력·Manifest·Gradle·공통 타입/fixture·DB·경제 코드는 변경하지 않았다. 입력·명령·미리보기·미연결 조건은 [09의 연결점](09_UI_UX_SPEC.md#55-가게시설꾸미기-표시-ui-연결점)을 따른다.

| 실행한 검사 | 실제 결과 |
|---|---|
| `python3 scripts/check_repository.py` | 필수21·문서26·로컬 링크251 통과(09/06 추가 전). 최종 문서 수정 후 재검사도 수행 |
| `:core:test :app:testDebugUnitTest` | core191·app78 통과, 각 실패/오류/생략0. app 중 신규 ShopInteraction7개 포함 |
| 추가 `:app:testDebugUnitTest --tests '*ShopInteractionTest'` | 시설 payload 보존/표시 금액·규칙 불일치 거절 1개 추가 후 ShopInteraction 총8개 통과. 위 전체 검사와 구분 |
| `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | 성공. Lint오류0·경고17(기존16 + 프로그램 생성 ShopView의 XML 도구용 생성자 안내1) |
| 최초 View 컴파일 | 재그리기 callback 순환의 Kotlin 반환형 추론 오류로 실패. `drawScreen(): Unit` 명시 후 위 전체 빌드 성공 |
| `git diff --check` | 통과 |

환경은 macOS arm64·Temurin JDK17·기존 Android SDK35·프로젝트 Gradle Wrapper8.11.1이며 `--offline --no-daemon`을 사용했다. 별도 worktree의 빌드 출력만 생성했다. 단위 시험은 미리보기/취소/실패/성공 응답에서 확정 snapshot 보존, 같은 명령 재시도 requestId 유지와 시도별 늦은 응답 무시, Pending 복원·중복 방지, 오래된 revision/가격/허용 상태 거절, 제한·동기 응답·입력 방어 복사·조회 재시도를 다룬다.

`io.drivegrowth.app.ui.shop.ShopViewTest` 6개를 시험 APK로 컴파일했다. 대상은 840×1184·1360×948·480×500dp의 세차장 레벨3 문구/차량/공간 표시, 미리보기·취소·실패 보존, 제한 및 stale 버튼, Pending 복원/다른 요청 응답, 성공 응답 후 외부 확정 조회 필요, 누락·Loading/Empty/Failure/Unsupported·잠금·모드 전환·Back이다. 장치 배정 후 해당 클래스만 instrumentation 실행하며 제품 패키지를 덮어쓰지 않는 격리 사본에서 실행해야 한다. 예상 점유 5분 이내다.

**실제 기기 시험은 수행하지 않았다.** 기기는 다른 세션이 단독 사용하므로 adb/AVD/설치/실행/설정/차량 신호를 조작하지 않았다. 세차장 문구의 실제 화면 가독성·터치/스크롤·Activity 재생성·프로세스 종료/재실행은 미검증이다. 위젯 시험 컴파일을 장치 성공으로 표시하지 않는다. 현재 제품에는 진입 호스트를 연결하지 않았고 #44 실제 구매/확장 저장·꾸미기 영속화·실제 잔액/시설/배치 일치도 미검증이다. #55 전체 완료·통합 검증 #28 완료가 아니다.

로컬 구현 커밋을 PR 게시 전 독립 검토에 인계한다. PR 게시/CI/main 머지는 메인이 검토 결과와 함께 조율하며 이 절을 이슈 종료·라벨/차단 관계 변경 근거로 사용하지 않는다.


### #55 독립 검토 P2 수정과 기기 시험 구분

독립 검토가 최초 head `52703ba6e82c016bc9aabd34d22acd803dafc2ed`에서 두 P2를 재현했다. 최신 revision13의 공간7/잔액900 뒤 늦은 revision12를 render하면 이전 값으로 되돌아갔고, 같은 snapshot에서 A실패→B실패→A재시도 시 A의 원래 거래 requestId가 사라졌다. 실제 DB 중복 과금 재현은 아니다.

`ShopFreshnessTest` 정식 회귀를 추가해 **수정 전3개 전부 실패**(두 P2 및 Loading/재생성 신선도)를 먼저 확인했다. 최근 수락 revision을 uiState에 보존하고 낮은 Content를 거절하되 false gate는 적용하도록 수정했다. 선택별 거래 requestId 맵을 보존·방어 복사해 옵션 전환·취소·제한·View 재생성 뒤에도 동일 요청을 이어가고, 새로운 revision에서 이전 키를 제거한다. 시도별 attemptId와 늦은 응답 거절은 유지한다. 추가 불변성/성공 뒤 중복 완료/새 revision 시험을 포함한 정식 Shop 시험은 기존8+신규4=12개다. 수정 전후 로그는 로컬 `/private/tmp/shop55-freshness-red.log`, `shop55-freshness-green.log`, 전체 검사는 `shop55-fixed-all.log`에 남겼다.

기기 시험은 수정 **전** head와 구분한다. 메인의 단독 배정 뒤 Pleos/API34·ADB5038·user10에서 고정 head의 격리 사본 `io.drivegrowth.verify55b`로 기존 `ShopViewTest`6개와 임시 네이티브 호스트1개가 통과했다(12.187초). 격리 앱 SHA-256 `c570ca1c6311096be9bc4e3cccf196c0f0c363b9c6adfd3838ae86249e591672`, 시험 APK `5a2a001af1f3148494bd92714e75bbc1efd1779f07ba8bf5c3bb4a518124359d`를 실제 설치본과 대조했다. 미리보기/취소/실패·제한·Pending 수동 복원·터치를 확인했으나 이번 두 P2는 그 시험에 포함되지 않았다. 최초 임시 호스트의 큰 폭 요청은 실제 CCP 영역을 넘었으므로 그 넓은 화면 가독성을 통과로 판단하지 않는다. 같은 점유 시간의 #53 독립 검증과 #55 작성자 시험은 서로 다른 소스/패키지/로그다.

기기 사용 후 본인이 설치한 격리4패키지만 제거하고 제품 APK `793e1db80c6ccda9c359cd13b18bc6f41cc05f6e4663753f91542ff740510bd9`·제품 파일6개·권한·user10·boot23·1080×600/120dpi·font null·night no·빈forward의 전후 동일, 내비 전면·활성 instrumentation 없음을 확인해 반환했다. 제품/DB 삭제·기어·GPS·AVD/서버·전역창/글꼴 설정 변경은 없었다. 근거는 로컬 `/private/tmp/b-ui-device-20261107/`의 source-comparison·53/55별 APK metadata·원본 시험 로그·화면·return-checks다. 반환 뒤 추가 adb 명령은 실행하지 않는다.

**로컬 수정 인계 당시 P2 수정본의 실제 기기 시험은 미수행**이어서 독립 C 재검토에 인계했다. 이후 배정된 수정본 시험은 아래 절과 구분한다. 위 최초 기기7개 통과를 수정본 검증·독립 승인·제품 저장/프로세스 복원 성공으로 바꾸지 않는다.

수정 후 최종 로컬 명령 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --no-daemon`은 BUILD SUCCESSFUL(26초)로 종료했다. core191·app83(Shop12 포함) 실패/오류/생략0, Lint오류0·경고17, 저장소21/26/252·diff 검사 통과다. 수정본 제품 APK SHA-256은 `61b75c6c29f8879fa70bff5fa8025215d32f941b1fa5a9c76a76c6c7cb83e01e`, 시험 APK는 `b3a83a21130df630b81e007f17844f1842381c8eba1e6410fc71564d9c1af439`다. 이 값은 격리 패키지의 수정 전 장치 시험 APK와 다르며 설치하지 않았다. PR·push·main 머지·이슈 변경 없이 수정본을 독립 재검토에 인계한다.


### #55 수정본 기기 표적·독립 재검토 및 게시 근거

검토 원코드 `6144ac83fef82dd501d69086e9b752994b9391b4`를 고정한 새 격리 사본에서 실행했다. 추적 소스116개를 대조해 기능/정식 시험은 모두 동일했고, 격리 applicationId·debug Manifest·임시 호스트만 달랐다. [검증 패치](evidence/issue55-shop-ui/verification-host.patch)는 고정 커밋을 추출한 임시 사본에만 적용한다. 기존 제품 또는 다른 작업자의 폴더에는 적용하지 않는다.

- APK 설치 전에 패키지·시험 target·동일 debug 인증서를 확인했다. 격리 앱 `io.drivegrowth.verify55fixedb` SHA-256 `0120358d3c57a40e0ae9cbd1b0ea93e8940acf1c4450a7a181abf3ce7fe0cc11`, 시험 APK `c1b3a2d3e2c475ddad5187749132b9d43ab43480972ab6547c9382f4c59022a1`이며 설치본과 두 해시가 일치했다. 제품 APK로 시험 결과를 대체하지 않는다.
- 실제 명령은 `am instrument --user 10 -w -r -e class io.drivegrowth.app.ui.shop.ShopViewTest,io.drivegrowth.app.ui.review.Host55DeviceTest io.drivegrowth.verify55fixedb.test/android.test.InstrumentationTestRunner`다. ADB5038/emulator-5556·API34/ARM64·user10에서 **View6개+네이티브 호스트2개 총8개 통과, 22.663초**, 실패/오류0([원본 로그](evidence/issue55-shop-ui/device-tests.log)).
- 부착된 실제 View에서 revision13/공간7/잔액900 뒤 낮은 revision12, 오래된 false/true, Loading·Failure·새 View 상태 복원 후 낮은 snapshot을 거절했다([최신 표시 화면](evidence/issue55-shop-ui/latest-after-restore.png)). 실제 Activity Bundle/프로세스 복원 시험은 아니다.
- 네이티브 터치로 A실패→B실패→제한→새 View 복원→A재시도를 실행했다. A와 retryA는 같은 거래ID, B는 다른 ID, 재시도 attemptId는 달랐고 이전 A 성공 응답도 현재 Pending을 해제하지 않았다([요청 식별자](evidence/issue55-shop-ui/request-ids.txt), [Pending 화면](evidence/issue55-shop-ui/alternating-retry.png)). 실제 DB 과금·저장 재생 시험은 아니다.
- 전역 wm/글꼴을 바꾸지 않고 실제 호스트 내부 폭을 제한했다. 요청320dp는 실제320dp, 요청1100dp는 CCP 가용936dp로 제한했다([측정값](evidence/issue55-shop-ui/host-layout.txt)). [320dp](evidence/issue55-shop-ui/car-wash-320dp.png)·[936dp](evidence/issue55-shop-ui/car-wash-936dp.png)에서 세차장 잠금·레벨3 조건을 스크롤해 읽었다. 위젯의 840×1184/1360×948dp layout 시험과 달리 네이티브936dp에서는 1000dp 이상 두 열 분기를 실행하지 못했으며 공식 폼팩터 전체 가독성으로 확대하지 않는다.
- 격리2패키지만 제거하고 제품 APK·기존 파일6개·권한·설정·user/boot·빈forward가 전후 동일, 내비 전면·활성 instrumentation 없음을 확인해 반환했다. 제품/DB 삭제·AVD 초기화·차량 서비스 우회·기어/GPS/전역 설정 변경은 없었다. 반환 뒤 추가 adb 없음. 해시·소스 대조·원복·증거 해시는 [metadata](evidence/issue55-shop-ui/metadata.json)에 기록했다.

별도 C의 독립 재검토는 같은 원코드에서 기존8+정식회귀4+독립표적3=15개 모두 통과, 두 P2 해결·추가 차단 없음으로 APPROVE였다. AI 보조 검토이며 팀원 사람 승인으로 기록하지 않는다. 최신 main `085786a`의 #123 공통 선행 계약 문서를 일반 통합해 보존했고 게시 준비에서 기능·정식 시험 소스는 검토 원코드와 동일하다. 실제 #44 확정 콘텐츠·구매/배치 저장·탐색·프로세스 복원·공식 폼팩터/실차는 미완료다. PR은 Refs #55로 부분 구현·검증만 인계하며 이슈 전체를 자동 종료하지 않는다.


게시 직후 main에 추가된 #124 탁송 표시 UI(`1a0499099006d4644e12527505eaf62f80e18d7e`)를 `688ed7d`의 일반 병합으로 보존했다. 추가 app/core 변경은 main의 탁송 파일이며 검토 원코드 `6144ac8` 대비 Shop 제품/정식 시험/리소스 차이는0이다. 양쪽09/06과 공개 증거를 보존했다. 통합 후 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`는31초에 성공했고 core191·app96(각 실패/오류/생략0), Lint오류0·경고18이다. 추가 경고는 main에서 합쳐진 프로그램 생성 DeliveryView의 도구용 생성자 안내이며 Shop 코드 변경은 없다. 저장소21/26/277·diff검사·공개 증거8개 해시도 통과했다. 이 통합 조합의 기기 시험을 다시 실행한 것은 아니다. 원격 정확head/CI 결과는 연결 PR에 기록한다.


### #55 새 View 첫 복원 순서의 Content 일관성 수정 — 2026-11-07

이미 머지된 #125 이후 독립 A가 main `fbbf98e`에서 계약 안의 첫 복원 순서 결함을 재현했다. 새 View에 Content revision3을 먼저 표시한 뒤 이전 View의 정상 최신 이력5를 처음 복원하면, 이력은5지만 현재 표시/옵션은3으로 남아 Submit이 허용됐다. 실제 DB 중복 과금 재현이 아니며 같은 활성 View의 과거 상태 반복 복원은 기존 지원 범위 밖으로 유지한다.

새 `app/55-restore-consistency` worktree에서 제품 코드3줄만 추가했다. 첫 복원 뒤 현재 Content가 병합한 최신 이력보다 낮으면 행동 없는 Loading으로 무효화하고, 선택 조회도 최신 기준을 검사한다. 복원된 Pending·requestIds·retryPending·현재 gate는 보존하며 이력만으로 성공·취소·새 요청을 만들지 않는다. #52 가챠 WIP와 기존 #55 브랜치/다른 작업자의 소스는 보존했다.

- 정식 `ShopFirstRestoreTest`3개를 먼저 추가해 수정 전2개 실패·1개 통과, 오류/생략0을 확인했다. 수정 후 기존Shop12+신규3=15개 전부 통과, 실패/오류/생략0이다.
- `:app:testDebugUnitTest --tests '*Shop*Test' :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --no-daemon` 성공(30초), Lint오류0·경고18. 기존 core/다른 기능 전체 시험은 반복하지 않았다.
- `ShopViewTest.testFirstRestoreAfterRenderInvalidatesOldContentAndButtons`를 추가해 옛 내용/옛 확인 버튼·낮은 재전달·제한·최신 복귀를 준비하고 시험 APK 컴파일을 확인했다. 실제 기기 실행은 미수행이며 기존8개 장치 근거로 대체하지 않는다. 배정 후 이 메서드1개 표적 실행을 요청하며 예상 점유1분 이내다.
- 저장소·diff 검사를 실행했다. red/green 원본 로그는 로컬 `/private/tmp/shop55-first-restore-red.log`, `/private/tmp/shop55-first-restore-green.log`다. 현재 기기 미배정으로 adb/AVD/제품 앱·데이터·설정을 조작하지 않았다.

09의 새 View 첫 복원 양순서와 미지원 반복 복원 경계를 명시했다. 실제 호스트/Activity·프로세스 저장, #44 경제/꾸미기 저장·탐색은 여전히 미연결이다. 고정 수정 커밋을 A 독립 재검토에 인계하며 PR은 검증 전 게시하지 않는다. 사용자 동결 지시대로 이 결함과 이미 착수한 #52 가챠 UI 외 새 작업은 시작하지 않는다.


## #121 가챠 공통 계약 첫 부분의 메모리 검증 — 2026-11-07

main `085786a`의 독립 core 브랜치에서 GachaChangeStore·전체 명령/결과·권위 상태와 기존 메모리 원장 공유를 구현했다. 첫 성공 기대 시험을 stub에서 실행해 AssertionError 1건으로 실패를 확인한 뒤 구현했다. 제품 GachaPull/VehicleCare/FirstGrant·SQLite/RentalLedger·UI·SDK·Gradle/CI는 수정하지 않았다. 이 기록은 메모리 참조 구현/소비 예제이며 SQLite·프로세스 재시작 시험이 아니다.

표적 시험은 전체 의미 입력 변경의 충돌·최초 전체 결과 재생, 성장/처분 뒤 원본 snapshot, 실제0 무료권·잔액 부족/저장 실패 무변경, 보장/후보/취득액 검증, 권위 revision/카운터/공간·원시 후보 Set 불변, legacy 요청 ID 충돌 양방향, 동일/서로 다른 요청의 마지막 칸·무료권 경쟁과 실패 재시도를 다룬다. 구성한 권위 상태가 없는 기존 fixture는 새 가챠 상태를 생성하지 않는다. 문서와 관련 이슈의 #123 임시 브랜치 링크는 main 기준으로 갱신했으며 #122의 실제 저장 차단은 유지했다.

로컬 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`는 성공했다. main `085786a` 기준 core212개·표적21개·app71개는 실패/오류/생략0, Lint오류0·경고16이었다. 검증 중 #124가 반영된 main `1a04990`으로 rebase해 공통 코드/시험3개가 처음 구현과 바이트 동일함을 확인하고 같은 검사를 다시 수행했다: core212개·app84개 실패/오류/생략0, APK/Lint성공(오류0·경고17). #124 탁송 표시 UI와 실행 기록을 보존했다. 저장소 검사(필수21·문서26·링크266)와 diff 검사도 통과했다. 독립 코드 검토 APPROVE·구조 CLEAR는 AI 보조 결과이며 사람 승인이 아니다. 정확한 인계 커밋과 추가 독립 검증은 #121에서 추적한다. 다중 재료·초기화/원래 지급 조회·무료권 지급·호열 #111 실제 소비·SQLite는 다음 묶음/담당 작업이며 이 부분 결과로 #121/#47/#122를 닫지 않는다.


### #121 가챠 독립 검증 회수와 게시 준비

검토 원본 `08eb3c4819f886381ab62b83473c86d8ebd6cc05`의 코드·참조 fixture·정식 시험3개 blob을 별도 C 검증 worktree에서 직접 대조했다. 독립 실행은 정식21+추가6=27개 실패/오류/생략0이며, 같은 ID의 일반/가챠 경쟁·혼합 마지막 칸·처분 ID 재사용·Long/Int 최대 경계·중첩 award 변경/컬렉션 불변성·미지원/기존 거래 유지까지 확인했다. 판정은 APPROVE·구조상 추가 차단 없음이며 AI 보조 결과다. 이 세션이 27개를 새로 실행한 것으로 표시하지 않는다.

원본 [명령/시험 로그](evidence/issue121-gacha-contract/independent-tests.txt), [커밋·소스/시험 metadata](evidence/issue121-gacha-contract/independent-metadata.json), [독립 추가 시험](evidence/issue121-gacha-contract/GachaIndependentBoundaryTest.kt)을 보존한다. 추가 소스는 정식21개에 편입한 코드가 아니다. 재현은 검증용 독립 사본에서만 해당 소스를 `core/src/test/kotlin/io/drivegrowth/core/contract/`에 복사하고 `:core:test --tests '*GachaChangeStoreTest' --tests '*GachaIndependentBoundaryTest' --offline`로 실행한다. 제품/다른 작업자 폴더에 복사하지 않는다.

가챠 게시 브랜치는 #125가 포함된 main `fbbf98e`에 rebase하며 06 충돌에서 기존 #55 설계/시험/실제 기기 기록과 이 가챠 기록을 모두 보존했다. 검토된 공통3개 파일은 원본과 바이트 동일하다. SQLite·무료권 지급·#111 실제 소비·초기화는 제공하지 않는다. 이미 착수한 다중 재료의 독립 타입/순수 검증은 별도 고정 커밋으로 인계하며 공유 fixture 결합·새 묶음으로 확대하지 않는다. 사용자 지시에 따라 현재 작업의 검증/PR/main 정리 후 후속 작업을 시작하지 않는다.

main `fbbf98e` 조합의 직접 소비 통합 명령 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon`은28초에 성공했다. core212·app96 실패/오류/생략0, APK/Lint성공(오류0·경고18), 저장소21/26/280·diff검사 통과다. #53/#55 제품 소스·09/06·기기 증거를 유지했고 새 장치 시험은 하지 않았다. 기존 독립 검토 원본과 공통3개 파일의 blob 동일성은 게시 전 다시 확인했다. 정확한 게시 head·원격 CI는 연결 PR에서 관리한다.
## #121 재료 강화 타입·순수 검증 준비 — 2026-11-07

가챠 검토 커밋 `08eb3c4` 기반 별도 브랜치에서 MaterialContract 타입/순수 검증과 MaterialContractTest 소비 예제를 추가했다. 기존 계산이 만든 두 Update를 명령에 넣고, 역할·현재 revision/활동·취득/재료 성장 보존·비용과 예정 두 snapshot을 검증한다. 처음 시험 소스의 콘텐츠 버전 경로 컴파일 오류를 교정한 뒤 stub의 성공 기대 assertion 실패1건을 확인하고 구현했다.

검토 중인 GachaContract·공유 RentalContractFixtures와 원작업 폴더는 변경하지 않았다. 메모리 commitMaterial 구현·실제 원자 경쟁·과거 결과 재생·SQLite·앱 프로세스 시험은 없다. 시험의 MaterialSettlement는 후속 결과 DTO의 형태 예시이며 저장을 실행한 결과가 아니다. 로컬 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 성공, core225개(재료 표적13개)·app84개 실패/오류/생략0, Lint오류0·경고17이다. 독립 코드 검토 APPROVE·구조 CLEAR는 이 타입/순수 검증 범위의 AI 보조 결과이며 사람 승인·실제 거래 검증이 아니다. 저장소/링크·diff 검사와 정확한 인계 커밋은 #121에서 구분한다.


재료 준비 원본 `fbd93c448e88aa0dc76e7a26438ba25c8d0dc898`은 별도 A 검토에서 코드 APPROVE·구조 CLEAR, 정식13+독립3=표적16개와 기존 planMaterial 소비152조합 확인 결과를 회수했다. 이 독립 결과는 AI 보조 검증이며 이 세션의 재실행이 아니다. 가챠 #126 병합 main `1089926f7815bf49d3c5a6f233b06f28d5ee5c49`에 준비4파일만 rebase하고 양쪽06 기록을 유지했다. MaterialContract 제품/정식 시험2파일은 원검토 커밋과 바이트 동일하고 GachaContract/공유 fixture/기존 UI는 변경하지 않았다.

통합 후 직접 실행한 `:core:test --tests '*MaterialContractTest' :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --offline --no-daemon`은8초에 성공했다. 재료13개 실패/오류/생략0과 기존 앱/시험 소비 컴파일을 확인했다. 기존 전체225/84·APK/Lint 근거는 원본 기준으로 보존하고 전체/장치 검사를 무조건 반복하지 않았다. 저장소21/26/280·diff검사 통과, 정확한 게시head/원격CI는 연결PR에서 기록한다. 초기화·공유 재료 store·SQLite 등 새 묶음은 시작하지 않고 현재 준비 범위를 인계한다.


### #55 첫 복원 수정의 독립 검토·장치 근거 회수

A의 독립 재검토는 원코드 `515507cac01ae15445c323836c07366edb5a063e`에서 코드 APPROVE·구조 CLEAR·Shop 표적16개 전부 통과였다. 이어 C가 같은 고정 코드의 격리 패키지 `io.drivegrowth.app.shop55restorec`에서 `ShopViewTest#testFirstRestoreAfterRenderInvalidatesOldContentAndButtons` **1개 통과, 실패0, 0.284초**를 확인했다([원본 표적 로그](evidence/issue55-shop-ui/first-restore/targeted-device.txt)). 작성자가 이 독립 JVM/기기 시험을 다시 수행한 것으로 표시하지 않는다.

C의 임시 실제 Activity도 revision3 표시→이력5 첫 복원→옛 버튼/낮은 내용 차단에서 `staleSubmit=0, oldBalanceHidden=true`, 최신5의 잔액900 복귀를 확인했다([호스트 로그](evidence/issue55-shop-ui/first-restore/host-log.txt), [조회 대기 화면](evidence/issue55-shop-ui/first-restore/loading.png), [최신 복귀 화면](evidence/issue55-shop-ui/first-restore/latest.png)). Android View 경계의 고정 입력이며 실제 #44 거래/꾸미기 저장·Activity/프로세스 영속 복원이나 실차 시험이 아니다. 주변 차량/제어 그림은 OEM 셸이다.

격리 앱 SHA-256 `5da60557549cb743bfe063765d4c1464bd4e027637f8cf686a28fed072083581`, 시험 APK `7d5bc78bcf066298bd9aa56809f48dd6af881cc99bd209b2780c12a525bcc835`다. 작성자가 전달받은 두 APK의 해시와 검토 원코드의 Shop 제품/정식 시험/리소스10개 Git blob, 임시 Activity SHA를 대조했다([metadata](evidence/issue55-shop-ui/first-restore/metadata.json)). 재현 호스트는 증거 폴더의 Activity/Manifest/init-script를 별도 임시 사본에서만 사용한다. init-script의 임시 소스 경로를 해당 격리 호스트 경로에 맞추며 제품/다른 작업자 폴더에 적용하지 않는다.

C는 자기 격리2패키지 제거, 제품 APK·파일6개 해시 동일, 기존 내비 복귀·user10·1080×600/120dpi·font null·활성 instrumentation/추가forward 없음으로 반환했다. 권한/기어/차량서비스/전역창/글꼴 변경·제품/DB 삭제·AVD 초기화가 없었다는 C 보고와 전달된 전후 APK/파일/상태 텍스트의 동일성을 확인했다. 작성자는 기기 배정이 없어 adb/AVD 명령을 실행하지 않았다.

최신 main `50b856d`의 #126 가챠·#127 재료 공통 계약/fixture·기존 UI를 일반 병합했고 06 충돌에서 양쪽 검증 기록을 모두 보존했다. 별도 #52 가챠 UI 브랜치는 변경하지 않았다. 현재 first-restore의 Shop 제품/정식 시험은 검토 원코드와 동일하며 추가 내용은 main의 공통 코드와 문서/증거다. Refs #55 범위의 수정이고 실제 경제·탐색·전체 이슈 완료로 확대하지 않는다.


첫 복원 수정의 최신 공통 코드 통합 후 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --no-daemon`은28초에 성공했다. core225·app99는 실패/오류/생략0, Lint오류0·경고18이다. 저장소21/26/286·diff검사 및 검토/장치 기준 Shop10개 blob·전달 APK2개·임시호스트/공개증거 해시를 확인했다. 이 통합 조합에서 장치 시험을 다시 실행한 것은 아니다. 원격 정확head·CI는 새 연결 PR에 기록한다.
## #57 퀘스트·업적·휴식 표시 UI 부분 검증

2026-11-07, main `b6631a44e80e56a6b86ea9bfc1790cefc4ea82c0`에서 독립 worktree와 `app/57-progress-ui` 브랜치를 만들었다. #57 본문/댓글(댓글 없음), PR #60, PRD의 거리 퀘스트·거리 업적/시즌·주말 이벤트 제외와 휴식 규칙, 기존 #119 `RestBonus` 계산 경계를 직접 대조했다. 표시·호출 계약은 [09 명세의 해당 절](09_UI_UX_SPEC.md#57-퀘스트업적휴식-독립-표시-ui)에 둔다. 계산·공통 저장·호스트·탐색·차량 gate는 수정하지 않았다.

### 입력과 본인 검증

debug 전용 `ProgressPreviewStates`는 실제 원장·DB·차량·위치·주차 증거가 아닌 결정적인 **고정 시험 입력**이다. 일일/주간·업적의 잠김/진행/완료/청구가능/처리중/수령/만료/실패/미지원, 누락·빈목록·40개 긴 목표명·선택 소멸, 휴식 조건 충족/미충족/판정 불가와 다음 출발 대기/활성/미지원을 재현한다. 채택되지 않은 목표·보상은 시험값임을 화면 출처와 주석에 명시하며 채택 이벤트를 새로 만들지 않는다.

환경은 macOS arm64·Temurin JDK17·기존 Gradle8.11.1/Kotlin2.2.21/AGP8.10.1·Android SDK35다. SDK 경로는 환경변수로 전달했고 Gradle/Manifest/의존성은 변경하지 않았다.

| 확인 | 실제 결과·의미 |
|---|---|
| 단위 회귀 선실행 | 표시 모델/상호작용 구현 전 신규 시험의 미정의 심볼로 컴파일 실패. 로컬 `/private/tmp/progress-red-tests.txt`에 보존 |
| `:app:testDebugUnitTest --tests '*ProgressInteractionTest'` | 10개 통과, 실패·오류·생략0. 확인 전 청구 차단, 반복 탭·재표시·제한·상세 닫기/소멸·View 교체의 요청 중복 방지, 오래된 sequence/revision/확인 버튼 거절, 새 복구 offer, 외부 Pending 차단, 미지원·누락·불허 행동, 목록 복사·중복 key, 명시적 재조회 잠금 해제 검증 |
| `:core:test --tests '*RestBonusTest'` | 기존 휴식 계산 15개 통과, 실패·오류·생략0. UI는 이 계산을 실행하지 않으며 기존 출력 enum의 소비 경계를 확인 |
| `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | 성공. Lint 오류0·경고17. 이번 View의 프로그램 생성자 관련 ViewConstructor 경고1개가 포함되며 나머지는 기존 대상API·SDK·뷰 등의 경고다 |
| `python3 scripts/check_repository.py`, `git diff --check` | 통과. 원격 CI·독립 검증은 아직 미실시 |
| `ProgressViewTest` | 배정된 기존 Pleos 기기의 격리 패키지에서 4개 통과(최종 1.598초). 청구 요청/수령 분리, 제한/선택 소멸, 빈/누락/미지원/휴식 상태, 440dp/1360dp 긴 목록 측정 검증. 실제 SDK·경제·저장 연결 시험과 구분 |

본인 검증은 청구 이벤트의 중복 방지와 상태 표시 계약에 한정된다. 실제 보상 지급·원장 멱등성·재실행 후 수령 이력·실제 휴식 증거·수익 적용을 검증한 것이 아니다. 전체 core/앱 시험을 반복하지 않고 새 UI에 관련된 단위 시험과 APK/Lint를 실행했다. 앱/시험 APK는 이 작업 폴더의 `app/build/outputs/apk/`에 있으며 로컬 로그는 `/private/tmp/progress-validation.txt`다.

### 실제 Android 화면과 남은 연결

본인검증 중 메인이 C 한 세션에 기기를 배정해 기존 ADB5038·emulator-5556/user10에서 수행했다. 제품 Manifest·Gradle은 그대로 두고 로컬 임시 init-script와 Activity로 `io.drivegrowth.app.progress57` 격리 호스트를 만들었다. 실제 View 표적4개와 네이티브 터치·Activity 재생성 뒤 Pending 복원, 정상/누락/제한/휴식·긴 목록·큰 글꼴을 확인했다. 초기 임시 설정 적용 순서 오류로 첫 APK에 제품 ID가 남아 설치/시험이 서명 불일치로 차단됐으며 제품이 교체되거나 시험이 시작되지 않았다. 적용 시점을 수정한 뒤 aapt로 실제 격리 package/target을 확인하고 재실행했다.

| 실제 Android 화면·근거 | 범위 |
|---|---|
| [정상 목록](evidence/issue57-progress-ui/normal.png) | 기존 앱 창의 한 열 구성, 고정 시험 출처·미채택 보상 명시 |
| [넓은 본문](evidence/issue57-progress-ui/wide-scaled.png) | 호스트 내부1360dp를 현재 창에 축소 표시한 두 열 View. 공식 폼 팩터/원래 크기 가독성 시험이 아님 |
| [440dp·큰 글꼴](evidence/issue57-progress-ui/many.png) | 호스트 내부440dp·로컬 context fontScale1.3, 긴 목표명 줄바꿈·40개 목록. 전역 설정 변경 없음 |
| [휴식 카드](evidence/issue57-progress-ui/rest.png) | 충족/다음 출발 대기와 모의 판정·실제 연결 미완료를 표시. 출발·운전 버튼/카운트다운 없음 |
| [제한 화면](evidence/issue57-progress-ui/restricted.png) | 외부 false 입력으로 게임 내용과 버튼 제거. 실제 기어/SDK gate 검증과 구분 |
| [청구 후 재생성](evidence/issue57-progress-ui/native-pending-restored.png)·[이벤트](evidence/issue57-progress-ui/native-events.txt) | 실제 터치로 요청 뒤 created restored=true, 동일 실행에서 ClaimReward1개, 반복 탭은 추가 요청 없음. 수령 성공을 만들지 않음 |
| [표적 시험 결과](evidence/issue57-progress-ui/instrumentation.txt)·[APK/환경 metadata](evidence/issue57-progress-ui/metadata.json) | 최종 격리 APK의 실제 실행, 제품 탐색 연결과 구분 |

주변 차량·차량 제어 화면은 OEM 셸이며 앱 자산이 아니다. 기존 공유 기기 불안정 기록을 해소한 것으로 확대하지 않는다. 공식 폼 팩터 전체·CCP·실차·실제 주행 정책 #81은 미검증이다.

시험 뒤 자기 격리 패키지2개만 제거했다. [제품 파일 전](evidence/issue57-progress-ui/product-hashes-before.txt)/[후](evidence/issue57-progress-ui/product-hashes-after.txt)의 6개 해시와 [제품 APK](evidence/issue57-progress-ui/product-apk-after.txt) `793e1db8…`가 동일하다. 권한·기어·전역 크기/밀도/글꼴은 변경하지 않았다. [종료 상태](evidence/issue57-progress-ui/state-after.txt)는 user10/boot완료/1080×600/120dpi/fontScale설정null, 원래 내비 복귀, 활성 instrumentation·추가 forward 없음이다. 기존 AVD PID64177·ADB5038 PID66071을 유지하고 기기 반환을 보고했으며 이후 추가 adb를 실행하지 않는다. 임시 호스트·init-script·원본 APK/로그는 `/private/tmp/progress-device-host/`·`/private/tmp/progress-device-evidence/`에 보존했다.

09에 공급 상태/이벤트/복원 예시를 남겼다. 경민 #45의 실제 목표·기간·진행/청구·영속 수령 API, 정원 #40의 장소/P/시간 증거·원자 저장·출발 감지와 수익 연결, 환석 #51의 탐색·gate/프로필/요청 세대·수명주기 연결은 후속 범위다. 휴식 시간을 UI가 합산/소모하지 않고 짧은 P 합산·일반 주차 중 소모 제안을 확정하지 않았다. #57 전체 체크·ready/Blocked by·담당·종료는 바꾸지 않는다. 최신 운영 지시에 따라 이 로컬 결과는 독립 검증 인계 전이며 PR 게시·CI·main 머지는 아직 하지 않는다.

### #57 독립 검토 수정 — 소멸 항목의 revision 역행 방어

2026-11-07, A의 독립 검토가 초기 구현 `339e2b369162efb31614719a98ab138dba291ae8`에서 같은 목표·기간 키의 revision5/수령 상태 → 빈 목록 → revision1/청구 가능 상태가 수락되는 P2를 재현했다. 직전 목록만 비교해 소멸 전 revision을 잊은 것이 원인이었다. 실제 DB 중복 지급 재현이 아니라 표시 역행과 청구 이벤트 허용 경계의 문제다.

- **수정 전:** 정식 회귀 `removedKeyRetainsHighestAcceptedRevisionAndCannotClaimOldState`, `restoredViewRetainsRemovedRevisionsButAllowsANewPeriod` 2개를 실행해 모두 assertion 실패를 확인했다. `/private/tmp/progress-revision-red.txt`에 보존했다.
- **수정:** `ProgressUiState.acceptedRevisions`에 목표·기간 키별 최대 수락 revision을 유지한다. 항목 소멸로 지우지 않으며, 더 낮은 항목을 포함한 Content는 전체 거절한다. 같은 키의 더 높은 revision과 다른 기간 키는 허용한다. View 교체 때 이 맵도 복원하며 방어 복사/읽기 전용 맵으로 외부 변경을 막는다. 같은 View에 오래된 복원 상태를 주어도 최대값은 낮아지지 않는다. 실제 원장/청구/경제 계약은 변경하지 않았다.
- **수정 후 직접 실행:** `:app:testDebugUnitTest --tests '*ProgressInteractionTest'` 14개 통과, 실패·오류·생략0. 기존10개(Pending·실패 복구·늦은 확인/결과·제한·View 교체)와 추가4개(소멸 뒤 역행/청구0·높은 revision, View 교체/새 기간, 맵 방어/오래된 복원, 데이터 거절과 별개인 현재 gate 적용)를 포함한다.
- **자동 검사:** `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 성공, Lint 오류0·경고17. 기존4개와 신규 `testRemovedRevisionGuardSurvivesViewReplacement`를 포함한 기기 시험5개를 컴파일했다. 저장소·diff 검사도 통과했다. 로그는 `/private/tmp/progress-revision-validation.txt`다. core/휴식 계산·View 배치/문구는 수정하지 않아 전체 core/앱 및 무관한 검사를 반복하지 않았다.
- **이전 기기 근거:** 위 표·화면·APK metadata의 기기4개/터치/Activity 재생성 증거는 수정 전 `339e2b3`의 실행 근거로 보존한다. 최초 로컬 보완에서는 B 단독 기기 배정을 지켜 JVM/컴파일만 진행했다. 이후 메인이 C에 단독 슬롯을 다시 배정해 아래 수정 표적을 실행했으며 이전4개 전체는 반복하지 않았다.
- **수정 후 기기 직접 실행:** 제품 코드가 고정된 `4ad549dbbda615928d91dfd1b1d417422b0e628d`에서 격리 APK의 실제 package/시험 target이 `io.drivegrowth.app.progress57`임을 aapt로 확인한 뒤 설치했다. 신규 `ProgressViewTest#testRemovedRevisionGuardSurvivesViewReplacement` [1개 통과·0.119초](evidence/issue57-progress-ui/revision-fix/targeted-device.txt). 임시 Activity의 Bundle에 `acceptedRevisions`를 넣고 revision5/수령→소멸→실제 recreate→복원→revision1/청구가능의 늦은 Content를 전달해 [rollbackAccepted=false, max=5 로그](evidence/issue57-progress-ui/revision-fix/restore-log.txt)와 [선택 소멸 화면](evidence/issue57-progress-ui/revision-fix/revision-restored.png)을 확인했다. 이는 격리 호스트의 실제 직렬화 검증이며 제품 MainActivity/프로세스 종료·원장 복원 연결 성공은 아니다. 나머지 기존4개는 앞선 근거를 유지하고 수정 후 새 실행으로 바꾸지 않는다.
- **수정 기기 반환:** 자기 격리 패키지2개만 제거했다. [파일 전](evidence/issue57-progress-ui/revision-fix/product-hashes-before.txt)/[후](evidence/issue57-progress-ui/revision-fix/product-hashes-after.txt)의 6개 해시와 [제품 APK](evidence/issue57-progress-ui/revision-fix/product-apk-after.txt)가 동일하며 권한·기어·전역 크기/밀도/글꼴은 변경하지 않았다. [종료 상태](evidence/issue57-progress-ui/revision-fix/state-after.txt)는 원래 user10·1080×600/120dpi/fontScale설정null·기존 내비 Activity 복귀이며 활성 시험·추가 forward 없음, 기존 AVD PID64177/ADB5038 PID66071 유지다. 반환 보고 이후 추가 adb를 하지 않는다. [정확 소스·APK SHA-256 metadata](evidence/issue57-progress-ui/revision-fix/metadata.json), 원본 APK/로그 `/private/tmp/progress-revision-device-evidence/`, 임시 호스트 `/private/tmp/progress-device-host/`를 보존했다.

09의 #57 복원 계약에 최대 revision 맵의 수명주기·동일 프로필 범위, View 내부 sequence 비교와 호스트의 조회 세대 책임을 구분했다. A의 같은 재현·영향 경계 재검토에 새 로컬 커밋을 인계하며, PR 게시/CI/main 머지는 계속 대기한다. #55·#53·공통 타입·DB·MainActivity는 수정하지 않았다.

### #57 독립 재검토 추가 수정 — 표시 후 복원 순서

2026-11-07, A가 `6e02ef7`(제품 코드 `4ad549d`)에서 `render(revision1/청구가능) → restore(동일 키 최대revision5) → claim` 순서의 추가 P2를 재현했다. 복원 맵은5가 됐지만 현재 표시가1로 남아 청구 이벤트를 허용했다. 원래 소멸 지적은 해결됐으나 복원 순서가 반대인 경계가 남은 것이며 실제 DB 중복 지급 재현은 아니다.

정식 `restorationInvalidatesOlderVisibleContentInEitherCallOrder`, `invalidatingVisibleContentPreservesPendingAndWaitsForAnExplicitRecoverySnapshot`을 먼저 실행해 **수정 전2개 실패**를 확인했다(`/private/tmp/progress-restore-order-red.txt`). 복원 맵 병합 뒤 현재 Content의 모든 항목을 재검사해 낮은 revision이 있으면 행동 없는 Loading으로 무효화했다. 최대 이력·Pending·요청 키·재조회 잠금은 보존하며 맵만으로 수령/실패 결과를 생성하지 않는다. `canClaim`도 항목 revision을 확인한다. 조회 세대 비교 이력은 유지하므로 같은 낮은 Content 재전달은 조회 대기를 끝내지 않으며, 유효한 새 Content가 수락되면 다시 표시한다.

수정 후 진행 UI 단위 **16개 통과, 실패·오류·생략0**이다. `render→restore`와 `restore→render`, 낮은 Content 재전달의 표시/청구 차단, 유효 Content 복귀, Pending 보존·명시적 실패 복구와 기존 소멸·새 기간·gate/불변성 회귀를 포함한다. `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`도 성공했고 Lint오류0·경고17이다. 신규 `testRenderBeforeRestoreClearsStaleContentAndRetainedButtons`를 포함한 기기 시험6개가 컴파일됐으며 변경된 View 표적만 새 배정 슬롯에서 실행한다. 로그는 `/private/tmp/progress-restore-order-validation.txt`다. 전체 core/앱과 앞선 기기 시험 전체는 반복하지 않는다. 09의 #57 복원 순서·표시 무효화 계약을 함께 갱신했으며 공통 호스트/DB·다른 기능은 변경하지 않았다.

추가 배정된 C 단독 기기 슬롯에서 제품 코드 `70f981e1bc1346f22e34c36672e89f25f2290f09`를 고정하고, 격리 APK의 package/시험 target `io.drivegrowth.app.progress57`을 aapt로 확인해 설치했다. 새 `ProgressViewTest#testRenderBeforeRestoreClearsStaleContentAndRetainedButtons`만 [1개 통과·0.135초](evidence/issue57-progress-ui/restore-order-fix/targeted-device.txt)했다. 실제 View에서 render→restore 직후 옛 내용·버튼 제거, 낮은 Content 재전달 및 보관한 옛 버튼의 청구 차단, 유효한 revision6 Content와 그 버튼의 청구 복귀를 확인했다. 임시 실제 Activity에서도 [Loading 화면](evidence/issue57-progress-ui/restore-order-fix/restore-order-loading.png)과 [rollbackAccepted=false/buttons=0/max=5 로그](evidence/issue57-progress-ui/restore-order-fix/restore-order-log.txt)를 확인했다. 앞선 기기 시험 전체를 다시 실행한 것으로 표시하지 않는다. [정확 소스·APK SHA-256 metadata](evidence/issue57-progress-ui/restore-order-fix/metadata.json)를 보존했다.

시험 후 자기 격리 패키지2개만 제거했다. [제품 파일 전](evidence/issue57-progress-ui/restore-order-fix/product-hashes-before.txt)/[후](evidence/issue57-progress-ui/restore-order-fix/product-hashes-after.txt) 6개와 [제품 APK](evidence/issue57-progress-ui/restore-order-fix/product-apk-after.txt)는 동일하다. [종료 상태](evidence/issue57-progress-ui/restore-order-fix/state-after.txt)는 기존 내비 Activity·user10·1080×600/120dpi·fontScale설정null이며 권한·기어·전역 설정·차량 서비스는 변경하지 않았다. 활성 시험·추가 forward 없음, 기존 AVD PID64177/ADB5038 PID66071 유지로 반환 보고했고 이후 추가 adb를 하지 않는다. 원본 APK/로그는 `/private/tmp/progress-restore-order-device-evidence/`에 보존한다. 이번 검증은 고정 입력의 표시/청구 이벤트 경계이며 제품 호스트/저장·실제 수령·SDK·프로세스 복원 완료가 아니다. A 재검토 통과 전 PR 게시 대기를 유지한다.

### #57 독립 재검토 추가 수정 — 과거 선택 복원의 요청 이력 보존

2026-11-07, A가 `f01ce65`에서 청구 전 저장한 uiState를 같은 View에 복원하면 현재 Pending/요청 키가 지워져 동일 offer가 두 번 청구되는 추가 P2를 재현했다. 앞선 revision 두 지적은 해결됐으며 이번 문제는 선택 상태와 요청 이력을 함께 과거 값으로 교체한 별도 경계다. 실제 DB 중복 지급이 아니라 UI 청구 이벤트 중복을 재현한 것이다.

정식 `restoringOldSelectionCannotForgetSubmittedOfferOrEnableAnotherClaim`·`restoringOldRetryFlagCannotUnlockAnOutstandingReadRequest`를 먼저 실행해 **수정 전2개 실패**를 확인했다(`/private/tmp/progress-request-restore-red.txt`). 요청 키는 현재/복원/알려진 Pending 키의 합집합으로 보존하고, 현재 미확정 Pending과 활성 Retry 잠금을 과거 null/false로 지우지 않도록 수정했다. 선택/탭은 복원하되 요청 이력과 분리한다. 서로 다른 Pending은 모두 `pendingConflicts`로 보존해 행동 없는 조회 대기와 복구 설명을 표시한다. 일반 Content만으로 두 요청이 모두 끝났다고 추정하지 않으며, 호스트가 각 요청의 실제 조회/복구 완료를 확인하고 `completePendingRecovery(resolved)`로 알려진 참조를 명시 해소한 뒤 새 Content를 공급한다. 이 API는 UI 잠금 해소일 뿐 지급·실패·원장 상태를 생성하지 않는다. 미확정 요청과 재화의 실제 조회/복구는 기존 담당의 후속 연결이다.

수정 후 진행 UI 단위 **20개 통과, 실패·오류·생략0**이다. 동일 offer 재전송 차단·총 청구1개, 다른 목표 청구 차단, 과거 false의 중복 Retry 차단, 두 Pending의 부분/전체 명시 복구와 외부 Set 변경 격리, 명시 실패 뒤 새 offer 재시도를 검증하고 기존16개 revision/양순서 복원/낡은 표시 무효화 회귀도 유지했다. `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 성공, Lint오류0·경고17이다. 신규 `testOldSelectionRestoreKeepsRequestLocksAndConflictsWaitForRecovery` 포함 기기7개는 컴파일됐으며 새 표적만 배정 슬롯에서 실행한다. 로그 `/private/tmp/progress-request-restore-validation.txt`를 보존했다. 09의 API/복원/해소 계약만 함께 갱신하며 MainActivity·공통 타입/DB·다른 게임 규칙은 바꾸지 않았다. 새 기능 배정 동결을 따라 이 수정과 인계만 마무리하고 A 재검토 전 PR 게시 대기를 유지한다.

코드 `0930bd3e39a5d1aca3e63f1b4556408b93fd4553`를 고정한 뒤 메인이 허가한 C 단독 슬롯에서 격리 package/시험 target을 확인하고 새 `testOldSelectionRestoreKeepsRequestLocksAndConflictsWaitForRecovery`만 [1개 통과·0.189초](evidence/issue57-progress-ui/request-restore-fix/targeted-device.txt)했다. 과거 선택 복원 후 같은 offer/보관 버튼과 다른 목표의 청구 차단, 과거 Retry false 복원 뒤 재조회1회, 충돌 명시 해소 전 대기와 외부 확정 Content 표시를 실제 View에서 확인했다. 임시 호스트에서 네이티브 터치 청구 뒤 과거 선택을 복원하고 Pending 충돌을 만든 다음 실제 Activity를 재생성해 [청구1개·복원 후 conflicts2/requested2 로그](evidence/issue57-progress-ui/request-restore-fix/request-restore-log.txt)와 [추가 청구 없는 복구 화면](evidence/issue57-progress-ui/request-restore-fix/recovery-restored.png)을 확인했다. 이 임시 Activity는 Pending 충돌 집합·요청 키·최대 revision을 Bundle에 보존했다. [소스·APK SHA-256 metadata](evidence/issue57-progress-ui/request-restore-fix/metadata.json)에 고정 소스와 실행 범위를 남겼다. 이전6개 기기 시험을 이번 코드에서 모두 실행한 것으로 바꾸지 않는다.

자기 격리 패키지2개만 제거하고 [제품 파일 전](evidence/issue57-progress-ui/request-restore-fix/product-hashes-before.txt)/[후](evidence/issue57-progress-ui/request-restore-fix/product-hashes-after.txt) 6개와 [제품 APK](evidence/issue57-progress-ui/request-restore-fix/product-apk-after.txt)의 동일성을 확인했다. [종료 상태](evidence/issue57-progress-ui/request-restore-fix/state-after.txt)는 원래 내비 Activity·user10·1080×600/120dpi·fontScale설정null이며 권한·기어·전역 설정·차량 서비스를 바꾸지 않았다. 활성 시험·추가 forward 없음, 기존 AVD PID64177/ADB5038 PID66071 유지로 반환했고 이후 추가 adb를 하지 않는다. 원본 APK/로그는 `/private/tmp/progress-request-restore-device-evidence/`에 보존했다. 실제 #45 저장 복구·지급/실패 및 제품 MainActivity 연결의 성공 근거가 아니며 A 재검토와 메인 PR/CI/머지 조율을 기다린다. 현재 작업 마무리 뒤 새 기능/검증 작업을 시작하지 않는다.

### #57 독립 최종 재검토와 최신 main 통합

A가 최종 검토 head `4f64ffc01ad14e922c1fe181e47cf64db849feb4`에 대해 코드 APPROVE·구조 CLEAR를 확정했다. 기본20개+독립표적5개=25개 통과 및 재빌드 APK의 코드/리소스가 실제 기기 증거 APK와 일치함을 확인한 독립 보고를 재사용했다. 이는 C가 새로25개를 실행한 기록이나 사람 승인 기록이 아니다. 기존 소멸 revision·표시 후 복원·요청 이력 복원 P2 세 건이 해소됐으며 실제 저장/지급 연결 완료로 확대하지 않는다.

게시 준비에서 최신 main `1089926f7815bf49d3c5a6f233b06f28d5ee5c49`(#126 가챠 계약, #124/#125 표시 UI 포함)을 일반 병합했다. 06/09의 추가 절 충돌은 main의 해당 파일 전체와 #57 절 원문이 각각 보존됐는지 확인해 합쳤다. 검토 원본 `4f64ffc` 대비 진행 UI 제품·debug fixture·리소스·정식 JVM/기기 시험의 diff는0이다. main의 다른 UI·공통 계약·기기 증거는 보존했다.

통합 조합에서 `:core:test --tests '*RestBonusTest' :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline`를 직접 실행해 **휴식15·앱116개 통과(실패/오류/생략0), APK/Lint/시험APK 성공**을 확인했다. Lint오류0·경고19는 합쳐진 기존 UI/SDK 경고를 포함한다. 저장소21/26/319·diff검사도 통과했다. 무관한 전체core와 기기 시험을 중복 반복하지 않았다. 새 원격 head/CI와 main 머지는 연결 PR에서 관리하며 #57 전체 종료 없이 메인 조율을 따른다.

게시 직전 #127 재료 계약이 main `50b856d1c5aef61fe838bf3310d5908fca6035bd`에 반영돼 이를 다시 일반 병합했다. 충돌은06의 추가 절만 있었고 main 파일 전체와 #57 원문을 모두 보존했다. 재료 계약/정식 시험·02/06도 그대로 유지했다. 진행 UI 제품·리소스·정식 시험은 검토 `4f64ffc`와 계속 동일하다. 새 계약은 UI에서 호출하지 않으므로 전체 core/기기를 반복하지 않고 `:app:testDebugUnitTest --tests '*ProgressInteractionTest' :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline`를 실행해 진행20개·APK/시험APK/Lint성공(오류0·경고19)을 확인했다. 이전 통합의 앱116개 결과와 구분하며 로그는 `/private/tmp/progress-material-main-validation.txt`다.


메인이 지정한 최신 main `94a8ee39b7d6c5724d6dfb46515837ef635b1290`(#129 진행 UI)을 추가 일반 병합했다. 06의 모든 main 문단과 양쪽 검증 기록, 09의 #57 절을 보존했고 기존 #55 첫 복원 계약 문단만 의도한 수정으로 유지했다. 검토/장치 원본 `515507c`의 Shop 제품·정식 시험·리소스10개 Git blob과 공개 증거 해시는 계속 동일하다. `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin --offline --no-daemon`은9초에 성공했다. 전체/기기 시험은 반복하지 않았으며 앞의225/99는 main50b 조합의 실제 결과로 보존한다. 최종 저장소21/26/325·PR 전체 diff 검사 통과다. 작성자 원격 게시가 자동 승인 검토에서 거절되어 재시도하지 않고 clean 고정 head·범위·본문을 메인에 인계한다. 원격 push/PR/CI 성공으로 기록하지 않는다.

## #52 가챠 독립 표시·확인·외부 결과 UI 본인검증 — 2026-11-07

기준 main `fbbf98e0ad53bdaa742ac17dcaf94183388b2742`에서 새 `app/52-gacha-ui` worktree를 사용했다. #52 최신 본문/댓글0·PR60·PRD·기술/화면·계획/기존 검증을 대조하고 실제 세로4p/가로5a 시안을 직접 열었다. 시안의3000을 옮기지 않고 비용/권수·현재 후보·적용 확률·보장·공간은 공급 입력으로 표시한다. 세부 입력/행동·복원·실패 해소 책임은 [09 가챠 연결점](09_UI_UX_SPEC.md#52-가챠-독립-표시확인결과-ui-연결점)에 기록했다.

| 실행 | 실제 결과 |
|---|---|
| 초기 기대 시험 | 미구현 가챠 타입 참조로 컴파일 실패 확인. 동작 실패 재현으로 표시하지 않음 |
| `:core:test :app:testDebugUnitTest` | core191·app112(신규 GachaInteraction16 포함) 전부 통과, 실패/오류/생략0 |
| `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` | 성공, 최종 전체명령 BUILD SUCCESSFUL(26초) |
| Lint | 오류0·경고19. 기존18+프로그램 생성 GachaView의 XML 도구용 생성자 안내1 |
| 구현 중 컴파일 실패 | 새 외부 Result 처리의 nullable Pending 접근1건. safe call 수정 후 위 최종 검사 성공 |
| 저장소·diff 검사 | 통과. 문서 수정 후 최종 재검사 포함 |

JDK17·기존 Android SDK35·Gradle8.11.1, `--offline --no-daemon`을 사용했다. UI는 Gacha.drawRarity/무작위 추첨/확률 재분배/해금 판정/SQL·경제 쓰기를 호출하지 않는다. 기존 GarageStyle·중립 차량 vector와 GameContentSnapshot/GachaRules/Rarity/Settlement/OwnedVehicle을 재사용했고 미머지 #111/#121 타입에 의존하지 않았다. 공유 타입/fixture·MainActivity·탐색/gate·기존Garage·SQL/경제·Manifest/Gradle/CI·README/02/08은 변경하지 않았다.

정식16개는 확인/취소 무변경, 유료/무료 A/B/A 실패 재시도의 공급 경제키 유지·새 attemptId, Pending 중복/생성자 복원/제한, 낮은/같은 세대의 다른 내용 거절, callback 성공 뒤 확정 조회 대기, 확정 결과 이후 새 키, 이전 실패 미해소 상태의 변경 키 차단·명시 해소, 입력/요청/복원 목록·맵 방어 복사, 확률 합계·옵션키 충돌·필수 정보 누락·게임 콘텐츠 참조 거절, 조회 token·동기 응답/release·차고 이동 중복을 확인했다. 기존 활성 인스턴스에 과거 상태를 주입하는 restore API는 없으며 복원은 생성자 한 번만 받는다. 실제 경제 키의 저장/중복 확정은 호스트/#47/#121/#122 책임이고 UI 상관키로 대체하지 않는다.

`io.drivegrowth.app.ui.gacha.GachaViewTest`7개를 시험 APK로 컴파일했다. 대상은 공급 적용 확률/보장 회차, 확인·취소·callback Pending, 제한/옛 버튼·생성자 Pending 복원, 실패 옵션 A/B/A, 빈후보/보장불가/공간·재화·권수 부족/누락/미지원, 외부 정산 개체/차고 이동, 긴 문구와320×440/840×1184/1360×948dp layout이다. 실제 장치 배정 전이므로 **기기 실행·터치/화면 캡처는 미수행**이며 표적7개 예상 점유2~3분이다. adb/AVD 조회·설치·실행·기기 설정·제품 데이터를 조작하지 않았다. 위젯 컴파일을 실제 SDK/장치 성공으로 바꾸지 않는다.

빌드 제품 APK SHA-256 `f0f6356d2475c1019a9d65b39980dd953f8ee7e1bab06684d0ef9ae5c57a0cdb`, 시험 APK `4310fce12bf60de7bd6567c57f8eb603d98aff80a156dc14d09ff1069cc66c10`이며 설치본 해시가 아니다. 원본 로컬 로그는 `/private/tmp/gacha52-red.log`, `gacha52-state-green.log`, `gacha52-view-check.log`, `gacha52-expanded-tests.log`, `gacha52-final-check.log`다.

실제 #47 추첨/차감/차량·티켓/보장 저장, #121/#122 공통 거래·실패 복구/재실행, MainActivity/탐색/공통 gate/발견 기록 연결·실차/공식 폼팩터는 미완료다. debug 고정 결과는 실제 획득이 아니고 확정 결과 모양의 시험 입력이다. #52 전체 종료·체크/라벨/담당/차단 관계는 변경하지 않는다. 본인검증 커밋을 독립 검토에 인계하고 PR은 검증 전 게시하지 않는다. 사용자 동결 지시대로 이미 착수한 #52와 인계된 #55 결함 외 새 작업은 시작하지 않는다.


### #52 가챠 두 P2 수정과 최신 main 통합 — 2026-11-07

독립 검증 A의 원본 `a4625484b78a5c0619c004d86b3067c5640761a2` 검토는 기존 정식 단위16개 통과 뒤 추가4개 중 assertion3개 실패로 **두 P2**를 확인했다. 미해소 경제 RequestId를 새 옵션 ID/변경 비용 또는 기존 다른 옵션으로 옮기면 원래 의도 대조를 우회했고, 같은 정산의 첫 차고 이동 완료가 두 번째 이동 중 늦게 오면 두 번째 잠금을 해제했다. 실제 차감·중복 지급이나 DB 실패 재현은 아니다. 검토 원문·metadata와 독립 추가 소스는 로컬 `/private/tmp/gacha52-review-a-evidence/`에 보존했다.

기존 B가 소유권 반환·작업 정지를 확인한 뒤 A 구현이 기존 미커밋 제품4파일과 신규 회귀 파일을 그대로 인계받아 완성했다. 인계된 신규 회귀의 수정 전 assertion3개 실패(`/private/tmp/gacha52-boundary-red.log`)와 수정 후 Gacha 단위 성공(`/private/tmp/gacha52-boundary-green.log`) 원문을 읽었다. 제품 수정 커밋은 `10cd845`다.

- 기존 옵션 ID별 보존 원문 검사를 유지하면서 모든 reservation에서 같은 경제 RequestId의 전체 `GachaDrawInput`을 대조한다. 옵션 ID·표시 세대·가격·다른 옵션으로 재결합해 우회하지 못한다. 정상 A/B/A는 보존 경제 키와 새 attemptId로 재시도하며, 호스트의 확정 실패 조회 뒤 명시적 해소는 유지한다. Pending/성공 조회 대기 키는 해소하지 않는다.
- `GachaGarageRequest(settlementId, ownedVehicleId, token)`을 OpenGarage 이벤트·현재 Pending·완료·새 View 생성자 복원에 함께 보존한다. 경제 RequestId와 별도인 이동 시도 token을 포함한 현재 요청 전체가 일치해야 잠금을 해제한다. 같은 정산의 이전 완료·복원 뒤 이전 완료는 현재 이동을 끝내지 못하고, 현재 완료 뒤에는 다시 이동할 수 있다.
- 정식 단위 회귀4개는 키 이동2건·명시적 실패 해소, 차고 두 번째 이동/복원/정확 완료, Pending·성공 대기 키 보존을 확인한다. Android View 회귀2개는 실패 키의 다른 옵션 이동을 새 View에서 막는 버튼 동작과, 두 번째 차고 이동 복원 후 옛 완료가 잠금을 유지하는 버튼 동작을 확인하도록 추가했다. 임의 sleep·SDK·새 의존성은 사용하지 않는다.

최신 main `f14c1933592515ab938d39fefba68b3be31cae6a`(PR #130 포함)를 일반 병합했다. 06·09의 같은 위치에 추가된 기록 충돌은 양쪽 기록을 모두 보존해 해결했고 #124/#125/#126/#127/#129/#130의 기존 제품·시험·증거를 유지했다. README 상단 임시 표는 표시 UI 부분 인계와 공통 제공/실제 소비·SQLite 미제공 범위를 맞췄다. 공통 타입·DB·경제·MainActivity/SDK·Gradle/CI를 새로 수정하지 않았으며 이미 제공된 공통 계약을 재구현하지 않았다.

| 수정·통합 후 직접 실행 | 실제 결과 |
|---|---|
| `:app:testDebugUnitTest --tests '*Gacha*' :app:assembleDebugAndroidTest --offline` | 정식16+신규회귀4=20개 통과, 시험 APK 컴파일 성공. 로컬 `gacha52-handoff-targeted.log` |
| `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline` | BUILD SUCCESSFUL 12초, core225·app139 전부 통과(실패/오류/생략0). Gacha20개 포함. 로컬 `/private/tmp/gacha52-handoff-full.log` |
| Lint | 오류0·경고20. 최신 main의 기존19개와 GachaView의 프로그램 생성자 안내1개이며 오류를 억제하지 않음 |
| `python3 scripts/check_repository.py`·`git diff --check` | 통과. 필수21·문서26·로컬링크326. 문서 최종 수정 뒤 재확인 |
| `GachaViewTest` | 기존7+신규2=9개 컴파일. **수정본 기기 실행은 미실행**이며 검증 A에 고정 소스·시험 APK와 인계 |

빌드 환경은 JDK17·기존 Android SDK35·Gradle8.11.1/Kotlin2.2.21/AGP8.10.1이다. 수정/통합 APK SHA-256은 `e72187ff2f1d2513feec57cde9552d6d4da5be41cdc92f920492b3ef8575a5fd`, 시험 APK는 `ff89ee36ba56d7712e37ece4897c0ead8e8bc0e480780cb67a39a662efdcc43c`이며 설치본 해시가 아니다. 빌드 후 변경은 README·06·09 설명뿐이다.

독립 검증 A는 **수정 전 a462548**에서 정식 View7개와 추가 실제 호스트2개를 최종 통과했다. 최초 추가 호스트의 잘못된 좌표 오류는 시험 수정 뒤 해당1개만 재실행했고 이 도구 오류도 원로그에 남겼다. 320/936dp 실제 호스트·고정 입력 터치·새 View 생성자 복원이며 제품 Activity/프로세스 저장이나 실제 SDK/경제 시험은 아니다. 이 9개 실행을 수정본의 기기 통과로 옮겨 쓰지 않는다. A의 당시 종료 기록상 자기 격리 패키지만 제거했고 제품 APK/파일6개·권한·사용자10·기어/전역 창 설정을 보존해 내비 전면으로 반환했다. **이번 마무리 세션은 adb 조회·설치·AVD·설정·제품 데이터를 전혀 조작하지 않았다.**

보조 코드 검토 APPROVE·구조 CLEAR를 회수했다. 검토 head `f8e4449`와 제품 수정 `10cd845`의 Gacha 소스가 동일하며 추가 필수 결함은 없었다. 두 lane은 원인·수정·시험 소스와 기존 red/green/XML을 읽은 검토이고 Gradle/기기를 새로 실행하지 않았으며 사람 승인으로 표시하지 않는다.

실제 #47 소비·티켓/보장/차량 지급·#122 SQLite·발견 저장·호스트 탐색/gate 연결·Activity Bundle/프로세스 복원·공식 폼팩터/실차는 미완료다. 이미 제공된 #126 가챠 메모리 참조와 #127 재료 순수 검증을 미제공으로 되돌리지 않으며 UI의 실제 연결과 구분한다. 이번 결과는 #52 전체 완료가 아니다. 수정본 장치 검증·정확한 원격 head/CI/PR/main 반영은 메인이 이어 기록하며 구현 세션은 로컬 고정 커밋/PR 본문 인계 후 새 기능을 시작하지 않는다.


### #52 수정본 최종 독립 검증 회수 — 2026-11-07

앞 절의 수정본 기기 미실행 표기는 구현 인계 당시 기록이다. 이후 **별도 검증 A**가 고정 `6bedb76a27dcf06b5a14ea782d1d19de91fe1c08`(통합 main `f14c193`)에서 두 P2 해소·추가 확정 결함0, 코드 APPROVE·구조 CLEAR를 확인했다. 정식20+독립7=**단위27개 실패/오류/생략0**, 표적 단위/APK/시험 APK 빌드16초 성공이다. 구현 세션은 [독립 보고서](evidence/issue52-gacha-ui/REPORT.md)·[로그](evidence/issue52-gacha-ui/targeted-build.txt)·XML 원문을 읽고 회수했으며 작성자의 전체 core225/app139·Lint20을 독립 검증자가 반복한 것으로 표시하지 않는다.

기존 ADB5038·emulator-5556·user10/boot23의 격리 앱 `io.drivegrowth.app.gacha52finala`에서 **수정된 정식 View9+실제 호스트2=11개, 7.116초, 실패/오류0**이었다. [장치 결과](evidence/issue52-gacha-ui/device-tests.txt)·[최종 호스트 이벤트](evidence/issue52-gacha-ui/host-log.txt)에 키 이동 차단/명시 해소·두 번째 이동 token 복원/옛 완료 거절/현재 완료 후 재이동과 A/B/A·외부 제한·옛 버튼·Pending 복원·성공 뒤 확정 조회 대기를 보존했다. [현재 이동 token 복원](evidence/issue52-gacha-ui/current-token-restored.png)·[320dp 긴 후보](evidence/issue52-gacha-ui/long-candidates-320dp.png)·[936dp 가로 호스트](evidence/issue52-gacha-ui/guaranteed-936dp.png)를 공개한다. 실제 화면과 로그를 직접 읽었으나 구현 세션이 장치를 재실행한 것은 아니다.

설치 앱 SHA-256은 `bad1e1314e0f3411c98cf7b9254148a1f144d2d37bb7fc3c9929115d8cebaab7`, 시험은 `1320fd3fb1372815c1ef29f5c882c400c4c5339b5ac0a91661096a198ecaf9e5`로 빌드/실제 설치가 일치했다. [metadata의 소스7개 Git blob·해시](evidence/issue52-gacha-ui/metadata.json)를 원본·현재 작업 파일과 직접 대조했다. [제품/설정 전후 발췌](evidence/issue52-gacha-ui/restoration.json)에서 제품 APK `793e1db8…`·기존 데이터 파일6개·권한·user10/boot23·1080×600/120dpi·font null/night no가 동일했고 자기 격리2패키지만 제거해 내비 전면으로 반환했다. 활성 시험 없음은 검증 A 보고이며 snapshot의 미지원 instrumentation 명령 오류를 성공 근거로 사용하지 않는다. 기어·전역 창·글꼴·CarService/VHAL·제품 데이터 변경 없이 반환했고 이후 추가 adb는 없다는 종료 보고를 회수했다.

[공개 증거 안내·격리 재현 호스트](evidence/issue52-gacha-ui/README.md)에 최소 자료를 모았다. 개인 경로·APK 바이너리·전체 dump를 제외하고 발췌/경로 치환은 명시했다. 이번 최종 정리는 문서·증거만 바꾸며 제품/정식 시험/리소스7파일은 고정 소스와 동일하다. 고정 입력·새 View 생성자 복원 시험이며 실제 #47 소비/경제·#122 SQLite·발견 저장·제품 탐색/gate·Activity Bundle/프로세스 영속 복원·공식 폼팩터/실차는 미완료다. #52 전체 완료로 닫지 않는다. 최종 PR·원격 head/CI·main 반영은 메인이 처리하고 새 기능은 시작하지 않는다.

### #122 가챠 SQLite 부분 구현

최신 main의 core225개·앱 단위139개, APK/시험APK 조립·lint·저장소 검사 통과. 독립 Android API37에서 GachaLedgerTest7개·RentalLedgerTest14개·SqliteGameChangeStoreTest26개 총47개 통과했다. 유료/무료의 전체 결과 재생, 성장 뒤 당시 개체 재생, 마지막 칸과 무료권의 두 연결 경쟁, 실패 후 원상태·재시도, 보장 회차, 일반 Add의 공간/source 검사, 일반 거래·대여 방문과 가챠의 요청 ID 충돌 양방향을 확인했다. v3 이전은 기존 원장을 보존하고 가챠 상태 미제공으로 남았으며 손상 진행값은 원본 파일을 보존해 실패했다.

신뢰한 신규 프로필 진행값은 시험에서 명시 입력했다. 기존 DB의 무료권/카운터를 기본값으로 생성하거나 제품 UI를 활성화하지 않는다. #50 프로필 초기화·복구·권위 콘텐츠 공급 연결, 추가 티켓 지급 명령과 #111의 실제 소비는 후속이다. 실제 기기 강제종료·전원/차량 SDK 연결 검증은 미수행이다.


## PR 수정본 재검토와 부분 인계 — 2026-11-08

검토 출발 main은 `7f8aa039`이다. #116·#111·#131·#135·#138은 각각 `0799eb5`·`094ee59`·`b777d1b`·`fc4fe328`·`7f8aa039`에 반영됐다. #136은 작성자 경민이 `a9d58d6`에 직접 머지했으며 이번 조율 채팅이 실행한 머지가 아니다. 과거 결과는 당시 head·환경의 근거로 보존한다. 아래는 독립 보고서·로그·XML/소스를 회수·대조한 AI 검토이며 사람 승인이나 이 문서 작업의 새 시험 실행이 아니다.

| PR·검토 head | 실제 검증과 현재 판단 | 남은 범위 |
|---|---|---|
| [#111](https://github.com/Yaho03/drive-growth/pull/111) `0003504` | 전체 GachaCommand 소비의 이전 두 P1 해결, 정식12+독립3=15개·소비 컴파일 및 구조 CLEAR, main 반영 | 제품 UI·프로세스 복원·무료권 지급 |
| [#116](https://github.com/Yaho03/drive-growth/pull/116) `9583e44` | 입력 snapshot·완료 정산 연결·원시값 세 지적 해결, main 반영 | 실제 발견 표시 이벤트·제품 소비. 이력 부재는 신규 판별 근거 아님 |
| [#131](https://github.com/Yaho03/drive-growth/pull/131) `4570870` / [#135](https://github.com/Yaho03/drive-growth/pull/135) `116b33b` | 도착 정산 정식18+독립4=22개·변경/포기 정식22+독립1=23개 통과, 구조 CLEAR, main 반영 | 실제 SDK·원장/화면 연결 |
| [#136](https://github.com/Yaho03/drive-growth/pull/136) `1322f85` | API34 격리 SQLite7개 통과. 원시 소수/문자열 거절·원본 보존 및 정상0/Long 상한/무료null 유지, 구조 CLEAR | 제품 초기 권위 상태·티켓 지급·UI/프로세스 |
| [#138](https://github.com/Yaho03/drive-growth/pull/138) `7cf700a` | 코드 정식13+독립4=17개, 구조 정식13+독립1=14개 통과. 중간 Int overflow를 BigInteger로 해결, main 반영 | 수리 명령·실제 저장/사고/화면. 잠정 내림은 최종 밸런스 합의 아님 |
| [#128](https://github.com/Yaho03/drive-growth/pull/128) `bb6e9da` → `a9ba814` | 기존 main 합성 SQLite7개 통과·코드/구조 수용. 새 head의 제품 동일·시험 delta 컴파일과 양쪽 검토를 확인해 main 반영 | 공용 통합 #139·발견 표시·SDK/제품 UI/프로세스 |
| [#134](https://github.com/Yaho03/drive-growth/pull/134) `f43c08e` → `c60f743` | 기존 합성 SQLite26개 통과. 도착/보관/주행·active 결제 검증 유지, 새 head 제품 동일·시험 delta 컴파일/독립 검토 후 main 반영 | 공용 이전 #139·SDK/제품 UI/프로세스 |
| [#137](https://github.com/Yaho03/drive-growth/pull/137) `b12f268` → `1451224` | 기존 합성 SQLite6개 통과. 포기 전체 명령·paid/정산·경로 방어 유지, 새 head 제품 동일·시험 delta 컴파일/독립 검토 후 main 반영 | 공용 이전 #139·SDK/제품 UI/프로세스 |
| [#139 최신 승인 검토](https://github.com/Yaho03/drive-growth/pull/139#pullrequestreview-5817122683) `7869634` | 이전 `c0566a7`의6통과·2실패로 수정 요청 후, 새 head에서 기존8+보완3=API34 SQLite11개 전부 통과(1.634초). 코드 APPROVE·구조 CLEAR·CI4개 SUCCESS | 결제 증거·구형 동일 GENERAL 재생 두 P2 해결, 새 prefix/기능 소유 충돌 방어 유지. 작성자의 Draft·미머지 상태 유지 |
| [#140 수정 요청](https://github.com/Yaho03/drive-growth/pull/140#pullrequestreview-5817031282) `60de79c` | 정식23+독립4=27개 중26통과·snapshot1실패, 코드 REQUEST_CHANGES·구조 BLOCK | 조회 List를 먼저 한 번 복사하고 같은 사본만 검증·변환. SQLite 경쟁 재현은 아님. FirstGrant false≠NEW/오류 전파는 정상 |
| [#141](https://github.com/Yaho03/drive-growth/pull/141) `0fee943` | 정식5+독립3=JVM8개·앱 소비 컴파일 통과, 구조 CLEAR. 부모 #138 main 반영·base main/자체3파일 확인 후 main 반영 | 판매/폐차 계획 부분. 수리·실제 SQLite 경쟁/DB·제품 재시작·화면은 후속 |

#128/#134/#137의 위 실제 장치 실행은 main `a9d58d6`과 각 이전 head의 합성본이다. 최종 main `7f8aa039` 합성에서도 공용 `SqliteGameChangeStore` blob `16c22a8b15d37a8040c83de7d9e7d102c6541b61` 동일·충돌0을 확인했다. 새 head는 이 main 공급본을 병합하고 시험을 추가했으므로 코드 APPROVE·독립 구조 CLEAR와 최소 시험 소스 컴파일을 회수하고 동일 head CI4개 SUCCESS·실제 병합 원장 동일성을 확인해 머지했다. 새로 추가된 시험 case를 기기에서 다시 실행한 것으로 표시하지 않는다. 오래된 head 단독에 최신 decoder가 있었다고 쓰지 않는다.

#139의 v4 fixture는 옛 writer가 허용한 정합 command/result/request를 구성해 v5로 재개방한 시험이며 옛 v4 APK의 최초 쓰기를 실행한 근거가 아니다. #140은 공개 VehicleHistoryStore의 순회가 달라지는 결정적 입력에서 검증/사용 불일치를 확인한 JVM 경계이며 실제 DB 손상·중복 지급으로 확대하지 않는다. 구체 재현과 수정 확인은 각 공식 리뷰에 모았다. #139 최신 검증자의 제품 APK/정확6SHA·권한/설정 보존·자기2패키지 제거·내비 복귀/슬롯 반환 자료도 회수했다. 작성자 API37 120개를 이번 API34 11개와 합산하지 않는다.

모든 실행 슬롯의 자기 격리 패키지 제거·제품 APK/정확6파일 SHA·권한/설정 보존·내비 복귀 보고를 회수했다. 이전 #137 슬롯의 파일 목록 수집 공백은 arrival 종료→ledger 시작/종료6SHA 연결 근거로 보완한 것이며 과거 직접 수집으로 소급하지 않는다. 이번 next 슬롯6SHA는 별도 직접 수집 결과다. 이 조율/문서 작업은 추가 adb를 사용하지 않았다. 실제 제품 SDK/UI·프로세스/전원·실차와 #37/#38/#39/#46/#47/#49/#50/#122 전체 완료는 아니다.

이번 조율의 일반 merge 결과: #128 `a9ba814` → `21a9ab2dc56100a5d5f6f6413e188809fc902004`, #134 `c60f743` → `84ee22f58cfb2ff77aabdc08a9806ef1ed1ec53b`, #137 `1451224` → `c2ad04a660e52429241c19e4a231c3ea95f20fbf`, #141 `0fee943` → `977bc935034855ca103452668951a785a5256490`. 사용자 2026-11-08 직접 머지 지시를 PR 리뷰/설명에 기록했고 별도 사람 승인은 확보하지 못했다. 보호 우회·강제 push·브랜치 삭제는 하지 않았다. #139는 두 P2 해결 승인 검토 후 작성자의 Draft·미머지 상태를 유지하고, #140은 기존 수정 요청을 유지한다. 관련 기능 이슈는 전체 미완료로 열어둔다.

### #122 탁송·주행 공용 저장 연결

정원 제공 #128/#131/#134/#135/#137의 Ledger·계산·시험을 경민의 저장 통합 브랜치에 합쳤다. 코어270개·앱 단위139개, debug/시험APK 조립·lint·저장소/diff 검사 통과. 독립 API37 시험 DB에서 store.delivery/store.driving으로 생성·조회·확정하는 SQLite 시험90개(선택19·도착/주행12·변경/포기12·가챠7·대여14·원장26)가 통과했다.

무금액 선택 요청 ID의 재사용 거절, 탁송/주행 예약 ID 선점 거절, 공용 record의 source 검사, 두 연결의 구매 선택 한 건만 성공, v4→v5의 잔액·정산·발견·가챠 진행 유지, 이전 도중 실패 시 원본 바이트 보존을 검증했다. 기존 v1/v2/v3 이전과 구매/도착/임시보관/변경/포기·기본주행·가챠·사고 조회 회귀도 유지했다. 최종 도착/포기의 과거 정산 충돌 검사 보완 후 영향받는 도착·변경·포기 시험24개를 별도 재실행해 모두 통과했다.

실제 표시 이벤트의 발견 기록, 실제 내비/SDK·화면 명령 연결, Activity/제품 프로세스 강제 종료·전원 시험은 이번 범위에서 수행하지 않았다. 선행 기능 PR의 검토와 main 반영은 별도이며 이 통합 브랜치는 해당 코드를 소비한 검증 준비다.

### 2026-11-08 리뷰·인계 수정 검증

#136의 과거 가챠 결과 보완은 commit4bf21d7에서 진행/결제와 최초 명령을 대조한다. 독립 API37에서 GachaLedgerTest9개를 통과했고, counter/revision/금액/방향/요청/출처/규칙/정산ID/원장명령 손상 시 명시 실패와 원본 바이트·잔액·개체 보존을 확인했다. 무료 결과의 가짜0원 결제도 거절한다. #116은 승인된9583e44를 main0799eb5에 반영했다.

#139는 정원의 최신 app/38-change-ledger(d39df69)를 합쳤다. 도착·보관 함수에 규칙/요청ID, 경로 재시작에 replacing 인자를 반영하고 새 도착/보관 결과·경로 이력 표를 schema5 생성/이전에 포함했다. 이동한 record 호출의 DELIVERY/DRIVING 인자와 예약 ID 검사를 유지했다. 손상 주입 fixture가 새 예약 ID 차단에 걸리는 초기 시험은 fixture를 DB 행 직접 주입으로 수정했다.

수정 최종 APK에서 API37 SQLite113개(선택26·도착/주행19·변경/포기19·가챠9·대여14·원장26), core276개·앱139개·조립·lint·저장소/diff 검사 통과. 실제 제품 프로세스 강제 종료/차량 SDK·화면 연결은 이번에 수행하지 않았다.

### 원시 금액 변환 전 검증 (#136/#139)

공용 decoder 수정1322f85는 LedgerJson.money에서 기존 정수 타입/범위 검사를 재사용한다. 독립 API37의 #136 전용 APK에서 가챠11·원장26개 총37개를 통과했다. 정산 JSON에 소수500.75·문자열"500"·500.0·5e2·Long 상하한 초과·음수·불리언·null·배열·객체11종을 원문으로 주입해 STORAGE_FAILURE와 DB 원본 바이트·계정 잔액·개체 보존을 확인했다. 정상0/Long.MAX_VALUE와 무료 money=null도 유지했다.

같은 수정 커밋을 merge한 #139 최종 APK에서는 도착 결과 조회/재시도에도 원시 금액5종 손상을 주입해 명시 실패·행 보존을 확인했다. 독립 API37 SQLite116개(선택26·도착/주행20·변경/포기19·가챠11·대여14·원장26), 코어276·앱 단위139·APK/시험APK·lint·저장소 검사 통과. 최초 부팅 중 설치 실패 뒤 안정된 기기에 파일 전송 설치로 재시도했으며 제품 데이터·실차 SDK·실제 프로세스 강제 종료 시험은 수행하지 않았다.

### #139 활성 구매 증거·v4 동일 명령 재생 보완 — 2026-11-08

공급본 #137 b12f268의 선택 이력 읽기 검증을 소비해 active/inProgress/terms에서도 실제 구매 정산을 대조한다. 결제 행 누락·종류 오연결은 STORAGE_FAILURE이며 정상 수익 의뢰는 결제 정산 없이 조회된다. 공용 record는 source·실제 기능 소유 이력을 먼저 검사하고, 기존 GENERAL의 동일 명령/정산을 검증·재생한 다음 신규 요청의 prefix 예약을 적용한다. 다른 명령·신규 prefix 선점·실제 주행 소유 이력의 GENERAL 재생 거절을 유지했다.

독립 API37/ARM64 SQLite119개(선택28·도착/주행21·변경/포기19·가챠11·대여14·원장26), core276·앱 단위139개 실패/오류0, APK/시험APK 조립·lint·저장소/diff 검사 통과. v4의 drive/legacy-record와 delivery/legacy/settle fixture를 v5로 이전·재개방한 뒤 과거 정산·현재 잔액·DB 바이트 보존을 확인했다. 이 fixture는 구형 writer가 허용한 표현을 재구성한 시험이며 실제 v4 APK의 최초 쓰기나 제품 DB 이전 성공 근거가 아니다. 리뷰어의 API34 표적 실행을 작성자가 반복한 것으로 표시하지 않는다. 이번 API37 시험은 별도 에뮬레이터에서 수행했으며 제품 기기·실차·SDK/UI 전체 시험은 수행하지 않았다. PR 재검토 전 Draft를 유지한다.

같은 날 선행 #128/#134/#137이 main c2ad04a에 반영되어 최신 main을 merge했다. 구매 검증의 중복 주석 충돌은 공급본을 유지하고 inProgress 회귀는 보존했다. main의 가챠/처분/가격 변경을 임의 수정하지 않았다. 통합 후 core294·앱139·APK/시험APK·lint·저장소/diff 검사와 API37 SQLite120개(선택28·도착/주행22·변경19·가챠11·대여14·원장26)가 통과했다(57.616초, 실패/오류0).

### #47 GachaPull × SQLite 가챠 저장소 기기 시험 — 2026-11-08

호열 로컬 Mac에서 #111 `GachaPull`이 만든 명령을 #136 `SqliteGameChangeStore.commitGacha`로 확정하는 통합 시험 `GachaPullSqliteTest`를 실행했다. 콘텐츠는 앱 assets 20종(`trial-content-2026-11-07`), 권위 상태는 같은 snapshot으로 시험 주입했다(제품 초기값·무료권 지급 아님).

| 항목 | 값 |
|---|---|
| 환경 | macOS arm64, JDK 17, Android Emulator 37.2.13, AVD `drive_growth_api34`(`system-images;android-34;default;arm64-v8a` r4, Pleos 이미지 아님), `emulator-5554` |
| 명령 | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.drivegrowth.app.data.GachaPullSqliteTest,io.drivegrowth.app.data.GachaLedgerTest` |
| 결과 | 15개 통과, 실패·오류·생략 0 (`GachaPullSqliteTest` 4 + 기존 `GachaLedgerTest` 11) |

`GachaPullSqliteTest`가 확인한 것: 유료 뽑기 확정 후 DB 재개방 → 같은 명령 재제출이 처음 `GachaSettlement`·진행 상태를 재생하고 추가 차감 없음 / 같은 requestId·다른 입력 `STATE_CONFLICT`·무변경 / 보유 2·칸 3에서 같은 상태로 만든 두 명령 중 한 건만 확정 / 무료권 1장은 금액 변경 없이 취득액 0으로 한 번만 쓰이고 재개방 뒤 두 번째는 `INSUFFICIENT_FUNDS`.

미검증: Pleos 이미지·실차, 제품 UI에서의 명령 보존·프로세스 강제 종료, 실제 무료권 지급(#50)·제품 권위 상태 공급.


## 공용 통합·조회 소비·가챠 통합시험 재검토 — 2026-11-09

이 절이 아래 PR의 최신 상태이며 위 2026-11-08 Draft/수정 요청 기록은 당시 결과로 보존한다. 기존 검증 A/B의 원본 소스·실행 로그·XML/설치·보존 자료와 최신 head를 PR 조율자가 회수·대조했다. 조율자가 장치 시험을 새로 실행한 기록이 아니며 AI 검토를 사람 승인으로 표시하지 않는다.

| PR·최종 검토 head | 확인한 범위와 결과 | 남은 범위 |
|---|---|---|
| [#139](https://github.com/Yaho03/drive-growth/pull/139) `0ba1bac` | 기존7869634의 구매 결제 증거·지원v4 동일 GENERAL 재생 두 P2 해결/API34 SQLite11개 통과 근거 재사용. app/core/CI/scripts 동일·최신 main 기록 보존을 양쪽 검토자가 확인, 코드 APPROVE·구조 CLEAR·정확 head CI4개 성공 후 main `88ffa496a28d77110f6d1ef1b5c00049bd24415f` 반영 | v4는 정합 fixture 이전이며 실제 옛 APK 최초 쓰기/제품 DB 이전 성공 근거 아님. SDK/UI·발견 표시·프로세스/전원·실차 후속 |
| [#140](https://github.com/Yaho03/drive-growth/pull/140) `9753469` | snapshot 지적 해결. 정식24+기존독립4=JVM28개 통과·앱 소비 컴파일·코드 APPROVE·구조 CLEAR·정확 head CI4개 성공 후 main `cf4295fec0c2b2da03b47bf1112c7a6da3c44aa0` 반영 | 실제 SQLite 경쟁 재현 아님. FirstGrant false≠NEW·오류 전파 유지. 도감/초기화/무료권/제품 복원 후속 |
| [#145](https://github.com/Yaho03/drive-growth/pull/145) `503afa2` | 원래8b69ecc의 실제 assets→GachaPull→SQLite 정식4개를 검증 A가 Pleos API34/ARM64 격리 패키지에서 직접 실행해 통과(0.364초). 최신 main의 #139/#140 공급을 통합하고 원래 시험과 양쪽06 기록 보존을 재검토, 새 head CI4개 성공 확인 후 main `a3dcaabaa5c66ba4581f19ce1ba4c15f39dde33a` 반영 | 권위/무료권/용량은 시험 주입. 마지막 칸은 두 계획 순차commit이며 새 동시 경쟁 아님. 제품 발급/UI 명령 보존·프로세스 후속 |
| [#143 수정 요청](https://github.com/Yaho03/drive-growth/pull/143#pullrequestreview-5820998093) `27dff47` | 검증 B의 실제 Pleos API34/ARM64: 정식10+독립4 통과, 추가 삭제 회귀2개 실패/오류0(총16개,2.348초). 코드 REQUEST_CHANGES·구조 BLOCK, P2 한 건/두 증상으로 미머지 | 20분→5분소모 후 마지막 소비 행 삭제 시 state/재개방이20분을 반환. 0ms 중간 소비 행 삭제도 정상 수용. 기대 STORAGE_FAILURE·원본 보존 회귀 수정 필요 |

#143 추가 시험은 자기 격리 DB에 단일 행 삭제를 주입했으며 실제 제품 DB 손상·수익 중복 지급 재현이 아니다. 삭제 뒤 새 소모 재전송은 실행하지 않았다. 완전성 근거를 같은 원자 경계에 보존·검증하되 정상 실패/rollback을 유지해야 한다. 모든 AUTOINCREMENT gap을 손상으로 취급하라는 요구는 아니며 구체 재현·수정/재검토 조건은 공식 리뷰 한 곳에서 관리한다.

#145의 새 head는 기존 시험2파일에 기검토 main #139/#140을 정상 병합한 결과이며 팀원 제품 코드를 대신 수정하지 않았다. 기존4개 실행과 새 조합 검토를 구분한다. 작성자의 일반 API34/비Pleos15개를 검증 A의 Pleos4개와 합산하지 않는다. 검증 A145/B143 모두 제품 APK·기존 데이터6파일 SHA·권한/설정 보존, 자기2패키지 제거·활성 시험 종료·내비 복귀·슬롯 반환 자료를 회수했다. 반환 후 추가 adb는 사용하지 않았다.

사용자 2026-11-09 명시적 머지 지시와 별도 사람 승인 미확보·잔여 위험을 PR에 공개했다. #140/#145의 직접 머지 승인은 이 조율 채팅에서 추가 확인했다. 보호 우회·강제 push·브랜치 삭제 없이 일반 merge했다. #37/#38/#39/#40/#46/#47/#50/#122는 부분 조건만 반영하고 열어두며 #121 본문은 기존 메인 담당 범위로 수정하지 않는다. Blocking/Blocked by는 재조회했으나 이번 대상8개 모두 비어 있어 새 해제/ready 전환은 없고 기존 상태를 유지한다.


## #121 재료 참조·수리·초기화 계약 통합 검증 — 2026-11-09

기준 main `dda226d`에서 독립 작업 세 묶음을 일반 merge한 코드 head는 `4ddbf391c893b15df03da46e292542f8c8d05e24`다. 재료 `6c9fa882`, 수리 `89448ff6`, 초기화/첫 선물 `ec133802`의 소유 10파일이 통합본과 바이트 동일함을 독립 검토했다. 실제 제공 API·호출/저장 책임은 [기술 계약](02_SYSTEM_ARCHITECTURE.md#121-재료-강화-메모리-참조와-추가-계약)에서 관리한다. 제품 게임 규칙·SQLite·차량 SDK·UI는 이 변경에서 추가하지 않았다.

초기화 후보 Set이 외부 변경에 영향받는 P2는 생성·copy의 방어 복사/변경 불가 노출로 수정했다. 작성자는 기존19+새3 시험에서 수정 전2실패→동일22통과를 확인했고, 독립 재검토는 같은22개와 이전 독립5개를 실행해27개 모두 통과했다. 재료의 독립 코드 검토는 기존 관련70+요청 종류 간 경쟁2=72개, 수리는 기존13+금액/revision 경계2=15개 통과다. 별도 구조 검토도 각 정확 head에서 재료52·수리13·초기화/FirstGrant28개 통과 및 CLEAR를 기록했다. 시험 집계는 서로 중복되므로 합산한 제품 시험 수로 쓰지 않는다. AI 검토이며 별도 사람 승인과 구분한다.

통합 전체 검사는 JDK17·Kotlin2.2.21·AGP8.10.1·Gradle8.11.1·기존 SDK35에서 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --offline --no-daemon --max-workers=2`로 실행했다. **코어351개·앱139개, 실패/오류/생략0**, 앱/시험 APK 빌드 성공, Lint 오류0·기존 경고20이다. 1분11초에 종료했다. 시험 APK 빌드는 기기 실행 성공이 아니다.

처음 native 관리 worktree의 물리적 NFC 경로에서는 core JAR 생성 후 fixture의 기존 타입 조회가 실패했고 daemon/비증분 변경으로도 해결되지 않았다. 같은 SHA의 JAR을 물리적 ASCII/NFD 경로에 복사하면 최소 Kotlin import 시험이 통과해 경로 영향을 분리했다. 컴파일러 내부 원인은 미확정이다. 제품/Gradle 수정 없이 정확 코드 head를 `git archive`한 ASCII 사본 `/private/tmp/drive121-integration-ascii-diagnostic-20261109`에서 위 전체 검사를 실행했다. 추적 app/core·빌드 설정181파일을 Git blob/원본 live/실행 사본으로 비교해 불일치0을 확인했다. 관리 worktree를 이름 변경하거나 기존 개발환경을 초기화하지 않았다. 재현 시 이 컴퓨터의 NFC 경로 한계와 ASCII 실행 경로를 구분한다.

근거는 `/private/tmp/drive121-resume-20261109/integration/`의 source 비교·최소 재현·실행 로그/XML/Lint·결과 JSON에 보존했다. Debug APK SHA-256은 `0fbea17a999e4e4477350d7b6fc76f5fa9b85c808523500f0a831c8ea7364e71`, 시험 APK는 `97e7e398f2195157a596424768ebd8ebcfc43118cc7ef8376f51f62ea146f28a`다. 설치하거나 adb/장치·제품 데이터를 사용하지 않았다. 실제 SQLite 다중 개체/수리/초기화/선물 commit·신규 근거 발급·legacy 복구·가챠 무료권 경쟁·제품/프로세스 복원은 남아 있다. #121/#122/#48/#49/#50 전체 완료 근거로 확대하지 않는다. 최종 게시 head/원격 CI/main 반영은 연결 PR에서 확인한다.

## #52 저장 차고 읽기·mapper 독립 검토와 게시 준비 — 2026-11-09

원본 구현 head `320309d43b65a69782536b7b558976728b9ef6c2`는 기준 main `dda226d`에서 StoredHostSource/HostSnapshot/GarageHostMapper 및 전용 시험6파일만 변경했다. 기존 HostSession의 실제 read 경로에서 VehicleHistoryStore/#140 Garage.collection(history), RentalLedger.activity/accidentCount/shop과 게임 콘텐츠 가격을 소비한다. 실제 제공/미제공 화면 범위는 [09 연결점](09_UI_UX_SPEC.md#52-저장-읽기mapper-연결)을 따른다. 관리 명령·SQL·core·SDK/gate·Views/디자인은 이 기능에서 수정하지 않았다.

| 근거·실행 주체 | 정확한 범위와 결과 |
|---|---|
| 작성자 본인 검증 · 원본320309d | 초기6개 assertion 실패→같은10개 통과, 추가 손상 수치 경계를 보완해 core298·앱150(신규11 포함) 실패/오류/skip0. APK/시험 APK·Lint오류0/경고20·저장소/diff 통과. 중간 import 컴파일 오류와 시험 null 표현 오류는 교정했으며 assertion 실패로 합산하지 않음 |
| 독립 코드 검토 · 원본320309d | 신규읽기11+기존mapper4+기존query5+독립3=23개 실패/오류/skip0, 시험소스 컴파일·저장소/diff 통과. 코드 APPROVE, 필수 수정0. 위 작성자 수와 중복되어 합산하지 않음 |
| 독립 구조/장치 검증 C · 원본320309d | 구조 CLEAR. Pleos/API34/ARM64·user10의 격리 GarageStoredHostStorageTest3개, 0.904초·실패/오류0. 실제 SQLite→read→mapper에서 처분 도감/개체별 값/공간/가격·사고 정산 누락 오류를 확인 |
| 최신 main 통합 뒤 작성자 최소 확인 · 코드 merge0c58abb | main `c00e52e1f7c8419eb7a5102c20710f2a43cf5233` 일반 merge. 원본 소유6파일 blob 동일·앱 표적15개/소비/시험소스 컴파일 성공(2초). 전체 suite·기기 시험을 다시 실행하지 않음 |

장치 앱 SHA-256 `18470d6503275fe33a6b35fea9c3a100882f2022f341c045efe2360026bed44a`, 시험 APK `c2cd5ca0e1142f33b2c6b9034bac249c8796cd69c4822f71506f9125bc18cdbe`가 실제 설치본과 일치한다는 검증 C의 원문/JSON을 회수했다. 검증 C는 자기2패키지를 제거하고 제품 데이터6파일 SHA·제품 APK·권한 보존을 확인했다. 당시 공유 슬롯의 B 가챠 대기는 별도이므로 A 패키지 정리를 전체 슬롯 반환으로 기록하지 않는다. 이 작성자 게시 준비는 추가 adb/설치·설정 변경을 하지 않았다.

원본320309d의 장치3개 결과를 최신 main merge/head의 새 기기 실행으로 소급하지 않는다. 전체 조회는 순차 읽기이며 전후 개체 비교가 활동/공간/잔액·이력의 원자성을 보장하지 않는다. 실제 제품 View·D/P·관리 명령·발견 표시 이벤트 쓰기·제품 재실행/프로세스·실차는 미검증/후속이다. #52 전체 종료·ready/Blocked by 변경 근거로 확대하지 않는다. AI 코드/구조 검토와 실제 사람 승인을 구분하며 최종 원격 게시/CI/head는 연결 PR에서 확인한다.

근거는 `/private/tmp/drive-next-implementation-20261109/garage-read/`의 원본 로그/XML, code-review/arch-review/summary.json, device/instrumentation.txt·installed.json·baseline/final.json, merge-source-identity.json·merge-minimal-checks.log에 보존했다. 이 절은 앞선 #147 통합·NFC 경로 진단/05 환경 근거를 변경하거나 대체하지 않는다.


### #52 가챠 호스트·정확한 비율의 로컬 수정 검증 — 2026-11-09

검토 head `2d95f62`(main `dda226d` 기준)은 호스트 입력 고정 `0c9c879`과 정확한 비율 표시/보장 연결을 제공한다. 검토 A의 같은 독립3개에서 mutable 후보 clear 회귀1개 실패를 재현한 뒤3개 모두 통과했다. 비율 확장 전21개 중5실패를 확인하고 호스트17+비율4+독립3+보장 호스트1=25개를 통과했다.

최종 앱 전체164개 실패/오류/생략0, core는 변경 없어 기존298개 전체 실행 근거가 UP-TO-DATE로 유지됐다. Debug 조립·Lint·기기시험 Kotlin 컴파일·저장소/링크/diff 검사 통과. Lint 오류0·경고20. JDK17·SDK35·offline Gradle, 최종1분41초다. 기존 퍼센트·불변성·복원 회귀를 함께 확인했다. 이 수정은 보장 순환소수 표시를 새 경제 정책 결정으로 넘기지 않는다.

작성자는 adb 금지로 기기시험을 작성·컴파일만 했다. 이후 독립 검증C가 정확2d95f627에서 실제 assets→GachaPull→SQLite 호스트3개와 분리된 GachaView 비율1개를 실행해 총4개 통과(1.022초, 실패/오류0)를 확인했다. View는 Activity에 부착되지 않은 시험이며 실제 화면/터치·D/P 시험이 아니다. 코드 검증A와 구조 검증C의 JVM45개씩은 중복 합산하지 않는다. 이전 alias P2는 같은 원소스/기대값으로 통과 전환을 확인했다. 검증C가 자기2패키지 제거·제품APK/정확6해시·권한/설정 보존·슬롯 반환을 확인했다. 정상 권위·진행·재화 주입은 고유 SIMULATION 시험 DB용이며 제품 기본값이 아니다. MainActivity/탐색 조립·실제 권위 공급·전체 command의 durable 보존/복원·제품 재시작/실차는 미검증이다. #52/#47/#122 전체 완료로 처리하지 않는다.

게시 담당은 최신 main `0448398868eef90ee22f482b78ad86834e515eb3`를 별도 worktree에 일반 merge한 코드 head `af052f598d449ad124b976ab8e03617122100d84`에서 원검토2d95의13파일 바이트 동일성을 확인했다. 이 조합에서 `:app:testDebugUnitTest --tests '*Gacha*' :app:compileDebugAndroidTestKotlin`를 직접 실행해45개 실패/오류/생략0·시험소스 컴파일 성공(33초)을 확인했다. 최초 실행은 샌드박스의 Gradle 로컬 소켓 bind 제한으로 시험 시작 전에 실패했고, 소스 수정 없이 같은 오프라인 명령을 허용된 실행 환경에서 재시도했다. 새 장치 시험은 추가하지 않았다.

#148은 head2928315의 정식354개 통과·구조32개 통과와 최종 코드 APPROVE 후 main0448398에 반영됐다. 코드 독립7개 중6통과·1실패는 prepare에서 출처를 반드시 거절하라는 더 강한 기대였으며, 실제 commit의 INVALID_INPUT/무변경을 확인해 초기 P2를 철회했다. 실패 원문을 보존하고7개 통과로 바꾸지 않았다. #150은 코드13개·구조7개 및CI 성공을 확인했으나 사용자가 검토 결과만 기록하고 머지를 보류해 OPEN이며 실제 수리 commit은 미제공이다.


### 최근 PR 조율·이슈 부분 완료 대조 — 2026-11-09

[PR #153](https://github.com/Yaho03/drive-growth/pull/153)은 검토 head8aaac7a에서 일반 merge38b3b517로 반영됐고 PR CI4개와 해당 main CI가 성공했다. 위45개 JVM·원검토2d95의 장치4개 근거를 재사용하며 새 제품 실행으로 소급하지 않는다. [PR #148](https://github.com/Yaho03/drive-growth/pull/148)의 main0448398 CI도 성공했다.

[PR #150](https://github.com/Yaho03/drive-growth/pull/150)은 과거 사용자 보류·검토 COMMENT 이후 메인 조율에서 전달된 새 승인 판단 위임에 따라 검토 head847d415를 일반 merge2d4658e로 반영했다. 독립 코드13PASS/구조7PASS의 겹치는 시험은 합산하지 않는다. 실제 commitRepair·SQLite·제품 전체 공급은 미제공이다. 별도 사람 PR 승인 미확보와 사용자 위임 근거는 PR에 기록했다. 과거 보류 당시 기록은 현재 머지 상태와 구분한다.

[PR #151](https://github.com/Yaho03/drive-growth/pull/151)은 head64afe55에서 EXISTING의 원래 초기화 결과 재생을 현재 콘텐츠로 다시 준비해 버전/후보 변경 때 복원이 실패하는 P2 수정 요청이다. 코드 정식356PASS와 독립7개 중5PASS/2FAIL(오류0)을 구분한다. 호열의 코드 수정·같은 두 회귀 기대값·원리뷰 회신은 #50에 연결했으며 #121 실제 메모리 commit 요청과 별개다. [PR #143](https://github.com/Yaho03/drive-growth/pull/143)의 소비 원장 누락 탐지 P2도 미해결이며 이를 실제 중복 지급 실행으로 확대하지 않는다.

[PR #152](https://github.com/Yaho03/drive-growth/pull/152)는 head6339af0의 합의된 차종 기준 가격1000·콘텐츠 버전 trial-content-2026-11-09를 main07a5703에 반영했다. 독립 코드 콘텐츠12+경계3=15PASS, 구조 가격/버전·과거 정산26PASS, 최신 #153 조합 가챠25PASS와 AndroidTest 소스 컴파일 근거를 대조했다. 겹치는 시험을 합산하지 않는다. 기존 취득액0/500/8000·원래 정산 재생, 신규 content/옛 authority 충돌·별도 가챠 단가500을 확인한 메모리/순수 검증이며 새 SQLite 전환·새 APK 기기·제품 재시작 검증은 아니다. 과거 trial-content-2026-11-07 기기 기록과8000 fixture는 당시/시험 입력 근거로 유지한다. 실제 권위 버전 전환과 제품 표시·재개방은 #35/#47/#51/#122의 기존 후속이다. 대여료/시설 전체1/8 조정이나 최종 경제 밸런스 확정으로 보지 않는다.

열린 이슈33개·감사 snapshot335체크·41이슈댓글과 관련 PR 본문/리뷰/줄 댓글을 대조한 초안은 독립 검토 후14이슈31치환을 최신 본문 해시와 대조해 반영했다. 이미 제공된 core/SQLite/mapper·View fixture의 부분만 체크하고 제품 프로세스·실차/폼 팩터·미제공 저장 확정 조건을 유지했다. 전체 종료 후보0이므로 이슈를 닫지 않았다. #121 메모리 수리→초기화→선물 요청과 #122 SQLite 후속은 원 댓글·담당·남은 조건에 연결했다. 담당/라벨·차단 관계를 일괄 변경하지 않았다. 이는 기존 근거 감사이며 새 제품 시험 실행 결과가 아니다.

최근 검증 감사는 2026-11-06~09의 조회 가능한 PR46개에 대해 과거 실행·수정/철회·소스 변경 후 후속 근거를 대조했다. 기존 main38b3 snapshot의 #150 보류·#152 독립 근거 미회수 표기는 이후 각각 새 판단 위임 일반 merge와 코드/구조 근거 회수·직접 승인 merge로 갱신됐다. #150 main2d4658e CI와 #152 main07a5703 CI도 성공했다. 실제 필수 결함은 #143/#151 두 건의 원래 회귀에 연결하고, #149 실제 View·#153 MainActivity/권위/durable 연결이 추가될 때 필요한 최소 표시/Back·gate·재생성/재시작 시험은 #28/#51/#52의 기존 후속에 유지한다. 원래 범위 밖 기능을 부분 PR의 새 반려 조건으로 만들거나 과거 시험을 전부 다시 실행하지 않았다. 외부 답변/공식 정책·폼 팩터/실차는 #80/#81/#82의 미완료 범위다.



## #121 수리 메모리 원장 참조 — 2026-11-09

[호열의 후속 요청](https://github.com/Yaho03/drive-growth/issues/121#issuecomment-6733710261)의 첫 묶음이다. main `04483988` 기반 로컬 코드 `acabbd75c5919c8ec0b637eacb6d8130d57f47e5`에서 기존 InMemoryGameChangeStore에 수리 read/commit/정산 조회를 추가했다. source/버전·활동·확정 사고·견적은 명시 시험 입력이며 실제 RentalLedger/VehicleCare live 공급이 아니다. 실제 제공·저장 책임은 [02](02_SYSTEM_ARCHITECTURE.md#121-재료-강화-메모리-참조와-추가-계약)를 따른다.

TDD: capability assertion1실패 → 수리 동작14개 중13실패 → 같은14개 통과를 확인했다. 수리·기존 수리 계약·재료·가챠 표적66개와 초기 코어 전체368개는 실패/오류/생략0이다. JDK17·Kotlin2.2.21·Gradle8.11.1의 별도 ASCII 작업트리에서 `:core:test :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --offline --no-daemon --max-workers=2`가27초에 성공했다. 저장소 검사21/28/357·diff 검사도 통과했다. 앱 소비 컴파일은 APK 조립/기기 실행이 아니다. 기존 GachaLedger Kotlin 경고는 남았다.

같은 명령을 성장·판매·잔액 변화 뒤 재제출해 최초 전체 결과를 재생하고, 다른 전체 입력 및 legacy/gacha/material ID 충돌 양방향·원자 실패/재시도·수리 두 요청/판매/재료 경쟁·NEEDS_REPAIR→IDLE 공유·정보 보존·비용0/Long/revision 경계를 확인했다. PR150 exact847d415의 미머지 VehicleRepair 소스는 외부 시험 경로에만 합성해 실제 prepare→commit/replay·저장 실패 무변경2개를12초에 통과했다. 해당 제품 코드를 이 변경에 복제/병합하지 않았다.

근거 `/private/tmp/drive121-repair-memory-20261109/`에 원문 요청·TDD/표적/전체/소비 로그·XML·summary/독립 검토 결과를 모았다. 독립 코드/구조 검토 최종 판정은 인계 보고서 및 연결 PR에서 확인한다. 이번에는 원격 게시/머지·장치/adb·환경 변경을 수행하지 않았다. 초기화/선물 commit·NEW/legacy 복구·실제 SQLite/live 활동·UI/제품 프로세스와 #121/#122/#49 전체 완료는 남는다.

독립 검토에서 판매선확정시 기존 계약의 DATA_UNAVAILABLE를 경쟁 시험이 STATE_CONFLICT로만 기대한 문제를 확인했다. 판매선확정으로 이전 assertion1실패를 재현하고 동시결과의 최종 lifecycle별 오류 및 양선확정 순서고정 회귀1개로 보완했다. fixture 구현은 바이트 동일하며 최종 코어369개·앱 소비 컴파일(동일 소스 UP-TO-DATE 포함)은16초에 통과했다. 최초368과 최종369를 합산하지 않는다. 최종 코드 head는 `acabbd75c5919c8ec0b637eacb6d8130d57f47e5`다.

최종 exacthead의 독립 코드 검토는 별도 ASCII 사본 표적67개를 새로 실행해 모두 통과하고 APPROVE, 구조 검토는 최종 시험 delta·계약·기존 XML/로그의 read-only 확인으로 CLEAR였다. 필수 수정0이며 사람 승인과 구분한다. 표적/전체/소비 및 검토자 수치를 합산하지 않는다.

게시 조율: 위 자료는 원작성/독립 검토 당시 실행 기록이다. #150은 이후 main2d4658e로 반영돼 실제 현재 제품 소스를 소비할 수 있다. 최신 main과의 최종 조합 검토·CI 및 원격 공급 상태는 연결 PR을 따른다. 앞 절의 수리 commit 미제공은 당시 상태이며 이번 수리 메모리 참조 공급 범위로만 갱신한다. SQLite/live 공급·초기화/선물 commit과 전체 기능은 여전히 미제공이다.

게시 담당이 최신 maine1dc5c8(#154 문서 동기화 포함)을 일반 merge한 조합에서 수리 계약 소비/원장 표적18개(RepairStore15·VehicleRepair3)를 직접 실행해 실패/오류/생략0을 확인했다. 앱 단위시험 소비 컴파일·Debug APK·Lint도42초에 성공했으며 Lint 오류0·경고20이다. 기존 독립67개 전체를 재실행하지 않았다. 원본 소유3파일 바이트 동일성을 유지했고 RepairContract 변경은 미제공 설명을 실제 참조 제공으로 바로잡는 KDoc뿐이다. 새 기기·SQLite/live·제품 프로세스 시험은 수행하지 않았다.


### #157 수리 wrapper의 실제 메모리 소비 — 2026-11-09

[PR #157](https://github.com/Yaho03/drive-growth/pull/157)은 head0409454의 시험2개와02 정정을 main3a0181f에 반영했다. 독립 검토자가 수리5(새2 포함)·RepairStore15·RepairContract13 총33개를36초에 실행해 실패/오류/생략0과 APPROVE·필수수정0을 확인했다. 작성자의 전체374개와 합산하지 않는다. 최초 SDK 위치 미지정은 Gradle 구성 실패였고 기존 SDK 환경변수 지정 후 위 실행에 성공했다. 정확 head의 repository/android CI4개도 성공했다.

실제 InMemoryGameChangeStore에서 prepare→수리비 차감/IDLE/revision·최초 결과 재생·실패 무변경을 확인한 소비다. 440은8000 기준 시험 콘텐츠의 희귀 캐스퍼 견적이며 현재 실제assets1000의 새 가격 결정이 아니다. SQLite/live 공급·DB 재개방·제품 UI/프로세스·실차나 #49/#121/#122 전체 완료는 아니다. 별도 사람 PR 승인은 미확보이며 사용자 위임에 따른 일반 merge 예외와 근거는 PR에 기록했다. 관련 이슈에는 부분만 체크하고 담당·라벨·전체 미완료를 유지했다.


### #151 원래 결과 재생·선물 호출 경계 수정 검증

초기 head64afe55의 EXISTING 재생 P2는01d9e050의 원래 command 재제출로 해결됐다. 원래 콘텐츠/규칙 변경·차종 제거의 같은 두 반례가 기대값 변경 없이 통과했고, 독립 구조 검토는 정식35·외부7=42개를 실행해 확인했다(외부7 중5는 기존 정식 복사이므로 별도 독립 사례로 합산하지 않는다).

추가 검토에서 prepareOpeningGift의 granted 분기가 호출자 source/profile을 무시하는 경계를 확인했다. 로컬4dd3be6은 반환 명령을 선택하기 전에 두 입력을 검증하며 원래 반환 명령은 보존한다. 작성 검토 lane은 기존21개 중1실패와 새 고정 회귀2개 실패를 먼저 확인한 뒤 표적23개·코어381개 통과/앱 소비 컴파일을 기록했다. 독립 delta 검토는 정확4dd3be6의 정식profile9·외부8=17개를27초에 실행해 실패/오류/생략0·CLEAR를 확인했다. 각 실행의 수치를 합산하지 않고 최초 실패를 보존한다.

게시 담당은 소유2파일의 바이트 동일성을 유지해 최신 main2003752와02/06의 기존 기록·현재 위임 기준을 정상 병합했다. 이 검증은 wrapper와 probe/명시 SIMULATION 입력의 동작이며 실제 메모리 초기화/선물 commit·SQLite 롤백·NEW issuer/legacy 복구·UI/프로세스/실차 성공이 아니다. 최종 정확 head/CI·기존 리뷰의 해결 회신과 원격 반영은 [PR #151](https://github.com/Yaho03/drive-growth/pull/151)을 따른다.



## #121 초기화 메모리 참조 본인 검증 — 2026-11-09

[수리→초기화→선물 요청](https://github.com/Yaho03/drive-growth/issues/121#issuecomment-6733710261)의 두번째 묶음이다. 요청 main e1dc5c8에서 미게시 수리 acabbd75를 정상 병합해 시작했고, 이후 수리 PR155의 main0f8da479 공급을 확인해 다시 정상 병합·보존했다. 초기화 코드 head는 `fa69c6a8e19938d6850b6003b56f79fd319a046d`이며 같은 InMemoryGameChangeStore의 지갑/차량/모니터/요청 공간을 사용한다. 실제 NEW issuer·legacy 복구·SQLite·첫 영업 선물 commit은 구현하지 않았다.

TDD는 capability1 assertion실패, 최소 stub12개 중11실패를 확인한 뒤 기존 FirstGrant 어댑터로 실제 메모리 확정·재생을 검증했다. 시험 필드명을 기존 Settlement.changedVehicle로 교정한 컴파일 오류2회는 제품 실패와 구분해 보존했다. 초기 관련 표적88개19초 성공, 공간 실패 회귀를 포함한 최종 core385개(초기화13개) 실패/오류/생략0 및 앱 main/단위시험 Kotlin 소비 컴파일28초 성공이다. JDK17·Kotlin2.2.21·Gradle8.11.1의 별도 ASCII 작업트리에서 --offline --no-daemon --max-workers=2를 사용했다. 저장소21/28/360·diff 검사도 통과했다. 기존 GachaLedger Kotlin 경고는 남았으며 APK/기기 실행은 하지 않았다.

초기1000·일반 첫차·공간3·프로필 마커/전체결과, UNKNOWN/legacy 무지급·마커/원결과 손상 오류, 실제 공용 잔액/소유개체 최종검사, 다른 선택/프로필/source/버전/후보 거절, legacy/gacha/material/repair ID 양방향 충돌, 동시선택 한건, 실패시 공간/진행 포함 전체 무변경을 확인했다. 성장/판매/잔액 변경과 현재 콘텐츠/규칙/후보 변경 뒤 보관한 원래 전체 명령은 최초 결과를 재생했다. #151 P2의 미수정 소비 래퍼를 고치거나 오류를 성공으로 숨기지 않았으며 해당 소비자 수정은 원리뷰에서 별도 검증한다.

근거 /private/tmp/drive121-initialization-memory-20261109/의 로그/XML/REPORT·summary와 문서 patch를 인계한다. 이는 구현자의 본인 검증이며 독립 검토/원격 게시/main 공급 완료 판정이 아니다. 수리 원본 worktree는 acabbd75 clean으로 고정했다. root/coordinator가 독립 검토·게시를 이어가며 선물 세번째 묶음은 시작하지 않았다. #121/#50/#122 전체와 제품/프로세스·실차 검증은 남는다.



## #121 초기화 기존 ID 충돌 P2 최소 수정 본인검증 — 2026-11-09

독립 검토 head `fa69c6a8e19938d6850b6003b56f79fd319a046d`에서 기존 SOLD ID를 초기화가 덮어쓰는 두 assertion 실패를 확인한 뒤 새 격리 브랜치 core/121-initialization-id-fix에서 최소 수정했다. 최종 코드 head `b0c1288f46f87bba551d62f48397edda9e7e3d3c`이며 원래 명령 Replay 뒤 Ready의 ID가 기존 vehiclesById에 있으면 STATE_CONFLICT로 반환한다. 기존 지갑/차량/활동/가챠 공간/마커/결과를 바꾸기 전에 처리한다.

같은 독립9개를 변경 없이 복사하고 정식13개에 회귀2개를 추가해 수정 전24개 중4 assertion 실패, 수정 후동일24개 모두 통과했다. 추가 회귀는 SOLD/SCRAPPED/CONSUMED의 실제 원래 개체·활동/잔액/가챠 공간·진행·NEW 마커/결과 무변경과 공개 Add→처분 뒤 ID 재사용 거절이다. 원래 두 독립 반례도 기대값을 유지했다. 최종 정식 core387개(초기화15개) 실패/오류/생략0과 앱 main/단위시험 소비 Kotlin 컴파일이31초에 성공했다. 독립9개는 정식 전체에서 분리해 보존하며 표적/전체 수치를 합산하지 않는다. 저장소21/28/360·diff 검사 통과, 기존 GachaLedger Kotlin 경고는 남는다.

근거 `/private/tmp/drive121-initialization-memory-20261109/fix/`의 red/green/full 로그·XML·REPORT/summary/patch에 보존했다. 작성자 fa69c6a worktree와 기존 독립검토 snapshot은 변경하지 않았다. 이는 수정본 본인검증이며 새 독립 재검증·원격 게시/CI/main 공급은 별도다. NEW issuer/legacy 복구·수리/선물·SQLite/PR151·제품 UI/SDK/ADB·프로세스/실차는 구현하거나 시험하지 않았다. 기존 초기화 공급의 docs.patch는 원래 인계 위치에 유지하며 이 보완 patch와 함께 최신 문맥에 맞춰 조율자가 반영한다.

초기화 게시 조율은 수정된 전체 공급 b0c1288을 기준으로 독립 정식15+원래 회귀9=24개 PASS·원본 기대값/소스 동일성을 회수했다. 최초 fa69의 P2와 red/green은 위 당시 기록으로 보존한다. 선물78cd12d와 통합88bb671은 이번 초기화 공급에 포함하지 않으며 이후 선물 자체 delta·최종 통합 검증으로 별도 처리한다. 최신 mainf1e0b45의 #151 wrapper/현재 위임 문서를 보존했다. NEW 근거는 명시 시험 입력이며 제품 issuer/legacy 복구·SQLite·제품 UI/프로세스/실차 완료가 아니다.

초기화 게시 담당의 최신 mainf1e0b45 조합에서는 InitializationStore15·FirstGrantProfile9=24개와 앱 단위시험 소비 컴파일이 실패/오류/생략0으로 성공했다. 이는 독립 정식15+외부9의24개와 다른 조합이며 합산하지 않는다. 전체 초기화 소유3파일은 b0c1288과 바이트 동일하며 InitializationContract 추가는 제공 상태 KDoc 정정뿐이다. 새 장치·SQLite·제품 프로세스 검증을 실행하지 않았다.



### #122 재료 강화 SQLite 로컬 구현·본인 검증 — 2026-11-09

main38b3b51의 #147 계약/메모리 참조·#148 VehicleUpgrade.prepareMaterial 소비를 기준으로 별도 app/122-material-sqlite 브랜치에서 구현했다. SqliteGameChangeStore의 MaterialChangeStore capability와 명시 버전 공급, 같은 DB 거래의 RentalLedger live 활동 조회, 두 개체/비용/공용 정산/최초 전체 결과를 연결했다. 스키마6 준비는 material_requests만 추가하며 지원 v1~v5 이전에서 기존 payload를 고치거나 프로필/무료권을 생성하지 않는다. 현재 구현/검증은 로컬이고 원격 PR·CI·main 공급 완료가 아니다.

| 직접 실행 | 실제 결과 |
|---|---|
| core 단위 + 앱 단위 | core354·앱175개 실패/오류/생략0. 최종 확인에서는 변경 없는 시험을 Gradle UP-TO-DATE로 유지 |
| APK/시험APK 조립·lint | ./gradlew --offline에 외부 build 디렉터리용 init script를 사용. 최종 BUILD SUCCESSFUL(8초). 기존 경고는 유지하며 SDK/실차 성공으로 쓰지 않음 |
| API37/ARM64 SQLite 전체 회귀 | 재료22 + 기존124(탁송 선택28·도착/주행22·변경19·가챠11·실제 콘텐츠 가챠4·대여14·공용 원장26) =146개 실패/오류0, 5.822초 |
| 최종 재료 표적 | 원시 잔액·Long.MAX_VALUE 비용을 추가한24개 실패/오류0, 1.823초. 앞의22개와 중복 합산하지 않으며 기존124개와 합쳐148개 고유 시험을 확인 |

정상 assets 계획의 무과금은 money=null, 대상 레벨2/진행50 + 고급 재료150은 레벨4/진행0, 재료의 성장/취득/희귀도는 보존하고 CONSUMED로 바꾼다. 양수 비용/Long.MAX_VALUE는 계약 경계 시험이며 새 제품 강화 요금이 아니다. 성장·처분/버전 공급 중단 뒤 최초 결과 재생, 같은 ID 다른 전체 입력 충돌, 일반 원장/가챠/대여 방문/무금액 탁송 선택 ID 양방향 방어, 두 DB 연결의 같은 명령/같은 재료/대여 배정 경쟁, 둘째 차량 쓰기 실패·COMMIT 직전 실패의 전체 롤백과 재시도를 확인했다. 대여 배정 후 revision을 현재값으로 맞춰도 양쪽 역할의 RENTED/RETURNING/NEEDS_REPAIR만으로 거절되는지 별도 확인했다.

원명령·원상태·역할별 결과·공용 정산 누락/오연결·소수/문자열/지수/범위 초과 금액·잔액 손상은 STORAGE_FAILURE와 DB 파일 바이트/잔액/개체 보존으로 확인했다. v1/v5 fixture와 기존 v2/v3/v4 회귀의 이전·재개방, v4의 중간 생성 뒤 실패 롤백·v5 이전 실패의 원본 보존을 확인했다. 이들은 옛 writer의 표 구조를 재구성한 시험이며 옛 APK 최초 쓰기·제품 DB 이전 성공 근거가 아니다.

초기 시험 차량이 INITIAL_GRANT의 희귀도/취득액 제약 및 새 Add의 revision0 계약과 맞지 않아 각22개가 준비 단계에서 실패한 이력을 보존했다. fixture를 고친 뒤21/22가 통과했고, 남은1개는 대여 배정으로 올라간 revision을 제거된 대여 행과 함께 되돌렸다고 가정한 시험의 잘못이었다. 현재 revision을 반영하고 live 활동 검사를 강화한 뒤146개/최종 재료24개가 통과했다. 중간 시험 import 누락 컴파일 오류도 수정했으며 제품 회귀 red→green 근거로 세지 않는다.

기존 제품 기기가 아닌 Medium_Phone_API_37.0의 읽기 전용·무스냅샷 전용 emulator-5580을 사용했다. JDK17/SDK35이며 Pleos API34·실차 결과로 표시하지 않는다. 실제 제품 권위 공급/UI 명령 영속 복원·프로세스/전원 시험은 미수행이고 수리/초기화/선물 commit·#122 전체 종료는 후속이다. 원문 로그는 /private/tmp/122-material-final-build.log, /private/tmp/122-material-device-full.log, /private/tmp/122-material-device-final-target.log에 보존했다.

### #122 재료 SQLite 게시 전 최신 main 통합 — 2026-11-09

사용자의 커밋·푸시·Draft PR 게시 지시에 따라 main e1dc5c8을 통합했다. 충돌은 06의 추가 기록뿐이며 main 원문과 위 재료 기록을 모두 보존했다. 기존 재료 제품/정식시험·공용 저장 소스는 로컬 구현0c5dc2f와 동일하다. main의 #150 수리 명령·#152 가격1000/콘텐츠 버전 변경은 공급 그대로 유지했으며 실제 수리 commit은 이 재료 PR 범위가 아니다.

통합 후 core357·앱175개 실패/오류/생략0, APK/시험APK·lint·저장소/diff 검사 통과(BUILD SUCCESSFUL10초). 새 콘텐츠에서 MaterialLedgerTest24 + GachaPullSqliteTest4 = API37 SQLite28개 실패/오류0, 34.994초를 직접 실행했다. 원문은 /private/tmp/122-material-main-build.log와 /private/tmp/122-material-publish-device.log에 보존했다. 기존124개 전체를 이 main 조합에서 다시 실행한 것으로 표시하지 않으며 앞선146개/최종24개 근거와 구분한다. 원격 head/CI는 PR 게시 후 확인하고 Draft·미머지로 공유한다.


### #156 재료 SQLite P2 수정·독립 장치 검증

원head dc9687d의 독립 정식39개 성공과 별도로, 자기 material 이력 누락 회귀는 API34에서 기대 STORAGE_FAILURE 대신 STATE_CONFLICT로1개 실패했고 정상 타기능 ID 비교1개는 통과했다(2개/1PASS·1FAIL·오류0·0.362초). 오류 assertion 전 손상 DB 바이트 불변은 통과했으며 잔액/개체 별도 값 비교나 새로운 중복 지급/제품 DB 손실 재현은 아니다. 이 당시 실패를 보존한다.

수정068ce4a1은 전용 이력 조회 실패 뒤 남은 공용 정산의 material-v1 종류를 확인해 누락 자기 이력을 STORAGE_FAILURE로 구분한다. 독립 검토는 소스198개·원래 회귀 SHA/기대값 무변경을 확인하고 코드 APPROVE·필수0을 회수했다. 같은 고정 회귀와 정식Material25를 Pleos API34/ARM64/사용자10 격리환경에서 단1회 실행해26개 전부 통과(실패/오류/생략0·runner529.861초)를 확인했다. 정상타기능 ID·두 snapshot 재개방/재생·원자 실패/지원 구조 이전도 포함한다. 기존39·RED2·작성자 API37/148과 합산하지 않는다.

설치 후 pull APK SHA가 빌드본과 두패키지 모두 일치했다. 자기 material156green2패키지를 제거하고 제품APK793e1db8·정확6파일·권한·설정·기존 전면 상태/관측 가능한 주차0 보존과 슬롯 반환을 확인했다. 기어 직접 조회는 user-build 제한으로 불가해 측정 성공으로 기록하지 않았다. AVD/서버/5580 변경은 없다.

게시 담당은 최신 main과 양쪽06 기록을 정상 통합하고 수정2파일·앱 전체 tree가 검토068ce4a1과 동일함을 확인했다. 이전 장치26개를 새 게시 head 실행으로 쓰지 않으며 새 정확 head CI와 원리뷰 해결·main 공급은 [PR #156](https://github.com/Yaho03/drive-growth/pull/156)을 따른다. schema6은 material_requests 추가와 지원 v1~v5 정합 구조 fixture 이전 범위이고 옛 APK 최초 쓰기/제품DB 이전·실제 강화 UI·durable 명령/프로세스/실차와 다른 SQLite 명령은 별도다.


### #143 원래 이력 누락 해결·head 무삽입 P2 수정

작성자6bb01c7의 prev/head/count/tail 저장으로 원래 tail 소비·0ms 중간 소비 삭제 독립2개가 기대값 변경 없이 통과했다. 기존 로컬1e980d7은 별도 보존하고 중복 게시하지 않았다. 이후 head insert 반환을 무시하는 경계에서 독립18개 중17PASS/1FAIL·오류0·2.673초를 확인했다. IGNORE 시 Success remaining900000·새로그/allocator 확정·stale head라는 부분 반영이며 ABORT 비교는 rollback/재시도까지 통과했다. raw 행/head/allocator 관측을 전체 DB bytes 검사나 실제 금전/중복보상·제품DB 손실 재현으로 쓰지 않는다.

위임 수정d12965e7은 반환행1을 확인해 무삽입을 기존 저장 실패 경계로 전달한다. 독립 코드 검토와 실제 정식15+원래삭제2+head ABORT/IGNORE2=19개가 API34/ARM64/사용자10 격리환경에서 모두 통과(실패/오류0·2.588초)했다. 원본 source464개와 독립 기대값/SHA를 보존했고 과거18개/실패 및 다른head 실행을 합산하지 않는다. 원래 삭제·정상gap·재생/rollback·IGNORE 같은 기대값이 포함됐다.

설치 후 pull 앱/시험APK SHA가 빌드본과 일치했고 자기 rest143c2패키지 제거·활성instrumentation 없음·제품APK/정확6파일/권한·창/설정 등18개 보존 확인을 마쳤다. C의 전체lease는 이어지는 Shop 순차검증에 유지돼 전체슬롯반환으로 표시하지 않는다. 게시 담당은 수정2파일 바이트 동일성을 유지해 최신main과 기록을 정상 병합했다. 새 게시head의 새장치 실행이나 실제 위치/운전 근거·제품수익·UI/프로세스/전원·실차 성공은 아니다. 최종head/CI·원리뷰 해결·main 공급은 [PR #143](https://github.com/Yaho03/drive-growth/pull/143)을 따른다.


### #121 첫 영업 선물 메모리 참조·초기화 통합 독립 검토 — 2026-11-10

선물78cd12d의 기존 독립 코드65PASS·구조60PASS를 별도 과거 근거로 회수했다. 초기화 기존 ID 덮어쓰기 guard b0c1288을 정상 통합한88bb671의 소스466개·선물2파일/초기화2파일/공유fixture 예상 합성본 SHA 동일성을 직접 대조한 독립 검토는 APPROVE·필수0이다. 정식 선물14+가챠21+프로필22+초기화15+원문 초기화 회귀9+새 공유 경계3=84개 PASS(실패/오류/생략0,30분13초). 작성자 전체403개·기존65/60/24와 합산하지 않는다. 새 공유 경계는 초기화→선물→재생, 처분ID 초기화 거절 뒤 선물 보존, 초기화/선물 동시 확정20회(시험1개)다.

이미 초기화는 #159로 main 공급됐으므로 이번 게시에는 선물 자체3파일 delta와 KDoc/문서만 남긴다. 최신 main92dd2a34와 정상 통합해 수정 소스가 독립88bb와 같은지 확인하며 새 정확 게시 head CI는 연결 PR에 기록한다. 당시03/06 과거 기록과 #151 wrapper main 공급을 유지했다. 이는 명시 SIMULATION 완료 근거의 같은 메모리 원장 참조이며, 실제 RentalLedger/live·SQLite/재개방·NEW issuer/legacy 복구·제품 UI/프로세스·SDK/실차 및 #121/#50/#122 전체 완료가 아니다. 원문 근거는 /private/tmp/drive121-init-gift-integration-20261109/independent-review/에 보존했다.


### #55 가게 전경 독립 코드·실제 Native 검증 — 2026-11-10

원head3dc3c8a의 소유8파일에 중립 Native Canvas 전경·전체/차량 중심·공급 공간/설치 시설·840dp 전경우선 배치·scene/controls 스크롤 UI 상태를 추가했다. 기존 v3 4a/4u/4o/4z를 비교했으며 PNG 전체/실차 외형·로고를 제품 자산으로 넣지 않았다. 독립 코드 CLEAR·필수0, JVM26PASS(정식Shop21+외부4+필터에 포함된Onboarding1,18초), 작성자 APK/Lint 오류0·기존경고21/scene경고0·시험APK 조립을 구분한다.

별도 검증 C가 같은466추적파일/정식9원문·APK pull SHA를 확인하고 Pleos API34/ARM64/user10의 격리 호스트에서 정식9PASS(2.295초), 실제 부착 큰/좁은창 matrix2PASS(10.848초), 시작1레벨3칸→첫확장2레벨5칸 비교2PASS(1.616초)를 실행했다. raw per-test status가 원문이며 XML은 그 원문으로 구성해 재집계했다. 큰앱702×438px=936×584dp, 좁은floating decor480dp/자식앱336×414px=448×552dp다. 채택40프레임(appcrop40/full40 PNG80·metadata40)으로 전경/관리·긴문구·간판원문·공간/시설·모드/스크롤/Pending 복원·미리보기 잔액/외형 불변·제한 내용 제거를 대조해 scoped Native PASS·차단결함 재현0을 확인했다. 최초 외부host의window-local crop 오류는 별도 보존·판정 제외 후 화면좌표로 보완해 재캡처했고 제품 원문은 바꾸지 않았다.

자기shopscenec 두패키지 제거·활성instrumentation0·제품APK/정확6데이터/권한·창/설정 등18보존checks 전부true·C최종lease반환을 회수했다. 이후 장치는 메인이 별도 배정하며 게시 담당은 adb를 실행하지 않았다. 원본 근거는 /private/tmp/drive-shop-final-design-20261109/independent-code-review/ 및 independent-device-review/에 보존한다. 최신 게시 조합의8파일이3dc와 같은지 확인하며 정확 head CI는 연결 PR에서 확인한다. 실제 제품HOME/MainActivity/store·실제 거래/프로세스·물리 터치 좌표·font/night 대안·다른 디스플레이/전원·실차와 #52/#55 전체 완료는 남는다. 고정 입력1/3·2/5/시설은 실제 구매 결과가 아니다.


### #122 수리 SQLite 독립 코드·실제 장치 검증 — 2026-11-10

구현272b62d와 호환시험 별도3c473dd의 기존SqliteGameChangeStore/RepairLedger 소유4파일·migration fixture4파일을 공급한다. 독립 코드 APPROVE·필수0, 실제 순수/메모리/capability34PASS(48초)를 확인했다. 작성자core374/app176·APK/Lint0오류20경고는 별도 본인근거다. 작성자의 AArch64 C1 Lint JVM crash 이력과 실행옵션 TieredStopAtLevel1 재시도 성공을 보존하며 프로젝트 설정 변경/제품 assertion 실패로 분류하지 않는다.

독립 검증 A는 최종3c의466추적파일 무변경·정식 기대값 무변경을 확인하고 정확3c의 app/test APK를17초에 재조립했다. 앱62af636e/시험762ce611 SHA는 설치후pull본과 일치했다. API34/ARM64/user10 격리 환경에서 RepairLedgerTest23개를 단1회 실행해23PASS·실패/오류/생략0(7.614초), 영향받은 Gacha v3·Rental v1/v2·Delivery v4/실패·Material v5/실패v4 migration 표적7개를 각각1회 실행해7PASS를 확인했다. 새 실제장치총30개이며 기존34/374/176과 합산하지 않는다. raw instrumentation이 원문이고 XML은 그 원문으로 구성했다.

실제assets 게임차종가1000과 명시 SIMULATION 영업 fixture의 prepare→공용SQLite수리→재개방/최초결과재생에서 비용55/잔액945·IDLE/revision2·사고/영업쉬기·성장/희귀도/취득정보를 확인했다. 비용0/Long.MAX 잔액·권위/상태/revision/견적 경계·한쪽 이력 누락/원시숫자 손상·commit/활동쓰기 실패 전체rollback·같은ID/다른종류ID·두연결수리/판매경쟁을 확인했다. material-first 일부는 raw예약행 fixture이며 실제 재료→수리 전체제품흐름으로 확대하지 않는다. v1..v6 정합구조6가지/15개손상변형은 내부반복으로 별도시험수에 더하지 않으며 모든경쟁스케줄/옛APK제품DB이전을 입증하지 않는다.

자기repairsqlitea app/test제거·활성instrumentation없음·제품APK793e1db8/6데이터/권한·창/설정 등18보존checks 전부PASS·슬롯반환/noFurtherAdb를 회수했다. 다른5580/AVD/서버reset/차량신호쓰기는 없으며 새기어P검증으로 기록하지 않는다. 원본근거는 /private/tmp/drive122-repair-sqlite-20261109/independent-review/ 및 independent-device-review/에 보존한다. 실제 제품repairContent/UI·프로세스/전원/실차·초기화/선물SQLite 및 #49/#122 전체완료는 별도다. schema6재료 다음schema7수리 순서를 유지하고 최신main 정상통합/정확head CI는 연결PR에 기록한다.


### #162 머지 후 수리 계좌 무갱신 P2·후속 수정 — 2026-11-10

기존3c의 수리23+호환7=30PASS와 일반merge67379487을 당시 근거로 보존한다. 추가 대기 요청/실제 재현은 머지 뒤 도착했다. API34/ARM64/user10의 외부1개가1실행/1assertion실패·오류0으로, account BEFORE UPDATE RAISE(IGNORE)에서 Success·DB잔액1000 vs반환945·revision2/IDLE·공용/수리기록삽입을 관측했다. PR162 추가검토5836490714와49/121/122에필수수정을연결하고122원자성체크를재개했다. 제품DB손실/실차피해재현이아니다.

후속51e0fb7은 account db.update 영향행1검사만추가하고 아니면SQLiteException으로 기존atomic STORAGE_FAILURE/rollback을재사용한다. 제품2줄추가/1줄삭제이며공용helper/다른Ledger·정책을바꾸지않았다. 원A독립소스SHA79a0aed3...가고정원문과바이트동일·기대완화0,정식23도원래3c와동일하다. 독립 archive468파일은정확51e와같고새빌드/설치pullSHA도일치했다(앱45e326ec/시험aaf4bab3).

같은독립1개0.323초PASS: Failure(STORAGE_FAILURE), 실제잔액1000/수리receipt없음/NEEDS_REPAIR, account·vehicles·rentals·settlements·repair_requests5표전후전체행동일을 확인했다. 트리거주입후실행의5표/상태보존이지준비과정포함전체파일bytes불변주장이아니다. 정식수리23개도6.984초23PASS·오류/실패/생략0으로재실행해새실제24개다. 기존30/호환7·작성자core374/app176과합산하지않는다. 작성자APK/시험APK/Lint와독립새빌드1분16초도구분한다.

자기repairfixa2패키지제거·활성instrumentation/자기process없음·제품APK/6데이터/권한/차량·창/설정등19보존checksPASS·해당구간종료를회수했다. A의다음checkpoint는메인배정의별도구간이며조율자adb0이다. 근거 /private/tmp/drive122-repair-account-fix-evidence/independent-review/의REPORT/summary/원문·관측·sourceidentity/설치hash·보존자료. 수정2파일의51e동일성·최신main정상통합/새정확head CI후후속공급하며최신게시head의새장치재실행으로표시하지않는다. 실제수리UI/프로세스·전원/실차/전체49·122완료와초기화/AppHost의별도P2는남는다.


### RentalRuntime 독립 코드·실제 SQLite 공급 근거 — 2026-11-10

원head eb096165(baseb11f225)의 소유8파일은 고정 전체 방문→기존RentalLedger assign, 검증 구간→account/settle/finishReturn, 기존 parked_since의 엄격한 정수 조회와 worker/main·stop/늦은응답 처리를 연결한다. 새 고객/요금/지갑·원장·helper/schema·Activity는 만들지 않았다. 별도 C 코드APPROVE/필수0·app23(작성17+외부6)/core13PASS(19초), 작성자core398/app192·APK/Lint0오류20경고는 구분하고 합산하지 않는다.

A는 exact473추적파일/정식9원문SHA 무변경을 확인하고 외부경계1개만 추가한 격리APK를34초에 새조립했다. 잘못된 최초cwd/init 경로는 Gradle구성전 실패로 별도보존한 뒤 경로만 바로잡았다. 새앱48b9ded7/시험6fce33b2의 설치후pullSHA가 빌드와 일치했다. API34/ARM64/user10의 정식9PASS(1.159초)+외부1PASS(0.233초)=새실제10개·실패/오류/생략0을 확인했다. assign/account/settle/return·DB재개방/원래방문재생·연속주차기준·관측공백무계상·정산실패재시도·원시소수거절·두연결경쟁·반환후사고/최초완료원인·미정반올림실패를 검증했다. 외부1은 권한누락 motion 무계상→유효1시간→RETURNING→반납/반복Missing/원래방문재생의 잔액1120/정산1회·실제rentalId/settlementId/source유지와타source거절이다.

이는 고정clock/NPC·요금/SIMULATION과같은SQLite의시험이다. 권한누락은Missing(PERMISSION_DENIED) 입력이며 실제Android권한revoke가아니다. 재개방은DB재개방이고프로세스/전원시험·모든경쟁스케줄·실제고객/실차/SDK/선물지급이아니다. 제품NPC·20종별정의/verifiedclock·전체motioncoverage·같은profile factory/durable원래방문·MainActivity연결은남는다. 원문 rawinstrumentation이근거이고 XML은원문에서집계했다. 자기rentalruntimea2패키지정리·제품APK793e1db8/6데이터/권한·창/설정등19보존checksPASS·해당구간종료를회수했다. 다음장치queue는root배정이며조율자adb0이다.

근거 /private/tmp/drive-rental-runtime-20261110/independent-review/ 및 independent-device-review/의 REPORT/summary/source·device에보존했다. 최신main정상통합에서 소유8파일바이트동일성과RentalLedger.Pending 조회delta만유지하고 helper/schema/진행중E소스를변경하지않는다. 실제10개는eb096실행이며새게시head장치재실행이아니다. 정확headCI/원격공급은연결PR에서확인하고 #41~43/#51/#54 및전체기능을닫지않는다. 수리/초기화/AppHost의미해결P2도이성공으로해결처리하지않는다.


### 제품 HOME·가챠 조회와 표시 세대 수정의 독립 검증 — 2026-11-10

AppHost 원6e8f672는 mainf1e0b45와Shop3dc를정상통합해 HOME ShopView·화면선택/Back·저장조회와GachaBrowseHost를연결했다. 원제품범위의독립코드CLEAR/직접JVM20(정식16+외부4,48초)·작성자core383/app190과빌드/Lint는별도근거다. 정식ResultsHostActivityTest101의Spinner준비전대기P2를64963fd에서제품/gate/원래정산기대완화없이수정하고소스컴파일25초·delta검토APPROVE를확인했다.

A의정확649473추적소스/새빌드·설치pullSHA일치(앱09f8b854/시험9ca4014f), 원정식ShopHost1/ResultsHost1/Navigation1/ShopView9=12PASS를회수했다. 최초자기CAR_SPEED권한거부의gate준비실패/자기process종료이력은보존하고자기권한만부여후재실행했다. 제품권한변경0이다. 별도네이티브Spinner경로에서가챠Loading15초잔류와실제host→GachaInteraction외부1FAIL/오류0을확인했다. 같은generation의Loading수락뒤Missing이거절된P2로12PASS와분리해반려했고raw/캡처를보존했다.

수정360c42ca는GachaBrowseHost/단위시험2파일만바꾸고작업무효화/표시generation을분리했다. MainActivity/차량입력/gate/Manifest와정식Android4파일은649와동일하다. 같은외부시험SHAaf8c01b24603bc2ac5d3f0a0b44f74051430a25a8b60cc1d88750665bc72bb7c/기대무변경의JVM1GREEN을확인했다(Loading1수락→Missing2수락). 새473archive/새빌드40초·설치pullSHA(앱f8dda2cd/시험2913a0a7)일치후 실제SDK재관측으로활성화된Spinner를native좌표로선택해가챠terminal Missing·뒤로가게·최상위Task숨김·복귀첫제한후새gate HOME을확인했다. 이전12개를360에서새로반복했다고쓰거나native경로를instrumentation개수로세지않는다.

새계측A는 APPROVE_SCOPED_GACHA_FRAME_FIX_DEVICE·필수0을반환했고제품/6데이터·권한/차량·창/설정/전경등19보존checks/자기2패키지정리·해당구간종료를확인했다. 최초정리에서원전경Task가PAUSED였던649이력과기존DrivingView→지도task포커스만원복한뒤19/19일치한자료도보존한다. 새Activity/기어/속도/제품DB·AVD/서버reset은없다. 독립확인후게시담당은실제terminal/HOME PNG도직접열어OEM영역과앱영역을구분했다.

원근거 /private/tmp/drive-app-host-integration-20261110/independent-review/ 및 independent-device-review/, display-frame-fix/independent-review/의REPORT/summary/원문·PNG·설치/보존자료에보존한다. 조회대상은시험출처의실제SQLite이고공급없는권위는Missing, 거래/영속전체명령미연결이면Draw/시설/초기화는Unsupported/Unavailable이다. 실제추첨/초기지급/관리·시설거래·제품NPC/clock·프로세스/전원·실차/새SDK D시험과전체51/52/54/55/56완료는남는다. 최종디자인은팀WIP/임시Views이며새미술작업이아니다. 게시head의소유10파일360동일성/최신main정상통합·CI는연결PR에서확인한다.


### 초기화·첫 선물·checkpoint SQLite 통합81 독립 검증 — 2026-11-10

원81bbaf30a2f5fd3d968abc66fa6404d1585af33d/schema10의 공통 helper/factory·초기화/첫 선물·typed checkpoint를 부분 공급한다. 독립 APPROVE_SCOPED_COMBINED81_SQLITE_SUPPLY·잔여 필수0을 회수했다. 이전627의 초기화 공백 고아정산/선물 전체요청 INSERT IGNORE 두 P2와 당시27/30/33PASS는 보존한다. 627→81 제품 수정은 trimStart 모양 판별과 선물 NEW 확정 끝 recorded==result 재조회 각1줄이며 원래 결과 우선 재생은 유지한다.

같은 원 RED 소스 SHA Gift c77f9f64.../Init dc8097cf...·기대 무변경으로 두 개를 한 실행에서2PASS(0.414초), 초기화16+공개4+쓰기경계9=29PASS(4.426초), 선물25+공개9=34PASS(15.068초), 실제 helper checkpoint4PASS(1.006초)를 확인했다. 새 실제 실행 메서드 총69·실패/오류/생략0이며 겹친 fixture가 있어 독립 커버리지69개로 세지 않는다. Gift STORAGE_FAILURE/진행rev0/무료권0·관련8표 원래행 동일, Init read/Add 실패/차량3 보존·원래행/파일 bytes 동일과 정상 공백 legacy 배열 반례를 확인했다.

checkpoint 모듈/시험5파일·helper/factory·연결4파일은627과 동일하다. 정식20+올바른 digest 독립3=627 실제23PASS를 해당 범위로 재사용했고81에서27전체 신규실행으로 표시하지 않는다. 실제 helper4는81에서 새 실행해 onCreate/save/execute/재개방/replay/finish와 v8/v9→10 프로필/진행/재화/개체/receipt 보존·실패 원본 보존을 확인했다. 기존 정식 원시변조20의 일부는 decoder 전에 digest에서 거절될 수 있다. 작성자core412/app186·이전 head/다른 기기 결과와 합산하지 않는다.

독립493추적파일 전부81 동일·저자clean·정식 기대완화0, 새빌드35초 app e0e93e20/test41731414의 설치후 pull SHA 일치를 회수했다. API34 ARM64/user10의 단독 구간13:36:08~13:37:51 KST, 자기 combined81a 두패키지 정리/잔여시험없음·제품APK/6파일·권한/차량/창/설정/전경 등19보존PASS·슬롯반환을 확인했다. 게시 담당 adb0이다. 원문/메서드status/XML·설치hash/소스동일성과보존근거는 /private/tmp/drive122-first-gift-write-fix-evidence/independent-review/에 보존했다. 이전 whitespace 보고서도 이 같은 실행을 참조하며 중복 합산하지 않는다.

최신 main168 정상 통합에서 변경 앱23파일이81과 바이트동일하고 이미 main의 수리guard·Runtime·AppHost를 보존했다. 정확 게시head CI는 연결 PR에서 확인하며 새 게시head 장치재실행으로 쓰지 않는다. 명시 SIMULATION/격리SQLite/DB재개방이고 제품 livefactory/save·execute·finish 소비·전체UI/명령·프로세스/전원·실차/전체 #50/#51/#52/#56/#121/#122 완료는 아니다. #44 Expand 후보도 별도다.


### 랭킹 모듈·Android JSON 오류 분리 수정 검증 — 2026-11-10

b35c0bf의독립코드APPROVE와실제원본13중12PASS/1FAIL을별도보존한다. rewarded=1.5의Cursor.integer가던진Android checkedJSONException이RuntimeException catch를벗어나outerFailure가된P2다. 원58407d173ae4d5135404243db4b5c8d0d4a66d3e는명시import/catch만추가하고DB/숫자/정책/기존기대값을바꾸지않았다.

A는같은원본2파일SHA/기대무변경·새APK/설치pullSHA일치로ReadSource9+View4=13PASS1.851초를확인했다. 원래실패method의주간Failure/다른지표와마지막DB파일bytes불변assertion까지도달·통과했다. 추가Android격리SQLite2PASS0.354초는RENTAL-only명시fixture의weeklyFailure/나머지5보존,기본정의의주행command손상에서누적+주간Failure/나머지4보존·DBbytes불변이다. 새실제15·실패/오류/생략0이며과거b35독립1PASS/작성자JVM197·Lint와합산하지않는다.

491추적파일584동일·저자clean·외부overlay/기대변경0,appbf1d9da4/test605afd5d설치pullSHA일치. API34 ARM64/user10 14:51:22~14:55:52KST단독구간·자기2패키지제거/잔여시험없음·제품APK/6데이터·권한/차량/창/설정/전경등19보존PASS·반환을회수했다. 게시담당adb0. 원문/status/XML·원본SHA/보존근거는 /private/tmp/drive-ranking-local-demo-20261110/fix/independent-device-review/에보존했다.

최신main169/170과정상통합한소유12파일의584동일성과로컬검사는인계자료/연결PR에고정한다. MainActivity실제진입·실제SDK출발지역/Questhook·온라인/타인순위는미검증이다. 고정KST/Quest원인은명시fixture이며실제6지표전체완료로표시하지않는다. 이전b35실패를소급변경하지않는다.
### 제품 첫 실행·가챠·공통 factory 소비 독립 검증 — 2026-11-10

원cab5670738beac214588ae5184095877fbef54ad는 기존 Garage/C274/E81 공급을 포함한다. C의906 scoped 독립검토·JVM27PASS(가챠18+기존외부3+추가6), 문구 enum 제거cab의1PASS·작성자core412/app258은 각 원head 근거로 보존하고 합산하지 않는다. Garage 소유15의 코드24PASS/실제7PASS(1.460초), C 소유7의 독립17PASS/실제11PASS(1.237초)는 동일 소스 범위로 재사용하며 cab에서 새 실행한 수로 세지 않는다.

A의 정확cab529추적파일 원문/정식 기대 무변경·새APK 설치pullSHA 일치와 실제 정식 GachaLiveCheckpointSqliteTest6PASS(2.180초)를 회수했다. 별도 checkpoint DELETE RAISE(IGNORE)1PASS(0.457초)는 STORAGE_FAILURE/원명령 유지·재개 후 동일 결과/잔액500·차량2 보존·trigger 제거 뒤 finish 성공을 확인했다. 같은 MainActivity 재생성1PASS(9.128초)는 다른 instance/retained session·첫 제한 뒤 새 SDK gate·DB 전체행 보존이다. 이8메서드와 아래 native 경로는 구분한다.

별도 fresh package의 실제 SDK gate/터치로 첫 차 캐스퍼 확인→source VEHICLE/잔액1000/차량1/공간3/초기화1건/무료권0, 유료500 1회 추첨→캐스퍼 일렉트릭 고급/취득액500/잔액500/보유2/가챠1건→실제 차고2/3을 확인했다. 랜덤 결과는 사후 관측이며 새ID로 재추첨하지 않았다. 확정 뒤 own package force-stop/component 재실행의 PID변경과 동일profile/지갑/개체/지급1·추첨1·전체행 보존을 확인했다. 실제 launcher icon·지급 중 process kill·실차/전원 시험은 아니다. 원본6의 무료권은 명시SIMULATION fixture이며 native 무료권0에서 무료 추첨/실제 첫 영업 선물을 실행하지 않았다.

원본6구간14:23:28~14:24:50KST/native14:25:51~14:34:26KST, API34 ARM64/user10. 원본 app a2f53772/testbcad69ba/nativeapp90b3bce3의 설치pullSHA 일치·두 구간19보존PASS·자기패키지/권한/잔여시험 정리·슬롯반환을 회수했다. 관측문구/스크롤/목적화면 이름을 바로잡은 외부harness 실패는 보존하고 지급/추첨 횟수·저장 기대를 바꾸지 않았다. 게시담당은 native 결과와 차고PNG를 직접 확인했으며 OEM 영역과 임시앱 화면을 구분했다. 조율자adb0이다.

근거는 /private/tmp/drive-gacha-live-host-20261110/independent-review/ 및 independent-device-review/의 REPORT/summary·device/native 원문/DB사본/PNG/hash·보존자료다. 최신 main169/170 정상 통합에서 변경 앱/빌드34파일이cab와 동일하고 기존Runtime/수리guard/helper를 보존한다. 로컬 통합검사·정확head/CI는 연결 PR 또는 인계자료에서 확인하며 이 기기결과를 새 게시head 재실행으로 표시하지 않는다. 첫 선물 runtime·전체 가게/확장·Quest/Delivery·실제D전이/센서장애·전원·실차/전체 이슈 완료는 별도다.


### 랭킹 모듈·Android JSON 오류 분리 수정 검증 — 2026-11-10

b35c0bf의독립코드APPROVE와실제원본13중12PASS/1FAIL을별도보존한다. rewarded=1.5의Cursor.integer가던진Android checkedJSONException이RuntimeException catch를벗어나outerFailure가된P2다. 원58407d173ae4d5135404243db4b5c8d0d4a66d3e는명시import/catch만추가하고DB/숫자/정책/기존기대값을바꾸지않았다.

A는같은원본2파일SHA/기대무변경·새APK/설치pullSHA일치로ReadSource9+View4=13PASS1.851초를확인했다. 원래실패method의주간Failure/다른지표와마지막DB파일bytes불변assertion까지도달·통과했다. 추가Android격리SQLite2PASS0.354초는RENTAL-only명시fixture의weeklyFailure/나머지5보존,기본정의의주행command손상에서누적+주간Failure/나머지4보존·DBbytes불변이다. 새실제15·실패/오류/생략0이며과거b35독립1PASS/작성자JVM197·Lint와합산하지않는다.

491추적파일584동일·저자clean·외부overlay/기대변경0,appbf1d9da4/test605afd5d설치pullSHA일치. API34 ARM64/user10 14:51:22~14:55:52KST단독구간·자기2패키지제거/잔여시험없음·제품APK/6데이터·권한/차량/창/설정/전경등19보존PASS·반환을회수했다. 게시담당adb0. 원문/status/XML·원본SHA/보존근거는 /private/tmp/drive-ranking-local-demo-20261110/fix/independent-device-review/에보존했다.

후속main173(4e100fb)과정상통합해소유12파일584동일·기존실제firstflow Main/HostSession/helper보존을확인했다. 새조합은랭킹JVM15PASS/계측Kotlin컴파일BUILD SUCCESSFUL26초·저장소21/29/413/diffPASS다. 이전e652의APK/Lint성공은그head범위로구분하며새원격정확headCI는메인이확인한다. MainActivity실제진입·실제SDK출발지역/Questhook·온라인/타인순위는미검증이다. 고정KST/Quest원인은명시fixture이며실제6지표전체완료로표시하지않는다. 이전b35실패를소급변경하지않는다.


### 완료 고객 집계·확장 소비의 실제 SQLite 검증 — 2026-11-10

정확원542649ae35d0d799a09281dae2b3502213ff3a95의2파일에독립코드APPROVE/필수0과A실제CompletedExpansionLedgerTest13PASS(3.130초·실패/오류/생략0)를회수했다. 명시SIMULATION DB의실제assign/account/settle/finishReturn/receipt를사용하며방문3/미정산은완료0·무차감거절,완료2→3/초과4·9→10/24→25의기존동의임계/비용/용량,동일settle replay미가산·무료/사고완료근거를검증했다. 누락/손상/공유/고아정산의실패/행보존·대문자타도메인정상·확장commit실패rollback/같은ID재시도·현재조건손상뒤원래purchase replay/DB재개방도확인했다. 행assertion을전체파일bytes시험으로과장하지않는다.

480추적파일/정식기대무변경·새APK appf414a98b/test76f07193설치pullSHA일치, API34 ARM64/user10 13:47:45~13:47:51KST구간·자기2패키지/잔여시험정리·19제품보존/반환을회수했다. 작성자core412/app182·D/Garage/E69·다른시험과합산하지않는다. 원문/status/XML·소스/hash·보존자료 /private/tmp/drive44-completed-expansion-20261110/independent-device-review/. 조율자adb0.

최종후보는최신main174(7f5c598)과정상통합하며원정식시험파일과완료count/expand delta를유지하고기존Runtime Pending의parkedSince/엄격조회만추가로보존했다. 새조합Runtime17+랭킹15=32JVM PASS·계측Kotlin컴파일16초·저장소21/29/414/diffPASS를확인했다. 이전b5e6의APK/Lint는그head범위로구분하고새정확head원격CI는root가확인한다. 원542에서Runtime혼합장치13을실행한것으로쓰지않으며새로컬Runtime표적/컴파일근거·정확head/CI는인계자료/연결PR을따른다. helper/schema·shop방문수/보험/buy를바꾸지않는다. 실제정의/제품미리보기·선택터치/Activity·프로세스/전원·실차/전체 #44는후속이다.


### core·저장 4이슈 완료와 후속 연결 — 2026-11-10

PR #169 실제 초기화/첫 선물·공용 저장 공급, #173 같은 factory의 초기화/가챠/차량 관리 소비를 기준으로 환석의 위임을 받은 메인 조율이 원래 top 완료조건을 직접 대조했다. [#47 가챠](https://github.com/Yaho03/drive-growth/issues/47) 7/7, [#48 강화](https://github.com/Yaho03/drive-growth/issues/48) 7/7, [#49 수리·처분](https://github.com/Yaho03/drive-growth/issues/49) 6/6, [#50 초기화·첫 선물](https://github.com/Yaho03/drive-growth/issues/50) 6/6을 확인하고 completed로 닫았다. 기존 assignee를 유지했으며 AI 보조 검토를 별도 사람 승인으로 표시하지 않았다. 과거 본문·실행/실패/수정 기록은 원문 그대로 보존했다. 새 시험 실행이나 다른 결과를 합산한 완료 판정이 아니다.

#48의 동시판매 경계는 원자적인 현재 lifecycle/revision 재검사와 관련 실제 경쟁 시험을 함께 근거로 판정했으며 별도 동시판매 장치 시험을 새로 실행했다고 쓰지 않는다. #49의 core/SQLite 복구와 개별 native 행동, #50의 SQLite 선물 지급과 native 첫 차량 지급을 구분한다. 원조건과 해당 근거는 각 이슈의 완료 체크·조율 댓글을 따른다.

닫힌 네 이슈의 명령/저장을 다시 공급 대기로 두지 않는다. #52의 남은 관리·발견/화면 조건, #56의 튜토리얼 실제 주행전환/복귀, #28 기능 간 통합은 기존 완료조건대로 계속 추적한다. 전체 앱·제출·실차 완료를 뜻하지 않는다. PR #174 랭킹 모듈은 main7f5c598, PR #175 완료 고객 집계는 main3bbc05f에 일반 머지됐고 #175 머지 뒤 CI42773737173 성공을 읽기 조회로 확인했다. 실제 랭킹 진입·시설/꾸미기·Quest/탁송 등 새 연결은 각각의 열린 이슈와 별도 후보에서 관리한다.

### 중앙 시험 정의·실제 확장 Main 통합 — 2026-11-10

기준 main `3bbc05f9` 위에 E `dec8b40`→`f3a7a00`→`26c1e7b`, B `9b50d1b`→`aef5a11`의 기능 커밋만 순서대로 정상 통합했다. 변경 소스·시험 26파일은 B의 정확 검증본 `aef5a11`과 모두 동일하며 통합 과정의 추가 소스 수정·기대값 변경은 없다. 구형 main 트리 덮어쓰기를 하지 않고 #173의 같은 factory 첫 실행/가챠/Garage, #174 랭킹 읽기 모듈, #175 완료 고객 조회를 보존했다. schema/helper·신규 Quest/Driving 생산자·랭킹 Main 진입은 포함하지 않았다. 상세 파일 해시와 최종 head는 연결 PR의 인계 자료를 따른다.

기존 독립 근거는 각각의 범위로 재사용한다. E `26c1e7b`의 코드·구조 검토 표적 JVM23 및 실제 SQLite 확장15·중앙 asset1, B `9b50d1b`의 실제 SQLite15+asset1+session binding2는 해당 원본에서의 결과다. 9b50의 동일 접근 상태 반복 렌더 P2는 원래 JVM2·native1 실패 근거를 보존하고 `aef5a11`에서 같은 JVM2·native 안정성1 통과로 해소했다. A의 aef 기기 검증은 실제 SDK gate, 신규 첫차1000→유료 가챠500, Back/재진입, Activity 재생성, 자기 패키지 속도 권한 거부/복원, 프로세스 재시작 후 같은 저장 행 보존을 확인했다. 실제 완료 고객0/3의 잠김 미리보기는 무차감이며 **native 확장 성공 구매는 실행하지 않았다**. 이를 SQLite 확장 성공 검증과 합쳐 native 구매 성공으로 표시하지 않는다. A는 제품/환경19개 보존 확인·자기 패키지 정리·장치 반환을 기록했다.

최신 main과의 이 조합에서 코어412·앱321 단위 시험이 실패/오류/생략0으로 통과했고 앱 APK·시험 APK 빌드와 Lint(오류0·경고23)가 성공했다. 전체 명령은 2분1초에 종료했으며 저장소 필수21·문서29·링크415 및 diff 검사도 통과했다. 원래 시험 기대값을 바꾸지 않았고 정확 head와 원격 CI는 연결 PR에서 확인한다. 새 기기 실행·실차·실제 D 전환·전원 검증은 수행하지 않았으며 기존 원본 실행을 이 조합의 신규 실행 수로 합산하지 않는다. 시설 구매/배치·실제 자동 영업 driver·Quest/Driving 사건 생산자·전체 앱 통합과 #44/#28 전체 완료는 후속이다. AI 보조 검토이며 별도 사람 승인 확보로 기록하지 않는다.


### Quest·Delivery schema12 통합 후보 — 2026-11-10

정확 E b8972b563ffb0c0d6a7e969097f4cd172fff1941의 A 독립 API34 ARM64 격리 SQLite 검증은 Quest54/54(8.197초)·Delivery17/17(4.555초), 실패/오류/생략0이다. Quest25+통합12+checkpoint15+성장 후조건2, Delivery 원래15+Hold 독립2의 범위다. 이전63/399의 실패·진단, 같은 D 원래RED2→수정GREEN2와 행 단언을 보존한다. fixture 두 파일은 public lazy upgrade 경로/정확schema12·필수표/열과 기존 행/tracking/profile 보존을 검사하며 기대값을 완화하지 않았다. 원본 제품399와 b897의 차이는 정식 시험2파일이다. 이 원본 결과를 새 main 조합의 신규 실행으로 합산하지 않는다.

main177 기준 후보는 E 기능8커밋만 적용하고 기존 Runtime Pending.parkedSince/엄격 조회를 유지했다. 별도 Driving13·Progress/Delivery Main·랭킹 진입은 포함하지 않았다. 소비 호환은 미연결 Quest/Delivery 보관 명령을 명시 STATE_CONFLICT로 전달하고 원문 보존을 회귀 검사한다. 새 조합의 코어420·앱328 시험이 실패/오류/생략0으로 통과했고 APK·시험 APK 빌드와 Lint(오류0·경고23)가 성공했다. 80개 task를 실행해2분19초에 종료했고 저장소 필수21·문서29·링크424 및 diff 검사를 통과했다. 정확 head·원본 동일성·소비 호환 patch는 연결 PR 인계에 기록한다. 이 조합의 새 기기 실행/제품DB 이전·Main UI/프로세스·SDK 도착/주행·실차는 미검증이다. AI 보조 검토이며 별도 사람 승인 미확보, 원격CI/게시/머지는 root 담당이다.


### 관측 주행 schema13 통합 후보 — 2026-11-10

E 정확 `0681fbfed4d7faa76fc557a50a001abd97512ed0`은 F 독립 재검토 `APPROVE_SCOPED_CODE_AND_A_SQLITE23_GREEN`·새 필수지적0이며 A가 실제 API34 ARM64 격리 SQLite 정식20+독립3=23개를4.237초에 통과했다. 입력은 명시 SIMULATION 관측이다. 원본bbbc의 독립RED3와 이전정식20/fixture실패를 보존했고 같은 원문·기대값의 독립3이 전체 row 단언까지 GREEN임을 확인했다. 고아 marker/state/provenance 경계와 raw NaN trip canonical 동일성 수정이 범위이며 A의 환경19개 보존·자기패키지 정리/장치반환 기록은 원본 근거다. F JVM witness/코드 검토를 Android23 실행 수에 합산하지 않는다.

최신main178 위에 recorder import21cb·codec8a548·저장bbbc·수정0681의 기능 커밋만 통합했다. 변경24소스/시험은 E0681과 모두 동일하고 D recorder7파일도 그대로다. Garage의 이미 존재하는 foreign 분기 순서 충돌은 main 코드를 유지했고 schema13 정식 fixture는 A가 검증한 최종 원문을 보존했다. Main/HostSession B52e·제품UI·랭킹은 포함하지 않는다. 새 main 조합에서 core420·app344 시험이 실패/오류/생략0으로 통과했고 APK·시험 APK·Lint(오류0·경고23)가 성공했다. 80개 task를 실행해2분4초에 완료했으며 저장소21/29/428·diff 검사도 통과했다. 정확head·원본동일성은 연결 PR 인계를 따른다.

이 통합본의 새 기기 실행은0이다. 원본 schema12 Quest54/Delivery17과 Driving23을 새 schema13 전체장치회귀로 합산하지 않는다. 실제 SDK D/속도/도착·Main receiver/calendar·onStop/프로세스·FGS/전원·실차·정책 허용과 전체39/28 완료는 후속이다. 과거42조건 대조의 root 적용4체크와45부분판정은 유지한다. AI검토를 사람승인으로 표시하지 않고 root가 원격CI/게시·머지를 담당한다.


### B 제품 연결 고정본의 게시 준비 — 2026-11-10

main179가 B5c9514d5의 조상임을 확인하고 새 후보에서 정상 merge했다. 최초 전체 트리가 B고정본과 동일했고, 추가 문서 뒤에도 app/src·core·Gradle은 고정본과 동일하다. main 대비67파일(제품/시험·설정64, 기존B문서3)의 출처를 고정했고 기존 app/data·core·RentalLedger는 main과 동일하다. 새 제품 논리나 fixture 수정은 하지 않았다.

B 본인검증은 af48의 결정적 Intro회귀 RED3/6→같은6GREEN, 표적16·core420/app458·APK/시험APK/Lint0오류27경고다. 검증af48과5c의 app/core/Gradle 동일성을 대조했고 원본APK두개의SHA도 B보고와 일치했다. 이 게시 준비에서 전체Gradle을 반복하지 않았다. 새 저장소·링크/diff 검사와 최종head는 인계에 기록한다.

Progress의 기존 Native8fc 첫플레이1과 fixture수정dac의SQLite13, 랭킹원3d BackFAIL과1a5 cold-wrapperPASS, Driving0681 A23은 각각 정확원본의 독립 근거다. 과거랭킹FAIL 원인을 새 Intro race 하나로 확정하지 않는다. **A의 정확5c 원본 cold-wrapper1회는13.006초에 PASS**이며 원래 method/wrapper·gate/HOME/wait·최종원장 기대값을 유지하고 최종suite출력을 회수했다. 새baseline24의 SDKgate 허용 뒤6지표·Demo/주간선택·재생성·Back2/HOME/ShopView·잔액/차량 불변을 확인했고19/19보존·장치반환을 기록했다. 이번 Android에서는 Intro가 첫 Ranking 선택보다 먼저 도착했다. 상충 순서를 강제한 Android RED→GREEN으로 쓰지 않으며 그 결정적 순서는 B JVM6의3RED→6GREEN 근거와 분리한다. 좌표터치/CCP·새 NativeProgress/Driving/도착·과거3dFAIL의 단일원인 해소는 미검증이다. D는 최소 Intro delta를 독립 검토해 JVM30개 통과·추가 필수 지적0으로 수용했고, A의 소스646파일·설치 APK·최종suite 출력을 대조했다. AI 검토와 별도 사람 승인은 구분한다. A의 원본5c 실행은 이 문서 준비의 신규 기기 실행으로 합산하지 않는다. 실제 SDK/내비도착 상관·권한·gate·프로세스/전원·실차·자동영업/시설·새경제원점정책은 각 실제검증 범위를 확인해야 한다.


### Rental·시설 schema15 정상 통합 후보 — 2026-11-10

원본 f99에서 A가 격리 SQLite Rental 시험 32개(8.263초)와 시설 시험 14개(2.867초)를 각각 통과했고, 환경 보존 19개 항목을 확인했다. 이후 F의 독립 반례는 완료된 비금융 쉬기 요청 ID가 일반 금전 명령으로 재사용되는 실패 1개를 재현했다. 반환값 검증에서 실패해 당시 후행 행 보존 assertion은 실행되지 않았으며, 그 결과를 정상 14개 성공으로 덮지 않는다.

정확 d8 수정본은 공용 record의 `facility_command_results` 소유 ID 검사를 추가했다. A가 원래 14개와 동일한 독립 반례 1개, 총 15개를 3.042초에 통과했다. 같은 ID에 `STATE_CONFLICT`가 반환되고 후행 전체 행 보존 assertion까지 통과했으며, 새 기준 환경의 19개 보존 항목과 장치 반환을 확인했다. F는 원문 소스·설치 APK·시험 결과를 대조해 이 공급 범위에 필수 수정 0개로 수용했다. 별도 사람 승인을 뜻하지 않는다.

E 결합본 `0e5532993f4a18f7955226b9814bf9394a6b2e00`은 f99에 최소 수정 2파일만 정상 통합했다. 원 Rental32 파일은 바뀌지 않았고 결합본의 앱 단위 332개·APK/시험 APK·Lint 오류 0/경고 22 검사는 별도 근거다. 이 결합본에서 Rental32 장치 시험을 다시 실행했다고 표시하지 않는다.

main182와 E 결합본의 정상 통합에는 충돌이 없었다. E 소유 28파일은 원문과 같고 MainActivity·HostSession·VehicleInput은 main182와 동일하다. 추가 호환은 아직 미연결인 시설/쉬기 pending에 명시적 `STATE_CONFLICT`를 반환하는 경로와 회귀 1개뿐이다. 새 조합 코어 420개·앱 463개는 실패·오류·생략 0, 앱/시험 APK와 Lint 오류 0·경고 27, 저장소·링크 및 diff 검사를 통과했다. root도 E28 원문·공통 호스트 보존과 경로 표적 6개를 직접 확인했다. 첫 표적 재실행은 비어 있는 별도 Gradle 캐시의 offline 의존성 미해결로 실패했고, 기존 캐시에서 같은 검사가 성공했다. 이 환경 실패를 코드 결함이나 검사 성공으로 바꾸지 않는다.

새 통합 조합의 장치 실행은 0이다. 제품 DB/옛 APK migration, 실제 Main 경제 이탈 driver·구성 변경 분류, 시설 화면·Rest 연결, SDK·FGS·프로세스/전원·실차는 별도 미완료다. 확정 정책의 저장 공급과 실제 앱 수명 검증을 구분한다. 최종 head·원격 검사·main 반영은 해당 PR에서 확인한다.


### 랭킹 공개 원인 조회 연결 검증 — 2026-11-10

B 정확 e9007ea00a5b23058e7f660c170557802c9bd288의 소유5파일을 schema15 정확 e3f3a8a 후보 위에 정상 통합했고 모두 원본과 바이트 동일하다. 기존 MainActivity/HostSession/GameSessionFactory·시설 명령의 STATE_CONFLICT·E schema15 저장28파일과 원래 랭킹 시험을 보존했다. helper/schema/새 지갑/새 경제 논리는 변경하지 않았다. B 본인검증 provider8 중7 RED→같은8 GREEN·core420/app466·APK2/Lint0오류27경고는 원본 결과로 보존한다. C 최종 독립 검토는 소유5파일·649추적파일·읽기 API/SQL/JSON/집계 보존을 대조해 필수수정0으로 수용했다. C 직접 JVM28은 랭킹27과 기존 Intro 탐색1이며 추가 독립4를 포함한다. 다른 실행과 합산하지 않는다.

A는 정확 e9007ea의 준비 APK 쌍을 설치해 정식 RankingProvenanceBindingTest9개를4.466초에 통과했다(실패/오류/생략0·최초1회·fixture/기대/소스 변경0). 공개 초기지급→정산 열람→Quest save/execute의 잔액1100/누적수익100·보유1, 공개 SIMULATION recorder가 저장한 원출발 zone/원정산의 주간15m를 확인했다. 다른 zone·미활성 legacy·활성 journal의 원영수증 없음은 Missing, Quest source/관측 digest/foreign profile 손상은 의존 지표 Failure, 미지원schema999는 전체조회 Failure다. 반복 snapshot·전체 사용자 테이블 행·DB SHA 동일과 VEHICLE DB 미생성 단언까지 완료했다. 손상 SQL 뒤 조회의 보존이며 조회가 손상을 수리한 결과가 아니다. Quest 정의 zone은 명시UTC, 관측은 고정 SIMULATION이고 실제 SDK D/P 입력이 아니다.

A의 전체649소스와 원본 시험3파일·설치 pull SHA가 준비 APK/metadata와 일치했다. API34 ARM64/user10의 단독 구간은20:36:18~20:37:09KST이며19/19 제품·환경 보존, 자기2패키지/프로세스 정리와 슬롯 반환을 확인했다. A의 새 빌드는0이며 C도 A9 원문/footer/XML/해시/DB 보존을 별도로 대조했다. 원문·설치/소스 해시·보존 자료는 /private/tmp/drive-ranking-provenance-hooks-20261110/independent-device-review/ 및 independent-review/에 있다. 이 게시 준비에서 ADB 실행은0이다.

새 schema15 조합에서는 core420·app471 시험이 실패/오류/생략0, 제품/시험 APK·Lint(오류0·경고27)가 성공했다.80 tasks를2분4초에 실행했다. 이 조합의 새 장치 실행은0이며 A의 원schema13 실행을 schema15 전체 기기 회귀로 바꾸지 않는다. 과거 읽기 모듈13+추가2·같은factory binding2·5c cold-wrapper1은 각 원본 범위로 유지한다. 실제 SDK 관측·Native 탐색/Back·프로세스/전원·실차·온라인 순위를 새로 시험한 것이 아니다. PR #183은 root가 main1a919af에 머지했다고 인계했으며 mainCI42809815840은 인계 시 진행 중이다. 최종 정확 head/CI/main 반영·#58 종료는 연결 PR/root가 판단한다. #28·#51의 넓은 미완료 조건은 유지한다. AI 보조 검토를 별도 사람 승인으로 표시하지 않는다.


### 전체 정산 이력 모듈 공급 검증 — 2026-11-10

C 원본25c82c2의 전체 이력 reader·Results controller/mapper/Bundle/표시와 수정1ea3e6a를 main183 1a919af 위에 정상 통합했다. 모듈·시험14파일은 정확1ea와 모두 바이트 동일하며 MainActivity/HostSession·공통 factory·helper/schema/writer·E28은 main 그대로다. 옛 activity-hook.patch나 B의 미완료 제품 파일을 복사하지 않았다. README·이슈의 원격 반영은 root 담당이며 이 공급으로 전체 #54/#28을 완료 처리하지 않는다.

원25c+원래2파일hook의 A 실제 정식7은5PASS/2FAIL(1.103초)이고 reader4/View1은 통과, factory2는 초기화 뒤 첫history read의 STORAGE_FAILURE에서 실패해 뒤 열람/재개방/Bundle 단언이 미도달이었다. 이 실패를 보존했다. 수정1ea는 기존 initialization-v1 canonical codec·readInitialization의 Existing.original/marker를 대조하고 표시 Done과 실제 로컬 Pending 응답 해소를 구분한다. F의 같은 반례1RED→동일1GREEN 및 기존29+같은1의JVM30 통과는 코드 검증 근거이며 Android 실행 수와 합산하지 않는다. F 최종 권고는 APPROVE 범위의 두P2 해소·필수수정0이고 전체 기능 승인으로 확대하지 않는다.

A의 정확1ea+원래동일2파일hook 조합은 원7PASS1.728초·추가초기화4PASS1.391초, 실패/오류/생략0이다. 원7 source/기대 무변경·전체620파일·빌드/설치pull APK해시를 대조했고 factory2가 실제 초기조회→열람사건0→1/중복사건1회·재개방 원정산·scope/선택/Bundle·유실Pending 자동쓰기0까지 도달했다. 추가4는 정상 초기화의 전체이력/현재콘텐츠부재/행무변경과 proof/marker/nested grant 소수 손상의 실제mutation·실패/원본보존을 확인했다. 고유SIMULATION SQLite이며 API34 ARM64/user10,20:33:40~20:34:48KST 단독 구간·19/19 제품/환경보존·자기패키지/프로세스/계측정리·반환이다. 원문/설치·소스hash/보존은 /private/tmp/drive-results-history-fix-20261110/independent-device-review/와 independent-review/에 있다. 기존25c 실패를 새1ea 성공으로 소급변경하지 않는다.

새 모듈 조합은 core420·app472 시험이 실패/오류/생략0, 제품/시험APK와 Lint(오류0·경고27)가 성공했다.80tasks/2분28초이며 저장소·링크/diff 검사는 최종 인계/PR에서 확인한다. 이 조합의 새 장치실행과 담당 ADB는0이다. **A11은 head단독이나 최신B Main 연결의 실행이 아니다.** Main/HostSession의 readHistory 실제 주입·최신 gate/Bundle/탐색과 UI·Activity/프로세스·SDK/전원·실차는 B/관련 후속에서 구분 검증한다. 저장된 매출/시설보너스/가상사고/평가·단골 nullable 표시만 공급하며 미제공 감액/상한·시간 provenance는 확인불가로 남긴다. AI 보조 검토이며 별도 사람 승인·정확 원격CI/main 반영을 확보한 것으로 표시하지 않는다.

후속 게시 준비는 PR #184가 main `fa03b9b289eaf101fd8e3a902af4c29df4f12214`에 반영된 뒤 그 main을 정상 부모로 통합했다. 소스 충돌은 없고 문서 끝의 두 추가 절 충돌은 랭킹·Results 기록을 모두 보존했다. Results14·Ranking5·E28과 현재 Main/HostSession/factory가 원본/최신main과 동일하다. 새 조합 앱480개는 실패/오류/생략0이며 이 실행에 포함된 Results30·Ranking23을 별도 실행 수로 합산하지 않는다. 제품/시험 APK·Lint(오류0·경고27)는78tasks/2분9초에 성공했고 저장소 필수21·문서29·링크439와diff검사를 통과했다. core와 공통 Gradle/앱 빌드 입력은 이전0e28 검증본과 동일하므로 core420은 그 이전 실행 근거로 재사용하고 이번 재실행으로 쓰지 않는다. A 원래11·F30 및 이전472/420과 새480을 구분한다. 이 조합의 새 기기/ADB 실행은0, 최신 Main readHistory 소비는 계속 B 별도 범위다. mainCI42811398819는 root 인계 시 진행 중이었으며 여기서 성공으로 변경하지 않는다. #58 종료와 #54/#51/#28 원격 변경 여부는 root 최종 기록을 따른다.


### 시설·영업 쉬기 소비 모듈 통합 검증 — 2026-11-10

C213과 최소 복구575584a의 모듈·시험12파일을 Results e936 후보 위에 정상 통합했다. 소스 충돌 없이12파일 모두 정확575와 바이트 동일하며 E28/d8 소유ID guard·랭킹5·Results14·기존 Main/HostSession/factory/HostPendingRoute를 보존했다. 원본 등록3파일patch와 B WIP는 포함하지 않았다. 같은 factory/직렬worker의 시설 조회·E공용 typed 원명령 소비 모듈이며 HOME/Shop/쉬기 event·pending 라우트의 최신 Main 등록은 B 별도 범위다. 게시 준비 당시 root는 PR #185 게시·CI 진행 중으로 인계했다. 이후 정확 e936의 CI4 성공 뒤 main8c067b9에 일반 머지·원래 로컬clean동일을 확인했다고 인계했고, 이 main185를 정상 부모로 통합했다. 머지 후 CI는 root 확인 중으로만 기록한다.

FACILITY-HOST-P2-1의 수정은 finish 성공으로 pending이 해제된 뒤 현재 facts 조회가 실패한 경우에도 Retry를 보이는 한 조건이다. 원213+동일F1은 선택/START1 뒤 기대 retry-null assertion이 자기 프로세스 crash 로그에 관측됐지만 정식method terminal0·정상footer 없음·collector error1이었다. 정식JUnit assertion 실패 보고0이고 후행Retry/오류해제/facts복구·finish/write각1 단언은 미도달이었다. 이를 정상 RED1 suite나 정상 suite 실행시간으로 바꾸지 않는다. 원C12 통과나 새575 결과로 이 관측을 덮지 않는다.

A의 정확575 준비쌍은 원12와 동일F1을 합친13개가2.487초에 정상footer로 통과했다(실패/오류/생략0). 원12 본문과 F1 원문/기대가 같고 전체600소스·빌드/설치pull SHA를 확인했다. 이13은 실제 factory/SQLite9, 고정 입력 View3, fake backend View/controller 복구1이다. 추가F1은 활성Retry·performClick·오류해제·facts복구와 재시도 뒤 finish/write 각1회 단언까지 통과했으며 금융/SQLite/SDK 시험으로 확대하지 않는다. 실제9는 중앙offer/기존E슬롯의구매·쉬기·원결과재생·현재OFF/성장보존·foreign/누락/finish실패·SAVED/STARTED재개방 경계를 확인한다. 명시SIMULATION이며 실제HOME/SDK 입력은 아니다.

A가 사용한 준비앱 a7166fc1312c712f2c782e48dd8e4ff4ac596d56de13702e037aa03c53e9f916, 시험7e656e94c00ecb0006e3d33554ae795675981819f4483338748f61258263b0ba는 전달원본과 설치pull이 각각 같다. API34 ARM64/user10,21:02:33~21:03:27KST 단독구간·19/19 제품/환경보존·자기패키지/프로세스/계측정리·반환을 확인했다. A 새빌드0이다. 원문/소스·설치hash/보존은 /private/tmp/drive-facility-read-recovery-fix-20261110/independent-device-review/에 보존한다. F 최종 APPROVE_SCOPED_FACILITY_READ_RECOVERY_CODE_AND_A575_SQLITE_VIEW13_GREEN은600소스/원12/F1·설치hash·정상footer/후행단언을 대조해 필수수정0으로 모듈 공급을 수용했다. F 새Gradle/장치0이며 C표적20·APK 근거를 확인했을 뿐 새실행 수에 합산하지 않는다.

원575의 A13에는 E d8 저장guard가 없었다. 이번 후보는 이미 검토된 E0e55/d8을 그대로 보존하지만 이를 A13과 같은 결합본 장치검증으로 표시하지 않는다. 새 조합 앱500개는 실패/오류/생략0이며 시설20은 이500에 포함된다. 제품/시험APK·Lint(오류0·경고27)는78tasks/2분3초에 성공했고 저장소필수21·문서29·링크444 및diff검사를 통과했다. core와 공통/앱 Gradle입력은이전검증본과동일해 core420은이전실행근거로만재사용하고이번에재실행하지않았다. 이 후보 담당의 새장치/ADB/원격쓰기는0이다. 실제B Main/HOME·Shop구매·쉬기터치·gate/좁은창·구성변경/프로세스·SDK/전원·실차·꾸미기/최종아트와전체#44/#55/#28은별도다. AI보조검토를별도사람승인으로쓰지않으며정확게시head/CI/main반영은root가확인한다.


main185 후속 부모 통합 직후에는 이전1e4a035와 전체 트리 차이가 없었다. 최종 README는 경민 행의 이미 제공한 시설 구매/비금융 쉬기 저장과 남은 Main 제품 연결·배치/꾸미기 저장을 구분하고, 본문의 시설 구매/쉬기 Main 소비 경계만 최소 보완했다. Results14·랭킹5·E28·시설12와 현재 Main/HostSession/HostPendingRoute/factory를 다시 대조했다. 추가 소스/빌드입력 변경이 없어 앱500·APK/시험APK·Lint0/27은 이전1e4a035의 실제 실행 근거로 재사용하며 새 실행으로 합산하지 않는다. 이번 후속은 저장소21/29/444·diff·소스동일성 검사만 새로 수행했고 새Gradle/기기/ADB 실행은0이다.


### 종료 영업 currentInactive 읽기 API 통합 검증 — 2026-11-10

E 정확efe4965595438442a7771e678a07baf9efd56155의 SqliteRentalProductJournal.currentInactive와 RentalProductJournalTest 추가9를 시설1ea4 후보 위에 정상 통합했다. 지정2파일은원본과바이트동일하고 기존E28중나머지26·시설12·Results14·랭킹5·현재Main/HostSession/PendingRoute/factory를보존했다. schema15/helper/기존writer/strictdecoder·core/Gradle과기존경제원점정책은변경없다. Rest830이나최신B Main/driver소비·이탈/구성변경판정은포함하지않았다. 게시준비당시root는시설후보를PR #186게시/CI중으로인계했다. 이후정확1ea4의CI4성공뒤main1358f6e일반머지·원래로컬clean동일을확인했다고인계했고그main186을정상부모로통합했다. 머지후CI는root확인중으로만기록한다.

C최종 ACCEPT_SCOPED_CURRENT_INACTIVE_READ_CODE_AND_A41_SQLITE는정확2파일/657소스·원32본문·추가9계획/SHA와A41자료를대조해필수수정0으로범위를수용했다. C새시험/ADB0이며작성자의앱471/APK2/Lint0/27과장치41컴파일근거를재실행으로합산하지않는다. 기존state.inactive의원marker/실제since/basis·원policyOrigin/phase/interval을읽는API이고정의·현재clock·policy공급자를재호출하지않는다. 바깥DB손상Failure와유효metadata안의당시policyOrigin Missing/명시Failure를구분하며phase는금융영수증/새지급완료가아니다.

A는정확efe 준비쌍에서원32+새9=41개를9.858초에정상footer로통과했다(실패/오류/생략0·첫실행·소스/fixture/기대변경0). 원32본문prefix가같고전체657소스·빌드/설치pull SHA를대조했다. 추가9는미활성/진짜부재·미준비이탈marker 재개방·legacy 물리원점/실제since구분·foreignslot뒤원준비to·완료뒤다음현재marker·Missing/당시Failure·선택marker/digest/source/basis/현재참조소실을확인했다. 정의/clock/policy 공급자가호출되면즉시던지는fixture의재개방조회까지통과해그호출0을확인했다.

조회 전후 보존은 account·vehicles·settlements·rentals·visits·rental_product_marker·rental_product_state·rental_product_inactive·host_command_checkpoint·quest_events의 **검증한10개테이블행** 범위다. 전체DB모든표/파일바이트해시불변으로확대하지않는다. 명시SIMULATION clock/customer/input이며실제Main onStop·SDK·프로세스재시작시험이아니다. API34 ARM64/user10의19/19 제품/환경보존·자기패키지정리·반환을확인했다. 준비앱1e0babb15cdfd4d9f4ee360fb01cabf9a737dcc9b596922e9ddf3d8574b97d6b,시험bdd230ce05e75fafda9a7837ea107176445b386a3c4a6463fb32df746f7ed413은원본/설치pull과같다. 원문/657소스/설치hash/19보존은 /private/tmp/drive-rental-inactive-marker-read-20261110/independent-device-review/ 및 independent-review/에보존한다. A새빌드0이며이통합담당의새장치/ADB0이다.

새통합조합은앱500 실패/오류/생략0·제품/시험APK·Lint오류0/경고27,78tasks/2분성공이다. 원본A41을새시설/Results결합본의장치41재실행으로쓰지않으며여기서는추가9를포함한계측소스컴파일/APK까지만수행했다. core/공통·앱Gradle입력이같아core420은이전실행근거로재사용/이번재실행0이다. 저장소21/29/447·diff검사도통과했다. 공개API공급과B의최신Main실제소비·전체#43/#51/#28완료는별도로유지하고기존원점/phase를현재값으로보충하지않는다. 원격쓰기0·별도사람승인확보주장0이며정확게시head/CI/main반영은root가확인한다.


main186 후속 부모 통합 직후 이전0f042b1과 전체 트리 차이가 없었고 최종 소스·빌드입력도 그 검증본과 같다. 따라서 앱500·제품/시험APK·Lint0/27·78tasks2분은0f042b1의실제실행근거로재사용하며이번새실행으로쓰지않는다. 이번에는부모/트리·원blob/소스동일성·저장소21/29/447·diff/clean만새로확인하고Gradle/기기/ADB는재실행하지않았다. 최신Main의B소비/실제이탈·구성변경구분과Rest830후속은계속이후보에포함하지않는다.


### FusedLocation SDK 6파일 통합 검증 — 2026-11-10

D 정확92c4ee31f826b2713dd7c481d9791465385d90b5의6파일을main187 b2c1a2fa89734d4a9241ccc635701f63cf2085d2 위에정상통합했다. SDK의존성1줄·명시FUSED_LOCATION권한1줄과어댑터/mapper·원시험만추가됐고모두원92c와바이트동일하다. B Main/HostSession/VehicleInput·currentInactive2·시설12·Results14·랭킹5·전체data/host/core트리를보존했다. schema15를유지하고Rest schema16·A진단Activity/시험patch·F추가시험은포함하지않았다. 원래 인계 때 실행 중이던 main #187 CI42820687455는 이후 두 검사 모두 성공했으며 root가 원격 결과를 확인했다. 이번 Location 후보 자체의 게시 후 CI와는 구분한다.

원D본인검증은core420/app487(위치24포함)·APK/Lint0오류27경고다. F정확92c검토는원24+독립2=26PASS/42초·필수수정0이며JVM대체SDK/queue의release실패/재시작·close재진입·옛callback폐기를확인했다. F추가스레드감사는필수0/새시험0으로기존수용을유지했고A장치결과를사용하지않았다. currentadapter main호출계약과SDK공식main필수는구분하고,원격Proxy register/unregister의oneway전송과caller의PackageManager/권한검사/unbindService를분리했다. 실제최악지연/UI정체·원격해제완료ACK는검증하지않았다.

A의실제실행은원92c에진단debugActivity/Android시험2파일·기존debugManifest등록1곳을더한660파일조합이다. 원658중제품adapter/Main/HostSession·원24시험/Gradle/mainManifest는그대로며실제SDK를그대로호출하는decorator를internal시험입구에주입했다. 위치/오류callback을합성하지않았지만제품publicconstructor 자체의새Native실행이나최신B Main연결·무패치head단독실행으로표시하지않는다. 최종같은APK의허용1은3.612초PASS/정상footer, supported=true·initialize/isSupported/register각1반환·실제nonnull onChanged1/오류0·Available1/초기NO_DATA1이었다. unregister/release각1·close반환,종료후3초관측/queue drain의adapter전달0을확인했다. 최종거부1은18.250초PASS/정상footer로PERMISSION_DENIED1·SDK생성/init/register0·rawcallback/Available0·close반환·종료뒤전달0이었다. 관측시간범위를미래영구무callback이나서비스완료보장으로확대하지않는다.

앞선초기probe허용1PASS3.820초와거부비교1FAIL3.252초/오류0/정상실패footer는보존한다. 거부실패는SDK시작전즉시hasWindowFocus assertion이었다. 진단Activity의실제focus latch로최대5초기다린뒤같은검사를수행하도록시험만보완했고제품어댑터/SDK값/기대조건을완화하지않았다. 총4회진단을최종한번의무결성공으로축약하지않으며F26/D487과합산하지않는다. 실제수신시각/SDK원timestamp·confidence를보존했지만시각차를지연SLA/센서신선도검증으로,confidence5.0을정확도m로해석하지않았다.

A최종앱76150b8ac71c73e8ec00264f6876f33f62e9939dfbec754cee12a1d31302e639·시험72df227ede1d977f052d72154b282ea85cf25d3e808e15487450e0df915f8f20의파일/built/설치pull SHA가같았다. API34 ARM64/user10·21:29:34~21:34:23KST단독구간·19/19제품/환경보존·자기2패키지/프로세스/계측/forward정리·반환이다. 기존SDK공유서비스PID는유지되고close뒤hasBound=false를관측했으며서비스강제종료는하지않았다. 좌표원문은로컬진단자료에만보존하고여기에는옮기지않았다. A소스/설치hash/원문footer/보존근거는 /private/tmp/drive-location-sdk-build-20261110/independent-device-review/, F26/추가감사는 independent-review/ 및 thread-audit/다.

새main187조합앱524는실패/오류/생략0이며위치24가이524에포함된다. 제품/시험APK·Lint0오류27경고는78tasks/2분18초에성공했다. 병합Manifest에서앱min28/target34,명시권한과SDKbinding권한·SDK1.1.3/서비스최소0.5.0metadata를확인했다. core와공통Gradle입력은같아core420은이전실행근거로재사용/이번재실행0이다. 저장소21/29/450·diff검사도통과했다. 이조합의새기기/ADB/원격쓰기0이며A660진단을새제품APK의실제실행으로쓰지않는다.

실제B Main/HostSession의동의·권한dialog·전경수명/캐시무효화·close예외처리,센서clock축/신선도/정확도·장소inside/outside·휴식/주차/출발적격성·Rest지급,실제서비스미지원/강제오류/release실패주입·UI정체·background/프로세스/전원·실차는별도미검증이다. source=VEHICLE/Available을실차/휴식인정으로확대하지않고공공215점의임의반경/영역을만들지않는다. 이미완료한#13기준환경이슈는보존하고#40/#51/#28/#81의부분공급/잔여조건만별도로연결한다. AI검토는사람승인이아니며정확게시head/CI/main반영은root가확인한다.


### 제품 영업·시설·정산 호스트 연결 검증 — 2026-11-10

정확 B0dd969f37fd588a7cea60be2694b812de1ad099e의 소유18파일과 main187의 data/core/Gradle/Manifest 보존을 직접 대조했다. 원본 본인검증 core420/app519·APK/시험APK·Lint 오류0/경고27과 마지막 OS clock 계측 추가 뒤 UP-TO-DATE 재확인은 별도 실행으로 합산하지 않는다. stub 시점의 같은 복구8개 중7실패와 구현 뒤8통과도 원본 기록으로 보존한다.

독립 검토는 C의 UI/gate34, D의 시간/원관측20, F의 금융복구23(기존22+독립1)을 각각 실행해 필수 수정0으로 수용했다. 세 그룹에는 겹치는 기존 시험이 있으므로 합산해 새 고유 시험 수로 표시하지 않는다. F가 검토 중 제기한 giftAttempt 실패 표식은 실제 Main/onboarding.refresh 재시도 연결을 대조한 뒤 확정 결함으로 채택하지 않았다.

A는 원본 B의 격리 패키지 io.drivegrowth.verify.rentalproductb에서 아래65개 고유 메서드를 첫 실행에 모두 통과했다. 실패·오류·생략0, 정상 그룹 footer와 후행 단언 도달을 확인했다.

| 그룹 | 결과 | 시간 |
|---|---|---|
| 새 실제 HOME cold | 1/1 | 18.484초 |
| 새 영업 SIMULATION | 2/2 | 3.793초 |
| 새 실제 OS clock | 1/1 | 0.018초 |
| 기존 Results | 11/11 | 1.511초 |
| 기존 Ranking | 9/9 | 3.844초 |
| 기존 Rental | 41/41 | 10.129초 |

HOME은 기본DB 없는 자기 새 패키지에서 실제 SDK P gate를 거쳐 UI 첫 지급→쉬기ON→재생성/같은원명령→확인→OFF→확인을 수행했다. 잔액1000·동일 캐스퍼/revision0·비금융 결과2/원 초기화 금융 정산1을 확인했다. 위젯 performClick이며 물리 좌표 터치/CCP 검증은 아니다. 영업SIM2는 공개 초기화·source/SQLite journal/runtime·원 대여 재접속 정산·첫 선물 한번 지급·새 factory 복원과 구성 변경의 미확인 구간/잔액 보존을 확인했다. boot/clock/customer/신호는 명시 모의 입력이다. OS clock1의 실제 BOOT_COUNT/UTC/elapsed 읽기는 이 모의 입력과 구분했다.

A source manifest690 항목은 모두 일치했으나 실제 Git 추적 파일691 중 공식 접수양식 DOCX1개가 빠져 있었다. 이를 전체 추적파일 완전 manifest로 표현하지 않으며 A의 전체 SHA에는 포함했다. 앱 SHA81bbefb1ebf7a61b95c2f7803d23130d26b456aa3ca4a108169e82200468ed14와 시험SHA bf94d969f3bae036aacfd76385dc2b3ea839c8bc14cf34109d80bed294fd2686는 설치 후 pull과 같았다. 22:28:51~22:31:33 KST 단독 사용 후 제품/환경19항목 보존, boot24·자기 권한 원복·패키지/계측/forward 정리와 반환을 확인했다. 원문은 /private/tmp/drive-product-main-followup-20261110/independent-device-review/에 보존했다.

원65의 HOME 로그 driver_deferred=DATA_UNAVAILABLE 두 건과 당시 실제 D 미입력/미수신 기록은 보존한다. 후속 B0dd 수동 관측에서는 실제 SDK D→짧은 명시 Back 이탈→같은 Activity 복귀·P 복귀와 원marker 소비를 확인했다(아래 별도 절). 완료 대여/첫 선물·장시간 종료 수익·프로세스/전원·실차 검증은 남는다. 최신 Main Results의 P 상태 UI 흐름은 아래 추가1에서 확인했다. 초기 P에서 과거 영업을 만들지 않는 상태와 실제 SDK 영업 성공을 구분한다. Rest·실제 위치/영역 등록·최종 디자인도 포함하지 않는다. 원65를 main188과 합친 새 APK의 장치 성공으로 확대하지 않으며 전체 #41~#44/#43/#51/#28 종료 근거로 사용하지 않는다.

새 main188 조합은 B18파일과 기존 Location6·data/core/Gradle/Manifest를 직접 대조하고 앱543개를 실패·오류·생략0으로 실행했다. 제품/시험APK·Lint 오류0/경고27도 성공했다. 원본 B의 core420은 동일 입력의 실행 근거로 재사용하며 새 core 실행으로 합산하지 않는다. 문서 후속만 추가한 뒤 저장소/링크·diff를 재확인한다. 이 조합의 새 장치 실행은0이며 원본 A65와 구분한다.


### 통합 모의주행 시연 계획 — 2026-11-10

차가 없는 개발·시연 환경에서 차량 신호와 내비 모의주행을 수동으로 따로 조작하는 부담을 줄이도록, 별도 시연용 APK의 한 흐름 실행을 준비한다. 기존 게임 UI에서 플레이하고 명시 SIMULATION 입력으로 출발→이동→도착→주차→동일 게임 계산/지급/SQLite 저장·재실행을 연결하는 검증 도구다. 실제 SDK 도착 콜백을 합성해 수신 성공으로 기록하지 않으며 실제 차량 원장·프로필·패키지와 분리한다. 긴 시간은 명시 시연용 논리시계로만 진행하고 실제 센서/OS 시각과 섞지 않는다. 기존 게임 규칙·지갑·경제 계산을 복제하지 않고 release에서 시험 입력을 켤 수 없도록 한다.

현재는 B의 별도 작업으로 설계·구현 중이며 사용 가능한 시연 기능이나 기존 시험 성공으로 표시하지 않는다. 기존 SDK/에뮬레이터 시험은 유지하고 실제 주행/UX 제한을 모의 P로 우회하지 않는다. #28에서 검증하며, 사용자 지시에 따라 #189 디자인 검토·적용은 진행 중인 기능 구현·검증 이후로 둔다.


원본 B0dd에 독립 Native 시험1만 추가한 별도 조합도 실제 실행했다. ActualMainResultsHistoryIndependentTest는 실제 UI 첫 지급→HOME→Results 미확인/전체 필터·원 정산 선택·열람 전후 두 번의 Activity 재생성·Back/재진입을24.856초에 통과했다. 정상 OK(1 test)·여섯 stage·원 금융 행 불변과 RESULT_VIEWED 정확1의 마지막 단언까지 확인했다. 원690 항목에 공식 양식DOCX1과 새 시험1을 포함한 전체692파일, 설치 앱SHA83fb1c92ad321e69bff43c4b1b50e1cca291f433e8972ea34d041117bd366449·시험SHA d11a3b1ad043909c31e15321f19b0d77623af57f3db5038a20044dc03730ff4d를 대조했다. 원65/Results11과 다른 시험 조합이며 합쳐 하나의 실행으로 표시하지 않는다.

22:55:47~23:01:20 KST 단독 슬롯·제품/환경19항목 보존과 자기 권한 원복/패키지 정리를 확인했다. 추가1 원문은 제품 source/기대값 변경 없이 저장소의 정식 Android 시험으로 보존하고 새 main188 조합에서 시험 APK 컴파일을15초에 확인했다. 제품/단위 입력은 기존543 검증본과 동일해 중복 실행으로 세지 않는다. 추가Results1 자체 로그의 driver_deferred=DATA_UNAVAILABLE3건은 당시 기록으로 보존한다. 실제 SDK 짧은 이탈/복귀는 후속 B0dd 별도 수동 관측에서 확인했으며, 이 시험의 새 실행이나 CCP/실제 런처·프로세스/전원 검증으로 합산하지 않는다.

[실제 HOME 화면](evidence/product-host-20261110/actual-home.png)과 [실제 전체 Results 화면](evidence/product-host-20261110/actual-results.png)은 같은 격리 앱/원 지급·열람 DB의 원본 ADB PNG다. 정식 시험 후 화면 선택/필터만3회 탭했고 금융·열람 요청 추가 없이 전후 원행과 RESULT_VIEWED1을 보존했다. HTML/목업/편집 이미지가 아니며 #189 디자인도 적용하지 않았다. 현재 임시 Views와 원B0dd 제품의 실행 증거이고 최신 결합본의 장치 실행으로 확대하지 않는다.


### Rest 6a4 세 수정의 main188 통합 검토 대기 — 2026-11-10

정확6a4d820645cbd58c05959d7dd8a48f73746a5972를main188 c6c345d1866569cb3156229ba363312d1778fede 위에정상통합했다. 소스충돌/추가수정0이며변경41파일전부6a4와바이트동일하다. Main/HostSession/PendingRoute/factory/VehicleInput·Location6·시설12·Results14를보존했다. marker생산API는그대로이며시험fixture만schema16으로갱신했다. RankingReadSource의공통Rest주행reader소비는원본의의도된변경이고기존Provenance hook/typedFailure catch는보존했다. core/Gradle/Manifest불변·B0dd미포함이다. **이main188 로컬후보당시에는새SQLite결과대기였다. 후속c28의실제47통과는아래별도조합으로기록하며, #190이후Main조합·최종독립회수전까지머지보류다.**

원830의A실제Journal18+Ledger18=36PASS와C JVM16·별도receiver-overlap1FAIL을구분한다. 동일boot의서로다른receiver가0..1000/500..1500의겹친물리시간을입력하면Success/2000ms소모를반환한C/E원반례가있었고,9db는boot별물리cursor·원문검증을보완했다. 원830공개원문손상반례2의실제RED는A가실행하지않았으며실행한것으로바꾸지않는다. 정확9db의A Journal23+Ledger18=41PASS/정상footer는그원본범위로보존하고후속C1/D4실패를합산해소급변경하지않는다.

C metadata소실추가1은정확9db+원C시험의실제1FAIL/오류·생략0/1.071초·정상실패footer다. 원Rest확정뒤observed receipts/state/marker DELETE의영향행수각1 단언3개가통과했고receipt/current/zone을호출했다. 첫receipt기대STORAGE_FAILURE에실제DATA_UNAVAILABLE이반환됐으며이미계산한current=DATA_UNAVAILABLE/zone=Missing(NO_DATA)도관측됐다. 후행current/zone기대단언과최종행보존단언은미도달이다. 이결과를금액손상이나전체행보존성공으로확대하지않는다.

D SQL4는정확9db+원D시험의실제3PASS/1FAIL·오류·생략0/2.980초다. day AFTER ack trigger의day+1 뒤첫Storage기대에실제Success(amount15/day1000)가반환됐고뒤rows rollback단언은미도달이었다. 최종DB day1001을별도로조회/assert한증거는없다. 다른3의consumeID소유충돌rollback·응답유실원결과재생·상한뒤0원/소모/ack는각후행단언까지그원조합에서통과했다. 비교는원시험의선택12표행범위이며전체DB바이트불변이아니다.

D의UTC A→B→A반례는물리elapsed중첩과별개다. 같은boot/domain A로돌아올때과거A의UTC cursor가소실돼원plan이Success(3Uses/2000ms소모)를반환했다. E는원D3를그대로보존해기존10+원D3=13중1RED를재현했고,수정뒤정상복귀/새boot/다른drive경계3을추가한16GREEN을확인했다. D의정확dfdc UTC수정독립16PASS/필수0은그UTC delta만수용한것이며가격식/SQL day/metadata·전체Rest최종승인이아니다.

최종6a4는UTC cursor·신규ack뒤day정확후조건·활성Rest근거뒤관측모듈전체소실의세수정을포함한다. E원본698파일을manifestSHA와대조했고clean·8개후속fix파일·원시험SHA를확인했다. E본인앱569/APK/Lint0/27은원source검사다. 준비된E46은Journal23+Ledger18+D SQL4+C metadata1의고유46이며원본문/기대값을유지했다. E준비앱6b56430e898af72fde47c4f820284b3e93a7aced2fca7d84136fe8423b336e03·시험88c5135b82702bd8f9425fda0a916e9f3b705cf2c39630f64c1db91472e4f38b는파일hash만확인했고설치/실행SHA라고쓰지않는다. 이46준비쌍자체의실제실행은주장하지않는다. 후속제품동일c28+F1의A47은아래에구분한다.

F복수departure1은정확9db+별도F시험의명시compact SIMULATION 조합으로7.967초PASS/정상footer·carry/두departure/단일입금/재생/rollback을확인한별도근거다. C1/day실패를해결한시험이아니며그F1을E46의source/APK/계획에넣거나46쌍을덮지않았다. 원830/9db·C/D/A실패/성공및새46준비자료는 /private/tmp/drive-rest-domain-utc-fix-20261110/REPORT.md와관련원본independent-review/device-review에분리보존했다.

새main188조합은앱593 실패/오류/생략0,제품/시험APK·Lint오류0/경고27,78tasks/2분19초에성공했다. RestTimeline/동일D clock16은이593에포함되며별도합산하지않는다. core420은동일core/commonbuild입력의이전근거/이번재실행0이다. 저장소21/29/453·diff/소스동일성검사도통과했다. 이는로컬JVM/컴파일/APK/Lint결과이며후속c28의A47과구분하고최종독립승인을대신하지않는다. mainCI42823800132는root인계시실행중으로만기록한다. 담당새기기/ADB/원격/canonical수정0이며실제B producer/Main/boot·domain/장소권위·SDK/프로세스·전원·실차·제품UI·전체#40/#39/#51/#57/#28/#122는미완료로유지한다.

후속 C는 정확6a4의 metadata·공개 읽기 코드 범위에서 새 필수 지적0을 보고했고, 공개 결과 소비 호환 JVM8을 별도 실행했다. 이는 새 SQLite guard 실행이 아니다. D도 정확6a4의 SQL day 수정 코드를 수용했으며 원 SQL4의 동일 SHA와 ack 이후·commit 이전의 정확 day 대조/rollback 및 과거 결과 선재생을 확인했다. 두 부분 코드 수용은 A의 수정 후 SQLite GREEN이나 전체 Rest 최종 승인이 아니다. D 보고의 후속47은 원 E46에 F 추가1을 더하는 별도 시험 조합이며, 원 E46 source/APK/계획을 교체하지 않는다. 후속 A47의 실제 실행 결과와 후행 단언 도달은 다음 절에서 회수했다. C/D의 최종 근거 회수는 메인이 별도로 진행한다.


### Rest c28 시험 전용 후속의 실제 SQLite47 회수 — 2026-11-10

정확 `c28fc5eab368b8420f2cba908eb971225f39f081`은 제품부모6a4의698파일과바이트동일하며F원문 `RestDrivingMultiDepartureIndependentTest.kt` 1파일만추가한699파일조합이다. F파일SHA171a7d0155b95b7b93b93b1d04dbf51966ce34af66e539de26e3c2f1e7b70673·다섯정식시험원문/fixture/기대값이이전실행과같다. 기존main188 후보521f562를보존한후속브랜치에서이시험1만정상통합했고생산소스변경0이다. 이는#190의새Main을아직포함하지않은준비단계다.

A가Journal23/Ledger18/D SQL4/Cmetadata1/F복수departure1의고유47개를같은새쌍으로각첫실행해모두통과했다. 실패/오류/생략0·메서드terminal0·다섯그룹정상OK footer/INSTRUMENTATION_CODE -1·재실행0이다. 그룹시간은D4 2.970초/C1 1.063초/Journal23 10.537초/Ledger18 2.386초/F1 5.648초다. 원9db의C1FAIL/D3PASS1FAIL과과거F1PASS·원41·UTC JVM16을이47에합산하지않는다.

D원문day반례는이제STORAGE_FAILURE와후행12표rollback단언까지통과했다. C원문은DELETE각1행뒤receipt/current/zone모두STORAGE_FAILURE와후행12표행동일단언까지통과했다. 해당선택12표는account/settlements/driving_days/driving_drives/observed_driving_state/observed_driving_receipts/rest_module/rest_contexts/rest_departures/rest_driving_receipts/rest_log/rest_head이며전체DB모든표/파일바이트불변으로확대하지않는다. 손상metadata를복구한것이아니며tearDown뒤별도DB조회근거도만들지않는다. 원9db의첫단언실패/후행미도달은위과거기록으로보존한다.

F원문1은원carry1500ms/seed7원에서두departure anchor1/3·stop1/2를보관했다. 두번째DEPART INSERT만IGNORE한경우12표전체rollback,제거뒤동일명령의단일금융62원/boost3500ms/잔여500ms/최종잔액69·main정산1개를확인했다. 재개방/정의Failure뒤원main/seed전체결과와옛revision departure재생·추가정의조회0·행동일까지통과했다. 명시축소SIMULATION 1000ms운전/1000ms주차/2000ms기간·150%/1000perKm/cap500000m이며제품1h/5m/20m·장소/단가정책을바꾸지않았다.

격리restutcmultie쌍의앱80bf65e48da9dd5eb6743a04daab97904e64bdee700d90fbdd5fe71e2a94ad37·시험36977065ba4f7e1ba5c7b214593fb5b6fda884fffa4e4a62bcc8ad3ac98039e2는metadata/준비파일/실제설치pull SHA가같았다. 원E46의restutcfixe쌍/계획은그대로보존했고이번설치대상이아니다. A새빌드0이며작성자c28 APK준비1분5초/62tasks와부모569/Lint0/27/core420을새실행으로바꾸지않는다. 이로컬통합담당도기존main188 앱593검사이후Gradle/장치/ADB새실행0이다.

A단독슬롯은API34ARM64/user10에서22:38:37~22:40:00KST였다.19/19제품/환경보존·자기2패키지/프로세스/계측/추가forward0·반환/released/noFurtherAdb를회수했다. 제품데이터초기화·권한/기어/속도/GPS/창설정변경0이며실제기어D를새로요청/수신하지않았다. 실제SDK/place/Main/제품UI·프로세스/전원·실차는이저장시험의결과가아니다. 원문/source699·설치hash/XML/footer/반환은 /private/tmp/drive-rest-domain-utc-multi-regression-20261110/independent-device-review/에보존한다. 최종C/D회수와#190후Main부모포함·정확head조합검증/CI는게시담당의후속이며전체이슈완료를표시하지않는다.


### Rest schema16과 main190 제품 호스트의 최종 로컬 조합 — 2026-11-10

기존 후보3139204에 정확main190 `af2ee70dee24c7ad9310372255d4b20d5ed79ee3`을 정상 부모로 포함한 소스 merge는9d7e21e97c148a77c132ff08831ff66f4260b308이다. Rest42파일 모두c28원문과바이트동일하며 B18+ActualMainResults시험1/PNG2·Location6·기존공급을 보존했다. core/Gradle/Manifest도main190과같다. 생산소스충돌/임의추가수정0이고 문서3곳의동시추가는양쪽근거를보존해병합했다.

새조합에서 :app:testDebugUnitTest·assembleDebug·lintDebug·assembleDebugAndroidTest를오프라인/JDK17로실행했다. **앱612 전부PASS·실패/오류/생략0, 제품/시험APK·Lint오류0/경고27,1분42초/78tasks(25실행53UP-TO-DATE)**이다. RestTimeline13+D원문clock3=16은612에포함된다. core/공통입력이같아기존420은재사용/이번core재실행0이며 app593/main188과app543/B main188·E569를612에더하지않는다. 최종문서후속뒤에도제품/시험소스·빌드입력이실제검사한merge와같음을확인했다. 저장소필수21/문서29/링크455·diff/source동일성검사도통과했다.

C최종판정은 ACCEPT_SCOPED_METADATA_PUBLIC_READ_CODE_JVM8_AND_A_C28_C1_GREEN, D는 APPROVE_SCOPED_SQL_DAY_FIX_WITH_A_SQL4_GREEN이다. C/D는원A47의제품698동일·시험원문·설치hash/footer와각C1/D4후행12표단언GREEN을회수했다. D UTC16은기존정확source근거재사용이며새SQL47에합산하지않는다. 원9db실패와미도달·F명시축소SIMULATION·원E46쌍보존은앞절대로유지한다.

새결합본의장치/ADB실행은0이다. A의c28 SQLite47과B0dd Native65·별도ActualMainResults1은각원본/조합근거이며새main190+Rest제품APK의장치성공으로쓰지않는다. 원B0dd의실제SDK D→짧은이탈/복귀·P복귀와원marker소비는후속별도수동관측으로확인했다. 완료대여/첫선물·장시간종료수익·새Rest결합본장치검증, Rest장소/boot/domain/권위·프로세스/전원·실차·최종UI/디자인과전체이슈완료는별도다. mainCI42833687083은메인인계당시진행중인값으로만기록한다. 원격쓰기/canonical변경0이며정확게시head·PR CI/사람리뷰예외·main반영및이슈부분동기화는게시담당이확인한다.


### B0dd 원본의 실제 SDK 짧은 이탈·복귀 경계 회수 — 2026-11-10

정확 제품 `0dd969f37fd588a7cea60be2694b812de1ad099e`의 기존 격리쌍 `io.drivegrowth.verify.rentalproductb/.test`에서 A가 별도 수동 SDK 경계를 관측했다. 원691소스/제품·시험·fixture·기대값·Gradle/Manifest·가격/임계값 변경0, A새빌드0·JUnit0이다. 기존Native65/Results추가1/Rest47 및 새Rest/main190 앱612와 합산하지 않는다. **새Rest 결합APK의 장치 실행 결과가 아니다.**

공식 Virtual Console browser/session heartbeat를 연결해 bridge/emulator/DDS ready를 확인한 뒤 공식 gear/controls 요청과 실제 SDK 수신·원관측 DB·UI를 각각 대조했다. D정차는 실제0m/s·DRIVE·UX제한, 콘솔20km/h 요청 뒤 D이동은 실제 저장5.555555820465088m/s·열린원trip/영업marker 활성화를 확인했다. 실제포커스를입증한뒤 Back으로 자체UI가숨겨졌고 같은Activity/PID의D복귀에서도제한을유지했다. 이후 SDK PARK/0/UXfalse·원trip종료·HOME/시설복귀를확인했다. callback/mockclock·gate/UXoverride·VHAL주입·시스템서비스패치/재시작·재부팅/AVD초기화는하지않았다.

첫 시도는 windows dump의 포커스필드 부재로 Back 전에 안전중단하고 Park/controls0으로 원복했다. 두 번째는 displays dump의 실제 포커스를 확인해 목표 경계를 완료했다. 초기 UI 준비의 스크롤 중단과 같은 공개흐름 재개도 원보고에 남겼다. 이들을 제품 결함·정식JUnit 실패나 두 번의 성공으로 바꾸지 않는다.

원 `APPLICATION_EXIT_DRIVING` marker의 since/origin을복귀뒤유지했고, 숨김뒤command=null/done=false에서 같은원점from과최초수락SDK anchor의UTC/elapsed에정확히일치하는to를한번준비해done=true로소비했다. 원점→최초수신은양시계모두 **3,760ms**였고 P복귀뒤에도같은완료marker/명령이었다. observation_gap APP_HIDDEN2755ms는관측재시작기준이어서경제window3760ms와구분한다. 완료marker를금융성공으로표시하지않는다. 최종잔액1000·금융정산1(초기지급)·방문1·대여0·원관측주행영수증2이며완료대여/첫영업선물·장시간30분/8시간수익은미검증이다.

이번PID의driver_deferred=DATA_UNAVAILABLE2건은초기/복귀NO_DATA frame과대응했고후속실제D frame과같은marker완료를확인했다. 지속DDS불통/영구driver실패라고판정하지않는다. 다만같은error코드로identity미준비·미활성P/currentInactive·관측실패의정확callsite를모두구분한것은아니며새추적hook도넣지않았다. 원65의경고2/추가Results1의경고3과이번PID의경고2를같은로그로합치거나새금융성공을추정하지않는다.

준비/설치pull앱81bbefb1ebf7a61b95c2f7803d23130d26b456aa3ca4a108169e82200468ed14·시험bf94d969f3bae036aacfd76385dc2b3ea839c8bc14cf34109d80bed294fd2686는같았다. A 단독구간23:14:05~23:27:05KST/API34ARM64/user10·제품/환경19/19보존·자기권한/forward/heartbeat·공식Park/controls0/bridge비활성상태복원·자기2패키지정리·released/noFurtherAdb를회수했다. 신호요청전보호제품PID/서비스부재와매요청전6파일동일guard를확인했고제품데이터삭제/권한변경0이다. 실제원본 SDK의짧은이탈/복귀만확보했으며Activity재생성·프로세스kill·전원·장시간영업·실차/전체#51/#28완료로확대하지않는다.

원문은 /private/tmp/drive-product-sdk-inactive-boundary-a-20261110/REPORT.md·summary.json 및 device/actual-sdk-boundary-proof.json·test-results.json·preservation.json/phase DB·SDK로그에보존한다. 이문서담당은위근거를직접대조하고3760ms·같은origin/최초anchor일치·설치pullhash·19보존/반환을확인했으며새ADB/장치/JUnit/Gradle실행0·source42변경0이다. 기존새조합612/제품·시험APK/Lint0/27은제품/시험/빌드입력불변의실제선행실행근거로유지한다.


### 가게 꾸미기 순수 계약 검증 — 2026-11-10

F의 고정 patch SHA-256 `a73c305af177e575afe5236fdd83a6341bcc097297d19c84842ed985d8f126e1`의 신규 4파일을 그대로 공급한다. 기존 공통 계약·fixture·app·Gradle을 바꾸지 않았다. 기준 원본은 main188이며 새 공급본은 Rest schema16·main190 제품 연결을 보존한다.

작성자 검증은 원 420개에 신규 24개를 포함한 core 444개 통과다. A는 별도 ASCII 사본에서 원 24개와 독립 반례 4개, 총 28개를 실행해 실패·오류·생략 0과 필수 수정 0을 확인했다. 유료→무료→소유품 적용의 연속 동작, 무료 명령 종류가 다른 같은 ID 충돌, 정의 변경 뒤 소유권 적용, revision 상한 직전의 정상 확정/재생을 대조했다. A의 추가 시험 최초 컴파일 실패는 시험 코드의 nullable 접근 오류로 분리 보존했고 정식 원문 기대값이나 구현을 바꾸지 않았다. 원 24개는 444개에 포함되며 별도 실행 수를 합산하지 않는다.

무료 결과의 가짜 금융 정산 거절, 양수 구매 정산 누락·불일치 거절, 목록/집합의 외부 변경 방지와 실패 무변경은 순수 시험 근거다. SQLite 재개방·전역 요청 ID·원자 rollback·host checkpoint·실제 UI·장치/프로세스·#55 및 #28 전체 완료를 뜻하지 않는다. E의 저장 구현과 F의 UI 소비는 별도 후보로 검증한다. 원문/API/독립 실행 근거는 로컬 `/private/tmp/drive-decoration-contract-20261110/`에 보존했다.

새 Rest/main190 조합에서는 root가 core 444개·앱 612개 XML의 실패·오류·생략 0을 확인했다. 최초 전체 명령은 시험과 APK 이후 Lint에서 기존 Temurin17.0.6 ARM64의 C1 OSR 컴파일러 내부 오류(`assembler_aarch64.hpp:267`, `Field too big for insn`)로 종료됐다. 이 실행 전체를 성공으로 표시하지 않으며 crash/replay 로그를 로컬에 보존했다. 소스·의존성·공유 설정을 바꾸지 않고 이번 명령에만 `-XX:TieredStopAtLevel=1 -XX:-UseOnStackReplacement`를 적용해 재확인했다. 동일 명령은 1분28초에 성공했고 APK·Lint 오류0/경고27, 55개 작업 중5개 실행/50개 UP-TO-DATE였다. 기존 시험 XML 재사용을 새로운 1,056개 실행으로 합산하지 않는다. 저장소·문서 링크457개와 diff도 확인했다. 실제 기기 실행은 이번 순수 공급 범위에서0이다.


### 위치 영역 어댑터와 예외 회귀 검증 — 2026-11-11

D 원5파일 후보의 광역 UOE 처리에서 기존 무인자 start의 예상 밖 오류를 UNSUPPORTED로 숨기는 회귀를 B가 발견했다. 기준 main의 같은 반례2는 통과했고 원 후보는 예외 소실·원예외 대신 정리 예외 반환으로2개 실패했다. D는 원5파일을 보존한 별도 사본에서 제품1파일의4줄 추가/4줄 삭제로 포트 null만 명시 처리하고 광역 catch를 제거했다. B의 수명주기2·동일 예외2 원문을 포함한 정확7파일 combined patch는 `3da410afd454f3305fc332337d785047b9f755418e90d2b19f7938ba33e9404c`다.

D 본인 검증은 위치46개·전체core420/app546·APK/Lint 오류0/경고27이다. F는 별도 사본 전체688파일 동일성과 원문 시험을 대조하고 위치46개를50초에 직접 실행해 실패·오류·생략0·필수 수정0을 확인했다. 원42·추가4는46에 포함되며 별도 실행 수를 합산하지 않는다. root도 새main192 조합에서 원7파일의 작성자·F 사본과 바이트 동일성 및 F XML46개를 대조했다. 기존 위치 SDK의 실제 허용/거부 진단과 이번 JVM 영역 시험을 구분한다.

실제 SDK 영역·제품 장소 권위·Main/소비자 연결·원격 해제 완료 ACK·Rest 지급·프로세스/전원·실차는 미검증이다. 이 변경은 SDK 버전·Gradle/Manifest·data/core·Main/HostSession·게임 규칙을 바꾸지 않는다. close/start 캐시 무효화와 발생시각/현재inside Missing의 기존 한계를 유지한다. 원문 실패/수정·독립 근거는 로컬 `/private/tmp/drive-location-area-exception-fix-d-20261111/`와 `/private/tmp/drive-location-area-rereview-f-20261111/`에 보존했다.

최신 main192 조합의 root 검사는 앱634개·실패/오류/생략0, 제품APK·Lint 오류0/경고27로2분27초에 성공했다(53개 작업 모두 실행). 기존 core444는 입력이 같아 #192의 근거를 재사용하며 이번 신규 실행으로 세지 않는다. source7은 작성자/F 독립 사본과 바이트 동일하고 Main/HostSession·기존data/core/Manifest/Gradle을 보존했다. 새 결합본의 실제 장치 실행은0이다.


## 가게 꾸미기 schema17 저장 공급 검증

최신 결합의 기준 main은 SDK 영역 공급 PR #193의 `df3e6d5a51ce2b754ed004f8c19b8bbb8da867b3`이다. E의 고정 GREEN 원본은 부모 `c72c225e0c868dcaa8838250d73f6b2ee5348ef1` + full patch SHA-256 `0831efc81f88571071a2c942f68022fb7f7e8aa557b8a91d934a7b427938995e`로 식별한다. root는 이미 main에 있는 core4파일을 복제하지 않고 E 소유24파일만 적용했으며, 해당24와 core4의 SHA가 원본 manifest와 모두 같음을 확인했다. Main/HostSession·최종 디자인은 이 공급에서 변경하지 않는다.

| 확인 출처 | 실제 결과와 범위 |
|---|---|
| A 수정 전 실제 RED | 같은 시험 APK의 금융 원문1·UNKNOWN STARTED 무료/유료2가 정상 JUnit 실패3·오류0. 금융 balance 첫 단언 뒤 at/후행 구간과 UNKNOWN 실패 뒤 후행 단언은 미도달로 보존 |
| E 최소 수정 | 원본729파일 중 제품3파일만 후속 변경. 공통 금융 원문 canonical 대조, 공개 UNKNOWN STARTED 신규 확정 거절, 같은 거래의 정상 SAVED→STARTED만 내부 실행. 원27 시험·기대값·시험 APK 동일 |
| A 수정 후 실제 SQLite | money1 + UNKNOWN2 + 원24, 고유27개 모두 통과·실패/오류/생략0. 각각0.324초·0.432초·6.734초. balance/at 두 loop와 조회/재생 실패·5표 불변, 정의 조회0·전체 사용자표 불변·잔액1000·STARTED 보존의 후행 단언까지 확인 |
| F 독립 검토 | 고정729파일·726동일/제품3변경·기존 공개 ABI·A 원문/설치 해시/후행 단언 대조, 필수 수정0. JVM pending 경로8개 직접 통과·Android 원시험 컴파일. F의 SQLite 직접 실행은0 |
| root 최신 main 결합 | 앱 단위635개, 실패/오류/생략0. APK·시험 APK 빌드·Lint 성공, 오류0/경고27. 2분34초·78작업 모두 실행. core444는 동일 core의 PR #192 근거를 재사용하며 새 실행으로 세지 않음 |

A의 실제 설치 앱 SHA-256은 `6ffac6e4fa0bae025c806c547b559c0d2d43b603e8e18cdc9b0b9101a2947c6d`, 동일 RED/GREEN 시험 APK는 `10e2f4c329ee313eddf8b8ca0ad304963c840e9e9a98f4640d71658c48fcd0d5`다. 설치 후 pull 해시를 대조했고 2026-11-11 00:43:37~00:44:52 KST에 격리 패키지만 사용했다. 제품·설정19/19 보존, 자체 패키지/DB 정리·장치 반환을 확인했다. root/F는 이 공급에서 adb를 사용하지 않았다.

원24에는 무료 typed 결과/유료 공통 차감·원요청 재생·정의 미지원 복구·foreign slot/전역 ID·두 연결 한 승자·필수 쓰기 무삽입/늦은 손상 rollback·schema16 이전/실패 보존·schema17 누락표 거절이 포함된다. 별도 optional86은 실행하지 않았다. 새 결합본의 실제 기기 실행은0이며 원본27과 동일 APK로 표현하지 않는다. 실제 Main/Shop 미리보기·구매/적용·gate/Activity/프로세스·전체 기능/실차·#55/#28 완료는 남아 있다. 최종 아트 #189는 기능 구현 이후 적용한다.

로컬 원본 근거는 `/private/tmp/drive-decoration-read-fix-20261110/green/independent-device-review/`, F 검토는 `/private/tmp/drive-decoration-delta-review-f-20261111/`, 최신 결합 로그는 `/private/tmp/drive-decoration-storage-root-validation-20261111.log`에 보존한다. `JAVA_HOME`·`ANDROID_HOME`과 기존 cache를 환경 변수로 지정했고 Gradle `--offline --no-daemon --max-workers=1`, 명령 한정 JVM `-Xmx2048m -XX:TieredStopAtLevel=1 -XX:-UseOnStackReplacement -XX:ReservedCodeCacheSize=512m`, Kotlin in-process를 사용했다. 개인 경로 설정·키·APK·로그는 커밋하지 않는다. 저장소/링크·diff 검사와 원격 게시 결과는 PR에서 실제 확인 후 연결한다.


### #55 가게 꾸미기·확장 완료 원결과 조회 검증

원본은 부모 `b3f38c058c76512765655b84935e62749801d7b3` 위 reader와 시험이다. A가 Pleos API 34의 격리 앱에서 실제 SQLite 74개를 실행해 **73 통과·1 실패·오류/생략 0**을 확인했다. 신규 reader는 27/28, 기존 꾸미기·시설·checkpoint는 46/46이었다. 실패는 같은 요청 ID의 다른 종류 SAVED fixture에 필수 차량 Update가 없어 기존 validator가 reader 호출 전에 거절한 경우다.

동일 제품 APK와 fixture만 교정한 시험 APK로 A가 해당 메서드 한 개를 재실행해 **1 통과(0.219초), 실패/오류/생략 0**을 확인했다. 꾸미기·확장 reader가 각각 `STATE_CONFLICT`를 반환했고 사용자 테이블 행·SAVED 원명령은 그대로였으며 정의 공급자는 호출되지 않았다. 두 실행의 결과는 각각 보존한다. C의 후속 독립 검토에서 제품·fixture 필수 수정은 0건이었다.

최신 main `8eb1c5904b34ee7a3a7d07a21f24babe62c9ee1f`에 승인 소스와 교정 시험을 결합했다. 기존 장치 근거의 원제품과 통합 후보의 시험 결과를 구분한다. 이번 결합의 기기 실행, Main 소비, 제품 UI, 프로세스·전원 재실행 및 실차 검증은 미수행이며 #55 및 #28 전체 완료는 별도다.


JDK 17.0.6과 Android SDK 35에서 코어 단위 444개·앱 단위 674개(원결과 reader 7개 포함)가 실패·오류·생략 0으로 통과했다. 앱 APK·시험 APK 빌드와 Lint(오류 0·경고 27), 저장소 검사와 diff 검사도 통과했다.


## 전경 휴식 생산자 원입력 재시도 검증

검토 원본은 부모 `79e5d1458a60439007996bb9571eed230374930f` + source patch SHA-256 `9c376a582ed380a37b7fb6eb4f4abf9d78a1cb106c9afd9c54a779f184b0ae86`의 source723이다. root는 최신 꾸미기17 공급과 결합하며 소유5파일이 원본 SHA와 바이트 동일함을 확인했다. 기존 Main/HostSession/SDK/core/DB·규칙 수치는 변경하지 않았다.

| 출처 | 실행과 한계 |
|---|---|
| D 원반례·C 수정 | 원 previous recognition 복원으로 같은 입력 재시도 뒤 출발이 사라지는 R1 세 사례와 source가 다시 reduce해 drive ID를 바꾸는 R2를 원 기대값으로 보존. R1 원본28+추가4를 수정 후 통과 |
| F 독립 검토 | 원723·ABI/provider=null 본문·단일 슬롯/실패 구분과 직접 JVM32 통과. 제품 필수 수정0. DEVICE_PLAN의 기존3 DB prefix 누락1은 계획만 정정, source/APK/기대값 불변을 대조 |
| A 실제 수정 전 | 같은 시험 APK의 old source 비교1은 정상 JUnit 실패1·오류0, 1.904초. capture rollback·원 batch drive2·12표 보존 뒤 line98의 같은 exit 재시도가 STATE_CONFLICT. 후행 금융/재생 단언은 미도달 |
| A 실제 수정 후 | RestSourceRetryTest5 모두 통과(12.217초), provider=null 기존3 모두 통과(0.313초). 원drive2·ID2회·준비 batch provider 재호출 없음·원요청 ID, 금융15·소모100000ms/잔여1100000ms, 새 factory 재개방의 같은 원결과/receipt와12표 불변까지 확인 |
| root 최신 결합 | 앱667개, 실패/오류/생략0. APK·시험 APK·Lint 성공(오류0/경고27), 2분26초·78작업 모두 실행. 동일 core444는 기존 근거를 재사용하며 새 실행으로 세지 않음 |

A는 다른 frame/receiver/clock/pause와 실제 previous revision의 충돌/원제안 보존, provider 준비 실패 전 만든 ID 유지, capture 성공 뒤 금융 drain 실패에서도 다음 입력 capture와 fault 제거 후 원순서 재생을 확인했다. 이전 RED를 새 성공에 합산하지 않는다. 실제 source/helper/SQLite와 명시 SIMULATION 입력이며 실제 SDK·장소 권위·실차·제품 Main 연결·프로세스 사망 전 미저장 제안 복원은 시험하지 않았다.

계획 정정본 SHA-256은 `74400b30d873aa3e26a4e9a235a0b5b037c97225de0e488b65568f87dc2595b7`이다. 새5의 `rest-source-retry-<source>-<nano>.db`와 기존3의 `driving-source-fixture-<source>-<nano>.db`를 모두 명시하고 이전 계획을 보존했다. 동일 시험 APK는 `07249435f97b1f76f13a5f355753f74c5ab3a925c9b7d2ee81146bd1b37c1c51`, 설치 pull old app은 `8cd900134866021faa2e1d38e3edcf2ac2358727abbe0ea6d329da571f587e8a`, new app은 `030efd3f7a88fd71df7dde3a1a68b9112a8d9a1bca84ef90dd2d3ea1289d609b`다. A 장치 사용은 2026-11-11 01:23:23~01:25:37 KST, 환경/제품19/19 보존·자체 DB/패키지 정리·슬롯 반환을 확인했다. root/F/C는 이 실제 실행에서 adb를 사용하지 않았다.

원본은 `/private/tmp/drive-rest-producer-retry-fix-20261111/independent-device-review/`, F 독립 대조는 `/private/tmp/drive-rest-retry-review-f-20261111/`, root 결합 로그는 `/private/tmp/drive-rest-retry-root-validation-20261111.log`에 보존한다. 최신 main 결합 APK의 실제 장치 실행은0이며 A 원본 쌍과 구분한다. Main의 원입력 보관·명시 등록/활성화·현재 SDK/장소 증거·Rest UI 및 #40/#51/#28 전체는 남는다. #189는 기능 구현 뒤 적용한다.


## #46 공통 차량 계약의 최신 main 재확인 — 2026-11-11

사용자의 공통 차량 계약 확장 요청을 최신 main `8eb1c5904b34ee7a3a7d07a21f24babe62c9ee1f`에서 다시 대조했다. #46 본문·공통 계약 요청과 후속 회신, PR #100의 조회 범위, PR #104 최종 head `9c32bb38c93b4f219a2cfcc4113c0cd1c8776db8`·머지 코드·CI와 최신 PRD를 직접 확인했다. 요청한 개체 희귀도·진행량·실제 취득액/출처, 단일 공통 Rarity, Add/Update·요청 동일성·당시 결과 재생 및 공통 fixture는 이미 공급돼 있어 재구현하지 않았다. 기존 소비 코드·테스트·빌드 설정도 수정하지 않았다.

02의 「#34 저장 확장의 연결점과 기존 데이터 조건」과 #46 요약에서 #116/#140/#149의 이력·발견·mapper 공급을 미제공으로 읽을 수 있는 과거 안내를 바로잡았다. 필드·단위·오류·실행 예제의 기준은 [기존 계약 절](02_SYSTEM_ARCHITECTURE.md#46-개체-정보-v1과-공통-소비-예제)이며 새 모델이나 별도 원장을 만들지 않았다. 기존 PR #104 검증과 아래 새 실행은 구분한다.

독립 worktree의 `codex/46-vehicle-contract-current-20261111`에서 Temurin JDK17.0.6·기존 Android SDK35/Build Tools35·Gradle Wrapper8.11.1로 실행했다. SDK·JDK는 환경변수로 지정했고 의존성·개발환경 설치나 에뮬레이터·adb 사용은 없었다.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest \
  --offline --no-daemon --max-workers=1 \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:-TieredCompilation'
python3 scripts/check_repository.py
git diff --check
```

| 실제 검사 | 결과·적용 범위 |
|---|---|
| 전체 Gradle 명령 | BUILD SUCCESSFUL, 3분33초, actionable 80개 모두 실행 |
| `:core:test` | 444개 통과, 실패·오류·생략0. `OwnedVehicleContractTest`11개·`GarageTest`18개·`VehicleUpgradeTest`11개·`GachaPullTest`12개·`FirstGrantTest`6개 포함 |
| 계약 회귀 입력 | 동일 차종/다른 희귀도·성장, 무료0/할인250/유료500/구매탁송800, 범위/최대레벨, 외부 목록 변경 방지, 생성→조회→성장→처분의 취득 정보 보존, 같은 요청 재생·새 필드 차이 충돌·실패 무변경을 기존 정식 테스트로 재실행 |
| `:app:testDebugUnitTest` | 667개 통과, 실패·오류·생략0. 앱 소비자 컴파일 포함, 실제 Android SQLite 실행 아님 |
| `:app:assembleDebug :app:lintDebug` | 성공. Lint 오류0·기존 제품 경고27. 빌드의 SDK XML 버전 경고와 deprecated API 경고는 숨기지 않았으며 도구 버전을 바꾸지 않음 |
| `:app:assembleDebugAndroidTest` | SQLite 개체 codec·원래 정산·legacy 거절 관련 시험을 포함한 장치 시험 APK 컴파일 성공. 장치 실행 없음 |

결과 근거는 같은 worktree의 `core/build/test-results/test/`·`app/build/test-results/testDebugUnitTest/` XML과 `app/build/reports/lint-results-debug.xml`이다. 저장소·링크 검사와 `git diff --check`도 통과했다. 문서 두 파일 이외의 소스·Gradle·Manifest 변경은 없다.

**남은 경계:** 이번 재확인은 공통 계약·메모리 동작·현재 소비자 자동 검사까지다. PR #104의 과거 SQLite26개 장치 실행은 기존 근거로만 참조하며 새로 실행했다고 세지 않는다. 탁송 후보·변경 후보는 표시 확인 뒤 발견 키를 저장하도록 main에 연결돼 있다. 이번에는 같은 세션에서 도감이 갱신되는지, 저장 실패 뒤 원래 표시 목록으로 재시도하는지, 제품 앱/프로세스 재실행 뒤 도감과 개체 상태가 일치하는지 실행하지 않았다. 가챠 지급 이력은 보유 칸에 반영되며, 결과 표시 이벤트의 발견 기록은 #52에서 별도로 다룬다. 필수 정보 없는 legacy 개체/명령/정산의 근거 기반 복구·이전 정책과 기능 간 동시 변경 검증도 남는다. 실제 무료0과 정보 누락을 구분하는 명시 실패를 유지하고 기존 DB·앱 데이터를 삭제하지 않았다. #46 전체 완료·#33/#34 재개방·담당/ready/Blocked by 변경 없이 현재 PR 검토와 기존 #28 통합 검증으로 인계한다.
