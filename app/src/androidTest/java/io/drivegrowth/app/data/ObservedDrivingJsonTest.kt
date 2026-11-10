package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.time.LocalDate

/** 실제 Android JSON codec 표적. DB 영속/SDK/주행 실행과 구분한다. */
@Suppress("DEPRECATION")
class ObservedDrivingJsonTest:InstrumentationTestCase() {
    private val source=InputSource.SIMULATION
    private fun observation(speed:Double=10.0)=VehicleObservation(source,1000,EpochMillis(10000),Observation.Available(speed),Observation.Available(Gear.DRIVE),Observation.Available(true))
    private fun trip(meters:BigDecimal=BigDecimal("1.2300"))=ObservedTrip("codec-trip",observation(),Observation.Missing(MissingReason.NO_DATA),ContractResult.Failure(ContractError.DATA_UNAVAILABLE),meters,
        listOf(DrivingSegment(observation(),observation().copy(receivedElapsedMs=2000,receivedAt=EpochMillis(11000)),meters)),
        listOf(DrivingGap(DrivingGapReason.INVALID_SIGNAL,RecordingClock(1000,EpochMillis(10000)),RecordingClock(2000,EpochMillis(11000)),observation(Double.fromBits(0x7ff8000000000042L)))))
    private fun state(value:ObservedTrip=trip())=RecordingState(GameIdentity(ProfileId("codec-profile"),source),4,"receiver-original",null,null,RecordingClock(2000,EpochMillis(11000)),null,listOf(value))
    fun testDecimalScaleMissingAuthorityAndRawNonfiniteBitsSurviveRoundTrip() {
        val original=state();val actual=ObservedDrivingJson.state(ObservedDrivingJson.state(original));assertEquals(original,actual)
        assertEquals(4,actual.outbox.single().meters.scale());assertEquals(0x7ff8000000000042L,(actual.outbox.single().gaps.single().observation!!.speedMps as Observation.Available).value.toRawBits())
        assertTrue(actual.outbox.single().zone is Observation.Missing);assertTrue(actual.outbox.single().rules is ContractResult.Failure)
    }
    fun testNegativeDecimalScaleAndOriginalReceiptRemainExact() {
        val t=trip(BigDecimal("1E+3"));assertEquals(t,ObservedDrivingJson.trip(ObservedDrivingJson.trip(t)));assertEquals(-3,ObservedDrivingJson.trip(ObservedDrivingJson.trip(t)).meters.scale())
        val receipt=ObservedDrivingReceipt(t,DriveCommit(t.id,1000,1000,GameMoney(0),DrivingDay(LocalDate.of(1970,1,1),1000),null))
        assertEquals(receipt,ObservedDrivingJson.receipt(ObservedDrivingJson.receipt(receipt)))
    }
    fun testDecodedCollectionsCannotBeChangedByCallers() {
        val decoded=ObservedDrivingJson.state(ObservedDrivingJson.state(state()))
        try{(decoded.outbox as MutableList).clear();fail("outbox 변경 금지")}catch(_:UnsupportedOperationException){}
        try{(decoded.outbox.single().segments as MutableList).clear();fail("segment 변경 금지")}catch(_:UnsupportedOperationException){}
    }
    fun testFractionalIntegerAndStringBooleanAreRejected() {
        try{ObservedDrivingJson.state(ObservedDrivingJson.state(state()).put("revision",4.5));fail("정수 절삭 금지")}catch(_:org.json.JSONException){}
        val raw=ObservedDrivingJson.observation(observation());raw.getJSONObject("ux").put("value","true")
        try{ObservedDrivingJson.observation(raw);fail("문자 boolean 보정 금지")}catch(_:org.json.JSONException){}
    }
}
