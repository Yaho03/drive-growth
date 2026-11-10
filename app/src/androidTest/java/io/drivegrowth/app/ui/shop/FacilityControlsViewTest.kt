package io.drivegrowth.app.ui.shop

import android.test.InstrumentationTestCase
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.ShopProgress

@Suppress("DEPRECATION")
class FacilityControlsViewTest:InstrumentationTestCase() {
    private val identity=GameIdentity(ProfileId("issued"),InputSource.SIMULATION)
    private val car=OwnedVehicle(OwnedVehicleId("one"),VehicleId("model"),1,Revision(3),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private val facts=FacilityFacts(identity,ContentVersion("central"),ShopState(ShopProgress(1,0,1),3,emptySet()),GameMoney(1000),listOf(car),mapOf(car.ownedVehicleId to VehicleActivity.RENTED),emptySet(),emptyMap())
    fun testActualRowToggleCarriesCurrentVersionAndExpectedSettingOnlyWhenAllowed() {
        instrumentation.runOnMainSync {
            val events=mutableListOf<FacilityControlsEvent>();val view=FacilityControlsView(instrumentation.targetContext,events::add)
            view.render(FacilityHostState(version=8,facts=facts),true,mapOf(car.vehicleId to "캐스퍼"))
            val toggle=view.findViewWithTag<Button>("facility:rest:one");assertTrue(toggle.isEnabled);toggle.performClick()
            assertEquals(FacilityControlsEvent.SetResting(8,VehicleRef(car.ownedVehicleId,car.revision),false,true),events.single())
            view.render(FacilityHostState(version=9,facts=facts),false,mapOf(car.vehicleId to "캐스퍼"));toggle.performClick();assertEquals(1,events.size)
        }
    }
    fun testPastReceiptDoesNotClaimCurrentSettingAndRequiresAcknowledgement() {
        instrumentation.runOnMainSync {
            val events=mutableListOf<FacilityControlsEvent>();val view=FacilityControlsView(instrumentation.targetContext,events::add)
            val command=FacilityCommand.Rest(identity,RequestId("old-on"),VehicleRef(car.ownedVehicleId,car.revision),false,true)
            view.render(FacilityHostState(version=2,pending=command,receipt=FacilityReceipt.Rested(command,false,true)),true,emptyMap())
            val detail=view.findViewWithTag<TextView>("facility:receipt").text.toString();assertTrue(detail.contains("당시"));assertTrue(detail.contains("현재"))
            assertNull(view.findViewWithTag<Button>("facility:rest:one"));view.findViewWithTag<Button>("facility:ack").performClick();assertEquals(listOf(FacilityControlsEvent.Acknowledge),events)
        }
    }
    fun testForeignAndMissingActivityDoNotOfferEnabledToggleOrCancel() {
        instrumentation.runOnMainSync {
            val events=mutableListOf<FacilityControlsEvent>();val view=FacilityControlsView(instrumentation.targetContext,events::add)
            view.render(FacilityHostState(foreign=true),true,emptyMap());assertNull(view.findViewWithTag<Button>("facility:cancel"))
            view.render(FacilityHostState(version=1,facts=facts.copy(activities=emptyMap())),true,mapOf(car.vehicleId to "캐스퍼"));val toggle=view.findViewWithTag<Button>("facility:rest:one");assertFalse(toggle.isEnabled);toggle.performClick();assertTrue(events.isEmpty())
        }
    }
    fun testIndependentAcknowledgedResultCanRetryFailedCurrentReadWithoutFinishingAgain() {
        instrumentation.runOnMainSync {
            var readMissing=false;var finishes=0;var writes=0;var saved:FacilityCommand?=null
            val backend=object:FacilityHostSource {
                override fun pending():ContractResult<FacilityPending> = ContractResult.Success(saved?.let{FacilityPending.Own(it)}?:FacilityPending.Empty)
                override fun read():ContractResult<FacilityFacts> = if(readMissing)ContractResult.Failure(ContractError.DATA_UNAVAILABLE) else ContractResult.Success(facts)
                override fun save(command:FacilityCommand):ContractResult<Unit>{saved=command;return ContractResult.Success(Unit)}
                override fun execute(command:FacilityCommand):ContractResult<FacilityReceipt>{writes++;val c=command as FacilityCommand.Rest;return ContractResult.Success(FacilityReceipt.Rested(c,c.expected,c.desired))}
                override fun finish(command:FacilityCommand):ContractResult<Unit>{finishes++;saved=null;return ContractResult.Success(Unit)}
                override fun cancelSaved(command:FacilityCommand):ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
            lateinit var host:FacilityHostController
            val view=FacilityControlsView(instrumentation.targetContext){event->when(event){
                FacilityControlsEvent.Acknowledge->host.acknowledge()
                FacilityControlsEvent.Retry->host.refresh()
                else->Unit
            }}
            host=FacilityHostController(backend,java.util.concurrent.Executor{it.run()},java.util.concurrent.Executor{it.run()})
            host.changed={state,allowed->view.render(state,allowed,mapOf(car.vehicleId to "검토 차량"))};host.setControlsEnabled(true)
            assertTrue(host.setResting(RestingRequest(host.state.version,RequestId("independent-read-recovery"),VehicleRef(car.ownedVehicleId,car.revision),false,true)))
            readMissing=true;view.findViewWithTag<Button>("facility:ack").performClick()
            assertEquals(1,finishes);assertEquals(ContractError.DATA_UNAVAILABLE,host.state.error)
            val retry=view.findViewWithTag<Button>("facility:retry")
            assertNotNull("슬롯 확인 완료 뒤 조회 실패 화면에는 재조회 행동이 필요함",retry)
            assertTrue(retry.isEnabled);readMissing=false;retry.performClick()
            assertNull(host.state.error);assertNotNull(host.state.facts);assertEquals(1,finishes);assertEquals(1,writes)
        }
    }

}
