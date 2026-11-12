# #40 격리 전용 계측

아래 소스 두 파일은 실제 장치 실행본과 바이트가 같다. 이 시험은 지정 격리 패키지, 각 실행의 새 원장과 제어 입력을 요구하므로 일반 Android 계측 소스셋 밖에 보존한다.

1. 일회용 사본의 기준을 `0bbaeba314f241923de201d14872e431d5c926ee`로 고정한다. `HostSimulationProvider.kt`는 `app/src/debug/java/io/drivegrowth/app/host/`에, `UnifiedSimulationRestNegativeEvidenceTest.kt`는 `app/src/androidTest/java/io/drivegrowth/app/simulation/`에 놓는다. 실행 트리는 `4b45d30b816c809d7581eb29406751d7689596aa`다.
2. JDK 17·SDK 35에서 이 폴더의 `isolation.gradle`을 init script로 지정하고 `-PverificationPackage=io.drivegrowth.verify.restnegativef :app:assembleDebug :app:assembleDebugAndroidTest`를 실행한다. 앱과 시험 APK의 빌드·설치 회수 해시를 확인한다.
3. 각 실행은 원장 없는 새 격리 설치에서 시작한다. runner는 `io.drivegrowth.verify.restnegativef.test/android.test.InstrumentationTestRunner`다. 아래 클래스의 각 메서드를 `-e class <class>#<method>`로 한 번에 하나씩 지정한다.

클래스는 `io.drivegrowth.app.simulation.UnifiedSimulationRestNegativeEvidenceTest`다.

| 메서드 | 입력 |
| --- | --- |
| `testOutsideAndMissingParkDoNotRefreshOrConsumeTheExistingRestGrant` | 장소 지정 파일 없이 한 번 실행 |
| `testQualifiedDrivingAndParkingGrantOnlyAtTheRecognizedPlace` | 각각 새 설치의 앱 내부 파일 `files/rest40-location.txt`에 `OUTSIDE`, `MISSING`, `RECOGNIZED` 중 하나를 넣고 별도 실행 |

[실행·APK·원장 식별](../metadata.json)에서 설치 해시와 원결과를 대조한다. 시험 DB는 문서에 첨부하지 않았다.
