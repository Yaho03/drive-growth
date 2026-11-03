# 통합 온라인 설명회 핵심 내용

## 1. 출처와 확인 범위

- 원본: [현대자동차그룹 차량용 앱 개발 공모전 통합 온라인 설명회](https://www.youtube.com/watch?v=na6tSJ44pWc), 게시 채널 `agency_`.
- 설명회 개최: **2026-10-28 14:00 KST**. 영상 게시: **2026-10-30**. 이 문서 확인: **2026-11-03 KST**.
- 영상 설명란의 35개 챕터와 **00:00~42:32의 제공 스크립트 312개 구간을 모두 읽었다.** 자막 내보내기는 실패했으나 YouTube의 ‘스크립트 표시’에서 본문을 확인했다. 설명란이나 챕터 제목만으로 작성한 요약이 아니다.
- 확인 방식은 제공 스크립트의 전체 독해다. 영상 전체의 음성을 직접 청취하거나 모든 장표를 시각적으로 대조한 것은 아니다. 불명확하게 표기된 발화는 추정하지 않는다.
- 이 문서는 **발표 시점의 안내·타임스탬프·차이점**을 관리한다. 제출 절차의 기준은 [공모전 안내](01_CONTEST_GUIDE.md), SDK 조건의 기준은 [개발 자료](02_PLEOS_DEVELOPMENT.md)다. 기획·구현 완료를 새로 선언하는 문서가 아니다.

## 2. 팀이 먼저 확인할 내용

| 중요 내용 | OT 근거 | 우리 팀에서의 의미 |
|---|---|---|
| 1차에는 기획서와 전원 서명 동의서가 필수이고 개발 자료는 가산점 대상 | [07:26~08:10](https://www.youtube.com/watch?v=na6tSJ44pWc&t=446s) | 공식 필수 조건과 팀이 목표로 하는 구현 수준을 구분한다. 1차에 완성 앱이 없다는 이유만으로 지원 불가로 판단하지 않는다 |
| 2차 PT 전에 작동하는 결과물을 완성해야 함 | [14:48~15:45](https://www.youtube.com/watch?v=na6tSJ44pWc&t=888s) | PT 뒤 전시 준비 기간을 미완성 기능 개발 기간으로 계산하지 않는다 |
| 생성형 AI를 기획·개발 전 과정에서 활용 가능 | [20:29~20:48](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1229s) | 참가자가 구조와 로직을 이해하고 기술 질의에 직접 답해야 한다. AI 사용 자체가 참가 제한은 아니다 |
| 공모전에서 실차 시연은 지원하지 않음 | [21:04~21:20](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1264s) | 공식 에뮬레이터 검증을 준비한다. 실차 지원을 기다리는 것을 착수 조건으로 두지 않는다 |
| 개발 단계에서는 별도 SDK 사용 승인 없이 권한 선언으로 시험 가능 | [32:29~33:14](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1949s), [38:17~38:42](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2297s) | 개발용 사전 승인과 앱 마켓 배포 심사를 분리한다. Android 실행 중 권한 요청이나 개별 API 설정까지 없다는 뜻은 아니다 |
| 외부 API와 공용 네트워크 사용 가능 | [37:49~37:56](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2269s) | 외부 데이터 연계는 검토 가능하다. 특정 휴게소 API 제공·사용권·정확도까지 보장하는 답변은 아니다 |
| SDK 개수보다 고객 문제와 핵심 기능의 연결이 중요 | [32:02~32:18](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1922s), [36:33~36:42](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2193s) | 필요한 기능의 목적과 차량 특화 가치를 설명한다. 이 권고를 팀이 요청한 추가 기능의 임의 제외 근거로 사용하지 않는다 |

## 3. 전체 구간별 중요 내용

타임스탬프는 영상 설명란의 챕터 시작 시각이다. 세부 발화는 몇 초 앞뒤에 걸칠 수 있다.

| 구간 시작 | 주제 | 확인한 요점 |
|---|---|---|
| [00:00](https://www.youtube.com/watch?v=na6tSJ44pWc&t=0s) | 진행 안내 | 공모전 안내·운영 Q&A·개발 안내·기술 Q&A 순서. 현대자동차그룹·42dot 담당자가 답변 |
| [01:55](https://www.youtube.com/watch?v=na6tSJ44pWc&t=115s) | 공모전 취지 | 운전자·동승자의 서로 다른 이용 상황에서 출발하고 차량 정보·하드웨어·이동 맥락을 경험에 연결 |
| [03:58](https://www.youtube.com/watch?v=na6tSJ44pWc&t=238s) | 개요 | 미완성 앱도 기획서로 1차 지원 가능. 개인 또는 최대 5인, 접수 10/21~11/15 |
| [05:04](https://www.youtube.com/watch?v=na6tSJ44pWc&t=304s) | 참가 대상·분야 | 대학원생은 대학생 부문에 포함. 일반 개발자·스타트업 참여 가능. 공모 분야 제한 없음 |
| [05:42](https://www.youtube.com/watch?v=na6tSJ44pWc&t=342s) | 시상·혜택 | 본상 10팀·총 5,100만 원. 아이디어 특별상도 개발 산출물 필요. 상금 표는 공모전 안내 참조 |
| [07:22](https://www.youtube.com/watch?v=na6tSJ44pWc&t=442s) | 일정 | 1차 마감과 PT 전 개발 완료를 구분. 12/18 수상팀 발표, 2월 순위 공개·시상 |
| [08:44](https://www.youtube.com/watch?v=na6tSJ44pWc&t=524s) | 접수 | 공식 양식·모든 참가자의 동의 및 서명, 이메일 제출과 전원 CC. 앱명으로 작품 구분 |
| [09:37](https://www.youtube.com/watch?v=na6tSJ44pWc&t=577s) | 필수·선택 | 기획서·동의서는 필수, 프로토타입 영상·GitHub는 선택 가산점. 숏폼은 별도 행사 |
| [10:35](https://www.youtube.com/watch?v=na6tSJ44pWc&t=635s) | GitHub·APK | APK는 이메일 첨부 대신 저장소/Release 링크로 제공. README에 실행·환경·설정·파일 위치 명시. 비공개 저장소 접근 확인 |
| [11:23](https://www.youtube.com/watch?v=na6tSJ44pWc&t=683s) | 수정 제출 | 같은 발신 주소로 마감 전 전체 자료·동의서를 재제출하고 전원 CC. 마지막 제출본을 최종본으로 처리 |
| [12:27](https://www.youtube.com/watch?v=na6tSJ44pWc&t=747s) | 심사 | 차량특화성·창의성·기술 활용성·사업성. SDK가 핵심 사용자 경험에 어떤 역할을 하는지와 지속 이용 가능성 설명 |
| [13:42](https://www.youtube.com/watch?v=na6tSJ44pWc&t=822s) | 결과 발표 | 11/23 1차 결과, 12/3~4 서울 UX 스튜디오 PT, 12/18 수상팀 발표. 순위는 2차 결과물·PT로 결정하고 2월 공개 |
| [14:39](https://www.youtube.com/watch?v=na6tSJ44pWc&t=879s) | PT·전시 | 1차 결과물은 PT 전 보완 가능. 12/19~다음 해 1월 중순은 전시 영상·현장 APK 시험 준비. 신규 기능 개발 기간으로 설명하지 않음 |
| [16:02](https://www.youtube.com/watch?v=na6tSJ44pWc&t=962s) | 숏폼 | 30초~1분 세로 영상, 릴스/쇼츠, 필수 해시태그와 양식의 링크. 본상과 독립 심사. Spot/Solve/Scene은 예시 |
| [17:22](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1042s) | 제출 전 점검 | 서명·링크 접근·실행 정보·숏폼 권리·해시태그 확인. 숏폼에서 차량 내외부 이미지 노출을 최소화하도록 안내 |
| [17:59](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1079s) | 운영 Q&A | 일반인 포함 팀은 일반 부문. 서로 다른 작품은 수량 제한 없이 출품 가능. 외국인·미성년자 조건은 별도 확인 |
| [20:28](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1228s) | AI Q&A | 생성형 AI 활용 가능하되 기술 이해와 직접 설명 책임은 참가자에게 있음 |
| [21:05](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1265s) | 시연·참가 Q&A | 실차 시연 미지원. 임직원은 참여 가능하지만 수상·시상·상금 제외. 대학생 창업자는 대학생 부문 안내 |
| [22:38](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1358s) | Playground | 플랫폼 이해부터 설계·SDK·시험·등록까지 연결하는 개발자 포털로 소개 |
| [24:06](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1446s) | Pleos Connect | 현대·기아·제네시스용 AAOS 기반 플랫폼. 주행 여부·차량 상태·운전자/동승자 사용 맥락 고려 |
| [25:07](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1507s) | App Market | 모바일 화면 이식 이상의 차량 특화 가치 강조. 25:56에 미공개 SDK 제공 예정 발언이 있어 아래 별도 해석 참조 |
| [26:18](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1578s) | 개발 절차 | 해결할 고객 문제 → 필요한 차량 정보·기능 → SDK·환경으로 구현할 방법을 먼저 정리 |
| [27:28](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1648s) | 시작 안내 | Getting Started의 프로젝트·에뮬레이터·첫 API 호출 순서. Java/Kotlin, API 28 이상·Gradle 8 이상 안내 |
| [29:37](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1777s) | SDK 개요 | 각 SDK의 사용 목적 소개이며 모든 API의 지원 목록을 열거한 발표는 아님 |
| [30:14](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1814s) | Vehicle | 도어·주행·배터리·충전·공조 등의 상태와 권한 범위 내 제어. 차량 데이터를 주요 기능에 연결 |
| [30:59](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1859s) | 내비·음성·위치 | NaviHelper의 목적지·경유지·경로 연동, TTS·STT·FusedLocation 소개. SDK 수 자체가 평가 목적은 아님 |
| [32:31](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1951s) | 권한 | 공모전 개발과 App Market 배포의 승인 절차를 구분. 개발 시 문서에 지정된 Manifest 권한 선언 |
| [33:48](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2028s) | 에뮬레이터 | 차량 없이 화면·SDK 기능을 지원 범위 안에서 시험할 환경 제공 |
| [34:33](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2073s) | Virtual Console | 기어·도어·배터리 등 모의 차량 상태를 변경하고 SDK 수신·앱 반응을 반복 확인 |
| [37:09](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2229s) | 기술 Q&A 시작 | 채팅으로 받은 기술 질문에 답변 |
| [37:49](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2269s) | 외부 연계 | 외부 API와 공용 네트워크 연결 허용 안내 |
| [38:14](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2294s) | SDK 승인 Q&A | Vehicle·NaviHelper·STT·TTS·FusedLocation의 개발 단계 사전 승인 불필요 재확인. 상세 질문은 이메일·FAQ로 후속 대응 |
| [40:18](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2418s) | 요점 재정리 | 1차 필수 서류·전원 CC, 선택 개발 산출물 가산점, PT 전 완성, 차량 특화 핵심 가치 강조 |
| [40:57](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2457s) | 설문 | Playground 사용 경험 설문을 다시보기·팝업과 함께 안내 예정. 일부 피드백 제공자 선물 안내. 참가 필수로 설명하지 않음 |
| [41:44](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2504s) | 마무리 | 최신 일정·추가 답변은 홈페이지 공지와 운영사무국 이메일로 확인 |

## 4. 문구 차이와 확정하지 않은 사항

| 항목 | 확인 내용 | 정리 기준 |
|---|---|---|
| 숏폼 상금 | 06:35 스크립트에 500만 원과 화면·공식 안내 50만 원이라는 보정이 함께 표기됨. 10:08·16:43은 50만 원 | [현재 숏폼 공식 안내](https://appdevchallenge.auto-hmg.io/guide/shortform)의 **50만 원** 유지. 음성 전체 직접 확인으로 오기 원인까지 판정한 것은 아님 |
| 메일 제목 | 09:13·12:10에는 앱명과 팀명/개인 이름을 넣는 안내. 현재 웹 양식은 팀명·대표자명 형식 | [공식 제출 페이지](https://appdevchallenge.auto-hmg.io/guide/submission)의 정확한 형식과 차이를 공모전 안내에 함께 기록. 복수 작품 구분 및 최종 제목은 접수 직전 확인 |
| 소스·APK 구성 | 09:50 스크립트는 소스 또는 APK라는 표현, 13:24와 제출 페이지는 소스+APK 안내 | 발표의 한 문장만 근거로 APK 단독 제출 허용이라고 바꾸지 않음. 현행 제출 페이지의 함께 제공 조건 유지 |
| 미공개 SDK | [25:56](https://www.youtube.com/watch?v=na6tSJ44pWc&t=1556s)에 공모전 제공 예정 발언 | 제공 의향·계획 근거로 기록. 구체적인 목록·배포일·계정 접근·실행 성공이 확인된 것으로 확대하지 않음 |
| 주행 중 Activity 정책 | [39:01~39:16](https://www.youtube.com/watch?v=na6tSJ44pWc&t=2341s)에 Activity Distraction 언급 후 불명확 발화, 상세 답변 후속 안내 | 주행 중 게임 화면·음성을 허용받았다고 해석하지 않음. 개별 앱 정책 확인은 계속 필요 |
| 참가·심사 정보 | 외국인·미성년자 조건은 추후 확인, 심사 GitHub 계정은 구체 아이디 미언급 | 복수 작품 허용과 복수 팀 소속 허용도 구분. 실제 계정·발표 시간·최종 제출 시각은 별도 확인 |

## 5. 기존 문서 반영과 후속 확인

2026-11-03에 OT를 근거로 이전 ‘미확인’ 중 혼합팀, 복수 작품, AI 활용, 실차 시연 지원 여부, 개발 단계 SDK 사전 승인 조건을 보완했다. 이는 공식 자료의 확인 상태 변경이며 제품 범위를 새로 확정한 결정이 아니다.

- [공모전 안내](01_CONTEST_GUIDE.md): 참가·AI·시연·일정·제출 차이·남은 질문 갱신.
- [개발 자료](02_PLEOS_DEVELOPMENT.md): 공모전 개발 조건과 일반 배포 정책 구분, 외부 API 허용, 미공개 SDK 계획의 한계 반영.
- [차량 API 자료](03_VEHICLE_API.md): 승인 설명을 위 기준으로 연결. API별 지원·주행 정책 질문은 유지.
- [공식 자료 목록](04_OFFICIAL_RESOURCES.md): 실제 다시보기 URL·확인 방법 등록. 전체 자막이나 영상을 저장소에 복제하지 않음.
- [프로젝트 계획](../08_PROJECT_PLAN.md): 관련 작업에서 OT를 확인할 위치 연결. 팀 기능·담당·마감일은 임의 변경하지 않음.
- 기존 PRD·화면·산식·앱 코드·테스트 결과는 이번 OT 조사로 변경하지 않음. 미공개 기능이나 주행 중 게임 허용이 확인된 것으로 기록하지 않음.
- **원격 이슈는 이번 작업에서 조회·수정하지 않았다.** 계획 문서에 연결된 #12·#13·#14·#21의 승인 대기·시연 환경·AI 설명 준비·제출 조건 반영 여부는 미확인이다. 담당은 미정이며 각 작업 착수 시 확인한다. 기존 검증 결과를 이번 OT 기준의 실행 성공으로 바꾸지 않는다.

## 6. 영상 속 웹 도구 후속 조사

2026-11-03 후속 요청으로 22:38의 Pleos Playground와 34:33의 Virtual Console을 구분해 조사했다. Playground 실제 메뉴의 펼친 6단계·Publish 하위 심사 문서·Workspace 접근 조건·디자인 다운로드 경로는 [공식 자료 목록](04_OFFICIAL_RESOURCES.md) 0절부터, 로컬 웹 콘솔의 연결·조작 범위는 [개발 자료](02_PLEOS_DEVELOPMENT.md) 5절에서 관리한다. 메뉴 소개만 읽고 SDK 전체 또는 계정 내부까지 확인한 것으로 표시하지 않는다.
