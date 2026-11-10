package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import org.junit.Assert.*
import org.junit.Test

class HostCheckpointCommandTest {
    private val source=InputSource.SIMULATION
    private val id=RequestId("request")
    private val content=ContentVersion("old-content")
    private val rule=RuleVersion("old-rules")
    private val update=VehicleWrite.Update(OwnedVehicleId("car"),Revision(2),3,VehicleLifecycle.OWNED,50)
    private fun garage(change:GameChange=GameChange(id,TransactionKind.GARAGE,source,rule,MoneyChange(MoneyDirection.DEBIT,GameMoney(2)),update))=HostCheckpointCommand.Garage(content,change)
    @Test fun levelUpAndDisposalAreAcceptedWithTheirExactOriginalInputs(){
        assertTrue(garage().validFor(source))
        assertTrue(garage(GameChange(id,TransactionKind.GARAGE,source,rule,MoneyChange(MoneyDirection.CREDIT,GameMoney(0)),update.copy(lifecycle=VehicleLifecycle.SOLD))).validFor(source))
        assertTrue(garage(GameChange(id,TransactionKind.GARAGE,source,rule,null,update.copy(lifecycle=VehicleLifecycle.SCRAPPED))).validFor(source))
    }
    @Test fun unrelatedKindsAddsAndConsumedGenericWritesAreRejected(){
        val base=GameChange(id,TransactionKind.RENTAL,source,rule,null,update)
        assertFalse(garage(base).validFor(source));assertFalse(garage(base.copy(kind=TransactionKind.GARAGE,vehicle=null,money=MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))).validFor(source))
        assertFalse(garage(base.copy(kind=TransactionKind.GARAGE,vehicle=update.copy(lifecycle=VehicleLifecycle.CONSUMED))).validFor(source))
        val added=OwnedVehicle(OwnedVehicleId("added"),VehicleId("v"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
        assertFalse(garage(base.copy(kind=TransactionKind.GARAGE,vehicle=VehicleWrite.Add(added))).validFor(source))
    }
    @Test fun sourceAndReservedRequestDomainsAreRejected(){
        val base=GameChange(id,TransactionKind.GARAGE,source,rule,null,update)
        assertFalse(garage(base).validFor(InputSource.VEHICLE))
        for(key in listOf("drive/x","delivery/x")) assertFalse(garage(base.copy(requestId=RequestId(key))).validFor(source))
    }
    @Test fun materialRepairAndGachaRetainTheirOwnCommandIdentity(){
        val material=HostCheckpointCommand.Material(MaterialCommand(id,source,content,rule,update,update.copy(ownedVehicleId=OwnedVehicleId("material"),lifecycle=VehicleLifecycle.CONSUMED),GameMoney(0)))
        val repair=HostCheckpointCommand.Repair(RepairCommand(id,source,content,rule,VehicleRef(update.ownedVehicleId,update.expectedRevision),1,GameMoney(55)))
        val vehicle=OwnedVehicle(OwnedVehicleId("award"),VehicleId("v"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.RARE,0,GameMoney(500),VehicleAcquisitionSource.GACHA)
        val gacha=HostCheckpointCommand.Gacha(GachaCommand(id,source,content,rule,Revision(0),1,3,0,GachaTicketUse.PURCHASED,GachaAward(vehicle,1,false)))
        for(command in listOf(material,repair,gacha)){assertTrue(command.validFor(source));assertEquals(id,command.requestId)}
        assertNotEquals(material,repair)
    }
    @Test fun payloadAndExecutionPhaseAreBothBoundByTheDigest(){
        assertNotEquals(HostCheckpointJson.digest("original",0),HostCheckpointJson.digest("changed",0))
        assertNotEquals(HostCheckpointJson.digest("original",0),HostCheckpointJson.digest("original",1))
        assertEquals(HostCheckpointJson.digest("original",1),HostCheckpointJson.digest("original",1))
    }

}
