package io.drivegrowth.app.host

import android.os.Process
import android.test.InstrumentationTestCase
import android.util.Log
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsUiState
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileId
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Each selector intentionally waits for the host to terminate this isolated process.
 * Run from independent copies of the same prepared #54 ledger.
 */
@Suppress("DEPRECATION")
class ResultsViewedInterruptionVerificationTest : InstrumentationTestCase() {
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun awaitProcessDeath(marker: String, target: String): Nothing {
        Log.i("Results54InterruptF", "$marker pid=${Process.myPid()} settlement=$target")
        CountDownLatch(1).await(45, TimeUnit.SECONDS)
        throw AssertionError("HOST_TERMINATION_MISSING: $marker")
    }

    fun testHoldInsideRealTransactionBeforeCommit() = interrupt(beforeCommit = true)

    fun testHoldAfterRealCommitBeforeHostResponse() = interrupt(beforeCommit = false)

    private fun interrupt(beforeCommit: Boolean) {
        val context = instrumentation.targetContext
        assertEquals("io.drivegrowth.verify.results54originalf", context.packageName)
        assertTrue(context.getDatabasePath("game-ledger-simulation.db").isFile)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
        SqliteGameChangeStore(context, InputSource.SIMULATION).use { store ->
            assertEquals(ProfileId("local-137dce8d-6f1d-4a96-a118-9b6b0de7e1e2"), ok(store.localProfileId()))
            val history = SettlementHistoryReader(store)
            val target = ok(history.read()).entries.single {
                it.settlement.transaction.requestId.value == "rental/settle/results54-stored-basis"
            }
            assertFalse(target.viewed)
            val id = target.settlement.settlementId
            val pendingReplies = ArrayDeque<Runnable>()
            var replies = 0
            val controller = ResultsHostController(Executor(Runnable::run), Executor { pendingReplies.addLast(it) },
                store::markViewed, {}, ResultsUiState(selectedId = id), history::read)
            controller.setControlsEnabled(true)
            pendingReplies.removeFirst().run()
            assertTrue(pendingReplies.isEmpty())
            controller.attach { _, _ -> replies++ }
            if (beforeCommit) {
                // The existing test hook runs after the real viewed/event writes, before SQLite COMMIT.
                store.beforeCommit = { awaitProcessDeath("BEFORE_SQLITE_COMMIT", id.value) }
            }
            assertTrue(controller.markViewed(id, null, true))
            assertEquals(ResultsReadState.Pending, controller.uiState.readStates[id])
            assertEquals(0, replies)
            assertEquals(1, pendingReplies.size)
            // markViewed has returned from endTransaction; the real response is still queued.
            assertFalse(beforeCommit)
            assertTrue(ok(history.read()).entries.single { it.settlement.settlementId == id }.viewed)
            awaitProcessDeath("AFTER_SQLITE_COMMIT_BEFORE_RESPONSE", id.value)
        }
    }
}
