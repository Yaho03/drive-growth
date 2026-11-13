package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate
import kotlin.random.Random

/** DB와 중앙 정의·검증된 내비 입력을 소비한다. SDK/새 저장소/경제 규칙을 소유하지 않는다. worker 전용. */
class StoredDeliveryHostSource(private val queries: DeliveryHostQueries,
    private val definitions: () -> ContractResult<DeliveryHostRules>, private val navigation: DeliveryNavigationPort,
    private val journal: DeliveryOperationJournal, private val today: () -> LocalDate,
    private val now: () -> EpochMillis, private val random: () -> Random,
    private val requestId: () -> RequestId, private val routeId: () -> RouteId) : DeliveryHostSource {
    private val offers = DeliveryOfferSource(queries, definitions, navigation, today, random)
    private var changing: Pair<ActiveDelivery, Long?>? = null
    private var changeOffer: DeliveryChangeOffer? = null
    private var changeRevision: Long? = null
    private var changeRoute: RouteId? = null

    override fun read(): ContractResult<DeliveryHostRead> {
        val identity = when (val read = queries.identity()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        // 과거 요청 복구에는 현재 규칙·가격·콘텐츠·내비 조회를 선행하지 않는다.
        val pending = when (val read = journal.pending(identity)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (pending != null) return ContractResult.Success(DeliveryHostRead(identity, DeliveryScreen.Loading, pending = pending))
        return current(identity, true)
    }
    private fun current(identity: GameIdentity, automatic: Boolean): ContractResult<DeliveryHostRead> {
        val data = when (val read = queries.read(identity)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (data.identity != identity) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val rules = when (val read = definitions()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val nav = readNavigation(identity, data)
        val names = data.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        val at = now()
        if (automatic) {
            for (held in data.held) {
                if (held.source != identity.source) return ContractResult.Failure(ContractError.STATE_CONFLICT)
                val result = when (val resolved = DeliverySettlement(rules.settlement).resolveHold(held, at, data.freeSlots)) {
                    is ContractResult.Failure -> return resolved
                    is ContractResult.Success -> resolved.value
                }
                if (result !is HoldOutcome.StillHeld) return ContractResult.Success(DeliveryHostRead(identity,
                    DeliveryScreen.Loading, data.held, pending = DeliveryHostOperation.Hold(rules.settlement, held.requestId, at), navigation = nav))
            }
        }
        val terms = data.terms
        if (terms == null) {
            changing = null; changeOffer = null; changeRevision = null; changeRoute = null
            if (nav.destination == null) return ContractResult.Success(DeliveryHostRead(identity,
                DeliveryScreen.Destination(null, null, sourceLabel(identity.source), true), data.held, navigation = nav))
            return offers.offers(identity)
        }
        val progress = data.progress
        if (automatic && progress != null) {
            if (progress.active != terms.active) return ContractResult.Failure(ContractError.STATE_CONFLICT)
            val result = when (val resolved = DeliverySettlement(rules.settlement).resolve(progress, nav.arrival, at, data.freeSlots)) {
                is ContractResult.Failure -> return resolved
                is ContractResult.Success -> resolved.value
            }
            if (result !is DeliveryOutcome.AwaitingEvidence) {
                val stored = when (val read = queries.lists(identity, terms.active.proposed.list.createdOn)) {
                    is ContractResult.Failure -> return read
                    is ContractResult.Success -> read.value.firstOrNull { sameKey(it, terms.active.proposed.list) }
                }
                val expected = stored ?: terms.active.proposed.list
                val completed = result is DeliveryOutcome.IncomeCompleted || result is DeliveryOutcome.VehicleDelivered || result is DeliveryOutcome.VehicleHeld
                val replacement = if (!completed) expected else when (val read = DeliveryProposal(rules.proposal)
                    .replaceCompleted(expected, terms.active.offer.id, data.content, data.shopLevel, random())) {
                    is ContractResult.Failure -> return read
                    is ContractResult.Success -> read.value
                }
                return ContractResult.Success(DeliveryHostRead(identity, DeliveryScreen.Loading, data.held,
                    pending = DeliveryHostOperation.Arrival(rules.settlement, terms.active.requestId, nav.arrival, at, expected, replacement), navigation = nav))
            }
        }
        val change = changing
        if (change != null && change.first == terms.active && changeRoute == progress?.route) {
            val selectedRevision = nav.selectionRevision
            if (selectedRevision == null || (change.second != null && selectedRevision <= change.second!!)) return ContractResult.Success(DeliveryHostRead(identity,
                DeliveryScreen.AwaitingResult(DeliveryHostMapper.active(terms, names, destinationName(terms.active, nav), nav.observed),
                    "내비게이션에서 새 목적지와 예상 경로를 확인해 주세요."), data.held, navigation = nav))
            val to = nav.destination ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            if (changeOffer?.let { !currentChange(it, nav, progress?.route) } == true) { changeOffer = null; changeRevision = null }
            val proposed = changeOffer ?: when (val read = DeliveryProposal(rules.proposal).proposeChange(terms.active,
                nav.observed, nav.estimated, to, data.content, data.shopLevel, today(), random())) {
                is ContractResult.Failure -> return read
                is ContractResult.Success -> read.value.also { changeOffer = it; changeRevision = nav.selectionRevision }
            }
            return ContractResult.Success(DeliveryHostRead(identity,
                DeliveryScreen.Offers(DeliveryHostMapper.changeProposal(terms, proposed, rules.change, names, nav.destinationName, data.balance, identity.source)), data.held, proposed.proposed, navigation = nav))
        }
        return ContractResult.Success(DeliveryHostRead(identity,
            DeliveryScreen.Active(DeliveryHostMapper.active(terms, names, destinationName(terms.active, nav), nav.observed)), data.held, navigation = nav))
    }
    override fun prepare(identity: GameIdentity, command: DeliveryCommand): ContractResult<DeliveryHostPrepared> {
        val data = when (val read = queries.read(identity)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (data.identity != identity) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val rules = when (val read = definitions()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val nav = readNavigation(identity, data)
        val names = data.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        val terms = data.terms
        val prepared: ContractResult<DeliveryHostPrepared> = when (command) {
            is DeliveryCommand.ChooseDestination -> {
                if (command.active != terms?.active || terms?.changeUsed == true) return ContractResult.Failure(ContractError.STATE_CONFLICT)
                when (val chosen = navigation.chooseDestination(identity, command.active)) {
                    is ContractResult.Failure -> chosen
                    is ContractResult.Success -> {
                        changing = command.active?.let { it to nav.selectionRevision }; changeOffer = null; changeRevision = null
                        changeRoute = data.progress?.route
                        ContractResult.Success(DeliveryHostPrepared(DeliveryScreen.Destination(null, null, sourceLabel(identity.source), false,
                            "내비게이션에서 목적지를 선택한 뒤 다시 조회해 주세요.")))
                    }
                }
            }
            is DeliveryCommand.Select -> {
                val change = changeOffer
                if (terms != null) {
                    if (change == null || change.proposed != command.proposed || changing?.first != terms.active || !currentChange(change, nav, data.progress?.route))
                        return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    val preview = when (val result = DeliveryChange(rules.change).change(terms, change, command.offerId, data.balance)) {
                        is ContractResult.Failure -> return result
                        is ContractResult.Success -> result.value
                    }
                    val money = preview.change?.money
                    ContractResult.Success(DeliveryHostPrepared(DeliveryScreen.ChangeReview(
                        DeliveryHostMapper.active(terms, names, destinationName(terms.active, nav), nav.observed),
                        DeliveryHostMapper.changeProposal(terms, change, rules.change, names, nav.destinationName, data.balance, identity.source), command.offerId,
                        DeliveryMoneyUi(money?.direction ?: MoneyDirection.CREDIT, money?.amount ?: GameMoney(0)),
                        "변경 후보의 표시된 희귀도를 적용해요. 차액과 포기 수수료는 달라요.", DeliveryAvailability(true))))
                } else {
                    if (nav.destination != command.proposed.destination ||
                        (nav.estimated as? Observation.Available)?.value != command.proposed.estimated)
                        return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    val saved = when (val read = queries.lists(identity, command.proposed.list.createdOn)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
                    if (command.proposed.list !in saved || command.proposed.estimated.source != identity.source)
                        return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    val id = requestId()
                    when (val selected = DeliverySelection.select(command.proposed, command.offerId, id, data.balance, null)) {
                        is ContractResult.Failure -> selected
                        is ContractResult.Success -> ContractResult.Success(DeliveryHostPrepared(DeliveryScreen.Loading,
                            DeliveryHostOperation.Select(selected.value.active.proposed, command.offerId, id, routeId(), now())))
                    }
                }
            }
            is DeliveryCommand.Change -> {
                val change = changeOffer ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                if (terms?.active != command.active || change.proposed != command.proposed || !currentChange(change, nav, data.progress?.route))
                    return ContractResult.Failure(ContractError.STATE_CONFLICT)
                val observedRoute = if (identity.source == InputSource.VEHICLE)
                    data.progress?.route ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE) else null
                when (val preview = DeliveryChange(rules.change).change(terms, change, command.offerId, data.balance)) {
                    is ContractResult.Failure -> preview
                    is ContractResult.Success -> ContractResult.Success(DeliveryHostPrepared(DeliveryScreen.Loading,
                        DeliveryHostOperation.Change(rules.change, change, command.offerId, routeId(), observedRoute)))
                }
            }
            is DeliveryCommand.ReviewAbandon -> if (terms?.active != command.active) ContractResult.Failure(ContractError.STATE_CONFLICT)
                else ContractResult.Success(DeliveryHostPrepared(DeliveryHostMapper.abandon(terms, rules.change, names, destinationName(terms.active, nav))))
            is DeliveryCommand.Abandon -> if (terms?.active != command.active) ContractResult.Failure(ContractError.STATE_CONFLICT)
                else ContractResult.Success(DeliveryHostPrepared(DeliveryScreen.Loading, DeliveryHostOperation.Abandon(rules.change, command.active.requestId)))
            DeliveryCommand.ExitReview, DeliveryCommand.Refresh -> {
                if (command == DeliveryCommand.ExitReview) { changing = null; changeOffer = null; changeRevision = null; changeRoute = null }
                when (val read = current(identity, false)) {
                    is ContractResult.Failure -> read
                    is ContractResult.Success -> ContractResult.Success(DeliveryHostPrepared(read.value.screen))
                }
            }
        }
        return when (prepared) {
            is ContractResult.Failure -> prepared
            is ContractResult.Success -> ContractResult.Success(prepared.value.copy(navigation = nav))
        }
    }
    override fun execute(identity: GameIdentity, operation: DeliveryHostOperation): ContractResult<DeliveryHostRead> {
        when (val bound = queries.identity()) {
            is ContractResult.Failure -> return bound
            is ContractResult.Success -> if (bound.value != identity) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        when (val saved = journal.save(identity, operation)) { is ContractResult.Failure -> return saved; else -> Unit }
        val receipt = when (val result = journal.execute(identity, operation)) { is ContractResult.Failure -> return result; is ContractResult.Success -> result.value }
        if (!matches(operation, receipt)) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
        changing = null; changeOffer = null; changeRevision = null; changeRoute = null
        return when (val read = current(identity, false)) {
            is ContractResult.Failure -> ContractResult.Success(DeliveryHostRead(identity,
                DeliveryScreen.Failure("원래 처리 결과는 저장됐지만 현재 화면 정보를 읽지 못했어요. 다시 확인해 주세요.", true),
                pending = operation, receipt = receipt))
            is ContractResult.Success -> ContractResult.Success(read.value.copy(pending = operation, receipt = receipt))
        }
    }
    override fun cancelUnstarted(identity: GameIdentity, operation: DeliveryHostOperation) = journal.cancelUnstarted(identity, operation)
    override fun finish(identity: GameIdentity, operation: DeliveryHostOperation) = journal.finish(identity, operation)
    override fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> {
        val change = changeOffer
        return if (change?.proposed == proposed) queries.discoverChange(identity, change) else offers.displayed(identity, proposed)
    }
    private fun readNavigation(identity: GameIdentity, data: DeliveryHostData): DeliveryNavigationSnapshot {
        val nav = when (val read = navigation.read(identity)) {
            is ContractResult.Success -> read.value
            is ContractResult.Failure -> {
                val reason = if (read.error == ContractError.PERMISSION_DENIED) MissingReason.PERMISSION_DENIED else MissingReason.NO_DATA
                DeliveryNavigationSnapshot(null, null, Observation.Missing(reason), Observation.Missing(reason), Observation.Missing(reason), null)
            }
        }
        return if (identity.source == InputSource.VEHICLE) nav.copy(observed = data.vehicleObserved) else nav
    }
    private fun destinationName(active: ActiveDelivery, nav: DeliveryNavigationSnapshot): String? =
        nav.destinationName.takeIf { nav.destination == active.proposed.destination }
    private fun currentChange(value: DeliveryChangeOffer, nav: DeliveryNavigationSnapshot, route: RouteId?): Boolean {
        val moved = (nav.observed as? Observation.Available)?.value
        return changeRoute == route && nav.selectionRevision != null && changeRevision == nav.selectionRevision && nav.destination == value.proposed.destination &&
            (nav.estimated as? Observation.Available)?.value == value.proposed.estimated &&
            moved?.source == value.proposed.estimated.source && moved.meters == value.traveledMeters
    }
    private fun matches(operation: DeliveryHostOperation, receipt: DeliveryHostReceipt) = when (operation) {
        is DeliveryHostOperation.Select -> receipt is DeliveryHostReceipt.Selected && receipt.value.selected.active.requestId == operation.requestId &&
            receipt.value.selected.active.proposed == operation.proposed && receipt.value.selected.active.offer.id == operation.offerId
        is DeliveryHostOperation.Change -> receipt is DeliveryHostReceipt.Changed && receipt.value.terms.active.requestId == operation.offer.requestId &&
            receipt.value.terms.active.proposed == operation.offer.proposed && receipt.value.terms.active.offer.id == operation.offerId
        is DeliveryHostOperation.Abandon -> receipt is DeliveryHostReceipt.Abandoned && receipt.value.requestId == operation.requestId
        is DeliveryHostOperation.Arrival -> receipt is DeliveryHostReceipt.Arrived
        is DeliveryHostOperation.Hold -> receipt is DeliveryHostReceipt.Held
    }
    private fun sameKey(a: DeliveryList, b: DeliveryList) = a.band == b.band && a.source == b.source && a.createdOn == b.createdOn &&
        a.contentVersion == b.contentVersion && a.ruleVersion == b.ruleVersion
    private fun sourceLabel(source: InputSource) = if (source == InputSource.SIMULATION) "모의 입력" else "차량 입력 프로필 · 실제 내비 증거는 별도 확인"
}
