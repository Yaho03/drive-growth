package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test

class FNavigationDeliveryPortTest {
    private val identity=GameIdentity(ProfileId("navigation-test"),InputSource.SIMULATION)
    private val stamp=NavigationStamp(1000,100,7,11)
    private val actual=NavigationDestination(37.0,127.0,"실제 반환 목적지",null,null,null,null)
    private val raw=NavigationSnapshot(stamp.copy(sequence=20,source=NavigationSource.APP_STATE),NavigationConnection.CONNECTED_OBSERVED,
        NavigationField.Value(actual,stamp),NavigationField.Value(12345L,stamp),NavigationField.Value("sdk-route",stamp),
        NavigationField.Value(NavigationGuidance(status=null,mode="SIMULATION_GUIDING",isRouting=null),stamp),NavigationField.Value(99999L,stamp),null,null)
    private fun port(snapshot:NavigationSnapshot=raw, key:Boolean=true, classified:Observation<InputSource> = Observation.Available(InputSource.SIMULATION), isFresh:Boolean=true, bindingIdentity:GameIdentity=identity)=
        FNavigationDeliveryPort({snapshot},{_,_-> if(key)DeliveryDestinationSelection(bindingIdentity,DestinationKey("confirmed"),3,actual,stamp)else null},
            {_,_->classified},{Observation.Missing(MissingReason.NO_DATA)},{isFresh},{_,_->ContractResult.Success(Unit)})
    private fun read(port:FNavigationDeliveryPort)= (port.read(identity) as ContractResult.Success).value
    @Test fun `남은 예상거리만 소비하고 실제거리와 도착은 만들지 않는다`() {
        val value=read(port());assertEquals(Observation.Available(EstimatedRouteDistance(12345,InputSource.SIMULATION)),value.estimated)
        assertEquals(Observation.Missing(MissingReason.NO_DATA),value.observed);assertEquals(Observation.Missing(MissingReason.NO_DATA),value.arrival)
        assertEquals(3L,value.selectionRevision);assertEquals(7L,value.metadata!!.connectionGeneration)
    }
    @Test fun `SDK 출처만 있거나 사용자 실제목적지 확인이 없으면 정상 후보입력을 만들지 않는다`() {
        val unknown=read(port(classified=Observation.Missing(MissingReason.NO_DATA)));assertTrue(unknown.estimated is Observation.Missing)
        val unselected=read(port(key=false));assertNull(unselected.destination);assertNull(unselected.selectionRevision);assertTrue(unselected.estimated is Observation.Missing)
    }
    @Test fun `서로 다른 필드 수신을 한 경로의 확정거리로 묶지 않는다`() {
        val value=read(port(raw.copy(remainingEstimatedMeters=NavigationField.Value(12345,stamp.copy(sequence=12)))))
        assertTrue(value.estimated is Observation.Missing)
        assertEquals(11L,value.metadata!!.fields.getValue("destination").sequence)
        assertEquals(12L,value.metadata!!.fields.getValue("remainingEstimatedMeters").sequence)
    }
    @Test fun `오래된 값은 원시 metadata를 보존하고 STALE로 넘긴다`() {
        val value=read(port(isFresh=false));assertEquals(Observation.Missing(MissingReason.STALE),value.estimated)
        assertEquals(1000L,value.metadata!!.fields.getValue("destination").receivedAtMillis)
        assertEquals(100L,value.metadata!!.fields.getValue("destination").receivedElapsedMillis)
    }
    @Test fun `내장 모의주행을 VEHICLE로 소비하지 않는다`() {
        val vehicle=GameIdentity(identity.profileId,InputSource.VEHICLE)
        val value=(port(classified=Observation.Available(InputSource.VEHICLE),bindingIdentity=vehicle).read(vehicle) as ContractResult.Success).value
        assertTrue(value.estimated is Observation.Missing);assertTrue(value.arrival is Observation.Missing)
    }

    @Test fun `확인 뒤 남아 있는 이전 경로 ID로 새 거리의 귀속을 추정하지 않는다`() {
        val newer = stamp.copy(sequence = stamp.sequence + 1)
        val binding = DeliveryDestinationSelection(identity, DestinationKey("confirmed"), 3, actual, stamp)
        val updated = raw.copy(destination = NavigationField.Value(actual, newer),
            remainingEstimatedMeters = NavigationField.Value(800L, newer))
        fun estimate(snapshot: NavigationSnapshot) = read(FNavigationDeliveryPort(
            { snapshot }, { _, _ -> binding }, { _, _ -> Observation.Available(InputSource.SIMULATION) },
            { Observation.Missing(MissingReason.NO_DATA) }, { true }, { _, _ -> ContractResult.Success(Unit) }
        )).estimated
        assertEquals(Observation.Available(EstimatedRouteDistance(12345, InputSource.SIMULATION)), estimate(raw))
        assertTrue(estimate(updated) is Observation.Missing)
        assertTrue(estimate(updated.copy(routeId = NavigationField.Value("other-route", newer))) is Observation.Missing)
        assertTrue(estimate(updated.copy(destination = NavigationField.Value(actual.copy(name = "다른 목적지"), newer))) is Observation.Missing)
    }

    @Test fun `SDK 실패 중 다시 받은 필드로 확인한 목적지와 예상 거리를 제공하지 않는다`() {
        for (error in listOf(NavigationError.PERMISSION_DENIED, NavigationError.ASYNC_SDK_ERROR)) {
            val failure = NavigationFailure(error, NavigationOperation.SDK_EVENT, NavigationErrorOrigin.ASYNCHRONOUS)
            val value = read(port(raw.copy(failure = failure)))
            val reason = if (error == NavigationError.PERMISSION_DENIED) MissingReason.PERMISSION_DENIED else MissingReason.NO_DATA
            assertEquals(Observation.Missing(reason), value.estimated)
            assertEquals(Observation.Missing(MissingReason.NO_DATA), value.arrival)
            assertNull(value.destination)
            assertNull(value.selectionRevision)
            assertEquals(11L, value.metadata!!.fields.getValue("destination").sequence)
        }
    }

    @Test fun `다른 프로필의 사용자 선택 문맥을 재사용하지 않는다`() {
        val value=read(port(bindingIdentity=GameIdentity(ProfileId("other"),identity.source)))
        assertNull(value.destination);assertNull(value.selectionRevision);assertTrue(value.estimated is Observation.Missing)
    }
}
