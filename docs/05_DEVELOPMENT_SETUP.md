# 개발 기반·버전 기준과 로컬 환경 기록

현재 설정 확인일: 2026-11-04 KST. 아래 과거 설치·조사 기록은 각 수행 시점을 따른다.

## 개발 기반과 버전 기준

기술 책임·선택 이유·게임 기획에 따른 결정 대기는 [기술 설계](02_SYSTEM_ARCHITECTURE.md#개발-기반-결정), 공식 Pleos SDK 조사 버전은 [개발 자료 2절](contest/02_PLEOS_DEVELOPMENT.md)에서 관리한다. 이 절은 실제 설정과 팀이 사용할 기준의 차이를 설명한다. 현재 설정은 저장소 파일, 설치 이력은 아래 기록, 실제 통과 결과는 [검증 문서](06_VERIFICATION.md)가 근거다.

- **문서 기준 채택:** 앞으로 적용·검증할 권장 조합을 정했다. 설정 파일 반영이나 앱 실행 성공을 뜻하지 않는다.
- **현재 적용값:** 실제 저장소 설정에 남아 있는 값이다. 권장값과 다르면 적용 대기다.
- **결정 대기:** 게임·정책·환경 조건이 더 필요한 항목이다.

### 호환성을 고려한 권장 조합

2026-11-03 재작성. **Kotlin/KGP 2.2.21 + AGP 8.10.1 + Gradle 8.11.1 + JDK 17**을 문서상 빌드 기준으로 채택한다. 기존 조합을 그대로 권장하거나 Kotlin만 바꾸는 방식 대신, KGP·AGP·Gradle·Android 컴파일러의 조건을 함께 대조했다. 공모전에서 정한 의무 버전이 아니라 프로젝트의 기술 선택이다. 당시에는 설정 변경·설치·실행 전의 결정이었다. 현재 설정과 실행 결과는 이 문서의 현재 적용값 및 06 검증 문서에서 구분한다.

| 구성 | 현재 적용값·기록 | 문서상 권장 기준 | 상태·적용 이유 |
|---|---|---|---|
| 언어·플랫폼 | Kotlin / Pleos Connect·AAOS | 유지 | 차량용 Android 앱 기반 채택. 게임 엔진 선택과 별개 |
| Kotlin / KGP | `2.2.21` | **`2.2.21`** | 2026-11-04 설정 반영. 아래 AGP·Gradle이 KGP 완전 지원 범위에 들어오는 조합 |
| Android Gradle Plugin(AGP) | `8.10.1` | **`8.10.1`** | 2026-11-04 설정 반영. Kotlin 2.2 계열의 Android 빌드 조건과 패치 릴리스의 R8 수정 반영을 함께 고려 |
| Gradle Wrapper | `8.11.1` | **`8.11.1`** | 2026-11-04 설정·공식 배포 SHA-256 반영. AGP 8.10의 최소·기본 버전. Wrapper 버전과 배포 SHA-256을 함께 갱신함 |
| JDK·JVM 대상 | Java 17, `jvmTarget = 17`, `jvmToolchain(17)` | **17 계열 유지** | AGP 요구와 일치. IDE 실행용 JBR과 Gradle JDK는 구분. 실제 사용할 17 패치 빌드는 환경 준비 시 기록 |
| 컴파일 API | `compileSdk = 35` | **35 유지** | AGP 8.10은 API 36까지 지원하므로 범위 안. 별도 요구 없이 컴파일 API까지 올리지 않음 |
| 최소 API | `minSdk = 28` | **28 유지** | Pleos의 최소 28 이상 조건 충족. 모든 API 28 차량에서의 기능 보장은 아님 |
| 대상 API | `targetSdk = 34` | **초기 개발 기준 34 유지** | Pleos API 34 환경에서 동작을 확인할 기준. 최종 제출·마켓 정책에 따른 변경 여부는 재확인 |
| Android Build Tools | CI 설치·로컬 기록 `35.0.0` | **35.0.0 유지** | AGP 8.10의 최소·기본 버전과 일치. 현재 앱 설정에서 명시 고정된 값은 아님 |
| 첫 차량 시험 환경 | Pleos Emulator `2.1.0`, API `34` | **유지** | 공식 Pleos 안내와 기존 부팅 기록 기준. SDK 연동·화면 실행까지 확인한 것은 아님 |
| Android Studio | 팀 공통 버전 미기록 | **Rabbit 2 / 2026.2.2** | 공식 stable 및 AGP 지원표를 대조한 IDE 기준. 로컬 설치 완료, IDE 첫 실행·팀 장비 검증은 미수행. 자동 AGP 업그레이드 제안으로 위 기준을 바꾸지 않음 |
| 단위 테스트 | JUnit `4.13.2` | **유지** | 기존 로직 검증 기반. UI·기기 테스트 기술은 별도 선택 |

`compileSdk`는 컴파일할 API, `minSdk`는 설치 가능한 최소 API, `targetSdk`는 대상으로 삼는 Android 동작 기준, 에뮬레이터 API는 시험 기기의 실제 OS다. 새 빌드 조합을 사용한다고 차량 OS가 바뀌지는 않는다. API 35의 `android.car.jar`를 참조하는 코드는 API 34에 있는 메서드인지 확인하고 미지원·누락 처리를 검증한다.

### 선택 근거와 기존 조합의 차이

| 대조 대상 | 공식 근거 | 판단 |
|---|---|---|
| Kotlin ↔ AGP·Gradle | [KGP 호환표](https://kotlinlang.org/docs/gradle-configure-project.html): KGP 2.2.20–2.2.21은 Gradle 7.6.3–8.14, AGP 7.3.1–8.11.1을 완전 지원 범위로 제시 | 권장 AGP 8.10.1·Gradle 8.11.1이 모두 포함됨 |
| AGP ↔ Gradle·JDK·SDK | [AGP 8.10 릴리스](https://developer.android.com/build/releases/agp-8-10-0-release-notes): Gradle 8.11.1, JDK 17, Build Tools 35.0.0, 최대 API 36. 8.10.1의 R8 수정도 안내 | 권장 조합과 compileSdk 35가 범위 안 |
| Kotlin ↔ Android 컴파일러 | [D8/R8 조건](https://developer.android.com/build/kotlin-support): Kotlin 2.2 행의 R8 기준 8.10.21. [AGP 8.10.1에 대응하는 R8 고정 소스](https://r8.googlesource.com/r8/+/797269e8b303243d4ef999fa1817bd64a1f2de0b/src/main/java/com/android/tools/r8/Version.java#16)의 버전은 8.10.24 | 요구 버전 이상. 공식 `agp-8.10.1` 태그로 대조했으며 POM의 별도 R8 의존성을 확인했다는 뜻은 아님. D8/R8를 앱 의존성으로 강제 추가하지 않음 |
| IDE ↔ AGP | [Android Studio 릴리스·호환표](https://developer.android.com/studio/releases): Rabbit 2 / 2026.2.2는 stable이며 AGP 7.1–9.5 지원 | AGP 8.10.1 포함. IDE의 내장 JBR 버전과 Gradle용 JDK 17 선택은 별도 확인 |
| Pleos 개발 조건 | [공식 개발환경](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator): Java/Kotlin, Gradle 8.0 이상, 최소 API 28 이상, Emulator 2.1.0 / API 34 | 공개된 기본 환경 조건 충족. Pleos가 이 전체 버전 조합을 개별 인증했다는 뜻은 아님 |

기존 KGP 2.0.21의 AGP 완전 지원 표 상한은 8.5여서 당시 AGP 8.8.2는 표 밖이었다. 이것은 **현대차 공모전의 사용 금지나 실제 빌드 실패 판정이 아니다.** 해당 조합은 과거 설정 기록으로 보존하고, 앞으로의 권장 조합과 구분한다. KGP 2.1.21도 AGP 상한이 8.7.2이므로 Kotlin만 소폭 올리는 방식으로 이 차이가 해결된다고 쓰지 않는다.

이번 선택은 위 지원 범위의 교집합을 바탕으로 한다. AGP 9나 최신 Kotlin 계열로 함께 이동할 별도 필요는 확인하지 않았다. 기존 Kotlin을 유지하려고 AGP를 낮추는 안은 compileSdk 35의 지원 조건까지 다시 바꿔야 하므로 기본안으로 택하지 않았다.

### 적용 순서와 실행 검증의 완료 조건

다음은 공통 적용·재현 순서다. 2026-11-04 로컬 설정 적용·빌드·저장 시험은 수행했고, 팀별 IDE·기기와 정책 검증은 남아 있다.

1. 환경 준비 시 루트 `build.gradle.kts`의 Android·Kotlin 플러그인과 Gradle Wrapper를 위 조합으로 함께 맞춘다. Wrapper 배포 파일의 공식 SHA-256을 확인하고, IDE·터미널·CI가 JDK 17을 쓰는지 대조한다. 개별 도구만 임의 업그레이드하지 않는다.
2. 의존성 해석·동기화에서 Kotlin 메타데이터·JVM 대상·AGP 요구 충돌을 확인한다. 경고를 숨기거나 버전 검사 우회로 통과 처리하지 않는다.
3. 실행할 앱 소스가 준비되면 기존 `./gradlew :core:test :app:assembleDebug :app:lintDebug`로 검사한다. 소스·Manifest 부재로 생략·실패한 결과와 버전 호환 실패를 구분한다.
4. 필요한 Pleos SDK를 연결한 뒤 API 34 에뮬레이터에서 설치·실행·서비스 연결·차량 콜백을 확인한다. 적용한 SDK·차량 Service 버전과 실패 로그를 기록한다.
5. 정산·저장·UI·주행 정책 검증은 해당 기능이 합의·구현된 뒤 별도로 수행한다. Release 서명·최적화 구성 변경 시 해당 빌드도 재검증한다.

2026-11-03 결정 당시 결론은 **문서상 도구 호환 조건 대조 완료 / 권장 조합 결정 / 설정 미적용 / Pleos 앱 빌드·실행 미검증**이었다. 2026-11-04 현재 설정 반영과 실행 결과는 아래 현재 공통 실행 절차 및 06 문서를 따른다. SDK의 실제 메타데이터·의존성이나 차량 환경에서 추가 제약이 발견되면 근거를 남겨 이 조합을 조정한다. UI·게임 엔진·저장·서버·음성 흐름의 미정 항목은 기술 설계를 따르며 이번 버전 결정으로 확정하지 않는다.

### Pleos 연동과 버전 확정 조건

Maven 저장소 주소는 등록돼 있지만 앱에는 아직 Pleos Vehicle·음성·내비게이션·위치 SDK 의존성이 선언돼 있지 않다. **공식 문서에서 조사한 버전과 프로젝트에 적용·검증한 버전은 다르다.** SDK별 숫자·자료 불일치는 [개발 자료 2절](contest/02_PLEOS_DEVELOPMENT.md)을 기준으로 삼아 중복 관리하지 않는다.

- Vehicle·AAOS 차량 API는 기록 기능의 검증 대상으로 삼되 실제 사용할 속성·권한·Capability와 런타임 전달을 확인한다.
- TTS·STT·NaviHelper·FusedLocation·Gleo는 기능별 필요성과 정책·인증·승인 조건을 확인한 뒤 연결한다. 미연결 상태는 기능 제외가 아니다.
- 에뮬레이터 이미지 버전, SDK 라이브러리 버전, 차량 내 Service 버전을 따로 기록한다. 빠른 시작 예제의 버전과 상세 SDK 문서가 다르면 동일하다고 가정하지 않는다.
- 다운로드·Gradle 동기화, 컴파일, 권한·서비스 연결, 실제 콜백·화면·저장 결과를 단계별로 확인한다. 첫 단계 성공을 마지막 단계 성공으로 표시하지 않는다.

이 절의 2026-11-03 조사 시점은 **문서 대조 완료 / 설정 변경 없음 / 새 빌드·앱 실행 미수행** 상태였다. 아래 설치·부팅·실패 이력은 보존하며 새 버전 조합의 검증 근거로 대체하지 않는다.

## 권장 도구의 로컬 설치 확인

2026-11-03 사용자 요청으로 권장 도구를 프로젝트의 `.local-tools`에 준비했다. 위 권장 기준 결정 당시의 미설치 기록과 구분한다. 제품의 Gradle 설정은 그대로이며 앱 구현·APK 빌드·Pleos 연동은 수행하지 않았다.

| 항목 | 로컬 결과 |
|---|---|
| Gradle 8.11.1 | 공식 ZIP의 SHA-256 확인 후 `.local-tools/distributions/gradle-8.11.1`에 설치. Java 17.0.6으로 `--version` 실행 성공 |
| Kotlin/KGP 2.2.21·AGP 8.10.1 | `.local-tools/toolchain-check`의 독립 확인용 Gradle 프로젝트에서 플러그인 다운로드·해석과 `help` 성공. 제품 앱의 컴파일·SDK 연동 검증은 아님 |
| 기존 도구 | JDK 17.0.6, Android Platform 35·Build Tools 35.0.0·ADB 설치 유지. Pleos 이미지 재설치 없음 |
| Android Studio Rabbit 2 | 공식 Mac ARM DMG SHA-256 일치, `.local-tools/ide/Android Studio.app`에 설치. 제품 정보 `AndroidStudio2026.2.2` 확인. 최초 복사본 서명 오류 후 원본 이미지 검사·속성 보존 재복사를 수행했고 최종 `codesign --verify --deep --strict` 통과. 보안 우회·재서명 없음. IDE 첫 실행·설정 마법사·프로젝트 열기는 미수행 |

Gradle의 `--version`에 표시되는 내장 Kotlin 2.0.20은 Gradle 자체 스크립트용이며 앱에 사용하는 KGP 2.2.21과 다르다. 2026-11-03 설치 직후 원래 프로젝트는 Kotlin 2.0.21·AGP 8.8.2·Wrapper 8.10.2를 가리켰다. 2026-11-04 현재 적용값은 위 표를 따른다. 새 도구를 내려받은 것만으로 제품의 버전이 전환되지 않는다. 확인 로그는 `.local-tools/toolchain-check/verification.log`에 있고 로컬 도구·캐시는 Git 대상이 아니다.

## 팀 공통 실행 절차

2026-11-04 권장 KGP·AGP·Wrapper 조합을 실제 프로젝트 설정에 반영했다. 새 팀원은 저장소의 해당 설정을 기준으로 사용하며 IDE의 자동 업그레이드 제안으로 임의 변경하지 않는다.

1. JDK 17과 Android Studio를 준비하고 Gradle JDK를 17로 지정한다. IDE 실행용 JBR과 구분한다.
2. Android SDK의 Platform 35·Build Tools 35.0.0·Platform Tools를 설치하고 `ANDROID_HOME` 또는 개인 `local.properties`의 `sdk.dir`로 SDK를 지정한다. SDK 경로·캐시·개인 설정은 Git에 넣지 않는다.
3. 저장소 루트에서 아래 검사 명령을 실행한다. Wrapper가 공식 Gradle 8.11.1 배포의 SHA-256을 검증한다. Maven/배포 네트워크 접근이 필요하며 우회 인증이나 비밀정보 공유를 하지 않는다.
4. Pleos 이미지·AVD는 공식 개발 자료와 승인된 사용 조건에 따라 준비한다. `adb devices -l`로 기기를 확인하고 시험 대상 시리얼을 명시한다.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

첫 명령은 핵심 테스트·APK 컴파일·Android Lint이며 두 번째 명령은 연결된 Android 시험 기기가 필요하다. 저장 테스트 실행을 새 렌터카 기능·주행 정책 적합성 검증으로 취급하지 않는다. 공식 SDK·차량 콜백·주행 제한 시험은 #13, 새 기능 통합은 #28의 별도 범위다.

팀원 네 명의 공통 명령줄 빌드 재현 근거를 [#12](https://github.com/Yaho03/drive-growth/issues/12)에 연결해 환경 확인을 완료했다. 팀원별 OS·CPU와 검사 범위는 #12의 근거를 따른다. IDE 설치·실행, Pleos AVD·차량 SDK·실차 시험은 실제 수행자와 환경별로 [검증 문서](06_VERIFICATION.md)와 해당 기능 이슈에 기록한다.

### 별도 모의 시연 Debug APK

JDK 17과 기존 Android SDK를 사용해 `./gradlew :app:assembleDebug`로 빌드한다. 기본 Debug 산출물은 `app/build/outputs/apk/debug/app-debug.apk`, 패키지는 `io.drivegrowth.app.simulation`이다. 시연 원장 `unified-simulation.db`와 진행 기록은 이 패키지에 저장된다. Release는 `io.drivegrowth.app` 패키지와 차량 입력을 사용한다.

시연 화면의 **시연 · 모의 입력**과 **SIMULATION** 표시는 게임에 공급하는 모의 차량·내비 입력을 뜻한다. 시연 중 발생한 게임 기록은 시연 원장에만 남으며 차량을 조작하지 않는다. 이 절차는 고정 시연 목적지의 도착을 재현한다. 실제 SDK로 주행 후 목적지를 변경하는 경로는 현재 시연에서 지원하지 않는다.

1. 기기 담당과 실행 시간을 조율하고 `SERIAL`에 시험 장치 시리얼을 지정한다. 시연 APK를 설치한 뒤 런처의 **드라이브 키우기 · 모의 시연**을 연다. 두 번째 명령으로도 같은 화면을 열 수 있다.

   ```sh
   adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s "$SERIAL" shell am start -n io.drivegrowth.app.simulation/io.drivegrowth.app.simulation.UnifiedSimulationActivity
   ```

2. 첫 실행이면 **첫 차량 고르기**에서 차량을 선택한다. **선택 내용 확인** → **이 차량으로 시작**으로 첫 지급을 확정하고 **가게로**를 누른다.
3. **탁송**에서 **시연 목적지 확인**을 누른다. **수익 탁송** 의뢰를 골라 **수익 탁송 선택 확정**을 누르고 선택 결과를 확인한다.
4. **시연 · 모의 입력 ▸**을 펼쳐 **모의주행 시작**을 누른다. 모의 주행 1시간이 논리 시각으로 자동 진행된다. 주행 중에는 게임 조작이 제한되므로 모의 주차가 끝날 때까지 기다린다. **같은 모의 입력 다시 시도**가 표시되면 그 버튼을 누른다.
5. 주차 후 **탁송**에서 완료 결과를, **미확인 정산**에서 수익 내역을 확인한다. **보유 차량**에서 첫 차량을, **가게**의 **시설**에서 각 시설의 상태와 해금 조건을 확인한다.
6. 새 프로세스에서 저장 결과를 확인하려면 시연 패키지만 종료하고 1단계의 실행 명령으로 다시 연다. 앱 데이터를 유지한 채 탁송 결과의 확인 대기 여부와 정산 기록을 확인한다. 이미 열람한 정산은 **전체 기록**에서 확인할 수 있다.

   ```sh
   adb -s "$SERIAL" shell am force-stop io.drivegrowth.app.simulation
   ```

재실행 로그를 대조할 때는 `DriveGrowthSimulation`의 `simulation_input`과 `simulation_terminal_restored`를 같은 논리 시각으로 비교한다. 각 시험의 범위는 [시연 검증 기록](06_VERIFICATION.md#통합-모의주행-debug-검증--2026-11-11)을 따른다.

## 기존 설치 상태

사용자가 **Android SDK와 Pleos/42dot 라이선스, 대용량 이미지 다운로드 및 설치를 명시적으로 승인**했다. Platform 35, Build Tools 35.0.0, Platform Tools, Command-line Tools, ARM64 에뮬레이터를 설치했다. Pleos 이미지의 크기·SHA-1 검증과 설치를 마쳤고, 전용 AVD의 부팅 완료 및 차량 서비스 실행을 확인했다.

## 확인한 로컬 환경

- 호스트 아키텍처: `arm64` (`uname -m` 실행 결과).
- Java: `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
- Java 실행 확인: Temurin OpenJDK **17.0.6+10** (`java -version` 성공).
- `~/Library/Android/sdk`, `/opt/android-sdk`, `/opt/android`, `/private/tmp/android-sdk`, `/private/tmp/sdk`는 발견되지 않았다.
- 설치된 Unity Editor 세 버전의 `AndroidPlayer/SDK` 경로에도 SDK가 없었다.
- `~/opt`에는 Anaconda가 있었고 Android SDK는 확인되지 않았다.
- 실행 중인 adb 경로 확인을 위한 `ps`는 현재 권한에서 거부되었다. 실행 중 프로세스가 없다고 판단한 것은 아니다.

## 작업 공간에만 적용되는 경로

| 용도 | 경로 |
|---|---|
| SDK 설치 예정 | `.local-tools/android-sdk` |
| Android 사용자 설정 | `.local-tools/android-user` |
| AVD | `.local-tools/avd` |
| 다운로드 및 공식 피드 | `.local-tools/downloads` |
| 현재 셸용 환경 설정 | `.local-tools/env.sh` |
| 전용 ADB 서버 | TCP `5038` (`ANDROID_ADB_SERVER_PORT`) |
| 전용 AVD | `codex_pleos_drive_spike` |
| 기기 식별자 | `emulator-5556` |
| 실행 스크립트 | `.local-tools/start-pleos.sh` |

작업 공간 루트에서 `source .local-tools/env.sh`로 설정한다. 사용자 홈 설정 파일은 수정하지 않는다. Android 빌드에 필요한 SDK는 설치되었다. Pleos AVD는 아래 검증 기록의 진행 상태를 따른다.

## 공식 메타데이터 확인

다음 두 피드를 실제 다운로드하여 읽었다.

- [Google Android 패키지 피드](https://dl.google.com/android/repository/repository2-3.xml): `.local-tools/downloads/android-repository2-3.xml`
- [Pleos ARM64 시스템 이미지 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml): `.local-tools/downloads/crp-arm64-sys-img.xml`

macOS ARM64에 해당하는 stable 패키지를 선택한 URL·크기·SHA-1·라이선스 ID는 `.local-tools/downloads/package-plan.json`에 기록했다. 이 메타데이터에 해당하는 여섯 아카이브의 SHA-1과 크기를 모두 검증했다.

| 패키지 | 피드에서 확인한 버전 / 크기 |
|---|---|
| Android Platform 35 | revision 2, 64,273,788 bytes |
| Build Tools 35.0.0 | macOS, 76,857,898 bytes |
| Platform Tools | 37.0.1, macOS, 16,110,554 bytes |
| Command-line Tools latest | 23.0, ARM64 빌드 16111833, 155,384,151 bytes |
| Android Emulator stable | 37.2.13, ARM64 빌드 16740729, 416,113,219 bytes |
| Pleos Connect | v2.1.0, revision 2, API 34, ARM64, 4,669,762,816 bytes |

**문서와 피드의 시점 차이:** Android Studio 다운로드 웹페이지에는 CLI 빌드 `15859902`가 표시되었으나, 직접 가져온 패키지 피드에는 `16111833`이 표시되었다. 설치 시에는 실제 선택한 버전을 다시 기록해야 한다. `avdmanager`는 현재 Java 17에서 실행됨을 확인했다. CLI 23의 `sdkmanager`는 새 Android CLI로 연결되며 `--licenses`는 더 이상 필요하지 않다고 안내한다. 공식 도구로 Platform 35를 등록했고 API 35 대상이 목록에 표시됨을 확인했다.

Pleos 이미지 식별자:

```text
system-images;android-34;pleos_car_emulator;arm64-v8a
https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_arm64_emulator.zip
SHA-1: 24d28eb2f18eba5e87c861a7eb2bdccebb049419
```

## 라이선스 확인 지점

- [Android 다운로드 페이지](https://developer.android.com/studio)의 SDK 라이선스는 사용 전 동의를 요구하며, SDK 사용 자체가 동의로 간주된다고 설명한다.
- Pleos 피드에 포함된 `pleos-connect-sdk-license` 역시 설치 또는 사용을 동의로 간주한다. 라이선스 마지막 갱신일 표기는 2024-01-02다.
- 사용자 승인 전에는 SDK 다운로드·설치·실행과 동의 파일 생성을 진행하지 않았다. 승인 후에는 피드의 해당 라이선스 원문 SHA-1을 SDK `licenses/`에 기록했다. Android 공식 관리 도구 실행 시 약관 고지도 확인했다. 이후 Android CLI 명령에는 `--no-metrics`를 사용했다.

## 실제 설치·기동 검증 결과

| 확인 | 결과 |
|---|---|
| 전체 다운로드 크기·SHA-1 | 여섯 아카이브 모두 일치 |
| Java 17의 avdmanager | 실행 성공, API 35 대상 인식 |
| adb | 37.0.1 실행 성공 |
| emulator | 37.2.13 실행 성공 |
| 하드웨어 가상화 | `emulator -accel-check` 결과 0, Hypervisor.Framework 사용 가능 |
| AVD 생성 | `codex_pleos_drive_spike` 생성 성공 |
| 기기 연결 | `emulator-5556` 상태 `device` |
| 부팅 완료 | `getprop sys.boot_completed` 결과 `1` |
| 시스템 API | `getprop ro.build.version.sdk` 결과 `34` |
| 차량용 기능 | `pm list features`에 `android.hardware.type.automotive` 존재 |
| CarPropertyService | `dumpsys car_service`에서 실행 및 속성 구독 목록 확인 |
| CarUxRestrictionsManagerService | 실행 확인; 조회 시 display 0의 UxR 값 0 |
| Pleos 서비스 | Vehicle, Navi, FusedLocation 서비스 패키지 설치 확인 |

컴파일용 Car API JAR:

```text
.local-tools/android-sdk/platforms/android-35/optional/android.car.jar
```

`javap`로 `CarPropertyManager.registerCallback/getProperty`, 속도·기어·주차브레이크 프로퍼티 상수, `CarUxRestrictionsManager`의 제한 상태 조회·리스너 메서드를 확인했다. API 35에 새로 추가된 메서드를 사용하면 API 34 런타임에서 동작하지 않을 수 있으므로 호출별 호환성은 구현에서 확인한다.

런타임에서는 `/system/framework/android.car.jar` 파일이 없었으며, 대신 다음 모듈 파일을 확인했다.

```text
/apex/com.android.car.framework/javalib/android.car-module.jar
```

## 설치 과정에서 확인한 차이와 처리

### Pleos 피드와 ZIP 내부 메타데이터

공식 피드는 `system-images;android-34;pleos_car_emulator;arm64-v8a`, revision 2를 제시하지만, ZIP 안의 `source.properties`는 `SystemImage.TagId=android-automotive`, `Pkg.Revision=1`로 되어 있다. 피드 기준 경로에서 AVD 생성 도구가 경로 불일치를 보고했다.

현재 설치는 아카이브의 메타데이터를 그대로 유지하고, 도구가 인식하는 다음 경로를 사용한다.

```text
.local-tools/android-sdk/system-images/android-34/android-automotive/arm64-v8a
system-images;android-34;android-automotive;arm64-v8a
```

AVD 목록에는 `Automotive (42dot Inc.)`, Android 14 / ARM64로 표시된다. 피드로부터 직접 구성한 임시 `package.xml`은 도구가 인식하지 않아 제거했으며, 아카이브의 실제 메타데이터를 기준으로 AVD를 생성했다. 이후 공식 배포 메타데이터 수정 여부를 확인할 필요가 있다.

### 공식 SDK 관리 도구 등록

수동으로 압축을 푼 Platform 35와 Emulator를 공식 새 Android CLI의 `sdk install`로 등록하여 패키지 메타데이터를 정상화했다. 도구가 해당 아카이브를 다시 내려받았으므로 실제 네트워크 전송량은 최초 여섯 아카이브 합계보다 많다.

### 실행 권한

제한된 프로세스에서 최초 `emulator -version`은 Qt의 neon 기능 검사에 실패했다. 권한 승인된 프로세스에서 `-no-window -version`, 가상화 검사, 실제 부팅은 성공했다. 부팅 로그는 `.local-tools/pleos-emulator.log`에 있다.

## 사용 방법

작업 공간 루트에서:

```sh
source .local-tools/env.sh
adb -s emulator-5556 devices
adb -s emulator-5556 shell getprop sys.boot_completed
```

이미 실행 중인 전용 AVD를 중복 실행하지 않는다. 종료 후 다시 실행할 때는:

```sh
.local-tools/start-pleos.sh
```

스크립트는 해당 AVD만 사용하고, 화면 없이 실행하며, 종료 시 스냅샷을 저장하지 않는다. 사용자 데이터 초기화 옵션은 사용하지 않는다. 기본 실행 메모리는 4096MB이고 AVD 생성 프로필은 `automotive_1080p_landscape`다. 실제 부팅 로그의 초기 화면 설정은 1080×600 / 120dpi였다. 최종 제품 화면 사양과 일치하는지 별도로 확인한다.

## 화면 시험과 기존 부팅 기록의 구분

2026-11-03 조사 보완: 위 `start-pleos.sh`의 화면 없는 실행은 부팅·서비스 확인용 기록이다. **시각 UI 검증이나 FormFactorConsole 시험 완료가 아니다.** 공식 폼 팩터 시험은 별도 창으로 실행한 Emulator와 Host Display Bridge를 사용하며 Headless·Android Studio 내장 Running Devices 창은 해당 시험에 사용할 수 없다. 실제 실행 옵션·AVD 이름은 시험 착수 시 확인한다. 이번 문서 보완에서 스크립트나 AVD 설정을 바꾸지는 않았다.

차량 상태 웹 콘솔(8088), SDK 샘플 앱, 화면 크기 시험 도구는 목적이 다르다. 설치·포트·종료 조건은 [공식 자료 목록](contest/04_OFFICIAL_RESOURCES.md) 6절과 [Virtual Console 안내](contest/02_PLEOS_DEVELOPMENT.md) 5절을 기준으로 사용한다. 이미 기록된 headless 부팅 성공을 이 시험들의 성공으로 재분류하지 않는다.

2026-11-13에는 별도 창의 AVD와 공식 Host Display Bridge·FormFactorConsole로 Portrait·Landscape를 실행했다. 현재 설치용 Debug APK의 직접 설치와 관측 범위는 [공식 창 실행 결과](06_VERIFICATION.md#공식-세로가로-창과-표준-debug-apk-직접-설치--2026-11-13)를 따른다. 기본 화면의 강제 회전·task 크기 변경을 공식 프리셋 시험으로 대신하지 않는다.

## 남은 검증

- 기존 APK 빌드·설치·기본 실행은 2026-11-04 확인했다. 새 게임 화면·기능과 정책 검증은 후속 구현 범위다.
- 앱 프로세스가 차량 속도·기어·주행 제한 이벤트를 실제로 수신하는지 확인해야 한다.
- 모의 차량 신호 입력 후 앱의 주행·정차 상태 전환을 확인해야 한다.
- 실차 API 지원과 권한 승인 여부는 에뮬레이터 부팅 성공으로 증명되지 않는다.

이 기록은 **SDK 설치, Pleos AVD 부팅, 차량 서비스 존재 확인**까지의 근거이며, 앱 기능 또는 실차 동작 검증 완료를 주장하지 않는다.


### 이 컴퓨터의 NFC 작업 경로 컴파일 한계 — 2026-11-09

#121 통합 시험에서 native worktree의 물리적 NFC 디렉터리 아래 core JAR을 Kotlin fixture가 읽지 못했다. 동일 SHA JAR의 ASCII/NFD 물리 경로 import 시험은 통과했으나 NFD 철자만 바꾸거나 ASCII symlink를 거치는 것으로는 해결되지 않았다. 한글 경로 전체나 다른 OS의 결함으로 일반화하지 않는다. 제품/Gradle 설정을 바꾸지 않고 정확 커밋의 ASCII 실행 사본과 원본 app/core·빌드 설정의 바이트 동일성을 확인해 검증했다. 상세 실행·한계는 [검증 기록](06_VERIFICATION.md#121-재료-참조수리초기화-계약-통합-검증--2026-11-09)을 따른다. 원본 폴더·AVD·DB 삭제나 이름 변경을 해결책으로 적용하지 않는다.
