package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.model.LocationData
import io.drivegrowth.core.contract.*

/** confidence의 단위를 accuracy metres로 추정하지 않는다. SDK timestamp도 검증 없이 수신UTC로 대체하지 않는다. */
data class PleosLocationFix(val source:InputSource,val latitude:Double,val longitude:Double,
 val rawConfidence:Float,val rawTimestampMillis:Long,val receivedElapsedMs:Long,val receivedAt:EpochMillis)
fun pleosLocationFix(value:LocationData?,elapsedMs:Long,utc:EpochMillis):Observation<PleosLocationFix> {
 if(value==null||elapsedMs<0||utc.value<0||!value.latitude.isFinite()||value.latitude !in -90.0..90.0||
  !value.longitude.isFinite()||value.longitude !in -180.0..180.0||!value.confidence.isFinite()||value.timestampMillis<0)
  return Observation.Missing(MissingReason.NO_DATA)
 return Observation.Available(PleosLocationFix(InputSource.VEHICLE,value.latitude,value.longitude,value.confidence,value.timestampMillis,elapsedMs,utc))
}
