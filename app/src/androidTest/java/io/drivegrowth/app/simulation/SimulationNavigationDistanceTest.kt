package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.driving.DrivingRecordingResult
import io.drivegrowth.app.host.driving.ObservedTrip
import io.drivegrowth.app.host.driving.RecordingState
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.garage.FirstGrant
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.random.Random

@Suppress("DEPRECATION")
class SimulationNavigationDistanceTest : InstrumentationTestCase() {
    fun testCompletedRunDistanceCannotBeUsedToChangeNewDeliveryBeforeDeparture() {
        val context = instrumentation.targetContext
        val name = "simulation-navigation-distance-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val source = InputSource.SIMULATION
        val games = GameSessionFactory(context, mapOf(source to "$name.db"), source)
        val day = LocalDate.of(2026, 11, 10)
        val version = RuleVersion("simulation-distance-test")
        var timeline = SimulationTimeline()
        fun navigation() = SimulationNavigation(prefs, games, { ContractResult.Success(timeline.clock) }, Executor { it.run() })
        val nav = navigation()

        try {
            val onboarding = StoredOnboardingHostSource(games)
            val identity = ok(onboarding.read()).identity
            ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
            val content = ok(games.open()).use { ok(it.content) }
            val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), content.catalog.vehicles.first().vehicleId, GameMoney(10))
            val list = DeliveryList(0, source, day, content.catalog.contentVersion, version, listOf(income), 1)
            val destination = DestinationKey("unified-simulation-destination")
            val estimated = EstimatedRouteDistance(36_000, source)
            fun select(list: DeliveryList, request: RequestId, route: RouteId) = ok(games.open()).use { game ->
                val proposed = DeliveryProposed(destination, estimated, list, list.offers)
                ok(game.store.delivery.select(proposed, list.offers.single().id, request))
                ok(game.store.delivery.beginRoute(request, route, timeline.clock.epoch))
            }
            fun record(navigation: SimulationNavigation, trip: ObservedTrip?) {
                val state = RecordingState(identity, 0, "simulation-distance-test", trip,
                    timeline.frame().observation, null, null, emptyList())
                ok(navigation.observed(timeline, ContractResult.Success(DrivingRecordingResult(state, emptyList(), null))))
            }

            ok(games.open()).use { ok(it.store.delivery.saveList(list)) }
            val first = select(list, RequestId("first-delivery"), RouteId("first-route"))
            timeline = timeline.begin()!!
            val trip = ObservedTrip("first-trip", timeline.frame().observation, timeline.frame().zone,
                ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
            record(nav, trip)
            timeline = generateSequence(timeline) { it.next() }.first { it.arrival }
            val traveled = trip.copy(meters = BigDecimal.valueOf(36_000))
            record(nav, traveled)
            val arrival = Observation.Available(DeliveryArrivalEvidence(first.route, source, timeline.clock.epoch))
            timeline = generateSequence(timeline) { it.next() }.last()

            val reopened = navigation()
            record(reopened, null)
            val terminal = ok(reopened.port.read(identity))
            assertEquals(Observation.Available(ObservedDistance(36_000, source)), terminal.observed)
            assertEquals(arrival, terminal.arrival)

            val replacement = ok(games.open()).use { game ->
                val ledger = game.store.delivery
                val rules = DeliverySettlementRules(version, 86_400_000, 86_400_000, 5)
                val settled = ok(ledger.settleArrival(rules, first.active.requestId, terminal.arrival, timeline.clock.epoch) { stored, _ ->
                    val next = income.copy(id = DeliveryOfferId("offer-1"))
                    ContractResult.Success(stored.copy(offers = listOf(next), nextOfferNumber = 2))
                })
                assertTrue(settled.outcome is DeliveryOutcome.IncomeCompleted)
                ok(ledger.lists(day)).single()
            }
            val second = select(replacement, RequestId("second-delivery"), RouteId("second-route"))
            ok(reopened.port.chooseDestination(identity, second.active))
            reopened.setControlsEnabled(true)
            ok(reopened.confirm())

            val change = ok(reopened.port.read(identity))
            val missing = Observation.Missing(MissingReason.NO_DATA)
            assertEquals(missing, change.observed)
            assertEquals(Observation.Available(estimated), change.estimated)
            val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                DeliveryProposal(ok(definition.deliveryRules())).proposeChange(second.active, change.observed, change.estimated,
                    requireNotNull(change.destination), content, 1, day, Random(0)))

            timeline = timeline.begin()!!
            record(reopened, traveled)
            assertEquals(missing, ok(reopened.port.read(identity)).observed)
            val secondTrip = trip.copy(id = "second-trip", departure = timeline.frame().observation)
            record(reopened, secondTrip)
            assertEquals(Observation.Available(ObservedDistance(0, source)), ok(reopened.port.read(identity)).observed)
            assertEquals(missing, ok(reopened.port.read(identity.copy(profileId = ProfileId("other-profile")))).observed)

            ok(games.open()).use {
                ok(it.store.delivery.beginRoute(second.active.requestId, RouteId("replacement-route"), timeline.clock.epoch, second.route))
            }
            assertEquals(missing, ok(reopened.port.read(identity)).observed)
        } finally {
            prefs.edit().clear().commit()
            context.deleteDatabase("$name.db")
        }
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
