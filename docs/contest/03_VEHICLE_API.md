# 차량 API와 설계 조건

- 최초 확인: **2026-11-02 KST**. **2026-11-03 백그라운드 기록·주행 제한 심화 확인**.
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

API 선언에 권한이 `normal`로 표시되는 것과 앱 마켓 정책상 권한 사용이 승인되는 것은 같은 의미가 아니다. SDK 정책은 Vehicle API 권한을 선언하고, Pleos App Market과 사전 협의·승인된 범위 안에서 사용하도록 안내한다. **2026-11-03 보완:** OT는 공모전 개발 단계의 사전 승인과 마켓 배포 승인을 구분했다. 승인 설명·자료 차이의 기준은 [개발 자료 2절](02_PLEOS_DEVELOPMENT.md)을 따른다. 개발 단계 승인 면제는 모든 API의 지원이나 주행 중 게임 허용을 보장하지 않는다.

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


### 4.4 거리·도착 단위와 설치 버전 검증 경계

2026-11-06 공식 클래스 본문 대조: `DestinationInfo.remainDistance`는 m, `remainTime`은 s, `DestinationArrivedInfo.distance`와 `duration`은 각각 m·s다. `CurrentLocationInfo`의 위도·경도는 degree이며 heading의 단위는 확인하지 못해 원값을 유지한다. `DrivingState.distance`는 명세상 출발점부터 지점까지의 거리이므로 남은 거리로 바꾸어 부르지 않는다.

출처: [DestinationInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry), [DestinationArrivedInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-arrived-info/entry), [CurrentLocationInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/current-location-info/entry), [DrivingState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/driving-state/entry).

`requestReRoute`는 활성 경로에서 사용하는 `NAVI_ROUTE_SEARCH` normal 권한 API다. 정상 반환과 실제 재탐색 완료 이벤트는 별개이며, routeId 유지·변경은 명세에서 보장한 것으로 확인하지 못했다. [공식 요청 명세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/request-re-route).

내장 모의주행의 실제 도착 수신, 요청 좌표와 서버 채택 POI의 불일치, 지정 설치 버전의 isRouting·수동 재탐색 제한, 권한 미선언과 복원 및 그래픽 결과는 [#80 추가 실행 검증](../06_VERIFICATION.md#80-내비-모의주행권한재탐색렌더링-추가-검증--2026-11-06)에서 관리한다. 문서상 API와 특정 버전의 실행 결과를 구분한다.

### 4.5 NaviHelper 기술 문의 회신 — 2026-11-11 사용자 제공

사용자가 전달한 운영사무국 답변은 “현재 공개된 SDK 문서 범위에서 개발해 주시고, 문서에서 지원되지 않는 기능은 기획서에 향후 계획으로 적어 주시면 됩니다.”이다. 2026-11-11은 사용자 제공 기록의 날짜다. 운영사무국의 실제 회신 일시는 별도로 확인하지 않았다. [#80 NaviHelper 기술 문의](https://github.com/Yaho03/drive-growth/issues/80)의 답변을 수신했다는 기록이며, 아래 세부 질문에 적용할 명세나 재현 절차는 회신에서 확인되지 않았다.

1. 수동 재탐색의 지원 조건과 완료 신호, 재탐색 시 routeId의 유지·변경 기준.
2. 취소 또는 새 경로 요청 뒤 늦게 도착한 이벤트를 원래 경로와 연결하는 방법과 순서 보장.
3. 백그라운드·중단·재연결·프로세스 재시작에서 이벤트 전달·유실·재전달과 실제 발생 시각을 확인하는 방법.

공개 API의 존재와 별개로, 세 질문에서 요청한 세부 동작·전달 보장은 미확인이다. 이번 회신을 모든 해당 기능의 미지원 확정이나 앱 모델의 정책 승인으로 해석하지 않는다. 현재 경로와 연결할 근거가 부족한 도착은 기존대로 확인 대기 상태로 두고 보상을 지급하지 않는다. 주행 중 게임 기록·음성·위치의 허용 여부는 별도 정책 확인 과제로 유지한다. [#81 주행 중 기능과 승인 조건](https://github.com/Yaho03/drive-growth/issues/81)

## 5. 차량 앱의 설계 조건

### 5.1 주행 제한 상태

Pleos는 `CarUxRestrictions`를 기준으로 주행 중 제한 상태를 확인하고, 제한 대상 기능의 UI와 동작을 축소하거나 비활성화하도록 안내한다. 기능 허용 여부에는 차량 상태, 지역, 앱 카테고리가 관여한다.

따라서 ‘화면 애니메이션을 멈춘다’, ‘속도가 0이다’, ‘브레이크를 밟았다’만으로 모든 게임 화면이나 조작을 허용할 수 있다고 가정하지 않는다. 필요한 기능을 플랫폼의 실제 제한 상태에 맞춰 설계하고 검증한다.

**2026-11-03 하위 심사 문서 추가 확인:** [운전자 주의 분산 심사](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements)는 게임·동영상·브라우저를 미디어 앱으로 분류하고, **운전 중 실행·사용 불가**, 운전 시작 시 **UI 숨김과 사용 일시 중지**, 주행 중 일시 중지 해제 금지, 헤드업 알림 금지를 명시한다. 화면만 숨기면 나머지 게임 기능은 허용된다는 규정으로 축소하지 않는다.

이는 App Market 심사 정책이며 공모전의 별도 가산점 기준이 아니다. 문서의 ‘정차’ 표현만으로 신호 대기·속도 0에서 조작을 허용하지 않으며 실제 `CarUxRestrictions`·카테고리 정책을 함께 확인한다. 이 페이지는 게임의 **주행 중 백그라운드 기록·계산·음성**을 명시적으로 허용하지 않는다. 반대로 앱의 모든 데이터 처리가 금지된다고 확대하지도 않는다. 해당 핵심 흐름은 개별 앱 모델에 대한 확인이 남는다.

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

### 5.3 백그라운드 기록 심화 확인

2026-11-03 공개 문서 대조 결과다. **Pleos에 조건부 백그라운드 동작 모델은 있지만, 게임 앱의 주행 중 자동 기록 예외는 확인하지 못했다.** 기록 요구사항은 유지하되 정책 적합성과 실행 지속성을 서로 다른 완료 조건으로 둔다.

| 쟁점 | 공식 문서에서 확인한 내용 | 우리 앱에 적용할 때의 한계 |
|---|---|---|
| 화면 숨김과 종료 | [앱 구조](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)는 화면에서 사라져도 백그라운드에 남을 수 있다고 설명. 화면 갱신·애니메이션·불필요한 센서 수집은 정리하고 지속 작업의 중단·재개 정책을 요구 | 숨김은 프로세스 종료와 다르지만, 프로세스 생존이나 차량 수집의 허가를 보장하지 않음 |
| 최상위 Back 이후 서비스 | [CCP 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development)는 앱 숨김 후 **백그라운드 동작이 허용된 기능**이 서비스 생명주기에 따라 지속될 수 있다고 설명 | ‘허용된 기능’이라는 조건이 있으므로 모든 게임 기록을 허용한다고 해석하지 않음 |
| 차량 SDK 연결·구독 | [도메인 객체 획득](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)은 초기화·사용 종료 시 해제, [Callback](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/callback)은 등록·해제·실패 처리를 안내 | 읽은 문서에 Activity 숨김 시 모든 구독을 종료하라는 조건은 없지만, 숨김·프로세스 종료·차량 Suspend 이후 연속 수신 보장도 없음 |
| DO 선언 | [주행 규제 구현](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)은 DO 선언 여부와 별도로 `CarUxRestrictions`·`ActiveRestrictions` 반영 요구 | DO 선언·API 권한·사용자 동의가 게임 기능의 주행 중 허용을 각각 대신하지 않음 |
| 카테고리 예외 | [Music/Audio](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide)는 오디오가 주 기능인 앱의 백그라운드 재생을 요구. [Navigation](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide)은 Background 경로 안내를 금지하고 OEM 안전운행 정보만 예외로 설명 | 음악·음성·휴게소 기능을 붙였다는 이유로 게임에 다른 카테고리의 예외가 적용되지 않음. Navigation의 안내 제한을 데이터 기록 금지로 확대하지도 않음 |

일반 Android의 [Foreground Service](https://developer.android.com/develop/background-work/services/fgs)는 사용자가 인지할 수 있는 지속 작업과 알림 등을 전제로 하는 실행 수단이다. 화면이 전면에 있는 Activity와 같은 뜻이 아니며, Pleos 게임 정책의 허가 근거도 아니다. 채택 여부는 미정이다. 채택 검토 시 실제 Android 버전·시작 조건·서비스 유형·권한·Pleos 알림 정책을 확인한다. 위치 수집에는 [Pleos 위치 정책의 별도 한계](02_PLEOS_DEVELOPMENT.md)도 적용한다.

사용자에게 수집 항목·목적·보관 조건을 설명하고 허용한 데이터만 접근해야 한다는 [개인정보 심사](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security)도 함께 확인했다. 사용자 동의, API 권한, 카테고리 정책 적합성, 실제 콜백 수신은 각각 확인해야 한다. 주행 중 음성의 판단은 [개발 자료 9.1절](02_PLEOS_DEVELOPMENT.md)에서 관리한다.

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
| 앱을 보지 않는 동안 주행 기록을 계속 받을 수 있는가? | 조건부 백그라운드 모델 확인. 게임 자동 기록의 허용·연속 전달 보장은 미확인(5.3절) | 아래 앱 모델에 대한 정책 답변과 별도의 실제 수신·중단·복원 시험 |
| 크루즈·방향지시등 등 기능이 차량마다 지원되는가? | 도메인/API 존재 확인 | 각 기능 Capability와 차종 지원표 확인 |
| 게임 요소와 주행 중 기록·음성의 결합이 허용되는가? | 게임 UI 숨김·일시 중지 정책 확인. 백그라운드 기록·계산·음성의 개별 허용은 미확인 | 실제 앱 기능·카테고리를 기준으로 운영사에 확인 |
| 실차 배포에 필요한 API 권한은 승인 가능한가? | 배포 사전 협의·승인 정책 확인, 앱별 승인 결과는 미확인 | OT의 개발 승인 면제와 구분해 App Market 등록 시 확인 |

### 6.1 공식 확인이 필요한 구체적 질문

아래는 **문의 초안이며 발송·답변 수신 전**이다. 공개 [공모전 FAQ](https://appdevchallenge.auto-hmg.io/faq)에는 이 앱 모델에 대한 답을 찾지 못했다. FAQ의 공식 문의 경로는 `appdevchallenge@hyundai-partners.com`이다. 담당·확인일은 미정이다.

1. 주차 중 사용자가 기록을 시작하고 동의한 뒤, 주행 중 게임 UI·음성·알림·조작을 중단한 상태에서 속도·기어·주행거리 등 필요한 이벤트만 로컬에 저장하고 주차 후 게임 결과에 반영하는 모델이 허용되는가? 이 모델은 **검토안**이며 제품의 계산 시점 확정이 아니다.
2. 허용된다면 필요한 카테고리·DO 선언·실행 서비스·알림·Vehicle 권한과 승인 조건은 무엇인가? 비시각적 기록과 주행 중 게임 계산은 각각 어디까지 허용되는가?
3. 화면 숨김·다른 앱 전면 전환·사용자 전환·IGN OFF/Suspend에서 SDK 연결 및 이벤트 전달의 공식 보장 범위는 무엇인가? 재연결 때 누락 구간을 조회할 수 있는가?
4. 위 답변은 공모전 에뮬레이터 개발과 App Market 배포에 각각 어떻게 적용되는가? 주행 중 음성 문의는 [개발 자료 9.1절](02_PLEOS_DEVELOPMENT.md)의 유형별 질문을 함께 사용한다.

답변을 확보하면 적용 버전·기능 범위·조건을 기록하고 PRD·기술 계약·검증 계획에 반영한다. 에뮬레이터에서 동작한다는 사실만으로 정책 승인으로 처리하지 않는다.

## 7. 기획서에 반영할 때의 표현 원칙

- **문서 확인:** API와 데이터 정의가 공식 문서에 존재한다.
- **동작 확인:** 지정 버전의 에뮬레이터 또는 실차에서 실제 결과를 확인했다.
- **설계 제안:** 그 데이터를 이용해 어떤 경험을 만들겠다는 팀의 아이디어다.
- **미확인:** 추가 문서·실험·운영사 답변이 필요하다.

위 네 상태를 구분하면, 초안의 가능성을 열어두면서도 구현 가능성과 검증 수준을 과장하지 않을 수 있다.
