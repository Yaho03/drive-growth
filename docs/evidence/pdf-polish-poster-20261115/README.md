# PDF 화면 보완과 #300 선택 적용의 기기 근거

2026-11-14~15 KST에 복제 Pleos 2.1.0/API 34 ARM64, Android 사용자 10에서 촬영한 앱 화면이다. 사진은 모두 모의 계정이다. 배포한 앱 버전과 기존 시험의 권위는 [검증 문서](../../06_VERIFICATION.md#pr-300-타이틀기능-창도감-선택-적용--2026-11-14)에서 구분한다.

| 실제 화면 | 앱 창 | 설치 앱 SHA-256 | PNG SHA-256 |
| --- | --- | --- | --- |
| [르르르 포스터와 시작 버튼](title-1200.png) | 1200×800 | `14805b1713989081001218633176c890551c19fe316d8d3f5e20b642df5d89a2` | `3732234f258cde2a178b65202ef5096ec3c02932ae9ef0e3165513019a0b574f` |
| [조회 완료 HOME과 크림 HUD](home-1200.png) | 1200×800 | `0109078ea4b04703fe9629dcb3f8d39baab6936012c48072103f39a286389f8a` | `0475f58b69bab40ef9d9b542f53595f58ec0575a1995cc9933b90bf2345d59db` |
| [세로 도감의 고정 필터와 색상](collection-480.png) | 480×700 | `14805b1713989081001218633176c890551c19fe316d8d3f5e20b642df5d89a2` | `f7249404f475e41dc1d11ff05eea7da7875145d838097d26b1f9d68e19ce18fd` |
| [모의 목적지 및 탁송 후보](delivery-1200.png) | 1200×800 | `ad0c465b6805974bc1e369d8eb2b641610147373ebdaa36a95c5a097f4ca5804` | `61d4cbc888c7bef4037c02411920229af79a13ab3136812c725b127532138f74` |
| [무료 뽑기 뒤 최초 획득 칸](collection-new-702.png) | 702×438 | `0127bb738a4d173f00dde39536671164d018469f4ad2e21854ecb9e84a60657f` | `3b4245b82d47713370a9f83120923b37e911a75cbd70ecf9657d63b729de87f2` |

시험은 격리 applicationId로 조립한 Debug APK를 사용했다. 정규 빌드의 해시와 구분한다. #300 통합 이후 추가한 최상위 Back 재실행과 기본 창 셔터 기록은 시험 설정·진단 변경이며 제품 코드는 같다.

- `PrototypeMainFlowDeviceTest#testPr300ActualWindowsAndCaptures`와 도감·시작화면·기능 창을 포함한 장치 회귀 134건을 통과했다. 창은 1200×800, 480×700, 650×420이다.
- `testStoredFirstGrantSettlementAndGachaCelebrateOnlyOnFirstDisplay`는 기본 702×438, 보조 1200×800에서 각각 통과했다. `testPr300MainRestoresCollectionViewport`는 활동 재생성 뒤 미발견 필터와 스크롤 320px을 확인했다.
- `testFirstCompletedRentalShowsGiftAndOpensGachaWithoutDrawing`는 첫 기본 디스플레이 실행에서 저장된 정산 이후 셔터 표시를 기다리다 실패했다. 셔터 시점의 실제 창과 포커스가 기록되지 않았다. 후속 702×438 기본 창 두 회차와 보조 창 1회는 통과했다. 기본 창 후속에서는 확정 버튼의 가시 영역, 확정 요청 1건, 무료권 0장을 각각 기록했다. 첫 실패 원인은 미확정이다.
- `NavigationTest#testTopLevelBackHidesTask`는 처음에 권한 창이 전면을 차지했다. 권한 창을 처리하고 Main 포커스를 확인한 재실행에서 앱 숨김·제한 표시·동일 Task 복귀 1건이 통과했다. 시험용 속도 권한을 회수했다. 이전 차량 입력 지연 시험도 통과했다.
- PDF 기준 APK에서 실제 CarService PARK 전환 후 대여 출발·반납을 확인했다. 이 결과는 #300 후보의 차량 SDK·실차 재검증과 구분한다. #300 후보의 동일 조건 성능 비교는 미수행이다.
