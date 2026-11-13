package io.drivegrowth.app.simulation

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.rental.SessionRentalProductSource
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** 격리 SQLite와 실제 HostSession의 terminal 소비·복원 경계. 모든 입력은 SIMULATION이다. */
@Suppress("DEPRECATION")
class SimulationTerminalOriginalsTest : InstrumentationTestCase() {
    private val main = Executor { Handler(Looper.getMainLooper()).post(it) }
    private val terminal = SimulationTimeline(3_605_000, 65)
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    fun testFailedRentalThenExactRetryRestoresWithOriginalDrivingReceipt() = terminalFixture(true) { context, name, _ ->
        assertRestore(context, name, expectReplay = false)
    }

    fun testMissingCursorVisitOriginalReplaysExactFrame() = terminalFixture(false) { context, name, oldHost ->
        assertRestore(context, name, expectReplay = false)
        ok(oldHost.games.open()).use { game ->
            ok(game.store.atomic { db ->
                // 마지막 방문의 배정·대여와 무관한 원명령만 누락시킨다. 커서와 원정산은 보존한다.
                val latest = db.rawQuery("SELECT id FROM visits WHERE host_command IS NOT NULL ORDER BY rowid DESC LIMIT 1", null)
                    .use { assertTrue(it.moveToFirst()); it.getString(0) }
                assertEquals(1, db.update("visits", android.content.ContentValues().apply { putNull("host_command") },
                    "id=?", arrayOf(latest)))
                ContractResult.Success(Unit)
            })
        }
        assertRestore(context, name, expectReplay = true)
    }

    private fun terminalFixture(
        failFirst: Boolean,
        verify: (Context, String, HostSession) -> Unit,
    ) {
        val name = "simulation-terminal-originals-${UUID.randomUUID()}"
        val context = IsolatedDatabaseContext(instrumentation.targetContext, name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putInt("stage", terminal.stage).putLong("elapsed", terminal.elapsedMs).commit())
        val original = UnifiedSimulation(context, name)
        val host = HostSession(context, main, simulation = original)
        val frames = mutableListOf<DrivingFrame>()
        val recorded = mutableListOf<DrivingRecordingResult>()
        val rentalResults = mutableListOf<ContractResult<RentalRuntimeSnapshot>>()
        var verified = false
        try {
            lateinit var receiver: String
            instrumentation.runOnMainSync {
                receiver = host.driving.start { recorded += ok(it) }
                host.rental.start(receiver) { rentalResults += it }
            }
            val defs = ok(AssetTrialDefinitionProvider(context.assets).load())
            val onboarding = StoredOnboardingHostSource(host.games)
            val actor = ok(onboarding.read()).identity
            ok(onboarding.choose(actor, FirstGrant.CANDIDATES.first()))
            ok(host.games.open()).use { ok(it.store.activateObservedDriving(actor.profileId)) }
            var step = checkNotNull(SimulationTimeline().begin())
            val driving = SessionObservedDrivingSource(host.games, SqliteObservedDrivingJournal(),
                ObservedDrivingReducer(original.policy) { "terminal-original-driving" }, defs::drivingIncomeRules)
            val rental = SessionRentalProductSource(host.games, { ContractResult.Success(defs) }, original.policy,
                { ContractResult.Success(step.clock) }, random = { original.rentalRandom(host.games, it) })
            while (step.stage < 65) {
                val frame = step.frame()
                val result = driving.observe(receiver, frame, RecordingClock(step.elapsedMs, step.clock.epoch))
                ok(result)
                assertTrue(ok(rental.observe(frame, result)).deferred.isEmpty())
                step = checkNotNull(step.next())
            }

            ok(host.games.open()).use { game ->
                ok(game.store.read { db ->
                    db.rawQuery("SELECT host_command FROM visits WHERE host_command IS NOT NULL ORDER BY rowid LIMIT 1", null)
                        .use {
                            assertTrue(it.moveToFirst())
                            val command = JSONObject(it.getString(0))
                            assertEquals("rental-prepared-v2", command.getString("type"))
                            val first = RentalProductJson.prepared(command)
                            assertNotNull(first.city)
                            assertEquals(FirstGrant.CANDIDATES.first(), first.visit.request.desired)
                            assertEquals(1, first.visit.request.hours)
                            val terms = first.visit.terms
                            assertTrue(terms.roll >= terms.customer.accidentPercent())
                        }
                })
            }

            val blocker = HostCheckpointCommand.Expand(RequestId("fixture-terminal-rental-blocker"),
                actor.source, defs.version, defs.expansions.offers.first())
            if (failFirst) ok(host.games.open()).use {
                assertEquals(HostCheckpointPhase.SAVED, ok(it.store.hostCommands.save(actor.profileId, blocker)).phase)
            }
            awaitTerminal(original) {
                host.attachSimulation { frame, done -> frames += frame; host.driving.accept(frame, done) }
            }
            assertEquals(listOf(terminal.frame()), frames)
            val originalReceipt = recorded.single().committed.single()
            val originalSettlements = ok(host.games.open()).use { ok(it.store.listUnviewedSettlements()) }
            assertTrue(originalSettlements.any { it.transaction.kind == TransactionKind.RENTAL })
            assertEquals(1, originalSettlements.count { it.transaction.kind == TransactionKind.DRIVING })
            if (failFirst) {
                assertTrue(original.canRetry)
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), rentalResults.single())
                ok(host.games.open()).use { ok(it.store.hostCommands.cancelUnstarted(actor.profileId, blocker)) }
                awaitTerminal(original) { original.retry() }
                assertEquals(listOf(terminal.frame(), terminal.frame()), frames)
                assertTrue("주행 원결과 재생 때 새 commit이 없어야 한다", recorded.last().committed.isEmpty())
            }
            assertTrue(original.controlsAllowed)
            assertTrue(ok(rentalResults.last()).deferred.isEmpty())
            val paused = CountDownLatch(1)
            instrumentation.runOnMainSync {
                host.driving.stop()
                host.expansionWorker { paused.countDown() }
            }
            assertTrue(paused.await(20, TimeUnit.SECONDS))
            verify(context, name, host)
            ok(host.games.open()).use {
                assertEquals(originalSettlements, ok(it.store.listUnviewedSettlements()))
                assertEquals(originalReceipt, ok(it.store.observedDrivingReceipt(originalReceipt.trip.id)))
            }
            verified = true
        } finally {
            instrumentation.runOnMainSync { host.release() }
            // 성공 경로는 driving pause를 기다렸다. 실패 시 비동기 쓰기와 삭제의 경합을 피하고 증거를 보존한다.
            if (verified) {
                prefs.edit().clear().commit()
                context.deleteDatabase("unified-simulation.db")
            }
        }
    }

    private fun assertRestore(context: Context, name: String, expectReplay: Boolean) {
        val fresh = UnifiedSimulation(context, name)
        val host = HostSession(context, main, simulation = fresh)
        val frames = mutableListOf<DrivingFrame>()
        try {
            awaitTerminal(fresh) {
                host.attachSimulation { frame, done ->
                    frames += frame
                    fresh.observed(frame, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
                    done()
                }
            }
            assertEquals(if (expectReplay) listOf(terminal.frame()) else emptyList(), frames)
            assertEquals(expectReplay, fresh.canRetry)
            assertEquals(!expectReplay, fresh.controlsAllowed)
            assertEquals(ContractResult.Success(terminal.clock), fresh.clock())
        } finally {
            instrumentation.runOnMainSync { host.release() }
        }
    }

    private fun awaitTerminal(simulation: UnifiedSimulation, action: () -> Unit) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            simulation.changed = { if (simulation.controlsAllowed || simulation.canRetry) done.countDown() }
            action()
        }
        assertTrue("terminal 결과가 도착해야 한다", done.await(30, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { simulation.changed = null }
    }

    private class IsolatedDatabaseContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = super.getDatabasePath("$prefix-$name")
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase("$prefix-$name", mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            super.openOrCreateDatabase("$prefix-$name", mode, factory, errorHandler)
        override fun deleteDatabase(name: String): Boolean = super.deleteDatabase("$prefix-$name")
    }
}
