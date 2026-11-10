package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate
import kotlin.random.Random

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
    fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> {
        if (displayed != (identity to proposed)) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val lists = when (val read = queries.lists(identity, proposed.list.createdOn)) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        if (proposed.list !in lists || proposed.estimated.source != identity.source || proposed.list.source != identity.source)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return queries.discover(identity, proposed).also { if (it is ContractResult.Success) displayed = null }
    }
}
