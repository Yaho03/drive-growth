package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.garage.restoreGaragePending
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DecorationPendingRouteTest {
    @Test fun unboundDecorationDoesNotRouteToAnUnrelatedHomeOrGarageOwner() {
        val command=HostCheckpointCommand.Decoration(DecorationCommand(RequestId("decoration"),ProfileId("actual-profile"),InputSource.SIMULATION,
            "neutral-sign",DecorationAction.BUY_AND_APPLY,RuleVersion("decoration-trial-v1"),Revision(0),GameMoney(0)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),pendingHostPage(command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),pendingHostPage(command,facilityPageAvailable=true))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(command))
    }
}
