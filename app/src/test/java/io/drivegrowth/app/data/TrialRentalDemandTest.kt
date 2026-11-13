package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.RentalCategory
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TrialRentalDemandTest {
    private fun asset(name: String) = sequenceOf(
        File("src/main/assets/content/$name"),
        File("app/src/main/assets/content/$name"),
    ).first { it.isFile }.readText()

    private val content = (GameContentLoader.load(asset("vehicle-facts.csv"), asset("game-content.csv")) as ContentLoad.Loaded).snapshot
    private val ids = content.catalog.vehicles.map { it.vehicleId }.toSet()
    private fun <T> ok(result: ContractResult<T>) = (result as ContractResult.Success).value
    private val trial = ok(parseTrialDefinition(asset("trial-definitions.csv"), ids))
    private val planner = RentalVisitPlanner()

    private class Draws(private vararg val draws: Long) : Random() {
        private var index = 0
        override fun nextLong(until: Long): Long = (draws.getOrNull(index++) ?: 0).also { require(it in 0 until until) }
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun selectDemand(definition: RentalVisitDefinition, car: Long, city: Long): RentalDemandSelection {
        val at = RentalClock("fixture", EpochMillis(10_000), 10_000)
        val cursor = RentalVisitCursor(Revision(0), 0, at)
        val identity = GameIdentity(ProfileId("trial"), InputSource.SIMULATION)
        val plan = checkNotNull(ok(planner.prepareWithDemand(identity, content.catalog, definition, cursor, at,
            Draws(car, 0, 0, city)) { RequestId("demand-fixture") }))
        assertEquals(plan.prepared.visit.request.desired, plan.demand.desired)
        return plan.demand
    }

    @Test fun `해금 레벨은 희망 차종만 좁히고 방문 간격과 대여 정의 버전을 공급한다`() {
        val current = ok(trial.rentalVisitDefinition())
        val clock = RentalClock("fixture", EpochMillis(10_000), 10_000)
        val cursor = RentalVisitCursor(Revision(0), 0, clock)
        val identity = GameIdentity(ProfileId("trial"), InputSource.SIMULATION)
        for ((index, size) in listOf(8, 14, 18, 20).withIndex()) {
            val level = index + 1
            val unlocked = content.unlockedAt(level).map { it.vehicleId }.toSet()
            val definition = ok(trial.rentalVisitDefinition(content, level))
            assertEquals(size, definition.demand.count { it.value > 0 })
            assertEquals(unlocked, definition.demand.filterValues { it > 0 }.keys)
            assertEquals(current.demand.filterKeys { it in unlocked }, definition.demand.filterValues { it > 0 })
            assertEquals(ids, definition.categories.keys)
            assertEquals(current.rates, definition.rates)
            assertEquals(current.rules, definition.rules)
            assertNotEquals(current.version, definition.version)
            val planned = checkNotNull(ok(planner.prepare(identity, content.catalog, definition, cursor, clock, Draws(size - 1L)) {
                RequestId("new-$level")
            }))
            assertEquals(content.catalog, planned.visit.catalog)
            assertEquals(current.rates, planned.visit.hourlyRates)
            assertTrue(planned.visit.request.desired in unlocked)
            val interval = listOf(30, 25, 20, 15)[index] * 60_000L
            assertEquals(clock.epoch.value + interval, planned.next.nextAt.epoch.value)
            assertEquals(clock.elapsedMs + interval, planned.next.nextAt.elapsedMs)
        }
        assertEquals(ContentVersion("trial-demo-2026-11-10-v5"), trial.version)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), trial.rentalVisitDefinition(content, 5))
    }

    @Test fun `고정 난수에서 동일 차종의 용도는 유지되고 도시만 해당 유형의 가중치로 바뀐다`() {
        val definition = ok(trial.rentalVisitDefinition(content, 1))
        val expected = listOf(
            Triple(0L, "hyundai_casper", RentalCategory.SMALL),
            Triple(3L, "hyundai_avante", RentalCategory.SEDAN),
            Triple(5L, "hyundai_tucson", RentalCategory.SUV),
            Triple(6L, "hyundai_staria_tourer", RentalCategory.VAN),
            Triple(7L, "hyundai_casper_electric", RentalCategory.ECO),
        )
        for ((draw, id, type) in expected) {
            val first = selectDemand(definition, draw, 0)
            val urbanWeight = listOf(6L, 4L, 1L, 1L, 4L)[type.ordinal]
            val suburbanWeight = listOf(2L, 2L, 6L, 4L, 2L)[type.ordinal]
            assertEquals(RentalDemandSelection(VehicleId(id), type, RentalCity.URBAN), first)
            assertEquals(first.copy(city = RentalCity.SUBURBAN), selectDemand(definition, draw, urbanWeight))
            assertEquals(first.copy(city = RentalCity.TRANSPORT_HUB), selectDemand(definition, draw, urbanWeight + suburbanWeight))
        }
    }

    @Test fun `직접 준비한 방문에도 같은 도시가 저장 입력으로 남는다`() {
        val at = RentalClock("fixture", EpochMillis(10_000), 10_000)
        val cursor = RentalVisitCursor(Revision(0), 0, at)
        val identity = GameIdentity(ProfileId("trial"), InputSource.SIMULATION)
        val definition = ok(trial.rentalVisitDefinition(content, 1))
        val request = { RequestId("direct-city-visit") }
        val direct = checkNotNull(ok(planner.prepare(identity, content.catalog, definition, cursor, at,
            Draws(0, 0, 0, 6), request)))
        assertEquals(RentalCity.SUBURBAN, direct.city)
        val detailed = checkNotNull(ok(planner.prepareWithDemand(identity, content.catalog, definition, cursor, at,
            Draws(0, 0, 0, 6), request)))
        assertEquals(detailed.demand.city, direct.city)
        assertEquals(detailed.prepared, direct)

        val legacy = checkNotNull(ok(planner.prepare(identity, content.catalog, ok(trial.rentalVisitDefinition()),
            cursor, at, Draws(0), request)))
        assertNull(legacy.city)
        assertEquals(legacy.visit.request, direct.visit.request)
        assertEquals(legacy.visit.terms, direct.visit.terms)
    }

    @Test fun `도시 가중치는 Lv4의 차종별 추첨 비중과 시간당 요율을 바꾸지 않는다`() {
        val current = ok(trial.rentalVisitDefinition())
        val definition = ok(trial.rentalVisitDefinition(content, 4))
        assertEquals(current.demand, definition.demand)
        assertEquals(current.rates, definition.rates)
        val selected = (0L until 20L).map { draw -> selectDemand(definition, draw, 0).desired }
        assertEquals(ids, selected.toSet())
        assertEquals(ids.size, selected.size)
    }

    @Test fun `가상 도시 추첨은 기존 희망 차종 기간 고객 사고 난수 뒤에 실행한다`() {
        val current = ok(trial.rentalVisitDefinition())
        val definition = ok(trial.rentalVisitDefinition(content, 4))
        val clock = RentalClock("fixture", EpochMillis(10_000), 10_000)
        val cursor = RentalVisitCursor(Revision(0), 0, clock)
        val identity = GameIdentity(ProfileId("trial"), InputSource.SIMULATION)
        fun prepare(input: RentalVisitDefinition, seed: Int) =
            checkNotNull(ok(planner.prepare(identity, content.catalog, input, cursor, clock, Random(seed)) {
                RequestId("seed-$seed")
            })).visit

        for (seed in 0..31) {
            val before = prepare(current, seed)
            val after = prepare(definition, seed)
            assertEquals("seed $seed", before.request, after.request)
            assertEquals("seed $seed", before.terms, after.terms)
        }
    }

    @Test fun `구형 중앙 정의는 기존 소비를 유지하고 레벨 정책은 미제공을 반환한다`() {
        val original = asset("trial-definitions.csv")
        val old = original.substringBefore("demand_definition_version,") + "\n[rental_categories]" +
            original.substringAfter("[rental_categories]")
        val legacy = ok(parseTrialDefinition(old, ids))
        assertEquals(trial.version, legacy.version)
        assertEquals(1_800_000L, ok(legacy.rentalVisitDefinition()).intervalMillis)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), legacy.rentalVisitDefinition(content, 1))
    }

    @Test fun `중앙 대여 정책의 누락 중복 잘못된 간격이나 도시 가중치는 거절한다`() {
        val original = asset("trial-definitions.csv")
        for ((old, bad) in listOf(
            "4,900000" to "",
            "2,1500000" to "2,1800000",
            "3,1200000" to "2,1200000",
            "URBAN,6,4,1,1,4" to "URBAN,0,0,0,0,0",
            "SUBURBAN,2,2,6,4,2" to "OTHER,2,2,6,4,2",
        )) {
            assertTrue(old, old in original)
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), parseTrialDefinition(original.replace(old, bad), ids))
        }
    }
}
