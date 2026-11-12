package io.drivegrowth.app.host.shop

import io.drivegrowth.app.host.HostQueryState
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.Facility

/** 저장 조회만 표시한다. revision은 표시 세대이며 DB/경제 revision이 아니다. 거래 옵션은 공급 전까지 만들지 않는다. */
class ShopHostMapper {
    fun map(state: HostQueryState, revision: Revision): ShopScreenState {
        val snapshot = when (state) {
            is HostQueryState.Content -> state.snapshot
            is HostQueryState.Failure -> return ShopScreenState.Failure("가게 데이터를 읽지 못했어요 (${state.error})")
            else -> return ShopScreenState.Loading
        }
        val owned = when (val result = Garage(snapshot.content.catalog).owned(snapshot.ownedVehicles)) {
            is ContractResult.Failure -> return ShopScreenState.Failure("차량 정보를 확인할 수 없어요 (${result.error})")
            is ContractResult.Success -> result.value
        }
        val stored = (snapshot.garageState as? ContractResult.Success)?.value
        if (stored != null && (stored.source != snapshot.source || stored.contentVersion != snapshot.content.catalog.contentVersion ||
            stored.ruleVersion != snapshot.content.ruleVersion || stored.vehicles.map { it.vehicle.ownedVehicleId }.distinct().size != stored.vehicles.size ||
            stored.vehicles.any { detail -> owned.none { it == detail.vehicle } }))
            return ShopScreenState.Failure("차량 조회가 바뀌었어요. 다시 조회해 주세요 (STATE_CONFLICT)")
        val names = snapshot.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        val details = stored?.vehicles.orEmpty().associateBy { it.vehicle.ownedVehicleId }
        fun unknown(id: String, title: String) = ShopOption(id, title, "현재 저장 조회 연결이 제공되지 않았어요",
            "확정 상태와 거래 조건을 확인한 뒤 사용할 수 있어요", ShopAvailability.UNKNOWN)
        return ShopScreenState.Content(revision, "드라이브 키우기 · " +
            if (snapshot.source == InputSource.SIMULATION) "모의 원장" else "차량 원장",
            level = null, ownedCount = owned.size,
            capacity = ((stored?.capacity as? ContractResult.Success)?.value)?.takeIf { it > 0 },
            balance = snapshot.balance, appearance = ShopAppearance("드라이브 키우기", 0xff58a6ff.toInt()),
            vehicles = owned.map { car ->
                val activity = (details[car.ownedVehicleId]?.activity as? ContractResult.Success)?.value
                ShopVehicle(car.ownedVehicleId, names.getValue(car.vehicleId), when (activity) {
                    VehicleActivity.IDLE -> "대기 중"; VehicleActivity.RENTED -> "대여 중"
                    VehicleActivity.RETURNING -> "반납 이동 중"; VehicleActivity.NEEDS_REPAIR -> "수리 필요"
                    null -> "활동 미확인"
                }, car.vehicleId, car.rarity, activity)
            },
            facilities = Facility.entries.map { ShopFacility(it, unknown("facility-$it", when (it) {
                Facility.FUEL_STATION -> "주유소"; Facility.INSURANCE -> "보험 시설"; Facility.CAR_WASH -> "세차장"
            })) },
            expansion = unknown("expansion-unavailable", "가게 확장"), decorations = emptyList(), origin = ShopOrigin.STORED)
    }
}
