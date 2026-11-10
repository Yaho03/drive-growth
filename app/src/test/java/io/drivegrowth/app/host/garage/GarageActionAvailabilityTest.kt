package io.drivegrowth.app.host.garage

import io.drivegrowth.app.host.*
import io.drivegrowth.app.ui.garage.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.*
import org.junit.Assert.*
import org.junit.Test

class GarageActionAvailabilityTest {
    private val car=OwnedVehicle(OwnedVehicleId("target"),VehicleId("car"),20,Revision(0),VehicleLifecycle.OWNED,
        Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private val content=GameContentSnapshot(VehicleCatalogSnapshot(ContentVersion("content"),listOf(VehicleDefinition(car.vehicleId,"시험차"))),RuleVersion("rule"),true,emptyList(),GachaRules())
    private fun actions(activity:VehicleActivity?,count:ContractResult<Int>):Map<VehicleAction,ActionPresentation> {
        val state=GarageScreenState.Content(GarageContent(listOf(VehiclePresentation(car,"시험차","id",activity)),null,"SIMULATION"))
        val snapshot=HostSnapshot(content,InputSource.SIMULATION,GameMoney(0),listOf(car),emptyList(),garageState=ContractResult.Success(
            StoredGarageState(InputSource.SIMULATION,content.catalog.contentVersion,content.ruleVersion,
                listOf(StoredVehicleState(car,activity?.let{ContractResult.Success(it)}?:ContractResult.Failure(ContractError.DATA_UNAVAILABLE),count)),ContractResult.Success(3))))
        return (GarageActionAvailability.map(state,snapshot) as GarageScreenState.Content).data.vehicles.single().actions
    }
    @Test fun maxLevelIsNotAReasonToBlockScrapOrRepairWhenRequired() {
        val a=actions(VehicleActivity.NEEDS_REPAIR,ContractResult.Success(3))
        assertTrue(a.getValue(VehicleAction.REPAIR).enabled);assertTrue(a.getValue(VehicleAction.SCRAP).enabled)
        assertFalse(a.getValue(VehicleAction.SELL).enabled);assertTrue(a.getValue(VehicleAction.SELL).explanation.contains("3회"))
        val idle=actions(VehicleActivity.IDLE,ContractResult.Success(0))
        assertFalse(idle.getValue(VehicleAction.LEVEL_UP).enabled);assertFalse(idle.getValue(VehicleAction.USE_MATERIAL).enabled)
    }
    @Test fun missingAndActiveVehicleSignalsNeverTurnIntoIdleActions() {
        for(activity in listOf(null,VehicleActivity.RENTED,VehicleActivity.RETURNING))
            assertTrue(actions(activity,ContractResult.Success(0)).values.none{it.enabled})
    }
    @Test fun missingAccidentCountDisablesSaleWithoutInventingZero() {
        val a=actions(VehicleActivity.IDLE,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertFalse(a.getValue(VehicleAction.SELL).enabled);assertTrue(a.getValue(VehicleAction.SCRAP).enabled)
    }
}
