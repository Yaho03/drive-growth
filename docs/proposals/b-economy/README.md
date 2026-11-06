# B 경제·해금·인계 검토 자료

게시 정리일: 2026-11-07 KST. 관련 이슈: [#24](https://github.com/Yaho03/drive-growth/issues/24), [#44](https://github.com/Yaho03/drive-growth/issues/44), [#41](https://github.com/Yaho03/drive-growth/issues/41), [#46](https://github.com/Yaho03/drive-growth/issues/46).

**이 폴더는 미확정 제안·비교 실험이다.** GitHub 공유나 이 문서 PR의 머지는 제안 숫자 채택·제품 구현·PR #92의 사람 승인·실차 검증을 의미하지 않는다. 제품 규칙의 기준은 [PRD](../../01_PRD.md)이며, 채택 시 #24에서 결정한 뒤 PRD·계약·콘텐츠·시험을 함께 반영한다.

## 읽는 순서

1. [경제·가게 성장 초안](economy-proposal.md): PR #92 검토 질문, 금액 단위·대여료·가격·확장 조건 제안, 팀 전달용 답변 초안.
2. [수익 비교 실험 보고](simulation-report.md): 고객 수요·대여/반납·사고·수리를 가정한 12개 시나리오와 한계. 상태 기록은 11월 6일 실험 시점이다.
3. [#46 → #41 인계 확인표](handoff-checklist.md): 받아야 할 API·fixture·커밋과 12개 시험 항목. 아직 시험을 실행했다는 기록이 아니다.

기타 자료: [20종 해금 후보](unlock_candidates.csv), [산술 결과](calculation_results.txt), [비교 결과 CSV](economy_results.csv), [비교 결과 JSON](economy_results.json).

## 재현

외부 Python 패키지 없이 저장소 루트에서 실행한다.

```sh
python3 docs/proposals/b-economy/check_proposal.py
python3 docs/proposals/b-economy/simulate_economy.py
python3 scripts/check_repository.py
```

첫 명령은 차종 ID·개수·게임 역할과 계산 예를 확인한다. 두 번째 명령은 고정 seed 200개로 12개 가정 시나리오를 비교한다. 앱의 실제 경제 엔진·주차 8시간 정산·전체 고객 분포·실제 성장 속도를 검증하는 도구는 아니다. 결과 파일은 각 스크립트 옆에 재생성된다. 입력 CSV는 배포 콘텐츠 로더 형식이 아니다.

## 게시 범위와 현재 절차

기존 11월 5~6일 로컬 산출물을 공유 가능한 경로로 정리했다. 11월 7일의 기준 main은 `77f7c54`다. 기존 코드·공통 타입·기준 PRD·확정 가격표를 변경하지 않는다.

[협업 규칙](../../../CONTRIBUTING.md#구현-이슈의-검증-책임)에 따라 별도 묶음 교차 검증은 PR 검토에 통합됐다. #71은 절차 대체로 종료됐으며, #41의 직접 선행은 #46의 필요한 조회 결과다. 이 자료 때문에 폐지된 #64/#71 종료 대기를 다시 만들지 않는다. 기능별 본인 시험과 PR 검토는 구현 이슈에, 기능 간 실제 연결·복원은 #28에 기록한다.
