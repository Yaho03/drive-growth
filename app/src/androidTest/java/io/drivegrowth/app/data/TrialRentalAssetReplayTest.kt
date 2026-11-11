package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalVisit
import io.drivegrowth.app.runtime.rental.product.RentalVisitDefinition
import io.drivegrowth.app.runtime.rental.product.RentalVisitPlanner
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import kotlin.random.Random

@Suppress("DEPRECATION")
class TrialRentalAssetReplayTest : InstrumentationTestCase() {
    private val v4 = ContentVersion("trial-demo-2026-11-10-v4")
    private val previousEcoCategories = mapOf(
        VehicleId("hyundai_casper_electric") to RentalCategory.SMALL,
        VehicleId("hyundai_ioniq5") to RentalCategory.SUV,
        VehicleId("hyundai_ioniq6") to RentalCategory.SEDAN,
        VehicleId("hyundai_nexo") to RentalCategory.SUV,
        VehicleId("hyundai_ioniq9") to RentalCategory.SUV,
    )

    fun testPackagedAssetsSupplyAllFiveCategoriesToGarageAssignment() {
        val assets = instrumentation.targetContext.assets
        val content = ok(AssetGameContent(assets).load().toResult())
        val categories = ok(ok(AssetTrialDefinitionProvider(assets).load()).rentalVisitDefinition()).categories
        assertEquals(previousEcoCategories.keys, categories.filterValues { it == RentalCategory.ECO }.keys)
        assertEquals(mapOf(RentalCategory.SMALL to 3, RentalCategory.SEDAN to 5, RentalCategory.SUV to 5,
            RentalCategory.VAN to 2, RentalCategory.ECO to 5), categories.values.groupingBy { it }.eachCount())
        assertEquals(RentalCategory.entries.toSet(), content.unlockedAt(1).map { categories.getValue(it.vehicleId) }.toSet())

        val garage = Garage(content.catalog)
        val exact = owned("hyundai_ioniq5")
        val alternate = owned("hyundai_ioniq6", level = 2)
        val hydrogen = owned("hyundai_nexo")
        val request = RentalRequest(RequestId("asset-eco-visit"), exact.vehicleId, 1)
        val vehicles = listOf(alternate, hydrogen, exact)
        assertEquals(AssignmentPlan.Candidate(request, exact, RentalMatch.EXACT),
            ok(garage.planRental(request, vehicles, categories, emptySet()) { ContractResult.Success(VehicleActivity.IDLE) }))
        val fallback = ok(garage.planRental(request, vehicles, categories, setOf(exact.ownedVehicleId)) {
            ContractResult.Success(if (it == alternate.ownedVehicleId) VehicleActivity.RENTED else VehicleActivity.IDLE)
        })
        assertEquals(AssignmentPlan.Candidate(request, hydrogen, RentalMatch.SAME_CATEGORY), fallback)
        assertEquals(AssignmentPlan.Candidate(request, alternate, RentalMatch.SAME_CATEGORY),
            ok(garage.planRental(request, listOf(alternate), categories, emptySet()) { ContractResult.Success(VehicleActivity.IDLE) }))
        assertEquals(AssignmentPlan.Revisit, ok(garage.planRental(request.copy(desired = alternate.vehicleId),
            listOf(owned("hyundai_avante")), categories, emptySet()) { ContractResult.Success(VehicleActivity.IDLE) }))
    }

    fun testPackagedEcoDefinitionPreservesOldPreparedAndCommittedAssignment() {
        val context = instrumentation.targetContext
        val source = InputSource.SIMULATION
        val name = "rental-eco-replay-${System.nanoTime()}.db"
        fun factory() = GameSessionFactory(context, mapOf(source to name, InputSource.VEHICLE to "$name-vehicle"), source)
        val at = RentalClock("eco-test-boot", EpochMillis(1_000_000_000L), 0)
        fun journal(definition: RentalVisitDefinition) = SqliteRentalProductJournal(
            factory(), { ContractResult.Success(definition) }, { ContractResult.Success(at) },
            { ContractResult.Success(Observation.Available(at)) },
        )

        val current = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).rentalVisitDefinition())
        assertFalse(v4 == current.version)
        val old = RentalVisitDefinition(v4, current.rules, current.trial, current.intervalMillis,
            current.categories + previousEcoCategories, current.rates, current.demand, current.durations,
            current.returns, current.customers, current.fuelBonus, current.rounding)
        val avante = owned("hyundai_avante")
        try {
            val (identity, catalog) = ok(factory().open()).use { game ->
                ok(game.store.commitGameChange(GameChange(RequestId("add-old-sedan"), TransactionKind.GARAGE,
                    source, old.rules, null, VehicleWrite.Add(avante))))
                game.identity to ok(game.content).catalog
            }
            val cursor = ok(journal(old).activate(identity))
            val prepared = checkNotNull(ok(RentalVisitPlanner().prepare(identity, catalog, old, cursor, at, Random(17)) {
                RequestId("old-sedan-visit")
            }))
            val visit = prepared.visit
            val request = visit.request.copy(desired = VehicleId("hyundai_ioniq6"))
            val original = prepared.copy(visit = RentalVisit(source, request, catalog, old.categories, visit.hourlyRates, visit.terms))
            assertEquals(original, ok(journal(old).save(original)))

            val reopened = journal(current)
            val recovered = checkNotNull(ok(reopened.pending(identity)))
            assertEquals(original, recovered)
            assertEquals(avante.ownedVehicleId, ok(reopened.execute(recovered)))
            ok(reopened.finish(recovered))
            val restarted = journal(current)
            assertNull(ok(restarted.pending(identity)))
            assertEquals(avante.ownedVehicleId, ok(restarted.execute(recovered)))
            val changed = recovered.copy(
                visit = RentalVisit(source, request, catalog, current.categories, visit.hourlyRates, visit.terms))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), restarted.execute(changed))
            assertEquals(prepared.next, ok(restarted.cursor(identity)))
            ok(factory().open()).use { game ->
                assertEquals(Revision(1), ok(game.store.listOwnedVehicles()).single().revision)
            }
        } finally {
            context.deleteDatabase(name)
            context.deleteDatabase("$name-vehicle")
        }
    }

    fun testSavedV4FacilityIsRejectedByV5WithoutChangingSlotOrLedger() = withFacilityDefinitionUpgrade(
        prepare = { commands, profile, command -> ok(commands.save(profile, command)) },
        checkReopened = { store, profile, command, saved ->
            val commands = store.hostCommands
            val settlements = ok(store.listUnviewedSettlements())
            assertEquals(HostCheckpointPhase.SAVED, saved.phase)
            assertEquals(saved, ok(commands.load(profile)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), commands.execute(profile, command))
            assertEquals(saved, ok(commands.load(profile)))
            assertNull(ok(commands.facilityResult(profile, command)))
            assertEquals(GameMoney(10_000), ok(store.balance()))
            assertTrue(ok(store.rental.shop()).facilities.isEmpty())
            assertEquals(settlements, ok(store.listUnviewedSettlements()))
            ok(commands.cancelUnstarted(profile, command))
            assertNull(ok(commands.load(profile)))
            assertEquals(settlements, ok(store.listUnviewedSettlements()))

            val current = ok(store.trialDefinitions.load())
            val newCommand = command.copy(requestId = RequestId("v5-facility"), definitionVersion = current.version,
                offer = current.facilityOffers.getValue(Facility.CAR_WASH))
            assertEquals(HostCommandCheckpoint(profile, newCommand, HostCheckpointPhase.SAVED),
                ok(commands.save(profile, newCommand)))
            val result = ok(commands.execute(profile, newCommand)) as HostCheckpointResult.FacilityPurchase
            assertEquals(newCommand.requestId, result.value.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, newCommand.offer.price), result.value.transaction.money)
            val expectedBalance = GameMoney(10_000 - newCommand.offer.price.units)
            assertEquals(expectedBalance, result.value.balance)
            assertEquals(HostCommandCheckpoint(profile, newCommand, HostCheckpointPhase.STARTED),
                ok(commands.load(profile)))
            assertEquals(result, ok(commands.execute(profile, newCommand)))
            assertEquals(result, ok(commands.finish(profile, newCommand)))
            assertNull(ok(commands.load(profile)))
            assertEquals(result, ok(commands.facilityResult(profile, newCommand)))
            assertEquals(expectedBalance, ok(store.balance()))
            assertEquals(setOf(Facility.CAR_WASH), ok(store.rental.shop()).facilities)
            assertEquals(settlements + result.value, ok(store.listUnviewedSettlements()))
            assertNull(ok(commands.facilityResult(profile, command)))
        },
    )

    fun testCommittedV4FacilityReplaysAndAcknowledgesUnderV5() = withFacilityDefinitionUpgrade(
        prepare = { commands, profile, command ->
            ok(commands.save(profile, command))
            ok(commands.execute(profile, command))
        },
        checkReopened = { store, profile, command, original ->
            val commands = store.hostCommands
            val settlements = ok(store.listUnviewedSettlements())
            val receipt = (original as HostCheckpointResult.FacilityPurchase).value
            assertEquals(1, settlements.count { it.transaction.requestId == receipt.transaction.requestId })
            assertEquals(HostCommandCheckpoint(profile, command, HostCheckpointPhase.STARTED), ok(commands.load(profile)))
            assertEquals(original, ok(commands.execute(profile, command)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), commands.cancelUnstarted(profile, command))
            assertEquals(original, ok(commands.finish(profile, command)))
            assertNull(ok(commands.load(profile)))
            assertEquals(original, ok(commands.facilityResult(profile, command)))
            val currentVersion = ok(store.trialDefinitions.load()).version
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                commands.facilityResult(profile, command.copy(definitionVersion = currentVersion)))
            assertEquals(GameMoney(10_000 - command.offer.price.units), ok(store.balance()))
            assertEquals(setOf(Facility.CAR_WASH), ok(store.rental.shop()).facilities)
            assertEquals(settlements, ok(store.listUnviewedSettlements()))
        },
    )

    private fun <T> withFacilityDefinitionUpgrade(
        prepare: (HostCommandCheckpointLedger, ProfileId, HostCheckpointCommand.FacilityPurchase) -> T,
        checkReopened: (SqliteGameChangeStore, ProfileId, HostCheckpointCommand.FacilityPurchase, T) -> Unit,
    ) {
        val context = instrumentation.targetContext
        val assets = context.assets
        val current = ok(AssetTrialDefinitionProvider(assets).load())
        val ids = ok(AssetGameContent(assets).load().toResult()).catalog.vehicles.map { it.vehicleId }.toSet()
        val csv = assets.open("content/trial-definitions.csv").bufferedReader().use { it.readText() }
        // 시설 공급에 쓰이는 v4 버전과 동일 제안을 복원한다.
        val previous = ok(parseTrialDefinition(
            csv.replaceFirst("definition_version,${current.version.value}", "definition_version,${v4.value}"), ids))
        assertEquals(v4, previous.version)
        assertFalse(v4 == current.version)
        assertEquals(previous.facilityOffers, current.facilityOffers)
        val source = InputSource.SIMULATION
        val name = "facility-definition-upgrade-${System.nanoTime()}.db"
        val command = HostCheckpointCommand.FacilityPurchase(RequestId("v4-facility"), source, v4,
            previous.facilityOffers.getValue(Facility.CAR_WASH))
        try {
            val (profile, original) = SqliteGameChangeStore(context, source, name).use { store ->
                store.trialDefinitions = TrialDefinitionSource { ContractResult.Success(previous) }
                val profile = ok(store.localProfileId())
                ok(store.commitGameChange(GameChange(RequestId("facility-fixture-funds"), TransactionKind.GARAGE,
                    source, previous.rules, MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
                ok(store.atomic { db ->
                    db.execSQL("UPDATE shop SET level=3,capacity=7") // 해금 상태를 격리 DB에 준비한다.
                    ContractResult.Success(Unit)
                })
                profile to prepare(store.hostCommands, profile, command)
            }
            SqliteGameChangeStore(context, source, name).use { store ->
                assertEquals(profile, ok(store.localProfileId()))
                assertEquals(current.version, ok(store.trialDefinitions.load()).version)
                checkReopened(store, profile, command, original)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun owned(model: String, level: Int = 1) = OwnedVehicle(
        OwnedVehicleId(model), VehicleId(model), level, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT,
    )

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
