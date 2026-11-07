package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.core.contract.*

/** 결정적 표시 시험 입력. #50·실제 프로필·DB·차량 입력과 연결하지 않는다. */
object OnboardingPreviewStates {
    const val SOURCE = "고정 시험 데이터 · 실제 지급·저장 아님"
    val casper = OnboardingCandidate(VehicleDefinition(VehicleId("hyundai_casper"), "캐스퍼"), true)
    val avante = OnboardingCandidate(VehicleDefinition(VehicleId("hyundai_avante"), "아반떼"), true,
        "긴 후보 비교 문구의 줄바꿈 시험입니다. 연식·트림·차량별 밸런스는 확정 전이에요.")
    // PRD의 초기 시험값을 '예정' 안내로만 사용한다. 실제 잔액·공간·티켓 확정값이 아니다.
    val plannedBenefits = listOf("초기 재화 1,000 게임머니 · PRD 시험값", "차고 3칸 · PRD 시험값", "개업 선물 뽑기권 1장 · 지급 예정 안내")
    val unavailable = OnboardingScreenState.Unavailable("첫 지급 여부를 확인하는 기능이 아직 연결되지 않았어요. 고정 시험 입력입니다.")
    val intro = OnboardingScreenState.Intro(SOURCE, plannedBenefits)
    val choosing = OnboardingScreenState.Choosing(listOf(casper, avante), SOURCE, plannedBenefits)
    val empty = choosing.copy(candidates = emptyList())
    val missing = choosing.copy(candidates = listOf(casper, avante.copy(selectable = false,
        unavailableReason = "일부 후보 정보 미지원 · 고정 시험 입력")), plannedBenefits = emptyList())
    val confirming = OnboardingScreenState.Confirming(casper, SOURCE, plannedBenefits, backAllowed = true)
    val submitting = OnboardingScreenState.Submitting(casper.vehicle.vehicleId, casper.vehicle.displayName, SOURCE)
    val loadError = OnboardingScreenState.LoadError("차량 후보를 읽지 못했어요. 다시 확인해 주세요.", true)
    val grantError = OnboardingScreenState.GrantError(casper.vehicle.vehicleId, casper.vehicle.displayName,
        "지급 여부가 확정되지 않았어요. 이전 요청의 결과를 다시 확인해 주세요.", true, false, SOURCE)
    val changeAllowedError = grantError.copy(changeAllowed = true)
    val completed = OnboardingScreenState.Completed(
        OwnedVehicle(OwnedVehicleId("onboarding-preview-owned"), casper.vehicle.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT), casper.vehicle.displayName,
        listOf("초기 재화 1,000 게임머니 · 고정 확정 시험 입력", "차고 3칸 · 고정 확정 시험 입력", "개업 선물 뽑기권 1장 · 고정 확정 시험 입력"),
        false, SOURCE,
    )
    val alreadyGranted = completed.copy(alreadyGranted = true)
}
