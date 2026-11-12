# #57 기간 경과 청구 복원 계측

[계측 소스](ProgressionPeriodRecoveryMainTest.kt)는 2026-11-13 장치 실행에 사용한 파일과 바이트가 같다. 전용 앱 ID, 새 격리 원장, 준비 후 프로세스 종료·재시작 순서가 필요한 지정 실행용 계측이다. 일반 `app/src/androidTest` 소스셋 밖에 둔다.

1. 제품 기준 `ea1eafefe3621e658b186968731f5eda40077c39`의 일회용 사본에서 계측 파일을 `app/src/androidTest/java/io/drivegrowth/app/host/progression/`에 복사한다.
2. JDK 17·SDK 35로 앱 ID `io.drivegrowth.verify.questperiode`와 계측 ID `io.drivegrowth.verify.questperiode.test`를 적용해 앱과 계측 APK를 빌드한다. 기기 슬롯을 인계받은 뒤 격리 패키지에 필요한 속도 권한과 실제 SDK P 허용을 확인한다. 기존 제품 앱과 DB를 사용하지 않는다.
3. 각 행마다 별도의 새 격리 설치에서 `android.test.InstrumentationTestRunner`에 `-e class io.drivegrowth.app.host.progression.ProgressionPeriodRecoveryMainTest#<method>`를 지정한다. 준비를 실행한 뒤 격리 앱만 `am force-stop`하고 PID 종료를 확인한다. 설치와 DB를 유지한 채 복원을 실행해 새 PID를 확인한다.

| 경계 | 준비 메서드 | 새 PID 복원 메서드 |
| --- | --- | --- |
| 기간이 지난 일간 SAVED | `testSeedExpiredDailyGrowthSaved` | `testColdMainCancelsExpiredDailySavedAndShowsCurrentPeriod` |
| 이전 주간 STARTED | `testSeedPreviousWeeklyRentalStarted` | `testColdMainClearsPreviousWeeklyStartedAndPreservesSettlement` |

기간 준비에는 시험 시계를 사용한다. 복원은 장치 현재 시간으로 실행한다. 설치 APK 식별과 판정 범위는 [06 실행 기록](../../../06_VERIFICATION.md#57-실제-main-상세-스크롤과-기간-경과-청구-복원--2026-11-13)에 있다.
