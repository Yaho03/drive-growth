# 휴게소 수면 연출 프레임

7차 Claude Design 보드 「르르르 휴게소 수면 애니메이션」의 `rest-stop-scene.js`(시제품 `docs/design-assets/pixel-kit/prototype/rest-stop-scene.js`)를 브라우저에서 시간별로 그려 묶은 무손실 애니메이션 WebP다. 340×237 원본을 앱이 픽셀 그대로 키운다.

| 파일 | 내용 | 프레임 |
|---|---|---|
| `*-intro.webp` | 0–6초 첫 재생(전경 → 트럭 → 운전석), 한 번만 | 72장 · 12fps |
| `*-loop.webp` | 6–16초 반복 구간, 이음매 없음 | 120장 · 12fps |
| `*-still.webp` | 움직임 줄이기: 운전석 정지 그림에 숨쉬기만 | 60장 · 6fps |

`rururu-*`는 르르르판, `blanket-*`는 담요를 머리끝까지 덮은 대체판이다. 르르르의 출품 사용권은 #29에서 확인하며, 허가가 나지 않으면 `RestStopMotionView.VARIANT`를 `blanket`으로 바꾼다.

| 파일 | SHA-256 |
|---|---|
| `blanket-intro.webp` | `de4abe660e5b8a8341c8598e1cca8e64cfc469d45ff4a42ce3dc88a7a441eec4` |
| `blanket-loop.webp` | `579579cb42c4730c9b911f3c96b027c41ddcd76dcc6c098fc2cd7454d61e0db3` |
| `blanket-still.webp` | `dadb8f5776374b2fa2e6b06e5fa484d166ab22d96b558c9f77b65dede56b246f` |
| `rururu-intro.webp` | `47b0115086061a20fd2ca50966eeae73c00398e9cad0696ae4ae1631fa43ec6d` |
| `rururu-loop.webp` | `512dacca3361137db23ae73f05e714afcc81783a3765b4e6fc5b8d29f5fe737c` |
| `rururu-still.webp` | `3be40c5892da4fd22fbc780598ced74e40715bed48a2ba92c3aa2163df19f4e7` |
