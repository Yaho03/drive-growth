# 주행 중 화면 프레임

9차 Claude Design 보드 「르르르 트럭 운전 애니메이션」(driving-scene.js)의 `DrivingScene.draw`를 1360×948로 그린 뒤 4분의 1(340×237, 보드의 1픽셀 단위)로 줄여 묶은 무손실 WebP다. 앱이 픽셀 그대로 키운다. 요청서는 `docs/design-assets/pixel-kit/briefs/driving-screen.md`.

| 파일 | 내용 |
|---|---|
| `rrr-day-loop.webp`, `rrr-night-loop.webp` | 12초 반복, 10fps, 120장 |
| `rrr-day-still.webp`, `rrr-night-still.webp` | 움직임 줄이기용 정지 그림 |

르르르 출품 사용권은 #29에서 확인한다. 보드에는 정비사판도 있어 필요하면 같은 방법으로 뽑는다.

| 파일 | SHA-256 |
|---|---|
| `rrr-day-loop.webp` | `e0e5b1c90b7ac63ab360cf3cd25ec0fcd12997f3bffbc54029f4e194dab0deab` |
| `rrr-day-still.webp` | `51ddb0871c0d4d4687055a7ca4885dd9c21b65a448f39bdc99de06c30bce36b3` |
| `rrr-night-loop.webp` | `1e117613cb3e6bb1e6716dbf5733d933515190080ce8e74496156000b24f8eee` |
| `rrr-night-still.webp` | `6aee96b53da3fcb6160ac19626cae358bdc68247a4c462a8af279f69bb97d627` |
