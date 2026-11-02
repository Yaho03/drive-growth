# Android / Pleos 로컬 개발 환경 확인 기록

확인일: 2026-11-03 KST

## 현재 상태

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

## 남은 검증

- 앱 APK 빌드·설치·실행과 화면 검증은 앱 구현 담당 범위다.
- 앱 프로세스가 차량 속도·기어·주행 제한 이벤트를 실제로 수신하는지 확인해야 한다.
- 모의 차량 신호 입력 후 앱의 주행·정차 상태 전환을 확인해야 한다.
- 실차 API 지원과 권한 승인 여부는 에뮬레이터 부팅 성공으로 증명되지 않는다.

이 기록은 **SDK 설치, Pleos AVD 부팅, 차량 서비스 존재 확인**까지의 근거이며, 앱 기능 또는 실차 동작 검증 완료를 주장하지 않는다.
