package io.drivegrowth.app.host.delivery
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

/** SDK 입력 경로 분류 시험. 실차 실행·도착 상관 인증과 구분한다. */
class NavigationEstimateSourceIndependentTest {
 private val stamp=NavigationStamp(1000,100,7,1,NavigationSource.PLEOS_NAVI_SDK)
 private val vehicle=GameIdentity(ProfileId("p"),InputSource.VEHICLE)
 private val simulation=GameIdentity(ProfileId("p"),InputSource.SIMULATION)
 private fun raw(mode:String="ROUTE_GUIDING",routing:Boolean=false):NavigationSnapshot=NavigationSnapshot(
  stamp.copy(source=NavigationSource.APP_STATE),NavigationConnection.CONNECTED_OBSERVED,
  NavigationField.Missing(NavigationMissing.NOT_OBSERVED),NavigationField.Missing(NavigationMissing.NOT_OBSERVED),
  NavigationField.Missing(NavigationMissing.NOT_OBSERVED),NavigationField.Value(NavigationGuidance(null,mode,routing),stamp),
  NavigationField.Missing(NavigationMissing.NOT_OBSERVED),null,null)
 private fun classified(identity:GameIdentity,raw:NavigationSnapshot)=deliveryEstimateSource(identity,raw){true}
 @Test fun realSdkRouteGuidingClassifiesVehicleWithoutRequiringIsRoutingTrue(){
  assertEquals(Observation.Available(InputSource.VEHICLE),classified(vehicle,raw(routing=false)))
  assertEquals(Observation.Available(InputSource.VEHICLE),classified(vehicle,raw(routing=true)))
 }
 @Test fun explicitlySimulatedGuidanceClassifiesOnlySimulation(){
  assertEquals(Observation.Available(InputSource.SIMULATION),classified(simulation,raw("SIMULATION_GUIDING")))
  assertTrue(classified(vehicle,raw("SIMULATION_GUIDING")) is Observation.Missing)
 }
 @Test fun normalSdkRouteMustNotBeRelabeledToSimulationProfile(){
  assertTrue(classified(simulation,raw()) is Observation.Missing)
 }
 @Test fun inactiveSafeMissingAndInventedModesNeverInferVehicle(){
  for(m in listOf("NOT_WORKING","SAFE_GUIDING","NORMAL_GUIDING","unknown"))assertTrue(m,classified(vehicle,raw(m)) is Observation.Missing)
  assertTrue(classified(vehicle,raw().copy(guidance=NavigationField.Missing(NavigationMissing.NOT_OBSERVED))) is Observation.Missing)
 }
 @Test fun nonSdkOriginMustNotClassifyEvenWithValidMode(){
  for(source in listOf(NavigationSource.APP_REQUEST,NavigationSource.APP_STATE)) {
   val r=raw().copy(guidance=NavigationField.Value(NavigationGuidance(null,"ROUTE_GUIDING",false),stamp.copy(source=source)))
   assertTrue(classified(vehicle,r) is Observation.Missing)
  }
 }
 @Test fun otherConnectionAndDisconnectedFieldsRemainMissing(){
  val r=raw().copy(guidance=NavigationField.Value(NavigationGuidance(null,"ROUTE_GUIDING",false),stamp.copy(connectionGeneration=6)))
  assertTrue(classified(vehicle,r) is Observation.Missing)
  assertTrue(classified(vehicle,raw().copy(connection=NavigationConnection.DISCONNECTED)) is Observation.Missing)
 }
 @Test fun staleRouteGuidanceRemainsStale(){
  assertEquals(Observation.Missing(MissingReason.STALE),deliveryEstimateSource(vehicle,raw()){false})
 }
 @Test fun classifiedVehicleEstimateStillNeedsExactConfirmedSdkDestinationAndNeverCreatesArrival(){
  val destination=NavigationDestination(37.0,127.0,"SDK 반환",null,null,null,null)
  val snapshot=raw().copy(destination=NavigationField.Value(destination,stamp),remainingEstimatedMeters=NavigationField.Value(12000L,stamp))
  val binding=DeliveryDestinationSelection(vehicle,io.drivegrowth.core.delivery.DestinationKey("confirmed"),1,destination,stamp)
  fun read(raw:NavigationSnapshot)= (FNavigationDeliveryPort({raw},{_,_->binding},
   {identity,s->deliveryEstimateSource(identity,s){true}},{Observation.Missing(MissingReason.NO_DATA)},{true},
   {_,_->ContractResult.Success(Unit)}).read(vehicle) as ContractResult.Success).value
  val accepted=read(snapshot)
  assertEquals(Observation.Available(EstimatedRouteDistance(12000,InputSource.VEHICLE)),accepted.estimated)
  assertTrue(accepted.observed is Observation.Missing);assertTrue(accepted.arrival is Observation.Missing)
  assertTrue(read(snapshot.copy(remainingEstimatedMeters=NavigationField.Value(12000L,stamp.copy(sequence=2)))).estimated is Observation.Missing)
 }

}
