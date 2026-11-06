# #80 시험 증거

2026-11-06 Pleos 에뮬레이터 시험에서 기록한 실제 SDK 이벤트와 화면이다. 입력은 내비 내장 모의주행이며 실차 입력·제품 정산 성공을 뜻하지 않는다. 상세 결과와 한계는 [검증 문서](../../docs/06_VERIFICATION.md#80-내비-모의주행권한재탐색렌더링-추가-검증--2026-11-06)를 따른다.

JSONL은 원본의 모든 행에서 시각·출처·이벤트·원시 payload 등 공통 필드만 선별한 공개본이다. 원본 전체가 아니며 중복 관측 스냅샷·요청 문맥·로컬 객체 식별 필드는 제외했다. 원본과 공개본의 건수·SHA-256, 선택 필드는 [manifest.json](manifest.json)에 기록했다. 이벤트 payload와 시각은 바꾸지 않았다. 같은 실행의 누적 로그가 여러 파일에 있으므로 파일을 합칠 때는 `runId`와 `sequence`로 중복을 제거하며, 파일별 도착 횟수를 단순 합산하지 않는다. 좌표는 에뮬레이터와 공개 시험 POI다.

- `original-events.jsonl`: 0.1.0 진단 앱의 기존 도착2회·취소. APK 해시 `32fe31a755321534ba4978d852f0b0200651a9561cc933b597b6d919d56bc585`.
- `followup-arrival-events.jsonl`: 0.1.1 정상 권한 APK에서 좌표·POI를 일치시킨 추가 도착1회. 이동 전체의 신선한 조회 좌표 열은 확보하지 못했다.
- `permission-denied-events.jsonl`, `permission-restored-events.jsonl`: 권한 미선언 빌드와 정상 선언 빌드. 같은 진단 패키지 교체 시험이며 런타임 거부 대화상자 시험이 아니다.
- `reroute-events.jsonl`: 수동 재탐색 요청 이후 30.760초 동안 완료 이벤트가 없는 결과.
- `gps-before-events.jsonl`, `gps-after-events.jsonl`: Android GPS 입력 전후 SDK 조회. 내비 위치 갱신 성공을 뜻하지 않는다.
- 화면: 일반 모의주행 패널, host GPU의 안내·주행 표시, swiftshader 복원 후 검정 재현.

`DestinationArrivedInfo`에는 routeId가 없다. 직전 활성 경로·목적지·시각을 함께 대조해야 하며, 임의 요청 ID를 SDK 응답 연결의 보증으로 사용하지 않는다. GPS·재탐색 및 제품 어댑터의 남은 조건은 #80 담당자가 검토·정리한다.
