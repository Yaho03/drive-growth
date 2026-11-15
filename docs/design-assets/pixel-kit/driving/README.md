# 9차 · 주행 중 화면 (르르르 트럭 운전 정면 뷰)

Claude Design 보드 「르르르 트럭 운전 애니메이션」(2026-11-15)의 소스 전체다. 요청서는 [briefs/driving-screen.md](../briefs/driving-screen.md).

| 파일 | 내용 |
|---|---|
| `driving-scene.js` | 픽셀 렌더러. `DrivingScene.build/pose/draw(ctx, t, opts)`. opts는 시간대(day/night), 운전자(rrr/mechanic), 배치(wide/narrow), 움직임(full/blinkOnly/still) |
| `driving-piece.jsx` | 보드 미리보기 조립(층 배치·캡션) |
| `driving-screen.dc.html` | 보드 페이지(원래 이름 「주행 중 화면.dc.html」) |
| `animations-v3.jsx`, `support.js` | Claude Design 보드 실행용 런타임(보드에서 그대로 복사) |
| `preview/` | 보드 작업 중 캡처(v3가 최종) |
| `tools/export-frames.html`, `tools/build_webp.py` | 앱 자산을 다시 만드는 도구. 브라우저에서 `draw`로 1360×948을 그린 뒤 340×237로 줄여 PNG로 내보내고, WebP로 묶는다 |

앱은 `app/src/main/assets/pixel/driving/rrr-{day,night}-{loop,still}.webp`를 `DrivingSceneView`로 재생한다. 12초 반복, 10fps. 정비사판·좁은 창은 보드에 있으나 앱 자산으로는 아직 뽑지 않았다.

주행 중 표시 여부는 PRD의 「주행 중 화면을 보게 하지 않는 것이 최우선」 원칙과 Pleos 정책(#80·#81)으로 팀이 확인한다. 르르르 출품 사용권은 #29.
