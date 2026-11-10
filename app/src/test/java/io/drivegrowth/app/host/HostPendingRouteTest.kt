package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.garage.restoreGaragePending
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import org.junit.Assert.*
import org.junit.Test

class HostPendingRouteTest {
    private val expand=HostCheckpointCommand.Expand(RequestId("expand"),InputSource.SIMULATION,ContentVersion("definition"),
        ShopExpansion(RuleVersion("rules"),true,1,3,5,3,GameMoney(500)))
    @Test fun expansionRoutesHomeButGarageCannotRestoreOrConsumeIt(){
        assertEquals(HostPage.HOME,pendingHostPage(expand))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(expand))
        assertEquals(RequestId("expand"),expand.requestId)
    }
    @Test fun garageRoutesManagementAndEmptyCheckpointDoesNotInventRoute(){
        val change=GameChange(RequestId("garage"),TransactionKind.GARAGE,InputSource.VEHICLE,RuleVersion("rules"),
            vehicle=VehicleWrite.Update(OwnedVehicleId("car"),Revision(0),2,VehicleLifecycle.OWNED,0))
        assertEquals(HostPage.GARAGE,pendingHostPage(HostCheckpointCommand.Garage(ContentVersion("v"),change)))
        assertNull(pendingHostPage(null))
    }
    @Test fun gachaRoutesItsOwnRecoveryWithoutChangingTheOriginalCommand(){
        val vehicle=OwnedVehicle(OwnedVehicleId("award"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,
            Rarity.COMMON,0,GameMoney(500),VehicleAcquisitionSource.GACHA)
        val command=GachaCommand(RequestId("draw"),InputSource.VEHICLE,ContentVersion("v"),RuleVersion("r"),
            Revision(0),1,3,0,GachaTicketUse.PURCHASED,GachaAward(vehicle,1,false))
        assertEquals(HostPage.GACHA,pendingHostPage(HostCheckpointCommand.Gacha(command)))
        assertEquals(vehicle,command.award.vehicle)
    }

}
