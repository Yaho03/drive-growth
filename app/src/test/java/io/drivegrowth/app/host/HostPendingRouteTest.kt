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
        assertEquals(ContractResult.Success(HostPage.HOME),pendingHostPage(expand))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(expand))
        assertEquals(RequestId("expand"),expand.requestId)
    }
    @Test fun garageRoutesManagementAndEmptyCheckpointDoesNotInventRoute(){
        val change=GameChange(RequestId("garage"),TransactionKind.GARAGE,InputSource.VEHICLE,RuleVersion("rules"),
            vehicle=VehicleWrite.Update(OwnedVehicleId("car"),Revision(0),2,VehicleLifecycle.OWNED,0))
        assertEquals(ContractResult.Success(HostPage.GARAGE),pendingHostPage(HostCheckpointCommand.Garage(ContentVersion("v"),change)))
        assertEquals(ContractResult.Success<HostPage?>(null),pendingHostPage(null))
    }
    @Test fun gachaRoutesItsOwnRecoveryWithoutChangingTheOriginalCommand(){
        val vehicle=OwnedVehicle(OwnedVehicleId("award"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,
            Rarity.COMMON,0,GameMoney(500),VehicleAcquisitionSource.GACHA)
        val command=GachaCommand(RequestId("draw"),InputSource.VEHICLE,ContentVersion("v"),RuleVersion("r"),
            Revision(0),1,3,0,GachaTicketUse.PURCHASED,GachaAward(vehicle,1,false))
        assertEquals(ContractResult.Success(HostPage.GACHA),pendingHostPage(HostCheckpointCommand.Gacha(command)))
        assertEquals(vehicle,command.award.vehicle)
    }

    @Test fun deliveryRoutesToItsOwnPageAndGaragePreservesForeignCommand(){
        val command=HostCheckpointCommand.Delivery(ContentVersion("content"),InputSource.VEHICLE,
            io.drivegrowth.app.data.DeliveryCheckpointOperation.Abandon(
                io.drivegrowth.core.delivery.DeliveryChangeRules(RuleVersion("delivery-rule"),10),RequestId("chosen")))
        assertEquals(ContractResult.Success(HostPage.DELIVERY),pendingHostPage(command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(command))
        assertEquals(RequestId("chosen"),command.operation.requestId)
    }

    @Test fun providedQuestPageReturnsTypedSuccessAndGaragePreservesForeignCommand() {
        val zone=java.time.ZoneId.of("UTC")
        val definition=io.drivegrowth.core.quest.QuestDefinition("daily-result-viewed",RuleVersion("q"),
            io.drivegrowth.core.quest.QuestCadence.DAILY,io.drivegrowth.core.quest.QuestAction.RESULT_VIEWED,1,GameMoney(10),zone,true)
        val command=HostCheckpointCommand.Quest(io.drivegrowth.core.quest.QuestClaimCommand(RequestId("quest/claim/foreign"),
            ProfileId("profile"),InputSource.SIMULATION,definition,io.drivegrowth.core.quest.QuestPeriod(
                io.drivegrowth.core.quest.QuestCadence.DAILY,java.time.LocalDate.of(2026,11,10),zone),Revision(0)))
        assertEquals(ContractResult.Success(HostPage.PROGRESSION),pendingHostPage(command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(command))
    }

}
