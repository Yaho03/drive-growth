# 픽셀 디자인 키트 (작업 중)

쿼터뷰 픽셀 스타일로 최종 화면을 교체하기 위한 디자인 작업 모음이다. 교체 방향은 [09 화면 명세 1절](../../09_UI_UX_SPEC.md#1-ui-구현-기준과-산출물)에 있다.

- 담당: @hoyeol903 — 지도, 본관, 화면 시안, 이 키트
- 사람·시설·소품·아이콘(#171)과 차량 20종·희귀도 테두리(#172): @rudals0414

**작업 중 자료다.**
- 앱 자산(`app/src/main/res`)으로 넣지 않았다. 디자인 완료나 최종 미술로 보지 않는다.
- 그림 속 숫자는 PRD 시험값이나 예시다.
- 실제 앱 적용은 그림이 확정된 뒤 별도 작업에서 한다.

## 읽는 순서

1. [asset-spec.md](asset-spec.md) — 제작 기준서: 공통 규칙, 게임 규칙에 맞춘 그림 목록, 넘기는 방법, 앱 쪽 변경 요청
2. 공용 팔레트·크기 — 아래 「공용 팔레트와 크기」
3. [map/](map/README.md) — 가게 지도: 바닥, 레벨별 덮개, 주차 칸 좌표
4. [building/](building/README.md) — 가게 본관 Lv1 / Lv2 이상
5. [prototype/](prototype/) — 위 그림과 화면 구성을 PRD 시험값으로 묶은 HTML 시제품
6. [drafts-v2/](drafts-v2/) — 확정한 2차 시안 A(Claude Design) 그림
7. [briefs/](briefs/) — Claude Design에 넣은 요청서

## 공용 팔레트와 크기

2차 시안 A가 실제로 쓴 32색을 그대로 공용 팔레트로 정했다.

| 파일 | 내용 |
|---|---|
| `palette.png` | 32색 원본 (32×1px) |
| `palette.gpl` | Aseprite·GIMP·Pixelorama 팔레트 |
| `palette.hex` | 색 코드 목록 |
| `palette-swatches.png` | 색 견본 (묶음·용도) |
| `size-sample.png`, `size-sample@4x.png` | 차 기본형 5종 + 직원 원본 크기 비교, 32×16 바닥 타일 격자 |
| `ref-sprites/` | 2차 시안에서 잘라낸 기본형 참고 그림(원본 크기, 투명 배경). 참고용이며 최종 그림이 아니다. 차 그림에는 시안의 진초록 바닥 그림자가 남아 있고, 시제품은 `build.py`에서 이를 지워 쓴다 |

크기는 원본 기준이며 2차 시안의 바닥 그림자까지 잰 값이다. 차는 이제 그림자를 그리지 않는다([asset-spec.md](asset-spec.md) 공통 규칙).

| 대상 | 크기(px) |
|---|---|
| 바닥 타일 | 32×16 |
| 경차 | 56×51 |
| 세단 | 66×46 |
| SUV | 69×58 |
| 승합 | 73×64 |
| 고급 세단 | 73×48 |
| 사람(서 있기) | 16×38 |

지도 원본은 640×464이며, 앱은 2배로 확대한다.

## 시제품 (`prototype/`)

`build.py`가 `template.html`에 지도·본관·참고 차 그림(data URI)과 좌표를 넣어 `prototype.html`을 만든다. 브라우저로 열면 다음을 눌러 볼 수 있다.

- 화면: 첫 실행, 가게, 차고, 차량 상세, 도감, 뽑기, 탁송, 정산, 랭킹
- 가게 화면의 「시험 도구」: 영업 1시간, 30km 주행, 탁송 도착, 다음 날
- 연출: 뽑기 셔터(등급별), 첫 지급, 정산 동전, 가게 확장. 4차 Claude Design 연출([briefs/motion.md](briefs/motion.md) 요청서)을 `motion.jsx`로 옮겼고, 「연출 다시 보기」 버튼으로 다시 볼 수 있다.

시제품의 한계:
- 영업 확률, 탁송 보상, 구매 가격, 포기 수수료는 PRD에 없는 예시 값이다.
- 시설은 그림과 비용이 없어 빠져 있다.
- 차는 기본형 5종의 색만 바꿨다.
- 앱 코드나 저장 계약과 연결되지 않은 디자인 확인용이다.
- 연출은 React 18.3.1·Babel standalone 7.24.7을 CDN에서 받아 브라우저에서 변환한다. 인터넷이 없으면 연출 없이 기존 결과 창이 뜬다.
- 가게 확장 연출의 맵은 Claude Design의 임시 그림이며 우리 지도가 아니다. 효과음은 없다.
- 나머지 12개 연출(요청서 2-4절)은 아직 시안이 없다.

## 다시 만들기

Python 3과 Pillow가 필요하다. 지도를 고치면 본관과 시제품도 다시 만든다.

```bash
cd docs/design-assets/pixel-kit
python3 map/make_map.py && python3 building/make_building.py && python3 prototype/build.py
```

## 원본 위치와 권리

- 2차·3차 시안 원본은 @hoyeol903의 Claude Design 캔버스(개인 공유)에 있다. 3차 화면 시안 30장은 그 캔버스에서만 볼 수 있다.
- 캐스퍼 참고 사진(Wikimedia Commons, CC BY-SA 4.0)은 저장소에 넣지 않았다. 출처 링크만 [briefs/casper-photo-sources.md](briefs/casper-photo-sources.md)에 남겼다.
- 실제 차종을 알아보게 그린 그림을 출품물에 넣는 일은 주최 측 확인 전이다([04 차량 조사 4절](../../04_VEHICLE_RESEARCH.md)).
- 르르르 등 공식 캐릭터는 쓰지 않았다.
