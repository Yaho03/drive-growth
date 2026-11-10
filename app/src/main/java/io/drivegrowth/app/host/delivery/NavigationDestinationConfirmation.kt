package io.drivegrowth.app.host.delivery

import io.drivegrowth.core.delivery.DestinationKey
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*

/** 사용자 확인은 수신 근거와 함께 고정한다. 연결/수신 번호를 선택 revision으로 사용하지 않는다. */
class NavigationDestinationConfirmation(private val fresh: (NavigationStamp) -> Boolean,
    private val newKey: () -> DestinationKey) {
    private var revision = 0L
    private var enabled = false
    private var identity: GameIdentity? = null
    private var selection: DeliveryDestinationSelection? = null
    @Volatile var snapshot: NavigationSnapshot? = null
        private set
    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) { snapshot = null; selection = null; identity = null }
    }
    @Synchronized fun bind(value: GameIdentity) {
        if (identity != value) { snapshot = null; selection = null; identity = value }
    }
    @Synchronized fun accept(value: NavigationSnapshot) { if (enabled) snapshot = value }
    @Synchronized fun selected(value: GameIdentity): DeliveryDestinationSelection? = selection?.takeIf { enabled && identity == value && it.identity == value }
    @Synchronized fun begin(value: GameIdentity) { bind(value); selection = null }
    @Synchronized fun confirm(): ContractResult<DeliveryDestinationSelection> {
        val profile = identity ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val raw = snapshot ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val destination = raw.destination as? NavigationField.Value ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val distance = raw.remainingEstimatedMeters as? NavigationField.Value ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (!enabled || raw.connection != NavigationConnection.CONNECTED_OBSERVED || raw.failure != null ||
            destination.stamp != distance.stamp || destination.stamp.source != NavigationSource.PLEOS_NAVI_SDK ||
            destination.stamp.connectionGeneration != raw.stamp.connectionGeneration || !fresh(destination.stamp) || distance.value < 0)
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (revision == Long.MAX_VALUE) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return ContractResult.Success(DeliveryDestinationSelection(profile, newKey(), ++revision, destination.value, destination.stamp)
            .also { selection = it })
    }
}
