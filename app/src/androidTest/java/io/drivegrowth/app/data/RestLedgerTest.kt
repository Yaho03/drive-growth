package io.drivegrowth.app.data

import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class RestLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val minute = 60_000L
    /** PRD 시험값: 직전 1시간 운전, 5분 이상 주차, 버프 20분, 1.5배. 확정값이 아니다. */
    private val rules = RestRules(RuleVersion("rest-trial-v0"), 60 * minute, 5 * minute, 20 * minute, 150)
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var ledger: RestLedger

    private fun open() = SqliteGameChangeStore(context, source, name)

    override fun setUp() {
        super.setUp()
        name = "rest-test-" + System.nanoTime() + ".db"
        store = open()
        ok(store.atomic { RestLedger.createTables(it); ContractResult.Success(Unit) })
        ledger = RestLedger(store)
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun reopen() { store.close(); store = open(); ledger = RestLedger(store) }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun stop(
        number: Long,
        place: RestPlace = RestPlace.SERVICE_AREA,
        drivenMin: Long = 60,
        parkedMin: Long = 5,
        s: InputSource = source,
    ) = RestStop(number, s, Observation.Available(place), Observation.Available(drivenMin * minute), Observation.Available(parkedMin * minute))

    private fun state() = ok(ledger.state())

    private fun sql(vararg statements: String) = ok(store.atomic { db -> statements.forEach(db::execSQL); ContractResult.Success(Unit) })

    /** 기록·머리 정보·번호 발급 상태를 형식까지 포함해 읽는다. 실패한 호출이 원본을 바꾸지 않았는지 본다. */
    private fun dump(): String = ok(store.atomic { db ->
        fun rows(sql: String) = db.rawQuery(sql, null).use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        ContractResult.Success((
            rows("SELECT quote(seq) || '|' || quote(prev) || '|' || quote(kind) || '|' || quote(key) || '|' || quote(command) || '|' || quote(result) FROM rest_log ORDER BY seq") +
                rows("SELECT 'head|' || quote(id) || '|' || quote(last) || '|' || quote(count) FROM rest_head") +
                rows("SELECT 'sequence|' || quote(seq) FROM sqlite_sequence WHERE name = 'rest_log'")
            ).joinToString("\n"))
    })

    private fun logCount(): Int = ok(store.atomic { db ->
        ContractResult.Success(db.rawQuery("SELECT count(*) FROM rest_log", null).use { it.moveToFirst(); it.getInt(0) })
    })

    private fun assertStorageFailureKeepsRows(vararg calls: () -> ContractResult<*>, label: String = "") {
        val before = dump()
        calls.forEach { assertEquals(label, ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        reopen()
        calls.forEach { assertEquals(label, ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        assertEquals(label, before, dump())
    }

    fun testAcceptedRestStartsBonusThatSurvivesReopenAndIsNotIssuedTwice() {
        assertEquals(RestBonusState.NONE, state())
        val first = ok(ledger.depart(rules, stop(1)))
        assertEquals(RestOutcome.ACCEPTED, first.outcome)
        assertEquals(RestBonusStarted(1, source, rules.ruleVersion, 20 * minute, 150, refreshed = false), first.started)
        assertEquals(RestBonusState(20 * minute, 1), state())
        reopen()
        // 앱을 다시 켜고 같은 출발을 다시 보내도 버프를 새로 발급하지 않고 당시 결과를 돌려준다.
        assertEquals(first, ok(ledger.depart(rules, stop(1))))
        assertEquals(RestBonusState(20 * minute, 1), state())
        assertEquals(1, logCount())
    }

    fun testSameStopWithAnotherInputOrRuleConflicts() {
        ok(ledger.depart(rules, stop(1)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.depart(rules, stop(1, parkedMin = 6)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            ledger.depart(rules.copy(ruleVersion = RuleVersion("rest-trial-v1")), stop(1)))
        assertEquals(RestBonusState(20 * minute, 1), state())
    }

    fun testParkingAndReopenDoNotConsumeOnlyDrivingDoes() {
        ok(ledger.depart(rules, stop(1)))
        reopen(); reopen()
        assertEquals(20 * minute, state().remainingMs)
        assertEquals(RestBonusState(15 * minute, 1), ok(ledger.consume(rules, "drive-a", 1, 5 * minute)))
        reopen()
        assertEquals(RestBonusState(15 * minute, 1), state())
        assertEquals(RestBonusState(0, 1), ok(ledger.consume(rules, "drive-b", 1, 30 * minute)))
        assertFalse(state().isActive)
    }

    fun testSameConsumeIsAppliedOnceAndAnotherInputConflicts() {
        ok(ledger.depart(rules, stop(1)))
        val once = ok(ledger.consume(rules, "drive-a", 1, 5 * minute))
        reopen()
        assertEquals(once, ok(ledger.consume(rules, "drive-a", 1, 5 * minute)))
        assertEquals(15 * minute, state().remainingMs)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.consume(rules, "drive-a", 1, 6 * minute))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.consume(rules, "drive-c", 1, -1))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.consume(rules, " ", 1, 1))
        assertEquals(15 * minute, state().remainingMs)
    }

    fun testDrivingFromBeforeTheNextDepartureCannotEatTheNewBonus() {
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-a", 1, 10 * minute))
        val refreshed = ok(ledger.depart(rules, stop(2)))
        assertTrue(requireNotNull(refreshed.started).refreshed)
        assertEquals(RestBonusState(20 * minute, 2), refreshed.state)
        // 정차 1 뒤에 운전한 구간이 정차 2 출발 처리 뒤에 늦게 도착한 경우.
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.consume(rules, "drive-late", 1, 10 * minute))
        assertEquals(20 * minute, state().remainingMs)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.consume(rules, "drive-none", null, 1))
    }

    fun testNotAcceptedStopKeepsRemainingAndOlderStopIsAlreadyHandled() {
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-a", 1, 5 * minute))
        for ((n, s, outcome) in listOf(
            Triple(2L, stop(2, place = RestPlace.OTHER), RestOutcome.NOT_REST_PLACE),
            Triple(3L, stop(3, parkedMin = 4), RestOutcome.PARKING_TOO_SHORT),
            Triple(4L, stop(4, drivenMin = 59), RestOutcome.DRIVING_TOO_SHORT),
            Triple(5L, RestStop(5, source, Observation.Missing(MissingReason.NO_DATA), Observation.Available(60 * minute),
                Observation.Available(5 * minute)), RestOutcome.UNCONFIRMED),
        )) {
            val d = ok(ledger.depart(rules, s))
            assertEquals(outcome, d.outcome)
            assertNull(d.started)
            assertEquals(RestBonusState(15 * minute, n), state())
        }
        val before = dump()
        assertEquals(RestOutcome.ALREADY_HANDLED, ok(ledger.depart(rules, stop(0))).outcome)
        assertEquals(before, dump())
        assertEquals(RestBonusState(15 * minute, 5), state())
    }

    fun testSourceMismatchIsRejected() {
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.depart(rules, stop(1, s = InputSource.VEHICLE)))
        assertEquals(RestBonusState.NONE, state())
    }

    fun testFailureBeforeCommitKeepsState() {
        ok(ledger.depart(rules, stop(1)))
        store.beforeCommit = { throw SQLiteException("주입") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.consume(rules, "drive-a", 1, 5 * minute))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.depart(rules, stop(2)))
        store.beforeCommit = null
        reopen()
        assertEquals(RestBonusState(20 * minute, 1), state())
        assertEquals(15 * minute, ok(ledger.consume(rules, "drive-a", 1, 5 * minute)).remainingMs)
    }

    fun testStoredLogThatDoesNotMatchItsCommandOrChainIsStorageFailure() {
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-a", 1, 5 * minute))
        ok(ledger.depart(rules, stop(2, place = RestPlace.OTHER)))
        val current = { ledger.state() }
        val replayConsume = { ledger.consume(rules, "drive-a", 1, 5 * minute) }
        val replayFirst = { ledger.depart(rules, stop(1)) }
        sql("CREATE TABLE backup AS SELECT * FROM rest_log", "CREATE TABLE head_backup AS SELECT * FROM rest_head")
        for ((corruption, calls) in listOf(
            "UPDATE rest_log SET result = replace(result, '\"remaining\":900000', '\"remaining\":1200000') WHERE kind = 'CONSUME'" to listOf(current, replayConsume),
            "UPDATE rest_log SET result = replace(result, '\"remaining\":900000', '\"remaining\":900000.5') WHERE kind = 'CONSUME'" to listOf(current, replayConsume),
            "UPDATE rest_log SET result = replace(result, '\"remaining\":900000', '\"remaining\":\"900000\"') WHERE kind = 'CONSUME'" to listOf(current, replayConsume),
            "UPDATE rest_log SET result = replace(result, 'ACCEPTED', 'NOT_REST_PLACE') WHERE key = '1'" to listOf(replayFirst),
            "UPDATE rest_log SET command = replace(command, '\"value\":3600000', '\"value\":3540000') WHERE key = '1'" to listOf(replayFirst),
            "UPDATE rest_log SET command = replace(command, '\"percent\":150', '\"percent\":200') WHERE key = '1'" to listOf(replayFirst),
            "UPDATE rest_log SET key = '9' WHERE key = '1'" to listOf({ ledger.depart(rules, stop(9)) }),
            "UPDATE rest_log SET kind = 'CONSUME' WHERE key = '2'" to listOf(current),
            "DELETE FROM rest_log WHERE kind = 'CONSUME'" to listOf(current),
            "UPDATE rest_log SET result = 7 WHERE key = '2'" to listOf(current),
            "UPDATE rest_log SET command = X'7B7D' WHERE key = '2'" to listOf(current),
            // 소모 결과와 다음 출발의 직전·이후 상태를 맞춰 바꿔 버프를 늘린 손상. 처음부터 다시 계산해야 드러난다.
            "UPDATE rest_log SET result = replace(result, '\"remaining\":900000', '\"remaining\":1200000')" to listOf(current, replayConsume),
            "UPDATE rest_log SET result = replace(result, ',\"last\":null}', '}') WHERE key = '1'" to listOf(current, replayFirst),
        )) {
            val clean = dump()
            sql(corruption)
            assertTrue("손상이 들어가지 않음: $corruption", dump() != clean)
            assertStorageFailureKeepsRows(*calls.toTypedArray(), label = corruption)
            sql("DELETE FROM rest_log", "INSERT INTO rest_log SELECT * FROM backup", "DELETE FROM rest_head", "INSERT INTO rest_head SELECT * FROM head_backup")
        }
        assertEquals(RestBonusState(15 * minute, 2), state())
    }

    fun testDeletedLastConsumeIsStorageFailureNotARestoredBonus() {
        // 리뷰 재현 1: 20분 → 5분 소모(15분) 뒤 마지막 소모 기록만 지우면 20분으로 되살아나던 문제.
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-a", 1, 5 * minute))
        sql("DELETE FROM rest_log WHERE kind = 'CONSUME'")
        assertStorageFailureKeepsRows(
            { ledger.state() }, { ledger.consume(rules, "drive-a", 1, 5 * minute) }, { ledger.consume(rules, "drive-b", 1, minute) },
            { ledger.depart(rules, stop(2)) },
        )
    }

    fun testDeletedMiddleRecordIsStorageFailure() {
        // 리뷰 재현 2: 0분 소모를 가운데 두고 지우면 남은 기록끼리는 맞아 보이던 문제.
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-zero", 1, 0))
        ok(ledger.depart(rules, stop(2, place = RestPlace.OTHER)))
        sql("DELETE FROM rest_log WHERE kind = 'CONSUME'")
        assertStorageFailureKeepsRows({ ledger.state() }, { ledger.consume(rules, "drive-zero", 1, 0) }, { ledger.depart(rules, stop(3)) })
    }

    fun testDeletedFirstRecordOrBrokenHeadIsStorageFailure() {
        ok(ledger.depart(rules, stop(1)))
        ok(ledger.consume(rules, "drive-a", 1, 5 * minute))
        sql("CREATE TABLE backup AS SELECT * FROM rest_log", "CREATE TABLE head_backup AS SELECT * FROM rest_head")
        for (corruption in listOf(
            "DELETE FROM rest_log WHERE kind = 'DEPART'",
            "DELETE FROM rest_head",
            "DELETE FROM rest_log",
            "UPDATE rest_head SET last = last - 1",
            "UPDATE rest_head SET count = count + 1",
            "UPDATE rest_head SET last = 'x'",
            "UPDATE rest_log SET prev = 0",
        )) {
            val clean = dump()
            sql("PRAGMA ignore_check_constraints = ON", corruption, "PRAGMA ignore_check_constraints = OFF")
            assertTrue("손상이 들어가지 않음: $corruption", dump() != clean)
            // CHECK를 우회한 값은 다시 열 때 저장소 전체 검사에 걸리므로 같은 연결에서 이 원장의 대조를 확인한다.
            val before = dump()
            assertEquals(corruption, ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.state())
            assertEquals(corruption, ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.consume(rules, "drive-b", 1, minute))
            assertEquals(corruption, before, dump())
            sql("DELETE FROM rest_log", "INSERT INTO rest_log SELECT * FROM backup", "DELETE FROM rest_head", "INSERT INTO rest_head SELECT * FROM head_backup")
        }
        assertEquals(RestBonusState(15 * minute, 1), state())
    }

    fun testNumberGapsAreNormal() {
        // AUTOINCREMENT 번호는 비어도 정상이다. 연결 관계만 맞으면 손상이 아니다.
        ok(ledger.depart(rules, stop(1)))
        sql("UPDATE sqlite_sequence SET seq = seq + 5 WHERE name = 'rest_log'")
        assertEquals(RestBonusState(15 * minute, 1), ok(ledger.consume(rules, "drive-a", 1, 5 * minute)))
        reopen()
        assertEquals(RestBonusState(15 * minute, 1), state())
        assertEquals(RestBonusState(15 * minute, 1), ok(ledger.consume(rules, "drive-a", 1, 5 * minute)))
        assertEquals(listOf(1L, 7L), ok(store.atomic { db ->
            ContractResult.Success(db.rawQuery("SELECT seq FROM rest_log ORDER BY seq", null).use { buildList { while (it.moveToNext()) add(it.getLong(0)) } })
        }))
    }

    fun testTwoConnectionsDepartingTheSameStopIssueOneBonus() {
        val other = open()
        val otherLedger = RestLedger(other)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { gate.await(); ledger.depart(rules, stop(1)) })
            val b = pool.submit(Callable { gate.await(); otherLedger.depart(rules, stop(1)) })
            gate.countDown()
            assertEquals(ok(a.get(10, TimeUnit.SECONDS)), ok(b.get(10, TimeUnit.SECONDS)))
            reopen()
            assertEquals(1, logCount())
            assertEquals(RestBonusState(20 * minute, 1), state())
        } finally { pool.shutdownNow(); other.close() }
    }
}
