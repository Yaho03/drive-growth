package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.ranking.*
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.time.*

/** 원본13개를 변경하지 않는 추가 실제 SQLite 회귀. 격리 SIMULATION DB만 사용한다. */
@Suppress("DEPRECATION")
class RankingMetricIsolationTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var content: GameContentSnapshot
    private val at = EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
    private fun open() = SqliteGameChangeStore(context, source, name)
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        name = "ranking-isolation-${System.nanoTime()}.db"
        content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        store = open()
        val car = OwnedVehicle(OwnedVehicleId("initial"), VehicleId("hyundai_casper"), 1, Revision(0),
            VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        ok(store.commitGameChange(GameChange(RequestId("initial"), TransactionKind.GARAGE, source,
            content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)), VehicleWrite.Add(car))))
        ok(store.driving.settle(DrivingIncomeRules(content.ruleVersion, 500000, GameMoney(1)),
            ConfirmedDrive("trip", LocalDate.parse("2026-11-09"), ObservedDistance(1000, source))))
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun read(definitions: RankingDefinitions = RankingDefinitions.acceptedLocal()) = ok(
        RankingReadSource(store, source, content, definitions,
            departureZone = { Observation.Available(ZoneId.of("Asia/Seoul")) }).read(at))
    private fun corrupt(sql: String, arguments: Array<Any> = emptyArray()): ByteArray {
        store.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql, arguments) }
        val bytes = file.readBytes(); store = open(); return bytes
    }
    private fun assertUnchanged(bytes: ByteArray) {
        store.close(); assertTrue(bytes.contentEquals(context.getDatabasePath(name).readBytes())); store = open()
    }
    fun testFractionalDayFailurePreservesFiveMetricsWhenIncomeDoesNotDependOnDriving() {
        // 사용자 기본 수익 정책을 바꾸지 않는다. 지표 의존성 분리만 검사하는 명시 정의다.
        val accepted = RankingDefinitions.acceptedLocal()
        val definitions = accepted.copy(income = accepted.income!!.copy(included = setOf(RankingIncomeOrigin.RENTAL)))
        val before = read(definitions)
        val bytes = corrupt("UPDATE driving_days SET rewarded=1.5")
        val after = read(definitions)
        assertEquals(RankingValue.Failure(ContractError.STORAGE_FAILURE), after.metrics[RankingMetric.WEEKLY_DISTANCE])
        for (metric in RankingMetric.entries.filter { it != RankingMetric.WEEKLY_DISTANCE })
            assertEquals(metric.name, before.metrics[metric], after.metrics[metric])
        assertUnchanged(bytes)
    }
    fun testMalformedDriveJsonFailsDependentMetricsAndPreservesOtherFourWithoutWrites() {
        val before = read()
        val bytes = corrupt("UPDATE driving_drives SET command=?", arrayOf("{"))
        val after = read()
        val dependent = setOf(RankingMetric.EARNED, RankingMetric.WEEKLY_DISTANCE)
        for (metric in dependent) assertEquals(metric.name, RankingValue.Failure(ContractError.STORAGE_FAILURE), after.metrics[metric])
        for (metric in RankingMetric.entries.filter { it !in dependent })
            assertEquals(metric.name, before.metrics[metric], after.metrics[metric])
        assertUnchanged(bytes)
    }
}
