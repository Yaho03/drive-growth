package io.drivegrowth.app.host.facility

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*

object FacilityHostMapper {
    fun options(facts:FacilityFacts, journalAvailable:Boolean):List<ShopFacility> = Facility.entries.map { facility ->
        val offer=facts.offers[facility]
        val installed=facility in facts.shop.facilities
        val availability=when {
            installed->ShopAvailability.INSTALLED
            offer==null || !journalAvailable->ShopAvailability.UNKNOWN
            offer.planPurchase(facts.shop.progress,facts.shop.facilities,facts.balance) is ContractResult.Success->ShopAvailability.AVAILABLE
            else->ShopAvailability.LOCKED
        }
        val progress = facility.progress(facts.shop.progress)
        val required = facility.requiredProgress
        val condition=when(facility) {
            Facility.FUEL_STATION->"보유 차량 $progress/${required}대"
            Facility.INSURANCE->"누적 고객 $progress/${required}명"
            Facility.CAR_WASH->"가게 레벨 $progress/$required"
        }+if(offer!=null && facts.balance.units<offer.price.units)" · 잔액 부족" else ""
        val title=when(facility){Facility.FUEL_STATION->"주유소";Facility.INSURANCE->"보험 시설";Facility.CAR_WASH->"세차장"}
        val effect = when {
            installed && (facility != Facility.FUEL_STATION || offer == null) -> "기존 설치 기록"
            !installed && !journalAvailable -> "구매 원명령 저장 연결 미지원"
            offer == null -> "중앙 시설 정의 미제공"
            facility == Facility.FUEL_STATION -> {
                val fuelEffect = facts.fuelBonusPerRental?.let { "대여당 ${it.units} 게임머니 추가 수익" }
                    ?: "대여당 추가 수익 · 금액 미제공"
                if (installed) "기존 설치 기록 · $fuelEffect" else fuelEffect
            }
            facility == Facility.INSURANCE -> "사고 손실 절반 보전"
            else -> "리뷰 상승"
        }
        ShopFacility(facility,ShopOption("facility-${facility.name}",title,condition,effect,availability,
            if(availability==ShopAvailability.AVAILABLE)ShopCommand.BuyFacility(offer!!) else null,
            offer?.price,offer?.ruleVersion,offer?.trial?:false))
    }
    /** 기존 확장·꾸미기·외형을 보존한다. B가 같은 identity의 최신 표시와 합성한다. */
    fun merge(screen:ShopScreenState.Content,facts:FacilityFacts,journalAvailable:Boolean)=screen.copy(facilities=options(facts,journalAvailable))
    fun restingRows(facts:FacilityFacts)=facts.vehicles.filter{it.lifecycle==VehicleLifecycle.OWNED}.map { car ->
        val activity=facts.activities[car.ownedVehicleId]
        val resting=car.ownedVehicleId in facts.resting
        RestingRow(VehicleRef(car.ownedVehicleId,car.revision),activity,resting,
            if(resting && activity in setOf(VehicleActivity.RENTED,VehicleActivity.RETURNING))"영업 쉬기 · 반납 후 배정 제외"
            else if(resting)"영업 쉬기" else "영업 참여")
    }
}
