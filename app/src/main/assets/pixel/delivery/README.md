# 탁송 연출 이미지

트럭과 도로는 [PR #282의 5차 탁송 시안](https://github.com/Yaho03/drive-growth/pull/282) head `f403d842ccfc56600e05d03953176a107bbbee79`에서 가져온 이미지다. 최종 브라우저 head `65d4079fd82455152aba4b15ca50d29b650cc8ae`에서도 연출 소스와 PNG는 같다. 최신 목적지 표기 변경은 Android의 문자열 표시로 대응한다.

| 앱 파일 | 원본과 처리 | SHA-256 |
| --- | --- | --- |
| `truck.png` | `prototype/delivery.jsx`의 `truckMini()`가 생성한 132×72 SVG를 PNG로 변환. 출발·고속도로·도착 연출에 사용 | `b6060374867f4d32e2d16db49e06d313317dbfdec94f0b8d95b09e24480d1e86` |
| `destination.png` | [PR #294](https://github.com/Yaho03/drive-growth/pull/294) head `19bd9328c4921413d41674dad7c81b7b147d8e20`의 `delivery-destination.png` 원본. 수익 탁송 확정 도착에 사용 | `698f112d5e86e8ab7b73f8a0f91247f5d0cc20117b1819823bd4f0a476f254d2` |
| `highway.png` | `prototype/highway.png`를 바이트 그대로 복사. `make_highway.py`에서 마을 도로·잔디·나무를 조합한 반복 배경 | `1397e74606b206a2677aec2550a908c1d80ce343502ef04b40908bc5e24457d7` |

외부 배포·출품에 필요한 권리 확인은 [#29](https://github.com/Yaho03/drive-growth/issues/29)에서 추적한다.
