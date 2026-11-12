package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*

/** SDK 원문과 경로 상관 검증은 F 소유다. arrival에는 검증된 증거만 공급한다. */
data class DeliveryNavigationSnapshot(
    val destination: DestinationKey?, val destinationName: String?,
    val estimated: Observation<EstimatedRouteDistance>, val observed: Observation<ObservedDistance>,
    val arrival: Observation<DeliveryArrivalEvidence>,
    /** 사용자가 실제 반환 목적지를 확인한 revision. SDK 연결 세대/수신 sequence는 대입하지 않는다. */
    val selectionRevision: Long?,
    val metadata: DeliveryNavigationMetadata? = null,
)
/** F 원문은 B session이 보존한다. 변환에 쓰인 필드별 수신 근거이며 도착 증명이 아니다. */
data class DeliveryFieldReceipt(val receivedAtMillis: Long, val receivedElapsedMillis: Long,
    val connectionGeneration: Long, val sequence: Long, val provenance: String)
class DeliveryNavigationMetadata(val connectionGeneration: Long?, fields: Map<String, DeliveryFieldReceipt>) {
    val fields: Map<String, DeliveryFieldReceipt> = java.util.Collections.unmodifiableMap(LinkedHashMap(fields))
}
interface DeliveryNavigationPort {
    fun read(identity: GameIdentity): ContractResult<DeliveryNavigationSnapshot>
    fun chooseDestination(identity: GameIdentity, active: ActiveDelivery?): ContractResult<Unit>
}
data class DeliveryHostRules(val proposal: DeliveryRules, val change: DeliveryChangeRules,
    val settlement: DeliverySettlementRules)

/** 저장 전에 고정할 전체 입력. 실제 직렬화/단일 checkpoint는 E 공통 원장이 소유한다. */
sealed interface DeliveryHostOperation {
    data class Select(val proposed: DeliveryProposed, val offerId: DeliveryOfferId, val requestId: RequestId,
        val route: RouteId, val at: EpochMillis) : DeliveryHostOperation
    data class Change(val rules: DeliveryChangeRules, val offer: DeliveryChangeOffer, val offerId: DeliveryOfferId,
        val route: RouteId, val observedRoute: RouteId? = null) : DeliveryHostOperation
    data class Abandon(val rules: DeliveryChangeRules, val requestId: RequestId) : DeliveryHostOperation
    data class Arrival(val rules: DeliverySettlementRules, val requestId: RequestId,
        val evidence: Observation<DeliveryArrivalEvidence>, val now: EpochMillis,
        val expected: DeliveryList, val replacement: DeliveryList) : DeliveryHostOperation
    data class Hold(val rules: DeliverySettlementRules, val requestId: RequestId, val now: EpochMillis) : DeliveryHostOperation
}
data class DeliveryHostRead(val identity: GameIdentity, val screen: DeliveryScreen,
    val held: List<HeldVehicle> = emptyList(), val displayedProposal: DeliveryProposed? = null,
    val pending: DeliveryHostOperation? = null, val receipt: DeliveryHostReceipt? = null,
    val navigation: DeliveryNavigationSnapshot? = null)
data class DeliveryHostPrepared(val screen: DeliveryScreen, val operation: DeliveryHostOperation? = null,
    val navigation: DeliveryNavigationSnapshot? = null)

sealed interface DeliveryHostReceipt {
    data class Selected(val value: DeliveryCommit) : DeliveryHostReceipt
    data class Changed(val value: ChangeCommit) : DeliveryHostReceipt
    data class Abandoned(val value: AbandonCommit) : DeliveryHostReceipt
    data class Arrived(val value: ArrivalCommit) : DeliveryHostReceipt
    data class Held(val value: HoldCommit) : DeliveryHostReceipt
}
/** E 공통 checkpoint adapter가 구현한다. 별도 영속 저장을 만들지 않는다. */
interface DeliveryOperationJournal {
    fun pending(identity: GameIdentity): ContractResult<DeliveryHostOperation?>
    fun save(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<Unit>
    fun execute(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<DeliveryHostReceipt>
    fun cancelUnstarted(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<Unit>
    fun finish(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<Unit>
}
/** 모두 worker. execute 구현은 같은 DB durable save 확인 뒤에만 기존 원장을 실행한다. */
interface DeliveryHostSource {
    fun read(): ContractResult<DeliveryHostRead>
    fun prepare(identity: GameIdentity, command: DeliveryCommand): ContractResult<DeliveryHostPrepared>
    fun execute(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<DeliveryHostRead>
    fun cancelUnstarted(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<Unit>
    fun finish(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<Unit>
    fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit>
}
