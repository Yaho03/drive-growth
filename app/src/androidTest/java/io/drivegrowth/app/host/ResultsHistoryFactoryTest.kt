package io.drivegrowth.app.host

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
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
    fun testAndroidBundleRestoresHistoryScopeAndSelectionWithoutAutomaticWrite() {
        val id=ok(SettlementHistoryReader.read(games)).entries.single().settlement.settlementId
        val bundle=ResultsStateBundle.encode(ResultsUiState(id,mapOf(id to ResultsReadState.Pending),true,ResultsScope.ALL))
        var writes=0;val host=ResultsHostController(Executor{it.run()},Executor{it.run()},{writes++;ContractResult.Success(Unit)},{},ResultsStateBundle.decode(bundle))
        assertEquals(id,host.uiState.selectedId);assertEquals(ResultsScope.ALL,host.uiState.scope);assertEquals(ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE),host.uiState.readStates[id]);assertEquals(0,writes)
    }
}
