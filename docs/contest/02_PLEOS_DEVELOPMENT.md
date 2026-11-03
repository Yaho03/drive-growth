# Pleos Connect 개발환경·음성·위치·Gleo 자료

- 최초 확인: **2026-11-02 KST**. **2026-11-03 OT·SDK 신청·권한 안내 및 주행 중 음성 정책 대조 보완**.
- 대상: 차량의 Pleos Connect 환경에 설치·실행하는 앱. 스마트폰 화면을 차량으로 투사하는 앱을 전제로 하지 않는다.
- 이 문서는 공식 공개 문서를 읽고 정리한 자료다. SDK 다운로드, 계정 생성, 권한 신청, 에뮬레이터 실행, 실차 테스트를 수행했다는 의미가 아니다.
- 공모전 접수 요건과 Pleos App Market 배포·권한 승인 요건은 서로 다른 절차다. 공모전 개발 단계의 사전 승인 면제 안내와 남아 있는 문서 차이는 아래 2절에서 관리한다.
- 차량 데이터 API와 주행 정책의 상세 내용은 별도 문서에서 다룬다. 여기서는 개발환경, 음성, 위치, Gleo 연동에 집중한다.

## 1. 플랫폼과 문서의 관계

Pleos Connect SDK는 Android Automotive OS(AAOS) 기반 차량용 인포테인먼트 앱 개발 도구다. 공식 문서는 Android API와 Pleos Connect API를 함께 사용하며, 에뮬레이터에서 차량 컨텍스트 및 제어 기능을 테스트할 수 있다고 설명한다.

| 구성 | 용도 |
|---|---|
| Vehicle SDK | 차량 상태 조회 및 차량 제어 |
| NaviHelper SDK | 차량 내비게이션과 경로·목적지·경유지 연동 |
| TextToSpeech SDK | 텍스트를 합성 음성으로 출력 |
| SpeechToText SDK | 음성을 텍스트로 변환 |
| FusedLocation SDK | 차량 위치 데이터 접근 |
| Gleo Interface | Gleo AI의 음성 요청을 앱의 기능 호출 및 응답과 연결 |

출처: [Pleos Connect SDK 개요](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)

## 2. 확인한 버전과 제공 상태의 불일치

조사에서 확인한 2026-10-19 릴리스 기준 버전은 아래와 같다. 실제 개발 시에는 다운로드하는 패키지와 실행 이미지의 버전을 다시 맞춰야 한다.

아래는 공식 자료의 조사 버전이다. 우리 프로젝트의 빌드 도구 기준·현재 적용값·호환성 미확정 항목은 [개발환경 기준](../05_DEVELOPMENT_SETUP.md#개발-기반과-버전-기준)에서 관리한다. 조사표를 실제 의존성 연결 완료 목록으로 사용하지 않는다.

| 구성 | 문서·릴리스 확인 버전 |
|---|---|
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |
| TextToSpeech SDK | 3.3.1 |
| SpeechToText SDK | 3.3.1 |
| FusedLocation SDK | 1.1.3 |
| Pleos Connect Emulator | 2.1.0 |

음성·위치·에뮬레이터 버전은 아래 각 공식 상세 문서에서도 명시한다. 차량·내비게이션 세부 내용은 별도 조사 문서를 참고한다.

2026-11-03 추가 확인: [Getting Started](https://document.pleos.ai/docs/pleos-only/getting-started)의 Vehicle 의존성 예제는 `2.3.1`, 하위 [Importing Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)는 `2.3.2`다. 빠른 시작 예제의 버전을 최신으로 가정하지 않는다. 첫 예제는 도어 조회이며 완성된 앱 생명주기 구현이 아니다. 비동기 작업 종료 전 SDK를 해제하지 않도록 안내한다.

### 공모전 OT에서 확인한 개발 조건

2026-10-28 OT는 **Vehicle·NaviHelper·STT·TTS·FusedLocation을 공모전 개발 단계에서 별도 사전 승인 없이 필요한 Manifest 권한을 선언해 사용할 수 있다**고 안내했다. App Market 배포에는 승인·심사가 별도로 적용된다. [32:29~33:14](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1949s), [38:28~38:42](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2308s).

이는 운영기관의 사전 승인에 관한 안내다. FusedLocation의 실행 중 권한 요청, STT 서버 모드의 앱 등록·인증, SDK 초기화와 API별 지원 확인을 생략할 수 있다는 뜻은 아니다. 각 조건은 아래 SDK별 절을 따른다. Gleo Schema 승인까지 면제됐다고 확대하지 않는다.

[25:56](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1556s)에는 아직 공개하지 않은 SDK도 공모전에 제공할 예정이라고 설명했으나 구체 목록·배포일·접근 성공은 확인되지 않았다. [37:56](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2276s)에는 외부 API·공용 네트워크 연결이 가능하다고 답했다. 이는 특정 외부 데이터 제공이나 사용권 보장이 아니다.

### 일반 포털 안내와 실제 가용성의 차이

[프로젝트 생성 및 SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project) 페이지는 다음과 같이 안내한다.

| 신청 안내상의 상태 | 해당 SDK |
|---|---|
| 신청 없이 사용 가능 | Vehicle State, NaviHelper |
| 준비 중 / 신청 불가 | FusedLocation, TextToSpeech, SpeechToText, Vehicle Control |

이 신청 안내는 2026-11-03 재확인 시에도 같은 상태다. 반면 TTS·STT·FusedLocation 상세 문서에는 의존성·권한·API·에뮬레이터 사용법이 있고, OT는 위 개발 조건을 안내했다. **자료 간 설명 차이가 남아 있으며 공모전 특례인지 일반 포털 갱신 지연인지 확인되지 않았다.** [공모전 FAQ](https://appdevchallenge.auto-hmg.io/faq)와 [개발 안내](https://appdevchallenge.auto-hmg.io/guide/development)도 다섯 SDK를 열거하면서 현재 상태는 SDK 문서를 확인하도록 안내한다.

따라서 현재 확인 가능한 결론은 다음과 같다.

- API가 문서화되어 있다는 사실은 확인했다.
- 신청 안내의 준비 중 문구만으로 해당 API가 현재 절대 사용 불가라고 확정하지 않는다.
- 상세 문서가 있다는 이유만으로 모든 참가 계정·실차·에뮬레이터에서 즉시 사용 가능하다고 확정하지 않는다.
- 공모전 개발의 사전 승인 조건은 OT 안내를 근거로 구분한다. 각 SDK 배포본·인증·실행 권한·에뮬레이터 지원은 개별 검증해야 하며 이 문서 조사로 실행 성공을 주장하지 않는다.

## 3. 계정·프로젝트·SDK 신청

일반 포털은 Pleos Playground 가입 → 프로젝트 생성 → 필요한 SDK 상태 확인 및 해당 시 사용 신청 → 개발환경 구성 순서를 안내한다. 이를 공모전 개발의 일괄 사전 승인 대기로 적용하지 않는다. 공모전 개발과 배포의 구분은 위 2절을 따른다.

프로젝트를 만들면 팀 멤버 관리와 SDK 사용 신청을 할 수 있다. 프로젝트 이름은 나중에 변경 가능하며, 개발사명·카테고리·앱 설명을 입력하도록 안내한다.

| 프로젝트 정보 | 용도 |
|---|---|
| 프로젝트 ID(Key) | 앱 심사를 요청할 때 Pleos App Market Console에 입력 |
| Client ID / Client Secret | TTS·STT 등 서버 연결을 요구하는 SDK의 앱 등록 |
| CRN | Emulator 2.1.0 이후에는 입력 불필요 |

SDK 상태에는 준비 중, 사용 가능, 신청 필요, 검토 중, 반려됨 등이 있다. 제휴 계약 필요 상태는 웹 신청 절차가 아니라 담당자와의 별도 계약 절차를 안내한다.

출처: [프로젝트 생성 및 SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project)

## 4. 개발 도구와 다운로드 경로

### 4.1 개발환경

| 항목 | 공식 안내 |
|---|---|
| 앱 최소 Android SDK | API 28 이상 |
| Gradle | 8.0 이상 |
| 언어 | Java, Kotlin |
| 개발 도구 | Android Studio |
| 에뮬레이터 이미지 | Pleos Connect 2.1.0, Android API 34 |
| Virtual Console 지원 최소 이미지 | Pleos Connect Emulator 2.1.0 |
| AVD 메모리 설정 | RAM 4GB |
| 부팅 설정 | Cold boot |

앱의 최소 지원 API 28과 에뮬레이터 이미지 API 34는 서로 다른 설정이다.

출처: [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

### 4.2 공식 자료·패키지 경로

아래는 다운로드·설정에 사용할 공식 경로를 모아둔 것이다. 이 문서를 작성하며 파일을 실제로 내려받거나 설치하지는 않았다.

| 자료 | 링크·주소 | 사용 방식 |
|---|---|---|
| 개발 자료·에뮬레이터 포털 | [Resources](https://developers.pleos.ai/playground/discover/resources) | 공식 개발 자료 진입점 |
| Android Studio | [Android Developers](https://developer.android.com/studio) | 개발 도구 설치 |
| Mac용 에뮬레이터 업데이트 피드 | [ARM64 시스템 이미지 XML](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml) | Android Studio SDK Update Sites에 등록 |
| Windows/Linux용 업데이트 피드 | [시스템 이미지 XML](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml) | Android Studio SDK Update Sites에 등록 |
| Pleos SDK Maven 저장소 | [Maven releases](https://nexus-playground.pleos.ai/repository/maven-releases/) | Gradle 저장소로 등록 |

TTS 문서에 명시된 의존성은 `ai.pleos.playground:TextToSpeech:3.3.1`이다. 다른 SDK의 의존성 이름과 버전은 해당 SDK의 Importing 페이지를 기준으로 설정해야 한다.

출처: [에뮬레이터 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator), [TextToSpeech SDK 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk)

### 4.3 설치 흐름과 주의할 설정

1. Android Studio의 SDK Manager에서 SDK Update Sites에 운영체제에 맞는 피드를 추가한다.
2. SDK Platforms에서 API 34의 Pleos Connect system image를 설치한다.
3. Android Studio 재시작 후 Device Manager에서 Automotive / Pleos Connect v2.1.0 가상 기기를 생성한다.
4. Cold boot와 RAM 4GB를 설정하고 API 34의 Pleos Connect 이미지를 선택한다.
5. 에뮬레이터를 실행하고 Virtual Console을 연결한다.

공식 설정 문서는 **Quick boot에서는 VHAL을 이용할 수 없으므로 Cold boot가 필수**라고 명시한다. STT 테스트에는 Extended Controls의 Microphone 설정도 필요하다. 문서가 다루는 버전은 AVD Bluetooth 동글 연동을 지원하지 않는다.

그래픽 사양 안내에는 NVIDIA/AMD 외장 GPU 기준이 제시되어 있다. Mac ARM64 설치 경로도 별도로 존재하지만, 이 자료만으로 특정 Mac 모델의 원활한 실행을 보장할 수는 없다. 팀의 실제 개발 장비에서 실행 확인이 필요하다.

출처: [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)

## 5. Virtual Console로 가능한 개발·시연

**OT의 34:33 구간에 소개된 웹 차량 조작 도구는 Pleos Connect Virtual Console이다.** 공개 개발 포털인 [Pleos Playground](https://developers.pleos.ai/playground/discover/resources)와 역할이 다르다. 브라우저 UI는 PC에서 실행 중인 Pleos Emulator에 연결하며, 웹사이트만 접속해 차량용 앱을 실행·개발하는 클라우드 환경으로 안내된 것은 아니다.

Virtual Console은 브라우저에서 차량 상태를 변경하고, 에뮬레이터 및 앱의 반응을 확인하는 도구다. 실제 차량을 움직이지 않고 기능을 검증하거나 데모 시나리오를 재현하는 데 사용하도록 안내한다.

에뮬레이터 실행 후 `adb forward tcp:8088 tcp:8088`을 설정하고 브라우저에서 `http://127.0.0.1:8088/`에 접속한다.

| 영역 | 안내된 시뮬레이션 예 |
|---|---|
| Motion | 기어, 브레이크, 스티어링 휠 각도, 주행 거리 |
| ADAS | 크루즈 준비·활성 상태와 목표 속도 |
| Convenience | 도어·창문·트렁크, 시트, 조명, 와이퍼 |
| Safety | 주차 브레이크, 안전벨트, ABS 등 경고등 |
| Energy | 주행 가능 거리, 배터리 용량, 충전 상태 |
| Climate | 공조 전원, 온도, 풍량, 열선·통풍 등 |

설정 가이드는 차량 속도와 상태 변경, 반복 테스트 및 데모 실행도 명시한다. **에뮬레이터에서 해당 상태를 변경할 수 있다는 사실과 특정 실차에서 앱이 그 데이터를 읽거나 제어할 권한을 갖는다는 사실은 구분해야 한다.**

출처: [Virtual Console 셋업](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [Virtual Console 사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)

### 웹 조작 순서와 입력의 의미

2026-11-03에 [Virtual Console 셋업](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)과 [사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)의 Vehicle·Motion·ADAS·Convenience·Safety·Energy·Climate 전체 표를 확인했다. 아래는 기획 관련 입력의 요약이며 모든 카드를 복제한 표는 아니다.

1. 실행 중인 Emulator 2.1.0 이상과 ADB 연결을 확인하고 위 8088 포워딩을 적용한다.
2. PC 브라우저에서 로컬 주소를 열고 Domain 또는 카드 검색으로 항목을 찾는다.
3. 필요한 경우 차량 영역을 선택한 뒤 목록·버튼·슬라이더를 조작한다.
4. 콘솔 표시값과 앱이 실제 받은 조회·콜백·단위를 각각 대조한다.

| 입력 카드 | 문서상 범위 | 앱 데이터와 구분할 점 |
|---|---|---|
| Gear Selection | P·R·N·D | 정차와 주차 전환 시험에 활용 가능 |
| Brake Pedal | 0–100% | 페달 입력값과 SDK의 Boolean 작동 상태는 같은 자료형이 아님 |
| Odometer | 0–1,000,000km, 1km 단위 | 미터 단위의 세밀한 거리 갱신 시험을 보장하지 않음 |
| Steering Angle | −540~540°, 1° 단위 | 스티어링 휠 각도 설명과 SDK 앞바퀴 조향각 정의를 동일시하지 않음 |
| Cruise Target Speed | 5.556~27.778m/s | **크루즈 목표 속도이며 실제 차량 속도가 아님** |
| Turn Signal State | Off·좌/우 Blink·좌/우 Flash | 방향지시 상태가 차로 변경 완료를 증명하지 않음 |
| Telltales | 주차 브레이크·안전벨트·ABS 등 | 경고등 입력과 실제 주차 판별 신호를 구분 |

셋업 문서는 P 상태에서 Highway/Forest 테마 변경과 Highway의 1·3인칭 시점을 안내한다. 테마 변경은 차량 테스트 기능에 영향을 주지 않는다고 설명한다. 연결 문제는 이미지 버전·ADB·포워딩·8088 포트 충돌을 확인한다.

**남은 확인:** 셋업 소개는 속도 변경을 언급하지만 상세 카드 표에서 별도의 실제 차량 속도 조작 카드를 확인하지 못했다. 정확한 속도 입력 UI·자동 주행 시나리오는 실제 도구에서 확인해야 한다. 연결된 시연 GIF는 이번 도구에서 본문을 확인하지 못했다. 이번 보완은 문서 조사이며 콘솔 실행·차량 값 변경·앱 수신 시험을 수행한 결과가 아니다.

Vehicle SDK Sample App은 AVD 내부 Android 앱이고, FormFactorConsole + Host Display Bridge는 화면 크기 시험 도구다. 세부 검증 조건은 [공식 자료 목록](04_OFFICIAL_RESOURCES.md) 6절에서 관리한다.

## 6. TextToSpeech: 차량 음성 출력

TTS v3.3.1은 Speech Service에 텍스트 합성을 요청하여 차량 오디오 시스템으로 출력한다.

| 모드 | 동작 | 서버 앱 등록 |
|---|---|---|
| HYBRID | 서버 합성 우선, 네트워크 연결 실패 시 온디바이스 전환 | 서버 합성 사용 시 필요 |
| SERVER | 서버 합성만 사용; 연결 실패 시 오류 | 필요 |
| ON_DEVICE | 차량 내부 엔진 사용, 네트워크 불필요 | 불필요 |

기본 모드는 HYBRID다. `pleos.car.permission.TTS_SERVICE` 권한 선언이 필요하다. 객체 생성 및 `initialize()` 후 이벤트 리스너와 필요한 로케일을 설정한다. 서버 모드는 등록·연결 성공 이후 합성을 요청한다.

- `speak(text)`: 음성 합성·재생 요청.
- `isFinal`: 여러 텍스트 조각을 하나의 발화로 보낼 때 마지막 조각 여부 지정.
- `stop()`: 진행 중인 합성과 재생 중지.
- 이벤트 리스너: 준비·시작·완료·중지·음량 변화·오류 확인.
- 사용 종료 시 리스너를 제거하고 `release()`로 자원 해제.

게임 안내나 휴식 권유에 연결할 수 있는 출력 수단이 문서화되어 있다. 다만 API의 존재가 주행 중 음성 사용 허가를 뜻하지는 않는다. 유형별 정책 판단과 오디오 조건은 아래 9.1절을 따른다.

출처: [TTS 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions), [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release), [합성 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners)

## 7. SpeechToText: 차량 마이크와 음성 응답

STT v3.3.1은 사용자 음성을 텍스트로 변환한다. **변환한 텍스트에 따라 앱의 기능을 실행하는 것은 별도 앱 로직 또는 Gleo 연동의 역할**이다. 음성 인식과 음성 명령 실행을 같은 기능으로 취급하지 않는다.

모드는 HYBRID, SERVER, ON_DEVICE이며, 서버 연결·앱 등록 조건은 TTS와 같은 구분으로 안내된다. `pleos.car.permission.STT_SERVICE` 권한이 필요하다.

| API·콜백 | 의미 |
|---|---|
| `request()` | 차량 마이크를 활성화하고 음성 인식 시작 |
| `stop()` | 음성 인식 및 마이크 사용 종료 |
| `onUpdated(stt, completed)` | 인식 텍스트 갱신; completed가 true이면 최종 결과 |
| `onEndedRecognition()` | 인식 종료 |
| `onError(errorType)` | 처리 오류 |

초기화와 결과 리스너 등록을 먼저 완료해야 한다. 서버 모드는 My Project의 Client ID와 Client Secret으로 등록하고 연결 성공 후 요청한다. ON_DEVICE만 쓰는 경우 서버 앱 등록이 필요하지 않다고 안내한다.

차량 마이크 대신 앱이 보유한 PCM 데이터를 `sendAudio()`로 전달하는 방법도 있다. **이 방식은 ON_DEVICE 전용**이며, 마지막 데이터 뒤에 `completeAudioSend()`를 호출한다. 가이드의 3,200바이트/100ms 예시는 16kHz·16비트·모노 PCM을 기준으로 한 예시다.

초안의 “응/아니” 답변 처리에는 이러한 인식 결과를 사용할 수 있다. 그러나 화자의 목소리만으로 운전자를 인증하거나 자동 식별하는 기능은 이번에 읽은 문서에서 확인하지 않았다.

출처: [STT 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions), [인식 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [결과 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [앱 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration), [PCM 데이터 전송](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)

## 8. FusedLocation: 위치와 영역 진입·이탈

FusedLocation v1.1.3은 차량 위치 서비스와 연동한다.

| 기능 | API |
|---|---|
| 현재 실행 환경 지원 확인 | `isSupported()` |
| 마지막으로 확인된 위치 조회 | `getLastKnownLocation()` |
| 위치 갱신 수신·해제 | `registerFusedLocationCallback()` / `unregisterFusedLocationCallback()` |
| 지정 영역 진입·이탈 알림 등록·해제 | `addProximityAlert()` / `removeProximityAlert()` |
| 위치 접근 차단 안내 | `showLocationAccessBlockedPopup()` |

위치 결과는 위도, 경도, 고도, 진행 방향, 신뢰도, 타임스탬프 등을 포함한 `LocationData`로 전달된다. 마지막 위치 조회는 새 위치 측정 요청이 아니며, 사용할 데이터가 없으면 null일 수 있다. 위치 변경 콜백도 null 및 오류를 처리해야 한다.

### 권한과 보장하지 않는 범위

- `pleos.car.permission.FUSED_LOCATION`은 dangerous 수준의 권한으로 안내된다.
- 차량의 개인정보 보호 설정이 위치 접근을 차단할 수 있다.
- 차단 안내 팝업은 차단 상태를 알려주는 기능이며, 접근을 강제로 허용하는 기능이 아니다.
- **공식 문서는 일반 Android의 `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` 동작 및 포그라운드·백그라운드 위치 정책을 별도로 보장하지 않는다고 명시한다.**
- Google Fused Location SDK 설명을 Pleos의 동작 보장으로 그대로 적용하면 안 된다.

지정 좌표 주변에 진입했는지 확인하는 기능과, 현재 내비게이션 경로 앞쪽의 휴게소를 검색해 남은 도로 거리를 계산하는 기능은 다르다. 휴게소 기능에는 위치 서비스 외에 장소 데이터와 경로 연동 조건을 따로 확인해야 한다.

출처: [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [위치 데이터 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access), [권한·접근 제한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions), [오류 처리](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/error-handling)

## 9. Gleo Interface: 음성으로 앱 기능 호출

Gleo는 사용자의 요청을 앱이 등록한 기능 호출로 연결하고, 앱이 반환한 결과에 따라 최종 응답을 생성하는 인터페이스다.

### 등록과 호출

1. 앱이 호출 가능한 기능과 인자를 App Schema로 정의한다.
2. 파일을 `assets/schema.json`에 넣는다.
3. 해당 기능의 Android action filter와 수신 처리를 구현한다.
4. Gleo는 요청을 해석해 Android Intent로 전달한다.
5. 앱은 처리 결과를 callback 형태로 반환한다.

공식 문서는 Schema 검토·승인 뒤 통합이 활성화된다고 설명한다. 파일 추가만으로 모든 실행 환경에서 연동이 승인된다고 단정하지 않는다.

| 항목 | 공식 문서 내용 |
|---|---|
| 음성 호출 기능 수 | `actionCalls` 최대 3개 |
| `component: activity` | `startActivity()`로 UI 실행 |
| `component: service` | 문서상 `sendBroadcast()`로 전달; UI 없는 처리에 사용 |
| callback 상태 | success / asking / fail |
| callback 출력 | voice / beep / silent |
| action timeout | 7초 후 취소한다고 안내 |

`asking`은 필요한 정보나 확인이 부족할 때 후속 상호작용을 요청하는 데 사용한다. 앱이 자체 TTS를 재생하는 경우 `silent`를 사용하여 Gleo의 중복 음성 출력을 피하는 사례도 있다.

문서 내 설명과 코드 예시 사이에 Intent 인자 표현 차이가 보인다. 개념 설명은 인자별 putExtra를 언급하지만 코드 예시는 `ACTION_PARAM`에 JSON 문자열을 전달한다. 실제 구현에서는 적용 버전의 계약과 샘플을 확인해야 한다.

출처: [Gleo 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback), [코드 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [Schema 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema), [라디오 연동 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/use-cases/radio-app)

### 9.1 주행 중 음성 정책 심화 확인

2026-11-03 확인 결과, **TTS·STT·Gleo의 기술 지원은 확인했지만 게임 앱의 주행 중 사용을 허용하는 예외는 확인하지 못했다.** 일반 음성 설계 권장과 게임 카테고리 제한은 적용 범위가 다르다. 기능을 임의로 제외하지 않되 다음 유형을 구분한다.

| 기능 유형 | 확인 결과와 판단 |
|---|---|
| 전투·피격·성장·보상·퀘스트 등 게임 피드백 음성 | [심사 가이드](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements)는 게임의 주행 중 실행·사용 중지를 요구. 음성 게임 예외는 없음. **해석:** 화면만 숨기고 음성으로 게임 활동을 계속하는 안을 허용된 설계로 확정할 근거가 없음. 모든 TTS 호출을 일괄 금지한다는 별도 조항을 찾았다는 뜻은 아님 |
| 앱이 먼저 말하는 비게임 목적 휴식·휴게소 권유 | 이동 관련 기능이라는 이유만으로 게임 앱에 Mobility Services·Navigation 예외가 자동 적용되지는 않음. 해당 앱에서 선제적 TTS를 사용할 수 있는지 별도 확인 필요 |
| 권유에 대한 짧은 수락·거절 STT | 텍스트 인식 API는 확인. 주행 중 자동 마이크 활성화·상시 청취·응답 수집 허용은 미확인. 출력 허용과 마이크 사용 조건을 분리 |
| 사용자가 시작하는 Gleo 명령 | 스키마 검토·승인 후 기능 호출 가능. `asking`·`silent`·UI 없는 전달 방식이 주행 제한 면제를 뜻하지 않음. 호출할 기능 자체의 허용 여부를 확인 |

[주행 안전 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)는 음성·물리 버튼 활용과 함께 게임 등 주의 분산 활동을 유도하지 않도록 안내한다. [지역별 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)의 북미 음성 검색 사례는 인포테인먼트 통합 검색이며 게임 음성 전반의 허가가 아니다. [카테고리별 허용 예](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)도 실제 앱 분류·기능에 맞춰 적용한다.

**허용되는 음성에도 필요한 오디오 조건:** [오디오 포커스 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)는 재생 전 포커스 요청과 획득·상실·일시 상실·덕킹(다른 소리를 위해 음량 낮추기) 처리를 요구한다. 요청 성공이 카테고리 정책 승인은 아니다. `GAME` 용도가 `CarAudioContext.MUSIC`으로 매핑되는 것은 오디오 분류이며 앱을 Music/Audio 카테고리로 승인한다는 의미가 아니다. 휴식 권유를 임의로 `SAFETY`·`EMERGENCY`로 선언해 우선권을 얻는 설계로 확정하지 않는다. 실제 Speech Service의 포커스 처리 책임과 앱에서 필요한 연동은 실행 검증 대상이다.

[Suspend·Resume 오디오 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)은 IGN OFF에 따른 저전력 상태를 다룬다. 주행 중 앱 숨김과 다른 상황이다. 일반 오디오는 Suspend 전에 중단·포커스 반납하고, Suspend 중 새 재생·포커스 요청을 하지 않으며, Resume 후에는 현재 조건을 다시 확인한다. 시스템 안전 경고 등의 예외에 게임의 휴식 권유가 자동 포함된다고 가정하지 않는다.

**공식 문의에 포함할 질문 — 미발송:** 게임 기능을 중지한 앱에서 ① 보상·퀘스트와 연결하지 않은 선제적 휴식 권유 ② 짧은 STT 수락·거절 ③ 사용자 시작형 Gleo 기능 호출 ④ 게임 결과 피드백 음성이 각각 허용되는가? 허용되는 유형별 카테고리·DO·백그라운드 실행·음성 시작 조건·오디오 용도는 무엇인가? 문의 경로와 기록 모델 질문은 [차량 자료 6.1절](03_VEHICLE_API.md)에서 관리한다. 정차 상태의 음성 API 실행 성공을 주행 중 허용·검증 완료로 대체하지 않는다.

## 10. 현재 기획과 연결할 때 미확정인 사항

아래는 기능 삭제 결론이 아니라, 구현·기획 확정 전에 확인할 항목이다.

| 질문 | 현재 확인 상태 |
|---|---|
| TTS·STT·위치 SDK를 참가팀이 현재 사용할 수 있는가? | OT에서 개발 단계 사전 승인 불필요 안내. 일반 포털 상태 차이와 API별 실제 실행·인증 조건은 별도 검증 필요(2절) |
| 차량에 타면 앱이 자동 시작되는가? | 이번에 읽은 음성·위치 API 문서만으로 보장되지 않음 |
| 화면이 비활성화되거나 다른 앱으로 전환되어도 장시간 기록 가능한가? | SDK 호출 가능성과 앱 실행 수명·백그라운드 정책을 따로 검증해야 함 |
| 주행 중 먼저 말을 걸고 응답을 받을 수 있는가? | 9.1절의 게임 피드백·비게임 휴식 권유·STT 응답·사용자 시작형 Gleo로 분리. 게임의 주행 중 사용 중지 확인, 유형별 예외 허용 미확인 |
| 휴게소 5km 전에 알려줄 수 있는가? | 위치·영역 이벤트는 문서화됨. 경로상 휴게소 검색과 도로 거리 계산은 별도 확인 |
| 목소리로 운전자 프로필을 자동 식별할 수 있는가? | STT는 텍스트 변환을 제공. 음성 신원 식별 API는 확인하지 않음 |
| Gleo 호출을 바로 시연할 수 있는가? | Schema 승인 및 실제 에뮬레이터의 Gleo 지원·등록 상태 확인 필요 |
| 에뮬레이터 성공이 실차 동작을 보장하는가? | 아님. 실차 지원 기능·권한·배포 심사를 따로 확인해야 함 |

## 11. 공식 문서 색인과 실제 확인 범위

아래 링크는 다시 탐색할 수 있도록 모은 색인이다. 모든 API 클래스·메서드를 전부 읽었다는 의미는 아니다.

### 개발환경

- [SDK 개요](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/) — 본문 확인.
- [프로젝트 생성 및 SDK 신청](https://document.pleos.ai/docs/pleos-only/create-my-project) — 본문 확인.
- [에뮬레이터 설치·실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator) — 본문 확인.
- [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator) — 본문 확인.
- [Virtual Console 셋업](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator) — 본문 확인.
- [Virtual Console 사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide) — 탐색 및 일부 본문 확인; 모든 카드 세부 항목 정독 아님.

### TextToSpeech

- [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro) — 본문 확인.
- [SDK 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk) — 본문 확인.
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions) — 본문 확인.
- [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release) — 본문 확인.
- [이벤트 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners) — 일부 본문 확인.
- [로케일](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale) — 페이지 열람; 세부 내용 정독 아님.
- [앱 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration) — 페이지 열람; 모드별 등록 조건은 소개 문서로 확인.
- [합성 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop) — 본문 확인.
- [오류 코드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/error-code) — 페이지 열람; 전체 코드 정독 아님.

### SpeechToText

- [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) — 본문 확인.
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions) — 본문 확인.
- [결과 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners) — 본문 확인.
- [앱 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration) — 본문 확인.
- [인식 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop) — 본문 확인.
- [PCM 전송](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data) — 본문 확인.

### FusedLocation

- [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro) — 본문 확인.
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions) — 본문 확인.
- [위치 데이터 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access) — 본문 확인.
- [오류 처리](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/error-handling) — 본문 확인.
- [API 참조 진입점](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/entry) — 색인 확인.
- [클래스 패키지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/entry) — 색인 확인.
- [데이터 모델 패키지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/entry) — 색인 확인; 개별 클래스 전체 정독 아님.

### Gleo

- [개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts) — 본문 확인.
- [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability) — 필드·예시·주요 제약 확인.
- [Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback) — 본문 확인.
- [구현 코드 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples) — 본문 확인.
- [Schema 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema) — 본문 확인.
- [라디오 앱 연동 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/use-cases/radio-app) — 예시 확인. 튜토리얼은 실제 앱의 승인·동작을 보장하는 근거로 사용하지 않음.
