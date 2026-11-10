package io.drivegrowth.app.host.shop

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.util.UUID

/** 격리 명시 SIMULATION DB의 factory 연결 시험. 제품 실행/SDK 입력 시험이 아니다. */
@Suppress("DEPRECATION")
class SessionShopExpansionSourceTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private fun factory()=GameSessionFactory(context,names,InputSource.SIMULATION)
    private fun <T> ok(result:ContractResult<T>):T {assertTrue(result.toString(),result is ContractResult.Success);return(result as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="shop-session-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"}}
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun initialize(games:GameSessionFactory){
        val onboarding=StoredOnboardingHostSource(games);val state=ok(onboarding.read());ok(onboarding.choose(state.identity,FirstGrant.CANDIDATES.first()))
    }
    fun testRepeatedReadUsesSameIdentityAndNeverCreatesAnotherModeOrCharges(){
        val games=factory();initialize(games);val source=SessionShopExpansionSource(context,games)
        val first=ok(source.read());val second=ok(source.read())
        assertEquals(first,second);assertEquals(InputSource.SIMULATION,first.source);assertEquals(first.profile,source.identity!!.profileId)
        assertEquals(GameMoney(1000),first.screen.balance);assertEquals(3,first.screen.capacity)
        assertEquals(0,first.completedRentals);assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
        ok(games.open()).use{assertEquals(first.profile,it.identity.profileId);assertEquals(GameMoney(1000),ok(it.store.balance()));assertNull(ok(it.store.hostCommands.load(it.identity.profileId)))}
    }
    fun testOtherFeatureCheckpointIsPreservedAndNextFactoryOperationStillWorks(){
        val games=factory();initialize(games)
        val command=ok(games.open()).use{game->
            val car=ok(game.store.listOwnedVehicles()).single()
            val value=HostCheckpointCommand.Garage((game.content as ContractResult.Success).value.catalog.contentVersion,
                GameChange(RequestId("other-feature"),TransactionKind.GARAGE,game.identity.source,
                    (game.content as ContractResult.Success).value.ruleVersion,
                    vehicle=VehicleWrite.Update(car.ownedVehicleId,car.revision,2,VehicleLifecycle.OWNED,0)))
            ok(game.store.hostCommands.save(game.identity.profileId,value));value
        }
        val source=SessionShopExpansionSource(context,games)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.pending())
        ok(games.open()).use{assertEquals(command,ok(it.store.hostCommands.load(it.identity.profileId))!!.command);assertEquals(GameMoney(1000),ok(it.store.balance()))}
    }
}
