package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate
import kotlin.random.Random

internal const val DELIVERY_SLOT_NOTICE = "다른 화면에서 보관한 요청을 먼저 확인해 주세요."

/** 후보 생성·저장과 실제 표시 확인을 분리한다. 규칙은 중앙 공급자가 명시한다. worker 전용. */
class DeliveryOfferSource(private val queries: DeliveryHostQueries,
    private val definitions: () -> ContractResult<DeliveryHostRules>, private val navigation: DeliveryNavigationPort,
    private val today: () -> LocalDate, private val random: () -> Random) {
    private var displayed: Pair<GameIdentity, DeliveryProposed>? = null
    fun offers(identity: GameIdentity): ContractResult<DeliveryHostRead> {
        displayed = null
        val current = when (val read = queries.read(identity)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (current.identity != identity || current.terms != null) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val rules = when (val read = definitions()) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val nav = when (val read = navigation.read(identity)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val destination = nav.destination ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val estimated = (nav.estimated as? Observation.Available)?.value
        if (estimated != null && estimated.source != identity.source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val day = today()
        val lists = when (val read = queries.lists(identity, day)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val generator = DeliveryProposal(rules.proposal)
        val proposed = when (val read = generator.propose(lists, destination, nav.estimated, current.content,
            current.shopLevel, day, random())) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        val saved = when (val read = queries.saveList(identity, proposed.list)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        // 다른 연결이 먼저 저장했다면 반드시 저장소가 반환한 목록으로 다시 공급한다. 새 추첨으로 덮지 않는다.
        val stored = when (val read = generator.propose(listOf(saved), destination, nav.estimated, current.content,
            current.shopLevel, day, random())) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (stored.list != saved || stored.list.source != identity.source) return ContractResult.Failure(ContractError.STORAGE_FAILURE)
        val names = current.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        displayed = identity to stored
        return ContractResult.Success(DeliveryHostRead(identity,
            DeliveryScreen.Offers(DeliveryHostMapper.proposal(stored, names, nav.destinationName, current.balance, identity.source)),
            current.held, stored, navigation = nav))
    }
    internal fun readOnly(data: DeliveryHostData, nav: DeliveryNavigationSnapshot, rules: DeliveryRules,
        names: Map<VehicleId, String>): ContractResult<DeliveryHostRead> {
        displayed = null
        val identity = data.identity
        val quote = (nav.estimated as? Observation.Available)?.value
        val destination = nav.destination
        val day = today()
        val screen = if (destination != null && quote != null && quote.meters > 0 && quote.source == identity.source) {
            val list = when (val read = queries.lists(identity, day)) {
                is ContractResult.Failure -> return read
                is ContractResult.Success -> read.value.singleOrNull {
                    it.band == rules.bandIndexOf(quote.meters) && it.source == identity.source &&
                        it.createdOn == day && it.contentVersion == data.content.catalog.contentVersion && it.ruleVersion == rules.ruleVersion
                }
            }
            if (list == null) null else when (val read = DeliveryProposal(rules).propose(
                listOf(list), destination, nav.estimated, data.content, data.shopLevel, day, random())) {
                is ContractResult.Failure -> return read
                is ContractResult.Success -> {
                    val proposal = DeliveryHostMapper.proposal(read.value, names, nav.destinationName, data.balance, identity.source)
                    DeliveryScreen.Offers(proposal.copy(availability = proposal.availability.mapValues {
                        DeliveryAvailability(false, DELIVERY_SLOT_NOTICE)
                    }))
                }
            }
        } else null
        return ContractResult.Success(DeliveryHostRead(identity,
            screen ?: DeliveryScreen.Destination(nav.destinationName.takeIf { destination != null },
                quote?.takeIf { destination != null && it.source == identity.source }
                    ?.let { io.drivegrowth.app.ui.delivery.deliveryDistance(it.meters) },
                null, false, DELIVERY_SLOT_NOTICE), data.held, navigation = nav, readOnly = true))
    }

    fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> {
        val (owner, original) = displayed ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
        if (owner != identity || !original.containsDisplayedOffers(proposed)) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val lists = when (val read = queries.lists(identity, proposed.list.createdOn)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (proposed.list !in lists || proposed.estimated.source != identity.source || proposed.list.source != identity.source)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return queries.discover(identity, proposed).also {
            if (it is ContractResult.Success) {
                val remaining = original.offers.filterNot { offer -> offer in proposed.offers }
                displayed = remaining.takeIf { it.any { offer -> offer is DeliveryOffer.Purchase } }
                    ?.let { identity to original.copy(offers = it) }
            }
        }
    }
}

internal fun DeliveryProposed.containsDisplayedOffers(shown: DeliveryProposed) =
    destination == shown.destination && estimated == shown.estimated && list == shown.list &&
        shown.offers.distinct().size == shown.offers.size && offers.containsAll(shown.offers)
