package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.garage.restoreGaragePending
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundDecorationPendingRouteTest {
    @Test fun onlyRegisteredHomeCanOwnDecorationAndGarageRemainsForeign() {
        val c=HostCheckpointCommand.Decoration(DecorationCommand(RequestId("decoration"),ProfileId("home"),InputSource.SIMULATION,
            "neutral-sign",DecorationAction.BUY_AND_APPLY,RuleVersion("decoration-trial-v1"),Revision(0),GameMoney(0)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),pendingHostPage(c))
        assertEquals(ContractResult.Success(HostPage.HOME),pendingHostPage(c,decorationPageAvailable=true))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(c))
    }
}
