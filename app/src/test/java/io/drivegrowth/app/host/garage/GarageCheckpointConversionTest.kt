package io.drivegrowth.app.host.garage

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.*
import org.junit.Test

class GarageCheckpointConversionTest {
    private val content=ContentVersion("original-content")
    private val rule=RuleVersion("original-rule")
    private val id=OwnedVehicleId("target")
    private val car=OwnedVehicle(id,VehicleId("car"),2,Revision(3),VehicleLifecycle.OWNED,Rarity.COMMON,25,GameMoney(0),VehicleAcquisitionSource.GACHA)
    @Test fun singleOriginalPayloadAndVersionSurviveWithoutInventingHistoricalFacts() {
        for((life,action) in listOf(VehicleLifecycle.OWNED to VehicleAction.LEVEL_UP,VehicleLifecycle.SOLD to VehicleAction.SELL,VehicleLifecycle.SCRAPPED to VehicleAction.SCRAP)) {
            val command=GameChange(RequestId("original"),TransactionKind.GARAGE,InputSource.SIMULATION,rule,
                MoneyChange(MoneyDirection.DEBIT,GameMoney(37)),VehicleWrite.Update(id,Revision(3),4,life,75))
            val input=GaragePending(GarageEvent.RequestAction(id,Revision(3),action),content,GarageCommand.Single(command),GameMoney(100),car,"이전 이름")
            val restored=(restoreGaragePending(input.checkpoint()) as ContractResult.Success).value
            assertEquals(input.command,restored.command);assertEquals(content,restored.contentVersion);assertEquals(input.request,restored.request)
            assertNull(restored.balance);assertNull(restored.vehicle);assertNull(restored.name)
            assertEquals(input.checkpoint(),restored.checkpoint())
        }
    }
    @Test fun materialAndRepairRecoverExactTypedCommandWithoutCurrentContentOrVehicleLookup() {
        val material=MaterialCommand(RequestId("material"),InputSource.SIMULATION,content,rule,
            VehicleWrite.Update(id,Revision(3),4,VehicleLifecycle.OWNED,0),VehicleWrite.Update(OwnedVehicleId("material"),Revision(6),7,VehicleLifecycle.CONSUMED,50),GameMoney(0))
        val repair=RepairCommand(RequestId("repair"),InputSource.SIMULATION,content,rule,VehicleRef(id,Revision(3)),2,GameMoney(55))
        for((stored,expected) in listOf(HostCheckpointCommand.Material(material) to GarageCommand.Material(material),HostCheckpointCommand.Repair(repair) to GarageCommand.Repair(repair))) {
            val restored=(restoreGaragePending(stored) as ContractResult.Success).value
            assertEquals(expected,restored.command);assertEquals(stored,restored.checkpoint())
        }
    }
    @Test fun gachaOwnedSlotIsAConflictAndMalformedGarageIsNotFilledWithDefaults() {
        val gacha=GachaCommand(RequestId("gacha"),InputSource.SIMULATION,content,rule,Revision(1),1,3,0,GachaTicketUse.FREE,
            GachaAward(car.copy(revision=Revision(0),level=1,levelProgressPercent=0),1,false))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),restoreGaragePending(HostCheckpointCommand.Gacha(gacha)))
        val bad=GameChange(RequestId("bad"),TransactionKind.GARAGE,InputSource.SIMULATION,rule,vehicle=VehicleWrite.Add(car))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),restoreGaragePending(HostCheckpointCommand.Garage(content,bad)))
    }
}
