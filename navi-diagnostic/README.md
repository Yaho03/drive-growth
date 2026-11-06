# Navi 콜백 진단 #80

Pleos NaviHelper의 실제 콜백을 관측하는 별도 진단 앱이다. 제품 앱의 게임 기능이나 도착 판정을 구현하지 않는다. [#80](https://github.com/Yaho03/drive-growth/issues/80)은 정원 담당으로 열어두며, 이 도구와 시험 결과만으로 이슈 전체를 완료 처리하지 않는다.

## 다운로드와 버전

게시 위치는 [#80 진단용 사전 배포](https://github.com/Yaho03/drive-growth/releases/tag/issue80-diagnostic-20261106)다. PR 게시 과정에서 아래 APK와 증거 묶음을 첨부하며, 링크의 실제 게시 여부는 해당 Release에서 확인한다.

- [정상 권한 APK](https://github.com/Yaho03/drive-growth/releases/download/issue80-diagnostic-20261106/navi-followup-permitted.apk)
- [권한 미선언 시험 APK](https://github.com/Yaho03/drive-growth/releases/download/issue80-diagnostic-20261106/navi-followup-no-permission.apk)
- 공개 발췌 로그와 화면: [evidence/](evidence/). 상세 결과·환경·남은 문제는 [#80 검증 기록](../docs/06_VERIFICATION.md#80-내비-모의주행권한재탐색렌더링-추가-검증--2026-11-06)을 따른다.

| 항목 | 값 |
|---|---|
| 앱 이름 | Navi 콜백 진단 #80 |
| `applicationId` | `io.drivegrowth.navidiagnostic` |
| 버전 | `0.1.1-issue80-followup` / `versionCode=1` |
| SDK | NaviHelper 3.0.5 |
| Android | minSdk 28 / targetSdk 34 / compileSdk 35 |

시험 APK의 SHA-256은 다음과 같다. 다운로드한 파일을 대조한다.

```text
e67c2580dd75ba23780c0544fe12fadbd8ee5d0854bdf6d81b7fa0d60286ab1f  navi-followup-permitted.apk
8c5faa0802c1b67be4622b9cd7e6e2d3167a47bf5f33b6ae4661da0a87554a95  navi-followup-no-permission.apk
```

기존 도착 2회는 `0.1.0-issue80` APK(`32fe31a755321534ba4978d852f0b0200651a9561cc933b597b6d919d56bc585`)로, 추가 1회는 위 정상 권한 후속 APK로 시험했다. 현재 소스는 후속 버전이다. 기존 APK와 결과를 후속 버전에서 모두 재현한 것으로 표현하지 않는다.

## 설치와 실행

Pleos Connect 2.1.0 / API 34에서 시험했다. 기존 AVD를 다른 시험과 동시에 조작하지 않는다. 아래 값은 시험 환경 예시이며 자신의 SDK·ADB 포트·기기·활성 사용자로 맞춘다. `ANDROID_HOME`은 자신이 설치한 Android SDK 경로로 설정한다.

```bash
ADB_BIN="${ANDROID_HOME:?ANDROID_HOME에 Android SDK 경로를 설정}/platform-tools/adb"
ADB_PORT=5038
DEVICE_SERIAL=emulator-5556
ANDROID_USER_ID=10

"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" shell am get-current-user
"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" install -r --user "$ANDROID_USER_ID" navi-followup-permitted.apk
"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" shell am start --user "$ANDROID_USER_ID" \
  -n io.drivegrowth.navidiagnostic/.MainActivity
```

제품 패키지 `io.drivegrowth.app`과 별개다. 두 진단 APK는 **같은 패키지·버전 코드·서명**을 사용하므로 서로 교체 설치한다. 기록을 보존하려면 삭제하지 않고 `-r`로 교체한다. 서명 불일치 오류가 나면 데이터 삭제로 우회하지 말고 기존 설치본·서명을 확인한다.

앱 실행만으로 SDK 연결·경로 요청은 발생하지 않는다. 연결 버튼을 누르면 `permissionSnapshot`에 권한 상태가 기록된다. 정상 APK는 Manifest에 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`를 선언한다.

## 도착 콜백 재현

1. **1. SDK 연결**을 누르고 `onConnected`와 `listenerRegistered`를 확인한다.
2. **2. 현재 목적지·경로·내비 위치 조회**로 기존 경로를 확인한다.
3. **3. 2초 조회 시작 / 중지**로 위치·거리 관측을 시작한다. 조회만 반복하며 자동 경로 요청은 하지 않는다.
4. 진단 앱에서 경로를 설정할 때만 **4. 시험 경로 요청: 42dot**을 한 번 누른다. 기존 내비 경로에 영향을 준다. 입력은 시흥 POI와 일치하는 위도 `37.41289678`, 경도 `127.09383464`, 주소 `수정구 시흥동`이다. 내비가 실제 채택한 목적지도 대조한다.
5. 내비로 전환해 경로 생성과 안내 시작을 확인한다. 실제 `ROUTE_DRIVE`와 **안내 종료** 화면이 나타난 뒤 설정 톱니바퀴를 약 1.5초 길게 누르고, 일반 **주행 시뮬레이션** 패널에서 **시작**을 누른다. 비밀번호 개발자 메뉴는 사용하지 않는다. 1080×600 시험 화면의 톱니 `(1006,440)`, 시작 약 `(766,334)`는 참고값이며 현재 화면을 먼저 확인한다.
6. 위치·남은 거리 변화와 `source=SDK_CALLBACK`, `event=onDestinationArrived`, `staleConnection=false`를 확인한다. 도착 payload의 실제 목적지, 직전 경로 ID·상태·시각을 함께 대조한다.
7. 시험 후 진단 앱으로 돌아와 **3번**으로 조회를 중지하고 **6. SDK 해제**를 누른다. 6번은 내비 경로를 취소하지 않으므로 활성 경로·모의주행도 별도로 종료한다.

**5. 경로 취소**는 취소 시험용, **7. 현재 활성 경로 재탐색**은 재탐색 시험용이다. 다른 시험 중에는 누르지 않는다. 현재 설치 버전에서는 재탐색 요청 전달 후 완료를 확인하지 못했다.

내장 모의주행을 입력으로 사용한 실제 SDK 콜백 시험이다. 실차 이동·Android GPS 연동·제품의 탁송 정산 검증을 뜻하지 않는다. `DestinationArrivedInfo`에는 경로 ID가 없고, 앱이 기록하는 요청 ID도 SDK가 보장한 응답 연결 ID가 아니다. 경로 탐색 완료·거리 0·P 기어·`onRouteEnded`만으로 도착 성공을 판단하지 않는다. 지정 버전의 `isRouting`도 활성 안내 판정에 단독 사용하지 않는다.

## 권한 미선언 시험과 복원

정상 APK에서 기존 기록을 보존한 뒤 권한 미선언 APK로 교체한다.

```bash
"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" install -r --user "$ANDROID_USER_ID" navi-followup-no-permission.apk
```

앱을 다시 실행해 연결·조회·경로 요청을 선택하고 권한 검사 `-1`, 해당 호출의 `SecurityException`을 대조한다. SDK 연결 성공만으로 권한이 있다고 판단하지 않는다. 이는 normal 권한의 **Manifest 미선언** 시험이며 사용자 런타임 거부 대화상자 시험과 다르다.

시험 후 정상 권한 APK로 `-r` 교체하고 다시 실행해 두 권한 검사 `0`과 실제 조회·경로 콜백을 확인한다. 이전 `0.1.0-issue80` 상태로 되돌려야 하는 시험이라면 교체 전 그 APK를 별도로 보존한다. 위 다운로드는 후속 버전 두 종류만 제공한다.

## 로그 회수

로그 태그는 `DG80Navi`다. 앱이 숨겨져도 Activity가 살아 있는 동안 리스너·조회는 유지하지만 프로세스 지속 실행은 보장하지 않는다. 화면의 run ID를 기록하고 종료·해제 시각까지 확인한다.

```bash
"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" \
  logcat -v raw -s DG80Navi:I '*:S' > navi80-logcat.txt
```

Logcat은 `run`·`seq`별 `part` 순서로 합쳐야 한다. 분할하지 않은 JSONL은 진단 앱의 `files/navi-<runId>.jsonl`에 있다. 다음은 사용자 10 예시다. [`run-as`의 사용자 지정 문법](https://android.googlesource.com/platform/system/core/+/refs/heads/android14-release/run-as/run-as.cpp)을 사용하므로 사용자 0의 데이터 경로를 하드코딩하지 않는다.

```bash
RUN_ID='화면에서 확인한 run ID'
"$ADB_BIN" -P "$ADB_PORT" -s "$DEVICE_SERIAL" \
  exec-out run-as io.drivegrowth.navidiagnostic --user "$ANDROID_USER_ID" \
  cat "files/navi-$RUN_ID.jsonl" > "navi-$RUN_ID.jsonl"
```

새 시험 로그에는 실제 위치·목적지가 포함될 수 있다. 공유 전 개인정보·인증정보를 확인하고 시험에 필요한 발췌만 게시한다. 파일 기록 오류가 표시되면 JSONL만으로 무수신을 판단하지 말고 Logcat도 확인한다.

## 그래픽 조건과 남은 제한

Apple M1 Pro에서 동일 AVD의 GPU 옵션만 `swiftshader`에서 `host`로 바꿨을 때 지도 바탕·마커·경로 표시가 개선됐다. 원래 `swiftshader`로 복원하면 검정이 재현됐다. 다른 GPU·장비·장시간 안정성은 미검증이며, 도로·건물 지도 제공이나 Android GPS 반영까지 해결한 결과는 아니다.

이 대안 조건을 시험할 때는 실행 중인 기존 AVD의 인수를 먼저 보존하고, 같은 AVD를 중복 실행하지 않는다. cold boot·RAM·포트 등 나머지 조건을 유지한 채 `-gpu host` 하나만 바꾸고 시험 후 원래 조건을 복원한다. [공식 GPU 옵션](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics)과 [검증 기록](../docs/06_VERIFICATION.md#80-내비-모의주행권한재탐색렌더링-추가-검증--2026-11-06)을 따른다.

## 소스에서 빌드

저장소 루트에서 JDK 17과 프로젝트의 Android SDK 설정을 사용한다. NaviHelper는 Gradle의 공식 Maven 의존성으로 받으며 SDK JAR·디버그 서명키는 배포하지 않는다.

```bash
./gradlew :navi-diagnostic:testDebugUnitTest \
  :navi-diagnostic:assembleDebug \
  :navi-diagnostic:assembleNoNaviPermission \
  :navi-diagnostic:lintDebug
```

출력은 `navi-diagnostic/build/outputs/apk/debug/`와 `navi-diagnostic/build/outputs/apk/noNaviPermission/`에 있다. 재빌드 결과는 빌드 환경·서명키 등에 따라 위 시험 APK와 해시가 다를 수 있다. 자신의 APK 해시·소스 커밋·서명을 따로 기록하고, 재빌드 성공을 SDK 실행 성공으로 표시하지 않는다.
