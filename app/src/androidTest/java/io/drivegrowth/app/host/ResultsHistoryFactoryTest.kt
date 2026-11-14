package io.drivegrowth.app.host

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.SettlementHistoryCause
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.GachaPull
import java.util.UUID
import java.util.concurrent.Executor

@Suppress("DEPRECATION")
class ResultsHistoryFactoryTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var games:GameSessionFactory
    private lateinit var names:Map<InputSource,String>
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="history-factory-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val identity=ok(onboarding.read()).identity;ok(onboarding.choose(identity,FirstGrant.CANDIDATES.first()))
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun viewedEvents()=ok(games.open()).use{g->ok(g.store.read{db->db.rawQuery("SELECT count(*) FROM quest_events WHERE action='RESULT_VIEWED'",null).use{it.moveToFirst();it.getInt(0)}})}
    fun testActualFactoryHistoryAndViewedReopenKeepSettlementAndQuestEventOnce() {
        val before=ok(SettlementHistoryReader.read(games));val row=before.entries.single();var writes=0
        val host=ResultsHostController(Executor{it.run()},Executor{it.run()},{id->writes++;ok(games.open()).use{it.store.markViewed(id)}},{},readHistory={SettlementHistoryReader.read(games)})
        host.setControlsEnabled(true);host.remember(ResultsUiState(selectedId=row.settlement.settlementId,scope=ResultsScope.ALL));assertEquals(0,viewedEvents())
        assertTrue(host.markViewed(row.settlement.settlementId,null,true));assertEquals(1,viewedEvents());assertFalse(host.markViewed(row.settlement.settlementId,null,true));assertEquals(1,writes)
        ok(games.open()).use{game->repeat(2){ok(game.store.markViewed(row.settlement.settlementId))}}
        assertEquals(1,viewedEvents());val after=ok(SettlementHistoryReader.read(games));assertEquals(row.settlement,after.entries.single().settlement);assertTrue(after.entries.single().viewed)
        assertEquals(ResultsScope.ALL,host.uiState.scope);assertEquals(row.settlement.settlementId,host.uiState.selectedId)
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testHistoryOnlyClassifiesVerifiedTypedInitializationAndGachaCommands() {
        val initial = ok(SettlementHistoryReader.read(games)).entries.single().settlement
        val paid = ok(games.open()).use { game ->
            val pull = GachaPull(ok(game.content))
            ok(game.store.commitGacha(ok(pull.prepare(game.store, RequestId("paid"), GachaTicketUse.PURCHASED)))).settlement
        }
        val generic = ok(games.open()).use { game ->
            val copy = checkNotNull(paid.changedVehicle).copy(ownedVehicleId = OwnedVehicleId("generic-add"))
            ok(game.store.commitGameChange(GameChange(RequestId("generic-add"), TransactionKind.GARAGE,
                game.identity.source, paid.transaction.ruleVersion, paid.transaction.money, VehicleWrite.Add(copy))))
        }
        val (growth, disposal) = ok(games.open()).use { game ->
            val car = checkNotNull(paid.changedVehicle)
            val grown = ok(game.store.commitGameChange(GameChange(RequestId("grow"), TransactionKind.GARAGE,
                game.identity.source, paid.transaction.ruleVersion,
                vehicle = VehicleWrite.Update(car.ownedVehicleId, car.revision, 2, VehicleLifecycle.OWNED, 0))))
            val after = checkNotNull(grown.changedVehicle)
            val sold = ok(game.store.commitGameChange(GameChange(RequestId("sell"), TransactionKind.GARAGE,
                game.identity.source, paid.transaction.ruleVersion,
                vehicle = VehicleWrite.Update(after.ownedVehicleId, after.revision, 2, VehicleLifecycle.SOLD, 0))))
            grown to sold
        }
        // 시험용으로 기존 첫 뽑기권 한 장만 공급한다. 가챠 명령·결과는 실제 writer가 기록한다.
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path,
            null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE gacha_progress SET tickets=1")
        }
        val free = ok(games.open()).use { game ->
            val pull = GachaPull(ok(game.content))
            ok(game.store.commitGacha(ok(pull.prepare(game.store, RequestId("free"), GachaTicketUse.FREE)))).settlement
        }
        val snapshot = ok(SettlementHistoryReader.read(games))
        val expected = mapOf(
            initial.settlementId to SettlementHistoryCause.INITIAL_GRANT,
            paid.settlementId to SettlementHistoryCause.GACHA,
            free.settlementId to SettlementHistoryCause.GACHA,
            generic.settlementId to null,
            growth.settlementId to null,
            disposal.settlementId to null,
        )
        assertEquals(expected, snapshot.entries.associate { it.settlement.settlementId to it.cause })
        val mapped = ResultsHostMapper().history(ContractResult.Success(snapshot)) as ResultsScreenState.Content
        assertEquals(expected, mapped.results.associate { it.settlement.settlementId to it.cause })
        assertEquals(snapshot, ok(SettlementHistoryReader.read(games)))

        // typed gacha 원명령에 대응하는 원래 결과 행을 잃으면 분류하는 대신 기존 오류를 반환한다.
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path,
            null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM gacha_requests WHERE request_id=?", arrayOf(paid.transaction.requestId.value))
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), SettlementHistoryReader.read(games))
    }

    fun testGenericFirstGrantShapeIsNotTypedInitialization() {
        val name = "history-generic-first-${UUID.randomUUID()}.db"
        try {
            SqliteGameChangeStore(context, InputSource.SIMULATION, name).use { store ->
                val initial = ok(SettlementHistoryReader.read(games)).entries.single().settlement
                val car = checkNotNull(initial.changedVehicle)
                val generic = ok(store.commitGameChange(GameChange(FirstGrant.REQUEST_ID, TransactionKind.GARAGE,
                    InputSource.SIMULATION, initial.transaction.ruleVersion, initial.transaction.money, VehicleWrite.Add(car))))
                val history = ok(SettlementHistoryReader(store).read())
                assertEquals(generic, history.entries.single().settlement)
                assertNull(history.entries.single().cause)
            }
        } finally { context.deleteDatabase(name) }
    }

    fun testAndroidBundleRestoresHistoryScopeAndSelectionWithoutAutomaticWrite() {
        val id=ok(SettlementHistoryReader.read(games)).entries.single().settlement.settlementId
        val bundle=ResultsStateBundle.encode(ResultsUiState(id,mapOf(id to ResultsReadState.Pending),true,ResultsScope.ALL))
        var writes=0;val host=ResultsHostController(Executor{it.run()},Executor{it.run()},{writes++;ContractResult.Success(Unit)},{},ResultsStateBundle.decode(bundle))
        assertEquals(id,host.uiState.selectedId);assertEquals(ResultsScope.ALL,host.uiState.scope);assertEquals(ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE),host.uiState.readStates[id]);assertEquals(0,writes)
    }
}
