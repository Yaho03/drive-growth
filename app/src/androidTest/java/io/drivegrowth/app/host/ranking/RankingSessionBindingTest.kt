package io.drivegrowth.app.host.ranking

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.StoredHostSource
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import io.drivegrowth.core.garage.FirstGrant
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 격리 SIMULATION의 실제 SQLite 조회. 주행/입금은 명시 fixture이고 실제 SDK·Quest 생산자가 아니다. */
@Suppress("DEPRECATION")
class RankingSessionBindingTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private val at=EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return(value as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="ranking-bind-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val source=StoredOnboardingHostSource(games);val state=ok(source.read());ok(source.choose(state.identity,FirstGrant.CANDIDATES.first()))}
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun rows():List<String> = ok(games.open()).use { game -> ok(game.store.read { db ->
        val tables=mutableListOf<String>();db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use{while(it.moveToNext())tables+=it.getString(0)}
        buildList{for(table in tables)db.rawQuery("SELECT * FROM \"${table.replace("\"","\"\"")}\" ORDER BY 1",null).use { row ->
            while(row.moveToNext())add(table+":"+(0 until row.columnCount).joinToString("|"){column->row.getType(column).toString()+":"+if(row.isNull(column))"<null>" else row.getString(column)})
        }}
    }) }
    fun testAllSixUseSameFactoryAndInitialGrantIsNotLifetimeIncome(){
        val before=rows();val source=StoredHostSource(context,sessions=games);val result=ok(source.readRanking(at))
        assertEquals(RankingMetric.entries.toSet(),result.metrics.keys);assertEquals(InputSource.SIMULATION,result.source)
        assertEquals(java.math.BigDecimal.ONE,(result.metrics.getValue(RankingMetric.OWNED) as RankingValue.Available).amount)
        assertTrue(result.metrics.getValue(RankingMetric.EARNED) is RankingValue.Empty)
        assertEquals(result,ok(source.readRanking(at)));assertEquals(before,rows())
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testMissingDepartureZoneAndUnknownQuestCreditRemainMissingWithoutWrites(){
        ok(games.open()).use{game->val content=(game.content as ContractResult.Success).value
            ok(game.store.driving.settle(DrivingIncomeRules(content.ruleVersion,500000,GameMoney(10)),ConfirmedDrive("unproven-zone",LocalDate.parse("2026-11-09"),ObservedDistance(1000,game.identity.source))))
            ok(game.store.commitGameChange(GameChange(RequestId("unclassified-credit"),TransactionKind.GARAGE,game.identity.source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(70)))))
        }
        val before=rows();val result=ok(StoredHostSource(context,sessions=games).readRanking(at))
        assertTrue(result.metrics.getValue(RankingMetric.WEEKLY_DISTANCE) is RankingValue.Missing)
        assertTrue(result.metrics.getValue(RankingMetric.EARNED) is RankingValue.Missing)
        assertTrue(result.metrics.getValue(RankingMetric.OWNED) is RankingValue.Available)
        assertEquals(before,rows())
    }
}
