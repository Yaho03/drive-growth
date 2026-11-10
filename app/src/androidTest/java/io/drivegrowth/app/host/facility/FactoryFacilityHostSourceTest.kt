package io.drivegrowth.app.host.facility

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.rental.Facility
import java.util.UUID

/** 같은 factory의 조회/미지원 경계만 검사한다. 실제 typed 금융/설정 저장은 E 공급 뒤 별도 검증한다. */
@Suppress("DEPRECATION")
class FactoryFacilityHostSourceTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private lateinit var source:FactoryFacilityHostSource
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="facility-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val identity=ok(onboarding.read()).identity;ok(onboarding.choose(identity,FirstGrant.CANDIDATES.first()));source=FactoryFacilityHostSource(context,games)
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    fun testActualFactoryReadsStoredFacilitiesAndCentralOffersWithoutAnotherMode() {
        val facts=ok(source.read());assertEquals(InputSource.SIMULATION,facts.identity.source);assertEquals(GameMoney(1000),facts.balance)
        assertEquals(1,facts.vehicles.size);assertEquals(Facility.entries.toSet(),facts.offers.keys);assertTrue(facts.shop.facilities.isEmpty());assertTrue(facts.resting.isEmpty())
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testMissingTypedJournalNeverCallsDirectPurchaseOrRestingWrite() {
        val before=ok(source.read());assertEquals(FacilityPending.Unsupported,ok(source.pending()))
        val buy=FacilityCommand.Buy(before.identity,RequestId("facility-buy"),before.definitionVersion,before.offers.getValue(Facility.FUEL_STATION))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.save(buy));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.execute(buy))
        val car=before.vehicles.single();val rest=FacilityCommand.Rest(before.identity,RequestId("rest"),VehicleRef(car.ownedVehicleId,car.revision),false,true)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.save(rest));assertEquals(before,ok(source.read()))
    }
}
