# Pleos Connect 개발환경·음성·위치·Gleo 자료

- 확인 기준일: **2026-11-02 KST**
- 대상: 차량의 Pleos Connect 환경에 설치·실행하는 앱. 스마트폰 화면을 차량으로 투사하는 앱을 전제로 하지 않는다.
- 이 문서는 공식 공개 문서를 읽고 정리한 자료다. SDK 다운로드, 계정 생성, 권한 신청, 에뮬레이터 실행, 실차 테스트를 수행했다는 의미가 아니다.
- 공모전 접수 요건과 Pleos App Market 배포·권한 승인 요건은 서로 다른 절차다. 공모전 참가자에게 적용되는 별도 제공 조건은 운영사무국 안내를 추가 확인해야 한다.
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

| 구성 | 문서·릴리스 확인 버전 |
|---|---|
| Vehicle SDK | 2.3.2 |
| NaviHelper SDK | 3.0.5 |
| TextToSpeech SDK | 3.3.1 |
| SpeechToText SDK | 3.3.1 |
| FusedLocation SDK | 1.1.3 |
| Pleos Connect Emulator | 2.1.0 |

음성·위치·에뮬레이터 버전은 아래 각 공식 상세 문서에서도 명시한다. 차량·내비게이션 세부 내용은 별도 조사 문서를 참고한다.

### 바로 사용 가능하다고 단정하면 안 되는 이유

[프로젝트 생성 및 SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project) 페이지는 다음과 같이 안내한다.

| 신청 안내상의 상태 | 해당 SDK |
|---|---|
| 신청 없이 사용 가능 | Vehicle State, NaviHelper |
| 준비 중 / 신청 불가 | FusedLocation, TextToSpeech, SpeechToText, Vehicle Control |

그러나 TTS·STT·FusedLocation의 상세 문서에는 최신 버전, 실제 의존성, 권한, API 호출 방법과 에뮬레이터 사용법이 제공된다. **공식 문서 사이에 제공 상태 설명이 일치하지 않는다.**

따라서 현재 확인 가능한 결론은 다음과 같다.

- API가 문서화되어 있다는 사실은 확인했다.
- 신청 안내의 준비 중 문구만으로 해당 API가 현재 절대 사용 불가라고 확정하지 않는다.
- 상세 문서가 있다는 이유만으로 모든 참가 계정·실차·에뮬레이터에서 즉시 사용 가능하다고 확정하지 않는다.
- 프로젝트 SDK 화면의 현재 상태와 공모전 참가자 제공 조건을 확인해야 한다.

## 3. 계정·프로젝트·SDK 신청

공식 순서는 Pleos Playground 가입 → 프로젝트 생성 → 필요한 SDK 상태 확인 및 사용 신청 → 개발환경 구성이다.

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

캐릭터 안내나 휴식 권유에 연결할 수 있는 출력 수단이 문서화되어 있다. 다만 이 API의 존재가 주행 중 임의의 시점에 모든 음성 안내를 출력해도 된다는 허용을 뜻하지는 않는다. 주행 정책과 오디오 우선순위는 별도 검토한다.

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

## 10. 현재 기획과 연결할 때 미확정인 사항

아래는 기능 삭제 결론이 아니라, 구현·기획 확정 전에 확인할 항목이다.

| 질문 | 현재 확인 상태 |
|---|---|
| TTS·STT·위치 SDK를 참가팀이 현재 사용할 수 있는가? | 신청 안내와 상세 문서의 상태가 달라 프로젝트 화면·운영 안내 확인 필요 |
| 차량에 타면 앱이 자동 시작되는가? | 이번에 읽은 음성·위치 API 문서만으로 보장되지 않음 |
| 화면이 비활성화되거나 다른 앱으로 전환되어도 장시간 기록 가능한가? | SDK 호출 가능성과 앱 실행 수명·백그라운드 정책을 따로 검증해야 함 |
| 주행 중 먼저 말을 걸고 응답을 받을 수 있는가? | 음성 API는 존재하나 카테고리·주행 제한·오디오 정책 적용 필요 |
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
