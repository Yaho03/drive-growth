package io.drivegrowth.app.host.ranking

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.progression.FactoryProgressionSource
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import io.drivegrowth.core.garage.FirstGrant
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 실제 공개 쓰기→제품 readRanking 연결. 관측·UTC Quest 정의는 명시 SIMULATION이며 SDK 입력이 아니다. */
@Suppress("DEPRECATION")
class RankingProvenanceBindingTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource, String>
    private lateinit var games: GameSessionFactory
    private val at = EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        val prefix = "ranking-provenance-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        games = GameSessionFactory(context, names, InputSource.SIMULATION) {
            when (val loaded = AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure -> loaded
                is ContractResult.Success -> loaded.value.questDefinitions(ZoneId.of("UTC"))
            }
        }
        val onboarding = StoredOnboardingHostSource(games)
        val actual = ok(onboarding.read())
        ok(onboarding.choose(actual.identity, FirstGrant.CANDIDATES.first()))
    }
    override fun tearDown() {
        names.values.forEach(context::deleteDatabase) // 이 시험이 만든 고유 DB만 제거한다.
        super.tearDown()
    }
    private fun raw(action: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path, null, SQLiteDatabase.OPEN_READWRITE).use(action)

    private fun rows(): List<String> = ok(games.open()).use { game -> ok(game.store.read { db ->
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use {
            while (it.moveToNext()) tables += it.getString(0)
        }
        buildList {
            for (table in tables) db.rawQuery("SELECT * FROM \"${table.replace("\"", "\"\"")}\" ORDER BY 1", null).use { row ->
                while (row.moveToNext()) add(table + ":" + (0 until row.columnCount).joinToString("|") {
                    row.getType(it).toString() + ":" + if (row.isNull(it)) "<null>" else row.getString(it)
                })
            }
        }
    }) }
    private fun fileHash() = MessageDigest.getInstance("SHA-256").digest(
        context.getDatabasePath(names.getValue(InputSource.SIMULATION)).readBytes()).toList()

    private fun unchangedRead(asOf: EpochMillis = at): RankingSnapshot {
        val original = rows()
        val bytes = fileHash()
        val source = StoredHostSource(context, sessions = games)
        val snapshot = ok(source.readRanking(asOf))
        assertEquals(snapshot, ok(source.readRanking(asOf)))
        assertEquals(original, rows())
        assertEquals(bytes, fileHash())
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
        return snapshot
    }
    private fun amount(snapshot: RankingSnapshot, metric: RankingMetric) =
        (snapshot.metrics.getValue(metric) as RankingValue.Available).amount

    private fun claimActualQuest() {
        ok(games.open()).use { game ->
            val grant = ok(game.store.listUnviewedSettlements()).single()
            ok(game.store.markViewed(grant.settlementId)) // 실제 초기 지급 정산의 공개 열람 hook.
        }
        val progression = FactoryProgressionSource(games) { Observation.Available(EpochMillis(System.currentTimeMillis())) }
        val ready = ok(progression.read(emptySet()))
        val command = ready.offers.values.single { it.definition.id == "daily-result-viewed" }
        assertEquals(GameMoney(100), command.definition.reward)
        ok(progression.save(ready.identity, command))
        val receipt = ok(progression.execute(ready.identity, command))
        assertEquals(GameMoney(1100), receipt.settlement.balance)
    }

    /** 같은 source/profile의 공개 recorder를 통과한다. 이 가상 관측을 실제 차/SDK로 표시하지 않는다. */
    private fun recordedTrip(zone: ZoneId): ObservedDrivingReceipt {
        ok(games.open()).use { ok(it.store.activateObservedDriving(it.identity.profileId)) }
        val rules = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).drivingIncomeRules())
        val recorder = SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(RecordingPolicy(2500, 2500, 1000, 3000, 0.1, 0.5)) { "ranking-recorded-trip" },
            { ContractResult.Success(rules) })
        var committed: ObservedDrivingReceipt? = null
        for (index in 0..5) {
            val parked = index >= 2
            val elapsed = 1000L + index * 1000
            val sample = VehicleObservation(InputSource.SIMULATION, elapsed, EpochMillis(at.value + elapsed),
                Observation.Available(if (parked) 0.0 else 10.0),
                Observation.Available(if (parked) Gear.PARK else Gear.DRIVE), Observation.Available(!parked))
            val result = ok(recorder.observe("ranking-sim-receiver", DrivingFrame(sample, Observation.Available(zone)),
                RecordingClock(elapsed, sample.receivedAt)))
            if (result.committed.isNotEmpty()) committed = result.committed.single()
        }
        assertNotNull("실제 recorder의 원정산이 있어야 한다", committed)
        val receipt = checkNotNull(committed)
        assertEquals(15L, receipt.commit.rewardedMeters)
        assertEquals(zone, (receipt.trip.zone as Observation.Available).value)
        return receipt
    }
    private fun legacyDrive(activateJournal: Boolean) {
        ok(games.open()).use { game ->
            if (activateJournal) ok(game.store.activateObservedDriving(game.identity.profileId))
            ok(game.store.driving.settle(DrivingIncomeRules(RuleVersion("legacy-sim"), 500000, GameMoney(10)),
                ConfirmedDrive("legacy-without-origin", LocalDate.of(2026, 11, 10), ObservedDistance(1000, game.identity.source))))
        }
    }

    fun testActualInitialGrantViewedAndQuestClaimEarnsOnlyOneHundred() {
        claimActualQuest()
        val snapshot = unchangedRead(EpochMillis(System.currentTimeMillis()))
        assertEquals(100.toBigDecimal(), amount(snapshot, RankingMetric.EARNED))
        assertEquals(1.toBigDecimal(), amount(snapshot, RankingMetric.OWNED))
    }
    fun testRecordedTripOriginalZoneFeedsWeeklyRewardedMeters() {
        val receipt = recordedTrip(RankingDefinitions.acceptedLocal(InputSource.SIMULATION).week!!.zone)
        val snapshot = unchangedRead()
        assertEquals(15.toBigDecimal(), amount(snapshot, RankingMetric.WEEKLY_DISTANCE))
        ok(games.open()).use { assertEquals(receipt, ok(it.store.observedDrivingReceipt(receipt.trip.id))) }
    }
    fun testOtherOriginalZoneStaysMissingInsteadOfBecomingRankingZone() {
        recordedTrip(ZoneId.of("Asia/Seoul"))
        assertTrue(unchangedRead().metrics.getValue(RankingMetric.WEEKLY_DISTANCE) is RankingValue.Missing)
    }
    fun testUnactivatedLegacyDepartureRemainsMissing() {
        legacyDrive(false)
        assertTrue(unchangedRead().metrics.getValue(RankingMetric.WEEKLY_DISTANCE) is RankingValue.Missing)
    }
    fun testActivatedJournalWithNoRecordedOriginRemainsMissing() {
        legacyDrive(true)
        assertTrue(unchangedRead().metrics.getValue(RankingMetric.WEEKLY_DISTANCE) is RankingValue.Missing)
    }
    fun testDamagedQuestCauseIsMetricFailureAndPreservesOriginalBytes() {
        claimActualQuest()
        raw { it.execSQL("UPDATE quest_claims SET source='VEHICLE'") }
        val snapshot = unchangedRead(EpochMillis(System.currentTimeMillis()))
        assertEquals(RankingValue.Failure(ContractError.STORAGE_FAILURE), snapshot.metrics.getValue(RankingMetric.EARNED))
        assertTrue(snapshot.metrics.getValue(RankingMetric.OWNED) is RankingValue.Available)
    }
    fun testDamagedObservedOriginIsFailureForMetricsThatUseIt() {
        recordedTrip(RankingDefinitions.acceptedLocal(InputSource.SIMULATION).week!!.zone)
        raw { it.execSQL("UPDATE observed_driving_receipts SET digest='damaged'") }
        val snapshot = unchangedRead()
        for (metric in listOf(RankingMetric.EARNED, RankingMetric.WEEKLY_DISTANCE))
            assertEquals(RankingValue.Failure(ContractError.STORAGE_FAILURE), snapshot.metrics.getValue(metric))
    }
    fun testForeignRecordedProfileIsFailureInsteadOfMissing() {
        recordedTrip(RankingDefinitions.acceptedLocal(InputSource.SIMULATION).week!!.zone)
        raw { it.execSQL("UPDATE observed_driving_receipts SET profile='foreign-profile'") }
        assertEquals(RankingValue.Failure(ContractError.STORAGE_FAILURE),
            unchangedRead().metrics.getValue(RankingMetric.WEEKLY_DISTANCE))
    }
    fun testUnsupportedSchemaFailsWithoutRepairingOrDeletingDatabase() {
        raw { it.version = 999 }
        val before = fileHash()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), StoredHostSource(context, sessions = games).readRanking(at))
        assertEquals(before, fileHash())
    }
}
