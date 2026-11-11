package io.drivegrowth.app.host.delivery

import android.test.InstrumentationTestCase
import android.view.View
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.delivery.DeliveryFrame
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.delivery.DeliveryView
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.FirstGrant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.random.Random

/** 같은 격리 DB를 쓰는 실제 View·호스트·조회 경계. 목적지와 표시 확인은 시험 입력이다. */
@Suppress("DEPRECATION")
class DeliveryDiscoverySessionSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val day = LocalDate.of(2026, 11, 11)
    private lateinit var names: Map<InputSource, String>
    private lateinit var games: GameSessionFactory
    private lateinit var identity: GameIdentity
    private lateinit var storage: FactoryDeliveryHostQueries
    private lateinit var delivery: DeliveryHostController
    private lateinit var queries: HostQueryController
    private lateinit var realSource: StoredDeliveryHostSource
    private lateinit var view: DeliveryView
    private lateinit var frame: DeliveryFrame
    private lateinit var snapshot: HostSnapshot
    private lateinit var list: DeliveryList
    private lateinit var unshownList: DeliveryList
    private lateinit var expected: Set<CollectionKey>
    private lateinit var unshown: CollectionKey
    private lateinit var incomeOffer: DeliveryOffer.Income
    private val work = ArrayDeque<Runnable>()
    private val replies = ArrayDeque<Runnable>()
    private val attempted = mutableListOf<Pair<GameIdentity, DeliveryProposed>>()
    private val saves = mutableListOf<ContractResult<Unit>>()
    private var reads = 0
    private var publications = 0
    private var refreshes = 0
    private var routeAvailable = true
    private var acceptedOfferVersion: Long? = null

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun <T> ui(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun drain() {
        while (work.isNotEmpty() || replies.isNotEmpty()) {
            if (work.isNotEmpty()) work.removeFirst().run()
            if (replies.isNotEmpty()) ui { replies.removeFirst().run() }
        }
    }

    override fun setUp() {
        super.setUp()
        assertEquals("io.drivegrowth.verify.discoveryf", context.packageName)
        val prefix = "discovery-session-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        games = GameSessionFactory(context, names, InputSource.SIMULATION)
        val onboarding = StoredOnboardingHostSource(games)
        identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
        storage = FactoryDeliveryHostQueries(games) { it.terms() }
        val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val rules = DeliveryHostRules(ok(definition.deliveryRules()), ok(definition.deliveryChangeRules()),
            ok(definition.deliverySettlementRules()))
        val hostRead = StoredHostSource(context, sessions = games)
        val initial = ok(hostRead.read())
        val models = initial.content.catalog.vehicles.map { it.vehicleId }
            .filterNot { id -> initial.ownedVehicles.any { it.vehicleId == id } }
        val purchase1 = DeliveryOffer.Purchase(DeliveryOfferId("offer-0"), models[0], Rarity.RARE, GameMoney(100))
        val purchase2 = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), models[1], Rarity.ADVANCED, GameMoney(100))
        expected = setOf(CollectionKey(purchase1.vehicleId, purchase1.rarity), CollectionKey(purchase2.vehicleId, purchase2.rarity))
        unshown = CollectionKey(models[2], Rarity.LEGEND)
        incomeOffer = DeliveryOffer.Income(DeliveryOfferId("offer-2"), models[3], GameMoney(5))
        val meters = rules.proposal.incomeMinMeters.coerceAtLeast(1)
        list = DeliveryList(rules.proposal.bandIndexOf(meters), identity.source, day,
            initial.content.catalog.contentVersion, rules.proposal.ruleVersion, listOf(
                purchase1, purchase2, incomeOffer), 3)
        unshownList = list.copy(createdOn = day.minusDays(1), offers = listOf(
            DeliveryOffer.Purchase(DeliveryOfferId("offer-0"), unshown.vehicleId, unshown.rarity, GameMoney(100))), nextOfferNumber = 1)
        assertEquals(list, ok(storage.saveList(identity, list)))
        assertEquals(unshownList, ok(storage.saveList(identity, unshownList)))
        val navigation = object : DeliveryNavigationPort {
            override fun read(identity: GameIdentity): ContractResult<DeliveryNavigationSnapshot> =
                if (!routeAvailable) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                else ContractResult.Success(DeliveryNavigationSnapshot(DestinationKey("fixture-route"), "시험 목적지",
                    Observation.Available(EstimatedRouteDistance(meters, identity.source)),
                    Observation.Missing(MissingReason.NO_DATA), Observation.Missing(MissingReason.NO_DATA), 1))
            override fun chooseDestination(identity: GameIdentity, active: ActiveDelivery?) = ContractResult.Success(Unit)
        }
        realSource = StoredDeliveryHostSource(storage, { ContractResult.Success(rules) }, navigation,
            SessionDeliveryJournal(games), { day }, { EpochMillis(1000) }, { Random(41) },
            { RequestId("fixture-choice") }, { RouteId("fixture-route") })
        val tracked = object : DeliveryHostSource by realSource {
            override fun read(): ContractResult<DeliveryHostRead> {
                reads++
                return realSource.read()
            }
            override fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> {
                attempted += identity to proposed
                return realSource.displayed(identity, proposed).also { saves += it }
            }
        }
        val worker = Executor { work.addLast(it) }
        val main = Executor { replies.addLast(it) }
        ui {
            queries = HostQueryController(worker, main, hostRead::read) { state ->
                if (state is HostQueryState.Content) {
                    snapshot = state.snapshot
                    publications++
                }
            }
            delivery = DeliveryHostController(tracked, worker, main)
            view = DeliveryView(context, delivery::onRequest)
            delivery.attach({ next, enabled ->
                frame = next
                if (view.render(next, enabled) && enabled && next.screen is DeliveryScreen.Offers)
                    acceptedOfferVersion = next.version
            }, { request, next -> view.complete(request, next) }, {
                refreshes++
                queries.refresh()
            })
            queries.setControlsEnabled(true)
            delivery.setControlsEnabled(true)
            delivery.refresh()
        }
        drain()
    }

    override fun tearDown() {
        if (::delivery.isInitialized) ui { delivery.release(); queries.release(); view.release() }
        if (::names.isInitialized) names.values.forEach(context::deleteDatabase)
        super.tearDown()
    }

    private fun keys() = ok(games.open()).use { ok(it.store.discoveredVehicles()) }

    private fun assertCollection(value: HostSnapshot, discoveries: Set<CollectionKey>) {
        val history = ok(value.collectionHistory)
        assertEquals(discoveries, history.discovered)
        assertEquals(identity.source, history.source)
        val shown = GarageHostMapper().collection(value, value.collectionHistory) as CollectionScreenState.Content
        val cells = shown.entries.associate { it.entry.key to it.entry.state }
        for (key in expected) assertEquals(
            if (key in discoveries) CollectionState.DISCOVERED else CollectionState.UNDISCOVERED, cells[key])
        assertEquals(CollectionState.UNDISCOVERED, cells[unshown])
        for (rarity in Rarity.entries)
            assertEquals(CollectionState.UNDISCOVERED, cells[CollectionKey(incomeOffer.vehicleId, rarity)])
    }

    private fun acknowledge(): DeliveryProposed {
        val original = (frame.screen as DeliveryScreen.Offers).proposal.proposed
        assertEquals(list, original.list)
        assertEquals(list.offers, original.offers)
        assertEquals(frame.version, acceptedOfferVersion)
        assertNull(ui { view.saveUiState().selectedOfferId })
        ui { delivery.acknowledgeDisplayed(frame.version) }
        drain()
        return original
    }

    private fun assertPersisted(before: HostSnapshot) {
        assertEquals(1, refreshes)
        assertEquals(2, publications)
        assertEquals(expected, keys())
        assertCollection(snapshot, expected)
        val reopened = GameSessionFactory(context, names, InputSource.SIMULATION)
        val reopenedSnapshot = ok(StoredHostSource(context, sessions = reopened).read())
        assertCollection(reopenedSnapshot, expected)
        assertEquals(before.balance, reopenedSnapshot.balance)
        assertEquals(before.ownedVehicles, reopenedSnapshot.ownedVehicles)
        assertEquals(before.unviewedSettlements, reopenedSnapshot.unviewedSettlements)
        assertEquals(listOf(list), ok(storage.lists(identity, day)))
        assertEquals(listOf(unshownList), ok(storage.lists(identity, day.minusDays(1))))
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }

    fun testRenderedFixtureRecordsUnselectedPurchasesAndRefreshesCollectionInSameSession() {
        val before = snapshot
        assertCollection(before, emptySet())
        assertTrue(keys().isEmpty())
        assertEquals(0, attempted.size)
        val original = acknowledge()
        assertEquals(listOf(identity to original), attempted)
        assertPersisted(before)
    }

    fun testSqliteFailureRollsBackAndRetryReplaysSameDisplayedListWithoutRouteRead() {
        val before = snapshot
        val proposed = (frame.screen as DeliveryScreen.Offers).proposal.proposed
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            realSource.displayed(identity, proposed.copy(list = proposed.list.copy(ruleVersion = RuleVersion("wrong-rule")))))
        assertTrue(keys().isEmpty())
        ok(games.open()).use { game -> ok(game.store.atomic { db ->
            db.execSQL("CREATE TRIGGER discovery_fixture_abort BEFORE INSERT ON discoveries " +
                "WHEN NEW.rarity = 'ADVANCED' BEGIN SELECT RAISE(ABORT, 'discovery fixture'); END")
            ContractResult.Success(Unit)
        }) }
        val original = acknowledge()
        assertTrue(frame.screen is DeliveryScreen.Failure)
        assertEquals(listOf(ContractResult.Failure(ContractError.STORAGE_FAILURE)), saves)
        assertEquals(0, refreshes)
        assertEquals(1, publications)
        assertTrue(keys().isEmpty())
        assertCollection(snapshot, emptySet())
        assertEquals(listOf(list), ok(storage.lists(identity, day)))
        ok(games.open()).use { game -> ok(game.store.atomic { db ->
            db.execSQL("DROP TRIGGER discovery_fixture_abort")
            ContractResult.Success(Unit)
        }) }
        routeAvailable = false
        ui {
            val retry = view.findViewWithTag<View>("delivery:refresh")
            assertNotNull(retry)
            assertTrue(retry.performClick())
        }
        drain()
        assertEquals(1, reads)
        assertEquals(listOf(identity to original, identity to original), attempted)
        assertEquals(ContractResult.Success(Unit), saves.last())
        assertEquals(original, (frame.screen as DeliveryScreen.Offers).proposal.proposed)
        assertPersisted(before)
    }
}
