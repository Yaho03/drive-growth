package io.drivegrowth.app.host.garage

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.host.GarageHostMapper
import io.drivegrowth.app.host.HostSnapshot
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.*
import io.drivegrowth.core.rental.CompletedRental
import org.junit.Assert.*
import org.junit.Test

class GarageStoreCommandSourceTest {
    private val model = VehicleId("car")
    private val version = ContentVersion("content")
    private val rule = RuleVersion("rule")
    private val car = OwnedVehicle(OwnedVehicleId("target"),model,2,Revision(3),VehicleLifecycle.OWNED,Rarity.RARE,50,GameMoney(250),VehicleAcquisitionSource.GACHA)
    private val material = car.copy(ownedVehicleId=OwnedVehicleId("material"),rarity=Rarity.ADVANCED,revision=Revision(1))
    private val content = GameContentSnapshot(VehicleCatalogSnapshot(version,listOf(VehicleDefinition(model,"시험 차량"))),rule,true,
        listOf(VehicleGameData(model,GameMoney(1000),1)),GachaRules())
    private inner class Ledger : GarageCommandLedger {
        override val source=InputSource.SIMULATION
        var cars=listOf(car,material);var state:ContractResult<VehicleActivity> = ContractResult.Success(VehicleActivity.IDLE);var accidents=0
        var rented: OwnedVehicleId? = null
        var calls=mutableListOf<GarageCommand>()
        override fun balance()=ContractResult.Success(GameMoney(500))
        override fun activity(id:OwnedVehicleId)=if (id == rented) ContractResult.Success(VehicleActivity.RENTED) else state
        override fun listOwnedVehicles()=ContractResult.Success(cars)
        override fun firstCompletedRental()=ContractResult.Success<CompletedRental?>(null)
        override fun completedRental(id:RequestId)=ContractResult.Success<CompletedRental?>(null)
        override fun accidentCount(id:OwnedVehicleId)=ContractResult.Success(accidents)
        override fun readMaterialState(target:OwnedVehicleId,materialId:OwnedVehicleId)=ContractResult.Success(MaterialState(source,version,rule,GameMoney(500),
            MaterialVehicleState(car,(state as ContractResult.Success).value),MaterialVehicleState(material,VehicleActivity.IDLE)))
        override fun readRepairState(id:OwnedVehicleId)=ContractResult.Success(RepairState(source,version,rule,GameMoney(500),car,VehicleActivity.NEEDS_REPAIR,1,GameMoney(55)))
        override fun repairSettlement(id:RequestId)=ContractResult.Success<RepairSettlement?>(null)
        override fun commitGameChange(change:GameChange):ContractResult<Settlement>{calls.add(GarageCommand.Single(change));return ContractResult.Failure(ContractError.STORAGE_FAILURE)}
        override fun commitMaterial(command:MaterialCommand):ContractResult<MaterialSettlement>{calls.add(GarageCommand.Material(command));return ContractResult.Failure(ContractError.STORAGE_FAILURE)}
        override fun commitRepair(command:RepairCommand):ContractResult<RepairSettlement>{calls.add(GarageCommand.Repair(command));return ContractResult.Failure(ContractError.STORAGE_FAILURE)}
    }
    private fun prepare(l:Ledger,action:VehicleAction,material:VehicleRef?=null):ContractResult<GaragePreparation> =
        GarageStoreCommandSource(content,l).prepare(GarageEvent.RequestAction(car.ownedVehicleId,car.revision,action),RequestId("request"),material)
    private fun ready(result:ContractResult<GaragePreparation>)=((result as ContractResult.Success).value as GaragePreparation.Ready).pending
    @Test fun plansCoreLevelUpSaleAndScrapWithActualAcquisitionAndNoCommit() {
        val ledger=Ledger()
        val level=(ready(prepare(ledger,VehicleAction.LEVEL_UP)).command as GarageCommand.Single).value
        assertEquals(GameMoney(44),level.money!!.amount);assertEquals(3,(level.vehicle as VehicleWrite.Update).level)
        val sale=(ready(prepare(ledger,VehicleAction.SELL)).command as GarageCommand.Single).value
        assertEquals(MoneyChange(MoneyDirection.CREDIT,GameMoney(152)),sale.money)
        val scrap=(ready(prepare(ledger,VehicleAction.SCRAP)).command as GarageCommand.Single).value
        assertEquals(GameMoney(50),scrap.money!!.amount);assertTrue(ledger.calls.isEmpty())
    }
    @Test fun activityFailuresAndThreeAccidentSaleAreExplicitFailures() {
        val l=Ledger();l.state=ContractResult.Failure(ContractError.STORAGE_FAILURE)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),prepare(l,VehicleAction.LEVEL_UP))
        l.state=ContractResult.Success(VehicleActivity.RENTED)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepare(l,VehicleAction.SCRAP))
        l.state=ContractResult.Success(VehicleActivity.NEEDS_REPAIR);l.accidents=3
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepare(l,VehicleAction.SELL))
        assertTrue(prepare(l,VehicleAction.SCRAP) is ContractResult.Success)
    }
    @Test fun candidatesUseOtherOwnedIdleVehiclesAndCoreBuildsTwoRoleCommand() {
        val l=Ledger()
        val choices=(prepare(l,VehicleAction.USE_MATERIAL) as ContractResult.Success).value as GaragePreparation.Materials
        assertEquals(listOf(material),choices.candidates)
        val c=(ready(prepare(l,VehicleAction.USE_MATERIAL,VehicleRef(material.ownedVehicleId,material.revision))).command as GarageCommand.Material).value
        assertEquals(4,c.target.level);assertEquals(0,c.target.levelProgressPercent);assertEquals(VehicleLifecycle.CONSUMED,c.material.lifecycle)
        assertEquals(GameMoney(0),c.cost);assertTrue(l.calls.isEmpty())
    }
    @Test fun materialNamesFollowTheWholeGarageEvenWhenCandidatesAreFiltered() {
        val rentedVehicle = material.copy(ownedVehicleId = OwnedVehicleId("ahead-rented"))
        val other = material.copy(ownedVehicleId = OwnedVehicleId("other"))
        val l = Ledger().apply {
            cars = listOf(other, car, material, rentedVehicle)
            rented = rentedVehicle.ownedVehicleId
        }
        val garage = GarageHostMapper().garage(HostSnapshot(content, l.source, GameMoney(500), l.cars, emptyList())) as GarageScreenState.Content
        val names = garage.data.vehicles.associate { it.vehicle.ownedVehicleId to vehicleDisplayName(it.name, it.instanceLabel) }
        val choices = (prepare(l, VehicleAction.USE_MATERIAL) as ContractResult.Success).value as GaragePreparation.Materials
        assertEquals(listOf(other, material), choices.candidates)
        assertEquals(mapOf(other.ownedVehicleId to "시험 차량 3번", material.ownedVehicleId to "시험 차량 2번"), choices.candidateNames)
        assertEquals(names.filterKeys { it in choices.candidateNames }, choices.candidateNames)

        val ref = VehicleRef(material.ownedVehicleId, material.revision)
        val prepared = ready(prepare(l, VehicleAction.USE_MATERIAL, ref))
        assertEquals(names.getValue(car.ownedVehicleId), prepared.name)
        assertEquals(choices.candidateNames.getValue(ref.ownedVehicleId), prepared.materialName)
        val update = (prepared.command as GarageCommand.Material).value.material
        assertEquals(ref, VehicleRef(update.ownedVehicleId, update.expectedRevision))
        l.cars = listOf(car)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), prepare(l, VehicleAction.USE_MATERIAL, ref))
        assertTrue(l.calls.isEmpty())
    }
    @Test fun repairUsesStoredQuoteAndCommitDispatchKeepsExactCommandAndFailure() {
        val l=Ledger();val source=GarageStoreCommandSource(content,l)
        val p=ready(prepare(l,VehicleAction.REPAIR));val repair=(p.command as GarageCommand.Repair).value
        assertEquals(GameMoney(55),repair.cost);assertEquals(1,repair.expectedAccidentCount)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),source.commit(p.command))
        assertEquals(listOf(p.command),l.calls)
    }
}
