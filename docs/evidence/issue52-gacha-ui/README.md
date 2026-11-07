# #52 가챠 수정본 독립 검증 공개 근거

별도 독립 검증 A가 제품 소스 `6bedb76a27dcf06b5a14ea782d1d19de91fe1c08`에서 수행한 결과다. 이 증거를 정리한 구현 세션은 시험·adb를 다시 실행하지 않았다. 판정/범위는 [보고서](REPORT.md), 제품7파일 Git blob·설치 해시·원본/공개 산출물 SHA-256은 [metadata](metadata.json)를 따른다. 개인 경로·APK 바이너리·전체 시스템 dump는 제외했다.

- [장치 결과 원문](device-tests.txt): 정식9+임시 실제 호스트2=11개, 7.116초, 실패/오류0.
- [표적 단위·빌드 로그](targeted-build.txt): 정식20+[독립7](GachaIndependentBoundaryTest.kt)=27개, 실패/오류/생략0. 작성자 전체 core225/app139·Lint는 재실행하지 않았다.
- [최종 실행 호스트 로그](host-log.txt): 원시 동일 태그 로그 중 최종 PID31757 행만 발췌했다. 이전 실행 행과 구분하며 원시 로그는 검증자의 로컬 증거에 보존한다.
- [실제 설치 해시](installed-sha256.json), [설정·제품 파일 전후 발췌](restoration.json): 제품 APK·기존6파일·권한/설정 보존, 격리 패키지 제거·내비 복귀. snapshot의 미지원 instrumentation 명령 응답은 활성 시험 없음의 성공 근거로 쓰지 않는다.
- [현재 이동 token 복원](current-token-restored.png): 고정 외부 결과의 현재 이동 잠금. 잠금/응답 대조는 로그와 시험 assertion을 함께 본다.
- [320dp 긴 후보](long-candidates-320dp.png), [936dp 가로 호스트](guaranteed-936dp.png): 호스트 내부 폭 시험이며 공식 폼팩터 인증이 아니다. 주변 차량/공조는 OEM 셸이고 제품 자산이 아니다.

## 격리 재현 방법

장치 점유를 메인과 조율한 검증자가 고정 커밋의 **별도 임시 사본**에서만 수행한다. 제품/다른 작업자의 폴더에는 호스트나 독립 시험을 추가하지 않는다. 기존 JDK17·Android SDK 환경을 사용하며 키/SDK/비밀값은 이 증거에 포함하지 않는다.

1. `6bedb76`의 임시 사본에 독립 단위 소스를 `app/src/test/java/io/drivegrowth/app/ui/gacha/GachaIndependentBoundaryTest.kt`로 복사한다.
2. [재현 호스트](temporary-host/java/io/drivegrowth/app/ui/gacha/GachaReviewHostActivity.kt)·[추가 장치 시험](temporary-host/test/io/drivegrowth/app/ui/gacha/GachaNativeReviewTest.kt)·[격리 Manifest](temporary-host/AndroidManifest.xml)·[init script](temporary-host/isolate.gradle)를 사용한다. 호스트/시험/Manifest는 실행 원본과 동일하며 init script는 원래 임시 절대 경로만 `GACHA_REVIEW_HOST` 환경변수로 바꿨다. 이 경로 치환본을 새로 실행한 결과는 아니다.
3. `GACHA_REVIEW_HOST`에 이 `temporary-host` 디렉터리의 절대 경로를 지정하고 아래 명령을 실행한다. 독립 단위7개가 정식 제품 시험에 편입된 것은 아니다.

```sh
./gradlew --offline --no-daemon   -I "$GACHA_REVIEW_HOST/isolate.gradle"   :app:testDebugUnitTest --tests '*Gacha*Test'   :app:assembleDebug :app:assembleDebugAndroidTest
```

4. 설치 전 aapt로 격리 앱 `io.drivegrowth.app.gacha52finala`·시험 `.test`와 target을 확인한다. 실제 실행 당시 설치본 해시는 metadata와 installed-sha256에 기록했다. 빌드 해시와 실제 설치 해시를 따로 대조한다. 설치 거절을 제품 제거/데이터 삭제로 해결하지 않는다.
5. 승인된 장치 슬롯에서 해당 두 격리 APK를 설치한 뒤 실행한 실제 명령은 다음과 같다.

```sh
adb -P 5038 -s emulator-5556 shell am instrument --user 10 -w -r   -e class io.drivegrowth.app.ui.gacha.GachaViewTest,io.drivegrowth.app.ui.gacha.GachaNativeReviewTest   io.drivegrowth.app.gacha52finala.test/android.test.InstrumentationTestRunner
```

실제 경제/차량 SDK 입력·제품 탐색·Activity Bundle/프로세스 영속 복원·실차 시험은 아니다. 새 View 생성자 복원과 고정 표시/명령 이벤트 경계를 확인했다. 현재 구현 세션은 위 재현 명령을 다시 실행하지 않았다.
