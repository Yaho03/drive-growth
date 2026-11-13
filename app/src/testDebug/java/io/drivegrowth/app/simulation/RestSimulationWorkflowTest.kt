package io.drivegrowth.app.simulation

import io.drivegrowth.app.simulation.RestSimulationWorkflow.Action
import io.drivegrowth.app.simulation.RestSimulationWorkflow.Screen
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RestSimulationWorkflowTest {
    private val unavailable = ContractResult.Failure(ContractError.STORAGE_FAILURE)

    @Test fun failingToReadBeforePreparationOffersOnlyReadRetry() {
        val workflow = RestSimulationWorkflow<String>()
        val doNotPrepare: () -> ContractResult<RestDemoOperation> =
            { error("원장 확인 없이는 원입력을 준비하지 않는다.") }

        assertEquals(Screen.Failure(unavailable.error, Action.READ),
            workflow.execute(Action.START, { unavailable }, doNotPrepare))
        assertEquals(Screen.Ready, workflow.execute(Action.READ, { ContractResult.Success(null) }, doNotPrepare))
    }

    @Test fun preparationFailureOffersPreparationAgainWithoutRunningAnInput() {
        val workflow = RestSimulationWorkflow<String>()
        var prepareCalls = 0
        var payments = 0
        val read = { ContractResult.Success(if (payments == 1) "original" else null) }
        val prepare: () -> ContractResult<RestDemoOperation> = {
            prepareCalls++
            if (prepareCalls == 1) unavailable else ContractResult.Success { _, _ ->
                payments++
                ContractResult.Success(true)
            }
        }

        assertEquals(Screen.Failure(unavailable.error, Action.START), workflow.execute(Action.START, read, prepare))
        assertEquals(0, payments)
        assertEquals(Screen.Saved("original"), workflow.execute(Action.START, read, prepare))
        assertEquals(2, prepareCalls)
        assertEquals(1, payments)
    }

    @Test fun returningToTheScreenDoesNotResubmitTheRetainedInputUntilExplicitRetry() {
        val workflow = RestSimulationWorkflow<String>()
        var prepareCalls = 0
        var attempts = 0
        var payments = 0
        val read = { ContractResult.Success(if (payments == 1) "original" else null) }
        val prepare: () -> ContractResult<RestDemoOperation> = {
            prepareCalls++
            ContractResult.Success { _, _ ->
                attempts++
                if (attempts == 1) unavailable else {
                    payments++
                    ContractResult.Success(true)
                }
            }
        }

        val waiting = Screen.Failure(unavailable.error, Action.RETRY_INPUT)
        assertEquals(waiting, workflow.execute(Action.START, read, prepare))
        assertEquals(waiting, workflow.execute(Action.READ, read, prepare))
        assertEquals(waiting, workflow.execute(Action.START, read, prepare))
        assertEquals(1, attempts)
        assertEquals(Screen.Saved("original"), workflow.execute(Action.RETRY_INPUT, read, prepare))
        assertEquals(1, prepareCalls)
        assertEquals(2, attempts)
        assertEquals(1, payments)
    }

    @Test fun failingToReadAfterCommitRetriesOnlyTheOriginalResult() {
        val workflow = RestSimulationWorkflow<String>()
        var prepareCalls = 0
        var payments = 0
        var stored: ContractResult<String?> = ContractResult.Success(null)
        val read = { stored }
        val prepare: () -> ContractResult<RestDemoOperation> = {
            prepareCalls++
            ContractResult.Success { _, _ ->
                payments++
                stored = unavailable
                ContractResult.Success(true)
            }
        }

        val retryRead = Screen.Failure(unavailable.error, Action.READ)
        assertEquals(retryRead, workflow.execute(Action.START, read, prepare))
        assertEquals(retryRead, workflow.execute(Action.READ, read, prepare))
        stored = ContractResult.Success(null)
        assertEquals(retryRead, workflow.execute(Action.START, read, prepare))
        stored = ContractResult.Success("original")
        assertEquals(Screen.Saved("original"), workflow.execute(Action.READ, read, prepare))
        assertEquals(Screen.Saved("original"), workflow.execute(Action.START, read, prepare))
        assertEquals(1, prepareCalls)
        assertEquals(1, payments)
    }

    @Test fun leavingPausesTheSameRunAndReadDoesNotResumeIt() {
        val workflow = RestSimulationWorkflow<String>()
        var prepareCalls = 0
        var samples = 0
        var canContinue = true
        val read = { ContractResult.Success(if (samples == 2) "original" else null) }
        val prepare: () -> ContractResult<RestDemoOperation> = {
            prepareCalls++
            ContractResult.Success { _, keepRunning ->
                while (samples < 2 && keepRunning()) {
                    samples++
                    if (samples == 1) canContinue = false
                }
                ContractResult.Success(samples == 2)
            }
        }

        assertEquals(Screen.Paused, workflow.execute(Action.START, read, prepare, canContinue = { canContinue }))
        assertEquals(1, samples)
        assertEquals(Screen.Paused, workflow.execute(Action.READ, read, prepare))
        assertEquals(Screen.Paused, workflow.execute(Action.START, read, prepare))
        assertEquals(1, samples)
        canContinue = true
        assertEquals(Screen.Saved("original"), workflow.execute(Action.RETRY_INPUT, read, prepare, canContinue = { canContinue }))
        assertEquals(1, prepareCalls)
        assertEquals(2, samples)
    }

    @Test fun leavingAtTheLastCommitStillRestoresTheOriginalWithoutAnotherRun() {
        val workflow = RestSimulationWorkflow<String>()
        var canContinue = true
        var payments = 0
        val read = { ContractResult.Success(if (payments == 1) "original" else null) }
        val prepare: () -> ContractResult<RestDemoOperation> = {
            ContractResult.Success { _, _ ->
                payments++
                canContinue = false
                ContractResult.Success(true)
            }
        }

        assertEquals(Screen.Saved("original"), workflow.execute(Action.START, read, prepare, canContinue = { canContinue }))
        assertEquals(Screen.Saved("original"), workflow.execute(Action.READ, read, prepare, canContinue = { canContinue }))
        assertEquals(1, payments)
    }
}
