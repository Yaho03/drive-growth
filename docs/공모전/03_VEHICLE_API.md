# 차량 API와 설계 조건

- 확인 기준일: **2026-11-02, 한국 시간(KST)**
- 대상: 차량에 설치되어 실행되는 **Pleos Connect 앱**
- SDK 소개 문서 기준 버전: **Vehicle SDK v2.3.2 / Vehicle Service v2.3.21**, **NaviHelper SDK v3.0.5**. 별도의 최신 Sample App 가이드는 Service **v2.3.22**를 사용하므로, SDK와 설치된 Service의 버전을 각각 확인한다.
- 용도: 아이디어를 구체화할 때 활용할 수 있는 기능, 조건, 확인 과제의 근거 자료

`드라이브 키우기` 기획서는 아이디어 초안이다. 이 문서는 해당 초안의 기능을 확정하거나 삭제하는 결정문이 아니다. 문서에 API가 있다는 사실, 해당 차량에서 지원한다는 사실, 공모전 환경에서 동작한다는 사실, 최종 앱 배포가 승인된다는 사실은 각각 구분한다.

## 1. 확인 범위와 근거 수준

Vehicle SDK 소개, 공통 Capability 가이드, Brake·SteeringWheel·Odometer·DrivingMode의 API 목록과 주요 본문, NaviHelper 소개·경유지 가이드·전체 클래스 메서드 목록을 확인했다. API Reference는 일반 웹 본문 추출에서 제목과 메뉴만 노출되는 경우가 있어, 주요 항목은 브라우저에 실제로 표시되는 API 본문도 확인했다.

모든 Vehicle 도메인과 메서드, NaviHelper의 모든 데이터 클래스 필드를 완독한 것은 아니다. SDK 설치, APK 실행, 에뮬레이터 신호 입력, 실차 테스트, 권한 승인 신청은 이 조사에서 수행하지 않았다. 아래의 ‘미확인’은 기능이 없다는 뜻이 아니다.

버전 근거:

- [Vehicle SDK 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [NaviHelper SDK 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [Pleos Connect SDK 전체 개요와 API 정책](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)
- [Sample App의 별도 실행 기준](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app)

## 2. 차량 데이터 API

### 2.1 브레이크 페달

`getCurrentBrakePedalStatus`는 현재 브레이크 페달 상태를 조회한다. 결과 콜백의 값은 `Boolean?`이며, `true`는 페달을 밟고 있다는 의미다. 요구 권한은 `pleos.car.permission.CAR_DRIVE`이고, 해당 상세 문서에는 보호 수준이 `normal`로 표시된다.

현재 Brake API 목록에는 다음 항목이 있다.

- `checkBrakePedalStatusCapabilities`
- `getCurrentBrakePedalStatus`
- `registerBrakePedalStatus`
- `unregisterBrakePedalStatus`

이 목록에서는 급제동 여부나 제동 강도를 직접 반환하는 메서드를 확인하지 못했다. 페달 상태를 받아 급제동 여부를 별도로 계산하는 아이디어와, SDK가 완성된 급제동 판정을 제공한다는 주장은 구분해야 한다.

**문서 간 불일치:** Vehicle 소개의 Brake 설명은 주차 브레이크 상태 조회를 포함하지만, 확인한 Brake API 목록은 페달 관련 네 항목만 제시한다. 주차 브레이크 신호의 실제 접근 경로는 별도로 확인해야 한다.

- [Brake API 목록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry)
- [getCurrentBrakePedalStatus 상세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/get-current-brake-pedal-status)
- [주차 브레이크를 언급하는 Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)

### 2.2 조향각

`getCurrentSteeringWheelAngle`의 상세 본문은 반환 신호를 **앞바퀴 조향각(front-wheel steering angle)**으로 정의한다. 단위는 도(degree), 반환 값은 `Float?`다. 메서드 이름만 보고 운전자가 돌리는 물리적인 핸들의 회전각이라고 단정하면 안 된다.

- 지원 범위 확인: `checkSteeringWheelAngleCapabilities`
- 현재 값 조회: `getCurrentSteeringWheelAngle`
- 변경 구독: `registerSteeringWheelAngle`
- 구독 해제: `unregisterSteeringWheelAngle`
- 요구 권한: `pleos.car.permission.CAR_DRIVE`

이 신호로 급회전 등을 평가하려면 신호 의미, 시간 간격, 차량 속도, 조향 상황을 함께 검증해야 한다. 위 메서드가 급회전 판정 결과를 직접 제공하는 것은 아니다.

출처: [SteeringWheel API 상세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry)

### 2.3 누적 주행거리와 트립 정보

같은 Odometer 도메인에서도 거리 단위가 다르다.

| API | 문서상 의미·단위 |
|---|---|
| `getCurrentDistanceDriven` | 차량 누적 주행거리, **km** |
| `getTripCurrentDrivingDistance` | 현재 트립 주행거리, **m** |
| `getTripCumulativeDrivingDistance` | 누적 트립 주행거리, **m** |
| `getTripInstantaneousDrivingDistance` | 문서상 instantaneous trip 거리, **m**. 실제 집계 구간은 추가 확인 필요 |
| `getTripSinceRefillDrivingDistance` | 주유 후 트립 주행거리, **m** |
| `getTripCurrentDrivingTime` 등 | 트립 시간 조회. 이 조사에서는 시간 값의 단위와 초기화 조건을 확정하지 않음 |

거리 조회 값은 `Float?`이며, 각 기능에 대응하는 Capability 확인 및 변경 구독 API가 있다. 조회·구독에는 `pleos.car.permission.CAR_DRIVE`가 요구된다. 거리 정규화, 경험치 계산 등을 설계할 때 누적거리와 트립거리의 단위를 통일해야 한다.

출처: [Odometer API 상세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/odometer/entry)

### 2.4 크루즈 컨트롤과 드라이브 모드

DrivingMode에는 다음 조회·구독 기능이 문서화되어 있다.

| 기능 | 현재 값 조회 | 구독 |
|---|---|---|
| 크루즈 컨트롤 상태 | `getCruiseControlState` | `registerCruiseControlState` |
| 크루즈 목표 속도 | `getCruiseControlTargetSpeed` | `registerCruiseControlTargetSpeed` |
| 드라이브 모드 | `getCurrentDrivingModeState` | `registerDrivingModeState` |

크루즈 목표 속도는 `Float?`, 단위는 **m/s**다. 각 기능의 Capability 확인 API도 있다. 이 도메인에서 확인한 기능은 상태 조회·구독이며, 크루즈를 켜거나 끄는 제어 기능을 확인한 것은 아니다.

방향지시등은 DrivingMode가 아니라 별도의 **TurnSignal** 도메인이다. 방향지시등 신호만으로 실제 차로 변경 완료까지 판정할 수 있는지는 별개의 문제다.

- [DrivingMode API 상세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/entry)
- [Vehicle 도메인 목록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)

### 2.5 차량 속도

Pleos 공식 FAQ는 클러스터와 동일한 차량 속도를 사용하려면 AAOS의 `CarPropertyManager`를 통해 `PERF_VEHICLE_SPEED`를 조회하거나 변경 이벤트를 구독하도록 안내한다. GPS 기반 속도와 차량 신호 기반 속도는 서로 다를 수 있다고 설명한다.

속도 데이터의 실제 갱신 주기, 시각 정보, 누락·오류 상태, 에뮬레이터에서의 입력 방법은 구현 검증 대상이다. 두 속도 값의 차이만으로 감속을 계산할 때도 **그 사이에 흐른 시간**이 필요하다. 이를 곧바로 검증된 안전운전 판정이라고 표현하지 않는다.

- [Pleos 실행 환경 FAQ: 클러스터 속도 조회](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq)
- [Android VehiclePropertyIds 공식 참조](https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED)

## 3. Capability와 권한

`checkXxxCapabilities`는 기능 지원 여부와 허용 범위를 확인하기 위한 API다. 반환 형태는 기능마다 다르며, 현재 가이드는 `CapabilityConfig`를 함께 반환하는 형태를 설명한다.

| 값 | 의미 |
|---|---|
| `NONE` | 접근 불가 |
| `READ` | 읽기 가능 |
| `WRITE` | 쓰기 가능 |
| `READ_WRITE` | 읽기·쓰기 가능 |

기능에 따라 `AreaBounds` 또는 영역별 범위 정보가 함께 제공된다. SDK 연결 및 차량 통신은 비동기이며 성공·실패 콜백을 구분해야 한다.

API 선언에 권한이 `normal`로 표시되는 것과 앱 마켓 정책상 권한 사용이 승인되는 것은 같은 의미가 아니다. SDK 정책은 Vehicle API 권한을 선언하고, Pleos App Market과 사전 협의·승인된 범위 안에서 사용하도록 안내한다. 공모전 개발 환경의 구체적인 권한 제공 방식은 별도로 확인한다.

- [Capability 사용 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability)
- [Vehicle 연결 및 비동기 처리 개요](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [SDK API 정책](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)

## 4. NaviHelper: 내비게이션과 연결되는 범위

### 4.1 확인한 기능

NaviHelper는 차량의 Navigation 서비스에 요청을 전달하고 이벤트를 받는 방식이다. 공식 소개와 클래스 목록에서 다음 기능을 확인했다.

- 목적지 설정·조회·취소
- 경로 옵션 변경·재탐색
- 경유지 추가·조회·제거
- 경유지를 포함한 경로 요청
- 현재 위치·경로 상태·TBT 정보 조회
- 북마크·최근 목적지·충전사업자 정보 조회
- Navigation 화면 요청
- 리스너 등록을 통한 결과·상태 이벤트 수신

클래스의 존재만으로 모든 차량·상태에서 모든 이벤트가 제공된다고 볼 수는 없다. 공식 소개는 실제 이벤트가 Navigation 앱의 구현과 현재 상태에 따라 달라질 수 있다고 명시한다.

- [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
- [NaviHelper 클래스 전체 메서드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)

### 4.2 경유지

가이드상 경유지는 최대 **두 개**이며 `WaypointIndex.FIRST`, `SECOND`로 구분한다.

- 추가: `addWaypoint(RequestWaypointInfo)`
- 제거: `removeWaypoint(...)`
- 조회: `getWaypointInfo()`
- 전체 제거: `removeWaypoint(WaypointIndex.ALL)`
- 경유지 도착 시 `onWayPointArrived()` 이벤트가 발생할 수 있음

경로 변경 계열은 `pleos.car.permission.NAVI_ROUTE_SEARCH`, 정보 조회 계열은 `pleos.car.permission.NAVI_ROUTE` 등 필요한 권한을 구분한다. 메서드별 권한은 해당 상세 명세를 따른다.

출처: [경유지 Programming Guide](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/waypoint-settings), [클래스 메서드별 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)

### 4.3 휴게소 탐색과 경유지 추가는 별도 확인 과제

확인한 NaviHelper 전체 클래스의 메서드 목록에서는 **휴게소 또는 일반 POI 검색 메서드를 찾지 못했다.** 이는 Pleos 전체에서 휴게소 탐색이 불가능하다는 결론이 아니다.

‘앞으로 5km 뒤의 휴게소를 알려준다’는 동작에는 다음을 각각 확인해야 한다.

1. 휴게소 위치·이름 등의 정보 출처
2. 현재 진행 방향과 경로에 포함되는 휴게소인지 판단하는 방법
3. 직선거리가 아닌 실제 주행 경로상 남은 거리 확인 방법
4. 경유지 추가에 필요한 좌표·식별 정보
5. 기존 경유지가 이미 두 개일 때의 처리 방식

단순히 `addWaypoint`가 있다는 이유로 위 기능까지 제공된다고 가정하지 않는다. 반대로 조사 범위에서 검색 메서드를 발견하지 못했다는 이유로 아이디어를 삭제하지도 않는다.

## 5. 차량 앱의 설계 조건

### 5.1 주행 제한 상태

Pleos는 `CarUxRestrictions`를 기준으로 주행 중 제한 상태를 확인하고, 제한 대상 기능의 UI와 동작을 축소하거나 비활성화하도록 안내한다. 기능 허용 여부에는 차량 상태, 지역, 앱 카테고리가 관여한다.

따라서 ‘화면 애니메이션을 멈춘다’, ‘속도가 0이다’, ‘브레이크를 밟았다’만으로 모든 게임 화면이나 조작을 허용할 수 있다고 가정하지 않는다. 필요한 기능을 플랫폼의 실제 제한 상태에 맞춰 설계하고 검증한다.

공식 문서는 운전 중 시선 분산을 줄이고, 게임 등 주의를 분산시키는 활동을 유도하지 않도록 안내한다. 이는 정차 후 이용하는 게임 요소나 허용 범위 내 데이터 처리까지 일괄 금지한다고 판정한 내용은 아니다. 구체적인 앱 동작 모델과 카테고리에 대한 확인이 필요하다.

- [주행 안전을 고려한 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
- [지역별 주행 규제 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)
- [앱 카테고리](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category)

### 5.2 화면·앱 실행·데이터 가용성

다음 세부 문서를 앱 설계·검증의 기준 자료로 함께 사용한다. 해당 문서 링크가 있다는 사실을 개별 구현의 적합성 확인으로 대신하지 않는다.

| 설계·검증 항목 | 공식 기준 문서 |
|---|---|
| 화면 구성, 멀티 윈도우, 포그라운드·백그라운드 동작 | [앱 구조 및 동작 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model) |
| AAOS와 Pleos API의 역할·정책 | [SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api) |
| 하드웨어 직접 접근 제한·네트워크 대응 | [하드웨어 접근 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture) |
| 차종·플랫폼별 API 지원 및 가용성 | [API 호환성 및 가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability) |
| 리소스 사용, 실행 성능, 오디오 처리 | [성능 및 리소스 효율](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt) |

주행 기록을 계속 수집하는 아이디어라면 내비게이션 등 다른 앱이 전면에 있을 때의 처리, 앱 종료·복귀, 서비스 연결 해제, 기록 저장 시점 등을 실제 환경에서 확인해야 한다. 이 문서에서는 백그라운드 상시 수집이 허용되거나 보장된다고 확정하지 않았다.

## 6. 남은 질문과 검증 방법

| 질문 | 현재 확인 상태 | 다음 확인 방법 |
|---|---|---|
| 공모전에서 사용할 SDK·에뮬레이터 버전은 무엇인가? | 공개 개발 문서의 기준 버전만 확인 | 공모전 제공 환경과 다운로드 버전 대조 |
| 차량 속도 변경 이벤트의 주기와 시각 정보는? | 조회·구독 경로 확인, 실제 값 미검증 | 에뮬레이터에서 속도 변화 입력 후 로그 확인 |
| 브레이크 페달 이벤트의 실제 전달 방식은? | Boolean 의미와 구독 API 확인 | 눌림·해제·미지원 상태 실측 |
| 주차 브레이크는 어디서 읽는가? | 소개와 Brake API 목록 불일치 | AAOS 지원 목록·SDK 상세·운영사 답변 대조 |
| 조향각의 실제 값과 갱신 범위는? | 앞바퀴 조향각·degree로 명세 확인 | Capability 결과와 에뮬레이터 값 대조 |
| 트립 시간의 단위·초기화 조건은? | 확정하지 않음 | 해당 메서드·리스너 상세 및 동작 확인 |
| 휴게소 목록과 경로상 거리는 제공되는가? | 확인한 클래스에 검색 메서드 없음 | 내비게이션 데이터 필드·별도 공식 데이터 경로 확인 |
| 앱을 보지 않는 동안 주행 기록을 계속 받을 수 있는가? | 보장 여부 미확인 | 앱 실행 모델·주행 제한·수명주기 테스트 |
| 크루즈·방향지시등 등 기능이 차량마다 지원되는가? | 도메인/API 존재 확인 | 각 기능 Capability와 차종 지원표 확인 |
| 게임 요소와 주행 중 기록·음성의 결합이 허용되는가? | 일반 정책 확인, 개별 앱 판정 없음 | 실제 앱 기능·카테고리를 기준으로 운영사에 확인 |
| 실차 배포에 필요한 API 권한은 승인 가능한가? | 사전 협의·승인 정책 확인 | 공모전 개발 권한과 App Market 등록 절차 구분 확인 |

## 7. 기획서에 반영할 때의 표현 원칙

- **문서 확인:** API와 데이터 정의가 공식 문서에 존재한다.
- **동작 확인:** 지정 버전의 에뮬레이터 또는 실차에서 실제 결과를 확인했다.
- **설계 제안:** 그 데이터를 이용해 어떤 경험을 만들겠다는 팀의 아이디어다.
- **미확인:** 추가 문서·실험·운영사 답변이 필요하다.

위 네 상태를 구분하면, 초안의 가능성을 열어두면서도 구현 가능성과 검증 수준을 과장하지 않을 수 있다.
