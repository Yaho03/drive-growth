package io.drivegrowth.app.host.delivery

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.FirstGrant
import java.time.LocalDate
import java.util.UUID

/** 격리 SIMULATION DB의 B→E 연결. 실제 SDK·차량·제품 복원 시험이 아니다. */
@Suppress("DEPRECATION")
class SessionDeliveryJournalSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource,String>
    private lateinit var games: GameSessionFactory
    private lateinit var identity: GameIdentity
    private lateinit var proposed: DeliveryProposed
    private val id = RequestId("journal-select")
    private val route = RouteId("journal-route")
    private val at = EpochMillis(1_000_000)
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(), r is ContractResult.Success); return (r as ContractResult.Success).value }
    override fun setUp() {
        super.setUp()
        val prefix = "delivery-session-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        games = GameSessionFactory(context, names, InputSource.SIMULATION)
        val onboarding = StoredOnboardingHostSource(games)
        identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
        ok(games.open()).use { game ->
            val content = ok(game.content)
            val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), content.catalog.vehicles.first().vehicleId, GameMoney(10))
            val list = DeliveryList(0, identity.source, LocalDate.of(2026,11,10), content.catalog.contentVersion,
                RuleVersion("journal-test"), listOf(income), 1)
            ok(game.store.delivery.saveList(list))
            proposed = DeliveryProposed(DestinationKey("confirmed-fixture"), EstimatedRouteDistance(1000, identity.source), list, list.offers)
        }
    }
    override fun tearDown() { names.values.forEach(context::deleteDatabase); super.tearDown() }
    private fun select() = DeliveryHostOperation.Select(proposed, proposed.offers.single().id, id, route, at)
    fun testReopenedJournalReplaysOriginalSelectionAndSameFactoryTermsWithoutExtraMode() {
        ok(SessionDeliveryJournal(games).save(identity, select()))
        val recovered = SessionDeliveryJournal(games)
        assertEquals(select(), ok(recovered.pending(identity)))
        val receipt = ok(recovered.execute(identity, select()))
        assertEquals(receipt, ok(SessionDeliveryJournal(games).execute(identity, select())))
        val data = ok(FactoryDeliveryHostQueries(games) { it.terms() }.read(identity))
        assertEquals(id, data.terms!!.active.requestId); assertEquals(GameMoney(0), data.terms!!.paid)
        assertEquals(route, data.progress!!.route); assertEquals(GameMoney(1000), data.balance)
        ok(recovered.finish(identity, select())); assertNull(ok(recovered.pending(identity)))
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testForeignCheckpointAndProfileAreRejectedWithoutLosingOriginal() {
        val foreign = ok(games.open()).use { game ->
            val vehicle = ok(game.store.listOwnedVehicles()).single()
            HostCheckpointCommand.Garage(ok(game.content).catalog.contentVersion, GameChange(RequestId("other"),
                TransactionKind.GARAGE, identity.source, RuleVersion("other"), vehicle = VehicleWrite.Update(
                    vehicle.ownedVehicleId, vehicle.revision, 2, VehicleLifecycle.OWNED, 0))).also {
                ok(game.store.hostCommands.save(identity.profileId, it))
            }
        }
        val journal = SessionDeliveryJournal(games)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), journal.pending(identity))
        assertTrue(journal.save(identity, select()) is ContractResult.Failure)
        assertTrue(journal.cancelUnstarted(identity, select()) is ContractResult.Failure)
        assertTrue(journal.pending(identity.copy(profileId = ProfileId("foreign"))) is ContractResult.Failure)
        ok(games.open()).use { assertEquals(foreign, ok(it.store.hostCommands.load(identity.profileId))!!.command) }
    }
    fun testNonfinalAwaitingReceiptAcknowledgementOnlyCancelsSameSavedOperation() {
        val journal = SessionDeliveryJournal(games)
        ok(journal.save(identity, select())); ok(journal.execute(identity, select())); ok(journal.finish(identity, select()))
        val waiting = DeliveryHostOperation.Arrival(DeliverySettlementRules(proposed.list.ruleVersion, 86400000,86400000,5),
            id, Observation.Missing(MissingReason.NO_DATA), EpochMillis(at.value + 1000), proposed.list, proposed.list)
        ok(journal.save(identity, waiting))
        val receipt = ok(journal.execute(identity, waiting)) as DeliveryHostReceipt.Arrived
        assertTrue(receipt.value.outcome is DeliveryOutcome.AwaitingEvidence)
        ok(games.open()).use { assertEquals(HostCheckpointPhase.SAVED, ok(it.store.hostCommands.load(identity.profileId))!!.phase) }
        ok(journal.finish(identity, waiting))
        ok(games.open()).use { assertNull(ok(it.store.hostCommands.load(identity.profileId))); assertEquals(id, ok(it.store.delivery.active())!!.requestId); assertEquals(GameMoney(1000), ok(it.store.balance())) }
    }
}
