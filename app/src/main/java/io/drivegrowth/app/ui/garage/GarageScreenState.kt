package io.drivegrowth.app.ui.garage

import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.garage.CollectionEntry
import io.drivegrowth.core.progression.Rarity
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

/** 공통 개체를 보존하고 조회 공급자가 제공하는 표시값만 덧붙인다. null은 확인 불가다. */
data class VehiclePresentation(
    val vehicle: OwnedVehicle,
    val name: String,
    val instanceLabel: String,
    val rarity: Rarity? = null,
    val progressLabel: String? = null,
    val activity: VehicleActivity? = null,
    val activityDetail: String? = null,
    val accidentLabel: String? = null,
    val acquisitionLabel: String? = null,
    val modelPriceLabel: String? = null,
    val actions: Map<VehicleAction, ActionPresentation> = emptyMap(),
    val informationNote: String? = null,
)

/** 비용·예상 결과·불가 사유는 공급자가 제공한다. UI에서 금액과 허용 조건을 계산하지 않는다. */
data class ActionPresentation(val enabled: Boolean, val explanation: String)
data class CollectionPresentation(
    val entry: CollectionEntry,
    val name: String,
    val unlockLabel: String?,
)
data class GarageContent(
    val vehicles: List<VehiclePresentation>,
    val capacityLabel: String?,
    val sourceLabel: String,
)
sealed interface GarageScreenState {
    data object Loading : GarageScreenState
    data class Content(val data: GarageContent) : GarageScreenState
    data class Error(val message: String) : GarageScreenState
}
sealed interface CollectionScreenState {
    data object Loading : CollectionScreenState
    data class Content(val entries: List<CollectionPresentation>, val sourceLabel: String) : CollectionScreenState
    data class Error(val message: String) : CollectionScreenState
}
sealed interface GarageEvent {
    data class SelectVehicle(val id: OwnedVehicleId) : GarageEvent
    data object CloseDetail : GarageEvent
    data object Retry : GarageEvent
    /** 실제 변경 명령이 아니라 효과·비용 확인을 여는 준비 요청이다. */
    data class RequestAction(val id: OwnedVehicleId, val revision: Revision, val action: VehicleAction) : GarageEvent
}

/** UI 입력의 중복을 막는다. 주차·차량 신호 판단과 거래의 요청 ID 관리는 호스트 책임이다. */
class GarageInteraction(private val onEvent: (GarageEvent) -> Unit) {
    var state: GarageScreenState = GarageScreenState.Loading
        private set
    var selectedId: OwnedVehicleId? = null
        private set
    var controlsEnabled: Boolean = false
        private set
    var requestPending: Boolean = false
        private set
    var retryPending: Boolean = false
        private set
    val selectedVehicle: VehiclePresentation?
        get() = (state as? GarageScreenState.Content)?.data?.vehicles?.find { it.vehicle.ownedVehicleId == selectedId }

    fun render(value: GarageScreenState, enabled: Boolean) {
        state = value
        controlsEnabled = enabled
        retryPending = false
    }

    fun select(id: OwnedVehicleId) {
        if (!controlsEnabled || (state as? GarageScreenState.Content)?.data?.vehicles?.none { it.vehicle.ownedVehicleId == id } != false) return
        selectedId = id
        onEvent(GarageEvent.SelectVehicle(id))
    }

    /** 호스트가 저장한 선택을 복원한다. 없는 개체도 소멸 안내를 위해 ID를 보존한다. */
    fun restoreSelection(id: OwnedVehicleId?) { selectedId = id }

    fun closeDetail() {
        if (!controlsEnabled) return
        selectedId = null
        onEvent(GarageEvent.CloseDetail)
    }

    fun request(
        action: VehicleAction,
        expectedId: OwnedVehicleId? = selectedId,
        expectedRevision: Revision? = selectedVehicle?.vehicle?.revision,
    ): Boolean {
        val selected = selectedVehicle ?: return false
        if (!controlsEnabled || requestPending || selected.actions[action]?.enabled != true ||
            selected.vehicle.ownedVehicleId != expectedId || selected.vehicle.revision != expectedRevision) return false
        requestPending = true
        onEvent(GarageEvent.RequestAction(selected.vehicle.ownedVehicleId, selected.vehicle.revision, action))
        return true
    }

    /** 확인 취소·준비 결과·확정 결과를 받은 호스트가 명시적으로 해제한다. */
    fun completeRequest() { requestPending = false }

    fun retry(): Boolean {
        if (!controlsEnabled || retryPending || state !is GarageScreenState.Error) return false
        retryPending = true
        onEvent(GarageEvent.Retry)
        return true
    }
}
