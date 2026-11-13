package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.data.DeliveryLedger
import io.drivegrowth.app.data.DeliveryObservedDistance
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.CollectionKey
import java.time.LocalDate

/** readTerms에는 E의 검증된 실제 권위 조회를 공급한다. active에서 paid/changeUsed를 추측하지 않는다. */
class FactoryDeliveryHostQueries(private val games: GameSessionFactory,
    private val readTerms: (DeliveryLedger) -> ContractResult<DeliveryTerms?>) : DeliveryHostQueries {
    private fun <T> use(identity: GameIdentity?, action: (GameSession) -> ContractResult<T>): ContractResult<T> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { session ->
            if (identity != null && identity != session.identity) ContractResult.Failure(ContractError.STATE_CONFLICT)
            else action(session)
        }
    }
    override fun identity() = use(null) { ContractResult.Success(it.identity) }
    override fun read(identity: GameIdentity): ContractResult<DeliveryHostData> = use(identity) { session ->
        val store = session.store
        val initialized = when (val read = store.readInitialization(identity.profileId)) {
            is ContractResult.Failure -> return@use read
            is ContractResult.Success -> read.value
        }
        if (initialized.status !is ProfileInitializationStatus.Existing) return@use ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val content = when (val read = session.content) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val balance = when (val read = store.balance()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val shop = when (val read = store.rental.shop()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val terms = when (val read = readTerms(store.delivery)) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val active = when (val read = store.delivery.active()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val routeDistance = store.atomic { db ->
            val progress = store.delivery.inProgress(db)
            when (val observed = DeliveryObservedDistance.read(db, identity, progress, store.currentDeliveryDistanceSession())) {
                is ContractResult.Failure -> observed
                is ContractResult.Success -> ContractResult.Success(progress to observed.value)
            }
        }
        val (progress, vehicleObserved) = when (routeDistance) {
            is ContractResult.Failure -> return@use routeDistance
            is ContractResult.Success -> routeDistance.value
        }
        val owned = when (val read = store.listOwnedVehicles()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val held = when (val read = store.delivery.held()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (terms?.active != active || progress != null && progress.active != active ||
            active != null && active.proposed.estimated.source != identity.source || held.any { it.source != identity.source })
            return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
        ContractResult.Success(DeliveryHostData(identity, content, balance, shop.progress.shopLevel, (shop.capacity - owned.size).coerceAtLeast(0), terms, progress, held.toList(), vehicleObserved))
    }
    override fun lists(identity: GameIdentity, day: LocalDate) = use(identity) { it.store.delivery.lists(day) }
    override fun saveList(identity: GameIdentity, list: DeliveryList) = use(identity) { session ->
        if (list.source != identity.source) ContractResult.Failure(ContractError.INVALID_INPUT)
        else session.store.delivery.saveList(list)
    }
    override fun discoverChange(identity: GameIdentity, change: DeliveryChangeOffer): ContractResult<Unit> = use(identity) { session ->
        val content = when (val read = session.content) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val terms = when (val read = readTerms(session.store.delivery)) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (terms == null || terms.changeUsed || terms.active.requestId != change.requestId ||
            change.proposed.list.source != identity.source || change.proposed.estimated.source != identity.source ||
            change.proposed.list.contentVersion != content.catalog.contentVersion)
            return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
        val keys = change.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().mapTo(mutableSetOf()) { CollectionKey(it.vehicleId, it.rarity) }
        session.store.recordDiscoveries(content.catalog, keys)
    }
    override fun discover(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> = use(identity) { session ->
        if (proposed.estimated.source != identity.source || proposed.list.source != identity.source)
            return@use ContractResult.Failure(ContractError.INVALID_INPUT)
        val content = when (val read = session.content) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (proposed.list.contentVersion != content.catalog.contentVersion) return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
        val lists = when (val read = session.store.delivery.lists(proposed.list.createdOn)) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (proposed.list !in lists || proposed.offers.any { it !in proposed.list.offers }) return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
        val keys = proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().mapTo(mutableSetOf()) { CollectionKey(it.vehicleId, it.rarity) }
        session.store.recordDiscoveries(content.catalog, keys)
    }
}
