package io.drivegrowth.app.host.driving

import io.drivegrowth.core.contract.*
import java.time.ZoneId

/** SDK 수신 worker에서 같은 observation과 함께 잡은 zone의 한 번 전달용 상관 저장소. 영속 저장소가 아니다. */
class ObservationCalendar(private val capacity: Int) {
 init { require(capacity > 0) }
 private val pending=LinkedHashMap<VehicleObservation,Observation<ZoneId>>()
 @Synchronized
 fun capture(observation: VehicleObservation, zone: ZoneId) {
  val offered=Observation.Available(zone)
  pending[observation]=when(val old=pending[observation]) {
   null -> offered
   offered -> old
   else -> Observation.Missing(MissingReason.NO_DATA)
  }
  while(pending.size>capacity)pending.remove(pending.keys.first())
 }
 @Synchronized
 fun take(observation: VehicleObservation): Observation<ZoneId> =
  pending.remove(observation)?:Observation.Missing(MissingReason.NO_DATA)
}
