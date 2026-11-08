package io.drivegrowth.app.ui.progress

import io.drivegrowth.core.driving.RestOutcome

/** 결정적인 표시 시험 입력. 목표·보상표 채택, 실제 원장·차량·휴식 증거가 아니다. */
object ProgressPreviewStates {
    const val SOURCE = "고정 시험 데이터 · 목표·보상은 미채택 예시 · 실제 지급·휴식 증거 아님"
    val firstKey = ProgressKey("result-check", "fixture-day")
    private fun entry(id: String, group: ProgressGroup, status: RewardStatus, title: String, percent: Int?) = ProgressItem(
        ProgressKey(id, if (group == ProgressGroup.DAILY) "fixture-day" else "fixture-period"), group, 1,
        title, percent?.let { "표시 진행 $it% · 고정 시험값" }, percent, "시험 보상 300 게임머니", status,
        note = "표시 시험 · 실제 보상표·청구 명령 미연결",
        claim = if (status == RewardStatus.CLAIMABLE) ClaimOffer("fixture-$id", true) else null,
    )
    val readyRest = RestPresentation(RestOutcome.ACCEPTED, BuffPhase.NEXT_DEPARTURE,
        listOf(RestCondition("인정 장소", true), RestCondition("직전 운전 조건", true), RestCondition("주차 휴식 조건", true)),
        "기본 수익 1.5배 · 고정 시험값", "적용 대기 · 고정 시험값", "모의 판정 · 실제 저장·출발·수익에 연결되지 않음")
    val normal = ProgressScreenState.Content(1, listOf(
        entry("result-check", ProgressGroup.DAILY, RewardStatus.CLAIMABLE, "영업 결과 확인하기", 100).copy(key = firstKey),
        entry("level-up", ProgressGroup.DAILY, RewardStatus.IN_PROGRESS, "차량 1대 레벨업", 0),
        entry("customers", ProgressGroup.DAILY, RewardStatus.LOCKED, "고객 결과 확인 · 잠금 조건 확인 필요", 60),
        entry("weekly-one", ProgressGroup.WEEKLY, RewardStatus.COMPLETE, "주간 목표의 완료 상태 표시 시험", 100),
        entry("weekly-two", ProgressGroup.WEEKLY, RewardStatus.CLAIMED, "이미 수령한 주간 목표", 100),
        entry("weekly-three", ProgressGroup.WEEKLY, RewardStatus.EXPIRED, "만료된 주간 목표", 60),
        entry("owned", ProgressGroup.ACHIEVEMENT, RewardStatus.IN_PROGRESS, "보유 차량", 60),
        entry("shop", ProgressGroup.ACHIEVEMENT, RewardStatus.LOCKED, "가게 레벨", 40),
        entry("collection", ProgressGroup.ACHIEVEMENT, RewardStatus.UNSUPPORTED, "도감 완성률", null),
        entry("total-customers", ProgressGroup.ACHIEVEMENT, RewardStatus.FAILED, "누적 고객 수의 긴 목표명과 보상 확인 문구 줄바꿈 시험", 100),
    ), SOURCE, "일일·주간 기간은 시험 식별자로 구분 · 실제 초기화 시각 아님", readyRest)
    val missing = normal.copy(entries = normal.entries.map { it.copy(progressLabel = null, progressPercent = null, rewardLabel = null, claim = null) },
        rest = RestPresentation(RestOutcome.UNCONFIRMED, BuffPhase.UNCONFIRMED))
    val empty = normal.copy(entries = emptyList(), rest = null)
    val many = normal.copy(entries = (1..40).map { entry("many-$it", ProgressGroup.DAILY, RewardStatus.IN_PROGRESS,
        "긴 목표 이름 배치 시험 $it · 결과와 조건 안내가 여러 줄이어도 행동을 유지해요", 35) })
    val pending = normal.copy(sequence = 2, entries = normal.entries.map { if (it.key == firstKey) it.copy(status = RewardStatus.PENDING) else it })
    val received = normal.copy(sequence = 3, entries = normal.entries.map { if (it.key == firstKey) it.copy(revision = 2, status = RewardStatus.CLAIMED, claim = null) else it })
    val removed = normal.copy(sequence = 3, entries = normal.entries.filterNot { it.key == firstKey })
    val failed = normal.copy(sequence = 3, entries = normal.entries.map { if (it.key == firstKey) it.copy(revision = 2,
        status = RewardStatus.FAILED, claim = ClaimOffer("fixture-recovery", true), note = "실패 확인 · 같은 청구의 복구 연결은 호스트 책임") else it })
    val activeRest = normal.copy(rest = readyRest.copy(buffPhase = BuffPhase.ACTIVE, remainingLabel = "잔여 12분 · 고정 시험 표시"))
    val restVariants = listOf(
        readyRest,
        readyRest.copy(recognition = RestOutcome.NOT_REST_PLACE, buffPhase = BuffPhase.INACTIVE,
            conditions = listOf(RestCondition("인정 장소", false))),
        readyRest.copy(recognition = RestOutcome.DRIVING_TOO_SHORT, buffPhase = BuffPhase.INACTIVE,
            conditions = listOf(RestCondition("직전 운전 조건", false))),
        readyRest.copy(recognition = RestOutcome.PARKING_TOO_SHORT, buffPhase = BuffPhase.INACTIVE,
            conditions = listOf(RestCondition("주차 휴식 조건", false))),
        RestPresentation(RestOutcome.UNCONFIRMED, BuffPhase.UNCONFIRMED),
        RestPresentation(null, BuffPhase.UNSUPPORTED),
    )
    val unsupported = ProgressScreenState.Unsupported("진행·청구 API 연결을 아직 제공하지 않아요.")
    val failure = ProgressScreenState.Failure("진행 기록을 읽지 못했어요. 기존 기록을 0으로 바꾸지 않아요.", true)
}
