# #54 격리 계측 재현 소스

두 Kotlin 파일은 2026-11-13 장치에서 사용한 소스와 바이트가 같다. 일반 `app/src/androidTest` 밖에 보존한다. 준비된 SIMULATION 원장, 지정 격리 패키지, 콘솔 D/P 전환 또는 시험 프로세스의 표식 기반 종료가 필요한 계측이다.

1. 제품 기준 `53db64fa0ac63da108faa323c9ed33cfa99135a0`의 일회용 사본에서 두 파일을 `app/src/androidTest/java/io/drivegrowth/app/host/`로 복사한다. 실행 트리는 `02b6f1ae7038e9e9c3f2cc69744486130f2ccd14`다.
2. JDK 17·SDK 35로 앱과 시험 APK를 빌드한다. 별도 빌드 설정으로 앱 ID `io.drivegrowth.verify.results54originalf`와 계측 ID `io.drivegrowth.verify.results54originalf.test`를 적용한다. [APK·원장 식별](../metadata.json)의 해시를 대조한다. #204에서 회수한 SIMULATION DB가 있어야 원 ID를 그대로 대조할 수 있다. 원장 파일은 이 기록에 첨부하지 않았다.
3. `android.test.InstrumentationTestRunner`에 `-e class io.drivegrowth.app.host.<class>#<method>`로 아래 메서드를 각각 지정한다. 원본 DB는 격리 패키지의 `databases/game-ledger-simulation.db`에만 공급한다. 두 종료 시험에는 각각 같은 준비 완료 DB를 독립 설치에 복사한다.

| 클래스 | 메서드 | 장치 제어와 판정 |
| --- | --- | --- |
| ActualMainStoredResultSourcesVerificationTest | testPrepareFromCapturedSimulatedOriginals | 원장 준비와 기존 정산 대조 |
| ActualMainStoredResultSourcesVerificationTest | testStoredOriginalsInMainAndRestoreRentalDetailAfterActualDrivePark | 요청 표식에서 D 정차, 새 Activity의 같은 PID SDK D를 받은 뒤 P |
| ResultsViewedInterruptionVerificationTest | testHoldInsideRealTransactionBeforeCommit | `BEFORE_SQLITE_COMMIT` 표식의 격리 PID만 종료하고 SQLite 0/0 확인 |
| ResultsViewedInterruptionVerificationTest | testHoldAfterRealCommitBeforeHostResponse | `AFTER_SQLITE_COMMIT_BEFORE_RESPONSE` 표식의 격리 PID만 종료하고 SQLite 1/1 확인 |

종료 뒤에는 새 PID의 실제 Main에서 동일 원정산을 조회한다. runner의 의도 중단 결과와 별도로 SQLite·화면을 판정한다. [실행 로그와 실패 이력](../run-excerpts.txt)
