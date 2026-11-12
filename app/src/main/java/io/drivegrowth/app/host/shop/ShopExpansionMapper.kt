package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import java.util.Collections

internal fun mapShopExpansion(profile: ProfileId, source: InputSource, definition: ShopExpansionDefinition,
    shop: ShopState, completed: Int, balance: GameMoney, vehicles: List<ShopVehicle>): ShopExpansionSnapshot {
    val offer=definition.offers.singleOrNull { it.fromLevel==shop.progress.shopLevel }
    val expansion=if(offer==null) ShopOption("expand", "추가 확장 정의 없음",
        "현재 레벨 ${shop.progress.shopLevel} · 공간 ${shop.capacity}칸", "이번 시험 정의의 마지막 단계 이후는 미제공",ShopAvailability.UNKNOWN)
    else {
        val capacityMatches=shop.capacity==offer.currentCapacity
        val available=offer.preview(shop.progress.copy(servedCustomers=completed),shop.capacity,balance) is ContractResult.Success
        ShopOption("expand","레벨 ${offer.fromLevel} → ${offer.fromLevel+1} · 공간 ${offer.currentCapacity} → ${offer.nextCapacity}",
            "완료 대여 ${completed}/${offer.requiredCustomers}건 · 현재 공간 ${shop.capacity}칸"+
                (if(!capacityMatches) " · 공간 정의 불일치" else if(balance.units<offer.price.units) " · 잔액 부족" else ""),
            "확정 후 공간 ${offer.nextCapacity}칸",
            if(!capacityMatches)ShopAvailability.UNKNOWN else if(available)ShopAvailability.AVAILABLE else ShopAvailability.LOCKED,
            ShopCommand.Expand(offer),offer.price,offer.ruleVersion,offer.trial)
    }
    val facilities=Facility.entries.map { facility ->
        val installed=facility in shop.facilities
        ShopFacility(facility,ShopOption("facility-${facility.name}",when(facility) {
            Facility.FUEL_STATION -> "주유소";Facility.INSURANCE -> "보험 시설";Facility.CAR_WASH -> "세차장"
        },"이번 확장 화면의 시설 구매 미연결",if(installed)"기존 설치 기록 · 이번 구매 연결 미지원" else "시설 구매 연결 미지원",
            if(installed)ShopAvailability.INSTALLED else ShopAvailability.UNKNOWN))
    }
    val screen=ShopScreenState.Content(Revision(0),"내 가게 · 저장 상태",shop.progress.shopLevel,shop.progress.ownedVehicles,
        shop.capacity,balance,ShopAppearance("외형 미연결",0xff8fbcff.toInt()),Collections.unmodifiableList(ArrayList(vehicles)),
        Collections.unmodifiableList(facilities),expansion,listOf(ShopOption("decoration-unsupported","꾸미기",
            "품목/가격/보유 공급 미연결","이번 연결 미지원",ShopAvailability.UNKNOWN)),ShopOrigin.STORED)
    return ShopExpansionSnapshot(profile,source,definition.version,completed,screen)
}
