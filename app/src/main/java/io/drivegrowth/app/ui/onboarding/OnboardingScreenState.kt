package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.VehicleDefinition
import io.drivegrowth.core.contract.VehicleId

/** 차량 정의를 재사용한다. 비교 설명·선택 가능 여부는 공급자가 제공한다. */
data class OnboardingCandidate(
    val vehicle: VehicleDefinition,
    val selectable: Boolean,
    val description: String? = null,
    val unavailableReason: String? = null,
)

sealed interface OnboardingScreenState {
    data object Loading : OnboardingScreenState
    data class Unavailable(val message: String) : OnboardingScreenState
    data class Intro(val sourceLabel: String, val plannedBenefits: List<String>) : OnboardingScreenState
    data class Choosing(
        val candidates: List<OnboardingCandidate>, val sourceLabel: String, val plannedBenefits: List<String>,
    ) : OnboardingScreenState
    data class Confirming(
        val candidate: OnboardingCandidate, val sourceLabel: String, val plannedBenefits: List<String>,
        val backAllowed: Boolean,
    ) : OnboardingScreenState
    data class Submitting(val vehicleId: VehicleId, val vehicleName: String?, val sourceLabel: String) : OnboardingScreenState
    data class LoadError(val message: String, val retryAllowed: Boolean) : OnboardingScreenState
    data class GrantError(
        val vehicleId: VehicleId, val vehicleName: String?, val message: String,
        val retryAllowed: Boolean, val changeAllowed: Boolean, val sourceLabel: String,
    ) : OnboardingScreenState
    /** #50/호스트가 확정 저장을 확인한 결과만 전달한다. 이미 지급됨 복귀도 이 상태를 사용한다. */
    data class Completed(
        val vehicle: OwnedVehicle, val displayName: String, val summary: List<String>,
        val alreadyGranted: Boolean, val sourceLabel: String,
    ) : OnboardingScreenState
}

sealed interface OnboardingEvent {
    data object StartChoosing : OnboardingEvent
    data class SelectVehicle(val vehicleId: VehicleId) : OnboardingEvent
    data class ReviewSelection(val vehicleId: VehicleId) : OnboardingEvent
    data class ConfirmSelection(val vehicleId: VehicleId) : OnboardingEvent
    data object Previous : OnboardingEvent
    /** null이면 후보/초기 상태 재조회. 차량 ID가 있으면 같은 지급 요청의 조회·복구이며 신규 지급이 아니다. */
    data class Retry(val vehicleId: VehicleId?) : OnboardingEvent
    data object GoToShop : OnboardingEvent
}

/** 프로필당 View 한 개의 입력 제어. 실제 requestId·지급 마커·원장은 #50/호스트가 보존한다. */
class OnboardingInteraction(private val onEvent: (OnboardingEvent) -> Unit) {
    var state: OnboardingScreenState = OnboardingScreenState.Loading
        private set
    var controlsEnabled = false
        private set
    var selectedId: VehicleId? = null
        private set
    var pendingSelection: VehicleId? = null
        private set
    var inputPending = false
        private set
    val selectedCandidate: OnboardingCandidate?
        get() = (state as? OnboardingScreenState.Choosing)?.candidates?.find { it.vehicle.vehicleId == selectedId }

    /** false이면 다른 선택의 늦은 응답/완료 뒤 역행이다. 호스트는 requestId·프로필·응답 순서도 검증해야 한다. */
    fun render(value: OnboardingScreenState, enabled: Boolean): Boolean {
        controlsEnabled = enabled
        val completed = state as? OnboardingScreenState.Completed
        if (completed != null && (value !is OnboardingScreenState.Completed ||
                completed.vehicle.ownedVehicleId != value.vehicle.ownedVehicleId)) return false
        val responseId = when (value) {
            is OnboardingScreenState.Submitting -> value.vehicleId
            is OnboardingScreenState.GrantError -> value.vehicleId
            is OnboardingScreenState.Completed -> if (value.alreadyGranted) null else value.vehicle.vehicleId
            else -> null
        }
        if (pendingSelection != null && responseId != null && pendingSelection != responseId) return false
        if (pendingSelection != null && (value is OnboardingScreenState.Intro || value is OnboardingScreenState.Choosing)) return false
        if (responseId != null && pendingSelection == null && selectedId != null && selectedId != responseId) return false
        if (value is OnboardingScreenState.Confirming && selectedId != null && selectedId != value.candidate.vehicle.vehicleId) return false
        val phaseChanged = value.javaClass != state.javaClass
        if (phaseChanged && completed == null) inputPending = false
        state = value
        when (value) {
            is OnboardingScreenState.Confirming -> selectedId = value.candidate.vehicle.vehicleId
            is OnboardingScreenState.Submitting -> { selectedId = value.vehicleId; pendingSelection = value.vehicleId }
            is OnboardingScreenState.GrantError -> { selectedId = value.vehicleId; if (phaseChanged) pendingSelection = null }
            is OnboardingScreenState.Completed -> { selectedId = value.vehicle.vehicleId; pendingSelection = null }
            else -> Unit
        }
        return true
    }

    fun restoreSelection(id: VehicleId?) {
        if (!inputPending && pendingSelection == null && state !is OnboardingScreenState.Completed && state !is OnboardingScreenState.Confirming) selectedId = id
    }

    fun start(): Boolean = state is OnboardingScreenState.Intro && emit(OnboardingEvent.StartChoosing)

    fun select(id: VehicleId): Boolean {
        val candidate = (state as? OnboardingScreenState.Choosing)?.candidates?.find { it.vehicle.vehicleId == id }
        if (!controlsEnabled || inputPending || pendingSelection != null || candidate?.selectable != true) return false
        selectedId = id
        onEvent(OnboardingEvent.SelectVehicle(id))
        return true
    }

    fun review(): Boolean {
        val candidate = selectedCandidate ?: return false
        if (!candidate.selectable) return false
        return emit(OnboardingEvent.ReviewSelection(candidate.vehicle.vehicleId))
    }

    fun confirm(expectedId: VehicleId): Boolean {
        val current = state as? OnboardingScreenState.Confirming ?: return false
        if (current.candidate.vehicle.vehicleId != expectedId || !current.candidate.selectable) return false
        if (!canInput()) return false
        pendingSelection = expectedId
        inputPending = true
        onEvent(OnboardingEvent.ConfirmSelection(expectedId))
        return true
    }

    fun retry(): Boolean = when (val current = state) {
        is OnboardingScreenState.Choosing -> current.candidates.isEmpty() && emit(OnboardingEvent.Retry(null))
        is OnboardingScreenState.LoadError -> current.retryAllowed && emit(OnboardingEvent.Retry(null))
        is OnboardingScreenState.GrantError -> {
            if (!current.retryAllowed || !canInput()) false else {
                pendingSelection = current.vehicleId; inputPending = true
                onEvent(OnboardingEvent.Retry(current.vehicleId)); true
            }
        }
        else -> false
    }

    fun goToShop(): Boolean = state is OnboardingScreenState.Completed && emit(OnboardingEvent.GoToShop)

    fun handleBack(): Boolean {
        if (!controlsEnabled) return false
        if (inputPending || pendingSelection != null || state is OnboardingScreenState.Submitting) return true
        return when (val current = state) {
            is OnboardingScreenState.Choosing -> emit(OnboardingEvent.Previous)
            is OnboardingScreenState.Confirming -> if (current.backAllowed) emit(OnboardingEvent.Previous) else true
            is OnboardingScreenState.GrantError -> if (current.changeAllowed) emit(OnboardingEvent.Previous) else true
            else -> false
        }
    }

    private fun canInput() = controlsEnabled && !inputPending && pendingSelection == null
    private fun emit(event: OnboardingEvent): Boolean {
        if (!canInput()) return false
        inputPending = true
        onEvent(event)
        return true
    }
}
