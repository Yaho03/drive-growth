package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*

/** B가 실제 반환 목적지를 사용자에게 확인받은 문맥. SDK 요청문구나 연결 세대로 만들지 않는다. */
data class DeliveryDestinationSelection(val identity: GameIdentity, val destination: DestinationKey, val selectionRevision: Long,
    val actualDestination: NavigationDestination, val actualStamp: NavigationStamp)

/** F 모델을 그대로 소비하는 순수 연결안. 실제 F source/선택 UI 공급은 B의 통합 책임이다. */
class FNavigationDeliveryPort(
    private val current: (GameIdentity) -> NavigationSnapshot?,
    private val selected: (GameIdentity, NavigationSnapshot) -> DeliveryDestinationSelection?,
    private val classifiedSource: (GameIdentity, NavigationSnapshot) -> Observation<InputSource>,
    private val observed: (GameIdentity) -> Observation<ObservedDistance>,
    private val fresh: (NavigationStamp) -> Boolean,
    private val choose: (GameIdentity, ActiveDelivery?) -> ContractResult<Unit>,
) : DeliveryNavigationPort {
    override fun chooseDestination(identity: GameIdentity, active: ActiveDelivery?) = choose(identity, active)
    override fun read(identity: GameIdentity): ContractResult<DeliveryNavigationSnapshot> {
        val raw = current(identity) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val stamps = buildMap<String, DeliveryFieldReceipt> {
            fun putField(name: String, field: NavigationField<*>) {
                val stamp = when (field) { is NavigationField.Value -> field.stamp; is NavigationField.Missing -> field.stamp } ?: return
                put(name, DeliveryFieldReceipt(stamp.receivedAtMillis, stamp.receivedElapsedMillis,
                    stamp.connectionGeneration, stamp.sequence, stamp.source.name))
            }
            putField("destination", raw.destination); putField("remainingEstimatedMeters", raw.remainingEstimatedMeters)
            putField("routeId", raw.routeId); putField("guidance", raw.guidance)
            putField("routeStartEstimatedMeters", raw.routeStartEstimatedMeters); putField("navigationStatus", raw.navigationStatus)
        }
        val metadata = DeliveryNavigationMetadata(raw.stamp.connectionGeneration, stamps)
        val binding = selected(identity, raw)
        val destination = raw.destination as? NavigationField.Value
        val distance = raw.remainingEstimatedMeters as? NavigationField.Value
        val sameDestination = binding != null && binding.identity == identity && destination != null && binding.actualDestination == destination.value &&
            binding.actualStamp == destination.stamp && binding.selectionRevision >= 0
        val classification = classifiedSource(identity, raw)
        val source = (classification as? Observation.Available)?.value
        val mode = (raw.guidance as? NavigationField.Value)?.value?.mode
        val sourceMatches = source == identity.source && (mode != "SIMULATION_GUIDING" || source == InputSource.SIMULATION)
        val stampsMatch = destination != null && distance != null && destination.stamp == distance.stamp &&
            destination.stamp.connectionGeneration == raw.stamp.connectionGeneration && destination.stamp.source == NavigationSource.PLEOS_NAVI_SDK
        val isFresh = destination != null && distance != null && fresh(destination.stamp) && fresh(distance.stamp)
        val missing = when {
            raw.failure?.error in setOf(NavigationError.PERMISSION_NOT_DECLARED, NavigationError.PERMISSION_DENIED, NavigationError.SECURITY_EXCEPTION) -> MissingReason.PERMISSION_DENIED
            raw.failure?.error == NavigationError.SDK_UNSUPPORTED -> MissingReason.UNSUPPORTED
            classification is Observation.Missing -> classification.reason
            stampsMatch && !isFresh -> MissingReason.STALE
            else -> MissingReason.NO_DATA
        }
        val estimated: Observation<EstimatedRouteDistance> = if (raw.connection == NavigationConnection.CONNECTED_OBSERVED &&
            sameDestination && sourceMatches && stampsMatch && isFresh && distance!!.value >= 0)
            Observation.Available(EstimatedRouteDistance(distance.value, source!!)) else Observation.Missing(missing)
        // F는 실제 이동 거리를 공급하지 않는다. 별도 기록 입력이 같은 source인 경우만 그대로 전달한다.
        val moved = observed(identity).let { value -> if (value is Observation.Available && value.value.source != identity.source)
            Observation.Missing(MissingReason.NO_DATA) else value }
        return ContractResult.Success(DeliveryNavigationSnapshot(
            if (sameDestination) binding!!.destination else null,
            destination?.value?.name, estimated, moved,
            Observation.Missing(MissingReason.NO_DATA), // F의 arrival는 항상 독립 상관 근거 확인 대기다.
            if (sameDestination) binding!!.selectionRevision else null, metadata))
    }
}
