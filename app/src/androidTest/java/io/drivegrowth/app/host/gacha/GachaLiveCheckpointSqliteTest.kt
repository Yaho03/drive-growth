package io.drivegrowth.app.host.gacha

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.util.UUID

/** 격리 DB의 명시 SIMULATION 시험 입력이다. 제품 무료권·SDK 이벤트를 만들지 않는다. */
@Suppress("DEPRECATION")
class GachaLiveCheckpointSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource,String>
    private fun factory()=GameSessionFactory(context,names,InputSource.SIMULATION)
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return (value as ContractResult.Success).value}
    override fun setUp(){
        super.setUp();val prefix="gacha-live-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"}
        // 기존 helper의 새 DB 시험 인수다. 제품 경로에서 기존 DB 진행/무료권을 보정하지 않는다.
        SqliteGameChangeStore(context,InputSource.SIMULATION,names.getValue(InputSource.SIMULATION),
            initialGachaProgress=GachaProgress(Revision(0),0,1)).use{ok(it.localProfileId())}
        val source=StoredOnboardingHostSource(factory());val state=ok(source.read());ok(source.choose(state.identity,FirstGrant.CANDIDATES.first()))
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun source(games:GameSessionFactory)=CheckpointGachaSource(StoredGachaHostSource(context,games),SessionGachaJournal(games))
    private fun prepare(source:CheckpointGachaSource,kind:GachaOptionKind):GachaCommand {
        val offers=ok(source.read(RequestId(UUID.randomUUID().toString()),RequestId(UUID.randomUUID().toString())))
        return ok(source.prepare(GachaDrawInput(offers,offers.options.single{it.kind==kind})))
    }
    fun testPaidAndFreeUseSameDatabaseAndReplayOriginalVehicleAfterReopen(){
        val games=factory();val first=source(games);val command=prepare(first,GachaOptionKind.PAID)
        assertEquals(command,ok(first.pending()));val result=ok(first.commit(command));assertEquals(GameMoney(500),result.settlement.balance)
        assertEquals(command.award.vehicle,result.settlement.changedVehicle);assertEquals(GameMoney(500),command.award.vehicle.acquisitionCost)
        val reopened=source(factory());assertEquals(command,ok(reopened.pending()));assertEquals(result,ok(reopened.commit(command)))
        assertEquals(result,ok(reopened.finish(command)));assertNull(ok(reopened.pending()))
        val free=prepare(reopened,GachaOptionKind.FREE);val freeResult=ok(reopened.commit(free))
        assertEquals(GameMoney(0),free.award.vehicle.acquisitionCost);assertEquals(GameMoney(500),freeResult.settlement.balance)
        assertNull(freeResult.settlement.transaction.money);assertEquals(0L,freeResult.progress.freeTickets)
        ok(factory().open()).use{assertEquals(3,ok(it.store.listOwnedVehicles()).size);assertEquals(GameMoney(500),ok(it.store.balance()))}
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testSavedCanCancelButStartedKeepsOriginalResultAndRejectsAnotherCommand(){
        val source=source(factory());val saved=prepare(source,GachaOptionKind.PAID)
        ok(source.cancelUnstarted(saved));assertNull(ok(source.pending()))
        ok(factory().open()).use{assertEquals(GameMoney(1000),ok(it.store.balance()));assertEquals(1,ok(it.store.listOwnedVehicles()).size)}
        val started=prepare(source,GachaOptionKind.PAID);val first=ok(source.commit(started))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.cancelUnstarted(started))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.commit(started.copy(requestId=RequestId("different"))))
        assertEquals(started,ok(source.pending()));assertEquals(first,ok(source.commit(started)))
    }
    fun testFullGarageNeverPreparesOrChangesBalanceOrTickets(){
        val source=source(factory())
        repeat(2){val command=prepare(source,GachaOptionKind.PAID);ok(source.commit(command));ok(source.finish(command))}
        val offers=ok(source.read(RequestId("full-paid"),RequestId("full-free")))
        assertEquals(GachaAvailability.NO_SPACE,offers.availability)
        assertTrue(source.prepare(GachaDrawInput(offers,offers.options[1])) is ContractResult.Failure)
        assertNull(ok(source.pending()))
        ok(factory().open()).use{assertEquals(GameMoney(0),ok(it.store.balance()));assertEquals(3,ok(it.store.listOwnedVehicles()).size);assertEquals(1L,ok(it.store.readGachaState()).progress.freeTickets)}
    }
    fun testMissingProgressIsNotRepairedWithFakeTicketsOrAuthority(){
        // 별도 기존 프로필에 가챠 진행이 없으면 factory가 초기 진행을 보충하지 않는다.
        names.values.forEach(context::deleteDatabase)
        SqliteGameChangeStore(context,InputSource.SIMULATION,names.getValue(InputSource.SIMULATION)).use{ok(it.localProfileId())}
        val source=source(factory());assertTrue(source.read(RequestId("missing-paid"),RequestId("missing-free")) is ContractResult.Failure)
        assertNull(ok(source.pending()))
        ok(factory().open()).use{assertEquals(GameMoney(0),ok(it.store.balance()));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),it.store.readGachaState())}
    }
    fun testSpentFreeTicketIsRejectedWithoutPreparingAnotherCommand(){
        val source=source(factory());val free=prepare(source,GachaOptionKind.FREE);ok(source.commit(free));ok(source.finish(free))
        val offers=ok(source.read(RequestId("empty-paid"),RequestId("empty-free")))
        assertEquals(GachaAvailability.INSUFFICIENT_TICKETS,offers.options.single{it.kind==GachaOptionKind.FREE}.availability)
        assertTrue(source.prepare(GachaDrawInput(offers,offers.options[1])) is ContractResult.Failure);assertNull(ok(source.pending()))
        ok(factory().open()).use{assertEquals(GameMoney(1000),ok(it.store.balance()));assertEquals(2,ok(it.store.listOwnedVehicles()).size)}
    }
    fun testInsufficientMoneyDoesNotSaveACommandOrGrantVehicle(){
        ok(factory().open()).use{game->ok(game.store.commitGameChange(GameChange(RequestId("fixture-debit"),TransactionKind.GARAGE,
            game.identity.source,(game.content as ContractResult.Success).value.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(900)))))}
        val source=source(factory());val offers=ok(source.read(RequestId("poor-paid"),RequestId("poor-free")))
        assertEquals(GachaAvailability.INSUFFICIENT_MONEY,offers.options[0].availability)
        assertTrue(source.prepare(GachaDrawInput(offers,offers.options[0])) is ContractResult.Failure);assertNull(ok(source.pending()))
        ok(factory().open()).use{assertEquals(GameMoney(100),ok(it.store.balance()));assertEquals(1,ok(it.store.listOwnedVehicles()).size)}
    }

}
