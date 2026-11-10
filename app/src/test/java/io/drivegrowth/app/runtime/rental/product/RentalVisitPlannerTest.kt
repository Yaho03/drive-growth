package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.math.RoundingMode
import kotlin.random.Random

class RentalVisitPlannerTest {
    private val a = VehicleId("a")
    private val b = VehicleId("b")
    private val identity = GameIdentity(ProfileId("profile"), InputSource.SIMULATION)
    private val catalog = VehicleCatalogSnapshot(ContentVersion("cars"), listOf(VehicleDefinition(a,"A"), VehicleDefinition(b,"B")))
    private val clock = RentalClock("actual-fixture-boot", EpochMillis(1000000), 1000)
    private val cursor = RentalVisitCursor(Revision(2), 7, clock)
    private class Draw : Random() { var count = 0; override fun nextBits(bitCount: Int): Int { count++; return 0 } }
    private fun definition(categories:Map<VehicleId,RentalCategory> = mapOf(a to RentalCategory.SMALL,b to RentalCategory.SEDAN),
        demand:Map<VehicleId,Int> = mapOf(a to 0,b to 5), rates:Map<VehicleId,GameMoney> = mapOf(a to GameMoney(100),b to GameMoney(150))) =
        RentalVisitDefinition(ContentVersion("trial-only"),RuleVersion("rent-rules"),true,1800000,categories,rates,demand,
            mapOf(1 to 0,3 to 7,6 to 0),mapOf(1 to 15,3 to 30,6 to 60),
            listOf(RentalCustomerWeight(SimulatedCustomer(24,6),true,1)),GameMoney(20),RoundingMode.DOWN)
    private fun <T> ok(r:ContractResult<T>) = (r as ContractResult.Success).value
    @Test fun originalWholeVisitAndFollowingScheduleArePreparedWithoutSavingOrAccounting() {
        var ids=0;val draw=Draw()
        val planned=ok(RentalVisitPlanner().prepare(identity,catalog,definition(),cursor,clock,draw){ids++;RequestId("original")})!!
        assertEquals(1,ids);assertEquals(identity,planned.identity);assertEquals(ContentVersion("trial-only"),planned.definitionVersion)
        assertEquals(cursor,planned.expected);assertEquals(8,planned.next.sequence);assertEquals(Revision(3),planned.next.revision)
        assertEquals(RentalClock(clock.bootId,EpochMillis(2800000),1801000),planned.next.nextAt)
        val visit=planned.visit
        assertEquals(RentalRequest(RequestId("original"),b,3),visit.request);assertEquals(identity.source,visit.source)
        assertEquals(SimulatedCustomer(24,6),visit.terms.customer);assertEquals(true,visit.terms.regularCustomer)
        assertEquals(0,visit.terms.roll);assertEquals(30,visit.terms.returnMinutes);assertEquals(clock.epoch,visit.terms.start)
        assertEquals(GameMoney(20),visit.terms.fuelBonus);assertEquals(RoundingMode.DOWN,visit.terms.rounding)
        assertEquals(GameMoney(150),visit.hourlyRates.getValue(b));assertEquals(catalog,visit.catalog)
    }
    @Test fun beforeDueTimeNeverDrawsOrRequestsAnId() {
        val draw=Draw();var ids=0
        val before=clock.copy(epoch=EpochMillis(clock.epoch.value-1),elapsedMs=clock.elapsedMs-1)
        assertNull(ok(RentalVisitPlanner().prepare(identity,catalog,definition(),cursor,before,draw){ids++;RequestId("no")}))
        assertEquals(0,ids);assertEquals(0,draw.count)
    }
    @Test fun missingDefinitionValuesAreRejectedBeforeAnyRandomDraw() {
        val invalid=listOf(definition(categories=mapOf(a to RentalCategory.SMALL)),
            definition(demand=mapOf(a to 0,b to 0)),definition(rates=mapOf(a to GameMoney(0),b to GameMoney(150))))
        for(def in invalid) {
            val draw=Draw()
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),RentalVisitPlanner().prepare(identity,catalog,def,cursor,clock,draw){error("no id")})
            assertEquals(0,draw.count)
        }
    }
    @Test fun rebootOrInconsistentClockMappingDoesNotFabricateEligibleTime() {
        for(now in listOf(clock.copy(bootId="new-boot"),clock.copy(epoch=EpochMillis(clock.epoch.value+1)))) {
            val draw=Draw()
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),RentalVisitPlanner().prepare(identity,catalog,definition(),cursor,now,draw){error("no id")})
            assertEquals(0,draw.count)
        }
    }
    @Test fun cursorAndTimeOverflowAreRejectedBeforeDrawing() {
        val last=RentalClock("boot",EpochMillis(Long.MAX_VALUE-1),Long.MAX_VALUE-1)
        for(c in listOf(cursor.copy(revision=Revision(Int.MAX_VALUE)),cursor.copy(sequence=Long.MAX_VALUE),cursor.copy(nextAt=last))) {
            val draw=Draw()
            assertTrue(RentalVisitPlanner().prepare(identity,catalog,definition(),c,c.nextAt,draw){error("no id")} is ContractResult.Failure)
            assertEquals(0,draw.count)
        }
    }
    @Test fun externalDefinitionAndCatalogChangesCannotRewritePreparedWholeInput() {
        val categories=mutableMapOf(a to RentalCategory.SMALL,b to RentalCategory.SEDAN)
        val demand=mutableMapOf(a to 0,b to 5);val def=definition(categories,demand)
        categories.clear();demand.clear()
        val cars=catalog.vehicles.toMutableList()
        val planned=ok(RentalVisitPlanner().prepare(identity,catalog.copy(vehicles=cars),def,cursor,clock,Draw()){RequestId("id")})!!
        cars.clear();assertEquals(2,planned.visit.catalog.vehicles.size);assertEquals(2,planned.visit.categories.size)
        assertThrows(UnsupportedOperationException::class.java){(planned.visit.categories as MutableMap).clear()}
        assertThrows(UnsupportedOperationException::class.java){(def.demand as MutableMap).clear()}
    }
    @Test fun recreatedWholeVisitUsesStructuralEqualityAndOneChangedInputIsDifferent() {
        val planned=ok(RentalVisitPlanner().prepare(identity,catalog,definition(),cursor,clock,Draw()){RequestId("original")})!!
        val v=planned.visit
        val recreated=planned.copy(visit=RentalVisit(v.source,v.request,v.catalog,v.categories,v.hourlyRates,v.terms))
        assertEquals(planned,recreated);assertEquals(planned.hashCode(),recreated.hashCode())
        val changed=planned.copy(visit=RentalVisit(v.source,v.request,v.catalog,v.categories,v.hourlyRates,v.terms.copy(roll=1)))
        assertNotEquals(planned,changed)
    }
}
