# 주행 중 화면 프레임

9차 Claude Design 보드 「르르르 트럭 운전 애니메이션」(driving-scene.js)의 `DrivingScene.draw`를 1360×948로 그린 뒤 4분의 1(340×237, 보드의 1픽셀 단위)로 줄여 묶은 무손실 WebP다. 앱이 픽셀 그대로 키운다. 요청서는 `docs/design-assets/pixel-kit/briefs/driving-screen.md`.

| 파일 | 내용 |
|---|---|
| `rrr-day-loop.webp`, `rrr-night-loop.webp` | 12초 반복, 20fps, 240장 |
| `rrr-day-still.webp`, `rrr-night-still.webp` | 움직임 줄이기용 정지 그림 |

르르르 출품 사용권은 #29에서 확인한다. 보드에는 정비사판도 있어 필요하면 같은 방법으로 뽑는다.

| 파일 | SHA-256 |
|---|---|
| `rrr-day-loop.webp` | `11d567a0b1f23c176835c24486426d6453f6d2b5859f5958428904aa932d0e00` |
| `rrr-day-still.webp` | `c49a15d4c91d77f180b0c44982deb3cd1844eec0cef5050e43670fc82ed5e88b` |
| `rrr-night-loop.webp` | `d2c8763a55cbcc7a7773ee5eac378be48c262acd012ac02bae0fa4deac3c7fa0` |
| `rrr-night-still.webp` | `3800c92fb7897717d4e9a91f7ddc7b6db3dbf01a88233cbc7664d5bb870b5d10` |
