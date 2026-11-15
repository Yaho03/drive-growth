# 첫 차량 격리 앱 최종 독립 재검토

**APPROVE — 4파일 변경과 `firstcarrecheck`의 재시도 포함 콜드 복원 근거.** 실제 AppGrid 아이콘 실행과 기존 cold-test 두 selector의 재실행은 이 판정의 장치 근거에 포함되지 않는다.

- 소스 기준 HEAD `709e08dcb9e8be0f98c3ccfba2ba7b33b51f12f0`. `git diff --binary HEAD` SHA-256 `71d5ff2befcdb7cb1301db3babb3668ce1a9e81394950cc4266f5e0328f1e5dd`. 저장된 `first-car.patch`와 바이트 일치, diff 공백 검사 통과. 최종 커밋 `6070445c154455c2b3f8c9e777ac2f5c04cb014b`과 기준 HEAD 사이 diff도 시험 시점의 diff 및 저장된 패치와 바이트 일치한다.
- `OnboardingGrantColdRecoveryTest.kt:219–237,262–292`의 전체 정산 reader 단언이 확정 전 0건, 지급 및 재실행에 원래 정산과 동일한 `INITIAL_GRANT` 1건을 요구한다. 보유 캐스퍼 1대, 잔액 1,000과 차고 3칸을 독립 기대값으로 검사한다. 기존 cold-test 두 메서드의 별도 `onboardingcold56` guard, 표식, 실행 단계는 유지된다. 공유 `readOriginalGrant()`에만 더 강한 읽기 검사가 적용되므로 그 기존 두 메서드의 실제 회귀 통과는 재실행 근거가 필요하다. 신규 세 selector는 `firstcarcheck` 또는 `firstcarrecheck`에서만 진행한다. 패키지별 문서의 메서드 단위 selector를 사용한다.
- 표준 Debug APK manifest의 `io.drivegrowth.app.simulation`과 기존 표시명 `르르르 렌터카 · 모의 시연`, 표준 Release의 `io.drivegrowth.app`과 `르르르 렌터카`를 아티팩트 기록에서 확인했다. 시연용 `firstcarplay`는 별도 패키지와 표시명이며, PackageManager가 표준 런처 인텐트를 해당 Activity로 해석했다. AppGrid에서 실제 아이콘 실행을 증명한 파일은 없었다. 시연용과 시험용 설치 결과를 합산하지 않는다.
- 최종 계측용 앱 `firstcarrecheck.apk` SHA-256 `0bd6bdf125d0c99496293bf1f47307265219d6ffb84440c5959a79b99fa0a25d`, 시험 `firstcarrecheck-test.apk` SHA-256 `f53b7f811fd8ed6eb6c34750b8861fdbbf4e8e6e0750bc51466398788f973c58`. 두 로컬 파일을 직접 해시하고 설치 해시 기록과 대조했다.
- `final-check`에서 확정 전 PID 9449, 지급 PID 23902, 복원 PID 28196이 서로 다르고 세 표식의 프로필이 같음을 확인했다. 지급 표식과 복원 표식의 grant 객체는 전체 일치한다. 첫 두 호출은 표식 뒤 의도적으로 종료하는 단계다. 최종 복원 호출은 16.862초, `OK (1 test)`, `INSTRUMENTATION_CODE: -1`. 지급 및 복원 PNG가 남아 있다.
- 최초 지급 호출은 selector 시작 전에 ActivityManager `Killing ... remove task`와 함께 중단됐다. 해당 구간에서 대상 앱 예외 stack은 확보되지 않았다. 원래 확정 전 표식·같은 격리 설치에서 지급부터 재시도해 복원까지 완료했다. 시스템에 의한 프로세스 종료는 관측됐고, 시스템 task 제거의 상위 원인은 확정하지 않았다.
- 기존 제품 설치 APK 해시와 user 10 설치·CE inode는 전후 일치했고, 표준 모의 앱은 전후 부재로 기록됐다. 이 fingerprint 범위는 기존 데이터 파일 내용의 바이트 대조까지 포함하지 않는다. 격리 APK·test APK만 설치 대상으로 삼는 호스트 코드와 제품 저장 구현 변경 0개를 소스에서 확인했다.
- 검토 C는 기존 소스·패치·빌드 및 장치 기록·바이너리를 읽어 비교했다. 장치 실행과 빌드는 A의 기록이며, C의 ADB·빌드·제품 수정은 0회다. 문서 패치는 별도 검토 대상이다.
